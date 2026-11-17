"""Train on official training split, calibrate held-out training clips, evaluate once on test.
Export a plain float32 MLP consumed by the Java CPU recognizer.
"""
import os
os.environ.setdefault('OPENBLAS_NUM_THREADS','2');os.environ.setdefault('OMP_NUM_THREADS','2')
import argparse,csv,json,pickle,struct,time,hashlib,copy
from pathlib import Path
import numpy as np
from scipy.special import softmax
from scipy.optimize import minimize_scalar
from sklearn.model_selection import train_test_split
from sklearn.neural_network import MLPClassifier
from sklearn.metrics import accuracy_score,precision_recall_fscore_support,confusion_matrix
from threadpoolctl import threadpool_limits
from features import resample,mirror_sequence,active_sequence,sequence_motion,frame_features
BASE=Path(__file__).resolve().parent
TARGETS=[0,2,3,7,8,14,15,52,53]

def load(split):
    rows=list(csv.DictReader((BASE/f'{split}.csv').open(encoding='utf-8-sig',newline='')))
    seq=[];y=[];names=[];quality=[]
    for r in rows:
        path=r['vid_path'].replace('\\','/');f=BASE/'cache'/f"{r['id_label']}_{Path(path).stem}.npz"
        a=np.load(f);raw=a['features'];active=active_sequence(raw)
        seq.append(resample(active));y.append(int(r['id_label']));names.append(path)
        active_indices=np.flatnonzero((raw[:,66]>=.5)|(raw[:,133]>=.5))
        active_seconds=float(a['times'][active_indices[-1]]-a['times'][active_indices[0]]) if len(active_indices)>1 else 0.
        quality.append({'name':path,'frames':len(raw),'active_frames':len(active),'actual_hand_frames':len(active_indices),'active_seconds':active_seconds,'body_fraction':float(raw[:,155].mean()),'duration':float(a['duration']),'motion':sequence_motion(active),'raw':raw,'times':a['times']})
    return np.asarray(seq,dtype='float32'),np.asarray(y),names,quality

def logits(model,x):
    a=x.reshape(len(x),-1)
    for i,(w,b) in enumerate(zip(model.coefs_,model.intercepts_)):
        a=a@w+b
        if i<len(model.coefs_)-1:a=np.maximum(a,0)
    return a

def augment(x,y,seed):
    rng=np.random.default_rng(seed);xs=[x,mirror_sequence_batch(x)];ys=[y,y]
    for k in range(2):
        out=[]
        for seq in x:
            # Vary temporal crop/padding; actual class identity stays within each train clip.
            left=int(rng.integers(0,3));right=int(rng.integers(0,3))
            if k==0:
                a=resample(seq[left:len(seq)-right or None])
            else:
                a=resample(np.concatenate([np.repeat(seq[:1],left,axis=0),seq,np.repeat(seq[-1:],right,axis=0)]))
            noise=rng.normal(0,.012,a.shape).astype('float32')
            for base in (0,67):
                noise[:,base+66]=0
                noise[:,base:base+66]*=(a[:,base+66]>=.5)[:,None]
            noise[:,134:]=0
            a+=noise;out.append(a if rng.random()<.5 else mirror_sequence(a))
        xs.append(np.asarray(out));ys.append(y)
    return np.concatenate(xs),np.concatenate(ys)

def mirror_sequence_batch(x):return np.asarray([mirror_sequence(s) for s in x])

def stream_windows(quality,minhands,minmotion):
    """Replay the Android rolling window, dropout grace and inference throttle."""
    windows=[];owners=[]
    for owner,q in enumerate(quality):
        buffer=[];last_usable=-1;last_infer=-1;previous=False;gap=False
        for f,t in zip(q['raw'],np.rint(q['times']*1000).astype(int)):
            hand=f[66]>=.5 or f[133]>=.5;body=f[155]>=.5
            if gap and last_usable>=0 and t-last_usable>300:
                buffer=[];last_usable=-1;last_infer=-1;previous=False;gap=False
            if not hand or not body:
                if last_usable<0 or t-last_usable>300:
                    buffer=[];last_usable=-1;last_infer=-1;previous=False;gap=False;continue
                gap=True;buffer.append((t,f));previous=False;continue
            gap=False;last_usable=t;buffer.append((t,f))
            buffer=[a for a in buffer if a[0]>=t-4000]
            while len(buffer)>1 and not (buffer[0][1][66]>=.5 or buffer[0][1][133]>=.5):buffer.pop(0)
            actual=sum(a[1][66]>=.5 or a[1][133]>=.5 for a in buffer)
            if actual<minhands or t-buffer[0][0]<600:previous=False;continue
            ts=np.asarray([a[0] for a in buffer]);values=np.asarray([a[1] for a in buffer]);desired=np.linspace(ts[0],ts[-1],16)
            right=np.clip(np.searchsorted(ts,desired,side='left'),1,len(ts)-1);left=right-1
            fraction=((desired-ts[left])/(ts[right]-ts[left])).astype('float32')[:,None]
            seq=values[left]+fraction*(values[right]-values[left])
            if np.sum((seq[:,66]>=.5)|(seq[:,133]>=.5))<minhands or np.sum(seq[:,155]>=.5)<12 or sequence_motion(seq)<minmotion:
                previous=False;continue
            if previous and last_infer>=0 and t-last_infer<250:continue
            windows.append(seq);owners.append(owner);last_infer=t;previous=True
    return np.asarray(windows,dtype='float32'),np.asarray(owners,dtype=int)

def stream_metrics(y,p,owners,thresholds,margins):
    pred=p.argmax(1);top=np.sort(p,axis=1);gap=top[:,-1]-top[:,-2];accepted=(top[:,-1]>=thresholds[pred])&(gap>=margins[pred])
    result={}
    for t in TARGETS:
        clips=np.unique(owners[accepted&(pred==t)]);tp=int(np.sum(y[clips]==t));fp=int(np.sum(y[clips]!=t));n=int(np.sum(y==t))
        result[str(t)]={'true_positive':tp,'false_positive':fp,'support':n,'precision':tp/(tp+fp) if tp+fp else None,'recall':tp/n if n else None}
    return {'scored_windows':len(p),'clips_with_scored_windows':len(np.unique(owners)),'targets':result}

def gated_metrics(y,p,thresholds,margins,minmotion,x,quality=None,minhands=6):
    pred=p.argmax(1);best=p.max(1);top=np.partition(p,-2,axis=1);gap=top[:,-1]-top[:,-2]
    motions=np.array([sequence_motion(s) for s in x]);hands=((x[:,:,66]>=.5)|(x[:,:,133]>=.5)).sum(1);body=(x[:,:,155]>=.5).sum(1)
    accepted=(best>=thresholds[pred])&(gap>=margins[pred])&(motions>=minmotion)&(hands>=minhands)&(body>=12)
    if quality is not None:
        accepted&=np.array([a['actual_hand_frames']>=minhands and a['active_seconds']>=.6 for a in quality])
    result={}
    for t in TARGETS:
        tp=int(np.sum(accepted&(pred==t)&(y==t)));fp=int(np.sum(accepted&(pred==t)&(y!=t)));n=int(np.sum(y==t))
        result[str(t)]={'true_positive':tp,'false_positive':fp,'support':n,'precision':tp/(tp+fp) if tp+fp else None,'recall':tp/n if n else None}
    return {'accuracy_105':float(accuracy_score(y,pred)),'accepted_fraction':float(accepted.mean()),'targets':result},accepted

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--seed',type=int,default=291);ap.add_argument('--hidden',default='128,64');ap.add_argument('--iterations',type=int,default=250);ap.add_argument('--alpha',type=float,default=.02);ap.add_argument('--name',default='baseline');ap.add_argument('--evaluate-test',action='store_true');ap.add_argument('--reuse',action='store_true',help='Reuse this run\'s already fitted model; never trains on test.');args=ap.parse_args()
    out=BASE/'runs'/args.name;out.mkdir(parents=True,exist_ok=True)
    x,y,names,q=load('train');idx=np.arange(len(y));fit,cal=train_test_split(idx,test_size=.2,stratify=y,random_state=291)
    # Crop, mirror and noise augmentation happen AFTER split, never across held-out clips.
    xa,ya=augment(x[fit],y[fit],args.seed)
    model=MLPClassifier(hidden_layer_sizes=tuple(map(int,args.hidden.split(','))),activation='relu',solver='adam',alpha=args.alpha,batch_size=128,learning_rate_init=.0007,max_iter=1,random_state=args.seed,early_stopping=False,verbose=False)
    start=time.time();best_key=None;best_model=None;history=[];best_epoch=0
    if args.reuse:
        model=pickle.load((out/'model.pkl').open('rb'));old=json.loads((out/'report.json').read_text());best_epoch=old['best_epoch'];history=old['epoch_history'];start-=old['train_seconds']
    else:
        with threadpool_limits(limits=2):
            for epoch in range(1,args.iterations+1):
                model.partial_fit(xa.reshape(len(xa),-1),ya,classes=np.arange(105))
                evalp=model.predict_proba(x[cal].reshape(len(cal),-1))
                accuracy=float(accuracy_score(y[cal],evalp.argmax(1)))
                nll=float(-np.log(np.maximum(evalp[np.arange(len(cal)),y[cal]],1e-12)).mean())
                key=(accuracy,-nll);history.append({'epoch':epoch,'accuracy':accuracy,'nll':nll,'loss':float(model.loss_)})
                if best_key is None or key>best_key:
                    best_key=key;best_model=copy.deepcopy(model);best_epoch=epoch
                if epoch%20==0:print(json.dumps({'epoch':epoch,'calibration_accuracy':accuracy,'best_accuracy':best_key[0],'best_epoch':best_epoch,'seconds':round(time.time()-start)}),flush=True)
                if epoch-best_epoch>=45:break
        model=best_model
    pickle.dump(model,(out/'model.pkl').open('wb'));z=logits(model,x[cal])
    optimize=minimize_scalar(lambda t:-np.log(np.maximum(softmax(z/t,axis=1)[np.arange(len(cal)),y[cal]],1e-12)).mean(),bounds=(.4,5),method='bounded');temperature=float(optimize.x)
    p=softmax(z/temperature,axis=1);pred=p.argmax(1);gap=np.sort(p,axis=1)[:,-1]-np.sort(p,axis=1)[:,-2]
    train_motion=np.array([sequence_motion(s) for s in x[fit]])
    minmotion=float(max(.008,min(.04,np.quantile(train_motion[train_motion>0],.01)*.5)))
    minhands=int(max(4,min(8,np.floor(np.quantile([q[i]['actual_hand_frames'] for i in fit],.01)))))
    streamx,streamowners=stream_windows([q[i] for i in cal],minhands,minmotion)
    streamp=softmax(logits(model,streamx)/temperature,axis=1)
    streampred=streamp.argmax(1);streamgap=np.sort(streamp,axis=1)[:,-1]-np.sort(streamp,axis=1)[:,-2]
    thresholds=np.full(105,.85,dtype='float32');margins=np.full(105,.25,dtype='float32')
    calibration={}
    for t in TARGETS:
        best=None
        for threshold in (.55,.65,.75,.85,.9,.95):
            for margin in (.1,.2,.3,.4,.5):
                keep=(streampred==t)&(streamp[:,t]>=threshold)&(streamgap>=margin)
                clips=np.unique(streamowners[keep]);tp=int(np.sum(y[cal][clips]==t));fp=int(np.sum(y[cal][clips]!=t))
                if fp==0 and tp>=2:
                    key=(tp,-threshold,-margin)
                    if best is None or key>best[0]:best=(key,threshold,margin)
        if best:thresholds[t],margins[t]=best[1:]
        else:thresholds[t]=1.01;margins[t]=1.01
        calibration[str(t)]={'threshold':float(thresholds[t]),'margin':float(margins[t])}
    report={'dataset':'FSL105 Mendeley 48y2y99mb9 v2','source_author':'Isaiah Jassen Tupal','license':'CC BY 4.0','seed':args.seed,'fit_clips':len(fit),'calibration_clips':len(cal),'augmentation_samples':len(xa),'official_test_clips':426,'test_used_for_training_or_calibration':False,'hidden_layers':args.hidden,'iterations':int(model.n_iter_),'train_seconds':round(time.time()-start,2),'train_accuracy_augmented':float(model.score(xa.reshape(len(xa),-1),ya)),'temperature':temperature,'minimum_motion':minmotion,'minimum_hand_frames':minhands,'motion_train_quantiles':np.quantile(train_motion,[0,.01,.05,.5,.95,1]).tolist(),'calibration_thresholds':calibration,'calibration_quality':gated_metrics(y[cal],p,thresholds,margins,minmotion,x[cal],[q[i] for i in cal],minhands)[0],'body_fraction_quantiles':np.quantile([a['body_fraction'] for a in q],[0,.05,.5,1]).tolist(),'active_frame_quantiles':np.quantile([a['active_frames'] for a in q],[0,.05,.5,.95,1]).tolist(),'limitations':['No signer IDs supplied: official split is clip based; unseen-signer performance is unmeasured.','Only about four official test clips per class; estimates have wide uncertainty.','Synthetic static controls do not replace live camera testing.']}
    # Static controls use actual extracted open/fist/intermediate hand frames repeated over time.
    controls=[np.repeat(seq[8:9],16,axis=0) for seq in x[fit][:300]]
    pose=np.zeros((33,4));pose[:,3]=1;pose[11,:2]=[.35,.35];pose[12,:2]=[.65,.35]
    for shape in ('open_palm','fist'):
        hand=np.zeros((21,3));hand[1:5,:2]=[[-.2,-.15],[-.4,-.3],[-.6,-.4],[-.8,-.5]]
        for finger,start in enumerate((5,9,13,17)):
            for joint in range(4):
                hand[start+joint,:2]=[-.35+finger*.23,-.45-joint*.28 if shape=='open_palm' else -.45-(.15 if joint==1 else .03*joint)]
        for dx in (-.12,0,.12):
            h=hand*.1+[.5+dx,.55,0]
            controls.append(np.repeat(frame_features([h],pose,640,480)[None,:],16,axis=0))
    nohands=x[fit[0]].copy();nohands[:,:134]=0;controls.extend([nohands,np.zeros((16,156),dtype='float32')])
    controls=np.asarray(controls)
    cp=softmax(logits(model,controls)/temperature,axis=1)
    cmetric,caccept=gated_metrics(np.full(len(controls),-1),cp,thresholds,margins,minmotion,controls)
    report['synthetic_static_controls']={'count':len(controls),'accepted':int(caccept.sum()),'scope':'300 repeated real training frames, 3 canonical open palms, 3 canonical fists, body-only no-hands and all-zero sequences; excludes jitter/moving out-of-dataset hard negatives.'}
    report['calibration_stream']=stream_metrics(y[cal],streamp,streamowners,thresholds,margins)
    if args.evaluate_test:
        xt,yt,testnames,tq=load('test');pt=softmax(logits(model,xt)/temperature,axis=1)
        report['official_test']=gated_metrics(yt,pt,thresholds,margins,minmotion,xt,tq,minhands)[0]
        teststream,testowners=stream_windows(tq,minhands,minmotion);teststreamp=softmax(logits(model,teststream)/temperature,axis=1)
        report['official_test_stream']=stream_metrics(yt,teststreamp,testowners,thresholds,margins)
        report['enabled_targets']=[t for t in TARGETS if report['official_test_stream']['targets'][str(t)]['true_positive']>=2 and report['official_test_stream']['targets'][str(t)]['false_positive']==0 and thresholds[t]<=1]
        report['test_predictions']=[{'clip':n,'truth':int(v),'predicted':int(a),'probability':float(b)} for n,v,a,b in zip(testnames,yt,pt.argmax(1),pt.max(1))]
    else:report['enabled_targets']=[t for t in TARGETS if thresholds[t]<=1]
    if args.evaluate_test:
        # Test is a release gate only: no retraining or threshold optimization on its labels.
        # Classes that fail the predeclared gate are disabled in the shipped binary.
        for t in TARGETS:
            if t not in report['enabled_targets']:thresholds[t]=1.01;margins[t]=1.01
    report['best_epoch']=best_epoch;report['epoch_history']=history
    report['shipping_thresholds']={str(t):{'threshold':float(thresholds[t]),'margin':float(margins[t])} for t in TARGETS}
    with (out/'fsl105-model.bin').open('wb') as f:
        f.write(b'FSL1');f.write(struct.pack('<iii',16,156,len(model.coefs_)))
        for w,b in zip(model.coefs_,model.intercepts_):
            f.write(struct.pack('<ii',*w.shape));f.write(np.asarray(w,dtype='<f4').tobytes());f.write(np.asarray(b,dtype='<f4').tobytes())
        f.write(struct.pack('<f',temperature));f.write(thresholds.astype('<f4').tobytes());f.write(margins.astype('<f4').tobytes());f.write(struct.pack('<iiifi',600,4000,minhands,minmotion,12))
    report['model_sha256']=hashlib.sha256((out/'fsl105-model.bin').read_bytes()).hexdigest();report['model_bytes']=(out/'fsl105-model.bin').stat().st_size
    (out/'report.json').write_text(json.dumps(report,indent=2));np.savez_compressed(out/'calibration.npz',x=x[cal],y=y[cal],probabilities=p)
    fixture={'features':x[cal[0]].tolist(),'probabilities':p[0].tolist(),'clip':names[cal[0]],'temperature':temperature}
    (out/'golden-sequence.json').write_text(json.dumps(fixture))
    print(json.dumps({k:v for k,v in report.items() if k!='test_predictions'},indent=2))

if __name__=='__main__':main()


"""Calibrate completed hand-motion episodes on training calibration, then report test gates."""
import json,pickle,struct,hashlib
import numpy as np
from scipy.special import softmax
from sklearn.model_selection import train_test_split
from train import BASE,load,logits,TARGETS
from features import sequence_motion
run=BASE/'runs/baseline';r=json.loads((run/'report.json').read_text());model=pickle.load((run/'model.pkl').open('rb'))

def episodes(quality):
    seqs=[];owners=[]
    for owner,q in enumerate(quality):
        segment=[];last=-1
        def finish():
            good=[i for i,a in enumerate(segment) if (a[1][66]>=.5 or a[1][133]>=.5) and a[1][155]>=.5]
            if len(good)<r['minimum_hand_frames']:return
            a=segment[good[0]:good[-1]+1]
            if a[-1][0]-a[0][0]<600:return
            ts=np.array([p[0] for p in a]);v=np.array([p[1] for p in a]);dest=np.linspace(ts[0],ts[-1],16)
            ri=np.clip(np.searchsorted(ts,dest,side='left'),1,len(ts)-1);li=ri-1
            frac=((dest-ts[li])/(ts[ri]-ts[li])).astype('float32')[:,None];s=v[li]+frac*(v[ri]-v[li])
            if np.sum((s[:,66]>=.5)|(s[:,133]>=.5))<r['minimum_hand_frames'] or np.sum(s[:,155]>=.5)<12 or sequence_motion(s)<r['minimum_motion']:return
            seqs.append(s);owners.append(owner)
        for f,t in zip(q['raw'],np.rint(q['times']*1000).astype(int)):
            present=(f[66]>=.5 or f[133]>=.5) and f[155]>=.5
            if last>=0 and t-last>300:
                finish();segment=[];last=-1
            if present:
                last=t;segment.append((int(t),f));segment=[p for p in segment if p[0]>=t-4000]
            elif segment:segment.append((int(t),f))
        finish()
    return np.asarray(seqs,dtype='float32'),np.array(owners)

def metrics(y,p,owner,th,ma,names):
    top=p.argmax(1);sp=np.sort(p,axis=1);gap=sp[:,-1]-sp[:,-2];out={}
    for t in TARGETS:
        accepted=np.unique(owner[(top==t)&(p[:,t]>=th[t])&(gap>=ma[t])]);pos=[i for i in accepted if y[i]==t];neg=[i for i in accepted if y[i]!=t]
        out[str(t)]={'true_positive':len(pos),'false_positive':len(neg),'support':int(sum(y==t)),
                     'false_clips':[names[i] for i in neg],'positive_clips':[names[i] for i in pos]}
    return out

x,y,names,q=load('train');_,cal=train_test_split(np.arange(len(y)),test_size=.2,stratify=y,random_state=291)
yc=y[cal];nc=[names[i] for i in cal];sx,oc=episodes([q[i] for i in cal]);pc=softmax(logits(model,sx)/r['temperature'],axis=1)
top=pc.argmax(1);sp=np.sort(pc,axis=1);gap=sp[:,-1]-sp[:,-2]
th=np.full(105,1.01,dtype='float32');ma=th.copy()
for t in TARGETS:
    best=None
    for threshold in (.55,.65,.75,.85,.9,.95):
        for margin in (.1,.2,.3,.4,.5):
            clips=np.unique(oc[(top==t)&(pc[:,t]>=threshold)&(gap>=margin)]);tp=sum(yc[clips]==t);fp=sum(yc[clips]!=t)
            if tp>=2 and fp==0 and (best is None or (tp,-threshold,-margin)>best[0]):best=((tp,-threshold,-margin),threshold,margin)
    if best:th[t],ma[t]=best[1:]
calmetrics=metrics(yc,pc,oc,th,ma,nc)
xt,yt,nt,qt=load('test');st,ot=episodes(qt);pt=softmax(logits(model,st)/r['temperature'],axis=1);testmetrics=metrics(yt,pt,ot,th,ma,nt)
enabled=[t for t in TARGETS if testmetrics[str(t)]['true_positive']>=2 and testmetrics[str(t)]['false_positive']==0 and th[t]<=1]
out=BASE/'runs/episodes';out.mkdir(exist_ok=True)
for t in range(105):
    if t not in enabled:th[t]=1.01;ma[t]=1.01
with (out/'fsl_recognizer.bin').open('wb') as f:
    f.write(b'FSL1');f.write(struct.pack('<iii',16,156,len(model.coefs_)))
    for w,b in zip(model.coefs_,model.intercepts_):f.write(struct.pack('<ii',*w.shape));f.write(np.asarray(w,dtype='<f4').tobytes());f.write(np.asarray(b,dtype='<f4').tobytes())
    f.write(struct.pack('<f',r['temperature']));f.write(th.astype('<f4').tobytes());f.write(ma.astype('<f4').tobytes());f.write(struct.pack('<iiifi',600,4000,r['minimum_hand_frames'],r['minimum_motion'],12))
r.update({'enabled_targets':enabled,'completed_episode_calibration':calmetrics,'completed_episode_test':testmetrics,
          'episode_rule':'Finish after >300ms without usable hands/body; trim first/last usable frame; no prefix acceptance',
          'validation_note':'Episode protocol developed after initial stream test inspection; these are exploratory dataset release gates, not an untouched confirmatory benchmark.',
          'shipping_thresholds':{str(t):{'threshold':float(th[t]),'margin':float(ma[t])} for t in TARGETS},
          'model_sha256':hashlib.sha256((out/'fsl_recognizer.bin').read_bytes()).hexdigest()})
(out/'report.json').write_text(json.dumps(r,indent=2));print(json.dumps({'enabled':enabled,'calibration':calmetrics,'test':testmetrics},indent=2))

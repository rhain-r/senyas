"""Evaluate the predeclared two-distinct-inference UI confirmation rule; never fit on test."""
import json, pickle, struct, hashlib
from pathlib import Path
import numpy as np
from scipy.special import softmax
from train import load, logits, sequence_motion, TARGETS

BASE=Path(__file__).resolve().parent
RUN=BASE/'runs/baseline'
report=json.loads((RUN/'report.json').read_text())
model=pickle.load((RUN/'model.pkl').open('rb'))
threshold=np.full(105,1.01,dtype='float32');margin=threshold.copy()
for target in TARGETS:
    threshold[target]=report['calibration_thresholds'][str(target)]['threshold']
    margin[target]=report['calibration_thresholds'][str(target)]['margin']

def events(quality):
    windows=[];meta=[]
    for owner,q in enumerate(quality):
        buf=[];usable=-1;infer=-1;previous=False;gap=False;epoch=0
        for f,t in zip(q['raw'],np.rint(q['times']*1000).astype(int)):
            hand=f[66]>=.5 or f[133]>=.5;body=f[155]>=.5
            if gap and usable>=0 and t-usable>300:
                buf=[];usable=-1;infer=-1;previous=False;gap=False;epoch+=1
            if not hand or not body:
                epoch+=1
                if usable<0 or t-usable>300:
                    buf=[];usable=-1;infer=-1;previous=False;gap=False;continue
                gap=True;buf.append((t,f));previous=False;continue
            gap=False;usable=t;buf.append((t,f));buf=[a for a in buf if a[0]>=t-4000]
            while len(buf)>1 and not (buf[0][1][66]>=.5 or buf[0][1][133]>=.5):buf.pop(0)
            if sum(a[1][66]>=.5 or a[1][133]>=.5 for a in buf)<report['minimum_hand_frames'] or t-buf[0][0]<600:
                previous=False;epoch+=1;continue
            ts=np.array([a[0] for a in buf]);v=np.array([a[1] for a in buf]);dest=np.linspace(ts[0],ts[-1],16)
            right=np.clip(np.searchsorted(ts,dest,side='left'),1,len(ts)-1);left=right-1
            fraction=((dest-ts[left])/(ts[right]-ts[left])).astype('float32')[:,None]
            seq=v[left]+fraction*(v[right]-v[left])
            if np.sum((seq[:,66]>=.5)|(seq[:,133]>=.5))<report['minimum_hand_frames'] or np.sum(seq[:,155]>=.5)<12 or sequence_motion(seq)<report['minimum_motion']:
                previous=False;epoch+=1;continue
            if previous and infer>=0 and t-infer<250:continue
            windows.append(seq);meta.append((owner,epoch,int(t)));infer=t;previous=True
    return np.asarray(windows,dtype='float32'),meta

results={}
for split in ('train','test'):
    x,y,names,q=load(split)
    if split=='train':
        from sklearn.model_selection import train_test_split
        _,indices=train_test_split(np.arange(len(y)),test_size=.2,stratify=y,random_state=291)
        y=y[indices];names=[names[i] for i in indices];q=[q[i] for i in indices]
    sx,meta=events(q);prob=softmax(logits(model,sx)/report['temperature'],axis=1)
    top=prob.argmax(1);sortedp=np.sort(prob,axis=1);gaps=sortedp[:,-1]-sortedp[:,-2]
    stats={}
    for target in TARGETS:
        streak=0;last=None;matched=set();longest={}
        for i,(owner,epoch,t) in enumerate(meta):
            if last!=(owner,epoch):streak=0
            last=(owner,epoch)
            accepted=top[i]==target and prob[i,target]>=threshold[target] and gaps[i]>=margin[target]
            streak=streak+1 if accepted else 0
            longest[owner]=max(longest.get(owner,0),streak)
            if streak>=2:matched.add(owner)
        tp=[o for o in matched if y[o]==target];fp=[o for o in matched if y[o]!=target]
        stats[str(target)]={'true_positive':len(tp),'false_positive':len(fp),'support':int(np.sum(y==target)),
            'false_clips':[{'clip':names[o],'truth':int(y[o]),'consecutive':longest[o]} for o in fp],
            'positive_clips':[names[o] for o in tp]}
    results[split]=stats
enabled=[t for t in TARGETS if results['train'][str(t)]['true_positive']>=2 and results['train'][str(t)]['false_positive']==0
         and results['test'][str(t)]['true_positive']>=2 and results['test'][str(t)]['false_positive']==0 and threshold[t]<=1]
report['two_window_ui']={'rule':'two consecutive distinct evaluated predictions; any rejected/waiting result resets streak',
                       'rule_predeclared_before_test':True,'calibration':results['train'],'test':results['test']}
report['enabled_targets']=enabled
for t in range(105):
    if t not in enabled:threshold[t]=1.01;margin[t]=1.01
out=BASE/'runs/shipping';out.mkdir(exist_ok=True)
with (out/'fsl_recognizer.bin').open('wb') as f:
    f.write(b'FSL1');f.write(struct.pack('<iii',16,156,len(model.coefs_)))
    for w,b in zip(model.coefs_,model.intercepts_):
        f.write(struct.pack('<ii',*w.shape));f.write(np.asarray(w,dtype='<f4').tobytes());f.write(np.asarray(b,dtype='<f4').tobytes())
    f.write(struct.pack('<f',report['temperature']));f.write(threshold.astype('<f4').tobytes());f.write(margin.astype('<f4').tobytes())
    f.write(struct.pack('<iiifi',600,4000,report['minimum_hand_frames'],report['minimum_motion'],12))
report['shipping_thresholds']={str(t):{'threshold':float(threshold[t]),'margin':float(margin[t])} for t in TARGETS}
report['model_sha256']=hashlib.sha256((out/'fsl_recognizer.bin').read_bytes()).hexdigest()
(out/'report.json').write_text(json.dumps(report,indent=2))
print(json.dumps({'enabled':enabled,'two_window':results},indent=2))

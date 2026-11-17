"""Version 1 Android/Python feature contract; coordinate units are image heights."""
import numpy as np
JOINTS = (0,11,12,13,14,15,16)
FRAMES, FEATURES = 16,156

def frame_features(hands, pose, width, height):
    aspect=width/height
    origin=np.array([.5*aspect,.5]);body_scale=.3*aspect
    valid=False
    if pose is not None:
        p=np.asarray(pose,dtype=np.float64)
        shoulders=p[[11,12],:2]*[aspect,1]
        size=np.linalg.norm(shoulders[1]-shoulders[0])
        valid=bool(np.isfinite(p).all() and min(p[11,3],p[12,3])>=.3 and size>=.05)
        if valid: origin=shoulders.mean(axis=0);body_scale=size
    out=np.zeros(FEATURES,dtype=np.float32)
    valid_hands=[]
    for hand in hands:
        h=np.asarray(hand,dtype=np.float64)
        if h.shape==(21,3) and np.isfinite(h).all():valid_hands.append(h)
    for slot,h in enumerate(sorted(valid_hands,key=lambda v:v[0][0])[:2]):
        h=h*[aspect,1,aspect]
        local=h-h[0]; palm=max(float(np.linalg.norm(local[[5,9,13,17]],axis=1).mean()),1e-4)
        j=slot*67;out[j:j+63]=(local/palm).reshape(-1)
        out[j+63:j+65]=(h[0,:2]-origin)/body_scale
        out[j+65]=palm/body_scale;out[j+66]=1
    if valid:
        for i,joint in enumerate(JOINTS):
            out[134+i*3:137+i*3]=[*((p[joint,:2]*[aspect,1]-origin)/body_scale),np.clip(p[joint,3],0,1)]
        out[155]=1
    return out

def resample(x,n=FRAMES):
    x=np.asarray(x,dtype=np.float32)
    if len(x)==0:return np.zeros((n,FEATURES),dtype=np.float32)
    times=np.linspace(0,len(x)-1,n)
    lo=np.floor(times).astype(int);hi=np.minimum(lo+1,len(x)-1);a=(times-lo).astype(np.float32)[:,None]
    return x[lo]*(1-a)+x[hi]*a

def mirror_sequence(x):
    y=np.array(x,copy=True)
    for base in (0,67):
        y[:,base:base+63:3]*=-1;y[:,base+63]*=-1
    both=(y[:,66]>=.5)&(y[:,133]>=.5)
    temp=y[both,:67].copy();y[both,:67]=y[both,67:134];y[both,67:134]=temp
    p=y[:,134:155].reshape(-1,7,3);p[:,:,0]*=-1;p[:]=p[:,[0,2,1,4,3,6,5]]
    return y

def active_sequence(x):
    x=np.asarray(x,dtype=np.float32)
    active=np.flatnonzero((x[:,66]>=.5)|(x[:,133]>=.5))
    return x[active[0]:active[-1]+1] if len(active) else x[:0]

def sequence_motion(x):
    """RMS hand motion, excluding missing-slot transitions and presence features."""
    x=resample(x);total=0.;count=0
    for base in (0,67):
        valid=(x[:-1,base+66]>=.5)&(x[1:,base+66]>=.5)
        dif=np.diff(x[:,base:base+66],axis=0)[valid]
        total+=float(np.sum(dif*dif));count+=dif.size
    return float(np.sqrt(total/count)) if count else 0.


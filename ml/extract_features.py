"""Extract cached landmark features from official FSL105 clips; no raw videos retained."""
import os
os.environ.setdefault('OMP_NUM_THREADS','1');os.environ.setdefault('OPENBLAS_NUM_THREADS','1');os.environ.setdefault('MPLCONFIGDIR',os.path.join(os.path.dirname(os.path.abspath(__file__)),'cache','matplotlib'))
import argparse,csv,json,time,zipfile,tempfile
from pathlib import Path
from concurrent.futures import ProcessPoolExecutor,as_completed
import cv2,numpy as np,mediapipe as mp
from mediapipe.tasks import python
from mediapipe.tasks.python import vision
from features import frame_features

BASE=Path(__file__).resolve().parent
ASSETS=BASE.parent/'android/app/src/main/assets'  # MediaPipe models shipped with the app
ZIP=None; HAND=None; POSE=None

def init(zip_path):
    global ZIP,HAND,POSE
    cv2.setNumThreads(1)
    ZIP=zipfile.ZipFile(zip_path)
    opts=python.BaseOptions(model_asset_path=str(ASSETS/'hand_landmarker.task'))
    HAND=vision.HandLandmarker.create_from_options(vision.HandLandmarkerOptions(base_options=opts,running_mode=vision.RunningMode.VIDEO,num_hands=2,min_hand_detection_confidence=.35,min_hand_presence_confidence=.35,min_tracking_confidence=.35))
    POSE=vision.PoseLandmarker.create_from_options(vision.PoseLandmarkerOptions(base_options=python.BaseOptions(model_asset_path=str(ASSETS/'pose_landmarker_lite.task')),running_mode=vision.RunningMode.VIDEO,num_poses=1,min_pose_detection_confidence=.35,min_pose_presence_confidence=.35,min_tracking_confidence=.35))
    init.timestamp=0

def extract(row_fps):
    row,fps_target=row_fps;name=row['vid_path'].replace('\\','/');label=int(row['id_label'])
    target=BASE/'cache'/f"{label}_{Path(name).stem}.npz"
    if target.exists():return name,'cached'
    fd,path=tempfile.mkstemp(suffix='.MOV',dir=BASE/'cache');os.close(fd)
    try:
        Path(path).write_bytes(ZIP.read(name));cap=cv2.VideoCapture(path)
        fps=cap.get(cv2.CAP_PROP_FPS) or 30.;total=int(cap.get(cv2.CAP_PROP_FRAME_COUNT));stride=max(1,round(fps/fps_target))
        seq=[];stamps=[];first=None;frame_idx=0
        while True:
            ok,bgr=cap.read()
            if not ok:break
            if frame_idx%stride==0:
                # Match mobile inference image scale while keeping input aspect.
                h,w=bgr.shape[:2]
                if max(h,w)>640:bgr=cv2.resize(bgr,(round(w*640/max(h,w)),round(h*640/max(h,w))))
                rgb=cv2.cvtColor(bgr,cv2.COLOR_BGR2RGB)
                img=mp.Image(image_format=mp.ImageFormat.SRGB,data=rgb)
                init.timestamp+=max(1,round(1000*stride/fps))
                hands=HAND.detect_for_video(img,init.timestamp);pose=POSE.detect_for_video(img,init.timestamp)
                hs=[[[p.x,p.y,p.z] for p in hand] for hand in hands.hand_landmarks]
                ps=None if not pose.pose_landmarks else [[p.x,p.y,p.z,p.visibility] for p in pose.pose_landmarks[0]]
                features=frame_features(hs,ps,w,h);seq.append(features);stamps.append(frame_idx/fps)
                if first is None and hs and ps:
                    first={'hands':hs,'pose':ps,'width':w,'height':h,'expected':features.tolist()}
            frame_idx+=1
        cap.release()
        np.savez_compressed(target,features=np.asarray(seq,dtype='float32'),times=np.asarray(stamps,dtype='float32'),label=label,duration=total/fps)
        if label==0 and Path(name).stem=='0' and first:(BASE/'golden-frame.json').write_text(json.dumps(first))
        return name,{'frames':len(seq),'hand':float(np.mean(np.asarray(seq)[:,66])),'body':float(np.mean(np.asarray(seq)[:,155]))}
    finally:
        Path(path).unlink(missing_ok=True)

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--zip',required=True);ap.add_argument('--workers',type=int,default=4);ap.add_argument('--fps',type=float,default=8);ap.add_argument('--limit',type=int);args=ap.parse_args()
    (BASE/'cache').mkdir(exist_ok=True)
    rows=[]
    for split in ('train','test'):
        with (BASE/f'{split}.csv').open(encoding='utf-8-sig',newline='') as f:rows.extend(csv.DictReader(f))
    rows=rows[:args.limit] if args.limit else rows
    start=time.time();done=0;bad=[]
    with ProcessPoolExecutor(max_workers=args.workers,initializer=init,initargs=(str(Path(args.zip).resolve()),)) as pool:
        futures={pool.submit(extract,(r,args.fps)):r for r in rows}
        for future in as_completed(futures):
            done+=1
            try:result=future.result()
            except Exception as e:bad.append({'row':futures[future],'error':str(e)});print('ERROR',bad[-1],flush=True)
            if done%25==0 or done==len(rows):print(json.dumps({'done':done,'total':len(rows),'elapsed_s':round(time.time()-start),'errors':len(bad)}),flush=True)
    (BASE/'extraction-errors.json').write_text(json.dumps(bad,indent=2))
    if bad:raise SystemExit(1)

if __name__=='__main__':main()

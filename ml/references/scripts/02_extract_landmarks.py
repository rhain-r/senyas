"""Step 2: tracks both hands in every clip with the app's MediaPipe hand model.

Frames are sampled at 15 fps (every 4th frame of the 60 fps clips), like a phone camera pipeline.
One JSON per clip, in the app's SignSample format:
{"label": "cCCC", "source": "fsl105", "srcFps": 60.0,
 "frames": [{"t": ms, "hands": [{"label", "score", "lm": [63 floats: x, y, z of 21 points]}]}]}
Needs mediapipe 0.10.21 and opencv-python (Python 3.12); see requirements-extract.txt.
Existing outputs are skipped, so an interrupted run can be restarted.

usage: python scripts/02_extract_landmarks.py [--model hand_landmarker.task] [--clips DIR] [--out DIR]
                                              [--fps 15] [--workers 4]
"""
import argparse
import json
import os
import sys
from concurrent.futures import ProcessPoolExecutor

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from senyas_refs import config  # noqa: E402


def work(job):
    model, clip, out_dir, fps = job
    import cv2
    import mediapipe as mp
    from mediapipe.tasks import python as mpp
    from mediapipe.tasks.python import vision
    dst = os.path.join(out_dir, os.path.splitext(os.path.basename(clip))[0] + ".json")
    if os.path.exists(dst):
        return dst, "skip"
    opts = vision.HandLandmarkerOptions(
        base_options=mpp.BaseOptions(model_asset_path=model),
        running_mode=vision.RunningMode.VIDEO, num_hands=2,
        min_hand_detection_confidence=0.5, min_hand_presence_confidence=0.5, min_tracking_confidence=0.5)
    cap = cv2.VideoCapture(clip)
    src_fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
    step = max(1, round(src_fps / fps))
    frames, i = [], 0
    with vision.HandLandmarker.create_from_options(opts) as lm:
        while True:
            ok, img = cap.read()
            if not ok:
                break
            if i % step == 0:
                t = int(i * 1000 / src_fps)
                rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
                res = lm.detect_for_video(mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb), t)
                hands = []
                for h, cat in zip(res.hand_landmarks, res.handedness):
                    pts = []
                    for p in h:
                        pts += [round(p.x, 6), round(p.y, 6), round(p.z, 6)]
                    hands.append({"label": cat[0].category_name, "score": round(cat[0].score, 6), "lm": pts})
                frames.append({"t": t, "hands": hands})
            i += 1
    cap.release()
    cls = int(os.path.basename(clip)[:3])
    with open(dst, "w") as f:
        json.dump({"label": "c%03d" % cls, "source": "fsl105", "srcFps": src_fps, "frames": frames}, f)
    return dst, "%d frames @%.0f->%d" % (len(frames), src_fps, fps)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--model", default=config.data(config.MODEL), help="hand_landmarker.task")
    ap.add_argument("--clips", default=config.data(config.CLIPS_DIR), help="folder of CCC_KK.mov")
    ap.add_argument("--out", default=config.data(config.LANDMARKS_DIR), help="folder for CCC_KK.json")
    ap.add_argument("--fps", type=int, default=15, help="sampling rate (default 15)")
    ap.add_argument("--workers", type=int, default=4, help="parallel processes (default 4)")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    clips = sorted(os.path.join(a.clips, n) for n in os.listdir(a.clips) if n.lower().endswith(".mov"))
    done = 0
    with ProcessPoolExecutor(a.workers) as ex:
        for dst, msg in ex.map(work, [(a.model, c, a.out, a.fps) for c in clips]):
            done += 1
            if done % 25 == 0 or done == len(clips):
                print(done, "/", len(clips), os.path.basename(dst), msg, flush=True)


if __name__ == "__main__":
    main()

"""Step 5: encodes the app's demonstration videos, signs/fsl105/<sign>.mp4.

Each video is signer A's first clip of the sign (CCC_00.mov), re-encoded with ffmpeg and libx264:
no audio, 30 fps, H.264 Main profile, yuv420p, preset slow, "faststart" for quick playback.
The app's videos were made in two batches, and the default "app" recipe repeats both:
  batch1  the first 9 signs: fps filter, level 3.1, CRF 27
  batch2  the other 17 signs: output rate 30, CRF 26
ffmpeg must be on the PATH (or given with --ffmpeg).

usage: python scripts/05_make_videos.py [--clips DIR] [--out DIR] [--signs all] [--recipe app]
                                        [--ffmpeg ffmpeg]
"""
import argparse
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from senyas_refs import config, kit  # noqa: E402

RECIPES = {
    "batch1": ["-an", "-vf", "fps=30", "-c:v", "libx264", "-profile:v", "main", "-level", "3.1",
               "-pix_fmt", "yuv420p", "-crf", "27", "-preset", "slow", "-movflags", "+faststart"],
    "batch2": ["-an", "-r", "30", "-c:v", "libx264", "-profile:v", "main", "-pix_fmt", "yuv420p",
               "-crf", "26", "-preset", "slow", "-movflags", "+faststart"],
}
BATCH1_SIGNS = ("kumusta", "magandang_umaga", "magandang_gabi", "salamat", "walang_anuman",
                "oo", "hindi", "nanay", "tatay")


def recipe_for(name, choice):
    if choice != "app":
        return RECIPES[choice]
    return RECIPES["batch1" if name in BATCH1_SIGNS else "batch2"]


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--clips", default=config.data(config.CLIPS_DIR), help="folder of CCC_KK.mov")
    ap.add_argument("--out", default=config.out(config.VIDEOS_DIR), help="folder for <sign>.mp4")
    ap.add_argument("--signs", default="all", help="'all' or a comma list of app signs")
    ap.add_argument("--recipe", default="app", choices=["app"] + sorted(RECIPES),
                    help="'app' (as shipped, default) or one recipe for every sign")
    ap.add_argument("--ffmpeg", default="ffmpeg", help="ffmpeg executable")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    for name in kit.parse_signs(a.signs):
        src = os.path.join(a.clips, "%03d_00.mov" % kit.SIGNS[name])
        dst = os.path.join(a.out, name + ".mp4")
        cmd = [a.ffmpeg, "-y", "-loglevel", "error", "-i", src] + recipe_for(name, a.recipe) + [dst]
        subprocess.run(cmd, check=True)
        print("%-20s %6.1f KB" % (name, os.path.getsize(dst) / 1024))


if __name__ == "__main__":
    main()

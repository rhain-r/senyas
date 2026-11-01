"""Step 1: unpacks the FSL-105 clips the pipeline uses from the dataset's clips.zip.

Each clip clips/<class>/<k>.MOV is written as CCC_KK.mov (class and clip number, zero-padded).
By default it unpacks every clip of the 26 app signs, and clips 0-4, 5, 10 and 15 of every other
class (signer A's five clips plus one clip of each other signer, used as negatives).
That is 1,164 clips; --all unpacks all 2,235.

usage: python scripts/01_unpack_clips.py [--zip clips.zip] [--out DIR] [--all] [--classes 7,85]
"""
import argparse
import os
import re
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from senyas_refs import config, kit  # noqa: E402

PATTERN = re.compile(r"clips/(\d+)/(\d+)\.mov$", re.I)
OTHER_CLIPS = {0, 1, 2, 3, 4, 5, 10, 15}


def wanted(c, k, everything):
    return everything or c in kit.NAMES or k in OTHER_CLIPS


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--zip", default=config.data(config.CLIPS_ZIP), help="FSL-105 clips.zip")
    ap.add_argument("--out", default=config.data(config.CLIPS_DIR), help="folder for CCC_KK.mov")
    ap.add_argument("--all", action="store_true", help="unpack every clip of every class")
    ap.add_argument("--classes", help="only these FSL-105 classes (comma list), e.g. for a quick test")
    a = ap.parse_args()
    only = {int(c) for c in a.classes.split(",")} if a.classes else None
    os.makedirs(a.out, exist_ok=True)
    n = 0
    with zipfile.ZipFile(a.zip) as z:
        for name in z.namelist():
            m = PATTERN.search(name)
            if not m:
                continue
            c, k = int(m.group(1)), int(m.group(2))
            if (only is not None and c not in only) or not wanted(c, k, a.all):
                continue
            with z.open(name) as src, open(os.path.join(a.out, "%03d_%02d.mov" % (c, k)), "wb") as f:
                f.write(src.read())
            n += 1
    print("unpacked", n, "clips into", a.out)


if __name__ == "__main__":
    main()

"""Imports the 0.3.5 build row and tap-menu art (round 3, made with Codex image generation by the art lane, 2026-10-22,
from C:/Users/mauro/wgbg-art/buildrow-0.3.5/final, see its ../NOTES.md):

  plate_<name>_on / _off   the build row's brass plates, raised (can build) and engraved (can't), at PLATE px
  btn_check / btn_x (+ _pressed)   the enamelled check and X under a tap menu, at BUTTON px
  icon_supply / icon_promote       the knight menu's Supply and Promote lines, at ICON px
  clasp_closed / _open_33 / _open_66 / _open   the box clasp on the setup screen's pickers (re-exported 2026-10-22 to
      fill its canvas), cropped to the clasp, all four frames to one box so the hinge stays put

Usage: py -3.12 tools/import_buildrow.py            import
       py -3.12 tools/import_buildrow.py --check    check the shipped files
"""
from pathlib import Path
import sys
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
SRC = Path(r"C:/Users/mauro/wgbg-art/buildrow-0.3.5/final")
OUT = ROOT / "app/src/main/res/drawable-nodpi"
PLATE, BUTTON, ICON, CLASP = 128, 160, 96, 96
PLATES = ["road", "homestead", "town", "fortune", "knight", "ramparts", "guilds", "academy", "crown"]
SIZES = {f"plate_{p}_{s}": PLATE for p in PLATES for s in ("on", "off")}
SIZES.update({b: BUTTON for b in ("btn_check", "btn_check_pressed", "btn_x", "btn_x_pressed")})
SIZES.update({"icon_supply": ICON, "icon_promote": ICON})
CLASPS = ["clasp_closed", "clasp_open_33", "clasp_open_66", "clasp_open"]
SIZES.update({c: CLASP for c in CLASPS})


def problems():
    """What is wrong with the shipped files: missing, or not the size the app draws them at."""
    out = []
    for name, px in SIZES.items():
        f = OUT / f"{name}.webp"
        if not f.exists():
            out.append(f"missing {f.name}")
            continue
        with Image.open(f) as im:
            if max(im.size) != px:
                out.append(f"{f.name} is {im.size}, want {px} on its long side")
    return out


def save(im, name, px):
    im = im.convert("RGBA")
    im.thumbnail((px, px), Image.LANCZOS)
    im.save(OUT / f"{name}.webp", "WEBP", quality=90, method=6)


def main():
    if "--check" in sys.argv:
        bad = problems()
        print("\n".join(bad) or "ok")
        return 1 if bad else 0
    for name, px in SIZES.items():
        if name not in CLASPS:
            save(Image.open(SRC / f"{name}.png"), name, px)
    frames = [Image.open(SRC / f"{c}.png").convert("RGBA") for c in CLASPS]
    boxes = [f.getchannel("A").point(lambda a: 255 if a > 8 else 0).getbbox() for f in frames]
    box = (min(b[0] for b in boxes), min(b[1] for b in boxes), max(b[2] for b in boxes), max(b[3] for b in boxes))
    # One square box for all four frames, its bottom on the hinge, so the swing doesn't shift the clasp.
    side = max(box[2] - box[0], box[3] - box[1])
    cx = (box[0] + box[2]) // 2
    sq = (cx - side // 2, box[3] - side, cx - side // 2 + side, box[3])
    for c, f in zip(CLASPS, frames):
        save(f.crop(sq), c, CLASP)
    bad = problems()
    print("\n".join(bad) or f"imported {len(SIZES)} files")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())

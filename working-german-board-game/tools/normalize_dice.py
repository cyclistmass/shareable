"""Gives every die face the same body width (#31: Mauro saw the dice at different sizes).

The faces were cropped to a square around each die's own body (wide or tall, whichever was larger), so a face whose
body showed a little more height came out narrower. This scales each 192 px face so its solid body (alpha > 160) is
TARGET px wide, centred on the canvas, and checks nothing is clipped. Tumble frames are already uniform (347 px body
in every 512 px render) and are left alone.

Usage: py -3.12 tools/normalize_dice.py OUT_DIR [--production PX] [--event PX] [--backup DIR]
  OUT_DIR is where the faces are written (the res folder itself once Mauro has picked); --backup copies the
  originals there first.
       py -3.12 tools/normalize_dice.py --check [DIR]
  Fails unless every number die face in DIR (default: res) has one body width, and every event die face one, and
  the faces of each number die share one body colour.
"""
import argparse
import shutil
from pathlib import Path
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res/drawable-nodpi"
PRODUCTION = ("nr", "ni", "pr", "pb")
EVENT = ("ae", "pe", "pe1", "pe2", "pe3")
SIZE = 192
COLOUR_SPREAD = 14  # #31 (Mauro): one die may vary a little across its sides, but must not change colour by number


def body(img: Image.Image):
    return img.getchannel("A").point(lambda a: 255 if a > 160 else 0).getbbox()


def body_colour(img: Image.Image):
    """The die's own colour: the median of the opaque pixels near the overall median, so pips and edges drop out."""
    a = np.asarray(img.convert("RGBA"), dtype=float)
    px = a[a[..., 3] > 200][:, :3]
    near = px[np.linalg.norm(px - np.median(px, axis=0), axis=1) < 60]
    return np.median(near, axis=0)


def normalize(img: Image.Image, target: int) -> Image.Image:
    img = img.convert("RGBA")
    l, t, r, b = body(img)
    ideal = round(SIZE * target / (r - l))
    # Scale premultiplied so the transparent edge doesn't pick up dark fringes; the nearest size that lands exactly.
    for side in sorted(range(ideal - 3, ideal + 4), key=lambda n: abs(n - ideal)):
        big = img.convert("RGBa").resize((side, side), Image.LANCZOS).convert("RGBA")
        l, t, r, b = body(big)
        if r - l == target:
            break
    out = Image.new("RGBA", (SIZE, SIZE))
    out.paste(big, (round(SIZE / 2 - (l + r) / 2), round(SIZE / 2 - (t + b) / 2)))
    nl, nt, nr, nb = body(out)
    assert abs((nr - nl) - target) <= 1, f"body {nr - nl} px, wanted {target}"
    assert (nr - nl, nb - nt) == (r - l, b - t), f"body clipped: {b - t} px tall in a {SIZE} px face"
    return out


def check(folder: Path) -> int:
    bad = 0
    for name, sets in (("number dice", PRODUCTION), ("event dice", EVENT)):
        widths = set()
        for s in sets:
            for f in range(1, 7):
                l, t, r, b = body(Image.open(folder / f"dice_{s}_{f}.webp").convert("RGBA"))
                widths.add(r - l)
        sizes = sorted(widths)
        print(f"{name}: body widths {sizes}")
        if len(sizes) > 1: bad += 1
    for s in PRODUCTION:
        cs = np.array([body_colour(Image.open(folder / f"dice_{s}_{f}.webp")) for f in range(1, 7)])
        spread = int((cs.max(axis=0) - cs.min(axis=0)).max())
        print(f"dice_{s}: body colour spread {spread} across its faces")
        if spread > COLOUR_SPREAD: bad += 1
    return 1 if bad else 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out", type=Path, nargs="?")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--production", type=int, default=189)  # Mauro's pick B (#31, 2026-10-20)
    ap.add_argument("--event", type=int, default=187)  # pick B
    ap.add_argument("--backup", type=Path)
    a = ap.parse_args()
    if a.check:
        raise SystemExit(check(a.out or RES))
    a.out.mkdir(parents=True, exist_ok=True)
    for sets, target in ((PRODUCTION, a.production), (EVENT, a.event)):
        for s in sets:
            for f in range(1, 7):
                src = RES / f"dice_{s}_{f}.webp"
                if a.backup:
                    a.backup.mkdir(parents=True, exist_ok=True)
                    if not (a.backup / src.name).exists():
                        shutil.copy2(src, a.backup / src.name)
                img = normalize(Image.open(src), target)
                img.save(a.out / src.name, quality=90, method=6)
                l, t, r, b = body(img)
                print(f"{src.name}: body {r - l} x {b - t} at ({l}, {t})")


if __name__ == "__main__":
    main()

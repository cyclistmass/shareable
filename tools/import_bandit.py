"""Imports the Bandit, the Raccoon Thief (#86, Mauro's pick, 0.3.4), rendered by
C:/Users/mauro/wgbg-art/bandit-v3/render_bandit_v3.py and sealed by compose_bandit_v3.py: 8 tilt steps (tilt 0.96*i/7)
at 512x512, orthographic 2.65 hex units across, foot anchor at the centre, with an opaque base that hides the number
plate. Writes bandit_tilt_0..7.webp at 384x384 and copies the 3D board's bandit.glb (export_assets.py --asset bandit).

Usage: py tools/import_bandit.py            import
       py tools/import_bandit.py --check    check the shipped sprites still hide the plate at every tilt
"""
from pathlib import Path
import math, shutil, sys
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
SRC = Path(r"C:/Users/mauro/wgbg-art/bandit-v3")
GLB_SRC = Path(r"C:/Users/mauro/wgbg-art/realtime/glb/bandit.glb")
OUT = ROOT / "app/src/main/res/drawable-nodpi"
GLB = ROOT / "app/src/main/assets/filament/bandit.glb"
STEPS = 8
SIZE = 384
PLATE = (.36, .30, .27)  # number plate centre (x right, y down, before the tilt squash) and radius, hex units


def sprite(i):
    return OUT / f"bandit_tilt_{i}.webp"


def problems():
    """What is wrong with the shipped sprites: size, mode, a see-through plate or figure clipped at the frame."""
    out = []
    for i in range(STEPS):
        p = sprite(i)
        if not p.is_file():
            out.append(f"{p.name}: missing"); continue
        im = Image.open(p)
        if im.size != (SIZE, SIZE) or im.mode != "RGBA":
            out.append(f"{p.name}: {im.size} {im.mode}, want {SIZE}x{SIZE} RGBA"); continue
        a = im.getchannel("A"); px = SIZE / 2.65; t = .96 * i / 7
        # The sprite is drawn with its anchor on the plate's centre, so the plate covers a disc round the anchor.
        for k in range(48):
            for rr in (0, .5, .9):
                ang = k * math.tau / 48; r = PLATE[2] * rr * px
                x = SIZE / 2 + r * math.cos(ang); y = SIZE / 2 + r * math.sin(ang) * math.cos(t)
                if a.getpixel((int(x), int(y))) < 250:
                    out.append(f"{p.name}: the plate shows through at ({int(x)}, {int(y)})"); break
            else:
                continue
            break
        box = a.getbbox()
        if box is None or min(box[:2]) <= 0 or max(box[2:]) >= SIZE:
            out.append(f"{p.name}: the figure touches the frame edge {box}")
    return out


def main():
    if "--check" not in sys.argv:
        for i in range(STEPS):
            Image.open(SRC / f"bandit3_coon_tilt_{i}.png").convert("RGBA").resize((SIZE, SIZE), Image.LANCZOS) \
                .save(sprite(i), quality=88, method=6)
        shutil.copy2(GLB_SRC, GLB)
    bad = problems()
    for b in bad: print(b)
    print("bandit:", "FAIL" if bad else f"OK ({STEPS} sprites)")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()

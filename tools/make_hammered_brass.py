"""Makes the aged hammered-brass texture for the panel frames (0.3.4, Mauro: "I like round corners, preferably
hammered that way").

Hand-beaten brass: a field of shallow round dents (each cell of a wrap-around Worley pattern is one hammer blow), lit
from the upper left like the rest of the table. It is aged, never new (art-spec): the dents are darker, the ridges
between them are worn bright where hands rub, and a little verdigris has settled in the deepest dents. The texture
tiles both ways, and has no direction, so the frame can lay it round its rounded corners without turning it. Our own
art, made from our own old_brass.jpg.

Usage: py -3.12 -I tools/make_hammered_brass.py [OUT] (default: app/src/main/res/drawable-nodpi/hammered_brass.webp)
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res/drawable-nodpi"
N = 256       # texture size, px (square, tiles both ways)
GRID = 12     # hammer blows on a jittered 12 x 12 grid: about 21 px apart, never bunched


def dents(rng: np.random.Generator) -> np.ndarray:
    """Depth of the beaten surface, 0 on the untouched sheet: each blow leaves a round, smooth dish of its own size;
    where dishes overlap the deeper one wins, so the ridges between blows are soft creases. Wraps round the edges."""
    y, x = np.mgrid[0:N, 0:N].astype(float)
    depth = np.zeros((N, N))
    step = N / GRID
    for i in range(GRID):
        for j in range(GRID):
            cx, cy = (i + rng.uniform(0.15, 0.85)) * step, (j + rng.uniform(0.15, 0.85)) * step
            r = step * rng.uniform(0.75, 1.05)
            dx = np.abs(x - cx); dx = np.minimum(dx, N - dx)
            dy = np.abs(y - cy); dy = np.minimum(dy, N - dy)
            dish = np.clip(1 - (dx * dx + dy * dy) / (r * r), 0, None) * rng.uniform(0.8, 1.0)
            depth = np.maximum(depth, dish)
    return depth


def blur(a: np.ndarray, r: int) -> np.ndarray:
    """A wrap-around box blur both ways (soft edges for the dents and the patina)."""
    k = 2 * r + 1
    for axis in (0, 1):
        a = sum(np.roll(a, s, axis=axis) for s in range(-r, r + 1)) / k
    return a


def make(seed: int = 86) -> Image.Image:
    rng = np.random.default_rng(seed)
    depth = blur(dents(rng), 1)
    height = -depth
    gy, gx = np.gradient(np.pad(height, 1, mode="wrap"))
    gx, gy = gx[1:-1, 1:-1], gy[1:-1, 1:-1]
    # Light from the upper left: a slope that faces it is lit, the far wall of each dent is in shade.
    lit = np.clip(0.5 + 9.0 * (gx + gy), 0, 1)
    # The old brass mottle, mirrored into four quarters so it repeats without a seam.
    quarter = np.asarray(Image.open(RES / "old_brass.jpg").convert("L").resize((N // 2, N // 2), Image.LANCZOS), dtype=float) / 255
    half = np.concatenate([quarter, quarter[:, ::-1]], axis=1)
    mottle = np.concatenate([half, half[::-1]], axis=0)
    mottle = blur((mottle - mottle.mean()) / mottle.std(), 2)
    light = 0.30 + 0.42 * lit - 0.10 * depth + 0.04 * mottle    # darker down in the dents
    light += 0.12 * np.clip((0.12 - depth) / 0.12, 0, 1)        # worn bright on the high points between blows
    dark, mid, bright = np.array([52, 34, 10]), np.array([160, 118, 48]), np.array([232, 200, 128])
    v = np.clip(light, 0, 1)[..., None]
    rgb = np.where(v < 0.5, dark + (mid - dark) * (v / 0.5), mid + (bright - mid) * ((v - 0.5) / 0.5))
    # Verdigris in the deepest dents, in patches (not every dent).
    patch = blur(rng.normal(0, 1, (N, N)), 18)
    patch = np.clip((patch / patch.std() - 0.9) / 1.0, 0, 1)
    green = (np.clip(depth - 0.7, 0, 0.3) / 0.3 * patch * 0.45)[..., None]
    rgb = rgb * (1 - green) + np.array([78, 132, 108]) * green
    return Image.fromarray(np.clip(rgb, 0, 255).astype(np.uint8), "RGB")


if __name__ == "__main__":
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else RES / "hammered_brass.webp"
    make().save(out, quality=88, method=6)
    print(out)

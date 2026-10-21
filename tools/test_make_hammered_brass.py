"""Tests for tools/make_hammered_brass.py (0.3.4 panel frames).

Usage: py -3.12 -I tools/test_make_hammered_brass.py
"""
import sys
import unittest
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
import make_hammered_brass as hb  # noqa: E402


def lum(img: Image.Image) -> np.ndarray:
    return np.asarray(img.convert("L"), dtype=float)


class HammeredBrass(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.img = hb.make()
        cls.l = lum(cls.img)

    def test_tiles_without_a_seam(self):
        """The frame repeats the texture round the panel: the step across the wrap must be about as big as the big
        steps inside it (the dents sit on a grid, so the creases at cell edges are the texture's biggest steps anyway)."""
        for axis in (1, 0):
            wrapped = np.concatenate([self.l, np.take(self.l, [0], axis=axis)], axis=axis)
            steps = np.abs(np.diff(wrapped, axis=axis)).mean(axis=1 - axis)
            self.assertLess(steps[-1], 1.5 * np.percentile(steps[:-1], 95), f"seam along axis {axis}")

    def test_aged_brass_not_new(self):
        """Art-spec: worn and aged, never mirror-bright; warm brass, a little verdigris, never a green sheet."""
        rgb = np.asarray(self.img, dtype=float)
        self.assertLess(self.l.mean(), 150, "too bright for aged brass")
        self.assertGreater(self.l.mean(), 70, "too dark to read as brass")
        self.assertLess((self.l > 235).mean(), 0.01, "mirror-bright highlights")
        r, g, b = rgb[..., 0].mean(), rgb[..., 1].mean(), rgb[..., 2].mean()
        self.assertTrue(r > g > b, "a warm brass hue")
        # Verdigris pulls green toward red: brass sits near (g - b) / (r - b) = 0.62.
        green = ((rgb[..., 1] - rgb[..., 2]) / np.maximum(rgb[..., 0] - rgb[..., 2], 1) > 0.75).mean()
        self.assertGreater(green, 0.002, "some verdigris")
        self.assertLess(green, 0.05, "only a little verdigris")

    def test_dents_are_lit_from_the_upper_left(self):
        """A dent's upper-left wall faces away from the light, so it is darker than its lower-right wall."""
        y, x = np.mgrid[0:hb.N, 0:hb.N]
        depth = hb.blur(hb.dents(np.random.default_rng(86)), 1)
        gy, gx = np.gradient(depth)
        falling = (gx + gy) > 0.02   # depth grows toward the lower right: the upper-left wall of a dent
        rising = (gx + gy) < -0.02
        self.assertLess(self.l[falling].mean(), self.l[rising].mean())

    def test_shipped_texture_is_this_script(self):
        shipped = lum(Image.open(hb.RES / "hammered_brass.webp"))
        self.assertEqual((hb.N, hb.N), shipped.shape)
        self.assertLess(np.abs(shipped - self.l).mean(), 3, "res/hammered_brass.webp is stale: re-run the script")


if __name__ == "__main__":
    unittest.main()

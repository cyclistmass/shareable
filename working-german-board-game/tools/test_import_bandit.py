"""Tests for tools/import_bandit.py's check (#86): the shipped Bandit hides the number plate at every tilt step.

Usage: py -3.12 -I tools/test_import_bandit.py
"""
import sys
import tempfile
import unittest
from pathlib import Path

from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parent))
import import_bandit as ib  # noqa: E402


class ShippedBandit(unittest.TestCase):
    def test_the_shipped_sprites_hide_the_plate_at_every_tilt(self):
        self.assertEqual([], ib.problems())

    def test_the_3d_board_has_its_model(self):
        self.assertTrue(ib.GLB.is_file())
        self.assertEqual(b"glTF", ib.GLB.read_bytes()[:4])


class BreakIt(unittest.TestCase):
    """Planted faults in a copy of the sprites: each must be caught."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.old = ib.OUT
        ib.OUT = Path(self.tmp.name)
        for i in range(ib.STEPS):
            Image.open(self.old / f"bandit_tilt_{i}.webp").save(ib.OUT / f"bandit_tilt_{i}.webp", lossless=True)

    def tearDown(self):
        ib.OUT = self.old; self.tmp.cleanup()

    def test_a_copy_passes(self):
        self.assertEqual([], ib.problems())

    def test_a_hole_over_the_plate_is_caught(self):
        p = ib.sprite(4); im = Image.open(p).convert("RGBA")
        c = ib.SIZE // 2; ImageDraw.Draw(im).ellipse((c + 10, c - 4, c + 22, c + 8), fill=(0, 0, 0, 0))
        im.save(p, lossless=True)
        self.assertTrue(any("plate shows through" in b for b in ib.problems()))

    def test_a_missing_step_and_a_wrong_size_are_caught(self):
        ib.sprite(7).unlink()
        Image.open(ib.sprite(2)).resize((512, 512)).save(ib.sprite(2), lossless=True)
        bad = ib.problems()
        self.assertTrue(any("bandit_tilt_7.webp: missing" in b for b in bad))
        self.assertTrue(any("bandit_tilt_2.webp" in b and "want 384x384" in b for b in bad))

    def test_a_figure_clipped_at_the_frame_is_caught(self):
        p = ib.sprite(0); im = Image.open(p).convert("RGBA")
        ImageDraw.Draw(im).rectangle((0, 100, 6, 120), fill=(90, 60, 30, 255)); im.save(p, lossless=True)
        self.assertTrue(any("frame edge" in b for b in ib.problems()))


if __name__ == "__main__":
    unittest.main()

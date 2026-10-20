"""Tests for tools/normalize_dice.py (#31: every die face the same size).

Usage: py -3.12 -I tools/test_normalize_dice.py
"""
import sys
import tempfile
import unittest
from pathlib import Path

from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parent))
import normalize_dice as nd  # noqa: E402


def face(w: int, h: int, size: int = nd.SIZE) -> Image.Image:
    """A die face: a solid w x h body, off centre, on a transparent canvas, with a soft edge."""
    img = Image.new("RGBA", (size, size))
    d = ImageDraw.Draw(img)
    x, y = 3, size - h - 5
    d.rectangle((x - 1, y - 1, x + w, y + h), fill=(200, 200, 200, 100))  # soft halo, below the body cut-off
    d.rectangle((x, y, x + w - 1, y + h - 1), fill=(240, 230, 210, 255))
    return img


def width(img: Image.Image) -> int:
    l, t, r, b = nd.body(img)
    return r - l


class NormalizeDice(unittest.TestCase):
    def test_wide_and_tall_faces_come_out_one_width_and_centred(self):
        for w, h in ((180, 170), (176, 178), (150, 150), (191, 160)):  # wide, tall (fits: 178 * 189 / 176 < 192), small, big
            out = nd.normalize(face(w, h), 189)
            self.assertEqual(out.size, (nd.SIZE, nd.SIZE))
            self.assertLessEqual(abs(width(out) - 189), 1, (w, h))
            l, t, r, b = nd.body(out)
            self.assertLessEqual(abs((l + r) - nd.SIZE), 2, "centred across")
            self.assertLessEqual(abs((t + b) - nd.SIZE), 2, "centred down")

    def test_break_it_a_body_too_tall_for_the_face_is_refused_not_clipped(self):
        with self.assertRaises(AssertionError):
            nd.normalize(face(150, 186), 189)  # 186 * 189 / 150 > 192: it would be cut off

    def test_check_fails_when_one_face_differs(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            for sets, target in ((nd.PRODUCTION, 189), (nd.EVENT, 187)):
                for s in sets:
                    for f in range(1, 7):
                        nd.normalize(face(170, 160), target).save(folder / f"dice_{s}_{f}.webp", lossless=True)
            self.assertEqual(0, nd.check(folder))
            nd.normalize(face(170, 160), 180).save(folder / "dice_pr_3.webp", lossless=True)
            self.assertEqual(1, nd.check(folder))

    def test_the_app_dice_are_pick_b(self):
        """Mauro's pick (#31, 2026-10-20): number dice 189 px wide, the event die 187 px, every face."""
        for sets, target in ((nd.PRODUCTION, 189), (nd.EVENT, 187)):
            for s in sets:
                for f in range(1, 7):
                    img = Image.open(nd.RES / f"dice_{s}_{f}.webp").convert("RGBA")
                    self.assertEqual((nd.SIZE, nd.SIZE), img.size, f"dice_{s}_{f}")
                    self.assertEqual(target, width(img), f"dice_{s}_{f}")
        self.assertEqual(0, nd.check(nd.RES))


if __name__ == "__main__":
    unittest.main()

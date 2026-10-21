"""Tests for tools/import_ledger_cards.py's check (#86): every Ledger card and progress-card painting ships whole.

Usage: py -3.12 -I tools/test_import_ledger_cards.py
"""
import re
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parent))
import import_ledger_cards as il  # noqa: E402


class ShippedCards(unittest.TestCase):
    def test_the_shipped_cards_pass(self):
        self.assertEqual([], il.problems())

    def test_the_code_points_at_every_fortune_card(self):
        # GuideArt.fortuneArt must name each shipped Fortune card, so none is left unused (or missing at run time).
        code = (il.ROOT / "app/src/main/java/com/mauro/wgbg/GuideArt.kt").read_text(encoding="utf8")
        used = set(re.findall(r"R\.drawable\.(card_fortune_\w+)", code))
        self.assertEqual({n for n in il.CARDS if n.startswith("card_fortune_")}, used)


class BreakIt(unittest.TestCase):
    """Planted faults in a copy of the cards: each must be caught."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.old = il.OUT
        il.OUT = Path(self.tmp.name)
        for name in list(il.CARDS) + il.PAINTINGS:
            shutil.copy2(self.old / f"{name}.webp", il.OUT / f"{name}.webp")

    def tearDown(self):
        il.OUT = self.old; self.tmp.cleanup()

    def test_a_copy_passes(self):
        self.assertEqual([], il.problems())

    def test_a_missing_card_and_a_wrong_size_are_caught(self):
        (il.OUT / "card_fortune_landmark.webp").unlink()
        Image.open(il.OUT / "card_clay.webp").resize((270, 390)).save(il.OUT / "card_clay.webp", lossless=True)
        bad = il.problems()
        self.assertTrue(any("card_fortune_landmark.webp: missing" in b for b in bad))
        self.assertTrue(any("card_clay.webp" in b and "want 280x390" in b for b in bad))

    def test_a_missing_progress_painting_is_caught(self):
        (il.OUT / "card_paint_weave.webp").unlink()
        self.assertTrue(any("card_paint_weave.webp: missing" in b for b in il.problems()))

    def test_a_wrong_size_progress_painting_is_caught(self):
        Image.open(il.OUT / "card_paint_parchment.webp").resize((390, 280)).save(il.OUT / "card_paint_parchment.webp", lossless=True)
        self.assertTrue(any("card_paint_parchment.webp" in b and "want 280x390" in b for b in il.problems()))

    def test_a_see_through_card_is_caught(self):
        p = il.OUT / "card_timber.webp"; im = Image.open(p).convert("RGBA")
        ImageDraw.Draw(im).rectangle((120, 180, 160, 220), fill=(0, 0, 0, 0)); im.save(p, lossless=True)
        self.assertTrue(any("see-through" in b for b in il.problems()))


if __name__ == "__main__":
    unittest.main()

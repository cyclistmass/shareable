"""Tests for tools/layout_check.py: a clean player list passes and each planted overlap is caught.

Usage: py -3.12 -I tools/test_layout_check.py
"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import layout_check as lc  # noqa: E402


def dump(rows, dice="[20,300][200,340]", panel="[10,10][300,260]"):
    """A uiautomator-like dump: the screen, the Players panel holding [rows] (raw node XML) and the dice row."""
    d = f'<node content-desc="Dice" bounds="{dice}"/>' if dice else ""
    return (f'<hierarchy><node class="android.widget.FrameLayout" bounds="[0,0][640,320]">'
            f'<node content-desc="Players" bounds="{panel}">{rows}</node>{d}</node></hierarchy>')


def n(bounds, tag="", text="", kids=""):
    rid = f' resource-id="{tag}"' if tag else ""
    return f'<node text="{text}"{rid} bounds="{bounds}">{kids}</node>'


CLEAN = (n("[20,20][46,46]", "score-0", kids=n("[28,24][40,42]", text="10"))
         + n("[54,20][128,38]", text="WWWWWWWWWWWW…")
         + n("[136,20][170,36]", "cards-0", text="20")
         + n("[136,38][165,52]", "road-0", kids=n("[150,38][165,52]", text="10"))
         + n("[171,38][200,52]", "army-0", kids=n("[185,38][200,52]", text="12")))


class Clean(unittest.TestCase):
    def test_a_tidy_panel_passes(self):
        self.assertEqual([], lc.faults(dump(CLEAN)))

    def test_a_number_inside_its_own_dot_is_not_an_overlap(self):
        self.assertEqual([], lc.faults(dump(n("[20,20][46,46]", "score-0", kids=n("[20,20][46,46]", text="9")))))

    def test_touching_edges_are_fine(self):
        self.assertEqual([], lc.faults(dump(n("[20,20][60,40]", text="a") + n("[60,20][100,40]", text="b"))))

    def test_nodes_scrolled_away_are_skipped(self):
        self.assertEqual([], lc.faults(dump(CLEAN + n("[20,260][20,260]", text="White"))))


class BreakIt(unittest.TestCase):
    def test_two_overlapping_texts_are_caught(self):
        bad = lc.faults(dump(n("[54,20][140,38]", text="Bartholomew Wolf") + n("[136,20][170,36]", "cards-0", text="20")))
        self.assertTrue(any("Bartholomew Wolf" in b and "cards-0" in b for b in bad), bad)

    def test_a_badge_past_the_panel_edge_is_caught(self):
        self.assertTrue(any("edge" in b for b in lc.faults(dump(n("[280,38][320,52]", "army-3")))))

    def test_the_dice_over_the_last_row_are_caught(self):
        # 0.3.3 at 640x320: the dice row was forced 110 dp tall and sat over White's row.
        bad = lc.faults(dump(n("[20,230][128,256]", text="White"), dice="[20,240][200,280]"))
        self.assertTrue(any("White" in b and "dice" in b for b in bad), bad)
        self.assertTrue(any(b.startswith("Players") and "dice" in b for b in bad), bad)

    def test_the_turn_prompt_over_a_name_is_caught(self):
        # 0.3.4 at 640x320: the prompt, centred on the screen, ran over the first player's name.
        x = dump(CLEAN)[:-len('</node></hierarchy>')] + n("[90,8][520,40]", "turn-prompt", "Tap a glowing spot") + '</node></hierarchy>'
        bad = lc.faults(x)
        self.assertTrue(any(b.startswith("turn-prompt") and "covers" in b for b in bad), bad)

    def test_the_prompt_right_of_the_list_is_fine(self):
        x = dump(CLEAN)[:-len('</node></hierarchy>')] + n("[310,8][520,40]", "turn-prompt", "Tap a glowing spot") + '</node></hierarchy>'
        self.assertEqual([], lc.faults(x))

    def test_a_panel_off_the_screen_is_caught(self):
        self.assertTrue(any("off the screen" in b for b in lc.faults(dump("", panel="[10,10][300,400]"))))

    def test_no_players_panel_is_an_error_not_a_pass(self):
        with self.assertRaises(ValueError):
            lc.faults('<hierarchy><node bounds="[0,0][640,320]"/></hierarchy>')


ROW0 = (n("[20,20][46,46]", "score-0") + n("[54,20][128,38]", "name-0", "Red") + n("[136,20][170,36]", "cards-0", "20")
        + n("[136,38][165,52]", "road-0") + n("[171,38][200,52]", "army-0"))


def row3(dy=180, cut=0):
    """White's row, [dy] px below Red's; [cut] px taken off its bottom (a list scrolled or clipped)."""
    def b(l, t, r, bot): return f"[{l},{t + dy}][{r},{bot + dy - cut}]"
    return (n(b(20, 20, 46, 46), "score-3") + n(b(54, 20, 128, 38), "name-3", "White") + n(b(136, 20, 170, 36), "cards-3", "20")
            + n(b(136, 38, 165, 52), "road-3") + n(b(171, 38, 200, 52), "army-3"))


WINDOW = ("mDisplayCutout=DisplayCutout{insets=Rect(132, 0 - 0, 0) boundingRect={Bounds=[Rect(0, 479 - 132, 601), "
          "Rect(0, 0 - 0, 0), Rect(0, 0 - 0, 0), Rect(0, 0 - 0, 0)]} cutoutPathParserInfo={}}")


class LastRow(unittest.TestCase):
    def test_whites_whole_row_passes(self):
        self.assertEqual([], lc.faults(dump(ROW0 + row3())))

    def test_whites_row_cut_by_the_panel_is_caught(self):
        bad = lc.faults(dump(ROW0 + row3(cut=10)))
        self.assertTrue(any(b.startswith("4p: last player's row not fully visible") and "score-3" in b for b in bad), bad)

    def test_a_scrolling_list_is_caught(self):
        x = dump(ROW0 + row3()).replace('content-desc="Players"', 'content-desc="Players" scrollable="true"')
        self.assertTrue(any("the list scrolls" in b for b in lc.faults(x)))

    def test_whites_missing_badge_is_caught(self):
        x = dump(ROW0 + row3()).replace('"army-3"', '"other"')
        self.assertTrue(any("army-3 missing" in b for b in lc.faults(x)))

    def test_whites_row_scrolled_out_of_sight_is_caught_when_the_seats_are_known(self):
        bad = lc.faults(dump(ROW0), players=4)
        self.assertTrue(any(b.startswith("4p: last player's row not fully visible") and "name-3 missing" in b for b in bad), bad)

    def test_a_trade_sheet_over_the_list_excuses_the_row(self):
        # 640x320 with an offer open: the sheet covers White's row and Compose drops the covered badge.
        sheet = n("[46,89][559,304]", "trade-panel")
        x = dump(ROW0 + row3()).replace('"army-3"', '"other"')
        x = x[:-len('</node></hierarchy>')] + sheet + '</node></hierarchy>'
        self.assertEqual([], lc.faults(x, players=4))

    def test_a_sheet_elsewhere_excuses_nothing(self):
        x = dump(ROW0 + row3()).replace('"army-3"', '"other"')
        x = x[:-len('</node></hierarchy>')] + n("[400,89][559,304]", "trade-panel") + '</node></hierarchy>'
        self.assertTrue(any("army-3 missing" in b for b in lc.faults(x, players=4)))


class Cutout(unittest.TestCase):
    def test_the_rect_is_read_from_dumpsys_window(self):
        self.assertEqual([(0, 479, 132, 601)], lc.cutouts(WINDOW))
        self.assertEqual([], lc.cutouts("no cutout here"))

    def test_the_dice_under_the_lens_are_caught(self):
        bad = lc.faults(dump(CLEAN, dice="[20,500][200,560]"), lc.cutouts(WINDOW))
        self.assertTrue(any(b.startswith("Dice") and "camera cutout" in b for b in bad), bad)

    def test_the_board_may_run_under_it(self):
        x = dump(CLEAN).replace('<hierarchy>', '<hierarchy><node content-desc="Board" bounds="[0,0][640,320]"/>', 1)
        self.assertEqual([], lc.faults(x, [(600, 100, 640, 200)]))


if __name__ == "__main__":
    unittest.main()

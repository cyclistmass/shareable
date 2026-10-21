"""Imports the Ledger cards (#86, Mauro's pick "Make a larger rectangle picture", 0.3.4): each card a page from an old
ledger with a large rectangular picture, made by C:/Users/mauro/wgbg-art/cards-ledger/make_ledger_cards.py (layout
'rect') at 560x780. Writes them at the app's card size, 280x390:

  card_timber, card_clay, card_fleece, card_grain, card_stone, card_parchment, card_weave, card_silver
      the resource and commodity cards, replacing the painted cards in place;
  card_fortune_sentinel, card_fortune_windfall, card_fortune_corner, card_fortune_trailblazers, card_fortune_landmark
      the Fortune cards (the Pirate theme keeps its own knight card for the Sentinel).

The commodity paintings card_paint_parchment / _weave / _silver are the picture inside the Age of Chivalry progress
cards' own named face and the deck piles. Parchment and Fabric have new ChatGPT paintings (Mauro's picks, 2026-10-21:
narrow scrolls and cut sheets; dyed cloth over a drying pole), imported here from paint_<key>.png; Silver keeps its
painted card.

Usage: py tools/import_ledger_cards.py            import
       py tools/import_ledger_cards.py --check    check the shipped cards
"""
from pathlib import Path
import sys
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
SRC = Path(r"C:/Users/mauro/wgbg-art/cards-ledger/out")
OUT = ROOT / "app/src/main/res/drawable-nodpi"
SIZE = (280, 390)
# Shipped name: Ledger key.
CARDS = {
    "card_timber": "timber", "card_clay": "clay", "card_fleece": "fleece", "card_grain": "grain", "card_stone": "stone",
    "card_parchment": "parchment", "card_weave": "fabric", "card_silver": "silver",
    "card_fortune_sentinel": "sentinel", "card_fortune_windfall": "windfall", "card_fortune_corner": "corner",
    "card_fortune_trailblazers": "trailblazers", "card_fortune_landmark": "landmark",
}
PAINTINGS = ["card_paint_parchment", "card_paint_weave", "card_paint_silver"]
# Shipped painting: Ledger key, for the paintings the Ledger maker writes (paint_<key>.png).
PAINTED = {"card_paint_parchment": "parchment", "card_paint_weave": "fabric"}


def problems():
    """What is wrong with the shipped cards: a missing file, the wrong size, or a card with see-through middle."""
    out = []
    for name in list(CARDS) + PAINTINGS:
        p = OUT / f"{name}.webp"
        if not p.is_file():
            out.append(f"{p.name}: missing"); continue
        im = Image.open(p)
        if im.size != SIZE:
            out.append(f"{p.name}: {im.size}, want {SIZE[0]}x{SIZE[1]}"); continue
        a = im.convert("RGBA").getchannel("A")
        w, h = im.size
        # Rounded corners may be clear; the page itself never is.
        if min(a.crop((w // 10, h // 10, w - w // 10, h - h // 10)).getextrema()) < 250:
            out.append(f"{p.name}: the card is see-through inside its edge")
    return out


def main():
    if "--check" not in sys.argv:
        for name, key in CARDS.items():
            Image.open(SRC / f"ledger_rect_{key}.png").convert("RGBA").resize(SIZE, Image.LANCZOS) \
                .save(OUT / f"{name}.webp", quality=88, method=6)
        for name, key in PAINTED.items():
            Image.open(SRC / f"paint_{key}.png").convert("RGBA").resize(SIZE, Image.LANCZOS)                 .save(OUT / f"{name}.webp", quality=88, method=6)
    bad = problems()
    for b in bad: print(b)
    print("ledger cards:", "FAIL" if bad else f"OK ({len(CARDS)} cards, {len(PAINTINGS)} paintings)")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()

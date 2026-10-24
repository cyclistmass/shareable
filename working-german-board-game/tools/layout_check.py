"""Overlap check on a uiautomator dump (0.3.4): the player list ("Players") must not overlap itself or the dice.

Inside the Players panel, no two text or icon nodes may intersect, and none may cross the panel's edge. None of them
may cross the dice row ("Dice") or lie under anything else on screen (the turn prompt, a notice), and the panel itself
must stay inside the screen. A node and its own children don't
count (a score dot holds its number). Icons are the Compose test tags the panel exposes as resource ids.

The last player's row (White's, in a 4-player game) must be fully on screen: each of its parts there, as tall as the
first row's, inside the panel, and the list not scrolled (Mauro: "White's score might be blocked on 4player"). A modal
sheet (the trade offer, the card choice, the discard sheet) may cover the list on a short screen while it is open; Compose
then reports the covered parts cut or gone, so the row is excused while one crosses the panel.

With --cutout, a `dumpsys window` capture: no text, icon or dice node anywhere on screen may cross the camera cutout
(0.3.4: the lens sat on the dice). The board itself (a node covering most of the screen) runs under it on purpose.

Used by tools/ci_layout.sh (every screen size, font scale and cutout) and reusable by the other ci_*.sh scripts:
  python3 tools/layout_check.py <dump.xml> [label] [--cutout window.txt] [--players N]
prints each fault; exit 1 if any, 2 if the dump is unusable
"""
import re
import sys
import xml.etree.ElementTree as ET

SLACK = 1  # px: anti-aliased edges that only touch are fine


def box(node):
    m = re.fullmatch(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", node.get("bounds", ""))
    return tuple(map(int, m.groups())) if m else None


def label(node):
    tag = node.get("resource-id", "").split("/")[-1]
    return tag or node.get("text") or node.get("content-desc") or node.get("class", "?").split(".")[-1]


def crosses(a, b):
    """True when boxes a and b share more than an edge."""
    return min(a[2], b[2]) - max(a[0], b[0]) > SLACK and min(a[3], b[3]) - max(a[1], b[1]) > SLACK


def inside(a, outer):
    return a[0] >= outer[0] - SLACK and a[1] >= outer[1] - SLACK and a[2] <= outer[2] + SLACK and a[3] <= outer[3] + SLACK


ROW_PARTS = ("name", "score", "cards", "road", "army")
SHEETS = ("trade-panel", "card-choice", "discard-sheet", "feast-sheet")  # modal sheets that may cover the list while open


def cutouts(window_dump):
    """The camera cutout's rects (left, top, right, bottom), in the screen's current rotation, from `dumpsys window`."""
    m = re.search(r"mDisplayCutout=DisplayCutout\{.*?boundingRect=\{Bounds=\[(.*?)\]", window_dump)
    rects = [tuple(map(int, r)) for r in re.findall(r"Rect\((-?\d+), (-?\d+) - (-?\d+), (-?\d+)\)", m.group(1))] if m else []
    return [r for r in rects if r[2] > r[0] and r[3] > r[1]]


def tag(node):
    return node.get("resource-id", "").split("/")[-1]


def faults(xml_text, holes=(), players=None):
    """Every overlap in the dump, as readable lines; raises ValueError when there is no player list to check."""
    root = ET.fromstring(xml_text)
    parent = {c: p for p in root.iter() for c in p}
    panels = [n for n in root.iter("node") if n.get("content-desc") == "Players"]
    if not panels:
        raise ValueError("no Players panel in the dump")
    panel = panels[0]; pb = box(panel)
    screen = box(root.find("node")) if root.find("node") is not None else None
    out = []
    if screen and pb and not inside(pb, screen):
        out.append(f"the Players panel {pb} runs off the screen {screen}")
    parts = [n for n in panel.iter("node") if n is not panel and box(n)
             and (n.get("text") or n.get("content-desc") or n.get("resource-id"))]
    parts = [n for n in parts if box(n)[2] > box(n)[0] and box(n)[3] > box(n)[1]]  # zero-size: off screen, scrolled away

    def ancestors(n):
        while n in parent:
            n = parent[n]; yield n
    up = {n: set(ancestors(n)) for n in parts}
    for n in parts:
        if not inside(box(n), pb):
            out.append(f"{label(n)} {box(n)} crosses the Players panel's edge {pb}")
    for i, a in enumerate(parts):
        for b in parts[i + 1:]:
            if a in up[b] or b in up[a]:
                continue
            if crosses(box(a), box(b)):
                out.append(f"{label(a)} {box(a)} overlaps {label(b)} {box(b)}")
    for dice in (n for n in root.iter("node") if n.get("content-desc") == "Dice" and box(n)):
        for n in [panel] + parts:
            if crosses(box(n), box(dice)):
                out.append(f"{label(n)} {box(n)} crosses the dice {box(dice)}")
    # Nothing outside the list may lie over its parts (0.3.4: the turn prompt ran over the first player's name at 640
    # dp); an open sheet may, and the dice have their own check above.
    skip = set(panel.iter("node")) | {a for a in parent.values() if panel in set(a.iter("node"))}
    for n in root.iter("node"):
        if tag(n) in SHEETS or n.get("content-desc") == "Dice":
            skip |= set(n.iter("node"))
    area = (screen[2] - screen[0]) * (screen[3] - screen[1]) if screen else 0
    for n in root.iter("node"):
        b = box(n)
        if n in skip or not b or b[2] <= b[0] or b[3] <= b[1] or (area and (b[2] - b[0]) * (b[3] - b[1]) > area / 2):
            continue
        if not (n.get("text") or n.get("content-desc") or n.get("resource-id")):
            continue
        for part in parts:
            if crosses(b, box(part)):
                out.append(f"{label(n)} {b} covers {label(part)} {box(part)} in the player list")
    # The last seat's row: all there, full height (not cut by a scroll), inside the panel.
    by_tag = {tag(n): n for n in parts if tag(n)}
    seats = [int(t[5:]) for t in by_tag if t.startswith("name-") and t[5:].isdigit()]
    covered = any(tag(n) in SHEETS and box(n) and crosses(box(n), pb) for n in root.iter("node"))
    if (seats or players) and not covered:
        last = players - 1 if players else max(seats)
        why = []
        if panel.get("scrollable") == "true":
            why.append("the list scrolls")
        for part in ROW_PARTS:
            n, first = by_tag.get(f"{part}-{last}"), by_tag.get(f"{part}-0")
            if first is None:
                continue
            if n is None:
                why.append(f"{part}-{last} missing")
            elif (box(n)[3] - box(n)[1]) < (box(first)[3] - box(first)[1]) - 2:
                why.append(f"{part}-{last} cut to {box(n)}")
        if why:
            out.append(f"{last + 1}p: last player's row not fully visible: " + ", ".join(why))
    # Nothing to read or tap under the camera; the board (most of the screen) runs under it on purpose.
    labelled = lambda n: n.get("text") or n.get("content-desc") or n.get("resource-id")
    if holes and screen:
        area = (screen[2] - screen[0]) * (screen[3] - screen[1])
        for n in root.iter("node"):
            b = box(n)
            if not b or b[2] <= b[0] or b[3] <= b[1] or (b[2] - b[0]) * (b[3] - b[1]) > area / 2:
                continue
            if not labelled(n):
                continue
            # A frame that spans the hole on purpose (the player strip steps only its rows round it) is judged
            # by what it holds: only the innermost labelled things count.
            if any(labelled(d) for d in n.iter() if d is not n):
                continue
            for h in holes:
                if crosses(b, h):
                    out.append(f"{label(n)} {b} is under the camera cutout {h}")
    return out


def main():
    args = sys.argv[1:]
    holes = []
    if "--cutout" in args:
        i = args.index("--cutout")
        holes = cutouts(open(args[i + 1], encoding="utf8", errors="replace").read()); del args[i:i + 2]
    players = None
    if "--players" in args:
        i = args.index("--players"); players = int(args[i + 1]); del args[i:i + 2]
    if not args:
        sys.exit(__doc__)
    where = args[1] if len(args) > 1 else args[0]
    try:
        bad = faults(open(args[0], encoding="utf8").read(), holes, players)
    except (ValueError, ET.ParseError, OSError) as e:
        print(f"layout {where}: can't check: {e}"); sys.exit(2)
    for b in bad:
        print(f"layout {where}: {b}")
    print(f"layout {where}: {'FAIL' if bad else 'OK'}")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()

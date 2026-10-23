# Seat colour picker (0.3.6)

Mauro, 2026-10-22. Not in 0.3.5.

- **Pick any colour.** Tap a seat's colour on the setup screen and choose it on a colour-space picker (hue and
  shade), not from a fixed list of named colours.
- **See it on a board.** A small sample board under the picker shows the seat's pieces (roads, a homestead, a town, a
  knight) in the colour being picked, next to the other seats' pieces, so you can check it before you take it.
- **No colour names.** Seats read P1, P2, P3, P4 until they have names. When a bot name list is chosen (Japanese
  emperors, English queens...), the seats show the names from that list instead.
- **Picking a name gives the whole list.** Tapping a seat's name opens every name in the chosen list.
- **Close colours: warn, allow anyway.** If the colour is hard to tell from another seat's, say so under the preview
  ("close to P2's colour") and still let it be picked. SeatColoursTest already measures this (CIELAB dE); reuse it.

Related, also 0.3.6: the side panel drags right to peek under the camera cutout and springs back when let go, keeping
the dice clear of the camera as best it can (player reports X5kAU..., 9ymLz...).

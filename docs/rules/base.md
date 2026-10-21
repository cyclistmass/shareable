# The base game

2 to 4 players. The first to **10 points** on their own turn wins.

## The island

- 19 land tiles: 4 Woodland (Timber), 3 Clay pit (Clay), 4 Meadow (Fleece), 4 Grainfield (Grain), 3 Quarry (Stone),
  and 1 desert.
- 18 number discs on the land, the desert excepted: one 2 and one 12, and two each of 3, 4, 5, 6, 8, 9, 10 and 11.
  6 and 8 come up most often and are printed red.
- 9 harbours around the coast: five that swap one named resource 2 for 1, and four that swap any resource 3 for 1.
- The Bandit starts on the desert.
- The bank holds 19 cards of each resource.

The setup screen offers three ways to lay the island:

| Layout | Tiles | Numbers |
|---|---|---|
| Random (the default) | shuffled, harbours too | shuffled; by default reshuffled until no two red numbers (6, 8) touch |
| Starter | a fixed board with the desert in the middle | fixed |
| Spiral | shuffled, harbours too | laid in a fixed order (5, 2, 6, 3, 8, 10, 9, 12, 11, 4, 8, 10, 9, 4, 5, 6, 3, 11) spiralling in from a random outer corner, skipping the desert |

## Your pieces

Each player has 15 roads, 5 Homesteads and 4 Towns. A Town replaces a Homestead, which goes back to your supply.

| Build | Costs | Gives |
|---|---|---|
| Road | 1 Timber, 1 Clay | |
| Homestead | 1 Timber, 1 Clay, 1 Fleece, 1 Grain | 1 point |
| Town | 2 Grain, 3 Stone | 2 points (in place of the Homestead's 1) |
| Fortune card | 1 Fleece, 1 Grain, 1 Stone | a random card from the Fortune deck |

**Where things go**

- Homesteads and Towns stand on corners where tiles meet. Roads run along tile edges.
- **Spacing:** a Homestead needs its corner and every corner one edge away to be empty.
- After setup, a new Homestead must also touch one of your roads.
- A road must join one of your buildings or roads. It can't pass through a corner where another player has built.
- A Town can only be built on one of your own Homesteads.

## Setting up

1. Players place in turn order, then again in reverse order: the last player places twice in a row.
2. Each placement is a Homestead on any corner that keeps the spacing, then a road touching it.
3. Your **second** Homestead pays you one card for each producing tile it touches, as far as the bank has them.
4. The first player then takes the first real turn.

The setup screen can pick the first player at random. The pick comes from the game's seed, so every phone in an online
game agrees on it.

## A turn

1. **Before rolling** you may play one Fortune card (a Sentinel, say, to move the Bandit off your best tile).
2. **Roll** the two dice. Every tile with that number pays its neighbours: 1 card per Homestead, 2 per Town. The tile
   under the Bandit pays nothing.
3. **Then, in any order:**
   - build;
   - buy Fortune cards;
   - trade with the bank;
   - trade with the other players;
   - play one Fortune card, if you haven't this turn.
4. **End your turn.**

Building and trading only happen after the roll.

### When the bank runs short

If the bank can't pay everyone what a roll owes them in some resource, nobody gets that resource. The one exception:
when only one player is owed it, they get whatever the bank has left.

### Rolling a 7

1. Everyone holding **more than 7 cards** gives back half of them, rounded down, picking which. They go one at a time,
   starting with the player who rolled.
2. The roller moves the Bandit to a different tile. The desert is allowed; staying put is not.
3. The roller takes one random card from a player with a Homestead or Town on that tile who holds at least one card.
   If there are several, the roller chooses which. If there are none, nothing is taken.

A 7 pays nobody.

## Trading

**With the bank** (on your turn, after rolling):

| You have | Rate |
|---|---|
| nothing | 4 of one resource for 1 of any other |
| a building on a 3-for-1 harbour | 3 for 1 |
| a building on a harbour for that resource | 2 for 1 |

The bank must have the card you want.

**With the other players** (only the player whose turn it is, after rolling):

- Make one offer at a time: some cards for some others. You can't swap a resource for the same resource.
- The offer goes round the table. Each player accepts, says no, or answers with a counter-offer.
- An offer that only gives, or only asks, can be answered with a counter-offer but never simply accepted.
- When everyone has answered, you pick who to trade with, or call it off. You can withdraw the offer at any time.
- A player who can't pay for an offer says no automatically.
- Players whose turn it isn't don't trade with each other.

## Fortune cards

25 cards, bought blind from a shuffled deck. Every card is in [cards.md](cards.md); in short:

| Card | Copies | Does |
|---|---|---|
| Sentinel | 14 | Move the Bandit and take a card, as on a 7. Counts toward the Strongest Watch. |
| Windfall | 2 | Take 2 resources of your choice from the bank. |
| Corner the Market | 2 | Name a resource: every other player hands you all of theirs. |
| Trailblazers | 2 | Build 2 roads for free. |
| Landmark | 5 | 1 point, kept hidden. |

- Play at most **one** Fortune card per turn, before or after rolling.
- You can't play a card on the turn you bought it.
- Landmarks are never played: each one counts toward your points while it stays hidden in your hand, so buying the
  one you need wins at once.

## Awards

- **Grand Highway, 2 points:** for the longest unbroken run of your roads, at least 5 long. The run can't use one road
  twice, and another player's Homestead or Town on a corner breaks it there. Whoever holds the award keeps it while
  tied. If the holder's run is broken and two or more others then tie for the longest, the award goes back to the box
  until one of them is longest on their own.
- **Strongest Watch, 2 points:** for the first player to play 3 Sentinels. Another player takes it by playing more
  Sentinels than the holder (a tie leaves it where it is).

## Winning

You win when you reach 10 points (or the target set before the game) **on your own turn**. Points you reach on someone
else's turn count the moment your turn begins. Hidden Landmarks are revealed at the end.

## How the app plays it

- 2 players are allowed.
- Trailblazers' roads go down at once, before anything else, and can't be turned down. If you run out of roads or
  places for them, the rest are lost.
- Windfall gives a single card only when the bank can't give you any pair at all.
- Players discarding on a 7 choose their cards one by one.
- A game of 1000 turns ends with no winner. It's a guard for bot-only games; people never get near it.
- The Starter layout's harbours are in a fixed order (3:1, Clay, Timber, 3:1, Grain, Stone, 3:1, Fleece, 3:1), going
  round the coast.

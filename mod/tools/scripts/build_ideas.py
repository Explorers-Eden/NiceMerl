#!/usr/bin/env python3
"""Adds generated build ideas to Merl's "what should I build next?" pool (build_idea in bot/data/lines.json).

The hand-written ideas in the pool stay first; this adds combinations of a building, a place it fits and a twist
("A lighthouse on a sea stack, with a garden on the roof."), each written by hand, so there are over a thousand
different ideas. Running it again gives the same list (the shuffle is seeded) and replaces the generated part only.

    python mod/tools/scripts/build_ideas.py
"""
import json
import random
from pathlib import Path

LINES = Path(__file__).resolve().parents[3] / "bot" / "data" / "lines.json"
MARKER = "build_idea_generated_from"  # the number of hand-written ideas is kept under this key in "_generated"
TARGET = 1100

# Where things can stand: L = on land, W = by or in the water, S = in the sky, U = underground, N = in the Nether.
BUILDINGS = [
    ("a lighthouse", "LW"), ("a watermill", "LW"), ("a windmill", "L"), ("a library", "LUSN"), ("a wizard tower", "LSN"),
    ("a tavern", "LUW"), ("a blacksmith's forge", "LUN"), ("a bakery", "L"), ("a castle", "LSN"), ("a treehouse", "L"),
    ("a fishing hut", "W"), ("a monastery", "LS"), ("an observatory", "LS"), ("a greenhouse", "LSU"), ("a train station", "LU"),
    ("a museum", "LUS"), ("a market hall", "LW"), ("a cozy cottage", "LW"), ("a hunting lodge", "L"), ("a temple", "LUSN"),
    ("a bathhouse", "LUN"), ("a clock tower", "LS"), ("a guard tower", "LN"), ("a farmhouse", "L"), ("a beekeeper's hut", "L"),
    ("an inn", "LW"), ("a shipyard", "W"), ("a dock with warehouses", "W"), ("a mine entrance", "LU"), ("a dragon's lair", "LUN"),
    ("a fortress", "LN"), ("an enchanting tower", "LSN"), ("a potion shop", "LUN"), ("a barn", "L"), ("a stable", "L"),
    ("a garden pavilion", "LS"), ("a palace", "LSW"), ("a bridge", "LWSN"), ("an amphitheater", "L"), ("a wishing well", "L"),
    ("a lookout post", "LS"), ("a cartographer's office", "LW"), ("a chapel", "LS"), ("a pirate hideout", "WU"), ("a mushroom house", "LU"),
    ("a tea house", "LW"), ("a hot spring resort", "LU"), ("a research lab", "UWN"), ("an archery range", "L"), ("a village square", "L"),
    ("a mob arena", "LUN"), ("a portal hub", "LUN"), ("a storage warehouse", "LU"), ("a toy workshop", "L"), ("a music hall", "LS"),
    ("a stone circle", "L"), ("a ruined keep", "LN"), ("a hedge maze", "L"), ("a ranger station", "L"), ("a sky dock", "S"),
]
PLACES = [
    ("on a sea stack", "W"), ("on a calm lake", "W"), ("at the edge of a waterfall", "LW"), ("in a mangrove swamp", "W"),
    ("on stilts over the ocean", "W"), ("on a beach at sunset", "W"), ("half sunken in a bay", "W"), ("on a river island", "W"),
    ("in a dark forest clearing", "L"), ("on a snowy mountain peak", "LS"), ("in a cherry grove", "L"), ("in a flower forest", "L"),
    ("in the badlands", "L"), ("in a desert oasis", "L"), ("on a windy cliff", "L"), ("in a bamboo jungle", "L"),
    ("in a pale garden", "L"), ("in a birch forest", "L"), ("on a meadow hill", "L"), ("in a savanna", "L"), ("in the jungle canopy", "LS"),
    ("on an ice spikes plain", "L"), ("in a taiga valley", "L"), ("at a crossroads", "L"), ("on the edge of a ravine", "LU"),
    ("floating above a lake", "SW"), ("on a sky island", "S"), ("among the clouds", "S"), ("hanging from chains under a floating rock", "S"),
    ("on top of a giant tree", "S"), ("on a floating rock above the ocean", "SW"),
    ("deep in a lush cave", "U"), ("in a dripstone cave", "U"), ("inside a hollowed-out mountain", "UL"), ("beside an underground lake", "U"),
    ("at the edge of the deep dark", "U"), ("in an amethyst geode", "U"), ("inside an old mineshaft", "U"),
    ("on a Nether lava lake", "N"), ("in a crimson forest", "N"), ("in a warped forest", "N"), ("in a soul sand valley", "N"),
    ("on a basalt delta cliff", "N"), ("on the Nether roof... well, just below it", "N"),
]
TWISTS = [
    "with a garden on the roof", "with a hidden basement", "with a secret passage behind a bookshelf", "lit only by lanterns",
    "built from copper you let oxidize over time", "with a minecart line leading to it", "with a waterfall running through it",
    "with a tiny farm next to it", "with stained glass windows", "overgrown with moss and vines", "with a bell tower",
    "with a cozy fireplace inside", "with a pond full of axolotls", "with a spiral staircase", "made of deepslate and dark oak",
    "made of sandstone and terracotta", "made of quartz and birch", "made of blackstone and gold", "made of prismarine and glass",
    "made of mud bricks and mangrove", "made of cherry wood and white concrete", "made of tuff and copper",
    "with a lookout on top", "with an enchanting room", "with a stable for horses", "with a dock for boats",
    "with a little shop for the wandering trader", "with a cat sleeping on every chest", "with a map room", "with a trophy hall",
    "with item frames showing everything you've found", "with a beacon on top", "half in ruins, but still lived in",
    "with a hidden treasure room", "with a parrot aviary", "with a library loft", "with a waterwheel", "with a rooftop terrace",
    "decorated for a festival", "with a beehive garden", "with a lava moat", "with banners from every friend on the server",
    "with a glass dome", "with a mob head collection", "with an armor stand guard at the door", "with a music disc jukebox room",
    "with a tiny graveyard out back", "with a mushroom garden", "with a sculpture of your favorite mob out front", "with lanterns hanging from chains",
]
# Twists that only fit some places (a dock needs water); the rest fit anywhere.
TWIST_PLACES = {"with a dock for boats": "W", "with a waterwheel": "W", "with a stable for horses": "L",
                "with a lava moat": "LUN", "with a waterfall running through it": "LWSU", "with a pond full of axolotls": "LWU",
                "with a tiny farm next to it": "LW", "with a beehive garden": "LS", "with a minecart line leading to it": "LUN",
                "with a parrot aviary": "LS", "with a mushroom garden": "LUN", "with a tiny graveyard out back": "LN"}
FRAMES = ["How about {b} {p}, {t}?", "Build {b} {p}, {t}!", "Try {b} {p}, {t}.", "What about {b} {p}, {t}?",
          "Next project idea: {b} {p}, {t}.", "Picture this: {b} {p}, {t}."]


def ideas() -> list[str]:
    rng = random.Random(26)
    combos = [(b, p, set(bk) & set(pk)) for b, bk in BUILDINGS for p, pk in PLACES if set(bk) & set(pk)]
    rng.shuffle(combos)
    out, used = [], set()
    twists = TWISTS[:]
    for i, (b, p, kinds) in enumerate(combos):
        if len(out) >= TARGET:
            break
        # The next twist that fits where it stands.
        t = next(x for k in range(len(twists)) for x in [twists[(i + k) % len(twists)]]
                 if kinds & set(TWIST_PLACES.get(x, "LWSUN")))
        if i % len(twists) == len(twists) - 1:
            rng.shuffle(twists)
        frame = FRAMES[i % len(FRAMES)]
        text = frame.format(b=b, p=p, t=t)
        text = text[0].upper() + text[1:]
        if text not in used:
            used.add(text)
            out.append(text)
    return out


def main() -> None:
    data = json.loads(LINES.read_text("utf-8"))
    pool = data["pools"]["build_idea"]
    meta = data.setdefault("_generated", {})
    handwritten = pool[: meta.get(MARKER, len(pool))]
    meta[MARKER] = len(handwritten)
    data["pools"]["build_idea"] = handwritten + [i for i in ideas() if i not in handwritten]
    LINES.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", "utf-8")
    print(f"{len(handwritten)} hand-written and {len(data['pools']['build_idea']) - len(handwritten)} generated build ideas")


if __name__ == "__main__":
    main()

"""Block palettes: blocks that go well together, built around one block or a random one.

Uses each full block's average color (bot/data/block_colors.json, made by mod/tools/scripts/block_colors.py from
the game's textures). A palette is the block itself, four others from dark to light in a similar color, and one
accent: a neutral one next to a colorful block, a warm one next to a gray block. The NiceMerl mod builds palettes
the same way (MerlPalette.java), so keep both in step.
"""
import json
import math
import random
import re
from pathlib import Path

DATA = Path(__file__).resolve().parent / "data" / "block_colors.json"
COLORS = ("white", "light_gray", "gray", "black", "brown", "red", "orange", "yellow", "lime", "green", "cyan",
          "light_blue", "blue", "purple", "magenta", "pink")
WOODS = ("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "bamboo", "crimson", "warped",
         "pale_oak", "poplar")
WEATHER = ("exposed", "weathered", "oxidized")
# Sets that only differ in color or wood: one of each per palette. Other materials (deepslate, copper…) two at most.
ONE_PER_PALETTE = {"concrete", "concrete_powder", "wool", "terracotta", "glazed_terracotta", "stained_glass", "planks",
                   "log", "wood", "stem", "hyphae", "stripped_log", "stripped_wood", "stripped_stem", "stripped_hyphae",
                   "block", "mosaic", "shulker_box"}
SAME_FAMILY = {"concrete_powder": "concrete", "wood": "log", "hyphae": "stem", "stripped_wood": "stripped_log",
               "stripped_hyphae": "stripped_stem"}
# Blocks nobody builds with (or can't get).
NEVER = {"bedrock", "suspicious_sand", "suspicious_gravel", "sponge", "wet_sponge", "magma_block", "end_portal_frame",
         "piston", "sticky_piston", "mycelium", "crimson_nylium", "warped_nylium", "muddy_mangrove_roots"}
# Blocks builders use less (soft, patterned or glowing): only picked when they fit clearly better.
LESS_USED = ("wool", "concrete_powder", "glazed_terracotta", "coral_block", "froglight", "raw_", "mushroom_block")
LESS_USED_PENALTY = 6.0
# How much a different hue counts against a block, compared with a different lightness.
HUE_WEIGHT = 1.6
# Lightness (CIELAB L) the palette spans, and its limits.
SPREAD, DARKEST, LIGHTEST = 36.0, 12.0, 95.0
TOO_SIMILAR = 3.0
NOISE = 6.0


def load(path: Path = DATA) -> dict:
    try:
        return json.loads(path.read_text())
    except (OSError, ValueError):
        return {}


def family(block: str) -> str:
    """'white_concrete' → 'concrete', 'oak_planks' → 'planks', 'exposed_cut_copper' → 'cut_copper'."""
    name = block
    for word in sorted(COLORS + WOODS + WEATHER, key=len, reverse=True):
        name = re.sub(rf"(^|_){word}(?=_|$)", "", name)
    name = name.strip("_") or block
    # Concrete powder is concrete before it sets, and wood is a log with bark all around: the same colors.
    return SAME_FAMILY.get(name, name)


def _distance(x: dict, y: dict) -> float:
    return math.dist(x["lab"], y["lab"])


def palette(colors: dict, base: str, rng: random.Random | None = None, include: tuple[str, ...] = ()) -> list[str]:
    """Six blocks from dark to light that go with base (base and the blocks in include as well), or [] when its colors
    are unknown. Only full building blocks are suggested; any block can be the start or included."""
    rng = rng or random.Random()
    if base not in colors:
        return []
    include = tuple(dict.fromkeys(b for b in include if b in colors and b != base))[:4]
    b = colors[base]
    l0, a0, b0 = b["lab"]
    chroma0 = math.hypot(a0, b0)
    lo, hi = max(DARKEST, l0 - SPREAD), min(LIGHTEST, l0 + SPREAD)
    # Near the darkest or lightest end, the palette reaches further the other way.
    if hi - lo < SPREAD * 1.5:
        lo, hi = (lo, min(LIGHTEST, lo + SPREAD * 1.5)) if lo == DARKEST else (max(DARKEST, hi - SPREAD * 1.5), hi)
    points = [lo + (hi - lo) * i / 4 for i in range(5)]
    # The lightness the start and each included block already cover isn't filled again.
    for l in [l0] + [colors[block]["lab"][0] for block in include]:
        if points:
            points.remove(min(points, key=lambda p: abs(p - l)))

    chosen = [base, *include]
    counts = {}
    for block in chosen:
        counts[family(block)] = counts.get(family(block), 0) + 1

    def allowed(block):
        if block in chosen or block in NEVER or not colors[block].get("pick", True):
            return False
        fam = family(block)
        if counts.get(fam, 0) >= (1 if fam in ONE_PER_PALETTE else 2):
            return False
        return all(_distance(colors[block], colors[c]) >= TOO_SIMILAR for c in chosen)

    def take(block):
        chosen.append(block)
        counts[family(block)] = counts.get(family(block), 0) + 1

    for target in points:
        def score(block):
            c = colors[block]
            l, a, bb = c["lab"]
            return (abs(l - target) + HUE_WEIGHT * math.hypot(a - a0, bb - b0) + 0.15 * abs(c["contrast"] - b["contrast"])
                    + _penalty(block, c) + rng.uniform(0, NOISE))
        options = [k for k in colors if allowed(k)]
        if options:
            take(min(options, key=score))

    def accent_score(block):
        c = colors[block]
        l, a, bb = c["lab"]
        chroma = math.hypot(a, bb)
        near = 0.3 * abs(l - l0) + _penalty(block, c) + rng.uniform(0, NOISE)
        if chroma0 > 12:
            return near + 0.6 * chroma  # a colorful block: a calm, gray-ish accent
        return near + 0.5 * abs(chroma - 28) + (6 if bb < 0 else 0)  # a gray block: a warm, colorful accent
    options = [k for k in colors if allowed(k) and abs(colors[k]["lab"][0] - l0) <= 25]
    if options and len(chosen) < 6:
        take(min(options, key=accent_score))
    return sorted(chosen, key=lambda k: colors[k]["lab"][0])


def _penalty(block: str, c: dict) -> float:
    return (15 if c.get("seeThrough") else 0) + (LESS_USED_PENALTY if any(w in block for w in LESS_USED) else 0)


def random_base(colors: dict, rng: random.Random | None = None) -> str | None:
    """A block that makes a good start for a random palette (not see-through, not too busy or patterned)."""
    rng = rng or random.Random()
    good = [k for k, c in colors.items() if c.get("pick", True) and not c.get("seeThrough") and c["contrast"] < 20
            and k not in NEVER and not any(w in k for w in LESS_USED)]
    return rng.choice(good) if good else None


# Partial blocks and their full block: oak stairs → oak planks, stone brick wall → stone bricks.
PART_SUFFIXES = ("_stairs", "_slab", "_wall", "_fence_gate", "_fence", "_trapdoor", "_door", "_button", "_pressure_plate",
                 "_hanging_sign", "_sign", "_pane", "_carpet")
FULL_SUFFIXES = ("", "s", "_planks", "_block", "_bricks", "_wool")
# Blocks whose English name isn't just their id ("Block of Copper", "Hay Bale"); the rest are their id in title case.
BLOCK_OF = ("iron", "gold", "diamond", "emerald", "redstone", "netherite", "coal", "copper", "quartz", "amethyst",
            "raw_iron", "raw_copper", "raw_gold", "bamboo", "stripped_bamboo", "resin")
NAMES = {"lapis_block": "Block of Lapis Lazuli", "hay_block": "Hay Bale", "jack_o_lantern": "Jack o'Lantern",
         "lapis_ore": "Lapis Lazuli Ore", "deepslate_lapis_ore": "Deepslate Lapis Lazuli Ore",
         "nether_quartz_ore": "Nether Quartz Ore", "short_grass": "Short Grass", "tnt": "TNT",
         "comparator": "Redstone Comparator", "repeater": "Redstone Repeater", "redstone_wire": "Redstone Dust",
         "spawner": "Monster Spawner", "vine": "Vines", "kelp_plant": "Kelp", "cave_vines_plant": "Cave Vines",
         "twisting_vines_plant": "Twisting Vines", "weeping_vines_plant": "Weeping Vines"}
# Small words that stay lower case in names: "Lily of the Valley".
SMALL_WORDS = {"of", "the", "on", "a", "and", "with"}
# Copies of other blocks that block_colors.py leaves out: waxed copper looks like copper, infested stone like stone.
COPY_PREFIXES = ("waxed_", "infested_")
WIKI_API = "https://minecraft.wiki/api.php"
ICON_FILE = "File:Invicon_{}.png"


def name(block: str) -> str:
    """The block's name as players see it: "deepslate_bricks" → "Deepslate Bricks", "copper_block" → "Block of Copper"."""
    if block in NAMES:
        return NAMES[block]
    if block.endswith("_block") and block[: -len("_block")] in BLOCK_OF:
        block = block[: -len("_block")]
        return "Block of " + " ".join(w.capitalize() for w in block.split("_"))
    words = block.split("_")
    return " ".join(w if i and w in SMALL_WORDS else w.capitalize() for i, w in enumerate(words))


def _singular(words: list[str]) -> list[str]:
    return [w[:-1] if len(w) > 3 and w.endswith("s") and not w.endswith("ss") else w for w in words]


def _words(text: str) -> set[str]:
    """ "Block of Copper" and "copper_block" → {"copper", "block"}."""
    return set(_singular(re.findall(r"[a-z0-9]+", text.lower().replace("_", " ")))) - {"of"}


def full_block(colors: dict, block: str) -> str | None:
    """The block whose colors are known for a block id: itself, the original of a copy (waxed copper → copper), or
    the full block of a partial one (oak stairs → oak planks)."""
    for prefix in COPY_PREFIXES:
        if block.startswith(prefix) and block[len(prefix):] in colors:
            return block[len(prefix):]
    if block in colors:
        return block
    for suffix in PART_SUFFIXES:
        if block.endswith(suffix):
            stem = block[: -len(suffix)]
            for full in FULL_SUFFIXES:
                if stem + full in colors:
                    return stem + full
    stem = re.sub(r"_(log|wood|stem|hyphae)$", "", block)
    return next((stem + full for full in FULL_SUFFIXES if stem + full in colors), None)


def by_name(colors: dict, asked: str, exact: bool = False) -> str | None:
    """The full block a name means ("deepslate bricks", "oak stairs", "copper"): exact names first, then partial
    blocks, then the shortest name with all the words. With exact, only a block called exactly that (or a partial
    block of one), so "what goes with diamonds" isn't taken for the Block of Diamond."""
    words = _words(asked)
    if not words:
        return None
    for block in colors:
        if words in (_words(block), _words(name(block))):
            return block
    joined = "_".join(re.findall(r"[a-z0-9]+", asked.lower()))
    if joined.endswith(PART_SUFFIXES) and (found := full_block(colors, joined)):
        return found
    if exact:
        return None
    if found := full_block(colors, joined) or full_block(colors, joined.rstrip("s")):
        return found
    containing = [b for b in colors if words <= _words(b) | _words(name(b))]
    if not containing:
        # Typos: "prismarin", "deepslat brick".
        containing = [b for b in colors if all(any(_close(w, own) for own in _words(b) | _words(name(b))) for w in words)]
    # "gold" means the Block of Gold rather than gold ore: building blocks first, then short names.
    return min(containing, key=lambda b: (not colors[b].get("pick", True), len(name(b)))) if containing else None


def blocks_by_name(colors: dict, asked: str, exact: bool = False) -> list[str]:
    """Every block a list of names means: "prismarine and gold" → prismarine and the Block of Gold. A name that
    isn't a block makes the whole list empty, so "what goes with the castle and the moon" isn't taken for blocks."""
    parts = [p for p in re.split(r",| and | & | plus | with ", asked) if p.strip()]
    found = [by_name(colors, p.strip(), exact) for p in parts]
    return list(dict.fromkeys(found)) if found and all(found) else []


def _close(typed: str, word: str) -> bool:
    """A word typed short or with a small typo: one wrong letter in short words, two in long ones (same as MerlPalette.close)."""
    if typed == word or (len(typed) >= 4 and word.startswith(typed)):
        return True  # also the start of a word: "cobble" for cobblestone
    if len(typed) < 4 or abs(len(typed) - len(word)) > 2:
        return False
    allowed = 1 if len(word) < 7 else 2
    previous = list(range(len(word) + 1))
    for i, a in enumerate(typed, 1):
        current = [i]
        for j, b in enumerate(word, 1):
            current.append(min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + (a != b)))
        previous = current
    return previous[-1] <= allowed


async def icon_urls(session, blocks: list[str], cache: dict) -> dict[str, str | None]:
    """Each block's icon on the Minecraft Wiki ("Invicon_Deepslate_Bricks.png"), asked from the wiki's API so
    redirects count (Prismarine's icon is an animated GIF). None for blocks without one."""
    import aiohttp

    wanted = {ICON_FILE.format(name(b).replace(" ", "_")): b for b in blocks if b not in cache}
    if wanted:
        params = {"action": "query", "titles": "|".join(wanted), "redirects": "1", "prop": "imageinfo", "iiprop": "url",
                  "format": "json", "formatversion": "2"}
        async with session.get(WIKI_API, params=params, timeout=aiohttp.ClientTimeout(total=10)) as resp:
            resp.raise_for_status()
            query = (await resp.json()).get("query", {})
        # Titles as asked → as the wiki writes them → where redirects lead.
        renamed = {n["from"]: n["to"] for n in query.get("normalized", [])}
        redirected = {r["from"]: r["to"] for r in query.get("redirects", [])}
        urls = {p["title"]: (p.get("imageinfo") or [{}])[0].get("url") for p in query.get("pages", [])}
        for title, block in wanted.items():
            title = renamed.get(title, title)
            cache[block] = urls.get(redirected.get(title, title))
    return {b: cache.get(b) for b in blocks}


async def render(session, colors: dict, blocks: list[str], cache: dict | None = None) -> bytes:
    """A strip of the palette's blocks: each block's icon from the Minecraft Wiki over a swatch of its color
    (just the swatch when the icon can't be had)."""
    import io

    import aiohttp
    from PIL import Image, ImageDraw

    cache = cache if cache is not None else {}
    urls = cache.setdefault("urls", {})
    sprites = cache.setdefault("sprites", {})
    try:
        found = await icon_urls(session, blocks, urls)
    except Exception:  # the wiki can't be reached: swatches only
        found = {}
    cell, icon, pad, bar = 120, 96, 16, 14
    width, height = pad + len(blocks) * (cell + pad), pad * 2 + icon + 8 + bar
    image = Image.new("RGBA", (width, height), (43, 45, 49, 255))
    draw = ImageDraw.Draw(image)
    for i, block in enumerate(blocks):
        x = pad + i * (cell + pad)
        color = colors[block]["color"]
        url = found.get(block)
        sprite = sprites.get(url) if url else None
        if url and url not in sprites:
            try:
                async with session.get(url, timeout=aiohttp.ClientTimeout(total=10)) as resp:
                    resp.raise_for_status()
                    # An animated icon (GIF) shows its first frame.
                    sprite = Image.open(io.BytesIO(await resp.read())).convert("RGBA").resize((icon, icon), Image.NEAREST)
            except Exception:  # no icon: the swatch alone
                sprite = None
            sprites[url] = sprite
        if sprite is not None:
            image.alpha_composite(sprite, (x + (cell - icon) // 2, pad))
        else:
            draw.rounded_rectangle([x + (cell - icon) // 2, pad, x + (cell + icon) // 2, pad + icon], radius=8, fill=color)
        draw.rounded_rectangle([x, pad + icon + 8, x + cell, pad + icon + 8 + bar], radius=4, fill=color)
    out = io.BytesIO()
    image.save(out, format="PNG")
    return out.getvalue()


if __name__ == "__main__":
    import sys
    data = load()
    for name in sys.argv[1:] or ["deepslate_bricks", "oak_planks", "white_concrete", "copper_block", "sandstone",
                                  "cherry_planks", "prismarine", "stone", "black_concrete", "moss_block"]:
        print(f"{name:20} → {', '.join(palette(data, name, random.Random(1)))}")
    for seed in range(3):
        base = random_base(data, random.Random(seed))
        print(f"random ({base}) → {', '.join(palette(data, base, random.Random(seed)))}")

#!/usr/bin/env python3
"""Average colors of Minecraft's blocks, for Merl's block palettes.

Reads the block states, models and textures from a Minecraft jar that has the client assets (Loom's merged jar) and
writes each block's average color to bot/data/block_colors.json, which the mod bundles. Full building blocks are
suggested in palettes; everything else (ores, workstations, plants, slabs…) has "pick": false, so a palette can start
from it ("a palette with cactus") but never suggests it. Run it again after a Minecraft update:

    python mod/tools/scripts/block_colors.py ~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.3/minecraft-merged-deobf-26.3.jar

Needs Pillow (pip install pillow).
"""
import io
import json
import math
import sys
import zipfile
from pathlib import Path

from PIL import Image

OUT = Path(__file__).resolve().parents[3] / "bot" / "data" / "block_colors.json"
FACES = ("up", "down", "north", "south", "east", "west")
# Not real blocks you can build with or look at.
TECHNICAL = {"air", "cave_air", "void_air", "light", "barrier", "structure_void", "moving_piston", "piston_head", "jigsaw",
             "structure_block", "test_block", "test_instance_block", "end_gateway", "end_portal", "nether_portal", "fire",
             "soul_fire", "bubble_column", "frosted_ice"}
# Copies of other blocks (infested stone looks exactly like stone): left out, their names lead to the original.
COPIES = ("infested_", "waxed_", "command_block", "petrified")
# Full blocks a palette never suggests: workstations, ores and oddities.
NOT_PICKED = {"furnace", "blast_furnace", "smoker", "crafting_table", "cartography_table", "fletching_table",
              "smithing_table", "loom", "dispenser", "dropper", "observer", "tnt", "jukebox", "note_block", "beehive",
              "bee_nest", "sculk_catalyst", "crafter", "respawn_anchor", "target", "lodestone", "spawner", "trial_spawner",
              "vault", "reinforced_deepslate", "budding_amethyst", "bedrock", "suspicious_sand", "suspicious_gravel",
              "sponge", "wet_sponge", "magma_block", "end_portal_frame", "piston", "sticky_piston", "mycelium",
              "crimson_nylium", "warped_nylium", "muddy_mangrove_roots", "creaking_heart"}
# Biome colors in a plains biome, for blocks colored by the biome (grass, leaves, water…).
TINTS = {"birch_leaves": (0x80, 0xA7, 0x55), "spruce_leaves": (0x61, 0x99, 0x61), "lily_pad": (0x20, 0x80, 0x30),
         "water": (0x3F, 0x76, 0xE4), "water_cauldron": (0x3F, 0x76, 0xE4), "redstone_wire": (0xC0, 0x00, 0x00),
         "attached_melon_stem": (0xE0, 0xC7, 0x1C), "attached_pumpkin_stem": (0xE0, 0xC7, 0x1C),
         "melon_stem": (0xE0, 0xC7, 0x1C), "pumpkin_stem": (0xE0, 0xC7, 0x1C)}
GRASS, FOLIAGE = (0x91, 0xBD, 0x59), (0x77, 0xAB, 0x2F)
GRASS_BLOCKS = {"grass_block", "short_grass", "tall_grass", "fern", "large_fern", "potted_fern", "sugar_cane", "bush"}
FOLIAGE_BLOCKS = {"oak_leaves", "jungle_leaves", "acacia_leaves", "dark_oak_leaves", "mangrove_leaves", "vine"}
# For blocks that aren't cubes: the texture that shows most of them.
MAIN_TEXTURES = ("particle", "side", "all", "texture", "cross", "plant", "flower", "crop", "stem", "top", "end", "front")


def tint_for(block: str):
    """The biome color mixed into a block's texture, or None for blocks with their own colors (cherry leaves…)."""
    if block in TINTS:
        return TINTS[block]
    if block in GRASS_BLOCKS:
        return GRASS
    if block in FOLIAGE_BLOCKS:
        return FOLIAGE
    return None


def main(jar_path: str) -> None:
    jar = zipfile.ZipFile(jar_path)
    names = set(jar.namelist())

    def read_json(path):
        return json.loads(jar.read(path)) if path in names else None

    def model_path(ref):
        ref = ref.split(":", 1)[-1]
        return f"assets/minecraft/models/{ref}.json"

    def resolve(ref):
        """Parent chain (model names) and textures of a block model, the closest model's textures winning."""
        chain, textures, elements, tinted = [], {}, None, False
        while ref:
            model = read_json(model_path(ref))
            if model is None:
                break
            chain.append(ref.split(":", 1)[-1])
            for key, value in model.get("textures", {}).items():
                textures.setdefault(key, value)
            if elements is None and "elements" in model:
                elements = model["elements"]
            ref = model.get("parent")
        for element in elements or []:
            for face in element.get("faces", {}).values():
                tinted |= "tintindex" in face
        return chain, textures, elements, tinted

    def texture(textures, key, depth=0):
        value = textures.get(key)
        while depth < 10:
            if isinstance(value, dict):  # {"sprite": "minecraft:block/stone", …}
                value = value.get("sprite")
            if not (isinstance(value, str) and value.startswith("#")):
                break
            value = textures.get(value[1:])
            depth += 1
        return value if isinstance(value, str) else None

    image_cache = {}

    def stats(ref, tint=None):
        """Mean color (sRGB) and how much of the texture is see-through, of the first animation frame (with the
        biome color mixed in for tinted faces)."""
        if (ref, tint) in image_cache:
            return image_cache[ref, tint]
        path = f"assets/minecraft/textures/{ref.split(':', 1)[-1]}.png"
        if path not in names:
            image_cache[ref, tint] = None
            return None
        img = Image.open(io.BytesIO(jar.read(path))).convert("RGBA")
        img = img.crop((0, 0, img.width, img.width))
        pixels = list(img.get_flattened_data() if hasattr(img, "get_flattened_data") else img.getdata())
        solid = [p for p in pixels if p[3] > 128]
        if tint:
            solid = [(p[0] * tint[0] // 255, p[1] * tint[1] // 255, p[2] * tint[2] // 255, p[3]) for p in solid]
        if not solid:
            image_cache[ref, tint] = None
            return None
        mean = tuple(sum(p[i] for p in solid) / len(solid) for i in range(3))
        lights = [lab(p[:3])[0] for p in solid]
        avg_l = sum(lights) / len(lights)
        contrast = math.sqrt(sum((l - avg_l) ** 2 for l in lights) / len(lights))
        image_cache[ref, tint] = (mean, 1 - len(solid) / len(pixels), contrast)
        return image_cache[ref, tint]

    def model_of(state):
        """The model a block shows most: standing logs rather than lying ones; for fences and walls, their post."""
        variants = state.get("variants")
        if variants:
            first = variants.get("axis=y") or next(iter(variants.values()))
        else:
            first = (state.get("multipart") or [{}])[0].get("apply")
        if isinstance(first, list):
            first = first[0]
        return (first or {}).get("model")

    blocks = {}
    seen_textures = {}
    for path in sorted(n for n in names if n.startswith("assets/minecraft/blockstates/") and n.endswith(".json")):
        block = path.rsplit("/", 1)[1][:-5]
        if block in TECHNICAL or block.startswith(COPIES):
            continue
        ref = model_of(read_json(path))
        if not ref:
            continue
        chain, textures, elements, tinted = resolve(ref)
        tint = tint_for(block)
        # A full block: cubes from corner to corner (grass blocks have a second one for the colored overlay).
        full = bool(elements) and all(e.get("from") == [0, 0, 0] and e.get("to") == [16, 16, 16] for e in elements) \
            and len(elements[0].get("faces", {})) == 6
        if full:
            cube = elements[0]
            faces = [texture({**textures, "_face": cube["faces"][f].get("texture")}, "_face") for f in FACES]
            tints = [tint if "tintindex" in cube["faces"][f] else None for f in FACES]
            # Sides count twice as much as the top and bottom: that's what you see of a wall. A grass block is its top.
            weights = [6, 1, 1, 1, 1, 1] if block in GRASS_BLOCKS else [1, 1, 2, 2, 2, 2]
        else:
            main = next((texture(textures, k) for k in MAIN_TEXTURES if texture(textures, k)), None)
            faces, tints, weights = [main], [tint if tinted or block in TINTS else None], [1]
        if any(f is None for f in faces):
            continue
        found = [stats(f, t) for f, t in zip(faces, tints)]
        if any(x is None for x in found):
            continue
        key = (tuple(faces), tuple(tints), full)
        total = sum(weights)
        rgb = tuple(sum(x[0][i] * w for x, w in zip(found, weights)) / total for i in range(3))
        clear = sum(x[1] * w for x, w in zip(found, weights)) / total
        contrast = sum(x[2] * w for x, w in zip(found, weights)) / total
        # The same look twice (two kinds of the same block): keep the shorter name.
        if key in seen_textures:
            other = seen_textures[key]
            if len(other) <= len(block):
                continue
            del blocks[other]
        seen_textures[key] = block
        l, a, b = lab(rgb)
        picked = full and block not in NOT_PICKED and not block.endswith("_ore")
        blocks[block] = {
            "color": "#%02X%02X%02X" % tuple(round(c) for c in rgb),
            "lab": [round(l, 1), round(a, 1), round(b, 1)],
            "contrast": round(contrast, 1),
            **({"seeThrough": True} if clear > 0.2 else {}),
            **({} if picked else {"pick": False}),
        }

    OUT.write_text(json.dumps(dict(sorted(blocks.items())), indent=1) + "\n")
    print(f"{len(blocks)} blocks written to {OUT}, {sum('pick' not in b for b in blocks.values())} of them suggested in palettes")


def lab(rgb):
    """sRGB (0-255) to CIELAB (D65)."""
    def linear(c):
        c /= 255
        return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4
    r, g, b = (linear(c) for c in rgb)
    x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
    y = 0.2126 * r + 0.7152 * g + 0.0722 * b
    z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883

    def f(t):
        return t ** (1 / 3) if t > 0.008856 else 7.787 * t + 16 / 116
    fx, fy, fz = f(x), f(y), f(z)
    return 116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])

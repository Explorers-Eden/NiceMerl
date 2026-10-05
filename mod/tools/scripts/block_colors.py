#!/usr/bin/env python3
"""Average colors of Minecraft's full blocks, for Merl's block palettes.

Reads the block states, models and textures from a Minecraft jar that has the client assets (Loom's merged jar),
keeps the full cubes (no slabs, stairs, plants or technical blocks) and writes each block's average color to
bot/data/block_colors.json, which the mod bundles. Run it again after a Minecraft update:

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
# Technical blocks and copies of other blocks (infested stone looks exactly like stone).
SKIP_EXACT = {"light", "barrier", "furnace", "blast_furnace", "smoker", "crafting_table", "cartography_table",
              "fletching_table", "smithing_table", "loom", "dispenser", "dropper", "observer", "tnt", "jukebox", "note_block",
              "beehive", "bee_nest", "sculk_catalyst", "crafter", "respawn_anchor", "target", "lodestone", "frosted_ice"}
SKIP = ("command_block", "structure_block", "jigsaw", "test_", "infested_", "waxed_", "spawner",
        "trial_spawner", "vault", "reinforced_deepslate", "budding_amethyst", "petrified")


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

    def stats(ref):
        """Mean color (sRGB) and how much of the texture is see-through, of the first animation frame."""
        if ref in image_cache:
            return image_cache[ref]
        path = f"assets/minecraft/textures/{ref.split(':', 1)[-1]}.png"
        if path not in names:
            image_cache[ref] = None
            return None
        img = Image.open(io.BytesIO(jar.read(path))).convert("RGBA")
        img = img.crop((0, 0, img.width, img.width))
        pixels = list(img.get_flattened_data() if hasattr(img, "get_flattened_data") else img.getdata())
        solid = [p for p in pixels if p[3] > 128]
        if not solid:
            image_cache[ref] = None
            return None
        mean = tuple(sum(p[i] for p in solid) / len(solid) for i in range(3))
        lights = [lab(p[:3])[0] for p in solid]
        avg_l = sum(lights) / len(lights)
        contrast = math.sqrt(sum((l - avg_l) ** 2 for l in lights) / len(lights))
        image_cache[ref] = (mean, 1 - len(solid) / len(pixels), contrast)
        return image_cache[ref]

    blocks = {}
    seen_textures = {}
    for path in sorted(n for n in names if n.startswith("assets/minecraft/blockstates/") and n.endswith(".json")):
        block = path.rsplit("/", 1)[1][:-5]
        # Workstations and ores aren't building blocks.
        if block in SKIP_EXACT or any(s in block for s in SKIP) or block.endswith("_ore"):
            continue
        state = read_json(path)
        variants = state.get("variants")
        if not variants:
            continue  # multipart: fences, walls, panes, redstone
        # Standing logs and pillars rather than lying ones.
        first = variants.get("axis=y") or next(iter(variants.values()))
        if isinstance(first, list):
            first = first[0]
        chain, textures, elements, tinted = resolve(first["model"])
        # Only full blocks: one cube from corner to corner. Biome-colored ones (grass, leaves) have no fixed color.
        if tinted or not elements or len(elements) != 1:
            continue
        cube = elements[0]
        if cube.get("from") != [0, 0, 0] or cube.get("to") != [16, 16, 16] or len(cube.get("faces", {})) != 6:
            continue
        faces = [texture({**textures, "_face": cube["faces"][f].get("texture")}, "_face") for f in FACES]
        if any(f is None for f in faces):
            continue
        key = tuple(faces)
        found = [stats(f) for f in faces]
        if any(s is None for s in found):
            continue
        # Sides count twice as much as the top and bottom: that's what you see of a wall.
        weights = [1, 1, 2, 2, 2, 2]
        total = sum(weights)
        rgb = tuple(sum(s[0][i] * w for s, w in zip(found, weights)) / total for i in range(3))
        clear = sum(s[1] * w for s, w in zip(found, weights)) / total
        contrast = sum(s[2] * w for s, w in zip(found, weights)) / total
        # The same look twice (copper and waxed copper, …): keep the shorter name.
        if key in seen_textures:
            other = seen_textures[key]
            if len(other) <= len(block):
                continue
            del blocks[other]
        seen_textures[key] = block
        l, a, b = lab(rgb)
        blocks[block] = {
            "color": "#%02X%02X%02X" % tuple(round(c) for c in rgb),
            "lab": [round(l, 1), round(a, 1), round(b, 1)],
            "contrast": round(contrast, 1),
            **({"seeThrough": True} if clear > 0.2 else {}),
        }

    OUT.write_text(json.dumps(dict(sorted(blocks.items())), indent=1) + "\n")
    print(f"{len(blocks)} blocks written to {OUT}")


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

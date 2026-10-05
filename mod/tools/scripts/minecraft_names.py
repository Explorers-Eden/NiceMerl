#!/usr/bin/env python3
"""English names of everything in vanilla Minecraft, for Merl's search.

Reads the game's English language file from a Minecraft jar and writes the names of all blocks, items, mobs, biomes,
enchantments and effects to bot/data/minecraft_names.json, which the mod bundles. The search never "corrects" a word
from these names into something else ("minecart" isn't a typo of "minecraft"), and a question that names one ("uses
for iron ingots") gets the Minecraft Wiki page about it. Run it again after a Minecraft update:

    python mod/tools/scripts/minecraft_names.py ~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.3/minecraft-merged-deobf-26.3.jar
"""
import json
import re
import sys
import zipfile
from pathlib import Path

OUT = Path(__file__).resolve().parents[3] / "bot" / "data" / "minecraft_names.json"
KINDS = ("block", "item", "entity", "biome", "enchantment", "effect")


def main(jar_path: str) -> None:
    lang = json.loads(zipfile.ZipFile(jar_path).read("assets/minecraft/lang/en_us.json"))
    names = set()
    for key, value in lang.items():
        parts = key.split(".")
        # "item.minecraft.iron_ingot", not descriptions like "item.minecraft.potion.effect.swiftness".
        if len(parts) == 3 and parts[0] in KINDS and parts[1] == "minecraft" and value and "%" not in value:
            names.add(re.sub(r"\s+", " ", value).strip())
    OUT.write_text(json.dumps(sorted(names), indent=1, ensure_ascii=False) + "\n")
    print(f"{len(names)} names written to {OUT}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])

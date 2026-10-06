"""
Adds quiz questions made from the game's own data to bot/data/trivia.json, so their answers are right by construction:
smelting results, crafting amounts and yields, the fastest tool for a block, mob drops, advancement goals and
enchantment levels. The hand-written questions in the file stay as they are; questions made by this script carry
"generated": true and are replaced on every run.

    python mod/tools/scripts/trivia_questions.py <minecraft jar>

The jar is the merged Minecraft jar Loom downloads (with data/ and assets/minecraft/lang/en_us.json inside).
"""
import json, pathlib, random, re, sys, zipfile
from collections import defaultdict

TRIVIA = pathlib.Path(__file__).resolve().parents[3] / "bot" / "data" / "trivia.json"
rng = random.Random(26)
jar = zipfile.ZipFile(sys.argv[1])
names = set(jar.namelist())
lang = json.loads(jar.read("assets/minecraft/lang/en_us.json"))


def read(path):
    return json.loads(jar.read(path))


def name(item_id):
    """English name of an item or block id ("minecraft:iron_ingot" → "Iron Ingot"), or None."""
    path = item_id.split(":", 1)[-1]
    for kind in ("item", "block"):
        key = f"{kind}.minecraft.{path}"
        if key in lang:
            return lang[key]
    return None


def entity_name(path):
    return lang.get(f"entity.minecraft.{path}")


def item_tag(tag, depth=0):
    """The items in an item tag (#minecraft:x), following nested tags."""
    path = f"data/minecraft/tags/item/{tag.split(':', 1)[-1]}.json"
    if path not in names or depth > 4:
        return []
    out = []
    for value in read(path)["values"]:
        value = value["id"] if isinstance(value, dict) else value
        out += item_tag(value[1:], depth + 1) if value.startswith("#") else [value]
    return out


def single(ingredient):
    """The one item an ingredient means, or None when it allows several."""
    if isinstance(ingredient, list):
        return ingredient[0] if len(ingredient) == 1 else None
    if isinstance(ingredient, dict):
        return ingredient.get("item") or ingredient.get("id")
    if ingredient.startswith("#"):
        items = item_tag(ingredient[1:])
        return items[0] if len(items) == 1 else None
    return ingredient


def wrong_from(pool, right, count=3, avoid=()):
    choices = sorted({p for p in pool if p != right and p not in avoid})
    rng.shuffle(choices)
    return choices[:count]


def numbers_near(right, count=3):
    near = [n for n in range(max(1, right - 4), right + 6) if n != right]
    rng.shuffle(near)
    return [str(n) for n in sorted(near[:count])]


# Things you don't count ("Redstone Dust", "Glass", "Leather"): no plural, no "a".
UNCOUNTED = {"dust", "glass", "wool", "leather", "cream", "meal", "sugar", "honey", "sand", "gravel", "clay", "snow", "wheat",
             "netherrack", "ice", "moss", "scaffolding", "powder", "coal", "charcoal", "bamboo", "kelp", "string", "paper", "lava",
             "water", "milk", "fish", "cod", "salmon", "obsidian", "dirt", "mud", "slime", "redstone", "quartz", "glowstone",
             "terracotta", "concrete", "stone", "cobblestone", "deepslate", "blackstone", "calcite", "tuff", "basalt", "prismarine",
             "purpur", "end stone", "gunpowder", "rabbit", "beef", "porkchop", "chicken", "mutton", "seagrass", "copper", "iron",
             "gold", "lapis lazuli", "flint", "bread", "stew", "soup", "pie", "cake", "sulfur"}


def uncounted(noun):
    last = noun.lower().split()[-1]
    return last in UNCOUNTED or noun.lower() in UNCOUNTED


def plural(noun):
    """"Diamond" → "Diamonds", "Bamboo Door" → "Bamboo Doors", "Redstone Dust" stays."""
    if uncounted(noun):
        return noun
    if re.search(r"(shelf|leaf|loaf)$", noun, re.I):
        return noun[:-1] + "ves"
    if re.search(r"(s|x|ch|sh)$", noun):
        return noun if noun.endswith("s") else noun + "es"
    if re.search(r"[^aeiou]y$", noun):
        return noun[:-1] + "ies"
    return noun + "s"


def a(noun):
    if uncounted(noun):
        return noun
    return ("an " if noun[:1].lower() in "aeiou" else "a ") + noun


def related_names(*things):
    """Item names that share a word with the given ones, as tricky wrong answers ("Iron Ore" → "Iron Nugget")."""
    words = {w.lower() for t in things for w in t.split() if len(w) > 3}
    return [n for k, n in lang.items() if re.fullmatch(r"(item|block)\.minecraft\.[a-z_]+", k)
            and words & {w.lower() for w in n.split()}]


questions = []
VARIANTS = (r"^(white|orange|magenta|light_blue|yellow|lime|pink|gray|light_gray|cyan|purple|blue|brown|green|red|black|"
            r"oak|spruce|birch|jungle|acacia|dark_oak|mangrove|cherry|pale_oak|bamboo|crimson|warped|poplar|"
            r"exposed|weathered|oxidized|waxed|stripped)_")


def family(item_id):
    """"minecraft:jungle_trapdoor" → "trapdoor", so the 12 wood kinds don't each get the same question."""
    path = item_id.split(":", 1)[-1]
    while re.match(VARIANTS, path):
        path = re.sub(VARIANTS, "", path, count=1)
    return path


def few_per_family(entries, key, most=2):
    seen = defaultdict(int)
    out = []
    for entry in entries:
        f = family(key(entry))
        if seen[f] < most:
            seen[f] += 1
            out.append(entry)
    return out


def add(q, right, wrong, why):
    if len(wrong) >= 2 and right not in wrong:
        questions.append({"q": q, "right": right, "wrong": wrong[:3], "why": why, "generated": True})


recipes = {p: read(p) for p in names if p.startswith("data/minecraft/recipe/") and p.endswith(".json")}
by_result = defaultdict(list)
for r in recipes.values():
    result = r.get("result")
    if isinstance(result, dict) and "id" in result:
        by_result[result["id"]].append(r)

# Smelting: "What do you get when you smelt Raw Iron?"
smelts = {}
for r in recipes.values():
    if r.get("type") == "minecraft:smelting":
        source = single(r["ingredient"])
        if source and name(source) and name(r["result"]["id"]):
            smelts.setdefault(source, set()).add(r["result"]["id"])
outputs = [name(o) for outs in smelts.values() for o in outs]
for source, outs in sorted(smelts.items()):
    if len(outs) != 1:
        continue
    out = name(next(iter(outs)))
    tricky = wrong_from(related_names(name(source), out), out, 2, avoid={name(source)})
    add(f"What do you get when you smelt {name(source)}?", out, tricky + wrong_from(outputs, out, 3 - len(tricky), avoid=set(tricky)),
        f"Smelting {name(source)} in a furnace gives {out}.")

# Crafting amounts and yields, only for items with exactly one recipe.
crafted = []
for result, rs in sorted(by_result.items()):
    if len(rs) != 1 or not name(result):
        continue
    r = rs[0]
    counts = defaultdict(int)
    if r.get("type") == "minecraft:crafting_shaped":
        for key, ingredient in r["key"].items():
            item = single(ingredient)
            if item is None:
                counts = None
                break
            counts[item] += sum(row.count(key) for row in r["pattern"])
    elif r.get("type") == "minecraft:crafting_shapeless":
        for ingredient in r["ingredients"]:
            item = single(ingredient)
            if item is None:
                counts = None
                break
            counts[item] += 1
    else:
        continue
    if not counts or any(not name(i) for i in counts):
        continue
    crafted.append((result, r["result"].get("count", 1), counts))
rng.shuffle(crafted)
for result, made, counts in few_per_family(crafted, lambda c: c[0]):
    item, need = max(counts.items(), key=lambda kv: kv[1])
    # "1 Candle for an Orange Candle" is too easy.
    if need == 1:
        continue
    what = a(name(result)) if made == 1 else f"{made} {plural(name(result))}"
    parts = ", ".join(f"{n} {name(i) if n == 1 else plural(name(i))}" for i, n in counts.items())
    unit = f"How much {name(item)}" if uncounted(name(item)) else f"How many {plural(name(item))}"
    add(f"{unit} do you need to craft {what}?", str(need), numbers_near(need),
        f"The recipe for {name(result)} uses {parts}.")
yields = [c for c in crafted if c[1] > 1]
for result, made, counts in few_per_family(yields, lambda c: c[0]):
    choices = [n for n in (1, 2, 3, 4, 6, 8, 9, 12, 16, 24, 32) if n != made]
    rng.shuffle(choices)
    if uncounted(name(result)):
        continue
    add(f"How many {plural(name(result))} do you get from one craft?", str(made), [str(n) for n in sorted(choices[:3])],
        f"One craft makes {made} {plural(name(result))}.")

# The fastest tool: "Which tool mines Bookshelf fastest?"
tools = {"pickaxe": "Pickaxe", "axe": "Axe", "shovel": "Shovel", "hoe": "Hoe"}
mined = defaultdict(set)
for tool in tools:
    for value in read(f"data/minecraft/tags/block/mineable/{tool}.json")["values"]:
        if isinstance(value, str) and not value.startswith("#"):
            mined[value].add(tool)
groups = {}
for block, ts in sorted(mined.items()):
    if len(ts) != 1 or not name(block):
        continue
    # One block per family (all the colored concretes are one question).
    family = re.sub(r"^(white|orange|magenta|light_blue|yellow|lime|pink|gray|light_gray|cyan|purple|blue|brown|green|red|black|"
                    r"oak|spruce|birch|jungle|acacia|dark_oak|mangrove|cherry|pale_oak|bamboo|crimson|warped|"
                    r"exposed|weathered|oxidized|waxed)_", "", block.split(":")[1])
    family = family.split("_")[-1]
    groups.setdefault(family, []).append((block, next(iter(ts))))
picks = [rng.choice(v) for v in groups.values()]
rng.shuffle(picks)
for block, tool in picks:
    add(f"Which tool breaks {name(block)} the fastest?", tools[tool], [t for k, t in tools.items() if k != tool],
        f"{name(block)} is mined fastest with {a(tools[tool].lower())}.")

# Mob drops: "Which mob drops Blaze Rod?"
drops = defaultdict(set)


def items_in(node, out):
    if isinstance(node, dict):
        if node.get("type") == "minecraft:item" and "name" in node:
            out.add(node["name"])
        for v in node.values():
            items_in(v, out)
    elif isinstance(node, list):
        for v in node:
            items_in(v, out)


mobs = {}
for path in names:
    m = re.fullmatch(r"data/minecraft/loot_table/entities/([a-z_]+)\.json", path)
    if not m or not entity_name(m.group(1)):
        continue
    found = set()
    items_in(read(path), found)
    found = {i for i in found if name(i)}
    if found:
        mobs[m.group(1)] = found
        for i in found:
            drops[i].add(m.group(1))
mob_names = [entity_name(m) for m in mobs]
for item, who in sorted(drops.items()):
    if len(who) != 1:
        continue
    mob = next(iter(who))
    if entity_name(mob).lower() in name(item).lower() or name(item).lower() in entity_name(mob).lower():
        continue
    add(f"Which mob drops {name(item)}?", entity_name(mob), wrong_from(mob_names, entity_name(mob)),
        f"{name(item)} is a drop of the {entity_name(mob).lower()}.")
for mob, items in sorted(mobs.items()):
    own = sorted(i for i in items if len(drops[i]) <= 2)
    if not own:
        continue
    own = [i for i in own if entity_name(mob).lower() not in name(i).lower()]
    if not own:
        continue
    item = rng.choice(own)
    others = [name(i) for i in drops if mob not in drops[i]]
    add(f"Which of these can {a(entity_name(mob)).split(' ', 1)[0]} {entity_name(mob)} drop?", name(item),
        wrong_from(others, name(item)), f"The {entity_name(mob).lower()} can drop {name(item)}.")

# Advancements: "Which advancement asks you to “Kill a mob”?"
advancements = []
for key, title in lang.items():
    m = re.fullmatch(r"advancements\.(\w+)\.(\w+)\.title", key)
    if m and m.group(2) != "root" and f"advancements.{m.group(1)}.{m.group(2)}.description" in lang:
        advancements.append((m.group(1), title, " ".join(lang[f"advancements.{m.group(1)}.{m.group(2)}.description"].split())))
titles = defaultdict(list)
for tab, title, _ in advancements:
    titles[tab].append(title)
for tab, title, description in advancements:
    description = description.rstrip(".")
    add(f"Which advancement asks you to: “{description}”?", title, wrong_from(titles[tab], title),
        f"“{title}”: {description}.")

# The other way round: "What do you need to do for “Diamonds!”?"
goals = defaultdict(list)
for tab, _, description in advancements:
    goals[tab].append(description.rstrip("."))
for tab, title, description in advancements:
    description = description.rstrip(".")
    if len(description) > 70:
        continue
    add(f"What do you need to do for the advancement \u201c{title}\u201d?", description, wrong_from(goals[tab], description),
        f"\u201c{title}\u201d: {description}.")


def biome_tag(tag, depth=0):
    path = f"data/minecraft/tags/worldgen/biome/{tag.split(':', 1)[-1]}.json"
    if path not in names or depth > 4:
        return []
    out = []
    for value in read(path)["values"]:
        value = value["id"] if isinstance(value, dict) else value
        out += biome_tag(value[1:], depth + 1) if value.startswith("#") else [value]
    return out


# Biomes: "In which dimension do you find the Basalt Deltas?"
dimensions = {"is_overworld": "The Overworld", "is_nether": "The Nether", "is_end": "The End"}
where = defaultdict(set)
for tag, dimension in dimensions.items():
    for biome in biome_tag(tag):
        where[biome].add(dimension)
for biome, found in sorted(where.items()):
    title = lang.get("biome.minecraft." + biome.split(":", 1)[-1])
    if len(found) != 1 or not title:
        continue
    dimension = next(iter(found))
    the = "" if title.startswith("The ") else "the "
    add(f"In which dimension do you find {the}{title} biome?", dimension, [d for d in dimensions.values() if d != dimension],
        f"{title} is in {dimension.replace('The ', 'the ', 1)}.")

# Enchantments: "What is the highest level of Sharpness?"
roman = ["I", "II", "III", "IV", "V"]
for path in sorted(p for p in names if re.fullmatch(r"data/minecraft/enchantment/[a-z_]+\.json", p)):
    e = read(path)
    title = lang.get(e.get("description", {}).get("translate", ""))
    level = e.get("max_level")
    if not title or not level or level > 5:
        continue
    add(f"What is the highest level of {title}?", roman[level - 1], [r for r in roman if r != roman[level - 1]][:3] if level > 3
        else wrong_from([r for r in roman if r != roman[level - 1]], roman[level - 1]),
        f"{title} goes up to level {roman[level - 1]}.")

data = json.loads(TRIVIA.read_text())
hand = [q for q in data["questions"] if not q.get("generated")]
seen = {q["q"] for q in hand}
generated = [q for q in questions if q["q"] not in seen and not seen.add(q["q"])]
data["questions"] = hand + generated
TRIVIA.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
print(f"{len(hand)} hand-written + {len(generated)} generated = {len(data['questions'])} questions")

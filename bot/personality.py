"""Merl's lines and small talk, loaded from data/lines.json (shared with the mod)."""

import json
import random
import re
import unicodedata
from datetime import datetime
from pathlib import Path

LINES = json.loads((Path(__file__).parent / "data" / "lines.json").read_text("utf-8"))
POOLS: dict[str, list[str]] = LINES["pools"]
INTENTS = [(re.compile(f"(?:{i['pattern']})"), i["pool"]) for i in LINES["intents"]]
# Roughly one answer in this many gets a little aside from Merl or Peanut Butter.
ASIDE_CHANCE = 12

_bags: dict[str, list[str]] = {}
_last: dict[str, str] = {}


def pick(pool: str, **values: str) -> str:
    """A random line from the pool, never the same one twice in a row, with {placeholders} filled in."""
    lines = POOLS[pool]
    bag = _bags.get(pool)
    if not bag:
        bag = random.sample(lines, len(lines))
        if len(bag) > 1 and bag[-1] == _last.get(pool):
            bag[0], bag[-1] = bag[-1], bag[0]
        _bags[pool] = bag
    line = bag.pop()
    _last[pool] = line
    for key, value in values.items():
        line = line.replace("{" + key + "}", value)
    return line


def greeting_pool(now: datetime) -> str:
    hour = now.hour
    if 5 <= hour < 12:
        return "greeting_morning"
    if 12 <= hour < 18:
        return "greeting_afternoon"
    if 18 <= hour < 23:
        return "greeting_evening"
    return "greeting_night"


def normalize(text: str) -> str:
    text = unicodedata.normalize("NFKD", text)
    text = "".join(c for c in text if not unicodedata.combining(c)).lower().replace("'", "")
    return " ".join(re.findall(r"[a-z0-9]+", text))


def small_talk(text: str) -> str | None:
    """The pool to answer from when the whole message is small talk ("thanks merl!"), else None.
    "greeting" means the caller picks the greeting for the time of day."""
    normalized = normalize(text)
    if not normalized:
        return None
    for pattern, pool in INTENTS:
        if pattern.fullmatch(normalized):
            return pool
    return None


def aside() -> str | None:
    return pick("asides") if random.randrange(ASIDE_CHANCE) == 0 else None

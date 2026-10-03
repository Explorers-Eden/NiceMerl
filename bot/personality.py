"""Merl's lines and small talk, loaded from data/lines.json (shared with the mod).

Mirrored in the mod's MerlLines.java, so changes to how replies are put together go in both places.
"""

import json
import random
import re
import unicodedata
from datetime import date, datetime
from pathlib import Path

from search import tokenize

LINES = json.loads((Path(__file__).parent / "data" / "lines.json").read_text("utf-8"))
POOLS: dict[str, list[str]] = LINES["pools"]
INTENTS = [(re.compile(f"(?:{i['pattern']})"), i["pool"]) for i in LINES["intents"]]
MOODS: list[str] = LINES["moods"]
PB_MOODS: list[str] = LINES["pb_moods"]
TOPICS: dict[str, list[str]] = LINES["topics"]

# Roughly one answer in this many gets a little aside from Merl or Peanut Butter.
ASIDE_CHANCE = 12
# One in this many: Peanut Butter walks over the keyboard, or interrupts (more often when she's playful).
PB_KEYBOARD_CHANCE = 60
PB_INTERRUPT_CHANCE = 40
PB_INTERRUPT_CHANCE_PLAYFUL = 12
# One in this many headlines gets a typo that Merl then fixes.
SLIP_CHANCE = 80
OPENER_CHANCE = 4
CLOSER_CHANCE = 4
SLEEPY_CHANCE = 3
# After this small talk, Merl sometimes asks what you're up to.
ASK_BACK_POOLS = {"how_are_you", "bored", "greeting", "idea"}
ASK_BACK_CHANCE = 3

# Small talk that can start a question ("thanks! how do I…"), and the short line it gets.
PREFIX_POOLS = {"greeting": "greeting_prefix", "thanks": "thanks_prefix", "sorry": "sorry_prefix",
                "ok": "ok_prefix", "compliment": "compliment_prefix"}
QUESTION_WORDS = {"how", "what", "where", "why", "when", "which", "who", "can", "is", "does", "do", "are",
                  "should", "could", "will", "whats", "wheres", "hows", "whos", "whys"}
FOLLOW_UP_CUES = ("and ", "also ", "what about ", "how about ", "but what about ", "and what about ")
# Words that can come between small talk and the question ("ok so what is…").
FILLER_WORDS = {"so", "and", "um", "uh", "btw", "but", "also", "like", "quick", "question"}
ASKING_WORDS = {"how", "what", "where", "why", "when", "which", "who", "whats", "wheres", "hows"}
STRESS_WORDS = {"help", "stuck", "urgent", "asap", "broken", "lost", "cant", "confused", "sos", "desperate", "panic"}
WORD = re.compile(r"[A-Za-z]{6,}")

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


def chance(n: int) -> bool:
    return random.randrange(n) == 0


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


def _intent(normalized: str) -> str | None:
    for pattern, pool in INTENTS:
        if pattern.fullmatch(normalized):
            return pool
    return None


def small_talk(text: str) -> str | None:
    """The pool to answer from when the whole message is small talk ("thanks merl!"), else None.
    "greeting" means the caller picks the greeting for the time of day."""
    normalized = normalize(text)
    return _intent(normalized) if normalized else None


def looks_like_question(rest: str, original: str) -> bool:
    tokens = tokenize(rest)
    if not tokens:
        return False
    first = next((w for w in rest.split() if w not in FILLER_WORDS), "")
    return len(tokens) >= 2 or "?" in original or first in QUESTION_WORDS


def split_small_talk(text: str) -> tuple[str | None, str]:
    """For "thanks merl! how do I get a boss key": ("thanks_prefix", "how do i get a boss key").
    (None, text) when the message doesn't start with small talk followed by a question."""
    words = normalize(text).split()
    for k in range(len(words) - 1, 0, -1):
        pool = _intent(" ".join(words[:k]))
        if pool is None:
            continue
        rest = " ".join(words[k:])
        if pool in PREFIX_POOLS and looks_like_question(rest, text):
            return PREFIX_POOLS[pool], rest
        break
    return None, text


def is_follow_up(text: str) -> bool:
    """ "and in the nether?", "what about the boss one" """
    normalized = normalize(text) + " "
    return any(normalized.startswith(cue) for cue in FOLLOW_UP_CUES)


def energy(text: str) -> str:
    """ "excited" (CAPS, "!!"), "stressed" ("help, I'm stuck"), "terse" (one or two words) or "normal". """
    letters = [c for c in text if c.isalpha()]
    if (len(letters) >= 4 and sum(c.isupper() for c in letters) >= 0.7 * len(letters)) or "!!" in text:
        return "excited"
    words = normalize(text).split()
    if STRESS_WORDS.intersection(words):
        return "stressed"
    if len(words) <= 2:
        return "terse"
    return "normal"


def topic(text: str) -> str | None:
    """What someone says they're up to ("just building my base" -> "build"), after Merl asked."""
    normalized = normalize(text)
    if not normalized or "?" in text or ASKING_WORDS.intersection(normalized.split()) or len(normalized.split()) > 8:
        return None
    padded = f" {normalized} "
    for name, words in TOPICS.items():
        if any(f" {w} " in padded for w in words):
            return name
    return None


def _epoch_day(day: date) -> int:
    return (day - date(1970, 1, 1)).days


def mood(day: date) -> str:
    """Merl's mood of the day, the same in the bot and the mod."""
    return MOODS[_epoch_day(day) % len(MOODS)]


def pb_mood(day: date) -> str:
    return PB_MOODS[(_epoch_day(day) * 7 + 3) % len(PB_MOODS)]


def slip(text: str) -> str:
    """Now and then, swap two letters in a longer word and have Merl fix it."""
    if not chance(SLIP_CHANCE):
        return text
    candidates = list(WORD.finditer(text))
    if not candidates:
        return text
    m = random.choice(candidates)
    word = m.group()
    i = random.randrange(1, len(word) - 2)
    typo = word[:i] + word[i + 1] + word[i] + word[i + 2:]
    if typo == word:
        return text
    return f"{text[:m.start()]}{typo}{text[m.end():]} {pick('slip_fix', word=word)}"


def headline(core: str, *, prefix: str | None = None, energy: str = "normal", hour: int = 12,
             user: str = "", slip_ok: bool = True) -> str:
    """Puts an answer's headline together: small-talk prefix, an opener that fits the
    person's energy or the time of day, the line itself, sometimes a closer."""
    parts = [pick(prefix, user=user)] if prefix else []
    if energy == "terse":
        return " ".join(parts + [core])
    opener = None
    if energy == "excited":
        opener = pick("excited_opener")
    elif energy == "stressed":
        opener = pick("calm_opener")
    elif not prefix and (hour >= 23 or hour < 5) and chance(SLEEPY_CHANCE):
        opener = pick("sleepy_opener")
    elif not prefix and chance(OPENER_CHANCE):
        opener = pick("opener")
    if opener:
        parts.append(opener)
    parts.append(core)
    if not opener and chance(CLOSER_CHANCE):
        parts.append(pick("closer"))
    text = " ".join(parts)
    if energy == "excited" and text.endswith("!") and chance(2):
        text += "!"
    return slip(text) if slip_ok else text


def aside(now: datetime, energy: str = "normal") -> str | None:
    """Usually None; now and then a little aside from Merl or Peanut Butter, colored by today's mood."""
    if energy == "terse":
        return None
    if chance(PB_KEYBOARD_CHANCE):
        return pick("pb_keyboard")
    if chance(PB_INTERRUPT_CHANCE_PLAYFUL if pb_mood(now.date()) == "playful" else PB_INTERRUPT_CHANCE):
        return pick("pb_interrupt")
    if not chance(ASIDE_CHANCE):
        return None
    if 5 <= now.hour < 10 and chance(2):
        return pick("morning_aside")
    return pick(random.choice(("asides", f"asides_{mood(now.date())}")))


def peanut_butter(now: datetime) -> str:
    """An answer about Peanut Butter, often about how she's doing today."""
    return pick(f"pb_{pb_mood(now.date())}") if chance(2) else pick("peanut_butter")


def status(now: datetime) -> str:
    return pick(f"status_{mood(now.date())}") if chance(4) else pick("status")


def ask_back(pool: str) -> str | None:
    """After some small talk, sometimes a question back ("What are you up to today?")."""
    return pick("ask_back") if pool in ASK_BACK_POOLS and chance(ASK_BACK_CHANCE) else None


def pet_milestone(count: int) -> bool:
    return count in (10, 50, 100, 250, 500) or (count > 0 and count % 1000 == 0)

"""Merl's lines and small talk, loaded from data/lines.json (shared with the mod).

Mirrored in the mod's MerlLines.java, so changes to how replies are put together go in both places.
"""

import json
import random
import re
import unicodedata
from datetime import date, datetime
from pathlib import Path

from search import edit_distance, tokenize

LINES = json.loads((Path(__file__).parent / "data" / "lines.json").read_text("utf-8"))
POOLS: dict[str, list[str]] = LINES["pools"]
INTENTS = [(re.compile(f"(?:{i['pattern']})"), i["pool"]) for i in LINES["intents"]]
MOODS: list[str] = LINES["moods"]
PB_MOODS: list[str] = LINES["pb_moods"]
TOPICS: dict[str, list[str]] = LINES["topics"]
KEYWORDS = LINES["keyword_intents"]
STRIP_START = sorted(KEYWORDS["strip_start"], key=len, reverse=True)
STRIP_END = sorted(KEYWORDS["strip_end"], key=len, reverse=True)
# (pool, trigger phrases, every word allowed next to them)
KEYWORD_INTENTS = [
    (i["pool"], i["triggers"],
     set(KEYWORDS["filler"]) | set(i["words"]) | {w for t in i["triggers"] for w in t.split()})
    for i in KEYWORDS["intents"]
]
# Words small talk is made of, for fixing typos ("thnaks" -> "thanks").
SMALL_TALK_WORDS = sorted(
    {w for _, _, allowed in KEYWORD_INTENTS for w in allowed}
    | {w for i in LINES["intents"] for w in re.findall(r"[a-z]{4,}", i["pattern"])})
LONG_RUN = re.compile(r"([a-z])\1{2,}")
FEELINGS = LINES["feelings"]
FEELING_ORDER: list[str] = FEELINGS["order"]
# (phrase, feeling), longest first so "not bad" wins over "bad"; ties keep the order in lines.json.
FEELING_PHRASES = sorted(((phrase, name) for name in FEELING_ORDER for phrase in FEELINGS["phrases"][name]),
                         key=lambda p: -len(p[0].split()))
FEELING_ASK_BACK = sorted(FEELINGS["ask_back"], key=len, reverse=True)
FEELING_MAX_WORDS = 10

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
ASK_BACK_POOLS = {"how_are_you", "bored", "greeting", "idea", "what_doing", "feeling_good", "feeling_meh"}
ASK_BACK_CHANCE = 3
# After this small talk, Merl sometimes asks how you are, then listens for "good, you?".
ASK_FEELING_POOLS = {"how_are_you", "greeting"}
ASK_FEELING_CHANCE = 2

# Small talk that can start a question ("thanks! how do I…"), and the short line it gets.
PREFIX_POOLS = {"greeting": "greeting_prefix", "thanks": "thanks_prefix", "sorry": "sorry_prefix",
                "ok": "ok_prefix", "no": "ok_prefix", "compliment": "compliment_prefix"}
QUESTION_WORDS = {"how", "what", "where", "why", "when", "which", "who", "can", "is", "does", "do", "are",
                  "should", "could", "will", "whats", "wheres", "hows", "whos", "whys"}
FOLLOW_UP_CUES = ("and ", "also ", "what about ", "how about ", "but what about ", "and what about ")
# Words that can come between small talk and the question ("ok so what is…").
FILLER_WORDS = {"so", "and", "um", "uh", "btw", "but", "also", "like", "quick", "question"}
ASKING_WORDS = {"how", "what", "where", "why", "when", "which", "who", "whats", "wheres", "hows"}
STRESS_WORDS = {"help", "stuck", "urgent", "asap", "broken", "lost", "cant", "confused", "sos", "desperate", "panic"}
WORD = re.compile(r"[A-Za-z]{6,}")
FIRST_WORD = re.compile(r"[^A-Za-z]*([A-Za-z]+)")
REPEATS = re.compile(r"(.)\1+")
# How far down the bag pick() looks for a line that starts differently from the last one.
START_LOOKAHEAD = 6


def start(line: str) -> str:
    """The first word, lowercased with doubled letters squashed, so "Ooh" and "Oh" count as the same start."""
    m = FIRST_WORD.match(line)
    return REPEATS.sub(r"\1", m.group(1).lower()) if m else ""


# Line starts that are just a sound or a filler word; an opener in front of one sounds doubled ("Ooh! Oh, found it!").
INTERJECTIONS = {start(w) for w in ("oh", "ah", "aha", "hmm", "hm", "hehe", "haha", "yay", "okay", "ok", "okie",
                                    "alright", "right", "well", "so", "wow", "oops", "aww", "hey", "yes", "yep",
                                    "mhm", "mm", "eh", "teehee", "whoa", "woohoo", "woo", "yippee", "hooray", "ta")}

_bags: dict[str, list[str]] = {}
_last: dict[str, str] = {}
_last_start = ""


def pick(pool: str, **values: str) -> str:
    """A random line from the pool, never the same one twice in a row, with {placeholders} filled in.
    It also avoids starting the same way as the line Merl said just before, from any pool."""
    global _last_start
    lines = POOLS[pool]
    bag = _bags.get(pool)
    if not bag:
        bag = random.sample(lines, len(lines))
        if len(bag) > 1 and bag[-1] == _last.get(pool):
            bag[0], bag[-1] = bag[-1], bag[0]
        _bags[pool] = bag
    if len(bag) > 1 and start(bag[-1]) == _last_start:
        for i in range(len(bag) - 2, max(len(bag) - 1 - START_LOOKAHEAD, 0) - 1, -1):
            if start(bag[i]) != _last_start:
                bag[i], bag[-1] = bag[-1], bag[i]
                break
    line = bag.pop()
    _last[pool] = line
    _last_start = start(line)
    for key, value in values.items():
        line = line.replace("{" + key + "}", value)
    return line


def moody(pool: str, day: date, **values: str) -> str:
    """Like pick, but half the time from the pool's twin for today's mood (how_are_you_cozy), if it has one."""
    twin = f"{pool}_{mood(day)}"
    return pick(twin if twin in POOLS and chance(2) else pool, **values)


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
    "greeting" means the caller picks the greeting for the time of day.
    Tries the exact patterns first, then forgiving versions of the message (stretched letters,
    typos, "can you … please" removed), then keyword intents ("im bored gimme ideas")."""
    normalized = normalize(text)
    if not normalized:
        return None
    variants = _variants(normalized)
    for variant in variants:
        if pool := _intent(variant):
            return pool
    for variant in variants:
        if pool := _keyword_intent(variant):
            return pool
    return None


def _variants(normalized: str) -> list[str]:
    """ "thaaanks merl lol" -> also "thanks merl lol", "thanks"."""
    out = [normalized]
    for collapsed in (LONG_RUN.sub(r"\1", normalized), LONG_RUN.sub(r"\1\1", normalized)):
        fixed = " ".join(_fix_typo(w) for w in collapsed.split())
        for v in (collapsed, fixed, _strip(fixed)):
            if v and v not in out:
                out.append(v)
    return out


def _fix_typo(word: str) -> str:
    """A word one typo away from a small-talk word becomes that word."""
    if len(word) < 3 or word in SMALL_TALK_WORDS:
        return word
    for known in SMALL_TALK_WORDS:
        if len(known) >= 4 and known[0] == word[0] and edit_distance(word, known, 1) <= 1:
            return known
    return word


def _strip(normalized: str) -> str:
    """Removes "can you", "please", "merl", "lol"… from the start and end."""
    text = normalized
    changed = True
    while changed and text:
        changed = False
        for phrase in STRIP_START:
            if text.startswith(phrase + " "):
                text, changed = text[len(phrase) + 1:], True
        for phrase in STRIP_END:
            if text.endswith(" " + phrase):
                text, changed = text[:-len(phrase) - 1], True
    return text


def _keyword_intent(normalized: str) -> str | None:
    padded = f" {normalized} "
    words = normalized.split()
    for pool, triggers, allowed in KEYWORD_INTENTS:
        if any(f" {t} " in padded for t in triggers) and all(w in allowed for w in words):
            return pool
    return None


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


def feeling(text: str) -> tuple[str, bool] | None:
    """How someone says they're doing, after Merl asked: "pretty good, you?" -> ("feeling_good", True),
    the second part saying whether they asked back. None when it doesn't sound like an answer."""
    padded = f" {normalize(text)} ".replace(" thank you ", " thanks ").replace(" thank u ", " thanks ")
    asked_back = False
    for phrase in FEELING_ASK_BACK:
        if f" {phrase} " in padded:
            padded, asked_back = padded.replace(f" {phrase} ", " "), True
    words = padded.split()
    if not words or len(words) > FEELING_MAX_WORDS or ASKING_WORDS.intersection(words):
        return None
    for phrase, name in FEELING_PHRASES:
        if f" {phrase} " in padded:
            return f"feeling_{name}", asked_back
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
    person's energy or the time of day, the line itself, sometimes a closer. No casual opener in front
    of a line that already starts with "Oh"/"Hmm", and no closer after one that ends in ":" or "?"."""
    parts = [pick(prefix, user=user)] if prefix else []
    if energy == "terse":
        return " ".join(parts + [core])
    casual = not prefix and start(core) not in INTERJECTIONS
    opener = None
    if energy == "excited":
        opener = pick("excited_opener")
    elif energy == "stressed":
        opener = pick("calm_opener")
    elif casual and (hour >= 23 or hour < 5) and chance(SLEEPY_CHANCE):
        opener = pick("sleepy_opener")
    elif casual and chance(OPENER_CHANCE):
        opener = pick("opener")
    if opener:
        parts.append(opener)
    parts.append(core)
    if not opener and not core.rstrip().endswith((":", "?")) and chance(CLOSER_CHANCE):
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


def ask_feeling(pool: str, line: str) -> str | None:
    """After "how are you?" or a hello, sometimes "And how are you?", unless Merl's line already asks something."""
    if pool in ASK_FEELING_POOLS and not line.rstrip().endswith("?") and chance(ASK_FEELING_CHANCE):
        return pick("ask_feeling")
    return None


def pet_milestone(count: int) -> bool:
    return count in (10, 50, 100, 250, 500) or (count > 0 and count % 1000 == 0)

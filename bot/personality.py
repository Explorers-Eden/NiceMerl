"""Merl's lines and small talk, loaded from data/lines.json (shared with the mod).

Mirrored in the mod's MerlLines.java, so changes to how replies are put together go in both places.
"""

import json
import random
import re
from dataclasses import dataclass
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
TOPIC_PHRASES: dict[str, str] = LINES["topic_phrases"]
OFTEN_PHRASES: list[str] = LINES["often_phrases"]
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

# Friendship: after this many chats Merl greets you like an old friend now and then.
FRIEND_CHATS = 50
FRIEND_GREETING_CHANCE = 3
# People with up to this many chats get Merl's introduction with a hello; regulars know what she does.
INTRO_CHATS = 5
# How long Merl asks about what you said you're up to, or about the page that last helped.
TOPIC_FOLLOW_UP_DAYS = 14
PAGE_FOLLOW_UP_DAYS = 7
# Days since you met that Merl mentions; after a year, every year.
ANNIVERSARIES = (7, 30, 100, 365)
# Numbers of chats Merl mentions; after that, every thousand.
CHAT_MILESTONES = (10, 25, 50, 100, 250, 500)
# Small talk after which a cheerful "that was our 50th chat!" would be out of place; it waits for the next chat.
QUIET_TALK = {"stop", "forget_me", "insult", "wrong", "comfort", "sorry", "confused", "goodbye", "tired"}

# Small talk that can start a question ("thanks! how do I…"), and the short line it gets.
PREFIX_POOLS = {"greeting": "greeting_prefix", "thanks": "thanks_prefix", "sorry": "sorry_prefix",
                "ok": "ok_prefix", "no": "ok_prefix", "compliment": "compliment_prefix", "laugh": "compliment_prefix"}
# Small talk that "more" / "another one" asks for again.
REPEATABLE = {"joke", "fact", "tip", "idea", "story", "sing", "creeper_song", "pet_pb", "hungry"}
# Sentences and clauses, for messages with several bits of small talk ("you're funny! tell me a joke").
CLAUSE = re.compile(r"[.!?,;]+")
# "so it's the bosses?" right after an answer: confusion about that answer, not a new question.
CLARIFY_CUES = ("so ", "wait so ", "you mean ", "do you mean ", "are you saying ", "so youre saying ", "so basically ")
# "have you met Alex?" is about a person; "do you know Alex?" only when Alex is someone Merl knows.
MET_STRICT = re.compile(r"(?:have you (?:ever )?(?:met|talked to|spoken to|chatted with)|did you (?:meet|talk to))\s+"
                        r"@?(.{2,40}?)(?:\s+(?:yet|before|already))?\s*[?!.]*", re.I)
MET_LOOSE = re.compile(r"(?:do you know|do you remember|you know)\s+@?(.{2,40}?)\s*[?!.]*", re.I)
NOT_NAMES = {"me", "you", "yourself", "him", "her", "them", "anyone", "someone", "everyone", "my name"}
NOT_NAME_STARTS = {"how", "what", "where", "why", "when", "which", "who", "the", "a", "an", "any", "about", "if",
                   "that", "this", "my", "your", "some", "of", "to"}
QUESTION_WORDS = {"how", "what", "where", "why", "when", "which", "who", "can", "is", "does", "do", "are",
                  "should", "could", "will", "whats", "wheres", "hows", "whos", "whys"}
FOLLOW_UP_CUES = ("and ", "also ", "what about ", "how about ", "but what about ", "and what about ")
# Words that can come between small talk and the question ("ok so what is…").
FILLER_WORDS = {"so", "and", "um", "uh", "btw", "but", "also", "like", "quick", "question"}
ASKING_WORDS = {"how", "what", "where", "why", "when", "which", "who", "whats", "wheres", "hows"}
STRESS_WORDS = {"help", "stuck", "urgent", "asap", "broken", "lost", "cant", "confused", "sos", "desperate", "panic"}
# Words that show someone wants information. Without one (or a "?"), a weak wiki match is more likely
# a misread comment ("NO! Stop!") than a question, so Merl asks what they mean instead.
INFO_WORDS = QUESTION_WORDS | {
    "find", "show", "explain", "info", "information", "recipe", "craft", "crafting", "get", "obtain", "make",
    "build", "spawn", "spawns", "location", "locate", "about", "guide", "tutorial", "help", "need", "looking",
    "search", "learn", "page", "setting", "settings", "config", "enabled", "disabled", "allowed", "chance",
    "drop", "drops", "use", "work", "works", "tame", "breed", "summon", "beat", "kill", "defeat", "enchant",
    "brew", "trade", "farm", "upgrade", "repair", "unlock", "requirements", "difference", "best", "tell"}
WORD = re.compile(r"[A-Za-z]{6,}")
# A message this short that names a page title is a topic search ("boss keys"), even if it's not a question.
TOPIC_WORDS = 2
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


RECIPE = re.compile(
    r"(how (do|can|would|should) (i|you|we|one) (craft|make)|how to (craft|make)|(whats|what is|show me|give me) the (crafting )?recipe (for|of)"
    r"|(crafting )?recipe (for|of)|craft(ing)? recipe for) (an? |the |some )?(?P<item>.+?)( in minecraft)?")
RECIPE_SUFFIX = re.compile(r"(?P<item>.+?) (crafting )?recipe")


RECALL_REPEAT = re.compile(
    r"(merl )?(can you |could you |please |pls )?(repeat( that| it| yourself| the last (answer|message|one)| your (last )?answer| please)?"
    r"|say (that|it) again|what did you (just )?say|(tell|show) me (that|it) again|one more time please|i missed (that|it)"
    r"|what was (that|your answer|the answer)( again)?)( please| pls| merl)?")
RECALL_QUESTION = re.compile(
    r".*\b(what (was|did|were) (i|we) (just )?(ask|asking|asked|say|saying|said|talking about)|what was my (last |previous )?question"
    r"|what did i (just )?ask( you)?|remind me what i (asked|said)|what were we talking about|what was the question)\b.*")


def recall(text: str) -> str | None:
    """ "say that again" → "repeat", "what was I asking?" → "question" (same as MerlLines.recall in the mod)."""
    normalized = normalize(text)
    if RECALL_QUESTION.fullmatch(normalized):
        return "question"
    if RECALL_REPEAT.fullmatch(normalized):
        return "repeat"
    return None


def recipe_item(text: str) -> str | None:
    """ "how do I craft a waypoint hub" → "waypoint hub" (same as MerlLines.recipeItem in the mod)."""
    normalized = normalize(text)
    m = RECIPE.fullmatch(normalized) or RECIPE_SUFFIX.fullmatch(normalized)
    if not m:
        return None
    item = re.sub(r"^(an?|the|some) ", "", m.group("item")).strip()
    return item if item and len(item.split()) <= 5 else None


# Block palettes: which questions ask for one, in data/palette_patterns.json (shared with the mod's MerlLines.palette).
def _palette_patterns() -> dict:
    raw = json.loads((Path(__file__).parent / "data" / "palette_patterns.json").read_text("utf-8"))
    parts = raw["parts"]

    def expand(pattern: str) -> str:
        for _ in range(5):  # parts use other parts
            for key, value in parts.items():
                pattern = pattern.replace("{" + key + "}", value)
        # Java writes named groups (?<name>…), Python (?P<name>…).
        return re.sub(r"\(\?<([A-Za-z])", r"(?P<\1", pattern)

    def compile_(pattern: str) -> re.Pattern:
        return re.compile(expand(pattern))
    return {
        "mentioned": compile_(raw["mentioned"]), "prefilter": compile_(raw["prefilter"]),
        "about": compile_(raw["aboutBlocks"]), "again": compile_(raw["again"]), "follow_up": compile_(raw["followUp"]),
        "random": [compile_(p) for p in raw["random"]],
        "with": [(compile_(w["pattern"]), w.get("loose", False), w.get("named", False)) for w in raw["with"]],
        "this": compile_(raw["this"]), "held": compile_(raw["held"]), "remove": compile_(raw["remove"]),
        "leading": compile_(raw["leading"]), "trailing": compile_(raw["trailing"]),
        "not_blocks": set(raw["notBlocks"]), "not_named": compile_(raw["notNamed"]), "max_words": raw["maxWords"],
    }


PALETTE = _palette_patterns()


@dataclass
class PaletteAsk:
    block: str | None = None   # the block asked about by name; None for "this" (the mod's looked-at block)
    held: bool = False         # "the block in my hand" (in-game only)
    random: bool = False       # a palette around a random block
    again: bool = False        # another palette like the last one
    loose: bool = False        # "what goes with X" without saying block or palette: only when X is a block
    said_palette: bool = False  # the question says "palette": an unknown block name gets "I don't know that block"


def palette(text: str) -> PaletteAsk | None:
    """ "what blocks go with deepslate", "random palette", "another palette" (same as MerlLines.palette)."""
    t = normalize(text)
    if not PALETTE["prefilter"].search(t):
        return None
    if PALETTE["again"].fullmatch(t):
        return PaletteAsk(again=True)
    if any(p.fullmatch(t) for p in PALETTE["random"]):
        return PaletteAsk(random=True)
    for pattern, loose, named in PALETTE["with"]:
        if m := pattern.fullmatch(t):
            break
    else:
        return None
    about_blocks = bool(PALETTE["about"].search(t))
    block = re.sub(r"\s+", " ", PALETTE["remove"].sub(" ", m.group("block"))).strip()
    block = PALETTE["trailing"].sub("", PALETTE["leading"].sub("", block)).strip()
    if not block or block in PALETTE["not_blocks"]:
        return PaletteAsk(random=True) if about_blocks else None
    if PALETTE["held"].search(block):
        return PaletteAsk(held=True)
    if PALETTE["this"].fullmatch(block):
        return PaletteAsk()
    if len(block.split()) > PALETTE["max_words"] or (named and PALETTE["not_named"].search(block)):
        return None
    return PaletteAsk(block=block, loose=loose and not about_blocks, said_palette=bool(PALETTE["mentioned"].search(t)))


def palette_follow_up(text: str) -> bool:
    """ "shuffle", "another", "try again" right after a palette (same as MerlLines.paletteFollowUp)."""
    return bool(PALETTE["follow_up"].fullmatch(normalize(text)))


# Fun facts and jokes about something or someone, in data/topic_patterns.json (shared with MerlLines.topicRequest).
def _topic_patterns() -> dict:
    raw = json.loads((Path(__file__).parent / "data" / "topic_patterns.json").read_text("utf-8"))

    def compile_(pattern: str) -> re.Pattern:
        return re.compile(re.sub(r"\(\?<([A-Za-z])", r"(?P<\1", pattern))
    return {"fact": [compile_(p) for p in raw["fact"]], "joke": [compile_(p) for p in raw["joke"]],
            "leading": compile_(raw["leading"]), "trailing": compile_(raw["trailing"]), "me": set(raw["me"]),
            "merl": set(raw["merl"]), "not_subjects": set(raw["notSubjects"]), "max_words": raw["maxWords"]}


TOPIC_REQUESTS = _topic_patterns()
ME, MERL = "@me", "@merl"
# With this many jokes about a subject it's a thing (creepers); with fewer probably a person (Katter).
MANY_TOPIC_JOKES = 12


def topic_request(text: str) -> tuple[str, str] | None:
    """ "fun fact about axolotls" → ("fact", "axolotls"), "make fun of Katter" → ("joke", "katter"); the subject is
    ME or MERL for "me" and "you", "" for no particular subject (same as MerlLines.topicRequest)."""
    t = normalize(text)
    for kind in ("joke", "fact"):
        for pattern in TOPIC_REQUESTS[kind]:
            if m := pattern.fullmatch(t):
                # Shown as asked ("the ender dragon"); "the", "my"… don't count for the checks.
                subject = TOPIC_REQUESTS["trailing"].sub("", m.group("subject").strip()).strip()
                bare = TOPIC_REQUESTS["leading"].sub("", subject).strip()
                if bare in TOPIC_REQUESTS["me"]:
                    return kind, ME
                if bare in TOPIC_REQUESTS["merl"]:
                    return kind, MERL
                if not bare or bare in TOPIC_REQUESTS["not_subjects"]:
                    return kind, ""
                return (kind, subject) if len(bare.split()) <= TOPIC_REQUESTS["max_words"] else None
    return None


def original_case(text: str, subject: str) -> str:
    """The subject as it was written ("katter" → "Katter" in "make fun of Katter")."""
    words = re.findall(r"[a-z0-9]+", subject)
    m = re.search(r"\W+".join(map(re.escape, words)), text, re.IGNORECASE) if words else None
    return m.group(0) if m else subject


def _topic_words(text: str) -> list[str]:
    words = []
    for w in re.findall(r"[a-z0-9]+", text.lower()):
        w = w[:-3] + "man" if w.endswith("men") and len(w) > 4 else w  # endermen → enderman
        words.append(w[:-1] if len(w) > 3 and w.endswith("s") and not w.endswith("ss") else w)
    return words


def about(pool: str, subject: str) -> list[str]:
    """The lines of a pool that mention the subject: every word of it starts a word of the line ("axolotls" finds
    "Axolotls play dead…", "ender dragon" finds "The Ender Dragon heals…") (same as MerlLines.about)."""
    wanted = [w for w in _topic_words(subject) if w not in ("the", "a", "an", "of", "my", "our", "your", "some")]
    if not wanted:
        return []
    lines = LINES["pools"].get(pool, [])
    found = [line for line in lines if all(any(word.startswith(w) for word in _topic_words(line)) for w in wanted)]
    if not found:
        # Typos: "prismarin", "axolotel".
        import palettes
        found = [line for line in lines if all(any(palettes._close(w, word) for word in _topic_words(line)) for w in wanted)]
    return found


def split_small_talk(text: str) -> tuple[str | None, str]:
    """For "thanks merl! how do I get a boss key": ("thanks_prefix", "how do i get a boss key").
    (None, text) when the message doesn't start with small talk followed by a question."""
    words = normalize(text).split()
    for k in range(len(words) - 1, 0, -1):
        pool = _intent(" ".join(words[:k]))
        if pool is None:
            continue
        rest = " ".join(words[k:])
        # "nice mob variants" is a pack's name, not a compliment: one word only counts with a break ("nice! how…").
        if pool == "compliment" and k == 1 and not re.match(r"\s*\S+\s*[!,.:;]", text):
            break
        if pool in PREFIX_POOLS and looks_like_question(rest, text):
            return PREFIX_POOLS[pool], rest
        break
    return None, text


def seeks_info(text: str) -> bool:
    """ "how do I…", "where are trial chambers?", "show me the boss key page" rather than "NO! Stop!"."""
    return "?" in text or bool(INFO_WORDS.intersection(normalize(text).split()))


def clearly_about(sure: str | None, title_match: bool, text: str, all_matched: bool = False) -> bool:
    """For a message that isn't a question: is the top page clearly what it's about? Yes when Merl is
    sure, or when the page is named after it and the message is just a topic ("turtles", "boss keys").
    "i hate creepers" names the Creeper page too, but it's a comment, not a search."""
    if not sure:
        return False
    short = len(normalize(text).split()) <= TOPIC_WORDS
    # "moobloom", "mannequin": a short message whose every word is on the page is a topic search.
    return sure == "sure" or (short and (title_match or all_matched))


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


def epoch_day(day: date) -> int:
    return (day - date(1970, 1, 1)).days


def mood(day: date) -> str:
    """Merl's mood of the day, the same in the bot and the mod."""
    return MOODS[epoch_day(day) % len(MOODS)]


def pb_mood(day: date) -> str:
    return PB_MOODS[(epoch_day(day) * 7 + 3) % len(PB_MOODS)]


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


def peanut_butter(now: datetime, user: str) -> str:
    """An answer about Peanut Butter, often about how she's doing today."""
    return pick(f"pb_{pb_mood(now.date())}", user=user) if chance(2) else pick("peanut_butter", user=user)


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


def follow_up(topic: str, topic_day: int, page: str, page_day: int, today: int) -> str | None:
    """When you come back: a question about what you were up to, or about the page that last helped."""
    if topic and today - topic_day <= TOPIC_FOLLOW_UP_DAYS and f"followup_{topic}" in POOLS:
        return pick(f"followup_{topic}")
    if page and today - page_day <= PAGE_FOLLOW_UP_DAYS:
        return pick("followup_page", page=page)
    return None


def friendship_note(chats: int, noted: int, days: int, last_anniversary: int,
                    user: str) -> tuple[str | None, int, int]:
    """A line for a round number of chats or a friendship anniversary not mentioned yet, else None;
    plus the chat milestone and anniversary to remember as mentioned."""
    milestone = max([m for m in CHAT_MILESTONES if m <= chats] + [chats // 1000 * 1000])
    if milestone > noted:
        return pick("friend_milestone", count=str(milestone), user=user), milestone, last_anniversary
    anniversary = max([a for a in ANNIVERSARIES if a <= days] + [days // 365 * 365])
    if anniversary > last_anniversary:
        return pick("friend_anniversary", days=str(anniversary), user=user), noted, anniversary
    return None, noted, last_anniversary


def remember_me(chats: int, days: int, topic: str, user: str) -> str:
    """ "do you remember me?" """
    if chats <= 1:
        return pick("remember_me_new", user=user)
    line = pick("remember_me", count=str(chats), days=str(days), user=user)
    if topic in TOPIC_PHRASES:
        line += " " + pick("remember_topic", activity=TOPIC_PHRASES[topic])
    return line


def multi_small_talk(text: str) -> tuple[str | None, str | None]:
    """ "you're funny! tell me a joke" -> ("compliment_prefix", "joke"): several sentences that are all small
    talk get an answer to the last one, with a short reply to the first. (None, None) otherwise."""
    clauses = [c for c in CLAUSE.split(text) if normalize(c)]
    if len(clauses) < 2:
        return None, None
    talks = [small_talk(c) for c in clauses]
    if None in talks:
        return None, None
    first, main = talks[0], talks[-1]
    return (PREFIX_POOLS.get(first) if first != main else None), main


def is_clarifying(text: str) -> bool:
    """ "so Katter is the bosses?", "you mean the skyrtle?" """
    normalized = normalize(text) + " "
    return normalized.startswith(CLARIFY_CUES) and not ASKING_WORDS.intersection(normalized.split())


def met_question(text: str) -> tuple[str, bool] | None:
    """ "have you met NotNiceRon yet?" -> ("NotNiceRon", True); the second part says it's surely about a
    person (for "do you know X?" Merl only answers when X is someone she knows). None otherwise."""
    for pattern, strict in ((MET_STRICT, True), (MET_LOOSE, False)):
        m = pattern.fullmatch(text.strip())
        if not m:
            continue
        name = m.group(1).strip()
        words = normalize(name).split()
        if words and normalize(name) not in NOT_NAMES and words[0] not in NOT_NAME_STARTS and len(words) <= 3:
            return name, strict
    return None


def often(chats: int) -> str:
    """How often someone talked to Merl: "once", "a few times", "lots of times"."""
    return OFTEN_PHRASES[0 if chats <= 1 else 1 if chats < 10 else 2]


# The project of the last answer counts this much extra in the next search ("and the loot?").
RECENT_PROJECT_SHARE = 0.5


def interests(shares: dict[str, float], recent_project: str | None) -> dict[str, float]:
    """A person's usual projects plus what they're asking about right now, for Index.find."""
    out = dict(shares)
    if recent_project:
        out[recent_project] = min(1.0, out.get(recent_project, 0.0) + RECENT_PROJECT_SHARE)
    return out


# "how do I beat him?" right after an answer: "him" is what that answer was about.
# "there" only as a place ("how do I get there"), not in "what dungeons are there".
REFERENCE = re.compile(r"\b(it|its|him|her|them|they|he|she)\b|(?<!\bare )(?<!\bis )(?<!\bwas )(?<!\bwere )\bthere\b", re.I)


def resolve_reference(search: str, previous_page: str) -> str:
    """ "where do I find him" after the Raj Raksha page -> "where do I find Raj Raksha"."""
    if not previous_page:
        return search
    return REFERENCE.sub(previous_page, search, count=1)

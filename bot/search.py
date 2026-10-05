"""Pure-Python BM25 search over wiki sections, with typo tolerance, synonyms and phrase boosts.

Mirrored in the mod's SearchIndex.java, so changes to tokenizing or scoring go in both places.
"""

import dataclasses
import json
import math
import re
import unicodedata
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

from wiki import SPOILER_END, SPOILER_START, Section

STOPWORDS = set("""
a about above after again all am an and any are as at be because been before being below
between both but by can could did do does doing done down during each else ever few for from
further get gets getting got had has have having he her here hers him his how i if in into is
it its itself just know let like make me more most much my need no nor not now of off on once
only or other our out over own please same she should so some such tell than thank thanks that
the their them then there these they this those through to too under until up us use using very
want was way we were what when where which while who whom why will with would you your
anyone anybody someone somebody something anything thing things find wiki explain explorer
explorers eden hi hello hey hallo heya hiya yo sup moin servus merl nicemerl
whats hows wheres whos whys im ive id dont cant isnt doesnt wont u ur pls plz didnt wasnt werent arent hasnt havent hadnt wouldnt couldnt shouldnt youre theyre thats theres heres
""".split())

# Plurals the suffix rules below would get wrong.
IRREGULAR = {
    "mice": "mouse", "geese": "goose", "children": "child", "feet": "foot", "teeth": "tooth",
    "wolves": "wolf", "leaves": "leaf", "shelves": "shelf", "hooves": "hoof", "knives": "knife",
    "halves": "half", "loaves": "loaf", "thieves": "thief", "endermen": "enderman",
    "potatoes": "potato", "tomatoes": "tomato",
}
# Slang and abbreviations ("tp", "ench", "xp"), shared with the mod.
SYNONYMS: dict[str, str] = {
    k: v for k, v in json.loads((Path(__file__).parent / "data" / "synonyms.json").read_text("utf-8")).items()
    if not k.startswith("_")
}

TITLE_WEIGHT = 3
PATH_WEIGHT = 2
K1, B = 1.5, 0.75
MIN_SCORE = 2.0
# How sure Merl sounds about the top result: a score this high, or this high with the page title
# asked about, is "sure"; below GUESS_SCORE she's guessing.
SURE_SCORE = 12.0
SURE_TITLE_SCORE = 7.0
GUESS_SCORE = 4.0
# A query word the index doesn't know can still match through these, at reduced weight.
SYNONYM_WEIGHT = 0.6        # "tp" also searches "teleport"
SYNONYM_ONLY_WEIGHT = 0.9   # ... more so when "tp" itself appears nowhere
TYPO_WEIGHT = 0.7           # "enchantmnt" -> "enchantment"
PREFIX_WEIGHT = 0.5         # "ench" -> "enchantment"
# Adjacent query words found next to each other ("nether portal").
BIGRAM_WEIGHT = 1.5
# The whole page title / heading appears in the question.
TITLE_MATCH_BONUS = 2.0
HEADING_MATCH_BONUS = 1.0
# Sections whose heading is one of the hint words, e.g. "Crafting" for "how do I make…".
HEADING_HINT_BONUS = 2.5
# The page's description and tags from the wiki count like a little extra text.
META_WEIGHT = 1
# A project's home page when the question only names the project ("who is katter").
PROJECT_BONUS = 3.0
# Hybrid search: keyword and meaning-based rankings merged by Reciprocal Rank Fusion. A page counts
# 1 / (RRF_K + its rank) per ranking it's in; the meaning ranking counts SEMANTIC_WEIGHT as much.
RRF_K = 10
SEMANTIC_WEIGHT = 0.6
SEMANTIC_CANDIDATES = 10
# Sections less similar than this to the question don't count as found by meaning.
SEMANTIC_MIN = 0.40
# A page found only by meaning gets this times its similarity as score, so it's never "sure".
SEMANTIC_SCORE = 9.0
# How much of a section is embedded (title, heading, description and the start of its text).
SEMANTIC_TEXT_CHARS = 400
# A person's usual projects (from past questions) tip close calls their way, never more than this.
INTEREST_BONUS = 2.5
# Page titles that say nothing; such pages are titled after their project instead.
GENERIC_TITLES = {"main", "home"}
# Changelogs mention everything, so they only win when the question is about changes.
CHANGELOG_FACTOR = 0.5
CHANGELOG_WORDS = {"changelog", "change", "update", "patch", "new", "added", "release"}
# Technical sections (summon commands, block counts, structure file lists) repeat a page's words over and over, so
# they only count fully when the question is about commands, blocks or loot (same as SearchIndex.java).
TECHNICAL_HEADINGS = {"summon command", "commands", "blocks", "entities", "loot tables", "per-structure file contents",
                      "contents"}
TECHNICAL_FACTOR = 0.4
# Results scoring below this share of the best result are dropped.
RELATIVE_CUTOFF = 0.35
EXCERPT_LEN = 220
# The answer line: one sentence from the top pages, covering at least this share of the question's words.
ANSWER_CHARS = 300
ANSWER_MIN_CHARS = 20
ANSWER_PAGES = 2
ANSWER_COVERAGE = 0.5
ANSWER_HINT_WEIGHT = 2.5
ANSWER_HIT_WEIGHT = 1.5
ANSWER_RANK_PENALTY = 2.0
# With the meaning model: how much a sentence's similarity to the question counts, and how similar a
# sentence must be to count without sharing the question's words.
ANSWER_SEMANTIC_WEIGHT = 0.0
ANSWER_SEMANTIC_MIN = 0.7
# The top result gets whole sentences / list lines up to this many characters.
LONG_EXCERPT_LEN = 450
LONG_EXCERPT_LINES = 8
LIST_QUESTION = re.compile(r"\b(what|which)\b.*\bare there\b|\bhow many\b|\blist of\b|\ball (the )?[a-z]+s\b|\bevery\b|"
                           r"\b(types|kinds|sorts) of\b|\boverview\b|\b(variants|types|kinds)\b")
SENTENCE_END = re.compile(r"(?<=[.!?])\s+(?=[A-Z0-9\"'(\[])")


def _fold(text: str) -> str:
    text = unicodedata.normalize("NFKD", text)
    return "".join(c for c in text if not unicodedata.combining(c)).lower()


def stem(word: str) -> str:
    if word in IRREGULAR:
        return IRREGULAR[word]
    if len(word) >= 5 and word.endswith("ies"):
        return word[:-3] + "y"
    if len(word) >= 5 and (word.endswith(("sses", "ches", "shes")) or (word.endswith("xes") and not word.endswith("axes"))):
        return word[:-2]
    if len(word) >= 4 and word.endswith("s") and not word.endswith(("ss", "us", "is")):
        word = word[:-1]
    if len(word) >= 6 and word.endswith("ing"):
        return word[:-3]
    if len(word) >= 5 and word.endswith("ied"):
        return word[:-3] + "y"
    if len(word) >= 5 and word.endswith("ed"):
        return word[:-2]
    return word


def words(text: str) -> list[tuple[str, str]]:
    """(word as written, search term) for every word that isn't a stopword."""
    return [(w, stem(w)) for w in re.findall(r"[a-z0-9]+", _fold(text)) if w not in STOPWORDS]


def tokenize(text: str) -> list[str]:
    return [term for _, term in words(text)]


def hint_terms(text: str) -> set[str]:
    """Every word stemmed, stopwords included, for matching headings like "Where to Find"."""
    return {stem(w) for w in re.findall(r"[a-z0-9]+", _fold(text))}


# Which section headings answer which kind of question.
WHERE_WORDS = {"where", "find", "location", "locate", "located", "spawn", "spawns", "found", "generate"}
WHERE_HINTS = hint_terms("where find location locations spawning spawn generation biomes found")
OBTAIN_WORDS = {"get", "obtain", "craft", "make", "recipe", "drop", "drops", "loot", "build", "create"}
OBTAIN_HINTS = hint_terms("obtaining crafting recipe loot drops obtain craft sources trading")
LIST_HINTS = hint_terms("overview list all")


def question_hints(question: str) -> set[str]:
    """Heading words that fit the kind of question: "where…" → location, "how do I get…" → obtaining."""
    asked = set(re.findall(r"[a-z]+", _fold(question)))
    hints = set()
    if asked & WHERE_WORDS:
        hints |= WHERE_HINTS
    if asked & OBTAIN_WORDS:
        hints |= OBTAIN_HINTS
    if LIST_QUESTION.search(_fold(question)):
        hints |= LIST_HINTS
    return hints


def _bigrams(tokens: list[str]) -> list[str]:
    return [f"{a} {b}" for a, b in zip(tokens, tokens[1:])]


def edit_distance(a: str, b: str, limit: int) -> int:
    """Damerau-Levenshtein (optimal string alignment), giving up once it exceeds limit."""
    if abs(len(a) - len(b)) > limit:
        return limit + 1
    prev2, prev = None, list(range(len(b) + 1))
    for i in range(1, len(a) + 1):
        cur = [i] + [0] * len(b)
        for j in range(1, len(b) + 1):
            cost = a[i - 1] != b[j - 1]
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            if i > 1 and j > 1 and a[i - 1] == b[j - 2] and a[i - 2] == b[j - 1]:
                cur[j] = min(cur[j], prev2[j - 2] + 1)
        if min(cur) > limit:
            return limit + 1
        prev2, prev = prev, cur
    return prev[-1]


PLACEHOLDER_TEXTS = {"your content here"}
# Long question words, one letter off ("wher", "shoud"); short ones like "have" are too close to real words ("hate").
QUESTION_WORDS = ("where", "which", "would", "could", "should", "there", "their", "about", "please", "someone",
                  "anyone", "something")
WHAT_TYPOS = {"wat", "wht", "waht", "whta", "whats", "wats"}


def _misspelled_question_word(word: str) -> bool:
    if word in WHAT_TYPOS:
        return True
    return len(word) >= 4 and any(abs(len(q) - len(word)) <= 1 and edit_distance(word, q, 1) <= 1 for q in QUESTION_WORDS)
_TECHNICAL_WORDS: set[str] | None = None


def technical_words() -> set[str]:
    global _TECHNICAL_WORDS
    if _TECHNICAL_WORDS is None:
        _TECHNICAL_WORDS = set(tokenize("command commands summon give block blocks entity entities loot table tables "
                                        "contents made composed structure file nbt"))
    return _TECHNICAL_WORDS


_MINECRAFT_TERMS: set[str] | None = None


def minecraft_terms() -> set[str]:
    """Every search term in the names of vanilla blocks, items, mobs, biomes, enchantments and effects
    (data/minecraft_names.json, from the game's language file)."""
    global _MINECRAFT_TERMS
    if _MINECRAFT_TERMS is None:
        try:
            names = json.loads((Path(__file__).parent / "data" / "minecraft_names.json").read_text("utf-8"))
        except (OSError, ValueError):
            names = []
        _MINECRAFT_TERMS = {t for n in names for t in tokenize(n)}
    return _MINECRAFT_TERMS


@dataclass
class Result:
    section: Section
    score: float
    excerpt: str
    # The page title or heading is exactly what was asked about.
    title_match: bool = False
    # How many of the question's words the section contains.
    matched: int = 0


@dataclass
class Group:
    """One query word and the ways it can match: [(terms, weight)], the exact term first."""
    word: str
    alternatives: list[tuple[tuple[str, ...], float]]
    exact: bool


@dataclass
class Outcome:
    results: list[Result]
    # Words that were read as something else, e.g. {"enchantmnt": "enchantment"}.
    corrections: dict[str, str] = field(default_factory=dict)
    # Some words only matched through typo/prefix guesses, or not at all.
    uncertain: bool = False

    @property
    def confidence(self) -> float:
        return self.results[0].score if self.results else 0.0


class Index:
    def __init__(self, sections: list[Section], priors: dict[str, float] | None = None,
                 heading_hints: set[str] | None = None, embed=None):
        """priors: optional per-page bonus (by path), e.g. the source's own search rank.
        heading_hints: terms that make a section heading a likely answer, e.g. {"craft", "obtain"}.
        embed: a semantic.Embedder for meaning-based search next to the keywords, or None."""
        # Unwritten pages show the wiki's placeholder ("Your content here"): the page is still found by its title
        # and description, but that text is never shown or searched.
        sections = [dataclasses.replace(s, text="") if s.text.strip().lower() in PLACEHOLDER_TEXTS else s
                    for s in sections]
        self.sections = sections
        self.embed = embed
        self.vectors = embed([f"{s.page_title}. {s.heading}. {s.meta}. {s.text[:SEMANTIC_TEXT_CHARS]}"
                              for s in sections]) if embed and sections else None
        self.priors = priors or {}
        self.heading_hints = heading_hints or set()
        self.docs: list[Counter] = []
        self.bigrams: list[Counter] = []
        self.lengths: list[int] = []
        self.title_terms: list[set[str]] = []
        self.heading_terms: list[set[str]] = []
        self.hint_targets: list[set[str]] = []
        self.project_terms: list[set[str]] = []
        # The words of each page's project folder ("katters_structures" → katter, structure).
        self.folder_terms: list[set[str]] = []
        surface: dict[str, Counter] = {}
        df: Counter = Counter()
        bdf: Counter = Counter()
        for s in sections:
            text_words = words(s.text)
            for w, term in text_words:
                surface.setdefault(term, Counter())[w] += 1
            text_tokens = [term for _, term in text_words]
            title_tokens = tokenize(f"{s.page_title} {s.heading}")
            path_words = s.path.replace("/", " ").replace("_", " ")
            tokens = (text_tokens + title_tokens * TITLE_WEIGHT + tokenize(path_words) * PATH_WEIGHT
                      + tokenize(s.meta) * META_WEIGHT)
            counts = Counter(tokens)
            pairs = Counter(_bigrams(text_tokens) + _bigrams(title_tokens) * TITLE_WEIGHT)
            self.docs.append(counts)
            self.bigrams.append(pairs)
            self.lengths.append(len(tokens))
            # A project's home page ("Main") is about the project ("Katters Structures").
            project = set(tokenize(s.path.split("/")[0].replace("_", " ")))
            home = s.path.endswith("/home") or s.page_title.lower() in GENERIC_TITLES
            title = set(tokenize(s.page_title)) - GENERIC_TITLES
            self.title_terms.append(title | project if home else title)
            self.project_terms.append(project if home and not s.vanilla else set())
            self.folder_terms.append(set() if s.vanilla else set(tokenize(s.path.split("/")[0].replace("_", " "))))
            # Headings answer kinds of questions ("Where to Find"); overview pages answer "what … are there".
            # A project's home page lists what the project has ("cat variants" → the Cat list on Nice Mob Variants).
            self.hint_targets.append(hint_terms(s.heading) | (hint_terms(s.page_title) & LIST_HINTS)
                                     | (LIST_HINTS if home and not s.vanilla else set()))
            self.heading_terms.append(set(tokenize(s.heading)) if s.heading != s.page_title else set())
            df.update(counts.keys())
            bdf.update(pairs.keys())
        self.all_folder_terms = set().union(*self.folder_terms) if self.folder_terms else set()
        n = len(sections)
        self.avg_len = sum(self.lengths) / n if n else 0
        self.idf = {t: math.log(1 + (n - f + 0.5) / (f + 0.5)) for t, f in df.items()}
        self.bigram_idf = {t: math.log(1 + (n - f + 0.5) / (f + 0.5)) for t, f in bdf.items()}
        self.df = df
        # Most common written form of each term, to show corrections readably.
        self.display = {term: forms.most_common(1)[0][0] for term, forms in surface.items()}
        # Candidates for typo and prefix matching, most common first.
        self.vocab = sorted((t for t in df if len(t) >= 4 and not t.isdigit()), key=lambda t: (-df[t], t))

    def _bm25(self, i: int, term: str) -> float:
        tf = self.docs[i].get(term)
        if not tf:
            return 0.0
        norm = K1 * (1 - B + B * self.lengths[i] / self.avg_len)
        return self.idf[term] * tf * (K1 + 1) / (tf + norm)

    def _typo(self, term: str) -> str | None:
        if len(term) < 4 or term.isdigit():
            return None
        limit = 1 if len(term) < 8 else 2
        best, best_dist = None, limit + 1
        for candidate in self.vocab:  # most common first, so ties keep the more common word
            dist = edit_distance(term, candidate, limit)
            if dist < best_dist:
                best, best_dist = candidate, dist
                if dist == 1:
                    break
        return best

    def _prefix(self, term: str) -> str | None:
        if len(term) < 4:
            return None
        return next((t for t in self.vocab if t.startswith(term) and t != term), None)

    def analyze(self, query: str) -> tuple[list[Group], dict[str, str]]:
        groups: list[Group] = []
        corrections: dict[str, str] = {}
        seen = set()
        for word, term in words(query):
            if term in seen:
                continue
            seen.add(term)
            alternatives: list[tuple[tuple[str, ...], float]] = []
            exact = term in self.idf
            if exact:
                alternatives.append(((term,), 1.0))
            synonym = tuple(t for t in tokenize(SYNONYMS.get(word, "")) if t in self.idf and t != term)
            if synonym:
                alternatives.append((synonym, SYNONYM_WEIGHT if exact else SYNONYM_ONLY_WEIGHT))
            # A misspelled question word ("wher", "wat") is a question word, not a typo of some wiki word.
            if not alternatives and term not in minecraft_terms() and _misspelled_question_word(word):
                continue
            if not alternatives:
                guess = self._typo(term)
                weight = TYPO_WEIGHT
                if guess is None:
                    guess, weight = self._prefix(term), PREFIX_WEIGHT
                # A real Minecraft word isn't a typo, even if this wiki never uses it: "minecart" doesn't become
                # "minecraft". Another form of the same word is fine ("friend" → "friendly").
                if guess is not None and term in minecraft_terms() and not (guess.startswith(term) or term.startswith(guess)):
                    guess = None
                if guess is not None:
                    alternatives.append(((guess,), weight))
                    corrections[word] = self.display.get(guess, guess)
            groups.append(Group(term, alternatives, exact or bool(synonym)))
        return groups, corrections

    def _score(self, i: int, groups: list[Group], query_terms: set[str], changelog_ok: bool, hints: set[str],
               technical_ok: bool = True) -> float:
        active = [g for g in groups if g.alternatives]
        score = 0.0
        matched = 0
        for g in active:
            best = max(w * sum(self._bm25(i, t) for t in terms) for terms, w in g.alternatives)
            if best > 0:
                matched += 1
                score += best
        if not matched:
            return 0.0
        for a, b in zip(active, active[1:]):
            # A pair with another project's name ("graveyard katters" on a Nice Mob Variants page that mentions
            # "Graveyard (Katters Structures)") is about that other project.
            first, second = a.alternatives[0][0][-1], b.alternatives[0][0][0]
            if {first, second} & (self.all_folder_terms - self.folder_terms[i]):
                continue
            pair = f"{first} {second}"
            tf = self.bigrams[i].get(pair)
            if tf:
                score += BIGRAM_WEIGHT * self.bigram_idf[pair] * tf * (K1 + 1) / (tf + K1)
        score *= math.sqrt(matched / len(active))
        if self._title_match(i, query_terms):
            score += TITLE_MATCH_BONUS
            # "graveyard katters": the page named in the question, in the project named in the question.
            if self.folder_terms[i] & query_terms:
                score += TITLE_MATCH_BONUS
        if self.heading_terms[i] and self.heading_terms[i] <= query_terms:
            score += HEADING_MATCH_BONUS
        if self.hint_targets[i] & hints:
            score += HEADING_HINT_BONUS
        if self.project_terms[i] and query_terms <= self.project_terms[i]:
            score += PROJECT_BONUS
        path = self.sections[i].path
        if not changelog_ok and "changelog" in path.lower():
            score *= CHANGELOG_FACTOR
        if not technical_ok and self.sections[i].heading.lower() in TECHNICAL_HEADINGS:
            score *= TECHNICAL_FACTOR
        return score + self.priors.get(path, 0.0)

    def _title_match(self, i: int, query_terms: set[str]) -> bool:
        return bool(self.title_terms[i]) and self.title_terms[i] <= query_terms

    def find(self, query: str, limit: int = 3, min_score: float = MIN_SCORE,
             interests: dict[str, float] | None = None) -> Outcome:
        """interests: project (first path part) → share of that person's past questions, 0..1."""
        groups, corrections = self.analyze(query)
        hints = self.heading_hints | question_hints(query)
        uncertain = any(not g.exact for g in groups)
        if not self.sections or (not any(g.alternatives for g in groups) and self.vectors is None):
            return Outcome([], corrections, uncertain=bool(groups))
        query_terms = {t for g in groups for terms, _ in g.alternatives for t in terms}
        changelog_ok = bool(query_terms & CHANGELOG_WORDS)
        technical_ok = bool(query_terms & technical_words())
        best: dict[str, tuple[float, int]] = {}
        for i, s in enumerate(self.sections):
            score = self._score(i, groups, query_terms, changelog_ok, hints, technical_ok)
            if score and interests:
                score += INTEREST_BONUS * interests.get(s.path.split("/")[0], 0.0)
            if score >= min_score and score > best.get(s.path, (0.0, -1))[0]:
                best[s.path] = (score, i)
        ranked = sorted(best.values(), reverse=True)[:limit]
        if ranked:
            ranked = [r for r in ranked if r[0] >= ranked[0][0] * RELATIVE_CUTOFF]
        # "cat variants": when the best matches are all pages of one kind (every cat variant has its own page), the
        # question wants the overview, the home page section named after it (the Cat list on Nice Mob Variants).
        if ranked and hints & LIST_HINTS and (overview := self._overview(ranked, query_terms)) is not None:
            ranked = [(ranked[0][0] + 0.01, overview)] + [r for r in ranked if self.sections[r[1]].path != self.sections[overview].path]
            ranked = ranked[:limit]
        # Meaning only helps when the keywords aren't sure: a strong match, or a page named in the
        # question, already is the answer ("curse of blindness" shouldn't drift to "color blindness").
        # So is a project's home page when the question names the project ("who is katter"), and a
        # section made for the kind of question ("what dungeons are there" → the overview).
        top = ranked[0][1] if ranked else -1
        keyword_sure = bool(ranked) and (
            ranked[0][0] >= SURE_SCORE
            or self._title_match(top, query_terms) and ranked[0][0] >= SURE_TITLE_SCORE
            or bool(self.project_terms[top]) and query_terms <= self.project_terms[top]
            or bool(self.hint_targets[top] & hints) and ranked[0][0] >= GUESS_SCORE)
        if self.vectors is not None and not keyword_sure:
            ranked = self._hybrid(query, best, limit, ranked)
        terms = list(query_terms)
        results = [
            Result(self.sections[i], score, (long_excerpt if rank == 0 else excerpt)(self.sections[i].text, terms),
                   self._title_match(i, query_terms)
                   or bool(self.heading_terms[i]) and self.heading_terms[i] <= query_terms,
                   sum(any(self.docs[i].get(t) for alt, _ in g.alternatives for t in alt) for g in groups))
            for rank, (score, i) in enumerate(ranked)
        ]
        return Outcome(results, corrections if results else {}, uncertain)

    def _overview(self, ranked: list[tuple[float, int]], query_terms: set[str]) -> int | None:
        """The overview for a list question when the two best results are sibling pages (same folder): that folder's
        overview page, else the home page section named after what's asked. None otherwise. Same as
        SearchIndex.overview."""
        # The best page named after what's asked ("which bosses are there" → Bosses) is the list already.
        if len(ranked) < 2 or self._title_match(ranked[0][1], query_terms):
            return None
        # The two best are pages of one kind (siblings in one folder).
        parents = {self.sections[i].path.rsplit("/", 1)[0] for _, i in ranked[:2]}
        if len(parents) != 1:
            return None
        parent = parents.pop()
        # The folder's own overview page ("Ambient Structures Overview") first.
        for i, s in enumerate(self.sections):
            if s.path.rsplit("/", 1)[0] == parent and (s.path.endswith("/overview") or "overview" in s.page_title.lower()):
                return i
        project = parent.split("/")[0]
        best, best_size = None, 0
        for i, s in enumerate(self.sections):
            if s.vanilla or s.path.split("/")[0] != project:
                continue
            if not (s.path.endswith("/home") or s.page_title.lower() in GENERIC_TITLES):
                continue
            heading = self.heading_terms[i]
            if heading and heading <= query_terms and len(heading) > best_size:
                best, best_size = i, len(heading)
        return best

    def _hybrid(self, query: str, best: dict[str, tuple[float, int]], limit: int,
                keyword_ranked: list[tuple[float, int]]) -> list[tuple[float, int]]:
        """Merges the keyword ranking with the meaning ranking. Pages keep their keyword score and
        section; a page found only by meaning gets a capped score, so Merl never sounds sure about it."""
        similarity = self.vectors @ self.embed([query])[0]
        by_meaning: dict[str, tuple[float, int]] = {}
        for i in similarity.argsort()[::-1][:SEMANTIC_CANDIDATES * 4]:
            path = self.sections[i].path
            if similarity[i] >= SEMANTIC_MIN and path not in by_meaning:
                by_meaning[path] = (float(similarity[i]), int(i))
        meaning_rank = {path: r for r, path in enumerate(list(by_meaning)[:SEMANTIC_CANDIDATES])}
        kept = {self.sections[i].path for _, i in keyword_ranked}
        keyword_rank = {path: r for r, (path, _) in enumerate(sorted(best.items(), key=lambda kv: -kv[1][0]))}
        fused = {}
        for path in set(keyword_rank) | set(meaning_rank):
            fused[path] = (1 / (RRF_K + keyword_rank[path]) if path in keyword_rank else 0.0) \
                + (SEMANTIC_WEIGHT / (RRF_K + meaning_rank[path]) if path in meaning_rank else 0.0)
        out = []
        for path in sorted(fused, key=lambda p: -fused[p]):
            if path in best and (path in kept or path in meaning_rank):
                out.append(best[path])
            elif path not in best and path in meaning_rank:
                similar, i = by_meaning[path]
                out.append((SEMANTIC_SCORE * similar, i))
            if len(out) == limit:
                break
        return out

    def search(self, query: str, limit: int = 3) -> list[Result]:
        return self.find(query, limit).results


Word = tuple[str, str, bool]  # (text, matched query term or "", inside_spoiler)


def _units(text: str, terms: list[str]) -> list[tuple[int, list[Word]]]:
    """Splits section text into sentences / list lines, tagged with their line number."""
    term_set = set(terms)
    units = []
    in_spoiler = 0
    for line_no, line in enumerate(text.split("\n")):
        for sentence in SENTENCE_END.split(line):
            unit = []
            for w in sentence.split():
                if w == SPOILER_START:
                    in_spoiler += 1
                elif w == SPOILER_END:
                    in_spoiler = max(0, in_spoiler - 1)
                else:
                    hit = next((t for t in tokenize(w) if t in term_set), "")
                    unit.append((w, hit, in_spoiler > 0))
            if unit:
                units.append((line_no, unit))
    return units


def _relevance(words_: list[Word]) -> tuple[int, int]:
    """Distinct query terms first, then total hits."""
    hits = [hit for _, hit, _ in words_ if hit]
    return len(set(hits)), len(hits)


def _render(words_: list[Word]) -> str:
    """Formats words for Discord: query hits bold, spoiler runs wrapped in ||…||."""
    out, run = [], []
    for w, hit, hidden in words_:
        w = _escape(w)
        w = f"**{w}**" if hit else w
        if hidden:
            run.append(w)
            continue
        if run:
            out.append(f"||{' '.join(run)}||")
            run = []
        out.append(w)
    if run:
        out.append(f"||{' '.join(run)}||")
    return " ".join(out)


def answer_line(question: str, results: list[Result], index: "Index | None" = None,
                max_chars: int = ANSWER_CHARS) -> str:
    """The sentence (plus the one after it) from the top pages that answers the question best, or ""
    when none covers enough of it. Every section of those pages counts, so "where is Raj Raksha" can
    answer from "Where to Find" even though the page's intro was the search hit. Rare words count more
    than common ones, and with the meaning model a sentence that says the same in other words counts
    too ("how much xp do I lose" → "they lose 30% of their Experience Levels"). No spoiler text."""
    weight = (lambda t: index.idf.get(t, 1.0)) if index else (lambda t: 1.0)
    # Each question word counts once, whether a sentence has it or one of its synonyms ("move" → "transfer").
    groups: dict[str, set[str]] = {}
    for word, term in words(question):
        groups.setdefault(term, {term}).update(tokenize(SYNONYMS.get(word, "")))
    terms = set().union(*groups.values()) if groups else set()
    if not terms:
        return ""
    hints = question_hints(question)
    total = sum(weight(t) for t in groups)
    needed = total * ANSWER_COVERAGE
    candidates = []  # (text, matched, fits, is hit section, rank, n)
    for rank, r in enumerate(results[:ANSWER_PAGES]):
        page = [s for s in index.sections if s.path == r.section.path] if index else []
        for section in page or [r.section]:
            fits = rank == 0 and bool(hint_terms(section.heading) & hints)
            units = [u for _, u in _units(section.text, list(terms))]
            for n, unit in enumerate(units):
                if any(hidden for _, _, hidden in unit):
                    continue
                text = " ".join(w for w, _, _ in unit)
                if not ANSWER_MIN_CHARS <= len(text) <= max_chars:
                    continue
                if n + 1 < len(units) and not any(hidden for _, _, hidden in units[n + 1]):
                    following = " ".join(w for w, _, _ in units[n + 1])
                    if len(text) + len(following) < max_chars:
                        text = f"{text} {following}"
                hits = {hit for _, hit, _ in unit if hit}
                matched = sum(weight(t) for t, alternatives in groups.items() if alternatives & hits)
                candidates.append((text, matched, fits, section is r.section, rank, n))
    if not candidates:
        return ""
    meaning = [0.0] * len(candidates)
    embed = index.embed if index else None
    if embed is not None:
        vectors = embed([question] + [c[0] for c in candidates])
        meaning = list(vectors[1:] @ vectors[0])
    best, best_score = "", 0.0
    for (text, matched, fits, is_hit, rank, n), similar in zip(candidates, meaning):
        # Enough of the question's words, a section made for this kind of question ("Where to Find"),
        # or (with the model) the same meaning in other words.
        if matched < needed and not fits and similar < ANSWER_SEMANTIC_MIN:
            continue
        score = (matched + ANSWER_HINT_WEIGHT * needed * fits + ANSWER_HIT_WEIGHT * is_hit
                 + ANSWER_SEMANTIC_WEIGHT * total * similar - ANSWER_RANK_PENALTY * rank - n * 0.01)
        if score > best_score:
            best, best_score = text, score
    return best


def excerpt(text: str, terms: list[str], length: int = EXCERPT_LEN) -> str:
    """Returns the single-line window of text covering the most query terms."""
    all_words = [w for _, unit in _units(text, terms) for w in unit]
    if not all_words:
        return ""

    window = 1
    while window < len(all_words) and len(" ".join(w for w, _, _ in all_words[:window + 1])) <= length:
        window += 1
    start = max(range(max(1, len(all_words) - window + 1)), key=lambda s: (_relevance(all_words[s:s + window]), -s))
    end = start + window

    result = _render(all_words[start:end])
    if start > 0:
        result = "…" + result
    if end < len(all_words):
        result += "…"
    return result


def long_excerpt(text: str, terms: list[str], length: int = LONG_EXCERPT_LEN) -> str:
    """Returns whole sentences / list lines around the best match, one line per wiki line."""
    units = _units(text, terms)
    if not units:
        return ""

    def size(i: int) -> int:
        return sum(len(w) + 1 for w, _, _ in units[i][1])

    best = max(range(len(units)), key=lambda i: (_relevance(units[i][1]), -i))
    if size(best) > length:
        return excerpt(text, terms, length)

    lo = hi = best
    total = size(best)

    def fits(i: int) -> bool:
        lines = {units[j][0] for j in range(min(lo, i), max(hi, i) + 1)}
        return total + size(i) <= length and len(lines) <= LONG_EXCERPT_LINES

    while True:
        if hi + 1 < len(units) and fits(hi + 1):
            hi += 1
            total += size(hi)
        elif lo > 0 and fits(lo - 1):
            lo -= 1
            total += size(lo)
        else:
            break

    lines: dict[int, list[Word]] = {}
    for line_no, unit in units[lo:hi + 1]:
        lines.setdefault(line_no, []).extend(unit)
    result = "\n".join(_render(unit) for unit in lines.values())
    if hi + 1 < len(units):
        result += " …"
    return result


def _escape(text: str) -> str:
    return re.sub(r"([*_~`|>\\])", r"\\\1", text)


if __name__ == "__main__":
    import asyncio
    import sys

    from config import VANILLA_WIKI_URL, WIKI_URL
    from vanilla import VanillaWiki, combine, plan
    from wiki import fetch_sections

    async def run(queries: list[str]):
        index = Index(await fetch_sections(WIKI_URL))
        vanilla = VanillaWiki(VANILLA_WIKI_URL)
        for q in queries:
            outcome = index.find(q)
            mode = plan(q, outcome)
            print(f"\n=== {q}   [eden {outcome.confidence:.2f}{', uncertain' if outcome.uncertain else ''} -> {mode}]")
            if outcome.corrections:
                print(f"    corrections: {outcome.corrections}")
            for r in outcome.results:
                s = r.section
                print(f"{r.score:6.2f}  {s.page_title} › {s.heading}  {WIKI_URL}/{s.path}#{s.anchor}")
                print(f"        {r.excerpt[:200]!r}")
            found, titled = await vanilla.search(q, require_title_match=mode == "check")
            for r in combine(q, outcome, found, titled, 3):
                source = vanilla.url(r.section) if r.section.vanilla else f"{WIKI_URL}/{r.section.path}"
                print(f"  -> {'MC ' if r.section.vanilla else 'EE '}{r.section.page_title} › {r.section.heading}  {source}")
                if r.section.vanilla:
                    print(f"        {r.excerpt[:200]!r}")
        await vanilla.close()

    asyncio.run(run(sys.argv[1:] or [input("Query: ")]))

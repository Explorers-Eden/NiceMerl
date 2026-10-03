"""Pure-Python BM25 search over wiki sections, with typo tolerance, synonyms and phrase boosts.

Mirrored in the mod's SearchIndex.java, so changes to tokenizing or scoring go in both places.
"""

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
whats hows wheres whos whys im ive id dont cant isnt doesnt wont u ur pls plz
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
# Sections whose heading is one of the caller's hint words, e.g. "Crafting" for "how do I make…".
HEADING_HINT_BONUS = 2.5
# Changelogs mention everything, so they only win when the question is about changes.
CHANGELOG_FACTOR = 0.75
CHANGELOG_WORDS = {"changelog", "change", "update", "patch", "new", "added", "release"}
# Results scoring below this share of the best result are dropped.
RELATIVE_CUTOFF = 0.35
EXCERPT_LEN = 220
# The top result gets whole sentences / list lines up to this many characters.
LONG_EXCERPT_LEN = 450
LONG_EXCERPT_LINES = 8
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


@dataclass
class Result:
    section: Section
    score: float
    excerpt: str
    # The page title or heading is exactly what was asked about.
    title_match: bool = False


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
                 heading_hints: set[str] | None = None):
        """priors: optional per-page bonus (by path), e.g. the source's own search rank.
        heading_hints: terms that make a section heading a likely answer, e.g. {"craft", "obtain"}."""
        self.sections = sections
        self.priors = priors or {}
        self.heading_hints = heading_hints or set()
        self.docs: list[Counter] = []
        self.bigrams: list[Counter] = []
        self.lengths: list[int] = []
        self.title_terms: list[set[str]] = []
        self.heading_terms: list[set[str]] = []
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
            tokens = text_tokens + title_tokens * TITLE_WEIGHT + tokenize(path_words) * PATH_WEIGHT
            counts = Counter(tokens)
            pairs = Counter(_bigrams(text_tokens) + _bigrams(title_tokens) * TITLE_WEIGHT)
            self.docs.append(counts)
            self.bigrams.append(pairs)
            self.lengths.append(len(tokens))
            self.title_terms.append(set(tokenize(s.page_title)))
            self.heading_terms.append(set(tokenize(s.heading)) if s.heading != s.page_title else set())
            df.update(counts.keys())
            bdf.update(pairs.keys())
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
            if not alternatives:
                guess = self._typo(term)
                weight = TYPO_WEIGHT
                if guess is None:
                    guess, weight = self._prefix(term), PREFIX_WEIGHT
                if guess is not None:
                    alternatives.append(((guess,), weight))
                    corrections[word] = self.display.get(guess, guess)
            groups.append(Group(term, alternatives, exact or bool(synonym)))
        return groups, corrections

    def _score(self, i: int, groups: list[Group], query_terms: set[str], changelog_ok: bool) -> float:
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
            pair = f"{a.alternatives[0][0][-1]} {b.alternatives[0][0][0]}"
            tf = self.bigrams[i].get(pair)
            if tf:
                score += BIGRAM_WEIGHT * self.bigram_idf[pair] * tf * (K1 + 1) / (tf + K1)
        score *= math.sqrt(matched / len(active))
        if self._title_match(i, query_terms):
            score += TITLE_MATCH_BONUS
        if self.heading_terms[i] and self.heading_terms[i] <= query_terms:
            score += HEADING_MATCH_BONUS
        if self.heading_terms[i] & self.heading_hints:
            score += HEADING_HINT_BONUS
        path = self.sections[i].path
        if not changelog_ok and "changelog" in path.lower():
            score *= CHANGELOG_FACTOR
        return score + self.priors.get(path, 0.0)

    def _title_match(self, i: int, query_terms: set[str]) -> bool:
        return bool(self.title_terms[i]) and self.title_terms[i] <= query_terms

    def find(self, query: str, limit: int = 3, min_score: float = MIN_SCORE) -> Outcome:
        groups, corrections = self.analyze(query)
        uncertain = any(not g.exact for g in groups)
        if not any(g.alternatives for g in groups) or not self.sections:
            return Outcome([], corrections, uncertain=bool(groups))
        query_terms = {t for g in groups for terms, _ in g.alternatives for t in terms}
        changelog_ok = bool(query_terms & CHANGELOG_WORDS)
        best: dict[str, tuple[float, int]] = {}
        for i, s in enumerate(self.sections):
            score = self._score(i, groups, query_terms, changelog_ok)
            if score >= min_score and score > best.get(s.path, (0.0, -1))[0]:
                best[s.path] = (score, i)
        ranked = sorted(best.values(), reverse=True)[:limit]
        if ranked:
            ranked = [r for r in ranked if r[0] >= ranked[0][0] * RELATIVE_CUTOFF]
        terms = list(query_terms)
        results = [
            Result(self.sections[i], score, (long_excerpt if rank == 0 else excerpt)(self.sections[i].text, terms),
                   self._title_match(i, query_terms)
                   or bool(self.heading_terms[i]) and self.heading_terms[i] <= query_terms)
            for rank, (score, i) in enumerate(ranked)
        ]
        return Outcome(results, corrections if results else {}, uncertain)

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

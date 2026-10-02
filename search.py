"""Small pure-Python BM25 search over wiki sections."""

import math
import re
import unicodedata
from collections import Counter
from dataclasses import dataclass

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
""".split())

TITLE_WEIGHT = 3
PATH_WEIGHT = 2
K1, B = 1.5, 0.75
MIN_SCORE = 2.0
EXCERPT_LEN = 220


def _fold(text: str) -> str:
    text = unicodedata.normalize("NFKD", text)
    return "".join(c for c in text if not unicodedata.combining(c)).lower()


def stem(word: str) -> str:
    if len(word) >= 5 and word.endswith("ies"):
        return word[:-3] + "y"
    if len(word) >= 4 and word.endswith("s") and not word.endswith(("ss", "us", "is")):
        word = word[:-1]
    if len(word) >= 6 and word.endswith("ing"):
        return word[:-3]
    if len(word) >= 5 and word.endswith("ed"):
        return word[:-2]
    return word


def tokenize(text: str) -> list[str]:
    words = re.findall(r"[a-z0-9]+", _fold(text))
    return [stem(w) for w in words if w not in STOPWORDS]


@dataclass
class Result:
    section: Section
    score: float
    excerpt: str


class Index:
    def __init__(self, sections: list[Section]):
        self.sections = sections
        self.docs: list[Counter] = []
        self.lengths: list[int] = []
        df: Counter = Counter()
        for s in sections:
            path_words = s.path.replace("/", " ").replace("_", " ")
            tokens = (
                tokenize(s.text)
                + tokenize(f"{s.page_title} {s.heading}") * TITLE_WEIGHT
                + tokenize(path_words) * PATH_WEIGHT
            )
            counts = Counter(tokens)
            self.docs.append(counts)
            self.lengths.append(len(tokens))
            df.update(counts.keys())
        n = len(sections)
        self.avg_len = sum(self.lengths) / n if n else 0
        self.idf = {t: math.log(1 + (n - f + 0.5) / (f + 0.5)) for t, f in df.items()}

    def _score(self, i: int, terms: list[str]) -> float:
        doc, length = self.docs[i], self.lengths[i]
        score = 0.0
        for t in terms:
            tf = doc.get(t)
            if tf:
                norm = K1 * (1 - B + B * length / self.avg_len)
                score += self.idf[t] * tf * (K1 + 1) / (tf + norm)
        return score

    def search(self, query: str, limit: int = 3) -> list[Result]:
        terms = list(dict.fromkeys(tokenize(query)))
        if not terms or not self.sections:
            return []
        best: dict[str, tuple[float, int]] = {}
        for i, s in enumerate(self.sections):
            score = self._score(i, terms)
            if score >= MIN_SCORE and score > best.get(s.path, (0.0, -1))[0]:
                best[s.path] = (score, i)
        ranked = sorted(best.values(), reverse=True)[:limit]
        return [Result(self.sections[i], score, excerpt(self.sections[i].text, terms)) for score, i in ranked]


def excerpt(text: str, terms: list[str], length: int = EXCERPT_LEN) -> str:
    """Returns the window of text with the most query-term hits, hits in bold, spoilers hidden."""
    words, spoiler = [], []
    in_spoiler = 0
    for w in text.split():
        if w == SPOILER_START:
            in_spoiler += 1
        elif w == SPOILER_END:
            in_spoiler = max(0, in_spoiler - 1)
        else:
            words.append(w)
            spoiler.append(in_spoiler > 0)
    if not words:
        return ""
    term_set = set(terms)
    hits = [any(t in term_set for t in tokenize(w)) for w in words]

    window = 1
    while window < len(words) and len(" ".join(words[:window + 1])) <= length:
        window += 1
    start = max(range(max(1, len(words) - window + 1)), key=lambda s: sum(hits[s:s + window]))
    end = start + window

    out, run = [], []
    for w, hit, hidden in zip(words[start:end], hits[start:end], spoiler[start:end]):
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

    result = " ".join(out)
    if start > 0:
        result = "…" + result
    if end < len(words):
        result += "…"
    return result


def _escape(text: str) -> str:
    return re.sub(r"([*_~`|>\\])", r"\\\1", text)


if __name__ == "__main__":
    import asyncio
    import sys

    from config import WIKI_URL
    from wiki import fetch_sections

    index = Index(asyncio.run(fetch_sections(WIKI_URL)))
    queries = sys.argv[1:] or [input("Query: ")]
    for q in queries:
        print(f"\n=== {q}")
        results = index.search(q)
        if not results:
            print("(no match)")
        for r in results:
            s = r.section
            print(f"{r.score:6.2f}  {s.page_title} › {s.heading}  {WIKI_URL}/{s.path}#{s.anchor}")
            print(f"        {r.excerpt}")

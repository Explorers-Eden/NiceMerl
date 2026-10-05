"""Answers vanilla Minecraft questions from the Minecraft Wiki (minecraft.wiki, MediaWiki API).

The wiki's own search finds candidate pages; those pages are then split into sections and
ranked with the same BM25 search as the community wiki. Mirrored in the mod's VanillaWiki.java.
"""

import asyncio
import json
import logging
import re
import time
from collections import OrderedDict
from pathlib import Path
from urllib.parse import quote

import aiohttp
from bs4 import BeautifulSoup

from search import GUESS_SCORE, MIN_SCORE, SURE_SCORE, SURE_TITLE_SCORE, SYNONYMS, Index, Outcome, Result, edit_distance, tokenize, words
from wiki import Section, split_sections

log = logging.getLogger(__name__)

USER_AGENT = "NiceMerl/1.2 (Discord bot; +https://github.com/Explorers-Eden/NiceMerl)"
# Words that say "I mean plain Minecraft".
VANILLA_CUE = re.compile(r"\b(vanilla|minecraft|mc|normal game|base game|default game)\b", re.I)
CUE_WORDS = {"vanilla", "minecraft", "mc", "game", "normal", "base", "default"}
# An Eden result at least this good (with every word understood) is trusted on its own.
STRONG_SCORE = 8.0
# Changelog and other non-article pages.
SKIP_TITLES = re.compile(r"Edition|Snapshot|Pre-release|Release Candidate|\(disambiguation\)|^(Category|Template|File):", re.I)
SKIP_SECTIONS = {
    "history", "issues", "trivia", "gallery", "screenshots", "videos", "references", "navigation",
    "in other media", "publicity", "data values", "achievements", "external links", "see also",
    "notes", "sounds", "renders", "mojang screenshots", "development images", "concept artwork",
}
REMOVE = (
    "style, script, .infobox, .notaninfobox, .navbox, .navigation-not-searchable, .msgbox, .hatnote, "
    ".searchaux, .toc, #toc, sup.reference, .mw-references-wrap, .references, .mw-editsection, "
    "table.collapsible, figure, .gallery, .noprint, .mw-empty-elt"
)
# "How do I make / get…" questions are best answered by these chapters.
HOW_TO = re.compile(r"\b(make|craft|build|create|get|obtain|find|where|spawn)\b", re.I)
HOW_TO_HEADINGS = set(tokenize("creation crafting obtaining construction recipe building natural generation spawning location"))
CANDIDATE_PAGES = 3
VANILLA_MIN_SCORE = MIN_SCORE / 2
# Minecraft Wiki sections score lower; this brings them to the Eden wiki's range for confidence().
VANILLA_SCALE = MIN_SCORE / VANILLA_MIN_SCORE


def plan(question: str, eden: Outcome) -> str:
    """When to ask minecraft.wiki: "search" (properly), or "check" (Eden is confident, so only
    add minecraft.wiki if one of its page titles is exactly what was asked about)."""
    strong = eden.confidence >= STRONG_SCORE and not eden.uncertain
    return "check" if strong and not VANILLA_CUE.search(question) else "search"


def combine(question: str, eden: Outcome, vanilla: list[Result], vanilla_titled: bool, total: int) -> list[Result]:
    """Orders and trims both sources. Vanilla goes first when the question says "vanilla", when
    a minecraft.wiki page is named after the subject and no Eden page is, or when Eden found nothing."""
    if not vanilla:
        return eden.results[:total]
    eden_titled = bool(eden.results) and eden.results[0].title_match
    vanilla_first = (
        not eden.results
        or bool(VANILLA_CUE.search(question))
        or (vanilla_titled and not eden_titled)
    )
    if vanilla_first:
        head = vanilla[:max(1, total - 1)] if eden.results else vanilla[:total]
        return head + eden.results[:total - len(head)]
    head = eden.results[:max(1, total - 1)]
    return head + vanilla[:total - len(head)]



def confidence(results: list[Result], eden: Outcome) -> str:
    """How sure Merl is about the top result: "sure", "maybe" or "guess"."""
    top = results[0]
    score = top.score * (VANILLA_SCALE if top.section.vanilla else 1)
    uncertain = eden.uncertain and not top.section.vanilla
    if score < GUESS_SCORE or (uncertain and not eden.corrections):
        return "guess"
    if not uncertain and (score >= SURE_SCORE or (top.title_match and score >= SURE_TITLE_SCORE)):
        return "sure"
    return "maybe"


def title_matches(title: str, asked: set[str]) -> bool:
    """Every word of the title appears in the question, allowing typos and word endings
    ("enchantmnt table" names "Enchanting Table")."""
    terms = set(tokenize(title))
    return bool(terms) and all(any(_similar(t, q) for q in asked) for t in terms)


def _similar(a: str, b: str) -> bool:
    if a == b:
        return True
    if min(len(a), len(b)) >= 4 and (a.startswith(b) or b.startswith(a)):
        return True
    limit = 1 if max(len(a), len(b)) < 8 else 2
    return min(len(a), len(b)) >= 4 and edit_distance(a, b, limit) <= limit


def keywords(question: str) -> str:
    """The question as search words, with slang spelled out and "vanilla"/"minecraft" removed."""
    out = []
    for word, _ in words(question):
        if word not in CUE_WORDS:
            out.append(SYNONYMS.get(word, word))
    return " ".join(out[:10])


class TTLCache:
    def __init__(self, size: int, seconds: float):
        self.size, self.seconds = size, seconds
        self.data: OrderedDict = OrderedDict()

    def get(self, key):
        item = self.data.get(key)
        if item is None or time.monotonic() - item[0] > self.seconds:
            self.data.pop(key, None)
            return None
        self.data.move_to_end(key)
        return item[1]

    def put(self, key, value):
        self.data[key] = (time.monotonic(), value)
        self.data.move_to_end(key)
        while len(self.data) > self.size:
            self.data.popitem(last=False)


class VanillaWiki:
    def __init__(self, base_url: str, timeout: float = 8):
        self.base_url = base_url.rstrip("/")
        self.api = f"{self.base_url}/api.php"
        self.timeout = aiohttp.ClientTimeout(total=timeout)
        self.session: aiohttp.ClientSession | None = None
        self.titles_cache = TTLCache(256, 24 * 3600)
        self.pages_cache = TTLCache(64, 12 * 3600)

    def url(self, section: Section) -> str:
        url = f"{self.base_url}/w/{quote(section.path, safe='/:()_,')}"
        return url + (f"#{quote(section.anchor, safe='._-')}" if section.anchor else "")

    async def close(self):
        if self.session:
            await self.session.close()

    async def _get(self, params: dict) -> dict:
        if self.session is None or self.session.closed:
            self.session = aiohttp.ClientSession(timeout=self.timeout, headers={"User-Agent": USER_AGENT})
        async with self.session.get(self.api, params={**params, "format": "json", "formatversion": "2"}) as resp:
            resp.raise_for_status()
            return await resp.json()

    async def titles(self, query: str) -> list[str]:
        cached = self.titles_cache.get(query)
        if cached is not None:
            return cached
        found = await self._search(query)
        self.titles_cache.put(query, found)
        return found

    async def _search(self, query: str, retry: bool = True) -> list[str]:
        data = await self._get({
            "action": "query", "list": "search", "srnamespace": "0", "srlimit": "8", "srprop": "",
            "srinfo": "suggestion", "srsearch": f"{query} -intitle:Edition -intitle:Snapshot",
        })
        found = [hit["title"] for hit in data["query"]["search"] if not SKIP_TITLES.search(hit["title"])]
        # Typos: retry once with the wiki's own "did you mean".
        suggestion = data["query"].get("searchinfo", {}).get("suggestion", "")
        suggestion = suggestion.replace("-intitle:Edition", "").replace("-intitle:Snapshot", "").strip()
        if not found and retry and suggestion and suggestion != query:
            return await self._search(suggestion, retry=False)
        return found

    async def page(self, title: str) -> list[Section]:
        cached = self.pages_cache.get(title)
        if cached is not None:
            return cached
        data = await self._get({"action": "parse", "page": title, "prop": "text", "redirects": "1"})
        # Redirects resolve to the real page ("Nether portal" -> "Nether Portal").
        sections = extract_sections(data["parse"]["text"], data["parse"]["title"])
        self.pages_cache.put(title, sections)
        return sections

    async def recipe_grid(self, title: str, item: str):
        """The crafting grid on the page for the item ("Bed"), or None. Never raises."""
        from recipes import find_grid

        key = f"grid:{title}"
        cached = self.pages_cache.get(key)
        if cached is not None:
            return cached or None
        try:
            data = await self._get({"action": "parse", "page": title, "prop": "text", "redirects": "1"})
            # A redirect to a different thing ("Waypoint Hub" → some other page) isn't this item's recipe.
            if set(tokenize(data["parse"]["title"])) != set(tokenize(item)):
                self.pages_cache.put(key, [])
                return None
            grid = find_grid(data["parse"]["text"], self.base_url, item)
        except Exception:
            log.debug("No crafting grid for %s", title, exc_info=True)
            return None
        self.pages_cache.put(key, grid or [])
        return grid

    async def named_page(self, title: str, question: str) -> Result | None:
        """The best section of the page with exactly this title ("Iron Ingot") for the question. Never raises."""
        try:
            sections = await self.page(title)
        except (aiohttp.ClientError, asyncio.TimeoutError, KeyError, ValueError) as e:
            log.debug("No Minecraft Wiki page %r: %s", title, e)
            return None
        if not sections or set(tokenize(sections[0].page_title)) != set(tokenize(title)):
            return None  # redirected somewhere else
        hints = HOW_TO_HEADINGS if HOW_TO.search(question) else set()
        index = Index(sections, {sections[0].path: 1.5}, hints)
        found = index.find(keywords(question), 1, 0).results or index.find(title, 1, 0).results
        if not found:
            return None
        found[0].title_match = True
        return found[0]

    async def search(self, question: str, limit: int = 2, require_title_match: bool = False) -> tuple[list[Result], bool]:
        """Best minecraft.wiki sections for the question, and whether the top page is named after
        what was asked. Never raises: errors mean no results."""
        query = keywords(question)
        if not tokenize(query):
            return [], False
        try:
            titles = (await self.titles(query))[:CANDIDATE_PAGES]
            asked = set(tokenize(query))
            titled = bool(titles) and title_matches(titles[0], asked)
            if require_title_match:
                if not titled:
                    return [], False
                titles, limit = titles[:1], 1
            if not titles:
                return [], False
            pages = await asyncio.gather(*(self.page(t) for t in titles), return_exceptions=True)
        except (aiohttp.ClientError, asyncio.TimeoutError, KeyError, ValueError) as e:
            log.warning("Minecraft Wiki lookup failed for %r: %s", question, e)
            return [], False
        for p in pages:
            if isinstance(p, BaseException):
                log.warning("Minecraft Wiki page fetch failed: %s", p)
        sections = [s for p in pages if isinstance(p, list) for s in p]
        if not sections:
            return [], False
        # The wiki's own ranking counts too: its first hits get a head start.
        priors = {}
        for rank, path in enumerate(dict.fromkeys(s.path for s in sections)):
            priors[path] = (1.5, 0.75)[rank] if rank < 2 else 0.0
        hints = HOW_TO_HEADINGS if HOW_TO.search(question) else set()
        return Index(sections, priors, hints).find(query, limit, VANILLA_MIN_SCORE).results, titled


# Vanilla names that are also everyday words: only counted when the question names nothing more specific.
AMBIGUOUS_NAMES = {"light", "air", "fire", "end", "note", "target", "lead", "map", "book", "key", "sign", "bell", "test",
                   "vault", "spawn", "speed", "luck", "stone", "water", "glass", "string", "stick", "bowl", "paper",
                   "arrow", "bread", "cake", "egg", "bone", "clock", "compass", "chain", "barrier", "jigsaw", "piston",
                   # The game's names for technical things ("Item" is a dropped item).
                   "item", "player", "marker", "interaction", "potion"}
_NAMES: list[tuple[str, frozenset]] | None = None


def _vanilla_names() -> list[tuple[str, frozenset]]:
    global _NAMES
    if _NAMES is None:
        try:
            names = json.loads((Path(__file__).parent / "data" / "minecraft_names.json").read_text("utf-8"))
        except (OSError, ValueError):
            names = []
        # Variants that aren't pages of their own ("Potted Fern", "Creeper Wall Head", "Pig Spawn Egg").
        names = [n for n in names if not n.startswith("Potted ") and " Wall " not in n and not n.endswith("Spawn Egg")]
        _NAMES = [(n, frozenset(tokenize(n))) for n in names if tokenize(n)]
    return _NAMES


def named_thing(question: str) -> str | None:
    """The vanilla block, item, mob, biome, enchantment or effect a question names ("how many uses are there for iron
    ingots?" → "Iron Ingot"): the name with the most words that are all in the question."""
    asked = set(tokenize(question))
    best = None
    for name, terms in _vanilla_names():
        if not terms <= asked or (len(terms) == 1 and (name.lower() in AMBIGUOUS_NAMES or len(name) < 4)):
            continue
        if best is None or len(terms) > len(best[1]) or (len(terms) == len(best[1]) and len(name) < len(best[0])):
            best = (name, terms)
    return best[0] if best else None


def extract_sections(html: str, title: str) -> list[Section]:
    content = BeautifulSoup(html, "html.parser")
    root = content.find("div", class_="mw-parser-output") or content
    for tag in root.select(REMOVE):
        tag.decompose()
    # Drop whole chapters players don't need (History, Trivia, Gallery…), up to the next h2.
    for wrapper in root.find_all(lambda t: t.name == "h2" or (t.name == "div" and "mw-heading2" in (t.get("class") or []))):
        if wrapper.decomposed or (wrapper.name == "h2" and wrapper.parent and "mw-heading2" in (wrapper.parent.get("class") or [])):
            continue
        if wrapper.get_text(" ", strip=True).lower() not in SKIP_SECTIONS:
            continue
        node = wrapper.next_sibling
        while node is not None and not _is_h2(node):
            following = node.next_sibling
            node.extract()
            node = following
        wrapper.decompose()
    # Chapters whose text all sits in subsections ("Drops" → "On death") have nothing to show.
    return [s for s in split_sections(root, title.replace(" ", "_"), title, vanilla=True) if s.text]


def _is_h2(node) -> bool:
    return getattr(node, "name", None) == "h2" or (
        getattr(node, "name", None) == "div" and "mw-heading2" in (node.get("class") or []))

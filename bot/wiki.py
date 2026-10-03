"""Fetches public Wiki.js pages and splits them into heading sections."""

import asyncio
import logging
import re
from dataclasses import dataclass

import aiohttp
from bs4 import BeautifulSoup, NavigableString, Tag

log = logging.getLogger(__name__)

HEADINGS = ("h1", "h2", "h3")
LINE_BLOCKS = ("p", "li", "tr", "div", "dt", "dd", "blockquote", "pre", "details", "h4", "h5", "h6")
# Marks text inside collapsed <details> blocks so excerpts can show it as a Discord spoiler.
SPOILER_START, SPOILER_END = "\u27e6", "\u27e7"
PAGE_LIST_QUERY = "{ pages { list(limit: 5000) { path title locale isPublished } } }"


@dataclass
class Section:
    path: str
    page_title: str
    heading: str
    anchor: str
    text: str
    # From the Minecraft Wiki instead of the community wiki.
    vanilla: bool = False


async def fetch_page_list(session: aiohttp.ClientSession, wiki_url: str) -> list[dict]:
    async with session.post(f"{wiki_url}/graphql", json={"query": PAGE_LIST_QUERY}) as resp:
        resp.raise_for_status()
        data = await resp.json()
    pages = data["data"]["pages"]["list"]
    return [p for p in pages if p["isPublished"] and p["locale"] == "en"]


def extract_sections(html: str, path: str, page_title: str) -> list[Section]:
    soup = BeautifulSoup(html, "html.parser")
    content = soup.find("template", attrs={"slot": "contents"})
    if content is None:
        return []
    # Text inside <template> is parsed as TemplateString, which get_text() skips.
    content = BeautifulSoup(content.decode_contents(), "html.parser")
    return split_sections(content, path, page_title)


def split_sections(content: BeautifulSoup, path: str, page_title: str, vanilla: bool = False) -> list[Section]:
    """Splits parsed page content into one Section per h1-h3 heading."""
    for tag in content.find_all(["style", "script"]):
        tag.decompose()
    for tag in content.find_all("a", class_="toc-anchor"):
        tag.decompose()
    for details in content.find_all("details"):
        for summary in details.find_all("summary"):
            summary.decompose()
        details.insert(0, NavigableString(f" {SPOILER_START} "))
        details.append(NavigableString(f" {SPOILER_END} "))
    # Keep the page's line structure (paragraphs, list items, table rows) so
    # excerpts can show whole sentences and bullet lists.
    for tr in content.find_all("tr"):
        cells = [c.get_text(" ", strip=True) for c in tr.find_all(["td", "th"])]
        tr.clear()
        tr.append(NavigableString(" | ".join(c for c in cells if c)))
    for li in content.find_all("li"):
        li.insert(0, NavigableString("• "))
    for tag in content.find_all(LINE_BLOCKS):
        tag.append(NavigableString("\n"))
    for br in content.find_all("br"):
        br.replace_with(NavigableString("\n"))

    sections = [Section(path, page_title, page_title, "", "", vanilla)]
    parts: list[str] = []

    def flush():
        text = re.sub(r"[^\S\n]+", " ", "".join(parts))
        sections[-1].text = re.sub(r" ?\n[\s]*", "\n", text).strip()
        parts.clear()

    for node in content.descendants:
        if isinstance(node, Tag) and node.name in HEADINGS:
            flush()
            heading = node.get_text(" ", strip=True)
            sections.append(Section(path, page_title, heading, node.get("id", ""), "", vanilla))
        elif isinstance(node, NavigableString) and node.find_parent(HEADINGS) is None:
            parts.append(str(node))
    flush()
    return [s for s in sections if s.text or s.anchor]


async def fetch_sections(wiki_url: str, concurrency: int = 4) -> list[Section]:
    """Downloads every published page and returns all of their sections."""
    timeout = aiohttp.ClientTimeout(total=30)
    headers = {"User-Agent": "ExplorersEdenWikiBot/1.0"}
    sem = asyncio.Semaphore(concurrency)

    async with aiohttp.ClientSession(timeout=timeout, headers=headers) as session:
        pages = await fetch_page_list(session, wiki_url)

        async def fetch(page: dict) -> list[Section]:
            async with sem:
                try:
                    async with session.get(f"{wiki_url}/{page['path']}") as resp:
                        resp.raise_for_status()
                        html = await resp.text()
                except (aiohttp.ClientError, asyncio.TimeoutError) as e:
                    log.warning("Failed to fetch %s: %s", page["path"], e)
                    return []
                finally:
                    await asyncio.sleep(0.1)
            return extract_sections(html, page["path"], page["title"])

        results = await asyncio.gather(*(fetch(p) for p in pages))

    sections = [s for page_sections in results for s in page_sections]
    log.info("Fetched %d sections from %d pages", len(sections), len(pages))
    return sections

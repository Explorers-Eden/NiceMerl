"""Recipe pictures for "how do I craft X?".

Our packs' recipes come from the website, whose CI renders every recipe to a PNG and lists them in a manifest
(name, result, pack, picture). Vanilla recipes are drawn here from the Minecraft Wiki's crafting grid: the
item sprites it shows per slot, laid out like the game's crafting table.
"""

import io
import logging
import re
from dataclasses import dataclass
from urllib.parse import urljoin

import aiohttp
from bs4 import BeautifulSoup

from search import tokenize

log = logging.getLogger("nicemerl")

# Sprites are 32 px; drawn 2× so they stay crisp in Discord.
SCALE = 2
SLOT = 36 * SCALE
ITEM = 32 * SCALE
PAD = 14 * SCALE // 2
GAP = 12 * SCALE
ARROW = 44 * SCALE
OUT_SLOT = 52 * SCALE
SPRITE_CACHE = 256


@dataclass
class Picture:
    """What to show: a picture on the web (url) or one drawn here (png), and a line about it."""
    line: str
    url: str = ""
    png: bytes = b""


@dataclass
class Recipe:
    name: str
    words: frozenset
    pack: str
    url: str
    crafting: bool


class Manifest:
    """The website's recipe list, kept in memory and refreshed with the wiki."""

    def __init__(self, url: str):
        self.url = url
        self.recipes: list[Recipe] = []

    async def refresh(self, session: aiohttp.ClientSession) -> bool:
        if not self.url:
            return False
        try:
            async with session.get(self.url, timeout=aiohttp.ClientTimeout(total=60)) as resp:
                resp.raise_for_status()
                entries = await resp.json(content_type=None)
        except Exception:
            log.warning("Could not load the recipe pictures, keeping the previous ones", exc_info=True)
            return False
        recipes = []
        for e in entries:
            if not e.get("imageUrl"):
                continue
            name = re.sub(r"\s*×\s*\d+$", "", e.get("result") or e.get("name") or "")
            recipes.append(Recipe(name, frozenset(tokenize(name)), e.get("datapack") or "",
                                  urljoin(self.url, e["imageUrl"]), "crafting" in (e.get("type") or "").lower()))
        self.recipes = recipes
        log.info("Found %d recipe pictures", len(recipes))
        return True

    def find(self, item: str) -> tuple[list[Recipe], list[Recipe]]:
        """Crafting recipes named exactly like the item, and looser matches (variants with all its words,
        or stonecutter recipes like Nice Things' mini blocks, which share the vanilla block's name)."""
        words = frozenset(tokenize(item)) - {"recipe"}
        if not words:
            return [], []
        exact = [r for r in self.recipes if r.words == words and r.crafting]
        loose = [r for r in self.recipes if words <= r.words and r not in exact]
        return exact, loose


def manifest_picture(recipes: list[Recipe], site: str) -> Picture:
    first = recipes[0]
    more = f" (+{len(recipes) - 1} variants, [all recipes]({site}/recipes))" if len(recipes) > 1 else ""
    return Picture(f"📜 Recipe: **{first.name}** · {first.pack}{more}", url=first.url)


@dataclass
class Grid:
    inputs: list  # 9 sprite URLs or "" for empty slots
    output: str
    count: str
    title: str


def find_grid(html: str, base: str, item: str) -> Grid | None:
    """The crafting grid for the item on its Minecraft Wiki page: the one whose result is named like it, else
    the page's own item (its result isn't a link, so it has no title), else the first one."""
    soup = BeautifulSoup(html, "html.parser")
    grids = []
    for ui in soup.select("span.mcui-Crafting_Table, span.mcui-Crafting\\_Table"):
        rows = ui.select(".mcui-input .mcui-row")
        inputs = []
        for row in rows[:3]:
            for slot in row.select(".invslot")[:3]:
                img = slot.select_one(".invslot-item img")
                inputs.append(urljoin(base, img["src"]) if img else "")
        out = ui.select_one(".mcui-output .invslot-item img")
        if len(inputs) != 9 or out is None:
            continue
        count = ui.select_one(".mcui-output .invslot-stacksize")
        link = out.find_parent("a")
        title = link.get("title", "") if link else ""
        grids.append(Grid(inputs, urljoin(base, out["src"]), count.get_text(strip=True) if count else "", title))
    if not grids:
        return None
    words = set(tokenize(item))
    return (next((g for g in grids if words == set(tokenize(g.title))), None)
            or next((g for g in grids if not g.title), None) or grids[0])


class Drawer:
    """Draws crafting grids with item sprites from the Minecraft Wiki, caching the sprites."""

    def __init__(self):
        self.sprites: dict[str, object] = {}

    async def sprite(self, session: aiohttp.ClientSession, url: str):
        from PIL import Image

        if url in self.sprites:
            return self.sprites[url]
        async with session.get(url, timeout=aiohttp.ClientTimeout(total=15)) as resp:
            resp.raise_for_status()
            data = await resp.read()
        image = Image.open(io.BytesIO(data)).convert("RGBA").resize((ITEM, ITEM), Image.NEAREST)
        if len(self.sprites) >= SPRITE_CACHE:
            self.sprites.pop(next(iter(self.sprites)))
        self.sprites[url] = image
        return image

    async def draw(self, session: aiohttp.ClientSession, grid: Grid) -> bytes:
        from PIL import Image, ImageDraw, ImageFont

        width = PAD * 2 + SLOT * 3 + GAP * 2 + ARROW + OUT_SLOT
        height = PAD * 2 + SLOT * 3
        panel = Image.new("RGBA", (width, height), (198, 198, 198, 255))
        draw = ImageDraw.Draw(panel)

        def slot(x, y, size):
            draw.rectangle([x, y, x + size - 1, y + size - 1], fill=(139, 139, 139, 255))
            draw.line([x, y, x + size - 1, y], fill=(55, 55, 55, 255), width=SCALE)
            draw.line([x, y, x, y + size - 1], fill=(55, 55, 55, 255), width=SCALE)
            draw.line([x, y + size - 1, x + size - 1, y + size - 1], fill=(255, 255, 255, 255), width=SCALE)
            draw.line([x + size - 1, y, x + size - 1, y + size - 1], fill=(255, 255, 255, 255), width=SCALE)

        for i, url in enumerate(grid.inputs):
            x, y = PAD + (i % 3) * SLOT, PAD + (i // 3) * SLOT
            slot(x, y, SLOT)
            if url:
                panel.alpha_composite(await self.sprite(session, url), (x + (SLOT - ITEM) // 2, y + (SLOT - ITEM) // 2))

        ax, ay = PAD + SLOT * 3 + GAP, height // 2
        shaft = ARROW * 2 // 3
        draw.rectangle([ax, ay - 5 * SCALE, ax + shaft, ay + 5 * SCALE], fill=(139, 139, 139, 255))
        draw.polygon([(ax + shaft, ay - 14 * SCALE), (ax + ARROW, ay), (ax + shaft, ay + 14 * SCALE)], fill=(139, 139, 139, 255))

        ox, oy = ax + ARROW + GAP, (height - OUT_SLOT) // 2
        slot(ox, oy, OUT_SLOT)
        panel.alpha_composite(await self.sprite(session, grid.output), (ox + (OUT_SLOT - ITEM) // 2, oy + (OUT_SLOT - ITEM) // 2))
        if grid.count and grid.count != "1":
            try:
                font = ImageFont.load_default(size=16 * SCALE)
            except TypeError:  # Pillow before 10.1
                font = ImageFont.load_default()
            tx, ty = ox + OUT_SLOT - 10 * SCALE, oy + OUT_SLOT - 6 * SCALE
            draw.text((tx + SCALE, ty + SCALE), grid.count, font=font, fill=(63, 63, 63, 255), anchor="rs")
            draw.text((tx, ty), grid.count, font=font, fill=(255, 255, 255, 255), anchor="rs")

        out = io.BytesIO()
        panel.save(out, "PNG")
        return out.getvalue()

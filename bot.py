import logging
import random
import re
import time
from pathlib import Path

import discord
from discord.ext import tasks

import config
from search import Index, tokenize
from wiki import fetch_sections

log = logging.getLogger("nicemerl")

BOT_NAME = "NiceMerl"
MERL_PINK = 0xF06EAA
ASSETS = Path(__file__).parent / "assets"
FOOTER = "NiceMerl · a fan homage to Merl from Minecraft Earth"

FOUND_LINES = [
    "Ooh, I know where to look!",
    "Here's what I found in the wiki!",
    "Peanut Butter and I dug this up for you!",
    "Let's go exploring!",
    "Found it! I think…",
]
NOT_FOUND_LINES = [
    "Hmm, I couldn't find that one, even Peanut Butter is stumped!",
    "I looked everywhere but the wiki doesn't seem to cover that.",
    "No luck this time! Maybe try different words?",
]
GREETING = re.compile(r"^\W*(hi|hey|hello|hallo|heya|hiya|yo|sup|moin|servus|merl|nicemerl)\b", re.I)


class NiceMerl(discord.Client):
    def __init__(self):
        intents = discord.Intents.default()
        intents.message_content = True
        super().__init__(intents=intents, allowed_mentions=discord.AllowedMentions.none())
        self.index = Index([])
        self.last_question: dict[int, float] = {}

    async def setup_hook(self):
        self.refresh_index.change_interval(hours=config.REINDEX_HOURS)
        self.refresh_index.start()

    @tasks.loop(hours=6)
    async def refresh_index(self):
        await self.reindex()

    async def reindex(self) -> bool:
        try:
            sections = await fetch_sections(config.WIKI_URL)
        except Exception:
            log.exception("Reindex failed, keeping the previous index")
            return False
        if not sections:
            log.warning("Reindex returned no sections, keeping the previous index")
            return False
        self.index = Index(sections)
        return True

    async def on_ready(self):
        log.info("Logged in as %s, answering in channel %s", self.user, config.CHANNEL_ID)

    async def on_message(self, message: discord.Message):
        if message.author.bot or message.channel.id != config.CHANNEL_ID:
            return
        content = message.content.strip()
        if not content:
            return

        if content == "!reindex":
            if message.author.guild_permissions.manage_guild:
                async with message.channel.typing():
                    ok = await self.reindex()
                pages = len({s.path for s in self.index.sections})
                await message.reply(f"Reindexed {pages} pages." if ok else "Reindex failed, check the logs.")
            return

        now = time.monotonic()
        if now - self.last_question.get(message.author.id, 0) < config.COOLDOWN_SECONDS:
            return
        self.last_question[message.author.id] = now

        question = re.sub(r"<@!?\d+>", "", content).strip()
        if not tokenize(question):
            if GREETING.match(question) or self.user in message.mentions:
                await self.send_with_thumbnail(message, self.hello_embed(), "thumb_hello.png")
            return

        results = self.index.search(question, limit=config.RESULTS)
        if results:
            await message.reply(embed=self.results_embed(results), mention_author=False)
        else:
            await self.send_with_thumbnail(message, self.not_found_embed(), "thumb_notfound.png")

    async def send_with_thumbnail(self, message: discord.Message, embed: discord.Embed, image: str):
        file = discord.File(ASSETS / image, filename=image)
        embed.set_thumbnail(url=f"attachment://{image}")
        await message.reply(embed=embed, file=file, mention_author=False)

    def branded(self, embed: discord.Embed) -> discord.Embed:
        embed.set_author(name=BOT_NAME, icon_url=self.user.display_avatar.url if self.user else None)
        embed.set_footer(text=FOOTER)
        return embed

    def results_embed(self, results) -> discord.Embed:
        blocks = []
        for r in results:
            s = r.section
            url = f"{config.WIKI_URL}/{s.path}" + (f"#{s.anchor}" if s.anchor else "")
            title = s.page_title if s.heading in ("", s.page_title) else f"{s.page_title} › {s.heading}"
            blocks.append(f"**[{title}]({url})**\n-# {project_name(s.path)}\n> {r.excerpt}")
        return self.branded(discord.Embed(
            title=random.choice(FOUND_LINES),
            description="\n\n".join(blocks)[:4096],
            color=MERL_PINK,
        ))

    def not_found_embed(self) -> discord.Embed:
        return self.branded(discord.Embed(
            title=random.choice(NOT_FOUND_LINES),
            description=f"Try other keywords, or have a look around the [wiki]({config.WIKI_URL}) yourself.",
            color=MERL_PINK,
        ))

    def hello_embed(self) -> discord.Embed:
        return self.branded(discord.Embed(
            title="Hi there! I'm NiceMerl 👋",
            description=(
                "Ask me anything about the Explorer's Eden projects, like structures, enchantments, "
                "mob variants or warping, and I'll find the right page in the "
                f"[wiki]({config.WIKI_URL}) for you!\n\n"
                "-# Try: *how do I get a boss key?*"
            ),
            color=MERL_PINK,
        ))


def project_name(path: str) -> str:
    segment = path.split("/")[0]
    if segment == "home":
        return "Wiki"
    return segment.replace("_", " ").title()


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    if not config.DISCORD_TOKEN or not config.CHANNEL_ID:
        raise SystemExit("DISCORD_TOKEN and CHANNEL_ID must be set (see .env.example)")
    NiceMerl().run(config.DISCORD_TOKEN, log_handler=None)


if __name__ == "__main__":
    main()

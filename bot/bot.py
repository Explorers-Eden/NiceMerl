import logging
import re
import time
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

import discord
from discord.ext import tasks

import config
import personality
from personality import pick
from search import Index, Outcome, Result, tokenize
from vanilla import VanillaWiki, combine, plan
from wiki import fetch_sections

log = logging.getLogger("nicemerl")

BOT_NAME = "NiceMerl"
COMMUNITY = "Explorer's Eden"
MERL_PINK = 0xF06EAA
ASSETS = Path(__file__).parent / "assets"
FOOTER = "NiceMerl · a fan homage to Merl from Minecraft"
# Small talk Merl answers with a reaction as well.
REACTIONS = {"thanks": "💗", "compliment": "💗", "love": "💗", "goodbye": "👋", "peanut_butter": "🐱", "joke": "😄"}


class NiceMerl(discord.Client):
    def __init__(self):
        intents = discord.Intents.default()
        intents.message_content = True
        super().__init__(intents=intents, allowed_mentions=discord.AllowedMentions.none())
        self.index = Index([])
        self.vanilla = VanillaWiki(config.VANILLA_WIKI_URL) if config.VANILLA_WIKI else None
        self.last_question: dict[int, float] = {}
        self.timezone = ZoneInfo(config.TIMEZONE)

    async def setup_hook(self):
        self.refresh_index.change_interval(hours=config.REINDEX_HOURS)
        self.refresh_index.start()
        self.rotate_status.start()

    async def close(self):
        if self.vanilla:
            await self.vanilla.close()
        await super().close()

    @tasks.loop(hours=6)
    async def refresh_index(self):
        await self.reindex()

    @tasks.loop(minutes=15)
    async def rotate_status(self):
        await self.change_presence(activity=discord.CustomActivity(name=pick("status")))

    @rotate_status.before_loop
    async def before_rotate_status(self):
        await self.wait_until_ready()

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
                await message.reply(pick("reindex_start"), mention_author=False)
                async with message.channel.typing():
                    ok = await self.reindex()
                pages = len({s.path for s in self.index.sections})
                await message.reply(pick("reindex_done", pages=str(pages)) if ok else pick("reindex_failed"),
                                    mention_author=False)
            return

        now = time.monotonic()
        if now - self.last_question.get(message.author.id, 0) < config.COOLDOWN_SECONDS:
            return
        self.last_question[message.author.id] = now

        question = re.sub(r"<@!?\d+>", "", content).strip()
        user = message.author.display_name

        talk = personality.small_talk(question)
        if talk == "greeting" or (talk is None and not tokenize(question)):
            if talk or self.user in message.mentions:
                await self.send_with_thumbnail(message, self.hello_embed(user), "thumb_hello.png")
            return
        if talk:
            await message.reply(pick(talk, user=user, community=COMMUNITY), mention_author=False)
            if talk in REACTIONS:
                await self.react(message, REACTIONS[talk])
            return

        if not self.index.sections:
            await message.reply(pick("still_reading"), mention_author=False)
            return

        outcome = self.index.find(question, limit=config.RESULTS)
        results = outcome.results
        if self.vanilla:
            async with message.channel.typing():
                found, titled = await self.vanilla.search(
                    question, limit=2, require_title_match=plan(question, outcome) == "check")
            results = combine(question, outcome, found, titled, config.RESULTS)

        if results:
            await message.reply(embed=self.results_embed(results, outcome), mention_author=False)
        else:
            await self.send_with_thumbnail(message, self.not_found_embed(), "thumb_idk.png")

    async def react(self, message: discord.Message, emoji: str):
        try:
            await message.add_reaction(emoji)
        except discord.HTTPException:
            pass  # Missing "Add Reactions" permission is fine.

    async def send_with_thumbnail(self, message: discord.Message, embed: discord.Embed, image: str):
        file = discord.File(ASSETS / image, filename=image)
        embed.set_thumbnail(url=f"attachment://{image}")
        await message.reply(embed=embed, file=file, mention_author=False)

    def branded(self, embed: discord.Embed) -> discord.Embed:
        embed.set_author(name=BOT_NAME, icon_url=self.user.display_avatar.url if self.user else None)
        embed.set_footer(text=FOOTER)
        return embed

    def results_embed(self, results: list[Result], outcome: Outcome) -> discord.Embed:
        has_eden = any(not r.section.vanilla for r in results)
        has_vanilla = any(r.section.vanilla for r in results)
        eden_corrected = has_eden and outcome.corrections and not results[0].section.vanilla
        if eden_corrected:
            title = pick("found_typo", term=", ".join(dict.fromkeys(outcome.corrections.values())))
        elif has_eden and has_vanilla:
            title = pick("found_both", community=COMMUNITY)
        elif has_vanilla:
            title = pick("found_vanilla")
        else:
            title = pick("found")

        blocks = []
        for r in results:
            s = r.section
            title_text = s.page_title if s.heading in ("", s.page_title) else f"{s.page_title} › {s.heading}"
            quote = "\n".join(f"> {line}" for line in r.excerpt.split("\n"))
            if s.vanilla:
                blocks.append(f"**[{title_text}]({self.vanilla.url(s)})**\n-# 📗 Minecraft Wiki\n{quote}")
            else:
                url = f"{config.WIKI_URL}/{s.path}" + (f"#{s.anchor}" if s.anchor else "")
                blocks.append(f"**[{title_text}]({url})**\n-# {project_name(s.path)}\n{quote}")

        extra = ""
        if config.HELP_CHANNEL_ID:
            extra += f"\n\n-# Still stuck? Ask the community in {help_channel()}."
        if aside := personality.aside():
            extra += f"\n-# 🐱 {aside}"
        return self.branded(discord.Embed(
            title=title[:256],
            description="\n\n".join(blocks)[:4096 - len(extra)] + extra,
            color=MERL_PINK,
        ))

    def not_found_embed(self) -> discord.Embed:
        return self.branded(discord.Embed(
            title=pick("not_found", community=COMMUNITY),
            description=(
                f"-# Try other keywords, have a look around the [wiki]({config.WIKI_URL}) yourself, "
                f"or post your question in {help_channel()} so someone can help you."
                if config.HELP_CHANNEL_ID else
                f"-# Try other keywords, or have a look around the [wiki]({config.WIKI_URL}) yourself."
            ),
            color=MERL_PINK,
        ))

    def hello_embed(self, user: str) -> discord.Embed:
        greeting = pick(personality.greeting_pool(datetime.now(self.timezone)), user=user)
        vanilla = (" I can also look things up in the [Minecraft Wiki](https://minecraft.wiki) "
                   "for vanilla questions." if self.vanilla else "")
        return self.branded(discord.Embed(
            title=greeting[:256],
            description=(
                "I'm NiceMerl! Ask me anything about the Explorer's Eden projects, like structures, "
                "enchantments, mob variants or warping, and I'll find the right page in the "
                f"[wiki]({config.WIKI_URL}) for you!{vanilla}\n\n"
                + (f"If I can't help, post your question in {help_channel()} and someone else will.\n\n"
                   if config.HELP_CHANNEL_ID else "")
                + "-# Try: *how do I get a boss key?*"
                + (" or *how do I make a nether portal?*" if self.vanilla else "")
            ),
            color=MERL_PINK,
        ))


def help_channel() -> str:
    """Channel mention for #user-help; Discord renders it as a clickable channel name."""
    return f"<#{config.HELP_CHANNEL_ID}>"


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

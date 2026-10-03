import asyncio
import logging
import random
import re
import time
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

import discord
from discord.ext import tasks

import config
import personality
from memory import Memory, Visit
from personality import pick
from search import SURE_TITLE_SCORE, Index, Outcome, Result, tokenize
from state import State
from vanilla import VanillaWiki, combine, confidence, plan
from wiki import fetch_sections

log = logging.getLogger("nicemerl")

BOT_NAME = "NiceMerl"
COMMUNITY = "Explorer's Eden"
MERL_PINK = 0xF06EAA
ASSETS = Path(__file__).parent / "assets"
# Every reply shows one of these in the corner of its embed; add more merl_*/thumb_* images to use them too.
THUMBNAILS = sorted(p for p in ASSETS.iterdir() if p.name.startswith(("merl_", "thumb_"))
                    and p.suffix.lower() in (".png", ".jpg", ".jpeg", ".gif", ".webp"))
FOOTER = "NiceMerl · a fan homage to Merl from Minecraft"
# Small talk Merl answers with a reaction as well.
REACTIONS = {"thanks": "💗", "compliment": "💗", "love": "💗", "goodbye": "👋", "peanut_butter": "🐱",
             "pet_pb": "🐱", "joke": "😄", "hug": "🤗", "cheer": "🎉", "newcomer": "👋", "feeling_bad": "💗"}
# How long Merl "types" before replying, in seconds. Time spent looking things up counts towards it.
TYPING_SMALL_TALK = (0.4, 1.2)
TYPING_ANSWER = 0.8
TYPING_PER_RESULT = 0.3
TYPING_MAX = 2.5


class NiceMerl(discord.Client):
    def __init__(self):
        intents = discord.Intents.default()
        intents.message_content = True
        super().__init__(intents=intents, allowed_mentions=discord.AllowedMentions.none())
        self.index = Index([])
        self.vanilla = VanillaWiki(config.VANILLA_WIKI_URL) if config.VANILLA_WIKI else None
        self.last_question: dict[int, float] = {}
        self.memory = Memory()
        self.state = State(config.STATE_DIR)
        self.timezone = ZoneInfo(config.TIMEZONE)
        self.last_thumbnail: Path | None = None

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
        await self.change_presence(activity=discord.CustomActivity(name=personality.status(self.now())))

    @rotate_status.before_loop
    async def before_rotate_status(self):
        await self.wait_until_ready()

    def now(self) -> datetime:
        return datetime.now(self.timezone)

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
                await self.send(message, embed=self.text_embed(pick("reindex_start")))
                async with message.channel.typing():
                    ok = await self.reindex()
                pages = len({s.path for s in self.index.sections})
                await self.send(message, embed=self.text_embed(
                    pick("reindex_done", pages=str(pages)) if ok else pick("reindex_failed")))
            return

        started = time.monotonic()
        if started - self.last_question.get(message.author.id, 0) < config.COOLDOWN_SECONDS:
            return
        self.last_question[message.author.id] = started

        question = re.sub(r"<@!?\d+>", "", content).strip()
        user = message.author.display_name
        visit = self.memory.visit(message.author.id)
        now = time.time()
        returning = visit.returning(now)
        visit.seen_at = now
        await self.answer(message, question, user, visit, now, returning, started)

    async def answer(self, message: discord.Message, question: str, user: str, visit: Visit,
                     now: float, returning: bool, started: float):
        talk = personality.small_talk(question)
        # Merl only waits one message for an answer to "what are you up to?".
        awaiting, visit.asked_back_at = visit.awaiting_reply(now), 0.0
        # The same for "how are you?": "good, you?" is an answer, not a compliment.
        awaiting_feeling, visit.asked_feeling_at = visit.awaiting_feeling(now), 0.0
        if awaiting_feeling and (felt := personality.feeling(question)):
            pool, asked_back = felt
            text = pick(pool, user=user)
            if asked_back:
                text += " " + personality.moody("about_me", self.now().date(), user=user)
            elif extra := self.ask_back(pool, visit, now):
                text += " " + extra
            await self.say(message, started, content=text)
            if pool in REACTIONS:
                await self.react(message, REACTIONS[pool])
            return
        if talk is None and awaiting:
            if topic := personality.topic(question):
                await self.say(message, started, content=pick(f"reply_{topic}"))
                return

        if talk == "greeting" or (talk is None and not tokenize(question)):
            if talk or self.user in message.mentions:
                greeting = pick("welcome_back" if returning else personality.greeting_pool(self.now()), user=user)
                extra = self.ask_back("greeting", visit, now) or self.ask_feeling("greeting", greeting, visit, now)
                await self.say(message, started, embed=self.hello_embed(greeting, extra))
            return
        if talk:
            text = self.small_talk_line(talk, user, visit, now)
            if extra := self.ask_back(talk, visit, now) or self.ask_feeling(talk, text, visit, now):
                text += " " + extra
            await self.say(message, started, content=text)
            if talk in REACTIONS:
                await self.react(message, REACTIONS[talk])
            return

        if not self.index.sections:
            await self.send(message, embed=self.text_embed(pick("still_reading")))
            return

        prefix, search = personality.split_small_talk(question)
        outcome = self.index.find(search, limit=config.RESULTS)
        # "and in the nether?" right after a question: if it finds nothing good on its own,
        # search it together with the previous question.
        weak = not outcome.results or outcome.confidence < SURE_TITLE_SCORE
        if weak and personality.is_follow_up(search) and (previous := visit.recent_question(now)):
            combined = self.index.find(f"{previous} {search}", limit=config.RESULTS)
            if combined.confidence >= outcome.confidence:
                search, outcome = f"{previous} {search}", combined
        results = outcome.results
        if self.vanilla:
            async with message.channel.typing():
                found, titled = await self.vanilla.search(
                    search, limit=2, require_title_match=plan(search, outcome) == "check")
            results = combine(search, outcome, found, titled, config.RESULTS)

        asked = " ".join(tokenize(search))
        repeat = visit.is_repeat(asked, now)
        visit.question, visit.asked_at = asked, now
        if results:
            visit.page, visit.answered_at = results[0].section.page_title, now
            energy = personality.energy(question)
            embed = self.results_embed(results, self.answer_title(
                results, outcome, prefix=prefix, repeat=repeat, energy=energy, user=user), energy)
            await self.say(message, started, embed=embed, results=len(results))
        else:
            visit.page = ""
            await self.say(message, started, embed=self.not_found_embed(), results=1)

    def small_talk_line(self, talk: str, user: str, visit: Visit, now: float) -> str:
        if talk == "thanks" and (page := visit.recent_page(now)):
            return pick("thanks_answered", page=page)
        if talk == "peanut_butter":
            return personality.peanut_butter(self.now())
        if talk == "pet_pb":
            count = self.state.bump("pets")
            if personality.pet_milestone(count):
                return pick("pet_pb_milestone", count=f"{count:,}")
            return pick("pet_pb")
        return personality.moody(talk, self.now().date(), user=user, community=COMMUNITY)

    def ask_back(self, talk: str, visit: Visit, now: float) -> str | None:
        line = personality.ask_back(talk)
        if line:
            visit.asked_back_at = now
        return line

    def ask_feeling(self, talk: str, said: str, visit: Visit, now: float) -> str | None:
        """Sometimes asks how they are. After "how are you?" Merl listens for "good, you?" either way."""
        line = personality.ask_feeling(talk, said)
        if line or talk == "how_are_you":
            visit.asked_feeling_at = now
        return line

    async def say(self, message: discord.Message, started: float, *, content: str | None = None,
                  embed: discord.Embed | None = None, results: int = 0):
        """Replies after a short "typing…" pause, so Merl doesn't answer inhumanly fast."""
        if results:
            delay = min(TYPING_MAX, TYPING_ANSWER + TYPING_PER_RESULT * results)
        else:
            delay = random.uniform(*TYPING_SMALL_TALK)
        remaining = delay - (time.monotonic() - started)
        if remaining > 0:
            async with message.channel.typing():
                await asyncio.sleep(remaining)
        await self.send(message, embed=embed or self.text_embed(content or ""))

    async def send(self, message: discord.Message, *, embed: discord.Embed):
        """Every reply is an embed with a random picture of Merl in the corner."""
        kwargs = {}
        if THUMBNAILS:
            choices = [t for t in THUMBNAILS if t != self.last_thumbnail] or THUMBNAILS
            image = self.last_thumbnail = random.choice(choices)
            kwargs["file"] = discord.File(image, filename=image.name)
            embed.set_thumbnail(url=f"attachment://{image.name}")
        await message.reply(embed=embed, mention_author=False, **kwargs)

    def text_embed(self, text: str) -> discord.Embed:
        """A small embed for small talk and status messages."""
        return self.branded(discord.Embed(description=text[:4096], color=MERL_PINK))

    async def react(self, message: discord.Message, emoji: str):
        try:
            await message.add_reaction(emoji)
        except discord.HTTPException:
            pass  # Missing "Add Reactions" permission is fine.

    def branded(self, embed: discord.Embed) -> discord.Embed:
        embed.set_author(name=BOT_NAME, icon_url=self.user.display_avatar.url if self.user else None)
        embed.set_footer(text=FOOTER)
        return embed

    def answer_title(self, results: list[Result], outcome: Outcome, *, prefix: str | None, repeat: bool,
                     energy: str, user: str) -> str:
        has_eden = any(not r.section.vanilla for r in results)
        has_vanilla = any(r.section.vanilla for r in results)
        eden_corrected = has_eden and outcome.corrections and not results[0].section.vanilla
        sure = confidence(results, outcome)
        if eden_corrected:
            core = pick("found_typo", term=", ".join(dict.fromkeys(outcome.corrections.values())))
        elif repeat:
            core = pick("repeat_question")
        elif has_eden and has_vanilla:
            core = pick("found_both", community=COMMUNITY)
        elif has_vanilla:
            core = pick("found_vanilla" if sure == "sure" else f"found_vanilla_{sure}")
        else:
            core = pick(f"found_{sure}")
        return personality.headline(core, prefix=prefix, energy=energy, hour=self.now().hour, user=user,
                                    slip_ok=not eden_corrected)

    def results_embed(self, results: list[Result], title: str, energy: str) -> discord.Embed:
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
        if aside := personality.aside(self.now(), energy):
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

    def hello_embed(self, greeting: str, ask_back: str | None = None) -> discord.Embed:
        vanilla = (" I can also look things up in the [Minecraft Wiki](https://minecraft.wiki) "
                   "for vanilla questions." if self.vanilla else "")
        return self.branded(discord.Embed(
            title=(f"{greeting} {ask_back}" if ask_back else greeting)[:256],
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

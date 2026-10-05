import asyncio
import io
import logging
import random
import re
import time
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

import aiohttp
import discord
from discord.ext import tasks

import config
import personality
import palettes
import recipes
import semantic
from friends import Friend, Friends
from memory import Memory, Visit
from personality import pick
from search import SURE_TITLE_SCORE, Index, Outcome, Result, tokenize
from search import answer_line as search_answer_line
from state import State
from vanilla import VanillaWiki, combine, confidence, plan
from vanilla import named_thing as vanilla_named
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
        # The small meaning-based search model; None means keyword search only.
        self.embed = semantic.load(config.SEMANTIC_MODEL)
        self.vanilla = VanillaWiki(config.VANILLA_WIKI_URL) if config.VANILLA_WIKI else None
        self.last_question: dict[int, float] = {}
        self.memory = Memory()
        self.state = State(config.STATE_DIR)
        self.friends = Friends(config.STATE_DIR)
        self.timezone = ZoneInfo(config.TIMEZONE)
        self.last_thumbnail: Path | None = None
        # Recipe pictures: our packs' from the website, vanilla ones drawn from the Minecraft Wiki.
        self.recipes = recipes.Manifest(config.RECIPES_URL)
        self.drawer = recipes.Drawer()
        self.web: aiohttp.ClientSession | None = None
        # Block palettes: the blocks' colors, each person's last palette start, and the block icons drawn so far.
        self.block_colors = palettes.load()
        self.last_palette: dict[int, list[str]] = {}
        self.palette_icons: dict = {}

    async def setup_hook(self):
        self.refresh_index.change_interval(hours=config.REINDEX_HOURS)
        self.refresh_index.start()
        self.rotate_status.start()

    async def close(self):
        if self.vanilla:
            await self.vanilla.close()
        if self.web:
            await self.web.close()
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
        self.index = Index(sections, embed=self.embed)
        await self.recipes.refresh(self.session())
        return True

    def session(self) -> aiohttp.ClientSession:
        if self.web is None or self.web.closed:
            self.web = aiohttp.ClientSession(headers={"User-Agent": "NiceMerl (Explorer's Eden Discord bot)"})
        return self.web

    async def recipe_picture(self, question: str, results: list[Result]) -> recipes.Picture | None:
        """For "how do I craft X?": our packs' recipe picture from the website, else the vanilla crafting
        grid drawn from the Minecraft Wiki. None when there's no recipe or anything fails."""
        item = personality.recipe_item(question)
        if not item:
            return None
        exact, loose = self.recipes.find(item)
        if exact:
            return recipes.manifest_picture(exact, config.SITE_URL)
        # Only the page named after the item, so a pack item never gets some vanilla page's recipe.
        grid = await self.vanilla.recipe_grid(item[:1].upper() + item[1:], item) if self.vanilla else None
        if grid is None:
            return recipes.manifest_picture(loose, config.SITE_URL) if loose else None
        try:
            png = await self.drawer.draw(self.session(), grid)
        except Exception:
            log.warning("Could not draw the recipe for %s", item, exc_info=True)
            return None
        return recipes.Picture(f"📜 Recipe: **{grid.title or item.title()}** · Minecraft", png=png)

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
        visit.seen_at = now
        today = personality.epoch_day(self.now().date())
        friend = self.friends.get(message.author.id)
        first, returning = friend.chats == 0, friend.returning(int(now // 60))
        friend.met, friend.chats, friend.seen = friend.met or today, friend.chats + 1, int(now // 60)
        friend.name = user
        note = None
        if personality.small_talk(question) not in personality.QUIET_TALK:
            note, friend.noted, friend.anniversary = personality.friendship_note(
                friend.chats, friend.noted, today - friend.met, friend.anniversary, user)
        await self.answer(message, question, user, visit, friend, today, first, returning, started)
        self.friends.save(message.author.id, friend, today)
        if note and not friend.forgotten:
            # "Oh! That was our 50th chat." comes as a little extra message after the answer.
            await self.say(message, time.monotonic(), content=note, record=False)

    async def answer(self, message: discord.Message, question: str, user: str, visit: Visit, friend: Friend,
                     today: int, first: bool, returning: bool, started: float):
        now = time.time()
        # "say that again" / "what was I asking?": the last question and answer (memory only, half an hour).
        if kind := personality.recall(question):
            await self.recall(message, started, visit, kind, user, now)
            return
        visit.pending = question
        # "have you met Alex?" (Discord mentions of others stay in, as <@id>)
        raw = re.sub(rf"<@!?{self.user.id}>", "", message.content).strip() if self.user else question
        if (met := personality.met_question(raw)) and (line := self.met_line(*met, message, user)):
            await self.say(message, started, content=line)
            return
        # "what blocks go with deepslate?", "random palette": a block palette.
        # "fun fact about axolotls", "make fun of @Katter" (mentions of others as their names)
        named = raw
        for member in message.mentions:
            if not self.user or member.id != self.user.id:
                named = re.sub(rf"<@!?{member.id}>", member.display_name, named)
        if topic := personality.topic_request(named):
            visit.talk, visit.talked_at, visit.topic = topic[0], now, topic[1]
            await self.say(message, started, content=self.topic_line(*topic, named, user))
            return
        if visit.recent_talk(now) == "palette" and personality.palette_follow_up(question):
            # "shuffle", "try again" right after a palette
            await self.palette(message, started, personality.PaletteAsk(again=True), visit, user, now)
            return
        if (ask := personality.palette(question)) and await self.palette(message, started, ask, visit, user, now):
            return
        talk = personality.small_talk(question)
        talk_prefix = None
        if talk is None:
            # "you're funny! tell me a joke"
            talk_prefix, talk = personality.multi_small_talk(question)
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
                friend.topic, friend.topic_day = topic, today
                await self.say(message, started, content=pick(f"reply_{topic}"))
                return

        resolved = personality.resolve_reference(question, visit.recent_page(now) or "")
        if talk == "greeting" or (talk is None and not tokenize(resolved)):
            if talk or self.user in message.mentions:
                greeting, extra = self.greeting(user, friend, today, first, returning)
                extra = extra or self.ask_back("greeting", visit, now) or self.ask_feeling("greeting", greeting, visit, now)
                intro = friend.chats <= personality.INTRO_CHATS
                await self.say(message, started, embed=self.hello_embed(greeting, extra, intro))
            return
        if talk == "more" and visit.recent_talk(now) in ("fact", "joke") and visit.topic:
            # "another one" after a fact or joke about something: another one about it
            visit.talked_at = now
            await self.say(message, started, content=self.topic_line(visit.talk, visit.topic, visit.topic, user))
            return
        if talk == "more" and visit.recent_talk(now) == "palette":
            await self.palette(message, started, personality.PaletteAsk(again=True), visit, user, now)
            return
        if talk == "more":
            # "another one!" after a joke is another joke.
            talk = visit.recent_talk(now) if visit.recent_talk(now) in personality.REPEATABLE else None
            if talk is None:
                await self.say(message, started, content=pick("more_what", user=user))
                return
        if talk:
            text = self.small_talk_line(talk, user, visit, friend, today, now)
            if talk_prefix:
                text = f"{pick(talk_prefix, user=user)} {text}"
            visit.talk, visit.talked_at, visit.topic = talk, now, ""
            if extra := self.ask_back(talk, visit, now) or self.ask_feeling(talk, text, visit, now):
                text += " " + extra
            await self.say(message, started, content=text)
            if talk in REACTIONS:
                await self.react(message, REACTIONS[talk])
            return

        if not self.index.sections:
            await self.send(message, embed=self.text_embed(pick("still_reading")))
            return

        if personality.is_clarifying(question) and visit.recent_page(now):
            # "so Katter is the bosses?" right after an answer
            await self.say(message, started, content=pick("clarify", user=user))
            return

        prefix, search = personality.split_small_talk(question)
        # "where do I find him?" right after the Raj Raksha page
        search = personality.resolve_reference(search, visit.recent_page(now) or "")
        # Close calls go to the projects this person usually asks about, and to what they're asking about now.
        leaning = personality.interests(friend.interest_shares(), visit.recent_project(now))
        outcome = self.index.find(search, limit=config.RESULTS, interests=leaning)
        # "and in the nether?" right after a question: if it finds nothing good on its own,
        # search it together with the previous question.
        # Something phrased as a question, or a follow-up to one; otherwise only a confident match counts.
        asking = bool(prefix) or personality.seeks_info(question) or personality.is_follow_up(search)
        weak = not outcome.results or outcome.confidence < SURE_TITLE_SCORE
        if weak and personality.is_follow_up(search) and (previous := visit.recent_question(now)):
            combined = self.index.find(f"{previous} {search}", limit=config.RESULTS, interests=leaning)
            if combined.confidence >= outcome.confidence:
                search, outcome = f"{previous} {search}", combined
        results = outcome.results
        if self.vanilla:
            async with message.channel.typing():
                found, titled = await self.vanilla.search(
                    search, limit=2, require_title_match=plan(search, outcome) == "check")
            results = combine(search, outcome, found, titled, config.RESULTS)
            # "uses for iron ingots": the Minecraft Wiki page about exactly that, unless it's already there.
            named = vanilla_named(search)
            if named and not any(r.section.vanilla and r.section.page_title.lower() == named.lower() for r in results):
                if page := await self.vanilla.named_page(named, search):
                    results = [r for r in results if r.section.path != page.section.path]
                    # Behind an Eden page named after the same thing, ahead of everything else.
                    eden_named = bool(results) and not results[0].section.vanilla \
                        and set(tokenize(named)) <= set(tokenize(results[0].section.page_title))
                    results = ([results[0], page, *results[1:]] if eden_named else [page, *results])[:config.RESULTS]

        all_matched = bool(results) and results[0].matched >= len(set(tokenize(search)))
        if not asking and not personality.clearly_about(confidence(results, outcome) if results else None,
                                                        bool(results) and results[0].title_match, question, all_matched):
            # "NO! Stop!" or "i like turtles": no question, and no page that's clearly about it.
            await self.say(message, started, embed=self.unclear_embed(user))
            return
        sure = confidence(results, outcome) if results else None
        if sure == "guess" and results[0].matched <= 1 and not results[0].title_match:
            # "anyone online?": one loose word in common with a page isn't an answer.
            await self.say(message, started, embed=self.unclear_embed(user))
            return
        if sure == "guess":
            # A guess is one page, not three loosely related ones.
            results = results[:1]

        asked = " ".join(tokenize(search))
        repeat = visit.is_repeat(asked, now)
        visit.question, visit.asked_at = asked, now
        if results:
            visit.page, visit.answered_at = results[0].section.page_title, now
            friend.page, friend.page_day = visit.page, today
            if not results[0].section.vanilla:
                visit.project = results[0].section.path.split("/")[0]
                friend.learn(visit.project)
            energy = personality.energy(question)
            answer = search_answer_line(search, results, self.index) if sure in ("sure", "maybe") else ""
            embed = self.results_embed(results, self.answer_title(
                results, outcome, prefix=prefix, repeat=repeat, energy=energy, user=user), energy, answer)
            picture = await self.recipe_picture(question, results)
            recipe_file = None
            if picture:
                embed.description = f"{picture.line}\n\n{embed.description}"[:4096]
                if picture.png:
                    recipe_file = discord.File(io.BytesIO(picture.png), filename="recipe.png")
                    embed.set_image(url="attachment://recipe.png")
                else:
                    embed.set_image(url=picture.url)
            await self.say(message, started, embed=embed, results=len(results), extra_file=recipe_file)
        else:
            visit.page = ""
            await self.say(message, started, embed=self.not_found_embed(), results=1)

    def topic_line(self, kind: str, subject: str, text: str, user: str) -> str:
        """A fun fact or a joke about something ("axolotls") or someone ("Katter", the asker, Merl). Jokes about
        people are friendly little teases from the joke_about pool, never mean."""
        if kind == "fact":
            if not subject or subject in (personality.ME, personality.MERL):
                return pick("fact")
            shown = personality.original_case(text, subject)
            if facts := personality.about("fact", subject):
                return f"{pick('fact_topic_intro', subject=shown)} {random.choice(facts)}"
            return f"{pick('fact_topic_none', subject=shown)} {pick('fact')}"
        if not subject:
            return pick("joke")
        name = user if subject == personality.ME else "Merl" if subject == personality.MERL else personality.original_case(text, subject)
        # Jokes that are already about it: always for things with many (creepers), sometimes for a person a joke
        # happens to mention (Katter), so they get the friendly name jokes too.
        jokes = [] if subject == personality.ME else personality.about("joke", "merl" if subject == personality.MERL else subject)
        if len(jokes) >= personality.MANY_TOPIC_JOKES or (jokes and random.random() < 0.5):
            return random.choice(jokes)
        line = pick("joke_about", name=name)
        return f"{pick('joke_about_intro', name=name)} {line}" if random.random() < 0.4 else line

    async def palette(self, message: discord.Message, started: float, ask: personality.PaletteAsk, visit: Visit,
                      user: str, now: float) -> bool:
        """A block palette with a picture of the blocks. False when it isn't one after all ("what goes with
        diamonds?" names no block), so the question is answered as usual."""
        colors = self.block_colors
        if not colors:
            return False
        if ask.again:
            named = self.last_palette.get(message.author.id, [])
        elif ask.random:
            named = []
        elif ask.block:
            # "prismarine and gold": every block named is in the palette.
            named = palettes.blocks_by_name(colors, ask.block, exact=ask.loose)
            if not named:
                # "what goes with diamonds?", "what blocks go with the castle?": not about a block after all.
                if ask.loose or not ask.said_palette:
                    return False
                await self.say(message, started, content=pick("palette_unknown_name", block=ask.block, user=user))
                return True
        else:
            # "what goes with this?": Discord has no block to look at.
            await self.say(message, started, content=pick("palette_name_needed", user=user))
            return True
        self.last_palette[message.author.id] = named
        start = named[0] if named else palettes.random_base(colors)
        blocks = palettes.palette(colors, start, include=tuple(named[1:]))
        shown = " and ".join(palettes.name(b) for b in named)
        title = pick("palette_intro", block=shown, user=user) if named else pick("palette_random", user=user)
        names = " · ".join(f"**{palettes.name(b)}**" if b in named else palettes.name(b) for b in blocks)
        embed = self.text_embed(f"{title}\n\n{names}\n\n-# {pick('palette_more_hint')}")
        picture = None
        try:
            png = await palettes.render(self.session(), colors, blocks, self.palette_icons)
            picture = discord.File(io.BytesIO(png), filename="palette.png")
            embed.set_image(url="attachment://palette.png")
        except Exception:
            log.warning("Could not draw the palette for %s", start, exc_info=True)
        visit.talk, visit.talked_at = "palette", now
        await self.say(message, started, embed=embed, extra_file=picture)
        return True

    async def recall(self, message: discord.Message, started: float, visit: Visit, kind: str, user: str, now: float):
        last = visit.recall(now)
        if last is None:
            await self.say(message, started, content=pick("recall_none", user=user), record=False)
            return
        said, answer = last
        if kind == "question" or answer is None:
            await self.say(message, started, content=pick("recall_question", question=said, user=user), record=False)
            return
        embed = answer.copy()
        if embed.image and str(embed.image.url or "").startswith("attachment://"):
            embed.set_image(url=None)  # the drawn recipe picture isn't kept
        embed.title = f"{pick('repeat_intro', user=user)} {embed.title or ''}".strip()[:256]
        await self.say(message, started, embed=embed, record=False)

    def met_line(self, name: str, strict: bool, message: discord.Message, user: str) -> str | None:
        """ "have you met Alex?": whether Merl knows them, by Discord mention or display name."""
        if normalize_name(name) in ("peanut butter", "pb", "your cat"):
            return personality.peanut_butter(self.now())
        if normalize_name(name) in ("merl", "nicemerl"):
            return pick("who_are_you", community=COMMUNITY)
        mention = re.fullmatch(r"<@!?(\d+)>", name)
        if mention:
            person = int(mention.group(1))
            friend = self.friends.get(person)
            member = message.guild.get_member(person) if message.guild else None
            name = member.display_name if member else friend.name or "them"
        else:
            found = self.friends.find(name)
            person, friend = found if found else (0, Friend())
            name = friend.name or name
        if person == message.author.id or normalize_name(name) == normalize_name(user):
            return pick("met_you", user=user)
        if friend.chats:
            return pick("met_yes", name=name, often=personality.often(friend.chats), user=user)
        return pick("met_no", name=name, user=user) if strict else None

    def greeting(self, user: str, friend: Friend, today: int, first: bool, returning: bool) -> tuple[str, str | None]:
        """Hello for someone new, an old friend, or someone back after a while (with a question about
        what they were up to); the second part is that question, if any."""
        if first:
            return pick("first_meeting", user=user), None
        if returning:
            return pick("welcome_back", user=user), personality.follow_up(
                friend.topic, friend.topic_day, friend.page, friend.page_day, today)
        if friend.chats > personality.FRIEND_CHATS and personality.chance(personality.FRIEND_GREETING_CHANCE):
            return pick("greeting_friend", user=user), None
        return pick(personality.greeting_pool(self.now()), user=user), None

    def small_talk_line(self, talk: str, user: str, visit: Visit, friend: Friend, today: int, now: float) -> str:
        # "thanks" after an answer: that project was right for them; "that's not what I asked": it wasn't.
        if talk in ("thanks", "wrong") and (project := visit.recent_project(now)):
            friend.learn(project, 1 if talk == "thanks" else -1)
        if talk == "remember_me":
            return personality.remember_me(friend.chats, today - friend.met, friend.topic, user)
        if talk == "forget_me":
            friend.forgotten = True
            return pick("forget_done", user=user)
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
                  embed: discord.Embed | None = None, results: int = 0, extra_file: discord.File | None = None,
                  record: bool = True):
        """Replies after a short "typing…" pause, so Merl doesn't answer inhumanly fast."""
        if results:
            delay = min(TYPING_MAX, TYPING_ANSWER + TYPING_PER_RESULT * results)
        else:
            delay = random.uniform(*TYPING_SMALL_TALK)
        remaining = delay - (time.monotonic() - started)
        if remaining > 0:
            async with message.channel.typing():
                await asyncio.sleep(remaining)
        embed = embed or self.text_embed(content or "")
        visit = self.memory.visit(message.author.id)
        if record and visit.pending:
            # Remembered (in memory only) for "say that again" and "what was I asking?".
            visit.said, visit.said_at, visit.last_answer = visit.pending, time.time(), embed
        await self.send(message, embed=embed, extra_file=extra_file)

    async def send(self, message: discord.Message, *, embed: discord.Embed, extra_file: discord.File | None = None):
        """Every reply is an embed with a random picture of Merl in the corner (and maybe a recipe picture)."""
        files = [extra_file] if extra_file else []
        if THUMBNAILS:
            choices = [t for t in THUMBNAILS if t != self.last_thumbnail] or THUMBNAILS
            image = self.last_thumbnail = random.choice(choices)
            files.append(discord.File(image, filename=image.name))
            embed.set_thumbnail(url=f"attachment://{image.name}")
        await message.reply(embed=embed, mention_author=False, files=files)

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

    def results_embed(self, results: list[Result], title: str, energy: str, answer: str = "") -> discord.Embed:
        # The answer line goes first, the pages below it.
        blocks = [f"💬 {discord.utils.escape_markdown(answer)}"] if answer else []
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

    def unclear_embed(self, user: str) -> discord.Embed:
        return self.branded(discord.Embed(
            description=f"{pick('unclear', user=user)}\n\n-# Try: *how do I get a boss key?* or *give me a tip*",
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

    def hello_embed(self, greeting: str, ask_back: str | None = None, intro: bool = True) -> discord.Embed:
        """A hello, with an introduction of what Merl does for newer people."""
        title = (f"{greeting} {ask_back}" if ask_back else greeting)[:256]
        if not intro:
            return self.branded(discord.Embed(title=title, color=MERL_PINK))
        vanilla = (" I can also look things up in the [Minecraft Wiki](https://minecraft.wiki) "
                   "for vanilla questions." if self.vanilla else "")
        return self.branded(discord.Embed(
            title=title,
            description=(
                "Ask me anything about the Explorer's Eden projects, like structures, "
                "enchantments, mob variants or warping, and I'll find the right page in the "
                f"[wiki]({config.WIKI_URL}) for you!{vanilla}\n\n"
                + (f"If I can't help, post your question in {help_channel()} and someone else will.\n\n"
                   if config.HELP_CHANNEL_ID else "")
                + "-# Try: *how do I get a boss key?*"
                + (" or *how do I make a nether portal?*" if self.vanilla else "")
            ),
            color=MERL_PINK,
        ))


def normalize_name(name: str) -> str:
    return name.lower().lstrip("@").strip()


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

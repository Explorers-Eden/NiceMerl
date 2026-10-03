<p align="center">
  <img src="bot/assets/merl_peanut_butter.png" alt="Merl and Peanut Butter" width="200">
</p>

# NiceMerl

*A fan homage to [Merl](https://minecraft.wiki/w/Minecraft_Support_Virtual_Agent), Minecraft's support assistant, now helping out on Explorer's Eden.*

NiceMerl answers questions about the Explorer's Eden projects by searching the [Explorer's Eden wiki](https://wiki.explorerseden.eu), and vanilla Minecraft questions from the [Minecraft Wiki](https://minecraft.wiki). She comes in two forms that share the same search and the same personality:

| | Folder | Where | Runs as |
|---|---|---|---|
| 🤖 **Discord bot** | [`bot/`](bot) | one Discord channel | Docker image `niceron/nicemerl`, deployed with Portainer + Watchtower |
| ⛏️ **Fabric mod** | [`mod/`](mod) | in-game, `/merl <question>` | server-side mod for Minecraft 26.3, published as GitHub releases |

Neither uses AI or a paid API, so there are no running costs. Both download the public wiki pages on startup and every 6 hours, keep a small search index in memory and search it locally. If nothing matches, Merl answers *"I don't know."*, just like the [real one](https://minecraft.wiki/w/Minecraft_Support_Virtual_Agent).

**How the search works:**
- BM25 ranking, with page titles and paths weighted higher.
- Typo tolerance (*"enchantmnt"* → *enchantment*, and Merl says so) and prefix matching (`ench`).
- Player slang from [`bot/data/synonyms.json`](bot/data/synonyms.json) (`tp`, `xp`, `keepinv`, …).
- Phrase boosts for words that appear next to each other (*nether portal*), and a bonus when a page title is exactly what was asked.
- A coverage factor, so pages matching *all* of the question win, and changelogs are damped unless the question is about updates.

**Vanilla questions:** these are looked up live through minecraft.wiki's public API. Its own search picks candidate pages, which are then split into sections and ranked with the same search. Merl blends both wikis:
- Eden questions stay on the Eden wiki.
- Questions that say *vanilla*/*Minecraft*, or name a Minecraft Wiki page Eden has no page for (*"nether portal"*), answer from the Minecraft Wiki first.
- When the Eden wiki has nothing good, she checks the Minecraft Wiki.

```
.
├── bot/                     Discord bot (Python)
│   ├── data/                Merl's lines + synonyms, shared with the mod
│   ├── SETUP.md             step-by-step deploy guide
│   └── …
├── mod/                     Fabric mod (Java)
│   ├── WIKI.md              wiki page for the mod, ready to paste
│   ├── tools/release_infos.yml
│   ├── changelog.log
│   └── …
└── .github/workflows/
    ├── docker.yml           bot → Docker Hub (on push to bot/, and every 6 h)
    ├── mod-build.yml        mod → test build (on push to mod/ or bot/data/)
    └── mod-release.yml      mod → GitHub release after a green build on main, keeps the newest per MC version
```

> ⚠️ The mod re-implements the bot's search and personality in Java: [`SearchIndex.java`](mod/src/main/java/eu/explorerseden/nicemerl/SearchIndex.java) mirrors [`search.py`](bot/search.py), [`VanillaWiki.java`](mod/src/main/java/eu/explorerseden/nicemerl/VanillaWiki.java) mirrors [`vanilla.py`](bot/vanilla.py), [`MerlLines.java`](mod/src/main/java/eu/explorerseden/nicemerl/MerlLines.java) mirrors [`personality.py`](bot/personality.py) and [`MerlMemory.java`](mod/src/main/java/eu/explorerseden/nicemerl/MerlMemory.java) mirrors [`memory.py`](bot/memory.py). Changes to stopwords, weights, stemming, scoring or how replies are put together need to be made in both. Both currently return identical scores.
>
> Merl's lines ([`lines.json`](bot/data/lines.json)) and the synonyms ([`synonyms.json`](bot/data/synonyms.json)) are **shared**: they live in `bot/data/`, and the mod build bundles them into the jar, so they only need editing once.

---

## 🤖 Discord bot

Every message in the configured channel is treated as a question. NiceMerl replies with the best-matching wiki pages and deep links to the matching sections. The top result shows the relevant sentences or list in full, which is often the complete answer, and the others show a short excerpt. Wiki spoilers become Discord `||spoilers||`.

- **Vanilla questions** are answered from the Minecraft Wiki, labelled 📗 *Minecraft Wiki*.
- **Saying hi** (or @-mentioning her) gets a greeting that fits the time of day, using their name.
- **Small talk:** *thanks*, *bye*, *how are you*, *who are you*, *what can you do*, *tell me a joke*, *give me a tip*, *fun fact*, *what should I do next?* (over 600 ideas), *good bot*, *who is Peanut Butter*, *pet Peanut Butter*, *I died*, *I'm bored*… Over 2,000 lines in all. She answers in character and reacts with 💗, 👋 or 🐱.
- **Mixed messages:** *"thanks! how do I get a boss key?"* gets a quick *"You're welcome!"* and the answer.
- **She sounds human:**
  - She says how sure she is (*"Found it!"*, *"I think this is it…"*, *"This is my best guess:"*), based on the search score.
  - She remembers each person for a little while: she notices repeated questions, understands follow-ups (*"and in the nether?"*), says *"Glad the Boss Key page helped!"* when thanked, and *"Welcome back!"* after a few hours away.
  - She matches your energy (CAPS → excited, *"help, I'm stuck"* → calm, one word → short).
  - She has a mood of the day and gets sleepy at night. Peanut Butter has moods too, sometimes interrupts, and her pets are counted.
  - She shows *"typing…"* for a moment before answering, sometimes asks what you're up to, and very rarely makes a typo and corrects herself.
- **She never repeats herself** right away, now and then adds a little aside, and her Discord status changes every 15 minutes (*Petting Peanut Butter*, *Looking out for creepers*, …).
- **When she can't help**, she answers *"I don't know."* and points people to #user-help.
- **`!reindex`** (requires *Manage Server*) refreshes the index right away after wiki edits.
- **Cooldown:** one question per user every 5 seconds.

### Setup

👉 **[bot/SETUP.md](bot/SETUP.md)** has the full guide: creating the Discord app, uploading the avatar, permissions, GitHub secrets, the Portainer stack and troubleshooting.

Short version: every push to `bot/` builds `niceron/nicemerl:latest` on Docker Hub, using the repo secrets `DOCKER_USERNAME` and `DOCKER_PASSWORD`. Paste [`bot/portainer-stack.yml`](bot/portainer-stack.yml) into Portainer, set `DISCORD_TOKEN` and `CHANNEL_ID`, and Watchtower keeps it up to date.

To run it locally:

```sh
cd bot
cp .env.example .env   # fill in DISCORD_TOKEN and CHANNEL_ID
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
.venv/bin/python bot.py
```

To test the search without Discord, run `.venv/bin/python search.py "how do I get a boss key"`.

### Configuration

| Variable | Default | |
|---|---|---|
| `DISCORD_TOKEN` | – | Bot token |
| `CHANNEL_ID` | – | Channel the bot answers in |
| `WIKI_URL` | `https://wiki.explorerseden.eu` | Wiki.js base URL |
| `REINDEX_HOURS` | `6` | How often to re-download the wiki |
| `RESULTS` | `3` | Results per answer |
| `COOLDOWN_SECONDS` | `5` | Per-user cooldown |
| `HELP_CHANNEL_ID` | `1245007015865225256` | Channel Merl points people to when she can't help (#user-help); `0` turns it off |
| `VANILLA_WIKI` | `true` | Answer vanilla questions from the Minecraft Wiki too |
| `VANILLA_WIKI_URL` | `https://minecraft.wiki` | MediaWiki used for vanilla questions |
| `TIMEZONE` | `Europe/Berlin` | Time zone for good morning / good evening, sleepy nights and the mood of the day |
| `STATE_DIR` | `bot/state` (`/app/state` in Docker) | Where Peanut Butter's pet count is saved. The Docker setups mount the `nicemerl-state` volume here, so it survives updates |

Search tuning (stopwords, weights, typo and synonym settings) lives at the top of [`bot/search.py`](bot/search.py), and the vanilla blending (`STRONG_SCORE`, skipped chapters) at the top of [`bot/vanilla.py`](bot/vanilla.py). To test without Discord, run `.venv/bin/python search.py "how do I make a nether portal"`: it prints each source, the corrections and the blend decision.

### Merl's lines

Everything Merl says lives in [`bot/data/lines.json`](bot/data/lines.json):
- `pools`: lists of lines. Placeholders are `{user}`, `{community}`, `{term}`, `{pages}`, `{page}`, `{word}`, `{count}`, `{deaths}`, `{hours}` and `{advancement}`.
- `intents`: small-talk patterns. Each one is matched against the whole message, lowercased, without punctuation and apostrophes. Messages that *start* with a greeting, thanks, sorry, ok or a compliment and go on with a question get a short line from the matching `*_prefix` pool before the answer.
- `moods` / `pb_moods`: Merl's and Peanut Butter's mood of the day (the same in the bot and the mod), which pick from `asides_<mood>`, `status_<mood>` and `pb_<mood>`.
- `topics`: words that tell what someone is up to after Merl asked (*"building"* → `reply_build`).
- `progress_ideas`: the mod's progress-based ideas. Each step has an `after` advancement that must be done, an `unless` advancement that must not be, and its lines.

Edit it once and both the bot and the next mod build pick it up. In Minecraft chat, emoji outside the basic plane and `*` are stripped automatically.

### Assets

[`bot/assets/avatar.png`](bot/assets/avatar.png) is the bot avatar; upload it in the Developer Portal. The `thumb_*.png` files are attached to the greeting and "I don't know" replies. The other images are only used in the docs.

---

## ⛏️ Fabric mod

Players type `/merl <question>`, and NiceMerl answers in chat with:
- the best-matching wiki pages, each with a clickable link and a short excerpt
- **Minecraft Wiki** pages for vanilla questions, marked in green (looked up in the background, so the server never waits)
- the **current data pack settings**, when the question is about settings (*"is pvp enabled?"*, *"keep inventory settings"*, *"blaze settings"*)

She also does small talk (`/merl thanks`, `/merl tell me a joke`, `/merl give me a tip`, `/merl fun fact`, `/merl pet peanut butter`), greets players by name, and has the same human touches as the bot (mixed messages, confidence, short memory, moods, asking back). On top of that, in-game:
- **`/merl what should I do next`** looks at the player's advancements and suggests the next step (*"You haven't been to the Nether yet!"*, *"Find an End city with a ship and grab the elytra!"*), or one of over 600 ideas.
- **She notices what you're doing:** now and then she comments on the dimension, weather or biome, low health, what you're holding (*"Ooh, a mace! Bonk responsibly."*), your elytra, your death count or your play time. Players can turn this off with `/nicemerl comments off`.
- **She celebrates with you:** big advancements (dragon, elytra, Wither, netherite armor…) get a private congratulation. Players can turn this off with `/nicemerl celebrate off`.

Answers are **only visible to the player who asked**. Wiki spoilers are scrambled and revealed on hover. The mod is **server-side only**: players join with an unmodded client.

**Requirements:** Minecraft **26.3**, Fabric Loader ≥ 0.19.5, [Fabric API](https://modrinth.com/mod/fabric-api) and Java 25. [LuckPerms](https://luckperms.net) is optional.

### Commands

| Command | Who | What it does |
|---|---|---|
| `/merl` | everyone | Merl says hi and explains herself |
| `/merl <question>` | everyone | searches the wiki (and settings) |
| `/nicemerl comments [on\|off]` | everyone | turns Merl's comments about you on or off, just for you |
| `/nicemerl celebrate [on\|off]` | everyone | turns Merl's congratulations on or off, just for you |
| `/nicemerl reindex` | operators | re-reads the wiki right away |

### Permissions (LuckPerms)

| Node | Default without a permissions mod |
|---|---|
| `nicemerl.command.merl` | everyone |
| `nicemerl.command.toggle` | everyone (`/nicemerl comments` and `/nicemerl celebrate`) |
| `nicemerl.command.reindex` | operators (level 2) |
| `nicemerl.bypass.cooldown` | operators (level 2) |
| `nicemerl.settings` | everyone (shows current data pack settings in answers) |

Example: `/lp group default permission set nicemerl.settings false` hides settings from regular players.

### Configuration

`config/nicemerl.json` is created on first start:

| Option | Default | What it does |
|---|---|---|
| `wikis` | Explorer's Eden + Minecraft Wiki | wikis to search, see below |
| `communityName` | `Explorer's Eden` | used in Merl's lines |
| `reindexHours` | `6` | how often the wiki is re-read |
| `results` | `3` | wiki pages per answer |
| `excerptLength` | `160` | excerpt length in characters |
| `cooldownSeconds` | `5` | per-player cooldown |
| `settingsSources` | the Explorer's Eden packs | which command storages hold settings, see below |
| `settingsIgnoreKeys` | `*_initial`, `command_template`, `*_template` | setting keys to hide |
| `settingsResults` | `6` | most settings listed per answer |
| `mediaWikiResults` | `2` | most pages from `mediawiki` wikis per answer |
| `playerComments` | `true` | Merl's comments about where players are and what they're doing (each player can also turn them off) |
| `celebrate` | `true` | congratulations on advancements (each player can also turn them off) |
| `celebrateAdvancements` | dragon, elytra, Wither, netherite armor, … | which advancements Merl congratulates players on |

Players' choices and Peanut Butter's pet count are saved in `config/nicemerl/state.json`.

### Wikis

`wikis` lists every wiki Merl searches, in two kinds:

```json
"wikis": [
  { "name": "Explorer's Eden", "url": "https://wiki.explorerseden.eu", "type": "wikijs" },
  { "name": "Minecraft Wiki", "url": "https://minecraft.wiki", "type": "mediawiki" }
]
```

- **`wikijs`** wikis are downloaded completely (every 6 hours) and searched locally. Add as many as you like. With more than one, results say which wiki they're from. If one can't be reached, its pages from the last download are kept.
- **`mediawiki`** wikis (any MediaWiki with a public `api.php`, e.g. a mod wiki on wiki.gg) are searched live, with results cached for a day. Merl asks them when the question says *vanilla*/*Minecraft*, when a page there is named after the subject, or when the `wikijs` wikis have nothing good. Remove the Minecraft Wiki entry to turn vanilla answers off.

Config files from 1.1.0 and older are migrated automatically: their `wikiUrl` becomes the first wiki, and the Minecraft Wiki is added after it.

### Data pack settings

Each entry in `settingsSources` points to one compound in command storage. The defaults cover Nice Keep Inventory, Fabled Roots, Nice Mob Manager, Nice Actions, the Nice Admin Tools gamerules and Warping Wonders (all in `eden:settings`), plus Katters Structures (in `kattersstructures:gamerule`):

```json
{ "storage": "eden:settings", "path": "keepinv", "name": "Nice Keep Inventory" }
```

Values are read live each time someone asks.

**New defaults on update:** when a mod update adds default entries, it raises `CURRENT_VERSION` in [`MerlConfig.java`](mod/src/main/java/eu/explorerseden/nicemerl/MerlConfig.java). Config files with an older `configVersion` get the missing entries added once, so anything you removed on purpose stays removed. When you add a default source or ignore key, raise `CURRENT_VERSION` as well.

**Readable names** come from the packs themselves. On startup and after `/reload`, the mod looks for config dialogs: functions that run `dialog show` and are called `with storage <storage> <path>`. From each dialog input it takes the `key`, the label and the option labels. That turns `keepinv.equip_dmg` into *Equipment Damage*, `taglist` into *Tag List*, and `1b` into *Enabled*.

Labels are sent as translation keys with the English fallback, so players with a pack's language files see their own language. If a pack has such dialogs, only the settings they name are listed, and the rest of its storage is treated as internal. Packs without dialogs show their keys as-is.

### Releasing

1. In [`mod/tools/release_infos.yml`](mod/tools/release_infos.yml), set `Version number` and `Version subtitle`, and add any new Minecraft version first under `Versions`. Also set `version` in [`mod/gradle.properties`](mod/gradle.properties) to match, for local builds.
2. Write the release notes in [`mod/changelog.log`](mod/changelog.log).
3. If the Minecraft version changed, update `minecraft_version`, `fabric_api_version` and `loader_version` in [`mod/gradle.properties`](mod/gradle.properties) (see <https://fabricmc.net/develop>) and `"minecraft"` in `mod/src/main/resources/fabric.mod.json`.
4. Push to `main`. Once **Mod Build** goes green, **Publish Mod Release** runs by itself. You can also start it by hand under **Actions → Publish Mod Release → Run workflow**.

> 💡 Every green build of `main` (also from changes to `bot/data/`) publishes. Without a version bump, it refreshes the jar and notes of the current version's release, so raise the version for anything players should notice as a new release.

The workflow builds the mod and publishes it as the GitHub release `mod-v<version>-mc<minecraft>` with the file `nice-merl-<version>-mc<minecraft>.jar`, e.g. `mod-v1.0.0-mc26.3`. The changelog becomes the release notes, and re-running the same version updates its release.

**Only the newest release per Minecraft version is kept.** Publishing `mod-v1.1.0-mc26.3` deletes `mod-v1.0.0-mc26.3` and its tag, while releases for other Minecraft versions (e.g. `mod-v1.0.0-mc26.2`) stay. The Minecraft version is the first one listed under `Versions`, the one the jar is built for.

The workflow fails early if `gradle.properties` and `release_infos.yml` name different Minecraft versions.

### Development

```sh
cd mod
./gradlew build        # jar in build/libs/
./gradlew runServer    # dev server in run/ (accept the EULA in run/eula.txt)
```

---

<sub>Merl and Peanut Butter are characters from *Minecraft* © Mojang Studios. Images via the Minecraft Wiki ([Merl](https://minecraft.wiki/w/Earth:Merl), [Support Virtual Agent](https://minecraft.wiki/w/Minecraft_Support_Virtual_Agent)). NiceMerl is an unofficial fan homage and isn't affiliated with or endorsed by Mojang or Microsoft. The mod's code is licensed under GPL-3.0.</sub>

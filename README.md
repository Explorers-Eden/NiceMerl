<p align="center">
  <img src="bot/assets/merl_peanut_butter.png" alt="Merl and Peanut Butter" width="200">
</p>

# NiceMerl

*A fan homage to [Merl](https://minecraft.wiki/w/Minecraft_Support_Virtual_Agent), Minecraft's support assistant, now helping out on Explorer's Eden.*

NiceMerl answers questions about the Explorer's Eden projects by searching the [Explorer's Eden wiki](https://wiki.explorerseden.eu). She comes in two forms that share the same search:

| | Folder | Where | Runs as |
|---|---|---|---|
| 🤖 **Discord bot** | [`bot/`](bot) | one Discord channel | Docker image `niceron/nicemerl`, deployed with Portainer + Watchtower |
| ⛏️ **Fabric mod** | [`mod/`](mod) | in-game, `/merl <question>` | server-side mod for Minecraft 26.3, published as GitHub releases |

Neither uses AI or a paid API, so there are no running costs. Both download the public wiki pages on startup and every 6 hours, keep a small search index in memory (BM25) and search it locally. If nothing matches, Merl answers *"I don't know."*, just like the [real one](https://minecraft.wiki/w/Minecraft_Support_Virtual_Agent).

```
.
├── bot/                     Discord bot (Python)
│   ├── SETUP.md             step-by-step deploy guide
│   └── …
├── mod/                     Fabric mod (Java)
│   ├── tools/release_infos.yml
│   ├── changelog.log
│   └── …
└── .github/workflows/
    ├── docker.yml           bot → Docker Hub (on push to bot/, and every 6 h)
    ├── mod-build.yml        mod → test build (on push to mod/)
    └── mod-release.yml      mod → GitHub release (run by hand)
```

> ⚠️ The mod re-implements the bot's search in Java ([`SearchIndex.java`](mod/src/main/java/eu/explorerseden/nicemerl/SearchIndex.java) and [`search.py`](bot/search.py)). Changes to stopwords, weights, stemming or scoring need to be made in both.

---

## 🤖 Discord bot

Every message in the configured channel is treated as a question. NiceMerl replies with the best-matching wiki pages and deep links to the matching sections. The top result shows the relevant sentences or list in full, which is often the complete answer, and the others show a short excerpt. Wiki spoilers become Discord `||spoilers||`.

- **Saying hi** (or @-mentioning her) gets a wave and a short intro.
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

Search tuning (stopwords, title/path weights, minimum score) lives at the top of [`bot/search.py`](bot/search.py).

### Assets

[`bot/assets/avatar.png`](bot/assets/avatar.png) is the bot avatar; upload it in the Developer Portal. The `thumb_*.png` files are attached to the greeting and "I don't know" replies. The other images are only used in the docs.

---

## ⛏️ Fabric mod

Players type `/merl <question>`, and NiceMerl answers in chat with:
- the best-matching wiki pages, each with a clickable link and a short excerpt
- the **current data pack settings**, when the question is about settings (*"is pvp enabled?"*, *"keep inventory settings"*, *"blaze settings"*)

Answers are **only visible to the player who asked**. Wiki spoilers are scrambled and revealed on hover. The mod is **server-side only**: players join with an unmodded client.

**Requirements:** Minecraft **26.3**, Fabric Loader ≥ 0.19.5, [Fabric API](https://modrinth.com/mod/fabric-api) and Java 25. [LuckPerms](https://luckperms.net) is optional.

### Commands

| Command | Who | What it does |
|---|---|---|
| `/merl` | everyone | Merl says hi and explains herself |
| `/merl <question>` | everyone | searches the wiki (and settings) |
| `/nicemerl reindex` | operators | re-reads the wiki right away |

### Permissions (LuckPerms)

| Node | Default without a permissions mod |
|---|---|
| `nicemerl.command.merl` | everyone |
| `nicemerl.command.reindex` | operators (level 2) |
| `nicemerl.bypass.cooldown` | operators (level 2) |
| `nicemerl.settings` | everyone (shows current data pack settings in answers) |

Example: `/lp group default permission set nicemerl.settings false` hides settings from regular players.

### Configuration

`config/nicemerl.json` is created on first start:

| Option | Default | What it does |
|---|---|---|
| `wikiUrl` | `https://wiki.explorerseden.eu` | Wiki.js wiki to search |
| `communityName` | `Explorer's Eden` | used in the "I don't know" lines |
| `reindexHours` | `6` | how often the wiki is re-read |
| `results` | `3` | wiki pages per answer |
| `excerptLength` | `160` | excerpt length in characters |
| `cooldownSeconds` | `5` | per-player cooldown |
| `settingsSources` | the Explorer's Eden packs | which command storages hold settings, see below |
| `settingsIgnoreKeys` | `*_initial`, `command_template`, `*_template` | setting keys to hide |
| `settingsResults` | `6` | most settings listed per answer |

### Data pack settings

Each entry in `settingsSources` points to one compound in command storage. The defaults cover Nice Keep Inventory, Fabled Roots, Nice Mob Manager, Nice Actions and Warping Wonders (all in `eden:settings`), plus Katters Structures (in `kattersstructures:gamerule`):

```json
{ "storage": "eden:settings", "path": "keepinv", "name": "Nice Keep Inventory" }
```

Values are read live each time someone asks.

**Readable names** come from the packs themselves. On startup and after `/reload`, the mod looks for config dialogs: functions that run `dialog show` and are called `with storage <storage> <path>`. From each dialog input it takes the `key`, the label and the option labels. That turns `keepinv.equip_dmg` into *Equipment Damage*, `taglist` into *Tag List*, and `1b` into *Enabled*.

Labels are sent as translation keys with the English fallback, so players with a pack's language files see their own language. If a pack has such dialogs, only the settings they name are listed, and the rest of its storage is treated as internal. Packs without dialogs show their keys as-is.

### Releasing

1. In [`mod/tools/release_infos.yml`](mod/tools/release_infos.yml), set `Version number` and `Version subtitle`, and add any new Minecraft version first under `Versions`.
2. Write the release notes in [`mod/changelog.log`](mod/changelog.log).
3. If the Minecraft version changed, update `minecraft_version`, `fabric_api_version` and `loader_version` in [`mod/gradle.properties`](mod/gradle.properties) (see <https://fabricmc.net/develop>) and `"minecraft"` in `mod/src/main/resources/fabric.mod.json`.
4. Push, then on GitHub run **Actions → Publish Mod Release → Run workflow**.

The workflow builds `nice-merl-<version>.jar` and creates or updates the GitHub release `mod-v<version>`, using the changelog as release notes. It fails early if `gradle.properties` and `release_infos.yml` name different Minecraft versions.

### Development

```sh
cd mod
./gradlew build        # jar in build/libs/
./gradlew runServer    # dev server in run/ (accept the EULA in run/eula.txt)
```

---

<sub>Merl and Peanut Butter are characters from *Minecraft* © Mojang Studios. Images via the Minecraft Wiki ([Merl](https://minecraft.wiki/w/Earth:Merl), [Support Virtual Agent](https://minecraft.wiki/w/Minecraft_Support_Virtual_Agent)). NiceMerl is an unofficial fan homage and isn't affiliated with or endorsed by Mojang or Microsoft. The mod's code is licensed under GPL-3.0.</sub>

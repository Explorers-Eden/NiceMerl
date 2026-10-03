# Description
NiceMerl is a server-side Fabric mod that lets players ask questions in chat. Using the /merl command, players get the best-matching pages from the Explorer's Eden wiki, answers to vanilla questions from the Minecraft Wiki, and the current settings of the installed data packs. Answers are only visible to the player who asked. Players do not need to install anything on their client.

NiceMerl is a fan homage to Merl, the support helper from Minecraft, and her cat Peanut Butter. It does not use AI or any paid service.

# Download
The mod is currently published only on GitHub. It requires Minecraft 26.3, Fabric Loader 0.19.5 or newer, Fabric API and Java 25. LuckPerms is optional. You can download it here: [NiceMerl on GitHub](https://github.com/Explorers-Eden/NiceMerl/releases/latest)

# Usage
Players type /merl followed by a question, for example /merl how do I get a boss key or /merl how do I make a nether portal. Merl answers with up to three wiki pages, each with a clickable link and a short excerpt. Short questions work best, typos and common abbreviations like tp or xp are understood, and spoilers from the wiki are hidden until hovered over.

Questions about the server's settings, like /merl is pvp enabled, also show the current values of the installed data packs. Merl also answers small talk, such as /merl thanks, /merl tell me a joke or /merl give me a tip.

# Commands
- /merl: Merl introduces herself and gives example questions
- /merl <question>: Searches the wikis and data pack settings for an answer
- /nicemerl reindex: Reads the wikis again right away, for example after editing a page

# Permissions
Without a permissions mod, every player can ask questions and operators can use /nicemerl reindex. With LuckPerms, the following permission nodes can be used:
- nicemerl.command.merl: Allows using /merl (default: everyone)
- nicemerl.command.reindex: Allows using /nicemerl reindex (default: operators)
- nicemerl.bypass.cooldown: Allows asking questions without the cooldown (default: operators)
- nicemerl.settings: Shows data pack settings in answers (default: everyone)

For example, /lp group default permission set nicemerl.settings false hides the data pack settings from regular players.

# Administration
The settings of the mod are stored in the config/nicemerl.json file, which is created on the first start. Changes take effect after a server restart.
- wikis: The wikis Merl searches, see below
- communityName: The name of your community used in Merl's answers (default: Explorer's Eden)
- reindexHours: How often the wikis are read again, in hours (default: 6)
- results: The number of pages shown per answer (default: 3)
- mediaWikiResults: The maximum number of Minecraft Wiki pages shown per answer (default: 2)
- excerptLength: The length of the text shown below each page, in characters (default: 160)
- cooldownSeconds: The time players have to wait between questions, in seconds (default: 5)
- settingsResults: The maximum number of data pack settings shown per answer (default: 6)
- settingsSources: The data pack storages Merl reads settings from
- settingsIgnoreKeys: Setting keys that are never shown

## Wikis
Each entry in the wikis list has a name shown to players, a url and a type. Wikis of the type wikijs are downloaded completely and searched on the server. Wikis of the type mediawiki, like the Minecraft Wiki, are only asked when needed. By default, the Explorer's Eden wiki and the Minecraft Wiki are included. Removing the Minecraft Wiki entry turns off answers to vanilla questions.

```json
"wikis": [
  { "name": "Explorer's Eden", "url": "https://wiki.explorerseden.eu", "type": "wikijs" },
  { "name": "Minecraft Wiki", "url": "https://minecraft.wiki", "type": "mediawiki" }
]
```

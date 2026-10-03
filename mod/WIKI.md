# Description
NiceMerl is a server-side Fabric mod that lets players ask questions in chat. Using the `/merl` command, players get the best-matching pages from the Explorer's Eden wiki, answers to vanilla questions from the Minecraft Wiki, and the current settings of the installed data packs. Answers are only visible to the player who asked. Players do not need to install anything on their client.

# Download
The mod is currently published only on GitHub. It requires Minecraft 26.3, Fabric Loader 0.19.5 or newer and Fabric API. LuckPerms is optional. You can download it here: [NiceMerl on GitHub](https://github.com/Explorers-Eden/NiceMerl/releases/latest)

# Usage
Players type `/merl` followed by a question, for example `/merl how do I get a boss key` or `/merl how do I make a nether portal`. Merl answers with up to three wiki pages, each with a clickable link and a short excerpt. Short questions work best, typos and common abbreviations like tp or xp are understood, and spoilers from the wiki are hidden until hovered over.

Questions about the server's settings, like `/merl is pvp enabled`, also show the current values of the installed data packs. Merl also answers small talk, such as `/merl thanks`, `/merl tell me a joke`, `/merl give me a tip` or `/merl pet peanut butter`.

## What should I do next?
Players who don't know what to do can ask `/merl what should I do next`, `/merl any ideas` or `/merl I'm out of ideas`. Merl looks at the player's advancements and suggests a fitting next step, for example building a nether portal, finding a stronghold or looking for an elytra. Now and then, or when no step fits, she picks one of over 1,200 general ideas instead.

## Merl's personality
Merl tries to sound like a real person rather than a bot:
- She understands small talk in many wordings, with stretched letters and small typos, like `/merl can you tell me a joke please`, `/merl thaaanks` or `/merl im bored gimme ideas`.
- Small talk and a question can be combined, like `/merl thanks! how do I get a boss key`. Merl replies to both.
- She says how sure she is about an answer, from a confident "Found it!" to "This is my best guess".
- For a few minutes she remembers the last question. She notices when it is asked again, understands follow-ups like `/merl and in the nether?`, and knows which page helped when a player says thanks. After a few hours away, she welcomes players back.
- She reacts to the way a question is asked: excited for capital letters, calm for "help, I'm stuck", short for one-word questions.
- She has a mood of the day, gets sleepy late at night, and her cat Peanut Butter has moods as well. Every pet Peanut Butter gets is counted.
- After small talk, she sometimes asks what the player is up to and reacts to the answer.
- Now and then she comments on the player's situation: the dimension, the biome, the weather, low health, the item in their hand, an elytra, their number of deaths or their play time.
- When a player completes a big advancement, like killing the Ender Dragon or finding an elytra, Merl congratulates them. Only that player sees it.

Players can turn the comments and the congratulations off for themselves, see below.

# Commands
- `/merl`: Merl introduces herself and gives example questions
- `/merl <question>`: Searches the wikis and data pack settings for an answer
- `/nicemerl comments [on|off]`: Turns Merl's comments about the player's situation on or off, only for that player
- `/nicemerl celebrate [on|off]`: Turns Merl's congratulations on advancements on or off, only for that player
- `/nicemerl reindex`: Reads the wikis again right away, for example after editing a page

# Permissions
Without a permissions mod, every player can ask questions and change their own comment and congratulation settings, and operators can use `/nicemerl reindex`. With LuckPerms, the following permission nodes can be used:
- **nicemerl.command.merl**: Allows using `/merl` (default: everyone)
- **nicemerl.command.toggle**: Allows using `/nicemerl comments` and `/nicemerl celebrate` (default: everyone)
- **nicemerl.command.reindex**: Allows using `/nicemerl reindex` (default: operators)
- **nicemerl.bypass.cooldown**: Allows asking questions without the cooldown (default: operators)
- **nicemerl.settings**: Shows data pack settings in answers (default: everyone)

For example, `/lp group default permission set nicemerl.settings false` hides the data pack settings from regular players.

# Administration
The settings of the mod are stored in the config/nicemerl.json file, which is created on the first start. Changes take effect after a server restart.
- **wikis**: The wikis Merl searches, see below
- **communityName**: The name of your community used in Merl's answers (default: Explorer's Eden)
- **reindexHours**: How often the wikis are read again, in hours (default: 6)
- **results**: The number of pages shown per answer (default: 3)
- **mediaWikiResults**: The maximum number of Minecraft Wiki pages shown per answer (default: 2)
- **excerptLength**: The length of the text shown below each page, in characters (default: 160)
- **cooldownSeconds**: The time players have to wait between questions, in seconds (default: 5)
- **settingsResults**: The maximum number of data pack settings shown per answer (default: 6)
- **settingsSources**: The data pack storages Merl reads settings from
- **settingsIgnoreKeys**: Setting keys that are never shown
- **playerComments**: Whether Merl comments on the player's situation at all (default: true)
- **celebrate**: Whether Merl congratulates players on advancements at all (default: true)
- **celebrateAdvancements**: The advancements Merl congratulates players on: big vanilla milestones, and the bosses, challenges and collections of the Explorer's Eden packs. Advancements of packs that aren't installed are simply never completed, so they do no harm

The players' own choices and Peanut Butter's pet count are stored in config/nicemerl/state.json.

## Wikis
Each entry in the wikis list has a name shown to players, a url and a type. Wikis of the type wikijs are downloaded completely and searched on the server. Wikis of the type mediawiki, like the Minecraft Wiki, are only asked when needed. By default, the Explorer's Eden wiki and the Minecraft Wiki are included. Removing the Minecraft Wiki entry turns off answers to vanilla questions.

## Default Config
This is the config/nicemerl.json file the mod creates on the first start. The configVersion is managed by the mod and should not be changed.

```json
{
  "configVersion": 4,
  "wikis": [
    {
      "name": "Explorer\u0027s Eden",
      "url": "https://wiki.explorerseden.eu",
      "type": "wikijs"
    },
    {
      "name": "Minecraft Wiki",
      "url": "https://minecraft.wiki",
      "type": "mediawiki"
    }
  ],
  "communityName": "Explorer\u0027s Eden",
  "reindexHours": 6.0,
  "results": 3,
  "excerptLength": 160,
  "cooldownSeconds": 5,
  "settingsSources": [
    {
      "storage": "eden:settings",
      "path": "keepinv",
      "name": "Nice Keep Inventory"
    },
    {
      "storage": "eden:settings",
      "path": "fabled_roots",
      "name": "Fabled Roots"
    },
    {
      "storage": "eden:settings",
      "path": "mob_manager",
      "name": "Nice Mob Manager"
    },
    {
      "storage": "eden:settings",
      "path": "nice_actions",
      "name": "Nice Actions"
    },
    {
      "storage": "eden:settings",
      "path": "warping_wonders",
      "name": "Warping Wonders"
    },
    {
      "storage": "kattersstructures:gamerule",
      "path": "settings",
      "name": "Katters Structures"
    },
    {
      "storage": "eden:settings",
      "path": "nice_admin_tools.gamerules",
      "name": "Nice Admin Tools"
    }
  ],
  "settingsIgnoreKeys": [
    "*_initial",
    "command_template",
    "*_template"
  ],
  "settingsResults": 6,
  "mediaWikiResults": 2,
  "playerComments": true,
  "celebrate": true,
  "celebrateAdvancements": [
    "minecraft:story/enter_the_nether",
    "minecraft:story/enter_the_end",
    "minecraft:end/kill_dragon",
    "minecraft:end/elytra",
    "minecraft:nether/summon_wither",
    "minecraft:nether/create_full_beacon",
    "minecraft:nether/netherite_armor",
    "minecraft:nether/all_effects",
    "minecraft:adventure/adventuring_time",
    "minecraft:adventure/kill_all_mobs",
    "minecraft:adventure/minecraft_trials_edition",
    "minecraft:husbandry/bred_all_animals",
    "minecraft:husbandry/complete_catalogue",
    "kattersstructures:dungeon/boss_key",
    "kattersstructures:dungeon/arachne",
    "kattersstructures:dungeon/pharaoh",
    "kattersstructures:dungeon/raj",
    "kattersstructures:dungeon/rusta",
    "kattersstructures:dungeon/tenku",
    "kattersstructures:dungeon/theron",
    "kattersstructures:crystal_blunt_heavy",
    "kattersstructures:ambient/villager_all",
    "kattersstructures:deepblue/deep_blue_portal",
    "kattersstructures:deepblue/deep_blue_wanderer",
    "kattersstructures:village/village_all",
    "eden:adventure/spirit_animal",
    "eden:adventure/the_rise_and_shine",
    "eden:adventure/arsenal_of_roots",
    "eden:adventure/full_set_of_roots",
    "eden:adventure/bards_repertoire",
    "eden:adventure/call_of_the_races",
    "eden:adventure/dressed_for_the_job",
    "eden:adventure/trophy_case",
    "eden:adventure/home_away_from_home",
    "eden:adventure/master_cartographer",
    "eden:adventure/scroll_scholar",
    "eden:adventure/ten_tales_told",
    "eden:adventure/anglers_almanac",
    "eden:adventure/jack_of_all_trades",
    "eden:adventure/weapon_master",
    "eden:adventure/triple_threat",
    "eden:adventure/legendary_slayer",
    "eden:adventure/mythical",
    "eden:adventure/variant_hunter",
    "eden:adventure/cattitude",
    "eden:adventure/good_boys",
    "eden:adventure/ribbiting_discovery",
    "eden:adventure/hog_wild",
    "eden:adventure/udderly_unique",
    "eden:adventure/fowl_play",
    "eden:adventure/abyssal_family",
    "eden:adventure/homestead",
    "eden:adventure/brewers_tour",
    "eden:adventure/gallery_opening",
    "eden:adventure/gourmet",
    "eden:adventure/outpost_explorer"
  ]
}
```

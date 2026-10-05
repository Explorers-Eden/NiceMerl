# Description
NiceMerl adds Merl, a friendly helper, to the server. Type `/merl` and a question in chat, and she finds the answer in the Explorer's Eden wiki, the Minecraft Wiki or the server's settings. Only you see her answers. You don't need to install anything.

# Download
Available on [GitHub](https://github.com/Explorers-Eden/NiceMerl/releases/latest). Needs Minecraft 26.3, Fabric Loader 0.19.5 or newer and Fabric API. LuckPerms is optional.

# Asking Merl
Type `/merl` and your question:
- `/merl how do I get a boss key`
- `/merl how do I make a nether portal`
- `/merl is pvp enabled` or `/merl keep inventory settings`
- `/merl where is the closest cherry grove` or `/merl where's a slime chunk`
- `/merl where is the closest waypoint`, `/merl where's my waypoint` or `/merl where is the castle waypoint` (with Warping Wonders)
- `/merl name tag phrases` or `/merl name tag to mute a mob`
- `/merl what can I craft` or `/merl how do I craft a waypoint hub`
- `/merl what is this` (look at a block or mob)
- `/merl where's my bed`, `/merl where did I die` or `/merl where's my claim` (with Get Off My Lawn)
- `/merl remind me in 10 minutes to check the furnace`
- `/merl what's the tps`, `/merl mob cap`, `/merl view distance`, `/merl server info` or `/merl what's my ping`
- `/merl is keep inventory on` or `/merl what's the random tick speed` (game rules and pack settings)
- `/merl take me to agnes' waypoint` (starts the sparkle path right away)
- `/merl say that again` or `/merl what was I asking?`

Ask in your own words: typos, abbreviations like tp or xp, and other wordings (*"how do I unlock the boss room"*) are fine. Merl answers in one line when she can, then shows up to three wiki pages with a link and a short excerpt. Spoilers stay hidden until you hover over them.

## More than answers
- **Coordinates:** ask where the closest biome is (vanilla, Terralith, Biomes O' Plenty or our packs, by name or id like `terralith:moonlight_grove`) or the closest slime chunk, and Merl tells you how far, which way and the exact spot. With Warping Wonders, she also finds the closest Waypoint Hub you can use, your own, or one by name. Click the coordinates to copy them, or click **[Guide me]** for a sparkle path on the ground that leads you there, around walls and over hills.
- **Name Tag texts:** `/merl name tag phrases` lists every text Nice Name Tags reacts to, each one click to copy.
- **Crafting:** `/merl what can I craft` lists what your inventory can make right now; `/merl what can I make with this` only what uses the item in your hand. `/merl how do I craft …` shows the real recipe (our packs' too) with a link to its picture, and she also answers *"what can I smelt / brew / enchant this with?"* for the item in your hand.
- **What's this?** Look at a block or mob (or hold an item) and ask `/merl what is this`: she names it (for mobs with their variant, like *"Variant: Creamy (Nice Mob Variants)"*) and adds a short summary from the wiki page that's about exactly that thing, if there is one.
- **Your places:** your bed, where you died (your grave), and with Get Off My Lawn your claims or the ones you're trusted on, with coordinates and **[Guide me]**.
- **Server info:** TPS and MSPT, mob counts and caps, view and simulation distance, players online, difficulty and more, read live from the server. *"What's my ping?"* (or a player's, or everyone's) too. No wiki links, just the numbers.
- **Settings and game rules:** *"is keep inventory on?"*, *"do mobs grief?"* or *"can I pvp?"* show the server's game rules and our packs' settings, without wiki links.
- **Context:** *"say that again"* repeats her last answer, *"what was I asking?"* tells you your last question (she forgets both after half an hour). *"Take me to …"*, *"guide me to …"* or *"give me a route to …"* start the sparkle path straight away.
- **Reminders:** `/merl remind me in 10 minutes to …`, `/merl my reminders`, `/merl cancel my reminders`. Gone after a server restart.
- **Ideas:** `/merl what should I do next` suggests a next step based on your advancements, or one of over 4,500 ideas.
- **Small talk:** over 2,000 jokes (stories, dialogues, mob reviews and more), tips, fun facts, stories, `/merl pet peanut butter` and more. Ask for `another one` after a joke.
- **She remembers you:** when you met, what you're up to, which page helped and which packs you ask about most, so her answers fit you better over time. Ask `/merl do you remember me`, or say `/merl forget me` to erase it. She never saves what you write.
- **She comments now and then** on where you are and what you're doing.
- **She celebrates with you:** big advancements (and how few players have them), and milestones like 100,000 blocks mined or 1,000 km traveled.

# Commands
- `/merl`: Merl says hi and gives examples
- `/merl <question>`: Ask Merl anything
- `/nicemerl comments [on|off]`: Turn her comments on your situation on or off, just for you
- `/nicemerl celebrate [on|off]`: Turn her congratulations on or off, just for you
- `/nicemerl guide stop`: Stop the sparkle trail
- `/nicemerl mannequin`: Place a Merl mannequin where you stand (operators). Right-clicking it shows a message; `/nicemerl mannequin remove` removes the closest one
- `/nicemerl reindex`: Read the wikis again right away (operators)

# Permissions
Everything works without a permissions mod. With LuckPerms:
- **nicemerl.command.merl**: use `/merl` (everyone)
- **nicemerl.command.toggle**: use `/nicemerl comments` and `/nicemerl celebrate` (everyone)
- **nicemerl.command.reindex**: use `/nicemerl reindex` (operators)
- **nicemerl.bypass.cooldown**: skip the cooldown between questions (operators)
- **nicemerl.settings**: see data pack settings in answers (everyone)
- **nicemerl.serverinfo**: see TPS, mob caps, distances, server info and pings (everyone)
- **nicemerl.command.mannequin**: use `/nicemerl mannequin` (operators)
- **nicemerl.locate**: get biome, slime chunk, waypoint, claim, bed and death coordinates, and the sparkle trail (everyone)

# For server admins
The config is in `config/nicemerl.json`, created on the first start. Restart the server after changing it. The most useful options:
- **wikis**: the wikis Merl searches. Remove the Minecraft Wiki to turn off vanilla answers
- **communityName**: your community's name (Explorer's Eden)
- **messagePrefix** and **prefixColor**: what Merl's messages start with (a pink `Merl: `); the color can be a name like `gold` or a hex color
- **messageSound**, **messageSoundVolume** and **messageSoundPitch**: the sound with her messages (the egg plop our packs use); leave the sound empty for none
- **results**: pages per answer (3)
- **cooldownSeconds**: wait time between questions (5)
- **settingsSources**: the data pack storages she reads settings from
- **playerComments** and **celebrate**: turn comments and congratulations off for everyone
- **celebrateAdvancements**: which advancements she congratulates on
- **celebrateStatistics**: turn statistic milestones off for everyone
- **locateBiomes** and **locateSlimeChunks**: turn coordinates off for everyone (turn slime chunks off if your seed is a secret)
- **locateWaypoints**: turn waypoint answers off for everyone
- **particleGuide**: turn the sparkle trail off for everyone
- **craftingHelp**: turn "what can I craft?" off for everyone
- **recipeHelp**: turn real recipes, smelting, brewing and enchanting answers off for everyone; **recipesUrl** is where the recipe pictures come from
- **whatsThis**, **locateHome**, **locateClaims** and **reminders**: turn "what's this?", bed and death, claims, and reminders off for everyone
- **serverInfo** and **settingsGameRules**: turn server info, or game rules in settings answers, off for everyone
- **mannequinMessage** and **mannequinMessageType**: what a Merl mannequin says when right-clicked, in `chat` or on the `actionbar`
- **semanticSearch**: understanding other wordings. Downloads a small file (about 31 MB) once to `config/nicemerl/model/`

What Merl remembers about players is saved in `config/nicemerl/state.json`: about a hundred bytes per player, no messages, and players gone for a year are forgotten.

## Default config
This is `config/nicemerl.json` as it's created on the first start. Delete the file and restart to get it back.

```json
{
  "configVersion": 5,
  "wikis": [
    {
      "name": "Explorer's Eden",
      "url": "https://wiki.explorerseden.eu",
      "type": "wikijs"
    },
    {
      "name": "Minecraft Wiki",
      "url": "https://minecraft.wiki",
      "type": "mediawiki"
    }
  ],
  "messagePrefix": "Merl: ",
  "prefixColor": "#F06EAA",
  "messageSound": "minecraft:entity.chicken.egg",
  "messageSoundVolume": 0.6,
  "messageSoundPitch": 2.0,
  "mannequinMessage": "Ask /merl anything at any time!",
  "mannequinMessageType": "actionbar",
  "communityName": "Explorer's Eden",
  "reindexHours": 6.0,
  "results": 3,
  "excerptLength": 160,
  "semanticSearch": true,
  "locateBiomes": true,
  "locateSlimeChunks": true,
  "locateWaypoints": true,
  "particleGuide": true,
  "whatsThis": true,
  "recipeHelp": true,
  "recipesUrl": "https://explorerseden.eu/api/generated-data.php?key=recipes-manifest",
  "locateClaims": true,
  "locateHome": true,
  "reminders": true,
  "serverInfo": true,
  "craftingHelp": true,
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
      "path": "nice_admin_tools.gamerules",
      "name": "Nice Admin Tools"
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
    }
  ],
  "settingsIgnoreKeys": [
    "*_initial",
    "command_template",
    "*_template"
  ],
  "settingsGameRules": true,
  "settingsResults": 6,
  "mediaWikiResults": 2,
  "playerComments": true,
  "celebrate": true,
  "celebrateStatistics": true,
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

package eu.explorerseden.nicemerl;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import net.fabricmc.loader.api.FabricLoader;

/** Settings stored in config/nicemerl.json. Missing fields fall back to these defaults. */
public class MerlConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	/**
	 * Raise this when a default list gains entries, so existing config files pick them up.
	 * Entries are only added once, so anything a server owner removed stays removed.
	 */
	private static final int CURRENT_VERSION = 5;

	/** Which defaults this file has seen. Files from before versioning count as 1. */
	public int configVersion = CURRENT_VERSION;

	/**
	 * Wikis /merl searches. "wikijs" wikis are downloaded and searched locally; "mediawiki" wikis
	 * (like the Minecraft Wiki) are searched live, for questions the others can't answer well.
	 */
	public List<WikiSource> wikis = new ArrayList<>(List.of(
			new WikiSource("Explorer's Eden", "https://wiki.explorerseden.eu", WikiSource.WIKIJS),
			new WikiSource("Minecraft Wiki", "https://minecraft.wiki", WikiSource.MEDIAWIKI)));
	/** What Merl's messages start with, in prefixColor. */
	public String messagePrefix = "Merl: ";
	/** The prefix's color: a name like "gold" or "dark_aqua", or a hex color like "#F06EAA" (Merl's pink). */
	public String prefixColor = "#F06EAA";
	/** The sound played to the player with Merl's messages, like the packs' egg plop. Empty for none. */
	public String messageSound = "minecraft:entity.chicken.egg";
	public float messageSoundVolume = 0.6f;
	public float messageSoundPitch = 2.0f;
	/** What a Merl mannequin says when someone right-clicks it ({user} is their name). Empty for Merl's own lines. */
	public String mannequinMessage = "Ask /merl anything at any time!";
	/** The mannequin also says it when a player looks straight at it from a few blocks away (once a minute at most). */
	public boolean mannequinGreetOnLook = true;
	/** Where that message shows: "chat" or "actionbar". */
	public String mannequinMessageType = "actionbar";
	/** Used in Merl's "I don't know" lines. */
	public String communityName = "Explorer's Eden";
	/** How often the wiki is downloaded again. */
	public double reindexHours = 6;
	/** Pages shown per answer. */
	public int results = 3;
	/** Approximate excerpt length in characters. */
	public int excerptLength = 160;
	/**
	 * Meaning-based search next to the keyword search, so "how do I unlock the boss room" finds Boss Keys.
	 * Downloads a small model (about 31 MB) once to config/nicemerl/model/; until then, or if that fails,
	 * Merl searches by keywords only.
	 */
	public boolean semanticSearch = true;
	/** "Where's the closest cherry grove?" gets the coordinates of the closest one (any biome, from any pack). */
	public boolean locateBiomes = true;
	/** "Where's a slime chunk?" gets the coordinates of the closest one. Turn off if your seed is a secret. */
	public boolean locateSlimeChunks = true;
	/** With Warping Wonders: "where's the closest waypoint?" points to the closest Waypoint Hub the player may use. */
	public boolean locateWaypoints = true;
	/** After coordinates, Merl offers a trail of sparkles to follow there (only the player sees it). */
	public boolean particleGuide = true;
	/** Merl herself walks ahead along the trail, holding a map (only the guided player sees her). */
	public boolean guideMerl = true;
	/** "What's this?" names the block or mob the player looks at (mob variants included) and shows its wiki page. */
	public boolean whatsThis = true;
	/** Real recipes from the server ("how do I craft …"), "what can I smelt / brew with this?" and "what can I enchant this with?". */
	public boolean recipeHelp = true;
	/** The website's recipe list, for [Recipe picture] links. Empty for none. */
	public String recipesUrl = "https://explorerseden.eu/api/generated-data.php?key=recipes-manifest";
	/** With Get Off My Lawn: "where's my claim?" points to the closest claim the player owns or is trusted on. */
	public boolean locateClaims = true;
	/** "Where's my bed?" and "where did I die?" with coordinates. */
	public boolean locateHome = true;
	/** "Remind me in 10 minutes to …" (kept in memory, gone after a restart). */
	public boolean reminders = true;
	/** "What's the TPS?", "mob cap", "view distance", "server info": live server info without wiki pages. */
	public boolean serverInfo = true;
	/** "What can I craft?" lists what the player can make from their inventory right now. */
	public boolean craftingHelp = true;
	/** Minimum seconds between questions per player. */
	public int cooldownSeconds = 5;
	/** Command storages holding data pack settings that /merl can report on. */
	public List<SettingsSource> settingsSources = new ArrayList<>(List.of(
			new SettingsSource("eden:settings", "keepinv", "Nice Keep Inventory"),
			new SettingsSource("eden:settings", "fabled_roots", "Fabled Roots"),
			new SettingsSource("eden:settings", "mob_manager", "Nice Mob Manager"),
			new SettingsSource("eden:settings", "nice_actions", "Nice Actions"),
			new SettingsSource("eden:settings", "nice_admin_tools.gamerules", "Nice Admin Tools"),
			new SettingsSource("eden:settings", "warping_wonders", "Warping Wonders"),
			new SettingsSource("kattersstructures:gamerule", "settings", "Katters Structures")));
	/** Setting keys to hide. "*" matches any start or end, e.g. "*_initial". */
	public List<String> settingsIgnoreKeys = new ArrayList<>(List.of("*_initial", "command_template", "*_template"));
	/** Game rules count as settings too ("is keep inventory on?", "what's the random tick speed?"). */
	public boolean settingsGameRules = true;
	/** Most settings listed per answer. */
	public int settingsResults = 6;
	/** Most pages from "mediawiki" wikis (the Minecraft Wiki) per answer. */
	public int mediaWikiResults = 2;
	/**
	 * Merl now and then comments on where players are, what they hold and how they're doing.
	 * Players can turn it off for themselves with /nicemerl comments.
	 */
	public boolean playerComments = true;
	/**
	 * Merl congratulates players (privately) on the advancements below.
	 * Players can turn it off for themselves with /nicemerl celebrate.
	 */
	public boolean celebrate = true;
	/**
	 * Merl also congratulates players on statistic milestones, like 100,000 blocks mined or 1,000 km
	 * traveled (the milestones are in Merl's lines). Uses the same per-player switch as celebrate.
	 */
	public boolean celebrateStatistics = true;
	/**
	 * Advancements Merl congratulates players on: big vanilla milestones and the Explorer's Eden
	 * packs' bosses, challenges and collections. Ids of packs that aren't installed never fire.
	 */
	public List<String> celebrateAdvancements = new ArrayList<>(List.of(
			// Vanilla
			"minecraft:story/enter_the_nether", "minecraft:story/enter_the_end", "minecraft:end/kill_dragon",
			"minecraft:end/elytra", "minecraft:nether/summon_wither", "minecraft:nether/create_full_beacon",
			"minecraft:nether/netherite_armor", "minecraft:nether/all_effects", "minecraft:adventure/adventuring_time",
			"minecraft:adventure/kill_all_mobs", "minecraft:adventure/minecraft_trials_edition",
			"minecraft:husbandry/bred_all_animals", "minecraft:husbandry/complete_catalogue",
			// Katters Structures: bosses, boss items and exploring
			"kattersstructures:dungeon/boss_key", "kattersstructures:dungeon/arachne", "kattersstructures:dungeon/pharaoh",
			"kattersstructures:dungeon/raj", "kattersstructures:dungeon/rusta", "kattersstructures:dungeon/tenku",
			"kattersstructures:dungeon/theron", "kattersstructures:crystal_blunt_heavy", "kattersstructures:ambient/villager_all",
			"kattersstructures:deepblue/deep_blue_portal", "kattersstructures:deepblue/deep_blue_wanderer",
			"kattersstructures:village/village_all",
			// Enchantments Encore
			"eden:adventure/spirit_animal", "eden:adventure/the_rise_and_shine",
			// Fabled Roots
			"eden:adventure/arsenal_of_roots", "eden:adventure/full_set_of_roots", "eden:adventure/bards_repertoire",
			"eden:adventure/call_of_the_races", "eden:adventure/dressed_for_the_job", "eden:adventure/trophy_case",
			"eden:adventure/home_away_from_home", "eden:adventure/master_cartographer", "eden:adventure/scroll_scholar",
			"eden:adventure/ten_tales_told",
			// Nice Actions, Nice Keep Inventory, Nice Mob Manager
			"eden:adventure/anglers_almanac", "eden:adventure/jack_of_all_trades", "eden:adventure/weapon_master",
			"eden:adventure/triple_threat", "eden:adventure/legendary_slayer", "eden:adventure/mythical",
			// Nice Mob Variants
			"eden:adventure/variant_hunter", "eden:adventure/cattitude", "eden:adventure/good_boys",
			"eden:adventure/ribbiting_discovery", "eden:adventure/hog_wild", "eden:adventure/udderly_unique",
			"eden:adventure/fowl_play", "eden:adventure/abyssal_family", "eden:adventure/homestead",
			// Nice Things
			"eden:adventure/brewers_tour", "eden:adventure/gallery_opening", "eden:adventure/gourmet",
			"eden:adventure/outpost_explorer"));

	public static class SettingsSource {
		/** Storage id, e.g. "eden:settings". */
		public String storage = "";
		/** Dot-separated path inside the storage, or "" for the whole storage. */
		public String path = "";
		/** Name shown to players. */
		public String name = "";

		public SettingsSource() {}

		public SettingsSource(String storage, String path, String name) {
			this.storage = storage;
			this.path = path;
			this.name = name;
		}
	}

	public static class WikiSource {
		public static final String WIKIJS = "wikijs";
		public static final String MEDIAWIKI = "mediawiki";

		/** Name shown to players next to results. */
		public String name = "";
		/** Base URL, e.g. "https://wiki.explorerseden.eu". */
		public String url = "";
		/** "wikijs" or "mediawiki". */
		public String type = WIKIJS;

		public WikiSource() {}

		public WikiSource(String name, String url, String type) {
			this.name = name;
			this.url = url;
			this.type = type;
		}

		public boolean isMediaWiki() {
			return MEDIAWIKI.equalsIgnoreCase(type);
		}
	}

	public List<WikiSource> wikiJsWikis() {
		return wikis.stream().filter(w -> !w.isMediaWiki()).toList();
	}

	public List<WikiSource> mediaWikis() {
		return wikis.stream().filter(WikiSource::isMediaWiki).toList();
	}

	/** The wiki with this base URL, or null. */
	public WikiSource wiki(String url) {
		return wikis.stream().filter(w -> w.url.equals(url)).findFirst().orElse(null);
	}

	/** Adds default entries that are newer than the file, keeping everything the file already has. */
	private void addNewDefaults(JsonObject json) {
		if (configVersion >= CURRENT_VERSION) return;
		MerlConfig defaults = new MerlConfig();
		if (!json.has("wikis")) {
			// Before version 3 there was a single "wikiUrl"; keep it as the first wiki.
			String oldUrl = json.has("wikiUrl") ? json.get("wikiUrl").getAsString() : defaults.wikis.get(0).url;
			wikis = new ArrayList<>(List.of(new WikiSource(communityName, oldUrl, WikiSource.WIKIJS)));
			for (WikiSource wiki : defaults.wikis) {
				if (wiki.isMediaWiki()) {
					wikis.add(wiki);
					NiceMerl.LOGGER.info("Added new default wiki {} ({})", wiki.name, wiki.url);
				}
			}
		}
		for (SettingsSource source : defaults.settingsSources) {
			boolean present = settingsSources.stream()
					.anyMatch(s -> s.storage.equals(source.storage) && s.path.equals(source.path));
			if (!present) {
				settingsSources.add(source);
				NiceMerl.LOGGER.info("Added new default settings source {} {}", source.storage, source.path);
			}
		}
		for (String key : defaults.settingsIgnoreKeys) {
			if (!settingsIgnoreKeys.contains(key)) settingsIgnoreKeys.add(key);
		}
		for (String id : defaults.celebrateAdvancements) {
			if (!celebrateAdvancements.contains(id)) {
				celebrateAdvancements.add(id);
				NiceMerl.LOGGER.info("Added new default celebration for {}", id);
			}
		}
		// Version 5: the old default prefix "▊ " became "Merl: " (a changed prefix stays as it is).
		if (configVersion < 5 && "▊ ".equals(messagePrefix)) messagePrefix = defaults.messagePrefix;
		configVersion = CURRENT_VERSION;
	}

	public static MerlConfig load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(NiceMerl.MOD_ID + ".json");
		MerlConfig config = new MerlConfig();
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path)) {
				JsonObject json = GSON.fromJson(reader, JsonObject.class);
				if (json != null) {
					config = GSON.fromJson(json, MerlConfig.class);
					if (!json.has("configVersion")) config.configVersion = 1;
					config.addNewDefaults(json);
				}
			} catch (IOException | RuntimeException e) {
				NiceMerl.LOGGER.error("Could not read {}, using defaults", path, e);
			}
		}
		for (WikiSource wiki : config.wikis) {
			wiki.url = wiki.url.replaceAll("/+$", "");
		}
		// Write back so new options show up in existing config files.
		try (Writer writer = Files.newBufferedWriter(path)) {
			GSON.toJson(config, writer);
		} catch (IOException e) {
			NiceMerl.LOGGER.warn("Could not write {}", path, e);
		}
		return config;
	}
}

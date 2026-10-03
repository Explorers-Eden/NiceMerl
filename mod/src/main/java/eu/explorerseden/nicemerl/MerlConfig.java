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
	private static final int CURRENT_VERSION = 2;

	/** Which defaults this file has seen. Files from before versioning count as 1. */
	public int configVersion = CURRENT_VERSION;

	/** Base URL of the Wiki.js wiki to search. */
	public String wikiUrl = "https://wiki.explorerseden.eu";
	/** Used in Merl's "I don't know" lines. */
	public String communityName = "Explorer's Eden";
	/** How often the wiki is downloaded again. */
	public double reindexHours = 6;
	/** Pages shown per answer. */
	public int results = 3;
	/** Approximate excerpt length in characters. */
	public int excerptLength = 160;
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
	/** Most settings listed per answer. */
	public int settingsResults = 6;

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

	/** Adds default entries that are newer than the file, keeping everything the file already has. */
	private void addNewDefaults() {
		if (configVersion >= CURRENT_VERSION) return;
		MerlConfig defaults = new MerlConfig();
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
					config.addNewDefaults();
				}
			} catch (IOException | RuntimeException e) {
				NiceMerl.LOGGER.error("Could not read {}, using defaults", path, e);
			}
		}
		config.wikiUrl = config.wikiUrl.replaceAll("/+$", "");
		// Write back so new options show up in existing config files.
		try (Writer writer = Files.newBufferedWriter(path)) {
			GSON.toJson(config, writer);
		} catch (IOException e) {
			NiceMerl.LOGGER.warn("Could not write {}", path, e);
		}
		return config;
	}
}

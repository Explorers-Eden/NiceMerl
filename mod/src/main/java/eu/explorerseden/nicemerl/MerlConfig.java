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

import net.fabricmc.loader.api.FabricLoader;

/** Settings stored in config/nicemerl.json. Missing fields fall back to these defaults. */
public class MerlConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

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

	public static MerlConfig load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(NiceMerl.MOD_ID + ".json");
		MerlConfig config = new MerlConfig();
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path)) {
				MerlConfig loaded = GSON.fromJson(reader, MerlConfig.class);
				if (loaded != null) {
					config = loaded;
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

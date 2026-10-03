package eu.explorerseden.nicemerl;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

/**
 * What Merl keeps across restarts, in config/nicemerl/state.json: Peanut Butter's pet count and
 * which players turned off Merl's comments or celebrations.
 */
public final class MerlState {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static final class Player {
		/** Merl now and then comments on where you are, what you hold and how you're doing. */
		public boolean comments = true;
		/** Merl congratulates you on big advancements. */
		public boolean celebrate = true;
	}

	private static final class Data {
		long pets;
		Map<String, Player> players = new HashMap<>();
	}

	private static Data data = new Data();

	private MerlState() {}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(NiceMerl.MOD_ID).resolve("state.json");
	}

	public static synchronized void load() {
		Path path = path();
		if (!Files.exists(path)) return;
		try (Reader reader = Files.newBufferedReader(path)) {
			Data loaded = GSON.fromJson(reader, Data.class);
			if (loaded != null) {
				if (loaded.players == null) loaded.players = new HashMap<>();
				data = loaded;
			}
		} catch (IOException | RuntimeException e) {
			NiceMerl.LOGGER.error("Could not read {}, starting fresh", path, e);
		}
	}

	private static void save() {
		Path path = path();
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path)) {
				GSON.toJson(data, writer);
			}
		} catch (IOException e) {
			NiceMerl.LOGGER.warn("Could not write {}", path, e);
		}
	}

	/** One more pet for Peanut Butter; returns the new total. */
	public static synchronized long pet() {
		data.pets++;
		save();
		return data.pets;
	}

	/** The player's settings; defaults when they never changed anything. */
	public static synchronized Player player(UUID id) {
		Player player = data.players.get(id.toString());
		return player != null ? player : new Player();
	}

	public static synchronized void update(UUID id, java.util.function.Consumer<Player> change) {
		Player player = data.players.computeIfAbsent(id.toString(), k -> new Player());
		change.accept(player);
		save();
	}
}

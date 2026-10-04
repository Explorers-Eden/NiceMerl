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
import com.google.gson.annotations.SerializedName;

import net.fabricmc.loader.api.FabricLoader;

/**
 * What Merl keeps across restarts, in config/nicemerl/state.json: Peanut Butter's pet count, which
 * players turned off Merl's comments or celebrations, and what she remembers about each player (see
 * {@link Player}). Kept small: compact JSON, empty fields left out, players gone for a year forgotten.
 */
public final class MerlState {
	private static final Gson GSON = new GsonBuilder().create();
	/** Players not seen for this long are forgotten. */
	private static final int KEEP_DAYS = 365;
	private static final int PAGE_CHARS = 60;
	/** "Welcome back!" after this long away (minutes), but not after so long that it's a first visit again. */
	static final long WELCOME_BACK_AFTER = 3 * 60;
	static final long WELCOME_BACK_UNTIL = 30 * 1440;

	public static final class Player {
		/** Merl now and then comments on where you are, what you hold and how you're doing. */
		public boolean comments = true;
		/** Merl congratulates you on big advancements. */
		public boolean celebrate = true;

		// What Merl remembers about you, like the bot's friends.py. No messages. Null means "nothing",
		// which Gson leaves out of the file, and the names in the file are short, to keep it small.
		/** Day we first talked (days since 1970). */
		@SerializedName("m") public Integer met;
		@SerializedName("c") public Integer chats;
		/** Last /merl, in minutes since 1970. */
		@SerializedName("s") public Long seen;
		/** What you said you're up to ("build"), see the topics in lines.json. */
		@SerializedName("t") public String topic;
		@SerializedName("td") public Integer topicDay;
		/** Title of the last page that answered you. */
		@SerializedName("p") public String page;
		@SerializedName("pd") public Integer pageDay;
		/** The last friendship anniversary (in days) and number of chats Merl mentioned. */
		@SerializedName("a") public Integer anniversary;
		@SerializedName("n") public Integer noted;

		public int chatCount() {
			return chats == null ? 0 : chats;
		}

		public int metDay() {
			return met == null ? 0 : met;
		}

		public boolean returning(long minute) {
			return seen != null && minute - seen >= WELCOME_BACK_AFTER && minute - seen <= WELCOME_BACK_UNTIL;
		}

		public void remember(String title, int today) {
			page = title.length() > PAGE_CHARS ? title.substring(0, PAGE_CHARS) : title;
			pageDay = today;
		}

		/** "forget me": everything but the comment and celebration choices. */
		void forget() {
			met = chats = topicDay = pageDay = anniversary = noted = null;
			seen = null;
			topic = page = null;
		}

		boolean isDefault() {
			return comments && celebrate && met == null;
		}
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
				long cutoff = (java.time.LocalDate.now().toEpochDay() - KEEP_DAYS) * 1440;
				data.players.values().forEach(p -> {
					if (p.seen != null && p.seen < cutoff) p.forget();
				});
				data.players.values().removeIf(Player::isDefault);
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
		if (player.isDefault()) data.players.remove(id.toString());
		save();
	}

	/** "forget me": erases what Merl remembers about the player. */
	public static void forget(UUID id) {
		update(id, Player::forget);
	}
}

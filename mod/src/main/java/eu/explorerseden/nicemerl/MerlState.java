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
	/** Projects remembered per player, and how high a count gets before all counts are halved. */
	private static final int INTERESTS_KEPT = 3;
	private static final int INTEREST_CAP = 50;
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
		/** Name, for "have you met Alex?". */
		@SerializedName("nm") public String name;
		/** The last milestone celebrated per statistic, as an index into its list: "mined:4 mobs:2". */
		@SerializedName("ms") public String milestones;

		/** The index of the last celebrated milestone for a statistic, or null when it was never checked. */
		public Integer milestone(String stat) {
			return pairs(milestones).get(stat);
		}

		public void setMilestone(String stat, int index) {
			java.util.Map<String, Integer> all = pairs(milestones);
			all.put(stat, index);
			milestones = all.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue()).reduce((a, b) -> a + " " + b).orElse(null);
		}

		/** Trivia: right answers, questions answered, the current and the best streak. */
		@SerializedName("qr") public Integer quizRight;
		@SerializedName("qa") public Integer quizAnswered;
		@SerializedName("qs") public Integer quizStreak;
		@SerializedName("qb") public Integer quizBest;
		/** Hide and seek: games won and the fastest find in seconds. */
		@SerializedName("hw") public Integer hideWins;
		@SerializedName("hb") public Integer hideBest;

		/** Projects they ask about most: "katters_structures:12 nice_keep_inventory:3". */
		@SerializedName("in") public String interests;

		/** Project → share of the questions they asked about it. */
		public java.util.Map<String, Double> interestShares() {
			java.util.Map<String, Integer> counts = interestCounts();
			int total = counts.values().stream().mapToInt(Integer::intValue).sum();
			java.util.Map<String, Double> shares = new java.util.HashMap<>();
			if (total > 0) counts.forEach((k, v) -> shares.put(k, (double) v / total));
			return shares;
		}

		/** Counts a question about a project; only the top few projects are kept, and old interests fade. */
		public void learn(String project, int amount) {
			java.util.Map<String, Integer> counts = interestCounts();
			counts.merge(project, amount, Integer::sum);
			if (counts.values().stream().anyMatch(v -> v > INTEREST_CAP)) counts.replaceAll((k, v) -> v / 2);
			String kept = counts.entrySet().stream().filter(e -> e.getValue() > 0)
					.sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed()).limit(INTERESTS_KEPT)
					.map(e -> e.getKey() + ":" + e.getValue()).reduce((a, b) -> a + " " + b).orElse(null);
			interests = kept;
		}

		private java.util.Map<String, Integer> interestCounts() {
			return pairs(interests);
		}

		/** Reads "key:3 other:1" lists; broken entries are dropped. */
		private static java.util.Map<String, Integer> pairs(String text) {
			java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
			if (text == null) return counts;
			for (String part : text.split(" ")) {
				int colon = part.lastIndexOf(':');
				if (colon > 0) {
					try {
						counts.put(part.substring(0, colon), Integer.parseInt(part.substring(colon + 1)));
					} catch (NumberFormatException ignored) {
						// A broken entry is simply dropped.
					}
				}
			}
			return counts;
		}

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
			topic = page = name = interests = null;
			quizRight = quizAnswered = quizStreak = quizBest = hideWins = hideBest = null;
		}

		boolean isDefault() {
			return comments && celebrate && met == null;
		}
	}

	private static final class Data {
		long pets;
		/** The map the guide Merl holds, made once so the world doesn't fill up with maps. */
		Integer guideMap;
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

	public static synchronized Integer guideMap() {
		return data.guideMap;
	}

	public static synchronized void setGuideMap(int id) {
		data.guideMap = id;
		save();
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

	/** A player Merl has talked to, by name (any case), or null. */
	public static synchronized Player find(String name) {
		for (Player player : data.players.values()) {
			if (player.name != null && player.name.equalsIgnoreCase(name)) return player;
		}
		return null;
	}

	/**
	 * The players Merl has talked to whose name is the given one or contains it ("nox" → MrNox), by UUID: the exact
	 * name alone when there is one.
	 */
	public static synchronized Map<UUID, String> named(String part) {
		Map<UUID, String> exact = new HashMap<>(), containing = new HashMap<>();
		String wanted = part.toLowerCase(java.util.Locale.ROOT);
		for (Map.Entry<String, Player> e : data.players.entrySet()) {
			String name = e.getValue().name;
			if (name == null) continue;
			String lower = name.toLowerCase(java.util.Locale.ROOT);
			if (lower.equals(wanted)) exact.put(UUID.fromString(e.getKey()), name);
			else if (wanted.length() >= 3 && lower.contains(wanted)) containing.put(UUID.fromString(e.getKey()), name);
		}
		return exact.isEmpty() ? containing : exact;
	}

	/** Everyone Merl remembers, by UUID (a copy, for leaderboards). */
	public static synchronized Map<UUID, Player> players() {
		Map<UUID, Player> all = new HashMap<>();
		data.players.forEach((id, player) -> all.put(UUID.fromString(id), player));
		return all;
	}

	/** "forget me": erases what Merl remembers about the player. */
	public static void forget(UUID id) {
		update(id, Player::forget);
	}
}

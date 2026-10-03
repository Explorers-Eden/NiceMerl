package eu.explorerseden.nicemerl;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Merl's lines, small talk and synonyms, read from lines.json and synonyms.json. Those files live in
 * the Discord bot's bot/data folder and are bundled into the mod jar at build time, so both share them.
 * How replies are put together mirrors the bot's personality.py.
 */
public final class MerlLines {
	/** Roughly one answer in this many gets a little aside from Merl or Peanut Butter. */
	private static final int ASIDE_CHANCE = 12;
	/** One in this many: Peanut Butter walks over the keyboard, or interrupts (more often when she's playful). */
	private static final int PB_KEYBOARD_CHANCE = 60;
	private static final int PB_INTERRUPT_CHANCE = 40;
	private static final int PB_INTERRUPT_CHANCE_PLAYFUL = 12;
	/** One in this many headlines gets a typo that Merl then fixes. */
	private static final int SLIP_CHANCE = 80;
	private static final int OPENER_CHANCE = 4;
	private static final int CLOSER_CHANCE = 4;
	private static final int SLEEPY_CHANCE = 3;
	/** After this small talk, Merl sometimes asks what you're up to. */
	private static final Set<String> ASK_BACK_POOLS = Set.of("how_are_you", "bored", "greeting", "idea");
	private static final int ASK_BACK_CHANCE = 3;

	/** Small talk that can start a question ("thanks! how do I…"), and the short line it gets. */
	private static final Map<String, String> PREFIX_POOLS = Map.of("greeting", "greeting_prefix", "thanks", "thanks_prefix",
			"sorry", "sorry_prefix", "ok", "ok_prefix", "compliment", "compliment_prefix");
	private static final Set<String> QUESTION_WORDS = Set.of("how", "what", "where", "why", "when", "which", "who", "can", "is",
			"does", "do", "are", "should", "could", "will", "whats", "wheres", "hows", "whos", "whys");
	/** Words that can come between small talk and the question ("ok so what is…"). */
	private static final Set<String> FILLER_WORDS = Set.of("so", "and", "um", "uh", "btw", "but", "also", "like", "quick", "question");
	private static final Set<String> ASKING_WORDS = Set.of("how", "what", "where", "why", "when", "which", "who", "whats", "wheres", "hows");
	private static final List<String> FOLLOW_UP_CUES = List.of("and ", "also ", "what about ", "how about ", "but what about ", "and what about ");
	private static final Set<String> STRESS_WORDS = Set.of("help", "stuck", "urgent", "asap", "broken", "lost", "cant", "confused",
			"sos", "desperate", "panic");
	private static final Pattern LONG_WORD = Pattern.compile("[A-Za-z]{6,}");
	private static final Pattern WORD = Pattern.compile("[a-z0-9]+");
	private static final Pattern COMBINING = Pattern.compile("\\p{M}");

	private record Intent(Pattern pattern, String pool) {}

	/** A step on the way through the game: suggested once {@code after} is done and {@code unless} isn't. */
	private record ProgressIdea(String after, String unless, List<String> lines) {}

	/** "thanks! how do I…" split into the prefix pool and the question; prefix is null when there is none. */
	public record Split(String prefix, String rest) {}

	private static final Map<String, List<String>> POOLS = new HashMap<>();
	private static final List<Intent> INTENTS = new ArrayList<>();
	private static final Map<String, String> SYNONYMS = new HashMap<>();
	private static final List<String> MOODS = new ArrayList<>();
	private static final List<String> PB_MOODS = new ArrayList<>();
	private static final Map<String, List<String>> TOPICS = new LinkedHashMap<>();
	private static final List<ProgressIdea> PROGRESS_IDEAS = new ArrayList<>();
	private static final Map<String, List<String>> BAGS = new HashMap<>();
	private static final Map<String, String> LAST = new HashMap<>();

	static {
		JsonObject lines = resource("lines.json");
		lines.getAsJsonObject("pools").entrySet().forEach(e -> POOLS.put(e.getKey(), strings(e.getValue())));
		for (JsonElement element : lines.getAsJsonArray("intents")) {
			JsonObject intent = element.getAsJsonObject();
			INTENTS.add(new Intent(Pattern.compile("(?:" + intent.get("pattern").getAsString() + ")"),
					intent.get("pool").getAsString()));
		}
		MOODS.addAll(strings(lines.get("moods")));
		PB_MOODS.addAll(strings(lines.get("pb_moods")));
		lines.getAsJsonObject("topics").entrySet().forEach(e -> TOPICS.put(e.getKey(), strings(e.getValue())));
		for (JsonElement element : lines.getAsJsonArray("progress_ideas")) {
			JsonObject idea = element.getAsJsonObject();
			PROGRESS_IDEAS.add(new ProgressIdea(
					idea.has("after") ? idea.get("after").getAsString() : null,
					idea.has("unless") ? idea.get("unless").getAsString() : null,
					strings(idea.get("lines"))));
		}
		resource("synonyms.json").entrySet().forEach(e -> {
			if (!e.getKey().startsWith("_")) SYNONYMS.put(e.getKey(), e.getValue().getAsString());
		});
	}

	private MerlLines() {}

	private static List<String> strings(JsonElement array) {
		List<String> out = new ArrayList<>();
		for (JsonElement line : array.getAsJsonArray()) out.add(line.getAsString());
		return List.copyOf(out);
	}

	private static JsonObject resource(String name) {
		try (InputStream in = MerlLines.class.getResourceAsStream("/nicemerl/" + name)) {
			if (in == null) throw new IllegalStateException("Missing bundled resource nicemerl/" + name);
			try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
				return JsonParser.parseReader(reader).getAsJsonObject();
			}
		} catch (IOException e) {
			throw new IllegalStateException("Could not read nicemerl/" + name, e);
		}
	}

	/** Slang and abbreviations ("tp", "ench", "xp") mapped to the words the wikis use. */
	public static Map<String, String> synonyms() {
		return SYNONYMS;
	}

	/**
	 * A random line from the pool, never the same one twice in a row, with {placeholders} filled in
	 * from key/value pairs, e.g. {@code pick("thanks", "user", "Steve")}.
	 */
	public static String pick(String pool, String... values) {
		String line;
		synchronized (BAGS) {
			List<String> lines = POOLS.getOrDefault(pool, List.of(""));
			List<String> bag = BAGS.get(pool);
			if (bag == null || bag.isEmpty()) {
				bag = new ArrayList<>(lines);
				Collections.shuffle(bag, ThreadLocalRandom.current());
				if (bag.size() > 1 && bag.get(bag.size() - 1).equals(LAST.get(pool))) {
					Collections.swap(bag, 0, bag.size() - 1);
				}
				BAGS.put(pool, bag);
			}
			line = bag.remove(bag.size() - 1);
			LAST.put(pool, line);
		}
		for (int i = 0; i + 1 < values.length; i += 2) {
			line = line.replace("{" + values[i] + "}", values[i + 1]);
		}
		return forChat(line);
	}

	static boolean chance(int n) {
		return ThreadLocalRandom.current().nextInt(n) == 0;
	}

	/** Removes what Minecraft chat can't show: Markdown stars and emoji outside the basic plane. */
	static String forChat(String line) {
		StringBuilder out = new StringBuilder();
		line.codePoints()
				.filter(c -> c <= 0xFFFF && c != 0xFE0F && c != 0x200D && c != '*')
				.forEach(out::appendCodePoint);
		return out.toString().replaceAll(" {2,}", " ").replaceAll(" ([!?.,])", "$1").trim();
	}

	public static String greetingPool(LocalTime now) {
		int hour = now.getHour();
		if (hour >= 5 && hour < 12) return "greeting_morning";
		if (hour >= 12 && hour < 18) return "greeting_afternoon";
		if (hour >= 18 && hour < 23) return "greeting_evening";
		return "greeting_night";
	}

	static String normalize(String text) {
		String folded = COMBINING.matcher(Normalizer.normalize(text, Normalizer.Form.NFKD)).replaceAll("")
				.toLowerCase(Locale.ROOT).replace("'", "");
		List<String> words = new ArrayList<>();
		Matcher m = WORD.matcher(folded);
		while (m.find()) words.add(m.group());
		return String.join(" ", words);
	}

	private static List<String> words(String normalized) {
		return normalized.isEmpty() ? List.of() : Arrays.asList(normalized.split(" "));
	}

	private static String intent(String normalized) {
		for (Intent intent : INTENTS) {
			if (intent.pattern().matcher(normalized).matches()) return intent.pool();
		}
		return null;
	}

	/**
	 * The pool to answer from when the whole message is small talk ("thanks merl!"), else null.
	 * "greeting" means the caller picks the greeting for the time of day.
	 */
	public static String smallTalk(String text) {
		String normalized = normalize(text);
		return normalized.isEmpty() ? null : intent(normalized);
	}

	static boolean looksLikeQuestion(String rest, String original) {
		List<String> tokens = SearchIndex.tokenize(rest);
		if (tokens.isEmpty()) return false;
		String first = words(rest).stream().filter(w -> !FILLER_WORDS.contains(w)).findFirst().orElse("");
		return tokens.size() >= 2 || original.contains("?") || QUESTION_WORDS.contains(first);
	}

	/**
	 * For "thanks merl! how do I get a boss key": ("thanks_prefix", "how do i get a boss key").
	 * (null, text) when the message doesn't start with small talk followed by a question.
	 */
	public static Split splitSmallTalk(String text) {
		List<String> words = words(normalize(text));
		for (int k = words.size() - 1; k > 0; k--) {
			String pool = intent(String.join(" ", words.subList(0, k)));
			if (pool == null) continue;
			String rest = String.join(" ", words.subList(k, words.size()));
			if (PREFIX_POOLS.containsKey(pool) && looksLikeQuestion(rest, text)) {
				return new Split(PREFIX_POOLS.get(pool), rest);
			}
			break;
		}
		return new Split(null, text);
	}

	/** "and in the nether?", "what about the boss one" */
	public static boolean isFollowUp(String text) {
		String normalized = normalize(text) + " ";
		return FOLLOW_UP_CUES.stream().anyMatch(normalized::startsWith);
	}

	/** "excited" (CAPS, "!!"), "stressed" ("help, I'm stuck"), "terse" (one or two words) or "normal". */
	public static String energy(String text) {
		int letters = 0;
		int upper = 0;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (Character.isLetter(c)) {
				letters++;
				if (Character.isUpperCase(c)) upper++;
			}
		}
		if ((letters >= 4 && upper >= 0.7 * letters) || text.contains("!!")) return "excited";
		List<String> words = words(normalize(text));
		if (words.stream().anyMatch(STRESS_WORDS::contains)) return "stressed";
		if (words.size() <= 2) return "terse";
		return "normal";
	}

	/** What someone says they're up to ("just building my base" → "build"), after Merl asked. */
	public static String topic(String text) {
		String normalized = normalize(text);
		List<String> words = words(normalized);
		if (words.isEmpty() || text.contains("?") || words.stream().anyMatch(ASKING_WORDS::contains) || words.size() > 8) {
			return null;
		}
		String padded = " " + normalized + " ";
		for (Map.Entry<String, List<String>> topic : TOPICS.entrySet()) {
			if (topic.getValue().stream().anyMatch(w -> padded.contains(" " + w + " "))) return topic.getKey();
		}
		return null;
	}

	/** Merl's mood of the day, the same in the bot and the mod. */
	public static String mood(LocalDate day) {
		return MOODS.get((int) Math.floorMod(day.toEpochDay(), (long) MOODS.size()));
	}

	public static String pbMood(LocalDate day) {
		return PB_MOODS.get((int) Math.floorMod(day.toEpochDay() * 7 + 3, (long) PB_MOODS.size()));
	}

	/** Now and then, swap two letters in a longer word and have Merl fix it. */
	static String slip(String text) {
		if (!chance(SLIP_CHANCE)) return text;
		List<MatchResult> candidates = new ArrayList<>();
		Matcher m = LONG_WORD.matcher(text);
		while (m.find()) candidates.add(new MatchResult(m.start(), m.end(), m.group()));
		if (candidates.isEmpty()) return text;
		MatchResult hit = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
		String word = hit.word();
		int i = ThreadLocalRandom.current().nextInt(1, word.length() - 2);
		String typo = word.substring(0, i) + word.charAt(i + 1) + word.charAt(i) + word.substring(i + 2);
		if (typo.equals(word)) return text;
		return text.substring(0, hit.start()) + typo + text.substring(hit.end()) + " " + pick("slip_fix", "word", word);
	}

	private record MatchResult(int start, int end, String word) {}

	/**
	 * Puts an answer's headline together: small-talk prefix, an opener that fits the person's energy
	 * or the time of day, the line itself, sometimes a closer.
	 */
	public static String headline(String core, String prefix, String energy, int hour, String user, boolean slipOk) {
		List<String> parts = new ArrayList<>();
		if (prefix != null) parts.add(pick(prefix, "user", user));
		if (energy.equals("terse")) {
			parts.add(core);
			return String.join(" ", parts);
		}
		String opener = null;
		if (energy.equals("excited")) {
			opener = pick("excited_opener");
		} else if (energy.equals("stressed")) {
			opener = pick("calm_opener");
		} else if (prefix == null && (hour >= 23 || hour < 5) && chance(SLEEPY_CHANCE)) {
			opener = pick("sleepy_opener");
		} else if (prefix == null && chance(OPENER_CHANCE)) {
			opener = pick("opener");
		}
		if (opener != null) parts.add(opener);
		parts.add(core);
		if (opener == null && chance(CLOSER_CHANCE)) parts.add(pick("closer"));
		String text = String.join(" ", parts);
		if (energy.equals("excited") && text.endsWith("!") && chance(2)) text += "!";
		return slipOk ? slip(text) : text;
	}

	/** Usually null; now and then a little aside from Merl or Peanut Butter, colored by today's mood. */
	public static String aside(LocalDateTime now, String energy) {
		if (energy.equals("terse")) return null;
		if (chance(PB_KEYBOARD_CHANCE)) return pick("pb_keyboard");
		if (chance(pbMood(now.toLocalDate()).equals("playful") ? PB_INTERRUPT_CHANCE_PLAYFUL : PB_INTERRUPT_CHANCE)) {
			return pick("pb_interrupt");
		}
		if (!chance(ASIDE_CHANCE)) return null;
		if (now.getHour() >= 5 && now.getHour() < 10 && chance(2)) return pick("morning_aside");
		return pick(chance(2) ? "asides" : "asides_" + mood(now.toLocalDate()));
	}

	/** An answer about Peanut Butter, often about how she's doing today. */
	public static String peanutButter(LocalDate day) {
		return chance(2) ? pick("pb_" + pbMood(day)) : pick("peanut_butter");
	}

	/** After some small talk, sometimes a question back ("What are you up to today?"), else null. */
	public static String askBack(String pool) {
		return ASK_BACK_POOLS.contains(pool) && chance(ASK_BACK_CHANCE) ? pick("ask_back") : null;
	}

	public static boolean petMilestone(long count) {
		return count == 10 || count == 50 || count == 100 || count == 250 || count == 500 || (count > 0 && count % 1000 == 0);
	}

	/**
	 * A next step that fits the player's progress, or null. {@code done} says whether an advancement
	 * is done, or returns null for advancements this server doesn't have (those steps are skipped).
	 * One of the first two steps that fit is picked, so side paths come up too.
	 */
	public static String progressIdea(Function<String, Boolean> done) {
		List<ProgressIdea> fitting = new ArrayList<>();
		for (ProgressIdea idea : PROGRESS_IDEAS) {
			Boolean after = idea.after() == null ? Boolean.TRUE : done.apply(idea.after());
			Boolean unless = idea.unless() == null ? Boolean.FALSE : done.apply(idea.unless());
			if (after == null || unless == null) continue;
			if (after && !unless) fitting.add(idea);
			if (fitting.size() == 2) break;
		}
		if (fitting.isEmpty()) return null;
		List<String> lines = fitting.get(ThreadLocalRandom.current().nextInt(fitting.size())).lines();
		return forChat(lines.get(ThreadLocalRandom.current().nextInt(lines.size())));
	}
}

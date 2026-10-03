package eu.explorerseden.nicemerl;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Merl's lines, small talk and synonyms, read from lines.json and synonyms.json. Those files live in
 * the Discord bot's bot/data folder and are bundled into the mod jar at build time, so both share them.
 */
public final class MerlLines {
	/** Roughly one answer in this many gets a little aside from Merl or Peanut Butter. */
	private static final int ASIDE_CHANCE = 12;
	private static final Pattern WORD = Pattern.compile("[a-z0-9]+");
	private static final Pattern COMBINING = Pattern.compile("\\p{M}");

	private record Intent(Pattern pattern, String pool) {}

	private static final Map<String, List<String>> POOLS = new HashMap<>();
	private static final List<Intent> INTENTS = new ArrayList<>();
	private static final Map<String, String> SYNONYMS = new HashMap<>();
	private static final Map<String, List<String>> BAGS = new HashMap<>();
	private static final Map<String, String> LAST = new HashMap<>();

	static {
		JsonObject lines = resource("lines.json");
		lines.getAsJsonObject("pools").entrySet().forEach(e -> {
			List<String> pool = new ArrayList<>();
			for (JsonElement line : e.getValue().getAsJsonArray()) pool.add(line.getAsString());
			POOLS.put(e.getKey(), List.copyOf(pool));
		});
		for (JsonElement element : lines.getAsJsonArray("intents")) {
			JsonObject intent = element.getAsJsonObject();
			INTENTS.add(new Intent(Pattern.compile("(?:" + intent.get("pattern").getAsString() + ")"),
					intent.get("pool").getAsString()));
		}
		resource("synonyms.json").entrySet().forEach(e -> {
			if (!e.getKey().startsWith("_")) SYNONYMS.put(e.getKey(), e.getValue().getAsString());
		});
	}

	private MerlLines() {}

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

	/**
	 * The pool to answer from when the whole message is small talk ("thanks merl!"), else null.
	 * "greeting" means the caller picks the greeting for the time of day.
	 */
	public static String smallTalk(String text) {
		String normalized = normalize(text);
		if (normalized.isEmpty()) return null;
		for (Intent intent : INTENTS) {
			if (intent.pattern().matcher(normalized).matches()) return intent.pool();
		}
		return null;
	}

	/** Usually null; now and then a little aside. */
	public static String aside() {
		return ThreadLocalRandom.current().nextInt(ASIDE_CHANCE) == 0 ? pick("asides") : null;
	}
}

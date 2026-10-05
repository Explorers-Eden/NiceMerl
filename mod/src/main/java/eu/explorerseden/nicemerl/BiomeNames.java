package eu.explorerseden.nicemerl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads "where is the closest cherry grove?", "nearest terralith:moonlight_grove" or "where's a slime chunk"
 * and works out which biomes are meant. Knows every biome the server has (vanilla, Terralith, Biomes O' Plenty
 * or any other), by id, by its name in the id and by its English name. No Minecraft classes, so it can be tested
 * on its own; MerlLocate does the searching in the world.
 */
public final class BiomeNames {
	/** Words that make a message a "where is the nearest …" question. */
	private static final Pattern LOCATE_CUE = Pattern.compile(
			"\\b(where|wheres|nearest|closest|nearby|near me|locate|how far|coords?|coordinates|which way|direction)\\b"
			+ "|\\bfind (me )?(a|an|the|some)?\\b|" + MerlLines.GUIDE_PHRASES);
	/** Words around the biome's name that aren't part of it. */
	private static final Set<String> LOCATE_WORDS = Set.of("nearest", "closest", "near", "nearby", "locate", "location",
			"biome", "biomes", "coord", "coords", "coordinate", "coordinates", "direction", "far", "go", "spot", "place",
			"area", "around", "here", "one", "look", "search", "show", "way", "which", "next", "is", "find", "found",
			"lead", "guide", "take", "bring", "navigate", "walk", "path", "route", "directions", "give",
			"point", "help", "get", "travel", "road", "escort", "towards", "back",
			"spawn", "spawns", "generate", "generates", "located", "situated", "best", "good", "big", "large", "small");
	private static final Pattern ID = Pattern.compile("\\b([a-z0-9_.-]+):([a-z0-9_/.-]+)\\b");
	/**
	 * Short names people use for a pack. Other packs are found by their name written out: "katters structures"
	 * or "biomes o plenty" is the namespace kattersstructures or biomesoplenty without the spaces.
	 */
	private static final Map<String, String> NAMESPACE_ALIASES = Map.of("bop", "biomesoplenty", "vanilla", "minecraft");
	private static final Pattern WORD = Pattern.compile("[a-z0-9]+");
	/** Biomes that share their name with a dimension, so "where is the end" isn't a biome question. */
	private static final Set<String> ONLY_BY_ID = Set.of("minecraft:the_end", "minecraft:the_void");
	/** "where is the nether" means the dimension, not one of its biomes (Nether Wastes is still found by name). */
	private static final Set<String> DIMENSION_WORDS = Set.of("end", "nether", "overworld");
	/** The words of every dimension's name ("deep blue"), which mean the dimension too. */
	private final List<Set<String>> dimensions = new ArrayList<>();
	/** Common names that aren't the biome's real name. */
	private static final Map<String, String> NICKNAMES = Map.of("mushroom islands", "mushroom fields",
			"mushroom island", "mushroom fields", "mesa", "badlands", "pale forest", "pale garden",
			"cherry blossom forest", "cherry grove", "lush cave", "lush caves", "dripstone cave", "dripstone caves");
	private static final Set<String> SLIME_WORDS = Set.of("slime", "chunk", "farm");
	private static final Pattern HERE = Pattern.compile("\\b(this|here|am i|im in|i'm in|i am in|standing)\\b");

	/** What was asked: a biome search (any of these ids, the closest wins), or a slime chunk. */
	public record Request(List<String> biomes, boolean slime, boolean here) {
		static Request slimeChunk(boolean here) {
			return new Request(List.of(), true, here);
		}
	}

	/** id → its words (from the id and from its English name), for matching. */
	private final Map<String, List<Set<String>>> names = new LinkedHashMap<>();
	private final Map<String, String> display = new LinkedHashMap<>();
	/** Each pack's namespace without separators ("fabledroots") → the namespace ("fabled_roots"). */
	private final Map<String, String> namespaces = new LinkedHashMap<>();

	public BiomeNames(Map<String, String> biomes) {
		this(biomes, List.of());
	}

	/**
	 * @param biomes id → English name, or null when the server has none (the name then comes from the id)
	 * @param dimensionIds the server's dimensions, like "kattersstructures:deep_blue"
	 */
	public BiomeNames(Map<String, String> biomes, List<String> dimensionIds) {
		for (String id : dimensionIds) dimensions.add(terms(id.substring(id.indexOf(':') + 1).replace('_', ' ')));
		biomes.forEach((id, name) -> {
			String path = id.substring(id.indexOf(':') + 1);
			String fromId = path.substring(path.lastIndexOf('/') + 1).replace('_', ' ');
			List<Set<String>> forms = new ArrayList<>();
			forms.add(terms(fromId));
			if (name != null && !name.isBlank()) forms.add(terms(name));
			names.put(id, forms);
			display.put(id, name != null && !name.isBlank() ? name : titleCase(fromId));
			String namespace = id.substring(0, id.indexOf(':'));
			namespaces.put(namespace.replaceAll("[^a-z0-9]", ""), namespace);
		});
	}

	public String display(String id) {
		return display.getOrDefault(id, id);
	}

	/** The request in the message, or null when it isn't asking where a biome or slime chunk is. */
	public Request parse(String message) {
		String text = message.toLowerCase(Locale.ROOT).replace('’', '\'');
		if (!LOCATE_CUE.matcher(text).find() && !text.contains("slime chunk")) return null;
		Matcher id = ID.matcher(text);
		while (id.find()) {
			String full = id.group(1) + ":" + id.group(2);
			if (names.containsKey(full)) return new Request(List.of(full), false, false);
		}
		text = text.replace("'", "");
		for (Map.Entry<String, String> nickname : NICKNAMES.entrySet()) {
			text = text.replaceAll("\\b" + nickname.getKey() + "\\b", nickname.getValue());
		}
		// A pack's name narrows it down: "terralith", "biomes o' plenty", "katters structures".
		String namespace = null;
		List<String> words = new ArrayList<>();
		Matcher word = WORD.matcher(text);
		while (word.find()) words.add(word.group());
		for (int start = 0; start < words.size() && namespace == null; start++) {
			StringBuilder joined = new StringBuilder();
			for (int end = start; end < Math.min(words.size(), start + 4); end++) {
				joined.append(words.get(end));
				String found = namespaces.get(joined.toString());
				if (found == null && end == start) found = NAMESPACE_ALIASES.get(joined.toString());
				if (found != null && (namespaces.containsValue(found))) {
					namespace = found;
					words.subList(start, end + 1).clear();
					text = String.join(" ", words);
					break;
				}
			}
		}
		Set<String> asked = new LinkedHashSet<>();
		for (SearchIndex.Word w : SearchIndex.words(text)) {
			if (!LOCATE_WORDS.contains(w.word())) asked.add(w.term());
		}
		if (asked.isEmpty()) return null;
		if (asked.contains("slime") && SLIME_WORDS.containsAll(asked)) return Request.slimeChunk(HERE.matcher(text).find());

		// The biome named exactly, else every biome whose name has all the words ("snowy" → the snowy ones).
		List<String> exact = new ArrayList<>(), family = new ArrayList<>();
		for (Map.Entry<String, List<Set<String>>> e : names.entrySet()) {
			if (ONLY_BY_ID.contains(e.getKey())) continue;
			if (namespace != null && !e.getKey().startsWith(namespace + ":")) continue;
			for (Set<String> form : e.getValue()) {
				if (form.equals(asked)) exact.add(e.getKey());
				else if (form.containsAll(asked)) family.add(e.getKey());
			}
		}
		if (exact.isEmpty() && (DIMENSION_WORDS.containsAll(asked) || dimensions.contains(asked))) return null;
		List<String> found = !exact.isEmpty() ? exact : family;
		return found.isEmpty() ? null : new Request(List.copyOf(new LinkedHashSet<>(found)), false, false);
	}

	private static Set<String> terms(String name) {
		return new HashSet<>(SearchIndex.tokenize(name.replace('\'', ' ')));
	}

	private static String titleCase(String words) {
		StringBuilder out = new StringBuilder();
		for (String w : words.split(" ")) {
			if (w.isEmpty()) continue;
			if (!out.isEmpty()) out.append(' ');
			out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
		}
		return out.toString();
	}

	/** "north", "southeast", … for a step of dx blocks east and dz blocks south. */
	public static String direction(double dx, double dz) {
		String[] names = {"north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest"};
		double angle = Math.toDegrees(Math.atan2(dx, -dz));
		return names[(int) Math.round((angle + 360) % 360 / 45) % 8];
	}
}

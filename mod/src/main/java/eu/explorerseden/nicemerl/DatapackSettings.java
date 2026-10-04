package eu.explorerseden.nicemerl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

/** Reads the live data pack settings from command storage and searches them. */
public final class DatapackSettings {
	/** Words that mark a question as being about current settings (stemmed like the search). */
	private static final Set<String> INTENT = Set.copyOf(SearchIndex.tokenize("""
			setting settings config configs configured configuration option options enabled disabled
			current currently value values turned active activated status toggle toggled gamerule gamerules
			allowed allow permitted rule rules server world setup
			"""));
	/**
	 * Player words and the words settings use for them ("xp" → "Exp Loss", "pets" → "Leashed Mobs"),
	 * and questions that ask for a kind of value ("how long" → a duration, warm-up or cooldown).
	 */
	private static final Map<String, String> SETTING_WORDS = Map.ofEntries(
			Map.entry("xp", "exp experience"), Map.entry("experience", "exp"), Map.entry("levels", "exp level"),
			Map.entry("armor", "equipment"), Map.entry("armour", "equipment"), Map.entry("gear", "equipment"),
			Map.entry("tools", "equipment"), Map.entry("durability", "equipment damage"),
			Map.entry("damaged", "damage"), Map.entry("damages", "damage"), Map.entry("broke", "breaking"),
			Map.entry("pets", "leashed mobs"), Map.entry("pet", "leashed mob"), Map.entry("animals", "leashed mobs"),
			Map.entry("dog", "leashed mob"), Map.entry("horse", "leashed mob"), Map.entry("leash", "leashed"),
			Map.entry("day", "daylight cycle"), Map.entry("days", "daylight cycle"), Map.entry("night", "daylight cycle"),
			Map.entry("random", "rtp"), Map.entry("rtp", "random"), Map.entry("minimum", "min"), Map.entry("maximum", "max"),
			Map.entry("sitting", "sit"), Map.entry("changing", "change"), Map.entry("clock", "time format"),
			Map.entry("hour", "time format"), Map.entry("destroy", "block damage griefing"), Map.entry("destroys", "block damage griefing"),
			Map.entry("bosses", "boss"), Map.entry("difficult", "difficulty"), Map.entry("hard", "difficulty"), Map.entry("waypoint", "waypoint hub"),
			Map.entry("waypoints", "waypoint hub"), Map.entry("die", "death"), Map.entry("dying", "death"),
			Map.entry("died", "death"), Map.entry("dies", "death"), Map.entry("lose", "loss"), Map.entry("losing", "loss"),
			Map.entry("lost", "loss"), Map.entry("break", "breaking"), Map.entry("breaks", "breaking"),
			Map.entry("warmup", "warm up"), Map.entry("countdown", "warm up"), Map.entry("delay", "warm up cooldown"),
			Map.entry("last", "duration"), Map.entry("disappear", "duration"), Map.entry("despawn", "duration"),
			Map.entry("bossbar", "boss bar"), Map.entry("bossbars", "boss bars"), Map.entry("teleporting", "teleport"),
			Map.entry("tp", "teleport"), Map.entry("look", "appearance"), Map.entry("looks", "appearance"),
			Map.entry("heads", "head"), Map.entry("skull", "head"), Map.entry("homes", "home"), Map.entry("event", "events"),
			Map.entry("griefing", "griefing"), Map.entry("explosions", "griefing"), Map.entry("creepers", "creeper griefing"));
	private static final List<Map.Entry<Pattern, String>> VALUE_QUESTIONS = List.of(
			Map.entry(Pattern.compile("\\bhow long\\b"), "duration length warm up cooldown minutes seconds"),
			Map.entry(Pattern.compile("\\bhow many\\b"), "max count number amount"),
			Map.entry(Pattern.compile("\\bhow much\\b"), "amount cost chance percent"),
			Map.entry(Pattern.compile("\\bhow (far|big|wide)\\b|\\bapart\\b"), "distance radius range"),
			Map.entry(Pattern.compile("\\bhow often\\b|\\bhow likely\\b|\\bodds\\b"), "chance cooldown"),
			Map.entry(Pattern.compile("\\bcosts?\\b|\\bpay\\b"), "cost"),
			Map.entry(Pattern.compile("\\bwait\\b"), "cooldown warm up"),
			Map.entry(Pattern.compile("\\bhow (high|low)\\b"), "height"),
			Map.entry(Pattern.compile("\\bhow (hard|difficult|tough)\\b"), "difficulty"));
	/** Phrases for settings that players say in other words ("fight other players" → PvP). */
	private static final List<Map.Entry<Pattern, String>> SETTING_PHRASES = List.of(
			Map.entry(Pattern.compile("\\bplayer (vs|versus|v) player\\b|\\b(fight|kill|attack|hit|hurt) (other )?players\\b"), "pvp"));
	/** Questions that ask about a setting ("is…", "can I…", "does…"), as opposed to "how does … work". */
	private static final Pattern SETTINGS_SHAPED = Pattern.compile("^(is|are|can|do|does|did|will|was|has|have|should|when)\\b");
	/** Words around a pack name that don't change what's asked ("show me the nice keep inventory config"). */
	private static final Set<String> PACK_FILLER = Set.copyOf(SearchIndex.tokenize("show list tell see all current server whole every"));
	/** Label words that only describe a setting ("Amount", "Duration"), so a question doesn't have to say them. */
	private static final Set<String> GENERIC_LABEL_WORDS = Set.copyOf(SearchIndex.tokenize("""
			amount type active enabled enable chance duration cost max min maximum minimum per player players seconds
			minutes level levels time length radius distance count number settings setting cooldown percent percentage
			between toggle teleport
			"""));
	/** Questions about getting or finding something are for the wiki, not for settings. */
	private static final Pattern WIKI_QUESTION = Pattern.compile(
			"\\b(where|craft|crafting|recipe|obtain|find|build|tame|summon|spawn egg)\\b|\\bhow (do|can) (i|you) (get|make)\\b");

	/** Words that ask about the settings themselves, so a miss is worth telling ("what are the settings for…"). */
	private static final Set<String> SETTINGS_WORDS = Set.copyOf(SearchIndex.tokenize("""
			setting settings config configs configured configuration option options gamerule gamerules toggle
			"""));
	/**
	 * Words that ask for a value ("what is the chance for …"). They mark a settings question
	 * too, but stay in the search so "Rarity Chance" beats the other rarity mob settings.
	 */
	private static final Set<String> ATTRIBUTE = Set.copyOf(SearchIndex.tokenize("""
			chance chances rate rates percent percentage probability odds likely often amount limit limits
			max maximum min minimum cooldown duration delay multiplier radius range
			"""));

	/**
	 * @param label readable name, e.g. "Blaze › Spawn Chance" (English, for searching)
	 * @param keyWords the raw storage keys as words, e.g. "blaze spawn chance"
	 * @param value the raw stored value, e.g. "enabled" or "taglist"
	 * @param labelText the name shown in chat (translated on clients that have the pack's language files)
	 * @param valueText the value shown in chat
	 */
	public record Setting(String pack, String label, String keyWords, String value,
			MutableComponent labelText, MutableComponent valueText) {}

	private DatapackSettings() {}

	/** The question with player words translated to setting words ("xp" → "exp"). */
	static String translate(String question) {
		String lower = question.toLowerCase(Locale.ROOT).replace("'", "");
		StringBuilder out = new StringBuilder(lower);
		for (String word : lower.split("\\W+")) {
			String extra = SETTING_WORDS.get(word);
			if (extra != null) out.append(' ').append(extra);
		}
		for (Map.Entry<Pattern, String> phrase : SETTING_PHRASES) {
			if (phrase.getKey().matcher(lower).find()) out.append(' ').append(phrase.getValue());
		}
		return out.toString();
	}

	/** The kinds of value asked for: "how long" → duration, warm-up, cooldown… */
	static Set<String> valueWords(String question) {
		String lower = question.toLowerCase(Locale.ROOT);
		Set<String> out = new HashSet<>();
		for (Map.Entry<Pattern, String> value : VALUE_QUESTIONS) {
			if (value.getKey().matcher(lower).find()) out.addAll(SearchIndex.tokenize(value.getValue()));
		}
		return out;
	}

	/** The question translated, with the kinds of value it asks for spelled out. */
	static String expand(String question) {
		return translate(question) + " " + String.join(" ", valueWords(question));
	}

	public static boolean isSettingsQuestion(String question) {
		for (String word : question.toLowerCase(Locale.ROOT).split("\\W+")) {
			if (word.equals("on") || word.equals("off")) return true;
		}
		for (String token : SearchIndex.tokenize(question)) {
			if (INTENT.contains(token) || ATTRIBUTE.contains(token)) return true;
		}
		return false;
	}

	/** "what are the keep inventory settings", "show me the config": a miss gets a list of the known packs. */
	public static boolean mentionsSettings(String question) {
		return SearchIndex.tokenize(question).stream().anyMatch(SETTINGS_WORDS::contains);
	}

	/** Names of the packs that have readable settings, in config order. */
	public static List<String> packs(MinecraftServer server, MerlConfig config) {
		return read(server, config).stream().map(Setting::pack).distinct().toList();
	}

	/**
	 * Returns the settings that best match the question, strongest first. {@code strict} is for
	 * questions that don't sound like settings questions ("can I pvp", "keep inventory"): then a
	 * setting only counts when the question names it, or names its pack.
	 */
	public static List<Setting> search(MinecraftServer server, MerlConfig config, String question, int limit, boolean strict) {
		return rank(read(server, config), question, limit, strict);
	}

	static List<Setting> rank(List<Setting> settings, String question, int limit) {
		return rank(settings, question, limit, false);
	}

	static List<Setting> rank(List<Setting> settings, String question, int limit, boolean strict) {
		if (settings.isEmpty()) return List.of();
		boolean wikiQuestion = WIKI_QUESTION.matcher(question.toLowerCase(Locale.ROOT)).find();
		if (strict && wikiQuestion) return List.of();
		String original = question;
		question = expand(question);

		// Only the subject of the question counts ("pvp", "grave type"), not words like
		// "enabled" or "settings" that would match every setting.
		List<String> subject = new ArrayList<>();
		for (String word : question.toLowerCase(Locale.ROOT).split("\\W+")) {
			List<String> tokens = SearchIndex.tokenize(word);
			if (!tokens.isEmpty() && !INTENT.contains(tokens.get(0))) {
				subject.add(word);
			}
		}
		if (subject.isEmpty()) return List.of();
		// Setting keys often glue words together ("bossbars"), so also try adjacent words joined.
		StringBuilder query = new StringBuilder(String.join(" ", subject));
		for (int i = 0; i + 1 < subject.size(); i++) {
			query.append(' ').append(subject.get(i)).append(subject.get(i + 1));
		}
		Set<String> terms = new HashSet<>(SearchIndex.tokenize(query.toString()));

		Named named = new Named(settings);
		Set<String> asked = new HashSet<>(SearchIndex.tokenize(String.join(" ", subject)));
		asked.removeAll(PACK_FILLER);
		// "is keep inventory on": a setting named exactly that wins over the pack of the same name,
		// unless the question asks for settings ("keep inventory settings").
		if (!mentionsSettings(original)) {
			for (Setting st : settings) {
				if (!asked.isEmpty() && asked.equals(new HashSet<>(SearchIndex.tokenize(st.label())))) return List.of(st);
			}
		}
		// A question that only names a pack ("keep inventory settings") gets that pack's settings.
		for (String pack : settings.stream().map(Setting::pack).distinct().toList()) {
			Set<String> packWords = new HashSet<>(SearchIndex.tokenize(pack));
			List<String> packTerms = named.packTerms(pack);
			Set<String> rest = new HashSet<>(asked);
			rest.removeAll(packWords);
			if (!packTerms.isEmpty() && asked.containsAll(packTerms) && rest.isEmpty()) {
				return settings.stream().filter(st -> st.pack().equals(pack)).limit(limit).toList();
			}
		}

		List<Section> docs = new ArrayList<>();
		List<Set<String>> docTerms = new ArrayList<>();
		for (int i = 0; i < settings.size(); i++) {
			Setting s = settings.get(i);
			String text = s.keyWords() + " " + s.value();
			// A word hidden inside a glued key ("chance" in "spawnchance") counts as a match too.
			String glued = s.keyWords().replace(" ", "").toLowerCase(Locale.ROOT);
			for (String term : terms) {
				if (term.length() >= 4 && glued.contains(term)) text += " " + term;
			}
			// The anchor carries the index back from the search result.
			docs.add(new Section("", s.pack(), s.label(), Integer.toString(i), text));
			docTerms.add(new HashSet<>(SearchIndex.tokenize(s.label() + " " + text)));
		}
		// Settings are a small collection where pack names repeat a lot, so any match counts.
		List<SearchIndex.Result> results = new SearchIndex(docs).search(query.toString(), Integer.MAX_VALUE, 0, 0, false);
		if (strict) {
			// Not phrased as a settings question: the question has to name the setting (all its key words,
			// "how long do graves last" → Grave Duration) or its pack.
			Set<String> askedTerms = new HashSet<>(SearchIndex.tokenize(translate(original)));
			String lower = original.toLowerCase(Locale.ROOT).strip();
			// Key words alone are enough for "is…/can I…/does…", value questions and short messages,
			// not for "how do graves work" or "what is a waypoint lock".
			boolean shaped = SETTINGS_SHAPED.matcher(lower).find() || !valueWords(original).isEmpty()
					|| lower.split("\\s+").length <= 3;
			results = results.stream()
					.filter(r -> {
						Setting st = settings.get(Integer.parseInt(r.section().anchor()));
						return named.named(st, terms) || shaped && named.keyWordsAsked(st, terms, askedTerms);
					})
					.toList();
		}
		if (results.isEmpty()) return List.of();

		// Settings matching more of the question's words win ("rarity mob chance" should list the
		// rarity chances, not every rarity mob setting), then the score decides.
		int[] coverage = new int[results.size()];
		int bestCoverage = 0;
		for (int i = 0; i < results.size(); i++) {
			Set<String> words = docTerms.get(Integer.parseInt(results.get(i).section().anchor()));
			for (String term : terms) {
				if (words.contains(term)) coverage[i]++;
			}
			bestCoverage = Math.max(bestCoverage, coverage[i]);
		}
		// "how far…" wants a distance or radius, "cost xp" a cost: settings of that kind come first.
		Set<String> wanted = valueWords(original);
		if (!wanted.isEmpty()) {
			List<SearchIndex.Result> ofKind = results.stream()
					.filter(r -> SearchIndex.tokenize(settings.get(Integer.parseInt(r.section().anchor())).label()).stream()
							.anyMatch(wanted::contains))
					.toList();
			if (!ofKind.isEmpty()) {
				results = ofKind;
				coverage = new int[results.size()];
				bestCoverage = 0;
				Set<String> subjectTerms = new HashSet<>(SearchIndex.tokenize(translate(original)));
				for (int i = 0; i < results.size(); i++) {
					Set<String> words = docTerms.get(Integer.parseInt(results.get(i).section().anchor()));
					// The words asked about each count; the kind of value ("how long") counts once.
					for (String term : subjectTerms) if (words.contains(term)) coverage[i]++;
					bestCoverage = Math.max(bestCoverage, coverage[i]);
				}
			}
		}
		List<SearchIndex.Result> best = new ArrayList<>();
		for (int i = 0; i < results.size(); i++) {
			if (coverage[i] == bestCoverage) best.add(results.get(i));
		}

		// Keep only settings that match about as well as the best one.
		double cutoff = best.get(0).score() * 0.5;
		List<Setting> matches = new ArrayList<>();
		for (SearchIndex.Result r : best) {
			if (r.score() < cutoff || matches.size() >= limit) break;
			matches.add(settings.get(Integer.parseInt(r.section().anchor())));
		}
		return matches;
	}

	/**
	 * Whether a question names a setting: all words of its name ("Blaze › Spawn Chance"), a name that is a
	 * single word no other setting uses ("PvP"), or the distinctive words of its pack ("Keep Inventory").
	 */
	private static final class Named {
		private final Map<String, Integer> labelUse = new java.util.HashMap<>();
		private final Map<String, Integer> packUse = new java.util.HashMap<>();

		Named(List<Setting> settings) {
			for (Setting s : settings) {
				for (String t : new HashSet<>(SearchIndex.tokenize(s.label()))) labelUse.merge(t, 1, Integer::sum);
			}
			for (String pack : settings.stream().map(Setting::pack).distinct().toList()) {
				for (String t : new HashSet<>(SearchIndex.tokenize(pack))) packUse.merge(t, 1, Integer::sum);
			}
		}

		boolean named(Setting s, Set<String> terms) {
			List<String> label = SearchIndex.tokenize(s.label());
			if (!label.isEmpty() && terms.containsAll(label)
					&& (new HashSet<>(label).size() > 1 || labelUse.getOrDefault(label.get(0), 0) == 1)) {
				return true;
			}
			List<String> pack = packTerms(s.pack());
			return !pack.isEmpty() && terms.containsAll(pack);
		}

		/**
		 * The question names every key word of the setting ("Grave" for "Grave Duration (Minutes)"), counting
		 * translated words ("xp" for "Exp"), and at least one of them is in the question as asked.
		 */
		boolean keyWordsAsked(Setting s, Set<String> terms, Set<String> asked) {
			// The setting's own name; its group ("Waypoint Hub › …") helps the ranking but needn't be said.
			String leaf = s.label().substring(s.label().lastIndexOf('›') + 1);
			Set<String> key = new HashSet<>(SearchIndex.tokenize(leaf));
			key.removeAll(GENERIC_LABEL_WORDS);
			if (key.isEmpty() || !terms.containsAll(key)) return false;
			// A one-word key must be asked as such; longer keys may come entirely from translations ("day" → Daylight Cycle).
			return key.size() > 1 || key.stream().anyMatch(asked::contains);
		}

		/** The words of a pack name that identify it; words shared by several packs ("Nice") don't. */
		List<String> packTerms(String pack) {
			return SearchIndex.tokenize(pack).stream().filter(t -> packUse.getOrDefault(t, 0) == 1).toList();
		}
	}

	static List<Setting> read(MinecraftServer server, MerlConfig config) {
		SettingLabels labels = NiceMerl.settingLabels();
		List<Setting> settings = new ArrayList<>();
		for (MerlConfig.SettingsSource source : config.settingsSources) {
			Identifier id = Identifier.tryParse(source.storage);
			if (id == null) {
				NiceMerl.LOGGER.warn("Invalid storage id in config: {}", source.storage);
				continue;
			}
			Optional<CompoundTag> root = Optional.of(server.getCommandStorage().get(id));
			for (String key : source.path.isEmpty() ? new String[0] : source.path.split("\\.")) {
				root = root.flatMap(tag -> tag.getCompound(key));
			}
			List<Setting> found = new ArrayList<>();
			Set<Setting> labelled = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
			Walk walk = new Walk(source.storage, source.name, config.settingsIgnoreKeys, labels, found, labelled);
			root.ifPresent(tag -> walk.flatten(tag, source.path, null, "", ""));
			// When a pack names its settings in a config dialog, everything else it stores is
			// internal bookkeeping ("type", "bodyicon", …), so only show the named ones.
			if (labelled.isEmpty()) {
				settings.addAll(found);
			} else {
				for (Setting setting : found) {
					if (labelled.contains(setting)) settings.add(setting);
				}
			}
		}
		return settings;
	}

	private record Walk(String storage, String pack, List<String> ignore, SettingLabels labels,
			List<Setting> out, Set<Setting> labelled) {
		/**
		 * @param path full storage path of {@code tag}
		 * @param group readable names of the compounds below the source, e.g. "Blaze", or null
		 */
		void flatten(CompoundTag tag, String path, MutableComponent group, String groupText, String groupKeys) {
			for (Map.Entry<String, Tag> entry : tag.entrySet()) {
				String key = entry.getKey();
				if (ignored(key, ignore)) continue;
				String full = path.isEmpty() ? key : path + "." + key;
				SettingLabels.Label known = labels.key(storage, full);
				String name = known != null ? known.fallback() : humanize(key);
				MutableComponent nameText = known != null ? known.component() : Component.literal(name);

				MutableComponent label = group == null ? nameText
						: group.copy().append(" › ").append(nameText);
				String labelString = groupText.isEmpty() ? name : groupText + " › " + name;
				String keyWords = (groupKeys + " " + key.replace('_', ' ')).trim();

				Tag value = entry.getValue();
				if (value instanceof CompoundTag compound) {
					flatten(compound, full, label, labelString, keyWords);
				} else if (value instanceof StringTag string) {
					SettingLabels.Label option = labels.value(storage, full, string.value());
					MutableComponent valueText = option != null ? option.component() : Component.literal(string.value());
					String searchValue = option != null ? string.value() + " " + option.fallback() : string.value();
					add(new Setting(pack, labelString, keyWords, searchValue, label, valueText), known != null);
				} else if (value instanceof NumericTag number) {
					String n = formatNumber(number.box());
					// Booleans are stored as 1b/0b but offered as "true"/"false" in the dialogs.
					String asBool = n.equals("1") ? "true" : n.equals("0") ? "false" : null;
					SettingLabels.Label option = asBool == null ? null : labels.value(storage, full, asBool);
					if (option != null) {
						add(new Setting(pack, labelString, keyWords, asBool + " " + option.fallback(), label,
								option.component()), known != null);
					} else {
						String shown = labels.percent(storage, full) ? percent(number.box()) : n;
						add(new Setting(pack, labelString, keyWords, n, label, Component.literal(shown)), known != null);
					}
				}
				// Lists and arrays are internal data, not settings.
			}
		}

		private void add(Setting setting, boolean hasLabel) {
			out.add(setting);
			if (hasLabel) labelled.add(setting);
		}
	}

	private static boolean ignored(String key, List<String> patterns) {
		for (String pattern : patterns) {
			if (pattern.startsWith("*") ? key.endsWith(pattern.substring(1))
					: pattern.endsWith("*") ? key.startsWith(pattern.substring(0, pattern.length() - 1))
					: key.equals(pattern)) {
				return true;
			}
		}
		return false;
	}

	private static String humanize(String key) {
		String words = key.replace('_', ' ').trim();
		if (words.isEmpty()) return key;
		return words.substring(0, 1).toUpperCase(Locale.ROOT) + words.substring(1);
	}

	/**
	 * Percent sliders store either the percentage itself (10) or a fraction for predicates
	 * (0.1f, from "store result … float 0.01"), so decimals up to 1 are read as fractions.
	 */
	private static String percent(Number number) {
		double d = number.doubleValue();
		boolean fraction = (number instanceof Float || number instanceof Double) && d <= 1;
		return formatNumber(fraction ? d * 100 : d) + "%";
	}

	private static String formatNumber(Number number) {
		// Round first: floats like 0.1f * 100 come out as 10.000000149.
		double d = Math.round(number.doubleValue() * 1000) / 1000.0;
		if (d == Math.rint(d) && Math.abs(d) < 1e15) {
			return Long.toString((long) d);
		}
		return Double.toString(d);
	}
}

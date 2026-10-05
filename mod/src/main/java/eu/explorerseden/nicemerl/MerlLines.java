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
	private static final Set<String> ASK_BACK_POOLS = Set.of("how_are_you", "bored", "greeting", "idea", "what_doing",
			"feeling_good", "feeling_meh");
	private static final int ASK_BACK_CHANCE = 3;
	/** After this small talk, Merl sometimes asks how you are, then listens for "good, you?". */
	private static final Set<String> ASK_FEELING_POOLS = Set.of("how_are_you", "greeting");
	private static final int ASK_FEELING_CHANCE = 2;
	private static final int FEELING_MAX_WORDS = 10;
	/** A message this short that names a page title is a topic search ("boss keys"), even if it's not a question. */
	private static final int TOPIC_WORDS = 2;

	/** Friendship: after this many chats Merl greets you like an old friend now and then. */
	public static final int FRIEND_CHATS = 50;
	public static final int FRIEND_GREETING_CHANCE = 3;
	/** How long (days) Merl asks about what you said you're up to, or about the page that last helped. */
	private static final int TOPIC_FOLLOW_UP_DAYS = 14;
	private static final int PAGE_FOLLOW_UP_DAYS = 7;
	/** Days since you met that Merl mentions; after a year, every year. */
	private static final int[] ANNIVERSARIES = {7, 30, 100, 365};
	/** Numbers of chats Merl mentions; after that, every thousand. */
	private static final int[] CHAT_MILESTONES = {10, 25, 50, 100, 250, 500};
	/** Small talk after which a cheerful "that was our 50th chat!" would be out of place; it waits for the next chat. */
	public static final Set<String> QUIET_TALK = Set.of("stop", "forget_me", "insult", "wrong", "comfort", "sorry", "confused",
			"goodbye", "tired");

	/** A friendship line (or null), plus the chat milestone and anniversary to remember as mentioned. */
	public record Note(String line, int noted, int anniversary) {}

	/** Small talk that can start a question ("thanks! how do I…"), and the short line it gets. */
	private static final Map<String, String> PREFIX_POOLS = Map.of("greeting", "greeting_prefix", "thanks", "thanks_prefix",
			"sorry", "sorry_prefix", "ok", "ok_prefix", "no", "ok_prefix", "compliment", "compliment_prefix", "laugh", "compliment_prefix");
	/** Small talk that "more" / "another one" asks for again. */
	public static final Set<String> REPEATABLE = Set.of("joke", "fact", "tip", "idea", "story", "sing", "creeper_song", "pet_pb", "hungry");
	/** Sentences and clauses, for messages with several bits of small talk ("you're funny! tell me a joke"). */
	private static final Pattern CLAUSE = Pattern.compile("[.!?,;]+");
	/** "so it's the bosses?" right after an answer: confusion about that answer, not a new question. */
	private static final List<String> CLARIFY_CUES = List.of("so ", "wait so ", "you mean ", "do you mean ", "are you saying ",
			"so youre saying ", "so basically ");
	/** "have you met Alex?" is about a person; "do you know Alex?" only when Alex is someone Merl knows. */
	private static final Pattern MET_STRICT = Pattern.compile("(?:have you (?:ever )?(?:met|talked to|spoken to|chatted with)"
			+ "|did you (?:meet|talk to))\\s+@?(.{2,40}?)(?:\\s+(?:yet|before|already))?\\s*[?!.]*", Pattern.CASE_INSENSITIVE);
	private static final Pattern MET_LOOSE = Pattern.compile("(?:do you know|do you remember|you know)\\s+@?(.{2,40}?)\\s*[?!.]*",
			Pattern.CASE_INSENSITIVE);
	private static final Set<String> NOT_NAMES = Set.of("me", "you", "yourself", "him", "her", "them", "anyone", "someone",
			"everyone", "my name");
	private static final Set<String> NOT_NAME_STARTS = Set.of("how", "what", "where", "why", "when", "which", "who", "the", "a",
			"an", "any", "about", "if", "that", "this", "my", "your", "some", "of", "to");

	/** "you're funny! tell me a joke" → the answer to the last bit of small talk, with a short reply to the first. */
	public record MultiTalk(String prefix, String talk) {}

	/** "have you met NotNiceRon yet?" → the name, and whether it's surely about a person. */
	public record Met(String name, boolean strict) {}
	private static final Set<String> QUESTION_WORDS = Set.of("how", "what", "where", "why", "when", "which", "who", "can", "is",
			"does", "do", "are", "should", "could", "will", "whats", "wheres", "hows", "whos", "whys");
	/** Words that can come between small talk and the question ("ok so what is…"). */
	private static final Set<String> FILLER_WORDS = Set.of("so", "and", "um", "uh", "btw", "but", "also", "like", "quick", "question");
	private static final Set<String> ASKING_WORDS = Set.of("how", "what", "where", "why", "when", "which", "who", "whats", "wheres", "hows");
	private static final List<String> FOLLOW_UP_CUES = List.of("and ", "also ", "what about ", "how about ", "but what about ", "and what about ");
	private static final Set<String> STRESS_WORDS = Set.of("help", "stuck", "urgent", "asap", "broken", "lost", "cant", "confused",
			"sos", "desperate", "panic");
	/**
	 * Words that show someone wants information. Without one (or a "?"), a weak wiki match is more likely
	 * a misread comment ("NO! Stop!") than a question, so Merl asks what they mean instead.
	 */
	private static final Set<String> INFO_WORDS = java.util.stream.Stream.concat(QUESTION_WORDS.stream(), java.util.stream.Stream.of(
			"find", "show", "explain", "info", "information", "recipe", "craft", "crafting", "get", "obtain", "make",
			"build", "spawn", "spawns", "location", "locate", "about", "guide", "tutorial", "help", "need", "looking",
			"search", "learn", "page", "setting", "settings", "config", "enabled", "disabled", "allowed", "chance",
			"drop", "drops", "use", "work", "works", "tame", "breed", "summon", "beat", "kill", "defeat", "enchant",
			"brew", "trade", "farm", "upgrade", "repair", "unlock", "requirements", "difference", "best", "tell"))
			.collect(java.util.stream.Collectors.toUnmodifiableSet());
	private static final Pattern LONG_WORD = Pattern.compile("[A-Za-z]{6,}");
	private static final Pattern WORD = Pattern.compile("[a-z0-9]+");
	private static final Pattern COMBINING = Pattern.compile("\\p{M}");
	private static final Pattern FIRST_WORD = Pattern.compile("[^A-Za-z]*([A-Za-z]+)");
	private static final Pattern REPEATS = Pattern.compile("(.)\\1+");
	/** How far down the bag pick() looks for a line that starts differently from the last one. */
	private static final int START_LOOKAHEAD = 6;
	/** Line starts that are just a sound or a filler word; an opener in front of one sounds doubled ("Ooh! Oh, found it!"). */
	private static final Set<String> INTERJECTIONS = java.util.stream.Stream.of("oh", "ah", "aha", "hmm", "hm", "hehe", "haha",
			"yay", "okay", "ok", "okie", "alright", "right", "well", "so", "wow", "oops", "aww", "hey", "yes", "yep", "mhm", "mm", "eh",
			"teehee", "whoa", "woohoo", "woo", "yippee", "hooray", "ta").map(MerlLines::start).collect(java.util.stream.Collectors.toUnmodifiableSet());

	private record Intent(Pattern pattern, String pool) {}

	/** A step on the way through the game: suggested once {@code after} is done and {@code unless} isn't. */
	private record ProgressIdea(String after, String unless, List<String> lines) {}

	/** "thanks! how do I…" split into the prefix pool and the question; prefix is null when there is none. */
	public record Split(String prefix, String rest) {}

	/** How someone says they're doing ("feeling_good"), and whether they asked back ("good, you?"). */
	public record Feeling(String pool, boolean askedBack) {}

	private record FeelingPhrase(String phrase, String name) {}

	private static final Map<String, List<String>> POOLS = new HashMap<>();
	private static final List<Intent> INTENTS = new ArrayList<>();
	private static final Map<String, String> SYNONYMS = new HashMap<>();
	private static final List<String> MOODS = new ArrayList<>();
	private static final List<String> PB_MOODS = new ArrayList<>();
	private static final Map<String, List<String>> TOPICS = new LinkedHashMap<>();
	/** How a remembered topic is said in remember_topic: "build" → "building". */
	private static final Map<String, String> TOPIC_PHRASES = new HashMap<>();
	/** Statistic milestones Merl celebrates, by statistic ("mined" → 10,000, 50,000, …). */
	private static final Map<String, List<Long>> STAT_MILESTONES = new LinkedHashMap<>();

	public static List<Long> statMilestones(String stat) {
		return STAT_MILESTONES.getOrDefault(stat, List.of());
	}

	/** How often someone talked to Merl in met_yes: once, a few times, lots of times. */
	private static final List<String> OFTEN_PHRASES = new ArrayList<>();
	private static final List<ProgressIdea> PROGRESS_IDEAS = new ArrayList<>();
	/** Small talk in any wording: a trigger phrase, and every other word allowed next to it. */
	private record KeywordIntent(String pool, List<String> triggers, Set<String> allowed) {}

	private static final List<KeywordIntent> KEYWORD_INTENTS = new ArrayList<>();
	private static final List<String> STRIP_START = new ArrayList<>();
	private static final List<String> STRIP_END = new ArrayList<>();
	/** Words small talk is made of, for fixing typos ("thnaks" → "thanks"). */
	private static final List<String> SMALL_TALK_WORDS = new ArrayList<>();
	private static final Pattern LONG_RUN = Pattern.compile("([a-z])\\1{2,}");
	private static final Pattern PATTERN_WORD = Pattern.compile("[a-z]{4,}");
	/** Longest first so "not bad" wins over "bad"; ties keep the order in lines.json. */
	private static final List<FeelingPhrase> FEELING_PHRASES = new ArrayList<>();
	private static final List<String> FEELING_ASK_BACK = new ArrayList<>();
	private static final Map<String, List<String>> BAGS = new HashMap<>();
	private static final Map<String, String> LAST = new HashMap<>();
	private static String lastStart = "";

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
		lines.getAsJsonObject("topic_phrases").entrySet().forEach(e -> TOPIC_PHRASES.put(e.getKey(), e.getValue().getAsString()));
		OFTEN_PHRASES.addAll(strings(lines.get("often_phrases")));
		lines.getAsJsonObject("stat_milestones").entrySet().forEach(e -> {
			if (e.getKey().startsWith("_")) return;
			List<Long> steps = new ArrayList<>();
			for (JsonElement step : e.getValue().getAsJsonArray()) steps.add(step.getAsLong());
			STAT_MILESTONES.put(e.getKey(), List.copyOf(steps));
		});
		for (JsonElement element : lines.getAsJsonArray("progress_ideas")) {
			JsonObject idea = element.getAsJsonObject();
			PROGRESS_IDEAS.add(new ProgressIdea(
					idea.has("after") ? idea.get("after").getAsString() : null,
					idea.has("unless") ? idea.get("unless").getAsString() : null,
					strings(idea.get("lines"))));
		}
		JsonObject keywords = lines.getAsJsonObject("keyword_intents");
		List<String> filler = strings(keywords.get("filler"));
		java.util.TreeSet<String> vocabulary = new java.util.TreeSet<>();
		for (JsonElement element : keywords.getAsJsonArray("intents")) {
			JsonObject intent = element.getAsJsonObject();
			List<String> triggers = strings(intent.get("triggers"));
			Set<String> allowed = new java.util.HashSet<>(filler);
			allowed.addAll(strings(intent.get("words")));
			for (String trigger : triggers) allowed.addAll(Arrays.asList(trigger.split(" ")));
			KEYWORD_INTENTS.add(new KeywordIntent(intent.get("pool").getAsString(), triggers, Set.copyOf(allowed)));
			vocabulary.addAll(allowed);
		}
		for (Intent intent : INTENTS) {
			Matcher m = PATTERN_WORD.matcher(intent.pattern().pattern());
			while (m.find()) vocabulary.add(m.group());
		}
		SMALL_TALK_WORDS.addAll(vocabulary);
		STRIP_START.addAll(strings(keywords.get("strip_start")));
		STRIP_START.sort(java.util.Comparator.comparingInt(String::length).reversed());
		STRIP_END.addAll(strings(keywords.get("strip_end")));
		STRIP_END.sort(java.util.Comparator.comparingInt(String::length).reversed());
		JsonObject feelings = lines.getAsJsonObject("feelings");
		JsonObject phrases = feelings.getAsJsonObject("phrases");
		for (String name : strings(feelings.get("order"))) {
			for (String phrase : strings(phrases.get(name))) FEELING_PHRASES.add(new FeelingPhrase(phrase, name));
		}
		FEELING_PHRASES.sort(java.util.Comparator.comparingInt((FeelingPhrase p) -> p.phrase().split(" ").length).reversed());
		FEELING_ASK_BACK.addAll(strings(feelings.get("ask_back")));
		FEELING_ASK_BACK.sort(java.util.Comparator.comparingInt(String::length).reversed());
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
	 * from key/value pairs, e.g. {@code pick("thanks", "user", "Steve")}. It also avoids starting the
	 * same way as the line Merl said just before, from any pool.
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
			if (bag.size() > 1 && start(bag.get(bag.size() - 1)).equals(lastStart)) {
				for (int i = bag.size() - 2; i >= Math.max(bag.size() - 1 - START_LOOKAHEAD, 0); i--) {
					if (!start(bag.get(i)).equals(lastStart)) {
						Collections.swap(bag, i, bag.size() - 1);
						break;
					}
				}
			}
			line = bag.remove(bag.size() - 1);
			LAST.put(pool, line);
			lastStart = start(line);
		}
		for (int i = 0; i + 1 < values.length; i += 2) {
			line = line.replace("{" + values[i] + "}", values[i + 1]);
		}
		return forChat(line);
	}

	/** Like pick, but half the time from the pool's twin for today's mood (how_are_you_cozy), if it has one. */
	public static String moody(String pool, LocalDate day, String... values) {
		String twin = pool + "_" + mood(day);
		return pick(POOLS.containsKey(twin) && chance(2) ? twin : pool, values);
	}

	/** The first word, lowercased with doubled letters squashed, so "Ooh" and "Oh" count as the same start. */
	static String start(String line) {
		Matcher m = FIRST_WORD.matcher(line);
		return m.lookingAt() ? REPEATS.matcher(m.group(1).toLowerCase(Locale.ROOT)).replaceAll("$1") : "";
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
		if (normalized.isEmpty()) return null;
		List<String> variants = variants(normalized);
		for (String variant : variants) {
			String pool = intent(variant);
			if (pool != null) return pool;
		}
		for (String variant : variants) {
			String pool = keywordIntent(variant);
			if (pool != null) return pool;
		}
		return null;
	}

	/** "thaaanks merl lol" → also "thanks merl lol", "thanks". */
	private static List<String> variants(String normalized) {
		List<String> out = new ArrayList<>(List.of(normalized));
		for (String collapsed : List.of(LONG_RUN.matcher(normalized).replaceAll("$1"), LONG_RUN.matcher(normalized).replaceAll("$1$1"))) {
			String fixed = String.join(" ", words(collapsed).stream().map(MerlLines::fixTypo).toList());
			for (String v : List.of(collapsed, fixed, strip(fixed))) {
				if (!v.isEmpty() && !out.contains(v)) out.add(v);
			}
		}
		return out;
	}

	/** A word one typo away from a small-talk word becomes that word. */
	private static String fixTypo(String word) {
		if (word.length() < 3 || SMALL_TALK_WORDS.contains(word)) return word;
		for (String known : SMALL_TALK_WORDS) {
			if (known.length() >= 4 && known.charAt(0) == word.charAt(0) && SearchIndex.editDistance(word, known, 1) <= 1) {
				return known;
			}
		}
		return word;
	}

	/** Removes "can you", "please", "merl", "lol"… from the start and end. */
	private static String strip(String normalized) {
		String text = normalized;
		boolean changed = true;
		while (changed && !text.isEmpty()) {
			changed = false;
			for (String phrase : STRIP_START) {
				if (text.startsWith(phrase + " ")) {
					text = text.substring(phrase.length() + 1);
					changed = true;
				}
			}
			for (String phrase : STRIP_END) {
				if (text.endsWith(" " + phrase)) {
					text = text.substring(0, text.length() - phrase.length() - 1);
					changed = true;
				}
			}
		}
		return text;
	}

	private static String keywordIntent(String normalized) {
		String padded = " " + normalized + " ";
		List<String> words = words(normalized);
		for (KeywordIntent intent : KEYWORD_INTENTS) {
			if (intent.triggers().stream().anyMatch(t -> padded.contains(" " + t + " "))
					&& words.stream().allMatch(intent.allowed()::contains)) {
				return intent.pool();
			}
		}
		return null;
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
	private static final Pattern ONE_WORD_BREAK = Pattern.compile("\\s*\\S+\\s*[!,.:;]");

	public static Split splitSmallTalk(String text) {
		List<String> words = words(normalize(text));
		for (int k = words.size() - 1; k > 0; k--) {
			String pool = intent(String.join(" ", words.subList(0, k)));
			if (pool == null) continue;
			String rest = String.join(" ", words.subList(k, words.size()));
			// "nice mob variants" is a pack's name, not a compliment: one word only counts with a break ("nice! how…").
			if (pool.equals("compliment") && k == 1 && !ONE_WORD_BREAK.matcher(text).lookingAt()) break;
			if (PREFIX_POOLS.containsKey(pool) && looksLikeQuestion(rest, text)) {
				return new Split(PREFIX_POOLS.get(pool), rest);
			}
			break;
		}
		return new Split(null, text);
	}

	/** "how do I…", "where are trial chambers?", "show me the boss key page" rather than "NO! Stop!". */
	public static boolean seeksInfo(String text) {
		return text.contains("?") || words(normalize(text)).stream().anyMatch(INFO_WORDS::contains);
	}

	/**
	 * For a message that isn't a question: is the top page clearly what it's about? Yes when Merl is sure
	 * ({@code sure} is "sure", "maybe" or "guess"), or when the page is named after it and the message is
	 * just a topic ("turtles", "boss keys"). "i hate creepers" names the Creeper page too, but it's a comment.
	 */
	public static boolean clearlyAbout(String sure, boolean titleMatch, String text, boolean allMatched) {
		if (sure == null) return false;
		boolean shortMessage = words(normalize(text)).size() <= TOPIC_WORDS;
		// "moobloom", "mannequin": a short message whose every word is on the page is a topic search.
		return sure.equals("sure") || (shortMessage && (titleMatch || allMatched));
	}

	/** "there" only as a place ("how do I get there"), not in "what dungeons are there". */
	private static final Pattern REFERENCE = Pattern.compile(
			"\\b(it|its|him|her|them|they|he|she)\\b|(?<!\\bare )(?<!\\bis )(?<!\\bwas )(?<!\\bwere )\\bthere\\b", Pattern.CASE_INSENSITIVE);

	/** "where do I find him" after the Raj Raksha page → "where do I find Raj Raksha". */
	public static String resolveReference(String search, String previousPage) {
		if (previousPage == null || previousPage.isEmpty()) return search;
		return REFERENCE.matcher(search).replaceFirst(Matcher.quoteReplacement(previousPage));
	}

	/** Several sentences that are all small talk; null when they aren't. */
	public static MultiTalk multiSmallTalk(String text) {
		List<String> clauses = Arrays.stream(CLAUSE.split(text)).filter(c -> !normalize(c).isEmpty()).toList();
		if (clauses.size() < 2) return null;
		List<String> talks = new ArrayList<>();
		for (String clause : clauses) {
			String talk = smallTalk(clause);
			if (talk == null) return null;
			talks.add(talk);
		}
		String first = talks.get(0);
		String main = talks.get(talks.size() - 1);
		return new MultiTalk(first.equals(main) ? null : PREFIX_POOLS.get(first), main);
	}

	/** "so Katter is the bosses?", "you mean the skyrtle?" */
	public static boolean isClarifying(String text) {
		String normalized = normalize(text) + " ";
		return CLARIFY_CUES.stream().anyMatch(normalized::startsWith) && words(normalized.strip()).stream().noneMatch(ASKING_WORDS::contains);
	}

	/** The person a "have you met …?" question is about, or null. */
	public static Met metQuestion(String text) {
		for (Pattern pattern : List.of(MET_STRICT, MET_LOOSE)) {
			Matcher m = pattern.matcher(text.strip());
			if (!m.matches()) continue;
			String name = m.group(1).strip();
			List<String> nameWords = words(normalize(name));
			if (!nameWords.isEmpty() && !NOT_NAMES.contains(normalize(name)) && !NOT_NAME_STARTS.contains(nameWords.get(0))
					&& nameWords.size() <= 3) {
				return new Met(name, pattern == MET_STRICT);
			}
		}
		return null;
	}

	/** How often someone talked to Merl: "once", "a few times", "lots of times". */
	public static String often(int chats) {
		return OFTEN_PHRASES.get(chats <= 1 ? 0 : chats < 10 ? 1 : 2);
	}

	/** The project of the last answer counts this much extra in the next search ("and the loot?"). */
	private static final double RECENT_PROJECT_SHARE = 0.5;

	/** A person's usual projects plus what they're asking about right now, for SearchIndex.find. */
	public static Map<String, Double> interests(Map<String, Double> shares, String recentProject) {
		Map<String, Double> out = new HashMap<>(shares);
		if (recentProject != null) out.put(recentProject, Math.min(1.0, out.getOrDefault(recentProject, 0.0) + RECENT_PROJECT_SHARE));
		return out;
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

	/**
	 * How someone says they're doing, after Merl asked: "pretty good, you?" → ("feeling_good", true).
	 * Null when it doesn't sound like an answer.
	 */
	public static Feeling feeling(String text) {
		String padded = (" " + normalize(text) + " ").replace(" thank you ", " thanks ").replace(" thank u ", " thanks ");
		boolean askedBack = false;
		for (String phrase : FEELING_ASK_BACK) {
			if (padded.contains(" " + phrase + " ")) {
				padded = padded.replace(" " + phrase + " ", " ");
				askedBack = true;
			}
		}
		List<String> words = words(padded.trim());
		if (words.isEmpty() || words.size() > FEELING_MAX_WORDS || words.stream().anyMatch(ASKING_WORDS::contains)) return null;
		for (FeelingPhrase p : FEELING_PHRASES) {
			if (padded.contains(" " + p.phrase() + " ")) return new Feeling("feeling_" + p.name(), askedBack);
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
	 * or the time of day, the line itself, sometimes a closer. No casual opener in front of a line that
	 * already starts with "Oh"/"Hmm", and no closer after one that ends in ":" or "?".
	 */
	public static String headline(String core, String prefix, String energy, int hour, String user, boolean slipOk) {
		List<String> parts = new ArrayList<>();
		if (prefix != null) parts.add(pick(prefix, "user", user));
		if (energy.equals("terse")) {
			parts.add(core);
			return String.join(" ", parts);
		}
		boolean casual = prefix == null && !INTERJECTIONS.contains(start(core));
		String opener = null;
		if (energy.equals("excited")) {
			opener = pick("excited_opener");
		} else if (energy.equals("stressed")) {
			opener = pick("calm_opener");
		} else if (casual && (hour >= 23 || hour < 5) && chance(SLEEPY_CHANCE)) {
			opener = pick("sleepy_opener");
		} else if (casual && chance(OPENER_CHANCE)) {
			opener = pick("opener");
		}
		if (opener != null) parts.add(opener);
		parts.add(core);
		String end = core.strip();
		if (opener == null && !end.endsWith(":") && !end.endsWith("?") && chance(CLOSER_CHANCE)) parts.add(pick("closer"));
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

	/** After "how are you?" or a hello, sometimes "And how are you?", unless Merl's line already asks something. */
	public static String askFeeling(String pool, String line) {
		return ASK_FEELING_POOLS.contains(pool) && !line.strip().endsWith("?") && chance(ASK_FEELING_CHANCE)
				? pick("ask_feeling") : null;
	}

	/** When you come back: a question about what you were up to, or about the page that last helped. */
	public static String followUp(String topic, int topicDay, String page, int pageDay, int today) {
		if (topic != null && today - topicDay <= TOPIC_FOLLOW_UP_DAYS && POOLS.containsKey("followup_" + topic)) {
			return pick("followup_" + topic);
		}
		if (page != null && today - pageDay <= PAGE_FOLLOW_UP_DAYS) return pick("followup_page", "page", page);
		return null;
	}

	/** A line for a round number of chats or a friendship anniversary not mentioned yet. */
	public static Note friendshipNote(int chats, int noted, int days, int lastAnniversary, String user) {
		int milestone = chats / 1000 * 1000;
		for (int m : CHAT_MILESTONES) if (m <= chats) milestone = Math.max(milestone, m);
		if (milestone > noted) {
			return new Note(pick("friend_milestone", "count", Integer.toString(milestone), "user", user), milestone, lastAnniversary);
		}
		int anniversary = days / 365 * 365;
		for (int a : ANNIVERSARIES) if (a <= days) anniversary = Math.max(anniversary, a);
		if (anniversary > lastAnniversary) {
			return new Note(pick("friend_anniversary", "days", Integer.toString(anniversary), "user", user), noted, anniversary);
		}
		return new Note(null, noted, lastAnniversary);
	}

	/** "do you remember me?" */
	public static String rememberMe(int chats, int days, String topic, String user) {
		if (chats <= 1) return pick("remember_me_new", "user", user);
		String line = pick("remember_me", "count", Integer.toString(chats), "days", Integer.toString(days), "user", user);
		String activity = topic != null ? TOPIC_PHRASES.get(topic) : null;
		return activity != null ? line + " " + pick("remember_topic", "activity", activity) : line;
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

	/** "what can I craft", "anything I could make", "what can I make with my stuff". */
	// Nothing but "with …", "now" or similar may follow, so "what do I need to craft a beacon" isn't one.
	private static final Pattern CRAFT_QUESTION = Pattern.compile("\\b(what|which|anything|something)\\b.*\\b(craft|make)\\b"
			+ "\\s*(right now|now|here|today|for me|from (my|what i).*|with .*)?\\s*[?!.]*\\s*$|\\bcraftable\\b");
	private static final Pattern CRAFT_ASKER = Pattern.compile("\\b(i|we)\\b.*|\\bcraftable\\b");
	private static final Pattern CRAFT_MODAL = Pattern.compile("\\b(can|could|should|to)\\b|\\bcraftable\\b");
	/** "…with this?" means the item in hand; "…with my stuff" or nothing means the whole inventory. */
	private static final Pattern CRAFT_WITH_HELD = Pattern.compile("\\bwith (this|that|it|the thing i'?m holding|what i'?m holding)\\b");
	private static final Pattern CRAFT_WITH = Pattern.compile("\\bwith\\b");
	private static final Pattern CRAFT_WITH_INVENTORY = Pattern.compile(
			"\\bwith (my|what i (have|got|carry)|the stuff|these|all|everything|inventory|items)\\b");

	/** True for inventory crafting questions, which Merl answers instead of searching the wiki. */
	public static boolean craftingQuestion(String message) {
		String text = message.toLowerCase(java.util.Locale.ROOT);
		if (!CRAFT_QUESTION.matcher(text).find() || !CRAFT_ASKER.matcher(text).find() || !CRAFT_MODAL.matcher(text).find()) return false;
		// "what can I make with copper" is about copper, so it goes to the wiki.
		return !CRAFT_WITH.matcher(text).find() || CRAFT_WITH_HELD.matcher(text).find() || CRAFT_WITH_INVENTORY.matcher(text).find();
	}

	public static boolean craftingWithHeld(String message) {
		return CRAFT_WITH_HELD.matcher(message.toLowerCase(java.util.Locale.ROOT)).find();
	}


	// Warping Wonders waypoints: "where is the closest waypoint", "nearest waypoint hub", "where's my waypoint".
	private static final Pattern WAYPOINT = Pattern.compile("\\b(waypoints?|waypoint hubs?)\\b");
	private static final Pattern WAYPOINT_CUE = Pattern.compile(
			"\\b(where|wheres|nearest|closest|nearby|near me|how far|coords?|coordinates|which way|direction|find|lead|guide|take|bring|navigate|way to|path to|show me)\\b");
	/** How-to questions about waypoints go to the wiki. */
	private static final Pattern WAYPOINT_HOW = Pattern.compile("\\b(how|craft|crafting|recipe|trust|untrust|lock|rename|place|break|color|dye|get|buy|obtain|trade|trading|cartographer|cost|make|work|works)\\b");
	private static final Pattern WAYPOINT_MINE = Pattern.compile("\\bmy (own )?(waypoints?|waypoint hubs?)\\b");

	/** True for "where is the closest waypoint?" and the like. */
	public static boolean waypointQuestion(String message) {
		String text = message.toLowerCase(java.util.Locale.ROOT).replace("'", "");
		if (!WAYPOINT.matcher(text).find()) return false;
		// "how do I get to mistiik's waypoint" asks for the way; "how do I craft a waypoint hub" goes to the wiki.
		if (WANTS_GUIDE.matcher(text).find()) return true;
		return WAYPOINT_CUE.matcher(text).find() && !WAYPOINT_HOW.matcher(text).find();
	}

	/** "where's my waypoint" means only the player's own. */
	public static boolean waypointOnlyMine(String message) {
		return WAYPOINT_MINE.matcher(message.toLowerCase(java.util.Locale.ROOT)).find();
	}

	// "What's this?": the block or mob in front of the player, or the held item.
	private static final Pattern WHATS_THIS = Pattern.compile(
			"(merl )?(what|whats|wat)( is)? (this|that)( thing| block| mob| item| creature| animal| here)?( merl)?"
			+ "|what am i (looking at|holding)( right now)?( merl)?|(identify|name) (this|that)( thing| mob| block| item)?");
	private static final Pattern HOLDING = Pattern.compile("\\b(holding|in my hand|item)\\b");

	/** "what is this", "what am I looking at"; null if not, else true when it's about the held item. */
	public static Boolean whatsThis(String message) {
		String text = normalize(message).replaceAll("[?!.]+$", "").strip();
		if (!WHATS_THIS.matcher(text).matches()) return null;
		return HOLDING.matcher(text).find();
	}

	// Recipes: "how do I craft a waypoint hub", "recipe for a bed", "bed recipe".
	private static final Pattern RECIPE = Pattern.compile(
			"(how (do|can|would|should) (i|you|we|one) (craft|make)|how to (craft|make)|(whats|what is|show me|give me) the (crafting )?recipe (for|of)"
			+ "|(crafting )?recipe (for|of)|craft(ing)? recipe for) (an? |the |some )?(?<item>.+?)( in minecraft)?");
	private static final Pattern RECIPE_SUFFIX = Pattern.compile("(?<item>.+?) (crafting )?recipe");

	/** The item a recipe question asks about ("waypoint hub"), or null. */
	public static String recipeItem(String message) {
		String text = normalize(message).replaceAll("[?!.]+$", "").strip();
		Matcher m = RECIPE.matcher(text);
		if (!m.matches()) m = RECIPE_SUFFIX.matcher(text);
		if (!m.matches()) return null;
		String item = m.group("item").replaceAll("^(an?|the|some) ", "").strip();
		return item.isEmpty() || item.split(" ").length > 5 ? null : item;
	}

	// Block palettes: "what blocks go with this", "palette for deepslate", "random palette", "another palette".
	/**
	 * @param block the block asked about by name, or null for the one looked at (or held)
	 * @param holding about the block in the player's hand
	 * @param random a palette around a random block
	 * @param again another palette like the last one
	 * @param loose "what goes with X" without saying block or palette: only a palette question when X is a block
	 */
	public record PaletteAsk(String block, boolean holding, boolean random, boolean again, boolean loose) {
		public PaletteAsk(String block, boolean holding, boolean random, boolean again) {
			this(block, holding, random, again, false);
		}
	}

	private static final String PALETTE_WORD = "(block |color |colour |building |build )?palettes?";
	private static final Pattern PALETTE_AGAIN = Pattern.compile(
			"(merl )?(give me |show me |try |can i get |i want )?(an ?other|a different|a new|different|new|more|one more|other) " + PALETTE_WORD + "( please| pls)?");
	private static final Pattern PALETTE_RANDOM = Pattern.compile(
			"(.* )?(random|surprise|any|some) " + PALETTE_WORD + "( please| pls| idea| ideas)?"
			+ "|(.* )?surprise me( with)?( an?| some)? " + PALETTE_WORD + "( please| pls)?"
			+ "|(merl )?(give me |show me |suggest |make me |can you (give|make|suggest|show) me |i want |i need |got )?an? " + PALETTE_WORD + "( please| pls)?"
			+ "|(merl )?" + PALETTE_WORD + "( please| pls| idea| ideas| inspiration)?"
			+ "|(.* )?" + PALETTE_WORD + " (ideas?|inspiration|suggestions?)( please| pls)?"
			+ "|(.* )?(what|which|some|random) blocks (go|fit|match|look good|work)( well| nicely| great)? together");
	private static final String PALETTE_THINGS = "(blocks?|materials?|colou?rs?)";
	private static final String PALETTE_MODAL = "((should|could|can|would|will|do|does|might|shall) (i |we |you |one )?)?";
	/** With "blocks" in the question any of these verbs counts; without, only the ones that can only mean "goes with". */
	private static final String PALETTE_VERB = "(go|goes|fit|fits|match|matches|pair|pairs|work|works|look good|looks good|look nice|looks nice"
			+ "|combine|blend|complement|complements|use|mix|put|build|pick|choose|add)";
	private static final String PALETTE_LOOSE_VERB = "(go|goes|fit|fits|match|matches|pair|pairs|look good|looks good|complement|complements|combine|blend)";
	private static final String PALETTE_HOW = "( it| them)?( well| nicely| best| good| great| together)*";
	private static final Pattern PALETTE_WITH = Pattern.compile(
			"(.* )?(what|which)( other| kind of| kinds of| type of| types of)? " + PALETTE_THINGS + " (to )?" + PALETTE_MODAL
			+ PALETTE_VERB + PALETTE_HOW + " (with|to|together with|alongside|next to|around) (?<block>.+)"
			+ "|(.* )?(what|which) " + PALETTE_MODAL + PALETTE_LOOSE_VERB + PALETTE_HOW + " (with|to|alongside|next to) (?<block2>.+)"
			+ "|(.* )?" + PALETTE_THINGS + " (that|which|to) " + PALETTE_MODAL + PALETTE_VERB + "s?" + PALETTE_HOW
			+ " (with |to |next to |alongside )?(?<block3>.+)"
			+ "|(.* )?(recommend|suggest)( me)?( some| a few)? " + PALETTE_THINGS + " (for|to go with|that go with|with|matching|to match) (?<block6>.+)"
			+ "|(.* )?" + PALETTE_THINGS + " (matching|similar to) (?<block7>.+)"
			+ "|(.* )?" + PALETTE_WORD + " (for|with|around|using|based on|from|of|to go with|that goes with) (?<block4>.+)"
			+ "|(merl )?(?<block5>[a-z ]{3,40}?) " + PALETTE_WORD + "( please| pls)?");
	private static final Pattern THIS_BLOCK = Pattern.compile(
			"(this|that|it|here|this one|that one)( block)?( here| right here| right now)?|(the )?block (im|i am) looking at|what im looking at");
	private static final Pattern HELD_BLOCK = Pattern.compile("\\b(holding|in my hand|my hand|held)\\b");
	private static final Set<String> NOT_PALETTE_BLOCKS = Set.of("a", "the", "my", "random", "any", "some", "another", "new",
			"different", "more", "one more", "other", "good", "nice", "cool", "best", "together", "each other");

	/** A palette question, or null. */
	public static PaletteAsk palette(String message) {
		String text = normalize(message);
		if (!text.contains("palette") && !text.matches(".*\\b(blocks?|materials?|colou?rs?|go|goes|fit|fits|match|matches|pairs?|complements?|looks?|combine|blend)\\b.*")) return null;
		if (PALETTE_AGAIN.matcher(text).matches()) return new PaletteAsk(null, false, false, true);
		if (PALETTE_RANDOM.matcher(text).matches()) return new PaletteAsk(null, false, true, false);
		Matcher m = PALETTE_WITH.matcher(text);
		if (!m.matches()) return null;
		String block = null;
		for (String group : List.of("block", "block2", "block3", "block4", "block5", "block6", "block7")) {
			if (m.group(group) != null) block = m.group(group);
		}
		// "what goes with diamonds" is no palette question unless it says block or palette, or names this block.
		boolean aboutBlocks = text.contains("palette") || text.matches(".*\\b(blocks?|materials?|colou?rs?)\\b.*");
		block = block.replaceAll("\\b(in minecraft|for (my|a|the) (build|house|base|wall|walls|floor|roof|castle|tower)|in (my|a) build|for building|please|pls|merl|well|nicely)\\b", " ")
				.replaceAll("^(the|a|an|some|my) ", "").replaceAll("\\s+", " ").strip();
		if (block.isEmpty() || NOT_PALETTE_BLOCKS.contains(block)) return aboutBlocks ? new PaletteAsk(null, false, true, false) : null;
		if (HELD_BLOCK.matcher(block).find()) return new PaletteAsk(null, true, false, false);
		if (THIS_BLOCK.matcher(block).matches()) return new PaletteAsk(null, false, false, false);
		if (block.split(" ").length > 5) return null;
		// "surprise me with a palette" and the like: no block named after all.
		if (m.group("block5") != null && block.matches(".*\\b(me|you|with|give|show|want|need|make|get|some|any)\\b.*")) {
			return new PaletteAsk(null, false, true, false);
		}
		return new PaletteAsk(block, false, false, false, !aboutBlocks);
	}

	// "What can I enchant this with?"
	private static final Pattern ENCHANT_FOR_THIS = Pattern.compile(
			"\\b(what|which|list|show|all|any)\\b.*\\benchant(ment)?s?\\b.*\\b(this|it|that|my hand|holding)\\b"
			+ "|\\benchant(ment)?s? (for|on) (this|it|that)\\b|\\bwhat can i enchant (this|it)( with)?\\b");

	public static boolean enchantForThis(String message) {
		String text = normalize(message);
		return ENCHANT_FOR_THIS.matcher(text).find() && !text.startsWith("how ");
	}

	// "Where's my bed?" and "where did I die?"
	private static final Pattern BED = Pattern.compile(
			"\\bwhere('?s| is| was)? (my|the) (bed|spawn( ?point)?|respawn( ?point)?|respawn anchor)\\b|\\bwhere (do|will) i (respawn|spawn)\\b"
			+ "|\\b(me|route|path|way|directions?|get|go) (to|back to|towards) (my|the) (bed|spawn( ?point)?|respawn( ?point)?|respawn anchor|home)\\b");
	private static final Pattern DEATH = Pattern.compile(
			"\\bwhere (did|have) i (die|died|just die)\\b|\\bwhere('?s| is| are) my (death( ?point)?|grave|stuff|items|loot|body)\\b"
			+ "|\\b(me|route|path|way|directions?|get|go) (to|back to|towards) (my|the) (death( ?point)?|grave|stuff|items|loot|body)\\b|\\b(me|back|get|go) (to )?where i died\\b"
			+ "|\\b(last )?death (point|location|spot|coords|coordinates)\\b|\\bwhere i died\\b");

	/** "bed", "death" or null. */
	public static String homeQuestion(String message) {
		String text = normalize(message).replace("'", "");
		// "teleport to my death location" or "how do I find where I died" are about pack features, for the wiki.
		if (!text.matches("(merl )?(hey )?(where|wheres)\\b.*") && !WANTS_GUIDE.matcher(text).find()) return null;
		if (DEATH.matcher(text).find()) return "death";
		if (BED.matcher(text).find()) return "bed";
		return null;
	}

	// "What can I smelt / brew with this?"
	private static final Pattern SMELT = Pattern.compile("\\b(smelt|smelting|cook|cooking|furnace|smoker|blast furnace|campfire|bake)\\b");
	private static final Pattern BREW = Pattern.compile("\\b(brew|brewing|brewing stand|potions?)\\b");
	private static final Pattern WITH_THIS = Pattern.compile("\\b(this|it|that|my hand|holding|with what i have)\\b");

	/** "smelt", "brew" or null for "what can I smelt with this", "can I brew this". */
	public static String smeltOrBrew(String message) {
		String text = normalize(message);
		if (!WITH_THIS.matcher(text).find() || text.startsWith("how do") && !text.contains("this")) return null;
		if (!text.matches(".*\\b(what|which|can|could|is|does)\\b.*")) return null;
		if (BREW.matcher(text).find()) return "brew";
		if (SMELT.matcher(text).find()) return "smelt";
		return null;
	}

	// Reminders: "remind me in 10 minutes to check the furnace", "remind me to eat in 1h".
	private static final String DURATION = "(?<amount>\\d+(?:[.,]\\d+)?|an?|one|half an?|a couple of|a few) ?(?<unit>seconds?|secs?|s|minutes?|mins?|m|hours?|hrs?|h)\\b";
	private static final Pattern REMIND_IN = Pattern.compile("(merl )?(please )?remind me (in|after) " + DURATION + "(?: (to|about|that|of))? ?(?<text>.*)");
	private static final Pattern REMIND_TO = Pattern.compile("(merl )?(please )?remind me (to|about|that|of) (?<text>.+?) (in|after) " + DURATION);
	private static final Pattern REMINDERS_LIST = Pattern.compile("\\b(my|list( my)?|show( my)?|what are my) reminders\\b");
	private static final Pattern REMINDERS_CANCEL = Pattern.compile("\\b(cancel|clear|delete|remove|stop|forget)( all)?( my)? reminders?\\b");

	/** A reminder request: in how many seconds, and what about ("" if nothing was said). */
	public record Reminder(long seconds, String text) {}

	/** The reminder in the message, or null. Longer than a day or shorter than 5 seconds doesn't count. */
	public static Reminder reminder(String message) {
		String text = message.toLowerCase(Locale.ROOT).strip().replaceAll("[!.]+$", "");
		Matcher m = REMIND_IN.matcher(text);
		if (!m.matches()) m = REMIND_TO.matcher(text);
		if (!m.matches()) return null;
		String amount = m.group("amount");
		double count = switch (amount) {
			case "a", "an", "one" -> 1;
			case "half a", "half an" -> 0.5;
			case "a couple of" -> 2;
			case "a few" -> 3;
			default -> Double.parseDouble(amount.replace(',', '.'));
		};
		String unit = m.group("unit");
		long seconds = Math.round(count * (unit.startsWith("h") ? 3600 : unit.startsWith("m") ? 60 : 1));
		if (seconds < 5 || seconds > 86_400) return null;
		String what = m.group("text") == null ? "" : m.group("text").strip();
		return new Reminder(seconds, what.replaceAll("^(to|about|that|of) ", ""));
	}

	/** "list", "cancel" or null for "my reminders", "cancel my reminders". */
	public static String remindersCommand(String message) {
		String text = normalize(message);
		if (REMINDERS_CANCEL.matcher(text).find()) return "cancel";
		if (REMINDERS_LIST.matcher(text).find()) return "list";
		return null;
	}

	// Get Off My Lawn claims: "where is my claim", "nearest claim I'm trusted on", "where is steve's claim".
	private static final Pattern CLAIM = Pattern.compile("\\bclaims?\\b");
	private static final Pattern CLAIM_CUE = Pattern.compile("\\b(where|wheres|nearest|closest|nearby|how far|which way|direction|find|coords?|coordinates|lead|guide|take|bring|navigate)\\b");
	/** How-to questions about claims go to the wiki. */
	private static final Pattern CLAIM_HOW = Pattern.compile("\\bhow\\b(?! far)|\\b(make|create|craft|crafting|recipe|expand|upgrade|resize|remove|delete|abandon|protect|cost|add|untrust|get|buy|anchors?)\\b");
	private static final Pattern CLAIM_TRUSTED = Pattern.compile("\\b(trusted|trust|access|allowed|friends?|others?|someone elses|other peoples?)\\b|\\bs claim");
	private static final Pattern CLAIM_MINE = Pattern.compile("\\bmy (own )?(\\w+ )?claims?\\b");

	/** "mine", "trusted", "any" or null. */
	public static String claimQuestion(String message) {
		String text = normalize(message);
		if (!CLAIM.matcher(text).find()) return null;
		if (!WANTS_GUIDE.matcher(text).find() && (!CLAIM_CUE.matcher(text).find() || CLAIM_HOW.matcher(text).find())) return null;
		if (CLAIM_TRUSTED.matcher(text).find()) return "trusted";
		if (CLAIM_MINE.matcher(text).find()) return "mine";
		return "any";
	}

	// Server info: TPS and MSPT, mob counts and caps, view and simulation distance, general server info.
	private static final Pattern SERVER_HOW = Pattern.compile("\\bhow (do|can|to|should|would)\\b|\\b(fix|reduce|lower|improve|increase|change|set)\\b");
	private static final Pattern SERVER_TPS = Pattern.compile(
			"\\b(tps|mspt|ticks? per second|tick ?rate|tick time|lag|laggy|lagging|lags|server (speed|performance|health|load))\\b|\\bis the server (slow|ok|okay|fine)\\b");
	private static final Pattern SERVER_MOBS = Pattern.compile(
			"\\b(mob ?caps?|mob ?counts?|mob limits?|spawn caps?|entity counts?|how many (mobs|monsters|animals|entities))\\b");
	/** "what's my ping", "mistiik's ping", "everyone's ping" (not "ping", which asks whether Merl is there). */
	private static final Pattern SERVER_PING = Pattern.compile("\\b\\w+ (ping|latency)\\b|\\b(ping|latency) (of|for)\\b|\\blatency\\b");
	private static final Pattern SERVER_DISTANCE = Pattern.compile("\\b(view|render|simulation|sim) ?distances?\\b");
	private static final Pattern SERVER_INFO = Pattern.compile(
			"\\b(server (properties|info|information|version|details|stats)|difficulty|game ?mode|max(imum)? players|player (limit|cap)"
			+ "|how many players|players online|who is online|whos online|whitelist(ed)?|hardcore|motd|what version|which version)\\b");

	/** "tps", "mobs", "distance", "info" or null. How-to questions ("how do I reduce lag") go to the wiki. */
	public static String serverInfo(String message) {
		String text = normalize(message);
		if (SERVER_HOW.matcher(text).find()) return null;
		if (SERVER_PING.matcher(text).find()) return "ping";
		if (SERVER_TPS.matcher(text).find()) return "tps";
		if (SERVER_MOBS.matcher(text).find()) return "mobs";
		if (SERVER_DISTANCE.matcher(text).find()) return "distance";
		if (SERVER_INFO.matcher(text).find()) return "info";
		return null;
	}

	/** Ways of asking to be led somewhere ("lead me to", "give me a route to", "how do I get to"); they start the trail. */
	public static final String GUIDE_PHRASES = "\\b(lead|guide|take|bring|navigate|direct|walk|escort|point) me\\b"
			+ "|\\bshow me (the way|how to get|where)\\b|\\bgive me (a |the )?(route|path|way|directions?)\\b"
			+ "|\\b(route|path|directions?|way|road) (to|back to|towards)\\b|\\bhow (do|can|would) i (get|go|walk|travel) (back )?to\\b"
			+ "|\\b(get|help) me (back )?(to|get to|find my way)\\b|\\b(navigate|guide) (to|towards)\\b";
	private static final Pattern WANTS_GUIDE = Pattern.compile(GUIDE_PHRASES);

	public static boolean wantsGuide(String message) {
		return WANTS_GUIDE.matcher(normalize(message)).find();
	}

	// "Say that again" and "what was I asking?" (same as personality.recall in the bot).
	private static final Pattern RECALL_REPEAT = Pattern.compile(
			"(merl )?(can you |could you |please |pls )?(repeat( that| it| yourself| the last (answer|message|one)| your (last )?answer| please)?"
			+ "|say (that|it) again|what did you (just )?say|(tell|show) me (that|it) again|one more time please|i missed (that|it)"
			+ "|what was (that|your answer|the answer)( again)?)( please| pls| merl)?");
	private static final Pattern RECALL_QUESTION = Pattern.compile(
			".*\\b(what (was|did|were) (i|we) (just )?(ask|asking|asked|say|saying|said|talking about)|what was my (last |previous )?question"
			+ "|what did i (just )?ask( you)?|remind me what i (asked|said)|what were we talking about|what was the question)\\b.*");

	/** "repeat", "question" or null. */
	public static String recall(String message) {
		String text = normalize(message);
		if (RECALL_QUESTION.matcher(text).matches()) return "question";
		if (RECALL_REPEAT.matcher(text).matches()) return "repeat";
		return null;
	}

	// "stop the route", "turn off gps", "don't guide me": the sparkle trail goes away.
	private static final String GUIDE_WORDS = "(route|routes|routing|guide|guiding|guidance|gps|navigation|navigating|navi|nav|trail|trails|path|sparkles?|particles?|directions?)";
	private static final Pattern STOP_GUIDE = Pattern.compile(
			"\\b(stop|end|cancel|quit|turn off|switch off|shut off|disable|deactivate|kill|clear|remove|hide|get rid of|no more|enough)\\b(?:\\s+\\w+){0,3}\\s+" + GUIDE_WORDS + "\\b"
			+ "|\\b" + GUIDE_WORDS + " (off|stop|away|be gone)\\b"
			+ "|\\b(dont|do not|stop|quit|no need to|you can stop) (guide|guiding|lead|leading|navigate|navigating|show|showing|route|routing) me\\b"
			+ "|\\bi (dont|do not) (need|want) (a |the |your |any )?" + GUIDE_WORDS + "\\b");

	/** True for "stop the route", "turn off gps", "don't guide me", "no more sparkles". */
	public static boolean stopGuide(String message) {
		String text = normalize(message);
		return !text.startsWith("how ") && STOP_GUIDE.matcher(text).find();
	}

	// Fixed coordinates: "take me to 100 64 -200", "guide me to x 100 z -200", "route to 300, -150 in the nether".
	private static final Pattern LABELLED = Pattern.compile("\\b([xyz])\\s*[=:]?\\s*(-?\\d{1,8})\\b");
	private static final Pattern NUMBERS = Pattern.compile("(?<![\\w.])(-?\\d{1,8})(?:\\s*,\\s*|\\s+)(-?\\d{1,8})(?:(?:\\s*,\\s*|\\s+)(-?\\d{1,8}))?(?![\\w.])");

	/** Coordinates in the message as {x, y, z} (y is null when only x and z were given), or null. */
	public static Integer[] coordinates(String message) {
		String text = message.toLowerCase(Locale.ROOT);
		Matcher labelled = LABELLED.matcher(text);
		Integer x = null, y = null, z = null;
		while (labelled.find()) {
			int value = Integer.parseInt(labelled.group(2));
			switch (labelled.group(1)) {
				case "x" -> x = value;
				case "y" -> y = value;
				default -> z = value;
			}
		}
		if (x != null && z != null) return new Integer[] {x, y, z};
		Matcher numbers = NUMBERS.matcher(text);
		if (!numbers.find()) return null;
		int a = Integer.parseInt(numbers.group(1)), b = Integer.parseInt(numbers.group(2));
		if (numbers.group(3) == null) return new Integer[] {a, null, b};
		return new Integer[] {a, b, Integer.parseInt(numbers.group(3))};
	}

	/** "take me to 100 64 -200" and the like: a request to be guided to fixed coordinates. */
	public static boolean coordinatesGuide(String message) {
		return coordinates(message) != null && (WANTS_GUIDE.matcher(normalize(message)).find()
				|| normalize(message).matches(".*\\b(go|walk|travel|navigate|head|get) to\\b.*"));
	}
}

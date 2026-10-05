package eu.explorerseden.nicemerl;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
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
 * BM25 search over wiki sections, with typo tolerance, synonyms and phrase boosts. Mirrors
 * search.py in the NiceMerl Discord bot, so changes to tokenizing or scoring should be made in both places.
 */
public class SearchIndex {
	private static final Set<String> STOPWORDS = new HashSet<>(Arrays.asList("""
			a about above after again all am an and any are as at be because been before being below
			between both but by can could did do does doing done down during each else ever few for from
			further get gets getting got had has have having he her here hers him his how i if in into is
			it its itself just know let like make me more most much my need no nor not now of off on once
			only or other our out over own please same she should so some such tell than thank thanks that
			the their them then there these they this those through to too under until up us use using very
			want was way we were what when where which while who whom why will with would you your
			anyone anybody someone somebody something anything thing things find wiki explain explorer
			explorers eden hi hello hey hallo heya hiya yo sup moin servus merl nicemerl
			whats hows wheres whos whys im ive id dont cant isnt doesnt wont u ur pls plz
			""".trim().split("\\s+")));

	/** Plurals the suffix rules in {@link #stem} would get wrong. */
	private static final Map<String, String> IRREGULAR = Map.ofEntries(
			Map.entry("mice", "mouse"), Map.entry("geese", "goose"), Map.entry("children", "child"),
			Map.entry("feet", "foot"), Map.entry("teeth", "tooth"), Map.entry("wolves", "wolf"),
			Map.entry("leaves", "leaf"), Map.entry("shelves", "shelf"), Map.entry("hooves", "hoof"),
			Map.entry("knives", "knife"), Map.entry("halves", "half"), Map.entry("loaves", "loaf"),
			Map.entry("thieves", "thief"), Map.entry("endermen", "enderman"),
			Map.entry("potatoes", "potato"), Map.entry("tomatoes", "tomato"));

	private static final int TITLE_WEIGHT = 3;
	private static final int PATH_WEIGHT = 2;
	private static final double K1 = 1.5;
	private static final double B = 0.75;
	public static final double MIN_SCORE = 2.0;
	/**
	 * How sure Merl sounds about the top result: a score this high, or this high with the page title
	 * asked about, is "sure"; below GUESS_SCORE she's guessing.
	 */
	public static final double SURE_SCORE = 12.0;
	public static final double SURE_TITLE_SCORE = 7.0;
	public static final double GUESS_SCORE = 4.0;
	// A query word the index doesn't know can still match through these, at reduced weight.
	private static final double SYNONYM_WEIGHT = 0.6;
	private static final double SYNONYM_ONLY_WEIGHT = 0.9;
	private static final double TYPO_WEIGHT = 0.7;
	private static final double PREFIX_WEIGHT = 0.5;
	/** Adjacent query words found next to each other ("nether portal"). */
	private static final double BIGRAM_WEIGHT = 1.5;
	/** The whole page title / heading appears in the question. */
	private static final double TITLE_MATCH_BONUS = 2.0;
	private static final double HEADING_MATCH_BONUS = 1.0;
	/** Sections whose heading is one of the hint words, e.g. "Crafting" for "how do I make…". */
	private static final double HEADING_HINT_BONUS = 2.5;
	/** The page's description and tags from the wiki count like a little extra text. */
	private static final int META_WEIGHT = 1;
	/** A project's home page when the question only names the project ("who is katter"). */
	private static final double PROJECT_BONUS = 3.0;
	/** A person's usual projects (from past questions) tip close calls their way, never more than this. */
	private static final double INTEREST_BONUS = 2.5;
	/** Page titles that say nothing; such pages are titled after their project instead. */
	private static final Set<String> GENERIC_TITLES = Set.of("main", "home");
	/** Changelogs mention everything, so they only win when the question is about changes. */
	private static final double CHANGELOG_FACTOR = 0.5;
	private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+(?=[A-Z0-9\"'(\\[])");
	// The answer line: one sentence (plus the next) from the top pages, covering at least this share of the question.
	private static final int ANSWER_CHARS = 300;
	private static final int ANSWER_MIN_CHARS = 20;
	private static final int ANSWER_PAGES = 2;
	private static final double ANSWER_COVERAGE = 0.5;
	private static final double ANSWER_HINT_WEIGHT = 2.5;
	private static final double ANSWER_HIT_WEIGHT = 1.5;
	private static final double ANSWER_RANK_PENALTY = 2.0;
	/** With the model, a sentence that means the same as the question in other words can answer it too. */
	private static final double ANSWER_SEMANTIC_MIN = 0.7;
	// Hybrid search (keywords + meaning, with the model): rankings merge by reciprocal rank fusion, each page
	// scoring 1 / (RRF_K + its rank) per ranking it's in; the meaning ranking counts SEMANTIC_WEIGHT as much.
	private static final int RRF_K = 10;
	private static final double SEMANTIC_WEIGHT = 0.6;
	private static final int SEMANTIC_CANDIDATES = 10;
	/** Pages less similar than this aren't found by meaning. */
	private static final double SEMANTIC_MIN = 0.40;
	/** Score of a page found only by meaning (times its similarity): never enough to sound sure. */
	private static final double SEMANTIC_SCORE = 9.0;
	/** How much of a section is embedded (title, heading, description and the start of its text). */
	private static final int SEMANTIC_TEXT_CHARS = 400;
	private static final Set<String> CHANGELOG_WORDS = Set.of("changelog", "change", "update", "patch", "new", "added", "release");
	/** Results scoring below this share of the best result are dropped. */
	private static final double RELATIVE_CUTOFF = 0.35;
	private static final Pattern WORD = Pattern.compile("[a-z0-9]+");
	private static final Pattern COMBINING = Pattern.compile("\\p{M}");
	// Which section headings answer which kind of question.
	private static final Set<String> WHERE_WORDS = Set.of("where", "find", "location", "locate", "located", "spawn", "spawns", "found", "generate");
	private static final Set<String> WHERE_HINTS = hintTerms("where find location locations spawning spawn generation biomes found");
	private static final Set<String> OBTAIN_WORDS = Set.of("get", "obtain", "craft", "make", "recipe", "drop", "drops", "loot", "build", "create");
	private static final Set<String> OBTAIN_HINTS = hintTerms("obtaining crafting recipe loot drops obtain craft sources trading");
	private static final Set<String> LIST_HINTS = hintTerms("overview list all");
	private static final Pattern LIST_QUESTION = Pattern.compile("\\b(what|which)\\b.*\\bare there\\b|\\blist of\\b|\\ball (the )?[a-z]+s\\b"
			+ "|\\bevery\\b|\\b(types|kinds|sorts) of\\b|\\boverview\\b|\\b(variants|types|kinds)\\b");

	/**
	 * @param titleMatch the page title or heading is exactly what was asked about
	 * @param matched how many of the question's words the section contains
	 */
	public record Result(Section section, double score, Excerpt excerpt, boolean titleMatch, int matched) {}

	/**
	 * @param corrections words that were read as something else, e.g. "enchantmnt" → "enchantment"
	 * @param uncertain some words only matched through typo/prefix guesses, or not at all
	 */
	public record Outcome(List<Result> results, Map<String, String> corrections, boolean uncertain) {
		public double confidence() {
			return results.isEmpty() ? 0 : results.get(0).score();
		}
	}

	/** A word as written and its search term. */
	public record Word(String word, String term) {}

	private record Alternative(List<String> terms, double weight) {}

	/** One query word and the ways it can match, the exact term first. */
	private record Group(String word, List<Alternative> alternatives, boolean exact) {}

	private final List<Section> sections;
	private final Map<String, Double> priors;
	private final Set<String> headingHints;
	private final List<Map<String, Integer>> docs = new ArrayList<>();
	private final List<Map<String, Integer>> bigrams = new ArrayList<>();
	private final int[] lengths;
	private final double avgLength;
	private final List<Set<String>> titleTerms = new ArrayList<>();
	private final List<Set<String>> headingTerms = new ArrayList<>();
	/** Heading words for question-type hints ("Where to Find"), stopwords included. */
	private final List<Set<String>> hintTargets = new ArrayList<>();
	/** The project words of a project's home page, else empty. */
	private final List<Set<String>> projectTerms = new ArrayList<>();
	private final Map<String, Double> idf = new HashMap<>();
	/** The meaning-based search model and each section's vector, or null for keyword search only. */
	private final SemanticModel model;
	private final float[][] vectors;
	private final Map<String, Double> bigramIdf = new HashMap<>();
	/** Most common written form of each term, to show corrections readably. */
	private final Map<String, String> display = new HashMap<>();
	/** Candidates for typo and prefix matching, most common first. */
	private final List<String> vocab;

	public SearchIndex(List<Section> sections) {
		this(sections, Map.of(), Set.of());
	}

	/**
	 * @param priors optional per-page bonus (by path), e.g. the source's own search rank
	 * @param headingHints terms that make a section heading a likely answer, e.g. "craft", "obtain"
	 */
	public SearchIndex(List<Section> sections, Map<String, Double> priors, Set<String> headingHints) {
		this(sections, priors, headingHints, null);
	}

	/** @param model meaning-based search next to the keywords, or null */
	public SearchIndex(List<Section> sections, SemanticModel model) {
		this(sections, Map.of(), Set.of(), model);
	}

	public SearchIndex(List<Section> sections, Map<String, Double> priors, Set<String> headingHints, SemanticModel model) {
		this.sections = List.copyOf(sections);
		this.priors = priors;
		this.headingHints = headingHints;
		this.lengths = new int[sections.size()];
		Map<String, Integer> df = new HashMap<>();
		Map<String, Integer> bdf = new HashMap<>();
		Map<String, Map<String, Integer>> surface = new HashMap<>();
		long total = 0;
		for (int i = 0; i < sections.size(); i++) {
			Section s = sections.get(i);
			List<String> textTokens = new ArrayList<>();
			for (Word w : words(s.text())) {
				surface.computeIfAbsent(w.term(), k -> new LinkedHashMap<>()).merge(w.word(), 1, Integer::sum);
				textTokens.add(w.term());
			}
			List<String> titleTokens = tokenize(s.pageTitle() + " " + s.heading());
			List<String> pathTokens = tokenize(s.path().replace('/', ' ').replace('_', ' '));
			List<String> tokens = new ArrayList<>(textTokens);
			for (int w = 0; w < TITLE_WEIGHT; w++) tokens.addAll(titleTokens);
			for (int w = 0; w < PATH_WEIGHT; w++) tokens.addAll(pathTokens);
			for (int w = 0; w < META_WEIGHT; w++) tokens.addAll(tokenize(s.meta()));

			Map<String, Integer> counts = new HashMap<>();
			for (String t : tokens) counts.merge(t, 1, Integer::sum);
			Map<String, Integer> pairs = new HashMap<>();
			for (String p : bigramsOf(textTokens)) pairs.merge(p, 1, Integer::sum);
			for (String p : bigramsOf(titleTokens)) pairs.merge(p, TITLE_WEIGHT, Integer::sum);
			docs.add(counts);
			bigrams.add(pairs);
			lengths[i] = tokens.size();
			total += tokens.size();
			// A project's home page ("Main") is about the project ("Katters Structures").
			Set<String> project = new HashSet<>(tokenize(s.path().split("/")[0].replace('_', ' ')));
			boolean home = s.path().endsWith("/home") || GENERIC_TITLES.contains(s.pageTitle().toLowerCase(Locale.ROOT));
			Set<String> title = new HashSet<>(tokenize(s.pageTitle()));
			title.removeAll(GENERIC_TITLES);
			if (home) title.addAll(project);
			titleTerms.add(title);
			projectTerms.add(home && !s.vanilla() ? project : Set.of());
			// Headings answer kinds of questions ("Where to Find"); overview pages answer "what … are there".
			Set<String> targets = hintTerms(s.heading());
			for (String t : hintTerms(s.pageTitle())) if (LIST_HINTS.contains(t)) targets.add(t);
			// A project's home page lists what the project has ("cat variants" → the Cat list on Nice Mob Variants).
			if (home && !s.vanilla()) targets.addAll(LIST_HINTS);
			hintTargets.add(targets);
			headingTerms.add(s.heading().equals(s.pageTitle()) ? Set.of() : new HashSet<>(tokenize(s.heading())));
			for (String t : counts.keySet()) df.merge(t, 1, Integer::sum);
			for (String p : pairs.keySet()) bdf.merge(p, 1, Integer::sum);
		}
		int n = sections.size();
		this.avgLength = n == 0 ? 0 : (double) total / n;
		df.forEach((t, f) -> idf.put(t, Math.log(1 + (n - f + 0.5) / (f + 0.5))));
		bdf.forEach((t, f) -> bigramIdf.put(t, Math.log(1 + (n - f + 0.5) / (f + 0.5))));
		surface.forEach((term, forms) -> {
			String best = null;
			int bestCount = 0;
			for (Map.Entry<String, Integer> e : forms.entrySet()) {
				if (e.getValue() > bestCount) {
					best = e.getKey();
					bestCount = e.getValue();
				}
			}
			display.put(term, best);
		});
		this.vocab = df.keySet().stream()
				.filter(t -> t.length() >= 4 && !t.chars().allMatch(Character::isDigit))
				.sorted(Comparator.<String>comparingInt(df::get).reversed().thenComparing(Comparator.naturalOrder()))
				.toList();
		this.model = sections.isEmpty() ? null : model;
		this.vectors = this.model == null ? null : sections.stream()
				.map(s -> this.model.embed(s.pageTitle() + ". " + s.heading() + ". " + s.meta() + ". " + start(s.text(), SEMANTIC_TEXT_CHARS)))
				.toArray(float[][]::new);
	}

	/** The first characters of the text (counting like Python, so emoji aren't cut in half). */
	private static String start(String text, int chars) {
		return text.codePointCount(0, text.length()) <= chars ? text : text.substring(0, text.offsetByCodePoints(0, chars));
	}

	public List<Section> sections() {
		return sections;
	}

	public int pageCount() {
		Set<String> paths = new HashSet<>();
		for (Section s : sections) paths.add(s.path());
		return paths.size();
	}

	/**
	 * Plain BM25 over exact terms, for small collections that do their own ranking (data pack settings).
	 *
	 * @param onePerPage keep only the best section of each page (wiki search), or list every match
	 */
	public List<Result> search(String query, int limit, int excerptLength, double minScore, boolean onePerPage) {
		List<String> terms = new ArrayList<>(new LinkedHashSet<>(tokenize(query)));
		if (terms.isEmpty() || sections.isEmpty()) {
			return List.of();
		}
		Map<String, double[]> best = new HashMap<>(); // path -> {score, section index}
		for (int i = 0; i < sections.size(); i++) {
			double score = 0;
			for (String t : terms) score += bm25(i, t);
			if (score <= 0 || score < minScore) continue;
			String key = onePerPage ? sections.get(i).path() : Integer.toString(i);
			double[] current = best.get(key);
			if (current == null || score > current[0]) {
				best.put(key, new double[] {score, i});
			}
		}
		Set<String> termSet = new HashSet<>(terms);
		return best.values().stream()
				.sorted((a, b) -> Double.compare(b[0], a[0]))
				.limit(limit)
				.map(hit -> {
					Section s = sections.get((int) hit[1]);
					return new Result(s, hit[0], Excerpt.of(s.text(), termSet, excerptLength), false, 0);
				})
				.toList();
	}

	/** The full wiki search: best section per page, with typo tolerance, synonyms and phrase boosts. */
	public Outcome find(String query, int limit, int excerptLength) {
		return find(query, limit, excerptLength, MIN_SCORE);
	}

	public Outcome find(String query, int limit, int excerptLength, double minScore) {
		return find(query, limit, excerptLength, minScore, Map.of());
	}

	/** @param interests project (first path part) → share of that person's past questions, 0..1 */
	public Outcome find(String query, int limit, int excerptLength, Map<String, Double> interests) {
		return find(query, limit, excerptLength, MIN_SCORE, interests);
	}

	public Outcome find(String query, int limit, int excerptLength, double minScore, Map<String, Double> interests) {
		Map<String, String> corrections = new LinkedHashMap<>();
		List<Group> groups = analyze(query, corrections);
		boolean uncertain = groups.stream().anyMatch(g -> !g.exact());
		if (sections.isEmpty() || groups.stream().allMatch(g -> g.alternatives().isEmpty()) && vectors == null) {
			return new Outcome(List.of(), corrections, !groups.isEmpty());
		}
		Set<String> queryTerms = new HashSet<>();
		for (Group g : groups) {
			for (Alternative a : g.alternatives()) queryTerms.addAll(a.terms());
		}
		boolean changelogOk = queryTerms.stream().anyMatch(CHANGELOG_WORDS::contains);
		Set<String> hints = new HashSet<>(headingHints);
		hints.addAll(questionHints(query));

		Map<String, double[]> best = new HashMap<>(); // page -> {score, section index}
		for (int i = 0; i < sections.size(); i++) {
			double score = score(i, groups, queryTerms, changelogOk, hints);
			if (score > 0 && !interests.isEmpty()) {
				score += INTEREST_BONUS * interests.getOrDefault(sections.get(i).path().split("/")[0], 0.0);
			}
			if (score < minScore || score <= 0) continue;
			Section s = sections.get(i);
			String page = s.wiki() + "/" + s.path(); // several wikis can have the same path
			double[] current = best.get(page);
			if (current == null || score > current[0]) {
				best.put(page, new double[] {score, i});
			}
		}
		// Highest score first; on a tie the later section wins, like Python's sort of (score, index).
		List<double[]> byScore = best.values().stream()
				.sorted((a, b) -> a[0] != b[0] ? Double.compare(b[0], a[0]) : Double.compare(b[1], a[1]))
				.toList();
		List<double[]> ranked = byScore.stream().limit(limit)
				.filter(hit -> hit[0] >= byScore.get(0)[0] * RELATIVE_CUTOFF)
				.toList();
		// Meaning only helps when the keywords aren't sure: a strong match, or a page named in the
		// question, already is the answer ("curse of blindness" shouldn't drift to "color blindness").
		// So is a project's home page when the question names the project ("who is katter"), and a
		// section made for the kind of question ("what dungeons are there" → the overview).
		boolean keywordSure = false;
		if (!ranked.isEmpty()) {
			int top = (int) ranked.get(0)[1];
			double topScore = ranked.get(0)[0];
			keywordSure = topScore >= SURE_SCORE
					|| titleMatch(top, queryTerms) && topScore >= SURE_TITLE_SCORE
					|| !projectTerms.get(top).isEmpty() && projectTerms.get(top).containsAll(queryTerms)
					|| hintTargets.get(top).stream().anyMatch(hints::contains) && topScore >= GUESS_SCORE;
		}
		if (vectors != null && !keywordSure) ranked = hybrid(query, best, byScore, ranked, limit);
		List<Result> results = new ArrayList<>();
		for (double[] hit : ranked) {
			int i = (int) hit[1];
			Section s = sections.get(i);
			boolean titled = titleMatch(i, queryTerms)
					|| (!headingTerms.get(i).isEmpty() && queryTerms.containsAll(headingTerms.get(i)));
			int matched = 0;
			for (Group g : groups) {
				if (g.alternatives().stream().anyMatch(a -> a.terms().stream().anyMatch(docs.get(i)::containsKey))) matched++;
			}
			results.add(new Result(s, hit[0], Excerpt.of(s.text(), queryTerms, excerptLength), titled, matched));
		}
		return new Outcome(results, results.isEmpty() ? Map.of() : corrections, uncertain);
	}

	/**
	 * Merges the keyword ranking with the meaning ranking. Pages keep their keyword score and section;
	 * a page found only by meaning gets a capped score, so Merl never sounds sure about it.
	 */
	private List<double[]> hybrid(String query, Map<String, double[]> best, List<double[]> byScore,
			List<double[]> keywordRanked, int limit) {
		float[] q = model.embed(query);
		Integer[] order = new Integer[sections.size()];
		float[] similarity = new float[sections.size()];
		for (int i = 0; i < order.length; i++) {
			order[i] = i;
			similarity[i] = SemanticModel.dot(vectors[i], q);
		}
		Arrays.sort(order, (a, b) -> Float.compare(similarity[b], similarity[a]));
		Map<String, double[]> byMeaning = new LinkedHashMap<>(); // page -> {similarity, section index}
		for (int r = 0; r < Math.min(order.length, SEMANTIC_CANDIDATES * 4); r++) {
			int i = order[r];
			if (similarity[i] >= SEMANTIC_MIN) byMeaning.putIfAbsent(page(i), new double[] {similarity[i], i});
		}
		Map<String, Integer> meaningRank = new HashMap<>();
		for (String page : byMeaning.keySet()) {
			if (meaningRank.size() == SEMANTIC_CANDIDATES) break;
			meaningRank.put(page, meaningRank.size());
		}
		Set<String> kept = new HashSet<>();
		for (double[] hit : keywordRanked) kept.add(page((int) hit[1]));
		Map<String, Integer> keywordRank = new HashMap<>();
		for (double[] hit : byScore) keywordRank.put(page((int) hit[1]), keywordRank.size());
		Map<String, Double> fused = new HashMap<>();
		Set<String> all = new HashSet<>(keywordRank.keySet());
		all.addAll(meaningRank.keySet());
		for (String page : all) {
			fused.put(page, (keywordRank.containsKey(page) ? 1.0 / (RRF_K + keywordRank.get(page)) : 0)
					+ (meaningRank.containsKey(page) ? SEMANTIC_WEIGHT / (RRF_K + meaningRank.get(page)) : 0));
		}
		List<double[]> out = new ArrayList<>();
		for (String page : all.stream().sorted(Comparator.<String>comparingDouble(fused::get).reversed()
				.thenComparing(Comparator.naturalOrder())).toList()) {
			if (best.containsKey(page) && (kept.contains(page) || meaningRank.containsKey(page))) {
				out.add(best.get(page));
			} else if (!best.containsKey(page) && meaningRank.containsKey(page)) {
				double[] meaning = byMeaning.get(page);
				out.add(new double[] {SEMANTIC_SCORE * meaning[0], meaning[1]});
			}
			if (out.size() == limit) break;
		}
		return out;
	}

	/** A section's page; several wikis can have the same path. */
	private String page(int i) {
		Section s = sections.get(i);
		return s.wiki() + "/" + s.path();
	}

	private List<Group> analyze(String query, Map<String, String> corrections) {
		List<Group> groups = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (Word w : words(query)) {
			String term = w.term();
			if (!seen.add(term)) continue;
			List<Alternative> alternatives = new ArrayList<>();
			boolean exact = idf.containsKey(term);
			if (exact) {
				alternatives.add(new Alternative(List.of(term), 1.0));
			}
			List<String> synonym = new ArrayList<>();
			for (String t : tokenize(MerlLines.synonyms().getOrDefault(w.word(), ""))) {
				if (idf.containsKey(t) && !t.equals(term)) synonym.add(t);
			}
			if (!synonym.isEmpty()) {
				alternatives.add(new Alternative(synonym, exact ? SYNONYM_WEIGHT : SYNONYM_ONLY_WEIGHT));
			}
			if (alternatives.isEmpty()) {
				String guess = typo(term);
				double weight = TYPO_WEIGHT;
				if (guess == null) {
					guess = prefix(term);
					weight = PREFIX_WEIGHT;
				}
				// A real Minecraft word isn't a typo, even if this wiki never uses it: "minecart" doesn't become
				// "minecraft". Another form of the same word is fine ("friend" → "friendly"). Same as the bot's search.
				if (guess != null && minecraftTerms().contains(term) && !(guess.startsWith(term) || term.startsWith(guess))) {
					guess = null;
				}
				if (guess != null) {
					alternatives.add(new Alternative(List.of(guess), weight));
					corrections.put(w.word(), display.getOrDefault(guess, guess));
				}
			}
			groups.add(new Group(term, alternatives, exact || !synonym.isEmpty()));
		}
		return groups;
	}

	private double score(int i, List<Group> groups, Set<String> queryTerms, boolean changelogOk, Set<String> hints) {
		List<Group> active = groups.stream().filter(g -> !g.alternatives().isEmpty()).toList();
		double score = 0;
		int matched = 0;
		for (Group g : active) {
			double best = 0;
			for (Alternative a : g.alternatives()) {
				double sum = 0;
				for (String t : a.terms()) sum += bm25(i, t);
				best = Math.max(best, a.weight() * sum);
			}
			if (best > 0) {
				matched++;
				score += best;
			}
		}
		if (matched == 0) return 0;
		for (int k = 0; k + 1 < active.size(); k++) {
			List<String> left = active.get(k).alternatives().get(0).terms();
			String pair = left.get(left.size() - 1) + " " + active.get(k + 1).alternatives().get(0).terms().get(0);
			Integer tf = bigrams.get(i).get(pair);
			if (tf != null) {
				score += BIGRAM_WEIGHT * bigramIdf.get(pair) * tf * (K1 + 1) / (tf + K1);
			}
		}
		score *= Math.sqrt((double) matched / active.size());
		if (titleMatch(i, queryTerms)) score += TITLE_MATCH_BONUS;
		if (!headingTerms.get(i).isEmpty() && queryTerms.containsAll(headingTerms.get(i))) score += HEADING_MATCH_BONUS;
		if (hintTargets.get(i).stream().anyMatch(hints::contains)) score += HEADING_HINT_BONUS;
		if (!projectTerms.get(i).isEmpty() && projectTerms.get(i).containsAll(queryTerms)) score += PROJECT_BONUS;
		String path = sections.get(i).path();
		if (!changelogOk && path.toLowerCase(Locale.ROOT).contains("changelog")) score *= CHANGELOG_FACTOR;
		return score + priors.getOrDefault(path, 0.0);
	}

	private boolean titleMatch(int i, Set<String> queryTerms) {
		return !titleTerms.get(i).isEmpty() && queryTerms.containsAll(titleTerms.get(i));
	}

	private double bm25(int i, String term) {
		Integer tf = docs.get(i).get(term);
		if (tf == null) return 0;
		double norm = K1 * (1 - B + B * lengths[i] / avgLength);
		return idf.get(term) * tf * (K1 + 1) / (tf + norm);
	}

	private static volatile Set<String> minecraftTerms;

	/**
	 * Every search term in the names of vanilla blocks, items, mobs, biomes, enchantments and effects
	 * (minecraft_names.json, from the game's language file, shared with the bot).
	 */
	static Set<String> minecraftTerms() {
		Set<String> terms = minecraftTerms;
		if (terms != null) return terms;
		Set<String> out = new java.util.HashSet<>();
		for (String name : VanillaWiki.names()) out.addAll(tokenize(name));
		minecraftTerms = Set.copyOf(out);
		return minecraftTerms;
	}

	private String typo(String term) {
		if (term.length() < 4 || term.chars().allMatch(Character::isDigit)) return null;
		int limit = term.length() < 8 ? 1 : 2;
		String best = null;
		int bestDist = limit + 1;
		for (String candidate : vocab) { // most common first, so ties keep the more common word
			int dist = editDistance(term, candidate, limit);
			if (dist < bestDist) {
				best = candidate;
				bestDist = dist;
				if (dist == 1) break;
			}
		}
		return best;
	}

	private String prefix(String term) {
		if (term.length() < 4) return null;
		for (String t : vocab) {
			if (t.startsWith(term) && !t.equals(term)) return t;
		}
		return null;
	}

	/** Damerau-Levenshtein (optimal string alignment), giving up once it exceeds limit. */
	static int editDistance(String a, String b, int limit) {
		if (Math.abs(a.length() - b.length()) > limit) return limit + 1;
		int[] prev2 = null;
		int[] prev = new int[b.length() + 1];
		for (int j = 0; j <= b.length(); j++) prev[j] = j;
		for (int i = 1; i <= a.length(); i++) {
			int[] cur = new int[b.length() + 1];
			cur[0] = i;
			int rowMin = cur[0];
			for (int j = 1; j <= b.length(); j++) {
				int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
				cur[j] = Math.min(Math.min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost);
				if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
					cur[j] = Math.min(cur[j], prev2[j - 2] + 1);
				}
				rowMin = Math.min(rowMin, cur[j]);
			}
			if (rowMin > limit) return limit + 1;
			prev2 = prev;
			prev = cur;
		}
		return prev[b.length()];
	}

	private static String fold(String text) {
		return COMBINING.matcher(Normalizer.normalize(text, Normalizer.Form.NFKD)).replaceAll("").toLowerCase(Locale.ROOT);
	}

	/** Every word stemmed, stopwords included, for matching headings like "Where to Find". */
	static Set<String> hintTerms(String text) {
		Set<String> out = new HashSet<>();
		Matcher m = WORD.matcher(fold(text));
		while (m.find()) out.add(stem(m.group()));
		return out;
	}

	/** Heading words that fit the kind of question: "where…" → location, "how do I get…" → obtaining. */
	static Set<String> questionHints(String question) {
		String folded = fold(question);
		Set<String> asked = new HashSet<>(Arrays.asList(folded.split("[^a-z]+")));
		Set<String> hints = new HashSet<>();
		if (asked.stream().anyMatch(WHERE_WORDS::contains)) hints.addAll(WHERE_HINTS);
		if (asked.stream().anyMatch(OBTAIN_WORDS::contains)) hints.addAll(OBTAIN_HINTS);
		if (LIST_QUESTION.matcher(folded).find()) hints.addAll(LIST_HINTS);
		return hints;
	}

	/**
	 * The sentence (plus the one after it) from the top pages that answers the question best, or ""
	 * when none covers enough of it. Every section of those pages counts, so "where is Raj Raksha" can
	 * answer from "Where to Find". Rare words count more than common ones. No spoiler text.
	 */
	public String answerLine(String question, List<Result> results) {
		Set<String> terms = new HashSet<>(tokenize(question));
		if (terms.isEmpty()) return "";
		Set<String> hints = questionHints(question);
		double needed = terms.stream().mapToDouble(t -> idf.getOrDefault(t, 1.0)).sum() * ANSWER_COVERAGE;
		float[] meaning = model == null ? null : model.embed(question);
		String best = "";
		double bestScore = 0;
		for (int rank = 0; rank < Math.min(ANSWER_PAGES, results.size()); rank++) {
			Section hit = results.get(rank).section();
			List<Section> page = sections.stream().filter(x -> x.path().equals(hit.path()) && x.wiki().equals(hit.wiki())).toList();
			for (Section section : page.isEmpty() ? List.of(hit) : page) {
				boolean fits = rank == 0 && hintTerms(section.heading()).stream().anyMatch(hints::contains);
				List<String> units = sentences(section.text());
				for (int n = 0; n < units.size(); n++) {
					String text = units.get(n);
					if (text == null) continue;
					double matched = 0;
					for (String t : new HashSet<>(tokenize(text))) if (terms.contains(t)) matched += idf.getOrDefault(t, 1.0);
					if (text.length() < ANSWER_MIN_CHARS || text.length() > ANSWER_CHARS) continue;
					if (n + 1 < units.size() && units.get(n + 1) != null && text.length() + units.get(n + 1).length() < ANSWER_CHARS) {
						text = text + " " + units.get(n + 1);
					}
					// Enough of the question's words, a section made for this kind of question ("Where to Find"),
					// or (with the model) the same meaning in other words.
					if (matched < needed && !fits
							&& (meaning == null || SemanticModel.dot(model.embed(text), meaning) < ANSWER_SEMANTIC_MIN)) continue;
					double score = matched + (fits ? ANSWER_HINT_WEIGHT * needed : 0) + (section == hit ? ANSWER_HIT_WEIGHT : 0)
							- ANSWER_RANK_PENALTY * rank - n * 0.01;
					if (score > bestScore) {
						best = text;
						bestScore = score;
					}
				}
			}
		}
		return best;
	}

	/** The sentences and list lines of a section; null for those inside spoilers. */
	private static List<String> sentences(String text) {
		List<String> out = new ArrayList<>();
		int spoiler = 0;
		for (String line : text.split("\n")) {
			for (String sentence : SENTENCE_END.split(line)) {
				boolean hidden = spoiler > 0 || sentence.contains(Section.SPOILER_START);
				spoiler += count(sentence, Section.SPOILER_START) - count(sentence, Section.SPOILER_END);
				spoiler = Math.max(0, spoiler);
				String clean = sentence.replace(Section.SPOILER_START, "").replace(Section.SPOILER_END, "").strip();
				if (!clean.isEmpty()) out.add(hidden ? null : clean);
			}
		}
		return out;
	}

	private static int count(String text, String part) {
		int n = 0;
		for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) n++;
		return n;
	}

	private static List<String> bigramsOf(List<String> tokens) {
		List<String> out = new ArrayList<>();
		for (int i = 0; i + 1 < tokens.size(); i++) out.add(tokens.get(i) + " " + tokens.get(i + 1));
		return out;
	}

	/** Every word that isn't a stopword, as written and as a search term. */
	public static List<Word> words(String text) {
		String folded = COMBINING.matcher(Normalizer.normalize(text, Normalizer.Form.NFKD))
				.replaceAll("").toLowerCase(Locale.ROOT);
		List<Word> words = new ArrayList<>();
		Matcher m = WORD.matcher(folded);
		while (m.find()) {
			String word = m.group();
			if (!STOPWORDS.contains(word)) {
				words.add(new Word(word, stem(word)));
			}
		}
		return words;
	}

	public static List<String> tokenize(String text) {
		List<String> tokens = new ArrayList<>();
		for (Word w : words(text)) tokens.add(w.term());
		return tokens;
	}

	static String stem(String word) {
		String irregular = IRREGULAR.get(word);
		if (irregular != null) {
			return irregular;
		}
		if (word.length() >= 5 && word.endsWith("ies")) {
			return word.substring(0, word.length() - 3) + "y";
		}
		if (word.length() >= 5 && (word.endsWith("sses") || word.endsWith("ches") || word.endsWith("shes")
				|| (word.endsWith("xes") && !word.endsWith("axes")))) {
			return word.substring(0, word.length() - 2);
		}
		if (word.length() >= 4 && word.endsWith("s") && !word.endsWith("ss") && !word.endsWith("us") && !word.endsWith("is")) {
			word = word.substring(0, word.length() - 1);
		}
		if (word.length() >= 6 && word.endsWith("ing")) {
			return word.substring(0, word.length() - 3);
		}
		if (word.length() >= 5 && word.endsWith("ied")) {
			return word.substring(0, word.length() - 3) + "y";
		}
		if (word.length() >= 5 && word.endsWith("ed")) {
			return word.substring(0, word.length() - 2);
		}
		return word;
	}
}

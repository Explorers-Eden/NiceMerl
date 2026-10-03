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
	/** Sections whose heading is one of the caller's hint words, e.g. "Crafting" for "how do I make…". */
	private static final double HEADING_HINT_BONUS = 2.5;
	/** Changelogs mention everything, so they only win when the question is about changes. */
	private static final double CHANGELOG_FACTOR = 0.75;
	private static final Set<String> CHANGELOG_WORDS = Set.of("changelog", "change", "update", "patch", "new", "added", "release");
	/** Results scoring below this share of the best result are dropped. */
	private static final double RELATIVE_CUTOFF = 0.35;
	private static final Pattern WORD = Pattern.compile("[a-z0-9]+");
	private static final Pattern COMBINING = Pattern.compile("\\p{M}");

	/** @param titleMatch the page title or heading is exactly what was asked about */
	public record Result(Section section, double score, Excerpt excerpt, boolean titleMatch) {}

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
	private final Map<String, Double> idf = new HashMap<>();
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

			Map<String, Integer> counts = new HashMap<>();
			for (String t : tokens) counts.merge(t, 1, Integer::sum);
			Map<String, Integer> pairs = new HashMap<>();
			for (String p : bigramsOf(textTokens)) pairs.merge(p, 1, Integer::sum);
			for (String p : bigramsOf(titleTokens)) pairs.merge(p, TITLE_WEIGHT, Integer::sum);
			docs.add(counts);
			bigrams.add(pairs);
			lengths[i] = tokens.size();
			total += tokens.size();
			titleTerms.add(new HashSet<>(tokenize(s.pageTitle())));
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
					return new Result(s, hit[0], Excerpt.of(s.text(), termSet, excerptLength), false);
				})
				.toList();
	}

	/** The full wiki search: best section per page, with typo tolerance, synonyms and phrase boosts. */
	public Outcome find(String query, int limit, int excerptLength) {
		return find(query, limit, excerptLength, MIN_SCORE);
	}

	public Outcome find(String query, int limit, int excerptLength, double minScore) {
		Map<String, String> corrections = new LinkedHashMap<>();
		List<Group> groups = analyze(query, corrections);
		boolean uncertain = groups.stream().anyMatch(g -> !g.exact());
		if (groups.stream().allMatch(g -> g.alternatives().isEmpty()) || sections.isEmpty()) {
			return new Outcome(List.of(), corrections, !groups.isEmpty());
		}
		Set<String> queryTerms = new HashSet<>();
		for (Group g : groups) {
			for (Alternative a : g.alternatives()) queryTerms.addAll(a.terms());
		}
		boolean changelogOk = queryTerms.stream().anyMatch(CHANGELOG_WORDS::contains);

		Map<String, double[]> best = new HashMap<>(); // page -> {score, section index}
		for (int i = 0; i < sections.size(); i++) {
			double score = score(i, groups, queryTerms, changelogOk);
			if (score < minScore || score <= 0) continue;
			Section s = sections.get(i);
			String page = s.wiki() + "/" + s.path(); // several wikis can have the same path
			double[] current = best.get(page);
			if (current == null || score > current[0]) {
				best.put(page, new double[] {score, i});
			}
		}
		// Highest score first; on a tie the later section wins, like Python's sort of (score, index).
		List<double[]> ranked = best.values().stream()
				.sorted((a, b) -> a[0] != b[0] ? Double.compare(b[0], a[0]) : Double.compare(b[1], a[1]))
				.limit(limit)
				.toList();
		List<Result> results = new ArrayList<>();
		for (double[] hit : ranked) {
			if (hit[0] < ranked.get(0)[0] * RELATIVE_CUTOFF) continue;
			int i = (int) hit[1];
			Section s = sections.get(i);
			boolean titled = titleMatch(i, queryTerms)
					|| (!headingTerms.get(i).isEmpty() && queryTerms.containsAll(headingTerms.get(i)));
			results.add(new Result(s, hit[0], Excerpt.of(s.text(), queryTerms, excerptLength), titled));
		}
		return new Outcome(results, results.isEmpty() ? Map.of() : corrections, uncertain);
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
				if (guess != null) {
					alternatives.add(new Alternative(List.of(guess), weight));
					corrections.put(w.word(), display.getOrDefault(guess, guess));
				}
			}
			groups.add(new Group(term, alternatives, exact || !synonym.isEmpty()));
		}
		return groups;
	}

	private double score(int i, List<Group> groups, Set<String> queryTerms, boolean changelogOk) {
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
		if (headingTerms.get(i).stream().anyMatch(headingHints::contains)) score += HEADING_HINT_BONUS;
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

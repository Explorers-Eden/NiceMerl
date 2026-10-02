package eu.explorerseden.nicemerl;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small BM25 search over wiki sections. Mirrors search.py in the NiceMerl Discord bot,
 * so changes to tokenizing or scoring should be made in both places.
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
			""".trim().split("\\s+")));

	private static final int TITLE_WEIGHT = 3;
	private static final int PATH_WEIGHT = 2;
	private static final double K1 = 1.5;
	private static final double B = 0.75;
	private static final double MIN_SCORE = 2.0;
	private static final Pattern WORD = Pattern.compile("[a-z0-9]+");
	private static final Pattern COMBINING = Pattern.compile("\\p{M}");

	public record Result(Section section, double score, Excerpt excerpt) {}

	private final List<Section> sections;
	private final List<Map<String, Integer>> docs = new ArrayList<>();
	private final int[] lengths;
	private final double avgLength;
	private final Map<String, Double> idf = new HashMap<>();

	public SearchIndex(List<Section> sections) {
		this.sections = List.copyOf(sections);
		this.lengths = new int[sections.size()];
		Map<String, Integer> df = new HashMap<>();
		long total = 0;
		for (int i = 0; i < sections.size(); i++) {
			Section s = sections.get(i);
			List<String> tokens = new ArrayList<>(tokenize(s.text()));
			List<String> titleTokens = tokenize(s.pageTitle() + " " + s.heading());
			List<String> pathTokens = tokenize(s.path().replace('/', ' ').replace('_', ' '));
			for (int w = 0; w < TITLE_WEIGHT; w++) tokens.addAll(titleTokens);
			for (int w = 0; w < PATH_WEIGHT; w++) tokens.addAll(pathTokens);

			Map<String, Integer> counts = new HashMap<>();
			for (String t : tokens) counts.merge(t, 1, Integer::sum);
			docs.add(counts);
			lengths[i] = tokens.size();
			total += tokens.size();
			for (String t : counts.keySet()) df.merge(t, 1, Integer::sum);
		}
		int n = sections.size();
		this.avgLength = n == 0 ? 0 : (double) total / n;
		df.forEach((t, f) -> idf.put(t, Math.log(1 + (n - f + 0.5) / (f + 0.5))));
	}

	public int pageCount() {
		Set<String> paths = new HashSet<>();
		for (Section s : sections) paths.add(s.path());
		return paths.size();
	}

	public List<Result> search(String query, int limit, int excerptLength) {
		return search(query, limit, excerptLength, MIN_SCORE, true);
	}

	/**
	 * @param onePerPage keep only the best section of each page (wiki search), or list every match
	 */
	public List<Result> search(String query, int limit, int excerptLength, double minScore, boolean onePerPage) {
		List<String> terms = new ArrayList<>(new LinkedHashSet<>(tokenize(query)));
		if (terms.isEmpty() || sections.isEmpty()) {
			return List.of();
		}
		Map<String, double[]> best = new HashMap<>(); // path -> {score, section index}
		for (int i = 0; i < sections.size(); i++) {
			double score = score(i, terms);
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
					return new Result(s, hit[0], Excerpt.of(s.text(), termSet, excerptLength));
				})
				.toList();
	}

	private double score(int i, List<String> terms) {
		Map<String, Integer> doc = docs.get(i);
		double score = 0;
		for (String t : terms) {
			Integer tf = doc.get(t);
			if (tf != null) {
				double norm = K1 * (1 - B + B * lengths[i] / avgLength);
				score += idf.get(t) * tf * (K1 + 1) / (tf + norm);
			}
		}
		return score;
	}

	public static List<String> tokenize(String text) {
		String folded = COMBINING.matcher(Normalizer.normalize(text, Normalizer.Form.NFKD))
				.replaceAll("").toLowerCase(Locale.ROOT);
		List<String> tokens = new ArrayList<>();
		Matcher m = WORD.matcher(folded);
		while (m.find()) {
			String word = m.group();
			if (!STOPWORDS.contains(word)) {
				tokens.add(stem(word));
			}
		}
		return tokens;
	}

	static String stem(String word) {
		if (word.length() >= 5 && word.endsWith("ies")) {
			return word.substring(0, word.length() - 3) + "y";
		}
		if (word.length() >= 4 && word.endsWith("s") && !word.endsWith("ss") && !word.endsWith("us") && !word.endsWith("is")) {
			word = word.substring(0, word.length() - 1);
		}
		if (word.length() >= 6 && word.endsWith("ing")) {
			return word.substring(0, word.length() - 3);
		}
		if (word.length() >= 5 && word.endsWith("ed")) {
			return word.substring(0, word.length() - 2);
		}
		return word;
	}
}

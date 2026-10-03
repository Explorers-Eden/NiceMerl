package eu.explorerseden.nicemerl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A short window of section text around the best query match, as words tagged for formatting. */
public record Excerpt(List<Word> words, boolean cutStart, boolean cutEnd) {
	/** @param term the query term this word matched, or null */
	public record Word(String text, String term, boolean spoiler) {
		public boolean hit() {
			return term != null;
		}
	}

	public static Excerpt of(String text, Set<String> terms, int length) {
		List<Word> words = new ArrayList<>();
		int inSpoiler = 0;
		for (String w : text.split("\\s+")) {
			if (w.isEmpty()) continue;
			if (w.equals(Section.SPOILER_START)) {
				inSpoiler++;
			} else if (w.equals(Section.SPOILER_END)) {
				inSpoiler = Math.max(0, inSpoiler - 1);
			} else {
				String hit = SearchIndex.tokenize(w).stream().filter(terms::contains).findFirst().orElse(null);
				words.add(new Word(w, hit, inSpoiler > 0));
			}
		}
		if (words.isEmpty()) {
			return new Excerpt(List.of(), false, false);
		}

		// Largest window of whole words that fits the length.
		int window = 1;
		int chars = words.get(0).text().length();
		while (window < words.size() && chars + 1 + words.get(window).text().length() <= length) {
			chars += 1 + words.get(window).text().length();
			window++;
		}

		// Slide it to the position covering the most distinct query terms, then the most hits
		// (earliest wins ties).
		Map<String, Integer> counts = new HashMap<>();
		int hits = 0;
		for (int i = 0; i < window; i++) {
			String term = words.get(i).term();
			if (term != null) {
				counts.merge(term, 1, Integer::sum);
				hits++;
			}
		}
		int bestStart = 0;
		int bestDistinct = counts.size();
		int bestHits = hits;
		for (int start = 1; start + window <= words.size(); start++) {
			String out = words.get(start - 1).term();
			if (out != null) {
				hits--;
				if (counts.merge(out, -1, Integer::sum) == 0) counts.remove(out);
			}
			String in = words.get(start + window - 1).term();
			if (in != null) {
				hits++;
				counts.merge(in, 1, Integer::sum);
			}
			if (counts.size() > bestDistinct || (counts.size() == bestDistinct && hits > bestHits)) {
				bestDistinct = counts.size();
				bestHits = hits;
				bestStart = start;
			}
		}
		int end = bestStart + window;
		return new Excerpt(List.copyOf(words.subList(bestStart, end)), bestStart > 0, end < words.size());
	}
}

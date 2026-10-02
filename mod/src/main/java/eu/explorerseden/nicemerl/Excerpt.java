package eu.explorerseden.nicemerl;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** A short window of section text around the best query match, as words tagged for formatting. */
public record Excerpt(List<Word> words, boolean cutStart, boolean cutEnd) {
	public record Word(String text, boolean hit, boolean spoiler) {}

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
				boolean hit = SearchIndex.tokenize(w).stream().anyMatch(terms::contains);
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

		// Slide it to the position with the most query hits (earliest wins ties).
		int hits = 0;
		for (int i = 0; i < window; i++) if (words.get(i).hit()) hits++;
		int bestStart = 0;
		int bestHits = hits;
		for (int start = 1; start + window <= words.size(); start++) {
			if (words.get(start - 1).hit()) hits--;
			if (words.get(start + window - 1).hit()) hits++;
			if (hits > bestHits) {
				bestHits = hits;
				bestStart = start;
			}
		}
		int end = bestStart + window;
		return new Excerpt(List.copyOf(words.subList(bestStart, end)), bestStart > 0, end < words.size());
	}
}

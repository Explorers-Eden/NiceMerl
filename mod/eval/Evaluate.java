package eu.explorerseden.nicemerl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Measures the mod's answers with the same test questions as the bot (bot/eval/questions.json) and
 * the settings test (mod/eval/settings.json). Run it with mod/eval/run.sh after a build.
 * Follows the steps of MerlCommand.ask() for the downloaded wiki, like the bot's evaluate.py.
 */
public final class Evaluate {
	private Evaluate() {}

	public static void main(String[] args) throws Exception {
		boolean verbose = List.of(args).contains("-v");
		// The meaning-based search model, from the folder given with --model (the bot's bot/model works).
		int at = List.of(args).indexOf("--model");
		SemanticModel model = at >= 0 && Files.isDirectory(Path.of(args[at + 1])) ? SemanticModel.load(Path.of(args[at + 1])) : null;
		System.out.println("Meaning-based search: " + (model != null ? "on" : "off"));
		SearchIndex index = new SearchIndex(snapshot(Path.of(args[0])), model);
		JsonObject data = JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonObject();

		int n = 0, hit1 = 0, hit3 = 0, answers = 0, withAnswer = 0;
		List<String> misses = new ArrayList<>();
		for (JsonElement e : data.getAsJsonArray("questions")) {
			JsonObject item = e.getAsJsonObject();
			String q = item.get("q").getAsString();
			List<String> pages = new ArrayList<>();
			for (JsonElement p : item.getAsJsonArray("pages")) pages.add(p.getAsString());
			Route r = route(index, q);
			n++;
			if (!r.results.isEmpty() && right(r.results.get(0).section().path(), pages)) hit1++;
			if (r.results.stream().limit(3).anyMatch(x -> right(x.section().path(), pages))) hit3++;
			else misses.add(q + ": " + r.kind + " " + r.results.stream().limit(3).map(x -> x.section().path()).toList());
			if (item.has("answer") && !r.results.isEmpty()) {
				withAnswer++;
				if (index.answerLine(q, r.results).toLowerCase().contains(item.get("answer").getAsString().toLowerCase())) answers++;
			}
		}
		int wrongChatter = 0;
		List<String> chatter = new ArrayList<>();
		for (JsonElement e : data.getAsJsonArray("chatter")) {
			Route r = route(index, e.getAsString());
			if (!r.results.isEmpty()) {
				wrongChatter++;
				chatter.add(e.getAsString() + " -> " + r.results.get(0).section().path());
			}
		}
		int contextOk = 0, contextBase = 0, contextN = 0;
		if (data.has("context")) {
			for (JsonElement e : data.getAsJsonArray("context")) {
				JsonObject item = e.getAsJsonObject();
				java.util.Map<String, Double> interests = new java.util.HashMap<>();
				item.getAsJsonObject("interests").entrySet().forEach(x -> interests.put(x.getKey(), x.getValue().getAsDouble()));
				List<String> pages = new ArrayList<>();
				for (JsonElement p : item.getAsJsonArray("pages")) pages.add(p.getAsString());
				Route with = route(index, item.get("q").getAsString(), interests);
				Route without = route(index, item.get("q").getAsString());
				contextN++;
				if (!with.results.isEmpty() && right(with.results.get(0).section().path(), pages)) contextOk++;
				if (!without.results.isEmpty() && right(without.results.get(0).section().path(), pages)) contextBase++;
			}
		}
		System.out.printf("Questions:  hit@1 %d/%d (%d%%)   hit@3 %d/%d (%d%%)%n", hit1, n, 100 * hit1 / n, hit3, n, 100 * hit3 / n);
		System.out.printf("Answer text contains the answer: %d/%d%n", answers, withAnswer);
		System.out.printf("Chatter wrongly answered with a page: %d/%d%n", wrongChatter, data.getAsJsonArray("chatter").size());
		System.out.printf("Context (what the person usually asks about): %d/%d (without it: %d)%n", contextOk, contextN, contextBase);
		if (verbose) {
			System.out.println("\nMisses:");
			misses.forEach(m -> System.out.println("  " + m));
			System.out.println("\nChatter that got a page:");
			chatter.forEach(c -> System.out.println("  " + c));
		}
		if (args.length > 2 && args[2].endsWith(".json")) settings(Path.of(args[2]), verbose);
	}

	private record Route(String kind, List<SearchIndex.Result> results) {}

	/** What Merl does with a message, like MerlCommand.ask(): small talk, unclear, or pages. */
	static Route route(SearchIndex index, String question) {
		return route(index, question, java.util.Map.of());
	}

	static Route route(SearchIndex index, String question, java.util.Map<String, Double> interests) {
		String talk = MerlLines.smallTalk(question);
		if (talk == null && MerlLines.multiSmallTalk(question) != null) talk = MerlLines.multiSmallTalk(question).talk();
		if (talk == null && MerlLines.recall(question) != null) talk = "recall";
		if (talk != null || MerlLines.metQuestion(question) != null) return new Route("talk", List.of());
		MerlLines.Split split = MerlLines.splitSmallTalk(question);
		String search = split.rest();
		SearchIndex.Outcome outcome = index.find(search, 3, 160, interests);
		List<SearchIndex.Result> results = outcome.results();
		boolean asking = split.prefix() != null || MerlLines.seeksInfo(question) || MerlLines.isFollowUp(search);
		String sure = results.isEmpty() ? null : VanillaWiki.confidence(results, outcome);
		boolean allMatched = !results.isEmpty() && results.get(0).matched() >= new HashSet<>(SearchIndex.tokenize(search)).size();
		if (!asking && !MerlLines.clearlyAbout(sure, !results.isEmpty() && results.get(0).titleMatch(), question, allMatched)) {
			return new Route("unclear", List.of());
		}
		if ("guess".equals(sure) && results.get(0).matched() <= 1 && !results.get(0).titleMatch()) return new Route("unclear", List.of());
		if ("guess".equals(sure)) results = results.subList(0, 1);
		return new Route("pages", results);
	}

	static boolean right(String path, List<String> pages) {
		return pages.stream().anyMatch(p -> path.equals(p) || p.endsWith("*") && path.startsWith(p.substring(0, p.length() - 1)));
	}

	static List<Section> snapshot(Path file) throws Exception {
		List<Section> sections = new ArrayList<>();
		for (JsonElement e : JsonParser.parseString(Files.readString(file)).getAsJsonArray()) {
			JsonObject o = e.getAsJsonObject();
			sections.add(new Section(o.get("path").getAsString(), o.get("page_title").getAsString(), o.get("heading").getAsString(),
					o.get("anchor").getAsString(), o.get("text").getAsString(), "eden", false,
					o.has("meta") ? o.get("meta").getAsString() : ""));
		}
		return sections;
	}

	/** The settings test: each question against settings modelled on the wiki's setting lists. */
	static void settings(Path file, boolean verbose) throws Exception {
		JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
		List<DatapackSettings.Setting> all = new ArrayList<>();
		for (JsonElement e : root.getAsJsonArray("settings")) {
			JsonArray s = e.getAsJsonArray();
			all.add(new DatapackSettings.Setting(s.get(0).getAsString(), s.get(1).getAsString(), s.get(2).getAsString(),
					s.get(3).getAsString(), null, null));
		}
		int ok = 0, n = 0;
		Set<String> failures = new java.util.LinkedHashSet<>();
		for (JsonElement e : root.getAsJsonArray("questions")) {
			JsonObject t = e.getAsJsonObject();
			String q = t.get("q").getAsString();
			String want = t.get("want").isJsonNull() ? null : t.get("want").getAsString();
			boolean loose = DatapackSettings.isSettingsQuestion(q);
			List<DatapackSettings.Setting> got = DatapackSettings.rank(all, q, 6, !loose);
			List<String> labels = got.stream().map(DatapackSettings.Setting::label).toList();
			boolean good;
			if (want == null) good = got.isEmpty();
			else if (want.equals("*")) good = got.isEmpty() && DatapackSettings.mentionsSettings(q);
			else if (want.startsWith("@")) good = !got.isEmpty() && got.stream().allMatch(x -> x.pack().equals(want.substring(1)));
			else good = labels.stream().limit(2).anyMatch(l -> l.equals(want) || l.endsWith("› " + want));
			n++;
			if (good) ok++;
			else failures.add(q + " -> " + labels + " (wanted " + want + ")");
		}
		System.out.printf("Settings: %d/%d (%d%%)%n", ok, n, 100 * ok / n);
		if (verbose) failures.forEach(f -> System.out.println("  " + f));
	}
}

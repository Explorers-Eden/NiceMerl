package eu.explorerseden.nicemerl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;

/**
 * Answers questions from a MediaWiki wiki searched live: by default the Minecraft Wiki (minecraft.wiki)
 * for vanilla questions, but any MediaWiki (e.g. a mod wiki) works. The wiki's own search finds candidate pages; those pages are then split into sections and ranked
 * with the same search as the community wiki. Mirrors vanilla.py in the NiceMerl Discord bot.
 *
 * <p>{@link #search} blocks on the network, so call it off the server thread.
 */
public final class VanillaWiki {
	private static final String USER_AGENT = "NiceMerl-Mod/1.2 (Minecraft server mod; +https://github.com/Explorers-Eden/NiceMerl)";
	/** Words that say "I mean plain Minecraft". */
	private static final Pattern VANILLA_CUE = Pattern.compile("\\b(vanilla|minecraft|mc|normal game|base game|default game)\\b",
			Pattern.CASE_INSENSITIVE);
	private static final Set<String> CUE_WORDS = Set.of("vanilla", "minecraft", "mc", "game", "normal", "base", "default");
	/** An Eden result at least this good (with every word understood) is trusted on its own. */
	private static final double STRONG_SCORE = 8.0;
	/** Changelog and other non-article pages. */
	private static final Pattern SKIP_TITLES = Pattern.compile(
			"Edition|Snapshot|Pre-release|Release Candidate|\\(disambiguation\\)|^(Category|Template|File):", Pattern.CASE_INSENSITIVE);
	private static final Set<String> SKIP_SECTIONS = Set.of(
			"history", "issues", "trivia", "gallery", "screenshots", "videos", "references", "navigation",
			"in other media", "publicity", "data values", "achievements", "external links", "see also",
			"notes", "sounds", "renders", "mojang screenshots", "development images", "concept artwork");
	private static final String REMOVE = "style, script, .infobox, .notaninfobox, .navbox, .navigation-not-searchable, "
			+ ".msgbox, .hatnote, .searchaux, .toc, #toc, sup.reference, .mw-references-wrap, .references, .mw-editsection, "
			+ "table.collapsible, figure, .gallery, .noprint, .mw-empty-elt";
	/** "How do I make / get…" questions are best answered by these chapters. */
	private static final Pattern HOW_TO = Pattern.compile("\\b(make|craft|build|create|get|obtain|find|where|spawn)\\b",
			Pattern.CASE_INSENSITIVE);
	private static final Set<String> HOW_TO_HEADINGS = Set.copyOf(SearchIndex.tokenize(
			"creation crafting obtaining construction recipe building natural generation spawning location"));
	private static final int CANDIDATE_PAGES = 3;
	private static final double VANILLA_MIN_SCORE = SearchIndex.MIN_SCORE / 2;
	/** Minecraft Wiki sections score lower; this brings them to the Eden wiki's range for {@link #confidence}. */
	private static final double VANILLA_SCALE = SearchIndex.MIN_SCORE / VANILLA_MIN_SCORE;

	/** How to ask minecraft.wiki: properly, or only to add a page named after the subject. */
	public enum Mode { SEARCH, CHECK }

	/** @param titled the top page is named after what was asked about */
	public record Answer(List<SearchIndex.Result> results, boolean titled) {
		static final Answer NONE = new Answer(List.of(), false);
	}

	private final String name;
	private final String baseUrl;
	private final String api;
	private final int excerptLength;
	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(8))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
	private final TtlCache<List<String>> titlesCache = new TtlCache<>(256, Duration.ofHours(24));
	private final TtlCache<List<Section>> pagesCache = new TtlCache<>(64, Duration.ofHours(12));

	public VanillaWiki(String name, String baseUrl, int excerptLength) {
		this.name = name;
		this.baseUrl = baseUrl.replaceAll("/+$", "");
		this.excerptLength = excerptLength;
		this.api = this.baseUrl + "/api.php";
	}

	/** SEARCH unless Eden is confident and the question doesn't say "vanilla". */
	public static Mode plan(String question, SearchIndex.Outcome eden) {
		boolean strong = eden.confidence() >= STRONG_SCORE && !eden.uncertain();
		return strong && !VANILLA_CUE.matcher(question).find() ? Mode.CHECK : Mode.SEARCH;
	}

	/**
	 * Orders and trims both sources. Vanilla goes first when the question says "vanilla", when a
	 * minecraft.wiki page is named after the subject and no Eden page is, or when Eden found nothing.
	 */
	public static List<SearchIndex.Result> combine(String question, SearchIndex.Outcome eden, Answer vanilla, int total) {
		List<SearchIndex.Result> edenResults = eden.results();
		List<SearchIndex.Result> vanillaResults = vanilla.results();
		if (vanillaResults.isEmpty()) {
			return edenResults.subList(0, Math.min(total, edenResults.size()));
		}
		boolean edenTitled = !edenResults.isEmpty() && edenResults.get(0).titleMatch();
		boolean vanillaFirst = edenResults.isEmpty()
				|| VANILLA_CUE.matcher(question).find()
				|| (vanilla.titled() && !edenTitled);
		List<SearchIndex.Result> first = vanillaFirst ? vanillaResults : edenResults;
		List<SearchIndex.Result> second = vanillaFirst ? edenResults : vanillaResults;
		int headSize = second.isEmpty() ? total : Math.max(1, total - 1);
		List<SearchIndex.Result> out = new ArrayList<>(first.subList(0, Math.min(headSize, first.size())));
		out.addAll(second.subList(0, Math.min(total - out.size(), second.size())));
		return out;
	}

	/** How sure Merl is about the top result: "sure", "maybe" or "guess". */
	public static String confidence(List<SearchIndex.Result> results, SearchIndex.Outcome eden) {
		SearchIndex.Result top = results.get(0);
		double score = top.score() * (top.section().vanilla() ? VANILLA_SCALE : 1);
		boolean uncertain = eden.uncertain() && !top.section().vanilla();
		if (score < SearchIndex.GUESS_SCORE || (uncertain && eden.corrections().isEmpty())) return "guess";
		if (!uncertain && (score >= SearchIndex.SURE_SCORE || (top.titleMatch() && score >= SearchIndex.SURE_TITLE_SCORE))) {
			return "sure";
		}
		return "maybe";
	}

	/** The question as search words, with slang spelled out and "vanilla"/"minecraft" removed. */
	static String keywords(String question) {
		List<String> out = new ArrayList<>();
		for (SearchIndex.Word w : SearchIndex.words(question)) {
			if (!CUE_WORDS.contains(w.word())) out.add(MerlLines.synonyms().getOrDefault(w.word(), w.word()));
		}
		return String.join(" ", out.subList(0, Math.min(10, out.size())));
	}

	/**
	 * Every word of the title appears in the question, allowing typos and word endings
	 * ("enchantmnt table" names "Enchanting Table").
	 */
	static boolean titleMatches(String title, Set<String> asked) {
		Set<String> terms = new HashSet<>(SearchIndex.tokenize(title));
		if (terms.isEmpty()) return false;
		for (String t : terms) {
			if (asked.stream().noneMatch(q -> similar(t, q))) return false;
		}
		return true;
	}

	private static boolean similar(String a, String b) {
		if (a.equals(b)) return true;
		int shorter = Math.min(a.length(), b.length());
		if (shorter >= 4 && (a.startsWith(b) || b.startsWith(a))) return true;
		int limit = Math.max(a.length(), b.length()) < 8 ? 1 : 2;
		return shorter >= 4 && SearchIndex.editDistance(a, b, limit) <= limit;
	}

	public String name() {
		return name;
	}

	public String baseUrl() {
		return baseUrl;
	}

	/** Searches several wikis and merges their answers, best score first. */
	public static Answer searchAll(List<VanillaWiki> wikis, String question, int limit, boolean requireTitleMatch) {
		List<SearchIndex.Result> results = new ArrayList<>();
		boolean titled = false;
		for (VanillaWiki wiki : wikis) {
			Answer answer = wiki.search(question, limit, requireTitleMatch);
			results.addAll(answer.results());
			titled |= answer.titled();
		}
		results.sort((a, b) -> Double.compare(b.score(), a.score()));
		return new Answer(List.copyOf(results.subList(0, Math.min(limit, results.size()))), titled);
	}

	public String url(Section section) {
		String url = baseUrl + "/w/" + encode(section.path());
		return section.anchor().isEmpty() ? url : url + "#" + encode(section.anchor());
	}

	private static String encode(String part) {
		return URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20")
				.replace("%2F", "/").replace("%3A", ":").replace("%28", "(").replace("%29", ")").replace("%2C", ",");
	}

	/** Best minecraft.wiki sections for the question. Never throws: errors mean no results. */
	public Answer search(String question, int limit, boolean requireTitleMatch) {
		String query = keywords(question);
		if (SearchIndex.tokenize(query).isEmpty()) return Answer.NONE;
		try {
			List<String> titles = titles(query);
			titles = titles.subList(0, Math.min(CANDIDATE_PAGES, titles.size()));
			Set<String> asked = new HashSet<>(SearchIndex.tokenize(query));
			boolean titled = !titles.isEmpty() && titleMatches(titles.get(0), asked);
			if (requireTitleMatch) {
				if (!titled) return Answer.NONE;
				titles = titles.subList(0, 1);
				limit = 1;
			}
			if (titles.isEmpty()) return Answer.NONE;

			List<CompletableFuture<List<Section>>> pages = new ArrayList<>();
			for (String title : titles) pages.add(page(title));
			List<Section> sections = new ArrayList<>();
			for (CompletableFuture<List<Section>> page : pages) {
				try {
					sections.addAll(page.join());
				} catch (RuntimeException e) {
					NiceMerl.LOGGER.warn("Minecraft Wiki page fetch failed: {}", e.getMessage());
				}
			}
			if (sections.isEmpty()) return Answer.NONE;

			// The wiki's own ranking counts too: its first hits get a head start.
			Map<String, Double> priors = new LinkedHashMap<>();
			Set<String> paths = new LinkedHashSet<>();
			for (Section s : sections) paths.add(s.path());
			int rank = 0;
			for (String path : paths) {
				priors.put(path, rank == 0 ? 1.5 : rank == 1 ? 0.75 : 0.0);
				rank++;
			}
			Set<String> hints = HOW_TO.matcher(question).find() ? HOW_TO_HEADINGS : Set.of();
			List<SearchIndex.Result> results = new SearchIndex(sections, priors, hints)
					.find(query, limit, excerptLength, VANILLA_MIN_SCORE).results();
			return new Answer(results, titled);
		} catch (IOException | RuntimeException e) {
			NiceMerl.LOGGER.warn("Minecraft Wiki lookup failed for '{}': {}", question, e.toString());
			return Answer.NONE;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Answer.NONE;
		}
	}

	/**
	 * The first sentences of the page with exactly this title (or the page it redirects to), for "what's this?".
	 * Never a search, so a thing without its own page gets no summary. Null when there's none.
	 */
	public MerlWhatsThis.Summary summary(String title) {
		try {
			JsonObject data = get(Map.of("action", "query", "prop", "extracts", "exintro", "1", "explaintext", "1",
					"exsentences", "2", "redirects", "1", "titles", title));
			JsonArray pages = data.getAsJsonObject("query").getAsJsonArray("pages");
			if (pages == null || pages.isEmpty()) return null;
			JsonObject page = pages.get(0).getAsJsonObject();
			if (page.has("missing") || !page.has("extract")) return null;
			String text = page.get("extract").getAsString().strip();
			// Disambiguation pages ("X may refer to") aren't about the thing.
			if (text.isEmpty() || text.contains("may refer to")) return null;
			// Two long sentences are too much for chat: keep the first, and cut a very long one at a word.
			if (text.length() > 280) text = text.split("(?<=[.!?])\\s+")[0];
			if (text.length() > 280) text = text.substring(0, text.lastIndexOf(' ', 277)) + "…";
			String real = page.get("title").getAsString();
			return new MerlWhatsThis.Summary(real, text, baseUrl + "/w/" + encode(real.replace(' ', '_')), name);
		} catch (IOException | RuntimeException e) {
			NiceMerl.LOGGER.debug("No Minecraft Wiki summary for {}", title, e);
			return null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
	}

	private List<String> titles(String query) throws IOException, InterruptedException {
		List<String> cached = titlesCache.get(query);
		if (cached != null) return cached;
		List<String> found = searchTitles(query, true);
		titlesCache.put(query, found);
		return found;
	}

	private List<String> searchTitles(String query, boolean retry) throws IOException, InterruptedException {
		JsonObject data = get(Map.of(
				"action", "query", "list", "search", "srnamespace", "0", "srlimit", "8", "srprop", "",
				"srinfo", "suggestion", "srsearch", query + " -intitle:Edition -intitle:Snapshot"));
		JsonObject result = data.getAsJsonObject("query");
		List<String> found = new ArrayList<>();
		for (JsonElement hit : result.getAsJsonArray("search")) {
			String title = hit.getAsJsonObject().get("title").getAsString();
			if (!SKIP_TITLES.matcher(title).find()) found.add(title);
		}
		// Typos: retry once with the wiki's own "did you mean".
		JsonObject info = result.getAsJsonObject("searchinfo");
		String suggestion = info != null && info.has("suggestion") ? info.get("suggestion").getAsString() : "";
		suggestion = suggestion.replace("-intitle:Edition", "").replace("-intitle:Snapshot", "").trim();
		if (found.isEmpty() && retry && !suggestion.isEmpty() && !suggestion.equals(query)) {
			return searchTitles(suggestion, false);
		}
		return found;
	}

	private CompletableFuture<List<Section>> page(String title) {
		List<Section> cached = pagesCache.get(title);
		if (cached != null) return CompletableFuture.completedFuture(cached);
		return http.sendAsync(request(Map.of("action", "parse", "page", title, "prop", "text", "redirects", "1")),
						HttpResponse.BodyHandlers.ofByteArray())
				.thenApply(response -> {
					if (response.statusCode() != 200) {
						throw new IllegalStateException("HTTP " + response.statusCode() + " for " + title);
					}
					JsonObject parse = JsonParser.parseString(body(response)).getAsJsonObject().getAsJsonObject("parse");
					String resolved = parse.get("title").getAsString();
					List<Section> sections = extractSections(parse.get("text").getAsString(), resolved, baseUrl);
					pagesCache.put(title, sections);
					return sections;
				});
	}

	private JsonObject get(Map<String, String> params) throws IOException, InterruptedException {
		HttpResponse<byte[]> response = http.send(request(params), HttpResponse.BodyHandlers.ofByteArray());
		if (response.statusCode() != 200) {
			throw new IOException("Minecraft Wiki request failed with HTTP " + response.statusCode());
		}
		return JsonParser.parseString(body(response)).getAsJsonObject();
	}

	/** Pages are about ten times smaller gzipped (≈50 KB instead of ≈500 KB), so ask for gzip and unpack it here. */
	private static String body(HttpResponse<byte[]> response) {
		byte[] bytes = response.body();
		if (response.headers().firstValue("Content-Encoding").orElse("").equalsIgnoreCase("gzip")) {
			try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
				bytes = in.readAllBytes();
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
		return new String(bytes, StandardCharsets.UTF_8);
	}

	private HttpRequest request(Map<String, String> params) {
		StringBuilder query = new StringBuilder("format=json&formatversion=2");
		params.forEach((k, v) -> query.append('&').append(k).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
		return HttpRequest.newBuilder(URI.create(api + "?" + query))
				.timeout(Duration.ofSeconds(8))
				.header("User-Agent", USER_AGENT)
				.header("Accept-Encoding", "gzip")
				.GET()
				.build();
	}

	static List<Section> extractSections(String html, String title, String baseUrl) {
		Element body = Jsoup.parseBodyFragment(html).body();
		Element root = body.selectFirst("div.mw-parser-output");
		if (root == null) root = body;
		root.select(REMOVE).remove();
		// Drop whole chapters players don't need (History, Trivia, Gallery…), up to the next h2.
		for (Element wrapper : root.select("div.mw-heading2, h2")) {
			if (wrapper.parent() == null || (wrapper.normalName().equals("h2") && wrapper.parent().hasClass("mw-heading2"))) {
				continue;
			}
			if (!SKIP_SECTIONS.contains(wrapper.text().trim().toLowerCase(Locale.ROOT))) continue;
			Node node = wrapper.nextSibling();
			while (node != null && !isH2(node)) {
				Node following = node.nextSibling();
				node.remove();
				node = following;
			}
			wrapper.remove();
		}
		// Chapters whose text all sits in subsections ("Drops" → "On death") have nothing to show.
		return WikiClient.splitSections(root, title.replace(' ', '_'), title, baseUrl, true).stream()
				.filter(s -> !s.text().isEmpty())
				.toList();
	}

	private static boolean isH2(Node node) {
		return node instanceof Element e && (e.normalName().equals("h2") || (e.normalName().equals("div") && e.hasClass("mw-heading2")));
	}

	/** A small least-recently-used cache whose entries expire. */
	private static final class TtlCache<V> {
		private record Entry<V>(long time, V value) {}

		private final int size;
		private final long ttlMillis;
		private final LinkedHashMap<String, Entry<V>> map;

		TtlCache(int size, Duration ttl) {
			this.size = size;
			this.ttlMillis = ttl.toMillis();
			this.map = new LinkedHashMap<>(16, 0.75f, true);
		}

		synchronized V get(String key) {
			Entry<V> entry = map.get(key);
			if (entry == null) return null;
			if (System.currentTimeMillis() - entry.time() > ttlMillis) {
				map.remove(key);
				return null;
			}
			return entry.value();
		}

		synchronized void put(String key, V value) {
			map.put(key, new Entry<>(System.currentTimeMillis(), value));
			while (map.size() > size) {
				map.remove(map.keySet().iterator().next());
			}
		}
	}
}

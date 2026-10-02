package eu.explorerseden.nicemerl;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

/** Fetches public Wiki.js pages and splits them into heading sections. */
public class WikiClient {
	private static final String PAGE_LIST_QUERY = "{ pages { list(limit: 5000) { path title locale isPublished } } }";
	private static final Set<String> HEADINGS = Set.of("h1", "h2", "h3");
	private static final String LINE_BLOCKS = "p, li, tr, div, dt, dd, blockquote, pre, details, h4, h5, h6";
	private static final int CONCURRENCY = 4;

	private final String wikiUrl;
	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(15))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	public WikiClient(String wikiUrl) {
		this.wikiUrl = wikiUrl;
	}

	private record Page(String path, String title) {}

	public List<Section> fetchSections() throws IOException, InterruptedException {
		List<Page> pages = fetchPageList();
		ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY, r -> {
			Thread t = new Thread(r, "NiceMerl Wiki Fetcher");
			t.setDaemon(true);
			return t;
		});
		try {
			List<CompletableFuture<List<Section>>> futures = new ArrayList<>();
			for (Page page : pages) {
				futures.add(CompletableFuture.supplyAsync(() -> fetchPage(page), pool));
			}
			List<Section> sections = new ArrayList<>();
			for (CompletableFuture<List<Section>> future : futures) {
				sections.addAll(future.join());
			}
			return sections;
		} finally {
			pool.shutdownNow();
		}
	}

	private List<Page> fetchPageList() throws IOException, InterruptedException {
		JsonObject body = new JsonObject();
		body.addProperty("query", PAGE_LIST_QUERY);
		HttpRequest request = request(URI.create(wikiUrl + "/graphql"))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
				.build();
		HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IOException("Page list request failed with HTTP " + response.statusCode());
		}
		JsonArray list = JsonParser.parseString(response.body()).getAsJsonObject()
				.getAsJsonObject("data").getAsJsonObject("pages").getAsJsonArray("list");
		List<Page> pages = new ArrayList<>();
		for (JsonElement element : list) {
			JsonObject page = element.getAsJsonObject();
			if (page.get("isPublished").getAsBoolean() && "en".equals(page.get("locale").getAsString())) {
				pages.add(new Page(page.get("path").getAsString(), page.get("title").getAsString()));
			}
		}
		return pages;
	}

	private List<Section> fetchPage(Page page) {
		try {
			HttpRequest request = request(URI.create(wikiUrl + "/" + page.path())).GET().build();
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				NiceMerl.LOGGER.warn("Failed to fetch {}: HTTP {}", page.path(), response.statusCode());
				return List.of();
			}
			Thread.sleep(100);
			return extractSections(response.body(), page.path(), page.title());
		} catch (IOException | IllegalArgumentException e) {
			NiceMerl.LOGGER.warn("Failed to fetch {}: {}", page.path(), e.getMessage());
			return List.of();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return List.of();
		}
	}

	private HttpRequest.Builder request(URI uri) {
		return HttpRequest.newBuilder(uri)
				.timeout(Duration.ofSeconds(30))
				.header("User-Agent", "NiceMerl-Mod/1.0");
	}

	static List<Section> extractSections(String html, String path, String pageTitle) {
		Document doc = Jsoup.parse(html);
		Element template = doc.selectFirst("template[slot=contents]");
		if (template == null) {
			return List.of();
		}
		Element content = Jsoup.parseBodyFragment(template.html()).body();

		content.select("style, script, a.toc-anchor").remove();
		for (Element details : content.select("details")) {
			details.select("summary").remove();
			details.prependText(" " + Section.SPOILER_START + " ");
			details.appendText(" " + Section.SPOILER_END + " ");
		}
		// Keep the page's line structure (paragraphs, list items, table rows).
		for (Element tr : content.select("tr")) {
			List<String> cells = new ArrayList<>();
			for (Element cell : tr.select("td, th")) {
				String text = cell.text().trim();
				if (!text.isEmpty()) {
					cells.add(text);
				}
			}
			tr.empty().appendText(String.join(" | ", cells));
		}
		for (Element li : content.select("li")) {
			li.prependText("• ");
		}
		for (Element block : content.select(LINE_BLOCKS)) {
			block.appendText("\n");
		}
		for (Element br : content.select("br")) {
			br.replaceWith(new TextNode("\n"));
		}

		List<Section> sections = new ArrayList<>();
		String[] current = {pageTitle, ""};
		StringBuilder text = new StringBuilder();
		Runnable flush = () -> {
			String cleaned = text.toString().replaceAll("[^\\S\\n]+", " ").replaceAll(" ?\\n\\s*", "\n").trim();
			if (!cleaned.isEmpty() || !current[1].isEmpty()) {
				sections.add(new Section(path, pageTitle, current[0], current[1], cleaned));
			}
			text.setLength(0);
		};

		NodeTraversor.traverse(new NodeVisitor() {
			@Override
			public void head(Node node, int depth) {
				if (node instanceof Element element && HEADINGS.contains(element.normalName())) {
					flush.run();
					current[0] = element.text().trim();
					current[1] = element.id();
				} else if (node instanceof TextNode textNode && !insideHeading(textNode)) {
					text.append(textNode.getWholeText());
				}
			}
		}, content);
		flush.run();
		return sections;
	}

	private static boolean insideHeading(Node node) {
		for (Node parent = node.parentNode(); parent != null; parent = parent.parentNode()) {
			if (parent instanceof Element element && HEADINGS.contains(element.normalName())) {
				return true;
			}
		}
		return false;
	}
}

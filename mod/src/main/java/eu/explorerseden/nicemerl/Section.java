package eu.explorerseden.nicemerl;

/**
 * One heading section of a wiki page. Text inside spoilers is wrapped in SPOILER_START/END markers.
 *
 * @param wiki base URL of the wiki the page belongs to
 * @param vanilla from a wiki searched live through MediaWiki (the Minecraft Wiki by default)
 *     instead of a downloaded Wiki.js wiki
 * @param meta the page's description and tags from the wiki, the same for every section of the page
 */
public record Section(String path, String pageTitle, String heading, String anchor, String text, String wiki, boolean vanilla,
		String meta) {
	public static final String SPOILER_START = "⟦";
	public static final String SPOILER_END = "⟧";

	public Section(String path, String pageTitle, String heading, String anchor, String text) {
		this(path, pageTitle, heading, anchor, text, "", false, "");
	}

	public Section(String path, String pageTitle, String heading, String anchor, String text, String wiki, boolean vanilla) {
		this(path, pageTitle, heading, anchor, text, wiki, vanilla, "");
	}

	public Section withMeta(String meta) {
		return new Section(path, pageTitle, heading, anchor, text, wiki, vanilla, meta);
	}
}

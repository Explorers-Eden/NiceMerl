package eu.explorerseden.nicemerl;

/**
 * One heading section of a wiki page. Text inside spoilers is wrapped in SPOILER_START/END markers.
 *
 * @param wiki base URL of the wiki the page belongs to
 * @param vanilla from a wiki searched live through MediaWiki (the Minecraft Wiki by default)
 *     instead of a downloaded Wiki.js wiki
 */
public record Section(String path, String pageTitle, String heading, String anchor, String text, String wiki, boolean vanilla) {
	public static final String SPOILER_START = "⟦";
	public static final String SPOILER_END = "⟧";

	public Section(String path, String pageTitle, String heading, String anchor, String text) {
		this(path, pageTitle, heading, anchor, text, "", false);
	}
}

package eu.explorerseden.nicemerl;

/** One heading section of a wiki page. Text inside spoilers is wrapped in SPOILER_START/END markers. */
public record Section(String path, String pageTitle, String heading, String anchor, String text) {
	public static final String SPOILER_START = "⟦";
	public static final String SPOILER_END = "⟧";
}

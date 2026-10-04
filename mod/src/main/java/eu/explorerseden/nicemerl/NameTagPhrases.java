package eu.explorerseden.nicemerl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The texts that Nice Name Tags reacts to ("Silent", "No Collision", …), read from its wiki page, so
 * "/merl name tag phrases" can list them for copying. No Minecraft classes, so it can be tested on its own.
 */
public final class NameTagPhrases {
	/** The wiki page and section that list them. */
	static final String PAGE = "nice_name_tags/home";
	static final String HEADING = "Features";
	private static final Pattern ACCEPTED = Pattern.compile("Accepted Texts?:\\s*(.+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");
	private static final Pattern NAME_TAG = Pattern.compile("\\bname ?tags?\\b");
	private static final Pattern ASKING_FOR_TEXTS = Pattern.compile(
			"\\b(phrases?|texts?|words?|list|options?|codes?|commands?|tricks?|effects?|what|which|all|available|supported|rename|renaming)\\b"
			+ "|\\bname ?tags? (to|for|that|which)\\b");
	/** "how do I get a name tag" is about the item, so it goes to the wiki. */
	private static final Pattern ITEM_QUESTION = Pattern.compile("\\b(get|craft|crafting|obtain|find|buy|trade|loot|drop|drops)\\b");
	private static final Set<String> GENERIC = Set.of("name", "tag", "tags", "nametag", "nametags", "phrase", "phrases", "text",
			"texts", "word", "words", "list", "option", "options", "code", "codes", "command", "commands", "trick", "tricks",
			"effect", "effects", "available", "supported", "rename", "renaming", "mob", "mobs", "entity", "entities", "nice");

	/** One effect: what it does, and the texts that trigger it (the first is the one to copy). */
	public record Effect(String description, List<String> texts) {
		/** One text per different word ("Silent" and "Mute", every villager type), without the spelling variants. */
		public List<String> distinct() {
			List<String> out = new ArrayList<>();
			Set<String> seen = new HashSet<>();
			for (String text : texts) {
				if (seen.add(text.toLowerCase(Locale.ROOT).replace(" ", ""))) out.add(text);
			}
			return out;
		}
	}

	private NameTagPhrases() {}

	public static boolean isQuestion(String message) {
		String text = message.toLowerCase(Locale.ROOT);
		return NAME_TAG.matcher(text).find() && ASKING_FOR_TEXTS.matcher(text).find() && !ITEM_QUESTION.matcher(text).find();
	}

	/** Mentions name tags without being about the item ("name tag villager type" counts when it names an effect). */
	public static boolean mentionsNameTag(String message) {
		String text = message.toLowerCase(Locale.ROOT);
		return NAME_TAG.matcher(text).find() && !ITEM_QUESTION.matcher(text).find();
	}

	/** True when the question names some of the effects, not all ("name tag to mute a mob"). */
	public static boolean specific(List<Effect> effects, String question) {
		return matching(effects, question).size() < effects.size();
	}

	/** The effects in the wiki section's text: a description line, then "Accepted Texts: "a", "b"". */
	public static List<Effect> parse(String section) {
		List<Effect> effects = new ArrayList<>();
		String description = "";
		for (String raw : section.split("\n")) {
			String line = raw.replace(Section.SPOILER_START, "").replace(Section.SPOILER_END, "").strip();
			if (line.isEmpty()) continue;
			Matcher accepted = ACCEPTED.matcher(line);
			if (accepted.find()) {
				List<String> texts = new ArrayList<>();
				Matcher quoted = QUOTED.matcher(accepted.group(1));
				while (quoted.find()) texts.add(quoted.group(1));
				if (!texts.isEmpty()) effects.add(new Effect(description, texts));
				description = "";
			} else {
				description = line;
			}
		}
		return effects;
	}

	/** The effects the question is about ("name tag to mute a mob"), or all of them. */
	public static List<Effect> matching(List<Effect> effects, String question) {
		Set<String> asked = new HashSet<>(SearchIndex.tokenize(question));
		asked.removeAll(GENERIC);
		if (asked.isEmpty()) return effects;
		List<Effect> found = new ArrayList<>();
		for (Effect effect : effects) {
			Set<String> words = new HashSet<>(SearchIndex.tokenize(effect.description() + " " + String.join(" ", effect.texts())));
			if (words.stream().anyMatch(asked::contains)) found.add(effect);
		}
		return found.isEmpty() ? effects : found;
	}
}

package eu.explorerseden.nicemerl;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

/** Reads the live data pack settings from command storage and searches them. */
public final class DatapackSettings {
	/** Words that mark a question as being about current settings (stemmed like the search). */
	private static final Set<String> INTENT = Set.copyOf(SearchIndex.tokenize("""
			setting settings config configs configured configuration option options enabled disabled
			current currently value values turned active activated status toggle toggled gamerule gamerules
			"""));

	/**
	 * @param label readable name, e.g. "Blaze › Spawn Chance" (English, for searching)
	 * @param keyWords the raw storage keys as words, e.g. "blaze spawn chance"
	 * @param value the raw stored value, e.g. "enabled" or "taglist"
	 * @param labelText the name shown in chat (translated on clients that have the pack's language files)
	 * @param valueText the value shown in chat
	 */
	public record Setting(String pack, String label, String keyWords, String value,
			MutableComponent labelText, MutableComponent valueText) {}

	private DatapackSettings() {}

	public static boolean isSettingsQuestion(String question) {
		for (String word : question.toLowerCase(Locale.ROOT).split("\\W+")) {
			if (word.equals("on") || word.equals("off")) return true;
		}
		for (String token : SearchIndex.tokenize(question)) {
			if (INTENT.contains(token)) return true;
		}
		return false;
	}

	/** Returns the settings that best match the question, strongest first. */
	public static List<Setting> search(MinecraftServer server, MerlConfig config, String question, int limit) {
		List<Setting> settings = read(server, config);
		if (settings.isEmpty()) return List.of();

		List<Section> docs = new ArrayList<>();
		for (int i = 0; i < settings.size(); i++) {
			Setting s = settings.get(i);
			// The anchor carries the index back from the search result.
			docs.add(new Section("", s.pack(), s.label(), Integer.toString(i), s.keyWords() + " " + s.value()));
		}
		// Only the subject of the question counts ("pvp", "grave type"), not words like
		// "enabled" or "settings" that would match every setting.
		List<String> subject = new ArrayList<>();
		for (String word : question.toLowerCase(Locale.ROOT).split("\\W+")) {
			List<String> tokens = SearchIndex.tokenize(word);
			if (!tokens.isEmpty() && !INTENT.contains(tokens.get(0))) {
				subject.add(word);
			}
		}
		if (subject.isEmpty()) return List.of();
		// Setting keys often glue words together ("bossbars"), so also try adjacent words joined.
		StringBuilder query = new StringBuilder(String.join(" ", subject));
		for (int i = 0; i + 1 < subject.size(); i++) {
			query.append(' ').append(subject.get(i)).append(subject.get(i + 1));
		}
		// Settings are a small collection where pack names repeat a lot, so any match counts.
		List<SearchIndex.Result> results = new SearchIndex(docs).search(query.toString(), limit, 0, 0, false);
		if (results.isEmpty()) return List.of();

		// Keep only settings that match about as well as the best one.
		double cutoff = results.get(0).score() * 0.5;
		List<Setting> matches = new ArrayList<>();
		for (SearchIndex.Result r : results) {
			if (r.score() < cutoff) break;
			matches.add(settings.get(Integer.parseInt(r.section().anchor())));
		}
		return matches;
	}

	static List<Setting> read(MinecraftServer server, MerlConfig config) {
		SettingLabels labels = NiceMerl.settingLabels();
		List<Setting> settings = new ArrayList<>();
		for (MerlConfig.SettingsSource source : config.settingsSources) {
			Identifier id = Identifier.tryParse(source.storage);
			if (id == null) {
				NiceMerl.LOGGER.warn("Invalid storage id in config: {}", source.storage);
				continue;
			}
			Optional<CompoundTag> root = Optional.of(server.getCommandStorage().get(id));
			for (String key : source.path.isEmpty() ? new String[0] : source.path.split("\\.")) {
				root = root.flatMap(tag -> tag.getCompound(key));
			}
			List<Setting> found = new ArrayList<>();
			Set<Setting> labelled = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
			Walk walk = new Walk(source.storage, source.name, config.settingsIgnoreKeys, labels, found, labelled);
			root.ifPresent(tag -> walk.flatten(tag, source.path, null, "", ""));
			// When a pack names its settings in a config dialog, everything else it stores is
			// internal bookkeeping ("type", "bodyicon", …), so only show the named ones.
			if (labelled.isEmpty()) {
				settings.addAll(found);
			} else {
				for (Setting setting : found) {
					if (labelled.contains(setting)) settings.add(setting);
				}
			}
		}
		return settings;
	}

	private record Walk(String storage, String pack, List<String> ignore, SettingLabels labels,
			List<Setting> out, Set<Setting> labelled) {
		/**
		 * @param path full storage path of {@code tag}
		 * @param group readable names of the compounds below the source, e.g. "Blaze", or null
		 */
		void flatten(CompoundTag tag, String path, MutableComponent group, String groupText, String groupKeys) {
			for (Map.Entry<String, Tag> entry : tag.entrySet()) {
				String key = entry.getKey();
				if (ignored(key, ignore)) continue;
				String full = path.isEmpty() ? key : path + "." + key;
				SettingLabels.Label known = labels.key(storage, full);
				String name = known != null ? known.fallback() : humanize(key);
				MutableComponent nameText = known != null ? known.component() : Component.literal(name);

				MutableComponent label = group == null ? nameText
						: group.copy().append(" › ").append(nameText);
				String labelString = groupText.isEmpty() ? name : groupText + " › " + name;
				String keyWords = (groupKeys + " " + key.replace('_', ' ')).trim();

				Tag value = entry.getValue();
				if (value instanceof CompoundTag compound) {
					flatten(compound, full, label, labelString, keyWords);
				} else if (value instanceof StringTag string) {
					SettingLabels.Label option = labels.value(storage, full, string.value());
					MutableComponent valueText = option != null ? option.component() : Component.literal(string.value());
					String searchValue = option != null ? string.value() + " " + option.fallback() : string.value();
					add(new Setting(pack, labelString, keyWords, searchValue, label, valueText), known != null);
				} else if (value instanceof NumericTag number) {
					String n = formatNumber(number.box());
					// Booleans are stored as 1b/0b but offered as "true"/"false" in the dialogs.
					String asBool = n.equals("1") ? "true" : n.equals("0") ? "false" : null;
					SettingLabels.Label option = asBool == null ? null : labels.value(storage, full, asBool);
					if (option != null) {
						add(new Setting(pack, labelString, keyWords, asBool + " " + option.fallback(), label,
								option.component()), known != null);
					} else {
						add(new Setting(pack, labelString, keyWords, n, label, Component.literal(n)), known != null);
					}
				}
				// Lists and arrays are internal data, not settings.
			}
		}

		private void add(Setting setting, boolean hasLabel) {
			out.add(setting);
			if (hasLabel) labelled.add(setting);
		}
	}

	private static boolean ignored(String key, List<String> patterns) {
		for (String pattern : patterns) {
			if (pattern.startsWith("*") ? key.endsWith(pattern.substring(1))
					: pattern.endsWith("*") ? key.startsWith(pattern.substring(0, pattern.length() - 1))
					: key.equals(pattern)) {
				return true;
			}
		}
		return false;
	}

	private static String humanize(String key) {
		String words = key.replace('_', ' ').trim();
		if (words.isEmpty()) return key;
		return words.substring(0, 1).toUpperCase(Locale.ROOT) + words.substring(1);
	}

	private static String formatNumber(Number number) {
		double d = number.doubleValue();
		if (d == Math.rint(d) && Math.abs(d) < 1e15) {
			return Long.toString((long) d);
		}
		return Double.toString(Math.round(d * 1000) / 1000.0);
	}
}

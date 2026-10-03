package eu.explorerseden.nicemerl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Readable names for data pack settings, taken from the packs' own config dialogs.
 *
 * <p>A config dialog is a function that runs {@code dialog show} and is called like
 * {@code function ns:dialog/dynamic/config with storage eden:settings keepinv}. Each of its
 * inputs has a {@code key} below that storage path, a label, and for choices the label of
 * every option, so "keepinv.equip_dmg" becomes "Equipment Damage" and "taglist" becomes "Tag List".
 */
public final class SettingLabels {
	public static final SettingLabels EMPTY = new SettingLabels(Map.of(), Map.of(), Set.of());

	private static final Pattern CALL = Pattern.compile(
			"function ([a-z0-9_.-]+:[a-z0-9_./-]+) with storage ([a-z0-9_.-]+:[a-z0-9_./-]+) ([A-Za-z0-9_.]+)");
	private static final Pattern MACRO = Pattern.compile("\\$\\([^)]*\\)");
	private static final Pattern CONTINUATION = Pattern.compile("\\\\\\s*\\r?\\n\\s*");

	public record Label(String translate, String fallback) {
		public MutableComponent component() {
			return translate == null
					? Component.literal(fallback)
					: Component.translatableWithFallback(translate, fallback);
		}
	}

	private final Map<String, Label> keys;
	private final Map<String, Label> values;
	private final Set<String> percent;

	private SettingLabels(Map<String, Label> keys, Map<String, Label> values, Set<String> percent) {
		this.keys = keys;
		this.values = values;
		this.percent = percent;
	}

	/** Label for the setting at {@code path} inside {@code storage}, or null. */
	public Label key(String storage, String path) {
		return keys.get(storage + " " + path);
	}

	/** Label for a choice value of that setting, or null. */
	public Label value(String storage, String path, String value) {
		return values.get(storage + " " + path + " " + value);
	}

	/** True when the dialog shows this setting as a percentage. */
	public boolean percent(String storage, String path) {
		return percent.contains(storage + " " + path);
	}

	public int size() {
		return keys.size();
	}

	public static SettingLabels scan(ResourceManager resources) {
		record Target(String storage, String path) {}
		Map<String, Set<Target>> calls = new HashMap<>();
		Map<String, String> dialogFunctions = new HashMap<>();

		Map<Identifier, Resource> files = new HashMap<>();
		files.putAll(resources.listResources("function", id -> id.getPath().endsWith(".mcfunction")));
		files.putAll(resources.listResources("dialog", id -> id.getPath().endsWith(".json")));

		for (Map.Entry<Identifier, Resource> file : files.entrySet()) {
			String text;
			try {
				text = read(file.getValue());
			} catch (IOException e) {
				continue;
			}
			Matcher m = CALL.matcher(text);
			while (m.find()) {
				calls.computeIfAbsent(m.group(1), k -> new HashSet<>()).add(new Target(m.group(2), m.group(3)));
			}
			Identifier id = file.getKey();
			if (id.getPath().startsWith("function/") && text.contains("dialog show")) {
				String path = id.getPath().substring("function/".length(), id.getPath().length() - ".mcfunction".length());
				dialogFunctions.put(id.getNamespace() + ":" + path, text);
			}
		}

		Map<String, Label> keys = new HashMap<>();
		Map<String, Label> values = new HashMap<>();
		Set<String> percent = new HashSet<>();
		for (Map.Entry<String, String> dialog : dialogFunctions.entrySet()) {
			Set<Target> targets = calls.get(dialog.getKey());
			if (targets == null) continue;
			for (JsonObject input : inputs(dialog.getValue())) {
				String key = input.get("key").getAsString();
				Label label = label(input.get("label"));
				for (Target t : targets) {
					String full = t.storage() + " " + t.path() + "." + key;
					if (label != null) keys.putIfAbsent(full, label);
					if (input.has("label_format") && input.get("label_format").toString().contains("percent")) {
						percent.add(full);
					}
					if (input.get("options") instanceof JsonArray options) {
						for (JsonElement option : options) {
							if (!(option instanceof JsonObject o) || !o.has("id")) continue;
							Label display = label(o.get("display"));
							if (display != null) values.putIfAbsent(full + " " + o.get("id").getAsString(), display);
						}
					}
				}
			}
		}
		return new SettingLabels(keys, values, percent);
	}

	/** All dialog inputs ({"key": …, "label": …}) found in the function's dialog show commands. */
	private static Iterable<JsonObject> inputs(String function) {
		String text = MACRO.matcher(CONTINUATION.matcher(function).replaceAll("")).replaceAll("0");
		java.util.List<JsonObject> found = new java.util.ArrayList<>();
		for (int at = text.indexOf("dialog show"); at >= 0; at = text.indexOf("dialog show", at + 1)) {
			int start = text.indexOf('{', at);
			if (start < 0) break;
			try {
				JsonReader reader = new JsonReader(new StringReader(text.substring(start)));
				reader.setStrictness(com.google.gson.Strictness.LENIENT);
				collectInputs(JsonParser.parseReader(reader), found);
			} catch (RuntimeException e) {
				// Not parseable as JSON, skip this dialog.
			}
		}
		return found;
	}

	private static void collectInputs(JsonElement element, java.util.List<JsonObject> out) {
		if (element instanceof JsonObject object) {
			for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
				if (entry.getKey().equals("inputs") && entry.getValue() instanceof JsonArray inputs) {
					for (JsonElement input : inputs) {
						if (input instanceof JsonObject o && o.has("key") && o.has("label")) out.add(o);
					}
				} else {
					collectInputs(entry.getValue(), out);
				}
			}
		} else if (element instanceof JsonArray array) {
			for (JsonElement child : array) collectInputs(child, out);
		}
	}

	private static Label label(JsonElement element) {
		if (element == null) return null;
		if (element.isJsonPrimitive()) return new Label(null, element.getAsString());
		if (element instanceof JsonObject o) {
			String translate = o.has("translate") ? o.get("translate").getAsString() : null;
			String fallback = o.has("fallback") ? o.get("fallback").getAsString()
					: o.has("text") ? o.get("text").getAsString() : null;
			if (fallback != null) return new Label(translate, fallback);
		}
		return null;
	}

	private static String read(Resource resource) throws IOException {
		try (BufferedReader reader = resource.openAsReader()) {
			StringBuilder sb = new StringBuilder();
			char[] buf = new char[8192];
			for (int n; (n = reader.read(buf)) > 0; ) sb.append(buf, 0, n);
			return sb.toString();
		}
	}
}

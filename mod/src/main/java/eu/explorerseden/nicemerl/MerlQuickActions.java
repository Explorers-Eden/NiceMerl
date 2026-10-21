package eu.explorerseden.nicemerl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

/**
 * A "Merl" button in the Nice Actions data pack's quick actions menu (the G key, dialog nice_actions:main),
 * added to the dialog's JSON while the server loads it, so the data pack itself stays as it is. Without Nice
 * Actions there's no such dialog and nothing happens. The button opens Merl's own menu: a general question, a
 * server setting, a biome, and quick actions that ask Merl what players ask most. Every button only runs
 * /merl with a question, so Merl answers exactly as if it was typed. Only features switched on when the
 * server starts are offered (dialogs are sent to players as loaded).
 */
public final class MerlQuickActions {
	private static final Identifier MENU = Identifier.fromNamespaceAndPath("nice_actions", "main");

	private MerlQuickActions() {}

	/** From the registry loader: the dialog's JSON, with Merl's button when it's the Nice Actions menu. */
	public static JsonElement patch(ResourceKey<?> key, JsonElement json) {
		if (!key.isFor(Registries.DIALOG) || !key.identifier().equals(MENU) || !(json instanceof JsonObject menu)
				|| !(menu.get("actions") instanceof JsonArray actions)) {
			return json;
		}
		try {
			MerlConfig config = NiceMerl.config();
			if (config == null || !config.niceActionsMenu) return json;
			JsonObject open = new JsonObject();
			open.addProperty("type", "show_dialog");
			open.add("dialog", menu(config));
			actions.add(button("Ask Merl", "Questions about " + config.communityName + " and Minecraft, and a few things Merl can do with you.", open));
			NiceMerl.LOGGER.info("Added Merl to the Nice Actions menu");
		} catch (RuntimeException e) {
			NiceMerl.LOGGER.warn("Couldn't add Merl to the Nice Actions menu: {}", e.toString());
		}
		return json;
	}

	private static JsonObject menu(MerlConfig config) {
		JsonArray buttons = new JsonArray();
		buttons.add(button("Ask a question", "Anything about " + config.communityName + " or vanilla Minecraft.",
				ask("Ask Merl", "Your question", "merl $(question)")));
		if (config.settingsGameRules || config.settingsServerRules || !config.settingsSources.isEmpty()) {
			buttons.add(button("Server settings", "Ask how something is set on this server, like keep inventory or the claim size.",
					ask("Server settings", "Which setting? (like keep inventory or mob spawning)", "merl what is the setting for $(question)")));
		}
		if (config.locateBiomes) {
			buttons.add(button("Find a biome", "The closest biome of a kind, with a trail there if you want.",
					ask("Find a biome", "Which biome? (like cherry grove)", "merl where is the closest $(question)")));
		}
		if (config.locateSlimeChunks) buttons.add(button("Nearest slime chunk", "Where the closest slime chunk is.", command("merl where is the closest slime chunk")));
		if (config.locateClaims) buttons.add(button("Nearest claim", "Your closest claim, with a trail there if you want.", command("merl where is the nearest claim")));
		if (config.locateWaypoints) buttons.add(button("Nearest waypoint hub", "The closest waypoint hub, with a trail there if you want.", command("merl where is the closest waypoint hub")));
		if (config.companion) {
			buttons.add(button("Stay with me", "Merl comes along and keeps you company.", command("merl can you stay with me")));
			buttons.add(button("You can go now", "Merl stops keeping you company.", command("merl you can go now")));
		}
		if (config.hideAndSeek) {
			buttons.add(button("Hide and seek", "Merl hides nearby and tells you if you're getting warmer.", command("merl lets play hide and seek")));
			buttons.add(button("I give up", "Merl comes out of hiding.", command("merl i give up")));
		}
		JsonObject dialog = new JsonObject();
		dialog.addProperty("type", "minecraft:multi_action");
		dialog.add("title", text("Merl"));
		dialog.add("body", message("Ask me anything, or pick one of these!"));
		dialog.addProperty("can_close_with_escape", true);
		dialog.addProperty("pause", false);
		dialog.addProperty("columns", 2);
		dialog.add("actions", buttons);
		dialog.add("exit_action", back());
		return dialog;
	}

	/** A dialog with one text box; its button runs the template with what was typed. */
	private static JsonObject ask(String title, String label, String template) {
		JsonObject input = new JsonObject();
		input.addProperty("type", "minecraft:text");
		input.addProperty("key", "question");
		input.add("label", text(label));
		input.addProperty("width", 300);
		input.addProperty("max_length", 200);
		JsonArray inputs = new JsonArray();
		inputs.add(input);

		JsonObject run = new JsonObject();
		run.addProperty("type", "dynamic/run_command");
		run.addProperty("template", template);
		JsonArray buttons = new JsonArray();
		buttons.add(button("Ask", "Merl answers in the chat.", run));

		JsonObject dialog = new JsonObject();
		dialog.addProperty("type", "minecraft:multi_action");
		dialog.add("title", text(title));
		dialog.add("inputs", inputs);
		dialog.addProperty("can_close_with_escape", true);
		dialog.addProperty("pause", false);
		dialog.addProperty("columns", 1);
		dialog.add("actions", buttons);
		dialog.add("exit_action", back());

		JsonObject show = new JsonObject();
		show.addProperty("type", "show_dialog");
		show.add("dialog", dialog);
		return show;
	}

	private static JsonObject command(String command) {
		JsonObject action = new JsonObject();
		action.addProperty("type", "run_command");
		action.addProperty("command", command);
		return action;
	}

	/** Back to the Nice Actions menu. */
	private static JsonObject back() {
		JsonObject action = new JsonObject();
		action.addProperty("type", "show_dialog");
		action.addProperty("dialog", MENU.toString());
		JsonObject button = new JsonObject();
		button.add("label", text("Back"));
		button.add("tooltip", text("Back to the actions menu"));
		button.add("action", action);
		return button;
	}

	private static JsonObject button(String label, String tooltip, JsonObject action) {
		JsonObject button = new JsonObject();
		button.add("label", text(label));
		button.add("tooltip", text(tooltip));
		button.add("action", action);
		return button;
	}

	private static JsonObject message(String s) {
		JsonObject body = new JsonObject();
		body.addProperty("type", "minecraft:plain_message");
		body.add("contents", text(s));
		return body;
	}

	private static JsonObject text(String s) {
		JsonObject t = new JsonObject();
		t.addProperty("text", s);
		return t;
	}
}

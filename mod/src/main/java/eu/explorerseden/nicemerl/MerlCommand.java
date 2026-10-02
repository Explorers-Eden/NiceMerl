package eu.explorerseden.nicemerl;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;

import me.lucko.fabric.api.permissions.v0.Permissions;

/**
 * /merl [question] for everyone, /nicemerl reindex for operators.
 * Replies are system messages sent only to whoever ran the command.
 *
 * <p>Permission nodes (LuckPerms or any other Fabric permissions mod):
 * <ul>
 *   <li>{@code nicemerl.command.merl}: use /merl (default: everyone)</li>
 *   <li>{@code nicemerl.command.reindex}: use /nicemerl reindex (default: operators, level 2)</li>
 *   <li>{@code nicemerl.bypass.cooldown}: skip the question cooldown (default: operators, level 2)</li>
 *   <li>{@code nicemerl.settings}: see current data pack settings in answers (default: everyone)</li>
 * </ul>
 */
public final class MerlCommand {
	public static final String PERMISSION_MERL = "nicemerl.command.merl";
	public static final String PERMISSION_REINDEX = "nicemerl.command.reindex";
	public static final String PERMISSION_BYPASS_COOLDOWN = "nicemerl.bypass.cooldown";
	public static final String PERMISSION_SETTINGS = "nicemerl.settings";

	private static final TextColor MERL_PINK = TextColor.fromRgb(0xF06EAA);
	private static final TextColor HIT_COLOR = TextColor.fromRgb(0xFFD966);

	private static final List<String> FOUND_LINES = List.of(
			"Ooh, I know where to look!",
			"Here's what I found in the wiki!",
			"Peanut Butter and I dug this up for you!",
			"Let's go exploring!",
			"Found it! I think…");

	private static final Map<UUID, Long> LAST_QUESTION = new ConcurrentHashMap<>();

	private MerlCommand() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("merl")
				.requires(Permissions.require(PERMISSION_MERL, true))
				.executes(MerlCommand::hello)
				.then(Commands.argument("question", StringArgumentType.greedyString())
						.executes(MerlCommand::ask)));

		dispatcher.register(Commands.literal("nicemerl")
				.requires(Permissions.require(PERMISSION_REINDEX, PermissionLevel.GAMEMASTERS))
				.then(Commands.literal("reindex").executes(MerlCommand::reindex)));
	}

	private static int hello(CommandContext<CommandSourceStack> ctx) {
		MerlConfig config = NiceMerl.config();
		reply(ctx.getSource(), Component.literal("Hi there! I'm NiceMerl. Ask me anything about " + config.communityName
				+ ", like ").append(Component.literal("/merl how do I get a boss key").withStyle(
						Style.EMPTY.withColor(HIT_COLOR)
								.withClickEvent(new ClickEvent.SuggestCommand("/merl how do I get a boss key"))))
				.append(Component.literal(", and I'll find the right wiki page for you!")));
		return 1;
	}

	private static int ask(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		MerlConfig config = NiceMerl.config();
		String question = StringArgumentType.getString(ctx, "question");

		ServerPlayer player = source.getPlayer();
		if (player != null && config.cooldownSeconds > 0
				&& !Permissions.check(source, PERMISSION_BYPASS_COOLDOWN, PermissionLevel.GAMEMASTERS)) {
			long now = System.currentTimeMillis();
			Long last = LAST_QUESTION.get(player.getUUID());
			if (last != null && now - last < config.cooldownSeconds * 1000L) {
				reply(source, Component.literal("One question at a time, please! Give me a few seconds.")
						.withStyle(ChatFormatting.GRAY));
				return 0;
			}
			LAST_QUESTION.put(player.getUUID(), now);
		}

		if (SearchIndex.tokenize(question).isEmpty()) {
			return hello(ctx);
		}

		List<DatapackSettings.Setting> settings = List.of();
		if (DatapackSettings.isSettingsQuestion(question) && Permissions.check(source, PERMISSION_SETTINGS, true)) {
			settings = DatapackSettings.search(source.getServer(), config, question, config.settingsResults);
		}

		SearchIndex index = NiceMerl.index();
		List<SearchIndex.Result> results = List.of();
		if (index != null) {
			// Keep the reply compact when settings are listed too.
			int limit = settings.isEmpty() ? config.results : Math.min(2, config.results);
			results = index.search(question, limit, config.excerptLength);
		} else if (settings.isEmpty()) {
			reply(source, Component.literal("I'm still reading the wiki. Ask me again in a moment!")
					.withStyle(ChatFormatting.GRAY));
			return 0;
		}

		if (settings.isEmpty() && results.isEmpty()) {
			reply(source, Component.literal(randomNotFound(config)));
			return 0;
		}

		MutableComponent message;
		if (!settings.isEmpty()) {
			message = Component.literal("Here's how things are set up right now:");
			for (DatapackSettings.Setting setting : settings) {
				message.append(Component.literal("\n"));
				message.append(formatSetting(setting));
			}
			if (!results.isEmpty()) {
				message.append(Component.literal("\nMore in the wiki:").withStyle(ChatFormatting.GRAY));
			}
		} else {
			message = Component.literal(random(FOUND_LINES));
		}
		for (SearchIndex.Result result : results) {
			message.append(Component.literal("\n"));
			message.append(formatResult(result, config.wikiUrl));
		}
		reply(source, message);
		return settings.size() + results.size();
	}

	private static MutableComponent formatSetting(DatapackSettings.Setting setting) {
		String raw = setting.value().split(" ")[0].toLowerCase(java.util.Locale.ROOT);
		TextColor color = switch (raw) {
			case "enabled", "true", "on" -> TextColor.fromLegacyFormat(ChatFormatting.GREEN);
			case "disabled", "false", "off" -> TextColor.fromLegacyFormat(ChatFormatting.RED);
			default -> HIT_COLOR;
		};
		return Component.literal(" ⚙ ").withStyle(Style.EMPTY.withColor(MERL_PINK))
				.append(Component.literal(setting.pack() + " › ").withStyle(ChatFormatting.GRAY))
				.append(setting.labelText().withStyle(ChatFormatting.WHITE))
				.append(Component.literal(": ").withStyle(ChatFormatting.WHITE))
				.append(setting.valueText().withStyle(Style.EMPTY.withColor(color)));
	}

	private static int reindex(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		reply(source, Component.literal("Re-reading the wiki…").withStyle(ChatFormatting.GRAY));
		NiceMerl.reindexAsync(ok -> source.getServer().execute(() -> {
			SearchIndex index = NiceMerl.index();
			reply(source, Component.literal(ok && index != null
					? "Reindexed " + index.pageCount() + " pages."
					: "Reindex failed, check the server log."));
		}));
		return 1;
	}

	private static MutableComponent formatResult(SearchIndex.Result result, String wikiUrl) {
		Section s = result.section();
		String url = wikiUrl + "/" + s.path() + (s.anchor().isEmpty() ? "" : "#" + s.anchor());
		String title = s.heading().isEmpty() || s.heading().equals(s.pageTitle())
				? s.pageTitle()
				: s.pageTitle() + " › " + s.heading();

		MutableComponent line = Component.literal(" ▸ ").withStyle(Style.EMPTY.withColor(MERL_PINK));
		line.append(Component.literal(title).withStyle(Style.EMPTY
				.withColor(ChatFormatting.AQUA)
				.withUnderlined(true)
				.withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("Open in the wiki\n")
						.append(Component.literal(url).withStyle(ChatFormatting.GRAY))))));
		line.append(Component.literal(" " + projectName(s.path())).withStyle(ChatFormatting.DARK_GRAY));
		line.append(Component.literal("\n   "));
		line.append(formatExcerpt(result.excerpt()));
		return line;
	}

	/** Query hits are highlighted, spoiler text is obfuscated and revealed on hover. */
	private static MutableComponent formatExcerpt(Excerpt excerpt) {
		MutableComponent out = Component.empty().withStyle(ChatFormatting.GRAY);
		if (excerpt.cutStart()) {
			out.append("…");
		}
		List<Excerpt.Word> words = excerpt.words();
		for (int i = 0; i < words.size(); ) {
			Excerpt.Word word = words.get(i);
			if (word.spoiler()) {
				StringBuilder hidden = new StringBuilder();
				while (i < words.size() && words.get(i).spoiler()) {
					if (!hidden.isEmpty()) hidden.append(' ');
					hidden.append(words.get(i).text());
					i++;
				}
				out.append(Component.literal(hidden.toString()).withStyle(Style.EMPTY
						.withObfuscated(true)
						.withColor(ChatFormatting.DARK_GRAY)
						.withHoverEvent(new HoverEvent.ShowText(Component.literal("Spoiler: ")
								.withStyle(ChatFormatting.GRAY)
								.append(Component.literal(hidden.toString()).withStyle(ChatFormatting.WHITE))))));
			} else {
				out.append(Component.literal(word.text()).withStyle(word.hit()
						? Style.EMPTY.withColor(HIT_COLOR)
						: Style.EMPTY));
				i++;
			}
			if (i < words.size()) {
				out.append(" ");
			}
		}
		if (excerpt.cutEnd()) {
			out.append("…");
		}
		return out;
	}

	private static void reply(CommandSourceStack source, Component body) {
		MutableComponent message = Component.literal("[NiceMerl] ").withStyle(Style.EMPTY.withColor(MERL_PINK).withBold(true));
		message.append(Component.empty().withStyle(Style.EMPTY.withBold(false).withColor(ChatFormatting.WHITE)).append(body));
		source.sendSystemMessage(message);
	}

	private static String projectName(String path) {
		String segment = path.split("/")[0];
		if (segment.equals("home")) {
			return "(Wiki)";
		}
		StringBuilder name = new StringBuilder();
		for (String part : segment.split("_")) {
			if (part.isEmpty()) continue;
			if (!name.isEmpty()) name.append(' ');
			name.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
		}
		return "(" + name + ")";
	}

	// Homage to the real Merl support agent's famous non-answers.
	private static String randomNotFound(MerlConfig config) {
		return random(List.of(
				"I don't know.",
				"I don't know the answer to that. Can I help you with a question related to " + config.communityName + "?",
				"I don't know how to help with that. Can I assist you with a question related to " + config.communityName + "?"));
	}

	private static String random(List<String> lines) {
		return lines.get(ThreadLocalRandom.current().nextInt(lines.size()));
	}
}

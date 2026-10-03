package eu.explorerseden.nicemerl;

import java.net.URI;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.level.Level;

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

	private static final TextColor VANILLA_GREEN = TextColor.fromRgb(0x7BC96F);
	/** Roughly one answer in this many mentions where the player is (Nether, night, low health…). */
	private static final int CONTEXT_CHANCE = 4;

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
		CommandSourceStack source = ctx.getSource();
		String greeting = MerlLines.pick(MerlLines.greetingPool(LocalTime.now()), "user", source.getTextName());
		MutableComponent message = Component.literal(greeting + " I'm NiceMerl! Ask me anything about " + config.communityName
				+ ", like ").append(example("/merl how do I get a boss key"));
		if (NiceMerl.mediaWikis().stream().anyMatch(w -> w.baseUrl().contains("minecraft.wiki"))) {
			message.append(Component.literal(", or about vanilla Minecraft, like ")).append(example("/merl how do I make a nether portal"));
		}
		reply(source, message.append(Component.literal(", and I'll find the right wiki page for you!")));
		return 1;
	}

	private static MutableComponent example(String command) {
		return Component.literal(command).withStyle(Style.EMPTY.withColor(HIT_COLOR)
				.withClickEvent(new ClickEvent.SuggestCommand(command)));
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
				reply(source, Component.literal(MerlLines.pick("cooldown")).withStyle(ChatFormatting.GRAY));
				return 0;
			}
			LAST_QUESTION.put(player.getUUID(), now);
		}

		String talk = MerlLines.smallTalk(question);
		if ("greeting".equals(talk) || (talk == null && SearchIndex.tokenize(question).isEmpty())) {
			return hello(ctx);
		}
		if (talk != null) {
			reply(source, Component.literal(MerlLines.pick(talk, "user", source.getTextName(), "community", config.communityName)));
			return 1;
		}

		List<DatapackSettings.Setting> settings = List.of();
		if (DatapackSettings.isSettingsQuestion(question) && Permissions.check(source, PERMISSION_SETTINGS, true)) {
			settings = DatapackSettings.search(source.getServer(), config, question, config.settingsResults);
		}

		SearchIndex index = NiceMerl.index();
		if (index == null && settings.isEmpty()) {
			reply(source, Component.literal(MerlLines.pick("still_reading")).withStyle(ChatFormatting.GRAY));
			return 0;
		}
		// Keep the reply compact when settings are listed too.
		int limit = settings.isEmpty() ? config.results : Math.min(2, config.results);
		SearchIndex.Outcome outcome = index != null
				? index.find(question, limit, config.excerptLength)
				: new SearchIndex.Outcome(List.of(), Map.of(), false);

		List<VanillaWiki> live = NiceMerl.mediaWikis();
		// Settings questions are about this server, so they skip the Minecraft Wiki.
		if (live.isEmpty() || !settings.isEmpty()) {
			return respond(source, settings, outcome, outcome.results());
		}

		VanillaWiki.Mode mode = VanillaWiki.plan(question, outcome);
		if (mode == VanillaWiki.Mode.SEARCH) {
			reply(source, Component.literal(MerlLines.pick("checking_vanilla")).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
		}
		MinecraftServer server = source.getServer();
		NiceMerl.lookupAsync(
				() -> VanillaWiki.searchAll(live, question, config.mediaWikiResults, mode == VanillaWiki.Mode.CHECK),
				answer -> server.execute(() -> respond(source, List.of(), outcome,
						VanillaWiki.combine(question, outcome, answer, config.results))));
		return 1;
	}

	private static int respond(CommandSourceStack source, List<DatapackSettings.Setting> settings,
			SearchIndex.Outcome outcome, List<SearchIndex.Result> results) {
		MerlConfig config = NiceMerl.config();
		if (settings.isEmpty() && results.isEmpty()) {
			reply(source, Component.literal(MerlLines.pick("not_found", "community", config.communityName)));
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
			message = Component.literal(headline(outcome, results));
		}
		for (SearchIndex.Result result : results) {
			message.append(Component.literal("\n"));
			message.append(formatResult(result, config));
		}
		String extra = contextLine(source.getPlayer());
		if (extra == null) extra = MerlLines.aside();
		if (extra != null) {
			message.append(Component.literal("\n" + extra).withStyle(Style.EMPTY.withColor(MERL_PINK).withItalic(true)));
		}
		reply(source, message);
		return settings.size() + results.size();
	}

	private static String headline(SearchIndex.Outcome outcome, List<SearchIndex.Result> results) {
		boolean hasEden = results.stream().anyMatch(r -> !r.section().vanilla());
		boolean hasVanilla = results.stream().anyMatch(r -> r.section().vanilla());
		if (hasEden && !outcome.corrections().isEmpty() && !results.get(0).section().vanilla()) {
			return MerlLines.pick("found_typo", "term", String.join(", ", new LinkedHashSet<>(outcome.corrections().values())));
		}
		if (hasEden && hasVanilla) {
			return MerlLines.pick("found_both", "community", NiceMerl.config().communityName);
		}
		return MerlLines.pick(hasVanilla ? "found_vanilla" : "found");
	}

	/** Now and then, a word about where the player is or how they're doing. */
	private static String contextLine(ServerPlayer player) {
		if (player == null || ThreadLocalRandom.current().nextInt(CONTEXT_CHANCE) != 0) return null;
		ServerLevel level = player.level();
		List<String> pools = new ArrayList<>();
		if (player.getHealth() <= player.getMaxHealth() * 0.3f) pools.add("context_hurt");
		if (level.dimension() == Level.NETHER) pools.add("context_nether");
		else if (level.dimension() == Level.END) pools.add("context_end");
		else if (level.isRaining()) pools.add("context_rain");
		else if (level.isDarkOutside()) pools.add("context_night");
		return pools.isEmpty() ? null : MerlLines.pick(pools.get(0));
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
		reply(source, Component.literal(MerlLines.pick("reindex_start")).withStyle(ChatFormatting.GRAY));
		NiceMerl.reindexAsync(ok -> source.getServer().execute(() -> {
			SearchIndex index = NiceMerl.index();
			reply(source, Component.literal(ok && index != null
					? MerlLines.pick("reindex_done", "pages", Integer.toString(index.pageCount()))
					: MerlLines.pick("reindex_failed")));
		}));
		return 1;
	}

	private static MutableComponent formatResult(SearchIndex.Result result, MerlConfig config) {
		Section s = result.section();
		VanillaWiki live = NiceMerl.mediaWikis().stream()
				.filter(w -> w.baseUrl().equals(s.wiki())).findFirst().orElse(null);
		String url = live != null
				? live.url(s)
				: s.wiki() + "/" + s.path() + (s.anchor().isEmpty() ? "" : "#" + s.anchor());
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
		MerlConfig.WikiSource wiki = config.wiki(s.wiki());
		String wikiName = wiki != null ? wiki.name : s.wiki();
		if (s.vanilla()) {
			line.append(Component.literal(" (" + wikiName + ")").withStyle(Style.EMPTY.withColor(VANILLA_GREEN)));
		} else {
			// With several Wiki.js wikis, say which one the page is from.
			String prefix = config.wikiJsWikis().size() > 1 ? wikiName + " › " : "";
			line.append(Component.literal(" (" + prefix + projectName(s.path()) + ")").withStyle(ChatFormatting.DARK_GRAY));
		}
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
			return "Wiki";
		}
		StringBuilder name = new StringBuilder();
		for (String part : segment.split("_")) {
			if (part.isEmpty()) continue;
			if (!name.isEmpty()) name.append(' ');
			name.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
		}
		return name.toString();
	}
}

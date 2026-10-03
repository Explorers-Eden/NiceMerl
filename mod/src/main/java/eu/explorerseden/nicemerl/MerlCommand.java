package eu.explorerseden.nicemerl;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.stats.Stats;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;

import me.lucko.fabric.api.permissions.v0.Permissions;

/**
 * /merl [question] for everyone; /nicemerl comments|celebrate [on|off] for players, /nicemerl reindex
 * for operators. Replies are system messages sent only to whoever ran the command.
 *
 * <p>Permission nodes (LuckPerms or any other Fabric permissions mod):
 * <ul>
 *   <li>{@code nicemerl.command.merl}: use /merl (default: everyone)</li>
 *   <li>{@code nicemerl.command.toggle}: use /nicemerl comments and /nicemerl celebrate (default: everyone)</li>
 *   <li>{@code nicemerl.command.reindex}: use /nicemerl reindex (default: operators, level 2)</li>
 *   <li>{@code nicemerl.bypass.cooldown}: skip the question cooldown (default: operators, level 2)</li>
 *   <li>{@code nicemerl.settings}: see current data pack settings in answers (default: everyone)</li>
 * </ul>
 */
public final class MerlCommand {
	public static final String PERMISSION_MERL = "nicemerl.command.merl";
	public static final String PERMISSION_TOGGLE = "nicemerl.command.toggle";
	public static final String PERMISSION_REINDEX = "nicemerl.command.reindex";
	public static final String PERMISSION_BYPASS_COOLDOWN = "nicemerl.bypass.cooldown";
	public static final String PERMISSION_SETTINGS = "nicemerl.settings";

	private static final TextColor MERL_PINK = TextColor.fromRgb(0xF06EAA);
	private static final TextColor HIT_COLOR = TextColor.fromRgb(0xFFD966);

	private static final TextColor VANILLA_GREEN = TextColor.fromRgb(0x7BC96F);
	/** Roughly one answer in this many comes with a word about where the player is or what they're doing. */
	private static final int CONTEXT_CHANCE = 4;
	/** "What should I do next" answers from the player's progress, except one time in this many. */
	private static final int RANDOM_IDEA_CHANCE = 3;
	private static final int MANY_DEATHS = 25;
	private static final int VETERAN_HOURS = 100;
	private static final long CELEBRATE_COOLDOWN_MS = 2 * 60_000L;
	private static final Map<Item, String> HELD_ITEMS = Map.ofEntries(
			Map.entry(Items.MACE, "holding_mace"), Map.entry(Items.TRIDENT, "holding_trident"),
			Map.entry(Items.TOTEM_OF_UNDYING, "holding_totem"), Map.entry(Items.NETHERITE_SWORD, "holding_netherite"),
			Map.entry(Items.NETHERITE_PICKAXE, "holding_netherite"), Map.entry(Items.NETHERITE_AXE, "holding_netherite"),
			Map.entry(Items.FISHING_ROD, "holding_fishing_rod"), Map.entry(Items.FILLED_MAP, "holding_map"),
			Map.entry(Items.COMPASS, "holding_compass"), Map.entry(Items.SPYGLASS, "holding_spyglass"),
			Map.entry(Items.BRUSH, "holding_brush"));
	private static final Set<ResourceKey<Biome>> SNOWY = Set.of(Biomes.SNOWY_PLAINS, Biomes.ICE_SPIKES, Biomes.SNOWY_TAIGA,
			Biomes.SNOWY_SLOPES, Biomes.FROZEN_PEAKS, Biomes.JAGGED_PEAKS, Biomes.GROVE);

	private static final Map<UUID, Long> LAST_CELEBRATION = new ConcurrentHashMap<>();
	/** Stands in for the advancement title in a celebration line until the title component goes in. */
	private static final String TITLE_MARK = "\uE000";

	/** What the reply builder needs to know about a question. */
	private record Ask(String prefix, boolean repeat, String energy, MerlMemory.Visit visit, long now) {}

	private MerlCommand() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("merl")
				.requires(Permissions.require(PERMISSION_MERL, true))
				.executes(MerlCommand::hello)
				.then(Commands.argument("question", StringArgumentType.greedyString())
						.executes(MerlCommand::ask)));

		dispatcher.register(Commands.literal("nicemerl")
				.requires(source -> Permissions.check(source, PERMISSION_TOGGLE, true)
						|| Permissions.check(source, PERMISSION_REINDEX, PermissionLevel.GAMEMASTERS))
				.then(Commands.literal("reindex")
						.requires(Permissions.require(PERMISSION_REINDEX, PermissionLevel.GAMEMASTERS))
						.executes(MerlCommand::reindex))
				.then(toggle("comments"))
				.then(toggle("celebrate")));
	}

	/** /nicemerl comments|celebrate [on|off]; without on/off it flips the setting. */
	private static LiteralArgumentBuilder<CommandSourceStack> toggle(String setting) {
		return Commands.literal(setting)
				.requires(Permissions.require(PERMISSION_TOGGLE, true))
				.executes(ctx -> toggle(ctx, setting, null))
				.then(Commands.literal("on").executes(ctx -> toggle(ctx, setting, true)))
				.then(Commands.literal("off").executes(ctx -> toggle(ctx, setting, false)));
	}

	private static int toggle(CommandContext<CommandSourceStack> ctx, String setting, Boolean value) {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			reply(source, Component.literal("Only players can change this.").withStyle(ChatFormatting.GRAY));
			return 0;
		}
		boolean comments = setting.equals("comments");
		boolean[] on = new boolean[1];
		MerlState.update(player.getUUID(), p -> {
			boolean current = comments ? p.comments : p.celebrate;
			on[0] = value != null ? value : !current;
			if (comments) p.comments = on[0];
			else p.celebrate = on[0];
		});
		MerlConfig config = NiceMerl.config();
		String text = comments
				? (on[0] ? "Yay! I'll chat about what you're up to again." : "Okay! I'll keep my comments to myself.")
				: (on[0] ? "Yay! I'll cheer when you get big advancements." : "Okay! I'll cheer for you quietly.");
		MutableComponent message = Component.literal(text);
		if (on[0] && !(comments ? config.playerComments : config.celebrate)) {
			message.append(Component.literal(" (It's turned off on this server right now, though.)").withStyle(ChatFormatting.GRAY));
		}
		reply(source, message);
		return 1;
	}

	private static int hello(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = source.getPlayer();
		long now = System.currentTimeMillis();
		MerlMemory.Visit visit = player != null ? MerlMemory.visit(player.getUUID()) : new MerlMemory.Visit();
		boolean returning = visit.returning(now);
		visit.seenAt = now;
		return hello(source, visit, returning, now);
	}

	private static int hello(CommandSourceStack source, MerlMemory.Visit visit, boolean returning, long now) {
		MerlConfig config = NiceMerl.config();
		String pool = returning ? "welcome_back" : MerlLines.greetingPool(LocalTime.now());
		String greeting = MerlLines.pick(pool, "user", source.getTextName());
		MutableComponent message = Component.literal(greeting + " I'm NiceMerl! Ask me anything about " + config.communityName
				+ ", like ").append(example("/merl how do I get a boss key"));
		if (NiceMerl.mediaWikis().stream().anyMatch(w -> w.baseUrl().contains("minecraft.wiki"))) {
			message.append(Component.literal(", or about vanilla Minecraft, like ")).append(example("/merl how do I make a nether portal"));
		}
		message.append(Component.literal(", and I'll find the right wiki page for you!"));
		String askBack = askBack("greeting", visit, now);
		if (askBack == null) askBack = askFeeling("greeting", greeting, visit, now);
		if (askBack != null) message.append(Component.literal(" " + askBack));
		reply(source, message);
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
		long now = System.currentTimeMillis();
		MerlMemory.Visit visit = player != null ? MerlMemory.visit(player.getUUID()) : new MerlMemory.Visit();

		if (player != null && config.cooldownSeconds > 0
				&& !Permissions.check(source, PERMISSION_BYPASS_COOLDOWN, PermissionLevel.GAMEMASTERS)) {
			if (now - visit.lastMessageAt < config.cooldownSeconds * 1000L) {
				reply(source, Component.literal(MerlLines.pick("cooldown")).withStyle(ChatFormatting.GRAY));
				return 0;
			}
			visit.lastMessageAt = now;
		}
		boolean returning = visit.returning(now);
		visit.seenAt = now;

		String talk = MerlLines.smallTalk(question);
		// Merl only waits one message for an answer to "what are you up to?".
		boolean awaiting = visit.awaitingReply(now);
		visit.askedBackAt = 0;
		// The same for "how are you?": "good, you?" is an answer, not a compliment.
		boolean awaitingFeeling = visit.awaitingFeeling(now);
		visit.askedFeelingAt = 0;
		MerlLines.Feeling felt = awaitingFeeling ? MerlLines.feeling(question) : null;
		if (felt != null) {
			String text = MerlLines.pick(felt.pool(), "user", source.getTextName());
			String extra = felt.askedBack()
					? MerlLines.moody("about_me", LocalDate.now(), "user", source.getTextName())
					: askBack(felt.pool(), visit, now);
			reply(source, Component.literal(extra != null ? text + " " + extra : text));
			return 1;
		}
		if (talk == null && awaiting) {
			String topic = MerlLines.topic(question);
			if (topic != null) {
				reply(source, Component.literal(MerlLines.pick("reply_" + topic)));
				return 1;
			}
		}

		if ("greeting".equals(talk) || (talk == null && SearchIndex.tokenize(question).isEmpty())) {
			return hello(source, visit, returning, now);
		}
		if (talk != null) {
			String text = smallTalkLine(talk, source, player, visit, now);
			String askBack = askBack(talk, visit, now);
			if (askBack == null) askBack = askFeeling(talk, text, visit, now);
			reply(source, Component.literal(askBack != null ? text + " " + askBack : text));
			return 1;
		}

		MerlLines.Split split = MerlLines.splitSmallTalk(question);
		String search = split.rest();
		Ask ask = new Ask(split.prefix(), false, MerlLines.energy(question), visit, now);

		List<DatapackSettings.Setting> settings = List.of();
		if (DatapackSettings.isSettingsQuestion(search) && Permissions.check(source, PERMISSION_SETTINGS, true)) {
			settings = DatapackSettings.search(source.getServer(), config, search, config.settingsResults);
		}

		SearchIndex index = NiceMerl.index();
		if (index == null && settings.isEmpty()) {
			reply(source, Component.literal(MerlLines.pick("still_reading")).withStyle(ChatFormatting.GRAY));
			return 0;
		}
		// Keep the reply compact when settings are listed too.
		int limit = settings.isEmpty() ? config.results : Math.min(2, config.results);
		SearchIndex.Outcome outcome = index != null
				? index.find(search, limit, config.excerptLength)
				: new SearchIndex.Outcome(List.of(), Map.of(), false);
		// "and in the nether?" right after a question: if it finds nothing good on its own,
		// search it together with the previous question.
		String previous = visit.recentQuestion(now);
		boolean weak = outcome.results().isEmpty() || outcome.confidence() < SearchIndex.SURE_TITLE_SCORE;
		if (index != null && weak && previous != null && MerlLines.isFollowUp(search)) {
			SearchIndex.Outcome combined = index.find(previous + " " + search, limit, config.excerptLength);
			if (combined.confidence() >= outcome.confidence()) {
				search = previous + " " + search;
				outcome = combined;
			}
		}
		String asked = String.join(" ", SearchIndex.tokenize(search));
		ask = new Ask(ask.prefix(), visit.isRepeat(asked, now), ask.energy(), visit, now);
		visit.question = asked;
		visit.askedAt = now;

		List<VanillaWiki> live = NiceMerl.mediaWikis();
		// Settings questions are about this server, so they skip the Minecraft Wiki.
		if (live.isEmpty() || !settings.isEmpty()) {
			return respond(source, settings, outcome, outcome.results(), ask);
		}

		String query = search;
		SearchIndex.Outcome eden = outcome;
		Ask answer = ask;
		VanillaWiki.Mode mode = VanillaWiki.plan(query, eden);
		if (mode == VanillaWiki.Mode.SEARCH) {
			reply(source, Component.literal(MerlLines.pick("checking_vanilla")).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
		}
		MinecraftServer server = source.getServer();
		NiceMerl.lookupAsync(
				() -> VanillaWiki.searchAll(live, query, config.mediaWikiResults, mode == VanillaWiki.Mode.CHECK),
				found -> server.execute(() -> respond(source, List.of(), eden,
						VanillaWiki.combine(query, eden, found, config.results), answer)));
		return 1;
	}

	private static String smallTalkLine(String talk, CommandSourceStack source, ServerPlayer player, MerlMemory.Visit visit, long now) {
		String page = visit.recentPage(now);
		if (talk.equals("thanks") && page != null) {
			return MerlLines.pick("thanks_answered", "page", page);
		}
		if (talk.equals("peanut_butter")) {
			return MerlLines.peanutButter(LocalDate.now());
		}
		if (talk.equals("pet_pb")) {
			long count = MerlState.pet();
			return MerlLines.petMilestone(count)
					? MerlLines.pick("pet_pb_milestone", "count", String.format("%,d", count))
					: MerlLines.pick("pet_pb");
		}
		if (talk.equals("idea") && player != null && !MerlLines.chance(RANDOM_IDEA_CHANCE)) {
			String idea = MerlLines.progressIdea(id -> isDone(player, id));
			if (idea != null) return idea;
		}
		return MerlLines.moody(talk, LocalDate.now(), "user", source.getTextName(), "community", NiceMerl.config().communityName);
	}

	/** Whether the player has this advancement; null when the server doesn't have it. */
	private static Boolean isDone(ServerPlayer player, String id) {
		Identifier key = Identifier.tryParse(id);
		AdvancementHolder holder = key != null ? player.level().getServer().getAdvancements().get(key) : null;
		return holder == null ? null : player.getAdvancements().getOrStartProgress(holder).isDone();
	}

	private static String askBack(String talk, MerlMemory.Visit visit, long now) {
		String line = MerlLines.askBack(talk);
		if (line != null) visit.askedBackAt = now;
		return line;
	}

	/** Sometimes asks how they are. After "how are you?" Merl listens for "good, you?" either way. */
	private static String askFeeling(String talk, String said, MerlMemory.Visit visit, long now) {
		String line = MerlLines.askFeeling(talk, said);
		if (line != null || talk.equals("how_are_you")) visit.askedFeelingAt = now;
		return line;
	}

	private static int respond(CommandSourceStack source, List<DatapackSettings.Setting> settings,
			SearchIndex.Outcome outcome, List<SearchIndex.Result> results, Ask ask) {
		MerlConfig config = NiceMerl.config();
		if (settings.isEmpty() && results.isEmpty()) {
			ask.visit().page = "";
			reply(source, Component.literal(MerlLines.pick("not_found", "community", config.communityName)));
			return 0;
		}
		if (!results.isEmpty()) {
			ask.visit().page = results.get(0).section().pageTitle();
			ask.visit().answeredAt = ask.now();
		}

		MutableComponent message;
		if (!settings.isEmpty()) {
			String prefix = ask.prefix() != null ? MerlLines.pick(ask.prefix(), "user", source.getTextName()) + " " : "";
			message = Component.literal(prefix + "Here's how things are set up right now:");
			for (DatapackSettings.Setting setting : settings) {
				message.append(Component.literal("\n"));
				message.append(formatSetting(setting));
			}
			if (!results.isEmpty()) {
				message.append(Component.literal("\nMore in the wiki:").withStyle(ChatFormatting.GRAY));
			}
		} else {
			message = Component.literal(headline(outcome, results, ask, source.getTextName()));
		}
		for (SearchIndex.Result result : results) {
			message.append(Component.literal("\n"));
			message.append(formatResult(result, config));
		}
		String extra = contextLine(source.getPlayer());
		if (extra == null) extra = MerlLines.aside(LocalDateTime.now(), ask.energy());
		if (extra != null) {
			message.append(Component.literal("\n" + extra).withStyle(Style.EMPTY.withColor(MERL_PINK).withItalic(true)));
		}
		reply(source, message);
		return settings.size() + results.size();
	}

	private static String headline(SearchIndex.Outcome outcome, List<SearchIndex.Result> results, Ask ask, String user) {
		boolean hasEden = results.stream().anyMatch(r -> !r.section().vanilla());
		boolean hasVanilla = results.stream().anyMatch(r -> r.section().vanilla());
		boolean corrected = hasEden && !outcome.corrections().isEmpty() && !results.get(0).section().vanilla();
		String sure = VanillaWiki.confidence(results, outcome);
		String core;
		if (corrected) {
			core = MerlLines.pick("found_typo", "term", String.join(", ", new LinkedHashSet<>(outcome.corrections().values())));
		} else if (ask.repeat()) {
			core = MerlLines.pick("repeat_question");
		} else if (hasEden && hasVanilla) {
			core = MerlLines.pick("found_both", "community", NiceMerl.config().communityName);
		} else if (hasVanilla) {
			core = MerlLines.pick(sure.equals("sure") ? "found_vanilla" : "found_vanilla_" + sure);
		} else {
			core = MerlLines.pick("found_" + sure);
		}
		return MerlLines.headline(core, ask.prefix(), ask.energy(), LocalTime.now().getHour(), user, !corrected);
	}

	/**
	 * Now and then, a word about where the player is, what they're holding or how they're doing.
	 * Players can turn this off with /nicemerl comments off.
	 */
	private static String contextLine(ServerPlayer player) {
		if (player == null || !NiceMerl.config().playerComments || !MerlState.player(player.getUUID()).comments
				|| !MerlLines.chance(CONTEXT_CHANCE)) {
			return null;
		}
		if (player.getHealth() <= player.getMaxHealth() * 0.3f) return MerlLines.pick("context_hurt");

		ServerLevel level = player.level();
		List<Supplier<String>> options = new ArrayList<>();
		if (level.dimension() == Level.NETHER) options.add(() -> MerlLines.pick("context_nether"));
		else if (level.dimension() == Level.END) options.add(() -> MerlLines.pick("context_end"));
		else if (level.isRaining()) options.add(() -> MerlLines.pick("context_rain"));
		else if (level.isDarkOutside()) options.add(() -> MerlLines.pick("context_night"));

		String held = HELD_ITEMS.get(player.getMainHandItem().getItem());
		if (held != null) options.add(() -> MerlLines.pick(held));
		if (player.getItemBySlot(EquipmentSlot.CHEST).getItem() == Items.ELYTRA) options.add(() -> MerlLines.pick("wearing_elytra"));
		String biome = biomePool(level.getBiome(player.blockPosition()));
		if (biome != null) options.add(() -> MerlLines.pick(biome));

		int deaths = player.getStats().getValue(Stats.CUSTOM.get(Stats.DEATHS));
		if (deaths >= MANY_DEATHS) options.add(() -> MerlLines.pick("many_deaths", "deaths", Integer.toString(deaths)));
		int hours = player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)) / 72_000;
		if (hours >= VETERAN_HOURS) options.add(() -> MerlLines.pick("veteran", "hours", Integer.toString(hours)));

		return options.isEmpty() ? null : options.get(ThreadLocalRandom.current().nextInt(options.size())).get();
	}

	private static String biomePool(Holder<Biome> biome) {
		if (biome.is(Biomes.DEEP_DARK)) return "biome_deep_dark";
		if (biome.is(Biomes.MUSHROOM_FIELDS)) return "biome_mushroom";
		if (biome.is(Biomes.CHERRY_GROVE)) return "biome_cherry";
		if (biome.is(Biomes.DESERT)) return "biome_desert";
		if (biome.is(BiomeTags.IS_OCEAN)) return "biome_ocean";
		if (biome.is(BiomeTags.IS_JUNGLE)) return "biome_jungle";
		if (SNOWY.stream().anyMatch(biome::is)) return "biome_snowy";
		return null;
	}

	/**
	 * Called when a player completes an advancement. Merl congratulates them privately on the
	 * ones listed in the config, unless they turned it off with /nicemerl celebrate off.
	 */
	public static void celebrate(ServerPlayer player, AdvancementHolder holder) {
		MerlConfig config = NiceMerl.config();
		if (config == null || !config.celebrate || !config.celebrateAdvancements.contains(holder.id().toString())
				|| !MerlState.player(player.getUUID()).celebrate) {
			return;
		}
		long now = System.currentTimeMillis();
		Long last = LAST_CELEBRATION.get(player.getUUID());
		if (last != null && now - last < CELEBRATE_COOLDOWN_MS) return;
		LAST_CELEBRATION.put(player.getUUID(), now);

		// The title goes in as the advancement's own text component, so players see it translated
		// (or the pack's English fallback) instead of a raw translation key.
		Component title = holder.value().display().<Component>map(d -> d.title().copy())
				.orElse(Component.literal(holder.id().getPath()));
		String line = MerlLines.pick("celebrate", "advancement", TITLE_MARK, "user", player.getName().getString());
		MutableComponent body = Component.empty();
		String[] parts = line.split(TITLE_MARK, -1);
		for (int i = 0; i < parts.length; i++) {
			if (i > 0) body.append(title.copy().withStyle(Style.EMPTY.withColor(HIT_COLOR)));
			body.append(Component.literal(parts[i]));
		}
		player.sendSystemMessage(framed(body.withStyle(Style.EMPTY
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to turn these off: /nicemerl celebrate off")
						.withStyle(ChatFormatting.GRAY)))
				.withClickEvent(new ClickEvent.SuggestCommand("/nicemerl celebrate off")))));
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

	private static MutableComponent framed(Component body) {
		MutableComponent message = Component.literal("[NiceMerl] ").withStyle(Style.EMPTY.withColor(MERL_PINK).withBold(true));
		message.append(Component.empty().withStyle(Style.EMPTY.withBold(false).withColor(ChatFormatting.WHITE)).append(body));
		return message;
	}

	private static void reply(CommandSourceStack source, Component body) {
		source.sendSystemMessage(framed(body));
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

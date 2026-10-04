package eu.explorerseden.nicemerl;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
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
	/** Players with up to this many chats get Merl's introduction with a plain /merl or a hello. */
	private static final int INTRO_CHATS = 5;

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
	/**
	 * @param asking phrased as a question (or a follow-up to one); otherwise only a confident match is shown
	 * @param knownPacks when the player asked about settings and none matched: the packs Merl knows settings of
	 */
	private record Ask(String prefix, boolean repeat, String energy, MerlMemory.Visit visit, long now,
			boolean asking, List<String> knownPacks, String question, String note, String search) {}

	private MerlCommand() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("merl")
				.requires(Permissions.require(PERMISSION_MERL, true))
				.executes(MerlCommand::hello)
				.then(Commands.argument("question", StringArgumentType.greedyString())
						.executes(MerlCommand::ask)));

		dispatcher.register(Commands.literal("nicemerl")
				.requires(source -> Permissions.check(source, PERMISSION_TOGGLE, true)
						|| Permissions.check(source, MerlLocate.PERMISSION_LOCATE, true)
						|| Permissions.check(source, PERMISSION_REINDEX, PermissionLevel.GAMEMASTERS))
				.then(Commands.literal("reindex")
						.requires(Permissions.require(PERMISSION_REINDEX, PermissionLevel.GAMEMASTERS))
						.executes(MerlCommand::reindex))
				.then(Commands.literal("guide")
						.requires(Permissions.require(MerlLocate.PERMISSION_LOCATE, true))
						.then(Commands.literal("stop").executes(MerlCommand::stopGuide))
						.then(Commands.argument("x", IntegerArgumentType.integer())
								.then(Commands.argument("y", StringArgumentType.word())
										.then(Commands.argument("z", IntegerArgumentType.integer())
												.then(Commands.argument("target", StringArgumentType.greedyString())
														.executes(MerlCommand::guide))))))
				.then(toggle("comments"))
				.then(toggle("celebrate")));
	}

	/** /nicemerl guide x y z target: the [Guide me] button after coordinates. y is "-" when any height will do. */
	private static int guide(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = source.getPlayer();
		if (player == null || !NiceMerl.config().particleGuide) return 0;
		String y = StringArgumentType.getString(ctx, "y");
		Double height = null;
		try {
			if (!y.equals("-")) height = (double) Integer.parseInt(y);
		} catch (NumberFormatException e) {
			return 0;
		}
		String target = StringArgumentType.getString(ctx, "target");
		MerlGuide.start(player, IntegerArgumentType.getInteger(ctx, "x"), height, IntegerArgumentType.getInteger(ctx, "z"), target);
		reply(source, Component.literal(MerlLines.pick("guide_start", "target", target, "user", player.getName().getString()) + " ")
				.append(Component.literal("[Stop]").withStyle(Style.EMPTY.withColor(ChatFormatting.GRAY)
						.withClickEvent(new ClickEvent.RunCommand("/nicemerl guide stop"))
						.withHoverEvent(new HoverEvent.ShowText(Component.literal("Stop the trail"))))));
		return 1;
	}

	private static int stopGuide(CommandContext<CommandSourceStack> ctx) {
		ServerPlayer player = ctx.getSource().getPlayer();
		if (player == null) return 0;
		reply(ctx.getSource(), Component.literal(MerlLines.pick(MerlGuide.stop(player) ? "guide_stopped" : "guide_not_guiding")));
		return 1;
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
		visit.seenAt = now;
		return hello(source, visit, meet(player, null, source.getTextName()), now);
	}

	/**
	 * Hello for someone new (with an introduction), someone back after a while (with a question about
	 * what they were up to), or an old friend.
	 */
	private static int hello(CommandSourceStack source, MerlMemory.Visit visit, Meeting meeting, long now) {
		MerlConfig config = NiceMerl.config();
		ServerPlayer player = source.getPlayer();
		MerlState.Player friend = player != null ? MerlState.player(player.getUUID()) : new MerlState.Player();
		String user = source.getTextName();
		String greeting;
		String follow = null;
		if (meeting.first()) {
			greeting = MerlLines.pick("first_meeting", "user", user);
		} else if (meeting.returning()) {
			greeting = MerlLines.pick("welcome_back", "user", user);
			follow = MerlLines.followUp(friend.topic, friend.topicDay == null ? 0 : friend.topicDay, friend.page,
					friend.pageDay == null ? 0 : friend.pageDay, meeting.today());
		} else if (friend.chatCount() > MerlLines.FRIEND_CHATS && MerlLines.chance(MerlLines.FRIEND_GREETING_CHANCE)) {
			greeting = MerlLines.pick("greeting_friend", "user", user);
		} else {
			greeting = MerlLines.pick(MerlLines.greetingPool(LocalTime.now()), "user", user);
		}
		MutableComponent message = Component.literal(greeting);
		// Regulars know what Merl does; only newer players get the introduction.
		if (friend.chatCount() <= INTRO_CHATS) {
			message.append(Component.literal(" Ask me anything about " + config.communityName + ", like "))
					.append(example("/merl how do I get a boss key"));
			if (NiceMerl.mediaWikis().stream().anyMatch(w -> w.baseUrl().contains("minecraft.wiki"))) {
				message.append(Component.literal(", or about vanilla Minecraft, like ")).append(example("/merl how do I make a nether portal"));
			}
			message.append(Component.literal(", and I'll find the right wiki page for you!"));
		}
		String extra = follow;
		if (extra == null) extra = askBack("greeting", visit, now);
		if (extra == null) extra = askFeeling("greeting", greeting, visit, now);
		if (extra != null) message.append(Component.literal(" " + extra));
		reply(source, message);
		sendNote(source, meeting.note());
		return 1;
	}

	/** "have you met Alex?": whether Merl knows them, by name; null when it's not about someone she knows. */
	private static String metLine(MerlLines.Met met, CommandSourceStack source) {
		String user = source.getTextName();
		String key = met.name().toLowerCase(java.util.Locale.ROOT).replaceFirst("^@", "");
		if (key.equals("peanut butter") || key.equals("pb") || key.equals("your cat")) return MerlLines.peanutButter(LocalDate.now());
		if (key.equals("merl") || key.equals("nicemerl")) {
			return MerlLines.pick("who_are_you", "community", NiceMerl.config().communityName);
		}
		if (key.equalsIgnoreCase(user)) return MerlLines.pick("met_you", "user", user);
		ServerPlayer online = source.getServer().getPlayerList().getPlayerByName(met.name());
		MerlState.Player friend = online != null ? MerlState.player(online.getUUID()) : MerlState.find(met.name());
		String name = online != null ? online.getName().getString() : friend != null && friend.name != null ? friend.name : met.name();
		if (friend != null && friend.chatCount() > 0) {
			return MerlLines.pick("met_yes", "name", name, "often", MerlLines.often(friend.chatCount()), "user", user);
		}
		return met.strict() ? MerlLines.pick("met_no", "name", name, "user", user) : null;
	}

	/** What this chat means for the friendship: first one, back after a while, and maybe a milestone line. */
	private record Meeting(boolean first, boolean returning, String note, int today) {}

	/** Counts a chat with the player and remembers when it was. {@code question} is null for a plain /merl. */
	private static Meeting meet(ServerPlayer player, String question, String user) {
		int today = (int) LocalDate.now().toEpochDay();
		if (player == null) return new Meeting(false, false, null, today);
		long minute = System.currentTimeMillis() / 60_000;
		MerlState.Player before = MerlState.player(player.getUUID());
		boolean first = before.chatCount() == 0;
		boolean returning = before.returning(minute);
		String talk = question != null ? MerlLines.smallTalk(question) : null;
		boolean quiet = talk != null && MerlLines.QUIET_TALK.contains(talk);
		String[] note = {null};
		MerlState.update(player.getUUID(), p -> {
			if (p.met == null) p.met = today;
			p.chats = p.chatCount() + 1;
			p.seen = minute;
			p.name = user;
			if (quiet) return;
			MerlLines.Note n = MerlLines.friendshipNote(p.chats, p.noted == null ? 0 : p.noted, today - p.met,
					p.anniversary == null ? 0 : p.anniversary, user);
			note[0] = n.line();
			if (n.noted() > 0) p.noted = n.noted();
			if (n.anniversary() > 0) p.anniversary = n.anniversary();
		});
		return new Meeting(first, returning, note[0], today);
	}

	/** "Oh! That was our 50th chat." comes as a little extra message after the answer. */
	private static void sendNote(CommandSourceStack source, String note) {
		if (note != null) reply(source, Component.literal(note).withStyle(Style.EMPTY.withColor(MERL_PINK)));
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
		visit.seenAt = now;
		Meeting meeting = meet(player, question, source.getTextName());

		// "what can I craft?" looks at the player's inventory.
		if (player != null && config.craftingHelp && MerlLines.craftingQuestion(question)) {
			reply(source, MerlCrafting.answer(player, question));
			sendNote(source, meeting.note());
			return 1;
		}

		MerlLines.Met met = MerlLines.metQuestion(question);
		String metLine = met != null ? metLine(met, source) : null;
		if (metLine != null) {
			reply(source, Component.literal(metLine));
			sendNote(source, meeting.note());
			return 1;
		}
		String talk = MerlLines.smallTalk(question);
		String talkPrefix = null;
		if (talk == null) {
			// "you're funny! tell me a joke"
			MerlLines.MultiTalk multi = MerlLines.multiSmallTalk(question);
			if (multi != null) {
				talk = multi.talk();
				talkPrefix = multi.prefix();
			}
		}
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
			sendNote(source, meeting.note());
			return 1;
		}
		if (talk == null && awaiting) {
			String topic = MerlLines.topic(question);
			if (topic != null) {
				if (player != null) MerlState.update(player.getUUID(), p -> {
					p.topic = topic;
					p.topicDay = meeting.today();
				});
				reply(source, Component.literal(MerlLines.pick("reply_" + topic)));
				sendNote(source, meeting.note());
				return 1;
			}
		}

		String resolved = MerlLines.resolveReference(question, visit.recentPage(now));
		if ("greeting".equals(talk) || (talk == null && SearchIndex.tokenize(resolved).isEmpty())) {
			return hello(source, visit, meeting, now);
		}
		if ("more".equals(talk)) {
			// "another one!" after a joke is another joke.
			String last = visit.recentTalk(now);
			if (last == null || !MerlLines.REPEATABLE.contains(last)) {
				reply(source, Component.literal(MerlLines.pick("more_what", "user", source.getTextName())));
				sendNote(source, meeting.note());
				return 1;
			}
			talk = last;
		}
		if (talk != null) {
			String text = smallTalkLine(talk, source, player, visit, now, meeting);
			if (talkPrefix != null) text = MerlLines.pick(talkPrefix, "user", source.getTextName()) + " " + text;
			visit.talk = talk;
			visit.talkedAt = now;
			String askBack = askBack(talk, visit, now);
			if (askBack == null) askBack = askFeeling(talk, text, visit, now);
			reply(source, Component.literal(askBack != null ? text + " " + askBack : text));
			sendNote(source, meeting.note());
			return 1;
		}

		if (MerlLines.isClarifying(question) && visit.recentPage(now) != null) {
			// "so Katter is the bosses?" right after an answer
			reply(source, Component.literal(MerlLines.pick("clarify", "user", source.getTextName())));
			sendNote(source, meeting.note());
			return 1;
		}

		// "name tag phrases": the Nice Name Tags texts from its wiki page, to copy.
		if (NameTagPhrases.mentionsNameTag(question) && nameTagPhrases(source, question)) {
			sendNote(source, meeting.note());
			return 1;
		}
		// "where's the closest waypoint?" with Warping Wonders.
		if (MerlWaypoints.handle(source, player, question, config)) {
			sendNote(source, meeting.note());
			return 1;
		}
		// "where's the closest cherry grove?" and "where's a slime chunk?" get coordinates.
		if (MerlLocate.handle(source, question, body -> reply(source, body))) {
			sendNote(source, meeting.note());
			return 1;
		}

		MerlLines.Split split = MerlLines.splitSmallTalk(question);
		// "where do I find him?" right after the Raj Raksha page
		String search = MerlLines.resolveReference(split.rest(), visit.recentPage(now));
		boolean asking = split.prefix() != null || MerlLines.seeksInfo(question) || MerlLines.isFollowUp(search);

		// Every question is checked against the settings: loosely when it sounds like a settings question
		// ("is pvp enabled"), otherwise only when it names a setting or pack outright ("can I pvp").
		List<DatapackSettings.Setting> settings = List.of();
		List<String> knownPacks = List.of();
		if (Permissions.check(source, PERMISSION_SETTINGS, true)) {
			boolean loose = DatapackSettings.isSettingsQuestion(search);
			settings = DatapackSettings.search(source.getServer(), config, search, config.settingsResults, !loose);
			if (settings.isEmpty() && DatapackSettings.mentionsSettings(search)) {
				knownPacks = DatapackSettings.packs(source.getServer(), config);
			}
		}
		Ask ask = new Ask(split.prefix(), false, MerlLines.energy(question), visit, now, asking, knownPacks, question, meeting.note(), search);

		SearchIndex index = NiceMerl.index();
		if (index == null && settings.isEmpty()) {
			reply(source, Component.literal(MerlLines.pick("still_reading")).withStyle(ChatFormatting.GRAY));
			return 0;
		}
		// Keep the reply compact when settings are listed too.
		int limit = settings.isEmpty() ? config.results : Math.min(2, config.results);
		// Close calls go to the projects this player usually asks about, and to what they're asking about now.
		Map<String, Double> leaning = MerlLines.interests(
				player != null ? MerlState.player(player.getUUID()).interestShares() : Map.of(), visit.recentProject(now));
		SearchIndex.Outcome outcome = index != null
				? index.find(search, limit, config.excerptLength, leaning)
				: new SearchIndex.Outcome(List.of(), Map.of(), false);
		// "and in the nether?" right after a question: if it finds nothing good on its own,
		// search it together with the previous question.
		String previous = visit.recentQuestion(now);
		boolean weak = outcome.results().isEmpty() || outcome.confidence() < SearchIndex.SURE_TITLE_SCORE;
		if (index != null && weak && previous != null && MerlLines.isFollowUp(search)) {
			SearchIndex.Outcome combined = index.find(previous + " " + search, limit, config.excerptLength, leaning);
			if (combined.confidence() >= outcome.confidence()) {
				search = previous + " " + search;
				outcome = combined;
			}
		}
		String asked = String.join(" ", SearchIndex.tokenize(search));
		ask = new Ask(ask.prefix(), visit.isRepeat(asked, now), ask.energy(), visit, now, ask.asking(), ask.knownPacks(), ask.question(), ask.note(), search);
		visit.question = asked;
		visit.askedAt = now;

		List<VanillaWiki> live = NiceMerl.mediaWikis();
		// Settings questions are about this server, so they skip the Minecraft Wiki.
		if (live.isEmpty() || !settings.isEmpty() || !knownPacks.isEmpty()) {
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

	private static String smallTalkLine(String talk, CommandSourceStack source, ServerPlayer player, MerlMemory.Visit visit, long now,
			Meeting meeting) {
		if (talk.equals("remember_me") && player != null) {
			MerlState.Player friend = MerlState.player(player.getUUID());
			return MerlLines.rememberMe(friend.chatCount(), meeting.today() - friend.metDay(), friend.topic, source.getTextName());
		}
		if (talk.equals("forget_me") && player != null) {
			MerlState.forget(player.getUUID());
			return MerlLines.pick("forget_done", "user", source.getTextName());
		}
		// "thanks" after an answer: that project was right for them; "that's not what I asked": it wasn't.
		String project = visit.recentProject(now);
		if ((talk.equals("thanks") || talk.equals("wrong")) && project != null && player != null) {
			int amount = talk.equals("thanks") ? 1 : -1;
			MerlState.update(player.getUUID(), p -> p.learn(project, amount));
		}
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
		int shown = answer(source, settings, outcome, results, ask);
		sendNote(source, ask.note());
		return shown;
	}

	private static int answer(CommandSourceStack source, List<DatapackSettings.Setting> settings,
			SearchIndex.Outcome outcome, List<SearchIndex.Result> results, Ask ask) {
		MerlConfig config = NiceMerl.config();
		if (settings.isEmpty() && !ask.knownPacks().isEmpty()) {
			return settingsOverview(source, results, ask, config);
		}
		boolean allMatched = !results.isEmpty() && results.get(0).matched() >= new HashSet<>(SearchIndex.tokenize(ask.search())).size();
		boolean confident = !results.isEmpty() && MerlLines.clearlyAbout(VanillaWiki.confidence(results, outcome),
				results.get(0).titleMatch(), ask.question(), allMatched);
		if (settings.isEmpty() && !ask.asking() && !confident) {
			// "NO! Stop!" or "i like turtles": no question, and no page that's clearly about it.
			reply(source, Component.literal(MerlLines.pick("unclear", "user", source.getTextName())).append(
					Component.literal(" Try ").withStyle(ChatFormatting.GRAY)).append(example("/merl how do I get a boss key")));
			return 0;
		}
		String sure = results.isEmpty() ? null : VanillaWiki.confidence(results, outcome);
		if (settings.isEmpty() && "guess".equals(sure) && results.get(0).matched() <= 1 && !results.get(0).titleMatch()) {
			// "anyone online?": one loose word in common with a page isn't an answer.
			reply(source, Component.literal(MerlLines.pick("unclear", "user", source.getTextName())).append(
					Component.literal(" Try ").withStyle(ChatFormatting.GRAY)).append(example("/merl how do I get a boss key")));
			return 0;
		}
		if ("guess".equals(sure)) {
			// A guess is one page, not three loosely related ones.
			results = results.subList(0, 1);
		}
		if (settings.isEmpty() && results.isEmpty()) {
			ask.visit().page = "";
			reply(source, Component.literal(MerlLines.pick("not_found", "community", config.communityName)));
			return 0;
		}
		if (!results.isEmpty()) {
			ask.visit().page = results.get(0).section().pageTitle();
			ask.visit().answeredAt = ask.now();
			ServerPlayer player = source.getPlayer();
			if (player != null) {
				int today = (int) LocalDate.now().toEpochDay();
				Section top = results.get(0).section();
				boolean learn = !top.vanilla();
				if (learn) ask.visit().project = top.path().split("/")[0];
				String project = ask.visit().project;
				MerlState.update(player.getUUID(), p -> {
					p.remember(ask.visit().page, today);
					if (learn) p.learn(project, 1);
				});
			}
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
			// The answer line goes first, the pages below it.
			SearchIndex index = NiceMerl.index();
			String line = index != null && ("sure".equals(sure) || "maybe".equals(sure)) ? index.answerLine(ask.search(), results) : "";
			if (!line.isEmpty()) message.append(Component.literal("\n" + line).withStyle(ChatFormatting.WHITE));
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

	/** A settings question that matched no setting: which packs Merl can answer about, plus any wiki pages. */
	private static int settingsOverview(CommandSourceStack source, List<SearchIndex.Result> results, Ask ask, MerlConfig config) {
		String prefix = ask.prefix() != null ? MerlLines.pick(ask.prefix(), "user", source.getTextName()) + " " : "";
		MutableComponent message = Component.literal(prefix + "I couldn't find that setting. I know the settings of "
				+ String.join(", ", ask.knownPacks()) + ". Name the setting, like ").append(example("/merl is pvp enabled"));
		if (!results.isEmpty()) {
			message.append(Component.literal("\nMaybe the wiki helps:").withStyle(ChatFormatting.GRAY));
			for (SearchIndex.Result result : results) {
				message.append(Component.literal("\n"));
				message.append(formatResult(result, config));
			}
		}
		reply(source, message);
		return results.size();
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
		// How many others on the server have it too ("only the 3rd explorer to do this!"). Online players
		// are checked live; everyone else's saved advancements are read off the server thread.
		MinecraftServer server = player.level().getServer();
		String id = holder.id().toString();
		Set<UUID> online = new HashSet<>();
		int onlineDone = 0;
		for (ServerPlayer other : server.getPlayerList().getPlayers()) {
			online.add(other.getUUID());
			if (other != player && other.getAdvancements().getOrStartProgress(holder).isDone()) onlineDone++;
		}
		int alreadyOnline = onlineDone;
		java.nio.file.Path dir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_ADVANCEMENTS_DIR);
		NiceMerl.lookupAsync(() -> alreadyOnline + MerlStats.countDone(dir, id, online), others -> server.execute(() -> {
			body.append(Component.literal(" " + MerlStats.rankLine(others)));
			sendCelebration(player, body);
		}));
	}

	/** A private congratulation, with a hover hint on how to turn them off. */
	static void sendCelebration(ServerPlayer player, MutableComponent body) {
		if (player.hasDisconnected()) return;
		player.sendSystemMessage(framed(body.withStyle(Style.EMPTY
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to turn these off: /nicemerl celebrate off")
						.withStyle(ChatFormatting.GRAY)))
				.withClickEvent(new ClickEvent.SuggestCommand("/nicemerl celebrate off")))));
		plop(player);
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

	/** Merl's messages look like the Explorer's Eden packs': a colored "▊ " bar (from the config), then white text. */
	private static MutableComponent framed(Component body) {
		MerlConfig config = NiceMerl.config();
		TextColor color = TextColor.parseColor(config.prefixColor).result().orElse(MERL_PINK);
		MutableComponent message = Component.literal(config.messagePrefix).withStyle(Style.EMPTY.withColor(color).withBold(true).withItalic(false));
		message.append(Component.empty().withStyle(Style.EMPTY.withBold(false).withItalic(false).withColor(ChatFormatting.WHITE)).append(body));
		return message;
	}

	private static void reply(CommandSourceStack source, Component body) {
		source.sendSystemMessage(framed(body));
		if (source.getPlayer() != null) plop(source.getPlayer());
	}

	private static final Map<UUID, Long> LAST_SOUND = new ConcurrentHashMap<>();
	/** Messages sent together ("Let me look…" and the answer) get one sound. */
	private static final long SOUND_GAP_MS = 400;

	/** The message sound from the config (the packs' egg plop), only for this player. */
	static void plop(ServerPlayer player) {
		MerlConfig config = NiceMerl.config();
		if (config.messageSound == null || config.messageSound.isBlank()) return;
		long now = System.currentTimeMillis();
		Long last = LAST_SOUND.put(player.getUUID(), now);
		if (last != null && now - last < SOUND_GAP_MS) return;
		Identifier id = Identifier.tryParse(config.messageSound.strip());
		if (id == null) return;
		// A sound from a resource pack isn't in the registry, but the client can still play it.
		Holder<SoundEvent> sound = BuiltInRegistries.SOUND_EVENT.get(id).<Holder<SoundEvent>>map(h -> h)
				.orElseGet(() -> Holder.direct(SoundEvent.createVariableRangeEvent(id)));
		player.connection.send(new ClientboundSoundPacket(sound, SoundSource.NEUTRAL, player.getX(), player.getY(), player.getZ(),
				config.messageSoundVolume, config.messageSoundPitch, player.getRandom().nextLong()));
	}

	/** Lists the Nice Name Tags texts, each one click to copy; false when the wiki doesn't have them. */
	private static boolean nameTagPhrases(CommandSourceStack source, String question) {
		SearchIndex index = NiceMerl.index();
		if (index == null) return false;
		List<NameTagPhrases.Effect> effects = index.sections().stream()
				.filter(s -> s.path().equals(NameTagPhrases.PAGE) && s.heading().equals(NameTagPhrases.HEADING))
				.findFirst().map(s -> NameTagPhrases.parse(s.text())).orElse(List.of());
		if (effects.isEmpty() || !NameTagPhrases.isQuestion(question) && !NameTagPhrases.specific(effects, question)) return false;
		List<NameTagPhrases.Effect> shown = NameTagPhrases.matching(effects, question);
		MutableComponent message = Component.literal(MerlLines.pick(shown.size() < effects.size() ? "nametag_some" : "nametag_all",
				"user", source.getTextName()));
		for (NameTagPhrases.Effect effect : shown) {
			message.append(Component.literal("\n ▸ ").withStyle(ChatFormatting.DARK_GRAY));
			List<String> texts = effect.distinct();
			for (int i = 0; i < texts.size(); i++) {
				String text = texts.get(i);
				if (i > 0) message.append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY));
				message.append(Component.literal(text).withStyle(Style.EMPTY.withColor(HIT_COLOR).withUnderlined(true)
						.withClickEvent(new ClickEvent.CopyToClipboard(text))
						.withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to copy\n").withStyle(ChatFormatting.GRAY)
								.append(Component.literal(effect.description()).withStyle(ChatFormatting.WHITE))
								.append(Component.literal("\nAlso works: " + String.join(", ", effect.texts()))
										.withStyle(ChatFormatting.GRAY))))));
			}
			message.append(Component.literal(" " + effect.description()).withStyle(ChatFormatting.GRAY));
		}
		reply(source, message);
		return true;
	}

	static void replyTo(CommandSourceStack source, Component body) {
		reply(source, body);
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

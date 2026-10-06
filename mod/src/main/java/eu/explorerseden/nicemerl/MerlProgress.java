package eu.explorerseden.nicemerl;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.google.gson.JsonParser;

import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.AdvancementType;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.biome.Biome;

/**
 * Biome collection ("which biomes haven't I visited?", from Adventuring Time) and the advancement coach ("what
 * advancement should I get?"): what's missing, a tip, and the closest missing biome with [Guide me].
 */
final class MerlProgress {
	private static final Identifier ADVENTURING_TIME = Identifier.withDefaultNamespace("adventure/adventuring_time");
	private static final int LISTED = 12;
	private static final TextColor PINK = TextColor.fromRgb(0xF06EAA);
	private static final TextColor VALUE = TextColor.fromRgb(0xFFD966);
	private static Map<String, String> tips;

	private MerlProgress() {}

	/** "Which biomes haven't I visited?": the count, the missing ones and the closest of them. */
	static void biomes(CommandSourceStack source, ServerPlayer player, Consumer<Component> reply) {
		MinecraftServer server = source.getServer();
		String user = player.getName().getString();
		AdvancementHolder holder = server.getAdvancements().get(ADVENTURING_TIME);
		if (holder == null) {
			reply.accept(Component.literal("This server has no Adventuring Time advancement, so I can't tell which biomes you've seen."));
			return;
		}
		AdvancementProgress progress = player.getAdvancements().getOrStartProgress(holder);
		List<String> missing = list(progress.getRemainingCriteria());
		int done = list(progress.getCompletedCriteria()).size(), total = done + missing.size();
		if (progress.isDone() || missing.isEmpty()) {
			reply.accept(Component.literal(MerlLines.pick("biomes_all", "user", user, "count", String.valueOf(total))));
			return;
		}
		BiomeNames names = MerlLocate.names(server);
		List<String> shown = missing.stream().map(id -> names.display(id)).sorted().toList();
		MutableComponent out = Component.literal(MerlLines.pick("biomes_progress", "user", user,
				"count", String.valueOf(done), "total", String.valueOf(total)));
		out.append(row("Seen", done + " of " + total + " biomes (" + Math.round(100.0 * done / Math.max(1, total)) + "%)"));
		out.append(row("Still missing", joined(shown)));
		reply.accept(out);
		// The closest missing one in this dimension, looked up off the server thread.
		Registry<Biome> registry = server.registryAccess().lookupOrThrow(Registries.BIOME);
		Set<Holder<Biome>> wanted = new HashSet<>();
		for (String id : missing) {
			Identifier parsed = Identifier.tryParse(id);
			if (parsed != null) registry.get(parsed).ifPresent(wanted::add);
		}
		ServerLevel level = source.getLevel();
		wanted.retainAll(level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes());
		if (wanted.isEmpty() || !NiceMerl.config().locateBiomes) return;
		MerlLocate.closest(source, wanted, "a biome you haven't seen", false, "biomes_closest", reply);
	}

	/**
	 * "What advancement should I get?" (the unfinished one with the most progress) or "what's left for Monster
	 * Hunter?" ({@code named}). Returns false when a named advancement isn't one the server has.
	 */
	static boolean coach(CommandSourceStack source, ServerPlayer player, String question, boolean named, Consumer<Component> reply) {
		MinecraftServer server = source.getServer();
		String user = player.getName().getString();
		AdvancementHolder pick = named ? byTitle(server, question) : best(server, player);
		if (pick == null) {
			if (named) return false;
			reply.accept(Component.literal(MerlLines.pick("coach_all_done", "user", user)));
			return true;
		}
		if (pick.id().equals(ADVENTURING_TIME)) {
			biomes(source, player, reply);
			return true;
		}
		DisplayInfo display = pick.value().display().orElseThrow();
		AdvancementProgress progress = player.getAdvancements().getOrStartProgress(pick);
		String title = display.title().getString();
		if (progress.isDone()) {
			reply.accept(Component.literal(MerlLines.pick("coach_done", "user", user, "advancement", title)));
			return true;
		}
		List<String> missing = list(progress.getRemainingCriteria());
		int done = list(progress.getCompletedCriteria()).size(), total = done + missing.size();
		MutableComponent out = Component.literal(MerlLines.pick(named ? "coach_named" : "coach_pick", "user", user, "advancement", title));
		out.append(row("Advancement", title + " (" + typeName(display.type()) + ")"));
		out.append(row("Goal", display.description().getString()));
		if (total > 1) {
			out.append(row("Progress", done + " of " + total));
			out.append(row("Missing", joined(missing.stream().map(c -> readable(server, c)).sorted().toList())));
		}
		String tip = tips().get(pick.id().toString());
		if (tip != null) out.append(Component.literal("\n💡 " + tip).withStyle(ChatFormatting.GRAY));
		reply.accept(out);
		// Missing biomes (like Hot Tourist Destinations): the closest one, with [Guide me].
		Registry<Biome> registry = server.registryAccess().lookupOrThrow(Registries.BIOME);
		Set<Holder<Biome>> wanted = new HashSet<>();
		for (String id : missing) {
			Identifier parsed = Identifier.tryParse(id);
			if (parsed != null) registry.get(parsed).ifPresent(wanted::add);
		}
		wanted.retainAll(source.getLevel().getChunkSource().getGenerator().getBiomeSource().possibleBiomes());
		if (!wanted.isEmpty() && wanted.size() == missing.size() && NiceMerl.config().locateBiomes) {
			MerlLocate.closest(source, wanted, "a biome you still need", false, "biomes_closest", reply);
		}
		return true;
	}

	/** The visible, unfinished advancement with the most progress: tasks before goals before challenges. */
	private static AdvancementHolder best(MinecraftServer server, ServerPlayer player) {
		List<AdvancementHolder> open = new ArrayList<>();
		Map<AdvancementHolder, Float> percent = new HashMap<>();
		for (AdvancementNode node : server.getAdvancements().tree().nodes()) {
			AdvancementHolder holder = node.holder();
			DisplayInfo display = holder.value().display().orElse(null);
			if (display == null || node.isRoot()) continue;
			AdvancementProgress progress = player.getAdvancements().getOrStartProgress(holder);
			if (progress.isDone()) continue;
			// Only ones they can see: the one before it is done (or it already has progress, even when hidden).
			boolean unlocked = node.parent() == null || player.getAdvancements().getOrStartProgress(node.parent().holder()).isDone();
			if (!unlocked || display.hidden() && !progress.hasProgress()) continue;
			open.add(holder);
			percent.put(holder, progress.getPercent());
		}
		if (open.isEmpty()) return null;
		java.util.Collections.shuffle(open);
		open.sort(Comparator.<AdvancementHolder>comparingDouble(h -> -percent.get(h))
				.thenComparingInt(h -> h.value().display().map(d -> d.type().ordinal()).orElse(9)));
		return open.get(0);
	}

	/** The advancement whose title the question names (the longest match), or null. */
	static AdvancementHolder byTitle(MinecraftServer server, String question) {
		String text = " " + MerlLines.normalize(question) + " ";
		AdvancementHolder best = null;
		int bestLength = 0;
		for (AdvancementHolder holder : server.getAdvancements().getAllAdvancements()) {
			DisplayInfo display = holder.value().display().orElse(null);
			if (display == null) continue;
			String title = MerlLines.normalize(display.title().getString());
			if (title.length() < 4 || title.length() <= bestLength) continue;
			if (text.contains(" " + title + " ")) {
				best = holder;
				bestLength = title.length();
			}
		}
		return best;
	}

	private static String typeName(AdvancementType type) {
		return switch (type) {
			case GOAL -> "goal";
			case CHALLENGE -> "challenge";
			default -> "task";
		};
	}

	/** "minecraft:zombie" → "Zombie", "minecraft:plains" → "Plains", "stone_pickaxe" → "Stone Pickaxe". */
	private static String readable(MinecraftServer server, String criterion) {
		Identifier id = Identifier.tryParse(criterion);
		if (id != null) {
			Language language = Language.getInstance();
			String dotted = id.getNamespace() + "." + id.getPath().replace('/', '.');
			for (String kind : new String[] {"biome", "entity", "item", "block", "effect"}) {
				if (language.has(kind + "." + dotted)) return language.getOrDefault(kind + "." + dotted);
			}
			if (server.registryAccess().lookupOrThrow(Registries.BIOME).containsKey(id)) return MerlLocate.names(server).display(criterion);
		}
		String path = id != null ? id.getPath() : criterion;
		StringBuilder out = new StringBuilder();
		for (String word : path.replace('/', ' ').replace('_', ' ').split(" ")) {
			if (word.isEmpty()) continue;
			if (!out.isEmpty()) out.append(' ');
			out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
		}
		return out.toString();
	}

	private static String joined(List<String> names) {
		if (names.size() <= LISTED) return String.join(", ", names);
		return String.join(", ", names.subList(0, LISTED)) + " and " + (names.size() - LISTED) + " more";
	}

	private static List<String> list(Iterable<String> criteria) {
		List<String> out = new ArrayList<>();
		criteria.forEach(out::add);
		return out;
	}

	private static Component row(String label, String value) {
		return Component.literal("\n ✦ ").withStyle(Style.EMPTY.withColor(PINK))
				.append(Component.literal(label + ": ").withStyle(ChatFormatting.WHITE))
				.append(Component.literal(value).withStyle(Style.EMPTY.withColor(VALUE)));
	}

	/** Hand-written tips per advancement id, from advancement_tips.json (shared with the bot's data). */
	private static synchronized Map<String, String> tips() {
		if (tips != null) return tips;
		Map<String, String> loaded = new HashMap<>();
		try (InputStream in = MerlProgress.class.getResourceAsStream("/nicemerl/advancement_tips.json")) {
			if (in != null) {
				try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
					JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("tips").entrySet()
							.forEach(e -> loaded.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().getAsString()));
				}
			}
		} catch (Exception e) {
			NiceMerl.LOGGER.warn("Could not read advancement_tips.json", e);
		}
		tips = Map.copyOf(loaded);
		return tips;
	}
}

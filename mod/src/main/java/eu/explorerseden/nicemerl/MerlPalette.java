package eu.explorerseden.nicemerl;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Block palettes: blocks that go well together, around the block the player looks at, holds or names, or a random
 * one. Built from each full block's average color (block_colors.json, made from the game's textures by
 * mod/tools/scripts/block_colors.py): the block, four others from dark to light in a similar color, and one accent.
 * The Discord bot builds them the same way (bot/palettes.py), so keep both in step.
 */
final class MerlPalette {
	private static final List<String> COLORS = List.of("light_gray", "light_blue", "white", "gray", "black", "brown", "red",
			"orange", "yellow", "lime", "green", "cyan", "blue", "purple", "magenta", "pink");
	private static final List<String> WOODS = List.of("dark_oak", "pale_oak", "oak", "spruce", "birch", "jungle", "acacia",
			"mangrove", "cherry", "bamboo", "crimson", "warped", "poplar");
	private static final List<String> WEATHER = List.of("exposed", "weathered", "oxidized");
	/** Sets that only differ in color or wood: one of each per palette. Other materials (deepslate, copper…) two at most. */
	private static final Set<String> ONE_PER_PALETTE = Set.of("concrete", "wool", "terracotta", "glazed_terracotta",
			"stained_glass", "planks", "log", "wood", "stem", "hyphae", "stripped_log", "stripped_wood", "stripped_stem",
			"stripped_hyphae", "block", "mosaic", "shulker_box");
	/** Blocks nobody builds with (or can't get). */
	private static final Set<String> NEVER = Set.of("bedrock", "suspicious_sand", "suspicious_gravel", "sponge", "wet_sponge",
			"magma_block", "end_portal_frame", "piston", "sticky_piston", "mycelium", "crimson_nylium", "warped_nylium",
			"muddy_mangrove_roots");
	/** Blocks builders use less (soft, patterned or glowing): only picked when they fit clearly better. */
	private static final List<String> LESS_USED = List.of("wool", "concrete_powder", "glazed_terracotta", "coral_block",
			"froglight", "raw_", "mushroom_block");
	private static final double LESS_USED_PENALTY = 6, SEE_THROUGH_PENALTY = 15;
	/** How much a different hue counts against a block, compared with a different lightness. */
	private static final double HUE_WEIGHT = 1.6;
	/** Lightness (CIELAB L) the palette spans, and its limits. */
	private static final double SPREAD = 36, DARKEST = 12, LIGHTEST = 95;
	private static final double TOO_SIMILAR = 3, NOISE = 6;
	/** Partial blocks and their full block: oak stairs → oak planks, stone brick wall → stone bricks. */
	private static final List<String> PART_SUFFIXES = List.of("_stairs", "_slab", "_wall", "_fence_gate", "_fence",
			"_trapdoor", "_door", "_button", "_pressure_plate", "_hanging_sign", "_wall_hanging_sign", "_wall_sign", "_sign",
			"_pane", "_carpet");
	private static final List<String> FULL_SUFFIXES = List.of("", "s", "_planks", "_block", "_bricks", "_wool");
	/** Looking at a block counts this far away, so you can ask about a wall across the room. */
	private static final double REACH = 20;
	/** Each player's last palette start (a block id, or RANDOM), for "another palette". */
	private static final Map<UUID, String> LAST = new ConcurrentHashMap<>();
	private static final String RANDOM = "";
	private static final Random RNG = new Random();

	record Shade(double l, double a, double b, double contrast, boolean seeThrough, int rgb) {}

	private static volatile Map<String, Shade> shades;
	private static volatile Map<String, String> names;

	private MerlPalette() {}

	/** "what blocks go with this", "palette for deepslate", "give me a random palette", "another palette". */
	static Component answer(ServerPlayer player, MerlLines.PaletteAsk ask) {
		String user = player.getName().getString();
		Map<String, Shade> all = shades();
		if (all.isEmpty()) return Component.literal(MerlLines.pick("palette_unknown", "block", "that", "user", user));
		String base;
		if (ask.again()) {
			base = LAST.getOrDefault(player.getUUID(), RANDOM);
		} else if (ask.random()) {
			base = RANDOM;
		} else if (ask.block() != null) {
			String id = byName(ask.block());
			if (id == null) return Component.literal(MerlLines.pick("palette_unknown_name", "block", ask.block(), "user", user));
			base = fullBlock(id);
			if (base == null) return Component.literal(MerlLines.pick("palette_unknown", "block", name(id), "user", user));
		} else {
			String id = ask.holding() ? held(player) : lookedAt(player);
			if (id == null && !ask.holding()) id = held(player);
			if (id == null) return Component.literal(MerlLines.pick("palette_nothing", "user", user));
			base = fullBlock(id);
			if (base == null) return Component.literal(MerlLines.pick("palette_unknown", "block", name(id), "user", user));
		}
		LAST.put(player.getUUID(), base);
		boolean random = base.equals(RANDOM);
		String start = random ? randomBase(all) : base;
		if (start == null) return Component.literal(MerlLines.pick("palette_unknown", "block", "that", "user", user));
		List<String> blocks = palette(all, start, RNG);
		MutableComponent message = Component.literal(random
				? MerlLines.pick("palette_random", "user", user)
				: MerlLines.pick("palette_intro", "block", name(start), "user", user));
		message.append("\n ");
		for (String id : blocks) {
			Shade shade = all.get(id);
			message.append(Component.literal("██").withStyle(Style.EMPTY.withColor(TextColor.fromRgb(shade.rgb()))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal(name(id))))));
		}
		message.append("\n");
		for (int i = 0; i < blocks.size(); i++) {
			String id = blocks.get(i);
			Shade shade = all.get(id);
			message.append(Component.literal(i == 0 ? " " : ", "));
			message.append(Component.literal("■ ").withStyle(Style.EMPTY.withColor(TextColor.fromRgb(shade.rgb()))));
			// Click a block for a palette around it instead.
			message.append(Component.literal(name(id)).withStyle(Style.EMPTY
					.withColor(id.equals(start) ? ChatFormatting.WHITE : ChatFormatting.GRAY).withBold(id.equals(start))
					.withHoverEvent(new HoverEvent.ShowText(Component.literal("minecraft:" + id + "\nClick for a palette around it")))
					.withClickEvent(new ClickEvent.RunCommand("/merl palette for " + name(id).toLowerCase(java.util.Locale.ROOT)))));
		}
		message.append("\n ").append(button("[Another one]", "/merl another palette", "A different palette like this one"));
		if (!random) message.append(" ").append(button("[Random palette]", "/merl random palette", "A palette around a random block"));
		return message;
	}

	private static MutableComponent button(String label, String command, String hover) {
		return Component.literal(label).withStyle(Style.EMPTY.withColor(ChatFormatting.LIGHT_PURPLE)
				.withClickEvent(new ClickEvent.RunCommand(command)).withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
	}

	/** Six blocks from dark to light that go with base (base included). */
	static List<String> palette(Map<String, Shade> all, String base, Random rng) {
		Shade b = all.get(base);
		if (b == null) return List.of();
		double chroma0 = Math.hypot(b.a(), b.b());
		double lo = Math.max(DARKEST, b.l() - SPREAD), hi = Math.min(LIGHTEST, b.l() + SPREAD);
		// Near the darkest or lightest end, the palette reaches further the other way.
		if (hi - lo < SPREAD * 1.5) {
			if (lo == DARKEST) hi = Math.min(LIGHTEST, lo + SPREAD * 1.5);
			else lo = Math.max(DARKEST, hi - SPREAD * 1.5);
		}
		List<Double> points = new ArrayList<>();
		for (int i = 0; i < 5; i++) points.add(lo + (hi - lo) * i / 4);
		points.remove(points.stream().min(Comparator.comparingDouble(p -> Math.abs(p - b.l()))).orElseThrow());

		List<String> chosen = new ArrayList<>(List.of(base));
		Map<String, Integer> counts = new HashMap<>(Map.of(family(base), 1));
		for (double target : points) {
			String best = null;
			double bestScore = Double.MAX_VALUE;
			for (Map.Entry<String, Shade> e : all.entrySet()) {
				if (!allowed(all, e.getKey(), chosen, counts)) continue;
				Shade c = e.getValue();
				double score = Math.abs(c.l() - target) + HUE_WEIGHT * Math.hypot(c.a() - b.a(), c.b() - b.b())
						+ 0.15 * Math.abs(c.contrast() - b.contrast()) + penalty(e.getKey(), c) + rng.nextDouble() * NOISE;
				if (score < bestScore) {
					best = e.getKey();
					bestScore = score;
				}
			}
			if (best != null) take(best, chosen, counts);
		}
		// The accent: a calm, gray-ish block next to a colorful one, a warm colorful one next to a gray one.
		String accent = null;
		double accentScore = Double.MAX_VALUE;
		for (Map.Entry<String, Shade> e : all.entrySet()) {
			Shade c = e.getValue();
			if (Math.abs(c.l() - b.l()) > 25 || !allowed(all, e.getKey(), chosen, counts)) continue;
			double chroma = Math.hypot(c.a(), c.b());
			double score = 0.3 * Math.abs(c.l() - b.l()) + penalty(e.getKey(), c) + rng.nextDouble() * NOISE
					+ (chroma0 > 12 ? 0.6 * chroma : 0.5 * Math.abs(chroma - 28) + (c.b() < 0 ? 6 : 0));
			if (score < accentScore) {
				accent = e.getKey();
				accentScore = score;
			}
		}
		if (accent != null) take(accent, chosen, counts);
		chosen.sort(Comparator.comparingDouble(id -> all.get(id).l()));
		return chosen;
	}

	private static boolean allowed(Map<String, Shade> all, String id, List<String> chosen, Map<String, Integer> counts) {
		if (chosen.contains(id) || NEVER.contains(id)) return false;
		String family = family(id);
		if (counts.getOrDefault(family, 0) >= (ONE_PER_PALETTE.contains(family) ? 1 : 2)) return false;
		Shade s = all.get(id);
		for (String other : chosen) {
			Shade o = all.get(other);
			if (Math.sqrt(Math.pow(s.l() - o.l(), 2) + Math.pow(s.a() - o.a(), 2) + Math.pow(s.b() - o.b(), 2)) < TOO_SIMILAR) return false;
		}
		return true;
	}

	private static void take(String id, List<String> chosen, Map<String, Integer> counts) {
		chosen.add(id);
		counts.merge(family(id), 1, Integer::sum);
	}

	private static double penalty(String id, Shade c) {
		return (c.seeThrough() ? SEE_THROUGH_PENALTY : 0) + (LESS_USED.stream().anyMatch(id::contains) ? LESS_USED_PENALTY : 0);
	}

	/** "white_concrete" → "concrete", "oak_planks" → "planks", "exposed_cut_copper" → "cut_copper". */
	static String family(String id) {
		String name = "_" + id + "_";
		for (List<String> words : List.of(COLORS, WOODS, WEATHER)) {
			for (String word : words) name = name.replace("_" + word + "_", "_");
		}
		name = name.replaceAll("^_+|_+$", "");
		if (name.isEmpty()) return id;
		// Concrete powder is concrete before it sets, and wood is a log with bark all around: the same colors.
		return switch (name) {
			case "concrete_powder" -> "concrete";
			case "wood" -> "log";
			case "hyphae" -> "stem";
			case "stripped_wood" -> "stripped_log";
			case "stripped_hyphae" -> "stripped_stem";
			default -> name;
		};
	}

	/** A block that makes a good start for a random palette (not see-through, not too busy or patterned). */
	private static String randomBase(Map<String, Shade> all) {
		List<String> good = all.entrySet().stream().filter(e -> !e.getValue().seeThrough() && e.getValue().contrast() < 20
				&& !NEVER.contains(e.getKey()) && LESS_USED.stream().noneMatch(e.getKey()::contains)).map(Map.Entry::getKey).toList();
		return good.isEmpty() ? null : good.get(RNG.nextInt(good.size()));
	}

	/** The full block whose colors we know for a block id: itself, or oak stairs → oak planks. Null if none. */
	static String fullBlock(String id) {
		Map<String, Shade> all = shades();
		if (all.containsKey(id)) return id;
		for (String suffix : PART_SUFFIXES) {
			if (!id.endsWith(suffix)) continue;
			String stem = id.substring(0, id.length() - suffix.length());
			for (String full : FULL_SUFFIXES) {
				if (all.containsKey(stem + full)) return stem + full;
			}
		}
		// Logs lying down, wood with bark… the same name with the "_log"/"_wood" ending swapped.
		for (String full : FULL_SUFFIXES) {
			if (all.containsKey(id.replaceAll("_(log|wood|stem|hyphae)$", "") + full)) return id.replaceAll("_(log|wood|stem|hyphae)$", "") + full;
		}
		return null;
	}

	/** The full block an exact block name means ("stone", not "diamonds"), or null when it isn't one we know. */
	static String fullBlockByExactName(String asked) {
		List<String> words = singular(SearchIndex.tokenize(asked));
		String id = names().entrySet().stream().filter(e -> singular(SearchIndex.tokenize(e.getValue())).equals(words))
				.map(Map.Entry::getKey).findFirst().orElse(null);
		return id == null ? null : fullBlock(id);
	}

	/** The block a name means ("deepslate bricks", "oak stairs"), by its English name; exact names first. Null if none. */
	static String byName(String asked) {
		List<String> words = singular(SearchIndex.tokenize(asked));
		if (words.isEmpty()) return null;
		String best = null;
		int bestLength = Integer.MAX_VALUE;
		boolean bestKnown = false;
		for (Map.Entry<String, String> e : names().entrySet()) {
			List<String> own = singular(SearchIndex.tokenize(e.getValue()));
			if (own.equals(words)) return e.getKey();
			if (!own.containsAll(words)) continue;
			// "oak" means oak planks rather than an oak button: blocks we know the colors of first, then short names.
			boolean known = shades().containsKey(e.getKey());
			if ((known && !bestKnown) || (known == bestKnown && e.getValue().length() < bestLength)) {
				best = e.getKey();
				bestLength = e.getValue().length();
				bestKnown = known;
			}
		}
		return best;
	}

	private static List<String> singular(List<String> words) {
		return words.stream().map(w -> w.length() > 3 && w.endsWith("s") && !w.endsWith("ss") ? w.substring(0, w.length() - 1) : w).toList();
	}

	private static String lookedAt(ServerPlayer player) {
		HitResult hit = player.pick(REACH, 0, false);
		if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) return null;
		return id(player.level().getBlockState(block.getBlockPos()).getBlock());
	}

	private static String held(ServerPlayer player) {
		if (player.getMainHandItem().getItem() instanceof BlockItem item) return id(item.getBlock());
		if (player.getOffhandItem().getItem() instanceof BlockItem item) return id(item.getBlock());
		return null;
	}

	private static String id(Block block) {
		Identifier key = BuiltInRegistries.BLOCK.getKey(block);
		return key.getNamespace().equals("minecraft") ? key.getPath() : key.toString();
	}

	static String name(String id) {
		String known = names().get(id);
		return known != null ? known : MerlRecipes.readable(Component.literal(id.replace('_', ' ')));
	}

	/** English block names by id (vanilla ids without "minecraft:"). */
	private static Map<String, String> names() {
		Map<String, String> loaded = names;
		if (loaded != null) return loaded;
		Map<String, String> out = new LinkedHashMap<>();
		for (Block block : BuiltInRegistries.BLOCK) out.put(id(block), MerlRecipes.readable(block.getName()));
		names = out;
		return out;
	}

	static Map<String, Shade> shades() {
		Map<String, Shade> loaded = shades;
		if (loaded != null) return loaded;
		Map<String, Shade> out = new LinkedHashMap<>();
		try (InputStream in = MerlPalette.class.getResourceAsStream("/nicemerl/block_colors.json")) {
			if (in != null) {
				try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
					JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
					for (Map.Entry<String, JsonElement> e : json.entrySet()) {
						JsonObject o = e.getValue().getAsJsonObject();
						var lab = o.getAsJsonArray("lab");
						out.put(e.getKey(), new Shade(lab.get(0).getAsDouble(), lab.get(1).getAsDouble(), lab.get(2).getAsDouble(),
								o.get("contrast").getAsDouble(), o.has("seeThrough") && o.get("seeThrough").getAsBoolean(),
								Integer.parseInt(o.get("color").getAsString().substring(1), 16)));
					}
				}
			}
		} catch (IOException | RuntimeException e) {
			NiceMerl.LOGGER.warn("Could not read the block colors for palettes: {}", e.toString());
		}
		shades = out;
		return out;
	}
}

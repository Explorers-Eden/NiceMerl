package eu.explorerseden.nicemerl;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.mojang.datafixers.util.Pair;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.WorldgenRandom;

import me.lucko.fabric.api.permissions.v0.Permissions;

/**
 * "Where's the closest cherry grove?" and "where's a slime chunk?": Merl looks it up in the world and
 * answers with coordinates. Biomes are searched like /locate biome, off the server thread.
 */
public final class MerlLocate {
	public static final String PERMISSION_LOCATE = "nicemerl.locate";
	/** How far biomes are searched, in blocks, and the steps between samples, like /locate. */
	private static final int BIOME_RADIUS = 6400;
	private static final int HORIZONTAL_STEP = 32;
	private static final int VERTICAL_STEP = 64;
	/** How far slime chunks are searched, in chunks (one in ten chunks is one, so this is plenty). */
	private static final int SLIME_RADIUS = 32;
	/** Slimes spawn in slime chunks below this height. */
	private static final int SLIME_MAX_Y = 40;
	private static final long SLIME_SALT = 987234911L;
	private static final TextColor COORDS_COLOR = TextColor.fromRgb(0xFFD966);
	/** Biomes whose height matters, so Merl gives the Y too. */
	private static final TagKey<Biome> UNDERGROUND = TagKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("c", "is_underground"));

	private static volatile BiomeNames names;
	private static volatile MinecraftServer namesFor;

	private MerlLocate() {}

	/** Every biome on the server with its English name, built once per server. */
	static BiomeNames names(MinecraftServer server) {
		if (namesFor != server || names == null) {
			Map<String, String> biomes = new LinkedHashMap<>();
			Language language = Language.getInstance();
			for (Identifier id : server.registryAccess().lookupOrThrow(Registries.BIOME).keySet()) {
				String key = "biome." + id.getNamespace() + "." + id.getPath().replace('/', '.');
				biomes.put(id.toString(), language.has(key) ? language.getOrDefault(key) : null);
			}
			List<String> dimensions = new java.util.ArrayList<>();
			for (ServerLevel level : server.getAllLevels()) dimensions.add(level.dimension().identifier().toString());
			names = new BiomeNames(biomes, dimensions);
			namesFor = server;
		}
		return names;
	}

	/**
	 * Answers the question if it asks where a biome or slime chunk is, and returns true; otherwise
	 * returns false and the question goes to the wiki as usual.
	 */
	static boolean handle(CommandSourceStack source, String question, Consumer<Component> reply) {
		MerlConfig config = NiceMerl.config();
		if (!config.locateBiomes && !config.locateSlimeChunks) return false;
		if (!Permissions.check(source, PERMISSION_LOCATE, true)) return false;
		BiomeNames biomeNames = names(source.getServer());
		BiomeNames.Request request = biomeNames.parse(question);
		if (request == null) return false;
		if (request.slime() && !config.locateSlimeChunks || !request.slime() && !config.locateBiomes) return false;
		ServerLevel level = source.getLevel();
		BlockPos from = BlockPos.containing(source.getPosition());
		String user = source.getTextName();
		if (request.slime()) {
			reply.accept(slimeChunk(level, from, request.here(), user, canTeleport(source)));
			return true;
		}

		Registry<Biome> registry = source.getServer().registryAccess().lookupOrThrow(Registries.BIOME);
		Set<Holder<Biome>> wanted = new HashSet<>();
		for (String id : request.biomes()) registry.get(Identifier.parse(id)).ifPresent(wanted::add);
		String asked = biomeNames.display(request.biomes().get(0));
		Holder<Biome> standingIn = level.getBiome(from);
		if (wanted.contains(standingIn)) {
			reply.accept(Component.literal(MerlLines.pick("locate_here", "biome", name(biomeNames, standingIn), "user", user)));
			return true;
		}
		Set<Holder<Biome>> possible = level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes();
		if (wanted.stream().noneMatch(possible::contains)) {
			// It grows somewhere else, like the Nether or the Deep Blue.
			for (ServerLevel other : source.getServer().getAllLevels()) {
				if (other.getChunkSource().getGenerator().getBiomeSource().possibleBiomes().stream().anyMatch(wanted::contains)) {
					reply.accept(Component.literal(MerlLines.pick("locate_other_dimension", "biome", asked,
							"dimension", dimensionName(other.dimension().identifier()), "user", user)));
					return true;
				}
			}
			reply.accept(Component.literal(MerlLines.pick("locate_never", "biome", asked, "user", user)));
			return true;
		}

		reply.accept(Component.literal(MerlLines.pick("locate_looking")).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
		boolean teleport = canTeleport(source);
		MinecraftServer server = source.getServer();
		// Biome sampling is pure world-generation math, made to run off the server thread.
		NiceMerl.lookupAsync(() -> level.findClosestBiome3d(wanted::contains, from, BIOME_RADIUS, HORIZONTAL_STEP, VERTICAL_STEP),
				found -> server.execute(() -> {
					if (found == null) {
						reply.accept(Component.literal(MerlLines.pick("locate_far", "biome", asked,
								"distance", String.format(Locale.ROOT, "%,d", BIOME_RADIUS), "user", user)));
						return;
					}
					BlockPos pos = found.getFirst();
					Holder<Biome> biome = found.getSecond();
					boolean height = biome.is(UNDERGROUND) || biome.unwrapKey().map(k -> k.identifier().getPath())
							.filter(p -> p.contains("cave") || p.contains("cavern") || p.contains("deep_dark") || p.contains("underground")).isPresent();
					reply.accept(found(MerlLines.pick("locate_found", "biome", name(biomeNames, biome), "user", user,
							"distance", distance(from, pos), "direction", BiomeNames.direction(pos.getX() - from.getX(), pos.getZ() - from.getZ())),
							pos.getX(), height ? pos.getY() : null, pos.getZ(), teleport, name(biomeNames, biome)));
				}));
		return true;
	}

	/** "Where's my bed?" / "where did I die?": the respawn point or last death, with distance and [Guide me]. */
	static Component home(ServerPlayer player, String kind) {
		String user = player.getName().getString();
		net.minecraft.core.GlobalPos target;
		if (kind.equals("death")) {
			target = player.getLastDeathLocation().orElse(null);
			if (target == null) return Component.literal(MerlLines.pick("death_none", "user", user));
		} else {
			ServerPlayer.RespawnConfig respawn = player.getRespawnConfig();
			if (respawn == null) return Component.literal(MerlLines.pick("bed_none", "user", user));
			target = respawn.respawnData().globalPos();
		}
		if (!target.dimension().equals(player.level().dimension())) {
			return Component.literal(MerlLines.pick(kind + "_other_dimension", "user", user,
					"dimension", dimensionName(target.dimension().identifier())));
		}
		BlockPos pos = target.pos(), from = player.blockPosition();
		String label = kind.equals("death") ? "where you died" : "your bed";
		return found(MerlLines.pick(kind + "_found", "user", user, "distance", distance(from, pos),
				"direction", BiomeNames.direction(pos.getX() - from.getX(), pos.getZ() - from.getZ())),
				pos.getX(), pos.getY(), pos.getZ(), canTeleport(player.createCommandSourceStack()), label);
	}

	private static Component slimeChunk(ServerLevel level, BlockPos from, boolean here, String user, boolean teleport) {
		if (level.dimension() != Level.OVERWORLD) {
			return Component.literal(MerlLines.pick("slime_wrong_dimension", "user", user));
		}
		ChunkPos start = ChunkPos.containing(from);
		long seed = level.getSeed();
		if (isSlimeChunk(start.x(), start.z(), seed)) {
			return Component.literal(MerlLines.pick("slime_here", "y", String.valueOf(SLIME_MAX_Y), "user", user));
		}
		if (here) {
			// "is this a slime chunk?": no, but here's the closest one.
			ChunkPos closest = closestSlimeChunk(start, from, seed);
			return closest == null ? Component.literal(MerlLines.pick("slime_not_here", "user", user))
					: slimeAnswer("slime_not_here_found", closest, from, user, teleport);
		}
		ChunkPos closest = closestSlimeChunk(start, from, seed);
		return closest == null ? Component.literal(MerlLines.pick("slime_none", "user", user))
				: slimeAnswer("slime_found", closest, from, user, teleport);
	}

	private static Component slimeAnswer(String pool, ChunkPos chunk, BlockPos from, String user, boolean teleport) {
		int x = chunk.getMiddleBlockX(), z = chunk.getMiddleBlockZ();
		BlockPos middle = new BlockPos(x, from.getY(), z);
		MutableComponent answer = found(MerlLines.pick(pool, "user", user, "distance", distance(from, middle),
				"direction", BiomeNames.direction(x - from.getX(), z - from.getZ())), x, null, z, teleport, null);
		return answer.append(Component.literal(" " + MerlLines.pick("slime_hint", "y", String.valueOf(SLIME_MAX_Y),
				"x1", String.valueOf(chunk.getMinBlockX()), "z1", String.valueOf(chunk.getMinBlockZ()),
				"x2", String.valueOf(chunk.getMaxBlockX()), "z2", String.valueOf(chunk.getMaxBlockZ())))
				.withStyle(ChatFormatting.GRAY)).append(NiceMerl.config().particleGuide ? MerlGuide.offer(x, null, z, "the slime chunk") : Component.empty());
	}

	/** The slime chunk whose middle is closest to the position, or null if there's none nearby. */
	static ChunkPos closestSlimeChunk(ChunkPos start, BlockPos from, long seed) {
		ChunkPos best = null;
		double bestDistance = Double.MAX_VALUE;
		for (int dx = -SLIME_RADIUS; dx <= SLIME_RADIUS; dx++) {
			for (int dz = -SLIME_RADIUS; dz <= SLIME_RADIUS; dz++) {
				int x = start.x() + dx, z = start.z() + dz;
				if (!isSlimeChunk(x, z, seed)) continue;
				double ex = x * 16 + 8 - from.getX(), ez = z * 16 + 8 - from.getZ();
				double d = ex * ex + ez * ez;
				if (d < bestDistance) {
					best = new ChunkPos(x, z);
					bestDistance = d;
				}
			}
		}
		return best;
	}

	/** The same rule Minecraft uses when spawning slimes. */
	static boolean isSlimeChunk(int x, int z, long seed) {
		return WorldgenRandom.seedSlimeChunk(x, z, seed, SLIME_SALT).nextInt(10) == 0;
	}

	/** The line, then the coordinates: click to copy them, or (for operators) to fill in a /tp. */
	static MutableComponent found(String line, int x, Integer y, int z, boolean teleport, String label) {
		String shown = y != null ? "X " + x + ", Y " + y + ", Z " + z : "X " + x + ", Z " + z;
		String copy = y != null ? x + " " + y + " " + z : x + " ~ " + z;
		Style style = Style.EMPTY.withColor(COORDS_COLOR).withUnderlined(true);
		style = teleport
				? style.withClickEvent(new ClickEvent.SuggestCommand("/tp @s " + copy))
						.withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to teleport there")))
				: style.withClickEvent(new ClickEvent.CopyToClipboard(copy))
						.withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to copy")));
		MutableComponent answer = Component.literal(line + " ").append(Component.literal(shown).withStyle(style));
		return label != null && NiceMerl.config().particleGuide ? answer.append(MerlGuide.offer(x, y, z, label)) : answer;
	}

	static boolean canTeleport(CommandSourceStack source) {
		return source.getPlayer() != null && Permissions.check(source, "minecraft.command.teleport", PermissionLevel.GAMEMASTERS);
	}

	private static String distance(BlockPos from, BlockPos to) {
		double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
		return String.format(Locale.ROOT, "%,d", Math.round(Math.sqrt(dx * dx + dz * dz)));
	}

	private static String name(BiomeNames names, Holder<Biome> biome) {
		return biome.unwrapKey().map(k -> names.display(k.identifier().toString())).orElse("biome");
	}

	/** "the Nether", "the End", or the dimension's name ("the Deep Blue"). */
	static String dimensionName(Identifier id) {
		String path = id.getPath().replace('_', ' ');
		if (id.getNamespace().equals("minecraft") && path.equals("overworld")) return "the Overworld";
		if (id.getNamespace().equals("minecraft") && path.equals("the nether")) return "the Nether";
		if (id.getNamespace().equals("minecraft") && path.equals("the end")) return "the End";
		StringBuilder out = new StringBuilder("the");
		for (String w : path.split(" ")) {
			if (!w.isEmpty() && !w.equals("the")) out.append(' ').append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
		}
		return out.toString();
	}
}

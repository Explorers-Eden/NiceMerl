package eu.explorerseden.nicemerl;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;

/**
 * "Where's my claim?": the closest claim the player owns or is a member of (trusted on), one by its owner
 * ("Steve's claim") or by its name or anchor ("my diamond claim"). Claims come from the Nice Control Center's
 * land claims when it has them switched on, else from Get Off My Lawn ReServed. Both are optional and used
 * through reflection: without either, claim questions just go to the wiki.
 */
public final class MerlClaims {
	/** A claim as a box (inclusive block corners); y is a height to point at. */
	record Claim(int minX, int minZ, int maxX, int maxZ, int y, Identifier dimension, String ownerName, boolean mine, String label) {
		int x() {
			return Math.floorDiv(minX + maxX, 2);
		}

		int z() {
			return Math.floorDiv(minZ + maxZ, 2);
		}

		boolean contains(int px, int pz) {
			return px >= minX && px <= maxX && pz >= minZ && pz <= maxZ;
		}
	}

	/** The Nice Control Center's ClaimsApi (enabled(), claimsOf(UUID)); null when it isn't installed. */
	private static volatile Method[] ncc;
	private static volatile boolean nccLooked;

	private static Method[] ncc() {
		if (nccLooked) return ncc;
		nccLooked = true;
		if (!FabricLoader.getInstance().isModLoaded("nicecontrolcenter")) return null;
		try {
			Class<?> api = Class.forName("eu.explorerseden.nicecontrolcenter.api.ClaimsApi", true, MerlClaims.class.getClassLoader());
			ncc = new Method[] { api.getMethod("enabled"), api.getMethod("claimsOf", UUID.class) };
			NiceMerl.LOGGER.info("Nice Control Center found: Merl can point players to their claims");
		} catch (ReflectiveOperationException | LinkageError e) {
			// An older Nice Control Center without claims.
		}
		return ncc;
	}

	/** The Nice Control Center's claims are switched on. */
	private static boolean nccActive() {
		Method[] m = ncc();
		try {
			return m != null && (Boolean) m[0].invoke(null);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return false;
		}
	}

	/** GOML's methods, looked up once; null when GOML isn't installed or its API changed. */
	private static volatile Api api;
	private static volatile boolean looked;

	private record Api(Method owned, Method withAccess, Method forEach, Method value, Method origin, Method owners,
			Method claimBox, Method radius, Method type) {}

	private MerlClaims() {}

	private static Api api() {
		if (looked) return api;
		looked = true;
		if (!FabricLoader.getInstance().isModLoaded("goml")) return null;
		try {
			ClassLoader loader = MerlClaims.class.getClassLoader();
			Class<?> utils = Class.forName("draylar.goml.api.ClaimUtils", true, loader);
			Class<?> claim = Class.forName("draylar.goml.api.Claim", true, loader);
			Class<?> box = Class.forName("draylar.goml.api.ClaimBox", true, loader);
			Class<?> selection = Class.forName("com.jamieswhiteshirt.rtree3i.Selection", true, loader);
			Class<?> entry = Class.forName("com.jamieswhiteshirt.rtree3i.Entry", true, loader);
			api = new Api(utils.getMethod("getClaimsOwnedBy", LevelReader.class, UUID.class),
					utils.getMethod("getClaimsWithAccess", LevelReader.class, UUID.class),
					selection.getMethod("forEach", Consumer.class), entry.getMethod("getValue"),
					claim.getMethod("getOrigin"), claim.getMethod("getOwners"), claim.getMethod("getClaimBox"),
					box.getMethod("getRadius"), claim.getMethod("getType"));
			NiceMerl.LOGGER.info("Get Off My Lawn found: Merl can point players to their claims");
		} catch (ReflectiveOperationException | LinkageError e) {
			NiceMerl.LOGGER.warn("Get Off My Lawn's claims can't be read (its API changed?): {}", e.toString());
		}
		return api;
	}

	static boolean available() {
		return nccActive() || api() != null;
	}

	/** Every claim the player owns or (unless onlyMine) is a member of, in every dimension. */
	static List<Claim> claims(MinecraftServer server, UUID player, boolean onlyMine) {
		if (nccActive()) return nccClaims(server, player, onlyMine);
		return gomlClaims(server, player, onlyMine);
	}

	@SuppressWarnings("unchecked")
	private static List<Claim> nccClaims(MinecraftServer server, UUID player, boolean onlyMine) {
		List<Claim> out = new ArrayList<>();
		try {
			for (Map<String, Object> c : (List<Map<String, Object>>) ncc()[1].invoke(null, player)) {
				boolean mine = Boolean.TRUE.equals(c.get("mine"));
				if (onlyMine && !mine) continue;
				Identifier dim = Identifier.tryParse(String.valueOf(c.get("dimension")));
				if (dim == null) continue;
				int minX = (Integer) c.get("minX"), minZ = (Integer) c.get("minZ"), maxX = (Integer) c.get("maxX"), maxZ = (Integer) c.get("maxZ");
				out.add(new Claim(minX, minZ, maxX, maxZ, surface(server, dim, Math.floorDiv(minX + maxX, 2), Math.floorDiv(minZ + maxZ, 2)), dim,
						String.valueOf(c.get("ownerName")), mine, String.valueOf(c.get("name"))));
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			NiceMerl.LOGGER.debug("Could not read the Nice Control Center's claims", e);
		}
		return out;
	}

	/** The ground at a spot when its chunk is loaded, else sea level (claims reach from bedrock to the sky). */
	private static int surface(MinecraftServer server, Identifier dim, int x, int z) {
		ServerLevel level = server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dim));
		if (level == null) return 64;
		if (!level.hasChunk(x >> 4, z >> 4)) return level.getSeaLevel();
		return level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
	}

	@SuppressWarnings("unchecked")
	private static List<Claim> gomlClaims(MinecraftServer server, UUID player, boolean onlyMine) {
		Api a = api();
		List<Claim> out = new ArrayList<>();
		if (a == null) return out;
		for (ServerLevel level : server.getAllLevels()) {
			try {
				Object selection = (onlyMine ? a.owned() : a.withAccess()).invoke(null, level, player);
				List<Object> found = new ArrayList<>();
				a.forEach().invoke(selection, (Consumer<Object>) found::add);
				for (Object entry : found) {
					Object claim = a.value().invoke(entry);
					Set<UUID> owners = (Set<UUID>) a.owners().invoke(claim);
					int radius = (int) a.radius().invoke(a.claimBox().invoke(claim));
					Object type = a.type().invoke(claim);
					String anchor = type instanceof Block block ? MerlRecipes.readable(block.getName()) : "";
					BlockPos origin = (BlockPos) a.origin().invoke(claim);
					String owner = owners.stream().map(id -> ownerName(server, id)).filter(n -> !n.isEmpty()).findFirst().orElse("someone");
					out.add(new Claim(origin.getX() - radius, origin.getZ() - radius, origin.getX() + radius, origin.getZ() + radius,
							origin.getY(), level.dimension().identifier(), owner, owners.contains(player), anchor));
				}
			} catch (ReflectiveOperationException | RuntimeException e) {
				NiceMerl.LOGGER.debug("Could not read claims in {}", level.dimension().identifier(), e);
			}
		}
		return out;
	}

	/** Merl's answer to a claim question ("mine", "trusted" or "any"), or null without GOML. */
	static Component answer(ServerPlayer player, String kind, String question, boolean guide) {
		if (!available()) return null;
		MinecraftServer server = player.level().getServer();
		String user = player.getName().getString();
		List<Claim> claims = claims(server, player.getUUID(), kind.equals("mine"));
		if (kind.equals("trusted")) claims = claims.stream().filter(c -> !c.mine()).toList();
		// "Steve's claim" or "my diamond claim" / "my farm claim" narrows it down.
		String text = question.toLowerCase(Locale.ROOT);
		List<Claim> named = claims.stream().filter(c -> !c.mine() && !c.ownerName().isEmpty()
				&& text.contains(c.ownerName().toLowerCase(Locale.ROOT))).toList();
		if (named.isEmpty()) {
			named = claims.stream().filter(c -> !c.label().isEmpty()
					&& text.contains(c.label().toLowerCase(Locale.ROOT).split(" ")[0])).toList();
		}
		if (!named.isEmpty()) claims = named;
		if (claims.isEmpty()) return Component.literal(MerlLines.pick("claim_none_" + kind, "user", user));

		Identifier here = player.level().dimension().identifier();
		BlockPos from = player.blockPosition();
		List<Claim> nearby = claims.stream().filter(c -> c.dimension().equals(here))
				.sorted(Comparator.comparingDouble(c -> distSqr(c, from))).toList();
		if (nearby.isEmpty()) {
			return Component.literal(MerlLines.pick("claim_other_dimension", "count", String.valueOf(claims.size()),
					"dimension", MerlLocate.dimensionName(claims.get(0).dimension()), "user", user));
		}
		Claim claim = nearby.get(0);
		String owner = claim.mine() ? user : claim.ownerName();
		double dx = claim.x() - from.getX(), dz = claim.z() - from.getZ();
		if (claim.contains(from.getX(), from.getZ())) {
			return Component.literal(MerlLines.pick(claim.mine() ? "claim_inside_mine" : "claim_inside_trusted",
					"owner", owner, "user", user));
		}
		String line = MerlLines.pick(claim.mine() ? "claim_found_mine" : "claim_found_trusted", "owner", owner,
				"user", user, "count", String.valueOf(claims.size()),
				"distance", String.format(Locale.ROOT, "%,d", Math.round(Math.sqrt(dx * dx + dz * dz))),
				"direction", BiomeNames.direction(dx, dz));
		String label = claim.mine() ? "your claim" : owner + "'s claim";
		var answer = MerlLocate.found(line, claim.x(), claim.y(), claim.z(),
				MerlLocate.canTeleport(player.createCommandSourceStack()), guide ? null : label);
		if (guide && NiceMerl.config().particleGuide) {
			answer.append(MerlGuide.startNow(player, claim.x(), (double) claim.y(), claim.z(), label));
		}
		return answer;
	}

	/** Distance to the nearest edge of the claim (0 inside). */
	private static double distSqr(Claim c, BlockPos p) {
		double dx = Math.max(0, Math.max(c.minX() - p.getX(), p.getX() - c.maxX()));
		double dz = Math.max(0, Math.max(c.minZ() - p.getZ(), p.getZ() - c.maxZ()));
		return dx * dx + dz * dz;
	}

	private static String ownerName(MinecraftServer server, UUID id) {
		ServerPlayer online = server.getPlayerList().getPlayer(id);
		if (online != null) return online.getName().getString();
		return server.services().nameToIdCache().get(id).map(n -> n.name()).orElse("");
	}
}

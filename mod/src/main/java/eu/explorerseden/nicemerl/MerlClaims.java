package eu.explorerseden.nicemerl;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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
 * "Where's my claim?" with Get Off My Lawn ReServed: the closest claim the player owns or is trusted on, one by
 * its owner ("Steve's claim") or by its anchor ("my diamond claim"). GOML is optional, so it's used through
 * reflection: without it, claim questions just go to the wiki.
 */
public final class MerlClaims {
	record Claim(BlockPos origin, int radius, Identifier dimension, Set<UUID> owners, boolean mine, String anchor) {}

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
		return api() != null;
	}

	/** Every claim the player owns or (unless onlyMine) is trusted on, in every dimension. */
	@SuppressWarnings("unchecked")
	static List<Claim> claims(MinecraftServer server, UUID player, boolean onlyMine) {
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
					out.add(new Claim((BlockPos) a.origin().invoke(claim), radius, level.dimension().identifier(), owners,
							owners.contains(player), anchor));
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
		// "Steve's claim" or "my diamond claim" narrows it down.
		String text = question.toLowerCase(Locale.ROOT);
		List<Claim> named = claims.stream().filter(c -> c.owners().stream().map(id -> ownerName(server, id))
				.anyMatch(n -> !n.isEmpty() && text.contains(n.toLowerCase(Locale.ROOT)))).toList();
		if (named.isEmpty()) {
			named = claims.stream().filter(c -> !c.anchor().isEmpty()
					&& text.contains(c.anchor().toLowerCase(Locale.ROOT).split(" ")[0])).toList();
		}
		if (!named.isEmpty()) claims = named;
		if (claims.isEmpty()) return Component.literal(MerlLines.pick("claim_none_" + kind, "user", user));

		Identifier here = player.level().dimension().identifier();
		BlockPos from = player.blockPosition();
		List<Claim> nearby = claims.stream().filter(c -> c.dimension().equals(here))
				.sorted(Comparator.comparingDouble(c -> c.origin().distSqr(from))).toList();
		if (nearby.isEmpty()) {
			return Component.literal(MerlLines.pick("claim_other_dimension", "count", String.valueOf(claims.size()),
					"dimension", MerlLocate.dimensionName(claims.get(0).dimension()), "user", user));
		}
		Claim claim = nearby.get(0);
		String owner = claim.mine() ? user : claim.owners().stream().map(id -> ownerName(server, id))
				.filter(n -> !n.isEmpty()).findFirst().orElse("someone");
		double dx = claim.origin().getX() - from.getX(), dz = claim.origin().getZ() - from.getZ();
		if (Math.abs(dx) <= claim.radius() && Math.abs(dz) <= claim.radius()) {
			return Component.literal(MerlLines.pick(claim.mine() ? "claim_inside_mine" : "claim_inside_trusted",
					"owner", owner, "user", user));
		}
		String line = MerlLines.pick(claim.mine() ? "claim_found_mine" : "claim_found_trusted", "owner", owner,
				"user", user, "count", String.valueOf(claims.size()),
				"distance", String.format(Locale.ROOT, "%,d", Math.round(Math.sqrt(dx * dx + dz * dz))),
				"direction", BiomeNames.direction(dx, dz));
		String label = claim.mine() ? "your claim" : owner + "'s claim";
		var answer = MerlLocate.found(line, claim.origin().getX(), claim.origin().getY(), claim.origin().getZ(),
				MerlLocate.canTeleport(player.createCommandSourceStack()), guide ? null : label);
		if (guide && NiceMerl.config().particleGuide) {
			answer.append(MerlGuide.startNow(player, claim.origin().getX(), (double) claim.origin().getY(), claim.origin().getZ(), label));
		}
		return answer;
	}

	private static String ownerName(MinecraftServer server, UUID id) {
		ServerPlayer online = server.getPlayerList().getPlayer(id);
		if (online != null) return online.getName().getString();
		return server.services().nameToIdCache().get(id).map(n -> n.name()).orElse("");
	}
}

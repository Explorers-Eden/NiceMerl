package eu.explorerseden.nicemerl;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.datafixers.util.Pair;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * Merl herself as the guide: she walks along the path a few steps ahead of the player, holding a map, waits when
 * they fall behind and looks at them when they arrive. She only exists on that player's screen (the server sends
 * them the packets of a mannequin that isn't in the world), so nobody else sees her and nothing is left behind.
 */
final class MerlGuideNpc {
	/** She walks this many path steps ahead of the player. */
	private static final int LEAD_STEPS = 5;
	/** Walking speed, and a faster one to catch up, in blocks per tick. */
	private static final double WALK = 0.2, CATCH_UP = 0.4;
	/** She waits when the player is further away than this. */
	private static final double WAIT_DISTANCE = 6.0;
	/** Further than this from where she should be (a new path, a teleport), she just appears there. */
	private static final double SNAP_DISTANCE = 10.0;
	/** After the player arrives she looks at them this long before leaving, in ticks. */
	private static final int GOODBYE_TICKS = 60;

	private static final class Npc {
		ServerPlayer owner;
		Entity body;
		Vec3 pos;
		float yaw;
		/** The path step she's at, so the sparkles can stop at her. */
		int step;
		int goodbyeTicks = -1;
	}

	private static final Map<UUID, Npc> NPCS = new ConcurrentHashMap<>();

	private MerlGuideNpc() {}

	/** Where she is on the path (a step index), or -1 when she isn't there. */
	static int step(UUID player) {
		Npc npc = NPCS.get(player);
		return npc == null || npc.body == null ? -1 : npc.step;
	}

	/**
	 * Moves her one tick along the path. {@code nearest} is the step the player is at; an empty path leaves
	 * her where she is (flying, or no way found).
	 */
	static void update(ServerPlayer player, List<BlockPos> path, int nearest) {
		Npc npc = NPCS.computeIfAbsent(player.getUUID(), id -> new Npc());
		if (npc.goodbyeTicks >= 0) return;
		// A new player object (after respawning) or a new dimension needs her to be sent again.
		if (npc.owner != player) {
			if (npc.owner != null && npc.body != null && !npc.owner.hasDisconnected()) remove(npc);
			npc.owner = player;
			npc.body = null;
		}
		if (path == null || path.size() < 2 || nearest < 0) {
			if (npc.body != null && npc.pos.distanceTo(player.position()) > 24) remove(npc);
			return;
		}
		int goal = Math.min(path.size() - 1, nearest + LEAD_STEPS);
		BlockPos at = path.get(goal);
		Vec3 target = new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
		if (npc.body == null) {
			spawn(npc, player, target);
			npc.step = goal;
			return;
		}
		double behind = npc.pos.distanceTo(target);
		boolean playerFar = npc.pos.distanceTo(player.position()) > WAIT_DISTANCE && behind < SNAP_DISTANCE;
		Vec3 next = npc.pos;
		if (behind > SNAP_DISTANCE) {
			next = target;
		} else if (!playerFar && behind > 0.05) {
			double speed = behind > 3 ? CATCH_UP : WALK;
			next = npc.pos.add(target.subtract(npc.pos).normalize().scale(Math.min(speed, behind)));
		}
		boolean moving = next.distanceToSqr(npc.pos) > 1e-4;
		Vec3 facing = moving ? next.subtract(npc.pos) : player.getEyePosition().subtract(next.add(0, 1.62, 0));
		float yaw = (float) (Math.toDegrees(Math.atan2(facing.z, facing.x)) - 90.0);
		float pitch = moving ? 0f : (float) Math.max(-40, Math.min(40, -Math.toDegrees(Math.atan2(facing.y, Math.hypot(facing.x, facing.z)))));
		move(npc, next, yaw, pitch);
		npc.step = moving ? closestStep(path, next) : npc.step;
	}

	/** The player arrived: she turns to them for a moment, then leaves. */
	static void arrived(ServerPlayer player) {
		Npc npc = NPCS.get(player.getUUID());
		if (npc == null || npc.body == null) {
			NPCS.remove(player.getUUID());
			return;
		}
		npc.goodbyeTicks = GOODBYE_TICKS;
	}

	/** Called every tick for goodbyes in progress (the player no longer has a guide by then). */
	static void tickGoodbyes() {
		for (Map.Entry<UUID, Npc> entry : NPCS.entrySet()) {
			Npc npc = entry.getValue();
			if (npc.goodbyeTicks < 0) continue;
			if (npc.owner == null || npc.owner.hasDisconnected() || npc.goodbyeTicks-- <= 0) {
				if (npc.owner != null && !npc.owner.hasDisconnected() && npc.body != null) remove(npc);
				NPCS.remove(entry.getKey());
				continue;
			}
			Vec3 facing = npc.owner.getEyePosition().subtract(npc.pos.add(0, 1.62, 0));
			move(npc, npc.pos, (float) (Math.toDegrees(Math.atan2(facing.z, facing.x)) - 90.0), 0f);
		}
	}

	/** The guide was stopped (or the player left): she disappears right away. */
	static void stop(UUID player) {
		Npc npc = NPCS.remove(player);
		if (npc != null && npc.body != null && npc.owner != null && !npc.owner.hasDisconnected()) remove(npc);
	}

	private static void spawn(Npc npc, ServerPlayer player, Vec3 at) {
		Entity body = createBody(player);
		if (body == null) return;
		npc.body = body;
		npc.pos = at;
		npc.yaw = 0;
		var connection = player.connection;
		connection.send(new ClientboundAddEntityPacket(body.getId(), body.getUUID(), at.x, at.y, at.z, 0f, 0f,
				body.getType(), 0, Vec3.ZERO, 0.0));
		var data = body.getEntityData().getNonDefaultValues();
		if (data != null && !data.isEmpty()) connection.send(new ClientboundSetEntityDataPacket(body.getId(), data));
		connection.send(new ClientboundSetEquipmentPacket(body.getId(), List.of(Pair.of(EquipmentSlot.MAINHAND, map(player)),
				Pair.of(EquipmentSlot.OFFHAND, new ItemStack(Items.COMPASS)))));
	}

	/**
	 * A real filled map for her hand (one without a map id is drawn blank). It's made once, of the area around
	 * spawn, and reused for every guide.
	 */
	private static ItemStack map(ServerPlayer player) {
		ItemStack stack = new ItemStack(Items.FILLED_MAP);
		try {
			ServerLevel overworld = player.level().getServer().overworld();
			Integer known = MerlState.guideMap();
			if (known != null && MapItem.getSavedData(new MapId(known), overworld) != null) {
				stack.set(DataComponents.MAP_ID, new MapId(known));
				return stack;
			}
			BlockPos spawn = overworld.getRespawnData().pos();
			ItemStack made = MapItem.create(overworld, spawn.getX(), spawn.getZ(), (byte) 2, false, false);
			MapId id = made.get(DataComponents.MAP_ID);
			if (id != null) MerlState.setGuideMap(id.id());
			return made;
		} catch (RuntimeException e) {
			NiceMerl.LOGGER.debug("Could not make the guide map", e);
			return stack;
		}
	}

	private static void move(Npc npc, Vec3 to, float yaw, float pitch) {
		npc.pos = to;
		npc.yaw = yaw;
		var connection = npc.owner.connection;
		connection.send(new ClientboundEntityPositionSyncPacket(npc.body.getId(), PositionPath.of(to), yaw, pitch, true));
		connection.send(new ClientboundRotateHeadPacket(npc.body, (byte) Math.floor(yaw * 256.0f / 360.0f)));
	}

	private static void remove(Npc npc) {
		npc.owner.connection.send(new ClientboundRemoveEntitiesPacket(npc.body.getId()));
		npc.body = null;
	}

	/** A mannequin with Merl's skin that's never added to the world; only its id and data are sent. */

	private static Entity createBody(ServerPlayer player) {
		try {
			String profile = MerlMannequin.SKIN_TEXTURE.isEmpty() ? ""
					: ",profile:{properties:[{name:\"textures\",value:\"" + MerlMannequin.SKIN_TEXTURE + "\"}]}";
			CompoundTag tag = TagParser.parseCompoundFully("{hide_description:1b,immovable:1b" + profile + "}");
			EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace("mannequin"));
			return EntityType.loadEntityRecursive(type, tag, player.level(), EntitySpawnReason.LOAD, EntityProcessor.NOP);
		} catch (Exception e) {
			NiceMerl.LOGGER.warn("Could not create the guide Merl: {}", e.toString());
			return null;
		}
	}

	private static int closestStep(List<BlockPos> path, Vec3 pos) {
		int best = 0;
		double bestDistance = Double.MAX_VALUE;
		for (int i = 0; i < path.size(); i++) {
			BlockPos p = path.get(i);
			double d = pos.distanceToSqr(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
			if (d < bestDistance) {
				best = i;
				bestDistance = d;
			}
		}
		return best;
	}

	static Set<UUID> guided() {
		return NPCS.keySet();
	}
}

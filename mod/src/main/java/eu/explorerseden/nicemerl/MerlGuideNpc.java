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
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
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
 * they fall behind and looks at them when they arrive. When they glide she flies along with an elytra and rockets,
 * and when they fly in creative mode she floats ahead of them. She only exists on that player's screen (the server sends
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
	/** While the player flies, she stays this far ahead of them toward the target, plus a bit more the faster they go. */
	private static final double FLY_AHEAD = 6.0, FLY_AHEAD_PER_SPEED = 2.0;
	/** How much of the gap to where she should be she closes each tick, on top of matching the player's speed. */
	private static final double FLY_CATCH_UP = 0.1;
	/** How quickly her flying speed follows the speed she wants (the rest keeps her last speed), so she glides smoothly. */
	private static final double FLY_STEER = 0.4;
	/** The fastest she turns while flying, in degrees per tick. */
	private static final float FLY_TURN = 12f;
	/** The player's speed is averaged over this many ticks (their position reaches the server in uneven bursts). */
	private static final int SPEED_TICKS = 6;
	/** Further than this behind while flying, she just appears where she should be. */
	private static final double FLY_SNAP = 30.0;
	/** After the player fires a rocket she trails firework sparks this long, in ticks. */
	private static final int BOOST_TICKS = 25;
	/** ClientboundAnimatePacket action for swinging the off hand. */
	private static final int SWING_OFF_HAND = 3;
	/** Entity flag for gliding with an elytra (Entity.FLAG_FALL_FLYING). */
	private static final int FLAG_FALL_FLYING = 7;
	private static java.lang.reflect.Method setSharedFlag;

	private static final class Npc {
		ServerPlayer owner;
		Entity body;
		Vec3 pos;
		float yaw;
		/** The path step she's at, so the sparkles can stop at her. */
		int step;
		int goodbyeTicks = -1;
		/** On her feet, gliding with an elytra, or flying like in creative mode. */
		Air air = Air.WALKING;
		/** The player's last few positions and their average speed, and her own flying speed, in blocks per tick. */
		final java.util.ArrayDeque<Vec3> playerTrail = new java.util.ArrayDeque<>();
		Vec3 playerSpeed = Vec3.ZERO;
		Vec3 velocity = Vec3.ZERO;
		int boostTicks;
		/** Ticks the player has been too far behind, and when she last said something (in update ticks). */
		int waiting;
		long ticks;
		final Map<String, Long> lastSaid = new java.util.HashMap<>();
	}

	/** She says each kind of remark (taking off, landing, waiting) at most this often, in ticks. */
	private static final long REMARK_PAUSE = 400;
	/** She mentions waiting after the player has been behind this long, in ticks. */
	private static final int WAIT_BEFORE_REMARK = 60;

	private enum Air { WALKING, GLIDING, HOVERING }

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
	static void update(ServerPlayer player, List<BlockPos> path, int nearest, Vec3 destination) {
		Npc npc = NPCS.computeIfAbsent(player.getUUID(), id -> new Npc());
		if (npc.goodbyeTicks >= 0) return;
		npc.ticks++;
		// A new player object (after respawning) or a new dimension needs her to be sent again.
		if (npc.owner != player) {
			if (npc.owner != null && npc.body != null && !npc.owner.hasDisconnected()) remove(npc);
			npc.owner = player;
			npc.body = null;
			npc.air = Air.WALKING;
			npc.playerTrail.clear();
		}
		trackSpeed(npc, player);
		// The player glides or flies: she puts on an elytra (or just takes off) and flies ahead of them.
		if (player.isFallFlying() || player.getAbilities().flying) {
			fly(npc, player, destination, player.isFallFlying() ? Air.GLIDING : Air.HOVERING);
			return;
		}
		if (npc.air != Air.WALKING && npc.body != null) {
			setAir(npc, Air.WALKING);
			remark(npc, "guide_landing");
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
		npc.waiting = playerFar ? npc.waiting + 1 : 0;
		if (npc.waiting == WAIT_BEFORE_REMARK) remark(npc, "guide_wait");
		Vec3 next = npc.pos;
		if (behind > SNAP_DISTANCE) {
			next = target;
		} else if (!playerFar && behind > 0.05) {
			double speed = behind > 3 ? CATCH_UP : WALK;
			next = npc.pos.add(target.subtract(npc.pos).normalize().scale(Math.min(speed, behind)));
		}
		boolean moving = next.distanceToSqr(npc.pos) > 1e-4;
		Vec3 facing = moving ? next.subtract(npc.pos) : player.getEyePosition().subtract(next.add(0, 1.62, 0));
		float yaw = yaw(facing);
		float pitch = moving ? 0f : (float) Math.max(-40, Math.min(40, -Math.toDegrees(Math.atan2(facing.y, Math.hypot(facing.x, facing.z)))));
		move(npc, next, yaw, pitch);
		npc.step = moving ? closestStep(path, next) : npc.step;
	}

	/** The player's speed in blocks per tick, averaged over the last few ticks so their uneven updates don't shake her. */
	private static void trackSpeed(Npc npc, ServerPlayer player) {
		Vec3 here = player.position();
		// A teleport isn't speed.
		if (!npc.playerTrail.isEmpty() && npc.playerTrail.peekLast().distanceToSqr(here) > 100) npc.playerTrail.clear();
		npc.playerTrail.addLast(here);
		if (npc.playerTrail.size() > SPEED_TICKS) npc.playerTrail.removeFirst();
		npc.playerSpeed = npc.playerTrail.size() < 2 ? Vec3.ZERO
				: here.subtract(npc.playerTrail.peekFirst()).scale(1.0 / (npc.playerTrail.size() - 1));
	}

	/**
	 * Flying with the player, a few blocks ahead of them toward the destination: with an elytra and rockets while
	 * they glide, or floating upright while they fly in creative mode. She moves as fast as they do, so she
	 * doesn't fall behind, and faces the way she's going.
	 */
	private static void fly(Npc npc, ServerPlayer player, Vec3 destination, Air air) {
		Vec3 here = player.position();
		Vec3 toward = new Vec3(destination.x - here.x, 0, destination.z - here.z);
		double far = toward.length();
		double ahead = FLY_AHEAD + FLY_AHEAD_PER_SPEED * npc.playerSpeed.length();
		double lift = air == Air.GLIDING ? 0.5 : 0.3 + 0.15 * Math.sin(npc.ticks * 0.15);
		Vec3 wanted = (far < ahead ? new Vec3(destination.x, here.y, destination.z) : here.add(toward.scale(ahead / far))).add(0, lift, 0);
		if (npc.body == null) {
			spawn(npc, player, wanted);
			if (npc.body == null) return;
		}
		if (npc.air != air) {
			boolean wasWalking = npc.air == Air.WALKING;
			setAir(npc, air);
			if (wasWalking) remark(npc, air == Air.GLIDING ? "guide_takeoff" : "guide_hover");
		}
		Vec3 next;
		if (npc.pos.distanceTo(wanted) > FLY_SNAP) {
			next = wanted;
			npc.velocity = npc.playerSpeed;
		} else {
			// Smoothly toward the player's speed plus a bit of the gap, instead of jumping with every update.
			Vec3 wantedSpeed = npc.playerSpeed.add(wanted.subtract(npc.pos).scale(FLY_CATCH_UP));
			npc.velocity = npc.velocity.scale(1 - FLY_STEER).add(wantedSpeed.scale(FLY_STEER));
			next = npc.pos.add(npc.velocity);
		}
		Vec3 step = npc.velocity;
		boolean moving = step.horizontalDistanceSqr() > 0.0025;
		float yaw, pitch;
		if (moving) {
			yaw = turn(npc.yaw, yaw(step));
			pitch = air == Air.GLIDING ? (float) Math.max(-30, Math.min(45, -Math.toDegrees(Math.atan2(step.y, step.horizontalDistance())))) : 0f;
		} else if (air == Air.HOVERING) {
			// Floating in place: she looks at the player.
			Vec3 look = player.getEyePosition().subtract(next.add(0, 1.62, 0));
			yaw = yaw(look);
			pitch = (float) Math.max(-40, Math.min(40, -Math.toDegrees(Math.atan2(look.y, look.horizontalDistance()))));
		} else {
			yaw = far > 1e-3 ? yaw(toward) : npc.yaw;
			pitch = 0f;
		}
		move(npc, next, yaw, pitch);
		if (air == Air.GLIDING) {
			// Her real speed, so the client leans her into turns the way it does for players, not sideways.
			npc.owner.connection.send(new ClientboundSetEntityMotionPacket(npc.body.getId(), step));
			if (npc.boostTicks > 0) {
				npc.boostTicks--;
				Vec3 tail = next.subtract(step.normalize().scale(0.6));
				player.level().sendParticles(player, ParticleTypes.FIREWORK, true, false, tail.x, tail.y + 0.3, tail.z, 1, 0.05, 0.05, 0.05, 0.02);
			}
		}
	}

	/** The player fired a rocket while gliding: so does she. */
	static void boost(ServerPlayer player) {
		Npc npc = NPCS.get(player.getUUID());
		if (npc == null || npc.body == null || npc.air != Air.GLIDING || npc.goodbyeTicks >= 0) return;
		boolean first = npc.boostTicks == 0;
		npc.boostTicks = BOOST_TICKS;
		npc.owner.connection.send(new ClientboundAnimatePacket(npc.body, SWING_OFF_HAND));
		if (first && npc.owner.getRandom().nextInt(4) == 0) remark(npc, "guide_boost");
	}

	/** From one yaw toward another, at most FLY_TURN degrees. */
	private static float turn(float from, float to) {
		float change = net.minecraft.util.Mth.wrapDegrees(to - from);
		return from + Math.max(-FLY_TURN, Math.min(FLY_TURN, change));
	}

	private static float yaw(Vec3 direction) {
		return (float) (Math.toDegrees(Math.atan2(direction.z, direction.x)) - 90.0);
	}

	/** A short remark in chat ("Wait for me!"), each kind at most every 20 seconds. */
	private static void remark(Npc npc, String pool) {
		Long last = npc.lastSaid.get(pool);
		if (last != null && npc.ticks - last < REMARK_PAUSE) return;
		npc.lastSaid.put(pool, npc.ticks);
		MerlCommand.replyTo(npc.owner.createCommandSourceStack(),
				net.minecraft.network.chat.Component.literal(MerlLines.pick(pool, "user", npc.owner.getName().getString())));
	}

	/**
	 * Gliding (elytra on, a rocket in her off hand), flying like in creative mode, or back on her feet (map and
	 * compass in her hands).
	 */
	private static void setAir(Npc npc, Air air) {
		npc.air = air;
		npc.boostTicks = 0;
		npc.velocity = Vec3.ZERO;
		Entity body = npc.body;
		boolean gliding = air == Air.GLIDING;
		try {
			if (setSharedFlag == null) {
				setSharedFlag = Entity.class.getDeclaredMethod("setSharedFlag", int.class, boolean.class);
				setSharedFlag.setAccessible(true);
			}
			setSharedFlag.invoke(body, FLAG_FALL_FLYING, gliding);
		} catch (ReflectiveOperationException | RuntimeException e) {
			NiceMerl.LOGGER.debug("Could not set the guide Merl's gliding flag", e);
		}
		body.setPose(gliding ? net.minecraft.world.entity.Pose.FALL_FLYING : net.minecraft.world.entity.Pose.STANDING);
		var connection = npc.owner.connection;
		var dirty = body.getEntityData().packDirty();
		if (dirty != null && !dirty.isEmpty()) connection.send(new ClientboundSetEntityDataPacket(body.getId(), dirty));
		connection.send(new ClientboundSetEquipmentPacket(body.getId(), List.of(
				Pair.of(EquipmentSlot.CHEST, gliding ? new ItemStack(Items.ELYTRA) : ItemStack.EMPTY),
				Pair.of(EquipmentSlot.OFFHAND, new ItemStack(gliding ? Items.FIREWORK_ROCKET : Items.COMPASS)))));
		if (!gliding) connection.send(new ClientboundSetEntityMotionPacket(body.getId(), Vec3.ZERO));
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
			move(npc, npc.pos, yaw(facing), 0f);
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

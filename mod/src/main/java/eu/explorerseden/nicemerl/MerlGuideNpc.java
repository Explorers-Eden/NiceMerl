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
import net.minecraft.network.protocol.game.ClientboundSwingAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.FriendlyByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.Cushion;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
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
	/**
	 * Per block-per-tick of speed: what the player sees of her is about 5 ticks behind (the client smooths her
	 * movement over 3 ticks, and the player's position reaches the server a tick or two late), so the faster they
	 * go, the further ahead she has to be to still look ahead of them (rockets: about 1.7 blocks per tick).
	 */
	private static final double FLY_AHEAD = 6.0, FLY_AHEAD_PER_SPEED = 5.0;
	/** How much of the gap to where she should be she closes each tick, on top of matching the player's speed. */
	private static final double FLY_CATCH_UP = 0.1;
	/** Falling behind by more than FLY_BEHIND blocks, she closes the gap faster (up to FLY_CATCH_UP_MAX). */
	private static final double FLY_BEHIND = 2.0, FLY_CATCH_UP_MAX = 0.3;
	/** How quickly her flying speed follows the speed she wants (the rest keeps her last speed), so she glides smoothly. */
	private static final double FLY_STEER = 0.4;
	/** The fastest she turns while flying, in degrees per tick. */
	private static final float FLY_TURN = 12f;
	/** The player's speed is averaged over this many ticks (their position reaches the server in uneven bursts). */
	private static final int SPEED_TICKS = 6;
	/** Further than this behind while flying, she just appears where she should be. */
	private static final double FLY_SNAP = 30.0;
	/** After the player fires a rocket she trails firework sparks as long as it burns: about 10 ticks per flight duration level, plus 10. */
	private static final int BOOST_TICKS_PER_DURATION = 10;
	/** Entity flag for gliding with an elytra (Entity.FLAG_FALL_FLYING). */
	private static final int FLAG_FALL_FLYING = 7;
	private static java.lang.reflect.Method setSharedFlag;

	private static final class Npc {
		ServerPlayer owner;
		Entity body;
		/** Hide and seek: the cushion she sits on (packet-only too), or null. */
		Entity cushion;
		/** The size last sent for her (the client starts at 1), so she's always as big as the player. */
		double sentScale = 1.0;
		/** Companion Merl: ticks the player has stood still (she sits down on a cushion after a while). */
		int idleTicks;
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
		/** The outfit companion Merl wears now (null: the guide's map and compass), re-sent after she reappears. */
		String outfit;
		List<Pair<EquipmentSlot, ItemStack>> outfitItems;
		boolean outfitSent;
		/** Ticks the player has been too far behind, and when she last said something (in update ticks). */
		int waiting;
		long ticks;
		final Map<String, Long> lastSaid = new java.util.HashMap<>();
	}

	/** She says each kind of remark (taking off, landing, waiting) at most this often, in ticks. */
	private static final long REMARK_PAUSE = 400;
	/** Companion Merl sits down on a cushion after the player has stood still this long, in ticks. */
	private static final int SIT_AFTER_TICKS = 200;
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
		if (npc.cushion != null) standUp(npc);
		trackSpeed(npc, player);
		matchSize(npc, player);
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
			// No path yet (still searching, or none found): she waits next to the player instead of not being there.
			stayClose(npc, player);
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

	/** Beside the player, facing them; she walks over when they move away and appears there when far behind. */
	private static void stayClose(Npc npc, ServerPlayer player) {
		Vec3 spot = besidePlayer(player);
		if (npc.body == null) {
			spawn(npc, player, spot);
			return;
		}
		Vec3 next = npc.pos;
		double gap = npc.pos.distanceTo(spot);
		if (gap > SNAP_DISTANCE) {
			next = spot;
		} else if (gap > 2.5) {
			next = npc.pos.add(spot.subtract(npc.pos).normalize().scale(Math.min(gap > 4 ? CATCH_UP : WALK, gap)));
		}
		boolean moving = next.distanceToSqr(npc.pos) > 1e-4;
		Vec3 facing = moving ? next.subtract(npc.pos) : player.getEyePosition().subtract(next.add(0, 1.62, 0));
		float pitch = moving ? 0f : (float) Math.max(-40, Math.min(40, -Math.toDegrees(Math.atan2(facing.y, Math.hypot(facing.x, facing.z)))));
		move(npc, next, yaw(facing), pitch);
	}

	/** A spot a step to the player's right where she can stand, else to their left, else right where they are. */
	private static Vec3 besidePlayer(ServerPlayer player) {
		double yaw = Math.toRadians(player.getYRot());
		Vec3 right = new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw));
		for (double side : new double[] {1.5, -1.5}) {
			Vec3 at = player.position().add(right.scale(side));
			if (MerlPath.standable(player.level(), BlockPos.containing(at))) return at;
		}
		return player.position();
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
		if (npc.cushion != null) standUp(npc);
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
			Vec3 gap = wanted.subtract(npc.pos);
			double behind = gap.dot(npc.playerSpeed.lengthSqr() > 1e-4 ? npc.playerSpeed.normalize() : Vec3.ZERO);
			double catchUp = behind > FLY_BEHIND ? Math.min(FLY_CATCH_UP_MAX, FLY_CATCH_UP + (behind - FLY_BEHIND) * 0.05) : FLY_CATCH_UP;
			Vec3 wantedSpeed = npc.playerSpeed.add(gap.scale(catchUp));
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
	static void boost(ServerPlayer player, ItemStack rocket) {
		Npc npc = NPCS.get(player.getUUID());
		if (npc == null || npc.body == null || npc.air != Air.GLIDING || npc.goodbyeTicks >= 0) return;
		boolean first = npc.boostTicks == 0;
		var fireworks = rocket.get(DataComponents.FIREWORKS);
		npc.boostTicks = BOOST_TICKS_PER_DURATION * (1 + (fireworks == null ? 1 : fireworks.flightDuration()));
		npc.owner.connection.send(new ClientboundSwingAnimationPacket(npc.body, InteractionHand.OFF_HAND, SwingAnimation.DEFAULT));
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
		npc.outfitSent = false;
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

	/**
	 * Companion Merl, one tick: next to the player, flying along when they glide or fly. The guide's figure is the
	 * same one, so a guide simply takes over and she comes back afterwards.
	 */
	static void accompany(ServerPlayer player) {
		Npc npc = NPCS.computeIfAbsent(player.getUUID(), id -> new Npc());
		if (npc.goodbyeTicks >= 0) return;
		npc.ticks++;
		if (npc.owner != player) {
			if (npc.owner != null && npc.body != null && !npc.owner.hasDisconnected()) remove(npc);
			npc.owner = player;
			npc.body = null;
			npc.air = Air.WALKING;
			npc.playerTrail.clear();
		}
		trackSpeed(npc, player);
		matchSize(npc, player);
		if (player.isFallFlying() || player.getAbilities().flying) {
			// Flying with them, toward where they're looking.
			fly(npc, player, player.position().add(player.getLookAngle().multiply(1, 0, 1).scale(40)),
					player.isFallFlying() ? Air.GLIDING : Air.HOVERING);
		} else {
			if (npc.air != Air.WALKING && npc.body != null) setAir(npc, Air.WALKING);
			// Standing still for a while: she sits down on a cushion next to them, and gets up when they move on.
			boolean still = player.onGround() && npc.playerSpeed.horizontalDistanceSqr() < 0.0004 && !player.isShiftKeyDown();
			npc.idleTicks = still ? npc.idleTicks + 1 : 0;
			if (npc.cushion != null && npc.idleTicks == 0) standUp(npc);
			if (npc.cushion != null) {
				Vec3 facing = player.getEyePosition().subtract(npc.pos.add(0, 1.0, 0));
				if (npc.ticks % 5 == 0) move(npc, npc.pos, yaw(facing), 0f);
				return;
			}
			stayClose(npc, player);
			if (npc.idleTicks >= SIT_AFTER_TICKS && npc.body != null && npc.goodbyeTicks < 0
					&& npc.pos.distanceTo(player.position()) < 3 && MerlPath.standable(player.level(), BlockPos.containing(npc.pos))) {
				Vec3 seat = Vec3.atBottomCenterOf(BlockPos.containing(npc.pos));
				move(npc, seat, npc.yaw, 0f);
				sitOnCushion(npc, player, seat, npc.yaw);
			}
		}
		// Just (re)appeared, or back from flying: her outfit again.
		if (npc.body != null && npc.air == Air.WALKING && !npc.outfitSent && npc.outfitItems != null) sendOutfit(npc);
	}

	/** Companion Merl's outfit for the situation; sent only when it changes. Returns whether it changed. */
	static boolean dress(UUID player, String name, List<Pair<EquipmentSlot, ItemStack>> items) {
		Npc npc = NPCS.get(player);
		if (npc == null || name.equals(npc.outfit)) return false;
		npc.outfit = name;
		npc.outfitItems = items;
		if (npc.body != null && npc.air == Air.WALKING) sendOutfit(npc);
		return true;
	}

	private static void sendOutfit(Npc npc) {
		npc.owner.connection.send(new ClientboundSetEquipmentPacket(npc.body.getId(), npc.outfitItems));
		npc.outfitSent = true;
	}

	/** Hide and seek: she stands still at her hiding spot, facing the given way, with empty hands. */
	static void hideAt(ServerPlayer player, Vec3 at, float yaw) {
		stop(player.getUUID());
		Npc npc = new Npc();
		npc.owner = player;
		NPCS.put(player.getUUID(), npc);
		spawn(npc, player, at);
		if (npc.body == null) return;
		move(npc, at, yaw, 0f);
		sitOnCushion(npc, player, at, yaw);
		npc.outfit = "hiding";
		npc.outfitItems = List.of(Pair.of(EquipmentSlot.MAINHAND, ItemStack.EMPTY), Pair.of(EquipmentSlot.OFFHAND, ItemStack.EMPTY));
		sendOutfit(npc);
	}

	/**
	 * Hide and seek: a cushion of a random color (26.3's own cushion, packet-only like her) where she hides, with her
	 * sitting on it. Her figure isn't in the world, so it can't really ride; the passengers packet is written by hand.
	 */
	private static void sitOnCushion(Npc npc, ServerPlayer player, Vec3 at, float yaw) {
		try {
			Cushion cushion = new Cushion(net.minecraft.world.entity.EntityTypes.CUSHION, player.level());
			DyeColor[] colors = DyeColor.values();
			cushion.setColor(colors[player.getRandom().nextInt(colors.length)]);
			cushion.setPos(at.x, at.y, at.z);
			var connection = player.connection;
			connection.send(new ClientboundAddEntityPacket(cushion.getId(), cushion.getUUID(), at.x, at.y, at.z, 0f, yaw,
					cushion.getType(), 0, Vec3.ZERO, 0.0));
			var data = cushion.getEntityData().getNonDefaultValues();
			if (data != null && !data.isEmpty()) connection.send(new ClientboundSetEntityDataPacket(cushion.getId(), data));
			FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
			buffer.writeVarInt(cushion.getId());
			buffer.writeVarIntArray(new int[] {npc.body.getId()});
			connection.send(ClientboundSetPassengersPacket.STREAM_CODEC.decode(buffer));
			npc.cushion = cushion;
		} catch (Exception e) {
			NiceMerl.LOGGER.debug("Could not make Merl's cushion", e);
		}
	}

	/** She gets up: the cushion goes away (which drops her off it) and she stands where it was. */
	private static void standUp(Npc npc) {
		if (npc.cushion == null || npc.owner == null || npc.owner.hasDisconnected()) {
			npc.cushion = null;
			return;
		}
		npc.owner.connection.send(new ClientboundRemoveEntitiesPacket(npc.cushion.getId()));
		npc.cushion = null;
		if (npc.body != null) move(npc, npc.pos, npc.yaw, 0f);
	}

	/** Hide and seek is over: she turns to the player, waves for a moment, then goes. */
	static void foundAt(ServerPlayer player) {
		Npc npc = NPCS.get(player.getUUID());
		if (npc == null || npc.body == null) return;
		npc.owner.connection.send(new ClientboundSwingAnimationPacket(npc.body, InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT));
		npc.goodbyeTicks = GOODBYE_TICKS;
	}

	/** Where her figure is, or null. */
	static Vec3 position(UUID player) {
		Npc npc = NPCS.get(player);
		return npc == null || npc.body == null ? null : npc.pos;
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
		npc.outfitSent = false;
		var connection = player.connection;
		connection.send(new ClientboundAddEntityPacket(body.getId(), body.getUUID(), at.x, at.y, at.z, 0f, 0f,
				body.getType(), 0, Vec3.ZERO, 0.0));
		var data = body.getEntityData().getNonDefaultValues();
		if (data != null && !data.isEmpty()) connection.send(new ClientboundSetEntityDataPacket(body.getId(), data));
		connection.send(new ClientboundSetEquipmentPacket(body.getId(), List.of(Pair.of(EquipmentSlot.MAINHAND, map(player)),
				Pair.of(EquipmentSlot.OFFHAND, new ItemStack(Items.COMPASS)))));
		npc.sentScale = 1.0;
		matchSize(npc, player);
	}

	/** Makes her the player's size (a scale attribute from a pack, a potion or a command), sent only when it changes. */
	private static void matchSize(Npc npc, ServerPlayer player) {
		if (!(npc.body instanceof LivingEntity living)) return;
		double scale = player.getAttributeValue(Attributes.SCALE);
		if (Math.abs(scale - npc.sentScale) < 1e-3) return;
		AttributeInstance size = living.getAttribute(Attributes.SCALE);
		if (size == null) return;
		size.setBaseValue(scale);
		player.connection.send(new ClientboundUpdateAttributesPacket(living.getId(), List.of(size)));
		npc.sentScale = scale;
	}

	/**
	 * A real filled map for her hand (one without a map id is drawn blank). It's made once, of the area around
	 * spawn, and reused for every guide.
	 */
	static ItemStack map(ServerPlayer player) {
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
		if (npc.cushion != null) {
			npc.owner.connection.send(new ClientboundRemoveEntitiesPacket(npc.body.getId(), npc.cushion.getId()));
			npc.cushion = null;
		} else {
			npc.owner.connection.send(new ClientboundRemoveEntitiesPacket(npc.body.getId()));
		}
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

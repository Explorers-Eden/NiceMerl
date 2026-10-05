package eu.explorerseden.nicemerl;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Guides a player to a biome, slime chunk or waypoint Merl found: a trail of sparkles toward it that only
 * they see, and the distance on their action bar, until they arrive.
 */
public final class MerlGuide {
	/** How often the trail is drawn, in ticks. */
	private static final int TICKS = 10;
	/** The trail: a sparkle every SPACING blocks, from START to LENGTH blocks ahead. */
	private static final double START = 2.0, SPACING = 1.5, LENGTH = 12.0;
	/** Close enough to count as there, in blocks. */
	private static final double ARRIVED = 12.0;
	/** Guides stop on their own after this long (30 minutes). */
	private static final long MAX_TICKS = 36_000;
	private static final int LABEL_CHARS = 48;

	/** y is null when only the direction matters (slime chunks: any height below 40 works). */
	record Target(double x, Double y, double z, Identifier dimension, String label, long startedAt) {}

	private static final Map<UUID, Target> GUIDED = new ConcurrentHashMap<>();
	/** Each guided player's planned stretch of path, and when it was planned (in ticks). */
	private static final Map<UUID, List<BlockPos>> PATHS = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> PLANNED = new ConcurrentHashMap<>();
	/** Whether each path goes all the way to the target (or is just the next stretch toward a far one). */
	private static final Map<UUID, Boolean> TO_TARGET = new ConcurrentHashMap<>();
	/** The direction last shown on each player's action bar, so it doesn't flicker between two. */
	private static final Map<UUID, String> SHOWN_DIRECTION = new ConcurrentHashMap<>();
	/** Players who asked to see what the path search did (/nicemerl guide debug), and its last result. */
	private static final java.util.Set<UUID> DEBUG = ConcurrentHashMap.newKeySet();
	private static final Map<UUID, String> LAST_PLAN = new ConcurrentHashMap<>();
	/** Searches in progress (a big base takes a few ticks), and when the last one found nothing. */
	private static final Map<UUID, MerlPath.Search> SEARCHES = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> NO_WAY = new ConcurrentHashMap<>();
	/** A found path is checked again this often (blocks change), or sooner when the player leaves it, in ticks. */
	private static final int REPLAN_TICKS = 200;
	/** After finding no way, try again this much later. */
	private static final int RETRY_TICKS = 60;
	/** A stretch toward a far target is extended when the player gets this close to its end, in path steps. */
	private static final int STRETCH_END = 8;
	private static final double OFF_PATH = 3.0;
	/** Sparkles shown ahead of the player, one per path step. */
	private static final int SHOWN_STEPS = 24;
	/** The trail's look in turn: a white sparkle, a pink speck of dust, a sparkle, … */
	private static final ParticleOptions[] MARKERS = {ParticleTypes.END_ROD, new DustParticleOptions(0xFF5FB4, 1.6f)};
	private static long ticks;

	private MerlGuide() {}

	/** "Want me to show you the way? [Guide me]", added after an answer with coordinates. */
	static MutableComponent offer(int x, Integer y, int z, String name) {
		// The name goes into a chat command: no formatting codes or control characters (they'd kick the player).
		String label = name.replaceAll("[\\p{Cntrl}§]", "").strip();
		if (label.length() > LABEL_CHARS) label = label.substring(0, LABEL_CHARS).strip();
		if (label.isEmpty()) label = "there";
		String command = "/nicemerl guide " + x + " " + (y != null ? y : "-") + " " + z + " " + label;
		return Component.literal(" " + MerlLines.pick("guide_offer") + " ").append(Component.literal("[Guide me]")
				.withStyle(Style.EMPTY.withColor(ChatFormatting.LIGHT_PURPLE).withBold(true)
						.withClickEvent(new ClickEvent.RunCommand(command))
						.withHoverEvent(new HoverEvent.ShowText(Component.literal((NiceMerl.config().guideMerl
								? "Merl walks you to " : "Follow a trail of sparkles to ") + label)))));
	}

	/** "Follow the sparkles to …" after starting the trail for "lead me to …". */
	static Component startNow(ServerPlayer player, double x, Double y, double z, String label) {
		start(player, x, y, z, label);
		return Component.literal(" " + MerlLines.pick(NiceMerl.config().guideMerl ? "guide_start_merl" : "guide_start",
				"target", label, "user", player.getName().getString()))
				.withStyle(ChatFormatting.LIGHT_PURPLE);
	}

	static void start(ServerPlayer player, double x, Double y, double z, String label) {
		start(player, x, y, z, label, player.level().dimension().identifier());
	}

	/** A guide to a spot in a given dimension; in another one it waits until the player gets there. */
	static void start(ServerPlayer player, double x, Double y, double z, String label, Identifier dimension) {
		MerlGuideNpc.stop(player.getUUID());
		forget(player.getUUID());
		GUIDED.put(player.getUUID(), new Target(x + 0.5, y, z + 0.5, dimension, label, ticks));
	}

	/** /nicemerl guide debug: shows what the path search did on the action bar. Returns whether it's on now. */
	static boolean toggleDebug(ServerPlayer player) {
		if (DEBUG.remove(player.getUUID())) return false;
		DEBUG.add(player.getUUID());
		return true;
	}

	static boolean stop(ServerPlayer player) {
		forget(player.getUUID());
		MerlGuideNpc.stop(player.getUUID());
		return GUIDED.remove(player.getUUID()) != null;
	}

	private static void forget(UUID player) {
		PATHS.remove(player);
		PLANNED.remove(player);
		SEARCHES.remove(player);
		NO_WAY.remove(player);
		TO_TARGET.remove(player);
		SHOWN_DIRECTION.remove(player);
	}

	enum Way { SHOWN, SEARCHING, NONE }

	/**
	 * Every tick: starts a new path search when needed (none yet, the player left the path, the stretch is nearly
	 * walked, or it's old) and runs the one in progress a bit further.
	 */
	private static void plan(ServerPlayer player, Target target) {
		UUID id = player.getUUID();
		MerlPath.Search search = SEARCHES.get(id);
		if (search == null) {
			if (!needsPlan(player, id)) return;
			// In the air there's no ground to start from; wait until they land.
			search = MerlPath.start(player.level(), player.blockPosition(), target.x(),
					target.y() == null ? null : (int) Math.floor(target.y()), target.z());
			if (search == null) return;
			SEARCHES.put(id, search);
		}
		MerlPath.Plan plan = search.step(MerlPath.PER_TICK);
		if (plan == null) return;
		SEARCHES.remove(id);
		LAST_PLAN.put(id, (plan.path().isEmpty() ? "no way found" : plan.path().size() + " steps")
				+ (plan.toTarget() ? " to the target" : " (next stretch)") + ", " + plan.looked() + " spots looked at");
		PLANNED.put(id, ticks);
		if (plan.path().size() < 2) {
			// Better no path than one into a wall.
			PATHS.remove(id);
			NO_WAY.put(id, ticks);
			return;
		}
		NO_WAY.remove(id);
		PATHS.put(id, plan.path());
		TO_TARGET.put(id, plan.toTarget());
	}

	private static boolean needsPlan(ServerPlayer player, UUID id) {
		Long noWay = NO_WAY.get(id);
		if (noWay != null) return ticks - noWay >= RETRY_TICKS;
		List<BlockPos> path = PATHS.get(id);
		if (path == null || path.size() < 2) return true;
		if (ticks - PLANNED.getOrDefault(id, 0L) >= REPLAN_TICKS) return true;
		int nearest = nearest(path, player.blockPosition());
		if (Math.sqrt(path.get(nearest).distSqr(player.blockPosition())) > OFF_PATH) {
			// Off the path: it's not shown until the new one is found.
			PATHS.remove(id);
			return true;
		}
		return !TO_TARGET.getOrDefault(id, false) && nearest >= path.size() - STRETCH_END;
	}

	/**
	 * Sparkles along the path toward the target, between the player and Merl. While flying or falling with no
	 * path, a short line of sparkles points the way instead.
	 */
	private static Way drawPath(ServerPlayer player, Target target) {
		UUID id = player.getUUID();
		ServerLevel level = player.level();
		List<BlockPos> path = PATHS.get(id);
		if (path == null || path.size() < 2) {
			if (player.onGround() || player.isInWater() || player.onClimbable()) {
				return SEARCHES.containsKey(id) || !NO_WAY.containsKey(id) ? Way.SEARCHING : Way.NONE;
			}
			// Flying or falling, there's no ground to follow: a short line points the way.
			Vec3 eye = player.getEyePosition();
			Vec3 goal = new Vec3(target.x(), target.y() != null ? target.y() : eye.y, target.z());
			Vec3 step = goal.subtract(eye).normalize();
			for (double d = START; d <= Math.min(LENGTH, goal.distanceTo(eye)); d += SPACING) {
				Vec3 at = eye.add(0, -0.4, 0).add(step.scale(d));
				level.sendParticles(player, MARKERS[(int) (Math.round(d / SPACING) % MARKERS.length)], true, false, at.x, at.y, at.z, 1, 0, 0, 0, 0);
			}
			return Way.SHOWN;
		}
		int nearest = nearest(path, player.blockPosition());
		// With Merl walking ahead, the sparkles only show the way between the player and her.
		int merl = NiceMerl.config().guideMerl ? MerlGuideNpc.step(id) : -1;
		int last = merl > nearest ? merl : nearest + 1 + SHOWN_STEPS;
		for (int i = Math.max(1, nearest + 1); i < Math.min(path.size(), last); i++) {
			BlockPos at = path.get(i);
			// By path step, so each spot keeps its color as the player walks.
			level.sendParticles(player, MARKERS[i % MARKERS.length], true, false, at.getX() + 0.5, at.getY() + 0.3, at.getZ() + 0.5, 1, 0, 0, 0, 0);
		}
		return Way.SHOWN;
	}

	private static int nearest(List<BlockPos> path, BlockPos feet) {
		int best = -1;
		double bestDistance = Double.MAX_VALUE;
		for (int i = 0; i < path.size(); i++) {
			double d = path.get(i).distSqr(feet);
			if (d < bestDistance) {
				best = i;
				bestDistance = d;
			}
		}
		return best;
	}

	/** Called every server tick: draws each guided player's trail and checks whether they've arrived. */
	public static void tick(MinecraftServer server) {
		ticks++;
		MerlGuideNpc.tickGoodbyes();
		if (GUIDED.isEmpty()) return;
		boolean frame = ticks % TICKS == 0;
		boolean merl = NiceMerl.config().guideMerl;
		for (Map.Entry<UUID, Target> entry : GUIDED.entrySet()) {
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			Target target = entry.getValue();
			if (player == null || ticks - target.startedAt() > MAX_TICKS) {
				GUIDED.remove(entry.getKey());
				forget(entry.getKey());
				MerlGuideNpc.stop(entry.getKey());
				continue;
			}
			// In another dimension the trail just waits until they're back.
			if (!player.level().dimension().identifier().equals(target.dimension())) {
				MerlGuideNpc.stop(entry.getKey());
				continue;
			}
			Vec3 eye = player.getEyePosition();
			Vec3 goal = new Vec3(target.x(), target.y() != null ? target.y() : eye.y, target.z());
			double flat = Math.hypot(goal.x - eye.x, goal.z - eye.z);
			if (flat < ARRIVED && (target.y() == null || Math.abs(goal.y - eye.y) < ARRIVED)) {
				GUIDED.remove(entry.getKey());
				forget(entry.getKey());
				MerlGuideNpc.arrived(player);
				player.sendOverlayMessage(Component.empty());
				MerlCommand.replyTo(player.createCommandSourceStack(), Component.literal(
						MerlLines.pick("guide_arrived", "target", target.label(), "user", player.getName().getString())));
				continue;
			}
			plan(player, target);
			if (merl) {
				List<BlockPos> path = PATHS.get(entry.getKey());
				MerlGuideNpc.update(player, path, path == null ? -1 : nearest(path, player.blockPosition()),
						new Vec3(target.x(), target.y() != null ? target.y() : player.getY(), target.z()));
			}
			if (!frame) continue;
			Way way = drawPath(player, target);
			String direction = BiomeNames.direction(goal.x - eye.x, goal.z - eye.z, SHOWN_DIRECTION.get(entry.getKey()));
			SHOWN_DIRECTION.put(entry.getKey(), direction);
			player.sendOverlayMessage(Component.literal(target.label() + ": " + String.format(Locale.ROOT, "%,d", Math.round(flat))
					+ " blocks " + direction + (way == Way.NONE ? " (no path)" : way == Way.SEARCHING ? " (finding the way…)" : "")
					+ (DEBUG.contains(entry.getKey()) ? " · " + LAST_PLAN.getOrDefault(entry.getKey(), "") : ""))
					.withStyle(ChatFormatting.LIGHT_PURPLE));
		}
	}
}

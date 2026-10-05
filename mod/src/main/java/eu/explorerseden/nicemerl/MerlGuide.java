package eu.explorerseden.nicemerl;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
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
	/** The direction last shown on each player's action bar, so it doesn't flicker between two. */
	private static final Map<UUID, String> SHOWN_DIRECTION = new ConcurrentHashMap<>();
	/** The path is planned again this often, or sooner when the player leaves it. */
	private static final int REPLAN_TICKS = 40;
	private static final double OFF_PATH = 3.0;
	/** Sparkles shown ahead of the player, one per path step. */
	private static final int SHOWN_STEPS = 24;
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
						.withHoverEvent(new HoverEvent.ShowText(Component.literal("Follow a trail of sparkles to " + label)))));
	}

	/** "Follow the sparkles to …" after starting the trail for "lead me to …". */
	static Component startNow(ServerPlayer player, double x, Double y, double z, String label) {
		start(player, x, y, z, label);
		return Component.literal(" " + MerlLines.pick("guide_start", "target", label, "user", player.getName().getString()))
				.withStyle(ChatFormatting.LIGHT_PURPLE);
	}

	static void start(ServerPlayer player, double x, Double y, double z, String label) {
		GUIDED.put(player.getUUID(), new Target(x + 0.5, y, z + 0.5, player.level().dimension().identifier(), label, ticks));
	}

	static boolean stop(ServerPlayer player) {
		forget(player.getUUID());
		return GUIDED.remove(player.getUUID()) != null;
	}

	private static void forget(UUID player) {
		PATHS.remove(player);
		PLANNED.remove(player);
		SHOWN_DIRECTION.remove(player);
	}

	/**
	 * Sparkles along a walkable path on the ground toward the target. While flying or falling (no ground to plan
	 * on), a short line of sparkles points the way instead.
	 */
	/** True when there's a path to show; false when the player stands on the ground and no way was found. */
	private static boolean drawPath(ServerPlayer player, Target target) {
		UUID id = player.getUUID();
		ServerLevel level = player.level();
		BlockPos feet = player.blockPosition();
		List<BlockPos> path = PATHS.get(id);
		int nearest = path == null ? -1 : nearest(path, feet);
		boolean stale = path == null || path.size() < 2 || ticks - PLANNED.getOrDefault(id, 0L) >= REPLAN_TICKS
				|| nearest < 0 || Math.sqrt(path.get(nearest).distSqr(feet)) > OFF_PATH;
		if (stale) {
			path = MerlPath.find(level, feet, target.x(), target.y() == null ? null : (int) Math.floor(target.y()), target.z());
			PATHS.put(id, path);
			PLANNED.put(id, ticks);
			nearest = path.isEmpty() ? -1 : 0;
		}
		if (path.size() < 2) {
			// On the ground with no way found: say so rather than draw a line through the walls.
			if (player.onGround() || player.isInWater()) return false;
			// Flying or falling, there's no ground to follow: a short line points the way.
			Vec3 eye = player.getEyePosition();
			Vec3 goal = new Vec3(target.x(), target.y() != null ? target.y() : eye.y, target.z());
			Vec3 step = goal.subtract(eye).normalize();
			for (double d = START; d <= Math.min(LENGTH, goal.distanceTo(eye)); d += SPACING) {
				Vec3 at = eye.add(0, -0.4, 0).add(step.scale(d));
				level.sendParticles(player, ParticleTypes.END_ROD, true, false, at.x, at.y, at.z, 1, 0, 0, 0, 0);
			}
			return true;
		}
		for (int i = Math.max(1, nearest + 1); i < Math.min(path.size(), nearest + 1 + SHOWN_STEPS); i++) {
			BlockPos at = path.get(i);
			level.sendParticles(player, ParticleTypes.END_ROD, true, false, at.getX() + 0.5, at.getY() + 0.35, at.getZ() + 0.5, 1, 0.05, 0.02, 0.05, 0);
		}
		return true;
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
		if (++ticks % TICKS != 0 || GUIDED.isEmpty()) return;
		for (Map.Entry<UUID, Target> entry : GUIDED.entrySet()) {
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			Target target = entry.getValue();
			if (player == null || ticks - target.startedAt() > MAX_TICKS) {
				GUIDED.remove(entry.getKey());
				forget(entry.getKey());
				continue;
			}
			// In another dimension the trail just waits until they're back.
			if (!player.level().dimension().identifier().equals(target.dimension())) continue;
			Vec3 eye = player.getEyePosition();
			Vec3 goal = new Vec3(target.x(), target.y() != null ? target.y() : eye.y, target.z());
			double flat = Math.hypot(goal.x - eye.x, goal.z - eye.z);
			if (flat < ARRIVED && (target.y() == null || Math.abs(goal.y - eye.y) < ARRIVED)) {
				GUIDED.remove(entry.getKey());
				forget(entry.getKey());
				player.sendOverlayMessage(Component.empty());
				MerlCommand.replyTo(player.createCommandSourceStack(), Component.literal(
						MerlLines.pick("guide_arrived", "target", target.label(), "user", player.getName().getString())));
				continue;
			}
			boolean found = drawPath(player, target);
			String direction = BiomeNames.direction(goal.x - eye.x, goal.z - eye.z, SHOWN_DIRECTION.get(entry.getKey()));
			SHOWN_DIRECTION.put(entry.getKey(), direction);
			player.sendOverlayMessage(Component.literal(target.label() + ": " + String.format(Locale.ROOT, "%,d", Math.round(flat))
					+ " blocks " + direction + (found ? "" : " · no way found from here, try going around"))
					.withStyle(ChatFormatting.LIGHT_PURPLE));
		}
	}
}

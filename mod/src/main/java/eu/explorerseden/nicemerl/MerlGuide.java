package eu.explorerseden.nicemerl;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
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

	static void start(ServerPlayer player, double x, Double y, double z, String label) {
		GUIDED.put(player.getUUID(), new Target(x + 0.5, y, z + 0.5, player.level().dimension().identifier(), label, ticks));
	}

	static boolean stop(ServerPlayer player) {
		return GUIDED.remove(player.getUUID()) != null;
	}

	/** Called every server tick: draws each guided player's trail and checks whether they've arrived. */
	public static void tick(MinecraftServer server) {
		if (++ticks % TICKS != 0 || GUIDED.isEmpty()) return;
		for (Map.Entry<UUID, Target> entry : GUIDED.entrySet()) {
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			Target target = entry.getValue();
			if (player == null || ticks - target.startedAt() > MAX_TICKS) {
				GUIDED.remove(entry.getKey());
				continue;
			}
			// In another dimension the trail just waits until they're back.
			if (!player.level().dimension().identifier().equals(target.dimension())) continue;
			Vec3 eye = player.getEyePosition();
			Vec3 goal = new Vec3(target.x(), target.y() != null ? target.y() : eye.y, target.z());
			double flat = Math.hypot(goal.x - eye.x, goal.z - eye.z);
			if (flat < ARRIVED && (target.y() == null || Math.abs(goal.y - eye.y) < ARRIVED)) {
				GUIDED.remove(entry.getKey());
				player.sendOverlayMessage(Component.empty());
				MerlCommand.replyTo(player.createCommandSourceStack(), Component.literal(
						MerlLines.pick("guide_arrived", "target", target.label(), "user", player.getName().getString())));
				continue;
			}
			Vec3 step = goal.subtract(eye).normalize();
			Vec3 low = eye.add(0, -0.4, 0);
			for (double d = START; d <= Math.min(LENGTH, goal.distanceTo(eye)); d += SPACING) {
				Vec3 at = low.add(step.scale(d));
				player.level().sendParticles(player, ParticleTypes.END_ROD, true, false, at.x, at.y, at.z, 1, 0, 0, 0, 0);
			}
			player.sendOverlayMessage(Component.literal(target.label() + ": " + String.format(Locale.ROOT, "%,d", Math.round(flat))
					+ " blocks " + BiomeNames.direction(goal.x - eye.x, goal.z - eye.z)).withStyle(ChatFormatting.LIGHT_PURPLE));
		}
	}
}

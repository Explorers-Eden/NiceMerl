package eu.explorerseden.nicemerl;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * "Let's play hide and seek": Merl's figure (only the player sees it) sits on a cushion 15–40 blocks away, preferably
 * out of sight, and the action bar says warmer or colder every second. Looking at her from close by finds her.
 */
final class MerlHideAndSeek {
	private static final int MIN_DISTANCE = 15, MAX_DISTANCE = 40, TRIES = 80;
	private static final long TIME_LIMIT_MS = 5 * 60_000;
	/** Found: her face within this many blocks of the player's eyes, looked at with nothing in between. */
	private static final double FOUND_RANGE = 3.5, FOUND_COS = 0.85;
	/** Her face above the ground while she sits on her cushion. */
	private static final double FACE_HEIGHT = 1.2;
	private static final TextColor PINK = TextColor.fromRgb(0xF06EAA);

	private static final class Game {
		final long started = System.currentTimeMillis();
		final ResourceKey<Level> dimension;
		final Vec3 spot;
		double lastDistance;
		Game(ResourceKey<Level> dimension, Vec3 spot, double distance) {
			this.dimension = dimension;
			this.spot = spot;
			this.lastDistance = distance;
		}
	}

	private static final Map<UUID, Game> GAMES = new ConcurrentHashMap<>();
	private static long ticks;

	private MerlHideAndSeek() {}

	static boolean playing(UUID player) {
		return GAMES.containsKey(player);
	}

	/** "let's play hide and seek". Returns what Merl says. */
	static Component start(ServerPlayer player) {
		String user = player.getName().getString();
		if (playing(player.getUUID())) return Component.literal(MerlLines.pick("hide_busy", "user", user));
		if (MerlGuide.guiding(player.getUUID())) return Component.literal(MerlLines.pick("hide_busy", "user", user));
		Vec3 spot = findSpot(player);
		if (spot == null) return Component.literal(MerlLines.pick("hide_nospot", "user", user));
		Vec3 toPlayer = player.position().subtract(spot);
		float yaw = (float) (Math.toDegrees(Math.atan2(toPlayer.z, toPlayer.x)) - 90.0);
		MerlGuideNpc.hideAt(player, spot, yaw);
		GAMES.put(player.getUUID(), new Game(player.level().dimension(), spot, player.position().distanceTo(spot)));
		return Component.literal(MerlLines.pick("hide_start", "user", user))
				.append(Component.literal("\n(" + MerlLines.pick("hide_rules") + ")").withStyle(ChatFormatting.GRAY));
	}

	/** "I give up". Returns what Merl says. */
	static Component giveUp(ServerPlayer player) {
		String user = player.getName().getString();
		Game game = GAMES.remove(player.getUUID());
		if (game == null) return Component.literal(MerlLines.pick("hide_not_playing", "user", user));
		MerlGuideNpc.foundAt(player);
		return Component.literal(MerlLines.pick("hide_giveup", "user", user)).append(where(player, game));
	}

	/** Stops a game without a word (guide started, player left, feature turned off). */
	static void cancel(UUID player) {
		if (GAMES.remove(player) != null) MerlGuideNpc.stop(player);
	}

	/** " (12 blocks behind you, at 100 64 -20)". */
	private static Component where(ServerPlayer player, Game game) {
		BlockPos at = BlockPos.containing(game.spot);
		int distance = (int) Math.round(player.position().distanceTo(game.spot));
		return Component.literal(" (" + distance + " blocks away, at " + at.getX() + " " + at.getY() + " " + at.getZ() + ")")
				.withStyle(ChatFormatting.GRAY);
	}

	/** Called every server tick. */
	static void tick(MinecraftServer server) {
		ticks++;
		if (GAMES.isEmpty() || ticks % 5 != 0) return;
		for (Map.Entry<UUID, Game> entry : GAMES.entrySet()) {
			UUID id = entry.getKey();
			Game game = entry.getValue();
			ServerPlayer player = server.getPlayerList().getPlayer(id);
			if (player == null || !NiceMerl.config().hideAndSeek || player.level().dimension() != game.dimension || MerlGuide.guiding(id)) {
				GAMES.remove(id);
				if (player != null && !MerlGuide.guiding(id)) MerlGuideNpc.stop(id);
				continue;
			}
			String user = player.getName().getString();
			if (found(player, game)) {
				GAMES.remove(id);
				long seconds = Math.max(1, (System.currentTimeMillis() - game.started) / 1000);
				int[] best = {0};
				MerlState.update(id, p -> {
					p.hideWins = (p.hideWins == null ? 0 : p.hideWins) + 1;
					best[0] = p.hideBest == null ? Integer.MAX_VALUE : p.hideBest;
					if (seconds < best[0]) p.hideBest = (int) seconds;
				});
				MerlGuideNpc.foundAt(player);
				String time = time(seconds);
				String line = MerlLines.pick("hide_found", "user", user, "time", time);
				if (best[0] != Integer.MAX_VALUE && seconds < best[0]) line += " " + MerlLines.pick("hide_record", "user", user, "time", time);
				MerlCommand.replyTo(player.createCommandSourceStack(), Component.literal(line));
				player.sendOverlayMessage(Component.literal("✦ Found Merl in " + time + "! ✦").withStyle(Style.EMPTY.withColor(PINK)));
				continue;
			}
			if (System.currentTimeMillis() - game.started > TIME_LIMIT_MS) {
				GAMES.remove(id);
				MerlGuideNpc.foundAt(player);
				MerlCommand.replyTo(player.createCommandSourceStack(),
						Component.literal(MerlLines.pick("hide_timeout", "user", user)).append(where(player, game)));
				continue;
			}
			if (ticks % 20 == 0) hint(player, game);
		}
	}

	/** Warmer or colder on the action bar, by how close they are and whether they got closer. */
	private static void hint(ServerPlayer player, Game game) {
		double distance = player.position().distanceTo(game.spot);
		double change = distance - game.lastDistance;
		game.lastDistance = distance;
		String heat = distance < 5 ? "🔥 Burning hot!" : distance < 10 ? "🔥 Hot" : distance < 18 ? "☀ Warm" : distance < 30 ? "❄ Cool" : "❄ Cold";
		String trend = change < -0.5 ? " · warmer ▲" : change > 0.5 ? " · colder ▼" : "";
		ChatFormatting color = distance < 10 ? ChatFormatting.RED : distance < 18 ? ChatFormatting.GOLD : ChatFormatting.AQUA;
		player.sendOverlayMessage(Component.literal(heat + trend).withStyle(color));
	}

	private static boolean found(ServerPlayer player, Game game) {
		Vec3 face = game.spot.add(0, FACE_HEIGHT * player.getScale(), 0);
		Vec3 eyes = player.getEyePosition();
		if (eyes.distanceTo(face) > (FOUND_RANGE + 1.0) * Math.max(1, player.getScale())) return false;
		if (eyes.distanceTo(face) <= 1.5) return true;
		if (player.getViewVector(1.0f).dot(face.subtract(eyes).normalize()) < FOUND_COS) return false;
		return visible(player.level(), eyes, face, player);
	}

	private static boolean visible(ServerLevel level, Vec3 from, Vec3 to, ServerPlayer player) {
		return level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player)).getType() == HitResult.Type.MISS;
	}

	/**
	 * A spot 15–40 blocks away where a player fits, preferably out of sight, and outdoors when the player is outdoors
	 * (underground when they're underground), so she isn't sealed in a cave next to their house.
	 */
	private static Vec3 findSpot(ServerPlayer player) {
		ServerLevel level = player.level();
		ThreadLocalRandom random = ThreadLocalRandom.current();
		BlockPos feet = player.blockPosition();
		boolean outdoors = level.canSeeSky(feet.above());
		Vec3 eyes = player.getEyePosition();
		Vec3 fallback = null;
		for (int i = 0; i < TRIES; i++) {
			double angle = random.nextDouble(Math.PI * 2), radius = random.nextDouble(MIN_DISTANCE, MAX_DISTANCE);
			int x = feet.getX() + (int) Math.round(Math.cos(angle) * radius);
			int z = feet.getZ() + (int) Math.round(Math.sin(angle) * radius);
			if (!level.getChunkSource().hasChunk(x >> 4, z >> 4)) continue;
			for (int dy = 6; dy >= -6; dy--) {
				BlockPos at = new BlockPos(x, feet.getY() + dy, z);
				if (!MerlPath.standable(level, at) || level.canSeeSky(at.above()) != outdoors) continue;
				if (!level.getFluidState(at).isEmpty()) continue;
				Vec3 spot = Vec3.atBottomCenterOf(at);
				if (!visible(level, eyes, spot.add(0, FACE_HEIGHT * player.getScale(), 0), player)) return spot;
				if (fallback == null) fallback = spot;
				break;
			}
		}
		return fallback;
	}

	static String time(long seconds) {
		return seconds < 60 ? seconds + (seconds == 1 ? " second" : " seconds")
				: (seconds / 60) + ":" + (seconds % 60 < 10 ? "0" : "") + (seconds % 60) + " minutes";
	}
}

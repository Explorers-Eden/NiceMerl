package eu.explorerseden.nicemerl;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToLongFunction;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.level.block.Block;

/**
 * Statistic milestones (100,000 blocks mined, 1,000 km traveled, …) that Merl congratulates players on,
 * and how many players on the server have an advancement, for "only the 3rd explorer to do this!".
 */
public final class MerlStats {
	/** Milestones are checked this often, in ticks (every 5 minutes). */
	private static final int CHECK_TICKS = 6000;

	private record Statistic(String name, ToLongFunction<ServerPlayer> value) {}

	/** Loaded on the first check, so the class can be used without Minecraft's statistics being ready. */
	private static final class Tracked {
		private static final List<Statistic> STATISTICS = List.of(
				new Statistic("mined", MerlStats::blocksMined),
				new Statistic("mobs", p -> custom(p, Stats.MOB_KILLS)),
				new Statistic("distance", p -> distanceCm(p) / 100_000),
				new Statistic("hours", p -> custom(p, Stats.PLAY_TIME) / 72_000),
				new Statistic("fish", p -> custom(p, Stats.FISH_CAUGHT)),
				new Statistic("bred", p -> custom(p, Stats.ANIMALS_BRED)),
				new Statistic("trades", p -> custom(p, Stats.TRADED_WITH_VILLAGER)),
				new Statistic("jumps", p -> custom(p, Stats.JUMP)));

		/** Ways of getting around that count as traveling (not falling, not creative flight). */
		private static final List<Identifier> DISTANCES = List.of(Stats.WALK_ONE_CM, Stats.SPRINT_ONE_CM, Stats.CROUCH_ONE_CM,
				Stats.WALK_ON_WATER_ONE_CM, Stats.WALK_UNDER_WATER_ONE_CM, Stats.SWIM_ONE_CM, Stats.CLIMB_ONE_CM,
				Stats.AVIATE_ONE_CM, Stats.BOAT_ONE_CM, Stats.MINECART_ONE_CM, Stats.HORSE_ONE_CM, Stats.PIG_ONE_CM,
				Stats.STRIDER_ONE_CM, Stats.HAPPY_GHAST_ONE_CM);
	}

	private static int ticks;

	private MerlStats() {}

	/** Called every server tick; checks online players' milestones every few minutes. */
	public static void tick(MinecraftServer server) {
		if (++ticks < CHECK_TICKS) return;
		ticks = 0;
		MerlConfig config = NiceMerl.config();
		if (config == null || !config.celebrate || !config.celebrateStatistics) return;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) check(player);
	}

	/**
	 * Congratulates the player on the highest new milestone, one per check. The first check only
	 * remembers where a player already is, so nobody gets years of old milestones at once.
	 */
	static void check(ServerPlayer player) {
		MerlState.Player state = MerlState.player(player.getUUID());
		if (!state.celebrate) return;
		for (Statistic stat : Tracked.STATISTICS) {
			List<Long> steps = MerlLines.statMilestones(stat.name());
			if (steps.isEmpty()) continue;
			long value = stat.value().applyAsLong(player);
			int reached = -1;
			for (int i = 0; i < steps.size(); i++) {
				if (value >= steps.get(i)) reached = i;
			}
			Integer known = state.milestone(stat.name());
			if (known != null && reached <= known) continue;
			int index = reached;
			MerlState.update(player.getUUID(), p -> p.setMilestone(stat.name(), index));
			if (known == null || reached < 0) continue;
			String count = String.format(Locale.ROOT, "%,d", steps.get(reached));
			MerlCommand.sendCelebration(player, Component.literal(
					MerlLines.pick("stat_" + stat.name(), "count", count, "user", player.getName().getString())));
			return;
		}
	}

	private static long custom(ServerPlayer player, Identifier stat) {
		return player.getStats().getValue(Stats.CUSTOM.get(stat));
	}

	private static long distanceCm(ServerPlayer player) {
		long cm = 0;
		for (Identifier stat : Tracked.DISTANCES) cm += custom(player, stat);
		return cm;
	}

	private static long blocksMined(ServerPlayer player) {
		long mined = 0;
		for (Block block : BuiltInRegistries.BLOCK) mined += player.getStats().getValue(Stats.BLOCK_MINED.get(block));
		return mined;
	}

	/**
	 * How many offline players have the advancement, from their saved files. Runs off the server thread;
	 * online players (whose files may be out of date) are skipped and checked live by the caller.
	 */
	static int countDone(Path dir, String advancement, Set<UUID> online) {
		int done = 0;
		try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
			for (Path file : files) {
				String name = file.getFileName().toString();
				try {
					if (online.contains(UUID.fromString(name.substring(0, name.length() - ".json".length())))) continue;
				} catch (IllegalArgumentException e) {
					continue;
				}
				try (Reader reader = Files.newBufferedReader(file)) {
					JsonElement entry = JsonParser.parseReader(reader).getAsJsonObject().get(advancement);
					if (entry instanceof JsonObject progress && progress.has("done") && progress.get("done").getAsBoolean()) done++;
				} catch (IOException | RuntimeException e) {
					// An unreadable file just isn't counted.
				}
			}
		} catch (IOException e) {
			NiceMerl.LOGGER.debug("Could not read {}", dir, e);
		}
		return done;
	}

	/** "You're the very first…", "only the 3rd explorer…", or "12 explorers did this before you". */
	static String rankLine(int others) {
		if (others == 0) return MerlLines.pick("celebrate_first");
		if (others < 10) return MerlLines.pick("celebrate_rank", "rank", ordinal(others + 1));
		return MerlLines.pick("celebrate_others", "count", String.format(Locale.ROOT, "%,d", others));
	}

	static String ordinal(int n) {
		int mod100 = n % 100;
		String suffix = mod100 >= 11 && mod100 <= 13 ? "th" : switch (n % 10) {
			case 1 -> "st";
			case 2 -> "nd";
			case 3 -> "rd";
			default -> "th";
		};
		return n + suffix;
	}
}

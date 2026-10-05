package eu.explorerseden.nicemerl;

import java.util.List;
import java.util.Locale;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;

/**
 * Live server info for "what's the tps?", "mob cap", "view distance" and "server info": read from the running
 * server, so it's always current. Nothing secret is shown (no seed, IPs or passwords).
 */
public final class MerlServerInfo {
	public static final String PERMISSION = "nicemerl.serverinfo";
	/** Mob caps are per 17×17 chunks around players, like the game counts them. */
	private static final int CAP_CHUNKS = 17 * 17;
	private static final TextColor PINK = TextColor.fromRgb(0xF06EAA);
	private static final TextColor VALUE = TextColor.fromRgb(0xFFD966);

	private MerlServerInfo() {}

	static Component answer(MinecraftServer server, ServerLevel level, String kind, String user, ServerPlayer asker, String question) {
		return switch (kind) {
			case "ping" -> ping(server, user, asker, question);
			case "tps" -> tps(server, user);
			case "mobs" -> mobs(level, user);
			case "distance" -> Component.literal(MerlLines.pick("serverinfo_distance", "user", user))
					.append(row("View distance", server.getPlayerList().getViewDistance() + " chunks"))
					.append(row("Simulation distance", server.getPlayerList().getSimulationDistance() + " chunks"));
			default -> info(server, level, user);
		};
	}

	/** Your ping, a named player's ("mistiik's ping"), or everyone's. */
	private static Component ping(MinecraftServer server, String user, ServerPlayer asker, String question) {
		String text = question.toLowerCase(Locale.ROOT).replace("'", "");
		List<ServerPlayer> online = server.getPlayerList().getPlayers();
		boolean everyone = text.matches(".*\\b(everyone|everybody|all|players|whole server|online)\\b.*") || asker == null;
		ServerPlayer named = online.stream().filter(p -> p != asker && text.contains(p.getName().getString().toLowerCase(Locale.ROOT)))
				.findFirst().orElse(null);
		if (named == null && !everyone) {
			return Component.literal(MerlLines.pick("serverinfo_ping_mine", "user", user)).append(pingRow(asker));
		}
		if (named != null) {
			return Component.literal(MerlLines.pick("serverinfo_ping_other", "player", named.getName().getString(), "user", user)).append(pingRow(named));
		}
		if (online.isEmpty()) return Component.literal(MerlLines.pick("serverinfo_ping_nobody"));
		MutableComponent out = Component.literal(MerlLines.pick("serverinfo_ping_all", "count", String.valueOf(online.size()), "user", user));
		online.stream().sorted(java.util.Comparator.comparingInt(p -> p.connection.latency())).limit(20).forEach(p -> out.append(pingRow(p)));
		if (online.size() > 20) out.append(Component.literal("\n and " + (online.size() - 20) + " more").withStyle(ChatFormatting.GRAY));
		return out;
	}

	private static Component pingRow(ServerPlayer player) {
		int ms = player.connection.latency();
		return row(player.getName().getString(), ms + " ms", ms < 80 ? ChatFormatting.GREEN : ms < 200 ? ChatFormatting.YELLOW : ChatFormatting.RED);
	}

	private static Component tps(MinecraftServer server, String user) {
		double mspt = server.getAverageTickTimeNanos() / 1_000_000.0;
		float target = server.tickRateManager().tickrate();
		double tps = Math.min(target, mspt > 0 ? 1000.0 / mspt : target);
		double budget = 1000.0 / target;
		String verdict = server.tickRateManager().isFrozen() ? "serverinfo_frozen"
				: mspt < budget * 0.8 ? "serverinfo_smooth" : mspt < budget ? "serverinfo_busy" : "serverinfo_lagging";
		ChatFormatting color = mspt < budget * 0.8 ? ChatFormatting.GREEN : mspt < budget ? ChatFormatting.YELLOW : ChatFormatting.RED;
		return Component.literal(MerlLines.pick(verdict, "user", user))
				.append(row("TPS", String.format(Locale.ROOT, "%.1f", tps) + (target != 20 ? String.format(Locale.ROOT, " (target %.0f)", target) : ""), color))
				.append(row("MSPT", String.format(Locale.ROOT, "%.1f ms", mspt) + String.format(Locale.ROOT, " (of %.0f ms per tick)", budget), color));
	}

	private static Component mobs(ServerLevel level, String user) {
		NaturalSpawner.SpawnState state = level.getChunkSource().getLastSpawnState();
		int entities = 0;
		for (Entity ignored : level.getAllEntities()) entities++;
		MutableComponent out = Component.literal(MerlLines.pick("serverinfo_mobs", "user", user));
		if (state == null) {
			return out.append(row("Entities in this dimension", String.valueOf(entities)))
					.append(Component.literal("\n" + MerlLines.pick("serverinfo_mobs_none")).withStyle(ChatFormatting.GRAY));
		}
		int chunks = state.getSpawnableChunkCount();
		for (MobCategory category : MobCategory.values()) {
			if (category == MobCategory.MISC) continue;
			int count = state.getMobCategoryCounts().getInt(category);
			int cap = category.getMaxInstancesPerChunk() * chunks / CAP_CHUNKS;
			out.append(row(categoryName(category), count + " / " + cap, count >= cap ? ChatFormatting.RED : ChatFormatting.WHITE));
		}
		return out.append(row("Entities in this dimension", String.valueOf(entities)))
				.append(Component.literal("\n(" + MerlLines.pick("serverinfo_mobs_note") + ")").withStyle(ChatFormatting.GRAY));
	}

	private static Component info(MinecraftServer server, ServerLevel level, String user) {
		MutableComponent out = Component.literal(MerlLines.pick("serverinfo_info", "user", user))
				.append(row("Version", server.getServerVersion()))
				.append(row("Players online", server.getPlayerCount() + " / " + server.getPlayerList().getMaxPlayers()))
				.append(row("Difficulty", MerlRecipes.readable(level.getDifficulty().getDisplayName()) + (server.isHardcore() ? " (Hardcore)" : "")))
				.append(row("Default game mode", MerlRecipes.readable(server.getDefaultGameType().getLongDisplayName())))
				.append(row("View distance", server.getPlayerList().getViewDistance() + " chunks"))
				.append(row("Simulation distance", server.getPlayerList().getSimulationDistance() + " chunks"))
				.append(row("Whitelist", server.getPlayerList().isUsingWhitelist() ? "on" : "off"));
		DatapackSettings.gameRules(server).stream().filter(s -> s.keyWords().startsWith("pvp ")).findFirst()
				.ifPresent(pvp -> out.append(row("PvP", pvp.value())));
		String motd = server.getMotd() == null ? "" : server.getMotd().replaceAll("§.", "").strip();
		if (!motd.isEmpty()) out.append(row("Message of the day", motd));
		return out;
	}

	private static String categoryName(MobCategory category) {
		return switch (category) {
			case MONSTER -> "Monsters";
			case CREATURE -> "Animals";
			case AMBIENT -> "Bats";
			case AXOLOTLS -> "Axolotls";
			case UNDERGROUND_WATER_CREATURE -> "Glow squids";
			case WATER_CREATURE -> "Squids and dolphins";
			case WATER_AMBIENT -> "Fish";
			default -> category.getName();
		};
	}

	private static Component row(String label, String value) {
		return row(label, value, null);
	}

	private static Component row(String label, String value, ChatFormatting color) {
		String shown = value;
		Style valueStyle = color != null ? Style.EMPTY.withColor(color)
				: shown.equals("on") ? Style.EMPTY.withColor(ChatFormatting.GREEN)
				: shown.equals("off") ? Style.EMPTY.withColor(ChatFormatting.RED) : Style.EMPTY.withColor(VALUE);
		return Component.literal("\n ⚙ ").withStyle(Style.EMPTY.withColor(PINK))
				.append(Component.literal(label + ": ").withStyle(ChatFormatting.WHITE))
				.append(Component.literal(shown).withStyle(valueStyle));
	}
}

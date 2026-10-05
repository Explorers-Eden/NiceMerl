package eu.explorerseden.nicemerl;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.stats.Stats;
import net.minecraft.world.level.storage.LevelResource;

/**
 * "Who is MrNox?": what the server knows about a player, online now or last seen, play time, deaths and mobs
 * defeated, since when Merl knows them, and a link to their skin. "who is X" only counts for players Merl knows (so
 * "who is Arachne" still gets the wiki); "who is the player X" or "X's skin" always looks them up. Same patterns as
 * the Discord bot's player cards (topic_patterns.json).
 */
final class MerlPlayers {
	record Lookup(String name, boolean explicit) {}

	private static final List<Pattern> EXPLICIT = new ArrayList<>(), LOOSE = new ArrayList<>();

	static {
		try (Reader reader = new InputStreamReader(MerlPlayers.class.getResourceAsStream("/nicemerl/topic_patterns.json"),
				StandardCharsets.UTF_8)) {
			JsonObject player = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("player");
			player.getAsJsonArray("explicit").forEach(p -> EXPLICIT.add(Pattern.compile(p.getAsString())));
			player.getAsJsonArray("loose").forEach(p -> LOOSE.add(Pattern.compile(p.getAsString())));
		} catch (IOException | RuntimeException e) {
			NiceMerl.LOGGER.warn("Could not read the player lookup patterns: {}", e.toString());
		}
	}

	private MerlPlayers() {}

	/** "who is the player MrNox" → MrNox (explicit), "who is Nox" → Nox (only if it's a player Merl knows); else null. */
	static Lookup lookup(String message) {
		for (Pattern p : EXPLICIT) {
			Matcher m = p.matcher(message);
			if (m.matches()) return new Lookup(m.group("name"), true);
		}
		for (Pattern p : LOOSE) {
			Matcher m = p.matcher(message);
			if (m.matches()) return new Lookup(m.group("name"), false);
		}
		return null;
	}

	/**
	 * Answers the lookup, or returns false when it isn't about a player after all ("who is Arachne"), so the
	 * question is answered as usual. Names nobody knows are looked up off the server thread.
	 */
	static boolean answer(CommandSourceStack source, Lookup lookup) {
		MinecraftServer server = source.getServer();
		// "who is Katter": a wiki page named after it wins over a player of that name, unless they asked for the player.
		if (!lookup.explicit()) {
			SearchIndex index = NiceMerl.index();
			SearchIndex.Outcome wiki = index == null ? null : index.find(lookup.name(), 1, 0);
			if (wiki != null && !wiki.results().isEmpty() && wiki.results().get(0).titleMatch()) return false;
		}
		NameAndId known = known(server, lookup.name());
		if (known != null) {
			MerlCommand.replyTo(source, card(server, known, source.getTextName()));
			return true;
		}
		if (!lookup.explicit()) return false;
		NiceMerl.lookupAsync(() -> server.services().nameToIdCache().get(lookup.name()), found -> server.execute(() ->
				MerlCommand.replyTo(source, found.isPresent() ? card(server, found.get(), source.getTextName())
						: Component.literal(MerlLines.pick("player_unknown", "name", lookup.name(), "user", source.getTextName())))));
		return true;
	}

	/** An online player or one Merl has talked to: the exact name, or the only one whose name contains it. */
	private static NameAndId known(MinecraftServer server, String name) {
		String wanted = name.toLowerCase(Locale.ROOT);
		NameAndId exact = null, containing = null;
		int containingCount = 0;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			String own = p.getName().getString();
			if (own.equalsIgnoreCase(name)) exact = new NameAndId(p.getUUID(), own);
			else if (wanted.length() >= 3 && own.toLowerCase(Locale.ROOT).contains(wanted)) {
				containing = new NameAndId(p.getUUID(), own);
				containingCount++;
			}
		}
		if (exact != null) return exact;
		Map<UUID, String> talked = MerlState.named(name);
		if (talked.size() == 1 && containingCount == 0) {
			Map.Entry<UUID, String> e = talked.entrySet().iterator().next();
			return new NameAndId(e.getKey(), e.getValue());
		}
		return containingCount == 1 && talked.isEmpty() ? containing : null;
	}

	private static Component card(MinecraftServer server, NameAndId who, String user) {
		MutableComponent card = Component.literal(MerlLines.pick("player_card", "name", who.name(), "user", user));
		ServerPlayer online = server.getPlayerList().getPlayer(who.id());
		Numbers stats = online != null ? fromOnline(online) : fromFile(server, who.id());
		if (online != null) {
			line(card, "Online now");
		} else if (stats.lastSeen() != null) {
			line(card, "Last seen " + ago(stats.lastSeen()));
		}
		if (stats.playTicks() > 0) line(card, "Play time: " + playTime(stats.playTicks()));
		if (stats.found()) {
			line(card, "Deaths: " + String.format(Locale.ROOT, "%,d", stats.deaths()));
			line(card, "Mobs defeated: " + String.format(Locale.ROOT, "%,d", stats.mobKills()));
		}
		MerlState.Player friend = MerlState.player(who.id());
		if (friend.metDay() > 0) {
			line(card, "Merl's friend since " + LocalDate.ofEpochDay(friend.metDay()).format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH))
					+ (friend.chatCount() > 0 ? ", " + friend.chatCount() + (friend.chatCount() == 1 ? " chat" : " chats") : ""));
		}
		if (online == null && !stats.found() && friend.metDay() == 0) line(card, "Hasn't played on this server yet");
		card.append("\n ").append(Component.literal("[View skin]").withStyle(Style.EMPTY.withColor(ChatFormatting.LIGHT_PURPLE)
				.withClickEvent(new ClickEvent.OpenUrl(java.net.URI.create("https://namemc.com/profile/" + who.id())))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(who.name() + "'s skin and name history on NameMC")))));
		return card;
	}

	private static void line(MutableComponent card, String text) {
		card.append(Component.literal("\n ▸ " + text).withStyle(ChatFormatting.GRAY));
	}

	private record Numbers(boolean found, long playTicks, int deaths, int mobKills, Instant lastSeen) {}

	private static Numbers fromOnline(ServerPlayer player) {
		var counter = player.getStats();
		return new Numbers(true, counter.getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)), counter.getValue(Stats.CUSTOM.get(Stats.DEATHS)),
				counter.getValue(Stats.CUSTOM.get(Stats.MOB_KILLS)), null);
	}

	/** An offline player's saved statistics (world/stats/<uuid>.json); the file's date is when they were last on. */
	private static Numbers fromFile(MinecraftServer server, UUID id) {
		Path file = server.getWorldPath(LevelResource.PLAYER_STATS_DIR).resolve(id + ".json");
		if (!Files.isRegularFile(file)) return new Numbers(false, 0, 0, 0, null);
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			JsonObject custom = Optional.ofNullable(JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("stats"))
					.map(s -> s.getAsJsonObject("minecraft:custom")).orElse(new JsonObject());
			return new Numbers(true, number(custom, "minecraft:play_time"), (int) number(custom, "minecraft:deaths"),
					(int) number(custom, "minecraft:mob_kills"), Files.getLastModifiedTime(file).toInstant());
		} catch (IOException | RuntimeException e) {
			return new Numbers(false, 0, 0, 0, null);
		}
	}

	private static long number(JsonObject o, String key) {
		return o.has(key) ? o.get(key).getAsLong() : 0;
	}

	private static String playTime(long ticks) {
		long minutes = ticks / 20 / 60;
		return minutes < 60 ? minutes + " min" : String.format(Locale.ROOT, "%,d h %d min", minutes / 60, minutes % 60);
	}

	private static String ago(Instant when) {
		long days = java.time.Duration.between(when, Instant.now()).toDays();
		if (days <= 0) return "today";
		if (days == 1) return "yesterday";
		return days < 60 ? days + " days ago" : "on " + LocalDate.ofInstant(when, ZoneId.systemDefault())
				.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH));
	}
}

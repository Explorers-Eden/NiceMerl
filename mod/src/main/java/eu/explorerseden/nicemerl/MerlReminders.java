package eu.explorerseden.nicemerl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * "Remind me in 10 minutes to check the furnace": Merl pings the player when it's time. Kept in memory only,
 * so reminders are gone after a restart (Merl says so); a player who's offline gets it when they come back.
 */
public final class MerlReminders {
	private static final int MAX_PER_PLAYER = 5;
	/** How often due reminders are checked, in ticks (every second). */
	private static final int TICKS = 20;

	record Reminder(long dueAt, String text) {}

	private static final Map<UUID, List<Reminder>> REMINDERS = new ConcurrentHashMap<>();
	private static int ticks;

	private MerlReminders() {}

	static Component add(ServerPlayer player, MerlLines.Reminder request) {
		String user = player.getName().getString();
		List<Reminder> list = REMINDERS.computeIfAbsent(player.getUUID(), k -> new ArrayList<>());
		synchronized (list) {
			if (list.size() >= MAX_PER_PLAYER) {
				return Component.literal(MerlLines.pick("reminder_full", "count", String.valueOf(MAX_PER_PLAYER), "user", user));
			}
			list.add(new Reminder(System.currentTimeMillis() + request.seconds() * 1000, request.text()));
		}
		String when = duration(request.seconds());
		return Component.literal(request.text().isEmpty()
				? MerlLines.pick("reminder_set_plain", "time", when, "user", user)
				: MerlLines.pick("reminder_set", "time", when, "text", request.text(), "user", user))
				.append(Component.literal(" (" + MerlLines.pick("reminder_restart_note") + ")").withStyle(ChatFormatting.GRAY));
	}

	static Component command(ServerPlayer player, String command) {
		String user = player.getName().getString();
		List<Reminder> list = REMINDERS.getOrDefault(player.getUUID(), List.of());
		if (command.equals("cancel")) {
			int n = list.size();
			REMINDERS.remove(player.getUUID());
			return Component.literal(MerlLines.pick(n == 0 ? "reminder_none" : "reminder_cancelled", "count", String.valueOf(n), "user", user));
		}
		if (list.isEmpty()) return Component.literal(MerlLines.pick("reminder_none", "user", user));
		MutableComponent message = Component.literal(MerlLines.pick("reminder_list", "count", String.valueOf(list.size()), "user", user));
		long now = System.currentTimeMillis();
		synchronized (list) {
			for (Reminder r : list) {
				message.append(Component.literal("\n ▸ ").withStyle(ChatFormatting.DARK_GRAY))
						.append(Component.literal("in " + duration(Math.max(1, (r.dueAt() - now) / 1000))).withStyle(ChatFormatting.YELLOW))
						.append(Component.literal(r.text().isEmpty() ? "" : ": " + r.text()));
			}
		}
		return message;
	}

	/** Called every server tick: delivers due reminders to online players. */
	public static void tick(MinecraftServer server) {
		if (++ticks % TICKS != 0 || REMINDERS.isEmpty()) return;
		long now = System.currentTimeMillis();
		for (Map.Entry<UUID, List<Reminder>> entry : REMINDERS.entrySet()) {
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player != null) deliver(player, now);
		}
	}

	/** Due reminders for this player (also called when they join, for the ones they missed). */
	public static void deliver(ServerPlayer player, long now) {
		List<Reminder> list = REMINDERS.get(player.getUUID());
		if (list == null) return;
		List<Reminder> due = new ArrayList<>();
		synchronized (list) {
			list.removeIf(r -> r.dueAt() <= now && due.add(r));
			if (list.isEmpty()) REMINDERS.remove(player.getUUID());
		}
		String user = player.getName().getString();
		for (Reminder r : due) {
			MerlCommand.replyTo(player.createCommandSourceStack(), Component.literal(r.text().isEmpty()
					? MerlLines.pick("reminder_due_plain", "user", user)
					: MerlLines.pick("reminder_due", "text", r.text(), "user", user)));
		}
	}

	/** "10 minutes", "1 hour 30 minutes", "45 seconds". */
	static String duration(long seconds) {
		long h = seconds / 3600, m = seconds % 3600 / 60, s = seconds % 60;
		List<String> parts = new ArrayList<>();
		if (h > 0) parts.add(h + (h == 1 ? " hour" : " hours"));
		if (m > 0) parts.add(m + (m == 1 ? " minute" : " minutes"));
		if (s > 0 && h == 0) parts.add(s + (s == 1 ? " second" : " seconds"));
		return parts.isEmpty() ? "a moment" : String.join(" ", parts);
	}
}

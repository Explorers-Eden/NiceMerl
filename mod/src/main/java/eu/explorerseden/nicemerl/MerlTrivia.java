package eu.explorerseden.nicemerl;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerPlayer;

/**
 * "Quiz me": hand-written Minecraft trivia (trivia.json, shared with the bot's data) with clickable answers. One open
 * question per player for two minutes; right answers, streaks and the best streak are kept for the leaderboard.
 */
final class MerlTrivia {
	private static final long ANSWER_MILLIS = 120_000;
	private static final String[] LETTERS = {"A", "B", "C", "D"};
	private static final TextColor PINK = TextColor.fromRgb(0xF06EAA);
	private static final TextColor VALUE = TextColor.fromRgb(0xFFD966);

	record Question(String question, String right, List<String> wrong, String why) {}

	private record Open(int id, int question, List<String> answers, int right, long until) {}

	private static List<Question> questions;
	private static final Map<UUID, Open> OPEN = new ConcurrentHashMap<>();
	/** Each player's shuffled bag of questions, so none repeats until all were asked. */
	private static final Map<UUID, Deque<Integer>> BAGS = new ConcurrentHashMap<>();
	private static final AtomicInteger IDS = new AtomicInteger(ThreadLocalRandom.current().nextInt(1000, 9000));

	private MerlTrivia() {}

	static synchronized List<Question> questions() {
		if (questions != null) return questions;
		List<Question> loaded = new ArrayList<>();
		try (InputStream in = MerlTrivia.class.getResourceAsStream("/nicemerl/trivia.json")) {
			if (in != null) {
				try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
					JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
					for (JsonElement e : root.getAsJsonArray("questions")) {
						JsonObject q = e.getAsJsonObject();
						List<String> wrong = new ArrayList<>();
						q.getAsJsonArray("wrong").forEach(w -> wrong.add(w.getAsString()));
						loaded.add(new Question(q.get("q").getAsString(), q.get("right").getAsString(), List.copyOf(wrong), q.get("why").getAsString()));
					}
				}
			}
		} catch (Exception e) {
			NiceMerl.LOGGER.warn("Could not read trivia.json", e);
		}
		questions = List.copyOf(loaded);
		return questions;
	}

	/** A new question with clickable answers. */
	static Component ask(ServerPlayer player) {
		List<Question> all = questions();
		String user = player.getName().getString();
		if (all.isEmpty()) return Component.literal("I lost my quiz cards! (trivia.json is missing.)");
		Deque<Integer> bag = BAGS.computeIfAbsent(player.getUUID(), k -> new ArrayDeque<>());
		if (bag.isEmpty()) {
			List<Integer> order = new ArrayList<>();
			for (int i = 0; i < all.size(); i++) order.add(i);
			Collections.shuffle(order);
			bag.addAll(order);
		}
		int index = bag.poll();
		Question q = all.get(index);
		List<String> answers = new ArrayList<>(q.wrong());
		answers.add(q.right());
		Collections.shuffle(answers);
		Open open = new Open(IDS.incrementAndGet(), index, List.copyOf(answers), answers.indexOf(q.right()), System.currentTimeMillis() + ANSWER_MILLIS);
		OPEN.put(player.getUUID(), open);

		MutableComponent out = Component.literal(MerlLines.pick("trivia_ask", "user", user))
				.append(Component.literal("\n" + q.question()).withStyle(Style.EMPTY.withColor(VALUE).withBold(true)));
		for (int i = 0; i < answers.size(); i++) {
			String command = "/nicemerl quiz " + open.id() + " " + (i + 1);
			out.append(Component.literal("\n "))
					.append(Component.literal("[" + LETTERS[i] + "] " + answers.get(i)).withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)
							.withClickEvent(new ClickEvent.RunCommand(command))
							.withHoverEvent(new HoverEvent.ShowText(Component.literal("Answer " + LETTERS[i])))));
		}
		out.append(Component.literal("\n(" + MerlLines.pick("trivia_hint") + ")").withStyle(ChatFormatting.GRAY));
		return out;
	}

	/** /nicemerl quiz id n: the clicked answer. */
	static Component answer(ServerPlayer player, int id, int choice) {
		String user = player.getName().getString();
		Open open = OPEN.get(player.getUUID());
		if (open == null || open.id() != id) return Component.literal(MerlLines.pick("trivia_expired", "user", user));
		OPEN.remove(player.getUUID());
		if (System.currentTimeMillis() > open.until()) return withNext(Component.literal(MerlLines.pick("trivia_expired", "user", user)));
		Question q = questions().get(open.question());
		boolean right = choice - 1 == open.right();
		int[] score = new int[3];
		MerlState.update(player.getUUID(), p -> {
			p.quizAnswered = (p.quizAnswered == null ? 0 : p.quizAnswered) + 1;
			if (right) {
				p.quizRight = (p.quizRight == null ? 0 : p.quizRight) + 1;
				p.quizStreak = (p.quizStreak == null ? 0 : p.quizStreak) + 1;
				p.quizBest = Math.max(p.quizBest == null ? 0 : p.quizBest, p.quizStreak);
			} else {
				p.quizStreak = 0;
			}
			score[0] = p.quizRight == null ? 0 : p.quizRight;
			score[1] = p.quizAnswered;
			score[2] = p.quizStreak;
		});
		MutableComponent out = Component.literal(right
				? MerlLines.pick(score[2] >= 3 ? "trivia_streak" : "trivia_right", "user", user, "count", String.valueOf(score[2]))
				: MerlLines.pick("trivia_wrong", "user", user, "answer", q.right()));
		out.append(Component.literal("\n" + q.why()).withStyle(ChatFormatting.GRAY));
		out.append(Component.literal("\n ✦ ").withStyle(Style.EMPTY.withColor(PINK)))
				.append(Component.literal("Score: ").withStyle(ChatFormatting.WHITE))
				.append(Component.literal(score[0] + " / " + score[1] + (score[2] > 1 ? "  ·  streak " + score[2] : ""))
						.withStyle(Style.EMPTY.withColor(VALUE)));
		return withNext(out);
	}

	private static Component withNext(MutableComponent out) {
		return out.append(Component.literal(" [Next question]").withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA)
				.withClickEvent(new ClickEvent.RunCommand("/merl quiz me"))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("Another one!")))));
	}

	/** The ten best quiz players, and where the asker stands. */
	static Component leaderboard(ServerPlayer player) {
		String user = player.getName().getString();
		List<Map.Entry<UUID, MerlState.Player>> ranked = new ArrayList<>(MerlState.players().entrySet().stream()
				.filter(e -> e.getValue().quizRight != null && e.getValue().quizRight > 0 && e.getValue().name != null).toList());
		if (ranked.isEmpty()) return withNext(Component.literal(MerlLines.pick("trivia_leaderboard_empty", "user", user)));
		ranked.sort(Comparator.<Map.Entry<UUID, MerlState.Player>>comparingInt(e -> e.getValue().quizRight).reversed()
				.thenComparingInt(e -> e.getValue().quizBest == null ? 0 : -e.getValue().quizBest));
		MutableComponent out = Component.literal(MerlLines.pick("trivia_leaderboard", "user", user));
		for (int i = 0; i < Math.min(10, ranked.size()); i++) {
			MerlState.Player p = ranked.get(i).getValue();
			boolean me = ranked.get(i).getKey().equals(player.getUUID());
			out.append(Component.literal("\n " + (i == 0 ? "🥇" : i == 1 ? "🥈" : i == 2 ? "🥉" : (i + 1) + ".") + " ").withStyle(Style.EMPTY.withColor(PINK)))
					.append(Component.literal(p.name).withStyle(me ? Style.EMPTY.withColor(ChatFormatting.AQUA) : Style.EMPTY.withColor(ChatFormatting.WHITE)))
					.append(Component.literal("  " + p.quizRight + " right" + (p.quizAnswered != null ? " of " + p.quizAnswered : "")
							+ (p.quizBest != null && p.quizBest > 1 ? ", best streak " + p.quizBest : "")).withStyle(Style.EMPTY.withColor(VALUE)));
		}
		int mine = -1;
		for (int i = 0; i < ranked.size(); i++) if (ranked.get(i).getKey().equals(player.getUUID())) mine = i;
		if (mine >= 10) out.append(Component.literal("\n You: #" + (mine + 1) + ", " + ranked.get(mine).getValue().quizRight + " right").withStyle(ChatFormatting.GRAY));
		return withNext(out);
	}
}

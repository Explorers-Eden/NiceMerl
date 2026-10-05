package eu.explorerseden.nicemerl;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What Merl remembers about each player for a little while: kept in memory only, never on disk.
 * Mirrors the bot's memory.py. Times are in milliseconds.
 */
public final class MerlMemory {
	private static final long REPEAT = 10 * 60_000L;
	private static final long FOLLOW_UP = 5 * 60_000L;
	private static final long THANKS = 5 * 60_000L;
	private static final long ASK_BACK = 5 * 60_000L;
	/** "What did you say?" and "what was I asking?" work for this long. */
	private static final long RECALL = 30 * 60_000L;
	/** Replies this close together belong to the same answer ("Let me look…", then the answer). */
	private static final long SAME_ANSWER = 10_000L;
	/** People not seen for this long are dropped when the map gets full. */
	private static final long WELCOME_BACK_UNTIL = 30 * 86_400_000L;
	private static final int MAX_PLAYERS = 5000;

	private static final Map<UUID, Visit> VISITS = new ConcurrentHashMap<>();

	private MerlMemory() {}

	public static final class Visit {
		/** Last question searched, as search words. */
		public String question = "";
		public long askedAt;
		/** Title of the page Merl answered it with. */
		public String page = "";
		/** The project that page belongs to (first part of its path). */
		public String project = "";
		public long answeredAt;
		public long askedBackAt;
		public long askedFeelingAt;
		/** The last small talk Merl answered, for "another one". */
		public String talk = "";
		public long talkedAt;
		public long seenAt;
		/** Last /merl question, for the cooldown. */
		public long lastMessageAt;
		/** The last question as written and Merl's answer to it (a chat component), memory only. */
		public String said = "";
		public long saidAt;
		public Object lastAnswer;
		/** The question being answered right now, so its replies can be remembered. */
		public String pending = "";

		/** Remembers a reply to the pending question; replies right after each other add up to one answer. */
		public void rememberReply(Object reply, java.util.function.BinaryOperator<Object> join, long now) {
			if (pending.isEmpty()) return;
			boolean same = pending.equals(said) && now - saidAt < SAME_ANSWER && lastAnswer != null;
			lastAnswer = same ? join.apply(lastAnswer, reply) : reply;
			said = pending;
			saidAt = now;
		}

		/** True when the last question was in the last half hour. */
		public boolean canRecall(long now) {
			return !said.isEmpty() && now - saidAt < RECALL;
		}

		public boolean isRepeat(String asked, long now) {
			return !asked.isEmpty() && asked.equals(question) && now - askedAt < REPEAT;
		}

		/** The last question if it was asked just now, else null. */
		public String recentQuestion(long now) {
			return !question.isEmpty() && now - askedAt < FOLLOW_UP ? question : null;
		}

		/** The page of the last answer if it was just now, else null. */
		public String recentPage(long now) {
			return !page.isEmpty() && now - answeredAt < THANKS ? page : null;
		}

		public String recentProject(long now) {
			return !project.isEmpty() && now - answeredAt < THANKS ? project : null;
		}

		public String recentTalk(long now) {
			return !talk.isEmpty() && now - talkedAt < FOLLOW_UP ? talk : null;
		}

		public boolean awaitingReply(long now) {
			return now - askedBackAt < ASK_BACK;
		}

		public boolean awaitingFeeling(long now) {
			return now - askedFeelingAt < ASK_BACK;
		}
	}

	public static Visit visit(UUID player) {
		if (VISITS.size() >= MAX_PLAYERS && !VISITS.containsKey(player)) {
			long cutoff = System.currentTimeMillis() - WELCOME_BACK_UNTIL;
			VISITS.values().removeIf(v -> v.seenAt < cutoff);
		}
		return VISITS.computeIfAbsent(player, id -> new Visit());
	}
}

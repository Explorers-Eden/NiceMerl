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
	/** "Welcome back!" after this long away, but not after so long that it's a first visit again. */
	private static final long WELCOME_BACK_AFTER = 3 * 3_600_000L;
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
		public long answeredAt;
		public long askedBackAt;
		public long askedFeelingAt;
		public long seenAt;
		/** Last /merl question, for the cooldown. */
		public long lastMessageAt;

		public boolean returning(long now) {
			return seenAt != 0 && now - seenAt >= WELCOME_BACK_AFTER && now - seenAt <= WELCOME_BACK_UNTIL;
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

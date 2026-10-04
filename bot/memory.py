"""What Merl remembers about each person for a little while: kept in memory only, never on disk.
The long-term part (when you met, what you're up to) is in friends.py.

Mirrored in the mod's MerlMemory.java.
"""

import time
from dataclasses import dataclass

REPEAT_SECONDS = 10 * 60
FOLLOW_UP_SECONDS = 5 * 60
THANKS_SECONDS = 5 * 60
ASK_BACK_SECONDS = 5 * 60
# "Welcome back!" after this long away, but not after so long that it's a first visit again.
WELCOME_BACK_AFTER = 3 * 3600
WELCOME_BACK_UNTIL = 30 * 86400
MAX_PEOPLE = 5000


@dataclass
class Visit:
    question: str = ""        # last question searched, as search words
    asked_at: float = 0.0
    page: str = ""            # title of the page Merl answered it with
    answered_at: float = 0.0
    asked_back_at: float = 0.0
    asked_feeling_at: float = 0.0
    seen_at: float = 0.0

    def is_repeat(self, question: str, now: float) -> bool:
        return bool(question) and question == self.question and now - self.asked_at < REPEAT_SECONDS

    def recent_question(self, now: float) -> str | None:
        return self.question if self.question and now - self.asked_at < FOLLOW_UP_SECONDS else None

    def recent_page(self, now: float) -> str | None:
        return self.page if self.page and now - self.answered_at < THANKS_SECONDS else None

    def awaiting_reply(self, now: float) -> bool:
        return now - self.asked_back_at < ASK_BACK_SECONDS

    def awaiting_feeling(self, now: float) -> bool:
        return now - self.asked_feeling_at < ASK_BACK_SECONDS


class Memory:
    def __init__(self):
        self.visits: dict[int, Visit] = {}

    def visit(self, person: int) -> Visit:
        v = self.visits.get(person)
        if v is None:
            if len(self.visits) >= MAX_PEOPLE:
                cutoff = time.time() - WELCOME_BACK_UNTIL
                self.visits = {k: v for k, v in self.visits.items() if v.seen_at >= cutoff}
            v = self.visits[person] = Visit()
        return v

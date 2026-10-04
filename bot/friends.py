"""What Merl remembers about people long-term, in STATE_DIR/friends.json: when you met, how often you've
chatted, when you were last here, what you said you're up to and the last page that helped. No messages.

Kept small on purpose: one short row per person, days instead of timestamps, and anyone not seen for a
year is dropped. "forget me" deletes the row. Mirrored in the mod's MerlState.Player.
"""

import json
import logging
from dataclasses import dataclass
from pathlib import Path

from memory import WELCOME_BACK_AFTER, WELCOME_BACK_UNTIL

log = logging.getLogger("nicemerl")

KEEP_DAYS = 365
MAX_FRIENDS = 5000
PAGE_CHARS = 60


@dataclass
class Friend:
    met: int = 0          # day we first talked (days since 1970), 0 = never
    chats: int = 0
    seen: int = 0         # last message, in minutes since 1970
    topic: str = ""       # what they said they're up to ("build"), see lines.json topics
    topic_day: int = 0
    page: str = ""        # title of the last page that answered them
    page_day: int = 0
    anniversary: int = 0  # the last friendship anniversary Merl mentioned, in days
    noted: int = 0        # the last number of chats Merl mentioned
    forgotten: bool = False

    def row(self) -> list:
        return [self.met, self.chats, self.seen, self.topic, self.topic_day, self.page, self.page_day,
                self.anniversary, self.noted]

    def returning(self, minute: int) -> bool:
        away = (minute - self.seen) * 60
        return bool(self.seen) and WELCOME_BACK_AFTER <= away <= WELCOME_BACK_UNTIL


class Friends:
    def __init__(self, directory: Path):
        self.path = directory / "friends.json"
        self.rows: dict[str, list] = {}
        try:
            self.rows = json.loads(self.path.read_text("utf-8"))
        except FileNotFoundError:
            pass
        except (OSError, ValueError):
            log.exception("Could not read %s, starting fresh", self.path)

    def get(self, person: int) -> Friend:
        row = self.rows.get(str(person))
        return Friend(*row) if row else Friend()

    def save(self, person: int, friend: Friend, day: int):
        if friend.forgotten:
            self.rows.pop(str(person), None)
        else:
            friend.page = friend.page[:PAGE_CHARS]
            self.rows[str(person)] = friend.row()
        if len(self.rows) > MAX_FRIENDS or day % 7 == 0:
            self._prune(day)
        try:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            self.path.write_text(json.dumps(self.rows, separators=(",", ":")), "utf-8")
        except OSError:
            log.exception("Could not write %s", self.path)

    def _prune(self, day: int):
        cutoff = (day - KEEP_DAYS) * 1440
        self.rows = {k: r for k, r in self.rows.items() if r[2] >= cutoff}
        if len(self.rows) > MAX_FRIENDS:
            newest = sorted(self.rows.items(), key=lambda kv: kv[1][2], reverse=True)[:MAX_FRIENDS]
            self.rows = dict(newest)

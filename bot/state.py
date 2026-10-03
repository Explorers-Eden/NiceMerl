"""The little that Merl keeps across restarts (Peanut Butter's pet count), in STATE_DIR/state.json."""

import json
import logging
from pathlib import Path

log = logging.getLogger("nicemerl")


class State:
    def __init__(self, directory: Path):
        self.path = directory / "state.json"
        try:
            self.data = json.loads(self.path.read_text("utf-8"))
        except FileNotFoundError:
            self.data = {}
        except (OSError, ValueError):
            log.exception("Could not read %s, starting fresh", self.path)
            self.data = {}

    def bump(self, key: str) -> int:
        self.data[key] = int(self.data.get(key, 0)) + 1
        try:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            self.path.write_text(json.dumps(self.data, indent=2), "utf-8")
        except OSError:
            log.exception("Could not write %s", self.path)
        return self.data[key]

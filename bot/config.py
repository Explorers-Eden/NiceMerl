import os
from pathlib import Path

from dotenv import load_dotenv

load_dotenv()

DISCORD_TOKEN = os.getenv("DISCORD_TOKEN", "")
CHANNEL_ID = int(os.getenv("CHANNEL_ID") or 0)
WIKI_URL = os.getenv("WIKI_URL", "https://wiki.explorerseden.eu").rstrip("/")
REINDEX_HOURS = float(os.getenv("REINDEX_HOURS") or 6)
RESULTS = int(os.getenv("RESULTS") or 3)
COOLDOWN_SECONDS = float(os.getenv("COOLDOWN_SECONDS") or 5)
# Channel people are pointed to when Merl can't help (#user-help). Set to 0 to leave it out.
HELP_CHANNEL_ID = int(os.getenv("HELP_CHANNEL_ID") or 1245007015865225256)
# Answer vanilla Minecraft questions from the Minecraft Wiki too ("false" turns it off).
VANILLA_WIKI = (os.getenv("VANILLA_WIKI") or "true").lower() not in ("0", "false", "no", "off")
VANILLA_WIKI_URL = os.getenv("VANILLA_WIKI_URL", "https://minecraft.wiki").rstrip("/")
# Time zone for Merl's "good morning" / "good evening" greetings.
TIMEZONE = os.getenv("TIMEZONE", "Europe/Berlin")
# Where Merl keeps what should survive a restart (Peanut Butter's pet count, friends.json). Mount a volume here.
STATE_DIR = Path(os.getenv("STATE_DIR") or Path(__file__).parent / "state")
# The small meaning-based search model (a folder, or a Hugging Face name). The Docker image has it
# in /app/model; empty turns meaning-based search off and Merl searches by keywords only.
SEMANTIC_MODEL = os.getenv("SEMANTIC_MODEL", str(Path(__file__).parent / "model"))

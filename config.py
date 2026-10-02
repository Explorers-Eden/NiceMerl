import os

from dotenv import load_dotenv

load_dotenv()

DISCORD_TOKEN = os.getenv("DISCORD_TOKEN", "")
CHANNEL_ID = int(os.getenv("CHANNEL_ID") or 0)
WIKI_URL = os.getenv("WIKI_URL", "https://wiki.explorerseden.eu").rstrip("/")
REINDEX_HOURS = float(os.getenv("REINDEX_HOURS") or 6)
RESULTS = int(os.getenv("RESULTS") or 3)
COOLDOWN_SECONDS = float(os.getenv("COOLDOWN_SECONDS") or 5)

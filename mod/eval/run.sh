#!/bin/sh
# Measures the mod's answers: ./gradlew build first, then run this from the mod folder.
#   eval/run.sh       # summary
#   eval/run.sh -v    # also lists every miss
# Uses the bot's test questions and wiki snapshot (run `python evaluate.py` in bot/ once to fetch it),
# and the meaning-based search model in bot/model if it's there (or pass --model <folder> yourself).
set -e
cd "$(dirname "$0")/.."
# The newest jar, not the last one by name (1.5.9 sorts after 1.5.18).
JAR=$(ls -t build/libs/nice-merl-*.jar | grep -v sources | head -1)
GSON=$(find ~/.gradle/caches -name 'gson-2*.jar' | grep -v sources | head -1)
MC=$(ls .gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-*/*/*.jar | head -1)
OUT=build/eval
mkdir -p "$OUT"
javac -cp "$JAR:$GSON:$MC" -d "$OUT" eval/Evaluate.java
java -cp "$OUT:$JAR:$GSON:$MC" eu.explorerseden.nicemerl.Evaluate \
  ../bot/eval/wiki_snapshot.json ../bot/eval/questions.json eval/settings.json "$@" \
  $( [ -d ../bot/model ] && echo --model ../bot/model )

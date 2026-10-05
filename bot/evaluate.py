"""Measures how well Merl answers, with the test questions in eval/questions.json.

    python evaluate.py            # uses eval/wiki_snapshot.json (fetched on the first run)
    python evaluate.py --refresh  # fetches the wiki again
    python evaluate.py -v         # also lists every miss
    python evaluate.py --no-model # keyword search only, to compare

Follows the same steps as bot.py's answer() for the community wiki (the Minecraft Wiki is left out:
it's live and changes). Run it before and after a change to see if answers got better.
"""

import asyncio
import dataclasses
import json
import sys
from pathlib import Path

import config
import personality as p
from search import Index, tokenize
from vanilla import confidence
from wiki import Section, fetch_sections

HERE = Path(__file__).parent
SNAPSHOT = HERE / "eval" / "wiki_snapshot.json"
QUESTIONS = HERE / "eval" / "questions.json"


def load_sections(refresh: bool) -> list[Section]:
    if refresh or not SNAPSHOT.exists():
        sections = asyncio.run(fetch_sections(config.WIKI_URL))
        SNAPSHOT.write_text(json.dumps([dataclasses.asdict(s) for s in sections]), "utf-8")
        return sections
    return [Section(**s) for s in json.loads(SNAPSHOT.read_text("utf-8"))]


def right(path: str, pages: list[str]) -> bool:
    return any(path == p or p.endswith("*") and path.startswith(p[:-1]) for p in pages)


def route(index: Index, question: str, previous_page: str = "", interests: dict | None = None) -> tuple[str, list, object]:
    """What Merl does with a message: ("talk", pool), ("unclear", []), ("pages", results, outcome)."""
    talk = p.small_talk(question) or p.multi_small_talk(question)[1] or (p.recall(question) and "recall")
    if talk:
        return "talk", talk, None
    if p.met_question(question):
        return "talk", "met", None
    prefix, search = p.split_small_talk(question)
    if hasattr(p, "resolve_reference"):
        search = p.resolve_reference(search, previous_page)
    outcome = index.find(search, limit=config.RESULTS, interests=interests)
    results = outcome.results
    asking = bool(prefix) or p.seeks_info(question) or p.is_follow_up(search)
    sure = confidence(results, outcome) if results else None
    all_matched = bool(results) and results[0].matched >= len(set(tokenize(search)))
    if not asking and not p.clearly_about(sure, bool(results) and results[0].title_match, question, all_matched):
        return "unclear", [], outcome
    if results and sure == "guess":
        if results[0].matched <= 1 and not results[0].title_match:
            return "unclear", [], outcome
        results = results[:1]
    return "pages", results, outcome


def main():
    verbose = "-v" in sys.argv
    import semantic
    # The bot uses the model folder baked into its Docker image; here it's downloaded when that folder is missing.
    source = config.SEMANTIC_MODEL if Path(config.SEMANTIC_MODEL).exists() else semantic.MODEL_NAME
    embed = None if "--no-model" in sys.argv else semantic.load(source)
    index = Index(load_sections("--refresh" in sys.argv), embed=embed)
    print("Meaning-based search:", "on" if embed else "off")
    data = json.loads(QUESTIONS.read_text("utf-8"))
    import search
    summarize = getattr(search, "answer_line", None)

    hit1 = hit3 = answered = 0
    has_answer = found_answer = 0
    misses = []
    answer_misses = []
    for item in data["questions"]:
        kind, results, outcome = route(index, item["q"])
        paths = [r.section.path for r in results] if kind == "pages" else []
        if paths and right(paths[0], item["pages"]):
            hit1 += 1
        if any(right(path, item["pages"]) for path in paths[:3]):
            hit3 += 1
        else:
            misses.append((item["q"], kind, results if kind != "pages" else paths[:3]))
        answered += kind == "pages" and bool(paths)
        if "answer" in item and kind == "pages" and results:
            has_answer += 1
            text = summarize(item["q"], results, index) if summarize else results[0].excerpt
            plain = (text or "").replace("**", "").replace("\\", "").lower()
            if item["answer"].lower() in plain:
                found_answer += 1
            else:
                answer_misses.append((item["q"], item["answer"], (text or "")[:140]))

    wrong_chatter = []
    for message in data["chatter"]:
        kind, results, _ = route(index, message)
        if kind == "pages" and results:
            wrong_chatter.append((message, results[0].section.path))

    talk_ok, talk_bad = 0, []
    for message, pool in data["talk"].items():
        if message.startswith("_"):
            continue
        kind, got, _ = route(index, message)
        if kind == "talk" and (got == pool or got in pool):
            talk_ok += 1
        else:
            talk_bad.append((message, pool, got if kind == "talk" else kind))

    follow_ok, follow_n = 0, 0
    for item in data.get("followups", []):
        if "pages" not in item:
            continue
        follow_n += 1
        _, first, _ = route(index, item["first"])
        previous = first[0].section.page_title if isinstance(first, list) and first else ""
        kind, results, _ = route(index, item["then"], previous)
        follow_ok += kind == "pages" and bool(results) and right(results[0].section.path, item["pages"])

    context_ok, context_base = 0, 0
    for item in data.get("context", []):
        _, with_interests, _ = route(index, item["q"], interests=item["interests"])
        _, without, _ = route(index, item["q"])
        context_ok += bool(with_interests) and right(with_interests[0].section.path, item["pages"])
        context_base += bool(without) and right(without[0].section.path, item["pages"])

    n = len(data["questions"])
    talk_n = len([k for k in data["talk"] if not k.startswith("_")])
    print(f"Questions:  hit@1 {hit1}/{n} ({hit1 / n:.0%})   hit@3 {hit3}/{n} ({hit3 / n:.0%})   answered {answered}/{n}")
    print(f"Answer text contains the answer: {found_answer}/{has_answer}")
    print(f"Chatter wrongly answered with a page: {len(wrong_chatter)}/{len(data['chatter'])}")
    print(f"Small talk understood: {talk_ok}/{talk_n}")
    print(f"Follow-ups (\"it\", \"him\") answered right: {follow_ok}/{follow_n}")
    print(f"Context (what the person usually asks about): {context_ok}/{len(data.get('context', []))}"
          f" (without it: {context_base})")
    if verbose:
        print("\nMisses (question, what happened, top pages):")
        for q, kind, got in misses:
            print(f"  {q!r}: {kind} {got}")
        print("\nAnswer lines without the answer (question, expected, what Merl said):")
        for q, expected, said in answer_misses:
            print(f"  {q!r}: wanted {expected!r}, said {said!r}")
        print("\nChatter that got a page:")
        for message, path in wrong_chatter:
            print(f"  {message!r} -> {path}")
        print("\nSmall talk not understood (message, expected, got):")
        for row in talk_bad:
            print(f"  {row}")


if __name__ == "__main__":
    main()

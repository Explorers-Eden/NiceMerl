"""Checks that every {placeholder} in Merl's lines gets a value wherever the code picks from that pool.

A line like "Careful, {user}!" picked without a user would show "{user}" in chat. This scans every pick(…)
call with a fixed pool name in the bot (Python) and the mod (Java) and compares the values passed with the
placeholders the pool's lines use. Pools picked by a computed name ("reply_" + topic) aren't checked. Run from bot/:

    python check_placeholders.py
"""
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
MOD = HERE.parent / "mod" / "src" / "main" / "java"


def calls(text: str) -> list[str]:
    """The argument text of every pick(…) call."""
    out = []
    for m in re.finditer(r"\bpick\(", text):
        i = j = m.end()
        depth, quote = 1, None
        while j < len(text) and depth:
            c = text[j]
            if quote:
                if c == "\\":
                    j += 1
                elif c == quote:
                    quote = None
            elif c in "\"'":
                quote = c
            elif c == "(":
                depth += 1
            elif c == ")":
                depth -= 1
            j += 1
        out.append(text[i:j - 1])
    return out


def arguments(text: str) -> list[str]:
    parts, depth, current, quote = [], 0, "", None
    for c in text:
        if quote:
            current += c
            if c == quote:
                quote = None
            continue
        if c in "\"'":
            quote = c
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        if c == "," and depth == 0:
            parts.append(current.strip())
            current = ""
        else:
            current += c
    if current.strip():
        parts.append(current.strip())
    return parts


def main() -> int:
    pools = json.loads((HERE / "data" / "lines.json").read_text("utf-8"))["pools"]
    needed = {pool: set(re.findall(r"\{(\w+)\}", " ".join(lines))) for pool, lines in pools.items()}
    problems: dict[tuple[str, str], set[str]] = {}
    sources = [("bot", p, False) for p in HERE.glob("*.py")] + [("mod", p, True) for p in MOD.rglob("*.java")]
    for side, path, java in sources:
        for call in calls(path.read_text("utf-8")):
            args = arguments(call)
            if not args or not re.fullmatch(r'"\w+"', args[0]):
                continue
            pool = args[0].strip('"')
            if java:  # pool, "key", value, "key", value…
                given = {args[k].strip('"') for k in range(1, len(args), 2) if re.fullmatch(r'"\w+"', args[k])}
            else:  # pool, key=value…
                given = {a.split("=")[0].strip() for a in args[1:] if "=" in a}
            missing = needed.get(pool, set()) - given
            if missing:
                problems.setdefault((side, pool), set()).update(missing)
    for (side, pool), missing in sorted(problems.items()):
        print(f"{side}: pick(\"{pool}\") never fills {sorted(missing)}")
    if problems:
        return 1
    print("Every placeholder gets a value")
    return 0


if __name__ == "__main__":
    sys.exit(main())

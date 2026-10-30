"""Does the comparison report the changes that matter, and only those?

    python -m clause_service.evaluate_diff [--contracts 12]

Real contracts from CUAD, each given a second version with one known edit, compared through the
backend exactly as the app compares them. Four edits change what the contract says:

- number: a figure in a clause changed (an amount, a rate, a number of days);
- negation: "shall" became "shall not" in a clause;
- removed: a clause deleted;
- added: a clause from another contract inserted;

and three do not:

- moved: two clauses swapped places;
- formatting: line breaks and spacing changed, a heading put in capitals;
- reworded: legal phrasing replaced by plain phrasing with the same meaning ("shall" -> "will",
  "prior to" -> "before", "in the event that" -> "if", "pursuant to" -> "under", ...).

A change that matters is found if the edited clause is reported as added, removed or changed in
meaning. An edit that does not matter is a false alarm if any clause is reported as changed in
meaning, added or removed. For comparison, a plain text diff: any clause whose text differs is a
change.
"""

from __future__ import annotations

import argparse
import json
import random
import re
from collections import defaultdict

import httpx

from .evaluate_extraction import ROOT, contracts, upload

API = "http://127.0.0.1:8080"
REWORDINGS = [(r"\bshall\b", "will"), (r"\bprior to\b", "before"), (r"\bin the event that\b", "if"),
              (r"\bpursuant to\b", "under"), (r"\bin accordance with\b", "under"), (r"\bupon\b", "on"),
              (r"\bhereunder\b", "under this Agreement"), (r"\bcommence\b", "begin"), (r"\bterminate\b", "end"),
              (r"\bsubsequent to\b", "after"), (r"\bin order to\b", "to"), (r"\bwith respect to\b", "about")]
FIGURE = re.compile(r"\b(\d{1,4}(?:[.,]\d+)?)\s*(%|percent|days?|months?|years?|dollars|\$)", re.IGNORECASE)


def bodies(c: dict) -> list[dict]:
    """Clauses worth editing: with a number, some text, not the preamble."""
    return [x for x in c["clauses"] if x["number"] and 150 <= x["end"] - x["start"] <= 3000]


def edit(kind: str, c: dict, donor: dict, rng: random.Random) -> tuple[str, str] | None:
    """(new text, the edited clause's first words) or None if the contract offers no place for it."""
    text = c["text"]
    cl = bodies(c)
    rng.shuffle(cl)
    if kind == "number":
        for x in cl:
            m = FIGURE.search(text, x["start"], x["end"])
            if m:
                v = m.group(1)
                new = str(int(float(v.replace(",", ".")) * 2) + 1)
                return text[:m.start(1)] + new + text[m.end(1):], text[x["start"]:x["start"] + 60]
    if kind == "negation":
        for x in cl:
            m = re.search(r"\bshall\b(?! not)", text[x["start"]:x["end"]])
            if m:
                at = x["start"] + m.end()
                return text[:at] + " not" + text[at:], text[x["start"]:x["start"] + 60]
    if kind == "removed" and cl:
        x = cl[0]
        return text[:x["start"]] + text[x["end"]:], text[x["start"]:x["start"] + 60]
    if kind == "added":
        d = bodies(donor)
        if d and cl:
            x, y = cl[0], rng.choice(d)
            piece = donor["text"][y["start"]:y["end"]].rstrip() + "\n"
            return text[:x["end"]] + piece + text[x["end"]:], piece[:60]
    if kind == "moved" and len(cl) >= 2:
        a, b = sorted(cl[:2], key=lambda x: x["start"])
        if a["end"] <= b["start"]:
            ta, tb = text[a["start"]:a["end"]], text[b["start"]:b["end"]]
            return text[:a["start"]] + tb + text[a["end"]:b["start"]] + ta + text[b["end"]:], ""
    if kind == "formatting":
        t = re.sub(r"(?<=[.;:]) (?=[A-Z])", "\n", text)  # sentences on lines of their own
        t = re.sub(r" (?=\w)", "  ", t, count=200)
        for x in cl[:1]:
            if x["title"]:
                t = t.replace(x["title"], x["title"].upper(), 1)
        return t, ""
    if kind == "reworded":
        for x in cl:
            body = text[x["start"]:x["end"]]
            new = body
            for pat, rep in REWORDINGS:
                new = re.sub(pat, rep, new)
            if new != body:
                return text[:x["start"]] + new + text[x["end"]:], text[x["start"]:x["start"] + 60]
    return None


def reported(entry: dict) -> bool:
    """Reported as a change that matters: added, removed, or changed in meaning."""
    if entry["kind"] in ("ADDED", "REMOVED"):
        return True
    return entry["kind"] == "MODIFIED" and (entry.get("meaning") or {}).get("meaning") != "same"


def touches(entry: dict, text_a: str, text_b: str, words: str) -> bool:
    key = words.strip()[:40]
    if not key:
        return False
    for side, t in (("a", text_a), ("b", text_b)):
        c = entry.get(side)
        if c and key[:25] in t[c["start"]:c["end"]]:
            return True
    return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--contracts", type=int, default=12)
    args = ap.parse_args()
    rng = random.Random(0)
    rows = contracts(args.contracts + 1, seed=7, max_chars=30000)
    kinds = ["number", "negation", "removed", "added", "moved", "formatting", "reworded"]
    matters = {"number", "negation", "removed", "added"}
    score = defaultdict(lambda: {"tried": 0, "ok": 0, "text_diff_ok": 0})
    notes = []
    for i, row in enumerate(rows[:-1]):
        a = upload(API, row["title"], row["context"])
        donor = upload(API, rows[-1]["title"], rows[-1]["context"])
        for kind in kinds:
            e = edit(kind, a, donor, rng)
            if e is None:
                continue
            new_text, words = e
            b = upload(API, row["title"] + f" ({kind})", new_text)
            try:
                r = httpx.get(f"{API}/api/compare", params={"a": a["id"], "b": b["id"]}, timeout=3600).json()
            except httpx.HTTPError as err:
                notes.append({"edit": kind, "contract": row["title"], "error": str(err)})
                continue
            entries = r["entries"]
            hits = [x for x in entries if reported(x)]
            text_changes = [x for x in entries if x["kind"] in ("MODIFIED", "ADDED", "REMOVED")]
            s = score[kind]
            s["tried"] += 1
            if kind in matters:
                ok = any(touches(x, a["text"], b["text"], words) for x in hits)
                s["ok"] += ok
                s["text_diff_ok"] += any(touches(x, a["text"], b["text"], words) for x in text_changes)
            else:
                s["ok"] += not hits  # no false alarm
                s["text_diff_ok"] += not text_changes
                if hits:
                    notes.append({"edit": kind, "contract": row["title"], "reported": [
                        {"kind": x["kind"], "meaning": x.get("meaning"), "facts": x["facts"]} for x in hits[:3]]})
        print(f"{i + 1:2d} {row['title'][:70]}", flush=True)
    summary = {k: {"tried": v["tried"], ("found" if k in matters else "no_false_alarm"): round(v["ok"] / max(1, v["tried"]), 3),
                   "plain_text_diff": round(v["text_diff_ok"] / max(1, v["tried"]), 3)} for k, v in score.items()}
    print(json.dumps(summary, indent=1))
    (ROOT / "results" / "diff.json").write_text(json.dumps({"summary": summary, "false_alarms": notes}, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()

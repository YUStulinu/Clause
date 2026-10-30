"""Does the checker catch a statement that its quote does not support?

    python -m clause_service.evaluate_verifier

Starting from the findings the extraction evaluation verified on CUAD contracts, each is
corrupted in one way, the way a language model gets things wrong, and checked again:

- number: a number in the statement changed (30 days -> 60 days);
- negation: the statement negated ("shall" -> "shall not", "may" -> "may not");
- party: the party in the statement swapped for another party of the same contract;
- wrong quote: the statement paired with the quote of another finding of the same contract;
- invented quote: the statement offered as its own quote, words the contract does not contain.

Reported: the share caught (not verified) for each, and the share of the uncorrupted findings that
still pass (they should all: the checks are deterministic).
"""

from __future__ import annotations

import json
import random
import re
from collections import defaultdict
from pathlib import Path

from .evaluate_extraction import ROOT, contracts, upload
from .extract import ClauseRef, Finding
from .nli import Nli
from .verify import NAME, STOP, verify

NUM = re.compile(r"\b\d+(?:[.,]\d+)?\b")
NEGATE = [(r"\bshall\b", "shall not"), (r"\bmust\b", "must not"), (r"\bmay\b", "may not"), (r"\bwill\b", "will not"),
          (r"\bcan\b", "cannot"), (r"\bis\b", "is not"), (r"\bare\b", "are not"), (r"\bhas\b", "does not have"),
          (r"\bpays\b", "does not pay"), (r"\bagrees\b", "does not agree")]


def corrupt_number(s: str, rng: random.Random) -> str | None:
    nums = NUM.findall(s)
    if not nums:
        return None
    n = rng.choice(nums)
    v = float(n.replace(",", "."))
    new = str(int(v * 2) if v >= 1 else v * 2) if v != 0 else "5"
    return s.replace(n, new, 1)


def corrupt_negation(s: str) -> str | None:
    for pat, rep in NEGATE:
        if re.search(pat, s) and not re.search(r"\bnot\b|n't|\bno\b|\bnever\b", s):
            return re.sub(pat, rep, s, count=1)
    return None


def names(s: str) -> list[str]:
    return [n for n in NAME.findall(s) if n.lower() not in STOP]


def corrupt_party(s: str, others: list[str]) -> str | None:
    mine = names(s)
    if not mine:
        return None
    candidates = [o for o in others if o not in s and o.lower() not in s.lower()]
    if not candidates:
        return None
    return s.replace(mine[0], candidates[0], 1)


def main():
    rng = random.Random(0)
    rows = [json.loads(l) for l in (ROOT / "data" / "findings-all.jsonl").read_text(encoding="utf-8").splitlines()]
    verified = [r for r in rows if r["status"] == "verified"]
    by_contract = defaultdict(list)
    for r in verified:
        by_contract[r["contract"]].append(r)
    texts = {}
    for row in contracts(10**6):
        if row["title"] in by_contract:
            texts[row["title"]] = upload("http://127.0.0.1:8080", row["title"], row["context"])
    nli = Nli()
    results = defaultdict(lambda: {"tried": 0, "caught": 0})
    examples = defaultdict(list)
    for title, findings in by_contract.items():
        c = texts[title]
        text = c["text"]
        clauses = [ClauseRef(x["id"], x["number"], x["title"], x["start"], x["end"]) for x in c["clauses"]]
        parties = sorted({n for f in findings for n in names(f["summary"]) + names(f["party"])})
        cases = []
        for i, f in enumerate(findings):
            base = dict(clause=f["clause"], kind=f["kind"], party=f["party"], summary=f["summary"], quote=f["quote"], why="")
            cases.append(("original", Finding(**base)))
            if (s := corrupt_number(f["summary"], rng)):
                cases.append(("number", Finding(**{**base, "summary": s})))
            if (s := corrupt_negation(f["summary"])):
                cases.append(("negation", Finding(**{**base, "summary": s})))
            if (s := corrupt_party(f["summary"], parties)):
                cases.append(("party", Finding(**{**base, "summary": s, "party": ""})))
            other = [g for g in findings if g["kind"] != f["kind"] and g["quote"] != f["quote"]]
            if other:
                g = rng.choice(other)
                cases.append(("wrong quote", Finding(**{**base, "quote": g["quote"], "clause": g["clause"]})))
            cases.append(("invented quote", Finding(**{**base, "quote": f["summary"]})))
        # One at a time: the de-duplication must not merge a corruption into its original.
        for kind, finding in cases:
            out = verify([finding], text, clauses, nli)[0]
            ok = out.status == "verified"
            results[kind]["tried"] += 1
            results[kind]["caught"] += (not ok) if kind != "original" else ok
            if kind != "original" and ok and len(examples[kind]) < 5:
                examples[kind].append({"summary": finding.summary, "quote": text[out.start:out.end]})
    summary = {k: {"tried": v["tried"], ("kept" if k == "original" else "caught"): round(v["caught"] / max(1, v["tried"]), 3)}
               for k, v in results.items()}
    print(json.dumps(summary, indent=1))
    (ROOT / "results" / "verifier.json").write_text(json.dumps({"summary": summary, "missed": examples}, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()

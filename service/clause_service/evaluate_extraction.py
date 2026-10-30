"""Extraction on real contracts: CUAD (Hendrycks et al., 2021; CC BY 4.0), 510 commercial contracts
whose clauses lawyers labelled in 41 categories, each label an exact span of the text.

    python -m clause_service.evaluate_extraction [--contracts 30]

The contracts go through the backend (reading, clause splitting) and then the same extraction and
checks the app uses. CUAD's labels do not cover everything Clause reports (any obligation, any
payment), so they measure recall: of the labelled clauses in the categories that match Clause's
kinds, how many does some finding quote? Reported:

- recall of CUAD's labelled spans by any finding, and by verified findings only;
- the same, counting only findings of the matching kind;
- how the quotes anchored (exact, repaired, missing) and how many findings passed the checks.

A sample of findings is written out for labelling by hand (bench/finding-labels.jsonl), which
measures what the checks themselves get right.
"""

from __future__ import annotations

import argparse
import json
import random
import time
from collections import Counter, defaultdict
from dataclasses import asdict
from pathlib import Path

import httpx

from . import anchor
from .extract import ClauseRef, extract
from .llm import Llm
from .nli import Nli
from .verify import verify

ROOT = Path(__file__).resolve().parents[2]
CUAD = ROOT / "data" / "cuad" / "CUAD_v1"

# CUAD's categories that are what Clause looks for, and the kind it should report them as.
CATEGORIES = {
    "Termination For Convenience": "termination",
    "Renewal Term": "termination",
    "Notice Period To Terminate Renewal": "termination",
    "Liquidated Damages": "penalty",
    "Cap On Liability": "liability",
    "Uncapped Liability": "liability",
    "Covenant Not To Sue": "liability",
    "Minimum Commitment": "payment",
    "Revenue/Profit Sharing": "payment",
    "Price Restrictions": "payment",
    "Non-Compete": "obligation",
    "Exclusivity": "obligation",
    "No-Solicit Of Customers": "obligation",
    "No-Solicit Of Employees": "obligation",
    "Non-Disparagement": "obligation",
    "Audit Rights": "obligation",
    "Insurance": "obligation",
}


def contracts(n: int, seed: int = 0, max_chars: int = 40000) -> list[dict]:
    data = json.loads((CUAD / "CUAD_v1.json").read_text(encoding="utf-8"))["data"]
    rows = []
    for d in data:
        p = d["paragraphs"][0]
        if len(p["context"]) > max_chars:
            continue
        labels = []
        for qa in p["qas"]:
            cat = qa["id"].split("__")[-1]
            if cat in CATEGORIES:
                labels += [{"category": cat, "text": a["text"]} for a in qa["answers"]]
        if labels:
            rows.append({"title": d["title"], "context": p["context"], "labels": labels})
    random.Random(seed).shuffle(rows)
    return rows[:n]


def upload(api: str, name: str, text: str) -> dict:
    r = httpx.post(f"{api}/api/contracts", files={"file": (f"{name[:60]}.txt", text.encode("utf-8"), "text/plain")}, timeout=60)
    r.raise_for_status()
    return r.json()


def overlaps(a: tuple[int, int], b: tuple[int, int]) -> bool:
    return a[0] < b[1] and b[0] < a[1]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--contracts", type=int, default=30)
    ap.add_argument("--api", default="http://127.0.0.1:8080")
    ap.add_argument("--out", default=str(ROOT / "results" / "extraction.json"))
    args = ap.parse_args()
    llm, nli = Llm(), Nli()
    if not llm.ready():
        raise SystemExit("start the language model first (python -m clause_service.server starts it)")

    found_any, found_verified, found_kind, total = Counter(), Counter(), Counter(), Counter()
    anchors, statuses, kinds = Counter(), Counter(), Counter()
    sample, per_contract = [], []
    for i, row in enumerate(contracts(args.contracts)):
        t0 = time.time()
        c = upload(args.api, row["title"], row["context"])
        text = c["text"]
        clauses = [ClauseRef(x["id"], x["number"], x["title"], x["start"], x["end"]) for x in c["clauses"]]
        findings = verify(extract(llm, text, clauses, "en"), text, clauses, nli)
        tn = anchor.normal(text)
        gold = []
        for lab in row["labels"]:
            a = anchor.find(lab["text"], text, None, tn)
            if a.status != "missing":
                gold.append((lab["category"], (a.start, a.end)))
        for cat, span in gold:
            total[cat] += 1
            hits = [f for f in findings if f.start >= 0 and overlaps((f.start, f.end), span)]
            found_any[cat] += bool(hits)
            found_verified[cat] += any(f.status == "verified" for f in hits)
            found_kind[cat] += any(f.status == "verified" and f.kind == CATEGORIES[cat] for f in hits)
        for f in findings:
            anchors[f.checks.get("anchor", "?")] += 1
            statuses[f.status] += 1
            kinds[f.kind] += 1
            sample.append({"contract": row["title"], **{k: v for k, v in asdict(f).items() if k != "checks"},
                           "checks": f.checks, "source": text[f.start:f.end] if f.start >= 0 else ""})
        per_contract.append({"title": row["title"], "chars": len(text), "clauses": len(clauses), "findings": len(findings),
                             "verified": sum(f.status == "verified" for f in findings), "seconds": round(time.time() - t0, 1)})
        print(f"{i + 1:2d} {row['title'][:60]:60s} {len(findings):3d} findings, {per_contract[-1]['verified']:3d} verified, "
              f"{len(gold)} labels ({per_contract[-1]['seconds']} s)", flush=True)

    def rate(c: Counter) -> dict:
        return {cat: round(c[cat] / total[cat], 3) for cat in total}

    n = sum(total.values())
    results = {
        "contracts": len(per_contract), "labelled_spans": n,
        "recall_any": round(sum(found_any.values()) / n, 3),
        "recall_verified": round(sum(found_verified.values()) / n, 3),
        "recall_verified_same_kind": round(sum(found_kind.values()) / n, 3),
        "by_category": {cat: {"labels": total[cat], "any": rate(found_any)[cat], "verified": rate(found_verified)[cat],
                              "same_kind": rate(found_kind)[cat]} for cat in sorted(total)},
        "findings": sum(statuses.values()), "anchors": dict(anchors), "statuses": dict(statuses), "kinds": dict(kinds),
        "median_seconds_per_contract": sorted(p["seconds"] for p in per_contract)[len(per_contract) // 2],
        "per_contract": per_contract,
    }
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text(json.dumps(results, indent=1), encoding="utf-8")
    random.Random(1).shuffle(sample)
    (ROOT / "data" / "findings-all.jsonl").write_text("".join(json.dumps(s, ensure_ascii=False) + "\n" for s in sample), encoding="utf-8")
    print(json.dumps({k: v for k, v in results.items() if k not in ("per_contract", "by_category")}, indent=1))


if __name__ == "__main__":
    main()

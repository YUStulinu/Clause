"""Checks findings written by someone else (an AI agent, a person) with the same four checks the app
uses (verify.py): the quote must be in the contract, every number and name in the statement must
be in the quote, and an entailment model must find the statement in the quote.

    python -m clause_service.check contract.json findings.json [--no-nli]

contract.json is what `java -jar clause.jar read <contract>` prints (text, clauses). findings.json
is a list of {"kind", "party", "summary", "quote"}, with "clause" (an id from contract.json) and
"why" if known. Prints {"verified": [...], "set_aside": [...]} as JSON, each finding with its
position in the text and the result of every check.

--no-nli skips the entailment model (it needs PyTorch and a 1 GB download): the anchor, number and
name checks still run, and a finding passes on those alone, marked "entailment": "skipped".
"""
from __future__ import annotations

import argparse
import json
import sys
from dataclasses import asdict
from pathlib import Path

from .extract import KINDS, ClauseRef, Finding
from .nli import Judgement
from .verify import verify


def skipped(pairs):
    """A stand-in for the entailment model that neither confirms nor refutes."""
    return [Judgement(1.0, 0.0, 0.0) for _ in pairs]


def check(contract: dict, items: list[dict], nli=None) -> dict:
    text = contract["text"]
    clauses = [ClauseRef(c["id"], c.get("number", ""), c.get("title", ""), c["start"], c["end"]) for c in contract.get("clauses", [])]
    findings = []
    for it in items:
        kind = it.get("kind", "")
        if kind not in KINDS:
            raise ValueError(f"unknown kind {kind!r}: use one of {', '.join(KINDS)}")
        findings.append(Finding(clause=it.get("clause", ""), kind=kind, party=it.get("party", ""),
                                summary=it.get("summary", ""), quote=it.get("quote", ""), why=it.get("why", "")))
    # verify() marks every finding in place, then keeps one per kind and passage for the app. Here every
    # finding is reported: two statements on one quote, one right and one wrong, must both come back.
    verify(findings, text, clauses, nli or skipped)
    verified, aside = [], []
    for f in sorted(findings, key=lambda f: (f.start if f.start >= 0 else 10**9)):
        d = asdict(f)
        if nli is None and "entailment" in d["checks"]:
            d["checks"]["entailment"] = "skipped"
            d["checks"].pop("contradiction", None)
        d["passage"] = text[f.start:f.end] if f.start >= 0 else ""
        (verified if f.status == "verified" else aside).append(d)
    return {"verified": verified, "set_aside": aside}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("contract", type=Path, help="the JSON of `java -jar clause.jar read <contract>`")
    ap.add_argument("findings", type=Path, help="a JSON list of {kind, party, summary, quote}")
    ap.add_argument("--no-nli", action="store_true", help="skip the entailment model")
    args = ap.parse_args()
    contract = json.loads(args.contract.read_text(encoding="utf-8"))
    items = json.loads(args.findings.read_text(encoding="utf-8"))
    if not isinstance(items, list):
        sys.exit("findings.json must be a JSON list of {kind, party, summary, quote}")
    nli = None
    if not args.no_nli:
        from .nli import Nli
        nli = Nli()
    try:
        result = check(contract, items, nli)
    except ValueError as e:
        sys.exit(str(e))
    sys.stdout.reconfigure(encoding="utf-8")
    print(json.dumps(result, indent=1, ensure_ascii=False))


if __name__ == "__main__":
    main()

"""Holding every statement to the contract. A finding is shown as fact only if all four checks pass:

1. anchor: its quote is in the contract (exactly, or closely enough to be repaired to the real
   text: anchor.py); the statement is checked against that real text from here on;
2. numbers: every number in the statement is in the quote (numbers.py);
3. names: every name in the statement (the Landlord, Zynga, Banca) appears in the quote: a model
   that turns "the Tenant pays" into "the Landlord pays" writes a sentence an entailment model
   happily accepts, so the parties are checked on their own. (A quote that says "you" or "we"
   allows the names the contract gives those parties.);
4. entailment: a natural language inference model reads the quote as premise and the statement as
   hypothesis, and must find it entailed (at least 0.5) and not contradicted (below 0.3).

What fails is kept, marked with the checks it failed, so the reader can see it was not confirmed.
"""

from __future__ import annotations

import re

from . import anchor, numbers
from .extract import ClauseRef, Finding

ENTAIL = 0.5
CONTRADICT = 0.3

# Capitalised words that are not names.
STOP = {w.lower() for w in """The A An This That These Those It Its If In On At By For To Of And Or But Not No Each Every Either Any All
Both You Your Yours We Our They Their He She His Her Upon After Before During Within Under Over Until Unless When Where
While Such Other Another Any Some Monthly Annual Annually Daily Contract Agreement Party Parties Notice Day Days Month Months
Year Years Fee Fees Payment Payments Interest Rate Term Terms Clause Section Article Price Prices
Contractul Contractului Prezentul Părțile Partea Părți Fiecare Orice Dacă Daca În In La Pe Cu Din Pentru Și Si Sau Nu
Plata Plății Dobânda Dobânzii Comisionul Comision Penalități Penalitati Termenul Luna Lunar Anual Ziua Zile""".split()}

NAME = re.compile(r"\b[A-ZĂÂÎȘȚ][\wăâîșț'’-]{2,}")
WORD = re.compile(r"[\wăâîșț]+", re.IGNORECASE)


PRONOUNS = {"you", "your", "yours", "we", "our", "us", "dumneavoastră", "dumneavoastra", "dvs", "noi"}


def unseen_names(statement: str, quote: str, context: str = "") -> list[str]:
    """Capitalised words in the statement with no word of the same stem in the quote. A quote in the
    first or second person ("you must pay") names nobody: then a name that the clause or the
    contract's opening defines (the Customer, the Company) is accepted."""
    words = [w.lower() for w in WORD.findall(quote)]
    personal = any(w in PRONOUNS for w in words)
    around = [w.lower() for w in WORD.findall(context)] if personal else []
    out = []
    for n in NAME.findall(statement):
        low = n.lower().strip("'’-")
        if low in STOP:
            continue
        stem = low[: min(5, len(low))]
        if not any(w.startswith(stem) for w in words) and not any(w.startswith(stem) for w in around):
            out.append(n)
    return out


def personal(statement: str, party: str) -> str:
    """The statement with "you" replaced by the party it is about, for the entailment model."""
    p = party.strip()
    if not p or p.lower() in ("both", "you", "either"):
        return statement
    return re.sub(r"\b[Yy]ou(r)?\b", lambda m: p + ("'s" if m.group(1) else ""), statement)


def verify(findings: list[Finding], text: str, clauses: list[ClauseRef], nli) -> list[Finding]:
    spans = {}
    for c in clauses:
        s, e = spans.get(c.id, (c.start, c.end))
        spans[c.id] = (min(s, c.start), max(e, c.end))
    numbers_of = {c.id: c.number for c in clauses}
    tn = anchor.normal(text)
    pending = []
    for f in findings:
        a = anchor.find(f.quote, text, spans.get(f.clause), tn)
        f.checks = {"anchor": a.status}
        if a.status == "missing":
            f.status = "unanchored"
            continue
        f.start, f.end = a.start, a.end
        source = text[a.start:a.end]
        clause_number = numbers_of.get(f.clause, "")
        ignore = {numbers.value(p) for p in re.findall(r"\d+", clause_number)}
        missing = [v for v in numbers.unsupported(f.summary, source) if v not in ignore]
        f.checks["numbers"] = "ok" if not missing else "missing " + ", ".join(f"{v:g}" for v in missing)
        span = spans.get(f.clause, (a.start, a.end))
        names = unseen_names(f.summary, source, text[:1500] + "\n" + text[span[0]:span[1]])
        f.checks["names"] = "ok" if not names else "not in the quote: " + ", ".join(names)
        pending.append((f, source))
    judged = nli([(source, personal(f.summary, f.party)) for f, source in pending]) if pending else []
    for (f, _), j in zip(pending, judged):
        f.checks["entailment"] = round(j.entailment, 3)
        f.checks["contradiction"] = round(j.contradiction, 3)
        ok = (f.checks["numbers"] == "ok" and f.checks["names"] == "ok"
              and j.entailment >= ENTAIL and j.contradiction < CONTRADICT)
        f.status = "verified" if ok else "unsupported"
    return dedupe(findings)


def dedupe(findings: list[Finding]) -> list[Finding]:
    """One finding per kind and passage: the first, or the verified one."""
    order = {"verified": 0, "unsupported": 1, "unanchored": 2, "unchecked": 3}
    kept: list[Finding] = []
    for f in sorted(findings, key=lambda f: order.get(f.status, 9)):
        if f.start >= 0 and any(k.kind == f.kind and k.start >= 0 and k.start < f.end and f.start < k.end for k in kept):
            continue
        if f.start < 0 and any(k.summary == f.summary for k in kept):
            continue
        kept.append(f)
    return sorted(kept, key=lambda f: (f.start if f.start >= 0 else 10**9))

"""What a contract asks of you, from the language model, a few clauses at a time.

The contract is read in chunks of whole clauses (about 3,500 characters), each with the
contract's opening (who the parties are) for context. For every chunk the model lists findings in
six kinds, each with a one-sentence summary and the exact sentence it rests on. The model only
proposes: nothing it says reaches the reader before verify.py has checked it against the text.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from .llm import Llm

KINDS = ["obligation", "payment", "penalty", "termination", "liability", "unusual"]

KIND_HELP = {
    "obligation": "something a party must do or must not do (including non-compete, exclusivity, confidentiality, insurance, audits)",
    "payment": "money a party pays: prices, fees, commissions, interest rates, the annual percentage rate (DAE/APR), deposits, price increases, minimum amounts",
    "penalty": "what a party pays or loses for a breach or a delay: late fees, penalty interest, liquidated damages, forfeited deposits",
    "termination": "how and when the contract ends or renews: its term, automatic renewal, notice periods, who may end it early and at what cost",
    "liability": "who answers for damage: limits or exclusions of liability, indemnities, waivers of rights",
    "unusual": "a clause that is unusual or one-sided for this kind of contract and that a careful reader would want to know about; say why",
}

SYSTEM = """You read contracts for people who are not lawyers and point out what matters to them.
You get an excerpt of a contract, clause by clause, each clause starting with its id in square brackets.
List the findings in it, of these kinds:
{kinds}
Rules:
- "quote" is copied character for character from the excerpt: one sentence or part of one, at most 60 words, without the clause id. Never paraphrase a quote, never join pieces of different sentences.
- "summary" says in plain words, in {language}, only what the quote says: who must do what, with the numbers exactly as in the quote. One sentence, at most 30 words. Do not mention clause numbers.
- "party" is the party the finding is about, named as the contract names it (for example "the Tenant", "Zynga"), or "both".
- Only list what the excerpt actually contains. If a clause has nothing that matters, list nothing for it. Do not repeat a finding.
- For "unusual", "why" says in one sentence what makes the clause unusual; for other kinds leave "why" empty."""

LANGUAGES = {"en": "English", "ro": "Romanian"}


def schema(clause_ids: list[str]) -> dict:
    return {
        "type": "object",
        "properties": {
            "findings": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "clause": {"type": "string", "enum": clause_ids},
                        "kind": {"type": "string", "enum": KINDS},
                        "party": {"type": "string"},
                        "summary": {"type": "string"},
                        "quote": {"type": "string"},
                        "why": {"type": "string"},
                    },
                    "required": ["clause", "kind", "party", "summary", "quote", "why"],
                },
            }
        },
        "required": ["findings"],
    }


@dataclass
class ClauseRef:
    id: str
    number: str
    title: str
    start: int
    end: int


@dataclass
class Finding:
    clause: str
    kind: str
    party: str
    summary: str
    quote: str
    why: str = ""
    # Filled in by the checks:
    status: str = "unchecked"
    start: int = -1
    end: int = -1
    checks: dict = field(default_factory=dict)


def chunks(clauses: list[ClauseRef], text: str, size: int = 3500) -> list[list[ClauseRef]]:
    """Whole clauses, packed up to `size` characters; a longer clause is cut at line or sentence ends."""
    out, cur, length = [], [], 0
    for c in clauses:
        if not text[c.start:c.end].strip():
            continue
        pieces = [c] if c.end - c.start <= size else split_long(c, text, size)
        for p in pieces:
            n = p.end - p.start
            if cur and length + n > size:
                out.append(cur)
                cur, length = [], 0
            cur.append(p)
            length += n
    if cur:
        out.append(cur)
    return out


def split_long(c: ClauseRef, text: str, size: int) -> list[ClauseRef]:
    pieces, start = [], c.start
    while c.end - start > size:
        window = text[start:start + size]
        cut = max(window.rfind("\n"), window.rfind(". "))
        cut = start + (cut + 1 if cut > size // 3 else size)
        pieces.append(ClauseRef(c.id, c.number, c.title, start, cut))
        start = cut
    pieces.append(ClauseRef(c.id, c.number, c.title, start, c.end))
    return pieces


def prompt(chunk: list[ClauseRef], text: str, opening: str) -> str:
    body = "\n\n".join(f"[{c.id}] {text[c.start:c.end].strip()}" for c in chunk)
    return f"The contract begins:\n{opening}\n\nExcerpt:\n{body}"


def extract(llm: Llm, text: str, clauses: list[ClauseRef], answer_language: str = "en",
            size: int = 3500) -> list[Finding]:
    system = SYSTEM.format(kinds="\n".join(f"- {k}: {h}" for k, h in KIND_HELP.items()),
                           language=LANGUAGES.get(answer_language, "English"))
    opening = text[:700].strip()
    found: list[Finding] = []
    for chunk in chunks(clauses, text, size):
        ids = sorted({c.id for c in chunk})
        data = llm.json(system, prompt(chunk, text, opening), schema(ids), max_tokens=1800)
        for f in data.get("findings", []):
            if not f.get("quote", "").strip() or not f.get("summary", "").strip():
                continue
            found.append(Finding(f["clause"], f["kind"], f.get("party", ""), f["summary"].strip(), f["quote"].strip(),
                                 f.get("why", "").strip()))
    return found

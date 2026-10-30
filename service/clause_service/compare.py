"""Did a changed clause change its meaning?

The backend has already paired the clauses of two versions and found the changed ones, with the
sentences found nowhere in the other version, the hard facts that changed in them (amounts, rates,
terms), and whether a negation came or went or an obligation became a permission. Here:

1. A changed fact, negation or obligation: the meaning changed, no question.
2. Otherwise the changed sentences are compared. Each is paired with its closest counterpart, and
   the entailment model must find each one entailing the other (both ways, at least 0.5). A
   sentence with no counterpart, added or dropped, changes the meaning. If every pair entails the
   other, the clause was only reworded. (0.5, not higher: measured on 200 sentences of CUAD
   contracts reworded in plain words, 0.8 called 40% of them changed, 0.5 called 15%; what a
   higher bar would add, changes of obligation like "may" into "shall", is caught in step 1.)
3. For a change of meaning the language model says, in one sentence, what changed; the sentence is
   checked like any statement (its numbers and names must be in the two versions), and dropped if
   it fails, leaving the facts and the word diff to speak.
"""

from __future__ import annotations

import re

from rapidfuzz import fuzz

from . import anchor, numbers
from .llm import Llm
from .verify import unseen_names

SAME_BOTH_WAYS = 0.5

SENTENCE = re.compile(r"(?<=[.;:!?])\s+(?=[\"“(]?[A-ZĂÂÎȘȚ0-9(])")

SYSTEM = """You compare two versions of the same contract clause. In one sentence (at most 35 words, in {language}), say what the change means for the parties: what someone must now do, pay or accept differently. Use only what the two versions say. Quote numbers exactly."""

SCHEMA = {"type": "object", "properties": {"explanation": {"type": "string"}}, "required": ["explanation"]}

LANGUAGES = {"en": "English", "ro": "Romanian"}


def sentences(text: str) -> list[str]:
    return [s.strip() for s in SENTENCE.split(text) if s.strip()]


def changed_sentences(before: str, after: str) -> tuple[list[tuple[str, str]], list[str], list[str]]:
    """Pairs of changed sentences, and the sentences only in `before` or only in `after`."""
    a, b = sentences(before), sentences(after)
    na = {anchor.normal(s).text: s for s in a}
    nb = {anchor.normal(s).text: s for s in b}
    only_a = [s for k, s in na.items() if k not in nb]
    only_b = [s for k, s in nb.items() if k not in na]
    pairs, used = [], set()
    for s in only_a:
        best, score = None, 0.0
        for i, t in enumerate(only_b):
            if i in used:
                continue
            r = fuzz.token_set_ratio(s, t)
            if r > score:
                best, score = i, r
        if best is not None and score >= 50:
            used.add(best)
            pairs.append((s, only_b[best]))
    gone = [s for s in only_a if all(s != p[0] for p in pairs)]
    added = [t for i, t in enumerate(only_b) if i not in used]
    return pairs, gone, added


def judge(pairs: list[dict], nli, llm: Llm | None, language: str = "en") -> list[dict]:
    out = []
    for p in pairs:
        before, after = p["before"], p["after"]
        facts = [f for f in p.get("facts", []) if f.get("before") or f.get("after")]
        reasons = []
        if facts:
            reasons.append("figures changed")
        if p.get("negation_changed"):
            reasons.append("a negation came or went")
        if p.get("modality_changed"):
            reasons.append("an obligation became a permission, or the reverse")
        # The sentences found nowhere in the other version (the backend's), else the whole clauses.
        if p.get("only_before") is not None or p.get("only_after") is not None:
            sent_pairs, gone, added = changed_sentences(" ".join(p.get("only_before") or []), " ".join(p.get("only_after") or []))
        else:
            sent_pairs, gone, added = changed_sentences(before, after)
        scores = []
        if not reasons:
            if gone:
                reasons.append(f"{len(gone)} sentence(s) dropped")
            if added:
                reasons.append(f"{len(added)} sentence(s) added")
            if sent_pairs:
                both = nli([(s, t) for s, t in sent_pairs] + [(t, s) for s, t in sent_pairs])
                n = len(sent_pairs)
                for i in range(n):
                    ab, ba = both[i].entailment, both[n + i].entailment
                    scores.append(round(min(ab, ba), 3))
                if any(s < SAME_BOTH_WAYS for s in scores):
                    reasons.append("a reworded sentence no longer means the same")
        meaning = "changed" if reasons else "same"
        result = {"id": p["id"], "meaning": meaning, "reasons": reasons, "sentence_scores": scores, "explanation": ""}
        if meaning == "changed" and llm is not None:
            text = llm.json(SYSTEM.format(language=LANGUAGES.get(language, "English")),
                            f"Before:\n{before[:3000]}\n\nAfter:\n{after[:3000]}", SCHEMA, max_tokens=200).get("explanation", "")
            both_texts = before + "\n" + after
            if text and not numbers.unsupported(text, both_texts) and not unseen_names(text, both_texts):
                result["explanation"] = text.strip()
            elif text:
                result["explanation_dropped"] = text.strip()
        out.append(result)
    return out

"""Finding a quote in the contract. A model copies text imperfectly: curly quotes come back
straight, line breaks as spaces, a dash as a hyphen. So both sides are compared in a normal form
(typography unified, whitespace collapsed), with a map back to the original characters, and the
answer is always a span of the original text.

- exact: the quote, in normal form, occurs in the text;
- repaired: it does not, but a passage of the text matches it closely (at least 90 of 100 by
  rapidfuzz's partial ratio, nearly the whole quote covered); the span of that passage replaces the
  model's quote;
- missing: neither. A statement whose quote is missing is never shown as fact.
"""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass

from rapidfuzz import fuzz

TYPOGRAPHY = str.maketrans({
    "“": '"', "”": '"', "„": '"', "«": '"', "»": '"', "‘": "'", "’": "'", "‚": "'",
    "–": "-", "—": "-", "‐": "-", "‑": "-", " ": " ", "­": "",
    "ş": "ș", "Ş": "Ș", "ţ": "ț", "Ţ": "Ț",
})


@dataclass
class Normal:
    """A text in normal form, and for each of its characters the position in the original."""
    text: str
    origin: list[int]

    def span(self, start: int, end: int) -> tuple[int, int]:
        return self.origin[start], self.origin[end - 1] + 1


def normal(text: str, fold: bool = True) -> Normal:
    out, origin = [], []
    space = False
    for i, ch in enumerate(text):
        ch = unicodedata.normalize("NFC", ch).translate(TYPOGRAPHY)
        if not ch:
            continue
        if ch.isspace():
            if not space and out:
                out.append(" ")
                origin.append(i)
            space = True
            continue
        space = False
        for c in (ch.lower() if fold else ch):
            out.append(c)
            origin.append(i)
    while out and out[-1] == " ":
        out.pop()
        origin.pop()
    return Normal("".join(out), origin)


@dataclass
class Anchor:
    status: str  # exact, repaired, missing
    start: int = -1
    end: int = -1
    score: float = 0.0


def strip_quote(q: str) -> str:
    """The model's quote without the decoration it sometimes adds: quote marks, ellipses at the ends."""
    q = q.strip().strip('"“”„\'')
    q = re.sub(r"^(\.\.\.|…)\s*|\s*(\.\.\.|…)$", "", q)
    return q.strip()


def find(quote: str, text: str, within: tuple[int, int] | None = None, text_normal: Normal | None = None) -> Anchor:
    """Where `quote` is in `text`, searching `within` (a span of the text) first, then everywhere."""
    q = normal(strip_quote(quote)).text
    if len(q) < 8:
        return Anchor("missing")
    tn = text_normal or normal(text)
    windows = []
    if within:
        lo = _index_at(tn, within[0])
        hi = _index_at(tn, within[1])
        windows.append((lo, hi))
    windows.append((0, len(tn.text)))
    for lo, hi in windows:
        at = tn.text.find(q, lo, hi)
        if at >= 0:
            s, e = tn.span(at, at + len(q))
            return Anchor("exact", s, e, 100.0)
    # Close enough? The best-matching passage of the right length.
    for lo, hi in windows:
        hay = tn.text[lo:hi]
        if not hay:
            continue
        m = fuzz.partial_ratio_alignment(q, hay, score_cutoff=90)
        if m is None:
            continue
        covered = (m.src_end - m.src_start) / len(q)
        if covered < 0.9:
            continue
        s, e = tn.span(lo + m.dest_start, lo + m.dest_end)
        return Anchor("repaired", s, e, m.score)
    return Anchor("missing")


def _index_at(tn: Normal, position: int) -> int:
    """The normal-form index of the first character at or after an original position."""
    lo, hi = 0, len(tn.origin)
    while lo < hi:
        mid = (lo + hi) // 2
        if tn.origin[mid] < position:
            lo = mid + 1
        else:
            hi = mid
    return lo

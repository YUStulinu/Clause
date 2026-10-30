"""The numbers in a piece of text, as values: "1.500 lei", "1,500" and "1500" are all 1500, "2,5%"
is 2.5, and "thirty (30) days" or "treizeci de zile" give 30. A statement may only use numbers its
quote contains: a model that writes "60 days" for a clause that says 30 is caught here, whatever
the rest of its sentence."""

from __future__ import annotations

import re

WORDS = {
    # English
    "one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6, "seven": 7, "eight": 8, "nine": 9, "ten": 10,
    "eleven": 11, "twelve": 12, "thirteen": 13, "fourteen": 14, "fifteen": 15, "sixteen": 16, "seventeen": 17,
    "eighteen": 18, "nineteen": 19, "twenty": 20, "thirty": 30, "forty": 40, "forty-five": 45, "fifty": 50, "sixty": 60,
    "seventy": 70, "eighty": 80, "ninety": 90, "hundred": 100, "half": 0.5,
    # Romanian
    "unu": 1, "una": 1, "doi": 2, "două": 2, "doua": 2, "trei": 3, "patru": 4, "cinci": 5, "șase": 6, "sase": 6,
    "șapte": 7, "sapte": 7, "opt": 8, "nouă": 9, "noua": 9, "zece": 10, "unsprezece": 11, "doisprezece": 12,
    "douăsprezece": 12, "cincisprezece": 15, "douăzeci": 20, "douazeci": 20, "treizeci": 30, "patruzeci": 40,
    "cincizeci": 50, "șaizeci": 60, "saizeci": 60, "nouăzeci": 90, "nouazeci": 90, "sută": 100, "suta": 100,
    "jumătate": 0.5, "jumatate": 0.5,
}

NUMBER = re.compile(r"(?<![\w.,])(\d{1,3}(?:[.,\s]\d{3})+(?:[.,]\d+)?|\d+(?:[.,]\d+)?)(?![\w])")
WORD = re.compile(r"[a-zăâîșț-]+", re.IGNORECASE)


def value(s: str) -> float:
    t = s.replace(" ", "")
    if re.fullmatch(r"\d{1,3}([.,]\d{3})+", t):
        return float(re.sub(r"[.,]", "", t))
    if re.fullmatch(r"\d{1,3}([.,]\d{3})+[.,]\d+", t):
        dec = max(t.rfind("."), t.rfind(","))
        return float(re.sub(r"[.,]", "", t[:dec]) + "." + t[dec + 1:])
    return float(t.replace(",", "."))


def values(text: str) -> set[float]:
    out = {value(m.group(1)) for m in NUMBER.finditer(text)}
    for w in WORD.findall(text.lower()):
        if w in WORDS:
            out.add(float(WORDS[w]))
    return out


def unsupported(claim: str, quote: str) -> list[float]:
    """The numbers in `claim` that `quote` does not contain (years and amounts alike)."""
    have = values(quote)
    missing = []
    for v in sorted({value(m.group(1)) for m in NUMBER.finditer(claim)}):
        if v in have:
            continue
        # "1 year" for "12 months" and the like are left to the entailment check: only flag what
        # no simple reading of the quote gives.
        if any(abs(v * k - h) < 1e-9 for h in have for k in (12, 1 / 12, 100, 0.01, 7, 1 / 7, 30, 1 / 30, 365, 1 / 365)):
            continue
        missing.append(v)
    return missing

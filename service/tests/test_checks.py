"""The anti-hallucination checks without a GPU: the anchor, the numbers, the names, and the
verdicts with a stand-in entailment model."""

import pytest

from clause_service import anchor, numbers
from clause_service.extract import ClauseRef, Finding, chunks
from clause_service.nli import Judgement
from clause_service.verify import personal, unseen_names, verify

TEXT = """CONTRACT DE ÎNCHIRIERE
Art. 4 Penalități
4.1. Pentru întârzierea la plata chiriei, Chiriașul datorează penalități de 0,1% din chirie pentru fiecare zi de întârziere.
Art. 5 Încetarea contractului
5.1. Oricare parte poate denunța contractul cu un preaviz scris de 30 de zile.
"""
CLAUSES = [ClauseRef("c0", "", "", 0, 23), ClauseRef("c1", "4", "Penalități", 23, 166), ClauseRef("c2", "5", "Încetarea", 166, len(TEXT))]


class FakeNli:
    """Entails whatever shares most of its words with the premise; contradicts a "nu" the premise lacks."""

    def __call__(self, pairs):
        out = []
        for p, h in pairs:
            pw, hw = set(p.lower().split()), set(h.lower().split())
            if "nu" in hw and "nu" not in pw:
                out.append(Judgement(0.01, 0.01, 0.98))
            else:
                share = len(pw & hw) / max(1, len(hw))
                out.append(Judgement(share, 1 - share, 0.0))
        return out


def test_anchor_exact_with_typography_and_whitespace():
    a = anchor.find("Chiriașul datorează penalități de 0,1%   din chirie", TEXT)
    assert a.status == "exact"
    assert TEXT[a.start:a.end] == "Chiriașul datorează penalități de 0,1% din chirie"
    q = anchor.find("“Oricare parte poate denunța contractul cu un preaviz scris de 30 de zile.”", TEXT)
    assert q.status == "exact" and TEXT[q.end - 1] == "."


def test_anchor_repairs_a_sloppy_copy_to_the_real_text():
    a = anchor.find("Chiriasul datoreaza penalitati de 0,1% din chirie pentru fiecare zi de intarziere", TEXT)
    assert a.status == "repaired"
    assert TEXT[a.start:a.end].startswith("Chiriașul datorează")


def test_anchor_refuses_what_is_not_there():
    assert anchor.find("Proprietarul poate mări chiria oricând, fără preaviz.", TEXT).status == "missing"
    assert anchor.find("prea", TEXT).status == "missing"  # too short to mean anything


def test_anchor_searches_the_clause_first():
    text = "Fee: 10 EUR.\nThe fee is due monthly.\nLater: The fee is due monthly."
    a = anchor.find("The fee is due monthly.", text, within=(text.index("Later"), len(text)))
    assert a.start == text.index("Later") + len("Later: ")


def test_numbers():
    assert numbers.values("1.500 lei, 1,500 RON, 2,5%, thirty (30) days") >= {1500.0, 2.5, 30.0}
    assert numbers.values("treizeci de zile") == {30.0}
    assert numbers.unsupported("Preaviz de 60 de zile.", "un preaviz scris de 30 de zile") == [60.0]
    assert numbers.unsupported("Notice of 30 days.", "thirty (30) days written notice") == []
    assert numbers.unsupported("The term is 1 year.", "a term of 12 months") == []


def test_names_catch_a_swapped_party():
    quote = "The Tenant shall pay a late fee of 0.1% of the rent for each day of delay."
    assert unseen_names("The Landlord pays 0.1% per day of delay.", quote) == ["Landlord"]
    assert unseen_names("The Tenant pays 0.1% per day of delay.", quote) == []
    assert unseen_names("Chiriașului i se aplică penalități.", "Chiriașul datorează penalități") == []
    # A quote in the second person: names the contract gives "you" are allowed.
    assert unseen_names("Customer pays fees.", "You agree to pay the fees.", "Acme (the Company) and you (the Customer)") == []
    assert unseen_names("Customer pays fees.", "The Supplier pays the fees.", "you (the Customer)") == ["Customer"]


def test_personal_puts_the_party_in_place_of_you():
    assert personal("You must pay your rent.", "the Tenant") == "the Tenant must pay the Tenant's rent."
    assert personal("You must pay.", "both") == "You must pay."


def test_verdicts():
    # Different kinds: findings of one kind on the same passage are merged.
    f = [
        Finding("c1", "penalty", "Chiriașul", "Chiriașul datorează penalități de 0,1% din chirie pe zi de întârziere.",
                "Chiriașul datorează penalități de 0,1% din chirie pentru fiecare zi de întârziere"),
        Finding("c2", "termination", "both", "Oricare parte poate denunța contractul cu un preaviz de 60 de zile.",
                "Oricare parte poate denunța contractul cu un preaviz scris de 30 de zile."),
        Finding("c2", "unusual", "both", "Contractul nu poate fi denunțat.",
                "Oricare parte poate denunța contractul cu un preaviz scris de 30 de zile."),
        Finding("c1", "liability", "Proprietarul", "Proprietarul datorează penalități de 0,1% din chirie.",
                "Chiriașul datorează penalități de 0,1% din chirie pentru fiecare zi de întârziere"),
        Finding("c1", "unusual", "both", "Chiria poate fi mărită oricând.", "Proprietarul poate mări chiria oricând."),
    ]
    out = {x.summary: x for x in verify(f, TEXT, CLAUSES, FakeNli())}
    ok = out["Chiriașul datorează penalități de 0,1% din chirie pe zi de întârziere."]
    assert ok.status == "verified" and TEXT[ok.start:ok.end].startswith("Chiriașul")
    assert out["Oricare parte poate denunța contractul cu un preaviz de 60 de zile."].checks["numbers"] == "missing 60"
    assert out["Contractul nu poate fi denunțat."].status == "unsupported"
    assert out["Proprietarul datorează penalități de 0,1% din chirie."].checks["names"].endswith("Proprietarul")
    assert out["Chiria poate fi mărită oricând."].status == "unanchored"
    assert sum(x.status == "verified" for x in out.values()) == 1


def test_chunks_keep_clauses_whole_and_split_long_ones():
    text = ("A" * 100 + "\n") * 50  # 5,050 characters
    cl = [ClauseRef("c0", "1", "", 0, 2000), ClauseRef("c1", "2", "", 2000, len(text))]
    parts = chunks(cl, text, size=3500)
    assert [c.id for p in parts for c in p] == ["c0", "c1"] or all(c.end - c.start <= 3500 for p in parts for c in p)
    long = chunks([ClauseRef("c0", "1", "", 0, len(text))], text, size=1000)
    pieces = [c for p in long for c in p]
    assert all(c.end - c.start <= 1000 for c in pieces)
    assert pieces[0].start == 0 and pieces[-1].end == len(text)
    assert all(a.end == b.start for a, b in zip(pieces, pieces[1:]))


@pytest.mark.parametrize("raw,clean", [('"quoted"', "quoted"), ("... the middle ...", "the middle"), ("“curly”", "curly")])
def test_strip_quote(raw, clean):
    assert anchor.strip_quote(raw) == clean

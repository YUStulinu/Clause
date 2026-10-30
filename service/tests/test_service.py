"""The service end to end with a stand-in model: extraction, checks, comparison, the HTTP API."""

import json

from fastapi.testclient import TestClient

from clause_service.compare import changed_sentences, judge
from clause_service.nli import Judgement
from clause_service.server import create_app

TEXT = """1. Rent
The Tenant pays 500 EUR every month. The rent is due on the first day of the month.
2. Notice
Either party may end this lease with 30 days written notice.
"""
CLAUSES = [{"id": "c0", "number": "1", "title": "Rent", "start": 0, "end": 92},
           {"id": "c1", "number": "2", "title": "Notice", "start": 92, "end": len(TEXT)}]


class FakeLlm:
    """Answers the extraction prompt with one true and one invented finding, the comparison with a sentence."""

    def __init__(self):
        self.prompts = []

    def ready(self):
        return True

    def json(self, system, user, schema, max_tokens=1500):
        self.prompts.append(user)
        if "findings" in schema.get("properties", {}):
            return {"findings": [
                {"clause": "c0", "kind": "payment", "party": "the Tenant", "summary": "The Tenant pays 500 EUR every month.",
                 "quote": "The Tenant pays 500 EUR every month.", "why": ""},
                {"clause": "c1", "kind": "termination", "party": "both", "summary": "Either party may end this lease with 90 days notice.",
                 "quote": "Either party may end this lease with 90 days written notice.", "why": ""},
            ]}
        return {"explanation": "The notice period went from 30 to 60 days."}


class FakeNli:
    def __call__(self, pairs):
        return [Judgement(0.95 if set(h.lower().split()) <= set(p.lower().split()) | {"every", "month."} else 0.2, 0.05, 0.0)
                for p, h in pairs]


def test_analyze_keeps_only_what_the_text_supports():
    llm = FakeLlm()
    client = TestClient(create_app(llm, FakeNli()))
    r = client.post("/analyze", json={"text": TEXT, "language": "en", "answer_language": "en", "clauses": CLAUSES})
    assert r.status_code == 200
    body = r.json()
    statuses = {f["summary"]: f["status"] for f in body["findings"]}
    assert statuses["The Tenant pays 500 EUR every month."] == "verified"
    # The model's quote says 90 days, the contract 30: the quote is repaired to the real sentence,
    # and then the 90 in the statement is not in it.
    notice = next(f for f in body["findings"] if f["kind"] == "termination")
    assert notice["status"] == "unsupported"
    assert notice["checks"]["anchor"] == "repaired" and notice["checks"]["numbers"] == "missing 90"
    assert body["counts"] == {"verified": 1, "unsupported": 1, "unanchored": 0}
    assert "[c0]" in llm.prompts[0] and "[c1]" in llm.prompts[0]


def test_changed_sentences():
    pairs, gone, added = changed_sentences(
        "The rent is 500 EUR. It is due monthly. Pets are not allowed.",
        "The rent is 500 EUR. It must be paid every month. Smoking is forbidden.")
    assert pairs == [("It is due monthly.", "It must be paid every month.")]
    assert gone == ["Pets are not allowed."] and added == ["Smoking is forbidden."]


def test_judge():
    class Both:
        def __init__(self, score):
            self.score = score

        def __call__(self, pairs):
            return [Judgement(self.score, 1 - self.score, 0) for _ in pairs]

    reworded = {"id": "p1", "before": "The rent is due monthly.", "after": "The rent must be paid every month.", "facts": [], "negation_changed": False}
    figures = {"id": "p2", "before": "Notice of 30 days.", "after": "Notice of 60 days.", "facts": [{"before": "30", "after": "60"}], "negation_changed": False}
    same = judge([reworded], Both(0.95), None)[0]
    assert same["meaning"] == "same" and same["sentence_scores"] == [0.95]
    assert judge([reworded], Both(0.3), None)[0]["meaning"] == "changed"
    modal = {**reworded, "modality_changed": True}
    assert judge([modal], Both(0.99), None)[0]["reasons"] == ["an obligation became a permission, or the reverse"]
    relocated = {**reworded, "only_before": [], "only_after": []}
    assert judge([relocated], Both(0.0), None)[0]["meaning"] == "same"
    out = judge([figures], Both(0.99), FakeLlm())[0]
    assert out["meaning"] == "changed" and out["reasons"] == ["figures changed"]
    assert out["explanation"] == "The notice period went from 30 to 60 days."


def test_an_explanation_with_invented_numbers_is_dropped():
    class Liar(FakeLlm):
        def json(self, system, user, schema, max_tokens=1500):
            return {"explanation": "The notice period went from 30 to 90 days."}

    out = judge([{"id": "p", "before": "Notice of 30 days.", "after": "Notice of 60 days.",
                  "facts": [{"before": "30", "after": "60"}]}], FakeNli(), Liar())[0]
    assert out["explanation"] == "" and "90" in out["explanation_dropped"]


def test_compare_endpoint_and_health():
    client = TestClient(create_app(FakeLlm(), FakeNli()))
    assert client.get("/health").json() == {"ok": True, "llm": True}
    r = client.post("/compare", json={"language": "en", "pairs": [
        {"id": "p0", "before": "Notice of 30 days.", "after": "Notice of 60 days.", "facts": [{"before": "30", "after": "60"}]}]})
    assert r.json()["judgements"][0]["meaning"] == "changed"
    json.dumps(r.json())

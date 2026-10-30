"""The analysis service the Java backend calls (127.0.0.1:8766):

    POST /analyze   a contract's text and clauses -> findings, each checked against its quote
    POST /compare   changed clause pairs -> whether the meaning changed, and how
    GET  /health

    python -m clause_service.server     (starts llama-server on :8093 if none is running)
"""

from __future__ import annotations

import time
from dataclasses import asdict

from fastapi import FastAPI
from pydantic import BaseModel

from . import compare as comparing
from .extract import ClauseRef, extract
from .llm import Llm, default_model, serve
from .verify import verify

KIND_ORDER = ["payment", "penalty", "termination", "obligation", "liability", "unusual"]


class ClauseIn(BaseModel):
    id: str
    number: str = ""
    title: str = ""
    start: int
    end: int


class AnalyzeIn(BaseModel):
    text: str
    language: str = "en"
    answer_language: str = "en"
    clauses: list[ClauseIn]


class PairIn(BaseModel):
    id: str
    before: str
    after: str
    facts: list[dict] = []
    negation_changed: bool = False
    modality_changed: bool = False
    only_before: list[str] | None = None
    only_after: list[str] | None = None


class CompareIn(BaseModel):
    language: str = "en"
    pairs: list[PairIn]


def create_app(llm: Llm | None, nli) -> FastAPI:
    app = FastAPI(title="Clause analysis")

    @app.get("/health")
    def health():
        return {"ok": True, "llm": bool(llm and llm.ready())}

    @app.post("/analyze")
    def analyze(req: AnalyzeIn):
        t0 = time.time()
        clauses = [ClauseRef(**c.model_dump()) for c in req.clauses]
        found = extract(llm, req.text, clauses, req.answer_language)
        checked = verify(found, req.text, clauses, nli)
        findings = [asdict(f) for f in checked]
        counts = {s: sum(f["status"] == s for f in findings) for s in ("verified", "unsupported", "unanchored")}
        return {
            "findings": findings,
            "kinds": KIND_ORDER,
            "counts": counts,
            "seconds": round(time.time() - t0, 1),
        }

    @app.post("/compare")
    def compare(req: CompareIn):
        return {"judgements": comparing.judge([p.model_dump() for p in req.pairs], nli, llm, req.language)}

    return app


def main():
    import uvicorn

    from .nli import Nli

    llm = Llm()
    proc = None if llm.ready() else serve(default_model())
    try:
        uvicorn.run(create_app(llm, Nli()), host="127.0.0.1", port=8766, log_level="warning")
    finally:
        if proc:
            proc.kill()


if __name__ == "__main__":
    main()

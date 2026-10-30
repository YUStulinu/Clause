"""Does a quote entail a statement? A multilingual natural language inference model
(mDeBERTa-v3-base trained on XNLI and multilingual NLI, MIT licence; Romanian and English alike),
pinned to a revision. It reads (premise, hypothesis) and gives the probabilities of entailment,
neutral and contradiction."""

from __future__ import annotations

from dataclasses import dataclass

MODEL = "MoritzLaurer/mDeBERTa-v3-base-xnli-multilingual-nli-2mil7"
REVISION = "b5113eb38ab63efdd7f280f8c144ea8b13f978ce"


@dataclass
class Judgement:
    entailment: float
    neutral: float
    contradiction: float


class Nli:
    def __init__(self, device: str | None = None):
        import torch
        from transformers import AutoModelForSequenceClassification, AutoTokenizer

        self.torch = torch
        self.device = device or ("cuda" if torch.cuda.is_available() else "cpu")
        self.tok = AutoTokenizer.from_pretrained(MODEL, revision=REVISION)
        self.model = AutoModelForSequenceClassification.from_pretrained(MODEL, revision=REVISION).to(self.device).eval()
        labels = {v.lower(): k for k, v in self.model.config.id2label.items()}
        self.idx = (labels["entailment"], labels["neutral"], labels["contradiction"])

    def __call__(self, pairs: list[tuple[str, str]], batch: int = 16) -> list[Judgement]:
        out = []
        for i in range(0, len(pairs), batch):
            chunk = pairs[i : i + batch]
            enc = self.tok([p for p, _ in chunk], [h for _, h in chunk], truncation="only_first", max_length=512,
                           padding=True, return_tensors="pt").to(self.device)
            with self.torch.no_grad():
                p = self.model(**enc).logits.softmax(-1).cpu().numpy()
            e, n, c = self.idx
            out += [Judgement(float(r[e]), float(r[n]), float(r[c])) for r in p]
        return out

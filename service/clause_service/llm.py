"""The language model: llama.cpp's llama-server (OpenAI-style chat endpoint), on this machine.
`serve()` starts it from third_party/llama with a GGUF model; `Llm` talks to it. Answers can be held
to a JSON schema, which the server turns into a grammar, so they always parse."""

from __future__ import annotations

import json
import os
import subprocess
import sys
import time
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parents[2]


class Llm:
    def __init__(self, url: str = "http://127.0.0.1:8093", timeout: float = 600.0):
        self.url = url.rstrip("/")
        self.client = httpx.Client(timeout=timeout)
        self.calls = 0

    def chat(self, system: str, user: str, max_tokens: int = 1500, schema: dict | None = None) -> str:
        self.calls += 1
        body = {
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
            "max_tokens": max_tokens,
            "temperature": 0.0,
            "cache_prompt": True,
        }
        if schema:
            body["response_format"] = {"type": "json_object", "schema": schema}
        r = self.client.post(f"{self.url}/v1/chat/completions", json=body)
        r.raise_for_status()
        return r.json()["choices"][0]["message"]["content"].strip()

    def json(self, system: str, user: str, schema: dict, max_tokens: int = 1500) -> dict:
        text = self.chat(system, user, max_tokens, schema)
        try:
            return json.loads(text)
        except json.JSONDecodeError:
            return {}  # cut off at max_tokens: treated as no answer

    def ready(self) -> bool:
        try:
            return self.client.get(f"{self.url}/health", timeout=2).status_code == 200
        except httpx.HTTPError:
            return False


def serve(model: str | Path, port: int = 8093, context: int = 8192, gpu_layers: int = 99) -> subprocess.Popen:
    """Starts llama-server and waits until it answers."""
    exe = ROOT / "third_party" / "llama" / ("llama-server.exe" if sys.platform == "win32" else "llama-server")
    if not exe.exists():
        raise FileNotFoundError(f"{exe} not found: run scripts/fetch-deps.sh --llm")
    p = subprocess.Popen(
        [str(exe), "-m", str(model), "--port", str(port), "-c", str(context), "-ngl", str(gpu_layers), "--parallel", "1", "--log-disable"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
    )
    llm = Llm(f"http://127.0.0.1:{port}")
    for _ in range(240):
        if p.poll() is not None:
            raise RuntimeError(f"llama-server exited ({p.returncode})")
        if llm.ready():
            return p
        time.sleep(0.5)
    p.kill()
    raise TimeoutError("llama-server did not start")


def default_model() -> Path:
    return Path(os.environ.get("CLAUSE_LLM_MODEL", ROOT / "third_party" / "models" / "Qwen2.5-7B-Instruct-Q4_K_M.gguf"))

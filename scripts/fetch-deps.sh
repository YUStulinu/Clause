#!/usr/bin/env bash
# Downloads what Clause's analysis service uses but does not keep in git, checks each file's
# SHA-256 and unpacks it into third_party/:
#   - llama.cpp's server, b11429 (the CUDA 13 build on Windows, the CPU build on Linux);
#   - Qwen2.5-7B-Instruct, Q4_K_M (4.7 GB), the language model.
# The entailment model (mDeBERTa-v3-base, multilingual NLI) is downloaded by the service on first
# use, pinned to a revision (service/clause_service/nli.py).
#   scripts/fetch-deps.sh [--cuad]
#     --cuad   also the CUAD dataset (CC BY 4.0, 100 MB) into data/cuad, for the evaluations
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
tp="$root/third_party"
dl="$tp/downloads"
mkdir -p "$dl" "$tp/models"
cuad=0
for a in "$@"; do
  case "$a" in
    --cuad) cuad=1 ;;
    *) echo "unknown option $a" >&2; exit 2 ;;
  esac
done

fetch() { # url sha256 file
  local url="$1" sha="$2" file="$dl/$3"
  if [ ! -f "$file" ] || ! echo "$sha  $file" | sha256sum -c - >/dev/null 2>&1; then
    echo "downloading $url"
    curl -fsSL --retry 3 -o "$file.part" "$url" || { echo "download failed: $url" >&2; exit 1; }
    echo "$sha  $file.part" | sha256sum -c - >/dev/null || { echo "checksum mismatch: $url" >&2; rm -f "$file.part"; exit 1; }
    mv "$file.part" "$file"
  fi
}

llama="https://github.com/ggml-org/llama.cpp/releases/download/b11429"
case "$(uname -s)" in
  MINGW* | MSYS* | CYGWIN*)
    fetch "$llama/llama-b11429-bin-win-cuda-13.4-x64.zip" 76ddc6eff2389570789ed608881efc6977751a722015d8e8c94f302224ff1a3a llama-b11429-bin-win-cuda-13.4-x64.zip
    fetch "$llama/cudart-llama-bin-win-cuda-13.4-x64.zip" 738f8c251ac22b70c3ae6f83a10cf222725df0395246a2cf58f32bdb85fbe668 cudart-llama-bin-win-cuda-13.4-x64.zip
    if [ ! -f "$tp/llama/llama-server.exe" ]; then
      mkdir -p "$tp/llama"
      for z in llama-b11429-bin-win-cuda-13.4-x64.zip cudart-llama-bin-win-cuda-13.4-x64.zip; do unzip -qo "$dl/$z" -d "$tp/llama"; done
    fi ;;
  Linux)
    fetch "$llama/llama-b11429-bin-ubuntu-x64.tar.gz" f6d25dde8f51133143d1453da4fd5f73b145127177612a283bf7995957af3392 llama-b11429-bin-ubuntu-x64.tar.gz
    if [ ! -x "$tp/llama/llama-server" ]; then
      mkdir -p "$tp/llama"
      tar -xzf "$dl/llama-b11429-bin-ubuntu-x64.tar.gz" -C "$tp/llama" --strip-components=1
    fi ;;
esac

model="$tp/models/Qwen2.5-7B-Instruct-Q4_K_M.gguf"
if [ ! -f "$model" ]; then
  fetch https://huggingface.co/bartowski/Qwen2.5-7B-Instruct-GGUF/resolve/main/Qwen2.5-7B-Instruct-Q4_K_M.gguf \
    65b8fcd92af6b4fefa935c625d1ac27ea29dcb6ee14589c55a8f115ceaaa1423 Qwen2.5-7B-Instruct-Q4_K_M.gguf
  mv -f "$dl/Qwen2.5-7B-Instruct-Q4_K_M.gguf" "$model"
fi

if [ $cuad = 1 ]; then
  mkdir -p "$root/data/cuad"
  sha=88b694d99007d39777fa44cd72daf8297773d285dc3eab0091ba32078888d18e
  f="$root/data/cuad/CUAD_v1.zip"
  if [ ! -f "$f" ] || ! echo "$sha  $f" | sha256sum -c - >/dev/null 2>&1; then
    curl -fsSL --retry 3 -o "$f.part" "https://zenodo.org/api/records/4595826/files/CUAD_v1.zip/content"
    echo "$sha  $f.part" | sha256sum -c - >/dev/null || { echo "checksum mismatch: CUAD" >&2; exit 1; }
    mv "$f.part" "$f"
  fi
  [ -d "$root/data/cuad/CUAD_v1" ] || (cd "$root/data/cuad" && unzip -qo CUAD_v1.zip)
fi
echo "dependencies are in $tp"

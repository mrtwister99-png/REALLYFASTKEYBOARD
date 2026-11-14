#!/usr/bin/env bash
# CI: testy Rust jadra (+ volitelne benchmarky a JVM test JNI mostu).
# Pouziti: scripts/ci.sh [--bench] [--jni]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/crates/keyboard-core"

echo "== cargo test =="
cargo test --all-targets

for arg in "$@"; do
  case "$arg" in
    --bench)
      echo "== cargo bench =="
      cargo bench
      ;;
    --jni)
      echo "== JVM test JNI mostu =="
      (cd "$ROOT" && ./gradlew :app:testDebugUnitTest --console=plain)
      ;;
  esac
done
echo "OK"

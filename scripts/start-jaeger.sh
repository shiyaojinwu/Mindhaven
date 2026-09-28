#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION=2.21.0
if [[ "$(uname -s)" != Darwin || "$(uname -m)" != arm64 ]]; then
  echo '此脚本当前适用于 Apple 芯片 Mac。' >&2; exit 1
fi
DIR="$ROOT/.runtime/jaeger"
BINARY="$DIR/jaeger-$VERSION-darwin-arm64/jaeger"
mkdir -p "$DIR"
if [[ ! -x "$BINARY" ]]; then
  BASE="https://github.com/jaegertracing/jaeger/releases/download/v$VERSION"
  curl --fail --location --retry 2 "$BASE/jaeger-$VERSION-darwin-arm64.tar.gz" -o "$DIR/package.tar.gz"
  tar -xzf "$DIR/package.tar.gz" -C "$DIR"
fi
EXPECTED=272e7c8df7ee1a7b8d81c9fe376d28962a5fc3e33927571ba3b72362a4187ac5
ACTUAL="$(shasum -a 256 "$BINARY" | awk '{print $1}')"
[[ "$ACTUAL" == "$EXPECTED" ]] || { echo 'Jaeger 二进制校验失败' >&2; exit 1; }
echo 'Jaeger: http://127.0.0.1:16686 （Ctrl+C 停止；内存中的 Trace 在停止后清空）'
exec "$BINARY" --config "$ROOT/config/jaeger-local.yml"

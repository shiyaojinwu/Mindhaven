#!/usr/bin/env bash
# Install pinned official binaries inside this project. No sudo or shell-profile changes.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ "$(uname -s)" != Darwin || "$(uname -m)" != arm64 ]]; then
  printf '此安装脚本适用于 Apple 芯片 Mac；其他系统请安装 Docker 与 Ollama 后运行 ./start.sh --vector。\n' >&2
  exit 1
fi
for tool in curl tar unzip shasum; do
  command -v "$tool" >/dev/null || { printf '缺少 %s\n' "$tool" >&2; exit 1; }
done
DOWNLOADS="$ROOT/.runtime/downloads"
TOOLS="$ROOT/.runtime/tools"
mkdir -p "$DOWNLOADS" "$TOOLS"
download_checked() {
  local archive="$1" url="$2" checksum="$3" actual
  if [[ ! -f "$DOWNLOADS/$archive" ]]; then
    printf '下载 %s…\n' "$archive"
    curl --fail --location --retry 3 --connect-timeout 20 --max-time 1800 \
      "$url" -o "$DOWNLOADS/$archive.part"
    mv "$DOWNLOADS/$archive.part" "$DOWNLOADS/$archive"
  fi
  actual="$(shasum -a 256 "$DOWNLOADS/$archive")"
  if [[ "${actual%% *}" != "$checksum" ]]; then
    printf '校验失败，请移走 %s 后重试。\n' "$DOWNLOADS/$archive" >&2
    exit 1
  fi
}
install_release() {
  local name="$1" version="$2" archive="$3" url="$4" checksum="$5" stage
  if [[ -x "$TOOLS/$name/$name" && -f "$TOOLS/$name/.version" ]] && [[ "$(cat "$TOOLS/$name/.version")" == "$version" ]]; then
    printf '复用 %s %s\n' "$name" "$version"
    return
  fi
  download_checked "$archive" "$url" "$checksum"
  stage="$(mktemp -d "$TOOLS/$name-install.XXXXXX")"
  tar -xzf "$DOWNLOADS/$archive" -C "$stage"
  [[ -x "$stage/$name" ]] || { printf '%s 安装包不完整\n' "$name" >&2; exit 1; }
  mkdir -p "$TOOLS/$name"
  cp -R "$stage/." "$TOOLS/$name/"
  rm -rf "$stage"
  printf '%s\n' "$version" > "$TOOLS/$name/.version"
  printf '已安装 %s %s\n' "$name" "$version"
}
# Qdrant matches compose.yml. Hashes pin the exact official release archives.
install_release qdrant v1.13.6 qdrant-aarch64-apple-darwin.tar.gz \
  https://github.com/qdrant/qdrant/releases/download/v1.13.6/qdrant-aarch64-apple-darwin.tar.gz \
  e706cea3a2fffe03549f7b9fd202a769ca31dad73f3c57cdf1ae42d538b43f86
install_release ollama v0.34.0 ollama-darwin.tgz \
  https://github.com/ollama/ollama/releases/download/v0.34.0/ollama-darwin.tgz \
  dd12b00bcce2d6551178e67ada90d5af9f75bdb54a118b96655250fa3e8ef734
UI_DIR="$TOOLS/qdrant-web-ui"
if [[ ! -f "$UI_DIR/.version" || ! -f "$UI_DIR/index.html" || ! -f "$UI_DIR/openapi.json" ]] || [[ "$(cat "$UI_DIR/.version")" != v0.2.15 ]]; then
  download_checked qdrant-web-ui-v0.2.15.zip \
    https://github.com/qdrant/qdrant-web-ui/releases/download/v0.2.15/dist-qdrant.zip \
    e7049bedadfc8591061875bb74507d00b5d3ca776ab7023a5144a2f5463cf63f
  download_checked qdrant-openapi-v1.13.6.json \
    https://raw.githubusercontent.com/qdrant/qdrant/v1.13.6/docs/redoc/master/openapi.json \
    396a1a98fa3f0620539fa4202f583f9abee7e6f2b4a3594b1e5915b8163cc68a
  stage="$(mktemp -d "$TOOLS/qdrant-ui-install.XXXXXX")"
  unzip -q "$DOWNLOADS/qdrant-web-ui-v0.2.15.zip" -d "$stage"
  mkdir -p "$UI_DIR"
  cp -R "$stage/dist/." "$UI_DIR/"
  cp "$DOWNLOADS/qdrant-openapi-v1.13.6.json" "$UI_DIR/openapi.json"
  rm -rf "$stage"
  printf 'v0.2.15\n' > "$UI_DIR/.version"
fi
printf 'Qdrant 控制台已安装，服务启动后访问 http://127.0.0.1:6333/dashboard\n'
printf '\n安装完成。运行 ./start.sh --vector 或 ./start.sh --live。\n首次启动下载 EmbeddingGemma（约 622 MB），模型保存在 .data/ollama/models。\n'

#!/usr/bin/env bash
# Local launcher. .env is trusted shell configuration; never commit credentials.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODE=demo
CHECK=0
for arg in "$@"; do
  case "$arg" in
    --demo) MODE=demo ;;
    --vector) MODE=vector ;;
    --ai) MODE=ai ;;
    --live) MODE=live ;;
    --check) CHECK=1 ;;
    --help|-h)
      cat <<'HELP'
用法：./start.sh [--demo|--ai|--vector|--live] [--check]
  默认/--demo  SQLite + 本地关键词检索 + 演示回复，无需模型密钥
  --ai         SQLite + 本地关键词检索 + 真实模型，无需 Docker/Ollama
  --vector     SQLite + Qdrant + Ollama Embedding，回答仍为演示回复
  --live       上述向量检索 + 真实模型
  真实模型模式支持 DEEPSEEK_* 配置的 OpenAI 兼容网关；密钥可由环境变量、.env 或终端隐藏输入提供。
  --check      仅检查依赖/配置/端口，不安装依赖、下载模型或启动服务
首次向量启动会下载 EMBEDDING_MODEL（默认 embeddinggemma），可能耗时较长。
Apple 芯片 Mac 可先运行 ./scripts/setup-vector-macos.sh 安装项目内运行包，无需 Docker。
Ctrl+C 停止本次启动的进程；Qdrant 容器与所有持久化数据保留。
HELP
      exit 0 ;;
    *) printf '未知参数：%s，请运行 ./start.sh --help\n' "$arg" >&2; exit 2 ;;
  esac
done
cd "$ROOT"
if [[ -f .env ]]; then set -a; source .env; set +a; fi
fail() { printf '\n启动失败：%s\n' "$*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || fail "缺少 $1。$2"; }
need node '请安装 Node.js 20.19+（或 22.12+）。'
need npm '请安装 npm。'
need mvn '请安装 Maven 3.9+。macOS 可用 brew install maven。'
need curl '请安装 curl。'
need lsof '需要 lsof 检查端口，避免覆盖已有服务。'
node -e 'const [a,b]=process.versions.node.split(".").map(Number);if(!((a===20&&b>=19)||(a===22&&b>=12)||a>22))process.exit(1)' || fail 'Node.js 版本过低，请使用 20.19+ 或 22.12+。'
# Respect JAVA_HOME when supplied; otherwise discover an installed JDK 21+.
java_ok() {
  local v
  v="$("$1" -version 2>&1 | head -n 1)"
  [[ "$v" =~ \"([0-9]+) ]] && (( BASH_REMATCH[1] >= 21 ))
}
if [[ -n "${JAVA_HOME:-}" ]] && java_ok "$JAVA_HOME/bin/java"; then
  :
else
  unset JAVA_HOME
  for candidate in /opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home /usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home; do
    if [[ -x "$candidate/bin/java" ]] && java_ok "$candidate/bin/java"; then export JAVA_HOME="$candidate"; break; fi
  done
  if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then
    candidate="$(/usr/libexec/java_home -v '21+' 2>/dev/null || true)"
    if [[ -n "$candidate" ]] && java_ok "$candidate/bin/java"; then export JAVA_HOME="$candidate"; fi
  fi
fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
need java '请安装 JDK 21+ 并设置 JAVA_HOME。'
java_ok "$(command -v java)" || fail '请安装 JDK 21+ 并设置 JAVA_HOME。'
[[ "${PORT:-8080}" == 8080 ]] || fail '本地启动入口固定使用后端 8080 端口，请移除 .env 中的 PORT 覆盖。'
export SERVER_ADDRESS=127.0.0.1 PORT=8080
for port in 8080 5173; do
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then fail "端口 $port 已被使用。请先停止已有 Mindhaven 服务；脚本不会结束未知进程。"; fi
done
export AI_MODE=demo VECTOR_MODE=local
if [[ "$MODE" == vector || "$MODE" == live ]]; then
  if [[ -x "$ROOT/.runtime/tools/ollama/ollama" ]]; then export PATH="$ROOT/.runtime/tools/ollama:$PATH"; fi
  if [[ -x "$ROOT/.runtime/tools/qdrant/qdrant" ]]; then export PATH="$ROOT/.runtime/tools/qdrant:$PATH"; fi
  QDRANT_RUNTIME="${QDRANT_RUNTIME:-auto}"
  if [[ "$QDRANT_RUNTIME" == auto ]]; then
    if command -v qdrant >/dev/null 2>&1; then QDRANT_RUNTIME=native; else QDRANT_RUNTIME=docker; fi
  fi
  case "$QDRANT_RUNTIME" in
    native) need qdrant '请先运行 ./scripts/setup-vector-macos.sh，或安装 qdrant 可执行文件。' ;;
    docker)
      need docker '请安装 Docker；Apple 芯片 Mac 也可运行 ./scripts/setup-vector-macos.sh。'
      docker compose version >/dev/null 2>&1 || fail '需要 Docker Compose v2。'
      docker info >/dev/null 2>&1 || fail '请先启动 Docker 服务。' ;;
    *) fail 'QDRANT_RUNTIME 只支持 auto、native 或 docker。' ;;
  esac
  need ollama '向量模式需要 Ollama，请安装 Ollama 后重试。'
  export VECTOR_MODE=qdrant QDRANT_HOST=127.0.0.1 QDRANT_PORT=6334
  export EMBEDDING_BASE_URL=http://127.0.0.1:11434 EMBEDDING_PATH=/v1/embeddings EMBEDDING_API_KEY=local
  export OLLAMA_HOST=127.0.0.1:11434 EMBEDDING_MODEL="${EMBEDDING_MODEL:-embeddinggemma}"
  export OLLAMA_MODELS="${OLLAMA_MODELS:-$ROOT/.data/ollama/models}" OLLAMA_NO_CLOUD=1
  if [[ -z "${QDRANT_COLLECTION:-}" ]]; then
    case "$EMBEDDING_MODEL" in
      embeddinggemma|embeddinggemma:300m) export QDRANT_COLLECTION=mindhaven_embeddinggemma_v1 ;;
      bge-m3) export QDRANT_COLLECTION=mindhaven_bge_m3_v1 ;;
      *) export QDRANT_COLLECTION="mindhaven_$(node -e 'process.stdout.write(require("crypto").createHash("sha256").update(process.env.EMBEDDING_MODEL).digest("hex").slice(0,16))')_v1" ;;
    esac
  fi
  printf 'Embedding：%s；Qdrant 集合：%s（换模型需重新同步索引）\n' "$EMBEDDING_MODEL" "$QDRANT_COLLECTION"
  printf 'Qdrant 运行方式：%s\n' "$QDRANT_RUNTIME"
fi
if [[ "$MODE" == live || "$MODE" == ai ]]; then
  if [[ -z "${DEEPSEEK_API_KEY:-}" ]]; then
    if [[ "$CHECK" == 0 && -t 0 ]]; then
      read -r -s -p '输入模型 API Key（仅用于本次运行，不写入文件）：' DEEPSEEK_API_KEY || fail '未读取到模型密钥。'
      printf '\n'
    fi
    [[ -n "${DEEPSEEK_API_KEY:-}" ]] || fail '真实模型模式需要 DEEPSEEK_API_KEY，可通过环境变量、.env 或交互启动时输入。'
  fi
  export AI_MODE=live DEEPSEEK_API_KEY
fi
printf '检查通过：模式 %s，Java %s，Node %s\n' "$MODE" "$(java -version 2>&1 | head -n 1)" "$(node --version)"
[[ "$CHECK" == 1 ]] && exit 0
RUN_DIR="$ROOT/.runtime/run-$(date +%Y%m%d-%H%M%S)-$$"
mkdir -p "$RUN_DIR"
# Keep the package cache writable even when the user-level npm cache is unavailable.
export npm_config_cache="${npm_config_cache:-$ROOT/.runtime/npm-cache}"
BACKEND_PID='' FRONTEND_PID='' OLLAMA_PID='' QDRANT_PID=''
cleanup() {
  local p
  trap - EXIT INT TERM
  for p in "$FRONTEND_PID" "$BACKEND_PID" "$OLLAMA_PID" "$QDRANT_PID"; do
    if [[ -n "$p" ]]; then kill "$p" 2>/dev/null || true; fi
  done
  for p in "$FRONTEND_PID" "$BACKEND_PID" "$OLLAMA_PID" "$QDRANT_PID"; do
    if [[ -n "$p" ]]; then wait "$p" 2>/dev/null || true; fi
  done
  rm -f "$RUN_DIR/app.jar"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
ready() {
  local url="$1" pid="$2" logfile="$3" label="$4" n
  for ((n=0;n<90;n++)); do
    if [[ -n "$pid" ]] && ! kill -0 "$pid" 2>/dev/null; then fail "$label 已退出，请查看 $logfile"; fi
    if curl --fail --silent --max-time 2 "$url" >/dev/null 2>&1; then return; fi
    sleep 1
  done
  fail "$label 就绪检查超时，请查看 $logfile"
}
if [[ "$MODE" == vector || "$MODE" == live ]]; then
  printf '启动 Qdrant（保留已有数据）…\n'
  if [[ "$QDRANT_RUNTIME" == docker ]]; then
    env -u DEEPSEEK_API_KEY docker compose -f "$ROOT/compose.yml" up -d qdrant
    ready http://127.0.0.1:6333/readyz '' 'docker compose logs qdrant' Qdrant
  elif ! curl --fail --silent --max-time 2 http://127.0.0.1:6333/readyz >/dev/null 2>&1; then
    for port in 6333 6334; do
      if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then fail "端口 $port 已占用，无法启动本机 Qdrant。"; fi
    done
    mkdir -p "$ROOT/.data/qdrant/storage" "$ROOT/.data/qdrant/snapshots"
    env -u DEEPSEEK_API_KEY QDRANT__SERVICE__HOST=127.0.0.1 \
      QDRANT__SERVICE__HTTP_PORT=6333 QDRANT__SERVICE__GRPC_PORT=6334 \
      QDRANT__SERVICE__STATIC_CONTENT_DIR="$ROOT/.runtime/tools/qdrant-web-ui" \
      QDRANT__STORAGE__STORAGE_PATH="$ROOT/.data/qdrant/storage" \
      QDRANT__STORAGE__SNAPSHOTS_PATH="$ROOT/.data/qdrant/snapshots" \
      QDRANT__TELEMETRY_DISABLED=true qdrant >"$RUN_DIR/qdrant.log" 2>&1 & QDRANT_PID=$!
    ready http://127.0.0.1:6333/readyz "$QDRANT_PID" "$RUN_DIR/qdrant.log" Qdrant
  fi
  printf 'Qdrant 已就绪：http://127.0.0.1:6333/\n'
  if curl --fail --silent --max-time 2 http://127.0.0.1:6333/dashboard >/dev/null 2>&1; then
    printf 'Qdrant 控制台：http://127.0.0.1:6333/dashboard\n'
  fi
  if ! curl --fail --silent --max-time 2 http://127.0.0.1:11434/api/tags >/dev/null 2>&1; then
    mkdir -p "$OLLAMA_MODELS"
    env -u DEEPSEEK_API_KEY ollama serve >"$RUN_DIR/ollama.log" 2>&1 & OLLAMA_PID=$!
    ready http://127.0.0.1:11434/api/tags "$OLLAMA_PID" "$RUN_DIR/ollama.log" Ollama
  fi
  printf '准备 Embedding 模型 %s（首次需要下载）…\n' "$EMBEDDING_MODEL"
  if env -u DEEPSEEK_API_KEY ollama show "$EMBEDDING_MODEL" >/dev/null 2>&1; then
    printf '复用本地已安装模型。\n'
  else
    env -u DEEPSEEK_API_KEY ollama pull "$EMBEDDING_MODEL"
  fi
fi
printf '安装前端依赖…\n'
(cd "$ROOT/frontend" && env -u DEEPSEEK_API_KEY npm ci --no-audit --no-fund)
printf '构建后端…\n'
MVN_ARGS=(-q -DskipTests package)
# Optional cache directory, useful in restricted development environments.
if [[ -n "${MAVEN_REPO_LOCAL:-}" ]]; then MVN_ARGS+=("-Dmaven.repo.local=$MAVEN_REPO_LOCAL"); fi
(cd "$ROOT/backend" && env -u DEEPSEEK_API_KEY mvn "${MVN_ARGS[@]}")
cp "$ROOT/backend/target/mindhaven-0.1.0.jar" "$RUN_DIR/app.jar"
(cd "$ROOT/backend" && exec java -jar "$RUN_DIR/app.jar") >"$RUN_DIR/backend.log" 2>&1 & BACKEND_PID=$!
ready http://127.0.0.1:8080/api/health "$BACKEND_PID" "$RUN_DIR/backend.log" 后端
(cd "$ROOT/frontend" && exec env -u DEEPSEEK_API_KEY node node_modules/vite/bin/vite.js --host 127.0.0.1 --port 5173 --strictPort) >"$RUN_DIR/frontend.log" 2>&1 & FRONTEND_PID=$!
ready http://127.0.0.1:5173/ "$FRONTEND_PID" "$RUN_DIR/frontend.log" 前端
printf '\nMindhaven 已启动：http://127.0.0.1:5173/\n日志目录：%s\n按 Ctrl+C 停止本次启动的应用。\n' "$RUN_DIR"
if [[ "$MODE" == vector || "$MODE" == live ]]; then
  printf '首次使用：注册/登录机构管理员 → AI 倾听室 → 知识与检索设置 → 同步向量索引。\n'
  if [[ "$QDRANT_RUNTIME" == docker ]]; then
    printf 'Qdrant 容器独立保留；停止它请执行 docker compose stop qdrant（不会删除数据）。\n'
  else
    printf '本次启动的 Qdrant 会随脚本停止，数据保留在 .data/qdrant；复用的已有进程不受影响。\n'
  fi
fi
while kill -0 "$BACKEND_PID" 2>/dev/null && kill -0 "$FRONTEND_PID" 2>/dev/null; do sleep 2; done
fail "应用进程意外退出，请查看 $RUN_DIR 中的日志。"

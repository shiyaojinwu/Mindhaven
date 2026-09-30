#!/usr/bin/env bash
# Project-local Redis. No login service and no public listening socket.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"
redis_ping() {
  if command -v redis-cli >/dev/null; then
    redis-cli -h 127.0.0.1 -p 6379 ping 2>/dev/null | grep -qx PONG
  else
    node -e 'const s=require("net").connect(6379,"127.0.0.1");s.setTimeout(1000);s.on("connect",()=>s.write("PING\r\n"));s.on("data",d=>{s.destroy();process.exit(d.toString().includes("PONG")?0:1)});s.on("error",()=>process.exit(1));s.on("timeout",()=>{s.destroy();process.exit(1)})' 2>/dev/null
  fi
}
if redis_ping; then echo 'Redis 已在 127.0.0.1:6379 运行'; exit 0; fi
command -v redis-server >/dev/null || { echo '请先安装 Redis 或启动 Compose Redis 服务：macOS brew install redis；Linux apt install redis-server' >&2; exit 1; }
mkdir -p "$ROOT/.data/redis" "$ROOT/.runtime"
redis-server --bind 127.0.0.1 --port 6379 --protected-mode yes   --appendonly yes --appendfsync everysec --maxmemory 256mb --maxmemory-policy noeviction   --dir "$ROOT/.data/redis" --daemonize yes   --pidfile "$ROOT/.runtime/redis.pid" --logfile "$ROOT/.runtime/redis.log"
for attempt in {1..30}; do
  if redis_ping; then echo 'Redis 已在 127.0.0.1:6379 就绪'; exit 0; fi
  sleep 0.1
done
echo 'Redis 未就绪，请查看 .runtime/redis.log' >&2
exit 1

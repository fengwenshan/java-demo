#!/usr/bin/env bash
# 启动服务端（本地开发）：优先复用已打包的 Jar，没有则先构建。
#
# 用法：
#   ./scripts/run-server.sh [--build] [--app-key KEY] [--app-secret SECRET] [--server.port PORT] [其它 Spring Boot 参数...]
#
# AppKey/AppSecret 未指定时使用 application.yml 里的内置演示默认值；
# 也可以直接用环境变量 UPLOAD_APP_KEY / UPLOAD_APP_SECRET 覆盖。
set -euo pipefail
source "$(dirname "$0")/env.sh"
cd "$PROJECT_ROOT"

BUILD=0
PORT="${SERVER_PORT:-8080}"
PASSTHROUGH=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --build)
      BUILD=1
      shift
      ;;
    --app-key)
      [[ $# -ge 2 ]] || { echo "--app-key 缺少值" >&2; exit 2; }
      export UPLOAD_APP_KEY="$2"
      shift 2
      ;;
    --app-secret)
      [[ $# -ge 2 ]] || { echo "--app-secret 缺少值" >&2; exit 2; }
      export UPLOAD_APP_SECRET="$2"
      shift 2
      ;;
    --help|-h)
      sed -n '2,8p' "$0"
      exit 0
      ;;
    --server.port=*)
      PORT="${1#--server.port=}"
      PASSTHROUGH+=("$1")
      shift
      ;;
    --server.port)
      [[ $# -ge 2 ]] || { echo "--server.port 缺少值" >&2; exit 2; }
      PORT="$2"
      PASSTHROUGH+=("$1" "$2")
      shift 2
      ;;
    *)
      PASSTHROUGH+=("$1")
      shift
      ;;
  esac
done

# 端口被占用时尽早给出可操作提示，避免客户端只看到「网络错误」。
if command -v lsof >/dev/null 2>&1; then
  PORT_LISTENERS="$(lsof -nP -iTCP:"$PORT" -sTCP:LISTEN 2>/dev/null || true)"
  if [[ -n "$PORT_LISTENERS" ]]; then
    echo "端口 ${PORT} 已被占用，服务端无法正常提供接口：" >&2
    echo "$PORT_LISTENERS" >&2
    echo "" >&2
    echo "请释放该端口，或换一个端口启动：" >&2
    echo "  SERVER_PORT=8081 ./scripts/run-server.sh" >&2
    echo "  然后把客户端「设置 → 服务器地址」改成 http://127.0.0.1:8081" >&2
    exit 1
  fi
fi

JAR="$(ls -1 server/target/ScopeFTP-server-*.jar 2>/dev/null | grep -v original | head -n1 || true)"
if [[ "$BUILD" == "1" || -z "$JAR" ]]; then
  echo "==> 构建服务端 Jar"
  "$PROJECT_ROOT/scripts/mvn.sh" -f server/pom.xml -DskipTests package
  JAR="$(ls -1 server/target/ScopeFTP-server-*.jar | grep -v original | head -n1)"
fi

echo "==> 启动：${JAR}（Ctrl+C 停止）"
echo "    AppKey：${UPLOAD_APP_KEY:-内置默认值}"
echo "    健康检查：http://127.0.0.1:${PORT}/api/health"
if [[ ${#PASSTHROUGH[@]} -gt 0 ]]; then
  exec java ${JAVA_OPTS:--Xms256m -Xmx512m} -jar "$JAR" "${PASSTHROUGH[@]}"
fi
exec java ${JAVA_OPTS:--Xms256m -Xmx512m} -jar "$JAR"

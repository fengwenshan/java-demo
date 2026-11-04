#!/usr/bin/env bash
# 启动服务端（本地开发）。端口/存储根等默认用 application.yml（8080、./data），
# 若存在本地的 application-<env>.yml 环境文件则覆盖；环境文件一律不入库。
#
# 凭证必须显式指定：--app-key/--app-secret；缺失时脚本会在启动前一次性提示并退出，
# 不会先拉起 Spring 再打印长堆栈；服务端没有内置默认值。
# AppSecret 任意非空即可，不是只能填 32 字节；少于 32 字符服务端只警告。生产推荐：
#   APP_SECRET="$(openssl rand -hex 32)"
#
# 用法：
#   ./scripts/run-server.sh [--env <环境名>] [--build] [--app-key KEY] [--app-secret SECRET] [其它 Spring Boot 参数...]
#
# 环境 = server/src/main/resources/application-<env>.yml（可选，本地/私有，不入库）：
#   dev（默认）/ docker / xiaoshan / xihu
# 不传 --env 时默认 dev；application-dev.yml 不存在时用 application.yml（仍需凭证）。
# 打包不需要凭证；启动时没有凭证会直接失败。
#
# 改过环境文件/application.yml 会自动重新打包；--build 可强制重新打包。
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

ENV_NAME="dev"
ENV_EXPLICIT=0
BUILD=0
PASSTHROUGH=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --env|--profile)
      [[ $# -ge 2 ]] || { echo "--env 缺少值（如 dev）" >&2; exit 2; }
      ENV_NAME="$2"
      ENV_EXPLICIT=1
      shift 2
      ;;
    --env=*|--profile=*)
      ENV_NAME="${1#*=}"
      ENV_EXPLICIT=1
      shift
      ;;
    --build)
      BUILD=1
      shift
      ;;
    --help|-h)
      sed -n '2,16p' "$0"
      exit 0
      ;;
    *)
      PASSTHROUGH+=("$1")
      shift
      ;;
  esac
done

# 先确定环境，再加载对应 env 配置文件：
# env.local.<env> → env.<env> → env.local → env（后者覆盖前者，详见 scripts/env.sh）。
export SCOP_FTP_ENV="$ENV_NAME"
source "$PROJECT_ROOT/scripts/env.sh"
cd "$PROJECT_ROOT"

RESOURCES="$PROJECT_ROOT/server/src/main/resources"
ENV_FILE="$RESOURCES/application-${ENV_NAME}.yml"
if [[ -f "$ENV_FILE" ]]; then
  CONFIG_FILE="$ENV_FILE"
  ENV_LABEL="${ENV_NAME}（application-${ENV_NAME}.yml）"
else
  if [[ "$ENV_EXPLICIT" == "1" ]]; then
    echo "找不到环境配置：$ENV_FILE" >&2
    AVAILABLE="$(cd "$RESOURCES" && ls application-*.yml 2>/dev/null | grep -v 'application-test.yml' | sed -e 's/^application-//' -e 's/\.yml$//' | tr '\n' ' ' || true)"
    echo "可用环境：${AVAILABLE:-（无，直接使用 application.yml 默认值）}" >&2
    exit 2
  fi
  CONFIG_FILE="$RESOURCES/application.yml"
  ENV_LABEL="${ENV_NAME}（未找到 application-${ENV_NAME}.yml，使用 application.yml 默认值）"
fi

# 启动前一次性校验凭证：不要在缺 secret 时先构建/拉起 Spring，再被长堆栈刷屏。
# 支持命令行 `--app-key KEY --app-secret SECRET`，也支持 UPLOAD_APP_KEY /
# UPLOAD_APP_SECRET 环境变量（Spring 启动参数优先级更高）。
CLI_APP_KEY=""
CLI_APP_SECRET=""
for ((i = 0; i < ${#PASSTHROUGH[@]}; i++)); do
  arg="${PASSTHROUGH[i]}"
  case "$arg" in
    --app-key)
      next="${PASSTHROUGH[i + 1]:-}"
      [[ "$next" == --* ]] && next=""
      CLI_APP_KEY="$next"
      ;;
    --app-key=*)
      CLI_APP_KEY="${arg#--app-key=}"
      ;;
    --app-secret)
      next="${PASSTHROUGH[i + 1]:-}"
      [[ "$next" == --* ]] && next=""
      CLI_APP_SECRET="$next"
      ;;
    --app-secret=*)
      CLI_APP_SECRET="${arg#--app-secret=}"
      ;;
  esac
done

APP_KEY_VALUE="${CLI_APP_KEY:-${UPLOAD_APP_KEY:-}}"
APP_SECRET_VALUE="${CLI_APP_SECRET:-${UPLOAD_APP_SECRET:-}}"

print_credential_help() {
  cat >&2 <<EOF

请一次性提供两项（服务端没有内置默认凭证）：
  APP_SECRET="\$(openssl rand -hex 32)"
  ./scripts/run-server.sh --env ${ENV_NAME} --app-key demo-app --app-secret "\$APP_SECRET"

AppKey 任意非空即可；AppSecret 任意非空字符串即可，不是只能填 32 字节。
服务端只对少于 32 字符的 AppSecret 提示，不会阻止启动；生产推荐随机密钥：
  openssl rand -hex 32

也可以使用环境变量：
  APP_SECRET="\$(openssl rand -hex 32)"
  UPLOAD_APP_KEY=demo-app UPLOAD_APP_SECRET="\$APP_SECRET" ./scripts/run-server.sh --env ${ENV_NAME}
EOF
}

if [[ -z "$APP_KEY_VALUE" || -z "$APP_SECRET_VALUE" ]]; then
  echo "==> 启动凭证不完整，已停止启动（不会拉起 Spring）" >&2
  if [[ -z "$APP_KEY_VALUE" && -z "$APP_SECRET_VALUE" ]]; then
    echo "    缺少 --app-key 和 --app-secret" >&2
  elif [[ -z "$APP_KEY_VALUE" ]]; then
    echo "    缺少 --app-key" >&2
  else
    echo "    缺少 --app-secret" >&2
  fi
  print_credential_help
  exit 1
elif (( ${#APP_SECRET_VALUE} < 32 )); then
  echo "==> 提示：AppSecret 只有 ${#APP_SECRET_VALUE} 个字符" >&2
  echo "    服务端允许启动（只提示、不阻止）；生产建议用 openssl rand -hex 32 生成随机密钥" >&2
fi

# 端口只用于启动前的占用检查与健康检查提示，真正端口以配置文件为准
PORT="$(awk '
  /^server:[[:space:]]*$/ { in_server = 1; next }
  in_server && /^[^[:space:]#]/ { in_server = 0 }
  in_server && /^[[:space:]]+port:[[:space:]]*/ { print $2; exit }
' "$CONFIG_FILE" | tr -d '"' || true)"
if [[ ! "$PORT" =~ ^[0-9]+$ ]]; then
  PORT="${SERVER_PORT:-8080}"
fi

# 命令行显式指定端口时以命令行为准
if [[ ${#PASSTHROUGH[@]} -gt 0 ]]; then
  for arg in "${PASSTHROUGH[@]}"; do
    case "$arg" in
      --server.port=*) PORT="${arg#--server.port=}" ;;
    esac
  done
fi

if command -v lsof >/dev/null 2>&1; then
  PORT_LISTENERS="$(lsof -nP -iTCP:"$PORT" -sTCP:LISTEN 2>/dev/null || true)"
  if [[ -n "$PORT_LISTENERS" ]]; then
    echo "端口 ${PORT} 已被占用，环境 ${ENV_NAME} 无法正常提供接口：" >&2
    echo "$PORT_LISTENERS" >&2
    echo "" >&2
    echo "换端口：改 $CONFIG_FILE 里的 server.port，或临时加 --server.port=8081" >&2
    exit 1
  fi
fi

JAR="$(ls -1 server/target/ScopeFTP-server-*.jar 2>/dev/null | grep -v original | head -n1 || true)"
build_jar() {
  echo "==> 构建服务端 Jar"
  "$PROJECT_ROOT/scripts/mvn.sh" -f server/pom.xml -DskipTests package
  JAR="$(ls -1 server/target/ScopeFTP-server-*.jar | grep -v original | head -n1)"
}
if [[ "$BUILD" == "1" || -z "$JAR" ]]; then
  build_jar
elif [[ -n "$JAR" ]]; then
  # Jar 不含源码里的配置文件，新增/修改 application*.yml 后必须重新打包；
  # 否则 Spring 找不到 profile 会静默回退到 application.yml + 代码默认值（端口/目录全错）。
  NEWER_ENV_FILE="$(find "$RESOURCES" -maxdepth 1 -name 'application*.yml' -newer "$JAR" -print -quit 2>/dev/null || true)"
  if [[ -n "$NEWER_ENV_FILE" ]]; then
    echo "==> 配置文件比 Jar 新（$(basename "$NEWER_ENV_FILE")），自动重新打包"
    build_jar
  fi
fi

echo "==> 启动：${JAR}"
echo "    环境：${ENV_LABEL}"
if [[ -n "$CLI_APP_KEY" ]]; then
  echo "    AppKey：命令行传入"
else
  echo "    AppKey：环境变量传入"
fi
if [[ -n "$CLI_APP_SECRET" ]]; then
  echo "    AppSecret：命令行传入"
else
  echo "    AppSecret：环境变量传入"
fi
echo "    健康检查：curl -H 'X-Device-Mac: 02:00:00:00:00:01' http://127.0.0.1:${PORT}/api/health"
if [[ ${#PASSTHROUGH[@]} -gt 0 ]]; then
  exec java ${JAVA_OPTS:--Xms256m -Xmx512m} -jar "$JAR" "--spring.profiles.active=${ENV_NAME}" "${PASSTHROUGH[@]}"
fi
exec java ${JAVA_OPTS:--Xms256m -Xmx512m} -jar "$JAR" "--spring.profiles.active=${ENV_NAME}"

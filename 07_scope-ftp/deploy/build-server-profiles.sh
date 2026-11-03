#!/usr/bin/env bash
# 构建服务端分环境部署包。
#
# 用法：
#   ./deploy/build-server-profiles.sh                  # 默认包（profile=default）
#   ./deploy/build-server-profiles.sh xihu             # 只打 xihu
#
# 固定产物名：目录 ftp-server/、压缩包 ftp-server.zip、Jar ftp-server.jar。
# 一次只能指定一个 profile；换 profile 重新打包会覆盖上一次的固定名产物。
#
# 没有配置名（profile）时用 default：不激活 Spring profile，直接使用 application.yml 的默认配置。
# 有配置名时对应 server/src/main/resources/application-<profile>.yml，并自动带 --spring.profiles.active=<profile>。
#
# 包内 scop-ftp.env 由 scripts/env* 中该 profile 的生效配置直接生成（只放 JDK、JVM 参数），
# start.sh 的默认 JDK / JVM 参数也来自同一份配置；端口、存储根等仍以 jar 内 application.yml（default）或 application-<profile>.yml 为准。
#
# 产物（release/server/，名字固定）：
#   ftp-server/      ftp-server.jar + start.sh + scop-ftp.env
#   ftp-server.zip   上述目录的压缩包
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RESOURCES="$PROJECT_ROOT/server/src/main/resources"
PROFILES=("$@")
if [[ ${#PROFILES[@]} -eq 0 ]]; then
  PROFILES=(default)
fi
if [[ ${#PROFILES[@]} -gt 1 ]]; then
  echo "产物名固定为 ftp-server，一次只能指定一个 profile；需要多个请分别执行。" >&2
  exit 2
fi

# 只打一个环境时按该环境加载 env 配置文件（env.local.<profile> → env.<profile> → env.local → env）。
if [[ -z "${SCOP_FTP_ENV:-}" && ${#PROFILES[@]} -eq 1 ]]; then
  export SCOP_FTP_ENV="${PROFILES[0]}"
fi
source "$PROJECT_ROOT/scripts/env.sh"

# 生成可直接 source / systemd EnvironmentFile 使用的双引号值。
quote_env_value() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//\$/\\\$}"
  value="${value//\`/\\\`}"
  printf '"%s"' "$value"
}

# 读取某个 profile 生效的 env 配置，写成可 source 的临时文件，避免影响当前构建环境。
load_profile_config_for_package() {
  local profile="$1" cfg_file="$2"
  (
    unset JAVA_HOME JAVA_OPTS
    SCOP_FTP_VERBOSE_ENV=""
    load_env_config_files "$profile"
    {
      printf 'CFG_ENV_FILE_COUNT=%q\n' "${#LOADED_ENV_FILES[@]}"
      printf 'CFG_JAVA_HOME=%q\n' "${ENV_FILE_JAVA_HOME:-}"
      printf 'CFG_JAVA_OPTS=%q\n' "${ENV_FILE_JAVA_OPTS:-}"
    } > "$cfg_file"
  )
}

for profile in "${PROFILES[@]}"; do
  if [[ "$profile" != "default" && ! -f "$RESOURCES/application-${profile}.yml" ]]; then
    echo "找不到环境文件：$RESOURCES/application-${profile}.yml" >&2
    AVAILABLE="$(cd "$RESOURCES" && ls application-*.yml 2>/dev/null | grep -v 'application-test.yml' | sed -e 's/^application-//' -e 's/\.yml$//' | tr '\n' ' ' || true)"
    echo "可用环境：${AVAILABLE:-（无）}" >&2
    exit 1
  fi
done

echo "==> 构建服务端 Jar（项目内 Maven 包装器）"
"$PROJECT_ROOT/scripts/mvn.sh" -f "$PROJECT_ROOT/server/pom.xml" -DskipTests clean package
JAR="$(ls -1 "$PROJECT_ROOT/server/target"/*.jar | grep -v original | head -n1)"

OUT="$PROJECT_ROOT/release/server"
mkdir -p "$OUT"

# 固定产物名：构建前清理旧的 ftp-server（以及历史 ftp-server-*）目录和 zip。
for existing in "$OUT"/ftp-server*; do
  [[ -e "$existing" ]] || continue
  rm -rf "$existing"
done

for profile in "${PROFILES[@]}"; do
  dir="$OUT/ftp-server"
  mkdir -p "$dir"
  cp "$JAR" "$dir/ftp-server.jar"

  # 1) 读取该 profile 在 scripts/env* 里的生效配置
  cfg_file="$(mktemp "${TMPDIR:-/tmp}/scop-ftp-env.XXXXXX")"
  load_profile_config_for_package "$profile" "$cfg_file"
  # shellcheck disable=SC1090
  source "$cfg_file"
  rm -f "$cfg_file"

  if [[ "$profile" == "default" ]]; then
    SPRING_PROFILE_OPT=""
    ENV_CONFIG_DESC="application.yml（不激活 Spring profile）"
    SPRING_PROFILE_DESC="不指定 --spring.profiles.active，使用 application.yml 默认值"
  else
    SPRING_PROFILE_OPT="--spring.profiles.active=${profile}"
    ENV_CONFIG_DESC="application-${profile}.yml"
    SPRING_PROFILE_DESC="--spring.profiles.active=${profile}"
  fi
  CFG_JAVA_OPTS_VALUE="${CFG_JAVA_OPTS:--Xms256m -Xmx512m}"
  JAVA_HOME_Q="$(quote_env_value "$CFG_JAVA_HOME")"
  JAVA_OPTS_Q="$(quote_env_value "$CFG_JAVA_OPTS_VALUE")"
  # 2) 直接生成可用的 scop-ftp.env（不再是 .example 模板）
  {
    echo "# 由 deploy/build-server-profiles.sh 根据 scripts/env* 的生效配置生成（profile=${profile}）"
    echo "# 生成时间：$(date '+%Y-%m-%d %H:%M:%S')；重新打包会覆盖本文件。"
    echo "# start.sh 会自动加载；只放 JDK、JVM 参数，端口/存储根等见 jar 内 ${ENV_CONFIG_DESC}。"
    echo
    if [[ "$CFG_ENV_FILE_COUNT" -gt 0 && -n "$CFG_JAVA_HOME" ]]; then
      echo "JAVA_HOME=$JAVA_HOME_Q"
    fi
    echo "JAVA_OPTS=$JAVA_OPTS_Q"
  } > "$dir/scop-ftp.env"
  chmod 600 "$dir/scop-ftp.env"

  # 3) 生成 start.sh：默认 JDK / JVM 参数也来自 scripts/env*，包内 scop-ftp.env 可覆盖
  JAVA_HOME_DEFAULT_BLOCK=""
  if [[ "$CFG_ENV_FILE_COUNT" -gt 0 && -n "$CFG_JAVA_HOME" ]]; then
    JAVA_HOME_DEFAULT_BLOCK="$(printf '%s\n' \
      '# 包内默认 JDK：仅当 scop-ftp.env 没有显式写 JAVA_HOME 时使用' \
      'if [[ -z "${JAVA_HOME:-}" && "${ENV_FILE_JAVA_HOME_EXPLICIT:-0}" != "1" ]]; then' \
      "  JAVA_HOME=$JAVA_HOME_Q" \
      'fi')"
  fi
  JAVA_OPTS_DEFAULT_BLOCK="$(printf '%s\n' \
    '# 默认 JVM 参数（包内 scop-ftp.env 可覆盖）' \
    'if [[ -z "${JAVA_OPTS:-}" ]]; then' \
    "  JAVA_OPTS=$JAVA_OPTS_Q" \
    'fi')"

  cat > "$dir/start.sh" <<EOF
#!/usr/bin/env bash
# 启动包：环境 profile=${profile}（配置在 jar 内的 ${ENV_CONFIG_DESC}）
# 包内 scop-ftp.env 由 scripts/env* 的生效配置生成，可直接修改；重新打包会覆盖。
#
# 用法：
#   ./start.sh --app-key=xxx --app-secret=yyy [选项...]             后台启动（日志 logs/console.log）
#   ./start.sh start --app-key=xxx --app-secret=yyy [选项...]       后台启动并透传 Spring 参数
#   ./start.sh run --app-key=xxx --app-secret=yyy [选项...]         前台启动（调试用，Ctrl+C 停止服务）
#   ./start.sh logs [行数]                                          查看/跟随日志（Ctrl+C 只退出日志）
#   ./start.sh status                                               查看运行状态
#   ./start.sh stop                                                 停止服务
#   ./start.sh restart --app-key=xxx --app-secret=yyy [选项...]     重启
#   ./start.sh -h | --help                                          显示使用说明
#
# 必传参数（启动命令直接传入，不写入包内 env）：
#   --app-key=xxx                  客户端 AppKey
#   --app-secret=yyy               客户端 AppSecret
#   AppKey：非空、启用的客户端内唯一；建议只用字母/数字/-/_，例如 my-desktop。
#   AppSecret：非空，无固定格式；服务端只对不足 32 字符告警，建议用
#              openssl rand -hex 32 生成；客户端必须填同一个值，
#              不要带首尾空格，特殊字符用单引号包起来。
#
# 可选参数（方括号表示可选）：
#   [--server.port=xxx]            临时覆盖服务端口
#   [--spring.profiles.active=xxx] 临时覆盖 Spring profile
#   [--其它Spring参数=xxx ...]      原样透传给服务端
#   直接传参数（不带 start/run）等同于 run，前台运行。
set -euo pipefail
cd "\$(dirname "\$0")"
DIR="\$(pwd)"
PID_FILE="\$DIR/scop-ftp.pid"
LOG_DIR="\$DIR/logs"
LOG_FILE="\$LOG_DIR/console.log"
JAR_FILE="ftp-server.jar"
SPRING_PROFILE_OPT="${SPRING_PROFILE_OPT}"
SHELL_JAVA_HOME="\${JAVA_HOME:-}"
ENV_FILE_JAVA_HOME_EXPLICIT=0

usage() {
  cat <<'USAGE'
用法：
  ./start.sh --app-key=xxx --app-secret=yyy [选项...]             后台启动（日志 logs/console.log）
  ./start.sh start --app-key=xxx --app-secret=yyy [选项...]       后台启动并透传 Spring 参数
  ./start.sh run --app-key=xxx --app-secret=yyy [选项...]         前台启动（Ctrl+C 停止服务）
  ./start.sh logs [行数]                                          查看/跟随日志（Ctrl+C 只退出日志）
  ./start.sh status                                               查看运行状态
  ./start.sh stop                                                 停止服务
  ./start.sh restart --app-key=xxx --app-secret=yyy [选项...]     重启
  ./start.sh -h | --help                                          显示本说明

必传参数（启动命令直接传入）：
  --app-key=xxx                  客户端 AppKey
  --app-secret=yyy               客户端 AppSecret
  AppKey：非空、启用的客户端内唯一；建议只用字母/数字/-/_，例如 my-desktop。
  AppSecret：非空，无固定格式；服务端只对不足 32 字符告警，建议用
             openssl rand -hex 32 生成；客户端必须填同一个值，
             不要带首尾空格，特殊字符用单引号包起来。

可选参数（方括号表示可选）：
  [--server.port=xxx]            临时覆盖服务端口
  [--spring.profiles.active=xxx] 临时覆盖 Spring profile
  [--其它Spring参数=xxx ...]      原样透传给服务端
  直接传参数（不带 start/run）等同于 run，前台运行。

示例：
  ./start.sh --app-key=my-key --app-secret=my-secret
  ./start.sh start --app-key=my-key --app-secret=my-secret
  ./start.sh logs
USAGE
}

case "\${1:-}" in
  -h|--help|help) usage; exit 0 ;;
esac

if [[ -f "\${DIR}/scop-ftp.env" ]]; then
  if grep -Eq '^[[:space:]]*(export[[:space:]]+)?JAVA_HOME=' "\${DIR}/scop-ftp.env"; then
    ENV_FILE_JAVA_HOME_EXPLICIT=1
  fi
  set -a
  # shellcheck disable=SC1091
  source "\${DIR}/scop-ftp.env"
  set +a
fi

# JAVA_HOME 为空（scop-ftp.env 里 JAVA_HOME= 或没有这一行）时：
# 先沿用启动前的 shell JAVA_HOME；没有则用包内默认 JDK 或 PATH 里的 java。
if [[ -z "\${JAVA_HOME:-}" && -n "\${SHELL_JAVA_HOME:-}" ]]; then
  JAVA_HOME="\$SHELL_JAVA_HOME"
fi
${JAVA_HOME_DEFAULT_BLOCK}
${JAVA_OPTS_DEFAULT_BLOCK}

resolve_java_bin() {
  if [[ -n "\${JAVA_HOME:-}" ]]; then
    if [[ ! -x "\${JAVA_HOME}/bin/java" ]]; then
      echo "JAVA_HOME 不可用：\${JAVA_HOME}/bin/java 不存在或不可执行" >&2
      echo "请在 scop-ftp.env 里把 JAVA_HOME 改成 JDK 21 的安装目录（例如 /opt/jdk-21）" >&2
      exit 1
    fi
    JAVA_BIN="\${JAVA_HOME}/bin/java"
  else
    JAVA_BIN="\$(command -v java || true)"
    if [[ -z "\$JAVA_BIN" ]]; then
      echo "找不到 java：请在 scop-ftp.env 里配置 JAVA_HOME，或把 JDK 21 的 bin 目录加入 PATH" >&2
      exit 1
    fi
  fi

  # 版本检查：项目按 Java 21 编译，低版本 JVM 会直接 UnsupportedClassVersionError
  JAVA_VERSION="\$("\$JAVA_BIN" -version 2>&1 | awk -F'"' '/version/ {print \$2; exit}')"
  JAVA_MAJOR="\$("\$JAVA_BIN" -version 2>&1 | awk -F'"' '/version/ {v=\$2; split(v,a,"."); print (a[1]=="1" ? a[2] : a[1]); exit}')"
  case "\${JAVA_MAJOR:-}" in
    ''|*[!0-9]*) ;;  # 解析不出来就不拦，交给 JVM 自己报错
    *)
      if [ "\$JAVA_MAJOR" -lt 21 ]; then
        echo "当前 Java 版本是 \${JAVA_VERSION}（\${JAVA_BIN}），本项目需要 JDK 21+" >&2
        echo "请在 scop-ftp.env 里把 JAVA_HOME 指向 JDK 21 的安装目录" >&2
        exit 1
      fi
      ;;
  esac
}

is_running() {
  [[ -f "\$PID_FILE" ]] || return 1
  local pid
  pid="\$(cat "\$PID_FILE" 2>/dev/null || true)"
  [[ -n "\$pid" ]] || return 1
  kill -0 "\$pid" 2>/dev/null || return 1
  local cmd
  cmd="\$(ps -p "\$pid" -o command= 2>/dev/null || true)"
  [[ -z "\$cmd" || "\$cmd" == *"\$JAR_FILE"* ]]
}

has_client_credentials() {
  local has_key=0
  local has_secret=0
  local arg
  while [[ \$# -gt 0 ]]; do
    arg="\$1"
    case "\$arg" in
      --app-key=*) has_key=1 ;;
      --app-secret=*) has_secret=1 ;;
      --app-key) if [[ \$# -ge 2 ]]; then has_key=1; fi ;;
      --app-secret) if [[ \$# -ge 2 ]]; then has_secret=1; fi ;;
      --app.security.clients*) has_key=1; has_secret=1 ;;
    esac
    shift
  done
  [[ "\$has_key" -eq 1 && "\$has_secret" -eq 1 ]]
}

check_client_credentials() {
  if has_client_credentials "\$@"; then
    return 0
  fi
  echo "缺少客户端凭证：启动参数未提供 --app-key / --app-secret。" >&2
  echo "前台启动：./start.sh --app-key=xxx --app-secret=yyy" >&2
  echo "后台启动：./start.sh start --app-key=xxx --app-secret=yyy" >&2
  echo "查看帮助：./start.sh -h" >&2
  return 1
}

start_bg() {
  check_client_credentials "\$@" || exit 1
  if is_running; then
    echo "服务已在运行（PID \$(cat "\$PID_FILE")），未重复启动" >&2
    echo "查看状态：./start.sh status    查看日志：./start.sh logs" >&2
    exit 1
  fi
  resolve_java_bin
  mkdir -p "\$LOG_DIR"
  printf '%s\n' "===== \$(date '+%Y-%m-%d %H:%M:%S') 启动 profile=${profile} =====" >> "\$LOG_FILE"
  nohup "\$JAVA_BIN" \$JAVA_OPTS -jar "\$JAR_FILE" \$SPRING_PROFILE_OPT "\$@" >> "\$LOG_FILE" 2>&1 < /dev/null &
  local pid=\$!
  echo "\$pid" > "\$PID_FILE"
  sleep 1
  if is_running; then
    echo "已后台启动：PID \$pid"
    echo "日志：\$LOG_FILE"
    echo "查看日志：./start.sh logs    停止：./start.sh stop    状态：./start.sh status"
  else
    rm -f "\$PID_FILE"
    echo "启动失败，最近日志：" >&2
    tail -n 40 "\$LOG_FILE" >&2 || true
    exit 1
  fi
}

run_fg() {
  check_client_credentials "\$@" || exit 1
  if is_running; then
    echo "服务已在后台运行（PID \$(cat "\$PID_FILE")），请先 ./start.sh stop" >&2
    exit 1
  fi
  resolve_java_bin
  exec "\$JAVA_BIN" \$JAVA_OPTS -jar "\$JAR_FILE" \$SPRING_PROFILE_OPT "\$@"
}

stop_bg() {
  if [[ ! -f "\$PID_FILE" ]]; then
    echo "未发现 PID 文件，服务可能未运行"
    return 0
  fi
  if ! is_running; then
    echo "服务未运行（清理过期 PID 文件）"
    rm -f "\$PID_FILE"
    return 0
  fi
  local pid
  pid="\$(cat "\$PID_FILE")"
  kill "\$pid" 2>/dev/null || true
  local i
  for i in \$(seq 1 30); do
    kill -0 "\$pid" 2>/dev/null || break
    sleep 1
  done
  if kill -0 "\$pid" 2>/dev/null; then
    echo "30 秒未退出，强制结束 PID \$pid" >&2
    kill -9 "\$pid" 2>/dev/null || true
  fi
  rm -f "\$PID_FILE"
  echo "已停止（PID \${pid}）"
}

status_bg() {
  if is_running; then
    echo "运行中：PID \$(cat "\$PID_FILE")"
    echo "日志：\$LOG_FILE"
  else
    echo "未运行"
    if [[ -f "\$PID_FILE" ]]; then
      echo "PID 文件：\${PID_FILE}（可能是过期文件）"
    fi
  fi
}

ACTION="start"
case "\${1:-}" in
  start|daemon) ACTION="start"; shift ;;
  run|foreground|-f|--foreground) ACTION="run"; shift ;;
  stop) ACTION="stop"; shift ;;
  status) ACTION="status"; shift ;;
  logs) ACTION="logs"; shift ;;
  restart) ACTION="restart"; shift ;;
  "") ACTION="start" ;;
  *) ACTION="run" ;;
esac

case "\$ACTION" in
  start) start_bg "\$@" ;;
  run) run_fg "\$@" ;;
  stop) stop_bg ;;
  status) status_bg ;;
  logs)
    if [[ ! -f "\$LOG_FILE" ]]; then
      echo "日志文件不存在：\$LOG_FILE" >&2
      exit 1
    fi
    exec tail -n "\${1:-200}" -f "\$LOG_FILE"
    ;;
  restart) stop_bg; start_bg "\$@" ;;
esac
EOF
  chmod +x "$dir/start.sh"

  (cd "$OUT" && rm -f "ftp-server.zip" && zip -q -r "ftp-server.zip" "ftp-server")
  echo "==> $dir"
done

echo "==> 全部完成：$OUT"

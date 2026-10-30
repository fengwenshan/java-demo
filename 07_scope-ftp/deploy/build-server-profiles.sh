#!/usr/bin/env bash
# 构建按生产环境 profile 拆分的服务端部署包
#
# 用法：
#   ./deploy/build-server-profiles.sh                 # default xiaoshan xihu
#   ./deploy/build-server-profiles.sh xiaoshan xihu   # 指定 profile
#
# 产物（release/server/）：
#   ScopeFTP-server-<profile>/     jar + 启动脚本 + 环境变量模板 + README
#   ScopeFTP-server-<profile>.zip  上述目录的压缩包
#
# 说明：
#   - 各 profile 的 application-<profile>.yml 已打进 jar，可用 SPRING_PROFILES_ACTIVE 切换；
#   - start.sh 会加载同目录 scop-ftp.env（不存在则用默认值）；
#   - 端口/存储根/日志目录/密钥都通过环境变量覆盖，模板见 scop-ftp.env.example。
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$PROJECT_ROOT/scripts/env.sh"

PROFILES=("$@")
if [[ ${#PROFILES[@]} -eq 0 ]]; then
  PROFILES=(default xiaoshan xihu)
fi

echo "==> 构建服务端 Jar（项目内 Maven 包装器）"
"$PROJECT_ROOT/scripts/mvn.sh" -f "$PROJECT_ROOT/server/pom.xml" -DskipTests clean package
JAR="$(ls -1 "$PROJECT_ROOT/server/target"/*.jar | grep -v original | head -n1)"

OUT="$PROJECT_ROOT/release/server"
mkdir -p "$OUT"

for profile in "${PROFILES[@]}"; do
  case "${profile}" in
    default) env_file=""; app_name="通用" ;;
    xiaoshan) env_file="xiaoshan"; app_name="萧山" ;;
    xihu) env_file="xihu"; app_name="西湖" ;;
    *) echo "未知 profile: ${profile}（支持 default/xiaoshan/xihu）" >&2; exit 1 ;;
  esac

  dir="$OUT/ScopeFTP-server-${profile}"
  rm -rf "$dir"
  mkdir -p "$dir"
  cp "$JAR" "$dir/ScopeFTP-server-${profile}.jar"

  if [[ "${profile}" == "default" ]]; then
    profile_arg="--spring.profiles.active=default"
    storage_default="./data"
    audit_default="./logs/audit"
    log_default="./logs"
  else
    profile_arg="--spring.profiles.active=${profile}"
    storage_default="./data/${profile}"
    audit_default="./logs/${profile}/audit"
    log_default="./logs/${profile}"
  fi

  cat > "$dir/start.sh" <<EOF
#!/usr/bin/env bash
# ${app_name}环境（profile=${profile}）启动脚本
set -euo pipefail
cd "\$(dirname "\$0")"
if [[ -f scop-ftp.env ]]; then
  set -a
  # shellcheck disable=SC1091
  source scop-ftp.env
  set +a
fi
JAVA_BIN="java"
if [[ -n "\${JAVA_HOME:-}" ]]; then JAVA_BIN="\$JAVA_HOME/bin/java"; fi
PASSTHROUGH=()
while [[ \$# -gt 0 ]]; do
  case "\$1" in
    --app-key)
      [[ \$# -ge 2 ]] || { echo "--app-key 缺少值" >&2; exit 2; }
      export UPLOAD_APP_KEY="\$2"; shift 2 ;;
    --app-secret)
      [[ \$# -ge 2 ]] || { echo "--app-secret 缺少值" >&2; exit 2; }
      export UPLOAD_APP_SECRET="\$2"; shift 2 ;;
    *) PASSTHROUGH+=("\$1"); shift ;;
  esac
done
if [[ \${#PASSTHROUGH[@]} -gt 0 ]]; then
  exec "\$JAVA_BIN" \${JAVA_OPTS:--Xms256m -Xmx512m} -jar ScopeFTP-server-${profile}.jar ${profile_arg} "\${PASSTHROUGH[@]}"
fi
exec "\$JAVA_BIN" \${JAVA_OPTS:--Xms256m -Xmx512m} -jar ScopeFTP-server-${profile}.jar ${profile_arg}
EOF
  chmod +x "$dir/start.sh"

  if [[ "${profile}" == "default" ]]; then
    storage_env_line="UPLOAD_STORAGE_ROOTS=default=./data"
  else
    storage_env_line="UPLOAD_STORAGE_ROOT=${storage_default}"
  fi

  cat > "$dir/scop-ftp.env.example" <<EOF
# ===== ${app_name}环境（profile=${profile}）配置模板 =====
# 复制为 scop-ftp.env 后修改；start.sh 会自动加载。
# 生产必须替换 UPLOAD_APP_SECRET（至少 32 字节）：
#   openssl rand -hex 32

UPLOAD_APP_KEY=demo-app
UPLOAD_APP_SECRET=请替换为至少32字节的随机密钥

SERVER_PORT=8080
SERVER_ADDRESS=0.0.0.0

# 存储根（目录不存在会自动创建，需可写）
${storage_env_line}

# 操作审计日志目录（JSONL 按天 + audit.sqlite3，客户端可查）
UPLOAD_AUDIT_ENABLED=true
UPLOAD_AUDIT_DIR=${audit_default}
UPLOAD_AUDIT_MAX_HISTORY_DAYS=90

# 服务端运行日志
UPLOAD_LOG_DIR=${log_default}

UPLOAD_DEVICE_BINDING_ENABLED=true
UPLOAD_TERMINAL_ENABLED=false
UPLOAD_MAX_FILE_SIZE=5368709120
JAVA_OPTS="-Xms256m -Xmx512m"
EOF

  cat > "$dir/README.txt" <<EOF
ScopeFTP-server —— ${app_name}环境（profile=${profile}）

【启动】
  cp scop-ftp.env.example scop-ftp.env
  vim scop-ftp.env          # 至少改 UPLOAD_APP_SECRET
  ./start.sh

【健康检查】
  curl http://127.0.0.1:\${SERVER_PORT:-8080}/api/health

【说明】
  - 本包已内置 application-${profile}.yml；start.sh 会自动激活 profile。
  - 审计日志：${audit_default}/audit-YYYY-MM-DD.jsonl + audit.sqlite3；客户端「操作日志」可查。
  - 数据目录：${storage_default}（内含 .upload-meta/metadata.sqlite3，备份时不要漏）。
  - 升级：替换 jar 后重启；保留 scop-ftp.env 与数据目录。
  - 生产必须使用 HTTPS 反向代理（见 deploy/nginx.conf）。
EOF

  (cd "$OUT" && rm -f "ScopeFTP-server-${profile}.zip" && zip -q -r "ScopeFTP-server-${profile}.zip" "ScopeFTP-server-${profile}")

  echo "==> $dir"
  echo "    产物：$OUT/ScopeFTP-server-${profile}.zip"
done

echo "==> 全部完成：$OUT"

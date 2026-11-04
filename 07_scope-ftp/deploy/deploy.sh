#!/usr/bin/env bash
# ScopeFTP-server —— 构建与部署脚本
#
# 用法：
#   ./deploy/deploy.sh remote                # 上传服务端 Jar（可从 ScopeFTP-Server.zip 解压）并重启 systemd 服务
#   ./deploy/deploy.sh build                 # 可选：本地调试时构建服务端 Jar
#   ./deploy/deploy.sh docker                # 服务器上用 Docker Compose 构建并启动
#
# remote 模式需要的环境变量（可写在 deploy/.env.deploy 中）：
#   SERVER_HOST=1.2.3.4
#   SERVER_USER=root
#   SERVER_PORT=22                 # SSH 端口
#   REMOTE_DIR=/opt/scop-ftp
#   SERVER_PROFILE=xiaoshan        # 环境名（jar 内 application-<env>.yml），默认 xiaoshan
#   APP_PORT=8080                  # 服务端端口，用于部署后的健康检查
#   REMOTE_JAVA_HOME=/opt/jdk-21   # 可选：服务器上要用的 JDK 21 路径（多版本时指定，
#                                  #       不填则用系统的 /usr/bin/java）
#   UPLOAD_APP_KEY=demo-app
#   UPLOAD_APP_SECRET=请改成足够长的随机串
#
# 前置：本地需要能访问服务器（SSH 免密或 ssh-agent）。
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$PROJECT_ROOT/scripts/env.sh"

CMD="${1:-build}"

build_jar() {
  echo "==> 构建服务端 Jar（项目内 Maven 包装器）"
  "$PROJECT_ROOT/scripts/mvn.sh" -f "$PROJECT_ROOT/server/pom.xml" -DskipTests clean package
  JAR="$(ls -1 "$PROJECT_ROOT/server/target"/*.jar | grep -v original | head -n1)"
  echo "==> 构建完成：$JAR"
}

deploy_remote() {
  if [[ -f "$PROJECT_ROOT/deploy/.env.deploy" ]]; then
    # shellcheck disable=SC1091
    source "$PROJECT_ROOT/deploy/.env.deploy"
  fi
  : "${SERVER_HOST:?请设置 SERVER_HOST}"
  : "${SERVER_USER:?请设置 SERVER_USER}"
  : "${REMOTE_DIR:?请设置 REMOTE_DIR}"
  SERVER_PORT="${SERVER_PORT:-22}"
  SERVER_PROFILE="${SERVER_PROFILE:-xiaoshan}"
  APP_PORT="${APP_PORT:-8080}"
  REMOTE_JAVA_HOME="${REMOTE_JAVA_HOME:-}"

  if [[ -n "${JAR_PATH:-}" ]]; then
    JAR="$JAR_PATH"
  else
    JAR="$(ls -1 "$PROJECT_ROOT/server/target/ScopeFTP-server-"*.jar 2>/dev/null | grep -v original | head -n1 || true)"
  fi
  if [[ -z "${JAR:-}" || ! -f "$JAR" ]]; then
    echo "未找到服务端 Jar：请下载 GitHub Actions 的 ScopeFTP-Server-package（ScopeFTP-Server.zip），解压后把 ScopeFTP-Server/ScopeFTP-Server.jar 放到 server/target/，或设置 JAR_PATH=/path/to/ScopeFTP-Server.jar" >&2
    exit 1
  fi
  echo "==> 上传 Jar 到 ${SERVER_USER}@${SERVER_HOST}:${REMOTE_DIR}"
  ssh -p "$SERVER_PORT" "${SERVER_USER}@${SERVER_HOST}" "mkdir -p '${REMOTE_DIR}/data'"
  rsync -avz -e "ssh -p ${SERVER_PORT}" \
    "$JAR" "${SERVER_USER}@${SERVER_HOST}:${REMOTE_DIR}/app.jar.new"

  echo "==> 生成环境文件并重启服务"
  ssh -p "$SERVER_PORT" "${SERVER_USER}@${SERVER_HOST}" bash -s <<EOF
set -euo pipefail
cd '${REMOTE_DIR}'
if [[ -f app.jar ]]; then cp app.jar app.jar.bak; fi
mv app.jar.new app.jar
umask 077
cat > scop-ftp.env <<'ENVEOF'
# 环境配置在 jar 内 application-<env>.yml；这里只放环境选择、JDK 与凭证
SPRING_PROFILES_ACTIVE=${SERVER_PROFILE}
# 服务器有多个 Java 版本时指定 JDK 21 路径；为空则用 /usr/bin/java
JAVA_HOME="${REMOTE_JAVA_HOME}"
UPLOAD_APP_KEY=${UPLOAD_APP_KEY:-demo-app}
UPLOAD_APP_SECRET=${UPLOAD_APP_SECRET}
JAVA_OPTS=-Xms256m -Xmx512m
ENVEOF
chmod 600 scop-ftp.env
sudo systemctl restart scop-ftp || true
sleep 3
curl -fsS -H 'X-Device-Mac: 02:00:00:00:00:01' http://127.0.0.1:${APP_PORT}/api/health || (sudo journalctl -u scop-ftp -n 50 --no-pager; exit 1)
echo
EOF
  echo "==> 部署完成：环境 ${SERVER_PROFILE}，JDK=${REMOTE_JAVA_HOME:-系统默认 /usr/bin/java}，健康检查 http://${SERVER_HOST}:${APP_PORT}/api/health"
}

deploy_docker() {
  echo "==> Docker Compose 构建并启动（在服务器上执行）"
  cd "$PROJECT_ROOT/deploy"
  [[ -f .env ]] || { echo "缺少 deploy/.env，请先 cp deploy/.env.example deploy/.env 并修改"; exit 1; }
  docker compose up -d --build
  docker compose ps
  echo "==> 健康检查：curl -H 'X-Device-Mac: 02:00:00:00:00:01' http://127.0.0.1:${SERVER_PORT:-8080}/api/health"
}

case "$CMD" in
  build) build_jar ;;
  remote) deploy_remote ;;
  docker) deploy_docker ;;
  *) echo "未知命令：$CMD（可用：build | remote | docker）"; exit 2 ;;
esac

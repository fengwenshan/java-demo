#!/usr/bin/env bash
# Maven 包装器：本地固定 JDK 21 + 项目内仓库；CI 使用系统 Maven。
# 用法： ./scripts/mvn.sh -f server/pom.xml test
set -euo pipefail
source "$(dirname "$0")/env.sh"
if [[ -n "${MAVEN_SETTINGS:-}" && -f "$MAVEN_SETTINGS" ]]; then
  exec "$MVN" -s "$MAVEN_SETTINGS" -Dmaven.repo.local="$MAVEN_REPO" -Dfile.encoding=UTF-8 "$@"
fi
exec "$MVN" -Dmaven.repo.local="$MAVEN_REPO" -Dfile.encoding=UTF-8 "$@"

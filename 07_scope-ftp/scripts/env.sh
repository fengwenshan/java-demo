#!/usr/bin/env bash
# 项目构建环境。
#
# - 本地（当前 macOS 沙箱）：优先使用 IntelliJ 自带 JDK/Maven，缓存落到项目 .cache/；
# - GitHub Actions：使用 setup-java 提供的 JAVA_HOME 和 PATH 里的 mvn，不覆盖 CI 变量。
#
# 用法： source scripts/env.sh

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# ---------- Java 21 ----------
LOCAL_JAVA="/Users/fengwenshan/Applications/IntelliJ IDEA Ultimate.app/Contents/jbr/Contents/Home"
if [[ -z "${CI:-}" && -x "$LOCAL_JAVA/bin/java" ]]; then
  export JAVA_HOME="$LOCAL_JAVA"
fi
if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "找不到可用的 JDK 21：请设置 JAVA_HOME（GitHub Actions 由 actions/setup-java 提供）" >&2
  return 1 2>/dev/null || exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"

# ---------- Maven ----------
LOCAL_MAVEN="/Users/fengwenshan/Applications/IntelliJ IDEA Ultimate.app/Contents/plugins/maven/lib/maven3"
if [[ -z "${CI:-}" && -x "$LOCAL_MAVEN/bin/mvn" ]]; then
  export MAVEN_HOME="$LOCAL_MAVEN"
  export MVN="$LOCAL_MAVEN/bin/mvn"
elif command -v mvn >/dev/null 2>&1; then
  export MVN="$(command -v mvn)"
else
  echo "找不到 Maven：请安装 mvn 或设置 MVN" >&2
  return 1 2>/dev/null || exit 1
fi
# 本地用阿里云镜像和项目内仓库；CI 没有这个 settings 文件时使用 setup-java 的 Maven。
if [[ -f "$PROJECT_ROOT/scripts/maven-settings.xml" ]]; then
  export MAVEN_SETTINGS="$PROJECT_ROOT/scripts/maven-settings.xml"
else
  unset MAVEN_SETTINGS
fi
export MAVEN_REPO="${MAVEN_REPO:-$PROJECT_ROOT/.cache/m2/repository}"

# ---------- Rust / Cargo ----------
export CARGO_HOME="${CARGO_HOME:-$PROJECT_ROOT/.cache/cargo}"
export RUSTUP_HOME="${RUSTUP_HOME:-$HOME/.rustup}"
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$PROJECT_ROOT/.cache/cargo-target}"

# ---------- Node / pnpm ----------
export NPM_CONFIG_CACHE="${NPM_CONFIG_CACHE:-$PROJECT_ROOT/.cache/npm}"
export PNPM_STORE_DIR="${PNPM_STORE_DIR:-$PROJECT_ROOT/.cache/pnpm-store}"
export PNPM_HOME="${PNPM_HOME:-$PROJECT_ROOT/.cache/pnpm-home}"

export PROJECT_ROOT

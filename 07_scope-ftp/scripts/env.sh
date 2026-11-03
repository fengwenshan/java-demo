#!/usr/bin/env bash
# 项目构建环境。
#
# 环境配置文件（可选，按优先级从低到高加载，后者覆盖前者）：
#   scripts/env → scripts/env.local → scripts/env.<profile> → scripts/env.local.<profile>
# profile 来源：SCOP_FTP_ENV，或调用脚本参数里的 --env/--profile；
# run-server（--env dev）与 build-server-profiles（xihu）会自动设置。
# 其中文件名含 local 的配置为本机私有（.gitignore 忽略），env[.<profile>] 可按需入库。
#
# JDK 路径来源优先级（从高到低）：
#   1. 当前 shell 显式 export 的 JAVA_HOME
#   2. 上述环境配置文件里的 JAVA_HOME
#   3. 自动探测：macOS `/usr/libexec/java_home -v 21`、Linux 常见 JDK 21 路径、PATH 里的 java 21
#   4. 本机 IntelliJ 自带 JBR（仅 macOS 开发机兜底）
# Maven 不用配置：优先 PATH 里的 mvn，找不到时回退本机 IntelliJ 自带 Maven。
# GitHub Actions：使用 setup-java 提供的 JAVA_HOME 和 PATH 里的 mvn，不覆盖 CI 变量。
#
# 用法： source scripts/env.sh

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# ---------- 环境配置文件（按 profile 从低到高加载） ----------
# 记下 shell 显式传入的 JAVA_HOME，保证 shell > 环境文件。
CLI_JAVA_HOME="${JAVA_HOME:-}"
PROFILE="${SCOP_FTP_ENV:-}"
if [[ -z "$PROFILE" ]]; then
  env_args=("$@")
  for ((i = 0; i < ${#env_args[@]}; i++)); do
    case "${env_args[i]}" in
      --env|--profile) PROFILE="${env_args[i + 1]:-}"; break ;;
      --env=*|--profile=*) PROFILE="${env_args[i]#*=}"; break ;;
    esac
  done
fi
if [[ -n "$PROFILE" && ! "$PROFILE" =~ ^[A-Za-z0-9_-]+$ ]]; then
  echo "警告：环境名 '$PROFILE' 非法，忽略环境配置文件" >&2
  PROFILE=""
fi

# 加载某个 profile 生效的 env 配置文件（只加载，不解析 JDK/Maven）。
# 打包脚本也会调用它，按环境生成包内的 scop-ftp.env。
load_env_config_files() {
  local profile="${1:-}"
  local env_file
  if [[ -n "$profile" && ! "$profile" =~ ^[A-Za-z0-9_-]+$ ]]; then
    echo "警告：环境名 '$profile' 非法，忽略环境配置文件" >&2
    profile=""
  fi

  LOADED_ENV_FILES=()
  for env_file in \
    "$PROJECT_ROOT/scripts/env" \
    "$PROJECT_ROOT/scripts/env.local"; do
    if [[ -f "$env_file" ]]; then
      set -a
      # shellcheck disable=SC1090
      source "$env_file"
      set +a
      LOADED_ENV_FILES+=("$env_file")
    fi
  done
  if [[ -n "$profile" ]]; then
    for env_file in \
      "$PROJECT_ROOT/scripts/env.$profile" \
      "$PROJECT_ROOT/scripts/env.local.$profile"; do
      if [[ -f "$env_file" ]]; then
        set -a
        # shellcheck disable=SC1090
        source "$env_file"
        set +a
        LOADED_ENV_FILES+=("$env_file")
      fi
    done
  fi

  # 记录来自配置文件的原始值（未混入自动探测的 JDK），供打包生成 scop-ftp.env。
  ENV_FILE_JAVA_HOME="${JAVA_HOME:-}"
  ENV_FILE_JAVA_OPTS="${JAVA_OPTS:-}"

  if [[ -n "${SCOP_FTP_VERBOSE_ENV:-}" && ${#LOADED_ENV_FILES[@]} -gt 0 ]]; then
    echo "已加载环境配置：${LOADED_ENV_FILES[*]}" >&2
  fi
}

load_env_config_files "$PROFILE"
FILE_JAVA_HOME="${ENV_FILE_JAVA_HOME}"

# ---------- Java 21 ----------
# 本机 IntelliJ 兜底路径（仅在 macOS 开发机找不到其它 JDK 时使用）。
LOCAL_JAVA="/Users/fengwenshan/Applications/IntelliJ IDEA Ultimate.app/Contents/jbr/Contents/Home"

java_major() {
  "$1/bin/java" -XshowSettings:properties -version 2>&1 \
    | awk -F'= ' '/java.specification.version =/{print $2; exit}'
}

# 返回 0=可用；1=路径不可用；2=版本低于 21
check_java_home() {
  local home="$1" major
  [[ -n "$home" && -x "$home/bin/java" ]] || return 1
  major="$(java_major "$home")"
  [[ "$major" =~ ^[0-9]+$ ]] || return 1
  if (( major < 21 )); then
    return 2
  fi
  return 0
}

detect_java_home() {
  local candidate
  if [[ "$(uname -s)" == "Darwin" && -x /usr/libexec/java_home ]]; then
    candidate="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
    if [[ -n "$candidate" && -x "$candidate/bin/java" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  fi
  for candidate in /usr/lib/jvm/*21*/ /opt/jdk-21 /opt/java-21; do
    candidate="${candidate%/}"
    if [[ -x "$candidate/bin/java" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  if command -v java >/dev/null 2>&1; then
    candidate="$("$(command -v java)" -XshowSettings:properties -version 2>&1 \
      | awk -F'= ' '/java.home =/{print $2; exit}')"
    if [[ -n "$candidate" && -x "$candidate/bin/java" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  fi
  return 1
}

# 候选顺序：shell 显式 > scripts/env.local > 自动探测 > IntelliJ 兜底。
java_candidates=()
[[ -n "$CLI_JAVA_HOME" ]] && java_candidates+=("$CLI_JAVA_HOME")
if [[ -n "$FILE_JAVA_HOME" && "$FILE_JAVA_HOME" != "$CLI_JAVA_HOME" ]]; then
  java_candidates+=("$FILE_JAVA_HOME")
fi

JAVA_HOME_SELECTED=""
java_problems=()
for candidate in ${java_candidates[@]+"${java_candidates[@]}"}; do
  if check_java_home "$candidate"; then
    JAVA_HOME_SELECTED="$candidate"
    break
  else
    if (( $? == 2 )); then
      java_problems+=("JAVA_HOME=$candidate 是 Java $(java_major "$candidate")，需要 JDK 21+")
    else
      java_problems+=("JAVA_HOME=$candidate 不存在或不可执行")
    fi
  fi
done

if [[ -z "$JAVA_HOME_SELECTED" ]] && detected_java="$(detect_java_home)"; then
  if check_java_home "$detected_java"; then
    JAVA_HOME_SELECTED="$detected_java"
  fi
fi
if [[ -z "$JAVA_HOME_SELECTED" && -z "${CI:-}" ]]; then
  if check_java_home "$LOCAL_JAVA"; then
    JAVA_HOME_SELECTED="$LOCAL_JAVA"
  fi
fi
if [[ -z "$JAVA_HOME_SELECTED" ]]; then
  if (( ${#java_problems[@]} > 0 )); then
    for problem in "${java_problems[@]}"; do
      echo "警告：$problem" >&2
    done
  fi
  echo "找不到可用的 JDK 21：请 export JAVA_HOME=/path/to/jdk-21，或写入 scripts/env / scripts/env.local" >&2
  return 1 2>/dev/null || exit 1
fi
export JAVA_HOME="$JAVA_HOME_SELECTED"
export PATH="$JAVA_HOME/bin:$PATH"

# ---------- Maven（不需要配置） ----------
# 优先 PATH 里的 mvn；本机没装 Maven 时回退 IntelliJ 自带 Maven（仅 macOS 开发机）。
LOCAL_MAVEN="/Users/fengwenshan/Applications/IntelliJ IDEA Ultimate.app/Contents/plugins/maven/lib/maven3"
if command -v mvn >/dev/null 2>&1; then
  export MVN="$(command -v mvn)"
elif [[ -z "${CI:-}" && -x "$LOCAL_MAVEN/bin/mvn" ]]; then
  export MAVEN_HOME="$LOCAL_MAVEN"
  export MVN="$LOCAL_MAVEN/bin/mvn"
else
  echo "找不到 Maven：请安装 mvn（或让 IDE 自带 Maven 可用）" >&2
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

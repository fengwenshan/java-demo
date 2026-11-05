#!/bin/sh
# 开发构建（cargo run）启动器：把 target/debug/ScopeFTP 复制成 "ScopeFTP-dev" 再执行。
#
# 只在 macOS 的 cargo run 生效（见 .cargo/config.toml）。macOS 程序坞的悬浮名取自
# 可执行文件名，复制后开发版在程序坞显示 ScopeFTP-dev，和安装版 ScopeFTP 区分开。
# release 二进制和 cargo test 的测试二进制不会命中这里的路径，原样执行。
bin="$1"
shift
case "$bin" in
  */target/debug/ScopeFTP | target/debug/ScopeFTP)
    dir="${TMPDIR:-/tmp}/scopeftp-dev"
    dst="$dir/ScopeFTP-dev"
    mkdir -p "$dir" || exec "$bin" "$@"
    cp -f "$bin" "$dst" || exec "$bin" "$@"
    chmod +x "$dst" || exec "$bin" "$@"
    exec "$dst" "$@"
    ;;
esac
exec "$bin" "$@"

#!/usr/bin/env bash
# 客户端 Rust 代码 ↔ 真实服务端 联调测试（不是 mock）。
# 前置：服务端已启动（默认 http://127.0.0.1:8080），可用 scripts/run-server.sh。
# 注意：客户端 AppKey/AppSecret 已内置（demo-app / demo-secret-… 默认值），
#       服务端请用与之匹配的 UPLOAD_APP_KEY / UPLOAD_APP_SECRET。
# 用法：
#   ./scripts/live-client-test.sh
#   LIVE_E2E_BASE_URL=https://upload.example.com ./scripts/live-client-test.sh
set -euo pipefail
source "$(dirname "$0")/env.sh"

cd "$PROJECT_ROOT/client/src-tauri"
LIVE_E2E_BASE_URL="${LIVE_E2E_BASE_URL:-http://127.0.0.1:8080}" \
LIVE_E2E_DIR="$PROJECT_ROOT/.cache/live-e2e" \
exec cargo test live_client_server_roundtrip -- --nocapture

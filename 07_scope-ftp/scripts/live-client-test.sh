#!/usr/bin/env bash
# 客户端 Rust 代码 ↔ 真实服务端 联调测试（不是 mock）。
# 前置：服务端已启动（默认 http://127.0.0.1:8080），可用 scripts/run-server.sh（默认 dev 环境）。
# 注意：服务端没有内置凭证，必须显式指定，且要与客户端使用的 AppKey/AppSecret 一致。
#       客户端联调测试用的是编译进二进制的内置值（默认 demo-app / demo-secret-…，
#       构建时可用 UPLOAD_CLIENT_APP_KEY / UPLOAD_CLIENT_APP_SECRET 覆盖），所以默认情况下服务端要这样启动：
#       ./scripts/run-server.sh --app-key demo-app --app-secret demo-secret-please-change-32-bytes-min
#       或先在本机客户端「设置」里改好凭证，再用相同值启动服务端。
# 用法：
#   ./scripts/live-client-test.sh
#   LIVE_E2E_BASE_URL=https://upload.example.com ./scripts/live-client-test.sh
set -euo pipefail
source "$(dirname "$0")/env.sh"

cd "$PROJECT_ROOT/client/src-tauri"
LIVE_E2E_BASE_URL="${LIVE_E2E_BASE_URL:-http://127.0.0.1:8080}" \
LIVE_E2E_DIR="$PROJECT_ROOT/.cache/live-e2e" \
exec cargo test live_client_server_roundtrip -- --nocapture

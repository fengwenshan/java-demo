# 部署指南

服务端是一个可执行 Spring Boot Jar，数据都在本地目录（SQLite 元数据/审计 + 上传文件，**无需安装数据库服务**），
所以部署只需要「Jar + 一个可写数据目录」，下面是三种常用方式。

## 0. 前置条件

| 方式 | 服务器要求 |
| --- | --- |
| Docker Compose（推荐） | 安装 Docker 24+ 与 docker compose 插件 |
| Jar + systemd | JRE 21（如 `apt install openjdk-21-jre-headless`）、可选 Nginx |
| 本地开发 | JDK 21 + Maven（本项目已内置 `scripts/mvn.sh`，使用 IDE 自带 JDK/Maven） |

> 生产环境必须使用 HTTPS：客户端的签名头、AppSecret 都依赖传输层保护，
> 否则中间人可以抓包重放（nonce/时间戳只能限制重放窗口，不能防止窃听）。

## 1. 方式 A：Docker Compose（推荐）

```bash
cd 07_scope-ftp/deploy
cp .env.example .env
# 必改：UPLOAD_APP_SECRET 换成 32 字节以上随机串
#      openssl rand -hex 32
vim .env

docker compose up -d --build
docker compose ps
curl http://127.0.0.1:8080/api/health
```

- 数据卷：`deploy/data/`（用户上传的文件/目录 + `.upload-meta/metadata.sqlite3`）；`deploy/logs/`（审计 JSONL/`audit.sqlite3` + 运行日志）
- 首次启动前确保容器用户（uid 1001）可写：`mkdir -p deploy/data deploy/logs && sudo chown -R 1001:1001 deploy/data deploy/logs`
- 升级：`git pull && docker compose up -d --build`，数据与日志不受影响
- 查看日志：`docker compose logs -f server`
- 客户端「设置」里的服务器地址填 `http(s)://你的域名或IP:8080`（前面如果加了 Nginx，就填 Nginx 地址，不带端口）

多存储根推荐直接在服务端配置（`application.yml` 或外部配置的 `app.storage.roots[]`，每项 `id/name/path`，第一个为默认根）：

```yaml
app:
  storage:
    roots:
      - id: work
        name: 工作资料
        path: /data/work
      - id: media
        name: 媒体库
        path: /data/media
```

多根只能通过 `app.storage.roots[]` 配置（见上）或环境变量 `UPLOAD_STORAGE_ROOTS="work=/data/work,media=/data/media"`（省略 `name` 时展示名=id）。**单根 `root-dir` / `UPLOAD_STORAGE_ROOT` 已删除**：`roots` 为空时服务端启动失败。

`deploy/.env` 中的环境变量会覆盖服务端配置：

| 变量 | 说明 | 默认 |
| --- | --- | --- |
| `UPLOAD_APP_KEY` | 客户端 appKey | `demo-app` |
| `UPLOAD_APP_SECRET` | 客户端 appSecret（**必须修改**） | 无，必填 |
| `UPLOAD_DEVICE_BINDING_ENABLED` | 是否启用设备绑定 | 生产建议 `true` |
| `UPLOAD_MAX_FILE_SIZE` | 文件上限（字节，分片上传生效；单次 multipart 请求仍受 200MB/210MB 限制） | `5368709120`（5GB） |
| `UPLOAD_STORAGE_ROOTS` | 多存储根，`id=path` 逗号分隔（覆盖 yaml roots，name=id） | 无（yaml 默认 `default=./data`） |
| `UPLOAD_MAX_EDIT_BYTES` | 在线编辑文本上限（字节） | `2097152`（2 MiB） |
| `UPLOAD_MAX_EXTRACT_ENTRIES` | zip 解压最多 entry 数 | `20000` |
| `UPLOAD_MAX_EXTRACT_TOTAL_BYTES` | zip 解压总字节上限 | `1073741824`（1 GiB） |
| `UPLOAD_MAX_EXTRACT_ENTRY_BYTES` | zip 单文件解压上限 | `209715200`（200 MiB） |
| `UPLOAD_TERMINAL_ENABLED` | 是否启用远程终端（公网/生产建议 `false`） | `true` |
| `UPLOAD_TERMINAL_TIMEOUT_SECONDS` | 终端命令超时（1-60） | `10` |
| `UPLOAD_TERMINAL_MAX_OUTPUT_BYTES` | 终端输出上限（字节） | `262144` |
| `UPLOAD_TERMINAL_MAX_SESSIONS` | 终端并发会话上限 | `50` |
| `UPLOAD_AUDIT_ENABLED` | 是否启用操作审计日志 | `true` |
| `UPLOAD_AUDIT_DIR` | 审计目录：JSONL 按天 + `audit.sqlite3`（不在存储根内） | `./logs/audit` |
| `UPLOAD_AUDIT_MAX_BODY_CHARS` | 请求体脱敏后最大记录字符数 | `4000` |
| `UPLOAD_AUDIT_MAX_HISTORY_DAYS` | 审计日志保留天数 | `90` |
| `UPLOAD_CHUNK_SIZE` | 分片上传的分片大小（字节） | `8388608`（8MB） |
| `UPLOAD_SESSION_TTL_MS` | 分片上传会话有效期（毫秒） | `86400000`（24h） |
| `UPLOAD_RATE_LIMIT_CAPACITY` | 令牌桶容量（大文件分片连续上传需要额度） | `1000` |
| `UPLOAD_RATE_LIMIT_REFILL_PER_SECOND` | 每秒补充令牌数 | `1` |
| `UPLOAD_RATE_LIMIT_UPLOAD_COST` | 单文件上传/分片 init、complete 消耗令牌 | `5` |
| `SERVER_PORT` | 宿主机端口 | `8080` |
| `JAVA_OPTS` | JVM 参数 | `-Xms256m -Xmx512m` |

> 环境变量是否生效取决于服务端 `application.yml` 中的占位符写法，见 `server/src/main/resources/application.yml`。

### 自定义存储目录

用户上传的文件直接存放在**存储根目录**内，由 `app.storage.roots[]` 配置（yaml 默认一个 `default` 根，路径 `./data`）；内部元数据放 `<root>/.upload-meta/`，multipart 临时文件放系统临时目录：

```bash
# Docker：把宿主机数据盘挂到 /app/data，容器内 root 保持 /app/data
# docker-compose.yml 已显式设置 UPLOAD_STORAGE_ROOTS=default=/app/data 并挂载 ./data

# 裸机 systemd：使用独立数据盘
UPLOAD_STORAGE_ROOTS=default=/data/scop-ftp
# 记得在 /etc/systemd/system/scop-ftp.service 的 ReadWritePaths 里加入该目录
```

目录结构为：

```text
<存储根目录>/
├── docs/            用户自己的目录（示例）
├── 报告.pdf          用户上传的文件（原名落盘，重名自动加序号）
└── .upload-meta/    内部元数据（隐藏）：metadata.sqlite3（旧 files.json/devices.json 自动迁移备份）
```

配置多个 `app.storage.roots[]` 时，每个根目录都是这样的结构（各自有 `.upload-meta/`），备份/挂载需要覆盖所有根；yaml 默认只有一个 `default` 根（`./data`）。

不再创建 `files/`、`meta/`、`tmp/` 目录，也**不再自动迁移旧单根布局**：旧 `meta/` 与 `.upload-meta/` 语义不同，升级旧数据需要手动整理（把旧 `files/` 内容放进新根目录、必要时按新 `storedPath` 语义迁移元数据）；旧 `db/` 与用户文件不动。

服务端启动时会解析绝对路径、创建目录并校验可写；不可写会直接启动失败。
**所有文件读写删除都被限制在该目录内**：客户端只能指定相对 root 的已存在目录/路径，metadata 中被篡改成 `../`、绝对路径或指向目录外的符号链接都会被拒绝。

## 2. 方式 B：Jar + systemd + Nginx

适合不想装 Docker 的云服务器（Debian/Ubuntu/CentOS 均可）。

### 2.1 获取构建产物

不再在本地打包。推送到 `main` 或手动触发仓库根目录的 `.github/workflows/scope-ftp.yml`，在 Actions 的 **ScopeFTP-server-jar** / **ScopeFTP-server-profiles** artifact 中下载 `ScopeFTP-server` Jar 或分环境 zip。

### 2.2 上传与安装

```bash
# 服务器上
sudo useradd -r -s /usr/sbin/nologin scopftp
sudo mkdir -p /opt/scop-ftp/data
sudo chown -R scopftp:scopftp /opt/scop-ftp

# 本地（先下载 Actions 的 ScopeFTP-server-jar artifact）
scp ScopeFTP-server-*.jar root@SERVER:/opt/scop-ftp/app.jar
scp deploy/scop-ftp.service root@SERVER:/etc/systemd/system/

# 服务器上，写环境文件（权限 600，含密钥）
sudo tee /opt/scop-ftp/scop-ftp.env >/dev/null <<'EOF'
UPLOAD_APP_KEY=demo-app
UPLOAD_APP_SECRET=请替换为随机长密钥
UPLOAD_DEVICE_BINDING_ENABLED=true
UPLOAD_MAX_FILE_SIZE=209715200
EOF
sudo chmod 600 /opt/scop-ftp/scop-ftp.env
sudo systemctl daemon-reload
sudo systemctl enable --now scop-ftp
curl http://127.0.0.1:8080/api/health
```

### 2.3 生产环境 profile 与私有配置

服务端内置 `default` / `xiaoshan` / `xihu` 三个 profile：

- `application-default.yml`：通用/本地，单根默认 `./data`，审计与运行日志写 `./logs`
- `application-xiaoshan.yml`：存储默认 `/risen/soft`，审计 `./logs/xiaoshan/audit`、运行日志 `./logs/xiaoshan`
- `application-xihu.yml`：同上，审计/日志在 `./logs/xihu`

这三个 `application-*.yml` 是私有环境配置，已加入 `.gitignore`，不会提交。CI 里由 GitHub Secrets 注入后再构建；本地保留自己的副本即可，不要 `git add`。

**GitHub Secrets**（`Settings → Secrets and variables → Actions`）：

| Secret | 内容 |
| --- | --- |
| `APPLICATION_DEFAULT_YML_BASE64` | `base64 -i server/src/main/resources/application-default.yml` 的输出 |
| `APPLICATION_XIAOSHAN_YML_BASE64` | 同上，xiaoshan |
| `APPLICATION_XIHU_YML_BASE64` | 同上，xihu |
| `UPLOAD_CLIENT_APP_KEY` / `UPLOAD_CLIENT_APP_SECRET` | 可选，客户端构建时内置的 AppKey/AppSecret |
| `UPLOAD_CLIENT_SERVER_URL` | 可选，客户端默认服务端地址 |

如果未配置三个 profile Secrets，CI 仍会构建 Jar（测试用例使用 `application-test.yml`），但不会生成分环境部署 zip。配置齐全后会自动生成 `ScopeFTP-server-<profile>.zip`。

服务端启动时也可以临时覆盖密钥（未指定则用内置演示默认值）：

```bash
# 直接启动 Jar：简写参数会被映射到第一个预配发客户端
java -jar ScopeFTP-server-1.0.0.jar --app-key my-key --app-secret my-secret

# 分环境包里的 start.sh 也支持
./start.sh --app-key my-key --app-secret my-secret

# 或环境变量
UPLOAD_APP_KEY=my-key UPLOAD_APP_SECRET=my-secret ./start.sh

# 或完整 Spring 属性
./start.sh --app.security.clients[0].app-key=my-key --app.security.clients[0].app-secret=my-secret
```

### 2.4 Nginx + HTTPS

```bash
sudo cp deploy/nginx.conf /etc/nginx/conf.d/scop-ftp.conf
# 修改 server_name / 证书路径（/etc/nginx/certs/）
sudo nginx -t && sudo systemctl reload nginx
```

证书可用 Let's Encrypt：

```bash
sudo apt install certbot python3-certbot-nginx
sudo certbot --nginx -d upload.example.com
```

### 2.5 一键 remote 部署（可选）

在 `deploy/.env.deploy` 中配置：

```bash
SERVER_HOST=1.2.3.4
SERVER_USER=root
SERVER_PORT=22
REMOTE_DIR=/opt/scop-ftp
UPLOAD_APP_KEY=demo-app
UPLOAD_APP_SECRET=请替换为随机长密钥
UPLOAD_DEVICE_BINDING_ENABLED=true
```

然后：

```bash
./deploy/deploy.sh remote
```

先把 GitHub Actions 下载的 `ScopeFTP-server-*.jar` 放到 `server/target/`，或设置 `JAR_PATH` 指向该文件；脚本会 rsync 到服务器、生成 600 权限环境文件、重启 systemd 并做健康检查，不再本地打包。

## 3. 方式 C：本地开发运行

```bash
cd 07_scope-ftp
source scripts/env.sh
./scripts/mvn.sh -f server/pom.xml spring-boot:run
# 或（首次会构建一次本地 Jar；发布包仍以 Actions 产物为准）
./scripts/run-server.sh
```

客户端开发：

```bash
cd client
pnpm install --store-dir ../.cache/pnpm-store
pnpm tauri dev        # 桌面客户端
pnpm dev              # 仅浏览器预览界面（接口不可用）
```

构建缓存统一放在 `.cache/`。本地只用于开发/测试，不再生成安装包；完整安装包由 GitHub Actions 生成：

```bash
cd client
pnpm install --store-dir ../.cache/pnpm-store
pnpm build
pnpm tauri dev
cd src-tauri && cargo check && cargo test
```

发布产物（macOS DMG / Windows NSIS `.exe` / 服务端 Jar 与分环境 zip）见 Actions 页面下载。

### 真实服务端联调测试

服务端启动后，可让**客户端 Rust 代码**直接打真实接口（验证签名、multipart 上传、下载校验、删除全链路）：

```bash
cd 07_scope-ftp
./scripts/live-client-test.sh
# 输出：健康检查 → 签名校验 → 设备激活 → 上传（中文文件名）→ 列表 → 下载校验 → 删除 → 删除后 404
```

也可以指定远程服务端：

```bash
LIVE_E2E_BASE_URL=https://upload.example.com ./scripts/live-client-test.sh

# 服务端改过密钥时，在客户端「设置」里填同样的 AppKey/AppSecret（留空则用内置默认）。
# 也可以构建时用 UPLOAD_CLIENT_APP_KEY=... UPLOAD_CLIENT_APP_SECRET=... 覆盖默认值。
```

## 4. 部署后验证

```bash
# 1) 健康检查（免签）
curl -s http://127.0.0.1:8080/api/health

# 2) 端到端签名测试（本机执行，默认连 127.0.0.1:8080）
#    扩展用例含分片/续传/搜索，令牌消耗较大；默认容量 1000 一般够用
node scripts/e2e.mjs
# 需要测试「按目录上传」时可显式指定本机存储根：
# E2E_STORAGE_ROOT=/app/data node scripts/e2e.mjs

# 3) 连接远程服务器
BASE_URL=https://upload.example.com \
APP_KEY=demo-app APP_SECRET=你的密钥 \
node scripts/e2e.mjs
```

成功输出示例：

```text
=== 结果：54 通过，0 失败 ===
```

### 本地连接失败（网络错误 / 空响应）

先确认端口到底被谁占用：

```bash
lsof -nP -iTCP:8080 -sTCP:LISTEN
curl -v http://127.0.0.1:8080/api/health
```

macOS 上 Java 监听 IPv6 `*:8080` 时，另一个前端项目的 Vite 可能同时监听 IPv4 `127.0.0.1:8080`：客户端用 `127.0.0.1` 会先连到 Vite，表现为 `error sending request` 或空响应，但服务端日志看起来正常。释放该端口，或换端口启动并同步修改客户端地址：

```bash
SERVER_PORT=8081 ./scripts/run-server.sh
# 客户端「设置 → 服务器地址」改成 http://127.0.0.1:8081
```

### 审计日志查询失败（no such table: audit_log）

如果服务运行期间删除/替换了 `logs/audit/audit.sqlite3`，SQLite 会新建一个空文件，但不会自动建表；旧实例查询时会报 `SQLITE_ERROR: no such table: audit_log`，接口返回「服务器内部错误：审计日志查询失败」。

- 新版 `AuditStore` 检测到该错误会自动重建 schema 并重试；重启服务也可恢复。
- 清空审计数据建议：停止服务 → 删除 `logs/audit/` 下 `audit-*.jsonl` 和 `audit.sqlite3` → 重新启动。不要在服务运行中只删 SQLite 文件。
- 客户端请求失败统一走顶部全局 `message` 提示，日志/目录加载失败不再在页面内嵌错误 Alert。

## 5. 运维要点

- **备份**：定期备份所有存储根目录（用户文件 + 隐藏的 `.upload-meta/metadata.sqlite3`）与 `UPLOAD_AUDIT_DIR`（JSONL + `audit.sqlite3`）。SQLite 运行中直接 `cp` 可能丢 WAL，建议 `sqlite3 metadata.sqlite3 ".backup backup.sqlite3"`（审计库同理），或停服后整目录备份。
- **磁盘**：上传文件不自动清理，建议加 cron 清理或接入对象存储（后续可替换 `FileStorageService`）。
- **日志**：默认 stdout + `UPLOAD_LOG_DIR/server.log`；操作审计默认开启，JSONL 与 `audit.sqlite3` 都在 `UPLOAD_AUDIT_DIR`（默认 `./logs/audit`），记录客户端全部 `/api/**` 操作（含失败），JSONL 按天、默认保留 90 天，SQLite 同步清理；字段含 requestId/action/path/参数/耗时/状态，敏感字段脱敏；客户端日志抽屉把操作与连接日志合并展示，可按类型/操作/时间筛选。
- **密钥轮换**：修改 `UPLOAD_APP_SECRET` 后重启服务，同时更新客户端「设置」里的 AppSecret；旧密钥立即失效。
- **端口**：仅暴露 Nginx 的 443；8080 不要直接暴露公网（云安全组限制内网）。
- **健康检查**：`GET /api/health` 免签，可接入负载均衡/监控。
- **时区**：签名时间戳是 epoch 毫秒，与服务器时区无关；只要客户端时钟准确即可。

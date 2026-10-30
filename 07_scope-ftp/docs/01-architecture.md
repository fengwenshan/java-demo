# ScopeFTP — 架构说明

一个演示级、但按生产思路实现的「Spring Boot 文件服务器 + Tauri 桌面客户端」项目。
客户端负责选择文件并上传/下载/管理，服务端负责存储与元数据管理；
所有业务接口都要求**请求签名 + 时间戳 + 一次性随机数**，用于防止第三方直接调用接口（防伪验证）。

## 1. 组成

```
07_scope-ftp/
├── server/                 Spring Boot 3.3.5 + Java 21 服务端（Maven）
├── client/                 Tauri 2 + React 18 + TypeScript + Vite + Ant Design 5 桌面客户端
├── deploy/                 Dockerfile、docker-compose、nginx、systemd、部署脚本
├── docs/                   架构 / 接口契约 / 安全设计 / 部署文档
├── scripts/                本地环境、构建、端到端测试脚本
└── .cache/                 项目内构建缓存（gitignore）
```

### 服务端

- Java 21，Spring Boot 3.3.5（与仓库其它模块保持一致），Maven 构建
- 持久化：**SQLite 本地库（无需数据库服务）**。元数据存 `<root>/.upload-meta/metadata.sqlite3`（隐藏目录，WAL/事务；启动加载进内存索引、写成功替换快照）；审计日志双写 `<UPLOAD_AUDIT_DIR>/audit.sqlite3` + JSONL；分片上传会话存系统临时目录 `${java.io.tmpdir}/scop-ftp-sessions/`（24h 过期清理）
- 存储根目录：**只支持多个存储根** `app.storage.roots[]`（每项 `{id,name,path}`，第一个为默认根；yaml 默认一个 `default` 根指向 `./data`，可用 `UPLOAD_STORAGE_ROOTS="work=/a,media=/b"` 覆盖；roots 为空启动失败，单根 `root-dir` 已删除）。启动时解析绝对路径、创建并校验可写；**每个根只放用户文件/目录**，内部元数据在各根 `.upload-meta/`，multipart 临时文件放系统临时目录；**所有文件操作只在请求指定的 root 内**（拒绝绝对路径、`..`、符号链接逃逸、跨根访问）
- 文件存储（FTP 式）：上传文件直接写入用户当前浏览的目录并保留原始文件名，重名自动加 ` (1)` 序号；不再使用 `files/yyyy/MM/dd/UUID` 布局
- 元数据：文件名、大小、类型、SHA-256、说明、appKey、deviceId、上传时间；持久化在 `<root>/.upload-meta/metadata.sqlite3`（SQLite，WAL/事务/索引），启动加载为内存只读快照
- 安全：`SignatureFilter` 统一校验所有 `/api/**` 业务接口（`/api/health` 除外），
  校验 appKey、时间戳窗口、HMAC-SHA256 签名、nonce 防重放、可选的设备绑定、令牌桶限流
- 端口：8080，上下文路径：`/api`

### 客户端

- Tauri 2（Rust）+ React 18 + TypeScript + Vite 6 + Ant Design 5（中文语言包）
- 所有网络请求、HMAC 签名、文件读写都在 **Rust 侧**完成，WebView 层拿不到 appSecret
- **AppKey / AppSecret 默认内置在客户端**（`src-tauri/src/builtin.rs`，构建时可用 `UPLOAD_CLIENT_APP_KEY` / `UPLOAD_CLIENT_APP_SECRET` 覆盖）；设置页可按服务器填写自定义 AppKey/AppSecret，留空使用内置值
- 主界面为左右两栏（FTP 式）：左侧是当前连接的服务器信息（状态/地址/设备/存储目录/大小上限 + 验证连接/设置），右侧是服务器目录浏览器
- 右侧目录浏览器支持拖拽上传：把文件（可多选）拖进右侧任意位置即上传到**当前浏览目录**，并复用 `upload-progress` 显示进度；文件行可下载/删除，目录行点击进入
- 大文件（>32MB）自动分片上传（8MB/片）并支持断点续传；右侧搜索框可在存储根目录内递归搜索，点结果跳到所在目录并高亮
- 左侧支持保存多台服务器（添加/切换/编辑），每台独立 deviceId，当前 active server 决定所有请求目标
- 审计：`SignatureFilter` 对每个 `/api/**` 请求生成 `X-Request-Id`，异步写 JSONL + SQLite（`app.audit.*`），成功/失败/签名失败/限流均记录，敏感字段脱敏；日志目录默认 `./logs/audit`，不在存储根内；客户端用 `GET /api/audit/logs?category=both` 合并查看操作/连接日志（也可用 `operation` / `connection` 单独筛选）
- 底部远程终端面板：签名 `POST /api/terminal/exec`，服务端执行 `/bin/sh -lc`，会话 cwd 持久；终端只能访问 `app.storage.roots` 内目录（TerminalScope + TerminalPathGuard 拦截越界 cwd/参数/重定向/symlink），高危命令由 TerminalGuard 拦截并弹警告；**护栏不是沙箱**，可用 `UPLOAD_TERMINAL_ENABLED=false` 关闭
- 目录列表为 Finder 式（名称/修改日期/大小/种类），单击选中、↑↓ 移动、Enter/再点重命名、双击文件打开、双击目录进入；下载/删除等走右键菜单
- 左侧「存储目录」在配置了**多个**存储根时显示（来自 `GET /api/storage/roots`），切换后右侧浏览/上传/下载/删除/搜索都限定在该根内；只有一个根时隐藏（根名已显示在面包屑）
- 目录表格行支持右键菜单：重命名（文件/目录，单段名字、不覆盖）、删除（文件/空目录）；重命名走 `POST /api/files/rename`，元数据同步
- VSCode 式快速查找：⌘P/Ctrl+P 打开浮层，调 `GET /api/files/search` 同时搜文件与文件夹，键盘导航后跳转/进入
- 设置页从左侧进入，点左侧服务器即可回到目录，不在内容区重复放「返回目录」；不再有「上传」「文件」顶部 Tab（上传页与已上传文件列表已移除，`list_files` API 仍保留）
- 上传使用 `reqwest` multipart 流式上传，进度通过 Tauri 事件回传前端

## 2. 安全模型（防伪验证）

第三方拿到接口地址也无法调用，因为每个请求都必须使用预先配发的 `appSecret` 计算签名：

```
签名头：X-App-Key / X-Timestamp / X-Nonce / X-Signature（/上传时 X-Content-Sha256、可选 X-Device-Id）
签名串：v1\nappKey\ntimestamp\nnonce\nMETHOD\npath\ncanonicalQuery\npayloadHash
签名值：hex(HMAC-SHA256(appSecret, 签名串))
```

服务端校验顺序：appKey 合法 → 时间戳在 ±5 分钟内 → 签名正确 → nonce 未使用过 →
（可选）设备绑定匹配 → 未超限流 → 业务处理。详细规则见 `02-api-contract.md`。

补充加固措施（详见 `04-security.md`）：

- 时间戳窗口 + nonce 缓存（Caffeine，10 分钟），阻断重放
- 上传时 `X-Content-Sha256` 参与签名，落盘后再校验一次文件哈希，防篡改
- 设备绑定（可开关）：激活后该 appKey 只允许绑定的 deviceId 调用
- 令牌桶限流（按 appKey + IP）
- 单次 multipart 请求 200MB、请求体大小限制（大文件走分片上传，分片与文件上限默认 5GB）、原始文件名清洗（**不再限制扩展名，任意类型可上传**）
- 生产部署强制 HTTPS（客户端与 nginx 配置中说明）；服务端 appSecret 通过 `UPLOAD_APP_SECRET` 或启动 Jar 时的 `--app-secret` 注入，不写死在镜像里；客户端内置值需与服务端一致，也可在「设置」中按服务器填自定义 AppKey/AppSecret

> 说明：客户端是分发给终端用户的程序，任何内嵌密钥都有被逆向提取的可能。
> 本项目的做法是把密钥放在 Rust 侧、配合设备绑定、限流与密钥轮换，属于业界常见的「提高攻击成本」方案；
> 若要更强的客户端可信，需要接入代码签名/证书固定/服务端风险风控，文档中会说明边界。

## 3. 接口一览

| 方法 | 路径 | 说明 | 是否需要签名 |
| --- | --- | --- | --- |
| GET | `/api/health` | 健康检查 | 否 |
| GET | `/api/auth/verify` | 校验签名、返回客户端信息 | 是 |
| POST | `/api/auth/activate` | 设备激活/绑定 | 是 |
| GET | `/api/files` | 分页查询文件列表 | 是 |
| POST | `/api/files/upload` | 上传单个文件（multipart） | 是 |
| GET | `/api/files/{id}/download` | 按 id 下载文件 | 是 |
| DELETE | `/api/files/{id}` | 按 id 删除文件 | 是 |
| GET | `/api/files/dir?path=` | 浏览存储根目录下的真实目录内容（隐藏 `.upload-meta`） | 是 |
| GET | `/api/files/download?path=` | 按相对路径下载 root 内任意文件 | 是 |
| DELETE | `/api/files?path=` | 按相对路径删除 root 内文件或空目录 | 是 |
| POST | `/api/files/upload/init` | 分片上传：创建会话（返回 uploadId/chunkSize） | 是 |
| PUT | `/api/files/upload/{uploadId}/chunk?index=` | 分片上传：上传一片（裸字节 + X-Content-Sha256） | 是 |
| GET | `/api/files/upload/{uploadId}` | 分片上传：查询已收分片（续传） | 是 |
| POST | `/api/files/upload/{uploadId}/complete` | 分片上传：校验并拼装落盘 | 是 |
| DELETE | `/api/files/upload/{uploadId}` | 分片上传：取消并清理会话 | 是 |
| GET | `/api/files/search?root=&keyword=&limit=` | 在指定存储根内递归搜索文件名 | 是 |
| POST | `/api/files/rename` | 重命名文件/目录（JSON，root 隔离） | 是 |
| GET | `/api/files/content` | 读取文本文件内容（在线编辑，≤2MiB） | 是 |
| PUT | `/api/files/content` | 覆盖保存文本文件（原子写 + 可选 sha 并发校验） | 是 |
| POST | `/api/files/extract` | 解压 .zip 到同级目录（zip-slip/symlink/bomb 防护，单层包裹自动剥层） | 是 |
| POST | `/api/terminal/exec` | 远程终端执行 shell 命令（高危拦截、会话 cwd、超时/截断） | 是 |
| POST | `/api/files/copy` | 原地复制文件/目录，`_副本` 命名，不覆盖 | 是 |
| POST | `/api/files/move` | 移动文件/目录到指定目录，可跨存储根 | 是 |
| GET | `/api/storage/roots` | 列出配置的存储根（id/name/path） | 是 |
| GET | `/api/audit/logs` | 查询当前 appKey 的操作审计日志（SQLite，分页/筛选） | 是 |

## 4. 本地运行

```bash
# 后端（项目内 Maven 包装器）
./scripts/mvn.sh -f server/pom.xml spring-boot:run

# 前端（浏览器预览，接口不可用，仅看界面）
cd client && pnpm install --store-dir ../.cache/pnpm-store && pnpm dev

# 桌面客户端（需要 Rust 工具链）
cd client && pnpm tauri dev

# 端到端签名自测（服务端需已启动）
node scripts/e2e.mjs
```

> 本项目所有构建缓存都在 `.cache/` 内（受沙箱限制，用户目录不可写）。
> 构建前先 `source scripts/env.sh`，或直接使用 `scripts/mvn.sh`。

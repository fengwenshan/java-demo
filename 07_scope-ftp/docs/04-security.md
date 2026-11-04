# 安全设计说明（防伪验证 / 防第三方调用）

> 目标：接口地址本身不是秘密，但**没有 appSecret 就无法伪造请求**；
> 即使抓到一次请求也无法重放；上传内容不可被中间篡改。

## 1. 威胁模型

| # | 威胁 | 应对措施 | 实现位置 |
| --- | --- | --- | --- |
| T1 | 第三方直接调用接口上传/列取/下载/删除文件 | 每个请求 HMAC-SHA256 签名，服务端持有 appSecret 校验 | `SignatureFilter` + `SignatureVerifier` |
| T2 | 抓包后重放请求 | 时间戳窗口 ±5 分钟 + nonce 一次性缓存（10 分钟） | `NonceCache` |
| T3 | 篡改上传文件内容 / 文件与元数据不一致 | 文件 SHA-256 参与签名，落盘后服务端复算比对 | `FileStorageService` |
| T4 | 拿到客户端后复制 appSecret 给第三方使用 | 设备绑定（激活后该 appKey 只认绑定的 deviceId）+ 限流 | `DeviceService` + `RateLimiter` |
| T5 | 暴力调用 / 资源耗尽（大文件、海量请求） | 令牌桶限流（按 appKey+IP）、文件上限（分片 5GB / 单请求 200MB）、分片大小与扫描上限（搜索 50000 条目） | `RateLimiter` + `application.yml` |
| T6 | 路径穿越、恶意文件名、越界读写 | 存储根目录唯一配置 + 严格目录约束（拒绝绝对路径/`..`/symlink 逃逸）；原始文件名只作元数据展示并清洗；磁盘文件名用 UUID | `StoragePaths` + `FileStorageService` |
| T7 | 上传可执行文件后诱导下载 | 不限制上传类型（业务要求），但下载固定 `Content-Disposition: attachment`、`X-Content-Type-Options: nosniff`，只存储不执行；生产建议接入文件内容扫描 | `FileStorageService` + Controller |
| T8 | 时钟漂移导致合法请求被拒 | `verify` 返回 `serverTime`，客户端可提示校准 | `/api/auth/verify` |
| T9 | 伪造客户端身份、跳过验证直接调用 activate、或换机绕过设备绑定 | 所有 `/api/**`（含 health）强制 `X-Device-Mac`；`client_blacklist_ip` / `client_blacklist_mac` 两张表任一命中即拦截；`activate` 前必须有同 MAC+IP 的近期 `verify`，否则按乱序封禁 | `ClientGuardFilter` + `ClientGuardService` |
| T10 | 把 A 当开放代理、SSRF 打内网/云元数据、下游篡改路径或 body | relay 默认关闭；目标地址只允许 http/https、禁止 userinfo/query/fragment，默认拒绝 loopback/link-local/云元数据，私网可配置放行，可选 `allowed-hosts`；每跳独立签名绑定 method/path/query/payloadHash，A 改路径/body 会让下游验签失败；链头不含 secret | `RelayForwardFilter` + `RelayUrlGuard` + `RelayChainCodec` |

## 2. 已实现的防护细节

### 2.1 请求签名（核心）

```
X-Signature = HMAC-SHA256(appSecret, canonical)
canonical   = v1 \n appKey \n timestamp \n nonce \n METHOD \n path \n canonicalQuery \n payloadHash
```

- 服务端用**常量时间比较**（`MessageDigest.isEqual`），避免时序侧信道
- appSecret 只保存在服务端配置与客户端 Rust 侧，不进 WebView、不进日志
- nonce 只在签名校验通过后才写入缓存，避免攻击者用无效签名污染缓存
- 校验顺序固定（开启身份强制时）：客户端身份（IP/MAC 黑名单，见 2.8）→ appKey → 时间戳 → 签名 → nonce → 设备绑定 → 限流 → activate 顺序校验

### 2.2 防重放

- 时间戳必须在本机时间 ±5 分钟内（可配 `app.security.timestamp-window-ms`）
- nonce 缓存 10 分钟（可配 `app.security.nonce-ttl-ms`），窗口内重复即拒绝
- 客户端每次请求都生成新的 UUID nonce

### 2.3 上传完整性

- 客户端上传前先算文件 SHA-256，放入 `X-Content-Sha256` 并让它参与签名
- 服务端解析 multipart 落盘后再算一次 SHA-256，与请求头不一致 → 40001 并删除文件
- 下载响应返回 `X-Content-Sha256`，客户端流式写入后校验，不一致删除文件并报错

### 2.4 设备绑定

- 客户端首次启动生成 UUID 设备号并持久化
- 设置页「验证连接」成功后自动把 appKey 与 deviceId 绑定（服务端 `device_binding` 表；不再单独提供「激活设备」按钮）
- 开启 `DEVICE_BINDING_ENABLED=true` 后，文件类接口要求 `X-Device-Id` 与绑定值一致，否则 403
- 处理换机：服务端清除绑定记录后重新激活，或临时关闭绑定开关
- 设备绑定只作用于 `/api/files/**`；需要“整台机器级”拦截时开启 2.8 的客户端身份强制校验（MAC/IP 黑名单，覆盖全部 `/api/**`）

### 2.5 限流

- 令牌桶按 `appKey + 客户端 IP` 维度，默认容量 60、每秒补 1；上传一次消耗 5 个令牌
- 客户端 IP 取 `request.getRemoteAddr()`（当前实现不信任 `X-Forwarded-For`，避免伪造 IP 绕过限流）；部署在 Nginx 后时同一反代 IP 会共享桶，生产可启用 Nginx `real_ip` 并在服务端配置可信代理
- 返回 42900 / HTTP 429，客户端提示「请求过于频繁」

### 2.6 存储与文件安全

- 磁盘文件名：直接使用清洗后的原始文件名（FTP 式，按当前浏览目录落盘），重名自动加 ` (1)` 序号；原始展示名另存元数据
- 文件名清洗：去除 `/`、`\`、控制字符，长度截断 255
- **不限制扩展名**：任意类型（含无扩展名）都可上传；服务端只存储不执行，下载固定为附件
- 单次 multipart 请求 200MB/210MB（Nginx 同步 210m）；分片上传文件上限默认 5GB（`app.storage.max-file-size`），每片 ≤ 8MB
- 分片会话放系统临时目录：`uploadId` 强制 UUID、会话元数据校验 size/sha256、分片落盘前复算 SHA-256、complete 前校验齐全与总大小，成功/失败/取消后清理，启动清理 24h 过期会话
- 下载固定 `Content-Disposition: attachment`，避免浏览器内联执行

### 2.7 存储根目录约束（只能对该目录操作）

- 配置项 `app.storage.roots[]`（每项 `{id,name,path}`，第一个为默认根；写在各环境文件 `application-<env>.yml` 里，如 dev 是 `./data`），启动时逐个解析成绝对路径、创建并校验可写；`roots` 为空时启动失败（不再有单根回退）
- 用户文件直接位于 `<root>` 下（可分子目录），内部元数据在 `<root>/.upload-meta/`，临时文件在系统临时目录；客户端只能指定相对 root 的已存在目录/路径
- 所有读写删除都会做路径校验：只接受相对路径，拒绝绝对路径和 `..` 段，解析后必须落在 root 内，并拒绝内部目录 `.upload-meta`；
  对已存在路径用 `toRealPath` 校验，父目录是符号链接且真实路径指向 root 外时同样拒绝
- 元数据文件被人工篡改（例如把 `storedPath` 改成 `../../etc/passwd`）也只会得到明确错误，不会读到/删到目录外文件
- 目录浏览（契约 3.8）与递归搜索（契约 3.16）受根目录约束：只能列出/搜索 `<root>` 下的条目，隐藏 `.upload-meta`，跳过符号链接；搜索限制扫描 50000 条目/深度 20，避免超大目录树拖垮服务
- 分片上传（契约 3.11~3.15）路径规则同单文件上传：`dir` 必须是 root 内已存在目录，拒绝越界/内部目录；`uploadId` 仅接受 UUID，避免会话目录路径注入
- **远程终端**：签名接口在服务器上执行命令；**目录范围**：默认 cwd=第一个存储根，`root` 决定首次会话根，`cd`/路径参数/重定向必须落在 `app.storage.roots` 内（TerminalScope + TerminalPathGuard 做 normalize+realpath 校验，拒绝 `..`、symlink、`/etc`、`/tmp`、`~` 等），越界返回中文原因且不启动进程；`TerminalGuard` 集中规则拦截常见高危操作（递归删除根/家目录、磁盘格式化、关机重启、提权、fork bomb、防火墙/内核/挂载、`curl | sh` 等），命中返回中文原因且不启动进程；会话 30 分钟 TTL、最多 50 个、按 appKey 隔离；超时杀进程树、输出限长；**护栏不是沙箱，无法防御刻意绕过**（如脚本内部访问根外文件），公网/多人环境应在对应环境文件里设 `app.terminal.enabled: false`
- **在线编辑**：读接口严格 UTF-8、上限 2 MiB；保存仅覆盖已存在普通文件，可选 `expectedSha256` 并发校验（不一致不写），同目录临时文件 + `ATOMIC_MOVE` 原子覆盖，元数据 sha/size 同步；路径同样受 root 隔离约束
- **zip 解压**：拒绝绝对路径/`..`/反斜杠/NUL/`.upload-meta`/symlink entry/加密包/同名重复 entry；entry 数、总字节、单 entry 字节上限防 zip bomb；任意失败回滚本次创建的目录；目标目录重名追加序号、不覆盖；zip64 暂不支持
- **重命名/删除目录**：`rename` 的 `newName` 限定单段名字，拒绝路径分隔符/控制字符/`.`/`..`/内部目录；目标已存在（含仅大小写差异）不覆盖；目录重命名同样走 `StoragePaths` 校验且拒绝搬动包含其他已配置根的目录。删除目录支持两种模式：默认（`recursive` 缺省或 `false`）仅允许空目录，非空目录 40000 且内容保留；`recursive=true` 时服务端在 `StoragePaths` 校验后一次性递归删除目录及内容，**不跟随 symlink**（只删除符号链接本身，不进入链接目标），并同步清理该目录下文件的元数据；若目录树内包含另一个已配置的存储根则整体拒绝（40000，不删除任何内容）；删除中途 IO 失败返回 50000「目录删除失败（部分内容可能已删除）」，此时可能已有部分内容被删且不可恢复。客户端删除目录前先列目录判断是否非空，非空时强提示「目录非空（共 N 项），其中的文件和子目录会一并删除，且不可恢复」并二次确认，空目录与文件则走普通确认（同样提示不可恢复）；确认后目录统一带 `recursive=true`（空目录也带，避免嵌套根下隐藏的 `.upload-meta` 造成非空误判）；元数据与磁盘改名后同步，写元数据失败会尝试回滚磁盘改名
- **多存储根隔离**：每个 root 有独立 `StoragePaths`，路径解析限定在请求指定的 root 内；跨根路径（`../其他根`、绝对路径、symlink→其他根）在解析阶段拒绝（40000），不会读到/删到该根外文件；按文件 id 删除时使用记录里的 `rootId`，`rootId` 未配置或记录与根不一致返回 50000 并拒绝删除；`.upload-meta` 任何层级都隐藏/拒绝（防嵌套根元数据被上层根访问）
- 客户端断点续传状态存本地 `uploads.json`（0600，仅路径/大小/mtime/sha256/uploadId，不含文件内容与密钥），启动清理超过 24h 的记录；服务端会话失效时客户端会重新 init 全量重传
- 路径下载/删除接口（契约 3.9/3.10）同样只接受 root 内相对路径且拒绝 `.upload-meta`；删除支持文件与目录：文件直接删，目录默认仅限空目录，非空目录必须显式 `recursive=true` 才递归删除（仍限定在该 root 的真实路径内，不跟随 symlink）
- **已知边界**：存储根目录是共享文件系统视图，路径式下载/删除不按 appKey 隔离（多租户需另做设计）；能通过签名的客户端可删除 root 内任意文件与目录（含用 `recursive=true` 递归删除非空目录，不可恢复），生产环境应配合设备绑定、最小权限账号与审计
- 生产建议：root 指向独立数据盘，权限只给运行用户，`data/` 不与其他服务共用

### 2.8 客户端身份强制校验（MAC/IP 黑名单 + 调用顺序）

默认关闭（`application.yml` 的 `app.security.client-identity-enforced: false`）；`application-dev.yml`、萧山/西湖生产环境文件已开启。

- **身份来源**：`IP = request.getRemoteAddr()`；`MAC = X-Device-Mac`（桌面客户端启动时读取本机网卡，每个请求都带，服务端规范化为 `AA:BB:CC:DD:EE:FF`）。
  HTTP 层拿不到客户端 MAC，只能由客户端自报；当前不解析 `X-Forwarded-For`，Nginx 反代后所有客户端会共享代理 IP。
- **所有 `/api/**`（含 `/api/health`）** 都先经过 `ClientGuardFilter`（最高优先级，在签名过滤器与 health 跳过之前）：
  - IP 或 MAC 缺失/非法 → `40312`（缺少设备标识），并把能拿到的 IP/MAC 写入黑名单；
  - 命中 `client_blacklist_ip` → `40310`；命中 `client_blacklist_mac` → `40311`（**任一条命中即拦截**）；
  - 黑名单**实时查 SQLite**，手动删行后立即生效，不需要重启服务。
- **调用顺序**：`activate` 必须在该 MAC+IP 最近一次成功 `verify` 之后 `app.security.verify-activate-window-ms`（默认 10 分钟）内调用；否则视为乱序 → `40313`，并把 MAC 和 IP 都写入黑名单。文件/终端等其它接口不检查顺序。
- **两张黑名单表 + verify 状态表**（默认存储根 `<root>/.upload-meta/metadata.sqlite3`，全局生效）：
  - `client_blacklist_ip(ip PRIMARY KEY, reason, created_at)`
  - `client_blacklist_mac(mac PRIMARY KEY, reason, created_at)`
  - `client_verify_state(mac, ip, app_key, device_id, verified_at, PRIMARY KEY(mac, ip))`
- **手动解封**（只删一张表时，另一条命中仍会拦截，所以要把命中的行都删掉）：

```bash
DB=<默认存储根>/.upload-meta/metadata.sqlite3
sqlite3 "$DB" "DELETE FROM client_blacklist_ip  WHERE ip='1.2.3.4';"
sqlite3 "$DB" "DELETE FROM client_blacklist_mac WHERE mac='AA:BB:CC:DD:EE:FF';"
```

- **部署注意**：
  - 健康检查/探活/监控请求也必须带 `X-Device-Mac`（`deploy/docker-compose.yml` 的 healthcheck 已带固定值），否则会被当成缺身份客户端封禁；封禁后 health 也返回 403。
  - 旧版客户端（不带 MAC）在开启开关后第一条请求就会被封；请先发布新版客户端，再重启服务端。
  - 测试 profile 显式关闭（历史单测不带 MAC）；黑名单/顺序校验由 `ClientIdentityGuardIntegrationTest` 单独开启验证。
  - e2e 统一使用固定 `02:00:00:00:00:01`（见 `scripts/e2e.mjs`），不依赖运行机器真实网卡。
- **风险**：`X-Device-Mac` 目前**不参与签名**，可被伪造（换 MAC 可绕过 MAC 黑名单，但 IP 黑名单仍生效）；MAC 是客户端自报，不能当作强身份；IP 黑名单会误伤 NAT/反代后的其他客户端。需要更强绑定时，应把 MAC 纳入签名串（契约升级）。

### 2.9 部署层

- 生产强制 HTTPS（Nginx 配置 + 文档），避免签名头与文件内容在公网明文传输
- `UPLOAD_APP_SECRET` 通过环境变量/权限 600 的环境文件注入，不写进镜像、不提交仓库
- 容器以非 root 用户运行；systemd 单元带 `NoNewPrivileges` / `ProtectSystem=strict` 等加固
- 8080 只监听内网，公网只开 Nginx 443

### 2.10 多级转发安全

- **无 secret 下行**：`X-Relay-Chain` 每项只有 `url/name/appKey/timestamp/nonce/signature/deviceId`，B/C 的 AppSecret 只存在客户端本地；A 即使被攻破也拿不到 B/C 密钥。
- **每跳验签**：A 的 `SignatureFilter` 用 A secret 验外层请求；B/C 用自己 secret 验对应链项签名。non-last hop 签 `/api/relay` + 原 path，last hop 签原 path；`canonicalQuery`/`payloadHash` 全链相同，A 改 query/body/path 会导致 B/C 验签失败（40010/40100）。
- **链头一致性**：A 要求链第 0 跳的 `appKey/timestamp/nonce/signature/deviceId` 与外层标准签名头完全一致；同一链 appKey 不得重复；链头长度/跳数/字段长度有上限（40010）。
- **来源链（P1）**：`X-Relay-Via` 由上一跳追加，B 审计 `[A]`、C 审计 `[A,B]`；它不是密码学证明，持有 A 凭据的客户端可伪造既往来源，**P1 依赖网络隔离（B/C 只允许 A 访问）**；P2 可给 Via 加逐跳签名/MAC。
- **SSRF**：目标 URL 只允许 http/https，禁止 userinfo/query/fragment；DNS 解析后拒绝 `0.0.0.0`、multicast、loopback（`allow-loopback` 默认 false）、link-local/169.254.169.254；私网默认允许（`allow-private-network=true`，内网场景需要），公网部署建议关掉并配 `allowed-hosts`。残余风险：DNS rebinding 未做 TOCTOU 二次校验，P2 处理。
- **审计**：中转节点审计 action 加 `relay.` 前缀，`relayVia` 写入 JSONL + SQLite `relay_via`（旧库自动 ALTER TABLE）；不记录原始 `X-Relay-Chain`/`X-Relay-Via` 头（只记录解析后的 `{appKey,name,url}`），不记录 secret。
- **部署**：relay 默认关闭，A/B 必须显式 `app.relay.enabled=true`；B/C 不要直接暴露给客户端或公网；旧客户端可能把 v3 relay 当 direct 绕过 A，发布期限制 B/C 可达性或轮换凭据。

## 3. 边界与已知限制（务必阅读）

1. **客户端密钥可被逆向**：AppKey / AppSecret 现在编译进客户端二进制（`builtin.rs`），分发给用户后理论上可被提取；
   本项目的目标是「显著提高第三方直接调用接口的成本」，不是绝对不可破。AppKey/AppSecret 默认内置；若在设置页为服务器填写自定义密钥，会保存在本地 config.json（权限 0600），不会进入 WebView；
   使用内置默认密钥时，轮换需要重新构建客户端；为服务器填写自定义密钥时可在设置页修改，并同步服务端 `UPLOAD_APP_KEY` / `UPLOAD_APP_SECRET`（或启动 Jar 时的 `--app-key` / `--app-secret`）。
2. **单机内存态**：nonce 缓存和限流都在进程内存里，多实例部署时需换成 Redis（`NonceCache` 与 `RateLimiter` 均为独立组件，替换成本低）。
3. **SQLite 元数据 + 单机内存索引**：无需安装数据库服务，适合中小规模单实例；SQLite 单写者，多实例共享同一存储根需要改成集中式数据库/对象存储，或保证同一根只由一个实例写入。
4. **无用户体系**：当前以 appKey 维度管理文件，未做用户登录/权限隔离；如果需要多用户，应在现有签名层之上再加身份层。
5. **审计日志**：已内置 JSONL 审计（见 4.1）；生产建议集中采集并配置异常告警。IP/MAC 黑名单已内置（见 2.8），当前需手动删 SQLite 行解封，没有管理界面。

## 4. 生产上线检查清单

- [ ] `UPLOAD_APP_SECRET` 已替换为 `openssl rand -hex 32` 生成的随机值，且只通过环境变量/环境文件注入
- [ ] 客户端发布前用 `UPLOAD_CLIENT_APP_KEY/SECRET/SERVER_URL` 重新构建，内置值与服务端配置一致
- [ ] `DEVICE_BINDING_ENABLED=true`
- [ ] `CLIENT_IDENTITY_ENFORCED=true`（环境文件 `app.security.client-identity-enforced: true`），且已发布带 `X-Device-Mac` 的新版客户端
- [ ] 探活/监控请求已带 `X-Device-Mac`（否则会被封禁，health 也会 403）
- [ ] HTTPS 证书已配置，客户端服务器地址使用 `https://`
- [ ] 服务端跑的 JVM 是 JDK 21+（多版本服务器在 `scop-ftp.env` 里用 `JAVA_HOME` 指定，不要依赖系统默认）
- [ ] 8080 端口未对公网开放（安全组/防火墙）
- [ ] Nginx `client_max_body_size` ≥ 服务端上限，`proxy_request_buffering off`
- [ ] `client/data/` 有备份策略，磁盘容量监控
- [ ] 若启用 relay：A/B 已升级并显式 `app.relay.enabled=true`；B/C 只允许 A/上一跳访问，未对公网暴露；按需关闭 `allow-private-network`、配置 `allowed-hosts`；发布期已限制旧客户端绕过 A
- [ ] 服务端时间同步（chrony/NTP），客户端设备时间准确
- [ ] 定期轮换 appSecret，并同步更新客户端
- [ ] 日志中确认未打印 appSecret 与签名原文

## 4.1 审计日志（已实现）

- 所有签名 `/api/**` 请求写 JSONL + `audit.sqlite3`：默认 `./logs/audit/`，JSONL 按天、默认保留 90 天；配置 `app.audit.*` / `UPLOAD_AUDIT_*`。
- 字段：requestId（响应头 `X-Request-Id`）、appKey/clientName/deviceId/clientIp、method/path/query/action、root/targetRoot/path/targetDir、terminalCommand、httpStatus/durationMs/success/error、contentSha256、requestBytes/responseBytes、脱敏 body、userAgent、`relayVia`（来源链 `{appKey,name,url}[]`，A 自己的转发请求为空）。
- **不记录** AppSecret、签名、nonce、文件正文；body 中 content/appSecret/password/token/secret → `***` 并截断。终端命令完整记录（追责需要），但终端不是沙箱，生产默认关闭。
- 审计目录不要放在存储根内，避免被客户端浏览/删除；建议独立磁盘并限制权限。客户端只能通过签名接口查当前 appKey 的日志，且审计写入/查询本身也会被记录。
- 客户端日志抽屉把上传/下载/删除/移动/编辑/解压/终端等实际操作与验证连接/设备激活合并展示，用类型/操作/时间筛选区分；列表列固定为「设备/IP、操作内容、时间、操作」，终端命令与文件目标直接展示，隐藏接口路径/结果/耗时，详情仍保留 requestId 等诊断字段；目录列表、搜索等只读 API 请求仍保留在底层审计库，但不进入该日志视图。
- SQLite 审计库与元数据库均为**单写者**：同一目录不要被多个服务实例同时写；生产多实例请拆分 audit 目录或改用集中式日志/数据库。

## 5. 后续可增强项

- nonce/限流迁移到 Redis，支持多实例
- 文件落盘前接入病毒扫描（ClamAV）
- 审计日志集中采集/告警（ELK/Loki），跨实例检索
- 客户端代码签名（macOS notarization / Windows Authenticode），配合服务端版本校验
- TLS 证书固定（pinning）与请求体加密

## 6. 验证方式

- 服务端单元/集成测试：签名测试向量、过期、重放、错误签名、哈希篡改、设备绑定、relay 链头/每跳签名/3 跳/流式/SSRF/错误映射/审计来源；`ClientIdentityGuardIntegrationTest` 覆盖缺 MAC/IP 封禁、MAC/IP 两张黑名单 OR 拦截、activate 乱序封禁、手动删行立即解封（335 passed）
- 端到端脚本：`E2E_STORAGE_ROOT=<root> node scripts/e2e.mjs`（真实 HTTP，覆盖 40100/40101/40102/40001/40003/40300 等错误路径，以及按目录上传/路径下载删除/越界）；开启 `client-identity-enforced` 后同样 55 passed（所有请求带 `X-Device-Mac`）

## 7. 接口认证覆盖检查（2026-10-30）

对当前服务端路由做了动态枚举与未签名调用检查：

- 业务接口共 **24 个路由**，全部位于 `/api/**`（storage/auth/files/terminal/audit）。
- `SignatureFilter` 只放行 `GET /api/health` 与 CORS `OPTIONS`；其余 `/api/**` 的 GET/POST/PUT/DELETE 都要求 `X-App-Key`、`X-Timestamp`、`X-Nonce`、`X-Signature`。
- 开启 `client-identity-enforced` 时，`ClientGuardFilter`（最高优先级）在 `SignatureFilter` 之前执行，覆盖所有 `/api/**`（含 `/api/health`）的 MAC/IP 身份与黑名单检查；测试 profile 关闭该开关，因此 `SignatureCoverageIntegrationTest` 仍按未签名 40100 验证。
- 签名串绑定 **method + path + 规范化 query + payloadHash**；JSON body 由服务端重新计算哈希，multipart/分片上传使用参与签名的 `X-Content-Sha256` 并在落盘时复算。
- 新增 `SignatureCoverageIntegrationTest`：从 `RequestMappingHandlerMapping` 动态取全部 `/api/**` 路由，不带签名逐一调用，必须返回 `40100`。以后新增接口若不在签名过滤范围内，该测试会直接失败。
- 新增 query 篡改、JSON body 篡改测试：签名对应 page=1/原始 body，实际发送 page=2/篡改 body，均返回 `40100`。

### 当前仍需注意的边界

1. **凭证已改为必填，但客户端二进制里仍留有演示默认值**：服务端已去掉 demo 兜底，`application.yml` / 各环境文件里 `appKey`/`appSecret` 为空时启动阶段直接失败（`CredentialPreflight` 先打印一次性提示，`AppProperties#afterPropertiesSet` 再做完整校验），必须由环境变量、`--app-key`/`--app-secret` 或环境文件显式指定；客户端「设置」页也把两项改为必填，IPC 保存时拒绝空值。仍需注意：`src-tauri/src/builtin.rs` 仍编译进一份演示 AppKey/AppSecret 作为旧配置兜底，发布构建务必用 `UPLOAD_CLIENT_APP_KEY` / `UPLOAD_CLIENT_APP_SECRET` 覆盖，生产密钥用 `openssl rand -hex 32` 生成并定期轮换。
2. **AppKey 是客户端级共享密钥，不是用户账号**：任何有效 appKey 都能访问全部存储根；没有按用户/租户的 ACL。分片上传的 status/cancel/chunk 也没有绑定 appKey（需要不可猜的 uploadId），多客户端互不信任时应再加会话归属校验。
3. **设备绑定只作用于 `/api/files`**：开启 `DEVICE_BINDING_ENABLED=true` 后，终端 `/api/terminal/exec`、审计查询、存储根查询仍只校验签名，不校验 deviceId；终端默认还是开启的，生产环境文件应显式 `app.terminal.enabled: false` 或再加设备绑定。开启 `client-identity-enforced` 后 MAC/IP 黑名单对**全部** `/api/**` 生效（含终端/审计），但只拦“已封禁/缺身份/乱序”，不做 deviceId 绑定。
4. **`GET /api/health` 无需签名**：只返回状态与时间，不返回文件/路径/密钥信息；开启 `client-identity-enforced` 后仍需带合法 `X-Device-Mac`，否则 40312 并被写入黑名单（探活/监控必须带头）。
5. **nonce/限流是单机内存态**：服务重启会清空 nonce 缓存（5 分钟窗口内的抓包仍有重放窗口）；多实例部署需要 Redis。Nginx 反代后 `getRemoteAddr()` 是反代 IP，限流维度会退化为 appKey，生产需配置可信 real_ip。
6. **客户端内置密钥可被逆向**：这是“提高直接调用成本”，不是不可破；不要把它当作用户身份认证，公网部署必须 HTTPS、限制网络入口并定期轮换密钥。

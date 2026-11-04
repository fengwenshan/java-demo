# ScopeFTP 多级转发链路设计（Relay Chain）

| 项 | 内容 |
| --- | --- |
| 状态 | 设计待评审 |
| 日期 | 2026-11-03 |
| 范围 | `07_scope-ftp` 客户端（Tauri 2 / Rust / React）与服务端（Spring Boot 3） |
| 关联文档 | `README.md`、`docs/01-architecture.md`、`docs/02-api-contract.md`、`docs/04-security.md` |

## 0. 摘要

客户端连接服务器 A 后，可以在 A 下添加「转发目标」B；B 只能从 A 访问。B 下还可以继续添加 C，形成
`A → B → C → …` 的任意层级转发链。每个转发目标在客户端本地保存自己的地址、AppKey、AppSecret 和设备身份，
服务端不保存下游凭据。

请求使用**按跳消费的签名数组**：客户端为链上每一跳预先计算签名，A 验证并消费自己的签名后，把下一跳的
签名和请求原样转发；B 再消费，直到最终目标。数组里只有签名，没有明文 secret；A 不知道 B/C 的 secret，
也无法篡改下游请求（否则下游验签失败）。

每一跳在转发时追加来源信息；最终目标 B 的审计记录「来源 A」，C 的审计记录「来源 A、B」，任意层级同理。

## 1. 背景与目标

### 1.1 场景

- 客户端能直连 A，但 B 只能从 A 访问，C 只能从 B 访问。
- 客户端目前的「服务器」是平铺列表，一个服务器一套地址/凭据/设备身份，不支持经过 A 访问 B。
- A 的 key/secret 变化后，客户端到 A 的所有请求失败；只修好 A 的客户端配置，B/C 也应恢复（因为它们的外层认证都经过 A）。
- 删除 A 时，A 下的 B/C 本地配置应一并删除。

### 1.2 需求

| 编号 | 需求 |
| --- | --- |
| R1 | A 连接成功之后，才出现 B 的服务器信息填写入口；B 的填写项包含目标地址、B 的 AppKey、B 的 AppSecret、设备名称 |
| R2 | 左侧服务器列表按树展示 A 及其「转发」目标；点击转发目标切换到该目标，右侧浏览目标内容 |
| R3 | 转发目标可编辑，编辑项包含目标中转服务器地址和 B 的凭据；A 的信息区显示已中转的目标列表，点击可切换 |
| R4 | 删除 A 级联删除其下所有转发目标（B/C/…）的本地配置与续传状态 |
| R5 | A 的 key/secret/地址变化时，客户端修正 A 后，B/C 自动恢复，不需要改 B/C 的配置 |
| R6 | B 的审计能知道请求来自 A；C 的审计能知道来自 A、B；任意层级记录完整来源链 |
| R7 | 层级不设产品上限，支持 `A → B → C → D → …`；服务端用可配置的安全上限防滥用 |
| R8 | 浏览、上传（含 200MB 单次 / 8MB 分片 / 断点续传）、下载、删除、重命名、拷贝移动、在线编辑、解压、搜索、终端、审计、存储根等现有功能都能经转发使用 |

### 1.3 非目标

- 不做 TCP/VPN/SSH 隧道，只转发 ScopeFTP 的 `/api/**` 请求。
- 不在 A/B 服务端保存转发目标配置或下游 secret；配置只在客户端本地。
- P1 不要求 B/C 对来源做密码学验证；来源由上一跳追加，依赖「B 只能从 A 访问、C 只能从 B 访问」的网络隔离。若需要密码学校验，需在 B/C 登记上游凭据，列为后续增强。
- 不改变最终目标的业务数据模型、文件布局、存储根语义。
- 不兼容旧客户端读取 v3 配置（见 11.3）。

## 2. 现状与约束

以下事实来自当前代码，是设计的前提：

- **客户端配置 v2**：`client/src-tauri/src/config.rs` 的 `ServerProfile` 平铺在 `servers[]`，每台服务器独立
  `serverUrl/deviceId/deviceName/appKey/appSecret`；`config.json` 权限 0600；v1 自动迁移到 v2。
- **客户端签名**：`client/src-tauri/src/signer.rs` 固定 8 行签名串
  `v1\nappKey\ntimestamp\nnonce\nMETHOD\npath\ncanonicalQuery\npayloadHash`；`crypto::auth_headers` 在
  `client/src-tauri/src/api.rs`，所有网络命令经 `Api`。
- **服务端签名**：`server/src/main/java/com/wenshan/upload/security/SignatureFilter.java`，顺序为
  ClientGuard → Signature（`@Order(ORDERED.HIGHEST_PRECEDENCE + 10)`）→ 业务 Controller。
  JSON body ≤1MiB 会缓存进 `CachedBodyHttpServletRequest`；multipart / application/octet-stream 不读 body，
  使用 `X-Content-Sha256` 参与签名。
- **多客户端**：服务端 `app.security.clients[]` 支持多个 appKey，但当前每台服务器只配置自己的客户端。
- **审计**：`server/src/main/java/com/wenshan/upload/audit/AuditLogService.java` 在 SignatureFilter 的
  finally 中记录审计；`AuditStore.java` 使用独立 SQLite（`<app.audit.dir>/audit.sqlite3`），
  `CREATE TABLE IF NOT EXISTS audit_log`，`MetaStore` 的严格 schema version 与审计库无关。
- **客户端多服务器**：`App.tsx` 以 `activeServerId` 决定所有请求目标；`ServerBrowser.tsx` 以
  `server:root` 作为重载 key；`uploads.rs` 的续传状态按 `server.id` 隔离。

## 3. 方案选择

### 3.1 备选方案

| 方案 | 做法 | 结论 |
| --- | --- | --- |
| 服务端注册表 | A 服务端新增转发目标表/管理 API，客户端只展示 A 下发的目标；B 的地址/凭据存 A | 不采用：与「B 的配置只在客户端」冲突；A 要保存 B 的 secret；每台客户端无法各自配置 |
| 原始凭据数组 | 数组项 `{url, appKey, appSecret}`；A 消费自己的，用 B 的 secret 重新签名 | 不采用：B 的 secret 进入 A 的内存/网络，A 能篡改 B 的请求内容 |
| **签名数组（选定）** | 数组项 `{url, name, appKey, timestamp, nonce, signature, deviceId}`；每跳只验自己的签名，消费后转发剩余 | 采用：下游 secret 不出客户端；A 无法篡改下游的 path/query/body；最终目标基本无需改动；任意层级自然成立 |

### 3.2 关键决策

1. **配置在客户端**：B/C 的地址和凭据只在客户端 `config.json`；A/B 服务端无转发目标存储。
2. **签名不是 secret**：`X-Relay-Chain` 中只有 HMAC 签名，没有 appSecret。
3. **外层仍走 A 的标准认证**：客户端到 A 的请求用 A 的 key/secret 签名；A 现有的
   `ClientGuardFilter` + `SignatureFilter` 完全复用。
4. **最终目标零业务改动**：最终目标收到的是标准 `/api/**` 请求，现有业务代码不变；仅为「来源审计」增加
   `relayVia` 字段（见 4.8）。
5. **任意层级**：链数组长度即层级；服务端 `app.relay.max-hops` 默认 16，可调高；客户端只做环检测和
   头大小限制，不设产品层数上限。
6. **入口前置连接**：只有父服务器当前连接成功（verify 通过）时，才出现转发目标填写/验证入口。

## 4. 协议设计

### 4.1 术语

- **hop**：链上的一台服务器。
- **root**：链的第一跳，客户端直连的服务器（示例中的 A）。
- **target**：当前激活的目标服务器（B/C/…）。
- **relay node**：需要执行转发逻辑的服务器（root 和中间跳）；最终目标不需要转发逻辑。
- **chain**：root → … → target 的有序 hop 列表。
- **via**：请求已经过/正在经过的来源服务器列表，用于审计。

### 4.2 配置树与链

客户端配置是一棵以 direct 服务器为根的树。转发目标用 `parentId` 指向父节点：

```text
A (direct)
└── B (relay, parentId=A)
    └── C (relay, parentId=B)
        └── D (relay, parentId=C)
```

对 target 发请求时，按 `parentId` 回溯得到链 `[A, B, C]`。链是**每次请求动态生成**的，不落盘，因此：

- 修改 A 的地址/key/secret/deviceId 后，所有后代的链自动使用新值。
- 修改 B 的 key/secret 只影响 B 这一跳（A→B 的签名和 B 作为中间跳的验签）。
- 删除 A 时，A 的整棵子树一起删除。

### 4.3 链数组

客户端把链数组放进请求头 `X-Relay-Chain`：

```text
X-Relay-Chain: base64url(JSON array, 无 padding)
```

数组项（顺序 = root → target）：

```json
{
  "url": "https://b.internal:8080/scopeftp",
  "name": "B",
  "appKey": "B_APP_KEY",
  "timestamp": 1760000000000,
  "nonce": "5f0c2d8e-...-9a1b",
  "signature": "hex(hmac-sha256)",
  "deviceId": "b-device-uuid"
}
```

字段说明：

| 字段 | 说明 |
| --- | --- |
| `url` | 该跳的 base URL。第 0 跳是客户端直连地址；第 i 跳是「第 i-1 跳能访问到的地址」。可带 nginx 子路径 |
| `name` | 仅用于展示和来源审计，不参与签名 |
| `appKey` | 该跳的 AppKey |
| `timestamp` | 该跳签名时的时间戳（毫秒） |
| `nonce` | 该跳签名时的随机串（每请求每跳新生成） |
| `signature` | 用该跳 appSecret 对「该跳实际收到的 path/query/body」计算的 HMAC-SHA256 |
| `deviceId` | 该跳的设备 ID，转发时作为下一跳的 `X-Device-Id` |

约束：

- 只允许 Base64URL 字母表，无 padding；服务端解码失败 → `40010`。
- 数组长度 1 的链不用于转发（direct 请求走普通接口）；转发请求的数组长度 ≥2。
- 数组总大小 ≤ `app.relay.max-chain-header-bytes`（默认 16KiB）；超出 → `40010`。
- 数组里**没有** `appSecret`；A 只知道 B/C 的 appKey 和签名，不知道 secret。
- 同一链中 `appKey` 不得重复（避免验签/来源歧义）；重复 → `40010`。
- URL 拼接前统一去掉尾部斜杠并规范化，禁止双斜杠和路径穿越。

### 4.4 每跳签名规则

沿用现有 8 行签名串，不新增签名版本：

```text
v1
appKey
timestamp
nonce
METHOD
path
canonicalQuery
payloadHash
```

每跳的 `path` 取决于这一跳是 relay 还是最终目标：

| 位置 | 该跳收到的实际路径 | 签名 path |
| --- | --- | --- |
| 第 0..n-2 跳（relay node） | `/api/relay` + 原始 path | `/api/relay` + 原始 path |
| 第 n-1 跳（最终目标） | 原始 path | 原始 path |

- `canonicalQuery`：所有跳相同，按契约 2.4 规范化。
- `payloadHash`：
  - GET / DELETE（空 body）：固定空 SHA-256；
  - JSON：`sha256(body)`；
  - multipart / `application/octet-stream`：使用 `X-Content-Sha256`（文件/分片的 SHA-256），与现有规则一致。
- 每跳的 `timestamp/nonce` 独立生成；同一个请求内不要跨跳复用 nonce。
- 客户端每次重试都重新生成整条链的 timestamp/nonce/signature（不复用旧签名）。

### 4.5 客户端请求

以 target=C、链 `[A,B,C]`、方法 `GET /api/files?root=root` 为例：

1. 客户端计算三个签名：
   - A：path=`/api/relay/api/files`，A 的 key/secret；
   - B：path=`/api/relay/api/files`，B 的 key/secret；
   - C：path=`/api/files`，C 的 key/secret。
2. 发送到 `A.url + /api/relay/api/files?root=root`。
3. 标准签名头使用数组第 0 项（A）：`X-App-Key/X-Timestamp/X-Nonce/X-Signature`。
4. `X-Device-Id` = A 的 deviceId；`X-Device-Mac` = 本机 MAC；`X-Relay-Chain` = 三项数组。
5. A 的 `SignatureFilter` 按现有逻辑验签（path=`/api/relay/api/files`），并照常做设备绑定/限流/审计。

direct 请求（target=A 或任意 direct 服务器）不经过 relay：不加 `/api/relay` 前缀，不发 `X-Relay-Chain`。

### 4.6 中转节点处理

新增 `RelayForwardFilter`（见 6.1），对 `/api/relay/api/**` 执行：

1. 解析并校验 `X-Relay-Chain`。
2. 校验数组头与本次请求的标准签名头一致（appKey/timestamp/nonce/signature/deviceId），防止头/请求错配。
3. 消费第 0 项，得到 `remaining = chain[1..]`；若 `remaining` 为空 → `40010`。
4. 取 `next = remaining[0]`，校验 `next.url`（见 6.4）。
5. 计算原始路径：`requestURI` 去掉 `/api/relay` 前缀，必须以 `/api/` 开头，拒绝 `..`、`//`、`%2e`、
   反斜杠和嵌套 `/api/relay`。
6. 追加来源：当前跳信息取自已消费的 `chain[0]` 的 `{appKey,name,url}`；
   `via = 当前 X-Relay-Via + 当前跳`。**root 节点必须忽略客户端携带的 `X-Relay-Via`，从空开始**，
   防止持有 A 凭据的客户端伪造来源；只有 relay 节点生成/追加的 via 才向下游传递。
7. 构造出站请求：
   - **`remaining` 长度 = 1（下一跳是最终目标）**
     - URL = `next.url + 原始路径 + 原始 query`
     - 标准签名头 = `next` 的 appKey/timestamp/nonce/signature
     - `X-Device-Id` = `next.deviceId`
     - 不带 `X-Relay-Chain`
     - `X-Relay-Via` = via
   - **`remaining` 长度 ≥ 2（下一跳仍是 relay node）**
     - URL = `next.url + /api/relay + 原始路径 + 原始 query`
     - 标准签名头 = `next`
     - `X-Device-Id` = `next.deviceId`
     - `X-Relay-Chain` = `remaining` 的 Base64URL JSON
     - `X-Relay-Via` = via
8. 透传 method、原始 query、body、`Content-Type`（含 multipart boundary）、`X-Content-Sha256`、
   `X-Device-Mac`、必要的 `Accept`；**不转发**本跳的 `X-App-Key/X-Timestamp/X-Nonce/X-Signature`。
9. 读取下游响应，透传状态码、业务响应头（`Content-Type/Content-Length/Content-Disposition/
   X-Content-Sha256`）和响应体；下游 `X-Request-Id` 以 `X-Relay-Request-Id` 返回，便于排查。
10. 下游错误按 6.5 映射；网络错误返回本跳的统一信封。

### 4.7 最终目标

最终目标收到的是标准请求（无 `/api/relay` 前缀、无链数组），只额外带 `X-Relay-Via`。它的业务逻辑不变：

- `SignatureFilter` 用自己配置的 appSecret 验下一跳预签的签名；
- 设备绑定/限流/审计/上传/下载/终端等全部按现有逻辑执行；
- 审计通过 `X-Relay-Via` 记录来源。

因此最终目标的数据面可以保持旧版本；但若要「知道来源」，需要升级到带 `relayVia` 审计的版本（见 4.8）。

### 4.8 来源审计（B 知道 A，C 知道 A、B）

新增请求头 `X-Relay-Via`：

```text
X-Relay-Via: base64url(JSON array of {appKey, name, url}, 无 padding)
```

- A 收到客户端请求时忽略请求携带的 `X-Relay-Via`，从空开始；A 在转发给 B 前追加自己：`[A]`。
  这样客户端无法伪造来源；B/C 只信任上一跳生成/追加的 via。
- B 收到后：
  - 审计记录 `relayVia = [A]`；
  - 若 B 继续转发给 C，追加 B：`[A, B]`。
- C 收到后审计记录 `relayVia = [A, B]`；若 C 继续转发给 D，追加 C。
- 最终目标只记录、不再追加。
- 大小 ≤ `app.relay.max-via-header-bytes`（默认 8KiB）；relay 追加后超出 → `40010`。
- 解析失败/字段非法：最终目标审计记录为空并继续（不阻断业务）；relay 节点转发前校验，非法 → `40010`。

**信任模型**：`X-Relay-Via` 由上一跳追加，未做密码学签名。它成立的依据是网络隔离：
B 只能从 A 访问，所以 B 收到的请求只能来自 A；C 只能从 B 访问，所以 C 收到的请求只能来自 B。
若需要密码学证明「A 确实是 A」，需要在 B/C 配置上游的 appKey/secret 并增加来源签名校验，列为 P2
（见 14）。

**存储**：

- 审计事件新增 `relayVia: RelayViaHop[]`（每个 hop `{appKey,name,url}`；JSONL 字段 `relayVia`）。
- 审计 SQLite 新增可空列 `relay_via TEXT`（存 JSON 数组）；旧库通过 `ALTER TABLE ADD COLUMN` 迁移（见 6.6）。
- 审计查询接口返回 `relayVia`；客户端日志列表/详情显示「来源」或「转发链」。
- A 的审计没有 `relayVia`（A 是 root）；B 的记录 `[A]`；C 的记录 `[A,B]`；D 的记录 `[A,B,C]`。

### 4.9 设备身份与激活

每个 hop 有独立 `deviceId/deviceName`，写在该 hop 的客户端配置里，并放入链数组。

- 外层 A 的 `X-Device-Id` = A 的 deviceId；A→B 的 `X-Device-Id` = B 的 deviceId；B→C 的 = C 的 deviceId。
- `X-Device-Mac` 始终是本机 MAC，逐跳透传。B/C 的 `ClientGuardService` 看到的身份 = 上一跳出口 IP
  + 本机 MAC；`verify → activate` 的 10 分钟顺序校验按「(上一跳出口 IP, 本机 MAC)」记录，客户端在验证连接时
  按顺序完成即可。
- 设备绑定：B 的 appKey 绑定的是客户端为 B 生成的 deviceId；C 同理。
- **逐跳预激活**：客户端在验证 target（例如 C）时：
  1. 按链顺序对每一台祖先服务器 H（A、B）执行 `verify`（通过链前缀 `[A..H]`）；
  2. 若 H 的 `verify.deviceBindingEnabled` 为 true 且 `device` 为空或 `device.deviceId != H.deviceId`，
     对 H 执行 `activate`（通过链前缀 `[A..H]`）；
  3. 最后对 target C 执行 `verify`（Settings 页原有的 activate 流程继续负责激活 C）。
  这样 B 作为中间节点时，B 的 SignatureFilter 对 `/api/relay/**` 的设备绑定检查也能通过。
- 若某跳 activate 失败（例如 B 的 appKey 已被别的设备绑定），返回明确的错误：
  「转发链上的 B 设备未授权，请检查/解绑后在 B 上重新激活」。
- 多客户端共用 B 的 appKey 时，B 的设备绑定仍只允许一台设备（沿用现状，非本功能引入）。

### 4.10 时钟、重试、幂等

- 时间戳窗口沿用 ±5 分钟；客户端本机时钟、A/B/C 服务器时钟都需 NTP 对时。
- 某一跳返回 `40101` 时：客户端提示具体是哪一跳时间偏差（从错误消息/请求链定位）；P2 可增加
  `/health` 的 `serverTime` 逐跳校准。
- 中转节点不做自动重试；客户端现有 `send_json` 重试会重新生成整条链签名（新 timestamp/nonce）。
- relay 相关错误（`40110/50210/50410/50212`）不自动重试写请求，避免「B 已处理但响应丢失」造成重复写。
- `GET` 的重试也只在客户端层做，且每次重新签名。

### 4.11 协议示例

**A→B（B 是最终目标）**

```text
客户端 → A:  GET A.url/api/relay/api/files
             X-App-Key=A, X-Signature=sign(A, /api/relay/api/files)
             X-Relay-Chain=[A,B], X-Relay-Via=(空)
A → B:       GET B.url/api/files
             X-App-Key=B, X-Signature=sign(B, /api/files)
             X-Relay-Via=[A], 无链数组
B 审计:      relayVia=[A]，path=/api/files
```

**A→B→C（C 是最终目标）**

```text
客户端 → A:  GET A.url/api/relay/api/files
             chain=[A,B,C]
A → B:       GET B.url/api/relay/api/files
             chain=[B,C], via=[A]
B → C:       GET C.url/api/files
             无链数组, via=[A,B]
C 审计:      relayVia=[A,B]，path=/api/files
```

## 5. 客户端设计

### 5.1 配置 v3

`client/src-tauri/src/config.rs` 的 `ServerProfile` 增加：

```rust
pub enum ServerKind { Direct, Relay }        // serde: "direct" / "relay"
pub struct ServerProfile {
    // 现有字段：id, name, server_url, device_id, device_name, app_key, app_secret
    pub kind: ServerKind,                    // 缺省 direct
    pub parent_id: Option<String>,           // relay 必填；direct 为空
}
```

`config.json` v3：

```json
{
  "configVersion": 3,
  "activeServerId": "b",
  "servers": [
    {
      "id": "a", "kind": "direct", "name": "A",
      "serverUrl": "https://a.example.com/scopeftp",
      "deviceId": "a-device", "deviceName": "我的 MacBook",
      "appKey": "A_KEY", "appSecret": "A_SECRET"
    },
    {
      "id": "b", "kind": "relay", "parentId": "a", "name": "B",
      "serverUrl": "http://10.0.0.5:8080",
      "deviceId": "b-device", "deviceName": "我的 MacBook",
      "appKey": "B_KEY", "appSecret": "B_SECRET"
    }
  ]
}
```

规则：

- `serverUrl`：
  - direct：客户端直连地址；
  - relay：**上一跳能访问到的目标地址**（A 访问 B 用这个地址，B 访问 C 用 C 的地址）。客户端不会直连它。
- `appKey/appSecret`：
  - direct：沿用现有逻辑（可回退内置值）；
  - relay：**必填**，不允许回退内置值；保存/验证时校验非空。
- `deviceId/deviceName`：每跳独立，新建时生成 deviceId，deviceName 默认本机设备名。
- `parentId`：relay 必填，必须指向存在且 kind 为 direct/relay 的服务器；禁止自指和环。
- relay 不能作为根；有服务器时必须至少有一台 direct（relay 的链必须能回溯到 direct）。
- 允许没有服务器（空配置）：删除最后一台 direct 时连同其子树一起删除，客户端显示空列表/添加服务器引导；
  不再沿用「至少保留一台服务器」保护。
- 空配置时 `activeServerId` 为空，`ConfigView.activeServer` 为 null；`App.tsx` 不渲染浏览器，显示空态；
  `api_from` 返回「请先添加服务器」；`save_server` 仍可新建 direct，新建后设为 active。
- `load_or_init` 仅在配置文件不存在（首次安装）时生成默认 direct；配置文件存在但 `servers=[]` 时必须保持空，
  `normalize()` 不得自动补默认服务器（否则删完重启又出现 A）。
- 新建（未保存）表单没有删除按钮；已保存的服务器（direct/relay）都可以删除；删除 direct 级联子树。
- 校验失败（父不存在/环/层级过深）时该服务器 `configured=false`，UI 提示修复，不静默改成 direct。
- 迁移：
  - v1（单配置）→ v2 逻辑保持不变；
  - v2（无 `configVersion`）→ v3：补 `configVersion=3`，所有服务器 `kind=direct`、`parentId=null`，deviceId 缺失时生成；迁移后立即持久化。
- 旧客户端读 v3：会忽略 `configVersion/kind/parentId`，把 relay 当 direct 处理。发布说明必须写明「v3 配置只给新版客户端使用」，或升级时先备份 `config.json`。

### 5.2 Rust 侧

#### 5.2.1 链解析与视图

`AppConfig` 新增：

- `resolve_chain(server_id) -> Result<Vec<ServerProfile>, String>`：沿 `parentId` 回溯、反转，校验父存在/无环/层数不超过客户端安全上限（建议 64）。
- `save_relay_with_credentials(...)`：新建/编辑转发目标，生成/保留 id 与 deviceId，校验父存在、凭据非空，落盘 0600。
- `delete_server(id)`：级联收集子节点，返回 `deleted_ids`；允许删到空配置；active 被删时回退到父级/最近的 direct，空则清空 active。
- `view()`：`ServerView` 增加 `kind/parentId/parentName/chainPath/chainHops/rootId/rootName`；`configured` 需要整条链的地址和凭据都有效；`ConfigView.activeServer` 允许为 null。
- `normalize()`：`servers` 为空时不再自动补默认服务器；`activeServerId` 为空时保持空；`load_or_init` 仅在配置文件不存在时创建默认 direct。

`commands.rs` 的 `api_from`：没有 active 时返回「请先添加服务器」；`App.tsx` 在 `ConfigView.activeServer == null`
时显示空态，不渲染浏览器。

`ConfigView` 增加 `configVersion`。`ServerView` 的 `effectiveAppKey` 仍表示该服务器自己的 AppKey；UI 另外用
`rootName/parentName/chainPath` 展示外层认证信息。

#### 5.2.2 `Api` 结构

`client/src-tauri/src/api.rs`：

```rust
pub struct Api {
    http: reqwest::Client,
    chain: Vec<ServerProfile>,   // root → target，长度 >= 1
}
```

- `root()` = `chain[0]`，`target()` = `chain.last()`；`server()` 保持返回 target（现有命令/续传/测试兼容）。
- `base_url()` 使用 root 的 `server_url`。
- `build_url(path, canonical_query)`：
  - `chain.len() > 1` → `root.url + "/api/relay" + path`；
  - 否则 → `root.url + path`。
- `auth_headers(method, path, canonical_query, payload_hash, extra)`：
  1. 若 `chain.len() > 1`，为每跳生成：
     - `hop_path = i < len-1 ? "/api/relay" + path : path`；
     - `signature = sign(hop.secret, canonical(hop.appKey, ts_i, nonce_i, method, hop_path, canonical_query, payload_hash))`；
     - 条目 `{url, name, appKey, timestamp, nonce, signature, deviceId}`；
  2. 标准头 = 第 0 跳；`X-Device-Id` = 第 0 跳 deviceId；`X-Device-Mac`；
  3. 若 `len > 1`，`X-Relay-Chain` = Base64URL(JSON(条目数组))。
- 所有显式拼 URL 的位置统一走 `build_url`：
  - `health()`（relay 时是 `/api/relay/api/health`，用于确认整条链可达）；
  - multipart 上传（`/api/files/upload`）；
  - 分片 init/chunk/complete/status/cancel；
  - 下载（含 `X-Content-Sha256` 透传校验）；
  - 搜索/终端/审计/存储根等经 `send_json`/`send_void` 的自动生效。
- `verify_once` / `activate_once`：用指定前缀链构造 `Api`，供逐跳预激活使用；避免递归触发完整 preflight。
- 错误映射：新增 `40320/40010/40110/50210/50410/50212` 的中文提示；`is_retryable_error` 排除 relay 错误。
- 下载：继续在客户端校验 `X-Content-Sha256`；relay 必须透传该头。
- Base64URL：客户端可复用 `Cargo.lock` 中已有的 `base64 0.21`（或实现 20 行 URL-safe helper），不引入新服务端依赖；
  服务端用 `java.util.Base64.getUrlEncoder().withoutPadding()`。
- 链头大小/层级在发送前检查：超过 `max-hops` 或 `max-chain-header-bytes` 直接报错，不发请求。

#### 5.2.3 Tauri 命令

| 命令 | 变更 |
| --- | --- |
| `get_config` | 返回 v3 视图（含 kind/parentId/chainPath/configVersion） |
| `save_server` | 仍只处理 direct |
| `save_relay` | 新增：`id?, parentId, name, serverUrl, deviceName, appKey, appSecret`；新建后设为 active；编辑时 appSecret 为空/掩码表示保留原值，新建必填 |
| `delete_server` | 级联删除子树；清理所有被删 id 的续传状态；返回新的 `ConfigView` |
| `set_active_server` | 允许 direct/relay；语义不变 |
| `verify_connection` | 对 active target：先逐跳 verify/activate 祖先，再 verify target；返回 target 的 `VerifyData` |
| `verify_connection_preview` | direct 新建预览保持不变 |
| `verify_relay_preview` | 新增：`id?, parentId, name, serverUrl, deviceName, appKey, appSecret`；先按 4.9 预激活 parent 链祖先，再临时链验证，不落盘；新建用临时 deviceId 且不激活目标，编辑沿用已保存 deviceId |
| `activate` | 仍激活 active target；祖先由 `verify_connection` 预激活 |
| 其余网络命令 | 无需改参数；`api_from` 改为解析链并构造 `Api` |

`commands.rs` 的 `api_from`：

```rust
let config = state.config.lock()?;
let active = config.require_active()?;
let chain = config.resolve_chain(&active.id)?;
Ok(Api::new(state.http.clone(), chain))
```

### 5.3 前端类型与 API

`client/src/types.ts`：

```ts
type ServerKind = "direct" | "relay";
interface ChainHop { id: string; name: string; }
interface RelayViaHop { appKey: string; name: string; url: string; }
interface ServerView {
  // 现有字段
  kind: ServerKind;
  parentId: string | null;
  parentName: string | null;
  chainPath: string[];      // ["A","B","C"]
  chainHops: ChainHop[];    // 可点击的链
  rootId: string;
  rootName: string;
}
interface ConfigView {
  configVersion: number;
  activeServer: ServerView | null;   // 空配置时为 null
  servers: ServerView[];
  // 现有字段
}
```

`AuditLogItem` 增加 `relayVia?: RelayViaHop[]`（服务端返回 `appKey/name/url`，客户端映射到本地服务器）。

`client/src/api.ts` 新增：

- `saveRelay(args)`；
- `verifyRelayPreview(args)`；
- 现有 `saveServer/deleteServer/setActiveServer/verifyConnection` 语义不变。

### 5.4 UI 与交互

#### 5.4.1 连接前置（R1）

- 只有**父服务器是当前 active 且连接状态为 `connected`** 时，才显示/启用「添加转发目标」入口。
- A 未连接：A 的设置页显示「请先连接 A，连接成功后才能添加转发目标」，不渲染 B 的地址/凭据输入框。
- B 未连接：B 下不能添加 C；选中 B 并验证成功后才出现 C 的填写入口。
- 编辑已保存的转发目标不需要父链连接，随时可以打开修改；只有「验证连接」需要父链已连接，保存后提示「需重新验证」。
- 新建转发目标的推荐流程：
  1. 选中 A，验证连接成功；
  2. A 的设置页出现「转发目标」→「添加」；
  3. 填 B 的名称、**目标中转服务器地址（A 可访问）**、B 的 AppKey、B 的 AppSecret、设备名称；
  4. 点「验证并添加」：客户端先健康检查/verify 整条链，再保存；
  5. B 出现在左侧 A 下，标签「转发」；选中 B，验证连接后浏览 B。
- 添加 C 同理：选中 B，验证成功后，在 B 的设置页添加 C（地址为 B 可访问的 C 地址）。

> 说明：R1 的「只有 A 连接成功以后才能出现 B 的信息填写」在 UI 层强制；Rust 命令仍可被高级调用，
> 但 `save_relay` 会校验父节点存在和凭据非空。

#### 5.4.2 左侧列表

```text
服务器（1）
● A   https://a.example.com/scopeftp        [+]
   └ [转发] B   http://10.0.0.5:8080        ✎
       └ [转发] C   http://10.0.0.6:8080    ✎
```

- 直连行保持现状（名称、URL、状态点、编辑）。
- relay 行：按层级缩进 + 引导线；`RelayOutlined` 图标 + 「转发」Tag；副标题显示 target URL；
  右侧 `✎` 编辑。
- `+` 只出现在「已连接且允许添加」的父行上；点击进入新增转发目标表单。
- 点击 relay 行：`setActiveServer(id)` → 切换 active → 右栏按 `server:root` key 重载；
  连接状态、存储根、日志、终端都切到该 target。
- 服务器列表为空时显示空态：「还没有服务器」+「添加服务器」按钮；不渲染 A 行。空态按钮打开新增
  direct 表单；没有 direct 时不能添加 relay。
- 状态点仍只对 active target 显示连接状态；非 active 的 relay 行显示灰色。

建议 `data-testid`：

- `server-list-item[data-server-id][data-server-kind][data-parent-id]`
- `relay-badge`
- `add-relay`
- `edit-relay`
- `settings-relays` / `settings-relay-item`

#### 5.4.3 设置页

**A（direct）的页面**：

- 没有服务器时，设置页直接显示「新增 direct 服务器」表单；保存后设为 active。
- 现有字段/验证/删除不变。
- 连接成功后新增「转发目标（N）」卡片：
  - 列出直接子目标（B/C…）：名称、地址、状态/「点击切换」；
  - 「添加转发目标」按钮；
  - 点击某目标：`onSelectServer(target.id)`，即「A 信息里显示中转了哪些列表，点击切换到目标」。
- 删除 A 的确认文案：「删除 A 会同时删除 N 个转发目标（B、C）的本地配置和续传状态，不会删除服务器上的文件。」
  如果 A 是最后一台，删除后进入空配置空态（不是阻止删除）。
- 删除按钮只对「已保存」的服务器显示；新建表单只有取消/保存，没有删除。

**B/C（relay）的页面**：

- 表单字段：
  - 名称；
  - 目标中转服务器地址（说明：A 可访问的 B 地址 / B 可访问的 C 地址）；
  - B 的 AppKey / AppSecret；
  - 设备名称；
  - 只读：上级服务器、转发链（A → B → C）。
- 「验证连接」：通过整条链验证 target；成功后按现有流程 activate。
- 「转发目标（N）」卡片：与 A 相同，用于继续添加 C/D。
- 删除：删除自身及子树；提示子树数量。

**凭据变更影响提示**：

- 修改 A 的地址/key/secret 时提示：「其下 N 个转发目标都使用 A 的外层签名，修改后需要重新验证。」
- 修改 B 的 key/secret 时提示：「会影响 B 这一跳及其下所有目标。」

**凭据掩码**：沿用现有 `SAVED_SECRET_MASK` 机制；relay 的 secret 也不回显。编辑时 AppSecret 留空/掩码
表示保留原值；新建时必填。

#### 5.4.4 右栏与审计

- `ServerBrowser` 标题/面包屑显示「B（经 A 转发）」或「C（经 A → B 转发）」；存储根、路径、日志、终端
  均作用于 target。
- 审计日志（B/C）列表/详情新增「来源/转发链」，可点击：
  - B：`A`；
  - C：`A → B`；
  - 任意层级：`A → B → C`。
  - `relayVia` 的 hop 带 `{appKey,name,url}`；客户端按 appKey（其次 url）映射到本地 `servers`，找到则渲染为
    链接/按钮，点击调用 `onSelectServer(id)` 切换 active（与左侧列表一致）；找不到（例如别的客户端的 A）则纯文本。
- 服务器信息/设置页也显示当前 target 的链（A → B → C），同样可点击切换；A 的「转发目标」列表已可点击。
- 日志筛选/分页沿用现有；P2 可加按来源筛选。

#### 5.4.5 非 Tauri 浏览器预览

- 非 Tauri 环境无法读取本地配置，沿用「请使用桌面客户端运行」提示；转发目标入口禁用。

### 5.5 客户端错误提示

| 场景 | 提示 |
| --- | --- |
| A 未开启转发（40320） | 「A 服务端未开启转发，请在 A 的配置中启用 app.relay.enabled」 |
| 链格式错/头过大（40010） | 「转发链配置异常，请检查转发目标或客户端版本」 |
| B/C 认证失败（40110） | 「中转目标认证失败，请检查 B 的 AppKey/AppSecret 或服务器时间」 |
| 目标不可达（50210） | 「经 A 转发失败：目标 B 不可达，请检查网络或 B 服务」 |
| 中转超时（50410） | 「经 A 转发超时，请稍后重试」 |
| 目标拒绝出口身份（50212） | 「B 拒绝了 A 的出口 IP/MAC，请在 B 上解封后重试」 |
| 设备未授权（40300） | 「转发链上的 B 设备未授权，请重新验证/激活」 |
| 父服务器未连接 | 「请先连接 A，成功后才能添加/验证转发目标」 |

## 6. 服务端设计

### 6.1 `RelayForwardFilter`

新增 `server/src/main/java/com/wenshan/upload/relay/RelayForwardFilter.java`：

- `@Order(Ordered.HIGHEST_PRECEDENCE + 20)`，排在 `SignatureFilter`（+10）之后、`DispatcherServlet` 之前。
- 只处理 `requestURI` 以 `/api/relay/api/` 开头的请求；OPTIONS 跳过；其余 `filterChain.doFilter`。
- **必须是 Filter，不能是 Controller**：Spring 6.1 的 `MultipartResolver` 在进入 Handler 前就会解析
  multipart，Controller 拿不到原始流，且会按 `MultipartConfigElement` 落临时盘；Filter 在
  `DispatcherServlet` 之前可以拿到原始 `InputStream`。
- 依赖：
  - `AppProperties.Relay`（开关、上限、超时、白名单）；
  - JDK 21 `java.net.http.HttpClient`（零新依赖，双向流式）；
  - 现有 `SignatureService`/`AuditContext`/`AuditLogService` 复用；
  - 新增 `RelayChainCodec`（Base64URL JSON 编解码/校验）、`RelayUrlGuard`（URL/SSRF）、
    `RelayErrorMapper`、`RelayViaCodec`。
- 对 relay 请求，不调用 `filterChain.doFilter`，直接写响应；`SignatureFilter` 的 finally 仍会记录 A 侧审计。

### 6.2 流式转发

- **JSON**：`request` 是 `CachedBodyHttpServletRequest` 时取 `getCachedBody()`，用
  `BodyPublishers.ofByteArray` 转发；payloadHash 与 A 的签名一致。
- **multipart / octet-stream**：不读 body，直接 `BodyPublishers.ofInputStream(() -> request.getInputStream())`；
  保留 `Content-Type`（boundary）、`X-Content-Sha256`；A 不做内容哈希校验，由 B 落盘复算。
- **无 body**：`BodyPublishers.noBody()`。
- 出站 `Accept-Encoding: identity`，避免 B 压缩后客户端下载校验失败。
- 响应：`HttpResponse.BodyHandlers.ofInputStream()`，64KiB 缓冲写 `HttpServletResponse`；设置
  `Content-Type/Content-Length/Content-Disposition/X-Content-Sha256`，下游 `X-Request-Id` 写
  `X-Relay-Request-Id`；客户端断开时停止读取。
- 超时：
  - connect timeout（默认 3s）；
  - JSON/普通请求（默认 20s）；
  - 上传分片/multipart（默认 180s）；
  - 下载不设总超时（避免 5GB 文件被截断），只依赖连接超时和 socket 读；P2 可加读空闲超时。
- 不自动重试；`followRedirects(NEVER)`；不转发 `Cookie/Set-Cookie`。

### 6.3 配置

`AppProperties` 新增 `Relay` 嵌套类，`application.yml` 默认：

```yaml
app:
  relay:
    enabled: false                 # 默认关闭，按环境开启
    max-hops: 16                   # 安全上限，可调高；产品层不设限
    max-chain-header-bytes: 16384
    max-via-header-bytes: 8192
    connect-timeout-ms: 3000
    request-timeout-ms: 20000
    upload-timeout-ms: 180000
    allowed-hosts: []              # 空 = 不限制 host（仍受 IP 规则约束）
    allow-private-network: true    # B 常在内网；显式允许
    allow-loopback: false          # 默认禁止 127.0.0.0/8、::1
    block-link-local: true         # 禁止 169.254.0.0/16、fe80::/10、云元数据
```

- A/B/C 环境文件按需 `app.relay.enabled: true`。
- 不在服务端保存转发目标；没有新的数据库表。

### 6.4 URL/SSRF 校验

`RelayUrlGuard`：

- 只允许 `http/https`；
- 禁止 userinfo、fragment、query（base URL 只允许 path）；
- 规范化后解析 host/IP：
  - 默认拒绝 loopback、link-local、multicast、unspecified、云元数据地址；
  - `allow-private-network` 控制私网（10/8、172.16/12、192.168/16、fc00::/7）；
  - `allowed-hosts` 非空时只允许列表中的 host（支持精确/后缀，按实现定）。
- 连接前校验解析出的 IP；连接后不再跟随重定向。
- 残余风险：DNS rebinding、恶意客户端用 A 当代理。relay 默认关闭 + 仅持有 A secret 的客户端可用 +
  可选 host 白名单；P2 可加固。

### 6.5 错误码与映射

新增（`ErrorCode`）：

| code | HTTP | 含义 |
| --- | --- | --- |
| 40010 | 400 | 转发链格式错误/头过大/路径非法 |
| 40320 | 403 | 本服务器未开启转发 |
| 40110 | 401 | 中转目标认证失败（下游 40100/40103） |
| 50210 | 502 | 中转目标不可达/连接失败 |
| 50211 | 502 | 中转目标返回非法/非 JSON 响应 |
| 50212 | 502 | 中转目标拒绝出口身份（下游黑名单/身份错误） |
| 50410 | 504 | 中转超时 |

映射规则：

- 本跳参数/链错误 → 40010/40320；
- 下游 40100/40103 → 40110（保留下游 message 便于判断是 B 的 key 还是时间）；
- 下游 40300 → 40300（设备绑定）；
- 下游 40310/40311/40312/40313 → 50212（提示在目标服务器解封）；
- 下游 42900 → 42900；
- 下游其他 4xx → 透传 code/status/body；
- 下游 5xx → 透传 status/body（保留下游 code），A 不伪装成 50000；
- 非 JSON 错误体 → 50210/50211；
- 网络异常 → 50210；超时 → 50410。

### 6.6 审计与 SQLite 迁移

- `AuditEvent` 增加 `List<RelayViaHop> relayVia`；JSONL 自动多一个字段。
- 新增 `RelayViaHop(String appKey, String name, String url)`（`audit` 包或 `dto` 包）。
- `AuditLogItem` 增加 `List<RelayViaHop> relayVia`。
- `AuditStore`：`relay_via TEXT` 存 `[{appKey,name,url}]` 的 JSON；`mapItem` 解析为列表。
- `AuditStore`：
  - `COLUMNS` 增加 `relay_via`；
  - `CREATE TABLE IF NOT EXISTS` 增加 `relay_via TEXT`；
  - 新增 `ensureColumn("audit_log", "relay_via", "TEXT")`：`PRAGMA table_info(audit_log)` 后
    `ALTER TABLE audit_log ADD COLUMN relay_via TEXT`；旧库启动时自动补列；
  - `INSERT` 增加参数；`mapItem` 解析 JSON 数组；
  - 现有行 `relay_via` 为 NULL，客户端显示「直连/无」。
- `AuditLogService.buildEvent`：读 `X-Relay-Via`，解析/校验后填入 `relayVia`；解析失败记录空并继续。
- `AuditAction.resolve`：对 `/api/relay/api/...` 先剥离 `/api/relay`，结果为 `relay.<原 action>`，
  例如 `relay.files.list`、`relay.upload.chunk`；`AuditCategory` 归为 operation。
- A 的审计记录 relay 请求（path 以 `/api/relay` 开头，action 带 `relay.`）；B/C 的最终请求记录正常 action
  + `relayVia`；B/C 作为中间跳的 relay 请求记录 `relay.<action>` + `relayVia`。

### 6.7 `SignatureFilter` 适配

现有 `SignatureFilter` 按路径判断设备绑定、限流成本、activate 顺序。relay 路径需要在判断前剥离
`/api/relay` 前缀，否则：

- `isFileApi("/api/relay/api/files")` 为 false → A/B 的设备绑定不生效；
- `isUploadCost("/api/relay/api/files/upload")` 为 false → relay 上传只扣 1 个令牌；
- `AuditAction` 无法识别业务动作。

新增工具方法 `effectivePath(path)`（只在路径以 `/api/relay/api/` 开头时剥离 `/api/relay`），用于：

- `isFileApi`、`isUploadCost`、`isExtractCost`、`isCopyOrMoveCost`、`isTerminalCost`；
- `AuditAction.resolve`；
- 其他依赖业务路径的判断。

注意：

- `/api/relay/api/health` **不能**跳过签名（它是 relay 入口，必须认证）；现有 `shouldSkip` 只跳过
  精确 `/api/health`，保持不变。
- 下游最终目标的 `/api/health` 仍按现有规则跳过签名；但 `ClientGuardFilter` 仍要求 `X-Device-Mac`，
  relay 必须透传 MAC。

### 6.8 限流、黑名单、设备绑定

- A 的限流：外层请求按 A 的 appKey + 客户端 IP；relay 上传/解压/终端等按剥离后的路径计算成本。
- B/C 的限流：按 B/C 的 appKey + 上一跳出口 IP；多个客户端经同一 A 访问 B 时共享 B 的桶
  （容量 1000，上传 5/次），高并发下可能提前触发 42900；运维可调 B 的容量，或接受限流。
- 黑名单：B/C 看到的客户端 IP 是上一跳的出口 IP。若客户端 MAC 缺失/非法或违反 verify→activate 顺序，
  B/C 会把上一跳 IP 写黑名单，导致该 A/B 下所有客户端被拦截。需要在 B/C 上手动删黑名单行；
  文档中给出 SQL。P2 可增加「可信 relay 出口 IP」配置，避免误封。
- 设备绑定：B/C 的 appKey 绑定客户端为各跳生成的 deviceId；多客户端共用同一 B appKey 时仍只允许一台
  设备（现状约束）。

## 7. 接口契约变更

### 7.1 请求头

| 头 | 方向 | 说明 |
| --- | --- | --- |
| `X-Relay-Chain` | 客户端 → 每个 relay node | Base64URL(JSON 数组)；每跳消费头部 |
| `X-Relay-Via` | relay node → 下游 | Base64URL(JSON 数组)；每跳追加自己 |
| `X-Relay-Request-Id` | 下游 → 客户端（经 relay 回传） | 下游 `X-Request-Id`，用于关联 A/B 审计 |
| `X-Device-Mac` | 逐跳透传 | 本机 MAC；B/C 的 ClientGuard 依赖 |
| `X-Content-Sha256` | 逐跳透传 | multipart/octet-stream 的签名 payloadHash |

### 7.2 链数组 schema

见 4.3。JSON 字段固定；新增字段需要客户端/服务端同步升级。

### 7.3 审计字段

- JSONL：`relayVia: {appKey,name,url}[]`。
- `GET /api/audit/logs` 返回的 `AuditLogItem.relayVia: {appKey,name,url}[]`。
- 旧客户端忽略新字段；新客户端对 null/缺失做兼容。

### 7.4 Tauri 命令

见 5.2.3。

### 7.5 服务端配置

见 6.3。

## 8. 安全分析

| 威胁 | 影响 | 控制 |
| --- | --- | --- |
| 下游 secret 泄露 | B/C 凭据被 A 或抓包获取 | 链里只有签名，无 secret；客户端 config 0600；HTTPS |
| A 篡改 B 的请求 | 文件内容/路径被改 | B 用自己 secret 验签预签的 path/query/bodyHash；改 path/query/bodyHash → 40100，改设备身份 → 40300 |
| 重放下游签名 | 重复执行写操作 | B 的 nonce 缓存（10 分钟）拒绝重复；relay 不自动重试写请求 |
| SSRF | 恶意客户端让 A 访问内网/元数据 | relay 默认关闭；URL 只允许 http/https；禁 userinfo/fragment；禁 loopback/link-local/元数据；可选 allowed-hosts |
| 链头被中间人篡改 | 改路由/目标地址 | 链头不参与 8 行签名；client→A 的链头完整性依赖 HTTPS；A→B 的链头由 A 生成，依赖 A 可信。A 改路由不能改 B 验签的 path/query/body；A 被攻破时本身就能伪造响应。P2 可加链头独立签名 |
| 来源伪造 | B/C 审计显示错误来源 | A 忽略客户端传入的 `X-Relay-Via` 并从空追加；B/C 只信任上一跳；网络隔离；P2 可密码学验证 |
| 旧客户端读 v3 | 把 relay 当 direct，可能绕过 A 直连 B | 发布说明 + 升级窗口限制 B/C 可达性/轮换 B/C 凭据，直到客户端全部升级 |
| B/C 误封 A 的出口 IP | 整个 A 下客户端不可用 | 明确文档 + 手动解封 SQL；P2 可信 relay 出口 IP |
| 设备绑定冲突 | 多客户端共用 B 时后到者 40300 | 沿用现状；文档说明每客户端独立 appKey 或关闭绑定 |
| 头过大/DoS | 请求头/内存压力 | max-chain-header-bytes、max-via-header-bytes、max-hops；流式转发不缓存大 body |
| 日志泄露 | 审计里出现 secret | 链/来源头里无 secret；审计只记录解析后的 appKey/name/url（无 secret），不记录原始 `X-Relay-Chain` 头/签名；原始头不写日志 |

## 9. 失败场景矩阵

| 场景 | 客户端表现 | 处理 |
| --- | --- | --- |
| A 的 key/secret 改错 | A 返回 40100；A/B 都断 | 修 A 的客户端配置并重新验证；B 配置不动 |
| A 的地址改错 | 网络错误/超时 | 修 A 地址 |
| A 未开启 relay | 40320 | 在 A 的 `app.relay.enabled` 开启 |
| B 的 key/secret 改错 | A 返回 40110 | 改 B 的转发目标凭据，重新验证 |
| B 服务宕机 | A 返回 50210 | 修 B；A 不受影响 |
| B 网络超时 | A 返回 50410 | 检查网络/超时配置 |
| B 黑名单封了 A 的 IP | A 返回 50212 | 在 B 上执行黑名单 SQL 解封 |
| B 的设备绑定给了别的设备 | 40300 | 在 B 上清理 device 记录/重新激活 |
| 删除 A | B/C 本地配置和续传状态一起删；若 A 是最后一台，客户端进入空态 | 服务端文件不动 |
| 删除 B | C 一起删 | A 不受影响 |
| 删除最后一台 direct | 连同子树一起删，客户端显示空配置 | 允许；重启后保持空，不自动补默认服务器 |
| A 的 key 改了但客户端没改 | A/B 都 40100 | 与现有 direct 行为一致 |
| C 时间偏差 | C 返回 40101（经 A/B 透传） | 校时；P2 health 校准 |

## 10. 测试

### 10.1 服务端

新增 `server/src/test/java/com/wenshan/upload/relay/`：

- `RelayChainCodecTest`：合法/非法 Base64、JSON、缺字段、超长、超 hops、重复 appKey。
- `RelayViaCodecTest`：追加/解析/超长/非法。
- `RelayUrlGuardTest`：http/https、私网、loopback、link-local、元数据、userinfo/fragment、allowed-hosts。
- `RelayErrorMapperTest`：下游 401/403/429/5xx → 本跳 code/status。
- `RelayForwardIntegrationTest`（`RANDOM_PORT` + JDK `com.sun.net.httpserver.HttpServer` 假 B）：
  - JSON GET/POST 的 path/query/body 签名可复算；
  - multipart 上传流式转发、8MB octet 分片不 OOM、下载字节一致；
  - `X-Relay-Via` 追加；
  - 3 跳（假 B 继续转发到假 C）与更深链；
  - 下游 401/403/429/500、连接拒绝、超时；
  - relay 关闭、非法链、SSRF；
  - `X-Relay-Request-Id` 回传。
- `RelayAuditIntegrationTest`：A 侧 action `relay.*`；B/C 侧 `relayVia`；JSONL+SQLite 双写。
- `AuditStoreMigrationTest`：旧库无 `relay_via` → 启动自动 ALTER；插入/查询/旧行 NULL。
- `SignatureRelayPathTest`：`/api/relay/api/files` 的设备绑定/限流成本按剥离后路径计算。

注意：MockMvc 下 multipart 流会被 Spring 提前消费，relay 的流式用例必须用真实端口。

### 10.2 客户端 Rust

`client/src-tauri/src/config.rs`：

- v2 → v3 迁移；v1 迁移不变；
- 父不存在/环/自指/层级过深 → `configured=false`；
- 级联删除、允许删到空配置、active 回退/清空、重启后保持空配置（normalize 不补默认）；
- relay 的 secret 不在常规视图回显；设置页点击密码框眼睛时按服务器 id 读取本地自定义值；
- `resolve_chain` 顺序/去重/最大深度。

`client/src-tauri/src/api.rs`：

- 链条目生成：每跳 path（relay/最终）、canonical query、payloadHash 三种模式；
- 用测试密钥复算签名，确认与 `signer` 一致；
- `build_url` 的 relay 前缀；health/multipart 显式 URL；
- `X-Relay-Chain` Base64URL 编解码/长度；
- relay 错误映射与重试排除。

`client/src-tauri/src/commands.rs`：`save_relay`、`delete_server` 级联、`verify_relay_preview` 参数校验。

### 10.3 前端

- `pnpm build`（tsc + vite）。
- 组件/手工：
  - A 未连接时无转发入口；连接后出现；
  - 添加 B 的表单字段完整（地址/AppKey/AppSecret/设备名）；
  - 左侧树缩进/「转发」Tag/切换；
  - A 信息区的转发目标列表点击切换；
  - 删除 A 的级联提示；
  - B 的日志显示「来源 A」，C 显示「来源 A → B」；来源 hop 可点击，切换到本地已配置的 A/B；
  - 删除最后一台服务器后显示空态，重启后仍为空；添加新 direct 后恢复列表；
  - 编辑已保存的 B/C 不要求父链连接；
  - 右栏标题「B（经 A 转发）」；
  - 非 Tauri 预览降级。

### 10.4 端到端

- `scripts/e2e.mjs` 扩展 relay 用例：JS 侧实现链签名，A+B 真服务（B 只监听 A 可达地址），验证
  浏览/上传/分片/下载/终端/审计/来源。
- 手工多级：A→B→C，验证任意层级、删除级联、A/B key 轮换。

## 11. 发布与兼容

1. **服务端**：A 必须升级并开启 `app.relay.enabled`；中间 B/C 也必须升级并开启；最终目标若要记录来源
   需要升级（数据面可旧版）。
2. **客户端**：升级到支持 v3 配置的版本。旧客户端读 v3 会忽略 `configVersion/kind/parentId`，把 relay 当 direct
   处理；若 B/C 对旧客户端网络可达，旧客户端会尝试带 B/C 凭据直连，绕过 A。发布说明必须注明，并在升级窗口
   内限制 B/C 的可达性/轮换 B/C 凭据，直到所有客户端升级；同时建议备份 `config.json`。
3. **网络**：A→B、B→C 的出站访问；生产 HTTPS；nginx 对 relay 关闭 buffering、放宽超时。
4. **安全配置**：
   - B/C 的 `client-identity-enforced` 保持现状；relay 透传客户端 MAC；
   - B/C 的 `device-binding-enabled` 保持现状；客户端逐跳激活；
   - 若 B/C 因 relay 误封 A/B 的出口 IP，需手动解封（文档给 SQL）。
5. **回滚**：关闭 `app.relay.enabled` 即停用转发；客户端 v3 配置中的 relay 条目不参与 direct 请求；
   旧客户端需回退配置备份。

## 12. 阶段与工作量

| 阶段 | 内容 | 估算 |
| --- | --- | --- |
| P1 | 协议/文档、服务端 `RelayForwardFilter`（流式、错误、URL 守卫、配置）、客户端 v3 配置/链签名/路由、UI 树/表单/连接前置/级联/空配置空态、B/C 来源审计（`relay_via` 迁移 + 可点击来源链）、任意层级协议 + A→B 全功能 + A→B→C 联调 | 服务端 8–10d，Rust 6–7d，前端 4–5d，联调/测试/文档 3–4d |
| P2 | 更深层级压测、逐跳时钟校准、可信 relay 出口 IP、密码学来源校验、审计筛选、性能/大文件优化 | 4–6d |

P1 内部建议顺序：

1. 服务端链协议/Filter/假 B 测试；
2. 客户端配置 v3 + `Api` 链签名 + 单测；
3. A→B 全功能联调（浏览/上传/下载/终端/审计）；
4. UI 树/表单/连接前置/级联；
5. 多级 A→B→C 联调 + 来源审计；
6. 错误/安全/文档/验收。

总计约 **20–26 人天**（不含 P2）。

## 13. 验收标准

| 编号 | 验收 |
| --- | --- |
| AC1 | 添加转发目标时，A 连接成功后 A 的设置页才出现「转发目标」入口；A 未连接时看不到 B 的地址/凭据输入框 |
| AC2 | B 的表单包含目标中转服务器地址、B 的 AppKey、B 的 AppSecret、设备名称；验证通过后才能保存 |
| AC3 | 左侧列表显示 A 下的「转发」目标；点击 B 切换到 B，右栏显示 B 的目录；存储根/终端/日志均作用于 B |
| AC4 | A 的信息区显示已中转目标列表，点击可切换 |
| AC5 | 删除 A 时 B/C 一起删；若 A 是最后一台 direct，删除后客户端显示空配置/添加服务器引导；重启后保持空；服务端文件不受影响 |
| AC6 | A 的 key/secret 改错 → A/B 均失败；修正 A 后 B 自动恢复（B 配置不动） |
| AC7 | B 的 key/secret 改错 → 提示 40110；修正 B 后恢复 |
| AC8 | B 宕机 → 提示 50210/50410；A 不受影响 |
| AC9 | B 的审计记录来源 A；C 的审计记录来源 A、B；任意层级记录完整链；来源 hop 可点击切换到本地已配置的服务器 |
| AC10 | 支持 A→B→C（以及更深）全功能：浏览/上传（含 8MB 分片断点续传）/下载/删除/重命名/拷贝移动/在线编辑/解压/搜索/终端/审计 |
| AC11 | 链数组里不含任何 appSecret；抓包/日志看不到 B/C 的 secret |
| AC12 | A 无法篡改 B/C 的请求内容（改动后下游验签失败） |
| AC13 | relay 关闭时 A 返回 40320，不影响现有 direct 功能 |
| AC14 | 旧库审计数据在升级后仍可查询；`relayVia` 为 NULL 时 UI 正常显示 |
| AC15 | 编辑已保存的 B/C 不需要父链连接，随时可改；「验证连接」仍要求父链可用 |

## 14. 待确认/后续增强

1. **来源密码学校验**：P1 的 `X-Relay-Via` 依赖网络隔离，不做密码学签名。若需要 B/C 密码学确认
   「确实来自 A」，需要在 B/C 的 `app.security.clients[]` 登记上游 appKey/secret，并增加来源签名校验；
   这会引入上游凭据在 B/C 的配置，建议作为 P2。
2. **时钟校准**：P1 要求 NTP；P2 可用每跳 `/health` 的 `serverTime` 计算偏移。
3. **可信 relay 出口 IP**：P2 可为 B/C 增加「来自这些 IP 的 relay 请求不写黑名单」的配置，避免误封。
4. **最大层级**：`app.relay.max-hops` 默认 16，可按环境调高；客户端链头大小是最终约束。
5. **转发目标编辑的门禁（已定）**：添加需要父连接成功；编辑已保存目标不需要父链连接，随时可改；
   「验证连接」需要父链可用。
6. **A 的审计是否显示转发到哪个目标**：P1 不做；P2 可在 A 的审计里加 target 摘要。
7. **删除到空配置（已定）**：允许删到空；实现需调整 `normalize()`/`load_or_init` 和前端空态，确保
   配置文件存在但 `servers=[]` 时重启不被自动补回默认服务器。

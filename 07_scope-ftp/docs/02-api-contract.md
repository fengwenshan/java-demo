# 接口与签名契约（v1）

> 本文档是**服务端与客户端的唯一契约**。两端实现必须逐字一致，尤其是签名串的拼装规则。
> 契约版本：`v1`。任何不兼容修改都必须提升签名串首行版本号。

## 1. 通用约定

| 项 | 值 |
| --- | --- |
| 服务端监听 | `0.0.0.0:8080` |
| 接口前缀 | `/api`（业务接口全部在此前缀下，`/api/health` 免签） |
| 字符编码 | UTF-8 |
| JSON 字段命名 | camelCase |
| 时间格式 | `createdAt` / `boundAt`：ISO-8601 UTC，如 `2026-10-28T04:12:00Z`；`serverTime` / 响应 `timestamp`：epoch 毫秒（number） |
| 成功响应 | `{"code":0,"message":"ok","data":{...},"timestamp":1760000000000}` |
| 失败响应 | `{"code":40100,"message":"签名无效","data":null,"timestamp":1760000000000}` |
| 下载成功响应 | 直接返回文件字节流（不走 JSON 信封）；下载失败才返回 JSON 信封 |

### 1.1 统一信封

```json
{
  "code": 0,
  "message": "ok",
  "data": null,
  "timestamp": 1760000000000
}
```

- `code = 0` 表示成功；非 0 见第 6 节错误码。
- 下载接口（`GET /api/files/{id}/download`）成功时是二进制流，**不套信封**。

## 2. 签名机制

### 2.1 预配发凭证

服务端各环境文件 `application-<env>.yml` 的 `app.security.clients` 中配置客户端凭证（凭证可用 `UPLOAD_APP_KEY` / `UPLOAD_APP_SECRET` 注入）：

```yaml
app:
  security:
    clients:
      # 没有内置默认值：必须由环境变量 UPLOAD_APP_KEY / UPLOAD_APP_SECRET
      # 或启动参数 --app-key / --app-secret 提供，缺失则启动失败
      - app-key: ${UPLOAD_APP_KEY:}
        app-secret: ${UPLOAD_APP_SECRET:}
        name: Tauri 桌面客户端
        enabled: true
```

客户端身份强制校验相关开关：`app.security.client-identity-enforced`（默认 `false`，环境文件按需开启）、`app.security.verify-activate-window-ms`（默认 600000，verify 后允许 activate 的时间窗），详见 [04-security.md](04-security.md) 的 2.8。

服务端启动时必须指定 `appKey` / `appSecret`（环境变量、`--app-key`/`--app-secret`，或当前环境文件），缺失会直接启动失败；客户端保存 direct/relay 时都要求填写 AppKey/AppSecret，**relay 额外禁止回退内置值**。自定义 `appSecret` 只保存在本地 config.json（0600），**不会进入 WebView**。

### 2.2 请求头

所有 `/api/**` 业务接口（`/api/health` 除外）必须携带签名头；开启客户端身份强制校验后（`app.security.client-identity-enforced: true`），**`/api/health` 也必须携带 `X-Device-Mac`**，否则 40312 并被写入黑名单：

| 请求头 | 必填 | 说明 |
| --- | --- | --- |
| `X-App-Key` | 是 | 客户端标识 |
| `X-Timestamp` | 是 | epoch 毫秒，字符串形式，如 `1760000000000` |
| `X-Nonce` | 是 | 一次性随机串，建议 UUID v4；服务端要求匹配 `^[A-Za-z0-9_-]{16,64}$`，10 分钟内不可重复 |
| `X-Signature` | 是 | 64 位小写十六进制 HMAC-SHA256 |
| `X-Device-Id` | 否 | 设备 UUID；文件类接口在开启设备绑定后必须携带且与绑定值一致 |
| `X-Device-Mac` | 开启身份强制时是 | 客户端读取本机物理地址后上报，服务端规范化为 `AA:BB:CC:DD:EE:FF`；**该头不参与签名**，缺失/非法会被拉黑（40312） |
| `X-Content-Sha256` | 上传必填 | 文件内容 SHA-256（小写 hex），同时参与签名 |
| `Content-Type` | 是 | JSON 接口用 `application/json; charset=utf-8`；上传用 `multipart/form-data` |

### 2.3 签名串（canonical string）

按**固定顺序**拼 8 行，行分隔符为 `\n`，末尾**不加**换行：

```
v1
{appKey}
{timestampMillis}
{nonce}
{METHOD}
{path}
{canonicalQuery}
{payloadHash}
```

| 占位符 | 规则 |
| --- | --- |
| `v1` | 固定字面量，签名版本 |
| `appKey` | 与 `X-App-Key` 完全一致 |
| `timestampMillis` | 与 `X-Timestamp` 完全一致（十进制毫秒，无前导零） |
| `nonce` | 与 `X-Nonce` 完全一致 |
| `METHOD` | HTTP 方法大写：`GET` / `POST` / `DELETE` |
| `path` | 仅 URI path，不含 query，也不含协议/域名/端口，如 `/api/files/upload`；`/api/files/{id}/download` 中 id 为实际 UUID |
| `canonicalQuery` | 查询串规范化后的结果，无参数时为空字符串（因此该行为空行）。规则见 2.4 |
| `payloadHash` | 见 2.5 |

签名值：

```text
X-Signature = lowercase_hex( HMAC-SHA256( key = appSecret(UTF-8), message = canonicalString(UTF-8) ) )
```

示例（`appSecret = demo-secret-please-change-32-bytes-min`，仅用于单元测试的固定时间戳）：

```text
canonical =
v1
demo-app
1760000000000
0b9f6c1e-2d1a-4c1b-9a7e-3f1c2b3d4e5f
GET
/api/auth/verify

e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855

X-Signature = 9ca81faf26909bec390a1bba9a38a02c812843d9a64443a1e226522ceaa5a14b
```

上传场景（文件内容为 `hello`）：

```text
canonical =
v1
demo-app
1760000000000
0b9f6c1e-2d1a-4c1b-9a7e-3f1c2b3d4e5f
POST
/api/files/upload

2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824

X-Signature = 4ce78a59afffd8264f7277fcebbf74daefab380eb95adae218005e6d79302c0c
```

### 2.4 查询串规范化（canonicalQuery）

1. 按 `&` 拆分为键值对；每对按第一个 `=` 拆成 key / value（无 value 视为空串）。
2. 对 key、value 做 **percent-decoding**（UTF-8，`+` 不当作空格）。
3. 对 key、value 重新做 **RFC3986 percent-encoding**：
   - 仅保留 `A-Z a-z 0-9 - . _ ~` 原样；
   - 其余字节一律编码为 `%XX`（大写十六进制，UTF-8 字节序）。
4. 先按 key 升序、key 相同再按 value 升序（按 UTF-8 字节序 / String 字典序）排序。
5. 重新拼成 `key=value`，用 `&` 连接。
6. 没有查询参数时结果为空字符串。

示例：原始 `size=20&page=1&keyword=中文 空格` →
`keyword=%E4%B8%AD%E6%96%87%20%E7%A9%BA%E6%A0%BC&page=1&size=20`。

### 2.5 payloadHash

| 场景 | payloadHash 取值 |
| --- | --- |
| 无请求体（GET / DELETE） | `sha256Hex("")` = `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| JSON 请求体（如激活） | `sha256Hex(原始请求体字节)`，注意是实际发送的字节，不是重新序列化的结果 |
| multipart 上传 | **文件内容**的 `sha256Hex`，取值与服务端 `X-Content-Sha256` 请求头一致 |

multipart 上传时服务端不会读取整个请求体计算哈希（避免大文件进内存），
而是校验 `X-Content-Sha256` 头 → 用它参与签名 → 解析出文件后**重新计算文件 SHA-256** 并与头比对，
不一致返回 `40001` 并删除临时文件。

### 2.6 服务端校验顺序（必须严格按此顺序，返回对应错误码）

0. 开启 `client-identity-enforced` 时，先由 `ClientGuardFilter` 校验客户端身份（覆盖所有 `/api/**`，含 `/api/health`）：IP/MAC 缺失或非法 → `40312` 并写入黑名单；命中 IP 黑名单 → `40310`；命中 MAC 黑名单 → `40311`（任一条命中即拦截）
1. 取必需请求头，缺失 → `40100`（HTTP 401）
2. `appKey` 查找客户端配置：不存在或 `enabled=false` → `40103`（401）
3. 解析 `X-Timestamp`：非数字 → `40100`；与本机时间偏差 > 300000ms → `40101`（401）
4. 计算 HMAC 并用**常量时间比较** `X-Signature` → 不一致 → `40100`（401）
5. 校验 nonce 未被使用：用 Caffeine 缓存记录（TTL 600s），已存在 → `40102`（401）。
   **注意：只有在签名校验通过后才写入 nonce**，避免攻击者用无效签名污染 nonce 缓存。
6. 文件类接口且 `deviceBindingEnabled=true`：appKey 已绑定设备且与 `X-Device-Id` 不一致 → `40300`（403）
7. 限流（令牌桶，按 appKey + 客户端 IP）：无令牌 → `42900`（429）
8. 开启身份强制时，`/api/auth/activate` 还要求该 MAC+IP 在 `verify-activate-window-ms` 内成功 `verify` 过，否则 → `40313` 并把 MAC+IP 写入黑名单
9. 进入业务处理；上传额外校验文件哈希、扩展名、大小

### 2.7 时间与重放

- 允许窗口：`±300000ms`（5 分钟），可配置 `app.security.timestamp-window-ms`。
- nonce 缓存 TTL：`600000ms`，可配置 `app.security.nonce-ttl-ms`。
- 客户端每次请求生成新 UUID 作为 nonce；时间戳取本机当前毫秒；若客户端时钟偏差过大，`/api/auth/verify` 返回 `serverTime` 供提示。

### 2.8 多级转发（relay）

客户端要访问最终目标 C 时，把原请求发到根 A 的 `/api/relay` + 原 path，例如：

```text
GET /api/relay/api/files?page=1
X-App-Key: <A 的 appKey>
X-Timestamp: <A 的时间戳>
X-Nonce: <A 的 nonce>
X-Signature: <用 A 的 appSecret 对 /api/relay/api/files 签名>
X-Device-Id: <A 的 deviceId>
X-Device-Mac: <客户端 MAC>
X-Relay-Chain: <Base64URL(无 padding) JSON 数组>
```

`X-Relay-Chain` 每项：

```json
{"url":"http://10.0.0.5:8080","name":"B","appKey":"ak-b","timestamp":1760000000000,"nonce":"uuid","signature":"<hex>","deviceId":"<B 的 deviceId>"}
```

- 数组顺序 root → target；A 收到后弹出第 0 跳，剩余长度 1 时转发到最终目标（原 path、无链头），剩余长度 ≥2 时转发到下一跳的 `/api/relay` + 原 path（带剩余链）。
- 每跳独立签名：非最后一跳签 `/api/relay` + 原 path，最后一跳签原 path；`canonicalQuery` / `payloadHash` 所有跳相同。链里**没有 AppSecret**。
- 外层请求的签名头必须是链第 0 跳；链头第 0 跳的 `appKey/timestamp/nonce/signature/deviceId` 必须与外层请求头完全一致。`SignatureFilter` 的 canonical path 仍是实际收到的 `/api/relay/api/...`；设备绑定/限流等业务判断用剥离前缀后的 `/api/...`。
- 中间节点转发时追加 `X-Relay-Via`：`{appKey,name,url}[]` 的 Base64URL JSON。A 不追加自己（A 的审计无来源）；B 收到 `[A]` 后追加 B 给 C；C 审计来源 `[A,B]`。
- `/api/relay` 默认关闭；关闭时返回 `40320`。链头超过 `app.relay.max-chain-header-bytes` / 跳数超过 `max-hops` → `40010`；目标不可达 `50210`、非 JSON 下游错误 `50211`、下游 403 `50212`、超时 `50410`。
- 服务端对目标地址做 SSRF 守卫：只允许 http/https，禁止 userinfo/query/fragment；默认拒绝 loopback/link-local/云元数据，私网可用 `app.relay.allow-private-network` 放行（默认 true），可用 `app.relay.allowed-hosts` 白名单。
- A/B 作为中转节点必须升级并显式 `app.relay.enabled=true`；最终目标的数据接口可以仍是旧版，但来源审计需要升级。

## 3. 接口定义

### 3.1 `GET /api/health`（无需签名）

开启客户端身份强制校验后仍需带 `X-Device-Mac`（否则 40312 并封禁）；响应仍只有状态与时间。响应 `200`：

```json
{"code":0,"message":"ok","data":{"status":"UP","time":1760000000000},"timestamp":1760000000000}
```

### 3.2 `GET /api/auth/verify`（签名，无请求体）

用途：客户端「验证连接」，确认地址、appKey、appSecret、设备绑定状态。
开启 `client-identity-enforced` 时，verify 成功会按 MAC+IP 记录一次 verify 状态，供随后的 activate 顺序校验使用（文件接口不检查该状态）。

响应 `data`：

```json
{
  "appKey": "demo-app",
  "name": "Tauri 桌面客户端",
  "serverTime": 1760000000000,
  "deviceBindingEnabled": true,
  "storageDir": "/srv/scop-ftp/data",
  "defaultRootId": "work",
  "roots": [
    { "id": "work", "name": "工作资料", "path": "/srv/scop-ftp/work" },
    { "id": "media", "name": "媒体库", "path": "/srv/scop-ftp/media" }
  ],
  "device": {
    "deviceId": "3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
    "deviceName": "我的 MacBook",
    "boundAt": "2026-10-28T04:12:00Z"
  },
  "limits": {
    "maxFileSize": 5368709120
  }
}
```

`device` 在未激活时为 `null`。`storageDir` 是默认存储根的绝对路径（兼容旧客户端）；`roots` 是全部存储根（`id`/`name`/`path`，只读、由服务端配置，客户端不能修改），`defaultRootId` 是缺省根（未传 `root` 的接口使用它）。

### 3.3 `POST /api/auth/activate`（签名，JSON）

请求体：

```json
{"deviceId": "3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d", "deviceName": "我的 MacBook"}
```

- `deviceId`：UUID 格式（客户端首次启动生成并持久化），必填
- `deviceName`：1~64 字符，必填，可含中文

行为：

- 该 appKey 从未绑定 → 创建绑定，返回成功
- 已绑定同一 deviceId → 更新 deviceName，返回成功
- 已绑定其它 deviceId 且 `deviceBindingEnabled=true` → `40300`（403）
- 已绑定其它 deviceId 且 `deviceBindingEnabled=false` → 覆盖绑定为新设备，返回成功
- 开启 `client-identity-enforced` 时：该 MAC+IP 必须在 `verify-activate-window-ms`（默认 10 分钟）内成功调用过 `GET /api/auth/verify`，否则返回 `40313`，并把 MAC+IP 都写入黑名单（客户端应先 verify 再 activate）

响应 `data`：

```json
{
  "appKey": "demo-app",
  "deviceId": "3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
  "deviceName": "我的 MacBook",
  "boundAt": "2026-10-28T04:12:00Z",
  "serverTime": 1760000000000
}
```

### 3.4 `GET /api/files`（签名）

查询参数：

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `page` | 否 | 从 1 开始，默认 1 |
| `size` | 否 | 1~100，默认 20 |
| `keyword` | 否 | 文件名模糊匹配（忽略大小写），≤100 字符 |
| `root` | 否 | 存储根 id；不传=聚合所有根，传则只查该根（非法 id → 40000） |

响应 `data`：

```json
{
  "items": [
    {
      "id": "0f8fad5b-d9cb-469f-a165-70867728950e",
      "name": "设计稿.pdf",
      "description": "季度方案",
      "size": 1048576,
      "contentType": "application/pdf",
      "sha256": "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
      "rootId": "work",
      "createdAt": "2026-10-28T04:12:00Z"
    }
  ],
  "total": 1,
  "page": 1,
  "size": 20
}
```

- 查询范围为当前 `appKey` 上传的文件，按 `createdAt` 倒序；`rootId` 标识文件所属存储根（旧记录为 `default`）。
- `sha256` 为该文件完整内容的 SHA-256（小写 hex）。

### 3.5 `POST /api/files/upload`（签名，multipart/form-data）

表单字段：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `file` | 是 | 文件二进制，文件名可任意（服务端优先使用 `name` 字段） |
| `name` | 否 | 原始文件名（UTF-8，可含中文），优先于 part filename |
| `description` | 否 | 说明，≤200 字符 |
| `dir` | 否 | 目标目录，相对所选存储根的**已存在**目录（空/缺省=根目录），如 `docs`、`docs/2026` |
| `root` | 否 | 存储根 id（缺省=默认根），`dir` 与落盘都限制在该根内 |

请求头额外要求：

- `X-Content-Sha256`：文件完整内容的 SHA-256 小写 hex，**参与签名**（见 2.5）

限制：

- **单次 multipart 请求** ≤ 200MB（`40003` / HTTP 413），单请求 ≤ 210MB（Spring `max-request-size`）；**超过 200MB 请走分片上传（3.11~3.15）**
- 分片上传与元数据上限 `app.storage.max-file-size` 默认 **5GB**（可在环境文件里改）
- **不限文件类型**：任意扩展名或无扩展名文件都允许（已取消白名单与 `40002`）
- **按当前目录原名落盘（FTP 式）**：文件写入 `dir` 指定的已存在目录（在该 `root` 根内），磁盘文件名=展示名（清洗后）；重名自动改为 `report (1).pdf`、`report (2).pdf`…（`CREATE_NEW` 原子占位，最多 1000 次，超限 `50000`）
- `root` 必须是配置中存在的根 id，否则 `40000`；路径只在**该根内**解析，跨根 / 根外一律拒绝
- 文件名清洗：去掉路径分隔符、控制字符，最长 255 字符
- 目录规则：`dir` 不存在 → `40400`；是文件、绝对路径、`..`、越界、指向 `.upload-meta` → `40000`。目前**没有新建目录接口**，目标目录需先在服务器磁盘上存在

响应 `data`：同 3.4 的 FileMeta 对象。

错误补充：

- 文件哈希与 `X-Content-Sha256` 不一致 → `40001`（400），并删除已写入的文件
- 空文件 → `40000`（400）
- `dir` 不存在 → `40400`（404）；`dir` 非法/越界/是文件/内部目录 → `40000`（400）

### 3.6 `GET /api/files/{id}/download`（签名，无请求体）

按元数据 id 下载；响应头里 `Content-Disposition` 使用元数据里的展示名。任意路径下载见 3.9。

成功：HTTP 200，响应体为文件字节流，响应头：

```text
Content-Type: <存储时的 content type>
Content-Length: <size>
Content-Disposition: attachment; filename="fallback-name"; filename*=UTF-8''%E8%AE%BE%E8%AE%A1%E7%A8%BF.pdf
X-Content-Sha256: <sha256>
Cache-Control: no-store
```

失败：JSON 信封，例如不存在 → `40400`（404）。

### 3.7 `DELETE /api/files/{id}`（签名，无请求体）

按元数据 id 删除；按路径删除见 3.10。

响应 `data`：

```json
{"id": "0f8fad5b-d9cb-469f-a165-70867728950e"}
```

不存在 → `40400`（404）。删除元数据记录与磁盘文件。

### 3.8 `GET /api/files/dir?root=&path=`（签名，无请求体）

浏览指定**存储根**（`root`，缺省=默认根；服务端可配置多个根）下的真实目录内容。`path` 是相对该根的路径，省略或空串表示该根目录本身。

响应 `data`：

```json
{
  "path": "docs/2026",
  "parent": "docs",
  "truncated": false,
  "entries": [
    { "name": "10", "path": "docs/2026/10", "type": "directory", "size": null, "modifiedAt": "2026-10-28T06:00:00Z" },
    { "name": "report.pdf", "path": "docs/2026/report.pdf", "type": "file", "size": 12345, "modifiedAt": "2026-10-28T06:01:02Z" }
  ]
}
```

- `path`：当前目录相对根目录的路径，根目录为 `""`；`parent`：父目录相对路径，根目录为 `null`。
- `entries[].type`：`file` | `directory`；文件有 `size`（字节）与 `modifiedAt`（ISO-8601 UTC），目录 `size` 为 `null`。
- 排序：目录在前、文件在后，各自按名称忽略大小写升序；最多 2000 项，超出时 `truncated=true`。
- 跳过符号链接（不列出），避免越界；`.` 开头的隐藏文件正常列出，但**内部元数据目录 `.upload-meta`（任何层级）都隐藏**，且不可访问（`40000`）。
- 路径只能是相对路径且必须落在**指定根**内：绝对路径、`..`、符号链接逃逸 → `40000`（400）；目录不存在或不是目录 → `40400`（404）；`root` 非法 → `40000`。
- 本接口只返回目录条目元信息，不返回文件内容；上传/下载/删除分别见 3.5 / 3.9 / 3.10。

### 3.9 `GET /api/files/download?root=&path=`（签名，无请求体）

按相对指定存储根的路径下载**任意**该根内文件（供目录浏览视图使用），`path` 如 `docs/report.pdf`，`root` 缺省=默认根。

- 成功：HTTP 200 文件字节流，响应头与 3.6 一致：`X-Content-Sha256`（现读现算）、`Content-Length`、`Content-Disposition: attachment`、`X-Content-Type-Options: nosniff`、`Cache-Control: no-store`。
- 目录/绝对路径/`..`/越界/`.upload-meta`/非法 root → `40000`（400）；文件不存在 → `40400`（404）。
- 只读：不会修改磁盘文件。

### 3.10 `DELETE /api/files?root=&path=&recursive=`（签名，无请求体）

按相对指定存储根的路径删除**普通文件或目录**，`path` 如 `docs/report.pdf`、`docs/empty`、`docs/archive`；`root` 缺省=默认根；`recursive` 可选，默认 `false`；**只能删除该根内的内容**，跨根/根外严格拒绝。

- 成功：`{"code":0,"data":{"path":"docs/report.pdf"}}`（删除目录时返回该目录的相对路径）。
- `recursive=false`（默认，保持旧行为）：文件可删；目录**只允许空目录**，空目录成功、内容为空；非空目录 → `40000`（message「目录非空，不能删除」），内容全部保留。
- 文件：删除文件；若 SQLite 元数据中存在完全相同的 `storedPath` 记录，会一并删除该元数据记录（`recursive` 对文件无影响）。
- `recursive=true` 时的目录（一次性递归删除，语义等同删除该目录及其全部内容）：
  - 服务端自行遍历删除目录下所有文件与子目录，**不跟随 symlink**（只删除符号链接本身，不进入其指向的目标）。
  - **元数据联动清理**：该目录下所有文件在 SQLite 元数据中的 `storedPath` 记录一并删除，删除后按文件名/列表查询不再出现这些文件。
  - **嵌套存储根拒绝**：若该目录内包含另一个已配置的存储根（`app.storage.roots[]` 中的其他根），整体拒绝 → `40000`（message「目录包含其他存储根，不能删除」），且不删除任何内容。
  - **部分失败**：遍历/删除中途 IO 异常 → `50000`（message「目录删除失败（部分内容可能已删除）」），此时可能已有部分内容被删除且不可恢复，客户端应刷新目录确认实际状态。
- 根目录本身、`.upload-meta`、目标路径本身是 symlink、不存在 → `40000`/`40400`；越界/绝对路径/`..`/非法 root → `40000`。递归删除时目录**内部**的 symlink 只删除链接本身（不跟随），不视为越界。
- `recursive` 是查询参数，参与签名（`canonicalQuery`，规则同 2.4）；仅 `recursive=true` 表示递归，缺省或 `false` 保持默认非递归行为。
- 客户端交互：删除统一走二次确认弹窗（`modal.confirm`，删除按钮 danger，文案强调不可恢复）。目录会先调 `GET /api/files/dir`（3.8）判断是否非空：空目录提示「该目录为空，删除后不可恢复。」；非空目录强提示「目录非空（共 N 项），其中的文件和子目录会一并删除，且不可恢复」；列目录失败时保守提示「若目录非空，其中的文件和子目录会一并删除，且不可恢复」。确认后目录统一带 `recursive=true`（空目录也同样带，避免嵌套根下隐藏的 `.upload-meta` 造成「非空」误判）；删除文件不带 `recursive`。

### 3.11 `POST /api/files/upload/init`（签名，JSON）

分片上传的第一步，创建上传会话。

```json
{ "name": "报告.pdf", "root": "work", "dir": "docs", "size": 123456789, "sha256": "<64 hex>", "description": "可选，≤200" }
```
`root` 可选（缺省=默认根），分片会话会记录 rootId，后续 chunk/complete 按该根落盘。

- `name` 必填（清洗规则同 3.5）；`dir` 可选，`null` 或 `""` 表示根目录，规则同 3.5（不存在 → `40400`，非法/越界/内部目录 → `40000`）
- `size` 必填，>0 且 ≤ `max-file-size`（默认 5GB），超出 → `40003`（HTTP 413）
- `sha256` 必填，整文件 SHA-256 小写 hex（大小写不敏感）
- `description` 可选，≤200 字符

响应 `data`：

```json
{ "uploadId": "<uuid>", "chunkSize": 8388608 }
```

- `chunkSize` 由服务端配置 `app.storage.chunk-size` 决定（默认 8MB，可 `UPLOAD_CHUNK_SIZE` 覆盖）
- 会话存在 `${java.io.tmpdir}/scop-ftp-sessions/<uploadId>/`（`meta.json` + 分片文件），**不在存储根目录内**；启动时清理超过 `app.storage.session-ttl-ms`（默认 24h）的会话

### 3.12 `PUT /api/files/upload/{uploadId}/chunk?index=N`（签名，裸字节）

请求体为**该分片的原始字节**（`Content-Type: application/octet-stream`），长度 ≤ `chunkSize`（最后一片可更短）。

请求头额外要求：

- `X-Content-Sha256`：**该分片字节**的 SHA-256，**参与签名**（`payloadHash`）；与请求体不一致 → `40001`，且不保存该片
- 过滤器对 octet-stream 请求不缓存 body，边读边落盘

规则：

- `index` 从 0 开始，必须 < `ceil(size/chunkSize)`；非法 index、空片、单片超 `chunkSize`、累计超 `size` → `40000`
- 同一 `index` 重复 PUT 幂等（原子覆盖）
- 会话不存在 / `uploadId` 非 UUID → `40400` / `40000`
- 响应 `data`：`{ "uploadId": "...", "received": N }`（N=当前已收分片**数量**）
- 限流：每片消耗 1 个令牌

### 3.13 `GET /api/files/upload/{uploadId}`（签名，无请求体）

查询会话状态（断点续传用）：

```json
{ "uploadId": "...", "name": "报告.pdf", "size": 123456789, "chunkSize": 8388608, "received": [0, 1, 3] }
```

会话不存在 → `40400`。

### 3.14 `POST /api/files/upload/{uploadId}/complete`（签名，无请求体）

校验并拼装：

- 分片必须齐全（`0..ceil(size/chunkSize)-1`）、总大小 == `size`，否则 `40000` 且**保留会话**（可补传后重试）
- 拼装后整文件 SHA-256 必须等于 init 声明的 `sha256`，否则 `40001` 且**删除会话**与半成品
- 成功：按 3.5 的落盘规则写入 `dir`（原名，重名 `name (1).ext`），创建元数据，删除会话
- 响应 `data`：FileMeta（同 3.4，**不包含 storedPath**，内部字段不外泄）
- 限流：消耗 5 个令牌

### 3.15 `DELETE /api/files/upload/{uploadId}`（签名，无请求体）

取消上传并删除会话目录；响应 `data: { "uploadId": "..." }`；会话不存在 → `40400`。

### 3.16 `GET /api/files/search?root=&keyword=&limit=`（签名，无请求体）

在**指定存储根**内递归搜索文件/目录名（忽略大小写包含匹配），供客户端快速定位。`root` 缺省=默认根；不传 root 时**只搜默认根**（不聚合）。

- `keyword` trim 后 1~100 字符，否则 `40000`；`limit` 1~500，默认 100
- 只在指定根内递归，跳过 `.upload-meta`（任何层级）与所有符号链接（不跟随）；`root` 非法 → 40000
- 防御上限：最多扫描 50000 个条目、深度 20；命中数超过 limit 或触达上限时 `truncated=true`
- 排序：目录在前、文件在后，各自按名称忽略大小写升序；先全量排序再截断

响应 `data`：

```json
{
  "keyword": "报告",
  "truncated": false,
  "entries": [
    { "name": "报告.pdf", "path": "docs/2026/报告.pdf", "parent": "docs/2026", "type": "file", "size": 123, "modifiedAt": "2026-10-28T06:01:02Z" },
    { "name": "报告归档", "path": "docs/报告归档", "parent": "docs", "type": "directory", "size": null, "modifiedAt": "2026-10-28T06:00:00Z" }
  ]
}
```

- `parent`：根目录下条目的父目录为 `""`（空串）；目录 `size` 为 `null`
- 搜索不会返回文件内容，也不会出现在目录浏览/搜索结果之外的内部文件

### 3.17 `GET /api/storage/roots`（签名，无请求体）

返回服务端配置的存储根列表（客户端启动/切换服务器后拉取）。

响应 `data`：

```json
{
  "defaultRootId": "work",
  "roots": [
    { "id": "work", "name": "工作资料", "path": "/srv/scop-ftp/work" },
    { "id": "media", "name": "媒体库", "path": "/srv/scop-ftp/media" }
  ]
}
```

- `id`：`^[a-zA-Z0-9_-]{1,32}$`，唯一；接口 `root` 参数即此 id
- `name`：展示名（未配置时=id）；`path`：该根绝对路径
- yaml 默认配置只有一个根：`{id:"default", name:"默认目录", path:"./data"}`，`defaultRootId="default"`（不再有单根 `root-dir` 配置）

### 3.18 `POST /api/files/rename`（签名，JSON，令牌 1）

重命名存储根内的**文件或目录**（不改变所在目录，目录整体改名）。

请求体：

```json
{ "root": "work", "path": "docs/报告.pdf", "newName": "季度报告.pdf" }
```

- `root` 可选（缺省=默认根；非法 → `40000`）
- `path` 相对该根，规则同 3.9/3.10（绝对路径、`.`/`..`、NUL、symlink、`.upload-meta`、跨根 → `40000`；不存在 → `40400`）
- `newName`：trim 后 1~255 字符，**单段名字**；拒绝 `/`、`\`、NUL、控制字符、`.`、`..`、空、`.upload-meta`；与已存在目标（含仅大小写差异）冲突或重命名为自身 → `40000`，**不覆盖任何文件**
- 支持普通文件与目录；根目录本身、内部元数据、symlink 拒绝

响应 `data`：

```json
{ "oldPath": "docs/报告.pdf", "newPath": "docs/季度报告.pdf", "type": "file" }
```

- `type`：`file` | `directory`；目录重命名时返回目录相对路径
- 元数据同步：文件更新 `storedPath`/`name`；目录把该目录下所有记录的 `storedPath` 前缀替换（SQLite 事务；多根写各自元数据库）
- 若目录包含另一个已配置存储根，拒绝（`40000`），防止把别的根整体搬走
- 失败：非法参数/冲突/越界 `40000`；不存在 `40400`；IO 异常 `50000`

### 3.19 `GET /api/files/content?root=&path=`（签名，无请求体，令牌 1）

读取存储根内**UTF-8 文本文件**内容，供客户端在线编辑。

响应 `data`：

```json
{
  "path": "docs/readme.txt",
  "content": "第一行\n第二行\n",
  "size": 17,
  "sha256": "<64 hex>",
  "editable": true
}
```

- 只允许指定 root 内**已存在的普通文件**；目录/symlink/`.upload-meta`/绝对路径/`..`/跨根/非法 root → `40000`；不存在 → `40400`
- 大小上限 `app.storage.max-edit-bytes`（默认 2 MiB）→ `40000`「文件过大，不支持在线编辑」
- 严格 UTF-8 解码；二进制/其它编码 → `40000`「二进制或非 UTF-8 文件不支持在线编辑」
- `sha256` 为当前内容摘要，保存时可回传做并发校验

### 3.20 `PUT /api/files/content`（签名，JSON，令牌 1）

用新内容**覆盖保存**已存在文件（不新建、不改名、不换目录）。

```json
{ "root": "work", "path": "docs/readme.txt", "content": "新内容\n", "expectedSha256": "<打开时读到的 sha>" }
```

- `content` 必须存在（允许空串），UTF-8 字节 ≤ `max-edit-bytes`，超限 `40000`
- `expectedSha256` 可选；与当前文件 SHA 不一致 → `40000`「文件已被修改，请重新加载后再保存」，**不写入**
- 目标不存在 → `40400`；目录/symlink/`.upload-meta`/越界/跨根/非法 root → `40000`
- **原子覆盖**：同目录隐藏临时文件 + `ATOMIC_MOVE`（失败清理临时文件），尽力保留原权限
- 若该根 SQLite 元数据有 `storedPath==path` 的记录，同步更新其 `sha256`/`size`；无记录不新建
- 响应：`{"path":"docs/readme.txt","size":123,"sha256":"<新 sha>"}`

### 3.21 `POST /api/files/extract`（签名，JSON，令牌 5）

解压存储根内的 `.zip` 到**同级同名目录**。

```json
{ "root": "work", "path": "docs/包.zip" }
```

- 只接受已存在的普通 `.zip`（大小写不敏感）；非 zip/损坏/目录/symlink/跨根 → `40000`/`40400`
- 目标目录：去掉 `.zip` 的文件名；已存在则追加 ` (1)`…` (999)`（最多 1000 次，超限 `50000`），**不覆盖任何已有内容**
- **自动剥单层包裹**：若所有 entry 都在唯一一个顶层目录下（存在该目录 entry 或全部以 `name/` 开头），自动去掉 `name/`，内容直接落在目标目录（如 `pkg/a.txt`、`pkg/sub/b.txt` → `目标/a.txt`、`目标/sub/b.txt`）；根下多文件/多顶层目录/单个根文件/空 zip 保持原结构
- 安全校验（任一失败中止并尽力删除本次创建的目录，`40000`）：
  - entry 名为空/绝对路径/含盘符/反斜杠/NUL/`..`/`.`/空段/`.upload-meta`（任意层级），目标必须落在该 root 内
  - 拒绝 symlink entry（Unix mode 0120000）、加密 zip、同名重复 entry
  - zip bomb 上限：`max-extract-entries` 20000、`max-extract-total-bytes` 1 GiB、`max-extract-entry-bytes` 200 MiB；zip64 暂不支持（`40000`）
  - 空 zip 允许（创建空目录）
- 解压出的文件**不写入文件元数据表**（非上传接口产物），但可正常浏览/搜索/下载/在线编辑/重命名/删除
- 响应：`{"targetDir":"docs/包","entries":12,"bytes":34567}`

### 3.22 `POST /api/terminal/exec`（签名，JSON，令牌 5）

在服务器本机执行一条 shell 命令（远程终端）。**高危命令会被拒绝且不会启动进程**。

```json
{ "sessionId": "sess-7f3a...", "command": "ls -la", "cwd": "/Users/me" }
```

- `sessionId`：8-64 位 `[A-Za-z0-9_-]`，客户端生成；服务端按 session 维护当前目录（TTL 30 分钟、最多 50 个，LRU 淘汰），`appKey` 之间隔离
- **安全范围**：终端只能访问 `app.storage.roots` 内的目录。`cwd` 可选（仅首次/新 session 生效），必须在某个存储根内；否则 `/tmp`、`/etc`、`~` 等越界路径一律拒绝
- `root` 可选：指定首次会话的存储根（合法 id，否则默认第一个根）
- `command`：1-4096 字符、单行；`cd` / `cd <path>` / `cd -` 由服务端更新会话 cwd（仅限根内，不启动进程），`cd ~` 与根外目录 → `40000`
- **路径参数防护**：除命令本身的绝对路径外，参数与重定向目标（`cat /etc/hosts`、`cd /tmp`、`echo > /tmp/x`、`../` 逃逸、symlink 逃逸）越出存储根 → `40000`；`/bin/echo`、`/usr/bin/env` 等可执行路径允许
- 拦截消息：
  - 越界路径：`命令涉及非安全目录：<token>（终端仅允许访问 app.storage.roots 内的目录）`
  - 越界 `cd`：`目录不在安全范围：<path>（终端仅允许访问 app.storage.roots 内的目录）`
- 后续以服务端会话 cwd 为准
- 执行：`/bin/sh -lc`，合并 stdout/stderr；超时（`app.terminal.timeout-seconds` 默认 10）→ `exitCode=-1` + 中文提示；输出超过 `app.terminal.max-output-bytes`（默认 256 KiB）→ `truncated=true`；非零退出仍 `code=0`，由 `exitCode` 表达
- **高危命令** → HTTP 400 / `40000`，message：`高危命令已禁止执行：<命令>（原因：<原因>）`
- `app.terminal.enabled=false` → `40000`「终端功能未启用」
- 响应 `data`：

```json
{
  "sessionId": "sess-7f3a...",
  "command": "ls -la",
  "output": "total 0\n...",
  "exitCode": 0,
  "cwd": "/Users/me",
  "truncated": false,
  "durationMs": 12
}
```

> ⚠️ 这是护栏不是沙箱：命令以服务进程用户身份执行，拦截规则只能覆盖常见高危命令/常见路径越界，无法防御刻意绕过（例如脚本内部访问根外文件）；暴露公网/多人使用时请在对应环境文件里设 `app.terminal.enabled: false`。

### 2.7 操作审计与请求追踪

- 所有签名 `/api/**` 请求（`/api/health`、OPTIONS 除外）都会写审计日志，响应头返回 `X-Request-Id`（UUID），可与日志中同名字段关联。
- 日志双写：`./logs/audit/audit-yyyy-MM-dd.jsonl`（一行一 JSON）+ `./logs/audit/audit.sqlite3`（表 `audit_log`）；配置 `app.audit.enabled/dir/max-body-chars/max-history-days`，环境变量 `UPLOAD_AUDIT_*`。
- 字段包含：`ts/requestId/appKey/clientName/deviceId/clientIp/method/path/query/action/root/targetRoot/path/targetDir/sessionId/terminalCommand/httpStatus/durationMs/success/error/contentSha256/requestBytes/responseBytes/body/userAgent/fileName`；`body` 会脱敏并截断（`content/appSecret/password/token/secret` → `***`），签名/nonce/密钥/文件正文不落盘。
- 客户端通过 3.25 的 `GET /api/audit/logs` 查询（只返回当前 appKey）；也可直接 `grep` JSONL 或用 `sqlite3` 查 `audit_log`。

### 3.23 `POST /api/files/copy`（签名，JSON，令牌 5）

原地复制文件/目录，自动生成 `_副本` 名字（不覆盖）。

```json
{ "root": "work", "path": "docs/a.txt" }
```

- 源：存储根内普通文件或目录；拒绝绝对路径/`..`/symlink/`.upload-meta`/越界
- 命名：文件保留最后一段扩展名 → `a_副本.txt`、`archive.tar_副本.gz`、`.env_副本`、`README_副本`；目录 `abc_副本`；重名依次 `_副本 (1)`、`_副本 (2)`…（最多 1000 次 → `50000`）
- 目录递归复制（含空目录、不跟随 symlink）；失败删除本次目标并 `40000`
- **不写文件元数据表**（与解压产物一致）
- 响应：`{"root":"work","path":"docs/a_副本.txt","type":"file","name":"a_副本.txt"}`

### 3.24 `POST /api/files/move`（签名，JSON，令牌 5）

移动文件/目录到另一个目录，可跨存储根。

```json
{ "root": "work", "path": "docs/a.txt", "targetRoot": "media", "targetDir": "sub" }
```

- `targetRoot` 省略/空 = 同根；`targetDir` 相对目标根（空串 = 目标根根目录，必须已存在且为目录）
- 禁止移动到自身/自身子目录（`40000`「不能移动到自身或子目录」）；目标同名 → `40000`「目标位置已存在同名文件或目录」，不覆盖
- 同根 `Files.move`；跨根复制+删除（目录递归、失败尽量回滚）
- 同步元数据（SQLite）：同根更新 `storedPath`（目录前缀），跨根更新 `rootId` 并迁移记录
- 响应：`{"root":"media","path":"sub/a.txt","type":"file","name":"a.txt"}`

### 3.25 `GET /api/audit/logs`（签名，令牌 1）

查询当前 appKey 的操作审计日志（SQLite `audit_log`），按 `created_at DESC, id DESC`。客户端日志抽屉默认用 `category=both` 合并展示实际操作与连接日志；底层所有 `/api/**` 请求仍完整保留在审计库中。

Query：
- `page` 默认 1；`size` 默认 50，范围 1..200（超出 → `40000`）
- `action` 可选精确值（`upload`/`download`/`delete`/`rename`/`copy`/`move`/`extract`/`terminal.exec`/`auth.verify`/`auth.activate` 等）
- `category` 可选 `operation` | `connection` | `both`：`operation` 只返回服务器实际操作（上传/下载/删除/重命名/拷贝/移动/解压/读取内容/保存内容/终端等），`connection` 只返回 `auth.verify`/`auth.activate`，`both` 返回两者并集；不传则返回全部审计记录
- `success` 可选 `true`/`false`
- `keyword` 可选，模糊匹配 `path` / `request_id` / `path_param` / `file_name` / `terminal_command`
- `from`/`to` 可选 ISO-8601（如 `2026-10-30T00:00:00+08:00` 或 `...Z`）；`from > to` → `40000`

响应 `data`：

```json
{
  "items": [
    {
      "id": 1,
      "ts": "2026-10-30T09:35:57.068349+08:00",
      "requestId": "3156da9b-...",
      "appKey": "demo-app",
      "clientName": "Tauri 桌面客户端",
      "deviceId": null,
      "clientIp": "127.0.0.1",
      "method": "GET",
      "path": "/api/audit/logs",
      "query": "page=1&size=50",
      "action": "audit.list",
      "root": null,
      "targetRoot": null,
      "pathParam": null,
      "targetDir": null,
      "sessionId": null,
      "terminalCommand": null,
      "body": null,
      "contentSha256": null,
      "requestBytes": null,
      "responseBytes": 1234,
      "durationMs": 3,
      "httpStatus": 200,
      "success": true,
      "error": null,
      "userAgent": "ScopeFTP-client/0.1.0",
      "fileName": null,
      "relayVia": []
    }
  ],
  "total": 1,
  "page": 1,
  "size": 50,
  "hasMore": false
}
```

- 只能查当前验签通过的 appKey，不能跨 appKey；查询自身写入一条 `action=audit.list`。
- `relayVia`：`{appKey,name,url}[]`，中间节点写入来源链；B 为 `[A]`，C 为 `[A,B]`，A 自己转发请求为空。旧库/无来源时为 `[]`。中转节点上收到 `/api/relay` 的请求 `action` 会加 `relay.` 前缀（如 `relay.files.list`）。
- 审计 DB 未启用/不可用时返回 `50000`，message 为「审计日志存储不可用/查询失败」。
- 日志是异步写入，刚完成的请求可能有毫秒级延迟；字段含义见 2.7。

## 4. 错误码表

| code | HTTP | message（中文） | 说明 |
| --- | --- | --- | --- |
| 0 | 200 | ok | 成功 |
| 40000 | 400 | 请求参数错误 | 校验失败、缺参数、空文件 |
| 40001 | 400 | 文件内容校验失败 | SHA-256 不一致 |
| 40003 | 413 | 文件超过大小限制 | 超过 maxFileSize |
| 40010 | 400 | 转发链无效 | 链头缺失/非法/超长/跳数超限/appKey 重复/路径非法 |
| 40100 | 401 | 签名无效 | 缺签名头 / 签名不匹配 |
| 40101 | 401 | 请求已过期 | 时间戳超窗 |
| 40102 | 401 | 请求重复 | nonce 已使用 |
| 40103 | 401 | 客户端未授权 | appKey 不存在或禁用 |
| 40110 | 401 | 转发目标鉴权失败 | 下游 401（非 40101） |
| 40300 | 403 | 设备未授权 | 设备绑定不匹配 |
| 40310 | 403 | IP 已被封禁 | 命中 `client_blacklist_ip`（所有 `/api/**` 生效） |
| 40311 | 403 | 设备物理地址已被封禁 | 命中 `client_blacklist_mac`（任一条命中即拦截） |
| 40312 | 403 | 缺少设备标识 | 开启身份强制后缺 `X-Device-Mac` 或取不到 IP（同时写入黑名单） |
| 40313 | 403 | 接口调用顺序错误 | activate 前没有同 MAC+IP 的近期 verify（同时写入黑名单） |
| 40320 | 403 | 转发功能未开启 | 本跳 `app.relay.enabled=false` |
| 40400 | 404 | 资源不存在 | 文件不存在 |
| 42900 | 429 | 请求过于频繁 | 触发限流 |
| 50000 | 500 | 服务器内部错误 | 未捕获异常 |
| 50210 | 502 | 转发目标不可达 | DNS/连接失败 |
| 50211 | 502 | 转发目标返回异常 | 3xx、非 JSON 429/5xx 等 |
| 50212 | 502 | 转发目标拒绝来源身份 | 下游 403（非设备绑定） |
| 50410 | 504 | 转发目标超时 | 下游超时 |

限流：默认令牌桶容量 1000、每秒补 1 个；单文件上传与分片 `init`/`complete` 各消耗 5 个令牌，分片 PUT/状态/取消及其它接口各 1 个（可通过 `app.security.rate-limit.*` / `UPLOAD_RATE_LIMIT_*` 配置）。

## 5. 客户端实现契约（Tauri / Rust）

### 5.1 配置

保存位置：`app_config_dir()/config.json`（macOS 例：`~/Library/Application Support/com.scopftp.client/config.json`）。
**多服务器（v3）**：每台服务器独立 `deviceId`/`deviceName`，用 `kind` + `parentId` 组成转发树：

```json
{
  "configVersion": 3,
  "activeServerId": "a-uuid",
  "servers": [
    {
      "id": "a-uuid",
      "kind": "direct",
      "name": "A",
      "serverUrl": "http://10.0.0.1:8080",
      "deviceId": "a-device-uuid",
      "deviceName": "我的 MacBook",
      "appKey": "ak-a",
      "appSecret": "sk-a"
    },
    {
      "id": "b-uuid",
      "kind": "relay",
      "parentId": "a-uuid",
      "name": "B",
      "serverUrl": "http://10.0.0.5:8080",
      "deviceId": "b-device-uuid",
      "deviceName": "我的 MacBook",
      "appKey": "ak-b",
      "appSecret": "sk-b"
    }
  ]
}
```

- `kind=direct` 是直连根；`kind=relay` 的 `parentId` 指向上一跳，可任意深度（`A → B → C → …`）。
- 每次请求按 `activeServerId` 解析 root→target 链：外层请求到 root，携带 `X-Relay-Chain`，每跳使用自己的 `appKey/appSecret/deviceId` 签名；链中**不出现 secret**。
- 旧单服务器配置（v1）与 v2 平铺列表在加载时自动迁移为 v3（全部按 direct 处理）；新客户端不再写 v2。
- `serverUrl` 保存时去掉末尾 `/`，必须是 `http(s)://host[:port][/子路径]`，不允许 query/fragment/userinfo。
- relay 的 AppKey/AppSecret 必填，**不回退内置值**；direct 保存时也要求显式填写（必须与服务端启动时一致），旧配置的空值仍可回退内置值。secret 只保存在本地 config.json（0600），不返回前端。
- `deviceId` 每台服务器首次保存时用 UUID v4 生成并持久化；`configVersion=3` 的配置不允许被旧客户端安全读取（旧客户端可能把 relay 当 direct 绕过 A）。
- 允许删到空配置：`servers=[]` 时 `activeServerId=""`、`activeServer=null`，重启不会自动补默认服务器；首次安装（文件不存在/空文件）才生成一台默认 direct。
- 删除服务器会级联删除其整棵子树、清理这些 serverId 的续传状态；active 被删时回退父级/剩余 direct，空则清空。

### 5.2 Tauri 命令（`invoke`）

| 命令 | 参数 | 返回 |
| --- | --- | --- |
| `get_config` | 无 | `{configVersion, activeServer: ServerView \| null, activeServerId, servers:[ServerView], appKey, hasSecret}`；`ServerView` 含 `id/name/serverUrl/deviceId/deviceName/appKey/effectiveAppKey/hasCustomSecret/configured/kind/parentId/parentName/chainPath/chainHops/rootId/rootName`，不返回 secret |
| `save_server` | `{id?, name, serverUrl, deviceName, appKey?, appSecret?}` | 新建/编辑 **direct**；`id` 缺省=新增（生成 id/deviceId 并自动设为 active），提供则编辑（保留 deviceId）。AppKey/AppSecret 必填；`appSecret` 为 `null`/省略时保留原值，空串清除自定义 secret，非空保存新值 |
| `save_relay` | `{id?, parentId, name, serverUrl, deviceName, appKey, appSecret?}` | 新建/编辑 **relay**；新建生成独立 id/deviceId 并设为 active，编辑保留 id/deviceId；parent 必须存在且不能成环；`appSecret` 为 `null`/空时保留已保存值（新建必填） |
| `verify_relay_preview` | `{parentId, existingId?, name, serverUrl, deviceName, appKey, appSecret?}` | 不落盘：按 parent 链 preflight 祖先 + 验证临时 target，返回 3.2 的 data；新建用临时 deviceId，不激活 target |
| `delete_server` | `{id}` | 同 `get_config`；级联删除整棵子树及其续传状态；允许删到空配置；active 被删回退父级/剩余 direct，空则 `activeServer=null` |
| `set_active_server` | `{id}` | 同 `get_config` |
| `verify_connection` | 无 | 3.2 的 data |
| `activate` | 无（用已存配置的 deviceId/deviceName） | 3.3 的 data |
| `upload_file` | `{path, description?, uploadId, dir?, root?}` | FileMeta（3.4）；`dir` 为相对所选根的已存在目录，缺省=根目录；`root` 缺省=默认根。**>32MB 内部自动走 3.11~3.15 分片上传并断点续传，命令签名不变** |
| `list_files` | `{page, size, keyword?, root?}` | 3.4 的 data；`root` 缺省=聚合所有根 |
| `download_file` | `{id, targetPath}` | 保存后的绝对路径字符串（按 id） |
| `delete_file` | `{id}` | `null` |
| `download_server_file` | `{path, targetPath, root?}` | 保存后的绝对路径字符串（按相对 root 路径，3.9） |
| `delete_server_file` | `{path, recursive?, root?}` | `null`（按相对 root 路径，3.10）；`recursive=true` 用于删除前已确认的非空目录（客户端先列目录判断并强提示） |
| `list_server_dir` | `{path?, root?}` | 3.8 的 data（`path` 省略/空=根目录） |
| `search_server_files` | `{keyword, limit?, root?}` | 3.16 的 data（`limit` 默认 100；`root` 缺省=默认根） |
| `rename_server_file` | `{path, newName, root?}` | 3.18 的 data；`newName` 仅单段名字，冲突/非法名返回中文错误 |
| `read_server_file_content` | `{path, root?}` | 3.19 的 data（UTF-8 文本 ≤2MiB） |
| `save_server_file_content` | `{path, content, expectedSha256?, root?}` | 3.20 的 data（覆盖保存，sha 冲突返回中文错误） |
| `extract_zip_file` | `{path, root?}` | 3.21 的 data（解压到同级同名目录） |
| `open_server_file` | `{path, root?}` | 下载到本机临时目录后用系统默认应用打开（双击文件）；失败返回中文错误 |
| `terminal_exec` | `{sessionId, command, cwd?, root?}` | 3.22 的 data；签名 POST `/api/terminal/exec`，cwd/路径仅限存储根，高危/越界返回 40000 中文原因，不自动重试 |
| `copy_server_path` | `{path, root?}` | 3.23 的 data；原地生成 `_副本`，失败透传中文错误 |
| `move_server_path` | `{path, root?, targetRoot?, targetDir}` | 3.24 的 data；`targetDir` 为目标根内相对目录（空串=根），冲突/自身子目录返回 40000 |
| `list_audit_logs` | `{page, size, action?, success?, keyword?, from?, to?}` | 3.25 的 data；服务端强制当前 appKey，`size` 1..200 |
| `list_storage_roots` | 无 | 3.17 的 data |
| `file_info` | `{path}` | `{name, size}`（只读辅助命令：读取本地待上传文件信息，不涉密钥、不发网络） |

命令错误统一返回前端可展示的中文字符串（Rust `Result<T, String>`），
例如：`签名无效，请检查 AppSecret`、`设备未授权，请重新激活`、`网络错误：…`。

### 5.3 事件

上传进度事件名：`upload-progress`，payload：

```json
{"uploadId": "上传批次 id", "sent": 1048576, "total": 10485760}
```

前端在调用 `upload_file` 前 `listen('upload-progress', …)`，按 `uploadId` 过滤。

### 5.4 Rust 侧要求

- HTTP 全部走 `reqwest`（rustls-tls），不经过 WebView，不启用 `@tauri-apps/plugin-http`
- 上传：先流式计算 SHA-256（第一遍），再以流式 body 上传（第二遍），边读边发进度事件
- 下载：流式写入目标文件，同时计算 SHA-256，与响应头 `X-Content-Sha256` 不一致则删除文件并报错
- 每次请求生成新的 `X-Nonce`（UUID）与当前时间戳
- 启动时读取本机物理地址（macOS 优先 `en0`/`en1`，Linux 读 `/sys/class/net`，Windows 用 `getmac`），**每个请求（含 `/api/health`）都带 `X-Device-Mac`**；读不到 MAC 时本地直接报错、不发出请求（避免被服务端当成缺身份客户端封禁）
- 签名函数与第 2 节逐字一致，必须提供 Rust 单元测试覆盖第 2.3 节两个测试向量
- 断点续传状态存本地 `uploads.json`（与配置同目录，0600）：记录 serverId/dir/name/size/mtime/sha256/uploadId，不含文件内容与密钥；启动清理 >24h 记录
- 普通 JSON/DELETE 请求对 429/网络/5xx 做最多 3 次退避重试；**relay 的非幂等写请求不自动重试**；分片 PUT 每片失败重试 3 次，`complete` 缺片 `40000` 时保留状态可续传
- relay 请求：`auth_headers` 生成链头（每跳独立 timestamp/nonce/signature），`X-Device-Id` 用 root；`build_url` 在 relay 时自动加 `/api/relay` 前缀，所有显式上传/分片/下载 URL 也必须走 `build_url`
- relay 访问 target 前必须 preflight 祖先：按 `[A]`、`[A,B]`… 前缀逐跳 `verify_connection()` + `activate()`，再验证 target；中间跳未激活会 40300。最终 target 的 activate 仍由 UI 在 verify 后调用

### 5.5 前端页面（React + antd）

- 语言：`ConfigProvider locale={zhCN}`；主题使用 antd 5 默认 + 少量 token（圆角 8、主色 #1677ff）；用 `<App>` 提供 message/modal 上下文
- 布局：左侧固定 Sider（约 280px）+ 右侧内容区，**左右两栏 FTP 式**，不再有顶部导航 Tab：
  1. **左侧服务器面板**：服务器列表（每项名称 + 地址，active 高亮，点击切换；铅笔图标进入该服务器详情/编辑）+「+ 添加服务器」；当前服务器卡片显示连接状态 Tag、地址、设备名、最大文件大小 +「验证连接」「设置」；其下为**「存储目录」列表**（来自 3.17 的 roots，显示 `name`、Tooltip 全路径，当前高亮，点击切换根并重置路径）；未配置时显示「去设置」引导
  2. **右侧目录浏览器**：**单行首行**：左侧路径（文件夹图标 + 面包屑，首段=当前根名称，可点击跳转、超长省略）；右侧 4 个纯图标按钮（无文字、Tooltip）：查找（VSCode 式命令面板，只在当前存储根内递归搜索 3.16，同时命中文件与目录；↑↓ 选择、Enter 打开、Esc 关闭）、上传、刷新、终端；无整块标题栏，返回上级通过面包屑完成（点击选择文件兜底）；所有请求都带当前 `rootId`；目录页工具栏为纯图标（查找 ⌘P / 上传 / 刷新 / 终端，均有 Tooltip），左侧仅路径；底部可打开 **远程终端面板**（40vh、深色、命令历史、cwd 显示、清空/关闭），命令直接在输出区输入（内联 prompt），Tab 补全当前目录/子目录路径（唯一候选补全、多候选列出）；右键文件/目录「在此打开终端」会把 cwd 定位到文件所在目录/该目录本身，高危与越界路径拦截后弹警告。

在线编辑弹窗内置 **Monaco（VS Code 内核）**：主题 `vs`、无 minimap、自动换行、自动布局；按扩展名自动识别语言（txt/json/js/ts/html/css/java/py/xml/yaml/sql/sh/md/...→plaintext），顶部「语言」下拉可手动切换（30 种中文标签）并立即高亮；顶部「Tab」下拉可选 2/4 空格（默认 4，`insertSpaces=true`、关闭自动检测缩进，选择持久化到 localStorage 并即时生效）；⌘S/Ctrl+S 保存，未关闭需确认，保存失败保留内容。

**Finder 式列表**：列 `名称` / `修改日期`（`YYYY年M月D日 HH:mm`）/ `大小`（目录 `--`）/ `种类`（文件夹/PDF 文稿/PPT 演示文稿/Word 文稿/Excel 表格/文本文稿/图片/压缩包/音频/视频/文件）；单击选中（Finder 蓝），↑/↓（Home/End）移动选中，Enter 或对选中行再次单击进入行内重命名，双击文件用系统默认应用打开、双击文件夹进入；无「操作」列，下载/删除/编辑/解压/终端/拷贝/移动走**行右键菜单**（在线编辑 / 解压仅 .zip / 在此打开终端 / 拷贝 / 移动到… / 下载 / 重命名 / 删除）；「移动到…」弹窗可切换存储根并浏览目录选择目标（重命名直接在该行名称单元格**内联编辑**：自动聚焦并全选原名，回车/失焦保存、Esc 取消，仅单段名字；删除目录前先 `GET /api/files/dir` 判断是否为空：空目录普通确认，非空目录强提示「目录非空（共 N 项），其中的文件和子目录会一并删除，且不可恢复」，确认后目录统一带 `recursive=true` 调 3.10 递归删除（空目录同样带，避免嵌套根下隐藏 `.upload-meta` 造成非空误判）；文件删除不带 `recursive`）；`Table`（名称、类型、大小、修改时间、操作[下载/删除]，目录行点击进入）；文件行下载走 3.9、删除走 3.10（删除均为 `modal.confirm` 二次确认，不可恢复）；空目录/truncated 提示
  3. **拖拽上传（核心）**：监听 Tauri 原生拖放 `getCurrentWebview().onDragDropEvent`——`enter/over` 显示整块遮罩「松开上传到 <当前目录>」，`drop` 后过滤目录/空文件，按当前 `path` 串行上传（`upload_file` 的 `dir`），复用 `upload-progress` 显示进度，完成后汇总提示并刷新；上传中禁用重复拖入。**>32MB 自动走分片上传（3.11~3.15），断点续传状态存本地 `uploads.json`（serverId/dir/name/size/mtime → uploadId）**
  4. **设置/服务器详情**（从左侧服务器铅笔进入，点左侧服务器返回目录）：`Form`（服务器名称、服务器地址、AppKey、AppSecret、设备名称）；保存时 AppKey/AppSecret 必填（必须与服务端启动时一致），**relay 额外禁止回退内置值**（编辑时留空/掩码=保留）；显示 deviceId、绑定状态、服务端限制（最大文件大小）、服务端存储目录、可点击转发链；设置页提供「验证连接」和「保存」（编辑非 active 服务器也可离线保存）；删除已保存服务器，允许删到空配置；**不再有单独的激活设备按钮**；「验证连接」= 验证 → 保存 → 激活设备：添加页先用当前表单值直接验证（不落盘），成功后才添加服务器并激活设备（激活失败保留服务器并提示，验证失败不保存不添加）；编辑页先保存未保存修改，再验证并激活当前设备（非当前服务器需先在左侧切换，验证成功才保存修改）；激活前客户端会先调 verify（服务端要求 verify→activate 顺序，乱序会被 40313 封禁）；离开时保留未保存确认；长文本必须换行/省略，不允许溢出卡片或产生页面横向滚动条
- **转发 UI（v3）**：左侧服务器列表按 `parentId` 递归成树，relay 行带「转发」Tag；A/B/C 的「转发目标」卡片列出直接子节点，点击切换 active；只有 active 且 `connection.status==="connected"` 才显示添加 `+`。A 未连接时 B 的地址/凭据表单无法出现；B/C 表单包含「目标地址 + 目标 AppKey/AppSecret + 设备名」。新建 relay 走 `verifyRelayPreview → saveRelay`（按钮「验证并添加」）；编辑已保存 relay 走 `saveRelay`，不要求父链连接。右侧标题显示「B（经 A → B 转发）」。审计详情「来源/转发链」按 `relayVia` 的 appKey（其次 url）映射本地服务器，可点击切换；找不到时显示纯文本。删除已保存服务器允许删到空配置，新建表单不显示删除。
- 未配置时：右侧显示 `Empty` + 添加服务器引导；**空配置（`servers=[]`）不再自动补默认服务器**，只有首次安装/空文件才生成默认 direct。
- 非 Tauri 环境（浏览器打开 vite dev）：捕获 invoke 异常，显示 `Alert`「请使用桌面客户端运行」
- 不引入多余 UI 框架；图标用 `@ant-design/icons`

## 6. 服务端实现约束

- 包名 `com.wenshan.upload`，模块目录 `server/`
- 统一响应 `ApiResponse<T>`，异常处理 `@RestControllerAdvice`
- 过滤器 `ClientGuardFilter`（客户端身份/黑名单，`Ordered.HIGHEST_PRECEDENCE`）→ `SignatureFilter`（签名校验，`HIGHEST_PRECEDENCE + 10`）→ `RelayForwardFilter`（`/api/relay`，`HIGHEST_PRECEDENCE + 20`），
  JSON 请求体用自定义 `CachedBodyHttpServletRequest` 缓存后计算哈希并向下传递；
  multipart/octet 请求**不得**缓存整个 body，relay 过滤器用 JDK HttpClient 的流式 BodyPublisher 直接桥接原始流
- `RelayForwardFilter` 在 `DispatcherServlet` 之前执行：弹 `X-Relay-Chain` 第 0 跳、校验与外层签名头一致、追加 `X-Relay-Via`、按剩余链是 `/api/relay` 还是最终原 path 转发；A/B 的 `SignatureFilter` 审计路径仍保留 `/api/relay` 前缀，`AuditAction` 映射为 `relay.*`；设备绑定/限流成本用剥离前缀后的业务路径
- relay 默认关闭 `app.relay.enabled=false`；`app.relay.*` 配置 `max-hops`、header 字节上限、connect/request/upload 超时、`allow-private-network`、`allow-loopback`、`allowed-hosts`
- 非 UTF-8 请求体 → `40000`
- `/api/health` 与 `OPTIONS` 跳过签名；开启客户端身份强制后，两者仍受 `ClientGuardFilter` 的 MAC/IP 检查约束
- **SQLite 元数据**：文件/设备元数据持久化在 `<root>/.upload-meta/metadata.sqlite3`（旧 `files.json`/`devices.json` 自动迁移备份），
  启动时加载到内存索引，变更在写锁内以短事务同步落 SQLite，成功后替换内存快照
- **存储根唯一配置**：`app.storage.roots[]`（每项 `id/name/path`，第一个为默认根；写在各环境文件 `application-<env>.yml` 里，如 dev 是 `./data`、docker 是 `/app/data`）。启动时解析为绝对路径、
  创建目录并校验可写；**根目录只放用户文件/目录**，内部元数据在 `<root>/.upload-meta/`，multipart 临时文件在
  `${java.io.tmpdir}/scop-ftp-tmp`；旧单根 `files/meta/tmp` 布局不再自动迁移（需手动整理）。
  **所有文件读写删除都限制在 root 内**：拒绝绝对路径、`..` 段、以及指向 root 外的符号链接（normalize + `toRealPath` 校验），并拒绝内部目录 `.upload-meta`
- 客户端只能指定**相对 root 且已存在**的上传目录（`dir`）与相对 root 的下载/删除路径，不能指定 root 外路径；`verify` 只回显只读的 `storageDir`
- 配置项使用 `@ConfigurationProperties(prefix = "app")`；只有客户端凭证支持环境变量注入（`UPLOAD_APP_KEY` / `UPLOAD_APP_SECRET`），其余配置都在环境文件里
- 凭证没有内置默认值：`app.security.clients[].appKey/appSecret` 必须由 `UPLOAD_APP_KEY` / `UPLOAD_APP_SECRET`、启动参数 `--app-key`/`--app-secret` 或当前环境文件提供；缺失时启动前由 `CredentialPreflight` 打印一次性提示并退出（直接 `java -jar` 也生效），完整校验仍由 `AppProperties#afterPropertiesSet` 负责（非空、唯一、至少一个启用客户端）
- 测试：JUnit 5 + `@SpringBootTest` + MockMvc，覆盖签名测试向量、过期、重放、错误签名、上传-列表-下载-删除全链路

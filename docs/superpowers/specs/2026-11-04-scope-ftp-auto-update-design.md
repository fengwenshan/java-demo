# ScopeFTP 客户端自动更新设计

日期：2026-11-04  
状态：待评审

## 目标

给 ScopeFTP 桌面客户端增加安全、可验证的自动更新能力：

- 按本机时间每天 10:00、16:00 各检测一次更新。
- 启动时如果距上次检测超过 6 小时，补检一次。
- 发现新版本后自动下载并安装；安装完成后提示用户重启，不在上传过程中强制退出。
- 更新包必须通过 Tauri updater 签名校验；安装包来自 GitHub Release。
- 发布流程自动生成 `latest.json`，不需要手工编辑版本清单。

## 非目标

- 不更新服务端部署包，服务端仍按现有 `ScopeFTP-Server.zip` 发布流程。
- 不做灰度、分批或本地差分更新。
- 不支持 Linux。
- 不新增系统级后台服务；客户端未运行时不会检测。
- 首版不处理 macOS 代码签名/公证（沿用当前 `--no-sign` 构建），但会验证 updater 在目标机器上可安装。

## 版本策略

客户端版本由 `client/src-tauri/tauri.conf.json` 的 `version` 决定，并同步到：

- `client/package.json`
- `client/src-tauri/Cargo.toml`

规则：

- 本次从 `0.1.0` 提升到 `1.0.3`，与发布 tag `v1.0.3` 对齐。
- 以后每次发 `v1.0.x`，客户端版本同步为 `1.0.x`。
- `latest.json` 的 `version` 从 `tauri.conf.json` 读取；发布脚本校验它等于 tag 去掉 `v`，不一致直接失败。

注意：`v1.0.2` 客户端没有 updater，无法自动升级到 `v1.0.3`。用户需要手动安装一次 `v1.0.3`，之后的版本才能自动更新。

## 架构

### 客户端

- 使用 Tauri 官方 `tauri-plugin-updater` 与 `tauri-plugin-process`。
- `tauri.conf.json` 配置：
  - `bundle.createUpdaterArtifacts: true`
  - `plugins.updater.endpoints` 指向
    `https://github.com/fengwenshan/java-demo/releases/latest/download/latest.json`
  - `plugins.updater.pubkey` 写入签名公钥
- Rust 侧初始化 updater/process 插件。
- Capabilities 增加：
  - `updater:default`
  - `process:allow-restart`（或 `process:default`）
- 前端新增：
  - `src/update/schedule.ts`：纯函数计算下一次 10:00/16:00，便于单元测试。
  - `src/update/useAutoUpdate.ts`：检测、下载、安装、状态管理。
  - `App.tsx` 接入 hook；使用 Ant Design notification/modal 展示下载进度、完成提示和重启确认。
  - 左侧栏底部显示客户端版本与「检查更新」入口，手动检测时展示明确结果。

### 更新流程

1. 启动后读取 `localStorage` 上次检测时间。
2. 如果距上次检测超过 6 小时，立即检测一次。
3. 计算下一次本机时间 10:00 或 16:00，设置定时器；每次检测后重新计算下一次。
4. 监听窗口 focus/visibility change；如果定时器因系统休眠错过，恢复后补检一次。
5. `check()` 发现新版本后自动调用 `downloadAndInstall()`，展示进度。
6. 下载安装完成后弹窗：「新版本已安装，重启后生效」，用户确认后 `relaunch()`。
7. 用户取消重启时保留已安装状态，不重复下载；下一次检测或手动检查可再次提示。

### 调度规则

- 本地时区，10:00 和 16:00 各一次。
- 同一时刻只允许一个检测/下载任务。
- 自动检测失败（离线、404、签名错误）只记录日志，不弹错误；下一次继续。
- 手动检查失败才显示错误信息。
- 更新成功后写入检测时间；若安装完成但未重启，不重复下载。

## 发布与 CI

### 签名密钥

- 生成一对 Tauri updater 签名密钥。
- 公钥写入 `tauri.conf.json`，可入库。
- 私钥只保存在本机，并配置为 GitHub Actions Secret：
  - `TAURI_SIGNING_PRIVATE_KEY`
  - `TAURI_SIGNING_PRIVATE_KEY_PASSWORD`（如果生成时设置了密码）
- 私钥绝不提交到仓库。
- 未配置 Secret 时 CI 明确失败，避免发布未签名的假更新包。

### 构建产物

macOS 与 Windows 构建步骤增加签名环境变量，并保留 updater 产物：

- macOS：
  - `*.app.tar.gz`
  - `*.app.tar.gz.sig`
  - 现有 DMG
- Windows：
  - NSIS updater 包（`*.nsis.zip`）
  - `*.nsis.zip.sig`
  - 现有 NSIS 安装包与免安装 exe

由于两个 macOS 架构的 updater 包默认同名，构建后按架构重命名，例如：

- `ScopeFTP_1.0.3_aarch64.app.tar.gz`
- `ScopeFTP_1.0.3_x64.app.tar.gz`

### latest.json

Release job 下载全部 artifacts 后，由 `deploy/build-update-manifest.py` 生成：

```json
{
  "version": "1.0.3",
  "notes": "v1.0.3",
  "pub_date": "2026-11-04T10:00:00Z",
  "platforms": {
    "darwin-aarch64": {
      "signature": "<sig 文件内容>",
      "url": "https://github.com/fengwenshan/java-demo/releases/download/v1.0.3/ScopeFTP_1.0.3_aarch64.app.tar.gz"
    },
    "darwin-x86_64": { "signature": "...", "url": "..." },
    "windows-x86_64": { "signature": "...", "url": "..." }
  }
}
```

要求：

- 三个平台条目齐全，否则发布失败。
- `signature` 读取对应 `.sig` 文件，不手工填写。
- `url` 使用当前 tag 的 GitHub Release 下载地址。
- `latest.json` 作为 Release asset 上传。
- CI 发布前校验 `version` 与 tag 一致、JSON 可解析、URL 文件存在。

## 错误处理

- 更新端点不可达：静默重试，不打扰用户。
- 签名校验失败：不安装，记录错误；手动检查时提示「更新包校验失败」。
- 下载中断：下次检测可重新下载；不保留半包作为已安装。
- 用户取消重启：状态保留，不重复下载。
- 正在进行文件上传时：不自动重启；只提示更新已就绪。
- 没有配置签名 Secret：CI 构建失败并提示需要配置哪个 Secret。

## 测试

- 调度纯函数单测：
  - 09:59 -> 10:00
  - 10:00 -> 16:00
  - 10:01 -> 16:00
  - 15:59 -> 16:00
  - 16:00 -> 次日 10:00
  - 16:01 -> 次日 10:00
- 手动模拟 `latest.json`，验证版本比较、平台选择、签名读取。
- CI 构建验证：updater 产物、`.sig`、`latest.json` 都存在且 URL 可对应。
- 端到端手测：
  1. 安装 `v1.0.3`。
  2. 发布 `v1.0.4`。
  3. 启动客户端，确认约 10:00/16:00 或手动检查时自动下载并提示重启。
  4. 重启后确认版本变为 `1.0.4`。

## 风险与限制

- macOS 未签名应用可能受 Gatekeeper 影响；如果 updater 安装失败，需要后续补 macOS 代码签名/公证。
- 客户端必须处于运行状态才会定时检测；关闭期间不会检查。
- 首次从 `v1.0.2` 升级需要手动安装 `v1.0.3`。
- 更新签名私钥丢失后，旧客户端无法验证新版本，必须重新手动安装；私钥需要备份。

## 实施顺序

1. 生成签名密钥并配置本地/远程 Secret（Secret 由用户手动添加）。
2. 接入 updater/process 插件、capabilities 和 `tauri.conf.json`。
3. 实现调度与更新 UI。
4. 改造 CI 产物和 `latest.json`。
5. 单元测试、构建验证、发布 `v1.0.3`。
6. 手测一次 `v1.0.4` 的自动更新闭环。

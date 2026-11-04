# ScopeFTP 自动更新 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 ScopeFTP 桌面客户端增加每日 10:00/16:00 检测、自动下载安装并经用户确认重启的签名自动更新能力。

**Architecture:** Tauri updater 从 GitHub Release 的 `latest.json` 获取各平台更新包；前端定时器负责本机时间调度和 UI，CI 负责签名、收集 updater 产物并生成清单。

**Tech Stack:** Tauri 2、Rust、React 18 + TypeScript、Ant Design 5、Vitest、GitHub Actions。

---

### Task 0: 生成签名密钥并配置 Secret

**Files:**
- Modify: `07_scope-ftp/client/src-tauri/tauri.conf.json`（只写公钥）
- Modify: `.github/workflows/scope-ftp.yml`（只引用 Secret 名）

- [ ] **Step 1: 生成 Tauri 签名密钥**

```bash
cd 07_scope-ftp/client
pnpm tauri signer generate -w ~/.tauri/scopeftp.key
```

记录命令输出的 public key；私钥在 `~/.tauri/scopeftp.key`，不得复制进仓库。

- [ ] **Step 2: 配置 GitHub Actions Secrets**

在仓库 `Settings → Secrets and variables → Actions` 添加：

- `TAURI_SIGNING_PRIVATE_KEY`：`~/.tauri/scopeftp.key` 文件内容。
- `TAURI_SIGNING_PRIVATE_KEY_PASSWORD`：生成密钥时设置的密码；无密码则留空/不添加。

- [ ] **Step 3: 验证私钥未被 git 跟踪**

```bash
git status --short | grep -E 'scopeftp.key|\.tauri' || true
```

Expected: 无输出（私钥不在仓库工作区）。

### Task 1: 接入 updater/process 插件与配置

**Files:**
- Modify: `07_scope-ftp/client/package.json`
- Modify: `07_scope-ftp/client/src-tauri/Cargo.toml`
- Modify: `07_scope-ftp/client/src-tauri/src/lib.rs`
- Modify: `07_scope-ftp/client/src-tauri/capabilities/default.json`
- Modify: `07_scope-ftp/client/src-tauri/tauri.conf.json`

- [ ] **Step 1: 安装前端插件依赖**

```bash
cd 07_scope-ftp/client
pnpm add @tauri-apps/plugin-updater@^2 @tauri-apps/plugin-process@^2
```

- [ ] **Step 2: 安装 Rust 插件依赖**

在 `Cargo.toml` 的 `[dependencies]` 增加：

```toml
tauri-plugin-updater = "2"
tauri-plugin-process = "2"
```

- [ ] **Step 3: 初始化 Rust 插件**

在 `src-tauri/src/lib.rs` 的 `tauri::Builder::default()` 链上增加：

```rust
.plugin(tauri_plugin_updater::Builder::new().build())
.plugin(tauri_plugin_process::init())
```

- [ ] **Step 4: 增加 capabilities 权限**

`src-tauri/capabilities/default.json` 的 `permissions` 增加：

```json
"updater:default",
"process:default"
```

- [ ] **Step 5: 配置 updater**

`tauri.conf.json`：

```json
"version": "1.0.3",
"bundle": {
  "active": true,
  "targets": "all",
  "createUpdaterArtifacts": true,
  "icon": ["icons/32x32.png", "icons/128x128.png", "icons/128x128@2x.png", "icons/icon.icns", "icons/icon.ico"]
},
"plugins": {
  "updater": {
    "pubkey": "<Task 0 生成的 public key>",
    "endpoints": [
      "https://github.com/fengwenshan/java-demo/releases/latest/download/latest.json"
    ]
  }
}
```

- [ ] **Step 6: 构建检查**

```bash
cd 07_scope-ftp/client
pnpm tauri build --debug --no-bundle
```

Expected: 编译成功；未配置签名时只验证 Rust 编译，不发布产物。

- [ ] **Step 7: Commit**

```bash
git add 07_scope-ftp/client/package.json 07_scope-ftp/client/pnpm-lock.yaml \
  07_scope-ftp/client/src-tauri/Cargo.toml 07_scope-ftp/client/src-tauri/Cargo.lock \
  07_scope-ftp/client/src-tauri/src/lib.rs \
  07_scope-ftp/client/src-tauri/capabilities/default.json \
  07_scope-ftp/client/src-tauri/tauri.conf.json
git commit -m "feat(scope-ftp): wire up Tauri updater plugins"
```

### Task 2: 调度纯函数与单测

**Files:**
- Create: `07_scope-ftp/client/src/update/schedule.ts`
- Create: `07_scope-ftp/client/src/update/schedule.test.ts`
- Modify: `07_scope-ftp/client/package.json`

- [ ] **Step 1: 写 schedule.ts**

```ts
export const UPDATE_CHECK_HOURS = [10, 16] as const;
export const STARTUP_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000;

/** 返回严格晚于 now 的下一个 10:00 或 16:00（本机时区）。 */
export function nextUpdateCheck(now = new Date()): Date {
  for (const hour of UPDATE_CHECK_HOURS) {
    const candidate = new Date(now);
    candidate.setHours(hour, 0, 0, 0);
    if (candidate.getTime() > now.getTime()) return candidate;
  }
  const tomorrow = new Date(now);
  tomorrow.setDate(tomorrow.getDate() + 1);
  tomorrow.setHours(UPDATE_CHECK_HOURS[0], 0, 0, 0);
  return tomorrow;
}

/** 启动时是否需要补检：从未检测，或距上次检测超过 6 小时。 */
export function shouldCheckOnStartup(nowMs: number, lastCheckMs: number | null): boolean {
  return lastCheckMs === null || nowMs - lastCheckMs >= STARTUP_CHECK_INTERVAL_MS;
}
```

- [ ] **Step 2: 写失败测试**

```ts
import { describe, expect, it } from "vitest";
import { nextUpdateCheck, shouldCheckOnStartup } from "./schedule";

function at(hour: number, minute: number, day = 4): Date {
  return new Date(2026, 10, day, hour, minute, 0, 0);
}

describe("nextUpdateCheck", () => {
  it("09:59 -> 10:00", () => {
    expect(nextUpdateCheck(at(9, 59)).getHours()).toBe(10);
  });
  it("10:00 -> 16:00", () => {
    expect(nextUpdateCheck(at(10, 0)).getHours()).toBe(16);
  });
  it("10:01 -> 16:00", () => {
    expect(nextUpdateCheck(at(10, 1)).getHours()).toBe(16);
  });
  it("15:59 -> 16:00", () => {
    expect(nextUpdateCheck(at(15, 59)).getHours()).toBe(16);
  });
  it("16:00 -> 次日 10:00", () => {
    expect(nextUpdateCheck(at(16, 0)).getHours()).toBe(10);
    expect(nextUpdateCheck(at(16, 0)).getDate()).toBe(5);
  });
  it("16:01 -> 次日 10:00", () => {
    expect(nextUpdateCheck(at(16, 1)).getDate()).toBe(5);
  });
});

describe("shouldCheckOnStartup", () => {
  it("从未检测时补检", () => {
    expect(shouldCheckOnStartup(1000, null)).toBe(true);
  });
  it("6 小时内不补检", () => {
    expect(shouldCheckOnStartup(6 * 60 * 60 * 1000 - 1, 0)).toBe(false);
  });
  it("超过 6 小时补检", () => {
    expect(shouldCheckOnStartup(6 * 60 * 60 * 1000, 0)).toBe(true);
  });
});
```

- [ ] **Step 3: 安装并运行测试**

```bash
cd 07_scope-ftp/client
pnpm add -D vitest
pnpm exec vitest run src/update/schedule.test.ts
```

Expected: 9 passed.

- [ ] **Step 4: 增加 test 脚本**

`package.json` 的 `scripts` 增加：

```json
"test": "vitest run"
```

- [ ] **Step 5: Commit**

```bash
git add 07_scope-ftp/client/package.json 07_scope-ftp/client/pnpm-lock.yaml \
  07_scope-ftp/client/src/update/schedule.ts 07_scope-ftp/client/src/update/schedule.test.ts
git commit -m "feat(scope-ftp): add update schedule logic"
```

### Task 3: 更新 hook 与界面

**Files:**
- Create: `07_scope-ftp/client/src/update/useAutoUpdate.ts`
- Modify: `07_scope-ftp/client/src/App.tsx`
- Modify: `07_scope-ftp/client/src/components/ServerSidebar.tsx`
- Modify: `07_scope-ftp/client/src/styles.css`

- [ ] **Step 1: 实现 useAutoUpdate.ts**

```ts
import { App as AntdApp, type ModalStaticFunctions } from "antd";
import { useCallback, useEffect, useRef, useState } from "react";
import { check } from "@tauri-apps/plugin-updater";
import { relaunch } from "@tauri-apps/plugin-process";

import { nextUpdateCheck, shouldCheckOnStartup } from "./schedule";

const LAST_CHECK_KEY = "scopeftp.update.lastCheck";
export type UpdatePhase = "idle" | "checking" | "downloading" | "ready" | "error";

export interface AutoUpdateState {
  phase: UpdatePhase;
  availableVersion: string | null;
  checkNow: (manual?: boolean) => Promise<void>;
}

export function useAutoUpdate(enabled: boolean): AutoUpdateState {
  const { notification, modal } = AntdApp.useApp();
  const [phase, setPhase] = useState<UpdatePhase>("idle");
  const [availableVersion, setAvailableVersion] = useState<string | null>(null);
  const busyRef = useRef(false);
  const nextTimerRef = useRef<number | null>(null);

  const runCheck = useCallback(async (manual: boolean) => {
    if (!enabled || busyRef.current) return;
    busyRef.current = true;
    setPhase("checking");
    try {
      const update = await check();
      if (!update) {
        if (manual) notification.info({ message: "当前已是最新版本" });
        return;
      }
      setAvailableVersion(update.version);
      setPhase("downloading");
      notification.info({
        key: "scopeftp-update",
        message: `发现新版本 ${update.version}`,
        description: "正在后台下载，完成后会提示重启",
        duration: 0,
      });
      let downloaded = 0;
      await update.downloadAndInstall((event) => {
        if (event.event === "Started") {
          downloaded = 0;
        } else if (event.event === "Progress") {
          downloaded += event.data.chunkLength;
          const total = event.data.contentLength;
          const percent = total ? Math.min(99, Math.floor((downloaded / total) * 100)) : null;
          notification.info({
            key: "scopeftp-update",
            message: `正在下载 ${update.version}`,
            description: percent === null ? "下载中…" : `${percent}%`,
            duration: 0,
          });
        } else if (event.event === "Finished") {
          setPhase("ready");
          notification.success({
            key: "scopeftp-update",
            message: `新版本 ${update.version} 已安装`,
            description: "重启客户端后生效",
            duration: 0,
          });
          modal.confirm({
            title: "更新已就绪",
            content: "新版本已安装，现在重启生效吗？",
            okText: "立即重启",
            cancelText: "稍后",
            onOk: () => relaunch(),
          });
        }
      });
    } catch (error) {
      setPhase("error");
      if (manual) {
        notification.error({
          message: "检查更新失败",
          description: error instanceof Error ? error.message : String(error),
        });
      }
    } finally {
      localStorage.setItem(LAST_CHECK_KEY, String(Date.now()));
      busyRef.current = false;
      if (phase !== "ready") setPhase("idle");
    }
  }, [enabled, modal, notification, phase]);

  useEffect(() => {
    if (!enabled) return;
    const last = Number(localStorage.getItem(LAST_CHECK_KEY) ?? "0") || null;
    if (shouldCheckOnStartup(Date.now(), last)) void runCheck(false);

    const armNext = () => {
      if (nextTimerRef.current !== null) window.clearTimeout(nextTimerRef.current);
      const delay = Math.max(0, nextUpdateCheck().getTime() - Date.now());
      nextTimerRef.current = window.setTimeout(() => {
        void runCheck(false).finally(armNext);
      }, delay);
    };
    armNext();

    const catchUp = () => {
      const lastCheck = Number(localStorage.getItem(LAST_CHECK_KEY) ?? "0") || 0;
      if (Date.now() - lastCheck >= 60_000) void runCheck(false);
    };
    window.addEventListener("focus", catchUp);
    document.addEventListener("visibilitychange", catchUp);
    return () => {
      if (nextTimerRef.current !== null) window.clearTimeout(nextTimerRef.current);
      window.removeEventListener("focus", catchUp);
      document.removeEventListener("visibilitychange", catchUp);
    };
  }, [enabled, runCheck]);

  return { phase, availableVersion, checkNow: runCheck };
}
```

- [ ] **Step 2: App 接入 hook**

在 `App.tsx` 顶部增加：

```ts
import { getVersion } from "@tauri-apps/api/app";
import { useAutoUpdate } from "./update/useAutoUpdate";
```

在组件状态区增加：

```ts
const [clientVersion, setClientVersion] = useState("—");
const autoUpdate = useAutoUpdate(isTauri() && !browserMode);

useEffect(() => {
  if (!isTauri()) return;
  void getVersion().then(setClientVersion).catch(() => {});
}, []);
```

`ServerSidebar` 增加 props：

```tsx
clientVersion={clientVersion}
updatePhase={autoUpdate.phase}
availableUpdateVersion={autoUpdate.availableVersion}
onCheckUpdate={() => void autoUpdate.checkNow(true)}
```

- [ ] **Step 3: 左侧栏显示版本和检查按钮**

`ServerSidebarProps` 增加：

```ts
clientVersion: string;
updatePhase: "idle" | "checking" | "downloading" | "ready" | "error";
availableUpdateVersion: string | null;
onCheckUpdate: () => void;
```

在 `return` 根节点末尾、未配置 Alert 之前增加：

```tsx
<div className="app-update">
  <Text type="secondary">
    v{clientVersion}
    {availableUpdateVersion ? ` · 可更新到 ${availableUpdateVersion}` : ""}
  </Text>
  <Button
    type="link"
    size="small"
    loading={updatePhase === "checking" || updatePhase === "downloading"}
    onClick={onCheckUpdate}
    data-testid="check-update"
  >
    检查更新
  </Button>
</div>
```

`styles.css` 增加：

```css
.app-update {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 12px;
  border-top: 1px solid #f0f0f0;
}
```

- [ ] **Step 4: 构建与测试**

```bash
cd 07_scope-ftp/client
pnpm build
pnpm test
```

Expected: TypeScript 构建成功；schedule 测试通过。

- [ ] **Step 5: Commit**

```bash
git add 07_scope-ftp/client/src/update/useAutoUpdate.ts \
  07_scope-ftp/client/src/App.tsx \
  07_scope-ftp/client/src/components/ServerSidebar.tsx \
  07_scope-ftp/client/src/styles.css
git commit -m "feat(scope-ftp): schedule and install client updates"
```

### Task 4: CI 签名、更新产物与 latest.json

**Files:**
- Create: `07_scope-ftp/deploy/build-update-manifest.py`
- Modify: `.github/workflows/scope-ftp.yml`

- [ ] **Step 1: 写 manifest 脚本**

```python
#!/usr/bin/env python3
"""从 Tauri updater 产物生成 GitHub Release latest.json。"""
import argparse, json, shutil
from datetime import datetime, timezone
from pathlib import Path

PLATFORMS = {
    "darwin-aarch64": ("ScopeFTP-macos-arm64", "aarch64", "app.tar.gz"),
    "darwin-x86_64": ("ScopeFTP-macos-x64", "x64", "app.tar.gz"),
    "windows-x86_64": ("ScopeFTP-client-windows-x64", "x64", "nsis.zip"),
}

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--artifacts", required=True, type=Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--out-dir", required=True, type=Path)
    args = parser.parse_args()
    args.out_dir.mkdir(parents=True, exist_ok=True)

    platforms = {}
    for key, (artifact, arch, suffix) in PLATFORMS.items():
        folder = args.artifacts / artifact
        matches = sorted(folder.rglob(f"*{suffix}"))
        if len(matches) != 1:
            raise SystemExit(f"{key}: expected one *{suffix} in {folder}, got {matches}")
        archive = matches[0]
        sig = archive.with_name(archive.name + ".sig")
        if not sig.exists():
            raise SystemExit(f"{key}: missing signature {sig}")
        filename = f"ScopeFTP_{args.version}_{arch}.{suffix}"
        target = args.out_dir / filename
        shutil.copy2(archive, target)
        shutil.copy2(sig, args.out_dir / f"{filename}.sig")
        platforms[key] = {
            "signature": sig.read_text().strip(),
            "url": f"https://github.com/fengwenshan/java-demo/releases/download/{args.tag}/{filename}",
        }

    manifest = {
        "version": args.version,
        "notes": f"ScopeFTP {args.tag}",
        "pub_date": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "platforms": platforms,
    }
    (args.out_dir.parent / "latest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(manifest, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    main()
```

- [ ] **Step 2: 客户端构建步骤注入签名 Secret**

macOS/Windows 的 `Build ...` step 增加：

```yaml
env:
  TAURI_SIGNING_PRIVATE_KEY: ${{ secrets.TAURI_SIGNING_PRIVATE_KEY }}
  TAURI_SIGNING_PRIVATE_KEY_PASSWORD: ${{ secrets.TAURI_SIGNING_PRIVATE_KEY_PASSWORD }}
```

- [ ] **Step 3: 上传 updater 产物**

macOS upload path 增加：

```yaml
07_scope-ftp/client/src-tauri/target/${{ matrix.target }}/release/bundle/macos/*.app.tar.gz
07_scope-ftp/client/src-tauri/target/${{ matrix.target }}/release/bundle/macos/*.app.tar.gz.sig
```

Windows upload path 增加：

```yaml
07_scope-ftp/client/src-tauri/target/x86_64-pc-windows-msvc/release/bundle/nsis/*.nsis.zip
07_scope-ftp/client/src-tauri/target/x86_64-pc-windows-msvc/release/bundle/nsis/*.nsis.zip.sig
```

- [ ] **Step 4: release job 生成 latest.json**

在 `release` job 的 `Download build artifacts` 后增加：

```yaml
      - name: Generate updater manifest
        run: |
          VERSION="$(node -p "require('./07_scope-ftp/client/src-tauri/tauri.conf.json').version")"
          TAG="${GITHUB_REF_NAME}"
          test "v${VERSION}" = "$TAG"
          python3 07_scope-ftp/deploy/build-update-manifest.py \
            --artifacts release-assets \
            --version "$VERSION" \
            --tag "$TAG" \
            --out-dir release-assets/updater
```

`softprops/action-gh-release` 的 `files` 增加：

```yaml
            release-assets/updater/*
            release-assets/latest.json
```

- [ ] **Step 5: 本地验证脚本**

```bash
cd 07_scope-ftp
mkdir -p /tmp/updater-test/ScopeFTP-macos-arm64
printf x > /tmp/updater-test/ScopeFTP-macos-arm64/ScopeFTP.app.tar.gz
printf sig > /tmp/updater-test/ScopeFTP-macos-arm64/ScopeFTP.app.tar.gz.sig
mkdir -p /tmp/updater-test/ScopeFTP-macos-x64
printf x > /tmp/updater-test/ScopeFTP-macos-x64/ScopeFTP.app.tar.gz
printf sig > /tmp/updater-test/ScopeFTP-macos-x64/ScopeFTP.app.tar.gz.sig
mkdir -p /tmp/updater-test/ScopeFTP-client-windows-x64
printf x > /tmp/updater-test/ScopeFTP-client-windows-x64/ScopeFTP.nsis.zip
printf sig > /tmp/updater-test/ScopeFTP-client-windows-x64/ScopeFTP.nsis.zip.sig
python3 deploy/build-update-manifest.py \
  --artifacts /tmp/updater-test --version 1.0.3 --tag v1.0.3 --out-dir /tmp/updater-test/updater
test -f /tmp/updater-test/latest.json && test -f /tmp/updater-test/updater/ScopeFTP_1.0.3_aarch64.app.tar.gz
```

Expected: 生成三平台 `latest.json`，且文件存在。

- [ ] **Step 6: Commit**

```bash
git add .github/workflows/scope-ftp.yml 07_scope-ftp/deploy/build-update-manifest.py
git commit -m "ci(scope-ftp): publish signed updater artifacts"
```

### Task 5: 版本、文档与依赖锁定

**Files:**
- Modify: `07_scope-ftp/client/package.json`
- Modify: `07_scope-ftp/client/src-tauri/Cargo.toml`
- Modify: `07_scope-ftp/client/src-tauri/tauri.conf.json`
- Modify: `07_scope-ftp/README.md`
- Modify: `07_scope-ftp/docs/05-ci.md`
- Modify: `07_scope-ftp/docs/03-deployment.md`

- [ ] **Step 1: 统一版本为 1.0.3**

三个文件中的 `version` 都改成 `1.0.3`。

- [ ] **Step 2: 文档写清自动更新**

README 增加：

```md
## 客户端自动更新

客户端每天本机时间 10:00、16:00 自动检测更新；启动时距上次检测超过 6 小时会补检。
发现新版本后自动下载并安装，安装完成提示重启。更新包使用 Tauri 签名校验。

首次从 v1.0.2 升级需要手动安装 v1.0.3；之后版本可自动更新。

发布要求：
- GitHub Actions Secret `TAURI_SIGNING_PRIVATE_KEY` 必须配置。
- 客户端版本与 tag 一致（v1.0.3 ↔ 1.0.3）。
- Release 必须包含 `latest.json`。
```

`docs/05-ci.md` 的 Build Artifacts 表增加 updater 产物和 `latest.json`，并说明签名 Secret。

- [ ] **Step 3: 构建验证**

```bash
cd 07_scope-ftp/client
pnpm install --frozen-lockfile
pnpm build
pnpm test
cargo check --manifest-path src-tauri/Cargo.toml
```

Expected: 全部成功；如果本机缺少 updater 签名密钥，只运行 `cargo check`，不运行发布构建。

- [ ] **Step 4: Commit**

```bash
git add 07_scope-ftp/client/package.json 07_scope-ftp/client/pnpm-lock.yaml \
  07_scope-ftp/client/src-tauri/Cargo.toml 07_scope-ftp/client/src-tauri/Cargo.lock \
  07_scope-ftp/client/src-tauri/tauri.conf.json \
  07_scope-ftp/README.md 07_scope-ftp/docs/05-ci.md 07_scope-ftp/docs/03-deployment.md
git commit -m "docs(scope-ftp): document automatic updates"
```

### Task 6: 发布与闭环验证

- [ ] **Step 1: 检查工作区只包含本功能改动**

```bash
git status --short -- 07_scope-ftp .github/workflows/scope-ftp.yml docs/superpowers
```

- [ ] **Step 2: 推 tag 触发构建**

```bash
git tag v1.0.3
git push origin main v1.0.3
```

- [ ] **Step 3: 验证 Release 资产**

必须存在：

- `ScopeFTP-Server.zip`
- `ScopeFTP_1.0.3_aarch64.app.tar.gz` + `.sig`
- `ScopeFTP_1.0.3_x64.app.tar.gz` + `.sig`
- `ScopeFTP_1.0.3_x64-setup.nsis.zip` + `.sig`
- `latest.json`

- [ ] **Step 4: 验证 `latest.json`**

```bash
curl -fsSL https://github.com/fengwenshan/java-demo/releases/latest/download/latest.json | python3 -m json.tool
```

Expected: 三个平台条目，版本为 `1.0.3`，URL 指向 v1.0.3 资产。

- [ ] **Step 5: 端到端手测**

1. 手动安装 v1.0.3（v1.0.2 无法自动升级）。
2. 推送 v1.0.4（客户端版本 1.0.4）。
3. 保持客户端运行，手动点「检查更新」或等到 10:00/16:00。
4. 确认自动下载、安装完成弹窗、重启后版本变为 1.0.4。

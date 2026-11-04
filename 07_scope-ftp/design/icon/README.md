# ScopeFTP 应用图标

ScopeFTP 的桌面图标：文件夹 + 上下传输箭头 + 四角取景框（Scope）。

另有白色 + 透明的菜单栏 / 托盘图标（简化版：文件夹 + 上下箭头）。

- 设计文档：`07_scope-ftp/docs/superpowers/specs/2026-11-04-scopeftp-app-icon-design.md`
- 预览图：`preview/scopeftp-icon-preview.png`、`preview/scopeftp-tray-preview.png`

## 目录

| 路径 | 说明 |
| --- | --- |
| `src/scopeftp-icon.svg` | 图标矢量源（纯矢量，无投影） |
| `scopeftp-icon-1024.png` | 主图（已叠软投影），`tauri icon` 的输入 |
| `preview/scopeftp-icon-preview.png` | 交付预览（大图 / 尺寸阶梯 / Dock 并排） |
| `tray/` | 菜单栏 / 托盘图标（模板图 + 白色位图） |
| `tools/build.py` | 生成矢量源与主图 |
| `tools/preview.py` | 生成交付预览图 |
| `tools/build_tray.py` | 生成菜单栏 / 托盘图标 |
| `tools/preview_tray.py` | 生成菜单栏图标预览 |
| `tools/rasterize.swift` | AppKit + CoreSVG 光栅化小工具（源码） |
| `tools/rasterize` | 编译产物（macOS 本地生成，不入库） |

## 重新生成

```bash
cd 07_scope-ftp/design/icon/tools

# 一次性：编译光栅化工具（仅 macOS）
TMPDIR="$PWD/.tmp" CLANG_MODULE_CACHE_PATH="$PWD/.modulecache" \
  swiftc -O -module-cache-path ./.modulecache rasterize.swift -o rasterize

python3 build.py     # -> ../src/scopeftp-icon.svg、../scopeftp-icon-1024.png
python3 preview.py   # -> ../preview/scopeftp-icon-preview.png

# 生成全平台图标集（写回客户端）
cd ../../../client
node_modules/.bin/tauri icon ../design/icon/scopeftp-icon-1024.png -o src-tauri/icons
```

`build.py` 依赖 Pillow；`rasterize` 依赖 macOS AppKit / CoreSVG，Linux/Windows 上可改用 `rsvg-convert` 或 `sharp` 光栅化 `src/scopeftp-icon.svg`。

## 菜单栏 / 托盘图标

`tray/` 是白色 + 透明的菜单栏图标，图形只保留「文件夹 + 上下箭头」（去掉底色和取景框）：

| 文件 | 用途 |
| --- | --- |
| `tray/tray-template-36.png` / `tray-template-54.png` | macOS 模板图（纯黑 + alpha），系统按菜单栏明暗自动反色 |
| `tray/tray-white-32.png` / `tray-white-48.png` | Windows / Linux 托盘（白色 + alpha） |
| `tray/scopeftp-tray-template.svg` / `scopeftp-tray-white.svg` | 矢量源（36 × 36 画布） |

客户端 `src-tauri/src/lib.rs` 用 `include_bytes!` 内嵌对应平台的 PNG，macOS 侧再调用 `icon_as_template(true)`；
`src-tauri/Cargo.toml` 的 `tauri` 依赖需要 `image-png` feature（`Image::from_bytes` 用）。

```bash
cd 07_scope-ftp/design/icon/tools
python3 build_tray.py      # -> ../tray/tray-*.png
python3 preview_tray.py    # -> ../preview/scopeftp-tray-preview.png

# 同步到客户端（include_bytes! 读的是这里的文件）
cp ../tray/tray-template-36.png ../tray/tray-white-32.png ../tray/tray-white-48.png \
   ../../../client/src-tauri/icons/tray/
```

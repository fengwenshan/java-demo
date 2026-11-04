# ScopeFTP 应用图标 设计文档

- 日期：2026-11-04
- 状态：已定稿
- 关联：`client/src-tauri/icons/`、`client/tauri.conf.json`、`design/icon/`

## 1. 背景与目标

`ScopeFTP` 是 Tauri 2 + React 的桌面 FTP 客户端：远程目录浏览、上传下载、拷贝移动、在线编辑、zip 解压、远程终端、操作日志。仓库里 `src-tauri/icons/` 一直是 Tauri CLI 生成的默认图标（黄青双环的 Tauri Logo），与产品气质无关。

本次交付两部分：

- 应用图标：1024 主图，派生 macOS / Windows / Android / iOS 全套尺寸与 `icon.icns`、`icon.ico`，覆盖 Dock、访达、任务栏、窗口与安装包里的占位图标。
- 菜单栏 / 托盘图标：白色 + 透明，随托盘常驻一起使用（窗口关闭后应用常驻菜单栏）。

## 2. 隐喻

| 元素 | 含义 |
| --- | --- |
| 文件夹 | FTP 远程目录，文件管理的主对象 |
| 上下箭头 | 上传 / 下载，双向传输 |
| 四角取景框 | Scope：锁定当前服务器与目录范围，也呼应产品名 |

取景框在 64px 以下会自然消失，剩下「文件夹 + 双向箭头」仍然一眼可读；128px 以上能看出 Scope 的取景框细节。

## 3. 视觉规范

| 项目 | 值 |
| --- | --- |
| 画布 | 1024 × 1024，透明背景 |
| 内容区 | 840 × 840 居中（同 Apple 824 网格的安全边距） |
| 外形 | 超椭圆 `n = 5.0`，近似 macOS 连续曲率圆角 |
| 底色渐变 | 135°，`#1B47E0` → `#2E6BFF`（52%）→ `#06B6D4` |
| 高光 | 顶部偏左白色径向渐变 30% |
| 暗部 | 右下角墨色径向渐变 20% |
| 描边 | 内圈 8px 白色渐变，压边提亮 |
| 图标主体 | 白色分层：后板 `#F8FBFF → #E1EDFF`，前面板 `#FFFFFF → #EAF3FF` |
| 传输箭头 | `#2E6BFF → #0E86D6` 竖向渐变 |
| 取景框 | 白色，26px 间距、70px 臂长、16px 圆头描边 |
| 投影 | 墨色 `#061023`，偏移 (0,18)，高斯 26，整体 40% |
| 构图 | 字形整体上移 22px、放大 1.15 倍，贴近 macOS 图标的内容占比 |

几何（1024 画布，应用缩放前的原始坐标）：

- 文件夹：`x 288–720`，顶 366，底 690；前面板 `288,486 → 432×238`，圆角 36。
- 箭头：上箭头 `x 392–504 / y 500–676`，下箭头 `x 520–632 / y 512–688`，箭头头部宽 112、杆宽 28。
- 取景框：四角 L 形，包围盒 `262–746 × 340–716`。

## 4. 资产清单

```text
07_scope-ftp/
├─ client/src-tauri/icons/        由 `tauri icon` 生成（本仓库已提交）
│  ├─ icon.icns / icon.ico / icon.png / 32x32.png / 128x128.png ...
│  └─ android/ ios/ Square*Logo.png StoreLogo.png
├─ design/icon/
│  ├─ src/scopeftp-icon.svg             矢量源（纯矢量，无投影）
│  ├─ scopeftp-icon-1024.png            主图（已叠软投影）
│  ├─ tray/                             菜单栏 / 托盘图标（模板图 + 白色位图）
│  ├─ preview/scopeftp-icon-preview.png、scopeftp-tray-preview.png
│  ├─ tools/build.py / preview.py / build_tray.py / preview_tray.py / rasterize.swift
│  └─ README.md
└─ docs/superpowers/specs/2026-11-04-scopeftp-app-icon-design.md
```

## 5. 集成说明

`client/src-tauri/tauri.conf.json` 的 `bundle.icon` 已引用 `icons/32x32.png`、`icons/128x128.png`、`icons/128x128@2x.png`、`icons/icon.icns`、`icons/icon.ico`，替换同名文件即可，无需改配置，也无需改 Rust 代码。

- macOS：`icon.icns` 用于 Dock / 访达 / 关于窗口。
- Windows：`icon.ico` 用于任务栏与窗口；`Square*Logo.png`、`StoreLogo.png` 用于 MSIX。
- Android / iOS：`android/`、`ios/` 目录同步更新。

### 菜单栏 / 托盘图标

窗口关闭后应用常驻托盘，`client/src-tauri/src/lib.rs` 的 `create_tray` 负责创建托盘图标：

- 图标资源：`client/src-tauri/icons/tray/`，用 `include_bytes!` 内嵌，不依赖安装包外路径。
    - macOS：`tray-template-36.png`，纯黑 + alpha 的模板图，`icon_as_template(true)` 后系统在深色菜单栏渲染为白色、浅色菜单栏渲染为黑色。
    - Windows：`tray-white-32.png`；Linux / 其他：`tray-white-48.png`，均为白色 + alpha。
- 图形：应用图标的简化版——只保留文件夹轮廓 + 上下箭头，去掉渐变底色和四角取景框，保证 18 / 22 px 下仍可辨认。
- Cargo：`tauri` 依赖需要 `image-png` feature（`Image::from_bytes` 解码内嵌 PNG）。

```bash
cd 07_scope-ftp/design/icon/tools
python3 build_tray.py
python3 preview_tray.py
```

## 6. 构建方式

```bash
cd 07_scope-ftp/design/icon/tools
TMPDIR="$PWD/.tmp" CLANG_MODULE_CACHE_PATH="$PWD/.modulecache" \
  swiftc -O -module-cache-path ./.modulecache rasterize.swift -o rasterize
python3 build.py
python3 preview.py

cd ../../../client
node_modules/.bin/tauri icon ../design/icon/scopeftp-icon-1024.png -o src-tauri/icons
```

## 7. 验证

- `icon.png` 512、`32x32.png` 32、`128x128@2x.png` 256；`icon.icns` 为 `ic08`、`icon.ico` 含 16–256 共 6 档 PNG。
- 预览图 `design/icon/preview/scopeftp-icon-preview.png`：1024 大图、尺寸阶梯、Dock 并排。
- 菜单栏图标：`tray-template-36.png` RGB 全 0、alpha 0–255（模板图）；`tray-white-32.png` RGB 全 255、alpha 0–255。
- 预览图 `design/icon/preview/scopeftp-tray-preview.png`：深浅菜单栏前后对比。
- 应用图标替换不改 Rust / 前端代码；菜单栏图标只改 `lib.rs` 的取图逻辑与 Cargo feature。

## 8. 取舍与后续

- 图标只做「文件夹 + 双向箭头」两件事，取景框是加分项：小尺寸靠前两者，大尺寸才看到 Scope 细节。
- 菜单栏图标刻意做减法：只用文件夹 + 箭头，18 px 下取景框会糊成噪点，所以不带。
- 替换前的默认 Tauri 图标已全部覆盖；旧文件没有保留必要。

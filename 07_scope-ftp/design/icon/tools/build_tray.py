#!/usr/bin/env python3
"""ScopeFTP 菜单栏 / 托盘图标：白色 + 透明。

- macOS 模板图：纯黑 + alpha，系统按菜单栏明暗自动反色（深色栏白、浅色栏黑）。
- Windows / Linux：白色 + alpha 位图。

图形是简化版应用图标：文件夹 + 上下箭头，去掉底色和取景框。
坐标基于 36 × 36 画布。
"""
import subprocess
from pathlib import Path

from PIL import Image

TOOLS = Path(__file__).resolve().parent
ICONS = TOOLS.parent
OUT = ICONS / "tray"
OUT.mkdir(parents=True, exist_ok=True)
RASTER = TOOLS / "rasterize"

FOLDER = ("M 8 8 H 13.5 a 4.5 4.5 0 0 1 4.5 4.5 V 13.5 H 28 a 4.5 4.5 0 0 1 4.5 4.5 "
          "V 26 a 4.5 4.5 0 0 1 -4.5 4.5 H 8.5 a 4.5 4.5 0 0 1 -4.5 -4.5 V 12.5 "
          "a 4.5 4.5 0 0 1 4.5 -4.5 Z")
UP = "M 13.5 16.5 L 18.4 22.4 H 15.7 V 28.3 H 11.3 V 22.4 H 8.6 Z"
DOWN = "M 22.5 29.1 L 17.6 23.2 H 20.3 V 17.3 H 24.7 V 23.2 H 27.4 Z"


def svg(body):
    return ('<svg width="36" height="36" viewBox="0 0 36 36" '
            f'xmlns="http://www.w3.org/2000/svg">{body}</svg>')


def outline(paint):
    return svg(f'<path d="{FOLDER}" fill="none" stroke="{paint}" stroke-width="2.8" '
               f'stroke-linejoin="round"/>'
               f'<path d="{UP}" fill="{paint}"/><path d="{DOWN}" fill="{paint}"/>')


def solid(paint):
    return svg(f'<path d="{FOLDER} {UP} {DOWN}" fill="{paint}" fill-rule="evenodd"/>')


def render(text, svg_name, name, size, scale=8):
    svg_path = OUT / svg_name
    svg_path.write_text(text, encoding="utf-8")
    raw = OUT / f"_{name}-{size * scale}.png"
    subprocess.run([str(RASTER), str(svg_path), str(raw), str(size * scale)], check=True)
    im = Image.open(raw).convert("RGBA").resize((size, size), Image.LANCZOS)
    im.save(OUT / f"{name}-{size}.png")
    raw.unlink()
    return im


if __name__ == "__main__":
    # macOS 模板图（黑 + 透明，描边风格）
    render(outline("#000000"), "scopeftp-tray-template.svg", "tray-template", 36)
    render(outline("#000000"), "scopeftp-tray-template.svg", "tray-template", 54)
    # Windows / Linux（白 + 透明，实心风格，小尺寸更实）
    render(solid("#FFFFFF"), "scopeftp-tray-white.svg", "tray-white", 32)
    render(solid("#FFFFFF"), "scopeftp-tray-white.svg", "tray-white", 48)
    print("built tray icons ->", OUT)

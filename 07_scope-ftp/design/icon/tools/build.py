#!/usr/bin/env python3
"""ScopeFTP 应用图标生成器（自包含，依赖 Pillow + macOS AppKit 光栅化工具）。

图形用 SVG 描述，交给 tools/rasterize（CoreSVG）光栅化；瓦片与字形分层渲染，
再用 Pillow 叠软投影（SVG 里做不出高斯模糊）。

用法：
  python3 build.py        # 生成 ../src/scopeftp-icon.svg 与 ../scopeftp-icon-1024.png

交给 Tauri 生成整套图标：
  node_modules/.bin/tauri icon ../design/icon/scopeftp-icon-1024.png -o src-tauri/icons
"""
from __future__ import annotations

import math
import subprocess
from pathlib import Path

from PIL import Image, ImageFilter

TOOLS = Path(__file__).resolve().parent
ICONS = TOOLS.parent
SRC = ICONS / "src"
DIST = ICONS / "dist"
RASTER = TOOLS / "rasterize"
for _d in (SRC, DIST):
    _d.mkdir(parents=True, exist_ok=True)

INK = "#061023"
CANVAS = 1024
TILE = 840
CX = CY = 512

# 品牌色：沿用客户端 antd 主色 #1677ff 的蓝，收尾到青色
GRAD = [("0", "#1B47E0"), ("0.52", "#2E6BFF"), ("1", "#06B6D4")]
ARROW_GRAD = [("0", "#2E6BFF"), ("1", "#0E86D6")]
SHIFT_Y = -22          # 字形整体上移，做视觉居中
SCALE = 1.15           # 字形缩放，贴近 macOS 图标的内容占比


# ---------------------------------------------------------------- SVG 工具
def squircle(cx, cy, half, n=5.0, steps=720):
    pts = []
    for i in range(steps):
        t = 2 * math.pi * i / steps
        c, s = math.cos(t), math.sin(t)
        pts.append((cx + half * math.copysign(abs(c) ** (2 / n), c),
                    cy + half * math.copysign(abs(s) ** (2 / n), s)))
    return pts


def path_d(pts):
    return "M " + " L ".join(f"{x:.2f} {y:.2f}" for x, y in pts) + " Z"


TILE_D = path_d(squircle(CX, CY, TILE / 2))


def svg(body, size=1024):
    return (f'<svg width="{size}" height="{size}" viewBox="0 0 {size} {size}" '
            f'xmlns="http://www.w3.org/2000/svg">\n{body}\n</svg>\n')


# ---------------------------------------------------------------- 瓦片
def tile_svg():
    stops = "".join(f'<stop offset="{o}" stop-color="{c}"/>' for o, c in GRAD)
    return svg(f'''<defs>
  <linearGradient id="bg" x1="0.03" y1="0" x2="0.97" y2="1">{stops}</linearGradient>
  <radialGradient id="hi" cx="0.32" cy="-0.06" r="0.86">
    <stop offset="0" stop-color="#FFFFFF" stop-opacity="0.30"/>
    <stop offset="0.44" stop-color="#FFFFFF" stop-opacity="0.06"/>
    <stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/></radialGradient>
  <radialGradient id="dk" cx="0.68" cy="1.05" r="0.92">
    <stop offset="0" stop-color="{INK}" stop-opacity="0.20"/>
    <stop offset="0.5" stop-color="{INK}" stop-opacity="0.05"/>
    <stop offset="1" stop-color="{INK}" stop-opacity="0"/></radialGradient>
  <linearGradient id="rim" x1="0" y1="0" x2="0" y2="1">
    <stop offset="0" stop-color="#FFFFFF" stop-opacity="0.55"/>
    <stop offset="0.45" stop-color="#FFFFFF" stop-opacity="0.10"/>
    <stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/></linearGradient>
  <clipPath id="clip"><path d="{TILE_D}"/></clipPath>
</defs>
<path d="{TILE_D}" fill="url(#bg)"/>
<g clip-path="url(#clip)">
  <path d="{TILE_D}" fill="url(#hi)"/>
  <path d="{TILE_D}" fill="url(#dk)"/>
  <path d="{TILE_D}" fill="none" stroke="url(#rim)" stroke-width="8"/>
</g>''')


# ---------------------------------------------------------------- 字形
FOLDER_BACK = ("M 288 690 V 400 a 34 34 0 0 1 34 -34 H 470 a 34 34 0 0 1 34 34 "
               "L 528 436 H 686 a 34 34 0 0 1 34 34 V 690 a 34 34 0 0 1 -34 34 "
               "H 322 a 34 34 0 0 1 -34 -34 Z")
FRONT = (288, 486, 432, 238, 36)
UP = "M 448 500 L 504 560 H 462 V 676 H 434 V 560 H 392 Z"
DOWN = "M 576 688 L 520 628 H 562 V 512 H 590 V 628 H 632 Z"


def folder(back="url(#back)", front="url(#front)"):
    fx, fy, fw, fh, fr = FRONT
    return (f'<path d="{FOLDER_BACK}" fill="{back}"/>'
            f'<rect x="{fx}" y="{fy}" width="{fw}" height="{fh}" rx="{fr}" fill="{front}"/>'
            f'<g clip-path="url(#frontClip)">'
            f'<rect x="{fx}" y="{fy}" width="{fw}" height="46" fill="url(#fold)"/></g>')


def arrows(paint="url(#accent)"):
    return f'<path d="{UP}" fill="{paint}"/><path d="{DOWN}" fill="{paint}"/>'


def corners(paint="url(#front)", gap=26, arm=70, w=16):
    # 包围盒按文件夹实际轮廓（顶部 366，底部 690）外扩
    l, t, r, b = 288 - gap, 366 - gap, 720 + gap, 690 + gap
    return (f'<g fill="none" stroke="{paint}" stroke-width="{w}" stroke-linecap="round">'
            f'<path d="M {l} {t+arm} V {t+16} a 16 16 0 0 1 16 -16 H {l+arm}"/>'
            f'<path d="M {r} {t+arm} V {t+16} a 16 16 0 0 0 -16 -16 H {r-arm}"/>'
            f'<path d="M {l} {b-arm} V {b-16} a 16 16 0 0 0 16 16 H {l+arm}"/>'
            f'<path d="M {r} {b-arm} V {b-16} a 16 16 0 0 1 -16 16 H {r-arm}"/>'
            f'</g>')


GLYPH_DEFS = f'''<defs>
  <linearGradient id="back" x1="0" y1="0" x2="0" y2="1">
    <stop offset="0" stop-color="#F8FBFF"/><stop offset="1" stop-color="#E1EDFF"/></linearGradient>
  <linearGradient id="front" x1="0" y1="0" x2="0" y2="1">
    <stop offset="0" stop-color="#FFFFFF"/><stop offset="1" stop-color="#EAF3FF"/></linearGradient>
  <linearGradient id="fold" x1="0" y1="0" x2="0" y2="1">
    <stop offset="0" stop-color="{INK}" stop-opacity="0.08"/>
    <stop offset="1" stop-color="{INK}" stop-opacity="0"/></linearGradient>
  <linearGradient id="accent" x1="0" y1="0" x2="0" y2="1">
    <stop offset="0" stop-color="#2E6BFF"/><stop offset="1" stop-color="#0E86D6"/></linearGradient>
  <clipPath id="frontClip"><rect x="{FRONT[0]}" y="{FRONT[1]}" width="{FRONT[2]}" height="{FRONT[3]}" rx="{FRONT[4]}"/></clipPath>
</defs>'''


def glyph_body(paint_back="url(#back)", paint_front="url(#front)", arrow="url(#accent)"):
    return corners() + folder(paint_back, paint_front) + arrows(arrow)


def _glyph_group(body: str) -> str:
    return (f'<g transform="translate({CX} {CY}) scale({SCALE}) translate({-CX} {-CY})">'
            f'<g transform="translate(0 {SHIFT_Y})">{body}</g></g>')


def icon_svg() -> str:
    return svg(GLYPH_DEFS + _glyph_group(glyph_body()))


# ---------------------------------------------------------------- 渲染
def render(svg_text, out_svg, out_png, size=CANVAS):
    out_svg.write_text(svg_text, encoding="utf-8")
    subprocess.run([str(RASTER), str(out_svg), str(out_png), str(size)], check=True)
    return Image.open(out_png).convert("RGBA")


def build():
    tile = render(tile_svg(), DIST / "_tile.svg", DIST / "_tile.png")
    white = render(icon_svg(), SRC / "scopeftp-icon.svg", DIST / "_glyph.png")
    dark = render(svg(_glyph_group(glyph_body(INK, INK, INK))),
                  DIST / "_glyph-dark.svg", DIST / "_glyph-dark.png")

    shadow = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    shadow.alpha_composite(dark, (0, 18))
    shadow = shadow.filter(ImageFilter.GaussianBlur(26))
    shadow.putalpha(shadow.split()[3].point(lambda a: int(a * 0.40)))

    out = tile.copy()
    out.alpha_composite(shadow)
    out.alpha_composite(white)
    master = ICONS / "scopeftp-icon-1024.png"
    out.save(master)
    print("built:", master)


if __name__ == "__main__":
    build()

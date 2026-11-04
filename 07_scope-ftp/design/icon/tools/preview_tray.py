#!/usr/bin/env python3
"""ScopeFTP 菜单栏图标预览：修改前后 + 深/浅菜单栏示意。"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

TOOLS = Path(__file__).resolve().parent
ICONS = TOOLS.parent
TRAY = ICONS / "tray"
PREVIEW = ICONS / "preview"
PREVIEW.mkdir(parents=True, exist_ok=True)

FONT = "/System/Library/Fonts/Hiragino Sans GB.ttc"
f_title = ImageFont.truetype(FONT, 38)
f_sub = ImageFont.truetype(FONT, 20)
f_row = ImageFont.truetype(FONT, 19)
f_clock = ImageFont.truetype(FONT, 18)
f_small = ImageFont.truetype(FONT, 16)

tpl36 = Image.open(TRAY / "tray-template-36.png").convert("RGBA")
white32 = Image.open(TRAY / "tray-white-32.png").convert("RGBA")
app = Image.open(ICONS / "scopeftp-icon-1024.png").convert("RGBA")

# 模板图在深色菜单栏里的样子：黑 → 白，保留 alpha
tpl_white = tpl36.copy()
tpl_white.putdata([(255, 255, 255, a) for (*_, a) in tpl_white.getdata()])

W, H = 1560, 1040
sheet = Image.new("RGBA", (W, H), (18, 21, 29, 255))
d = ImageDraw.Draw(sheet)
d.text((60, 44), "ScopeFTP 菜单栏图标", font=f_title, fill=(238, 242, 250, 255))
d.text((60, 96), "白色 + 透明 · macOS 模板图自动反色", font=f_sub, fill=(150, 160, 185, 255))


def bar(y, bg, ink, items, label, note):
    d.rounded_rectangle((60, y, W - 60, y + 76), radius=14, fill=bg)
    d.text((88, y + 28), label, font=f_row, fill=ink)
    d.text((W - 300, y + 28), "11月4日 周三 15:12", font=f_clock, fill=ink)
    x = W - 320
    for im, size in reversed(items):
        x -= size + 10
        sheet.alpha_composite(im.resize((size, size), Image.LANCZOS), (x, y + (76 - size) // 2))
    d.text((88, y + 88), note, font=f_small, fill=(130, 140, 165, 255))


bar(160, (46, 44, 52, 255), (255, 255, 255, 255), [(app, 22)],
    "修改前：直接用应用图标",
    "彩色方块在 22px 下糊成一团，和菜单栏其他符号不搭")
bar(306, (46, 44, 52, 255), (255, 255, 255, 255), [(tpl_white, 18), (tpl_white, 22)],
    "修改后：深色菜单栏（模板图渲染为白色）",
    "白色文件夹 + 上下箭头、透明背景，18 / 22 px 都清晰")
bar(452, (238, 239, 241, 255), (30, 30, 32, 255), [(tpl36, 18), (tpl36, 22)],
    "修改后：浅色菜单栏（模板图自动变成黑色）",
    "同一份模板图，系统按菜单栏明暗自动反色")

y0 = 640
d.text((60, y0), "资源", font=f_row, fill=(220, 228, 240, 255))
cards = [
    ("template 36 / 54", tpl_white, "纯黑 + alpha，macOS 模板图（自动反色）"),
    ("white 32 / 48", white32, "白 + alpha，Windows / Linux 托盘"),
]
for i, (title, im, note) in enumerate(cards):
    x = 60 + i * 460
    d.rounded_rectangle((x, y0 + 36, x + 420, y0 + 316), radius=14, fill=(30, 34, 44, 255))
    sheet.alpha_composite(im.resize((132, 132), Image.LANCZOS), (x + 26, y0 + 70))
    for j, s in enumerate((18, 22, 32)):
        sheet.alpha_composite(im.resize((s, s), Image.LANCZOS), (x + 190 + j * 60, y0 + 96))
    d.text((x + 26, y0 + 236), title, font=f_row, fill=(226, 234, 248, 255))
    d.text((x + 26, y0 + 264), note, font=f_small, fill=(140, 150, 175, 255))

d.text((60, H - 70), "应用图标见 preview/scopeftp-icon-preview.png；菜单栏图只用文件夹 + 箭头，去掉底色与取景框",
       font=f_small, fill=(105, 115, 138, 255))
sheet.convert("RGB").save(PREVIEW / "scopeftp-tray-preview.png")
print("saved", PREVIEW / "scopeftp-tray-preview.png")

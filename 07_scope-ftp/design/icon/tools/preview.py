#!/usr/bin/env python3
"""ScopeFTP 图标交付预览：大图 + 尺寸阶梯 + Dock 实景。"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

TOOLS = Path(__file__).resolve().parent
ICONS = TOOLS.parent
OUT = ICONS / "preview"
OUT.mkdir(parents=True, exist_ok=True)

ICON = Image.open(ICONS / "scopeftp-icon-1024.png").convert("RGBA")
SYS = Path(__file__).resolve().parents[2] / "icons" / "dist" / "preview" / "_sysicons"

W, H = 2000, 1240
sheet = Image.new("RGBA", (W, H), (15, 18, 27, 255))
d = ImageDraw.Draw(sheet)

glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
gd = ImageDraw.Draw(glow)
gd.ellipse((60, 40, 1180, 1000), fill=(30, 80, 220, 30))
gd.ellipse((760, 240, 1960, 1220), fill=(6, 160, 200, 24))
sheet.alpha_composite(glow)

FONT_CJK = "/System/Library/Fonts/Hiragino Sans GB.ttc"
FONT_LATIN = "/System/Library/Fonts/HelveticaNeue.ttc"
f_title = ImageFont.truetype(FONT_CJK, 46)
f_sub = ImageFont.truetype(FONT_CJK, 24)
f_label = ImageFont.truetype(FONT_CJK, 22)
f_small = ImageFont.truetype(FONT_CJK, 18)

d.text((80, 56), "ScopeFTP", font=ImageFont.truetype(FONT_LATIN, 26), fill=(150, 160, 185, 255))
d.text((80, 92), "远程文件 · 图标定稿", font=f_title, fill=(238, 242, 250, 255))

sheet.alpha_composite(ICON.resize((520, 520), Image.LANCZOS), (80, 200))
d.text((84, 738), "应用图标 1024 × 1024", font=f_sub, fill=(160, 170, 195, 255))

sizes = [256, 128, 64, 48, 32, 16]
x, y = 700, 200
d.text((x, y - 34), "尺寸阶梯", font=f_label, fill=(160, 170, 195, 255))
for s in sizes:
    sheet.alpha_composite(ICON.resize((s, s), Image.LANCZOS), (x, y + (256 - s) // 2))
    d.text((x, y + 262), f"{s}px", font=f_small, fill=(130, 140, 165, 255))
    x += s + 28

# 设计拆解
y2 = 520
d.text((700, y2), "设计拆解", font=f_label, fill=(160, 170, 195, 255))
notes = [
    ("文件夹", "FTP 远程目录，文件管理的主对象"),
    ("上下箭头", "上传 / 下载，双向传输"),
    ("四角取景框", "Scope：锁定当前服务器与目录范围"),
]
yy = y2 + 44
for i, (k, v) in enumerate(notes):
    d.text((700, yy), f"0{i+1}", font=ImageFont.truetype(FONT_LATIN, 20), fill=(120, 150, 235, 255))
    d.text((740, yy - 2), k, font=f_label, fill=(226, 234, 248, 255))
    d.text((740, yy + 28), v, font=f_small, fill=(150, 160, 185, 255))
    yy += 84

# Dock 模拟
dock_y = 860
dock = Image.new("RGBA", (1740, 150), (0, 0, 0, 0))
dd = ImageDraw.Draw(dock)
dd.rounded_rectangle((0, 0, 1740, 150), radius=34, fill=(255, 255, 255, 26))
dd.rounded_rectangle((0, 0, 1740, 150), radius=34, outline=(255, 255, 255, 40), width=2)
sheet.alpha_composite(dock, (130, dock_y))
d.text((130, dock_y + 168), "程序坞实景（同一尺寸与其他应用并排）", font=f_label, fill=(160, 170, 195, 255))

imgs = [ICON]
for name in ("Finder", "Notes", "Calendar", "Calculator", "Music", "App Store"):
    p = SYS / f"{name}.png"
    if p.exists():
        imgs.append(Image.open(p).convert("RGBA"))
gap, size = 18, 128
total = len(imgs) * size + (len(imgs) - 1) * gap
cx = (W - total) // 2
for i, im in enumerate(imgs):
    xx = cx + i * (size + gap)
    sheet.alpha_composite(im.resize((size, size), Image.LANCZOS), (xx, dock_y + 12))
    if im is ICON:
        d.rounded_rectangle((xx - 8, dock_y + 2, xx + size + 8, dock_y + 148), radius=22,
                            outline=(120, 190, 255, 200), width=3)

d.text((80, H - 70), "渐变 #1B47E0 → #2E6BFF → #06B6D4     箭头 #2E6BFF → #0E86D6     取景框白色 92%",
       font=f_small, fill=(110, 120, 145, 255))
sheet.convert("RGB").save(OUT / "scopeftp-icon-preview.png")
print("saved", OUT / "scopeftp-icon-preview.png")

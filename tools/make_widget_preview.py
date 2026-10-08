"""生成小组件预览图 res/drawable-nodpi/widget_preview.png。

对照 res/layout/widget_preview.xml 手绘一张等价的位图：老版本的桌面（Android 11 及以下）
只认 android:previewImage，组件库里那张缩略图就是它。3 倍密度，一张 828x549 的 PNG。
"""

from PIL import Image, ImageDraw, ImageFont
import os

SCALE = 3
W_DP, H_DP = 276, 183  # 4 格宽 x 3 格高（5 列网格，公式 格数*73-16 / 格数*66-15）
W, H = W_DP * SCALE, H_DP * SCALE

CARD = (32, 42, 56, 230)      # @color/widget_background  #E6202A38
TITLE = (255, 255, 255, 255)  # @color/widget_title
NAME = (242, 245, 249, 255)   # @color/widget_name
SUB = (169, 182, 198, 255)    # @color/widget_sub
ACCENT = (127, 176, 232, 255) # @color/widget_accent

FONT_CANDIDATES = [
    r"C:\Windows\Fonts\msyh.ttc",
    r"C:\Windows\Fonts\msyhbd.ttc",
    r"C:\Windows\Fonts\simhei.ttf",
    r"C:\Windows\Fonts\Deng.ttf",
    r"C:\Windows\Fonts\simsun.ttc",
]


def load_font(px):
    for path in FONT_CANDIDATES:
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, px)
            except Exception:
                continue
    raise SystemExit("找不到可用的中文字体，无法生成预览图")


def sp(v):
    return int(round(v * SCALE))


img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
d = ImageDraw.Draw(img)

# 圆角卡片 16dp
d.rounded_rectangle([0, 0, W - 1, H - 1], radius=16 * SCALE, fill=CARD)

pad = 10 * SCALE
f_day = load_font(sp(13))
f_week = load_font(sp(11))
f_date = load_font(sp(11))
f_time = load_font(sp(11))
f_name = load_font(sp(12))
f_room = load_font(sp(10))

# ---- 头部：「今天」左对齐，「第 8 周」右对齐（在日期左边），日期最右 ----
y = pad
d.text((pad, y), "今天", font=f_day, fill=TITLE)

week_txt, date_txt = "第 8 周", "10月8日"
dw = d.textlength(date_txt, font=f_date)
d.text((W - pad - dw, y + 4), date_txt, font=f_date, fill=SUB)
ww = d.textlength(week_txt, font=f_week)
d.text((W - pad - dw - 6 * SCALE - ww, y + 4), week_txt, font=f_week, fill=ACCENT)

header_h = sp(13) + 6 * SCALE
top = pad + header_h
bottom = H - pad
rows = [
    ("07:50\n09:20", "高等数学 A", "教7-301 · 王老师"),
    ("09:35\n11:10", "大学英语（三）", "教4-215 · 李老师"),
    ("13:30\n15:00", "数据结构", "计算机楼-402 · 张老师"),
]
row_h = (bottom - top) // len(rows)

for i, (time_txt, name_txt, room_txt) in enumerate(rows):
    ry = top + i * row_h
    lines = time_txt.split("\n")
    lh = sp(11) + 4
    ty = ry + (row_h - lh * 2) // 2
    for line in lines:
        d.text((pad, ty), line, font=f_time, fill=ACCENT)
        ty += lh
    # 右侧：课名 + 教室，整体垂直居中
    nx = pad + 54 * SCALE + 3 * SCALE
    nh, rh = sp(12), sp(10)
    ny = ry + (row_h - (nh + 6 + rh)) // 2
    d.text((nx, ny), name_txt, font=f_name, fill=NAME)
    d.text((nx, ny + nh + 6), room_txt, font=f_room, fill=SUB)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # tools/ 的上一层
out = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi", "widget_preview.png")
os.makedirs(os.path.dirname(out), exist_ok=True)
img.save(out, "PNG", optimize=True)
print("wrote", out, os.path.getsize(out), "bytes", img.size)

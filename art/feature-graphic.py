"""Draws the Google Play feature graphic (1024x500): the lattice "T" from
tune-logo.svg followed by "une" in Selawik Light, on the launcher icon's gray.

    python art/feature-graphic.py   ->  art/feature-graphic.png
"""
import pathlib
import re

from PIL import Image, ImageDraw, ImageFont

HERE = pathlib.Path(__file__).resolve().parent
FONT = HERE.parent / "app/src/main/res/font/selawik_light.ttf"

W, H, SS = 1024, 500, 4          # final size, supersampling
BG = (0x2B, 0x2B, 0x2B)          # launcher_bg
TEXT = (0xF2, 0xF2, 0xF2)
TOP, BOTTOM = (0xE6, 0x16, 0x8A), (0xF8, 0x8E, 0x1E)  # the logo's gradient

svg = (HERE / "tune-logo.svg").read_text()
path = re.search(r'<path d="([^"]+)"', svg).group(1)
segments = [tuple(map(float, re.findall(r"[-\d.]+", s))) for s in path.split("M")[1:]]
stroke = 4.2
# Logo bounds in its own units (centre lines plus half the stroke).
xs = [v for s in segments for v in (s[0], s[2])]
ys = [v for s in segments for v in (s[1], s[3])]
lx0, lx1 = min(xs) - stroke / 2, max(xs) + stroke / 2
ly0, ly1 = min(ys) - stroke / 2, max(ys) + stroke / 2

logo_h = 300 * SS
k = logo_h / (ly1 - ly0)
logo_w = (lx1 - lx0) * k

font = ImageFont.truetype(str(FONT), int(250 * SS))
word = "une"
# "une" follows the T directly, as one word; its baseline sits on the T's foot.
wx0, wy0, wx1, wy1 = font.getbbox(word, anchor="ls")
gap = 0
total = logo_w + gap + (wx1 - wx0)
x0 = (W * SS - total) / 2
y0 = (H * SS - logo_h) / 2

# The T: strokes as a mask, filled with the vertical gradient.
mask = Image.new("L", (W * SS, H * SS), 0)
d = ImageDraw.Draw(mask)
r = stroke * k / 2
pt = lambda x, y: (x0 + (x - lx0) * k, y0 + (y - ly0) * k)
for x1, y1, x2, y2 in segments:
    a, b = pt(x1, y1), pt(x2, y2)
    d.line([a, b], fill=255, width=round(stroke * k))
    for cx, cy in (a, b):
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=255)

g0, g1 = pt(0, 27.8)[1], pt(0, 83.8)[1]  # gradient ends, as in the SVG
grad = Image.new("RGB", (1, H * SS))
for y in range(H * SS):
    t = min(max((y - g0) / (g1 - g0), 0), 1)
    grad.putpixel((0, y), tuple(round(a + (b - a) * t) for a, b in zip(TOP, BOTTOM)))
grad = grad.resize((W * SS, H * SS))

img = Image.new("RGB", (W * SS, H * SS), BG)
img.paste(grad, (0, 0), mask)

# "une": baseline on the bottom of the T's stem.
foot = pt(0, 83.8)[1] + r
ImageDraw.Draw(img).text((x0 + logo_w + gap - wx0, foot), word, font=font, fill=TEXT, anchor="ls")

img.resize((W, H), Image.LANCZOS).save(HERE / "feature-graphic.png", optimize=True)
print(HERE / "feature-graphic.png")

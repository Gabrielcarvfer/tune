"""Draws the Google Play feature graphic (1024x500): the lattice "T" followed
by "une" in Selawik Light, on white (like the launcher icon) and on dark gray.

    python art/feature-graphic.py   ->  art/feature-graphic.png, art/feature-graphic-gray.png
"""
from PIL import Image, ImageDraw, ImageFont

from lattice_t import ASPECT, HERE, T, VARIANTS

FONT = HERE.parent / "app/src/main/res/font/selawik_light.ttf"
W, H, SS = 1024, 500, 4  # final size, supersampling

logo_h = 300 * SS
font = ImageFont.truetype(str(FONT), int(250 * SS))
word = "une"
# "une" follows the T directly, as one word; its baseline sits on the T's foot.
wx0, _, wx1, _ = font.getbbox(word, anchor="ls")
total = logo_h * ASPECT + (wx1 - wx0)
t = T((W * SS - total) / 2, (H * SS - logo_h) / 2, logo_h)

for suffix, bg, text in VARIANTS:
    img = Image.new("RGB", (W * SS, H * SS), bg)
    t.draw(img)
    ImageDraw.Draw(img).text((t.x + t.width - wx0, t.foot), word, font=font, fill=text, anchor="ls")
    out = HERE / f"feature-graphic{suffix}.png"
    img.resize((W, H), Image.LANCZOS).save(out, optimize=True)
    print(out)

"""Draws the Google Play store icon (512x512): the lattice "T" centred on the
launcher icon's gray and on white, full bleed (Play rounds the corners itself).

    python art/play-icon.py   ->  art/play-icon.png, art/play-icon-white.png
"""
from PIL import Image

from lattice_t import ASPECT, HERE, T, VARIANTS

S, SS = 512, 4  # final size, supersampling
FILL = 0.62     # the T's height, as a share of the icon: clear of Play's corner rounding

logo_h = S * SS * FILL
t = T((S * SS - logo_h * ASPECT) / 2, (S * SS - logo_h) / 2, logo_h)

for suffix, bg, _ in VARIANTS:
    img = Image.new("RGB", (S * SS, S * SS), bg)
    t.draw(img)
    out = HERE / f"play-icon{suffix}.png"
    img.resize((S, S), Image.LANCZOS).save(out, optimize=True)
    print(out)

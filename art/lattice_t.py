"""The lattice "T" from tune-logo.svg, drawn with Pillow for the store images
(feature-graphic.py, play-icon.py): round-capped strokes filled with the
logo's magenta-to-orange gradient."""
import pathlib
import re

from PIL import Image, ImageDraw

HERE = pathlib.Path(__file__).resolve().parent

GRAY = (0x2B, 0x2B, 0x2B)  # launcher_bg
WHITE = (0xFF, 0xFF, 0xFF)
# (file name suffix, background, text colour)
VARIANTS = [("", GRAY, (0xF2, 0xF2, 0xF2)), ("-white", WHITE, GRAY)]
TOP, BOTTOM = (0xE6, 0x16, 0x8A), (0xF8, 0x8E, 0x1E)
GRADIENT_Y = (27.8, 83.8)  # where the SVG's gradient starts and ends
STROKE = 4.2

_svg = (HERE / "tune-logo.svg").read_text()
SEGMENTS = [tuple(map(float, re.findall(r"[-\d.]+", s)))
            for s in re.search(r'<path d="([^"]+)"', _svg).group(1).split("M")[1:]]

# Bounds in the logo's own units: centre lines plus half the stroke.
_xs = [v for s in SEGMENTS for v in (s[0], s[2])]
_ys = [v for s in SEGMENTS for v in (s[1], s[3])]
X0, X1 = min(_xs) - STROKE / 2, max(_xs) + STROKE / 2
Y0, Y1 = min(_ys) - STROKE / 2, max(_ys) + STROKE / 2
ASPECT = (X1 - X0) / (Y1 - Y0)


class T:
    """The T placed with its top-left corner at (x, y) and the given height, in pixels."""

    def __init__(self, x, y, height):
        self.x, self.y, self.k = x, y, height / (Y1 - Y0)
        self.width, self.height = (X1 - X0) * self.k, height

    def at(self, lx, ly):
        return self.x + (lx - X0) * self.k, self.y + (ly - Y0) * self.k

    @property
    def foot(self):
        """The bottom of the stem, in pixels."""
        return self.at(0, Y1)[1]

    def draw(self, img):
        w, h = img.size
        mask = Image.new("L", (w, h), 0)
        d = ImageDraw.Draw(mask)
        r = STROKE * self.k / 2
        for x1, y1, x2, y2 in SEGMENTS:
            a, b = self.at(x1, y1), self.at(x2, y2)
            d.line([a, b], fill=255, width=round(STROKE * self.k))
            for cx, cy in (a, b):
                d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=255)
        g0, g1 = (self.at(0, y)[1] for y in GRADIENT_Y)
        column = Image.new("RGB", (1, h))
        for y in range(h):
            t = min(max((y - g0) / (g1 - g0), 0), 1)
            column.putpixel((0, y), tuple(round(a + (b - a) * t) for a, b in zip(TOP, BOTTOM)))
        img.paste(column.resize((w, h)), (0, 0), mask)

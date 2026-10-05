"""Draws numbered arrows on the guide screenshots.

Reads the PNG + JSON pairs that the GuideScreenshots test saved (pulled into
SRC) and writes annotated, downscaled PNGs to docs/guide/. Each mark is the
screen box of something to tap; it gets an outline, and an arrow from a
numbered badge placed beside it, on the side with more room.

    python tools/guide/annotate.py <pulled guide dir>
"""
import json
import pathlib
import sys

from PIL import Image, ImageDraw, ImageFont

OUT = pathlib.Path(__file__).resolve().parents[2] / "docs" / "guide"
SCALE = 0.5            # 1080x2400 -> 540x1200
COLOR = (233, 30, 140)  # the magenta end of the logo's gradient
HALO = (255, 255, 255)
BADGE_R = 46
GAP = 150               # badge distance from the box


def font(size):
    for name in ("segoeuib.ttf", "arialbd.ttf", "DejaVuSans-Bold.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            pass
    return ImageFont.load_default()


def badge_spot(box, others, taken, w, h, side=None):
    """Badge centre near the box, clear of the other marked boxes and badges.

    Tries beside it (left or right) and above or below (toward the right
    edge, where text is sparser), scoring each spot by what it would cover.
    """
    l, t, r, b = box
    cx, cy = (l + r) / 2, (t + b) / 2
    room = BADGE_R + 12
    edge_x = min(r - BADGE_R, w - room)
    # A side may carry its own distance, e.g. "right:420", to clear a neighbour.
    gap = GAP
    if side and ":" in side:
        side, gap = side.split(":")[0], int(side.split(":")[1])
    named = {"left": (l - gap, cy), "right": (r + gap, cy), "above": (cx, t - gap), "below": (cx, b + gap)}
    spots = [
        named["left"], named["right"],
        (edge_x, t - GAP), (edge_x, b + GAP),
        named["above"], named["below"],
    ]
    if side in named:  # the screenshot asked for this side: it wins
        return named[side]
    best, best_score = None, None
    for rank, (x, y) in enumerate(spots):
        if not (room <= x <= w - room and room <= y <= h - room):
            continue
        score = rank * 0.1
        tip = edge_point(box, x, y)
        for o in others:
            if circle_hits(o, x, y, BADGE_R + 10):
                score += 10
            if segment_hits(o, (x, y), tip):
                score += 5
        for tx, ty in taken:
            if (x - tx) ** 2 + (y - ty) ** 2 < (2.5 * BADGE_R) ** 2:
                score += 10
        if best_score is None or score < best_score:
            best, best_score = (x, y), score
    return best or (min(max(cx, room), w - room), min(max(cy, room), h - room))


def circle_hits(box, x, y, radius):
    l, t, r, b = box
    nx, ny = min(max(x, l), r), min(max(y, t), b)
    return (x - nx) ** 2 + (y - ny) ** 2 < radius ** 2


def segment_hits(box, p, q, steps=24):
    l, t, r, b = box
    for i in range(steps + 1):
        x = p[0] + (q[0] - p[0]) * i / steps
        y = p[1] + (q[1] - p[1]) * i / steps
        if l <= x <= r and t <= y <= b:
            return True
    return False


def edge_point(box, x, y):
    """Where the arrow from (x, y) meets the box."""
    l, t, r, b = box
    px = min(max(x, l), r)
    py = min(max(y, t), b)
    return px, py


def arrow(d, x0, y0, x1, y1, width):
    import math
    ang = math.atan2(y1 - y0, x1 - x0)
    head = width * 3.2
    # Stop the shaft under the head.
    sx, sy = x1 - math.cos(ang) * head * 0.8, y1 - math.sin(ang) * head * 0.8
    pts = [
        (x1, y1),
        (x1 - head * math.cos(ang - 0.45), y1 - head * math.sin(ang - 0.45)),
        (x1 - head * math.cos(ang + 0.45), y1 - head * math.sin(ang + 0.45)),
    ]
    for c, extra in ((HALO, 6), (COLOR, 0)):
        d.line([(x0, y0), (sx, sy)], fill=c, width=width + extra)
        d.polygon(pts, fill=c, outline=c if extra == 0 else HALO, width=extra // 2 or 1)


def annotate(png, meta):
    img = Image.open(png).convert("RGB")
    w, h = img.size
    d = ImageDraw.Draw(img)
    f = font(int(BADGE_R * 1.2))
    taken = []
    boxes = [(m["left"] - 10, m["top"] - 10, m["right"] + 10, m["bottom"] + 10) for m in meta["marks"]]
    for m, box in zip(meta["marks"], boxes):
        d.rounded_rectangle(box, radius=14, outline=HALO, width=12)
        d.rounded_rectangle(box, radius=14, outline=COLOR, width=7)
        x, y = badge_spot(box, [o for o in boxes if o is not box], taken, w, h, m.get("side"))
        taken.append((x, y))
        ex, ey = edge_point(box, x, y)
        arrow(d, x, y, ex, ey, 9)
        d.ellipse((x - BADGE_R - 4, y - BADGE_R - 4, x + BADGE_R + 4, y + BADGE_R + 4), fill=HALO)
        d.ellipse((x - BADGE_R, y - BADGE_R, x + BADGE_R, y + BADGE_R), fill=COLOR)
        d.text((x, y + 2), str(m["n"]), font=f, fill=HALO, anchor="mm")
    img = img.resize((int(w * SCALE), int(h * SCALE)), Image.LANCZOS)
    OUT.mkdir(parents=True, exist_ok=True)
    img.save(OUT / png.name, optimize=True)


def main(src):
    src = pathlib.Path(src)
    for png in sorted(src.glob("*.png")):
        meta_file = png.with_suffix(".json")
        meta = json.loads(meta_file.read_text()) if meta_file.exists() else {"marks": []}
        annotate(png, meta)
        print(OUT / png.name)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else ".")

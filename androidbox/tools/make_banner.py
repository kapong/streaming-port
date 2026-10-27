"""Renders the launcher banner: the four-screen mark and "StreamPort" in Google Sans.

    .venv/bin/python -I streamport/tools/make_banner.py

Writes app/src/main/res/drawable-xhdpi/banner.png (640x360, the 320x180dp TV banner at xhdpi).
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
FONT = ROOT / "background/source/GoogleSans-VariableFont_GRAD,opsz,wght.ttf"
OUT = ROOT / "streamport/app/src/main/res/drawable-xhdpi/banner.png"

W, H, SS = 640, 360, 4                     # drawn 4x larger, then scaled down for smooth edges
BG = (11, 15, 20)
TILES = [(48, 224, 148), (48, 224, 196), (54, 121, 242), (255, 14, 54)]   # brand gradient stops
TEXT = (242, 245, 248)


def main():
    img = Image.new("RGB", (W * SS, H * SS), BG)
    d = ImageDraw.Draw(img)

    font = ImageFont.truetype(str(FONT), 84 * SS)
    try:
        font.set_variation_by_axes([0, 48, 600])    # GRAD, opsz, wght: semibold
    except OSError:
        pass
    text = "StreamPort"
    tl, tt, tr, tb = d.textbbox((0, 0), text, font=font)
    text_w, text_h = tr - tl, tb - tt

    # The mark: 2x2 rounded screens, as tall as the cap height plus a little.
    cell_w, cell_h, gap = 48 * SS, 33 * SS, 7 * SS
    mark_w, mark_h = 2 * cell_w + gap, 2 * cell_h + gap
    space = 26 * SS
    x0 = (W * SS - (mark_w + space + text_w)) // 2
    y0 = (H * SS - mark_h) // 2
    for i, colour in enumerate(TILES):
        cx = x0 + (i % 2) * (cell_w + gap)
        cy = y0 + (i // 2) * (cell_h + gap)
        d.rounded_rectangle((cx, cy, cx + cell_w, cy + cell_h), radius=6 * SS, fill=colour)

    tx = x0 + mark_w + space - tl
    ty = (H * SS - text_h) // 2 - tt
    d.text((tx, ty), text, font=font, fill=TEXT)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    img.resize((W, H), Image.LANCZOS).save(OUT, optimize=True)
    print(OUT)


if __name__ == "__main__":
    main()

"""Builds every Android icon asset from the two master artworks in icons/.

    python tools/make_icons.py

Inputs (icons/):
    master-banner.png   4000x2125  wide artwork with the name  -> Android TV launcher banner
    master-square.png   4000x4000  square artwork              -> launcher icon (adaptive + legacy), 512 px store icon

Outputs (app/src/main/res/, all committed so a build never needs Python or Pillow):
    mipmap-*/ic_launcher.png, ic_launcher_round.png     legacy square / round icons (48..192 px)
    mipmap-*/ic_launcher_foreground.png                  adaptive foreground, 108 dp canvas, wordmark inside the safe zone
    values/ic_launcher_background.xml                    adaptive background colour (the artwork's blue)
    drawable-{hdpi,xhdpi,xxhdpi}/banner.png              Android TV banner, 320x180 dp (16:9 crop of the wide artwork)
and icons/icon-512.png (store / README icon).

The artworks have a flat opaque background, so the adaptive foreground is made by "keying" the wordmark out of it: each
pixel's distance from the background colour becomes its alpha, and its colour is the nearest of the artwork's two
foreground colours (off-white for the letters and glow, near-black for the centre dot).
Requires Pillow and NumPy (not needed to build the app).
"""
import os
import numpy as np
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ICONS = os.path.join(ROOT, "icons")
RES = os.path.join(ROOT, "app", "src", "main", "res")

BANNER_SRC = os.path.join(ICONS, "master-banner.png")
SQUARE_SRC = os.path.join(ICONS, "master-square.png")

DENSITY = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
SAFE_FRACTION = 0.74      # wordmark scaled so its corners stay inside the 66 dp adaptive-icon safe circle


def save(img, *parts):
    path = os.path.join(RES, *parts)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, optimize=True)


def background_colour(img):
    return img.convert("RGB").getpixel((4, 4))


def key_foreground(img, bg):
    """RGBA image where the flat background is transparent and the artwork keeps its colours."""
    a = np.asarray(img.convert("RGB")).astype(np.float32)
    b = np.array(bg, dtype=np.float32)
    light = np.array([242, 243, 247], dtype=np.float32)
    dark = np.array([41, 41, 46], dtype=np.float32)
    d = a - b
    brighter = d.sum(axis=2) >= 0
    target = np.where(brighter[..., None], light - b, dark - b)
    num = (d * target).sum(axis=2)
    den = (target * target).sum(axis=2)
    alpha = np.clip(num / den, 0.0, 1.0)
    alpha[alpha < 0.02] = 0.0
    colour = np.where(brighter[..., None], light, dark)
    out = np.dstack([colour, alpha[..., None] * 255.0]).astype(np.uint8)
    return Image.fromarray(out)


def adaptive_foreground(square, bg, size):
    """size x size canvas (108 dp), wordmark centred and shrunk into the safe zone, transparent elsewhere."""
    keyed = key_foreground(square, bg)
    inner = int(round(size * SAFE_FRACTION))
    keyed = keyed.resize((inner, inner), Image.LANCZOS)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    off = (size - inner) // 2
    canvas.paste(keyed, (off, off), keyed)
    return canvas


def round_icon(img):
    size = img.size[0]
    mask = Image.new("L", (size * 4, size * 4), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
    mask = mask.resize((size, size), Image.LANCZOS)
    out = img.convert("RGBA").copy()
    out.putalpha(mask)
    return out


def main():
    square = Image.open(SQUARE_SRC).convert("RGBA")
    banner = Image.open(BANNER_SRC).convert("RGBA")
    bg = background_colour(square)

    for name, scale in DENSITY.items():
        legacy = int(round(48 * scale))
        icon = square.resize((legacy, legacy), Image.LANCZOS)
        save(icon.convert("RGB"), f"mipmap-{name}", "ic_launcher.png")
        save(round_icon(icon), f"mipmap-{name}", "ic_launcher_round.png")
        save(adaptive_foreground(square, bg, int(round(108 * scale))), f"mipmap-{name}", "ic_launcher_foreground.png")

    # Android TV banner: 320x180 dp (16:9). The artwork is slightly wider than 16:9, so trim the sides equally.
    w, h = banner.size
    crop_w = int(round(h * 16 / 9))
    left = (w - crop_w) // 2
    cropped = banner.crop((left, 0, left + crop_w, h)).convert("RGB")
    for name in ("hdpi", "xhdpi", "xxhdpi"):
        s = DENSITY[name]
        save(cropped.resize((int(320 * s), int(180 * s)), Image.LANCZOS), f"drawable-{name}", "banner.png")

    square.resize((512, 512), Image.LANCZOS).convert("RGB").save(os.path.join(ICONS, "icon-512.png"), optimize=True)

    hexcol = "#%02X%02X%02X" % bg
    os.makedirs(os.path.join(RES, "values"), exist_ok=True)
    with open(os.path.join(RES, "values", "ic_launcher_background.xml"), "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">%s</color>\n</resources>\n' % hexcol)
    print("icons written; background", hexcol)


if __name__ == "__main__":
    main()

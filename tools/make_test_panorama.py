"""Test equirectangular panorama: sky gradient, horizon, and labels at FRONT/RIGHT/BACK/LEFT.

Centre column = straight ahead. Labels are written normally (not mirrored); if the app shows them mirrored or
RIGHT appears on the left, the mapping is wrong.
"""
import sys
from PIL import Image, ImageDraw, ImageFont

W, H = 4096, 2048
img = Image.new("RGB", (W, H))
px = img.load()
for y in range(H):
    t = y / (H - 1)                       # 0 top (zenith) .. 1 bottom (nadir)
    if t < 0.5:
        k = t / 0.5
        c = (int(20 + 90 * k), int(40 + 110 * k), int(90 + 110 * k))
    else:
        k = (t - 0.5) / 0.5
        c = (int(60 - 40 * k), int(70 - 45 * k), int(40 - 25 * k))
    for x in range(W):
        px[x, y] = c
d = ImageDraw.Draw(img)
d.line([(0, H // 2), (W, H // 2)], fill=(255, 255, 255), width=4)
try:
    font = ImageFont.truetype("arial.ttf", 150)
except OSError:
    font = ImageFont.load_default()
for name, frac in [("FRONT", 0.5), ("RIGHT", 0.75), ("BACK", 0.0), ("BACK", 1.0), ("LEFT", 0.25)]:
    x = int(frac * W)
    d.line([(x, H // 2 - 200), (x, H // 2 + 200)], fill=(255, 220, 80), width=8)
    tw = d.textlength(name, font=font)
    d.text((x - tw / 2, H // 2 - 420), name, fill=(255, 255, 255), font=font)
img.save(sys.argv[1], quality=88)
print("saved", sys.argv[1])

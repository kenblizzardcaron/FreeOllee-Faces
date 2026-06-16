"""Render the SUPER FREE ollee wordmark lockup to a transparent PNG.

SUPER in Anton (Everforest green), FREE in Permanent Marker (marker red) with
the 100-style double underline, and the watch's "ollee" in lit-green 7-segment
LCD beneath — matching the app icon lockup. Mirrors assets/app-icon.html.

Fonts (Anton OFL, Permanent Marker Apache-2.0) and the recoloured ollee LCD art
are committed under tools/brand/ so this is reproducible without the design
bundle; they are only rasterised here, never shipped in the app."""
import os
import tempfile
from fontTools.ttLib.woff2 import decompress
from PIL import Image, ImageDraw, ImageFilter, ImageFont

FONTS = "tools/brand/fonts"  # committed display fonts — run from repo root
OLLEE = "tools/brand/ollee-lcd.png"  # green 7-segment "ollee", from the watch logo
OUT = "app/src/commonMain/composeResources/drawable/wordmark_super_free.png"  # run from repo root

for woff2 in ("Anton-400.woff2", "PermanentMarker-400.woff2"):
    if not os.path.exists(f"{FONTS}/{woff2}"):
        raise SystemExit(f"Font not found: {FONTS}/{woff2} — run from the repo root.")
if not os.path.exists(OLLEE):
    raise SystemExit(f"ollee art not found: {OLLEE} — run from the repo root.")

tmp = tempfile.mkdtemp()
anton = os.path.join(tmp, "anton.ttf")
marker = os.path.join(tmp, "marker.ttf")
decompress(f"{FONTS}/Anton-400.woff2", anton)
decompress(f"{FONTS}/PermanentMarker-400.woff2", marker)

GREEN = (167, 192, 128, 255)
RED = (248, 85, 82, 255)
W, H = 900, 740
img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
f_super = ImageFont.truetype(anton, 170)
f_free = ImageFont.truetype(marker, 210)

sw = d.textlength("SUPER", font=f_super)
d.text(((W - sw) / 2, 30), "SUPER", font=f_super, fill=GREEN)

fw = d.textlength("FREE", font=f_free)
fx = (W - fw) / 2
d.text((fx, 210), "FREE", font=f_free, fill=RED)

y = 470
d.line([(fx, y), (fx + fw, y)], fill=RED, width=14)
d.line([(fx + 20, y + 34), (fx + fw + 20, y + 34)], fill=RED, width=14)

# "ollee" in lit-green LCD, centred below the underline with a soft green glow.
ollee = Image.open(OLLEE).convert("RGBA")
ow = 360
oh = round(ollee.height * ow / ollee.width)
ollee = ollee.resize((ow, oh), Image.LANCZOS)
ox, oy = (W - ow) // 2, 560
glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
glow.paste(ollee, (ox, oy), ollee)
glow = glow.filter(ImageFilter.GaussianBlur(9))
img.alpha_composite(glow)  # double the glow for a stronger LCD bloom
img.alpha_composite(glow)
img.paste(ollee, (ox, oy), ollee)

os.makedirs(os.path.dirname(OUT), exist_ok=True)
img.save(OUT)
print("Wordmark generated:", OUT)

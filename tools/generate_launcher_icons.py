"""Generate Android launcher icons from the design-system 1024px app icon.

The design tile is a full-bleed square (rounded LCD plate + SUPER/FREE/ollee
lockup on a green segment-grid gradient). The lockup fills ~90% of the tile's
diagonal, so a circle launcher mask clips it ("ollee" gets sliced off). To stay
safe on every mask shape the adaptive icon is split into two layers:

  - background: the Everforest radial gradient + faint segment grid, full-bleed.
  - foreground: just the rounded plate + lockup, scaled into the circle safe
    zone and centred on transparency.

Legacy (pre-adaptive) icons keep the full square tile."""
import math
import os
from PIL import Image, ImageDraw

SRC = "tools/brand/app-icon-1024.png"  # committed design-system source art — run from repo root
RES = "app/src/androidMain/res"  # relative — run this from the repo root

if not os.path.exists(SRC):
    raise SystemExit(f"Source not found: {SRC} — run from the repo root.")

art = Image.open(SRC).convert("RGBA")
TILE = art.size[0]

# Plate geometry in source-tile pixels (404x344 @ r52 on the 512 design, x2),
# and the measured diagonal of the bright lockup, used to size the foreground.
PLATE_BOX = (108, 168, 916, 856)
PLATE_RADIUS = 104
LOCKUP_DIAG = 918.0          # px, in source-tile space
LOCKUP_TARGET_FRAC = 0.70    # lockup diagonal as a fraction of the icon canvas

# Everforest radial-gradient stops from assets/app-icon.html.
G0, G1, G2 = (0x34, 0x41, 0x4a), (0x2d, 0x35, 0x3b), (0x20, 0x27, 0x2b)
GRID = (0xa7, 0xc0, 0x80, 9)  # faint green segment grid (~5% over the dark canvas)

LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
ADAPTIVE = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}


def save(img, density, name):
    d = os.path.join(RES, f"mipmap-{density}")
    os.makedirs(d, exist_ok=True)
    img.save(os.path.join(d, name))


def _lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def gradient_background(size):
    """Radial gradient (centre top) + faint 36px-grid, matching the tile."""
    img = Image.new("RGBA", (size, size))
    px = img.load()
    cx, rx, ry = 0.5 * size, 1.25 * size, 1.05 * size
    for y in range(size):
        for x in range(size):
            t = min(1.0, math.hypot((x - cx) / rx, y / ry))
            c = _lerp(G0, G1, t / 0.44) if t < 0.44 else _lerp(G1, G2, (t - 0.44) / 0.56)
            px[x, y] = (c[0], c[1], c[2], 255)
    step = 36 * size / 512.0
    d = ImageDraw.Draw(img, "RGBA")
    n = 1
    while n * step < size:
        p = int(n * step)
        d.line([(p, 0), (p, size)], fill=GRID)
        d.line([(0, p), (size, p)], fill=GRID)
        n += 1
    return img


def plate_layer():
    """The rounded plate + lockup cropped off the tile, on transparency."""
    plate = art.crop(PLATE_BOX)
    mask = Image.new("L", plate.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, plate.width - 1, plate.height - 1], radius=PLATE_RADIUS, fill=255
    )
    plate.putalpha(mask)
    return plate


PLATE = plate_layer()


def adaptive_foreground(size):
    f = (LOCKUP_TARGET_FRAC * size) / LOCKUP_DIAG
    pw, ph = max(1, int(PLATE.width * f)), max(1, int(PLATE.height * f))
    p = PLATE.resize((pw, ph), Image.LANCZOS)
    fg = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    fg.alpha_composite(p, ((size - pw) // 2, (size - ph) // 2))
    return fg


# Legacy icons: the complete square tile.
for density, size in LEGACY.items():
    im = art.resize((size, size), Image.LANCZOS)
    save(im, density, "ic_launcher.png")
    save(im, density, "ic_launcher_round.png")

# Adaptive layers: gradient background + safe-zone plate foreground.
for density, size in ADAPTIVE.items():
    save(gradient_background(size), density, "ic_launcher_background.png")
    save(adaptive_foreground(size), density, "ic_launcher_foreground.png")

print("Launcher icons generated.")

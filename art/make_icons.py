#!/usr/bin/env python3
# Builds the Android launcher icon layers from the Libreon artwork sheet
# (art/libreon-icons-sheet.png), without redrawing anything: the artwork is cut out
# of the sheet, separated from its tile background, and centred in Android's
# adaptive-icon safe zone.
#
#   foreground   the full-colour fox and tiles (transparent around them)
#   monochrome   the one-colour themed version (alpha only; Android tints it)
#   background   the tile colour, as a colour resource
#
# Usage (needs Pillow): python3 art/make_icons.py
import os
from PIL import Image, ImageChops, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "app", "src", "main", "res")
SHEET = Image.open(os.path.join(HERE, "libreon-icons-sheet.png")).convert("RGB")

# Where things are on the sheet (pixels), measured from the artwork.
PRIMARY_ART = (90, 81, 411, 410)      # fox and tiles inside the primary icon
TILE_COLOUR = (248, 250, 252)         # the primary icon's tile
THEMED_TILE = (1246, 601, 1498, 842)  # the light-mode themed icon (192 dp sample)
THEMED_RADIUS = 46

# The adaptive icon layer is 108 dp; launchers show at least the central 72 dp. The art
# fills the same share of the visible tile as on the sheet (about 82%).
ART_DP = 59
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def foreground_art():
    """The primary artwork with its tile background made transparent."""
    crop = SHEET.crop(PRIMARY_ART)
    out = Image.new("RGBA", crop.size)
    src, dst = crop.load(), out.load()
    bg = TILE_COLOUR
    for y in range(crop.height):
        for x in range(crop.width):
            c = src[x, y]
            d = max(abs(c[i] - bg[i]) for i in range(3))
            # The tile has a faint gradient (up to ~8 levels); treat that as background.
            a = min(1.0, max(0.0, (d - 12) / 40))
            if a <= 0:
                dst[x, y] = (0, 0, 0, 0)
                continue
            # Undo the blend with the tile so edges stay clean on any background.
            rgb = tuple(max(0, min(255, round((c[i] - (1 - a) * bg[i]) / a))) for i in range(3))
            dst[x, y] = rgb + (round(a * 255),)
    # Drop isolated specks (stray shadow pixels on the sheet); the artwork's outlines are
    # far thicker than the 5 px opening, which keeps everything attached to it.
    alpha = out.getchannel("A")
    solid = alpha.point(lambda v: 255 if v > 0 else 0).filter(ImageFilter.MinFilter(5)).filter(ImageFilter.MaxFilter(9))
    out.putalpha(ImageChops.multiply(alpha, solid))
    return out


def monochrome_art():
    """The themed artwork as an alpha mask: ink where the sample is purple."""
    x0, y0, x1, y1 = THEMED_TILE
    crop = SHEET.crop(THEMED_TILE)
    mask = Image.new("L", crop.size, 0)
    # Skip the tile's own anti-aliased edge; the artwork stays 18 px or more inside it.
    inset = 14
    ImageDraw.Draw(mask).rounded_rectangle((inset, inset, crop.width - 1 - inset, crop.height - 1 - inset), THEMED_RADIUS - inset, fill=255)
    out = Image.new("RGBA", crop.size)
    src, m, dst = crop.load(), mask.load(), out.load()
    for y in range(crop.height):
        for x in range(crop.width):
            if not m[x, y]:
                continue
            lum = sum(src[x, y]) / 3
            a = min(1.0, max(0.0, (250 - lum) / (250 - 60)))
            dst[x, y] = (255, 255, 255, round(a * 255))
    return out.crop(out.getbbox())


def layer(art, px, art_px):
    """Centres art (scaled so its longer side is art_px) on a px-by-px canvas."""
    scale = art_px / max(art.size)
    scaled = art.resize((round(art.width * scale), round(art.height * scale)), Image.LANCZOS)
    canvas = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    canvas.alpha_composite(scaled, ((px - scaled.width) // 2, (px - scaled.height) // 2))
    return canvas


def main():
    fg, mono = foreground_art(), monochrome_art()
    for name, factor in DENSITIES.items():
        folder = os.path.join(RES, f"mipmap-{name}")
        os.makedirs(folder, exist_ok=True)
        px, art_px = round(108 * factor), round(ART_DP * factor)
        layer(fg, px, art_px).save(os.path.join(folder, "ic_launcher_foreground.png"), optimize=True)
        layer(mono, px, art_px).save(os.path.join(folder, "ic_launcher_monochrome.png"), optimize=True)

    # The artwork alone (transparent around it), for the logo on the app's home screen.
    nodpi = os.path.join(RES, "drawable-nodpi")
    os.makedirs(nodpi, exist_ok=True)
    layer(fg, 560, 512).crop(layer(fg, 560, 512).getbbox()).save(os.path.join(nodpi, "libreon_logo.png"), optimize=True)

    # Play Store icon (512 px, full square; the store applies its own rounding).
    store = Image.new("RGBA", (512, 512), TILE_COLOUR + (255,))
    store.alpha_composite(layer(fg, 512, round(512 * 0.82)))
    store.convert("RGB").save(os.path.join(HERE, "libreon-store-icon-512.png"), optimize=True)

    # Previews of how launchers mask the icon: circle, squircle and rounded square.
    preview = Image.new("RGB", (3 * 216 + 40, 256), (255, 255, 255))
    full = Image.new("RGBA", (216, 216), TILE_COLOUR + (255,))
    full.alpha_composite(layer(fg, 324, round(ART_DP * 3)).crop((54, 54, 270, 270)))
    for i, radius in enumerate([108, 72, 40]):
        mask = Image.new("L", (216, 216), 0)
        ImageDraw.Draw(mask).rounded_rectangle((0, 0, 215, 215), radius, fill=255)
        preview.paste(full, (10 + i * 226, 20), mask)
    preview.save(os.path.join(HERE, "libreon-mask-preview.png"))

    # Themed icon previews: Android tints the monochrome layer (dark ink on a light
    # tile, or light ink on a dark tile).
    themed = Image.new("RGB", (3 * 216 + 40, 256), (255, 255, 255))
    for i, (tile, ink) in enumerate([((231, 228, 247), (58, 53, 119)), ((42, 79, 168), (214, 226, 255)), ((47, 68, 36), (231, 241, 221))]):
        icon = Image.new("RGBA", (216, 216), tile + (255,))
        alpha = layer(mono, 324, round(ART_DP * 3)).crop((54, 54, 270, 270)).getchannel("A")
        icon.paste(Image.new("RGBA", (216, 216), ink + (255,)), (0, 0), alpha)
        mask = Image.new("L", (216, 216), 0)
        ImageDraw.Draw(mask).rounded_rectangle((0, 0, 215, 215), 72, fill=255)
        themed.paste(icon, (10 + i * 226, 20), mask)
    themed.save(os.path.join(HERE, "libreon-themed-preview.png"))


if __name__ == "__main__":
    main()

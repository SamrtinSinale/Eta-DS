#!/usr/bin/env python3
"""Regenerate Heta's brand icon bitmaps from the master mark.

Source of truth: scripts/brand/heta-mark.png — the white brand mark (the H) on a
transparent background. Everything below is derived from it, so this script is
idempotent: run it as often as you like.

Four outputs, and the sizing rule is *not* the same for all of them:

* Launcher foreground (adaptive icon, API 26+). The canvas is 108dp, but the
  launcher only shows the middle 72dp, so the brand square is mapped onto that
  visible area and the mark keeps its share of the square.
* Legacy launcher icons (pre-API 26). Nothing masks these, so the brand square
  is baked in: the source artwork's squircle filled with brand blue, mark on
  top. The round variant swaps the squircle for an inscribed circle.
* Notification small icon. The canvas is the visible 24dp and Material keeps a
  22dp content area, so the mark fills that by width.

Usage: python3 scripts/gen-brand-icons.py
"""

from __future__ import annotations

import math
import struct
import zlib
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
MASTER = REPO / "scripts" / "brand" / "heta-mark.png"
RES = REPO / "app" / "src" / "main" / "res"

# --- brand constants --------------------------------------------------------

# Measured off the master artwork: the flat fill of the brand square.
BRAND_BLUE = (0x00, 0x59, 0xFF)

# --- geometry ---------------------------------------------------------------

# The mark's width as a share of the brand square's side, measured off the
# master artwork (526px mark on a 922px square). Every output keeps this share
# of whatever box stands in for the square.
MARK_WIDTH_RATIO = 526.0 / 922.0

# The brand square's corners are a superellipse (an iOS-style continuous
# corner), not a circular arc. Fitted to the master artwork's silhouette:
# radius 0.2863 of the side, exponent 2.32, RMSE 1.4px on a 922px square.
SQUIRCLE_CORNER_RATIO = 0.2863
SQUIRCLE_EXPONENT = 2.32

# Adaptive icon: 108dp canvas, 72dp visible. The square maps onto the visible
# area, so the mark ends up at MARK_WIDTH_RATIO * 72/108 of the canvas.
ADAPTIVE_CANVAS = 432  # 108dp @ xxxhdpi
ADAPTIVE_VISIBLE_RATIO = 72.0 / 108.0

# Notification: 24dp canvas, 22dp content area.
NOTIFICATION_CONTENT_RATIO = 22.0 / 24.0
NOTIFICATION_BUCKETS = {
    "mdpi": 24,
    "hdpi": 36,
    "xhdpi": 48,
    "xxhdpi": 72,
    "xxxhdpi": 96,
}

# Legacy launcher icons: 48dp, so the px canvas is the bucket's density scale.
LEGACY_BUCKETS = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

# Alpha below this is treated as padding when measuring the mark's bounding box.
ALPHA_FLOOR = 8

# Sub-samples per axis when rasterising a mask. 4 => 16 samples per pixel,
# enough that a 48px icon's corner is still smooth.
MASK_SAMPLES = 4


# --- minimal PNG io (no third-party deps) -----------------------------------


def decode_png(path: Path) -> tuple[int, int, bytearray]:
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError(f"{path}: not a PNG")
    pos, idat, plte, trns = 8, bytearray(), None, None
    width = height = depth = color_type = None
    while pos < len(data):
        length = struct.unpack(">I", data[pos : pos + 4])[0]
        kind = data[pos + 4 : pos + 8]
        body = data[pos + 8 : pos + 8 + length]
        if kind == b"IHDR":
            width, height, depth, color_type, _, _, interlace = struct.unpack(
                ">IIBBBBB", body
            )
            if depth != 8 or interlace != 0:
                raise ValueError(f"{path}: only 8-bit non-interlaced PNGs")
        elif kind == b"PLTE":
            plte = body
        elif kind == b"tRNS":
            trns = body
        elif kind == b"IDAT":
            idat += body
        elif kind == b"IEND":
            break
        pos += 12 + length

    raw = zlib.decompress(bytes(idat))
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[color_type]
    stride = width * channels
    rows = bytearray(height * stride)
    previous = bytearray(stride)
    cursor = 0
    for y in range(height):
        filter_type = raw[cursor]
        cursor += 1
        line = bytearray(raw[cursor : cursor + stride])
        cursor += stride
        if filter_type == 1:
            for x in range(channels, stride):
                line[x] = (line[x] + line[x - channels]) & 0xFF
        elif filter_type == 2:
            for x in range(stride):
                line[x] = (line[x] + previous[x]) & 0xFF
        elif filter_type == 3:
            for x in range(stride):
                left = line[x - channels] if x >= channels else 0
                line[x] = (line[x] + ((left + previous[x]) >> 1)) & 0xFF
        elif filter_type == 4:
            for x in range(stride):
                left = line[x - channels] if x >= channels else 0
                up = previous[x]
                up_left = previous[x - channels] if x >= channels else 0
                estimate = left + up - up_left
                dl, du, dul = (
                    abs(estimate - left),
                    abs(estimate - up),
                    abs(estimate - up_left),
                )
                if dl <= du and dl <= dul:
                    predictor = left
                elif du <= dul:
                    predictor = up
                else:
                    predictor = up_left
                line[x] = (line[x] + predictor) & 0xFF
        rows[y * stride : (y + 1) * stride] = line
        previous = line

    pixels = bytearray(width * height * 4)
    for y in range(height):
        for x in range(width):
            src = y * stride + x * channels
            dst = (y * width + x) * 4
            if color_type == 6:
                pixels[dst : dst + 4] = rows[src : src + 4]
            elif color_type == 2:
                pixels[dst : dst + 3] = rows[src : src + 3]
                pixels[dst + 3] = 255
            elif color_type == 4:
                grey = rows[src]
                pixels[dst : dst + 3] = bytes((grey, grey, grey))
                pixels[dst + 3] = rows[src + 1]
            elif color_type == 0:
                grey = rows[src]
                pixels[dst : dst + 3] = bytes((grey, grey, grey))
                pixels[dst + 3] = 255
            else:  # palette
                index = rows[src]
                pixels[dst : dst + 3] = plte[index * 3 : index * 3 + 3]
                pixels[dst + 3] = trns[index] if trns and index < len(trns) else 255
    return width, height, pixels


def encode_png(path: Path, width: int, height: int, pixels: bytearray) -> None:
    raw = bytearray()
    stride = width * 4
    for y in range(height):
        raw.append(0)
        raw += pixels[y * stride : (y + 1) * stride]

    def chunk(kind: bytes, body: bytes) -> bytes:
        return (
            struct.pack(">I", len(body))
            + kind
            + body
            + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)
        )

    blob = b"\x89PNG\r\n\x1a\n"
    blob += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    blob += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    blob += chunk(b"IEND", b"")
    path.write_bytes(blob)


# --- image maths ------------------------------------------------------------


def alpha_bbox(width, height, pixels, floor=ALPHA_FLOOR):
    x0, y0, x1, y1 = width, height, -1, -1
    for y in range(height):
        row = y * width * 4
        for x in range(width):
            if pixels[row + x * 4 + 3] >= floor:
                x0, x1 = min(x0, x), max(x1, x)
                y0, y1 = min(y0, y), max(y1, y)
    if x1 < 0:
        raise ValueError("empty alpha channel")
    return x0, y0, x1, y1


def resample_alpha(alpha, src_w, src_h, dst_w, dst_h):
    """Area-average resample of a single-channel array (float in, float out)."""
    out = [0.0] * (dst_w * dst_h)
    scale_x, scale_y = src_w / dst_w, src_h / dst_h
    for dy in range(dst_h):
        y0, y1 = dy * scale_y, (dy + 1) * scale_y
        for dx in range(dst_w):
            x0, x1 = dx * scale_x, (dx + 1) * scale_x
            total, area = 0.0, 0.0
            for sy in range(int(y0), int(math.ceil(y1))):
                cover_y = min(sy + 1, y1) - max(sy, y0)
                if cover_y <= 0:
                    continue
                for sx in range(int(x0), int(math.ceil(x1))):
                    cover_x = min(sx + 1, x1) - max(sx, x0)
                    if cover_x <= 0:
                        continue
                    weight = cover_x * cover_y
                    total += alpha[sy * src_w + sx] * weight
                    area += weight
            out[dy * dst_w + dx] = total / area if area else 0.0
    return out


def load_master():
    width, height, pixels = decode_png(MASTER)
    x0, y0, x1, y1 = alpha_bbox(width, height, pixels)
    mark_w, mark_h = x1 - x0 + 1, y1 - y0 + 1
    alpha = [
        pixels[((y0 + y) * width + (x0 + x)) * 4 + 3] / 255.0
        for y in range(mark_h)
        for x in range(mark_w)
    ]
    return mark_w, mark_h, alpha


def write_mark(path: Path, canvas, mark_w, mark_h, alpha):
    """Centre the resampled mark (pure white + alpha) on a transparent canvas."""
    pixels = bytearray(canvas * canvas * 4)
    offset_x, offset_y = (canvas - mark_w) // 2, (canvas - mark_h) // 2
    for y in range(mark_h):
        for x in range(mark_w):
            value = alpha[y * mark_w + x]
            if value <= 0.0:
                continue
            dst = ((y + offset_y) * canvas + (x + offset_x)) * 4
            pixels[dst : dst + 4] = bytes((255, 255, 255, round(value * 255)))
    encode_png(path, canvas, canvas, pixels)
    print(f"{path.relative_to(REPO)}  {canvas}x{canvas}  mark {mark_w}x{mark_h}")


# --- masks ------------------------------------------------------------------


def squircle_coverage(size, samples=MASK_SAMPLES):
    """Anti-aliased coverage of the brand square, filling the whole canvas."""
    radius = SQUIRCLE_CORNER_RATIO * size
    exponent = SQUIRCLE_EXPONENT
    step = 1.0 / samples
    offsets = [(i + 0.5) * step for i in range(samples)]
    out = [0.0] * (size * size)
    for py in range(size):
        for px in range(size):
            hits = 0
            for oy in offsets:
                y = py + oy
                dy = radius - y if y < radius else (y - (size - radius) if y > size - radius else 0.0)
                for ox in offsets:
                    x = px + ox
                    dx = radius - x if x < radius else (x - (size - radius) if x > size - radius else 0.0)
                    if dx <= 0.0 or dy <= 0.0:
                        hits += 1
                    elif (dx / radius) ** exponent + (dy / radius) ** exponent <= 1.0:
                        hits += 1
            out[py * size + px] = hits / (samples * samples)
    return out


def circle_coverage(size, samples=MASK_SAMPLES):
    """Anti-aliased coverage of the circle inscribed in the canvas."""
    centre = size / 2.0
    radius = size / 2.0
    step = 1.0 / samples
    offsets = [(i + 0.5) * step for i in range(samples)]
    out = [0.0] * (size * size)
    for py in range(size):
        for px in range(size):
            hits = 0
            for oy in offsets:
                dy = py + oy - centre
                for ox in offsets:
                    dx = px + ox - centre
                    if dx * dx + dy * dy <= radius * radius:
                        hits += 1
            out[py * size + px] = hits / (samples * samples)
    return out


def write_legacy_icon(path: Path, size, mask, mark_w, mark_h, alpha):
    """Brand blue through `mask`, with the white mark composited on top."""
    red, green, blue = BRAND_BLUE
    pixels = bytearray(size * size * 4)
    offset_x, offset_y = (size - mark_w) // 2, (size - mark_h) // 2
    for y in range(size):
        for x in range(size):
            coverage = mask[y * size + x]
            if coverage <= 0.0:
                continue
            mark = 0.0
            mx, my = x - offset_x, y - offset_y
            if 0 <= mx < mark_w and 0 <= my < mark_h:
                mark = alpha[my * mark_w + mx]
            out_alpha = mark + coverage * (1.0 - mark)
            if out_alpha <= 0.0:
                continue
            # Source-over: the mark is pure white, the backdrop is brand blue.
            scale = coverage * (1.0 - mark)
            dst = (y * size + x) * 4
            pixels[dst : dst + 4] = bytes(
                (
                    round((255.0 * mark + red * scale) / out_alpha),
                    round((255.0 * mark + green * scale) / out_alpha),
                    round((255.0 * mark + blue * scale) / out_alpha),
                    round(out_alpha * 255),
                )
            )
    encode_png(path, size, size, pixels)
    print(f"{path.relative_to(REPO)}  {size}x{size}  mark {mark_w}x{mark_h}")


def main() -> None:
    src_w, src_h, src_alpha = load_master()
    print(f"master mark: {src_w}x{src_h} (aspect {src_w / src_h:.3f})")

    # Launcher foreground: the brand square maps onto the adaptive icon's 72dp
    # visible area, so the mark keeps its share of that area.
    fg_w = round(ADAPTIVE_CANVAS * MARK_WIDTH_RATIO * ADAPTIVE_VISIBLE_RATIO)
    fg_h = max(1, round(fg_w * src_h / src_w))
    write_mark(
        RES / "drawable-nodpi" / "ic_launcher_foreground.png",
        ADAPTIVE_CANVAS,
        fg_w,
        fg_h,
        resample_alpha(src_alpha, src_w, src_h, fg_w, fg_h),
    )

    # Legacy launcher icons: the brand square fills the canvas.
    for bucket, canvas in LEGACY_BUCKETS.items():
        mark_w = round(canvas * MARK_WIDTH_RATIO)
        mark_h = max(1, round(mark_w * src_h / src_w))
        scaled = resample_alpha(src_alpha, src_w, src_h, mark_w, mark_h)
        write_legacy_icon(
            RES / f"mipmap-{bucket}" / "ic_launcher.png",
            canvas,
            squircle_coverage(canvas),
            mark_w,
            mark_h,
            scaled,
        )
        write_legacy_icon(
            RES / f"mipmap-{bucket}" / "ic_launcher_round.png",
            canvas,
            circle_coverage(canvas),
            mark_w,
            mark_h,
            scaled,
        )

    # Notification: the mark fills the 22dp content area by width.
    for bucket, canvas in NOTIFICATION_BUCKETS.items():
        target_w = round(canvas * NOTIFICATION_CONTENT_RATIO)
        target_h = max(1, round(target_w * src_h / src_w))
        write_mark(
            RES / f"drawable-{bucket}" / "ic_notification.png",
            canvas,
            target_w,
            target_h,
            resample_alpha(src_alpha, src_w, src_h, target_w, target_h),
        )


if __name__ == "__main__":
    main()

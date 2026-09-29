#!/usr/bin/env python3
"""Regenerate Heta's brand icon bitmaps from the master mark.

Source of truth: scripts/brand/heta-mark.png — the white brand mark on a
transparent background (432x432). Everything below is derived from it, so this
script is idempotent: run it as often as you like.

Two sizes matter, and they are *not* the same number:

* Launcher foreground (adaptive icon). The canvas is 108dp, but only the middle
  72dp is visible after the launcher mask. The mark is therefore scaled to the
  same share of the *visible* area that it has in the standalone icon mockup
  (~54% x 55%), i.e. 2/3 of the canvas share.
* Notification small icon. The canvas is the visible 24dp; Material keeps a 2dp
  padding, so the mark fills the 20dp live area (~83%).

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

# --- geometry ---------------------------------------------------------------

# Adaptive icon: 108dp canvas, 72dp visible, mark scaled to 2/3 of the canvas
# share it has in the standalone mockup.
ADAPTIVE_CANVAS = 432  # 108dp @ xxxhdpi
ADAPTIVE_MARK_SCALE = 2.0 / 3.0

# Notification: 24dp canvas, 20dp live area.
NOTIFICATION_LIVE_AREA = 20.0 / 24.0
NOTIFICATION_BUCKETS = {
    "mdpi": 24,
    "hdpi": 36,
    "xhdpi": 48,
    "xxhdpi": 72,
    "xxxhdpi": 96,
}

# Alpha below this is treated as padding when measuring the mark's bounding box.
ALPHA_FLOOR = 8


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


def main() -> None:
    src_w, src_h, src_alpha = load_master()
    print(f"master mark: {src_w}x{src_h} (aspect {src_w / src_h:.3f})")

    # Launcher foreground: 108dp canvas, mark at 2/3 of its standalone share.
    fg_w = round(src_w * ADAPTIVE_MARK_SCALE)
    fg_h = round(src_h * ADAPTIVE_MARK_SCALE)
    write_mark(
        RES / "drawable-nodpi" / "ic_launcher_foreground.png",
        ADAPTIVE_CANVAS,
        fg_w,
        fg_h,
        resample_alpha(src_alpha, src_w, src_h, fg_w, fg_h),
    )

    # Notification: 24dp canvas, mark inside the 20dp live area.
    for bucket, canvas in NOTIFICATION_BUCKETS.items():
        target_h = round(canvas * NOTIFICATION_LIVE_AREA)
        target_w = max(1, round(src_w * target_h / src_h))
        write_mark(
            RES / f"drawable-{bucket}" / "ic_notification.png",
            canvas,
            target_w,
            target_h,
            resample_alpha(src_alpha, src_w, src_h, target_w, target_h),
        )


if __name__ == "__main__":
    main()

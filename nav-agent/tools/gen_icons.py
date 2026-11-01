#!/usr/bin/env python3
"""生成 nav-agent 的传统 PNG 图标（各密度）。

为什么需要 PNG：
    自适应图标（mipmap-anydpi-v26）只覆盖 Android 8.0+。
    部分启动器/桌面仍会取 mipmap-*/ic_launcher.png，
    没有的话会退化成系统默认图标。

为什么不用 Pillow：
    本机没装，而为了生成几张图标去装依赖不值得。
    PNG 就是 zlib 压缩的扫描线，纯标准库就能写。

图案：
    深蓝渐变方框 + 白色导航箭头（跟自适应图标前景一致）
"""

import os
import struct
import zlib

OUT_ROOT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "android", "app", "src", "main", "res"
)

# 各密度：目录名 -> 边长
DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

# 配色（跟应用界面统一）
BG_TOP = (0x1B, 0x30, 0x40)       # #1B3040
BG_BOTTOM = (0x0E, 0x16, 0x20)    # #0E1620
FG_WHITE = (0xFF, 0xFF, 0xFF)
FG_LIGHT = (0xE8, 0xF2, 0xFB)
FG_MID = (0xB8, 0xD4, 0xEE)
FG_DARK = (0x4A, 0x7B, 0xA7)


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def write_png(path, width, height, pixels):
    """pixels: list of rows, each row is list of (r,g,b,a)"""
    raw = bytearray()
    for row in pixels:
        raw.append(0)  # filter type 0
        for r, g, b, a in row:
            raw += bytes((r, g, b, a))

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", ihdr)
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


def point_in_triangle(px, py, tri):
    (x1, y1), (x2, y2), (x3, y3) = tri
    d = (y2 - y3) * (x1 - x3) + (x3 - x2) * (y1 - y3)
    if abs(d) < 1e-9:
        return False
    a = ((y2 - y3) * (px - x3) + (x3 - x2) * (py - y3)) / d
    b = ((y3 - y1) * (px - x3) + (x1 - x3) * (py - y3)) / d
    c = 1 - a - b
    return a >= 0 and b >= 0 and c >= 0


def point_in_polygon(px, py, poly):
    """射线法判断点是否在多边形内（支持任意边数的多边形）。"""
    n = len(poly)
    inside = False
    j = n - 1
    for i in range(n):
        xi, yi = poly[i]
        xj, yj = poly[j]
        if (yi > py) != (yj > py):
            x_cross = (xj - xi) * (py - yi) / (yj - yi + 1e-12) + xi
            if px < x_cross:
                inside = not inside
        j = i
    return inside


def build_icon(size):
    """按 108x108 的坐标系画，再缩放到 size"""
    S = 108.0
    k = size / S

    # 箭头在 108 坐标系里的顶点（对应 ic_launcher_foreground.xml）
    top_l = (54, 28)
    top_r = (76, 38)
    mid_l = (32, 38)
    mid_c = (54, 48)
    bot_l = (32, 70)
    bot_c = (54, 80)
    bot_r = (76, 70)

    # 四个面（用多边形，箭头是折角造型，不是单纯的三角形）
    face_a = [top_l, mid_c, bot_c, bot_l]        # 左下深色
    face_b = [top_l, top_r, mid_c]              # 上三角（白）
    face_c = [top_r, bot_r, bot_c, mid_c]        # 右下浅色
    face_d = [mid_c, bot_c, bot_r]              # 中间浅色三角

    rows = []
    for y in range(size):
        row = []
        for x in range(size):
            px = (x + 0.5) / k
            py = (y + 0.5) / k
            # 背景：斜向渐变
            t = min(1.0, max(0.0, (px + py) / (2 * S)))
            color = lerp(BG_TOP, BG_BOTTOM, t)
            alpha = 255

            if point_in_polygon(px, py, face_b):
                color = FG_WHITE
            elif point_in_polygon(px, py, face_a):
                color = FG_DARK
            elif point_in_polygon(px, py, face_c):
                color = FG_MID
            elif point_in_polygon(px, py, face_d):
                color = FG_LIGHT

            row.append((color[0], color[1], color[2], alpha))
        rows.append(row)
    return rows


def main():
    for folder, size in DENSITIES.items():
        out_dir = os.path.join(OUT_ROOT, folder)
        os.makedirs(out_dir, exist_ok=True)
        pixels = build_icon(size)
        for name in ("ic_launcher.png", "ic_launcher_round.png"):
            path = os.path.join(out_dir, name)
            write_png(path, size, size, pixels)
        print(f"{folder}: {size}x{size} 已生成")


if __name__ == "__main__":
    main()

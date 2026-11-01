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
    亮蓝灰渐变方框 + 白色朝上的定位箭头（跟自适应图标前景一致）

★ 为什么是「朝上的定位箭头」而不是折角：
    折角造型本身就像立体方块，叠加 EMUI 的前景立体化后整个图标变成立方体，
    完全看不出是导航 app（实测踩过）。定位箭头最高度可辨识。

★ 为什么底色比应用界面亮：
    桌面壁纸通常也是深色的，图标底色太深会跟壁纸糊在一起（用户反馈「底色太深」）。
    图标跟应用界面不必完全同色 —— 图标要的是「在桌面上能一眼找到」。
"""

import math
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

# 配色：亮蓝灰渐变（比应用界面亮，保证深色壁纸上能跳出来）
BG_TOP = (0x6B, 0xAE, 0xE0)       # #6BAEE0
BG_BOTTOM = (0x2F, 0x6F, 0xA8)    # #2F6FA8
FG_WHITE = (0xFF, 0xFF, 0xFF)
FG_HOLE = (0x3E, 0x7C, 0xB4)      # 箭头中间的孔


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
    """按 108x108 的坐标系画朝上的定位箭头，再缩放到 size"""
    S = 108.0
    k = size / S

    # 箭头参数（对应 ic_launcher_foreground.xml）
    tip_x, tip_y = 54.0, 30.0     # 尖（朝上）
    cx, cy = 54.0, 65.0           # 圆心
    r = 21.0                      # 下半圆的半径
    hole_r = 10.0                 # 中间孔的半径

    rows = []
    for y in range(size):
        row = []
        for x in range(size):
            px = (x + 0.5) / k
            py = (y + 0.5) / k

            # 背景：斜向渐变
            t = min(1.0, max(0.0, (px + py) / (2 * S)))
            color = lerp(BG_TOP, BG_BOTTOM, t)

            # 箭头形状：
            #   上半部分是从尖角到圆心的两侧切线（一个尖顶的等腰三角形）
            #   下半部分是圆
            in_arrow = False
            if py >= cy - 1e-9:
                # 下半圆
                if (px - cx) ** 2 + (py - cy) ** 2 <= r * r:
                    in_arrow = True
            else:
                # 三角形：从尖角 (tip_x,tip_y) 到圆弧两端 (cx±r, cy)
                half_w = r * (py - tip_y) / (cy - tip_y)
                if abs(px - cx) <= half_w:
                    in_arrow = True

            if in_arrow:
                # 中间的孔挖掉
                if (px - cx) ** 2 + (py - cy) ** 2 <= hole_r * hole_r:
                    color = FG_HOLE
                else:
                    color = FG_WHITE

            row.append((color[0], color[1], color[2], 255))
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

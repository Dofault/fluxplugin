#!/usr/bin/env python3
"""Advance of every character of Minecraft's default font, for text measured on the server.

The HUD (Flux hud package) places each part of a boss bar title from the centre of the screen, then steps back by the
part's width: it must know, to the pixel, how far the client moves after each character. This reads the default font
of the client jar (space provider, then the bitmap pages of include/default.json, in their order: the first provider
that has a character wins, as in the game) and writes {codepoint: advance} to Flux's resources.

Characters not listed (the unifont fallback) are measured as 6 px by Flux.
No dependency: PNG decoded with zlib.

Usage: gen_font_widths.py <client-26.x.jar>   (rerun after a Minecraft update)
"""
import json
import os
import struct
import sys
import zipfile
import zlib

OUT = os.path.join(os.path.dirname(__file__), "..", "..", "src", "main", "resources", "font_widths.json")


def png_alpha(data):
    """Width, height and alpha rows of a PNG (RGBA, RGB, grey or palette, 8 bits)."""
    assert data[:8] == b"\x89PNG\r\n\x1a\n"
    i, idat, trns = 8, b"", None
    while i < len(data):
        n = struct.unpack(">I", data[i:i + 4])[0]
        kind, body = data[i + 4:i + 8], data[i + 8:i + 8 + n]
        i += 12 + n
        if kind == b"IHDR":
            w, h, depth, ctype = struct.unpack(">IIBB", body[:10])
            assert depth == 8 or ctype in (0, 3), "only 8-bit colour PNGs"
        elif kind == b"IDAT":
            idat += body
        elif kind == b"tRNS":
            trns = body
    bpp = {6: 4, 2: 3, 3: 1, 0: 1, 4: 2}[ctype] if depth == 8 else 1
    stride = w * bpp if depth == 8 else (w * depth + 7) // 8
    raw, prev, p, rows = zlib.decompress(idat), bytearray(stride), 0, []
    for _ in range(h):
        f = raw[p]
        line = bytearray(raw[p + 1:p + 1 + stride])
        p += 1 + stride
        for x in range(stride):
            a = line[x - bpp] if x >= bpp else 0
            b = prev[x]
            c = prev[x - bpp] if x >= bpp else 0
            if f == 1:
                line[x] = (line[x] + a) & 255
            elif f == 2:
                line[x] = (line[x] + b) & 255
            elif f == 3:
                line[x] = (line[x] + (a + b) // 2) & 255
            elif f == 4:
                pp = a + b - c
                pa, pb, pc = abs(pp - a), abs(pp - b), abs(pp - c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        prev = line
        if depth < 8:
            mask = (1 << depth) - 1
            line = [(line[(x * depth) // 8] >> (8 - depth - (x * depth) % 8)) & mask for x in range(w)]
        if ctype == 6:
            rows.append([line[x * 4 + 3] for x in range(w)])
        elif ctype == 4:
            rows.append([line[x * 2 + 1] for x in range(w)])
        elif ctype == 3:
            rows.append([(trns[k] if trns and k < len(trns) else 255) for k in line])
        elif ctype == 0 and trns:
            key = struct.unpack(">H", trns[:2])[0]
            rows.append([0 if v == key else 255 for v in line])
        else:
            rows.append([255] * w)
    return w, h, rows


def main(jar_path):
    jar = zipfile.ZipFile(jar_path)
    widths = {}
    for k, v in json.loads(jar.read("assets/minecraft/font/include/space.json"))["providers"][0]["advances"].items():
        widths[ord(k)] = int(v)
    for provider in json.loads(jar.read("assets/minecraft/font/include/default.json"))["providers"]:
        if provider.get("type") != "bitmap":
            continue
        ns, path = provider["file"].split(":", 1) if ":" in provider["file"] else ("minecraft", provider["file"])
        w, h, alpha = png_alpha(jar.read("assets/%s/textures/%s" % (ns, path)))
        chars = provider["chars"]
        cell_w, cell_h = w // len(chars[0]), h // len(chars)
        scale = provider.get("height", 8) / cell_h
        for row, line in enumerate(chars):
            for col, ch in enumerate(line):
                cp = ord(ch)
                if cp == 0 or cp in widths:
                    continue
                actual = 0
                for x in range(cell_w - 1, -1, -1):
                    if any(alpha[row * cell_h + y][col * cell_w + x] for y in range(cell_h)):
                        actual = x + 1
                        break
                # BitmapProvider: (int) (0.5 + width * scale) + 1
                widths[cp] = int(0.5 + actual * scale) + 1
    out = {str(k): widths[k] for k in sorted(widths)}
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(out, f, separators=(",", ":"))
    print("%d characters -> %s" % (len(out), os.path.normpath(OUT)))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "/home/influencraft/serveurs/04_labs/plugins/FluxPortal/cache/client-26.2.jar")

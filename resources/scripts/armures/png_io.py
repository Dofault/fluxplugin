"""PNG sans dépendance (pas de PIL sur la machine) : lecture de tout PNG non entrelacé (palette, gris, RGB,
RGBA, 1 à 8 bits) vers une grille RGBA, écriture en RGBA 8 bits."""
import struct
import zlib

SIG = b"\x89PNG\r\n\x1a\n"


def read(path):
    """-> (largeur, hauteur, px) avec px[y][x] = (r, g, b, a)."""
    return decode(open(path, "rb").read(), path)


def decode(d, path="<octets>"):
    assert d[:8] == SIG, path
    i, idat, plte, trns = 8, b"", None, None
    while i < len(d):
        ln, = struct.unpack(">I", d[i:i + 4])
        t, c = d[i + 4:i + 8], d[i + 8:i + 8 + ln]
        if t == b"IHDR":
            w, h, bd, ct, _, _, il = struct.unpack(">IIBBBBB", c)
            assert il == 0 and bd <= 8, path
        elif t == b"PLTE":
            plte = c
        elif t == b"tRNS":
            trns = c
        elif t == b"IDAT":
            idat += c
        i += 12 + ln
    ch = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ct]
    bpp = max(1, ch * bd // 8)
    st = (w * ch * bd + 7) // 8
    raw = zlib.decompress(idat)
    prev, p, px = bytearray(st), 0, []
    for _ in range(h):
        f, line = raw[p], bytearray(raw[p + 1:p + 1 + st])
        p += 1 + st
        for x in range(st):
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
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else (b if pb <= pc else c))) & 255
        prev = line
        if bd < 8:
            vals = [(line[(k * bd) // 8] >> (8 - bd - (k * bd) % 8)) & ((1 << bd) - 1) for k in range(w * ch)]
        else:
            vals = list(line)
        row = []
        for x in range(w):
            v = vals[x * ch:x * ch + ch]
            if ct == 3:
                k = v[0]
                row.append((plte[k * 3], plte[k * 3 + 1], plte[k * 3 + 2], trns[k] if trns and k < len(trns) else 255))
            elif ct == 0:
                g = v[0] * 255 // ((1 << bd) - 1)
                row.append((g, g, g, 255))
            elif ct == 4:
                row.append((v[0], v[0], v[0], v[1]))
            elif ct == 2:
                row.append((v[0], v[1], v[2], 255))
            else:
                row.append(tuple(v))
        px.append(row)
    return w, h, px


def write(path, px):
    h, w = len(px), len(px[0])
    raw = b"".join(b"\x00" + b"".join(bytes(c) for c in row) for row in px)

    def chunk(t, c):
        return struct.pack(">I", len(c)) + t + c + struct.pack(">I", zlib.crc32(t + c) & 0xFFFFFFFF)
    with open(path, "wb") as f:
        f.write(SIG + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def sheet(images, cols, scale=1, pad=6, bg=(30, 28, 38, 255)):
    """Planche de contact : images = grilles RGBA, agrandies au plus proche voisin, sur damier sombre."""
    cw = max(len(i[0]) for i in images) * scale + pad
    chh = max(len(i) for i in images) * scale + pad
    rows = (len(images) + cols - 1) // cols
    out = [[bg] * (cols * cw + pad) for _ in range(rows * chh + pad)]
    for n, im in enumerate(images):
        ox, oy = pad + (n % cols) * cw, pad + (n // cols) * chh
        for y, row in enumerate(im):
            for x, c in enumerate(row):
                if c[3] == 0:
                    continue
                a = c[3] / 255
                for dy in range(scale):
                    for dx in range(scale):
                        b = out[oy + y * scale + dy][ox + x * scale + dx]
                        out[oy + y * scale + dy][ox + x * scale + dx] = tuple(
                            int(c[k] * a + b[k] * (1 - a)) for k in range(3)) + (255,)
    return out

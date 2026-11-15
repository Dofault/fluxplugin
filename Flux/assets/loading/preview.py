"""Renders gen_loading.py's shaders the way Minecraft 26.2's LoadingOverlay draws (moderngl, EGL, no window).

Each picture replays the game's fills in one vertex buffer, as GuiRenderer does, so gl_VertexID % 4 is the same.
"""
import math
import pathlib
import struct

import moderngl
import numpy as np
from PIL import Image

BRAND = (239, 50, 61)


def ortho(w, h):
    near, far = 1000.0, 11000.0
    m = np.zeros((4, 4), dtype="f4")
    m[0, 0] = 2.0 / w
    m[1, 1] = -2.0 / h
    m[2, 2] = -2.0 / (far - near)
    m[0, 3] = -1.0
    m[1, 3] = 1.0
    m[2, 3] = -(far + near) / (far - near)
    m[3, 3] = 1.0
    return m


def translate_z(z):
    m = np.eye(4, dtype="f4")
    m[2, 3] = z
    return m


def mat_bytes(m):
    return m.T.astype("f4").tobytes()  # column-major


def fill(quads, x0, y0, x1, y1, argb):
    # GuiGraphicsExtractor.fill: x0/y0 become the larger, then (x0,y0) (x0,y1) (x1,y1) (x1,y0)
    if x0 < x1:
        x0, x1 = x1, x0
    if y0 < y1:
        y0, y1 = y1, y0
    quads.append(((x0, y0), (x0, y1), (x1, y1), (x1, y0), argb))


def overlay_quads(gw, gh, progress, alpha=255):
    """LoadingOverlay.extractProgressBar."""
    logo_h = min(gw * 0.75, gh) * 0.25
    half = int(logo_h * 4.0 * 0.5)
    bar_y = int(gh * 0.8325)
    x0, y0, x1, y1 = gw // 2 - half, bar_y - 5, gw // 2 + half, bar_y + 5
    width = math.ceil((x1 - x0 - 2) * progress)
    white = (alpha, 255, 255, 255)
    q = []
    fill(q, x0 + 2, y0 + 2, x0 + width, y1 - 2, white)
    fill(q, x0 + 1, y0, x1 - 1, y0 + 1, white)
    fill(q, x0 + 1, y1, x1 - 1, y1 - 1, white)
    fill(q, x0, y0, x0 + 1, y1, white)
    fill(q, x1, y0, x1 - 1, y1, white)
    return q


class Bench:
    def __init__(self, shaders):
        self.ctx = moderngl.create_standalone_context(backend="egl", require=330)
        self.prog = self.ctx.program(vertex_shader=(shaders / "gui.vsh").read_text(),
                                     fragment_shader=(shaders / "gui.fsh").read_text())
        for i, name in enumerate(("DynamicTransforms", "Projection", "Globals")):
            if name in self.prog:
                self.prog[name].binding = i

    def frame(self, fw, fh, scale, quads, clear, time=0.0):
        gw, gh = fw / scale, fh / scale
        ctx = self.ctx
        fbo = ctx.simple_framebuffer((fw, fh))
        fbo.use()
        fbo.clear(*[c / 255 for c in clear], 1.0)
        ctx.enable(moderngl.BLEND)
        ctx.blend_func = moderngl.SRC_ALPHA, moderngl.ONE_MINUS_SRC_ALPHA
        dyn = mat_bytes(translate_z(-2000.0)) + struct.pack("4f", 1, 1, 1, 1) + struct.pack("4f", 0, 0, 0, 0) + mat_bytes(np.eye(4))
        proj = mat_bytes(ortho(gw, gh))
        glob = struct.pack("3i4x3f4x2f2f2i8x", 0, 0, 0, 0, 0, 0, fw, fh, 1.0, time, 0, 0)
        ubos = [ctx.buffer(dyn), ctx.buffer(proj), ctx.buffer(glob)]
        for i, b in enumerate(ubos):
            b.bind_to_uniform_block(i)
        verts, idx = [], []
        for n, (*corners, argb) in enumerate(quads):
            a, r, g, b = argb
            for (x, y) in corners:
                verts.append(struct.pack("3f4B", x, y, 0.0, r, g, b, a))
            k = n * 4
            idx += [k, k + 1, k + 2, k + 2, k + 3, k]
        vbo = ctx.buffer(b"".join(verts))
        ibo = ctx.buffer(np.array(idx, dtype="i4").tobytes())
        vao = ctx.vertex_array(self.prog, [(vbo, "3f 4f1", "Position", "Color")], index_buffer=ibo)
        vao.render(moderngl.TRIANGLES)
        img = Image.frombytes("RGBA", (fw, fh), fbo.read(components=4)).transpose(Image.FLIP_TOP_BOTTOM)
        for o in [vao, vbo, ibo, fbo] + ubos:
            o.release()
        return img.convert("RGB")


def render(shaders, out, px, palette):
    out.mkdir(parents=True, exist_ok=True)
    bench = Bench(shaders)
    shots = []
    for fw, fh, scale in ((1920, 1080, 3), (854, 480, 2), (1280, 1024, 4)):
        gw, gh = math.ceil(fw / scale), math.ceil(fh / scale)
        for p in (0.0, 0.62, 1.0):
            img = bench.frame(fw, fh, scale, overlay_quads(gw, gh, p), BRAND, time=0.0004)
            name = "load_%dx%d_%d.png" % (fw, fh, round(p * 100))
            img.save(out / name)
            shots.append(name)
    # fade out (phase 2): the game's screen (grey here) under the brand fill at half alpha
    gw, gh = 640, 360
    q = [((gw, gh), (gw, 0), (0, 0), (0, gh), (255, 60, 60, 70))]
    fill(q, 0, 0, gw, gh, (128,) + BRAND)
    bench.frame(1920, 1080, 3, q, (40, 40, 40)).save(out / "fade_half.png")
    # any other GUI: white fills, a red full-screen fill with a margin, untouched
    q = []
    fill(q, 10, 10, 200, 40, (255, 255, 255, 255))
    fill(q, 230, 294, 260, 304, (255, 255, 255, 255))  # the frame's rows, other columns
    fill(q, 141, 330, 300, 340, (255, 255, 255, 255))  # the bar's columns, other rows
    fill(q, 5, 5, gw - 5, gh - 5, (80,) + BRAND)
    bench.frame(1920, 1080, 3, q, (40, 40, 40)).save(out / "other_gui.png")
    # animation strip
    frames = [bench.frame(960, 540, 2, overlay_quads(480, 270, 0.4), BRAND, time=0.0004 + i * 0.25 / 1200.0)
              for i in range(12)]
    frames[0].save(out / "anim.gif", save_all=True, append_images=frames[1:], duration=250, loop=0)
    art = Image.new("RGBA", (len(px[0]), len(px)))
    for y, row in enumerate(px):
        for x, v in enumerate(row):
            art.putpixel((x, y), palette[v] + (255,) if v else (0, 0, 0, 0))
    art.resize((art.width * 4, art.height * 4), Image.NEAREST).save(out / "picture.png")
    print("preview in", out, shots)

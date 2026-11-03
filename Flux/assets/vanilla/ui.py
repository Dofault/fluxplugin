"""Vanilla UI kit (2026-10-29): the drawing pieces of the new menu theme, meant to become Flux's menu builder.

Window: the owner's generic background (menu_vanilla.png: Minecraft grey window, title plate, two gold pins).
Pieces, in window pixels (176 x 221, item of a slot at fond_lib.objet(slot)):
  title(im, txt)                       text on the title plate
  slot(d, case, kind)                  vanilla | dark | lit (selected) | colour cell
  container(im, d, box, style, ...)    sub-container: ardoise | encre | puits | carte | verre, header strip/tab/label
  button(d, box, colour, icon)         button of the reference: black outline, bevel, white line under the bottom edge
  field(d, box, ...)                   dark display (value, page "1/2")
  bar(d, box, pct, colour)             progress bar
"""
import os
import sys

from PIL import Image, ImageDraw

sys.path.insert(0, "/home/influencraft/plugins/fluxplugin/Flux/assets")
import fond_lib  # noqa: E402
from fond_lib import objet  # noqa: E402

fond_lib._EXTRA.update({
    "/": ["..#", "..#", ".#.", "#..", "#.."], "%": ["#.#", "..#", ".#.", "#..", "#.#"], ".": [".", ".", ".", ".", "#"],
    ":": [".", "#", ".", "#", "."], "+": ["...", ".#.", "###", ".#.", "..."], "·": [".", ".", "#", ".", "."],
})

W, H = 176, 221
BLACK = (0, 0, 0, 255)
WHITE = (255, 255, 255, 255)
BODY = (198, 198, 198, 255)
SHADE = (85, 85, 85, 255)
SLOT = (139, 139, 139, 255)
SLOT_DARK = (55, 55, 55, 255)
INK = (64, 64, 64, 255)
LIGHT_TEXT = (230, 230, 230, 255)


def rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) + (255,)


def tone(c, f):
    if f >= 0:
        return tuple(round(c[i] + (255 - c[i]) * f) for i in range(3)) + (255,)
    return tuple(round(c[i] * (1 + f)) for i in range(3)) + (255,)


def window(generic):
    """The generic window with a clean outline: 1 px black all around (the source misses the bottom edge and the top
    of the right edge), 2 px dark grey shade inside the right and bottom edges, as the rest of the frame."""
    im = Image.open(generic).convert("RGBA").copy()
    px = im.load()
    w, h = im.size
    for y in range(7, h):
        px[w - 1, y] = BLACK
        px[0, y] = BLACK if y >= 8 else px[0, y]
    for x in range(0, w):
        px[x, h - 1] = BLACK
    for x in range(3, w - 1):
        px[x, h - 3] = SHADE
        px[x, h - 2] = SHADE
    for y in range(h - 3, h - 1):    # bottom left: the white light of the left edge stops at the shade
        px[1, y] = SHADE
        px[2, y] = SHADE
    return im


def write(im, txt, cx, y, colour, shadow=None, left=None):
    """QuinqueFive capitals centred on cx (or starting at left), optional shadow (None = flat)."""
    x = left if left is not None else cx - fond_lib.text_width(txt) // 2
    px = im.load()
    for c in txt.upper():
        g, extra = fond_lib._glyph(c)
        if not g:
            x += 4
            continue
        for dy, row in enumerate(g):
            for dx, ch in enumerate(row):
                if ch == "#":
                    if shadow:
                        px[x + dx, y + dy + 1] = shadow
        for dy, row in enumerate(g):
            for dx, ch in enumerate(row):
                if ch == "#":
                    px[x + dx, y + dy] = colour
        for dx, dy in extra:
            px[x + dx, y + dy] = colour
        x += len(g[0]) + 1
    return x


def title(im, txt):
    write(im, txt, 88, 4, INK)


def slot_box(case, rows=6):
    x, y = objet(case, rows)
    return x - 1, y - 1, x + 16, y + 16


def slot(d, case, kind="vanilla", colour=None, rows=6):
    """rows: chest rows of the menu; under a smaller chest the inventory is higher in game."""
    x0, y0, x1, y1 = slot_box(case, rows)
    if kind == "vanilla":
        base, tl, br = SLOT, SLOT_DARK, WHITE
    elif kind == "dark":
        base, tl, br = rgb("#4A4B50"), rgb("#2C2D31"), rgb("#6E6F75")
    elif kind == "ink":
        base, tl, br = rgb("#2B2B30"), rgb("#18181B"), rgb("#45454C")
    elif kind == "lit":        # selected: light raised cell
        base, tl, br = rgb("#D9D9D9"), WHITE, rgb("#6E6E6E")
    else:                      # colour cell (highlight)
        base, tl, br = colour, tone(colour, -0.35), tone(colour, 0.35)
    d.rectangle((x0, y0, x1, y1), fill=base)
    d.line((x0, y0, x1 - 1, y0), fill=tl)
    d.line((x0, y0, x0, y1 - 1), fill=tl)
    d.line((x0 + 1, y1, x1, y1), fill=br)
    d.line((x1, y0 + 1, x1, y1), fill=br)


def outline_cell(d, case, colour=WHITE):
    x0, y0, x1, y1 = slot_box(case)
    d.rectangle((x0 - 1, y0 - 1, x1 + 1, y1 + 1), outline=colour)


STYLES = {
    #           fill                  inner shadow           header band           header text
    "ardoise": (rgb("#585B5F"), rgb("#45474B"), rgb("#35363A"), LIGHT_TEXT),
    "encre": (rgb("#232327"), rgb("#141416"), rgb("#141416"), LIGHT_TEXT),
    "puits": (SLOT, SLOT_DARK, rgb("#6B6B6B"), WHITE),
    "carte": (rgb("#E4E4E4"), WHITE, rgb("#35363A"), LIGHT_TEXT),
    "verre": (rgb("#A9ABB0"), rgb("#8E9095"), rgb("#585B5F"), WHITE),
}


def container(im, d, box, style="ardoise", head=None, head_kind="strip", head_h=10, accent=None):
    """Sub-container. head_kind: strip (band inside the top), tab (folder tab above the left), label (ink text above)."""
    fill, shadow, band, ink = STYLES[style]
    x0, y0, x1, y1 = box
    if head and head_kind == "tab":
        tw = fond_lib.text_width(head) + 10
        d.rectangle((x0, y0 - head_h, x0 + tw, y0), fill=BLACK)
        d.rectangle((x0 + 1, y0 - head_h + 1, x0 + tw - 1, y0), fill=accent or band)
        d.line((x0 + 1, y0 - head_h + 1, x0 + tw - 1, y0 - head_h + 1), fill=tone(accent or band, 0.3))
        write(im, head, 0, y0 - head_h + 3, ink if not accent else WHITE, None, left=x0 + 5)
    if style == "carte":       # raised card around a darker well
        d.rectangle(box, fill=BLACK)
        d.rectangle((x0 + 1, y0 + 1, x1 - 1, y1 - 1), fill=fill)
        d.line((x0 + 1, y0 + 1, x1 - 1, y0 + 1), fill=WHITE)
        d.line((x0 + 1, y0 + 1, x0 + 1, y1 - 1), fill=WHITE)
        d.line((x0 + 1, y1 - 1, x1 - 1, y1 - 1), fill=rgb("#8E8E8E"))
        d.line((x1 - 1, y0 + 1, x1 - 1, y1 - 1), fill=rgb("#8E8E8E"))
    elif style == "puits":     # vanilla inset, no black outline
        d.rectangle(box, fill=fill)
        d.line((x0, y0, x1 - 1, y0), fill=SLOT_DARK)
        d.line((x0, y0, x0, y1 - 1), fill=SLOT_DARK)
        d.line((x0 + 1, y1, x1, y1), fill=WHITE)
        d.line((x1, y0 + 1, x1, y1), fill=WHITE)
    else:
        d.rectangle(box, fill=BLACK)
        d.rectangle((x0 + 1, y0 + 1, x1 - 1, y1 - 1), fill=fill)
        d.line((x0 + 1, y0 + 1, x1 - 1, y0 + 1), fill=shadow)
        d.line((x0 + 1, y0 + 1, x0 + 1, y1 - 1), fill=shadow)
        d.line((x0, y1 + 1, x1, y1 + 1), fill=WHITE)   # white line under the box, from edge to edge
    if head and head_kind == "strip":
        top = y0 + 1
        d.rectangle((x0 + 1, top, x1 - 1, top + head_h - 1), fill=accent or band)
        d.line((x0 + 1, top + head_h, x1 - 1, top + head_h), fill=BLACK)
        write(im, head, (x0 + x1) // 2, top + (head_h - 5) // 2, WHITE if accent else ink, None)
    if head and head_kind == "label":
        write(im, head, 0, y0 - 7, INK, None, left=x0 + 1)


def button(d, box, colour, icon=None, label=None, im=None):
    """Reference button: black outline, light top-left edge, darker bottom, white line under the bottom outline."""
    x0, y0, x1, y1 = box
    d.rectangle(box, fill=BLACK)
    d.rectangle((x0 + 1, y0 + 1, x1 - 1, y1 - 1), fill=tone(colour, 0.45))
    d.rectangle((x0 + 2, y0 + 2, x1 - 1, y1 - 1), fill=tone(colour, -0.25))
    d.rectangle((x0 + 2, y0 + 2, x1 - 2, y1 - 3), fill=colour)
    d.line((x0 + 1, y1 + 1, x1 - 1, y1 + 1), fill=WHITE)
    d.point((x0, y1 + 1), fill=tone(BODY, 0.4))
    d.point((x1, y1 + 1), fill=tone(BODY, 0.4))
    cx, cy = (x0 + x1) // 2, (y0 + y1) // 2 - 1
    if icon:
        ICONS[icon](d, cx, cy)
    if label and im is not None:
        write(im, label, cx + 1, cy - 2, WHITE, tone(colour, -0.55))


def button_slot(d, case, colour, icon):
    x, y = objet(case)
    button(d, (x - 1, y - 1, x + 16, y + 15), colour, icon)


def _px(d, pts, cx, cy, c=WHITE, sh=(0, 0, 0, 140)):
    for x, y in pts:
        d.point((cx + x + 1, cy + y + 1), fill=sh)
    for x, y in pts:
        d.point((cx + x, cy + y), fill=c)


def _arrow(sign):
    def f(d, cx, cy):
        pts = [(sign * i, j) for i in range(-3, 4) for j in (-1, 0)]
        pts += [(sign * -3 + sign * k, j) for k in range(0, 4) for j in range(-k - 1, k + 1) if abs(j + 0.5) <= 3.5 - k]
        _px(d, set(pts), cx, cy)
    return f


def _cross(d, cx, cy):
    _px(d, [(i, i) for i in range(-3, 4)] + [(i, -i) for i in range(-3, 4)] + [(i + 1, i) for i in range(-3, 3)] + [(i + 1, -i) for i in range(-3, 4)], cx, cy)


def _info(d, cx, cy):
    _px(d, [(0, -4), (1, -4), (0, -2), (1, -2)] + [(x, y) for x in (0, 1) for y in range(-1, 4)] + [(-1, 3), (2, 3)], cx, cy)


def _check(d, cx, cy):
    _px(d, [(-3, 0), (-2, 1), (-1, 2), (0, 1), (1, 0), (2, -1), (3, -2), (-3, 1), (-2, 2), (-1, 3), (0, 2), (1, 1), (2, 0), (3, -1)], cx, cy)


def _plus(d, cx, cy):
    _px(d, [(i, j) for i in range(-3, 4) for j in (0, 1)] + [(j, i) for i in range(-3, 4) for j in (0, 1) if i not in (0, 1)], cx, cy)


def _back(d, cx, cy):
    _px(d, [(-3, 0), (-2, -1), (-2, 1), (-1, -2), (-1, 2)] + [(i, 0) for i in range(-2, 4)] + [(3, -1), (3, -2), (3, -3)], cx, cy)


ICONS = {"left": _arrow(1), "right": _arrow(-1), "close": _cross, "info": _info, "check": _check, "plus": _plus, "back": _back}


def field(im, d, box, txt="", colour=LIGHT_TEXT, align="center", style="ink"):
    x0, y0, x1, y1 = box
    fill = rgb("#35363A") if style == "ink" else rgb("#232327")
    d.rectangle(box, fill=BLACK)
    d.rectangle((x0 + 1, y0 + 1, x1 - 1, y1 - 1), fill=fill)
    d.line((x0 + 1, y0 + 1, x1 - 1, y0 + 1), fill=tone(fill, -0.4))
    if txt:
        ty = (y0 + y1) // 2 - 2
        if align == "center":
            write(im, txt, (x0 + x1) // 2 + 1, ty, colour)
        else:
            write(im, txt, 0, ty, colour, left=x0 + 4)


def bar(d, box, pct, colour):
    x0, y0, x1, y1 = box
    d.rectangle(box, fill=BLACK)
    d.rectangle((x0 + 1, y0 + 1, x1 - 1, y1 - 1), fill=rgb("#2B2B30"))
    w = round((x1 - x0 - 2) * pct)
    if w > 0:
        d.rectangle((x0 + 1, y0 + 1, x0 + w, y1 - 1), fill=colour)
        d.line((x0 + 1, y0 + 1, x0 + w, y0 + 1), fill=tone(colour, 0.4))
        d.line((x0 + 1, y1 - 1, x0 + w, y1 - 1), fill=tone(colour, -0.3))
    d.line((x0 + 1, y1 + 1, x1 - 1, y1 + 1), fill=WHITE)


def flat_button(d, box, icon):
    """Light pager button of the shop reference."""
    x0, y0, x1, y1 = box
    d.rectangle(box, fill=BLACK)
    d.rectangle((x0 + 1, y0 + 1, x1 - 1, y1 - 1), fill=rgb("#D4D4D4"))
    d.line((x0 + 1, y0 + 1, x1 - 1, y0 + 1), fill=WHITE)
    d.line((x0 + 1, y0 + 1, x0 + 1, y1 - 1), fill=WHITE)
    d.line((x0 + 1, y1 - 1, x1 - 1, y1 - 1), fill=rgb("#8B8B8B"))
    cx, cy = (x0 + x1) // 2, (y0 + y1) // 2
    pts = [(0, 0), (0, -1), (0, 1), (0, -2), (0, 2), (1, -1), (1, 0), (1, 1), (2, 0)]
    for x, y in pts:
        d.point((cx + (x if icon == "right" else -x), cy + y), fill=rgb("#6B6B6B"))


def item(im, case, png, dx=0, dy=0):
    if png is None or not os.path.exists(png):
        return
    tex = Image.open(png).convert("RGBA")
    if tex.size != (16, 16):
        tex = tex.resize((16, 16), Image.LANCZOS if tex.width > 16 else Image.NEAREST)
    x, y = objet(case)
    im.alpha_composite(tex, (x + dx, y + dy))


def portrait(im, png, x, y):
    im.alpha_composite(Image.open(png).convert("RGBA").resize((64, 64), Image.NEAREST), (x, y))

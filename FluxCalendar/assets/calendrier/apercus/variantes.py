import sys, os
sys.path.insert(0, sys.argv[1])
import gen_fond as g, ui
from PIL import Image, ImageDraw
from fond_lib import text_width

W, H, LIFT = g.W, g.H, g.LIFT


def narrow_frame(cut):
    """Current frame minus `cut` plain columns on each side (left interior, right: between plate and tabs and before
    the border), so the plate stays centred on x 88. Window then spans cut .. 175 - cut."""
    base = g.frame().im
    left = set(range(8, 8 + cut))
    rcols = list(range(139, 143)) + list(range(163, 172))
    right = set(rcols[:cut])
    keep = [x for x in range(W) if x not in left and x not in right]
    out = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    for i, x in enumerate(keep):
        out.paste(base.crop((x, 0, x + 1, H)), (cut + i, 0))
    out.putpixel((W - 1, LIFT + 20), (0, 0, 0, 8))   # keeps the 176 px advance of the background glyph
    return g.Canvas(out)


def sheet(c, pal, piles=True, spiral=False, torn=False, outline=g.OUTLINE):
    x0, y0, x1, y1 = g.SHEET
    if piles:
        for k, colour in ((2, pal["pile"][1]), (1, pal["pile"][0])):
            c.rect((x0 + 1 + k, y1 - 4 + k * 2, x1 + k, y1 + k * 2), fill=colour, outline=outline)
    c.rect(g.SHEET, outline=outline)
    g.bevel(c, (x0 + 1, y0 + 1, x1 - 1, y1 - 1), pal["paper"], pal["paper_hi"], pal["paper_lo"])
    if torn:   # tear-off page: zigzag bottom edge under the last row
        for x in range(x0, x1 + 1):
            d = (x - x0) % 4
            yy = y1 + (d if d <= 2 else 4 - d) - 1
            c.line([(x, yy), (x, y1 + 3)], fill=(0, 0, 0, 0))
            c.point(x, yy, outline)
    c.rect((x0 + 1, g.BAND_TOP, x1 - 1, g.BAND_BOTTOM), fill=pal["band"])
    c.line([(x0 + 1, g.BAND_TOP), (x1 - 1, g.BAND_TOP)], fill=pal["band_hi"])
    c.line([(x0 + 1, g.BAND_BOTTOM + 1), (x1 - 1, g.BAND_BOTTOM + 1)], fill=pal["band_lo"])
    for i, ch in enumerate(g.WEEKDAYS):
        c.text(ch, g.item(1 + i)[0] + 8, g.BAND_TOP + 3, colour=pal["weekend"] if i >= 5 else pal["letters"],
               shadow=pal["band_lo"])
    if spiral:
        for x in range(x0 + 6, x1 - 3, 9):
            c.rect((x, g.BAND_TOP - 4, x + 1, g.BAND_TOP + 1), fill=pal["ring_dark"])
            c.line([(x, g.BAND_TOP - 3), (x, g.BAND_TOP)], fill=pal["ring"])
    else:
        for x in (g.item(3)[0] - 1, g.item(6)[0] - 1):
            c.rect((x - 1, g.BAND_TOP - 3, x + 1, g.BAND_TOP + 3), fill=pal["ring_dark"])
            c.line([(x, g.BAND_TOP - 2), (x, g.BAND_TOP + 2)], fill=pal["ring"])
    if pal.get("stars"):
        for x in (x0 + 4, x1 - 4):
            star(c, x, g.BAND_TOP + 5)


def star(c, x, y):
    c.point(x, y, g.GOLD_LIGHT)
    for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
        c.point(x + dx, y + dy, g.GOLD)


def slots(c):
    for r in range(6):
        for col in range(1, 8):
            c.slot(r * 9 + col)


def ribbon(c, body, light, dark):
    old = (g.RED, g.RED_LIGHT, g.RED_DARK)
    g.RED, g.RED_LIGHT, g.RED_DARK = body, light, dark
    g.welcome(c)
    g.RED, g.RED_LIGHT, g.RED_DARK = old


RED = dict(band=g.RED, band_hi=g.RED_LIGHT, band_lo=g.RED_DARK, letters=g.WHITE, weekend=g.GOLD_LIGHT,
           paper=g.PAPER, paper_hi=g.PAPER_HI, paper_lo=g.PAPER_LO, pile=g.PILE, ring=g.RING, ring_dark=g.RING_DARK)
VIOLET = dict(RED, band=ui.rgb("#5B2D9E"), band_hi=ui.rgb("#B376E5"), band_lo=ui.rgb("#1E0A3C"),
              ring=g.GOLD_LIGHT, ring_dark=g.GOLD_DARK, stars=True)
GOLD = dict(RED, band=ui.rgb("#1E0A3C"), band_hi=ui.rgb("#5B2D9E"), band_lo=ui.rgb("#0D0D0D"), letters=g.GOLD_LIGHT,
            weekend=ui.rgb("#E060A0"), paper=ui.rgb("#F3E9D2"), paper_hi=g.WHITE, paper_lo=ui.rgb("#B59A62"),
            pile=[ui.rgb("#E2D3B0"), ui.rgb("#C9B68C")], ring=g.GOLD_LIGHT, ring_dark=g.GOLD_DARK, stars=True)


def v1():   # classic, narrowed
    c = narrow_frame(12); sheet(c, RED); slots(c); g.welcome(c); return c.im


def v2():   # charter violet
    c = narrow_frame(12); sheet(c, VIOLET); slots(c)
    ribbon(c, ui.rgb("#5B2D9E"), ui.rgb("#B376E5"), ui.rgb("#1E0A3C")); return c.im


def v3():   # tear-off with a spiral binding
    c = narrow_frame(12); sheet(c, RED, piles=False, spiral=True, torn=True); slots(c); g.welcome(c); return c.im


def v4():   # minimal: no paper, letters engraved on the window
    c = narrow_frame(13)
    for i, ch in enumerate(g.WEEKDAYS):
        ui.write(c.im, ch, g.item(1 + i)[0] + 8, g.BAND_TOP + 3 + LIFT, g.RED_DARK if i >= 5 else g.INK)
    c.line([(g.item(1)[0] - 1, g.BAND_BOTTOM + 1), (g.item(7)[0] + 16, g.BAND_BOTTOM + 1)], fill=ui.rgb("#8B8B8B"))
    c.line([(g.item(1)[0] - 1, g.BAND_BOTTOM + 2), (g.item(7)[0] + 16, g.BAND_BOTTOM + 2)], fill=g.WHITE)
    slots(c)
    ui.write(c.im, "Bienvenue !", 88, g.BANNER_Y + 2 + LIFT, g.INK)
    return c.im


def v5():   # cream and gold, night band
    c = narrow_frame(12); sheet(c, GOLD, outline=g.GOLD_DARK); slots(c)
    ribbon(c, g.GOLD, g.GOLD_LIGHT, g.GOLD_DARK); return c.im


if __name__ == "__main__":
    out = sys.argv[2]
    names = [("1_classique_resserre", v1), ("2_violet_charte", v2), ("3_ephemeride_spirale", v3),
             ("4_minimal_grave", v4), ("5_creme_et_or", v5)]
    shots = []
    for n, f in names:
        p = g.preview(f(), 3)
        p.save(os.path.join(out, n + ".png")); shots.append(p)
    cur = g.preview(g.background(), 3)
    cur.save(os.path.join(out, "0_actuel.png"))
    allp = [cur] + shots
    sheet_im = Image.new("RGBA", (allp[0].width * 3 + 40, allp[0].height * 2 + 30), (30, 30, 30, 255))
    for i, p in enumerate(allp):
        sheet_im.alpha_composite(p, ((i % 3) * (p.width + 20), (i // 3) * (p.height + 30)))
    sheet_im.save(os.path.join(out, "planche.png"))
    print("ok")


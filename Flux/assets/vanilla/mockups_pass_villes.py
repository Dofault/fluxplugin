"""Mockups (2026-10-30): pass menu (free track grey, premium track green) and Ville / Pays home, vanilla theme.
Usage: python3 mockups_pass_villes.py <plugins dir> <out dir>
"""
import os
import sys

from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_ui  # noqa: E402  (game slot positions, fitted windows, sprites)
import ui  # noqa: E402
from ui import BLACK, WHITE, rgb, tone, write  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
GENERIC = os.path.join(HERE, "menu_vanilla.png")
INK = rgb("#404040")
SH = (40, 40, 40, 255)
GREEN, GREEN_D, GREEN_L = rgb("#3E8E2E"), rgb("#2A6420"), rgb("#6FBF4A")
GOLD = rgb("#D9A21B")
PLUG = ""
T = {}


def tex(name):
    if name in T:
        return T[name]
    c = os.path.join(PLUG, "ItemsAdder", "contents")
    cands = {
        "chest": c + "/influitems/textures/chest.png", "chest_open": c + "/influitems/textures/chest_unlock.png",
        "chest_done": c + "/influitems/textures/chest_checkmark.png", "lock": c + "/fluxui/textures/re/re_influitems_lock.png",
        "unlock": c + "/fluxui/textures/re/re_influitems_unlock.png", "back": c + "/fluxui/textures/btn/btn_back_orange.png",
        "info": c + "/fluxui/textures/btn/btn_info_blue.png", "next": c + "/fluxui/textures/btn/btn_right_orange.png",
        "prev": c + "/fluxui/textures/btn/btn_left_orange.png", "pass": c + "/influitems/textures/pass.png",
        "diamond": c + "/itemshopplus/textures/itemshopplus/diamond.png", "emerald": c + "/itemshopplus/textures/itemshopplus/emerald.png",
        "gold": c + "/itemshopplus/textures/itemshopplus/gold_ingot.png", "book": c + "/itemshopplus/textures/itemshopplus/books/enchanted_book.png",
        "apple": c + "/itemshopplus/textures/itemshopplus/golden_apple.png", "iron": c + "/itemshopplus/textures/itemshopplus/iron_ingot.png",
        "ville": c + "/fluxui/textures/re/re_influville_ville.png", "pays": c + "/fluxui/textures/re/re_influville_pays.png",
        "annuaire": c + "/fluxui/textures/re/re_influville_annuaire.png", "warp": c + "/fluxui/textures/re/re_influville_warp.png",
        "agence": c + "/fluxui/textures/re/re_influville_agence.png", "echoppe": c + "/fluxui/textures/re/re_influville_echoppe.png",
        "habitants": c + "/fluxui/textures/re/re_influville_habitants.png", "banque": c + "/fluxui/textures/re/re_influville_banque.png",
        "capitale": c + "/fluxui/textures/re/re_influville_capitale.png", "niveau": c + "/fluxui/textures/re/re_influville_niveau.png",
        "clock": c + "/itemshopplus/textures/itemshopplus/books/book.png",
    }
    p = cands.get(name)
    T[name] = Image.open(p).convert("RGBA") if p and os.path.exists(p) else None
    return T[name]


def put(im, case, name, dx=0, dy=0):
    t = tex(name)
    if t is None:
        return
    x, y = gen_ui.game_objet(case)
    im.alpha_composite(t.resize((16, 16), Image.NEAREST), (x + dx, y + dy))


def big(im, name, x, y, size=32):
    t = tex(name)
    if t is not None:
        im.alpha_composite(t.resize((size, size), Image.NEAREST), (x, y))


def slot(d, case, kind="vanilla", colour=None):
    ui.slot(d, case, kind, colour)


def box_of(cases, pad=1):
    xs = [gen_ui.game_objet(c)[0] for c in cases]
    ys = [gen_ui.game_objet(c)[1] for c in cases]
    return min(xs) - 1 - pad, min(ys) - 1 - pad, max(xs) + 16 + pad, max(ys) + 16 + pad


def green_box(im, d, box):
    x0, y0, x1, y1 = box
    d.rectangle(box, fill=BLACK)
    d.rectangle((x0 + 1, y0 + 1, x1 - 1, y1 - 1), fill=GREEN_D)
    d.line((x0 + 1, y0 + 1, x1 - 1, y0 + 1), fill=tone(GREEN_D, -0.3))
    d.line((x0, y1 + 1, x1, y1 + 1), fill=WHITE)


def gslot(d, case):
    ui.slot(d, case, "colour", GREEN)


def title(im, txt, first):
    write(im, txt, 88, 5 + 18 * first, INK, None)


def fill_bar(d, im, x, y, w, pct, colour=GREEN, txt=None):
    ui.bar(d, (x, y, x + w, y + 11), pct, colour)
    if txt:
        write(im, txt, x + w // 2 + 1, y + 3, WHITE, SH)


REW = ["diamond", "gold", "book", "emerald", "apple", "iron", "diamond"]


# ---------------------------------------------------------------------------------------------------- pass

def p1(im, d):
    """Two tracks (screenshot): free on top, lock strip, premium green below; 7 tiers per page."""
    title(im, "Pass", 0)
    put(im, 0, "back"); put(im, 8, "pass")
    write(im, "Saison 3 · 12 j", 88, 22, INK, None)
    for k in range(7):
        c = 10 + k
        slot(d, c); put(im, c, REW[k] if k < 3 else "chest")
    for k in range(7):
        c = 19 + k
        put(im, c, "unlock" if k < 3 else "lock")
    green_box(im, d, box_of([28, 34]))
    for k in range(7):
        c = 28 + k
        gslot(d, c); put(im, c, "chest_done" if k < 2 else "chest_open" if k == 2 else "chest")
    write(im, "Gratuit", 4, 0, INK, None, left=4) if False else None
    put(im, 18, "prev"); put(im, 26, "next")
    fill_bar(d, im, 6, 56 + 18 * 2 - 2 + 18, 163, 0.42, GREEN, "Palier 13/50")


def p2(im, d):
    """Branches: one column per tier, number badge in the middle, free above, premium below, on a green line."""
    title(im, "Pass", 0)
    put(im, 0, "back"); put(im, 8, "pass")
    green_box(im, d, box_of([37, 43]))
    d.rectangle((6, 62, 169, 63), fill=GREEN_L)
    for k in range(7):
        x, _ = gen_ui.game_objet(10 + k)
        reached = k < 3
        col = GREEN_L if reached else rgb("#8B8B8B")
        d.rectangle((x + 7, 34, x + 8, 71), fill=col)                    # branch up
        d.rectangle((x + 7, 90, x + 8, 107), fill=col)                   # branch down
        slot(d, 10 + k); put(im, 10 + k, REW[k])
        bx, by = x, 57
        d.rectangle((bx - 1, by - 1, bx + 16, by + 11), fill=BLACK)
        d.rectangle((bx, by, bx + 15, by + 10), fill=GOLD if reached else rgb("#5A5A5A"))
        write(im, str(11 + k), bx + 8, by + 3, WHITE, SH)
        gslot(d, 37 + k); put(im, 37 + k, "chest_done" if k < 2 else "chest_open" if k == 2 else "chest")
    put(im, 27, "prev"); put(im, 35, "next")


def p3(im, d):
    """Season card: big banner with progress, then milestones every 5 tiers, premium row in green."""
    title(im, "Pass", 0)
    put(im, 0, "back"); put(im, 8, "info")
    ui.container(im, d, (6, 35, 169, 70), "ardoise")
    big(im, "pass", 10, 37, 32)
    write(im, "Saison 3", 48, 0, WHITE, SH, left=48) if False else write(im, "Saison 3", 0, 40, WHITE, SH, left=48)
    write(im, "Fin dans 12 j", 0, 49, rgb("#B4B4B4"), SH, left=48)
    fill_bar(d, im, 48, 57, 116, 0.42, GOLD, "13/50")
    for k in range(7):
        c = 37 + k
        slot(d, c); put(im, c, REW[k])
        x, y = gen_ui.game_objet(c)
        write(im, str(5 * (k + 1)), x + 8, y - 7, INK, None)
    green_box(im, d, box_of([46, 52]))
    for k in range(7):
        gslot(d, 46 + k); put(im, 46 + k, "chest")
    put(im, 36, "prev"); put(im, 44, "next")


def p4(im, d):
    """Ladder: one tier per row, number, free reward, progress segment, two premium rewards in green."""
    title(im, "Pass", 0)
    put(im, 0, "back"); put(im, 8, "pass")
    for r in range(4):
        row = 1 + r
        base = row * 9
        reached = r < 2
        bx, by = 8, 18 + 18 * row + 2
        d.rectangle((bx - 1, by - 1, bx + 14, by + 12), fill=BLACK)
        d.rectangle((bx, by, bx + 13, by + 11), fill=GOLD if reached else rgb("#5A5A5A"))
        write(im, str(12 + r), bx + 7, by + 3, WHITE, SH)
        slot(d, base + 1); put(im, base + 1, REW[r])
        ui.bar(d, (44, 18 + 18 * row + 3, 113, 18 + 18 * row + 13), 1.0 if reached else (0.4 if r == 2 else 0), GREEN)
        green_box(im, d, box_of([base + 7, base + 8], 1))
        gslot(d, base + 7); gslot(d, base + 8)
        put(im, base + 7, "chest_done" if reached else "chest"); put(im, base + 8, "diamond")
    put(im, 49, "prev"); put(im, 50, "next")


def p5(im, d):
    """Missions below: categories as tabs, missions list, the tracks on top (the whole pass in one window)."""
    title(im, "Pass", 0)
    put(im, 0, "back"); put(im, 8, "pass")
    for k in range(7):
        slot(d, 10 + k); put(im, 10 + k, REW[k])
    green_box(im, d, box_of([19, 25]))
    for k in range(7):
        gslot(d, 19 + k); put(im, 19 + k, "chest")
    put(im, 9, "prev"); put(im, 17, "next")
    fill_bar(d, im, 6, 18 + 18 * 3 + 3, 163, 0.42, GREEN, "Palier 13/50")
    write(im, "Missions", 0, 18 + 18 * 4 + 6, INK, None, left=8)
    for k in range(5):
        c = 47 + k
        slot(d, c, "lit" if k == 0 else "vanilla"); put(im, c, ["clock", "book", "book", "book", "book"][k])
    for k in range(7):
        slot(d, 55 + k); put(im, 55 + k, REW[(k + 2) % 7])


# ---------------------------------------------------------------------------------------------------- ville / pays

def card(im, d, x0, y0, x1, y1, icon, name, l1, l2, style="ardoise"):
    ui.container(im, d, (x0, y0, x1, y1), style)
    big(im, icon, (x0 + x1) // 2 - 16, y0 + 6, 32)
    write(im, name, (x0 + x1) // 2 + 1, y0 + 42, WHITE, SH)
    write(im, l1, (x0 + x1) // 2 + 1, y0 + 51, rgb("#C8C8C8"), SH)
    write(im, l2, (x0 + x1) // 2 + 1, y0 + 59, rgb("#C8C8C8"), SH)


def shortcuts(im, d, row):
    for k, n in enumerate(["annuaire", "warp", "agence", "echoppe", "pays"]):
        put(im, row * 9 + 2 + k, n)


def v1(im, d):
    """Two cards: big icon, name, two facts."""
    title(im, "Territoires", 0)
    put(im, 0, "back")
    card(im, d, 6, 35, 85, 105, "ville", "Lumiere", "Niv. 3 · Cite", "12 habitants")
    card(im, d, 90, 35, 169, 105, "pays", "Aurora", "4 villes", "Chef Dofault")
    shortcuts(im, d, 5)


def v2(im, d):
    """Shields: each panel is a coat of arms in the town / country colour, name on a ribbon."""
    title(im, "Territoires", 0)
    put(im, 0, "back")
    for (cx, colour, icon, name, line) in ((46, rgb("#3D7FB8"), "ville", "Lumiere", "Niv. 3"), (130, rgb("#C0392B"), "pays", "Aurora", "4 villes")):
        pts = [(cx - 28, 38), (cx + 28, 38), (cx + 28, 78), (cx, 100), (cx - 28, 78)]
        d.polygon([(x + (1 if x > cx else -1 if x < cx else 0), y + (1 if y > 70 else -1)) for x, y in pts], fill=BLACK)
        d.polygon(pts, fill=colour)
        d.line((cx - 27, 39, cx + 27, 39), fill=tone(colour, 0.4))
        big(im, icon, cx - 16, 44, 32)
        d.rectangle((cx - 34, 80, cx + 34, 90), fill=BLACK)
        d.rectangle((cx - 33, 81, cx + 33, 89), fill=GOLD)
        write(im, name, cx + 1, 83, WHITE, SH)
        write(im, line, cx + 1, 104, INK, None)
    shortcuts(im, d, 5)


def v3(im, d):
    """Map: the town as a small chunk map (claimed chunks lit), the country as its towns on a line."""
    title(im, "Territoires", 0)
    put(im, 0, "back")
    ui.container(im, d, (6, 35, 85, 105), "ardoise")
    for gx in range(8):
        for gy in range(5):
            x, y = 12 + gx * 9, 40 + gy * 9
            claimed = (gx - 3.5) ** 2 + (gy - 2) ** 2 < 6
            d.rectangle((x, y, x + 7, y + 7), fill=GREEN_L if claimed else rgb("#45474B"))
    write(im, "18 chunks", 46, 92, WHITE, SH)
    ui.container(im, d, (90, 35, 169, 105), "ardoise")
    for k, nx in enumerate((104, 129, 154)):
        d.rectangle((nx - 9, 52, nx + 9, 70), fill=BLACK)
        d.rectangle((nx - 8, 53, nx + 8, 69), fill=rgb("#3D7FB8") if k else GOLD)
        big(im, "capitale" if k == 0 else "ville", nx - 8, 53, 16)
    d.line((113, 61, 120, 61), fill=WHITE); d.line((138, 61, 145, 61), fill=WHITE)
    write(im, "3 villes", 130, 92, WHITE, SH)
    shortcuts(im, d, 5)


def v4(im, d):
    """Bands: town band then country band across the window, facts on the right."""
    title(im, "Territoires", 0)
    put(im, 0, "back")
    for row, icon, name, a, b in ((1, "ville", "Lumiere", "Niveau 3 · Cite", "12 habitants"),
                                         (3, "pays", "Aurora", "Nation · 4 villes", "Capitale Lumiere")):
        y0 = 18 + 18 * row - 2
        ui.container(im, d, (6, y0, 169, y0 + 34), "ardoise")
        big(im, icon, 10, y0 + 2, 32)
        write(im, name, 0, y0 + 6, WHITE, SH, left=48)
        write(im, a, 0, y0 + 16, rgb("#C8C8C8"), SH, left=48)
        write(im, b, 0, y0 + 24, rgb("#C8C8C8"), SH, left=48)
    shortcuts(im, d, 5)


# ---------------------------------------------------------------------------------------------------- round 2

def season(im, d, y0=35, h=35, icon=True, ring=False):
    """Season card of p3: icon, name, time left, golden bar."""
    ui.container(im, d, (6, y0, 169, y0 + h), "ardoise")
    if ring:
        cx, cy = 26, y0 + h // 2
        d.ellipse((cx - 15, cy - 15, cx + 15, cy + 15), fill=BLACK)
        d.pieslice((cx - 14, cy - 14, cx + 14, cy + 14), -90, -90 + int(360 * 0.42), fill=GOLD)
        d.ellipse((cx - 9, cy - 9, cx + 9, cy + 9), fill=rgb("#585B5F"))
        write(im, "13", cx + 1, cy - 2, WHITE, SH)
    elif icon:
        big(im, "pass", 10, y0 + 2, 32)
    write(im, "Saison 3", 0, y0 + 5, WHITE, SH, left=48)
    write(im, "Fin dans 12 j", 0, y0 + 14, rgb("#B4B4B4"), SH, left=48)
    if not ring:
        fill_bar(d, im, 48, y0 + 22, 116, 0.42, GOLD, "13/50")


def milestones(im, d, row, premium_row, numbers=True):
    for k in range(7):
        c = row * 9 + 1 + k
        slot(d, c); put(im, c, REW[k])
        if numbers:
            x, y = gen_ui.game_objet(c)
            write(im, str(5 * (k + 1)), x + 8, y - 7, INK, None)
    green_box(im, d, box_of([premium_row * 9 + 1, premium_row * 9 + 7]))
    for k in range(7):
        gslot(d, premium_row * 9 + 1 + k); put(im, premium_row * 9 + 1 + k, "chest_done" if k < 2 else "chest")
    put(im, row * 9, "prev"); put(im, row * 9 + 8, "next")


def missions_row(im, d, first_inv_row, tabs=True):
    base = 54 + 9 * first_inv_row
    if tabs:
        for k in range(5):
            slot(d, base + 2 + k, "lit" if k == 0 else "vanilla"); put(im, base + 2 + k, ["clock", "book", "book", "book", "book"][k])
        base += 9
    for k in range(7):
        slot(d, base + 1 + k); put(im, base + 1 + k, REW[(k + 3) % 7])


def q1(im, d):
    """Season card, milestones, premium, then missions (tabs + list) in the inventory area."""
    title(im, "Pass", 0); put(im, 0, "back"); put(im, 8, "info")
    season(im, d); milestones(im, d, 4, 5)
    write(im, "Missions", 0, 132, INK, None, left=8)
    missions_row(im, d, 0)


def q2(im, d):
    """Missions on the side: the season card then a column of 4 missions right of the tracks."""
    title(im, "Pass", 0); put(im, 0, "back"); put(im, 8, "info")
    season(im, d)
    for k in range(5):
        c = 37 + k
        slot(d, c); put(im, c, REW[k])
    green_box(im, d, box_of([46, 50]))
    for k in range(5):
        gslot(d, 46 + k); put(im, 46 + k, "chest")
    put(im, 36, "prev"); put(im, 45, "next")
    ui.container(im, d, box_of([43, 53]), "ardoise")
    for c in (43, 44, 52, 53):
        slot(d, c, "dark"); put(im, c, "book")
    write(im, "Missions", 148, 27 + 18 * 1, INK, None) if False else None


def q3(im, d):
    """Ring: the season progress as a golden ring on the card, the tracks, missions as a daily row with a timer."""
    title(im, "Pass", 0); put(im, 0, "back"); put(im, 8, "info")
    season(im, d, ring=True)
    fill_bar(d, im, 48, 57, 116, 0.6, GREEN, "Pts 620/1000")
    milestones(im, d, 4, 5)
    ui.container(im, d, box_of([55, 61]), "ardoise")
    for k in range(7):
        slot(d, 55 + k, "dark"); put(im, 55 + k, ["clock", "book", "book", "book", "book", "book", "book"][k])
    write(im, "Du jour · 6 h", 88, 132, INK, None)


def q4(im, d):
    """Week tabs: the 5 mission categories as tabs right under the card, missions, then the tracks at the bottom."""
    title(im, "Pass", 0); put(im, 0, "back"); put(im, 8, "info")
    season(im, d)
    for k in range(5):
        c = 38 + k
        slot(d, c, "lit" if k == 0 else "vanilla"); put(im, c, ["clock", "book", "book", "book", "book"][k])
    for k in range(7):
        slot(d, 46 + k); put(im, 46 + k, REW[(k + 3) % 7])
    write(im, "Paliers", 0, 132, INK, None, left=8)
    for k in range(7):
        slot(d, 55 + k); put(im, 55 + k, REW[k])
    green_box(im, d, box_of([64, 70]))
    for k in range(7):
        gslot(d, 64 + k); put(im, 64 + k, "chest")
    put(im, 54, "prev"); put(im, 62, "next")


def q5(im, d):
    """Free and premium side by side as two lanes going down, the season card on top, missions at the bottom."""
    title(im, "Pass", 0); put(im, 0, "back"); put(im, 8, "info")
    season(im, d)
    for r, row in enumerate((3, 4, 5)):
        bx, by = 72, 18 + 18 * row + 2
        d.rectangle((bx - 1, by - 1, bx + 32, by + 12), fill=BLACK)
        d.rectangle((bx, by, bx + 31, by + 11), fill=GOLD if r < 1 else rgb("#5A5A5A"))
        write(im, "Pal. %d" % (13 + r), bx + 16, by + 3, WHITE, SH)
        slot(d, row * 9 + 2); put(im, row * 9 + 2, REW[r])
    green_box(im, d, box_of([33, 51]))
    for row in (3, 4, 5):
        gslot(d, row * 9 + 6); put(im, row * 9 + 6, "chest")
    write(im, "Gratuit", 0, 61, INK, None, left=24) if False else None
    put(im, 27, "prev"); put(im, 35, "next")
    missions_row(im, d, 0, tabs=False)


def shield(d, cx, cy, w, colour, kind="shield"):
    if kind == "shield":
        pts = [(cx - w, cy - w), (cx + w, cy - w), (cx + w, cy + w // 2), (cx, cy + w + w // 2), (cx - w, cy + w // 2)]
    elif kind == "banner":
        pts = [(cx - w, cy - w), (cx + w, cy - w), (cx + w, cy + w + 6), (cx, cy + w - 2), (cx - w, cy + w + 6)]
    else:   # pennant
        pts = [(cx - w, cy - w), (cx + w + 10, cy), (cx - w, cy + w)]
    d.polygon([(x + (1 if x > cx else -1 if x < cx else 0), y + (1 if y > cy else -1)) for x, y in pts], fill=BLACK)
    d.polygon(pts, fill=colour)
    return pts


TOWN, LAND = rgb("#3D7FB8"), rgb("#C0392B")


def w1(im, d):
    """The shields alone, side by side."""
    title(im, "Territoires", 0); put(im, 0, "back")
    for cx, col, icon in ((46, TOWN, "ville"), (130, LAND, "pays")):
        shield(d, cx, 58, 26, col); d.line((cx - 25, 33, cx + 25, 33), fill=tone(col, 0.4))
        big(im, icon, cx - 16, 44, 32)
    shortcuts(im, d, 5)


def w2(im, d):
    """Gilded: gold border, the country crowned."""
    title(im, "Territoires", 0); put(im, 0, "back")
    for cx, col, icon, crown in ((46, TOWN, "ville", False), (130, LAND, "pays", True)):
        shield(d, cx, 60, 28, GOLD); shield(d, cx, 60, 24, col)
        big(im, icon, cx - 16, 46, 32)
        if crown:
            d.polygon([(cx - 10, 34), (cx - 10, 26), (cx - 5, 30), (cx, 23), (cx + 5, 30), (cx + 10, 26), (cx + 10, 34)], fill=GOLD)
    shortcuts(im, d, 5)


def w3(im, d):
    """Hanging banners: a pole across the window, both banners hang from it."""
    title(im, "Territoires", 0); put(im, 0, "back")
    d.rectangle((14, 36, 162, 39), fill=rgb("#6B4A2B")); d.line((14, 36, 162, 36), fill=rgb("#8D6E4A"))
    for cx, col, icon in ((54, TOWN, "ville"), (122, LAND, "pays")):
        d.line((cx - 20, 40, cx - 20, 43), fill=BLACK); d.line((cx + 20, 40, cx + 20, 43), fill=BLACK)
        shield(d, cx, 66, 22, col, "banner")
        d.line((cx - 21, 45, cx + 21, 45), fill=tone(col, 0.4))
        big(im, icon, cx - 16, 52, 32)
    shortcuts(im, d, 5)


def w4(im, d):
    """Town inside country: the country's big shield holds the town's small one, linked."""
    title(im, "Territoires", 0); put(im, 0, "back")
    shield(d, 112, 54, 28, LAND)
    big(im, "pays", 96, 36, 32)
    shield(d, 54, 64, 22, TOWN)
    big(im, "ville", 38, 52, 32)
    d.line((76, 66, 88, 66), fill=GOLD); d.line((76, 67, 88, 67), fill=GOLD)
    shortcuts(im, d, 5)


def w5(im, d):
    """Pennants on poles: one flag per territory, slanted, like a camp."""
    title(im, "Territoires", 0); put(im, 0, "back")
    for x, col, icon in ((40, TOWN, "ville"), (108, LAND, "pays")):
        d.rectangle((x - 1, 32, x + 1, 104), fill=rgb("#6B4A2B"))
        shield(d, x + 22, 52, 20, col, "pennant")
        big(im, icon, x + 6, 44, 16)
    shortcuts(im, d, 5)


def sheet(variants, path, first_last):
    tiles = []
    for (name, fn), (f, l) in zip(variants, first_last):
        im = gen_ui.bg_window(GENERIC, f, l)
        fn(im, ImageDraw.Draw(im, "RGBA"))
        tiles.append(im.resize((528, 663), Image.NEAREST))
    cols = len(tiles)
    out = Image.new("RGBA", (cols * 548 + 20, 683), (30, 30, 34, 255))
    for i, t in enumerate(tiles):
        out.alpha_composite(t, (20 + i * 548, 20))
    out.save(path)


if __name__ == "__main__":
    PLUG, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    sheet([("p1", p1), ("p2", p2), ("p3", p3), ("p4", p4), ("p5", p5)], os.path.join(out, "pass.png"),
          [(0, 5), (0, 5), (0, 5), (0, 5), (0, 6)])
    sheet([("v1", v1), ("v2", v2), ("v3", v3), ("v4", v4)], os.path.join(out, "villes.png"),
          [(0, 5), (0, 5), (0, 5), (0, 5)])
    sheet([("q1", q1), ("q2", q2), ("q3", q3), ("q4", q4), ("q5", q5)], os.path.join(out, "pass2.png"),
          [(0, 7), (0, 5), (0, 6), (0, 8), (0, 7)])
    sheet([("w1", w1), ("w2", w2), ("w3", w3), ("w4", w4), ("w5", w5)], os.path.join(out, "villes2.png"),
          [(0, 5)] * 5)
    print("ok")

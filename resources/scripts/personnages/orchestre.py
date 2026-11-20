#!/usr/bin/env python3
"""Orchestre : un personnage par instrument des blocs musicaux, chacun avec ses animations idle et playing.

Usage : orchestre.py [id ...]   (défaut : tous) ; écrit assets/personnages/orchestre/<id>/
"""
import os
import sys

from corps import SIDES, Corps, Inst, rows_fill  # noqa: F401
from voxel import ROOT, Model, T, bump, key, pal, ramp, rgb, stamp, sw  # noqa: F401

OUT = os.path.join(ROOT, "assets", "personnages", "orchestre")
BUILD = {}


def perso(f):
    BUILD[f.__name__] = f
    return f


def idle(m):
    m.anim("idle", 2.0).set("root", "position", lambda p: (0, 0, 0))


def add(a, b, k=1.0):
    return [x + k * y for x, y in zip(a, b)]


# Gestes joués une fois par note (FluxMusic les lance un peu avant la note : le contact tombe à CONTACT secondes).
CONTACT = 0.15


def geste(m, name, length=0.4):
    """Animation jouée une fois ; renvoie aussi la phase du contact."""
    return m.anim(name, length, loop="once"), CONTACT / length


def tenue(m, name, side, target, stretch=(0.85, 1.6)):
    """Pose tenue d'un bras (main gauche sur le manche), en boucle immobile par-dessus la posture de jeu."""
    a = m.anim(name, 1.0)
    a.arm(side, lambda p: target, stretch=stretch)
    return a


WOOD = pal("#6E4428", "#99643A", "#BC8650", "#D6A66E")
DARKWOOD = pal("#3A2620", "#553729", "#734C36")
STRING = rgb("#E3D6B4")


# =============================================================================================== guitariste
@perso
def guitariste():
    m = Model("guitariste")
    leather, sage, ivory = pal("#49342D", "#79513A", "#9B704E", "#B17D4C"), ramp("#74816A"), pal("#B5A78E", "#DCCFB5", "#EEE4CE")
    boots, hair = pal("#3A2924", "#55392D", "#74513A"), pal("#5A2E22", "#84452C", "#A9633A")
    G = Corps(m, "hale", legs=(3, 6, 3, 1, sage), boots=(3, boots, 1), torso=(7, 8, 4, leather), head=(8, 8, 7),
              arm=(3, 3, leather), belt=(1, pal("#3A2924", "#49342D", "#6B4A3A"), pal("#8A5E38", "#B17D4C", "#D19E68")),
              head_rot=(0, 0, 5))
    G.jambes()
    G.avance("droit", -1)
    G.torse(front=["mliiilm", "mliiilm", "mmiiimd", "mlijimd", "mmiiimd", "mljiimd", "mmiiidd", "ddjjjdd"],
            extra={"i": ivory[1], "j": ivory[0]}, buckle_x=4)

    def scarf(f, g):
        if f == "north":
            stamp(g, ["lmmmml", " mdld "], key(sage))
    m.box("corps", "foulard", (-3, G.top - 2, G.front - 1), (3, G.top, G.td / 2), sage, scarf, top=1, bot=0, noise=0)
    m.box("corps", "sacoche", (G.tw / 2, G.hips - 2, -1.5), (G.tw / 2 + 2, G.hips + 1, 1.5), leather,
          lambda f, g: rows_fill(g, leather[2], [0]) if f not in ("up", "down") else None, noise=0)
    G.tete(eye="#4A5A3A", hair=hair, brows=True)
    G.cheveux(hair, front=1, side=5, fringe="ml mdm lm", open_cols=2, above=0)
    hat = pal("#4A3A2C", "#6B543C", "#8A7050")
    G.bord(hat, G.head_top, grow=1.5)
    G.calotte(hat, G.head_top + 1, 2, shrink=1, paint=lambda f, g: rows_fill(g, sage[1], [len(g) - 1])
              if f not in ("up", "down") else None)

    I = Inst(m, "guitare", (0, G.hips + 2.5, G.front - 1), (0, 0, -20))

    def body(f, g):
        if f == "north":
            stamp(g, ["ccccccc", "cllllmc", "cbssHHs", "cbssHHs", "cmlllmc", "ccccccc"],
                  dict(key(WOOD), c=rgb("#E6C894"), H=rgb("#2E1D17"), b=DARKWOOD[0], s=STRING))
        elif f != "south":
            rows_fill(g, WOOD[1], range(len(g)))
            rows_fill(g, WOOD[0], [len(g) - 1])
    I.box("caisse", (-3, -3, -1), (4, 3, 1), WOOD, body, noise=0.05)
    I.box("manche", (-8, -0.5, -1), (-3, 0.5, 0), DARKWOOD,
          lambda f, g: stamp(g, ["sdsds"], dict(key(DARKWOOD), s=STRING)) if f == "north" else None, noise=0)
    I.box("tete_guitare", (-10, -1, -1), (-8, 1, 0), WOOD,
          lambda f, g: stamp(g, ["mh", "hm"], key(WOOD)) if f == "north" else None, noise=0)
    G.bras(I.w(-6, 0, -1), I.w(3, -2.2, -1.6))

    idle(m)
    a = m.anim("playing", 1.8)
    a.set("corps", "rotation", lambda p: (0, 0, 1.5 * sw(p)))
    a.set("tete", "rotation", lambda p: (-4 - 1.5 * sw(p, 1, 0.2), 0, 0))
    a.arm("droit", lambda p: a.at("guitare", I.w(3, -1.4 + 1.5 * sw(p, 3), -1.6), p))
    a.arm("gauche", lambda p: a.at("guitare", I.w(-6 + 1.2 * bump(p, 0.3, 0.4, 0.7), 0, -1), p))
    g, c = geste(m, "hit_0")
    r = I.w(3, -1.4, -1.6)
    g.arm("droit", lambda p: path(p, [(0, r), (c * 0.45, I.w(3, -0.3, -1.7)), (c * 1.2, I.w(3, -2.9, -1.6)), (1, r)]))
    for k, x in enumerate((-7, -5.5, -4)):       # grave près de la tête du manche, aigu près de la caisse
        tenue(m, f"hold_{k}", "gauche", I.w(x, 0, -1))
    return m


# =============================================================================================== banjoïste
@perso
def banjoiste():
    m = Model("banjoiste")
    beige, olive, brown = pal("#B3A07E", "#DCC9A3", "#EDDFC0"), ramp("#69734D"), pal("#4A3122", "#694631", "#86603F")
    red, ocre, hair = rgb("#965D4B"), pal("#8C6638", "#B58A52", "#D3AB72"), pal("#7A3F22", "#A85A2C", "#C97B3F")
    G = Corps(m, "clair", legs=(3, 4, 4, 1, brown), boots=(3, pal("#33241D", "#4A3122", "#694631"), 1),
              torso=(9, 8, 5, beige), head=(9, 8, 8), arm=(3, 4, beige), splay=7)
    G.jambes()
    G.torse(front=["vrvmmmvrv", "vrvmmmvrv", "vrvvmvvrv", "vrvvbvvrv", "VrvvmvvrV", "VrvvbvvrV", "VVvvmvvVV", "dddmmmddd"],
            back=["vrvvvvvrv", "vvrvvvrvv", "vvvrvrvvv", "vvvvrvvvv", "VvvrvrvvV", "VvrvvvrvV", "VVvvvvvVV", "ddddddddd"],
            extra={"v": olive[1], "V": olive[0], "r": red, "b": ocre[2]})
    G.tete(eye="#5A6A8A", hair=hair, stache=hair, cheek="#D9806E", mouth=None)
    G.cheveux(hair, front=1, side=4, fringe="m       l", open_cols=3, above=0)
    G.bord(ocre, G.head_top, grow=1, rot=(0, 0, -5))
    G.calotte(ocre, G.head_top + 1, 3, shrink=0.5, rot=(0, 0, -5),
              paint=lambda f, g: (rows_fill(g, red, [len(g) - 2]), stamp(g, ["..l", ".l."], key(ocre), 2, 0))
              if f not in ("up", "down") else None)

    I = Inst(m, "banjo", (0.5, G.hips + 3, G.front - 1.5), (0, 0, -15))
    ivory, rim = pal("#C9BC9F", "#E8DDC2", "#F4ECD8"), WOOD

    def pot(f, g):
        if f == "north":
            stamp(g, ["wlliiiw", "wliiiiw", "wbsssss", "wiiiiiw", "wiiiidw"],
                  dict(key(ivory), w=rim[1], i=ivory[1], s=rgb("#A89C84"), b=DARKWOOD[0]))
        elif f != "south":
            rows_fill(g, rim[1], range(len(g)))
    I.box("fut", (-3.5, -2.5, -1), (3.5, 2.5, 1), rim, pot, noise=0)
    I.box("fut_haut", (-2.5, -3.5, -0.5), (2.5, 3.5, 0.5), rim,
          lambda f, g: rows_fill(g, rim[2], [0]) if f == "north" else None, noise=0)
    I.box("manche", (-8, -0.5, -1), (-3.5, 0.5, 0), DARKWOOD,
          lambda f, g: stamp(g, ["dsdsd"], dict(key(DARKWOOD), s=STRING)) if f == "north" else None, noise=0)
    I.box("tete_banjo", (-10, -1, -1), (-8, 1, 0), DARKWOOD, noise=0)
    G.bras(I.w(-6.5, 0, -1), I.w(2.5, -2, -1.6), cuff=beige[0])

    idle(m)
    a = m.anim("playing", 1.5)
    a.set("corps", "rotation", lambda p: (0, 0, 2.2 * sw(p, 2)))
    a.set("tete", "rotation", lambda p: (-2, 0, -2.5 * sw(p, 2, 0.1)))
    a.arm("droit", lambda p: a.at("banjo", I.w(2.5 + 0.3 * sw(p, 3), -1.6 + 0.6 * sw(p, 6), -1.6), p))
    a.arm("gauche", lambda p: a.at("banjo", I.w(-6.5 + 1.2 * bump(p, 0.2, 0.3, 0.5) - 0.8 * bump(p, 0.6, 0.7, 0.9), 0, -1), p))
    g, c = geste(m, "hit_0", 0.3)
    r = I.w(2.5, -1.6, -1.6)
    g.arm("droit", lambda p: path(p, [(0, r), (c, I.w(2.7, -2.4, -1.7)), (1, r)]))
    for k, x in enumerate((-7.5, -6, -4.5)):
        tenue(m, f"hold_{k}", "gauche", I.w(x, 0, -1))
    return m


# =============================================================================================== percussions
def frappes(G, a, side, tip, t0, direction, gap, hits, width=0.09, stretch=None):
    """Le bras part de t0, frappe (contact exact) aux phases hits, remonte. Renvoie l'amplitude."""
    A = G.contact(side, tip, t0, direction, gap)

    def k(p):
        return sum(bump(p, h - width, h, h + width * 1.6) for h in hits)
    a.arm(side, lambda p: add(t0, direction, A * k(p)), stretch=stretch)
    return A


@perso
def caisse_claire():
    m = Model("caisse_claire")
    blue, brown, ivory = ramp("#586D78"), pal("#3F2D25", "#5D4438", "#7C5E4C"), pal("#B5AA93", "#DDD1B9", "#EEE6D2")
    red, brass, hair = rgb("#814E45"), pal("#8A6434", "#B78B50", "#D8B274"), pal("#2B2422", "#3F3531", "#5A4C45")
    G = Corps(m, "pale", legs=(3, 8, 3, 1, brown), boots=(3, pal("#2E211B", "#46332A", "#5D4438"), 1),
              torso=(7, 8, 4, blue), head=(7, 7, 7), arm=(3, 3, blue), belt=(1, brown, brass), splay=2)
    G.jambes()
    G.torse(front=["iRRRRRi", "mimmmim", "mmibimm", "mmmimmm", "mmibimm", "mimmmim", "immmmmi", "ddmmmdd"],
            back=["immmmmi", "mimmmim", "mmimimm", "mmmimmm", "mmimimm", "mimmmim", "immmmmi", "ddddddd"],
            extra={"i": ivory[1], "R": red, "b": brass[2]})
    G.tete(eye="#3F3531", hair=hair)
    G.cheveux(hair, front=1, side=3, fringe="dm     md", open_cols=3, above=0)
    G.calotte(blue, G.head_top, 3, shrink=0.5, rot=(0, 0, 6), dz=0,
              paint=lambda f, g: (rows_fill(g, red, [len(g) - 1]), g[len(g) - 1].__setitem__(len(g[0]) // 2, brass[2]) if f == "north" else None)
              if f not in ("up", "down") else None)
    m.box("tete", "visiere", (-2.5, G.head_top, -G.hd / 2 - 2), (2.5, G.head_top + 1, -G.hd / 2), brown, noise=0)

    I = Inst(m, "tambour", (0, G.hips + 1.5, G.front - 4.5), (8, 0, 0))
    shell, skin = pal("#4E3428", "#6E4A38", "#8C6248"), pal("#C4B594", "#E6D9BC", "#F3EAD4")

    def drum(f, g):
        w, h = len(g[0]), len(g)
        if f == "up":
            for y in range(h):
                for x in range(w):
                    edge = x in (0, w - 1) or y in (0, h - 1)
                    g[y][x] = ivory[0 if (x + y) % 5 == 0 else 1] if edge else skin[1 if (x in (1, w - 2) or y in (1, h - 2)) else 2]
            g[0][0] = g[0][-1] = g[-1][0] = g[-1][-1] = shell[0]
        elif f != "down":
            g[0] = [ivory[1]] * w
            g[-1] = [ivory[0]] * w
            for x in range(1, w, 3):
                g[1][x] = brass[1]
                g[2][x] = brass[0]
    I.box("fut", (-4, -2, -3.5), (4, 2, 3.5), shell, drum, noise=0.04)
    top = I.w(0, 2, -0.5)
    hands = [(-3.2, top[1] + 4.2, top[2] + 3.2), (3.2, top[1] + 4.2, top[2] + 3.2)]
    G.bras(*hands)
    stick = pal("#A98958", "#CDB07C", "#E4CC9C")
    tips = {s: G.baton(s, (-sx * 0.55, -0.42, -0.6), 4.5, stick) for s, sx in SIDES}

    def gap(q):   # hauteur au-dessus de la peau (plan incliné du dessus du tambour)
        n = (0, I.R[1][1], I.R[2][1])
        return sum((q[i] - top[i]) * n[i] for i in range(3)) - 0.5
    idle(m)
    a = m.anim("playing", 1.0)
    amp = [frappes(G, a, s, tips[s], t0, (0, -1, 0), gap, hits, width=0.07)
           for (s, sx), hits, t0 in zip(SIDES, ((0.35, 0.6, 0.85), (0.1, 0.72)), hands)]
    a.set("tete", "rotation", lambda p: (-3, 0, 0))
    for (s, sx), t0, A, nm in zip(SIDES, hands, amp, ("hit_l", "hit_r")):
        g, c = geste(m, nm, 0.3)
        g.arm(s, lambda p, t0=t0, A=A, c=c: path(p, [(0, t0), (c, add(t0, (0, -1, 0), A)), (1, t0)]), stretch=None)
    m.check = lambda: ("amplitudes", [round(x, 2) for x in amp], "écart frappe / repos",
                       [round(gap(G.bout(s, tips[s], a, h)), 2) for s, h in (("droit", 0.1), ("gauche", 0.35), ("droit", 0.4))])
    return m


@perso
def grosse_caisse():
    m = Model("grosse_caisse")
    red, brown, beige, green = ramp("#985B4D"), pal("#432E26", "#604337", "#7E5C4B"), pal("#B0A082", "#D9C7A8", "#EBDDC3"), ramp("#42594A")
    hair = pal("#3A2A20", "#56402F", "#745842")
    G = Corps(m, "hale", legs=(4, 5, 4, 1, green), boots=(3, brown, 1), torso=(10, 8, 5, red), head=(8, 8, 7),
              arm=(3, 4, red), belt=(2, brown, pal("#8A6434", "#B78B50", "#D8B274")), splay=6)
    G.jambes()
    G.torse(front=["lssmmbmmmm", "mmssmmmmmm", "mmmssbmmmd", "mmmmssmmmd", "mmmmmssmmd", "mmmmmbssmd", "dmmmmmmssd", "ddmmmmmdss"],
            back=["mmmmmmmssm", "mmmmmmssmm", "mmmmmssmmm", "mmmmssmmmm", "mmmssmmmmm", "mmssmmmmmd", "dssmmmmmmd", "ssdddddddd"],
            extra={"s": beige[1], "b": beige[2]})
    G.tete(eye="#4A3A2A", hair=hair, stache=hair, mouth=None)
    G.cheveux(hair, front=1, side=4, fringe="d       d", open_cols=3, above=0)
    G.bord(red, G.head_top, h=2, grow=0.5,
           paint=lambda f, g: rows_fill(g, beige[1], [1]) if f not in ("up", "down") else None)
    m.box("tete", "pompon", (-1, G.head_top + 2, -1), (1, G.head_top + 3, 1), beige, noise=0)

    c = (0, G.hips + 4.5, G.front - 6)
    I = Inst(m, "tambour", c)
    shell = pal("#4A3023", "#6B4632", "#8A5F44")
    head = pal("#B9AA8A", "#DCCDAC", "#EFE4C9")

    def drum(f, g):
        w, h = len(g[0]), len(g)
        if f in ("east", "west"):
            for y in range(h):
                for x in range(w):
                    e = min(x, y, w - 1 - x, h - 1 - y)
                    g[y][x] = beige[0] if e == 0 else head[1 if e < 2 or (x + 2 * y) % 7 == 0 else 2]
        else:
            for y in range(h):
                zig = 1 + (1, 2, 3, 2)[y % 4] * (w - 3) // 4   # laçage en zigzag entre les deux cerclages
                if 0 < zig < w - 1:
                    g[y][zig] = beige[0]
                for x in (0, w - 1):
                    g[y][x] = beige[1] if f in ("north", "up") else beige[0]
    I.box("fut", (-3, -5.5, -3.5), (3, 5.5, 3.5), shell, drum, noise=0.03)
    I.box("fut_large", (-2.5, -3.5, -5.5), (2.5, 3.5, 5.5), shell, drum, noise=0.03)
    hands = [(-8, c[1] + 1, c[2] + 1), (8, c[1] + 1, c[2] + 1)]
    G.bras(*hands, cuff=beige[1])
    stick = pal("#8C6B48", "#B08A5E", "#CCA878")
    felt = pal("#B5A78E", "#E0D3B8", "#F2E8D2")
    tips = {s: G.baton(s, (-sx, 0, 0), 2.5, stick, head=(2, felt), name="mailloche") for s, sx in SIDES}
    idle(m)
    a = m.anim("playing", 1.0)
    a.set("corps", "rotation", lambda p: (0, 0, 1.2 * (bump(p, 0.05, 0.15, 0.3) - bump(p, 0.55, 0.65, 0.8))))
    amps = {}
    for (s, sx), hit, t0 in zip(SIDES, (0.65, 0.15), hands):
        # la mailloche recule d'abord (élan), puis frappe la peau
        A = G.contact(s, tips[s], t0, (-sx, 0, 0), lambda q, sx=sx: sx * q[0] - 3)
        amps[s] = A
        a.arm(s, lambda p, t0=t0, sx=sx, A=A, hit=hit: add(t0, (-sx, 0, 0), A * bump(p, hit - 0.07, hit, hit + 0.12)
                                                              - 1.2 * bump(p, hit - 0.3, hit - 0.12, hit - 0.04)), stretch=None)
    for (s, sx), t0, nm in zip(SIDES, hands, ("hit_l", "hit_r")):
        g, c = geste(m, nm, 0.45)
        g.arm(s, lambda p, t0=t0, sx=sx, A=amps[s], c=c: path(p, [(0, t0), (c * 0.4, add(t0, (sx, 0, 0), 0.9)),
                                                                   (c, add(t0, (-sx, 0, 0), A)), (1, t0)]), stretch=None)
    m.check = lambda: ("amplitudes", amps, "écart à la frappe",
                       {s: round(sx * G.bout(s, tips[s], a, h)[0] - 3, 2) for (s, sx), h in zip(SIDES, (0.65, 0.15))})
    return m


def path(p, keys):
    """Trajet lissé entre des points clés [(phase, point), ...] (premier à 0, dernier à 1)."""
    import math
    p = p % 1
    for (t0, a), (t1, b) in zip(keys, keys[1:]):
        if t0 <= p <= t1:
            f = 0.5 - 0.5 * math.cos(math.pi * (p - t0) / (t1 - t0)) if t1 > t0 else 0
            return [x + (y - x) * f for x, y in zip(a, b)]
    return list(keys[-1][1])


def maillets(G, I, hand, strike, top_y, stick, head, rest_gap=1.1, hs=1.5, **bras):
    """Deux maillets au-dessus d'un plateau I : mains en I.w(±hand), frappe vers I.w(±strike).
    Renvoie (mains monde, bouts, fonction d'écart à la surface)."""
    from voxel import dist
    hands = [I.w(-hand[0], hand[1], hand[2]), I.w(hand[0], hand[1], hand[2])]
    G.bras(*hands, **bras)
    tips = {}
    for (s, sx), h in zip(SIDES, hands):
        t = I.w(sx * strike[0], strike[1], strike[2])
        d = [t[i] - h[i] for i in range(3)]
        tips[s] = G.baton(s, d, dist(t, h) - rest_gap - hs + 0.5, stick, head=(hs, head), name="maillet")
    n = [I.R[0][1], I.R[1][1], I.R[2][1]]
    P = I.w(0, top_y, 0)
    return hands, tips, (lambda q: sum((q[i] - P[i]) * n[i] for i in range(3))), n


def jeu_maillets(G, a, I, hands, tips, gap, n, hits, slide=1.0, width=0.08):
    """Frappes alternées avec un léger déplacement latéral le long du plateau. hits = (gauche, droit)."""
    out = {}
    lat = [I.R[0][0], I.R[1][0], I.R[2][0]]
    for (s, sx), t0, hs_ in zip(SIDES, hands, hits):
        A = G.contact(s, tips[s], t0, [-v for v in n], gap)
        out[s] = round(gap(G.tip_at(s, tips[s], add(t0, n, -A))), 2)
        a.arm(s, lambda p, t0=t0, A=A, hs_=hs_, sx=sx: add(add(t0, n, -A * sum(bump(p, h - width, h, h + 1.6 * width) for h in hs_)),
                                                           lat, slide * sx * sw(p)), stretch=None)
    return out


def gestes_maillets(m, G, I, hands, tips, gap, n, strike_x, xs, length=0.4):
    """hit_<k> : frappe de la lame placée en xs[k] (repère du plateau, du grave à l'aigu), main gauche pour la
    moitié grave, droite pour l'aiguë ; le contact est recalculé pour chaque lame."""
    lat = [I.R[0][0], I.R[1][0], I.R[2][0]]
    out = []
    for k, x in enumerate(xs):
        (s, sx), t0 = (SIDES[0], hands[0]) if k < len(xs) / 2 else (SIDES[1], hands[1])
        over = add(t0, lat, x - sx * strike_x)
        A = G.contact(s, tips[s], over, [-v for v in n], gap)
        hit = add(over, n, -A)
        out.append(round(gap(G.tip_at(s, tips[s], hit)), 2))
        g, c = geste(m, f"hit_{k}", length)
        g.arm(s, lambda p, t0=t0, hit=hit, over=over, c=c: path(p, [(0, t0), (c, hit), (min(0.9, c * 2.2), over), (1, t0)]), stretch=None)
    return out


def plateau(m, G, name, hw, body, top, tilt=-35, depth=4, thick=2, dy=2.5, dz=3.5, paint=None):
    """Instrument porté devant le ventre, incliné vers le spectateur. top : fonction(grille) du dessus."""
    I = Inst(m, name, (0, G.hips + dy, G.front - dz), (tilt, 0, 0))

    def p(f, g):
        if f == "up":
            top(g)
        elif paint:
            paint(f, g)
    I.box(name, (-hw, -thick / 2, -depth / 2), (hw, thick / 2, depth / 2), body, p, noise=0.04)
    return I


# =============================================================================================== harpiste
@perso
def harpiste():
    m = Model("harpiste")
    blue, ivory, gold, grey = ramp("#344A5C"), pal("#BDB09A", "#E6D9C1", "#F4EBD8"), pal("#96784A", "#C3A16B", "#DDBF8C"), rgb("#657E88")
    hair, brown = pal("#A8926E", "#CDB78E", "#E6D4AE"), pal("#3F312A", "#59463B", "#75604F")
    G = Corps(m, "pale", legs=(2, 10, 3, 1, blue), boots=(3, brown, 1), torso=(6, 8, 4, blue), head=(7, 7, 7),
              arm=(3, 4, blue), belt=(1, gold, None), splay=0, head_rot=(0, 10, 0))
    G.jambes()
    G.torse(front=["miiiim", "mmiimm", "mgmmgm", "mmggmm", "mgmmgm", "mmmmmm", "dmmmmd", "ddmmdd"],
            extra={"i": ivory[1], "g": grey})

    def robe(f, g):
        if f not in ("up", "down"):
            w, h = len(g[0]), len(g)
            for x in range(w):
                g[h - 2][x] = gold[1] if x % 2 else gold[0]
                if x % 4 == 1:
                    g[h - 4][x] = grey
                if x % 4 == 3:
                    g[h - 5][x] = grey
    G.jupe(8, blue, grow=1, paint=robe)
    G.tete(eye="#657E88", hair=hair, eyes="clos", cheek="#DDA596")
    G.cheveux(hair, front=1, side=7, back=9, fringe="ml     lm", open_cols=2, band=(2, gold))
    m.box("tete", "chignon", (-1.5, G.head_top + 1, 0), (1.5, G.head_top + 3, 3), hair, noise=0.1)

    I = Inst(m, "harpe", (-2.5, G.hips - 4, G.front - 3))
    kW = key(WOOD)
    I.box("socle", (-4.5, 0, -1), (4.5, 2, 1), WOOD, noise=0.05)
    I.box("colonne", (-4.5, 2, -1), (-2.5, 13, 1), WOOD,
          lambda f, g: stamp(g, ["lh", "lm", "mm", "lm", "mm", "lm", "mm", "lm", "mm", "md", "dd"], kW) if f == "north" else None, noise=0)
    I.box("caisse", (2.5, 2, -1), (4.5, 9, 1), WOOD,
          lambda f, g: stamp(g, ["ml", "mm", "mg", "mm", "mg", "mm", "dd"], dict(kW, g=gold[1])) if f == "north" else None, noise=0)
    I.box("console", (-2.5, 11, -1), (2.5, 13, 1), WOOD, lambda f, g: stamp(g, ["hlllm", "mmgmd"], dict(kW, g=gold[1])) if f == "north" else None, noise=0)
    I.box("console_basse", (1.5, 9, -1), (4.5, 11, 1), WOOD, noise=0)

    def strings(f, g):
        for y in range(len(g)):
            for x in range(len(g[0])):
                g[y][x] = T if f not in ("north", "south") or x % 2 else (gold[2] if x == 2 else STRING)
    I.box("cordes", (-2.5, 2, -0.5), (2.5, 11, 0.5), ivory, strings, noise=0)
    tl, tr_ = I.w(-3.5, 9.5, -1.4), I.w(1, 6, -1.6)
    G.bras(tl, tr_, cuff=gold[1], lmax=12)
    idle(m)
    a = m.anim("playing", 2.0)
    a.set("tete", "rotation", lambda p: (-3, 2 * sw(p), 0))
    a.arm("droit", lambda p: I.w(0.5 + 1.5 * sw(p), 6 + 1.0 * sw(p, 2), -1.6 - 0.5 * bump(p, 0.15, 0.25, 0.4) - 0.5 * bump(p, 0.65, 0.75, 0.9)))
    a.arm("gauche", lambda p: I.w(-1 - 1.2 * sw(p, 1, 0.25), 8 + 0.8 * sw(p, 2, 0.5), 1.5 + 0.5 * bump(p, 0.4, 0.5, 0.65) + 0.5 * bump(p, 0.9, 1.0, 1.15)))
    r = I.w(0.5, 6, -1.6)
    for k, x in enumerate((-2, -0.7, 0.7, 2)):   # cordes graves (longues) côté colonne
        g, c = geste(m, f"hit_{k}", 0.45)
        g.arm("droit", lambda p, x=x, c=c: path(p, [(0, r), (c, I.w(x, 6.5, -1.2)), (c * 1.7, I.w(x, 6.6, -2.2)), (1, r)]))
    return m


# =========================================================================================== contrebassiste
@perso
def contrebassiste():
    m = Model("contrebassiste")
    deep, green, beige = pal("#33251F", "#48352F", "#62493F"), ramp("#425C4D"), pal("#A99878", "#D1BE9D", "#E6D7BB")
    leather, bronze, hair = pal("#5A3A2A", "#80553D", "#A07052"), pal("#7A5A34", "#A47B4C", "#C69E6C"), pal("#1F1A18", "#33291F", "#4A3C2E")
    G = Corps(m, "brun", legs=(4, 6, 5, 1, deep), boots=(3, leather, 1), torso=(11, 12, 6, green), head=(7, 7, 7),
              arm=(4, 5, green), belt=(2, leather, bronze), splay=5)
    G.jambes()
    G.torse(front=["lmmliiilmml", "mmmliiilmmm", "mmmljijlmmm", "mmmliiilmmm", "mmmliiilmmd", "mmmljijlmmd", "mmmliiilmmd",
                   "mmmliiilmmd", "mmmljijlmmd", "dmmliiilmmd", "dmmmlllmmmd", "dddmmmmmddd"],
            extra={"i": beige[1], "j": bronze[1]})
    G.tete(eye="#33291F", hair=hair, beard=hair, brows=True, mouth=None, cheek=None)
    G.cheveux(hair, front=1, side=5, fringe="d     d", open_cols=1, above=0)
    hat = pal("#2B211C", "#40312A", "#5A463B")
    G.bord(hat, G.head_top, grow=1)
    G.calotte(hat, G.head_top + 1, 3, shrink=0.5,
              paint=lambda f, g: rows_fill(g, bronze[1], [len(g) - 1]) if f not in ("up", "down") else None)

    I = Inst(m, "contrebasse", (2, 0, G.front - 2.5), parent="root")
    kW = dict(key(WOOD), s=STRING, F=rgb("#2E1D17"), B=DARKWOOD[1], t=DARKWOOD[0])
    I.box("caisse_bas", (-4.5, 0, -1.5), (4.5, 8, 1.5), WOOD,
          lambda f, g: stamp(g, ["lmmssmmml", "lmFssmFmm", "mmFssmFmm", "mmBBBBBmd", "mmmsttmmd", "mmmmttmmd", "dmmmttmdd", "ddmmmmddd"], kW)
          if f == "north" else None, noise=0.05)
    I.box("caisse_haut", (-3.5, 8, -1.5), (3.5, 14, 1.5), WOOD,
          lambda f, g: stamp(g, ["hlmssml", "lmmssmm", "mmmssmm", "mmmssmd", "mmmssmd", "dmmssmd"], kW) if f == "north" else None, noise=0.05)
    I.box("manche", (-1, 14, -1.5), (1, 21, -0.5), DARKWOOD,
          lambda f, g: stamp(g, ["sd"] * 7, dict(key(DARKWOOD), s=STRING)) if f == "north" else None, noise=0)
    I.box("volute", (-1.5, 21, -1.5), (1.5, 24, 0), WOOD,
          lambda f, g: stamp(g, ["lhl", "ldm", "mmd"], key(WOOD)) if f == "north" else None, noise=0)
    tl, tr_ = I.w(-0.5, 19, -2), I.w(1, 13.5, -2)
    G.bras(tl, tr_, bare=3, lmax=13)
    idle(m)
    a = m.anim("playing", 2.0)
    a.set("corps", "rotation", lambda p: (-2 - 0.8 * sw(p), 0, 0))
    a.arm("droit", lambda p: add(tr_, (0.9, 0.3, -0.9), bump(p, 0.02, 0.12, 0.3) + bump(p, 0.52, 0.62, 0.8)))
    a.arm("gauche", lambda p: add(tl, (0, 1, 0), -1.5 * bump(p, 0.3, 0.45, 0.95)))
    g, c = geste(m, "hit_0", 0.5)
    g.arm("droit", lambda p: path(p, [(0, tr_), (c, add(tr_, (0.9, 0.3, -0.9))), (1, tr_)]))
    for k, dy in enumerate((1.2, 0, -1.5)):       # grave en haut du manche
        tenue(m, f"hold_{k}", "gauche", add(tl, (0, dy, 0)))
    return m


# =============================================================================================== didgeridoo
@perso
def didgeridoo():
    m = Model("didgeridoo")
    terra, brown, ocre = ramp("#A36548"), pal("#33241E", "#49352E", "#644A40"), pal("#8E6A40", "#B78A58", "#D4AA78")
    beige, moss, hair = rgb("#D8C3A0"), rgb("#657153"), pal("#241C19", "#3A2D27", "#55433A")
    G = Corps(m, "olive", legs=(4, 6, 4, 1, brown), boots=(2, brown, 1), torso=(11, 9, 6, terra), head=(9, 8, 8),
              arm=(3, 4, None), belt=(2, ocre, None), splay=6)
    G.apal = G.skin
    G.jambes()
    G.torse(front=["mmmbbmbbmmm", "mmmmbbbmmmm", "mmmmmbmmmmm", "ommmmmmmmmo", "momomomomom", "omomomomomo", "mmmmmmmmmmd",
                   "dmmgmgmgmmd", "ddmmmmmmmdd"], extra={"b": beige, "o": ocre[1], "g": moss})
    G.jupe(3, terra, grow=0.5, paint=lambda f, g: [g[1].__setitem__(x, ocre[1]) for x in range(0, len(g[0]), 2)]
           if f not in ("up", "down") else None)
    G.tete(eye="#49352E", hair=hair, brows=True, mouth=None, paint=lambda f, g: (g[2].__setitem__(4, ocre[1]), g[5].__setitem__(4, ocre[1])) if f == "north" else None)
    G.cheveux(hair, front=2, side=5, fringe="d       d", open_cols=3, above=1, band=(2, [moss, moss]))
    m.box("tete", "chignon", (-1.5, G.head_top + 1, -0.5), (1.5, G.head_top + 4, 2.5), hair, noise=0.1)

    I = Inst(m, "didgeridoo", (0, G.top + 1.5, G.face_z - 0.6), (32, 0, 0))
    wood = pal("#553729", "#7A5238", "#99704C")

    def tube(f, g):
        if f in ("up",):
            for row in g:
                row[:] = [rgb("#241712")] * len(row)
        elif f != "down":
            for y in (2, 3, 8, 12, 13):
                g[y] = [ocre[1 if y % 2 else 2]] * len(g[0])
            for y in (5, 10):
                g[y][0] = beige
    I.box("tube", (-1, -15, -1), (1, 0, 1), wood, tube, noise=0.12)
    I.box("pavillon", (-1.5, -17, -1.5), (1.5, -14, 1.5), wood,
          lambda f, g: (rows_fill(g, ocre[1], [0]), g[1].__setitem__(1, beige)) if f not in ("up", "down") else
          [row.__setitem__(slice(None), [rgb("#241712")] * len(row)) for row in g] if f == "down" else None, noise=0.1)
    G.bras(I.w(-0.6, -4.5, 0), I.w(0.6, -8.5, 0), band=(1, pal("#5A3A2A", "#80553D", "#A07052")[1]), lmax=12,
           paint=lambda f, g: (rows_fill(g, terra[1], [0, 1]), rows_fill(g, ocre[1], [2])) if f not in ("up", "down") else None)
    idle(m)
    a = m.anim("playing", 2.5)
    a.set("corps", "scale", lambda p: (1 + 0.025 * sw(p), 1, 1 + 0.04 * sw(p)))
    a.set("didgeridoo", "rotation", lambda p: (0.7 * sw(p, 1, 0.2), 0, 0))
    a.arm("gauche", lambda p: a.at("didgeridoo", I.w(-0.6, -4.5, 0), p))
    a.arm("droit", lambda p: a.at("didgeridoo", I.w(0.6, -8.5, 0), p))
    return m


# =============================================================================================== charleston
@perso
def charleston():
    m = Model("charleston")
    slate, brown, blue = ramp("#56646A"), pal("#3A2D25", "#554237", "#70594B"), ramp("#637D83")
    beige, copper, hair = pal("#AE9E80", "#D7C6A7", "#EADCC2"), pal("#7C5A36", "#A77C4F", "#C89E70"), pal("#5A2A1C", "#7E3F28", "#A05A3A")
    G = Corps(m, "clair", legs=(3, 6, 3, 2, slate), boots=(3, brown, 1), torso=(6, 8, 4, blue), head=(6, 6, 6),
              arm=(3, 3, blue), belt=(1, brown, copper), splay=8)
    G.jambes()
    G.torse(front=["liiiil", "mliilm", "mmiimm", "mmiimd", "mmiimd", "dmccmd", "dmmmmd", "ddmmdd"],
            extra={"i": beige[1], "c": copper[2]})
    G.tete(eye="#3A2D25", hair=hair)
    G.cheveux(hair, front=1, side=3, fringe="m    d", open_cols=2, above=0)
    G.bord(slate, G.head_top, grow=0, front=1,
           paint=lambda f, g: stamp(g, ["GgcgG"], {"G": rgb("#8BB5B0"), "g": copper[1], "c": copper[0]}, 1, 0) if f == "north" else None)
    G.calotte(slate, G.head_top + 1, 2, shrink=0.5)

    I = Inst(m, "charleston", (2.5, 0, G.front - 4), parent="root")
    cym = pal("#8E6E34", "#C19A4A", "#E0BE6C", "#F0D896")

    def disc(f, g):
        w, h = len(g[0]), len(g)
        if f == "up":
            for y in range(h):
                for x in range(w):
                    e = min(x, y, w - 1 - x, h - 1 - y)
                    g[y][x] = cym[2 if e == 0 else (1 if e == 1 else 3)]
            g[0][0] = g[0][-1] = g[-1][0] = g[-1][-1] = T
        elif f == "down":
            g[0][0] = g[0][-1] = g[-1][0] = g[-1][-1] = T
    I.box("tige", (-0.5, 0, -0.5), (0.5, 17, 0.5), brown, noise=0.1)
    I.box("pied", (-2, 0, -2), (2, 1, 2), DARKWOOD, noise=0)
    I.box("cymbale_bas", (-3, 13, -3), (3, 14, 3), cym, disc, noise=0)
    I.sub("cymbale_haut")
    I.box("cymbale_haut", (-3, 15, -3), (3, 16, 3), cym, disc, bone="cymbale_haut", noise=0)
    m.bone("pedale", (2.5, 0, G.front - 1), "charleston")
    m.box("pedale", "pedale", (1.5, 0, G.front - 3.5), (3.5, 1, G.front - 1), copper, noise=0)
    hands = [(-4.5, G.hips + 1.5, -2), (6, 18.2, G.front - 2.5)]
    G.bras(*hands, bare=2)
    tip = G.baton("droit", (-0.5, -0.35, -0.5), 3.5, pal("#A98958", "#CDB07C", "#E4CC9C"))
    gap = lambda q: q[1] - 16   # dessus de la cymbale haute (ouverte)
    idle(m)
    a = m.anim("playing", 1.0)
    close = lambda p: bump(p, 0.08, 0.2, 0.34) + bump(p, 0.58, 0.7, 0.84)
    a.set("cymbale_haut", "position", lambda p: (0, -1.0 * close(p), 0))
    a.set("pedale", "rotation", lambda p: (-10 * close(p), 0, 0))
    a.set("jambe_droite", "rotation", lambda p: (5 - 5 * close(p), 0, 0))
    A = frappes(G, a, "droit", tip, hands[1], (0, -1, 0), gap, (0.45, 0.95), width=0.07)
    a.arm("gauche", lambda p: add(hands[0], (0, 0.4, 0), close(p)))
    g, c = geste(m, "hit_0", 0.4)
    shut = lambda p: bump(p, 0.05, c, 0.85)
    g.set("cymbale_haut", "position", lambda p: (0, -1.0 * shut(p), 0))
    g.set("pedale", "rotation", lambda p: (-10 * shut(p), 0, 0))
    g.set("jambe_droite", "rotation", lambda p: (5 * shut(p), 0, 0))
    Ac = G.contact("droit", tip, hands[1], (0, -1, 0), lambda q: q[1] - 15)   # cymbale refermée : un cran plus bas
    g.arm("droit", lambda p: path(p, [(0, hands[1]), (c, add(hands[1], (0, -1, 0), Ac)), (1, hands[1])]), stretch=None)
    m.check = lambda: ("écart baguette", round(gap(G.bout("droit", tip, a, 0.45)), 2), "cymbales fermées à",
                       a.value("cymbale_haut", "position", 0.2)[1])
    return m


# =============================================================================================== cloche
@perso
def cloche():
    m = Model("cloche")
    ocre, olive, brown = ramp("#B58A50"), ramp("#68734E"), pal("#45301F", "#644733", "#83624A")
    beige, copper, hair = pal("#B5A88C", "#DED0B3", "#EFE5CD"), pal("#7E5A36", "#AD7B4D", "#CC9E6E", "#E2BE90"), pal("#8A6A2E", "#B8923F", "#D9B660")
    G = Corps(m, "clair", legs=(3, 3, 4, 1, brown), boots=(3, pal("#33241A", "#4A3424", "#644733"), 1),
              torso=(10, 8, 6, ocre), head=(10, 9, 8), arm=(3, 4, ocre), belt=(1, brown, copper), splay=8)
    G.jambes()
    G.torse(front=["vvvmmmmvvv", "vvvmmmmvvv", "vvVmmmmVvv", "vvVmbmmVvv", "vvVmmmmVvv", "vvVmbmmVvv", "VVVmmmmVVV", "dddmmmmddd"],
            extra={"v": olive[1], "V": olive[0], "b": beige[1]}, paint=lambda f, g: [row.__setitem__(slice(None), [olive[0 if y > 5 else 1]] * len(row))
                                                                                    for y, row in enumerate(g)] if f == "south" else None)
    G.tete(eye="#5A6A3A", hair=hair, cheek="#DE8672", mouth="#8F5A48",
           paint=lambda f, g: (g[6].__setitem__(3, rgb("#C08A6E")), g[6].__setitem__(6, rgb("#C08A6E")), g[5].__setitem__(4, rgb("#C08A6E"))) if f == "north" else None)
    G.cheveux(hair, front=1, side=4, fringe="lm  l  dm l", open_cols=3, above=0)
    straw = pal("#A88A48", "#CFAE64", "#E6CC88")
    G.bord(straw, G.head_top, grow=2, paint=lambda f, g: [g[y].__setitem__(x, straw[0]) for y in range(len(g)) for x in range(len(g[0])) if (x + 2 * y) % 5 == 0] if f == "up" else None)
    G.calotte(straw, G.head_top + 1, 2, shrink=1, paint=lambda f, g: rows_fill(g, olive[1], [len(g) - 1]) if f not in ("up", "down") else None)

    left = (-6.5, G.top + 0.5, G.front - 3)
    bell_x, by = left[0] + 2.5, left[1] - 4
    t0 = (bell_x + 1 + 3.2, by - 0.4, left[2])      # main droite : baguette à 1 unité du flanc de la cloche
    hands = [left, t0]
    G.bras(*hands, cuff=beige[0])
    copper = pal("#89602E", "#C18A3E", "#E3B65F", "#F0D18B")
    dark = rgb("#3A2616")
    G.baton("gauche", (0, -1, 0), 2, copper, thick=3, head=(5, copper, 4), name="cloche",
            head_paint=lambda f, g: [row.__setitem__(slice(None), [dark] * len(row)) for row in g] if f == "down" else
            stamp(g, ["hllmm", "llmmm", "lmmmd", "mmddd"], key(copper)) if f == "north" else None)
    tip = G.baton("droit", (-1, 0.12, 0), 3.2, pal("#8C6B48", "#B08A5E", "#CCA878"))
    gap = lambda q: q[0] - bell_x
    A = G.contact("droit", tip, t0, (-1, 0, 0), gap)
    idle(m)
    a = m.anim("playing", 1.3)
    hit = lambda p: bump(p, 0.2, 0.28, 0.42) + bump(p, 0.62, 0.7, 0.84)
    a.arm("droit", lambda p: add(t0, (-1, 0, 0), A * hit(p)), stretch=None)
    a.arm("gauche", lambda p: add(hands[0], (-0.6, 0.2, 0), bump(p, 0.28, 0.4, 0.6) + bump(p, 0.7, 0.82, 1.0)), stretch=None)
    g, c = geste(m, "hit_0", 0.35)
    g.arm("droit", lambda p: path(p, [(0, t0), (c, add(t0, (-1, 0, 0), A)), (1, t0)]), stretch=None)
    m.check = lambda: ("écart baguette / cloche", round(gap(G.bout("droit", tip, a, 0.28)), 2))
    return m


# =============================================================================================== glockenspiel
def lames(colors, n, depth, frame, gaps=True, taper=True):
    """Peinture du dessus d'un jeu de lames : n lames (colonnes), séparées par un vide, de longueur décroissante."""
    def top(g):
        w = len(g[0])
        for y in range(len(g)):
            for x in range(w):
                i = x // 2 if gaps else x
                bar = (x % 2 == 0) if gaps else True
                short = (i * 2 // n) if taper else 0        # lames plus courtes vers la droite du personnage
                inside = short // 2 <= y < len(g) - (short + 1) // 2 if taper else True
                if bar and inside and i < n:
                    c = colors[i % len(colors)]
                    g[y][x] = c[2] if y == short // 2 else (c[0] if y == len(g) - 1 - (short + 1) // 2 else c[1])
                else:
                    g[y][x] = T if gaps else frame
    return top


@perso
def glockenspiel():
    m = Model("glockenspiel")
    blue, ivory, bronze = ramp("#75939A"), pal("#BEB39C", "#E7DCC6", "#F5EEDC"), pal("#86663A", "#B28A52", "#D2AE78", "#E8CC9C")
    brown, deep, hair = pal("#4A372E", "#695044", "#876A5C"), rgb("#425B68"), pal("#B89A5A", "#DCC07E", "#F0DCA4")
    G = Corps(m, "pale", legs=(3, 9, 3, 1, brown), boots=(2, pal("#33261F", "#4A372E", "#695044"), 1),
              torso=(6, 8, 4, blue), head=(8, 8, 7), arm=(3, 3, blue), belt=(1, [deep, deep, deep], None), splay=2)
    G.jambes()
    G.torse(front=["kiiiik", "mkibkm", "mmkkmm", "mmmbmm", "mmmmmd", "mmmbmd", "dmmmmd", "ddmmdd"],
            extra={"i": ivory[1], "b": bronze[2], "k": deep})
    G.tete(eye="#425B68", hair=hair, eyes="rond", cheek="#E0A898")
    G.cheveux(hair, front=2, side=3, fringe="lmm l    ", open_cols=3, above=1)
    m.box("tete", "epi", (0.5, G.head_top + 1, -1), (2.5, G.head_top + 2, 1), hair, noise=0)

    I = plateau(m, G, "glockenspiel", 5, brown, lambda g: [row.__setitem__(slice(None), [brown[1]] * len(row)) for row in g], thick=1, depth=5, dy=3, dz=4)
    I.box("lames", (-4, 0.5, -2), (4, 1.5, 2), bronze,
          lambda f, g: lames([bronze[1:], pal("#A07A44", "#C9A262", "#E4C68C")], 8, 4, brown[0], gaps=False)(g) if f == "up" else None, noise=0)
    hands, tips, gap0, n = maillets(G, I, (3.6, 5.2, 2.2), (1.6, 1.5, -0.3), 1.5, pal("#8C6B48", "#B08A5E", "#CCA878"), ivory, cuff=ivory[1])
    idle(m)
    a = m.anim("playing", 1.6)
    chk = jeu_maillets(G, a, I, hands, tips, gap0, n, ((0.45, 0.92), (0.2, 0.7)), slide=0.9)
    a.set("tete", "rotation", lambda p: (-4, 0, 0))
    chk2 = gestes_maillets(m, G, I, hands, tips, gap0, n, 1.6, (-3, -1, 1, 3))
    m.check = lambda: ("écart à la frappe", chk, chk2)
    return m


# =============================================================================================== carillonneur
@perso
def carillonneur():
    m = Model("carillonneur")
    night, silver, ivory = ramp("#354A60"), pal("#737E82", "#9DA8AA", "#C2CBCB", "#DDE3E2"), pal("#B6AF9E", "#E0D9C8", "#F1ECDF")
    violet, brown = ramp("#776B91"), pal("#392F2B", "#51433D", "#6C5B53")
    G = Corps(m, "pale", legs=(2, 10, 3, 1, night), boots=(2, brown, 1), torso=(6, 9, 4, night), head=(7, 7, 7),
              arm=(3, 4, night), belt=(1, silver, None), splay=0)
    G.jambes()
    G.torse(front=["mmiimm", "mmiimm", "mmssmm", "msmmsm", "mmssmm", "mmmmmm", "dmsmmd", "dmmmmd", "ddmmdd"],
            extra={"i": ivory[1], "s": silver[1]})
    G.jupe(9, night, grow=1, paint=lambda f, g: (rows_fill(g, silver[0], [len(g) - 2]),
                                                 [g[len(g) - 4].__setitem__(x, violet[1]) for x in range(1, len(g[0]), 3)])
           if f not in ("up", "down") else None)
    m.box("corps", "cape", (-G.tw / 2 - 0.5, G.top - 3, -G.td / 2 - 0.5), (G.tw / 2 + 0.5, G.top, G.td / 2 + 1), violet,
          lambda f, g: rows_fill(g, silver[1], [len(g) - 1]) if f not in ("up", "down") else None, top=1, bot=0)
    G.tete(eye="#776B91", cheek=None, mouth="#A07868")
    G.cheveux(violet, front=1, side=7, fringe=None, open_cols=1, above=1, noise=0.08)   # capuchon
    G.calotte(violet, G.head_top + 1, 1, shrink=1.5, dz=1)
    G.calotte(violet, G.head_top + 2, 1, shrink=2.5, dz=2, name="pointe")

    I = Inst(m, "carillon", (-1.5, G.top + 0.5, G.front - 5))
    I.box("barre", (-4, -1, -0.5), (4, 0, 0.5), DARKWOOD, noise=0)
    xs, Ls = (-2.5, 0, 2.5), (10, 8, 6)
    for i, (x, L) in enumerate(zip(xs, Ls)):
        b = f"tube_{i + 1}"
        m.bone(b, I.w(x, -1, 0), "carillon")
        I.box(b, (x - 0.5, -1 - L, -0.5), (x + 0.5, -1, 0.5), silver,
              lambda f, g: (g[0].__setitem__(0, DARKWOOD[1]), [g[y].__setitem__(0, silver[3]) for y in range(2, len(g), 3)]) if f == "north" else None,
              bone=b, noise=0.05)
    hl = I.w(-3.5, -0.5, 0.3)
    hr0 = (I.w(3, 0, 0)[0] + 5, I.w(0, -4.5, 0)[1], I.pivot[2])
    G.bras(hl, hr0, cuff=silver[1], lmax=12)
    tip = G.baton("droit", (-1, 0, 0), 2.5, DARKWOOD, head=(1.5, silver), name="percuteur")
    f3, f2 = I.w(3, 0, 0)[0], I.w(0.5, 0, 0)[0]       # faces droites des tubes 3 et 2
    low = (hr0[0] - 2.5, I.w(0, -8, 0)[1], hr0[2])
    A1 = G.contact("droit", tip, hr0, (-1, 0, 0), lambda q: q[0] - f3)
    A2 = G.contact("droit", tip, low, (-1, 0, 0), lambda q: q[0] - f2)
    idle(m)
    a = m.anim("playing", 2.0)
    a.arm("droit", lambda p: path(p, [(0, hr0), (0.15, add(hr0, (-1, 0, 0), A1)), (0.3, hr0), (0.5, low),
                                      (0.65, add(low, (-1, 0, 0), A2)), (0.8, low), (1, hr0)]), stretch=None)
    a.arm("gauche", lambda p: add(hl, (0, 0.3, 0), sw(p, 1, 0.3)))
    a.set("carillon", "position", lambda p: (0, 0.3 * sw(p, 1, 0.3), 0))
    sway = lambda p, h: 4 * bump(p, h, h + 0.08, h + 0.3) - 2.5 * bump(p, h + 0.22, h + 0.36, h + 0.55)
    a.set("tube_3", "rotation", lambda p: (0, 0, -sway(p, 0.15)))
    a.set("tube_2", "rotation", lambda p: (0, 0, -sway(p, 0.65) + sway(p - 1, 0.65)))
    g, c = geste(m, "hit_0", 0.9)      # grave : le tube long, frappé sous le tube court
    g.arm("droit", lambda p, c=c: path(p, [(0, hr0), (c * 0.55, low), (c, add(low, (-1, 0, 0), A2)), (0.5, low), (1, hr0)]), stretch=None)
    g.set("tube_2", "rotation", lambda p, c=c: (0, 0, -sway(p, c)))
    g, c = geste(m, "hit_1", 0.9)      # aigu : le tube court
    g.arm("droit", lambda p, c=c: path(p, [(0, hr0), (c, add(hr0, (-1, 0, 0), A1)), (0.45, hr0), (1, hr0)]), stretch=None)
    g.set("tube_3", "rotation", lambda p, c=c: (0, 0, -sway(p, c)))
    m.check = lambda: ("écarts percuteur", round(G.bout("droit", tip, a, 0.15)[0] - f3, 2), round(G.bout("droit", tip, a, 0.65)[0] - f2, 2))
    return m


# ============================================================================================ claviers à lames
def table(m, G, name, y, bars, colors, tilt, legs_pal, hw=6.5, resonators=None):
    """Instrument à lames sur pieds, devant le musicien. Renvoie l'instrument (repère du plateau incliné)."""
    z = G.front - 5.5
    I = Inst(m, name, (0, y, z), (tilt, 0, 0), parent="root")
    I.box("cadre", (-hw - 0.5, -0.5, -2.5), (hw + 0.5, 0.5, 2.5), legs_pal, noise=0.05)
    I.box("lames", (-hw, 0.5, -2), (hw, 1.5, 2), colors[0],
          lambda f, g: lames(colors, bars, 4, legs_pal[0])(g) if f == "up" else
          [g[yy].__setitem__(x, T) for yy in range(len(g)) for x in range(1, len(g[0]), 2)] if f in ("north", "south") else
          [row.__setitem__(slice(None), [T] * len(row)) for row in g] if f == "down" else None, noise=0)
    for sx in (-1, 1):
        m.box(name, f"pied_{'g' if sx < 0 else 'd'}", (sx * hw - 0.5, 0, z + 0.5), (sx * hw + 0.5, y, z + 1.5), legs_pal, noise=0.05)
    if resonators:
        def res(f, g):
            w, h = len(g[0]), len(g)
            for yy in range(h):
                for x in range(w):
                    L = h - (x // 2) * (h - 2) // max(1, w // 2) if f == "south" else h - ((w - 1 - x) // 2) * (h - 2) // max(1, w // 2)
                    ok = f in ("north", "south") and x % 2 == 0 and yy < L
                    g[yy][x] = (resonators[2] if yy == 0 else resonators[1 if yy < L - 1 else 0]) if ok else T
        m.box(name, "resonateurs", (-hw, y - 6.5, z - 1), (hw, y - 0.5, z), resonators, res, noise=0)
    return I


@perso
def xylophoniste():
    m = Model("xylophoniste")
    sage, brown, ivory = ramp("#809078"), pal("#553A2B", "#76513D", "#946C54"), pal("#BBAE92", "#E4D5B8", "#F3E9D2")
    ocre, red, hair = rgb("#B88C53"), ramp("#A36A55"), pal("#4A3020", "#6B4630", "#8C6244")
    G = Corps(m, "hale", legs=(3, 4, 4, 1, brown), boots=(3, pal("#3A281E", "#553A2B", "#76513D"), 1),
              torso=(9, 8, 5, ivory), head=(9, 9, 8), arm=(3, 4, ivory), belt=(1, brown, None), splay=6)
    G.jambes()
    G.torse(front=["mmammmamm", "mmammmamm", "mmaaaaamm", "mmaaoaamm", "mmaaaaamm", "maaaaaaam", "maaaaaaam", "dAAAAAAAd"],
            back=["mmammmamm", "mmmamammm", "mmmmammmm", "mmmamammm", "mmammmamm", "mmmmmmmmm", "dmmmmmmmd", "ddddddddd"],
            extra={"a": red[1], "A": red[0], "o": ocre})
    G.tete(eye="#4A3020", hair=hair, cheek="#D98C7E", mouth="#8F5A48", brows=True)
    G.cheveux(hair, front=1, side=4, fringe="mdl m ld m", open_cols=3, above=0)
    G.bord(sage, G.head_top, h=1, grow=1, rot=(0, 0, -6))
    G.calotte(sage, G.head_top + 1, 1, shrink=0.5, rot=(0, 0, -6), name="beret")
    m.box("tete", "queue_beret", (-0.5, G.head_top + 2, -0.5), (0.5, G.head_top + 3, 0.5), sage, noise=0)

    woods = [pal("#8A5A34", "#B07A48", "#CC9A66"), pal("#9C6A3C", "#C48C54", "#DEAC74"), pal("#7A4A30", "#A0663E", "#BE865A"),
             pal("#A87A44", "#CC9C5E", "#E4BC80")]
    I = table(m, G, "xylophone", G.hips + 4.5, 7, woods, -28, DARKWOOD)
    hands, tips, gap0, n = maillets(G, I, (4.2, 5.2, 2.6), (2.0, 1.5, 0), 1.5, pal("#8C6B48", "#B08A5E", "#CCA878"), red, bare=2, hs=1.5)
    idle(m)
    a = m.anim("playing", 1.5)
    chk = jeu_maillets(G, a, I, hands, tips, gap0, n, ((0.3, 0.8), (0.05, 0.55)), slide=1.4, width=0.07)
    a.set("tete", "rotation", lambda p: (-6 + sw(p, 2), 0, 0))
    chk2 = gestes_maillets(m, G, I, hands, tips, gap0, n, 2.0, (-4.5, -1.5, 1.5, 4.5))
    m.check = lambda: ("écart à la frappe", chk, chk2)
    return m


@perso
def vibraphoniste():
    m = Model("vibraphoniste")
    blue, silver, night = ramp("#5D7884"), pal("#7C898C", "#A9B6B8", "#CBD5D5", "#E4EAEA"), ramp("#344956")
    ivory, brown, hair = pal("#B5B09F", "#DED9C8", "#EFECE0"), pal("#4A3C35", "#65534A", "#82706A"), pal("#8E969A", "#B9C1C4", "#DCE2E3")
    G = Corps(m, "pale", legs=(3, 12, 3, 1, night), boots=(2, brown, 1), torso=(6, 8, 4, blue), head=(7, 7, 7),
              arm=(3, 3, blue), splay=0)
    G.jambes()
    G.torse(front=["liiiil", "mmsmsm", "mmmmmm", "mmsmsm", "mmmmmd", "mmsmsd", "dmmmmd", "ddmmdd"],
            extra={"i": ivory[1], "s": silver[2]})
    m.box("corps", "col", (-3.5, G.top - 1, -G.td / 2 - 0.5), (3.5, G.top + 1, G.td / 2 + 0.5), blue,
          lambda f, g: stamp(g, ["m" + " " * (len(g[0]) - 2) + "m"] * 2, key(blue)) if f == "north" else None, noise=0)
    G.tete(eye="#5D7884", hair=hair, cheek=None, brows=True, mouth="#A07868")
    G.cheveux(hair, front=1, side=3, back=5, fringe="l  m   ", open_cols=3, above=1, noise=0.12)

    metal = [silver[:3], pal("#6F7E86", "#98A8B0", "#BDCBD0")]
    I = table(m, G, "vibraphone", G.hips + 2.5, 7, metal, -24, pal("#231F22", "#35302F", "#4A4442"), resonators=pal("#6F7E86", "#98A8B0", "#C6D2D6"))
    hands, tips, gap0, n = maillets(G, I, (4.4, 5.6, 2.6), (2.2, 1.5, 0), 1.5, pal("#35302F", "#4A4442", "#655E5B"), night, cuff=silver[1], hs=1.5)
    idle(m)
    a = m.anim("playing", 1.6)
    chk = jeu_maillets(G, a, I, hands, tips, gap0, n, ((0.7,), (0.2,)), slide=1.8, width=0.1)
    chk2 = gestes_maillets(m, G, I, hands, tips, gap0, n, 2.2, (-4.5, -1.5, 1.5, 4.5), length=0.5)
    m.check = lambda: ("écart à la frappe", chk, chk2)
    return m


# =============================================================================================== claviers
def presses(G, a, I, base, dips, travel=0.0, depth=0.55, width=0.06):
    """Main posée sur un clavier : pressions aux phases dips, avec un glissement latéral le long des touches."""
    n = [I.R[0][1], I.R[1][1], I.R[2][1]]
    lat = [I.R[0][0], I.R[1][0], I.R[2][0]]
    return lambda p: add(add(base, n, -depth * sum(bump(p, d - width, d, d + width) for d in dips)), lat, travel * sw(p))


def gestes_touches(m, I, hl, hr, xs, depth=0.55, glow=None):
    """hit_<k> : pression de la touche placée en xs[k] (repère du clavier), main gauche pour la moitié grave."""
    n = [I.R[0][1], I.R[1][1], I.R[2][1]]
    lat = [I.R[0][0], I.R[1][0], I.R[2][0]]
    for k, x in enumerate(xs):
        s, base, x0 = ("gauche", hl, -2.5) if k < len(xs) / 2 else ("droit", hr, 2.5)
        down = add(add(base, lat, x - x0), n, -depth)
        g, c = geste(m, f"hit_{k}", 0.35)
        g.arm(s, lambda p, base=base, down=down, c=c: path(p, [(0, base), (c, down), (1, base)]))
        if glow:
            g.set(glow, "scale", lambda p, c=c: tuple(1 + 0.22 * bump(p, c * 0.5, c, 0.95) for _ in range(3)))


@perso
def bit():
    m = Model("bit")
    em, turq, deep = ramp("#356F60"), ramp("#5C9B89"), pal("#2A2522", "#3F3834", "#59504A")
    ivory, cyan = pal("#ADAC96", "#D6D5BD", "#E8E7D4"), pal("#5CA897", "#8BD8C5", "#C4F2E6")
    G = Corps(m, "olive", legs=(3, 6, 3, 1, deep), boots=(3, pal("#1F1B19", "#2A2522", "#3F3834"), 1), torso=(7, 8, 4, em),
              head=(8, 8, 7), arm=(3, 3, em), belt=(1, deep, cyan), splay=3, head_rot=(0, 0, -4))
    G.jambes()
    G.torse(front=["lmmmtmm", "mmmtmmm", "mmtmmmm", "mtcmmmm", "tmmmmmd", "mmmmmmd", "dmmmmmd", "ddmmmdd"],
            extra={"t": turq[1], "c": cyan[1]}, buckle_x=1)
    m.box("corps", "epauliere", (-G.tw / 2 - 3.5, G.top - 2, -2.5), (-G.tw / 2 + 0.5, G.top + 1, 2.5), turq,
          lambda f, g: rows_fill(g, deep[1], [len(g) - 1]) if f not in ("up", "down") else None)
    m.box("corps", "poche", (G.tw / 2, G.hips - 2, -1.5), (G.tw / 2 + 2, G.hips + 1, 1.5), deep, noise=0)
    G.tete(eye="#8BD8C5", cheek=None, mouth="#7A5A48", noise=0)
    G.cheveux(em, front=2, side=7, fringe="d      dd", open_cols=1, above=1, noise=0.06,
              band=(1, [turq[0], turq[1]]))                                          # capuche courte
    m.box("tete", "cristal", (-1, G.head_top, -G.hd / 2 - 1), (1, G.head_top + 2, -G.hd / 2), cyan, noise=0)

    def top(g):
        keys = "eIeIIeI"
        for y in range(len(g)):
            for x in range(len(g[0])):
                g[y][x] = deep[1 if (x + y) % 3 else 2]
        for x, ch in enumerate(keys):
            g[0][1 + x] = em[2] if ch == "e" else ivory[2]
            g[1][1 + x] = em[1] if ch == "e" else ivory[1]
        g[3][0] = g[3][-1] = rgb("#B9A56A")
        g[2][0] = g[2][-1] = rgb("#8C7A48")
    I = plateau(m, G, "clavier", 4.5, deep, top, dy=2.5, dz=3.5)
    I.sub("glow_cristal")
    m.bones["glow_cristal"]["origin"] = I.w(0, 1.5, 1)
    I.box("ecran", (-1.5, 1, 0), (1.5, 2, 2), cyan, bone="glow_cristal", noise=0,
          paint=lambda f, g: stamp(g, ["lhl", "mlm"], key(cyan)) if f == "up" else None)
    hl, hr = I.w(-2.5, 2.2, -1.3), I.w(2.5, 2.2, -1.3)
    G.bras(hl, hr, paint=lambda f, g: [g[y].__setitem__(x, turq[1]) for y in (2, 4) for x in range(len(g[0])) if (x + y // 2) % 2 == 0]
           if f not in ("up", "down") and len(g) > 5 else None)
    idle(m)
    a = m.anim("playing", 1.5)
    rd, ld = (0.08, 0.26, 0.44, 0.62, 0.8), (0.35, 0.9)
    a.arm("droit", presses(G, a, I, hr, rd, travel=0.8))
    a.arm("gauche", presses(G, a, I, hl, ld, travel=-0.5))
    a.set("glow_cristal", "scale", lambda p: tuple(1 + 0.18 * sum(bump(p, d - 0.05, d, d + 0.12) for d in rd + ld) for _ in range(3)))
    a.set("tete", "rotation", lambda p: (-5 - sw(p), 0, 0))
    gestes_touches(m, I, hl, hr, (-3, -1.5, 1.5, 3), glow="glow_cristal")
    return m


@perso
def pling():
    m = Model("pling")
    plum, ivory, brown = ramp("#72556F"), pal("#C2B59E", "#E9DCC5", "#F6EEDD"), pal("#3C2C2A", "#55403E", "#725957")
    gold, rose, wig = pal("#987C4C", "#C5A46E", "#E0C592"), rgb("#B88C9E"), pal("#C9B8B4", "#E9DCD6", "#F7F0EC")
    G = Corps(m, "pale", legs=(3, 5, 3, 1, brown), boots=(2, pal("#241A19", "#3C2C2A", "#55403E"), 1), torso=(7, 8, 4, plum),
              head=(8, 8, 7), arm=(3, 3, plum), splay=3)
    G.jambes(cuff=gold[1])
    G.torse(front=["mliiilm", "mmiiimm", "mmjijmm", "mgmimgm", "mmmmmmm", "mgmmmgm", "dmmmmmd", "ddmmmdd"],
            extra={"i": ivory[1], "j": ivory[0], "g": gold[2]})
    m.box("corps", "basques", (-3.5, G.hips - 4, G.td / 2 - 1), (3.5, G.hips, G.td / 2 + 0.5), plum,
          lambda f, g: [g[y].__setitem__(len(g[0]) // 2, T) for y in range(1, len(g))] if f in ("south", "north") else None, top=0)
    G.tete(eye="#55403E", hair=wig, cheek="#C98FA0", mouth="#A0606A", brows=False)
    G.cheveux(wig, front=1, side=5, back=6, fringe="lm    ml", open_cols=2, above=2, noise=0.2)
    G.bord(plum, G.head_top + 2, grow=-1.5, rot=(0, 0, 8), name="haut_de_forme_bord")
    G.calotte(plum, G.head_top + 3, 2, shrink=2.5, rot=(0, 0, 8), name="haut_de_forme",
              paint=lambda f, g: rows_fill(g, gold[1], [len(g) - 1]) if f not in ("up", "down") else None)

    def top(g):
        for y in range(len(g)):
            for x in range(len(g[0])):
                edge = x in (0, len(g[0]) - 1)
                g[y][x] = gold[1 if y % 2 else 2] if edge else (ivory[2 if y == 0 else 1] if y < 3 else brown[1])
        for x in (2, 3, 5, 6, 7):
            g[2][x] = brown[0]
        g[3][4] = g[3][5] = gold[2]
    I = plateau(m, G, "clavier", 5, pal("#4E3428", "#6E4A38", "#8C6248"), top, dy=3, dz=3.5)
    hl, hr = I.w(-2.5, 2.2, -1.0), I.w(2.5, 2.2, -1.0)
    G.bras(hl, hr, cuff=gold[1])
    idle(m)
    a = m.anim("playing", 1.7)
    a.arm("droit", presses(G, a, I, hr, (0.06, 0.2, 0.34, 0.48, 0.62, 0.76), travel=1.3, depth=0.5))
    a.arm("gauche", presses(G, a, I, hl, (0.25, 0.75), travel=-0.4, depth=0.5))
    a.set("tete", "rotation", lambda p: (-3, 0, 2 * sw(p)))
    gestes_touches(m, I, hl, hr, (-3.5, -1.5, 1.5, 3.5), depth=0.5)
    return m



# ==================================================================================================== sortie
# Balancement de chaque musicien : (roulis du buste en degrés, rebond en unités, hochement de tête en degrés,
# torsion du buste en degrés, battement du pied en degrés). Instrument posé au sol : pas de roulis ni de torsion,
# les mains doivent rester sur l'instrument. Instrument à la bouche : pas de hochement.
GROOVE = {
    "trompettiste": (2, 0.3, 0, 0, 0), "guitariste": (3, 0.35, 4, 3, 0), "banjoiste": (4.5, 0.6, 5, 4, 0),
    "harpiste": (1.5, 0.1, 2, 0, 0), "contrebassiste": (0, 0.2, 4, 0, 7), "didgeridoo": (2, 0.3, 0, 0, 0),
    "grosse_caisse": (3, 0.5, 3, 0, 0), "caisse_claire": (2, 0.4, 3, 0, 0), "charleston": (0, 0.15, 5, 0, 0),
    "cloche": (4, 0.5, 5, 2, 0), "glockenspiel": (2, 0.25, 3, 0, 0), "carillonneur": (1, 0.1, 1.5, 0, 0),
    "xylophoniste": (0, 0.25, 5, 0, 6), "vibraphoniste": (0, 0.1, 3, 0, 5), "bit": (2.5, 0.3, 4, 2, 0),
    "pling": (2, 0.25, 3, 0, 0),
}


def balancement(a, roll, bob, nod, twist, tap):
    """Ajoute à une pose tenue le balancement en mesure : un aller-retour du buste et deux rebonds par boucle."""
    import math
    base = {(b, ch): a.value(b, ch, 0) for b in ("corps", "tete", "jambe_gauche") for ch in ("rotation", "position")}

    def plus(bone, ch, f):
        b0 = base[(bone, ch)]
        a.set(bone, ch, lambda p: tuple(x + y for x, y in zip(b0, f(p))))
    dip = lambda p: 0.5 - 0.5 * math.cos(4 * math.pi * p)        # 0 sur le temps, 1 entre deux temps
    plus("corps", "rotation", lambda p: (0, twist * sw(p), roll * sw(p)))
    plus("corps", "position", lambda p: (0, -bob * dip(p), 0))
    plus("tete", "rotation", lambda p: (-nod * (1 - dip(p)) * 0.6 + nod * 0.3, 0, -0.6 * roll * sw(p, 1, -0.08)))
    if tap:
        plus("jambe_gauche", "rotation", lambda p: (tap * dip(p), 0, 0))


def main(ids):
    for i in ids:
        if i == "trompettiste":
            import trompettiste
            m = trompettiste.animate(trompettiste.build())
        else:
            m = BUILD[i]()
        if i == "trompettiste":      # une pression de pistons par note
            g, c = geste(m, "hit_0", 0.3)
            g.set("main_droite", "position", lambda p, c=c: (0, -0.6 * bump(p, 0, c, 0.85), 0))
        if i == "didgeridoo":        # un souffle par note
            g, c = geste(m, "hit_0", 0.6)
            g.set("corps", "scale", lambda p, c=c: (1 + 0.03 * bump(p, 0, c, 0.95), 1, 1 + 0.05 * bump(p, 0, c, 0.95)))
        # ready : la posture de jeu, que le musicien tient pendant tout un morceau où il joue, en se balançant
        # en mesure (FluxMusic règle la vitesse : la boucle d'une seconde contient deux temps)
        ready = m.freeze("ready", next(x for x in m.anims if x.name == "playing"), length=1.0)
        balancement(ready, *GROOVE[i])
        out = os.path.join(OUT, i)
        m.write(out)
        ys = [max(e["from"][1], e["to"][1]) for e in m.elements]
        print(f"{i}: {len(m.elements)} volumes, atlas {m.size}, hauteur {max(ys):g}", getattr(m, "check", lambda: "")())
        if i == "trompettiste":
            import png_util
            for name, cols in CUIVRES.items():
                trompettiste.B = pal(*cols)
                v = trompettiste.build()
                v.pack()
                png_util.write(os.path.join(out, f"trompettiste_cuivre_{name}.png"), v.size, v.size, v.px)
        m.sheet(os.path.join(out, i + "_apercu.png"), views=((0, 4), (35, 18), (215, 18)), size=320)
        play = next(a for a in m.anims if a.name == "playing")
        m.strip(os.path.join(out, i + "_playing.png"), play, phases=(0, 0.15, 0.4, 0.65, 0.85), size=260)


CUIVRES = {"neuf": ("#8A4A2E", "#C2703F", "#E39A63", "#F3C08F"), "terni": ("#7A5236", "#A9764C", "#C99A6C", "#DDB88E"),
           "patine": ("#5E6A4E", "#86906A", "#A9B48A", "#C6CFA8"), "oxyde": ("#2F6B5E", "#4E9A86", "#78BFA8", "#A6DCC6")}


def collection(path, size=250, cols=8):
    """Tous les musiciens côte à côte, même échelle (cadre commun), vus de trois quarts."""
    import apercu
    import png_util
    import trompettiste
    ids = ["trompettiste"] + list(BUILD)
    frame = [[-11, 0, -14], [11, 0, 5], [-11, 35, -14], [11, 35, 5], [-11, 35, 5], [11, 35, -14], [-11, 0, 5], [11, 0, -14]]
    cells = []
    for i in ids:
        m = trompettiste.animate(trompettiste.build()) if i == "trompettiste" else BUILD[i]()
        m.pack()
        cells.append(apercu.render(m.posed(), m.px, 28, 14, size=size, frame_points=frame))
    blank = [[cells[0][0][0]] * size for _ in range(size)]
    rows = []
    for k in range(0, len(cells), cols):
        line = cells[k:k + cols] + [blank] * (cols - len(cells[k:k + cols]))
        rows += [sum((c[y] for c in line), []) for y in range(size)]
    png_util.write(path, size * cols, len(rows), rows)


def planche(ids, path, size=230):
    """Contrôle groupé : une ligne par personnage (face, trois quarts, dos, deux instants de playing)."""
    import apercu
    import png_util
    rows = []
    for i in ids:
        if i == "trompettiste":
            import trompettiste
            m = trompettiste.animate(trompettiste.build())
        else:
            m = BUILD[i]()
        m.pack()
        play = next(a for a in m.anims if a.name == "playing")
        shots = [(None, 0, (0, 4)), (None, 0, (35, 18)), (None, 0, (215, 18)), (play, 0.2, (35, 18)), (play, 0.7, (-35, 18))]
        imgs = [apercu.render(m.posed(an, p), m.px, v[0], v[1], size=size) for an, p, v in shots]
        rows += [sum((im[y] for im in imgs), []) for y in range(size)]
        print(i, len(m.elements), getattr(m, "check", lambda: "")())
    png_util.write(path, size * 5, len(rows), rows)


if __name__ == "__main__":
    if sys.argv[1:2] == ["--collection"]:
        collection(sys.argv[2] if len(sys.argv) > 2 else os.path.join(OUT, "collection.png"))
        sys.exit()
    if sys.argv[1:2] == ["--planche"]:
        planche(sys.argv[3:], sys.argv[2])
        sys.exit()
    main(sys.argv[1:] or ["trompettiste"] + list(BUILD))

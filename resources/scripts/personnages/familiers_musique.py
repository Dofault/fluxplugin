#!/usr/bin/env python3
"""Familiers instruments (fête de la musique) : dix petites créatures dont le corps est un instrument.

Chaque modèle a les animations dont FluxPets et FluxMusic ont besoin :
  idle      pose de repos, immobile
  playing   boucle de jeu, propre à l'instrument
  walk      déplacement (dandinement au sol, glissé pour ceux qui flottent)
  happy     joie à l'adoption (révélation de FluxPets)
  hit_*     gestes d'une note (FluxMusic : contact à 0,15 s) : cloche hit_0, xylophone et batterie hit_l / hit_r

Conventions de voxel.py : la créature regarde vers -Z, sa droite est en +X, le sol en y = 0 (les familiers qui
flottent ont leur corps vers y = 8). Pas de bruit aléatoire dans les textures : chaque face est peinte par
fill_face puis retouchée (yeux, cordes, touches).

Usage : familiers_musique.py [id ...]   (défaut : tous) ; écrit assets/personnages/familiers_musique/<id>/
        familiers_musique.py --collection  refait aussi la planche d'ensemble
"""
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import voxel as V  # noqa: E402
from voxel import T, bump, rgb, sw  # noqa: E402

OUT = os.path.join(V.ROOT, "assets", "personnages", "familiers_musique")
BUILD = {}
CONTACT = 0.15


def familier(f):
    BUILD[f.__name__] = f
    return f


def P(base, light=None, dark=None, shine=None):
    """Palette ombre / base / lumière / éclat ; sans valeurs données, tirée de la base."""
    r = V.ramp(base)
    return [rgb(dark) if dark else r[0], rgb(base), rgb(light) if light else r[2], rgb(shine) if shine else r[3]]


def fill_face(face, g, p):
    """Peinture propre : dessus clair, dessous sombre, flancs à la base avec arête haute claire et basse sombre,
    dos et flanc gauche un peu plus sombres. Aucun pixel au hasard."""
    d, m, l, h = p
    H, W = len(g), len(g[0])
    if face == "up":
        base = l
    elif face == "down":
        base = d
    else:
        base = V.mix(m, d, 0.3) if face in ("south", "west") else m
    for y in range(H):
        for x in range(W):
            c = base
            if face not in ("up", "down") and H >= 3:
                if y == 0:
                    c = l if face not in ("south", "west") else m
                elif y == H - 1:
                    c = d
            g[y][x] = c


def put(g, pts, c):
    c = rgb(c) if isinstance(c, str) else c
    for x, y in pts:
        if 0 <= y < len(g) and 0 <= x < len(g[0]):
            g[y][x] = c


def clear(g):
    for row in g:
        for x in range(len(row)):
            row[x] = T


class Pet(V.Model):
    def part(self, bone, name, frm, to, p, paint=None, rot=None, origin=None):
        def paint_all(face, g):
            fill_face(face, g, p)
            if callable(paint):
                paint(face, g)
            elif paint and face in paint:
                paint[face](g)
        return self.box(bone, name, frm, to, p, paint=paint_all, rot=rot, origin=origin)


# ------------------------------------------------------------------------------------------- rotations utiles
def up_rot(d):
    """Rotation [x, 0, z] qui amène l'axe +Y sur la direction d."""
    return V.aim([0, 0, 0], [-d[0], -d[1], -d[2]])


def strike(pivot, rest, tip):
    """Rotation à ajouter à un os dont les cubes sont tournés de `rest` pour que son axe passe par `tip`."""
    d = [t - p for t, p in zip(tip, pivot)]
    R = V.mul(V.rot_matrix(*up_rot(d)), V.tr(V.rot_matrix(*rest)))
    return V.euler(R)


def scaled(v, k):
    return tuple(x * k for x in v)


def idle(m, length=2.0):
    m.anim("idle", length).set("root", "position", lambda p: (0, 0, 0))


def rock(m, a, body, legs, theta, half, lift_extra=lambda p: 0.0):
    """Le corps penche de theta(p) autour de son pivot, les pattes (enfants du corps) restent verticales et le
    corps monte de ce que la patte du côté qui descend s'enfonce : il bascule d'un pied sur l'autre."""
    a.set(body, "rotation", lambda p: (0, 0, theta(p)))
    a.set(body, "position", lambda p: (0, half * abs(math.sin(math.radians(theta(p)))) + lift_extra(p), 0))
    for leg in legs:
        prev = a.tracks.get(leg, {}).get("rotation")
        if prev:
            a.set(leg, "rotation", lambda p, f=prev: (f(p)[0], f(p)[1], f(p)[2] - theta(p)))
        else:
            a.set(leg, "rotation", lambda p: (0, 0, -theta(p)))


def waddle(m, body, legs, half, length=0.7, sway=6, swing=24, bob=0.3):
    a = m.anim("walk", length)
    for leg in legs:
        s = -1 if "_L" in leg else 1
        a.set(leg, "rotation", lambda p, s=s: (s * swing * sw(p), 0, 0))
    rock(m, a, body, legs, lambda p: sway * sw(p), half, lambda p: bob * abs(sw(p)))
    return a


def trot(m, body, legs, length=0.6, swing=22, bob=0.35):
    a = m.anim("walk", length)
    for leg in legs:
        s = 1 if leg in ("leg_FL", "leg_BR") else -1
        a.set(leg, "rotation", lambda p, s=s: (s * swing * sw(p), 0, 0))
    a.set(body, "position", lambda p: (0, bob * abs(sw(p)), 0))
    return a


def glide(m, body, length=1.6, bob=0.6, tilt=-6, roll=3):
    a = m.anim("walk", length)
    a.set(body, "position", lambda p: (0, bob * sw(p), 0))
    a.set(body, "rotation", lambda p: (tilt, 0, roll * sw(p, 1, 0.25)))
    return a


def hop(m, body, length=1.0, height=3.0, loop="once"):
    a = m.anim("happy", length, loop=loop)
    a.set(body, "position", lambda p: (0, height * bump(p, 0.1, 0.35, 0.6) + 0.8 * height * bump(p, 0.6, 0.75, 0.9), 0))
    return a


def spin(m, body, length=1.2, height=1.5):
    a = m.anim("happy", length, loop="once")
    ease = lambda p: 0.5 - 0.5 * math.cos(math.pi * min(1, p / 0.8))   # noqa: E731
    a.set(body, "rotation", lambda p: (0, 360 * ease(p), 0))
    a.set(body, "position", lambda p: (0, height * bump(p, 0.0, 0.4, 0.9), 0))
    return a


def gesture(m, name, length=0.4):
    return m.anim(name, length, loop="once"), CONTACT / length


EYE, SHINE = "#3B2E26", "#E9E3D6"


# ====================================================================================================== flûte
@familier
def flute():
    """Délicate, rêveuse : la flûte flotte à l'horizontale, l'embouchure à gauche, deux yeux tout près."""
    m = Pet("familier_flute")
    silver = P("#AAB5B7", "#C6CDCD", "#7E898D", "#DCE0DC")
    end = P("#8E999C", "#A9B2B3", "#687275")
    lip = P("#D0D5D1", "#E0E3DE", "#A8AFAD")
    m.bone("tube", (0, 8, 0))
    m.bone("tete", (-2, 8, 0), "tube")
    m.bone("pied", (2, 8, 0), "tube")

    def eyes(face, g):
        if face == "north":       # face avant de la tête (x -6.5..-2), colonne 0 = côté +X
            put(g, [(1, 1), (3, 1)], EYE)
            put(g, [(1, 0), (3, 0)], P("#AAB5B7")[0])   # paupières mi-closes : air rêveur
        if face == "up":
            put(g, [(x, 0) for x in range(len(g[0]))], "#D8DDD9")

    def holes(n):
        def f(face, g):
            if face == "up":
                W = len(g[0])
                put(g, [(int(W * (k + 1) / (n + 1)), 1) for k in range(n)], "#4A5256")
            if face == "north":
                put(g, [(x, 0) for x in range(1, len(g[0]) - 1, 2)], "#D8DDD9")   # reflet en pointillé
        return f
    m.part("tete", "tube_tete", (-6.5, 6.5, -1.5), (-2, 9.5, 1.5), silver, eyes)
    m.part("tete", "bouchon", (-7.5, 6.3, -1.7), (-6.5, 9.7, 1.7), end)
    m.part("tete", "embouchure", (-5.5, 9.5, -1), (-3.5, 10.5, 1), lip,
           {"up": lambda g: put(g, [(1, 1)], "#4A5256")})
    m.part("tube", "tube_corps", (-2, 6.5, -1.5), (2, 9.5, 1.5), silver, holes(1))
    m.part("pied", "tube_pied", (2, 6.5, -1.5), (6.5, 9.5, 1.5), silver, holes(2))
    m.part("pied", "pavillon", (6.5, 6.3, -1.7), (7.5, 9.7, 1.7), end,
           {"east": lambda g: put(g, [(x, y) for x in (1, 2) for y in (1, 2)], "#4A5256")})

    idle(m)
    a = m.anim("playing", 2.0)
    a.set("tete", "rotation", lambda p: (0, 0, -4 * sw(p)))
    a.set("tube", "rotation", lambda p: (0, 0, 1.2 * sw(p, 1, -0.12)))
    a.set("pied", "rotation", lambda p: (0, 0, 4 * sw(p, 1, -0.25)))
    glide(m, "tube")
    spin(m, "tube")
    return m


# ====================================================================================================== cloche
@familier
def cloche():
    """Timide, douce : clochette dorée sur deux pattes, le battant entre les pattes, joues roses."""
    m = Pet("familier_cloche")
    gold = P("#C7A45B", "#D8BC79", "#927344", "#E6CF98")
    rim = P("#D8BC79", "#E6CF98", "#A88A55")
    bronze, legc = P("#80613D"), P("#80634C")
    m.bone("cloche", (0, 2.5, 0))
    m.bone("battant", (0, 2.5, 0), "cloche")
    for s, x in (("L", -2.5), ("R", 2.5)):
        m.bone("leg_" + s, (x, 2.5, 0), "cloche")
        m.part("leg_" + s, "patte_" + s, (x - 1, 0, -1), (x + 1, 2.5, 1), legc)

    def face(face, g):
        if face == "north":       # 8 x 5
            put(g, [(2, 2), (5, 2), (2, 3), (5, 3)], "#493A2B")
            put(g, [(2, 2), (5, 2)], "#7A6346")                      # regard baissé : le haut de l'œil plus clair
            put(g, [(1, 4), (6, 4)], "#D9967E")                      # joues roses
            put(g, [(3, 4), (4, 4)], "#8A6B3E")
    m.part("cloche", "rebord", (-4.5, 2.5, -4), (4.5, 3.5, 4), rim)
    m.part("cloche", "bas", (-4, 3.5, -3.5), (4, 8.5, 3.5), gold, face)
    m.part("cloche", "haut", (-2.5, 8.5, -2.5), (2.5, 11.5, 2.5), gold,
           {"north": lambda g: put(g, [(3, 0), (3, 1), (4, 0)], "#EDD9A6")})   # reflet en haut à gauche
    m.part("cloche", "anse", (-1, 11.5, -0.5), (1, 12.5, 0.5), rim)
    m.part("battant", "tige", (-0.5, 1.3, -0.5), (0.5, 2.5, 0.5), bronze)
    m.part("battant", "boule", (-0.6, 0.3, -0.6), (0.6, 1.3, 0.6), bronze)

    legs = ["leg_L", "leg_R"]
    idle(m)
    a = m.anim("playing", 1.6)
    rock(m, a, "cloche", legs, lambda p: 12 * sw(p), 2.5)
    a.set("battant", "rotation", lambda p: (0, 0, -18 * sw(p, 1, -0.08)))
    g, c = gesture(m, "hit_0", 0.4)
    rock(m, g, "cloche", legs, lambda p: -10 * bump(p, 0, c * 0.8, 1), 2.5)
    g.set("battant", "rotation", lambda p: (0, 0, 28 * bump(p, 0, c, 0.85)))
    w = waddle(m, "cloche", legs, 2.5, length=0.8, sway=8, swing=20)
    w.set("battant", "rotation", lambda p: (0, 0, -10 * sw(p, 1, -0.1)))
    h = hop(m, "cloche", 1.0, 3)
    h.set("battant", "rotation", lambda p: (0, 0, 20 * sw(p, 3)))
    return m


# =================================================================================================== xylophone
BARS_X = [(-5, -3.5), (-3.3, -1.8), (-1.6, -0.1), (0.1, 1.6), (1.8, 3.3), (3.5, 5)]


@familier
def xylophone():
    """Joyeux, maladroit : un petit chien-xylophone, les lames de couleur sur le dos, deux maillets en antennes."""
    m = Pet("familier_xylophone")
    wood, legc, mallet = P("#9B7650"), P("#7E5E40"), P("#C4A078")
    m.bone("corps", (0, 3, 0))
    for n, (x, z) in (("leg_FL", (-3.5, -2.5)), ("leg_FR", (3.5, -2.5)), ("leg_BL", (-3.5, 2.5)), ("leg_BR", (3.5, 2.5))):
        m.bone(n, (x, 3, z))
        m.part(n, n, (x - 1, 0, z - 1), (x + 1, 3, z + 1), legc)

    def face(face, g):
        if face == "north":       # 10 x 4
            put(g, [(2, 1), (3, 1), (2, 2), (3, 2), (6, 1), (7, 1), (6, 2), (7, 2)], "#3A2A20")
            put(g, [(2, 1), (6, 1)], SHINE)
            put(g, [(4, 3), (5, 3)], "#5A3A28")
            put(g, [(1, 3), (8, 3)], "#C98A70")
    m.part("corps", "ventre", (-5, 3, -4), (5, 7, 4), wood, face)
    colours = ["#BA7770", "#C99A66", "#D4C080", "#8DAB8A", "#829EAD", "#A38CA8"]
    lengths = [6.5, 6, 5.5, 5, 4.5, 4]
    for k, ((x0, x1), col, ln) in enumerate(zip(BARS_X, colours, lengths)):
        m.part("corps", f"lame_{k}", (x0, 7, 0.5 - ln / 2), (x1, 8, 0.5 + ln / 2), P(col))
    rests = {}
    for s, x, oz in (("L", -3, 15), ("R", 3, -15)):
        pivot = (x, 7, -3.5)
        rest = (30, 0, oz)
        rests[s] = (pivot, rest)
        b = "maillet_" + s
        m.bone(b, pivot, "corps", cube_rot=rest)
        m.part(b, b + "_tige", (x - 0.5, 7, -4), (x + 0.5, 10, -3), legc)
        m.part(b, b + "_tete", (x - 1, 10, -4.5), (x + 1, 12, -2.5), mallet)
    # la tête du maillet (à 4 du pivot) vient se poser sur une lame de son côté
    hit = {s: strike(pv, rs, (math.copysign(2.45, pv[0]), 9.2, -0.2)) for s, (pv, rs) in rests.items()}
    lift = {s: strike(pv, rs, (pv[0] + (-1.2 if s == "L" else 1.2), pv[1] + 3.7, pv[2] + 1.3))
            for s, (pv, rs) in rests.items()}

    def stroke(s, a, b, c):
        return lambda p: tuple(h * bump(p, a, b, c) + l_ * bump(p, a - 0.15, a, b) for h, l_ in zip(hit[s], lift[s]))
    legs = ["leg_FL", "leg_FR", "leg_BL", "leg_BR"]
    idle(m)
    a = m.anim("playing", 1.4)
    a.set("maillet_L", "rotation", stroke("L", 0.12, 0.25, 0.42))
    a.set("maillet_R", "rotation", stroke("R", 0.62, 0.75, 0.92))
    a.set("corps", "position", lambda p: (0, 0.25 * (bump(p, 0.2, 0.27, 0.4) + bump(p, 0.7, 0.77, 0.9)), 0))
    for s in ("L", "R"):
        g, c = gesture(m, "hit_" + s.lower(), 0.35)
        g.set("maillet_" + s, "rotation", lambda p, s=s, c=c: tuple(h * bump(p, 0, c, 1) for h in hit[s]))
    w = trot(m, "corps", legs)
    for s, k in (("L", 1), ("R", -1)):
        w.set("maillet_" + s, "rotation", lambda p, k=k: (6 * sw(p, 2), 0, k * 6 * sw(p, 1)))
    h = hop(m, "corps", 1.0, 3)
    for s, k in (("L", 1), ("R", -1)):
        h.set("maillet_" + s, "rotation", lambda p, k=k: (0, 0, k * 18 * sw(p, 3)))
    return m


# ================================================================================================== vibraphone
@familier
def vibraphone():
    """Calme, rêveur : un vibraphone argenté perché sur ses quatre tubes, les yeux mi-clos."""
    m = Pet("familier_vibraphone")
    frame, tube, dark = P("#929FA4"), P("#9AAAB4", "#BCC8CE", "#6F7E87"), P("#5F6B71")
    shades = ["#AAB6B8", "#D0D5D0", "#7D8C93"]
    m.bone("cadre", (0, 4, 0))
    m.bone("lames", (0, 7, 0), "cadre")

    def shine(face, g):
        if face in ("north", "east"):
            put(g, [(0, y) for y in range(len(g))], "#C9D3D8")
    for n, (x, z) in (("leg_FL", (-4, -2)), ("leg_FR", (4, -2)), ("leg_BL", (-4, 2)), ("leg_BR", (4, 2))):
        m.bone(n, (x, 4, z))
        m.part(n, n, (x - 1, 0, z - 1), (x + 1, 4, z + 1), tube, shine)
    m.part("leg_BL", "traverse", (-3, 1.5, 1.5), (3, 2.5, 2.5), dark)

    def face(face, g):
        if face == "north":       # 11 x 3
            put(g, [(2, 1), (3, 1), (7, 1), (8, 1)], "#35434A")
            put(g, [(2, 2), (3, 2), (7, 2), (8, 2)], "#A4B0B4")   # paupières basses : regard paisible
    m.part("cadre", "cadre", (-5.5, 4, -3.5), (5.5, 7, 3.5), frame, face)
    lengths = [6, 5.6, 5.2, 4.8, 4.4, 4.0]
    for k, (ln) in enumerate(lengths):
        x0 = -5.5 + k * (1.6 + 0.28)
        m.part("lames", f"lame_{k}", (x0, 7, -ln / 2), (x0 + 1.6, 7.8, ln / 2), P(shades[k % 3]))
    legs = ["leg_FL", "leg_FR", "leg_BL", "leg_BR"]
    idle(m)
    a = m.anim("playing", 1.8)
    a.set("cadre", "position", lambda p: (0, 0.4 * bump(p, 0, 0.3, 0.7), 0))
    a.set("lames", "position", lambda p: (0, 0.12 * sw(p, 6) * (0.4 + bump(p, 0, 0.3, 1)), 0))
    for leg in legs:
        a.set(leg, "scale", lambda p: (1, 1 + 0.1 * bump(p, 0, 0.3, 0.7), 1))
    w = trot(m, "cadre", legs, length=0.8, swing=14, bob=0.25)
    w.set("lames", "position", lambda p: (0, 0.08 * sw(p, 4), 0))
    h = hop(m, "cadre", 1.0, 2.5)
    h.set("lames", "position", lambda p: (0, 0.2 * sw(p, 8) * bump(p, 0.3, 0.6, 1), 0))
    return m


# ====================================================================================================== harpe
@familier
def harpe():
    """Élégante, féerique : une petite harpe dorée qui vole sur deux ailes, trois cordes vibrantes."""
    m = Pet("familier_harpe")
    gold, col, neck = P("#C2A060"), P("#B89455"), P("#B08A4A")
    wing, wing2 = P("#E7D9B9", "#F2E8D0", "#C5AD83"), P("#C5AD83")
    m.bone("harpe", (0, 9, 0))
    m.bone("cordes", (0, 9, 0), "harpe")

    def eyes(face, g):
        if face == "north":       # socle 12 x 3 : yeux fermés en arc, sereins
            for x0 in (3, 7):
                put(g, [(x0, 1), (x0 + 1, 2), (x0 + 2, 1)], "#5A4026")
            put(g, [(1, 2), (10, 2)], "#D99A84")
    m.part("harpe", "socle", (-6, 2, -1.5), (6, 5, 1.5), gold, eyes)
    m.part("harpe", "colonne", (-6, 5, -1), (-4, 14, 1), col)
    m.part("harpe", "caisse", (3, 5, -1.5), (6, 13, 1.5), gold,
           {"north": lambda g: put(g, [(1, y) for y in range(1, 7)], "#D9BC80")})
    m.part("harpe", "col_g", (-6, 14, -1), (0, 15, 1), neck)
    m.part("harpe", "col_d", (0, 13, -1), (6, 14.5, 1), neck)

    def strings(face, g):
        clear(g)
        if face in ("north", "south"):
            for x in range(0, len(g[0]), 2):
                put(g, [(x, y) for y in range(len(g))], "#E4D6AF")
    m.part("cordes", "cordes", (-4, 5, -0.25), (3, 13, 0.25), wing, strings)
    for s, sx in (("L", -1), ("R", 1)):
        b = "aile_" + s
        m.bone(b, (sx * 6, 10.5, 1), "harpe")
        x0 = sx * 6
        m.part(b, b + "_haut", (x0, 9, 0.5), (x0 + sx * 2, 12, 1.5), wing)
        m.part(b, b + "_bas", (x0, 8, 0.5), (x0 + sx * 1.5, 9, 1.5), wing2)
    idle(m)
    a = m.anim("playing", 2.0)
    a.set("aile_L", "rotation", lambda p: (0, 0, 20 * sw(p)))
    a.set("aile_R", "rotation", lambda p: (0, 0, -20 * sw(p, 1, -0.04)))
    a.set("cordes", "position", lambda p: (0.07 * sw(p, 16), 0, 0))
    a.set("harpe", "rotation", lambda p: (0, 0, 2 * sw(p, 1, 0.25)))
    w = glide(m, "harpe", 1.2, 0.5, -6, 2)
    w.set("aile_L", "rotation", lambda p: (0, 0, 25 * sw(p, 2)))
    w.set("aile_R", "rotation", lambda p: (0, 0, -25 * sw(p, 2)))
    h = spin(m, "harpe")
    h.set("aile_L", "rotation", lambda p: (0, 0, 25 * sw(p, 4)))
    h.set("aile_R", "rotation", lambda p: (0, 0, -25 * sw(p, 4)))
    return m


# ===================================================================================================== guitare
@familier
def guitare():
    """Énergique, espiègle : guitare debout sur deux pattes, la tête du manche pour tête, une main qui gratte."""
    m = Pet("familier_guitare")
    honey = P("#A8754B", "#C39462", "#775037")
    neckc, headc, legc = P("#80583C"), P("#6E4A33"), P("#80634C")
    m.bone("caisse", (0, 2, 0))
    m.bone("tete", (0, 12, 0), "caisse")
    for s, x in (("L", -2), ("R", 2)):
        m.bone("leg_" + s, (x, 2, 0), "caisse")
        m.part("leg_" + s, "patte_" + s, (x - 1, 0, -1), (x + 1, 2, 1), legc)

    def lower(face, g):
        if face == "north":       # 9 x 4 : rosace, cordes, chevalet
            put(g, [(3, 0), (5, 0), (3, 1), (5, 1)], "#E2D8C3")
            put(g, [(4, 0), (4, 1)], "#3E2A1E")
            put(g, [(x, 2) for x in range(2, 7)], "#4E3424")
    m.part("caisse", "bas", (-4.5, 2, -2), (4.5, 6, 2), honey, lower)
    m.part("caisse", "haut", (-3.5, 6, -2), (3.5, 9, 2), honey,
           {"north": lambda g: put(g, [(2, y) for y in range(3)] + [(4, y) for y in range(3)], "#E2D8C3")})
    m.part("caisse", "manche", (-1, 9, -1), (1, 12, 1), neckc)

    def head(face, g):
        if face == "north":       # 5 x 3 : yeux et sourire en coin
            put(g, [(1, 1), (3, 1)], "#2E2018")
            put(g, [(1, 0)], "#4E3424")                               # sourcil levé : espiègle
            put(g, [(2, 2), (3, 2)], "#4E3424")
        if face in ("east", "west"):
            put(g, [(0, 1), (2, 1)], "#D9C27E")                       # chevilles
    m.part("tete", "tete", (-2.5, 12, -1.5), (2.5, 15, 1.5), headc, head)
    pivot = (4.5, 6, -2.5)
    m.bone("bras", pivot, "caisse")
    m.part("bras", "bras", (4.5, 4, -3), (5.5, 6, -2), neckc)
    m.part("bras", "main", (4.4, 2.4, -3.4), (6, 4, -2), legc)

    legs = ["leg_L", "leg_R"]
    idle(m)
    a = m.anim("playing", 1.6)
    strum = lambda p: -40 - 30 * (0.5 + 0.5 * math.cos(2 * math.pi * (2 * p) + 0.6 * math.sin(4 * math.pi * p)))  # noqa
    a.set("bras", "rotation", lambda p: (0, 0, strum(p)))
    a.set("caisse", "position", lambda p: (0, 0.35 * abs(sw(p, 1)), 0))
    a.set("tete", "rotation", lambda p: (0, 0, 5 * sw(p, 2)))
    w = waddle(m, "caisse", legs, 2, length=0.7, sway=5, swing=24)
    w.set("bras", "rotation", lambda p: (20 * sw(p), 0, 0))
    h = hop(m, "caisse", 1.0, 3)
    h.set("bras", "rotation", lambda p: (0, 0, 70 * bump(p, 0.05, 0.3, 0.9) + 15 * sw(p, 4) * bump(p, 0.2, 0.5, 0.8)))
    return m


# ======================================================================================================= banjo
@familier
def banjo():
    """Amusant, rond, bondissant : la caisse ronde est le visage, le chevalet fait le sourire."""
    m = Pet("familier_banjo")
    head = P("#D7C8A8", "#E6DBC3", "#B3A486")
    ring, neckc, peg, legc = P("#79573E"), P("#7A5338"), P("#553A28"), P("#80634C")
    RING, STR = "#79573E", "#9E9A92"
    m.bone("caisse", (0, 2, 0))
    for s, x in (("L", -2), ("R", 2)):
        m.bone("leg_" + s, (x, 2, 0), "caisse")
        m.part("leg_" + s, "patte_" + s, (x - 1, 0, -1), (x + 1, 2, 1), legc)

    def strip(edge_cols, strings, full=False):
        def f(face, g):
            if face == "north":
                W = len(g[0])
                if full:
                    put(g, [(x, 0) for x in range(W)], RING)
                put(g, [(x, 0) for x in edge_cols], RING)
                put(g, [(x, 0) for x in strings], STR)
            elif face in ("east", "west", "up", "down"):
                for row in g:
                    for x in range(len(row)):
                        row[x] = ring[1]
        return f

    def main(face, g):
        if face == "north":       # 10 x 5
            put(g, [(0, y) for y in range(5)] + [(9, y) for y in range(5)], RING)
            put(g, [(4, y) for y in range(3)] + [(5, y) for y in range(3)], STR)
            put(g, [(2, 1), (2, 2), (7, 1), (7, 2)], "#3A2A20")
            put(g, [(2, 1), (7, 1)], SHINE)
            put(g, [(3, 3), (4, 3), (5, 3), (6, 3)], "#6E4E35")      # chevalet en sourire
            put(g, [(2, 2), (7, 2)], "#3A2A20")
            put(g, [(4, 4), (5, 4)], "#553A28")
        elif face in ("east", "west"):
            for row in g:
                for x in range(len(row)):
                    row[x] = ring[1]
    m.part("caisse", "fond", (-3, 2, -2), (3, 3, 2), head, strip([], [], full=True))
    m.part("caisse", "bas", (-4, 3, -2), (4, 4, 2), head, strip([0, 1, 6, 7], []))
    m.part("caisse", "milieu", (-5, 4, -2), (5, 9, 2), head, main)
    m.part("caisse", "haut", (-4, 9, -2), (4, 10, 2), head, strip([0, 1, 6, 7], [3, 4]))
    m.part("caisse", "sommet", (-3, 10, -2), (3, 11, 2), head, strip([], [2, 3], full=True))
    m.bone("manche", (0, 11, 0), "caisse", cube_rot=(0, 0, -12))
    m.part("manche", "manche", (-1, 11, -1), (1, 14, 1), neckc)
    m.part("manche", "chevillier", (-2, 14, -1), (2, 16, 1), peg,
           {"north": lambda g: put(g, [(0, 0), (3, 0), (0, 1), (3, 1)], "#D9C27E")})

    legs = ["leg_L", "leg_R"]
    idle(m)
    a = m.anim("playing", 1.5)
    jump = lambda p: 1.5 * max(0.0, math.sin(2 * math.pi * p))  # noqa: E731
    a.set("caisse", "position", lambda p: (0, jump(p), 0))
    a.set("manche", "rotation", lambda p: (-10 * sw(p, 1, -0.2), 0, 0))
    for leg in legs:
        a.set(leg, "rotation", lambda p: (-15 * max(0.0, math.sin(2 * math.pi * p)), 0, 0))
    w = waddle(m, "caisse", legs, 2, length=0.6, sway=7, swing=26, bob=0.6)
    w.set("manche", "rotation", lambda p: (0, 0, 8 * sw(p, 1, -0.15)))
    h = hop(m, "caisse", 1.0, 3.5)
    h.set("manche", "rotation", lambda p: (0, 0, 15 * sw(p, 3)))
    return m


# ================================================================================================= contrebasse
@familier
def contrebasse():
    """Imposante, calme, protectrice : la plus grande, gros sourcils, se balance lentement."""
    m = Pet("familier_contrebasse")
    wood = P("#85553E", "#9E6A4F", "#5E3B2B")
    neckc, headc, legc = P("#624431"), P("#74503A"), P("#5A3E2E")
    m.bone("corps", (0, 3, 0))
    m.bone("tete", (0, 15, 0), "corps", cube_rot=(-10, 0, 0))
    for s, x in (("L", -3), ("R", 3)):
        m.bone("leg_" + s, (x, 3, 0), "corps")
        m.part("leg_" + s, "patte_" + s, (x - 1.5, 0, -1.5), (x + 1.5, 3, 1.5), legc)

    def lower(face, g):
        if face == "north":       # 12 x 4 : ouïes et chevalet
            put(g, [(2, 0), (2, 1), (9, 0), (9, 1)], "#3A241A")
            put(g, [(x, 2) for x in range(4, 8)], "#C9A57A")
        if face in ("north", "east", "west"):
            put(g, [(x, y) for y in range(len(g)) for x in range(len(g[0])) if (x * 3 + y) % 11 == 0
                    and g[y][x] == wood[1]], "#7A4E39")                 # veines verticales discrètes
    m.part("corps", "bas", (-6, 3, -2.5), (6, 7, 2.5), wood, lower)
    m.part("corps", "taille", (-4.5, 7, -2.5), (4.5, 9, 2.5), wood)

    def upper(face, g):
        if face == "north":       # 10 x 3
            put(g, [(2, 1), (3, 1), (2, 2), (3, 2), (6, 1), (7, 1), (6, 2), (7, 2)], "#2E1F18")
            put(g, [(3, 1), (7, 1)], SHINE)
    m.part("corps", "haut", (-5, 9, -2.5), (5, 12, 2.5), wood, upper)
    m.part("corps", "sourcil_L", (-3.6, 11.2, -2.9), (-0.9, 11.9, -2.5), P("#49352E"),
           rot=(0, 0, -4), origin=(-2.25, 11.55, -2.7))
    m.part("corps", "sourcil_R", (0.9, 11.2, -2.9), (3.6, 11.9, -2.5), P("#49352E"),
           rot=(0, 0, 4), origin=(2.25, 11.55, -2.7))
    m.part("corps", "manche", (-1.5, 12, -1.5), (1.5, 15, 1.5), neckc,
           {"north": lambda g: put(g, [(0, y) for y in range(3)] + [(2, y) for y in range(3)], "#B7AA95")})
    m.part("tete", "volute", (-2.5, 15, -1.5), (2.5, 18, 1.5), headc,
           {"east": lambda g: put(g, [(1, 1)], "#3A241A"), "west": lambda g: put(g, [(1, 1)], "#3A241A")})
    m.bone("cordes", (0, 6, 0), "corps")

    def strings(face, g):
        clear(g)
        if face == "north":
            for x in (0, 2, 4):
                put(g, [(x, y) for y in range(len(g))], "#B7AA95")
    m.part("cordes", "cordes", (-2.5, 4, -2.75), (2.5, 9, -2.55), wood, strings)

    legs = ["leg_L", "leg_R"]
    idle(m)
    a = m.anim("playing", 2.5)
    rock(m, a, "corps", legs, lambda p: 5 * sw(p), 3)
    a.set("tete", "rotation", lambda p: (0, 0, 2 * sw(p, 1, -0.1)))
    a.set("cordes", "position", lambda p: (0.07 * sw(p, 10), 0, 0))
    w = waddle(m, "corps", legs, 3, length=1.0, sway=4, swing=18, bob=0.2)
    w.set("tete", "rotation", lambda p: (2 * sw(p, 2), 0, 0))
    h = hop(m, "corps", 1.4, 2)
    h.set("tete", "rotation", lambda p: (-8 * bump(p, 0.1, 0.35, 0.6), 0, 0))
    return m


# ======================================================================================================= pling
KEYS = [1.0, 0.7, 0.4, 0.4, 0.7, 1.0]


@familier
def pling():
    """Magique, mystérieux, joyeux : un petit clavier qui flotte, ses touches dessinent un sourire."""
    m = Pet("familier_pling")
    body, ends = P("#63536D", "#7A6A85", "#4A3E52"), P("#897895")
    ivory, prune, crystal = P("#E7DCC7", "#F3ECDD", "#BFB39C"), P("#64516C"), P("#7FCFC4", "#A9E3DA", "#5AA79C")
    m.bone("clavier", (0, 7, 0))
    m.part("clavier", "corps", (-6.5, 4.5, -3), (6.5, 9.5, 3), body)

    def panel(face, g):
        if face == "north":       # 13 x 3
            put(g, [(4, 1), (5, 1), (8, 1), (9, 1)], "#34455A")
            put(g, [(5, 1), (9, 1)], "#9FB7D0")
            put(g, [(6, 0)], "#A9E3DA")                               # petite étoile au milieu
    m.part("clavier", "pupitre", (-6.5, 9.5, 0.5), (6.5, 12.5, 3), body, panel)
    for s, x in (("L", -7), ("R", 7)):
        m.part("clavier", "joue_" + s, (x - 0.5, 5, -2.5), (x + 0.5, 11, 2.5), ends)
    tops = []
    for k, hgt in enumerate(KEYS):
        x0 = -6 + k * 2.04
        b = f"key_{k}"
        m.bone(b, (x0 + 0.9, 9.5, -1.25), "clavier")
        m.part(b, b, (x0, 9.5, -3), (x0 + 1.8, 9.5 + hgt, 0.5), ivory)
        tops.append(9.5 + hgt)
    for k in range(5):
        xg = -6 + k * 2.04 + 1.92
        y0 = max(tops[k], tops[k + 1])
        m.part("clavier", f"diese_{k}", (xg - 0.5, y0, -1), (xg + 0.5, y0 + 0.4, 0.5), prune)
    m.part("clavier", "cristal", (-0.75, 12.6, 1), (0.75, 14.1, 2.5), crystal, rot=(0, 45, 0),
           origin=(0, 13.35, 1.75))
    order = [2, 0, 4, 3, 1, 5]
    idle(m)
    a = m.anim("playing", 1.7)
    for i, k in enumerate(order):
        t = i / 6
        a.set(f"key_{k}", "position", lambda p, t=t: (0, -0.45 * bump(p, t, t + 0.05, t + 0.16), 0))
    glide(m, "clavier", 1.4, 0.5, -5, 2)
    h = spin(m, "clavier")
    for k in range(6):
        h.set(f"key_{k}", "position", lambda p, k=k: (0, -0.4 * bump(p, 0.2 + k * 0.08, 0.26 + k * 0.08, 0.36 + k * 0.08), 0))
    return m


# ===================================================================================================== batterie
@familier
def batterie():
    """Énergique, désordonnée : grosse caisse pour corps, caisse claire en chapeau, charleston en oreille.
    La baguette gauche frappe le charleston, la droite la caisse claire, la grosse caisse se sent dans le corps."""
    m = Pet("familier_batterie")
    shell, head = P("#855A45", "#9C6C55", "#5E3E30"), P("#D8CBB0", "#E8DEC8", "#B3A68C")
    bronze, legc, stick, metal = P("#B18B55", "#CBA772", "#86683F"), P("#5A3E2E"), P("#D9C29A"), P("#6E5A4A")
    m.bone("grosse_caisse", (0, 2, 0))
    for s, x in (("L", -3), ("R", 3)):
        m.bone("leg_" + s, (x, 2, 0), "grosse_caisse")
        m.part("leg_" + s, "patte_" + s, (x - 1, 0, -1), (x + 1, 2, 1), legc)

    def front(face, g):
        if face == "north":       # 11 x 9 : peau claire, cercle sombre, visage
            H, W = len(g), len(g[0])
            for y in range(H):
                for x in range(W):
                    edge = x in (0, W - 1) or y in (0, H - 1)
                    g[y][x] = shell[0] if edge else (head[2] if y == 1 else head[1])
            put(g, [(3, 3), (4, 3), (3, 4), (4, 4), (7, 3), (8, 3), (7, 4), (8, 4)], "#2E1F18")
            put(g, [(3, 3), (7, 3)], SHINE)
            put(g, [(4, 6), (5, 6), (6, 6), (5, 7)], "#5A3A2A")         # bouche ouverte, énergique
    m.part("grosse_caisse", "corps", (-5.5, 2, -4), (5.5, 11, 4), shell, front)

    def snare(face, g):
        if face in ("north", "south", "east", "west"):
            H = len(g)
            put(g, [(x, y) for y in (0, H - 1) for x in range(len(g[0]))], "#6E4A33")
    m.part("grosse_caisse", "caisse_claire", (-4, 11, -3.5), (4, 14, 3.5), head, snare)
    m.part("grosse_caisse", "bras_charleston", (-6.5, 8, -0.5), (-5.5, 9, 0.5), metal)
    m.part("grosse_caisse", "pied_charleston", (-7.5, 8, -0.5), (-6.5, 12.5, 0.5), metal)
    m.part("grosse_caisse", "cymbale_bas", (-8.75, 12.5, -1.75), (-5.25, 13, 1.75), bronze)
    m.bone("charleston", (-7, 13.5, 0), "grosse_caisse")
    m.part("charleston", "cymbale_haut", (-8.75, 13.25, -1.75), (-5.25, 13.75, 1.75), bronze)
    sticks = {}
    for s, x, oz in (("L", -2.5, 15), ("R", 2.5, -15)):
        pivot, rest, b = (x, 14, 2), (-15, 0, oz), "baguette_" + s
        sticks[s] = (pivot, rest)
        m.bone(b, pivot, "grosse_caisse", cube_rot=rest)
        m.part(b, b, (x - 0.5, 14, 1.5), (x + 0.5, 18.5, 2.5), stick)
    hit = {"L": strike(*sticks["L"], (-7, 13.9, 0)), "R": strike(*sticks["R"], (2.5, 14.6, -2.3))}
    up = {"L": strike(*sticks["L"], (-4.5, 18, 1)), "R": strike(*sticks["R"], (3.5, 18, 3))}

    def swing(s, t, width=0.12):
        return lambda p: tuple(h * bump(p, t - width, t, t + width) + u * bump(p, t - 2 * width, t - width, t)
                               for h, u in zip(hit[s], up[s]))

    def both(fs):
        return lambda p: tuple(sum(f(p)[i] for f in fs) for i in range(3))
    legs = ["leg_L", "leg_R"]
    idle(m)
    a = m.anim("playing", 1.2)
    a.set("baguette_L", "rotation", both([swing("L", 0.25), swing("L", 0.75)]))
    a.set("baguette_R", "rotation", swing("R", 0.5))
    a.set("charleston", "position", lambda p: (0, -0.25 * (bump(p, 0.2, 0.25, 0.35) + bump(p, 0.7, 0.75, 0.85)), 0))
    kick = lambda p: bump(p, 0.0, 0.03, 0.12) + bump(p, 0.47, 0.5, 0.6)   # noqa: E731
    a.set("grosse_caisse", "scale", lambda p: (1 + 0.04 * kick(p), 1 - 0.03 * kick(p), 1 + 0.04 * kick(p)))
    for s, name in (("L", "hit_l"), ("R", "hit_r")):
        g, c = gesture(m, name, 0.35)
        g.set("baguette_" + s, "rotation", lambda p, s=s, c=c: tuple(h * bump(p, 0, c, 1) for h in hit[s]))
        g.set("grosse_caisse", "scale", lambda p, c=c: (1 + 0.03 * bump(p, c, c + 0.05, c + 0.3),
                                                        1 - 0.02 * bump(p, c, c + 0.05, c + 0.3), 1))
        if s == "L":
            g.set("charleston", "position", lambda p, c=c: (0, -0.25 * bump(p, c - 0.05, c, c + 0.25), 0))
    w = waddle(m, "grosse_caisse", legs, 3, length=0.6, sway=5, swing=24)
    w.set("baguette_L", "rotation", lambda p: (12 * sw(p), 0, 0))
    w.set("baguette_R", "rotation", lambda p: (-12 * sw(p), 0, 0))
    h = hop(m, "grosse_caisse", 1.0, 3)
    h.set("baguette_L", "rotation", lambda p: (0, 0, 30 * sw(p, 3)))
    h.set("baguette_R", "rotation", lambda p: (0, 0, -30 * sw(p, 3)))
    return m


# ===================================================================================================== sortie
def count(m):
    return len(m.elements)


def size(m):
    lo = [min(min(e["from"][i], e["to"][i]) for e in m.elements) for i in range(3)]
    hi = [max(max(e["from"][i], e["to"][i]) for e in m.elements) for i in range(3)]
    return [round(h - l, 2) for l, h in zip(lo, hi)]


def main(args):
    collection = "--collection" in args
    ids = [a for a in args if not a.startswith("--")] or list(BUILD)
    for i in ids:
        m = BUILD[i]()
        out = os.path.join(OUT, i)
        m.write(out)
        m.sheet(os.path.join(out, i + "_apercu.png"))
        anims = {a.name: a for a in m.anims}
        m.strip(os.path.join(out, i + "_playing.png"), anims["playing"])
        print(f"{i:12} {count(m):3} cubes  {size(m)}  texture {m.size}px  "
              f"animations {', '.join(a.name for a in m.anims)}")
    if collection:
        import apercu
        import png_util
        tiles = []
        for i in BUILD:
            m = BUILD[i]()
            m.pack()
            tiles.append(apercu.render(m.posed(), m.px, 30, 14, size=220))
        rows = [tiles[k:k + 5] for k in range(0, len(tiles), 5)]
        px = []
        for row in rows:
            for y in range(220):
                px.append(sum((t[y] for t in row), []))
        png_util.write(os.path.join(OUT, "collection.png"), 220 * 5, 220 * len(rows), px)


if __name__ == "__main__":
    main(sys.argv[1:])

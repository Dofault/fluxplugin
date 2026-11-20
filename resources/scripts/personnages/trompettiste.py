#!/usr/bin/env python3
"""Trompettiste de fanfare : modèle d'origine (26 volumes), inchangé, et ses animations idle / playing."""
import math

from voxel import Model, T, aim, bump, dist, euler, fill, key, mul, rgb, rot_matrix, stamp, sw, tr  # noqa: F401

NAME = "trompettiste"

# palettes : ombre, principale, lumière, (éclat)
J = [rgb(c) for c in ("#263F46", "#365D63", "#527A78", "#73918A")]   # veste bleu pétrole
I = [rgb(c) for c in ("#B9A994", "#E2D7BE", "#F1E8D5")]              # chemise ivoire
P = [rgb(c) for c in ("#49352F", "#65483B", "#85634B")]              # pantalon
L = [rgb(c) for c in ("#49312B", "#79513A", "#9B704E")]              # cuir
LD = [rgb(c) for c in ("#3A2722", "#49312B", "#79513A", "#9B704E")]  # cuir sombre (chaussures, ceinture)
B = [rgb(c) for c in ("#89602E", "#C18A3E", "#E3B65F", "#F0D18B")]   # laiton
S = [rgb(c) for c in ("#B77F68", "#D69F83", "#E9B99B")]              # peau
H = [rgb(c) for c in ("#47312C", "#65463A", "#88614A")]              # cheveux
EYE_W, IRIS, PUPIL = rgb("#E9DDC9"), rgb("#65463A"), rgb("#302A27")
CHEEK, MOUTH = rgb("#D98C7E"), rgb("#9A6451")




# ---------------------------------------------------------------------------------------------- personnage
# Trompette : repère local le long du tube (X), groupe incliné de 15° ; pavillon vers la droite du personnage.
# Les mains sont des poings fermés autour du tube, dans le groupe de la trompette (même inclinaison).
TR_PIVOT = (0.5, 14, -4)
HAND_L = (-4, -2)   # étendue le long du tube : entre l'embouchure et les pistons
HAND_R = (0, 2)     # sous les pistons, qui dépassent du poing


def tr_world(x, y=0):
    """Point du repère de la trompette -> monde."""
    c, s = math.cos(math.radians(15)), math.sin(math.radians(15))
    return (TR_PIVOT[0] + x * c - y * s, TR_PIVOT[1] + x * s + y * c, TR_PIVOT[2])


def build():
    m = Model(NAME)
    kS, kJ, kI, kL, kB, kH, kLD = key(S), key(J), key(I), key(L), key(B), key(H), key(LD)

    m.bone("jambe_gauche", (-2.5, 10, 0), cube_rot=(0, 4, 0))
    m.bone("jambe_droite", (2.5, 10, 0), cube_rot=(0, -4, 0))
    m.bone("corps", (0, 10, 0))
    m.bone("bandouliere", (-0.25, 16.75, -2), "corps", cube_rot=(0, 0, 38))
    m.bone("tete", (0, 21, 0), "corps", cube_rot=(0, -3, 0))
    for side, sx, hand in (("gauche", -1, HAND_L), ("droit", 1, HAND_R)):
        piv = (6 * sx, 20, 0)
        m.bone("bras_" + side, piv, "corps")
        m.bone("bras_" + side + "_pose", piv, "bras_" + side, rotation=aim(piv, tr_world(sum(hand) / 2)))
        m.arm_len[side] = dist(piv, tr_world(sum(hand) / 2))
    m.bone("trompette", TR_PIVOT, "corps", cube_rot=(0, 0, 15))
    m.bone("main_droite", TR_PIVOT, "trompette", cube_rot=(0, 0, 15))

    # --- jambes et chaussures
    def leg(f, g):
        if f != "up" and f != "down":
            g[0] = [P[0]] * len(g[0])
        if f == "north":
            stamp(g, ["l", "l", "l"], key(P), 0, 1)

    def shoe(side):
        def p(f, g):
            w = len(g[0])
            if f in ("north", "south", "east", "west"):
                g[-1] = [LD[0]] * w
            if f == "north":
                stamp(g, ["dmmd", "mddd"], kL)
            if f == "up":
                stamp(g, ["dmmd", "mlmm", "dmmd", "dddd", "dddd", "dddd"], kL)
            if f == "east":
                stamp(g, ["dddmmm", "dddddm"], kL)
            if f == "west":
                stamp(g, ["mmmddd", "mddddd"], kL)
        return p
    for side, sx in (("gauche", -1), ("droite", 1)):
        x0, x1 = (-4, -1) if sx < 0 else (1, 4)
        m.box("jambe_" + side, "jambe_" + side, (x0, 3, -2), (x1, 10, 2), P, leg, top=0)
        m.box("jambe_" + side, "chaussure_" + side, (min(x0, x0 + sx), 0, -4), (max(x1, x1 + sx), 3, 2), L,
              shoe(side), noise=0)

    # --- corps
    def torso(f, g):
        if f == "north":
            stamp(g, ["llliillm",
                      "lmliilmm",
                      "mmlddlmm",
                      "mmliilmd",
                      "mmlddlmd",
                      "mmmllmmd",
                      "mmmmlmmd",
                      "dmmmmmdd",
                      "ddmmmddd"], dict(kJ, i=I[1], b=B[2]) | {"d": J[0]})
            for y in (2, 4):  # ombre de la chemise sous le col et au bas de l'ouverture
                g[y][3] = g[y][4] = I[0]
            g[3][3], g[3][4] = I[2], I[1]
        if f == "south":
            for y in range(3, 9):
                g[y][4] = J[0]
    m.box("corps", "torse", (-4, 12, -2.5), (4, 21, 2.5), J, torso)

    def collar(f, g):
        if f == "north":
            stamp(g, ["lmSsml", " mddm "], dict(kI, S=S[1], s=S[0]))
        if f in ("east", "west"):
            g[1] = [I[0]] * len(g[1])
    m.box("corps", "col", (-3, 19, -3.5), (3, 21, 1.5), I, collar, top=2, bot=0, noise=0)

    def belt(f, g):
        if f == "north":
            stamp(g, ["mlmdBbmd", "ddddbDdd"], dict(kLD, B=B[2], b=B[1], D=B[0]))
    m.box("corps", "ceinture", (-4, 10, -2.5), (4, 12, 2.5), LD, belt, top=0, bot=1, noise=0.25)

    def shoulder(side):
        def p(f, g):
            if f == "up":  # liseré brun en U sur le bord extérieur
                for y in range(5):
                    for x in range(4):
                        out = x == (0 if side < 0 else 3)
                        if out or y in (0, 4):
                            g[y][x] = L[2] if (out and y in (1, 3)) else L[1]
        return p
    m.box("corps", "epaule_gauche", (-8, 18, -2.5), (-4, 22, 2.5), J, shoulder(-1))
    m.box("corps", "epaule_droite", (4, 18, -2.5), (8, 22, 2.5), J, shoulder(1))

    def bag(f, g):
        if f in ("north", "south", "east", "west"):
            w = len(g[0])
            g[0] = [L[2]] * w
            g[1] = [L[1]] * w
            g[2] = [L[0]] + [L[1]] * (w - 2) + [L[0]]   # ombre sous le rabat
            g[3] = [L[0]] * w
        if f == "west":
            stamp(g, ["mllm", "mmmm", "dBbd", "dmmd"], dict(kL, B=B[2], b=B[1]))
    m.box("corps", "sacoche", (-7, 8, -2), (-4, 12, 2), L, bag, noise=0)

    def lapel(f, g):
        if f == "north":
            stamp(g, ["lm", "lm", "mm", "md"], kI)
    m.box("corps", "revers", (1, 15, -3.5), (3, 19, -2.5), I, lapel, noise=0)

    def badge(f, g):
        if f == "north":  # croche : tête en bas à gauche du spectateur, hampe et crochet
            stamp(g, ["lNN", "hNm", "NNm"], dict(kB, N=rgb("#4A3320")))
    m.box("corps", "insigne", (-3, 16, -3.5), (0, 19, -2.5), B, badge, noise=0)

    def strap(f, g):
        if f == "north":
            for y in range(len(g)):
                g[y] = [L[2] if y % 2 == 0 else L[1], L[1]]
            g[-1] = [L[0], L[0]]
        elif f != "south":
            for row in g:
                row[:] = [L[0]] * len(row)
    m.box("bandouliere", "bandouliere", (-1.25, 11.75, -3), (0.75, 21.75, -1), L, strap, noise=0)

    # --- bras et mains
    def arm(f, g):
        w = len(g[0])
        if f not in ("up", "down"):
            g[-1] = [I[0] if f in ("west", "south") else I[1]] * w
            g[-2] = [J[0]] * w
    m.box("bras_gauche_pose", "bras_gauche", (-7.5, 13, -2), (-4.5, 20, 2), J, arm, bot=0)
    m.box("bras_droit_pose", "bras_droit", (4.5, 13, -2), (7.5, 20, 2), J, arm, bot=0)

    # --- tête
    def head(f, g):
        if f == "north":
            stamp(g, ["llllllll",
                      "llllllll",
                      "mllllllm",
                      "mWPmmPWm",
                      "mWImmIWm",
                      "mcmmmmcm",
                      "dmmoommd",
                      "ddmmmmdd"], dict(kS, W=EYE_W, P=PUPIL, I=IRIS, c=CHEEK, o=MOUTH, s=S[0]))
        if f == "south":
            for y in range(6):
                g[y] = [H[0]] * 8
            g[5][2] = g[5][5] = S[0]
    m.box("tete", "tete", (-4, 21, -3.5), (4, 29, 3.5), S, head, noise=0.05)

    def hair(f, g):
        w = len(g[0])
        if f == "north":
            stamp(g, [".........", "mlmdmmlmd", "mld m  dm", "         ", "         "], kH)
        if f == "east":   # colonnes de l'arrière vers l'avant
            stamp(g, ["......  ", "mdmm m  "], kH, 0, 3)
        if f == "west":   # colonnes de l'avant vers l'arrière
            stamp(g, ["  ......", "  m mmdm"], kH, 0, 3)
        if f == "south":
            stamp(g, ["mmdmmmdmm"], kH, 0, 4)
        if f == "down":
            g[6] = [T] * w
            g[7] = [T] * w
    m.box("tete", "cheveux", (-4.5, 24, -4), (4.5, 29, 4), H, hair, noise=0.15)

    def band(f, g):
        w = len(g[0])
        if f in ("north", "south", "east", "west"):
            g[1] = [I[0] if (x % 3) else I[1] for x in range(w)]
            if f in ("west", "south"):
                g[1] = [I[0]] * w
        if f == "north":  # petit ornement de laiton, côté gauche du personnage
            stamp(g, ["lm", "md"], kB, 7, 0)
        if f == "up":
            for row in g:
                row[:] = [J[1]] * w
    m.box("tete", "casquette", (-5, 28, -4.5), (5, 30, 4.5), J, band, bot=0, noise=0.05)

    def crown(f, g):
        if f == "up":
            for y in range(7):
                for x in range(8):
                    edge = x in (0, 7) or y in (0, 6)
                    g[y][x] = J[1] if edge else J[2]
            g[2][2] = g[2][3] = g[3][2] = J[3]
            g[5][5] = g[5][6] = g[4][6] = J[1]
    m.box("tete", "casquette_dessus", (-4, 30, -3.5), (4, 32, 3.5), J, crown, bot=1)

    def visor(f, g):
        if f == "up":
            stamp(g, ["mllmmd", "mmmmdd"], kJ)
        if f == "north":
            stamp(g, ["mmmmdd"], kJ)
        if f == "down":
            for row in g:
                row[:] = [J[0]] * len(row)
    m.box("tete", "visiere", (-3, 27, -6), (3, 28, -4), J, visor, top=0, bot=0, noise=0)

    # --- trompette (coordonnées avant rotation du groupe)
    px, py, pz = TR_PIVOT

    def trb(x0, y0, z0, x1, y1, z1):
        return (px + x0, py + y0, pz + z0), (px + x1, py + y1, pz + z1)

    def tube(f, g):
        if f == "up":
            stamp(g, ["llhhlll", "mllllmm"], kB)
        if f in ("north", "south"):
            stamp(g, ["mmlmlmm", "ddmmmdd"] if f == "north" else ["mmmmmmm", "ddddddd"], kB)
    m.box("trompette", "tube", *trb(-4, -1, -1, 3, 1, 1), B, tube, noise=0)

    def bell(f, g):
        if f == "east":   # ouverture : bord clair, intérieur sombre
            stamp(g, ["lhl", "ldl", "ldl", "mlm"], dict(kB, d=B[0]))
            g[1][1] = rgb("#6E4A22")
        if f == "north":
            stamp(g, ["hlmm", "lmmm", "lmmd", "mddd"], kB)
        if f == "south":
            stamp(g, ["mmml", "mmml", "dmmm", "dddm"], kB)
        if f == "up":
            stamp(g, ["mmlh", "mllh", "mmml"], kB)
        if f == "west":
            for row in g:
                row[:] = [B[0]] * len(row)
    m.box("trompette", "pavillon", *trb(3, -2, -1.5, 7, 2, 1.5), B, bell, noise=0)

    def valves(f, g):
        if f in ("up", "north"):   # trois pistons : capuchons clairs, creux sombres entre eux
            stamp(g, ["hdhdh", "mdmdm"], kB)
        if f == "south":
            stamp(g, ["ldldl", "mdmdm"], kB)
    m.box("trompette", "pistons", *trb(-2, 1, -1, 3, 3, 1), B, valves, noise=0)

    def fist(f, g):
        if f in ("north", "south"):
            stamp(g, ["lm", "mm", "md"] if f == "north" else ["mm", "md", "dd"], kS)
        if f == "up":
            stamp(g, ["lm", "ll", "mm"], kS)
    for name, (x0, x1) in (("main_gauche", HAND_L), ("main_droite", HAND_R)):
        m.box(name if name == "main_droite" else "trompette", name, *trb(x0, -1.5, -1.5, x1, 1.5, 1.5), S, fist, noise=0)

    def mouth(f, g):
        stamp(g, ["hl", "lm"] if f in ("up", "north", "west") else ["lm", "md"], kB)
    m.box("trompette", "embouchure", *trb(-6, -1, -1, -4, 1, 1), B, mouth, noise=0)
    return m




def animate(m):
    m.anim("idle", 2.0).set("root", "position", lambda p: (0, 0, 0))
    a = m.anim("playing", 1.8)
    # Trompette levée, pavillon vers l'avant, embouchure aux lèvres. Les cubes portent déjà Rz(15).
    final = mul(rot_matrix(6, 0, 0), rot_matrix(0, 90, 0))
    r = euler(mul(final, rot_matrix(0, 0, -15)))
    lips = (0, 22.6, -4.3)
    end = apply_(final, (-6, 0, 0))
    pos = tuple(lips[i] - TR_PIVOT[i] - end[i] for i in range(3))
    a.set("trompette", "rotation", lambda p: r)
    a.set("trompette", "position", lambda p: pos)
    # Trois pressions distinctes de la main droite sur les pistons (elle glisse d'un piston à l'autre).
    def fingers(p):
        d = [bump(p, c - 0.09, c, c + 0.12) for c in (0.14, 0.47, 0.80)]
        return (-0.7 * d[0] + 0.7 * d[2], -0.5 * max(d), 0)
    a.set("main_droite", "position", fingers)
    a.arm("gauche", lambda p: a.at("trompette", tr_world(sum(HAND_L) / 2), p))
    a.arm("droit", lambda p: a.at("main_droite", tr_world(sum(HAND_R) / 2), p))
    return m


def apply_(M, v):
    return [sum(M[i][k] * v[k] for k in range(3)) for i in range(3)]

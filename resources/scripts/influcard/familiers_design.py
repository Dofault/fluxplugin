"""Les familiers InfluCard, sculptés un par un (Atelier).

Repère Blockbench : y vers le haut, le familier regarde le nord (-z), pieds en y = 0.
Os : « familier » (racine), « h_head » (tête), puis membres par rôle pour les animations.
Chaque familier doit se reconnaître par sa silhouette et par un objet de son métier.
"""
import math

from atelier import Atelier

DESIGNS = {}


def design(fn):
    DESIGNS[fn.__name__] = fn
    return fn


# ---------------------------------------------------------------- aides

def base(a, y=0):
    a.bone("familier", [0, y, 0])
    a.role("root", "familier")


def head(a, origin, parent="familier"):
    a.bone("h_head", origin, parent)
    a.role("head", "h_head")


def crown(a, origin, parent="h_head"):
    a.bone("crown", origin, parent)
    a.role("crown", "crown")


def limb(a, name, origin, role, parent="familier"):
    a.bone(name, origin, parent)
    a.role(role, name)
    return name


def legs(a, mat, xs=(-1.5, 1.5), h=2, w=1.5, z=0):
    for side, x in zip(("L", "R"), xs):
        n = limb(a, "leg_" + side, [x, h, z], "legs")
        a.box(n, n, [x - w / 2, 0, z - w / 2], [x + w / 2, h, z + w / 2], mat)


def arms(a, mat, y, half_w, length=2, thick=1.5, parent="h_head", drop=0):
    for side, sx in (("L", -1), ("R", 1)):
        n = limb(a, "arm_" + side, [sx * half_w, y, 0], "arms", parent)
        x0, x1 = (sx * half_w - length, sx * half_w) if sx < 0 else (sx * half_w, sx * half_w + length)
        a.box(n, n, [x0, y - thick / 2 - drop, -thick / 2], [x1, y + thick / 2 - drop, thick / 2], mat)


def pt(o, v, rot):
    """Bout d'un segment : o + R(rot) * v (rotation Blockbench, ordre ZYX), pour y poser un embout."""
    x, y, z = (math.radians(r) for r in rot)
    vx, vy, vz = v
    vy, vz = vy * math.cos(x) - vz * math.sin(x), vy * math.sin(x) + vz * math.cos(x)
    vx, vz = vx * math.cos(y) + vz * math.sin(y), -vx * math.sin(y) + vz * math.cos(y)
    vx, vy = vx * math.cos(z) - vy * math.sin(z), vx * math.sin(z) + vy * math.cos(z)
    return [o[0] + vx, o[1] + vy, o[2] + vz]


def segment(a, bone, name, p0, p1, thick, mat):
    """Barre de section thick x thick allant du point p0 au point p1 (patte, antenne...), pivot en p0."""
    d = [p1[i] - p0[i] for i in range(3)]
    n = math.sqrt(sum(c * c for c in d))
    dx, dy, dz = (c / n for c in d)
    rx = math.degrees(math.asin(max(-1.0, min(1.0, dz))))
    rz = math.degrees(math.atan2(-dx, dy))
    h = thick / 2
    a.box(bone, name, [p0[0] - h, p0[1], p0[2] - h], [p0[0] + h, p0[1] + n, p0[2] + h], mat,
          rot=[round(rx, 2), 0, round(rz, 2)], origin=list(p0))


# ---------------------------------------------------------------- animations de marche sur mesure
# Conventions d'animation Blockbench : rotation x > 0 penche le haut vers l'avant (-z), une patte à x < 0 envoie
# son pied vers l'avant ; rotation z > 0 lève le bras droit, z < 0 le bras gauche. Contrôle : apercu_anim.py.

def _round(points):
    return [(round(t, 3), tuple(round(v, 3) for v in xyz)) for t, xyz in points]


def hop_tracks(roles, height=5.0, length=0.6, startle=False):
    """walk en bonds : élan penché, parabole, pattes repliées en l'air (pattes avant tendues vers l'avant,
    arrière vers l'arrière), bras levés, couronne et queue qui fouettent à l'atterrissage, couvercle / tête qui
    s'ouvre au sommet. startle : petit sursaut (immobile, puis bond vif et court)."""
    k = length / 0.6
    t_up, t_down = (0.3 * k, 0.5 * k) if startle else (0.08 * k, 0.5 * k)
    apex = (t_up + t_down) / 2
    steps = 6
    tracks = {}
    for b in roles.get("root", []):
        pos = [(0, (0, 0, 0))] + [(t_up + (t_down - t_up) * i / steps,
                                   (0, 4 * height * (i / steps) * (1 - i / steps), 0)) for i in range(steps + 1)]
        tracks[b] = {"position": _round(pos + [(length, (0, 0, 0))]),
                     # penché seulement en l'air (au sol, une bascule enfoncerait un bord dans le sol)
                     "rotation": _round([(0, (0, 0, 0)), (t_up, (0, 0, 0)), (t_up + 0.07 * k, (10, 0, 0)),
                                         (apex, (-2, 0, 0)), (t_down - 0.07 * k, (-6, 0, 0)), (t_down, (0, 0, 0)),
                                         (length, (0, 0, 0))])}
    for b in roles.get("legs", []):
        front = b.startswith("leg_F")
        back = b.startswith("leg_B")
        air = 35 if back else -35
        # pattes droites tant qu'elles touchent le sol, repliées seulement en l'air
        tracks[b] = {"rotation": _round([(0, (0, 0, 0)), (t_up, (0, 0, 0)), (t_up + 0.12 * k, (air, 0, 0)),
                                         (t_down - 0.1 * k, (air * (0.9 if front or back else 0.85), 0, 0)),
                                         (t_down, (0, 0, 0)), (length, (0, 0, 0))])}
    for b in roles.get("arms", []):
        s = -1 if "_L" in b else 1
        tracks[b] = {"rotation": _round([(0, (0, 0, 0)), (t_up, (0, 0, 0)), (apex, (0, 0, s * 45)),
                                         (t_down, (0, 0, s * 10)), (length, (0, 0, 0))])}
    for b in roles.get("crown", []) + roles.get("tail", []):
        tracks[b] = {"rotation": _round([(0, (0, 0, 0)), (t_up + 0.04 * k, (0, 0, 0)), (apex, (-18, 0, 0)),
                                         (t_down - 0.03 * k, (12, 0, 0)), (t_down + 0.05 * k, (-6, 0, 0)),
                                         (length, (0, 0, 0))])}
    for b in roles.get("head", []):
        if b in tracks:
            continue
        lid = b == "couvercle"
        tracks[b] = {"rotation": _round([(0, (0, 0, 0)), (t_up, (0, 0, 0)), (apex, (-30 if lid else -6, 0, 0)),
                                         (t_down, (0, 0, 0)), (length, (0, 0, 0))])}
    return tracks


def walk_hop(a, height=5.0, length=0.6, startle=False):
    """Le familier se déplace par bonds (familier au sol : à lister aussi dans familiers_sol.ANIMATIONS_SOL)."""
    a.anim_overrides = getattr(a, "anim_overrides", {})
    a.anim_overrides["walk"] = (length, "loop", hop_tracks(a.roles, height, length, startle))


def _leg_length(a, bone):
    """(hauteur, demi-profondeur du pied) d'une patte : de son pivot au bas de ses cubes (pieds à y = 0)."""
    uuids = set(a.bones[bone]["children"])
    els = [e for e in a.elements if e["uuid"] in uuids]
    low = min(min(e["from"][1], e["to"][1]) for e in els)
    feet = [e for e in els if min(e["from"][1], e["to"][1]) <= low + 0.01]
    oz = a.bones[bone]["origin"][2]
    depth = max(max(abs(e["from"][2] - oz), abs(e["to"][2] - oz)) for e in feet)
    return a.bones[bone]["origin"][1] - low, depth


def walk_stride(a, angle=34, length=0.8, arms=25, twist=0, lean=0, nod=5):
    """Grandes foulées : jambes opposées (bipède) ou en diagonale (quadrupède), bras en balancier ; le corps
    descend d'autant que les jambes inclinées raccourcissent, donc les pieds restent toujours au sol."""
    legs = a.roles.get("legs", [])
    leg, foot = max(_leg_length(a, b) for b in legs)
    n = 16

    def drop(theta):
        # le corps descend de ce que la jambe inclinée raccourcit, moins ce que le coin du pied descend :
        # le pied bascule sur sa pointe et aucun coin ne passe sous le sol
        r = math.radians(abs(theta))
        return leg * (1 - math.cos(r)) - foot * math.sin(r)

    def at(i):
        return length * i / n

    def phase(b):
        if len(legs) == 4:
            return 0 if b in ("leg_FL", "leg_BR") else math.pi
        return 0 if "_L" in b else math.pi
    tracks = {}
    for b in a.roles.get("root", []):
        tracks[b] = {
            "position": _round([(at(i), (0, -drop(angle * math.sin(2 * math.pi * i / n)), 0)) for i in range(n + 1)]),
            "rotation": _round([(at(i), (lean + 2 * math.sin(4 * math.pi * i / n), twist * math.sin(2 * math.pi * i / n), 0))
                                for i in range(n + 1)])}
    for b in legs:
        ph = phase(b)
        tracks[b] = {"rotation": _round([(at(i), (angle * math.sin(2 * math.pi * i / n + ph), 0, 0))
                                         for i in range(n + 1)])}
    for b in a.roles.get("arms", []):
        ph = 0 if "_R" in b else math.pi  # bras opposé à la jambe du même côté
        tracks[b] = {"rotation": _round([(at(i), (arms * math.sin(2 * math.pi * i / n + ph), 0, 0))
                                         for i in range(n + 1)])}
    for b in a.roles.get("head", []):
        if b not in tracks:
            tracks[b] = {"rotation": _round([(at(i), (nod * math.sin(4 * math.pi * i / n), 0, 0)) for i in range(n + 1)])}
    for b in a.roles.get("tail", []):
        tracks[b] = {"rotation": _round([(at(i), (0, 20 * math.sin(4 * math.pi * i / n), 0)) for i in range(n + 1)])}
    a.anim_overrides = getattr(a, "anim_overrides", {})
    a.anim_overrides["walk"] = (length, "loop", tracks)


def cube_at(a, bone, name, c, s, mat, rot=None):
    a.box(bone, name, [c[0] - s / 2, c[1] - s / 2, c[2] - s / 2], [c[0] + s / 2, c[1] + s / 2, c[2] + s / 2], mat,
          rot=rot)


def rond(a, bone, name, frm, to, mat, k=0.75, front=None, rot=None, origin=None):
    """Pavé aux arêtes adoucies : trois pavés croisés, chacun plein sur un axe et rentré de k sur les deux autres."""
    for ax in range(3):
        f, t = list(frm), list(to)
        for o in range(3):
            if o != ax:
                f[o] += k
                t[o] -= k
        a.box(bone, f"{name}_{ax}", f, t, mat, rot=rot, origin=origin, front=front if ax == 2 else None)


# ================================================================ MINEUR

@design
def pepite(a: Atelier):
    stone, gold = a.mat("#7D7D7D", "speck", "#F2C230"), a.mat("#F2C230", "glow")
    face = a.face("#8A8A8A", cheek="#F2C230", mouth="#3A2A10")
    base(a)
    head(a, [0, 5, 0])
    a.box("h_head", "bloc", [-3.5, 2, -3.5], [3.5, 9, 3.5], stone, front=face)
    crown(a, [0, 9, 0])
    a.box("crown", "pepite_1", [-2, 9, -1], [0.5, 10.5, 1.5], gold, rot=[0, 20, 0])
    a.box("crown", "pepite_2", [1, 9, -2], [2.5, 10, -0.5], gold)
    arms(a, a.mat("#6A6A6A"), 5.5, 3.5, length=1.5, thick=1.5)
    legs(a, a.mat("#5A5A5A"), xs=(-1.75, 1.75), h=2, w=2)


@design
def lanterne(a: Atelier):
    iron, glass = a.mat("#2E2E36"), a.mat("#FFB347", "glow")
    face = a.face("#FFC864", eye="#5A2A08", cheek="#FF7A2E", mouth="#5A2A08")
    base(a, 2)
    head(a, [0, 8, 0])
    a.box("h_head", "socle", [-3.5, 2, -3.5], [3.5, 3.5, 3.5], iron)
    # vitre éclairée, visage sur la face avant
    a.box("h_head", "vitre", [-2.6, 3.5, -2.6], [2.6, 9.5, 2.6], glass, front=face)
    for i, (x, z) in enumerate(((-3, -3), (2.25, -3), (-3, 2.25), (2.25, 2.25))):
        a.box("h_head", f"montant_{i}", [x, 3.5, z], [x + 0.75, 9.5, z + 0.75], iron)
    # toit en deux gradins et anneau de suspension
    a.box("h_head", "toit", [-3.5, 9.5, -3.5], [3.5, 10.5, 3.5], iron)
    a.box("h_head", "toit_haut", [-2, 10.5, -2], [2, 11.5, 2], iron)
    crown(a, [0, 11.5, 0])
    a.box("crown", "anneau_haut", [-1.25, 13.5, -0.3], [1.25, 14.1, 0.3], iron)
    a.box("crown", "anneau_g", [-1.25, 11.5, -0.3], [-0.65, 14.1, 0.3], iron)
    a.box("crown", "anneau_d", [0.65, 11.5, -0.3], [1.25, 14.1, 0.3], iron)
    legs(a, iron, xs=(-2, 2), h=2, w=1.25)


@design
def wagonnet(a: Atelier):
    iron, iron_in, rust = a.mat("#7A7A86"), a.mat("#3A3A44"), a.mat("#5A3A2A", "speck", "#7A4A30")
    rim, coal = a.mat("#9A9AA6"), a.mat("#2E2E34", "speck", "#4A4A52")
    dirt = a.mat("#5A4E46", "speck", "#3A3430")
    face = a.face("#7A7A86", eye="#15151A", cheek="#C0504A", mouth="#15151A")
    base(a)
    head(a, [0, 5, 0])
    # benne ouverte : fond, quatre parois (intérieur sombre), rebord, cerclages rouillés
    a.box("h_head", "fond", [-4, 2.5, -5.5], [4, 3.25, 5.5], iron_in)
    a.box("h_head", "paroi_avant", [-4.5, 2.5, -6], [4.5, 8, -5.25], iron, front=face)
    a.box("h_head", "paroi_arriere", [-4.5, 2.5, 5.25], [4.5, 8, 6], iron)
    for side, x in (("g", -4.5), ("d", 3.75)):
        a.box("h_head", "paroi_" + side, [x, 2.5, -5.25], [x + 0.75, 8, 5.25], iron)
        for zz in (-3, 3):
            a.box("h_head", f"cerclage_{side}_{zz}", [x - 0.1, 2.4, zz - 0.4], [x + 0.85, 8.1, zz + 0.4], rust)
    for nm, frm, to in (("rebord_av", [-5, 8, -6.5], [5, 8.75, -5.25]), ("rebord_ar", [-5, 8, 5.25], [5, 8.75, 6.5]),
                        ("rebord_g", [-5, 8, -5.25], [-3.75, 8.75, 5.25]), ("rebord_d", [3.75, 8, -5.25], [5, 8.75, 5.25])):
        a.box("h_head", nm, frm, to, rim)
    a.box("h_head", "cerclage_bas", [-4.6, 2.4, -6.1], [4.6, 3.1, 6.1], rust)
    # chargement : tas de pierre et de charbon qui dépasse du bord, minerais brillants piqués dedans
    crown(a, [0, 8, 0])
    a.box("crown", "tas", [-3.75, 3.25, -5.25], [3.75, 6, 5.25], coal)
    a.box("crown", "tas_haut", [-3, 6, -4], [2.5, 8.25, 3.75], dirt)
    a.box("crown", "tas_sommet", [-2, 8.25, -2.5], [1.5, 9.5, 2], coal)
    for i, (col, x, y, z, r) in enumerate((("#5ADCE8", -2, 8.75, -2.75, 1.5), ("#E03030", 1.75, 8.5, 1.25, 1.25),
                                          ("#F2C230", -1.25, 9.5, 1.75, 1.5), ("#3AE07A", 2.25, 7.75, -2.75, 1),
                                          ("#F2C230", 0, 10, -0.75, 1))):
        a.box("crown", f"minerai_{i}", [x - r / 2, y - r / 2, z - r / 2], [x + r / 2, y + r / 2, z + r / 2],
              a.mat(col, "glow"), rot=[0, 45, 0] if i % 2 else [35, 45, 0])
    # essieux et roues (rôle « legs » : elles tournent quand il roule)
    for zz in (-3.5, 3.5):
        a.box("familier", f"essieu_{zz}", [-5.25, 1.5, zz - 0.4], [5.25, 2.3, zz + 0.4], rust)
    for side, x in (("L", -5.25), ("R", 5.25)):
        for zz in (-3.5, 3.5):
            n = limb(a, f"leg_{side}{'A' if zz < 0 else 'B'}", [x, 2, zz], "legs")
            x0, x1 = (x - 0.6, x + 0.4) if x < 0 else (x - 0.4, x + 0.6)
            a.box(n, n, [x0, 0, zz - 1.25], [x1, 4, zz + 1.25], coal)
            a.box(n, n + "_b", [x0, 0.75, zz - 2], [x1, 3.25, zz + 2], coal)
            a.box(n, n + "_moyeu", [x0 - 0.15 if x < 0 else x0, 1.5, zz - 0.5], [x1 if x < 0 else x1 + 0.15, 2.5, zz + 0.5], rust)


@design
def dragon_gemmes(a: Atelier):
    """Petit dragon assis taillé dans la roche profonde, couvert de grappes de gemmes."""
    rock = a.mat("#2E2E3A", "speck", "#3E3E4C")
    diamond, emerald = a.mat("#7FF0FF", "glow"), a.mat("#3FE07A", "glow")
    amethyst, ruby = a.mat("#C07AFF", "glow"), a.mat("#FF4A6A", "glow")
    face = a.face("#2E2E3A", eye="#7FF0FF", eyes="round", glow_eyes=True, cheek="#C07AFF", mouth="#7FF0FF")
    base(a, 0)
    a.box("familier", "corps", [-3.5, 2, -2.5], [3.5, 9, 4.5], rock)
    a.box("familier", "poitrail", [-1.5, 4, -3], [1.5, 7, -2.5], diamond, rot=[0, 0, 45])
    head(a, [0, 11, -1])
    a.box("h_head", "tete", [-3, 9, -5], [3, 14, 0.5], rock, front=face)
    a.box("h_head", "museau", [-1.75, 9.5, -6.5], [1.75, 12, -5], rock)
    crown(a, [0, 14, -2])
    a.box("crown", "corne_g", [-2.75, 13.5, -2.5], [-1.25, 17.5, -1], diamond, rot=[-20, 45, -15], origin=[-2, 14, -1.75])
    a.box("crown", "corne_d", [1.25, 13.5, -2.5], [2.75, 17.5, -1], diamond, rot=[-20, 45, 15], origin=[2, 14, -1.75])
    a.box("crown", "gemme_front", [-0.6, 13.5, -4.5], [0.6, 15, -3.3], ruby, rot=[0, 45, 0])
    for i, (x, z, h, m) in enumerate(((-1.5, 0, 4, amethyst), (1.5, 1.5, 3.5, emerald), (-0.5, 3, 3, diamond))):
        a.box("familier", f"grappe_{i}", [x - 1, 8.5, z - 1], [x + 1, 8.5 + h, z + 1], m, rot=[0, 45, 0])
    for side, sx in (("L", -1), ("R", 1)):
        n = limb(a, "wing_" + side, [sx * 3.5, 8, 1], "wings")
        for k, (ln, h, m) in enumerate(((7, 5, diamond), (5.5, 4, amethyst), (4, 3, emerald))):
            x0, x1 = (sx * (3.5 + ln), sx * 3.5) if sx < 0 else (3.5, 3.5 + ln)
            a.box(n, f"{n}_eclat_{k}", [x0, 7 + k * 0.5, 1 + k * 1.5], [x1, 7 + k * 0.5 + h, 1.75 + k * 1.5], m,
                  rot=[0, 0, sx * -15], origin=[sx * 3.5, 8, 1])
    limb(a, "tail", [0, 3, 4.5], "tail")
    a.box("tail", "queue", [-1.25, 2, 4.5], [1.25, 4, 10], rock)
    a.box("tail", "queue_gemme", [-1.5, 2, 9.5], [1.5, 5.5, 12], ruby, rot=[0, 45, 0])
    for name, x, z in (("leg_FL", -2.25, -1.5), ("leg_FR", 2.25, -1.5), ("leg_BL", -2.5, 3), ("leg_BR", 2.5, 3)):
        n = limb(a, name, [x, 2, z], "legs")
        a.box(n, n, [x - 1, 0, z - 1], [x + 1, 2.5, z + 1], rock)


# ================================================================ ROCHISTE

@design
def caillou(a: Atelier):
    rock, moss = a.mat("#8E8E86", "speck", "#6E6E68"), a.mat("#5BA040", "grass", "#7CC456")
    face = a.face("#9A9A92", cheek="#C08070", mouth="#3A3A36")
    base(a)
    head(a, [0, 3, 0])
    a.box("h_head", "rocher", [-4, 0, -3.5], [4, 6.5, 3.5], rock, front=face)
    a.box("h_head", "haut", [-3, 6.5, -2.5], [2, 8, 2.5], rock)
    crown(a, [0, 8, 0])
    a.box("crown", "mousse", [-3, 8, -1], [0.5, 8.75, 2], moss)
    a.box("crown", "galet", [1, 7.5, -1], [3, 9, 1], a.mat("#B4B4AC"), rot=[0, 30, 0])
    arms(a, a.mat("#7A7A72"), 2.5, 4, length=1.5, thick=2, parent="familier")


@design
def mimique(a: Atelier):
    """Coffre vivant tiré de la roche : couvercle qui claque (rôle « head »), dents, langue, or au fond,
    coin de pierre moussue encore collé."""
    wood, plank = a.mat("#8A5A2E"), a.mat("#6A4222", "stripes", "#7A4E28")
    iron, gold = a.mat("#4A4A52"), a.mat("#F2C230", "glow")
    tooth, tongue, mouth = a.mat("#F4F0E0"), a.mat("#D85A7A"), a.mat("#2A0E12")
    stone, moss = a.mat("#8A8A86", "speck", "#6E6E6A"), a.mat("#5BA040", "grass", "#7CC456")
    eye, pupil = a.mat("#FFD94D", "glow"), a.mat("#1A0A06")
    base(a, 1)
    # caisse : planches, coins et sangles de fer, gueule sombre, or et gemme au fond
    a.box("familier", "caisse", [-4, 1.5, -3.5], [4, 6, 3.5], plank)
    a.box("familier", "fond", [-3.5, 4.5, -3], [3.5, 5.9, 3], mouth)
    for x in (-2.5, 2.5):
        a.box("familier", f"sangle_{x}", [x - 0.4, 1.4, -3.6], [x + 0.4, 6.05, 3.6], iron)
    for x in (-4.1, 3.6):
        for z in (-3.6, 3.1):
            a.box("familier", f"coin_{x}_{z}", [x, 1.4, z], [x + 0.5, 6.05, z + 0.5], iron)
    a.box("familier", "pierre_coin", [-4.3, 1.25, 1], [-1.5, 3.5, 3.8], stone)
    a.box("familier", "mousse_coin", [-4.35, 3.25, 1.5], [-2.5, 3.75, 3.85], moss)
    for i, x in enumerate((-3, -1.6, 1.6, 3)):
        a.box("familier", f"dent_bas_{i}", [x - 0.45, 6, -3.4], [x + 0.45, 7, -2.8], tooth)
    a.box("familier", "or_1", [-2.5, 5.5, -1], [-0.5, 6.25, 1.5], gold)
    a.box("familier", "or_2", [0.5, 5.5, 0], [2.5, 6.5, 2], gold, rot=[0, 30, 0])
    a.box("familier", "gemme", [-0.5, 5.75, 1.5], [0.5, 6.75, 2.5], a.mat("#5ADCE8", "glow"), rot=[0, 45, 0])
    # langue qui passe par-dessus le bord et pend devant (rôle « tail » : elle ondule)
    n = limb(a, "langue", [0, 6.25, -2.5], "tail")
    a.box(n, n, [-1.1, 6, -4.25], [1.1, 6.6, -1], tongue)
    a.box(n, n + "_bout", [-1, 3.5, -4.6], [1, 6.6, -3.9], tongue)
    a.box(n, n + "_pli", [-0.2, 3.75, -4.7], [0.2, 6.5, -4.55], a.mat("#B03A5A"))
    # couvercle : charnière à l'arrière, entrouvert sur les dents du haut ; yeux et serrure sur le devant
    # os sans préfixe h_ : BetterModel fait suivre le regard du joueur aux os « h_ », le couvercle se
    # décrochait de la caisse ; rôle « head » gardé pour le claquement des animations
    a.bone("couvercle", [0, 6.5, 3.5], "familier")
    a.role("head", "couvercle")
    r, o = [22, 0, 0], [0, 6.5, 3.5]
    a.box("couvercle", "couvercle", [-4, 6.5, -3.5], [4, 9.5, 3.5], wood, rot=r, origin=o)
    a.box("couvercle", "couvercle_haut", [-3.5, 9.5, -3], [3.5, 10.25, 3], plank, rot=r, origin=o)
    for x in (-2.5, 2.5):
        a.box("couvercle", f"sangle_h_{x}", [x - 0.4, 6.4, -3.6], [x + 0.4, 10.35, 3.6], iron, rot=r, origin=o)
    for i, x in enumerate((-3, -1.6, 1.6, 3)):
        a.box("couvercle", f"dent_haut_{i}", [x - 0.4, 5.6, -3.4], [x + 0.4, 6.6, -2.8], tooth, rot=r, origin=o)
    a.box("couvercle", "serrure", [-0.75, 6.25, -3.85], [0.75, 7.75, -3.5], gold, rot=r, origin=o)
    for sx in (-1, 1):
        x = sx * 1.4
        a.box("couvercle", f"oeil_{sx}", [x - 0.75 if sx > 0 else x - 0.75, 7.6, -3.8], [x + 0.75, 9.3, -3.5], eye,
              rot=r, origin=o)
        a.box("couvercle", f"pupille_{sx}", [x - 0.25, 7.75, -3.9], [x + 0.25, 9.1, -3.75], pupil, rot=r, origin=o)
    # petites pattes de fer
    for name, x, z in (("leg_FL", -3, -2.5), ("leg_FR", 3, -2.5), ("leg_BL", -3, 2.5), ("leg_BR", 3, 2.5)):
        n = limb(a, name, [x, 1.5, z], "legs")
        a.box(n, n, [x - 0.75, 0, z - 0.75], [x + 0.75, 1.5, z + 0.75], iron)


@design
def colosse(a: Atelier):
    basalt, lava = a.mat("#3A3A40", "stripes", "#2A2A30"), a.mat("#FF6A1A", "glow")
    face = a.face("#45454C", eye="#FF8A2A", mouth="#FF6A1A", glow_eyes=True)
    base(a, 5)
    for i, (x, z, h) in enumerate(((-3, 0, 13), (0, -0.5, 15), (3, 0, 12.5), (-1.5, 2.5, 14), (1.5, 2.5, 13.5))):
        a.box("familier", f"colonne_{i}", [x - 1.5, 4, z - 1.5], [x + 1.5, h, z + 1.5], basalt)
    a.box("familier", "fissure_1", [-0.25, 5, -2.1], [0.25, 11, -1.9], lava)
    a.box("familier", "fissure_2", [-3.25, 7, -1.6], [-2.75, 10, -1.4], lava)
    head(a, [0, 15, 0])
    a.box("h_head", "tete", [-2.5, 13, -2.5], [2.5, 17, 2], basalt, front=face)
    crown(a, [0, 17, 0])
    a.box("crown", "braise", [-0.75, 17, -0.75], [0.75, 18, 0.75], lava)
    for side, sx in (("L", -1), ("R", 1)):
        n = limb(a, "arm_" + side, [sx * 4.5, 12, 0], "arms")
        x0, x1 = (-7.5, -4.5) if sx < 0 else (4.5, 7.5)
        a.box(n, n, [x0, 5, -1.5], [x1, 12.5, 1.5], basalt)
        a.box(n, n + "_poing", [x0 - 0.25, 4, -1.75], [x1 + 0.25, 6, 1.75], a.mat("#2A2A30"))
    legs(a, basalt, xs=(-2, 2), h=4, w=3)


@design
def titan(a: Atelier):
    stone, amethyst = a.mat("#8C8478", "speck", "#6E675C"), a.mat("#B06BE8", "glow")
    face = a.face("#9A9286", eye="#2A1A3A", cheek="#B06BE8", mouth="#2A1A3A")
    base(a, 6)
    a.box("familier", "torse", [-5, 6, -3], [5, 15, 3.5], stone)
    a.box("familier", "coeur", [-1.5, 9, -3.25], [1.5, 12, -3], amethyst)
    head(a, [0, 15, 0])
    a.box("h_head", "tete", [-3, 15, -3], [3, 20, 2.5], stone, front=face)
    crown(a, [0, 20, 0])
    for i, (x, z, r) in enumerate(((-2, 0, 15), (0.5, -0.5, -10), (2.5, 1, 25), (-0.5, 2.5, 5))):
        a.box("crown", f"cristal_{i}", [x - 0.75, 19.5, z - 0.75], [x + 0.75, 23 - (i % 2), z + 0.75], amethyst,
              rot=[0, 45, r])
    for i, (x, z) in enumerate(((-4, 1), (4, 1.5), (-2.5, 3), (2, 3.25))):
        a.box("familier", f"dos_{i}", [x - 1, 13, z], [x + 1, 17, z + 1.5], amethyst, rot=[-20, 45, 0])
    for side, sx in (("L", -1), ("R", 1)):
        n = limb(a, "arm_" + side, [sx * 5.5, 14, 0], "arms")
        x0, x1 = (-8.5, -5) if sx < 0 else (5, 8.5)
        a.box(n, n, [x0, 6, -1.75], [x1, 14.5, 1.75], stone)
    legs(a, stone, xs=(-2.5, 2.5), h=6, w=3.5)


# ================================================================ DÉBLAYEUR

@design
def fourmi(a: Atelier):
    """Fourmi en casque de chantier, qui porte sur le dos un bloc de sable qui ne tombe pas."""
    shell, shell2 = a.mat("#9A3A22"), a.mat("#6A2614")
    leg_m, helmet = a.mat("#4A1A10"), a.mat("#F2C230")
    sand = a.mat("#E6D6A0", "speck", "#CDBA80")
    lamp = a.mat("#FFF4B0", "glow")
    face = a.face("#9A3A22", eye="#140806", eyes="round", cheek="#D86A4A", mouth="#3A1008")
    base(a, 3)
    rond(a, "familier", "thorax", [-1.6, 3.5, -3.25], [1.6, 6.25, 0.75], shell, k=0.5)
    a.box("familier", "taille", [-0.7, 3.9, 0.5], [0.7, 5.1, 2], shell2)
    rond(a, "familier", "abdomen", [-2.6, 2.5, 1.25], [2.6, 7, 7.25], shell, k=0.9, rot=[10, 0, 0], origin=[0, 5, 1.25])
    a.box("familier", "abdomen_reflet", [-1.5, 6.85, 2.5], [0, 7.2, 4.5], a.mat("#C86A48"), rot=[10, 0, 0],
          origin=[0, 5, 1.25])
    # bloc de sable porté sur le dos, bien droit
    a.box("familier", "sable", [-1.9, 6.25, -2.9], [1.9, 10, 0.9], sand, rot=[0, 12, 0])
    head(a, [0, 5.5, -3])
    a.box("h_head", "tete", [-2.5, 3.25, -7.5], [2.5, 7.75, -3.25], shell, front=face)
    for side, sx in (("g", -1), ("d", 1)):
        a.box("h_head", "mandibule_" + side, [sx * 1.25 - 0.4, 3, -8.5], [sx * 1.25 + 0.4, 3.75, -7.25], shell2,
              rot=[0, sx * 20, 0])
    crown(a, [0, 7.75, -5.25])
    a.box("crown", "casque", [-2.75, 7.5, -7.75], [2.75, 9, -3], helmet)
    a.box("crown", "casque_haut", [-2, 9, -7], [2, 9.5, -3.75], helmet)
    a.box("crown", "casque_visiere", [-2.75, 7.5, -8.5], [2.75, 8, -7.75], helmet)
    a.box("crown", "lampe", [-0.75, 7.75, -8.25], [0.75, 8.9, -7.65], lamp)
    for side, sx in (("g", -1), ("d", 1)):
        o, r = [sx * 2.1, 8.75, -5], [0, 0, sx * -30]
        a.box("crown", "antenne_" + side, [o[0] - 0.25, 8.75, -5.25], [o[0] + 0.25, 11.5, -4.75], leg_m, rot=r,
              origin=o)
        e = pt(o, [0, 2.6, 0], r)
        a.box("crown", "antenne_fouet_" + side, [e[0] - 0.25, e[1] - 0.25, e[2] - 2.75],
              [e[0] + 0.25, e[1] + 0.25, e[2] + 0.25], leg_m, rot=[-20, 0, 0], origin=e)
    # six pattes articulées : cuisse qui monte vers l'extérieur, genou, tibia qui redescend vers le pied
    for k, (z, dz) in enumerate(((-2.5, -1.5), (-1.25, 0), (0, 1.75))):
        for side, sx in (("L", -1), ("R", 1)):
            hip = [sx * 1.4, 4.25, z]
            knee = [sx * 3.75, 5.25, z + dz]
            foot = [sx * 4.75, 1.25, z + dz * 1.6]
            n = limb(a, f"leg_{side}{k}", hip, "legs")
            segment(a, n, n + "_cuisse", hip, knee, 0.6, leg_m)
            segment(a, n, n + "_tibia", knee, foot, 0.5, leg_m)
            cube_at(a, n, n + "_genou", knee, 0.75, shell2)


@design
def cabri(a: Atelier):
    """Cabri debout, les quatre sabots au sol, clochette au cou ; marche à grandes foulées (walk sur mesure)."""
    coat, patch = a.mat("#F2EEE4"), a.mat("#8A6A4A")
    horn, hoof = a.mat("#A89880"), a.mat("#4A3A2E")
    bell, collar = a.mat("#F0A800", "glow"), a.mat("#C8256A")
    face = a.face("#F2EEE4", eye="#3A2410", eyes="round", cheek="#F0B8A8", mouth="#8A5A4A")
    base(a, 6)
    a.box("familier", "corps", [-2.5, 6, -4], [2.5, 10, 4], coat)
    a.box("familier", "tache", [-2.6, 7.5, 0.5], [2.6, 10.1, 3], patch)
    a.box("familier", "collier", [-2.1, 8, -5], [2.1, 8.75, -3.75], collar, rot=[25, 0, 0], origin=[0, 9, -4])
    a.box("familier", "clochette", [-0.75, 6.25, -5.5], [0.75, 7.75, -4.5], bell)
    a.box("familier", "cou", [-1.5, 8.5, -5.5], [1.5, 12, -3], coat, rot=[25, 0, 0], origin=[0, 9, -4])
    head(a, [0, 12, -5])
    a.box("h_head", "tete", [-2.25, 10.5, -9], [2.25, 14.25, -4.75], coat, front=face)
    a.box("h_head", "museau", [-1.5, 10.5, -10.25], [1.5, 12.25, -9], coat)
    a.box("h_head", "nez", [-0.75, 11.6, -10.35], [0.75, 12.15, -10.25], a.mat("#C89080"))
    a.box("h_head", "barbiche", [-0.5, 9.25, -10], [0.5, 10.75, -9.25], patch)
    crown(a, [0, 14.25, -6.5])
    for side, sx in (("g", -1), ("d", 1)):
        o, r = [sx * 1.25, 14, -6], [-35, 0, sx * -10]
        a.box("crown", "corne_" + side, [o[0] - 0.5, 14, -6.5], [o[0] + 0.5, 16.75, -5.5], horn, rot=r, origin=o)
        e = pt(o, [0, 2.5, 0], r)
        a.box("crown", "corne_bout_" + side, [e[0] - 0.4, e[1] - 0.4, e[2]], [e[0] + 0.4, e[1] + 0.4, e[2] + 1.75],
              horn, rot=[15, 0, 0], origin=e)
        a.box("crown", "oreille_" + side, [sx * 2.25 if sx > 0 else sx * 4.5, 12.5, -6.25],
              [sx * 4.5 if sx > 0 else sx * 2.25, 13.5, -5.25], patch, rot=[0, 0, sx * -15],
              origin=[sx * 2.25, 13, -5.75])
    limb(a, "tail", [0, 9.5, 4], "tail")
    a.box("tail", "queue", [-0.6, 9.5, 3.75], [0.6, 11.5, 5], coat, rot=[-25, 0, 0], origin=[0, 9.5, 4])
    # debout : quatre pattes droites sous le corps, sabots posés au sol (y = 0)
    for name, x, z in (("leg_FL", -1.5, -3), ("leg_FR", 1.5, -3), ("leg_BL", -1.5, 3), ("leg_BR", 1.5, 3)):
        n = limb(a, name, [x, 7, z], "legs")
        a.box(n, n, [x - 0.75, 1, z - 0.75], [x + 0.75, 7, z + 0.75], coat)
        a.box(n, n + "_sabot", [x - 0.85, 0, z - 0.85], [x + 0.85, 1.1, z + 0.85], hoof)
    # walk : grandes foulées en diagonale (avant gauche + arrière droite, puis l'inverse) ; le corps descend
    # d'autant que les pattes inclinées raccourcissent (7 px de long), donc les sabots restent toujours au sol ;
    # tête qui accompagne, queue qui frétille
    stride, length = 34, 0.8

    def swing(phase):
        return [(round(length * i / 16, 3), (round(stride * math.sin(2 * math.pi * i / 16 + phase), 2), 0, 0))
                for i in range(17)]
    a.anim_overrides = {"walk": (length, "loop", {
        "familier": {"position": [(round(length * i / 16, 3), (0, round(-7 * (1 - math.cos(math.radians(
            stride * math.sin(2 * math.pi * i / 16)))), 3), 0)) for i in range(17)],
                     "rotation": [(round(length * i / 8, 3), (round(2 * math.sin(4 * math.pi * i / 8), 2), 0, 0))
                                  for i in range(9)]},
        "leg_FL": {"rotation": swing(0)}, "leg_BR": {"rotation": swing(0)},
        "leg_FR": {"rotation": swing(math.pi)}, "leg_BL": {"rotation": swing(math.pi)},
        "h_head": {"rotation": [(round(length * i / 8, 3), (round(5 * math.sin(4 * math.pi * i / 8), 2), 0, 0))
                                for i in range(9)]},
        "tail": {"rotation": [(round(length * i / 8, 3), (0, round(20 * math.sin(4 * math.pi * i / 8), 2), 0))
                              for i in range(9)]},
    })}


@design
def lievre_neiges(a: Atelier):
    """Lièvre variable blanc : grandes pattes-raquettes pour la poudreuse, oreilles à pointe noire,
    écharpe bleue au vent, flocons qui l'accompagnent."""
    fur, fur2, inner = a.mat("#F8FAFF"), a.mat("#DCE6F2"), a.mat("#F4C8D0")
    tip, scarf = a.mat("#2A2A34"), a.mat("#3A6ADC", "stripes", "#8ACAFF")
    flake, frost = a.mat("#CFF4FF", "glow"), a.mat("#A8E4F8")
    face = a.face("#F8FAFF", eye="#1E2A4A", eyes="round", cheek="#F4B8C8", mouth="#8A6A7A")
    base(a, 5)
    a.box("familier", "corps", [-2.75, 5, -3], [2.75, 10, 4], fur)
    a.box("familier", "poitrail", [-2, 5.25, -3.6], [2, 9, -2.9], fur2)
    a.box("familier", "givre_dos", [-1.75, 10, -0.5], [1.75, 10.5, 3], frost)
    head(a, [0, 10, -3])
    a.box("h_head", "tete", [-2.5, 9, -7], [2.5, 13.5, -2.75], fur, front=face)
    a.box("h_head", "joues", [-2.75, 9.25, -6.5], [2.75, 10.75, -4], fur2)
    a.box("h_head", "nez", [-0.5, 10.5, -7.15], [0.5, 11, -7], a.mat("#E890A8"))
    crown(a, [0, 13.5, -4.5])
    for side, sx in (("g", -1), ("d", 1)):
        x = sx * 1.4
        a.box("crown", "oreille_" + side, [x - 0.9, 13.25, -5.25], [x + 0.9, 19, -4.25], fur,
              rot=[-20, 0, sx * -12], origin=[x, 13.5, -4.75])
        a.box("crown", "oreille_in_" + side, [x - 0.5, 13.75, -5.3], [x + 0.5, 18, -5.2], inner,
              rot=[-20, 0, sx * -12], origin=[x, 13.5, -4.75])
        a.box("crown", "oreille_pointe_" + side, [x - 0.95, 18.25, -5.3], [x + 0.95, 19.5, -4.2], tip,
              rot=[-20, 0, sx * -12], origin=[x, 13.5, -4.75])
    for i, (x, y, z, s) in enumerate(((-5, 15, -2, 1.2), (4.5, 17, -3, 1), (5.5, 12, 1, 0.8), (-4, 11, 3, 0.9))):
        a.box("crown", f"flocon_{i}", [x - s / 2, y - s / 2, z - s / 2], [x + s / 2, y + s / 2, z + s / 2], flake,
              rot=[45, 45, 0])
    # écharpe : tour de cou + pan qui flotte au vent (rôle « tail »)
    a.box("familier", "echarpe", [-2.9, 8.75, -3.6], [2.9, 10, -1.5], scarf)
    n = limb(a, "echarpe_pan", [1.75, 9.25, -1.5], "tail")
    a.box(n, n, [1.25, 7, -1.5], [2.5, 9.5, 3.5], scarf, rot=[-55, 25, 0], origin=[1.75, 9.25, -1.5])
    limb(a, "tail", [0, 8, 4], "tail")
    a.box("tail", "pompon", [-1.25, 7, 3.75], [1.25, 9.5, 6], fur2)
    # pattes avant courtes et repliées, pattes arrière tendues en raquettes larges
    for name, x, z, tilt in (("leg_FL", -1.5, -2, 40), ("leg_FR", 1.5, -2, 30)):
        n = limb(a, name, [x, 6, z], "legs")
        a.box(n, n, [x - 0.7, 2.5, z - 0.7], [x + 0.7, 6, z + 0.7], fur, rot=[tilt, 0, 0], origin=[x, 6, z])
    # pattes arrière ramassées sous le corps : cuisse contre le flanc, grand pied-raquette juste dessous
    for name, x, tilt in (("leg_BL", -2.25, -20), ("leg_BR", 2.25, -12)):
        n = limb(a, name, [x, 6.5, 2.5], "legs")
        a.box(n, n + "_cuisse", [x - 1, 4, 1], [x + 1, 7.5, 4.5], fur, rot=[tilt, 0, 0], origin=[x, 6.5, 2.5])
        a.box(n, n + "_raquette", [x - 1.4, 3, 0.5], [x + 1.4, 4, 6.5], fur2, rot=[tilt, 0, 0], origin=[x, 6.5, 2.5])
        a.box(n, n + "_givre", [x - 1.45, 2.9, 5.5], [x + 1.45, 4.1, 6.6], frost, rot=[tilt, 0, 0],
              origin=[x, 6.5, 2.5])


@design
def ver_sables(a: Atelier):
    sand, dark = a.mat("#D9C27A", "speck", "#C0A860"), a.mat("#8A6A30")
    mouth, tooth = a.mat("#5A1A1A"), a.mat("#F4ECD8")
    base(a, 0)
    head(a, [0, 10, -3])
    a.box("h_head", "tete", [-4, 6, -6], [4, 14, -1], sand)
    a.box("h_head", "gueule", [-3, 7, -6.25], [3, 13, -6], mouth)
    for i, (x, y) in enumerate(((-2, 12.25), (0, 12.25), (2, 12.25), (-2.5, 9), (2.5, 9), (-1, 7), (1, 7))):
        a.box("h_head", f"dent_{i}", [x - 0.4, y - 0.5, -6.6], [x + 0.4, y + 0.5, -6.1], tooth)
    a.box("h_head", "oeil_g", [-4.25, 11, -4], [-4, 12, -3], a.mat("#202020"))
    a.box("h_head", "oeil_d", [4, 11, -4], [4.25, 12, -3], a.mat("#202020"))
    crown(a, [0, 14, -3])
    a.box("crown", "crete", [-0.5, 14, -5], [0.5, 15.5, -1.5], dark)
    parent, z, y, s = "familier", -1, 9.5, 6.5
    for i in range(5):
        n = f"seg_{i}"
        a.bone(n, [0, y, z], parent)
        a.role("tail", n)
        a.box(n, n, [-s / 2, y - s / 2, z], [s / 2, y + s / 2, z + 3.5], sand if i % 2 == 0 else a.mat("#C8AE68"))
        a.box(n, n + "_anneau", [-s / 2 - 0.25, y - s / 2 - 0.25, z + 3], [s / 2 + 0.25, y + s / 2 + 0.25, z + 3.5], dark)
        parent, z, y, s = n, z + 3.5, y - 1.5, max(2.5, s - 0.9)


# ================================================================ CULTIVATEUR

@design
def abeille(a: Atelier):
    """Abeille ronde, rayée, ailes translucides et pelotes de pollen aux pattes."""
    yellow, black = a.mat("#F2C230"), a.mat("#2A2018")
    wing, wing_edge = a.mat("#D8F2FF", "glow"), a.mat("#A8D8F0")
    pollen = a.mat("#FF9A2A", "speck", "#FFC060")
    face = a.face("#F2C230", eye="#1A1008", eyes="round", cheek="#FF9A6A", mouth="#5A3A10")
    base(a, 4)
    head(a, [0, 6, 0])
    a.box("h_head", "corps", [-3.5, 2.5, -3.5], [3.5, 9, 4.5], yellow, front=face)
    for i, z in enumerate((-0.25, 2.25)):
        a.box("h_head", f"rayure_{i}", [-3.6, 2.4, z], [3.6, 9.1, z + 1.25], black)
    a.box("h_head", "derriere", [-3.6, 2.4, 3.75], [3.6, 9.1, 4.6], black)
    a.box("h_head", "dard", [-0.4, 4.75, 4.6], [0.4, 5.5, 6], black)
    crown(a, [0, 9, -2])
    for side, x in (("g", -1.5), ("d", 1.5)):
        r = [-20, 0, 15 if x < 0 else -15]
        a.box("crown", "antenne_" + side, [x - 0.3, 9, -2.75], [x + 0.3, 11.5, -2.15], black, rot=r,
              origin=[x, 9, -2.45])
        cube_at(a, "crown", "antenne_bout_" + side, pt([x, 9, -2.45], [0, 2.6, 0], r), 1, black)
    for side, sx in (("L", -1), ("R", 1)):
        n = limb(a, "wing_" + side, [sx * 1, 9, 0.5], "wings")
        x0, x1 = (sx * 7, sx * 1) if sx < 0 else (1, 7)
        a.box(n, n, [x0, 9, -1], [x1, 9.4, 3], wing, rot=[0, sx * -15, sx * 20], origin=[sx * 1, 9, 0.5])
        a.box(n, n + "_bord", [x0 if sx < 0 else x1 - 1.5, 9.05, 2.5], [x0 + 1.5 if sx < 0 else x1, 9.45, 3.5],
              wing_edge, rot=[0, sx * -15, sx * 20], origin=[sx * 1, 9, 0.5])
    for side, x in (("L", -1.75), ("R", 1.75)):
        n = limb(a, "leg_" + side, [x, 2.5, 0], "legs")
        a.box(n, n, [x - 0.4, 0.75, -0.4], [x + 0.4, 2.5, 0.4], black)
        a.box(n, n + "_pollen", [x - 0.9, 0, -0.9], [x + 0.9, 1.25, 0.9], pollen)


def _hop(t0, t1, height, steps=6):
    """Points (temps, hauteur) d'un bond en parabole de t0 à t1, sommet à height."""
    return [(round(t0 + (t1 - t0) * i / steps, 3), round(4 * height * (i / steps) * (1 - i / steps), 3))
            for i in range(steps + 1)]


@design
def carottin(a: Atelier):
    """Carotte arrachée du potager, fanes au vent ; se déplace par bonds (animation walk sur mesure)."""
    orange, dark, leaf = a.mat("#F07A20", "stripes", "#D86A18"), a.mat("#C05A14"), a.mat("#3E9A34")
    face = a.face("#F07A20", eye="#3A1808", cheek="#FFB070", mouth="#3A1808")
    base(a, 2)
    head(a, [0, 7, 0])
    a.box("h_head", "haut", [-3, 6, -3], [3, 11, 3], orange, front=face)
    a.box("h_head", "milieu", [-2.25, 3.5, -2.25], [2.25, 6, 2.25], orange)
    a.box("h_head", "pointe", [-1.25, 1.5, -1.25], [1.25, 3.5, 1.25], dark)
    crown(a, [0, 11, 0])
    for i, (x, r) in enumerate(((-1, 18), (0, 0), (1, -18))):
        a.box("crown", f"fane_{i}", [x - 0.5, 11, -0.5], [x + 0.5, 15 - abs(x), 0.5], leaf,
              rot=[0, 0, r], origin=[x, 11, 0])
    arms(a, dark, 7.5, 3, length=1.5, thick=1)
    # petites jambes écartées, bien visibles sous la pointe, pieds plats pour les bonds
    for side, x in (("L", -1.4), ("R", 1.4)):
        n = limb(a, "leg_" + side, [x, 2, 0], "legs")
        a.box(n, n, [x - 0.5, 0.5, -0.5], [x + 0.5, 2, 0.5], dark)
        a.box(n, n + "_pied", [x - 0.7, 0, -1.1], [x + 0.7, 0.6, 0.6], dark)
    # walk : un bond par cycle (0,6 s) ; élan penché en avant, jambes repliées en l'air, bras levés, fanes
    # qui fouettent à l'atterrissage. Le corps ne passe jamais sous le sol.
    hop = _hop(0.08, 0.5, 5)
    a.anim_overrides = {"walk": (0.6, "loop", {
        "familier": {"position": [(0, (0, 0, 0))] + [(t, (0, y, 0)) for t, y in hop] + [(0.6, (0, 0, 0))],
                     "rotation": [(0, (0, 0, 0)), (0.08, (12, 0, 0)), (0.29, (-3, 0, 0)), (0.46, (-8, 0, 0)),
                                  (0.52, (5, 0, 0)), (0.6, (0, 0, 0))]},
        **{f"leg_{s}": {"rotation": [(0, (0, 0, 0)), (0.08, (20, 0, 0)), (0.2, (-35, 0, 0)), (0.4, (-30, 0, 0)),
                                     (0.5, (10, 0, 0)), (0.6, (0, 0, 0))]} for s in "LR"},
        **{f"arm_{s}": {"rotation": [(0, (0, 0, 0)), (0.29, (0, 0, k * 45)), (0.5, (0, 0, k * 10)),
                                     (0.6, (0, 0, 0))]} for s, k in (("L", -1), ("R", 1))},
        "crown": {"rotation": [(0, (0, 0, 0)), (0.12, (0, 0, 0)), (0.29, (-18, 0, 0)), (0.47, (12, 0, 0)),
                               (0.55, (-6, 0, 0)), (0.6, (0, 0, 0))]},
    })}


@design
def ouistiti(a: Atelier):
    """Ouistiti à pinceaux blancs, assis, qui serre une cabosse de cacao ; queue annelée en spirale."""
    fur, dark, mask = a.mat("#8A7A6A", "speck", "#A09080"), a.mat("#4A3A30"), a.mat("#E8D0B0")
    tuft, pod = a.mat("#F4F0E8"), a.mat("#D0782A", "stripes", "#A85A1E")
    ring = a.mat("#8A7A6A", "stripes", "#3A2E26")
    leaf, stem = a.mat("#4E9A3A", "grass", "#6CC04E"), a.mat("#5A3A1E")
    face = a.face("#E8D0B0", eye="#1A100A", eyes="round", cheek="#E8A080", mouth="#5A3020")
    base(a)
    # corps assis, ventre clair
    a.box("familier", "corps", [-2.5, 2.5, -1.5], [2.5, 8, 2.5], fur)
    a.box("familier", "ventre", [-1.75, 3, -1.6], [1.75, 7, -1.5], mask)
    for side, x in (("L", -1.5), ("R", 1.5)):
        n = limb(a, "leg_" + side, [x, 3, 0], "legs")
        a.box(n, "cuisse_" + side, [x - 1, 1.5, -2.75], [x + 1, 3.5, 1], fur)
        a.box(n, "pied_" + side, [x - 1.1, 0.75, -3.75], [x + 1.1, 1.75, -2.25], dark)
    # tête : masque clair, front sombre, pinceaux blancs sur les oreilles
    head(a, [0, 9, 0.5])
    a.box("h_head", "tete", [-3, 8, -2.25], [3, 13, 2.5], fur)
    a.box("h_head", "visage", [-2.5, 8.25, -2.4], [2.5, 12, -2.25], mask, front=face)
    a.box("h_head", "front", [-3.1, 12, -2.4], [3.1, 13.1, 2.6], dark)
    a.box("h_head", "tache", [-0.5, 12, -2.5], [0.5, 12.9, -2.4], tuft)
    crown(a, [0, 11, 0.5])
    for side, sx in (("g", -1), ("d", 1)):
        a.box("crown", "pinceau_" + side, [sx * 3 - 1, 9.75, -0.5], [sx * 3 + 1, 13, 1.5], tuft,
              rot=[0, 0, sx * -20], origin=[sx * 3, 10.5, 0.5])
        a.box("crown", "pinceau_bout_" + side, [sx * 4.5 - 1, 11.5, -0.25], [sx * 4.5 + 0.75, 14, 1.25], tuft,
              rot=[0, 0, sx * -35], origin=[sx * 3, 10.5, 0.5])
    # bras serrés sur la cabosse (feuille et queue de la cabosse en haut)
    for side, sx in (("L", -1), ("R", 1)):
        n = limb(a, "arm_" + side, [sx * 2.5, 7, -0.5], "arms")
        a.box(n, n, [sx * 2.5 - 0.6, 4.25, -3.5], [sx * 2.5 + 0.6, 7.25, -0.5], fur,
              rot=[-35, 0, sx * 15], origin=[sx * 2.5, 7, -0.5])
        a.box(n, n + "_main", [sx * 1.9 - 0.6, 4.25, -4.25], [sx * 1.9 + 0.6, 5.5, -3.25], dark)
    a.box("familier", "cabosse", [-1.4, 3.25, -5.5], [1.4, 7.25, -2.75], pod, rot=[0, 0, 10])
    a.box("familier", "cabosse_bout", [-0.75, 2.5, -4.9], [0.75, 3.25, -3.4], pod, rot=[0, 0, 10])
    a.box("familier", "cabosse_queue", [-0.3, 7.25, -4.4], [0.3, 8.25, -3.8], stem, rot=[0, 0, 10])
    a.box("familier", "cabosse_feuille", [0, 7.75, -4.6], [2.25, 8.1, -3.4], leaf, rot=[0, 0, -10])
    # queue annelée qui remonte derrière et s'enroule (chaque anneau ondule)
    segs = [([-0.6, 2.25, 2.5], [0.6, 3.5, 5.5]), ([-0.6, 2.25, 5], [0.6, 9.5, 6.2]),
            ([-0.6, 9, 3.5], [0.6, 10.2, 6.2]), ([-0.6, 7, 3.5], [0.6, 9.25, 4.7])]
    parent = "familier"
    for i, (frm, to) in enumerate(segs):
        n = limb(a, f"tail_{i}", [0, frm[1] + 0.6, frm[2] + 0.6], "tail", parent=parent)
        a.box(n, n, frm, to, ring)
        parent = n


@design
def epouvantail(a: Atelier):
    straw, cloth = a.mat("#E8C860", "stripes", "#D0B048"), a.mat("#5A7AB0", "checker", "#4A6A98")
    wood, sack, crow = a.mat("#7A5230", "bark"), a.mat("#D8C8A0"), a.mat("#202028")
    face = a.face("#D8C8A0", eye="#2A1A10", eyes="dot", mouth="#2A1A10")
    base(a, 0)
    a.box("familier", "piquet", [-0.5, 0, -0.5], [0.5, 8, 0.5], wood)
    a.box("familier", "chemise", [-3, 7, -2], [3, 14, 2], cloth)
    a.box("familier", "paille_bas", [-2.5, 6, -1.5], [2.5, 7.5, 1.5], straw)
    head(a, [0, 15, 0])
    a.box("h_head", "tete", [-2.75, 14, -2.75], [2.75, 19.5, 2.75], sack, front=face)
    crown(a, [0, 19.5, 0])
    a.box("crown", "bord", [-5, 19.5, -5], [5, 20.25, 5], straw)
    a.box("crown", "chapeau", [-2.75, 20.25, -2.75], [2.75, 23, 2.75], straw)
    a.box("crown", "ruban", [-2.85, 20.25, -2.85], [2.85, 21, 2.85], a.mat("#C03030"))
    for side, sx in (("L", -1), ("R", 1)):
        n = limb(a, "arm_" + side, [sx * 3, 12.5, 0], "arms")
        x0, x1 = (-10, -3) if sx < 0 else (3, 10)
        a.box(n, n, [x0, 12, -1], [x1, 13.5, 1], cloth)
        a.box(n, n + "_paille", [x0 - 1 if sx < 0 else x1, 11.5, -0.75], [x0 if sx < 0 else x1 + 1, 14, 0.75], straw)
    limb(a, "corbeau", [6.5, 14, 0], "crown", "arm_R")
    a.box("corbeau", "corbeau", [5.5, 13.5, -1.5], [7.5, 16, 1.5], crow)
    a.box("corbeau", "bec", [6, 14.75, -2.75], [7, 15.5, -1.5], a.mat("#E8B020"))


# ================================================================ HERBORISTE

@design
def coquelicot(a: Atelier):
    stem, petal, heart = a.mat("#3E9A34"), a.mat("#E8302A"), a.mat("#2A2020")
    face = a.face("#F2D030", eye="#2A1A10", cheek="#E87A30", mouth="#2A1A10")
    base(a)
    a.box("familier", "tige", [-0.75, 0, -0.75], [0.75, 8, 0.75], stem)
    for i, (x, r) in enumerate(((-2.5, 30), (2.5, -30))):
        a.box("familier", f"feuille_{i}", [x - 1.5, 3, -0.25], [x + 1.5, 4, 0.25], stem, rot=[0, 0, r], origin=[0, 3.5, 0])
    head(a, [0, 10, 0])
    a.box("h_head", "coeur", [-2, 8, -1.5], [2, 12, 0.5], heart, front=face)
    for i, (x0, y0, x1, y1) in enumerate(((-5, 7, -2, 13), (2, 7, 5, 13), (-2, 12, 2, 15), (-2, 5, 2, 8))):
        a.box("h_head", f"petale_{i}", [x0, y0, -0.5], [x1, y1, 1.5], petal)
    a.bone("crown", [0, 15, 0], "h_head")
    a.role("crown", "crown")
    for side, sx in (("L", -1), ("R", 1)):
        n = limb(a, "arm_" + side, [sx * 0.75, 5, 0], "arms")
        a.box(n, n, [sx * 0.75 - (2 if sx < 0 else 0), 4.5, -0.4], [sx * 0.75 + (0 if sx < 0 else 2), 5.25, 0.4], stem)


@design
def champinou(a: Atelier):
    cap, spot, stem = a.mat("#D8342A"), a.mat("#FFF4E8"), a.mat("#F0E0C8")
    face = a.face("#F0E0C8", eye="#2A1A10", cheek="#F0A0A0", mouth="#2A1A10")
    base(a)
    head(a, [0, 5, 0])
    a.box("h_head", "pied", [-3, 1.5, -3], [3, 8.5, 3], stem, front=face)
    crown(a, [0, 8.5, 0])
    a.box("crown", "chapeau", [-6, 8.5, -6], [6, 12.5, 6], cap)
    a.box("crown", "chapeau_haut", [-4, 12.5, -4], [4, 14.5, 4], cap)
    for i, (x, y, z, w) in enumerate(((-3.5, 10, -6.25, 2), (3, 10.5, -6.25, 1.5), (-6.25, 10, 1, 2), (0, 14.5, -1, 2),
                                      (6, 10, -2, 1.5))):
        a.box("crown", f"pois_{i}", [x - w / 2, y - w / 2, z], [x + w / 2, y + w / 2, z + 0.4] if abs(z) > 5 else
              [x - w / 2 + w, y - w / 2 + 0.5, z + w], spot)
    legs(a, stem, xs=(-1.5, 1.5), h=1.5, w=1.5)


@design
def papillon(a: Atelier):
    """Papillon aux ailes de fleur (rose, violet, taches dorées), antennes à boule lumineuse."""
    body, body2 = a.mat("#3A2650"), a.mat("#5A3A78", "stripes", "#2A1A3A")
    up, up_in, low = a.mat("#F070B0"), a.mat("#B376E5"), a.mat("#E060A0")
    edge, spot, glow = a.mat("#2A1A3A"), a.mat("#FFD94D", "glow"), a.mat("#FFE680", "glow")
    face = a.face("#5A3A78", eye="#F8E8FF", eyes="round", glow_eyes=True, cheek="#F070B0", mouth="#F8E8FF")
    base(a, 3)
    # thorax, abdomen rayé (bout vers le bas), tête ronde
    rond(a, "familier", "thorax", [-1.75, 5.25, -2.25], [1.75, 8.75, 1.75], body, k=0.5)
    rond(a, "familier", "abdomen", [-1.4, 4.75, 1.25], [1.4, 7.75, 9], body2, k=0.5, rot=[12, 0, 0],
         origin=[0, 6.5, 1.5])
    head(a, [0, 8, -2])
    a.box("h_head", "tete", [-2.5, 5.75, -6.25], [2.5, 10.25, -2], a.mat("#5A3A78"), front=face)
    a.box("h_head", "tete_dessus", [-2, 10.25, -5.75], [2, 10.75, -2.5], a.mat("#5A3A78"))
    crown(a, [0, 10.5, -4.5])
    for side, sx in (("g", -1), ("d", 1)):
        o, r = [sx * 1.1, 10.5, -4.5], [-25, 0, sx * -20]
        a.box("crown", "antenne_" + side, [o[0] - 0.25, 10.5, -4.75], [o[0] + 0.25, 14.5, -4.25], body, rot=r,
              origin=o)
        cube_at(a, "crown", "antenne_bout_" + side, pt(o, [0, 4.1, 0], r), 1.2, glow)
    # 4 ailes en V : ailes avant (grandes) et arrière, bordure sombre, cœur violet, taches dorées
    for side, sx in (("L", -1), ("R", 1)):
        def wing(n, x0, x1, z0, z1, mat, y, tilt):
            o, r = [sx * 1.25, y, 0], [0, 0, sx * tilt]

            def span(u0, u1):
                return (sx * u1, sx * u0) if sx < 0 else (u0, u1)
            lo, hi = span(x0, x1)
            a.box(n, n, [lo, y - 0.25, z0], [hi, y + 0.25, z1], edge, rot=r, origin=o)
            lo, hi = span(x0 + 0.5, x1 - 0.75)
            a.box(n, n + "_peau", [lo, y - 0.3, z0 + 0.75], [hi, y + 0.3, z1 - 0.75], mat, rot=r, origin=o)
            lo, hi = span(x0 + 0.75, x0 + 3.25)
            a.box(n, n + "_coeur", [lo, y - 0.35, z0 + 1.5], [hi, y + 0.35, z1 - 1.5], up_in, rot=r, origin=o)
            return r, o
        n = limb(a, "wing_" + side, [sx * 1.25, 8, 0], "wings")
        r, o = wing(n, 1.25, 9, -3.75, 3.25, up, 8, 28)
        for k, (dx, dz) in enumerate(((6.75, -1.75), (5, 1.5))):
            cube_at(a, n, f"{n}_tache_{k}", [sx * dx, 8, dz], 1.4, spot)
            a.elements[-1]["rotation"], a.elements[-1]["origin"] = r, o
        n = limb(a, "wing_" + side + "2", [sx * 1.25, 7, 3.5], "wings")
        r, o = wing(n, 1.25, 6.75, 3, 9, low, 7, 14)
        cube_at(a, n, f"{n}_tache", [sx * 4.5, 7, 6.5], 1.1, spot)
        a.elements[-1]["rotation"], a.elements[-1]["origin"] = r, o
    for side, x in (("L", -0.75), ("R", 0.75)):
        n = limb(a, "leg_" + side, [x, 5.5, -0.5], "legs")
        a.box(n, n, [x - 0.25, 3, -1.5], [x + 0.25, 5.5, -1], edge, rot=[15, 0, 0], origin=[x, 5.5, -1])
        a.box(n, n + "_b", [x - 0.25, 3, 0.25], [x + 0.25, 5.5, 0.75], edge, rot=[-15, 0, 0], origin=[x, 5.5, 0.5])


@design
def dryade(a: Atelier):
    skin, hair = a.mat("#C8E8B0"), a.mat("#3E9A34", "grass", "#5CC048")
    petal, petal2, wing = a.mat("#F07AB0"), a.mat("#F8B0D0"), a.mat("#9AE07A", "glow")
    face = a.face("#C8E8B0", eye="#2A4A20", cheek="#F07AB0", mouth="#7A3A50")
    base(a, 2)
    for i, r in enumerate((0, 60, 120, 180, 240, 300)):
        a.box("familier", f"jupe_{i}", [-1.5, 1, -5], [1.5, 6, -1], petal if i % 2 else petal2,
              rot=[-20, r, 0], origin=[0, 6, 0])
    a.box("familier", "buste", [-2, 6, -1.5], [2, 11, 1.5], skin)
    head(a, [0, 13, 0])
    a.box("h_head", "tete", [-2.75, 11, -2.75], [2.75, 16, 2.75], skin, front=face)
    a.box("h_head", "cheveux", [-3, 13, -1], [3, 17, 3.25], hair)
    crown(a, [0, 17, 0])
    for i, (x, c) in enumerate(((-2, "#F2D030"), (0, "#F07AB0"), (2, "#9AC8F8"))):
        a.box("crown", f"fleur_{i}", [x - 0.75, 16.5, -2], [x + 0.75, 18, -0.5], a.mat(c, "glow"))
    for side, sx in (("L", -1), ("R", 1)):
        n = limb(a, "wing_" + side, [sx * 1.5, 10, 1.5], "wings")
        x0, x1 = (-8, -1.5) if sx < 0 else (1.5, 8)
        a.box(n, n, [x0, 8, 1.5], [x1, 15, 2], wing, rot=[0, sx * -20, sx * 15], origin=[sx * 1.5, 10, 1.5])
    arms(a, skin, 10, 2, length=3, thick=1, parent="familier", drop=1)


# ================================================================ BÛCHERON

@design
def gland(a: Atelier):
    """Gland d'où jaillit déjà un petit chêne, racines pendantes, poudre d'os qui scintille autour."""
    nut, nut2 = a.mat("#C8883A"), a.mat("#A86A28")
    cap, stem = a.mat("#7A5230", "checker", "#5E3E22"), a.mat("#4A3018")
    bark, leaf, leaf2 = a.mat("#6A4426", "bark"), a.mat("#4E9A3A", "grass", "#6CC04E"), a.mat("#3E8A2E")
    root, sparkle = a.mat("#8A6A44"), a.mat("#F8F8F0", "glow")
    mini = a.mat("#C8883A", "glow")
    face = a.face("#C8883A", eye="#2A1808", cheek="#E8A060", mouth="#2A1808")
    base(a, 3)
    head(a, [0, 6, 0])
    a.box("h_head", "gland", [-3.25, 3, -3.25], [3.25, 9.5, 3.25], nut, front=face)
    a.box("h_head", "gland_bas", [-2.25, 2, -2.25], [2.25, 3, 2.25], nut2)
    a.box("h_head", "pointe", [-0.75, 1, -0.75], [0.75, 2, 0.75], stem)
    a.box("h_head", "cupule", [-4, 8.5, -4], [4, 11, 4], cap)
    a.box("h_head", "cupule_haut", [-3, 11, -3], [3, 11.75, 3], cap)
    # le petit chêne : tronc, deux branches, trois touffes de feuilles, deux glands minuscules
    crown(a, [0, 11.75, 0])
    a.box("crown", "tronc", [-0.6, 11.75, -0.6], [0.6, 16.5, 0.6], bark)
    a.box("crown", "branche_g", [-2.5, 14.5, -0.4], [-0.5, 15.25, 0.4], bark, rot=[0, 0, -25], origin=[-0.5, 14.75, 0])
    a.box("crown", "branche_d", [0.5, 15, -0.4], [2.5, 15.75, 0.4], bark, rot=[0, 0, 25], origin=[0.5, 15.25, 0])
    a.box("crown", "feuillage", [-2.5, 16.25, -2.5], [2.5, 19.5, 2.5], leaf)
    a.box("crown", "feuillage_g", [-4.75, 15, -1.75], [-1.75, 17.5, 1.75], leaf2)
    a.box("crown", "feuillage_d", [1.75, 15.5, -1.75], [4.75, 18, 1.75], leaf2)
    a.box("crown", "feuillage_haut", [-1.5, 19.5, -1.5], [1.5, 20.5, 1.5], leaf)
    a.box("crown", "mini_gland_1", [-3.75, 14.25, -0.5], [-2.75, 15.25, 0.5], mini)
    a.box("crown", "mini_gland_2", [3, 14.75, 0.25], [4, 15.75, 1.25], mini)
    for i, (x, y, z, s) in enumerate(((-5, 9, -2, 0.9), (5, 7, 1, 1), (-4.5, 4, 2.5, 0.7), (4.25, 12.5, -2.5, 0.8),
                                      (0.5, 13, -4.5, 0.6))):
        a.box("crown", f"poudre_{i}", [x - s / 2, y - s / 2, z - s / 2], [x + s / 2, y + s / 2, z + s / 2], sparkle,
              rot=[45, 45, 0])
    # racines qui pendent (rôle « legs »)
    for i, (x, z, ang) in enumerate(((-1.5, -1, 20), (1.5, -1, -20), (0, 1.5, 0))):
        n = limb(a, f"leg_{i}", [x, 2.5, z], "legs")
        a.box(n, n, [x - 0.4, -0.5, z - 0.4], [x + 0.4, 2.5, z + 0.4], root, rot=[0, 0, ang], origin=[x, 2.5, z])


@design
def castor(a: Atelier):
    fur, light, dark, tooth = a.mat("#8A5A30"), a.mat("#B07A48"), a.mat("#5A3A1E"), a.mat("#F8F4E8")
    tail_m, log, logend = a.mat("#3A2A20", "checker", "#2A1E16"), a.mat("#7A5230", "bark"), a.mat("#C8A060", "stripes",
                                                                                                    "#B08A4A")
    face = a.face("#8A5A30", eye="#140C06", eyes="dot", cheek="#C08060")
    base(a, 0)
    # corps en poire : bassin large, buste plus étroit
    a.box("familier", "bassin", [-3.5, 0, -1.5], [3.5, 4.5, 4], fur)
    a.box("familier", "buste", [-2.75, 4.5, -1], [2.75, 7.5, 3], fur)
    for side, x in (("g", -2.25), ("d", 2.25)):
        a.box("familier", "pied_" + side, [x - 1, 0, -2.75], [x + 1, 0.75, -1], dark)
    # tête : visage, museau clair qui avance, incisives dessous
    head(a, [0, 8.5, 0.5])
    a.box("h_head", "tete", [-2.75, 7.5, -2], [2.75, 12, 2.5], fur, front=face)
    a.box("h_head", "museau", [-1.5, 7.75, -3.25], [1.5, 9.5, -2], light)
    a.box("h_head", "truffe", [-0.6, 9, -3.4], [0.6, 9.6, -3.2], dark)
    a.box("h_head", "dents", [-0.6, 7, -3.1], [0.6, 7.75, -2.9], tooth)
    crown(a, [0, 12, 0.5])
    for i, x in enumerate((-2, 2)):
        a.box("crown", f"oreille_{i}", [x - 0.6, 11.75, 0], [x + 0.6, 12.75, 1], dark)
    # bras qui tiennent la bûche par les bouts
    for side, sx in (("g", -1), ("d", 1)):
        a.box("familier", "bras_" + side, [sx * 2.75 - 0.6, 3.5, -3.75], [sx * 2.75 + 0.6, 6.75, -1], fur,
              rot=[-25, 0, 0], origin=[sx * 2.75, 6.5, -1])
        a.box("familier", "patte_" + side, [sx * 3 - 0.75, 3.5, -5], [sx * 3 + 0.75, 4.75, -3.25], dark)
    a.box("familier", "buche", [-4, 1.75, -5.25], [4, 3.75, -3.25], log)
    for side, x in (("g", -4.25), ("d", 4)):
        a.box("familier", "buche_bout_" + side, [x, 1.75, -5.25], [x + 0.25, 3.75, -3.25], logend)
    # queue en pagaie posée au sol
    limb(a, "tail", [0, 1, 4], "tail")
    a.box("tail", "queue", [-2, 0, 4], [2, 0.75, 10], tail_m, rot=[-5, 0, 0], origin=[0, 1, 4])


@design
def souche(a: Atelier):
    bark, rings, leaf = a.mat("#6A4426", "bark"), a.mat("#D0A868", "stripes", "#B08A4A"), a.mat("#5BA040")
    steel, wood = a.mat("#B8BEC8"), a.mat("#8A6238")
    face = a.face("#6A4426", eye="#140C06", cheek="#A06A40", mouth="#140C06")
    base(a)
    head(a, [0, 5, 0])
    # tronc à section octogonale (deux pavés croisés), cernes au même profil
    a.box("h_head", "tronc_x", [-4.5, 1.5, -3], [4.5, 9, 3], bark)
    a.box("h_head", "tronc_z", [-3, 1.5, -4.5], [3, 9, 4.5], bark, front=face)
    a.box("h_head", "cernes_x", [-4, 9, -2.5], [4, 9.5, 2.5], rings)
    a.box("h_head", "cernes_z", [-2.5, 9, -4], [2.5, 9.5, 4], rings)
    crown(a, [0, 9.5, 0])
    a.box("crown", "manche", [1.5, 9.5, -0.4], [2.25, 14, 0.4], wood, rot=[0, 0, -20], origin=[1.9, 9.5, 0])
    a.box("crown", "fer", [0.5, 9, -0.75], [2.5, 11, 0.75], steel, rot=[0, 0, -20], origin=[1.9, 9.5, 0])
    a.box("crown", "pousse", [-3, 9.5, 1], [-2.5, 11.5, 1.5], leaf)
    a.box("crown", "feuille", [-3.75, 11, 0.5], [-1.75, 11.5, 2], leaf)
    # racines en guise de pattes, qui s'évasent aux quatre coins
    for i, ang in enumerate((45, 135, 225, 315)):
        rx, rz = 3.2 * math.sin(math.radians(ang)), -3.2 * math.cos(math.radians(ang))
        n = limb(a, f"leg_{i}", [rx, 2, rz], "legs")
        a.box(n, n, [rx - 1.1, 0, rz - 2.25], [rx + 1.1, 2.5, rz + 0.75], bark, rot=[-15, -ang, 0], origin=[rx, 2, rz])


@design
def sylvain(a: Atelier):
    bark, leaves, moss = a.mat("#5A3A20", "bark"), a.mat("#3E8A30", "speck", "#5CAA40"), a.mat("#6AA040")
    fruit = a.mat("#E04040", "glow")
    face = a.face("#5A3A20", eye="#C8F070", eyes="dot", mouth="#2A1A0E", glow_eyes=True)
    base(a, 4)
    head(a, [0, 10, 0])
    a.box("h_head", "tronc", [-3.5, 4, -3], [3.5, 15, 3], bark, front=face)
    a.box("h_head", "mousse", [-3.6, 4, -3.1], [-1.5, 6, 3.1], moss)
    crown(a, [0, 15, 0])
    a.box("crown", "feuillage", [-6, 15, -5], [6, 21, 5], leaves)
    a.box("crown", "feuillage_haut", [-4, 21, -3.5], [4, 23.5, 3.5], leaves)
    for i, (x, y, z) in enumerate(((-4, 17, -5.25), (3, 19, -5.25), (5, 16.5, 1))):
        a.box("crown", f"fruit_{i}", [x - 0.6, y - 0.6, z], [x + 0.6, y + 0.6, z + 0.4] if abs(z) > 5 else
              [x + 6.25 - 6, y, z - 0.6], fruit)
    for side, sx in (("L", -1), ("R", 1)):
        n = limb(a, "arm_" + side, [sx * 3.5, 12, 0], "arms")
        x0, x1 = (-8, -3.5) if sx < 0 else (3.5, 8)
        a.box(n, n, [x0, 11.5, -1], [x1, 13, 1], bark, rot=[0, 0, sx * -25], origin=[sx * 3.5, 12, 0])
        a.box(n, n + "_feuilles", [x0 - 0.5 if sx < 0 else x1 - 2, 13, -1.5], [x0 + 2 if sx < 0 else x1 + 0.5, 15, 1.5],
              leaves)
    for side, x in (("L", -1.75), ("R", 1.75)):
        n = limb(a, "leg_" + side, [x, 4, 0], "legs")
        a.box(n, n, [x - 1.25, 0, -1.25], [x + 1.25, 4, 1.25], bark)
        a.box(n, n + "_racine", [x - 2, 0, -2], [x + 2, 0.75, 2], bark)


# ================================================================ FONDEUR

@design
def flammeche(a: Atelier):
    raise NotImplementedError("modèle d'origine, copié tel quel")


@design
def soufflet(a: Atelier):
    """Soufflet de forge : planche du haut qui pompe (rôle « head »), cuir plissé, embout de laiton
    qui crache des braises."""
    wood, wood2 = a.mat("#7A4E28", "bark"), a.mat("#5A3618")
    leather = a.mat("#8A3A2A", "stripes", "#6A2A1E")
    brass, ember, ember2 = a.mat("#D8A830"), a.mat("#FF7A2A", "glow"), a.mat("#FFD24A", "glow")
    white, pupil = a.mat("#FFFFFF"), a.mat("#1A0E08")
    base(a, 1)
    # planche du bas (en goutte), cuir plissé
    a.box("familier", "planche_bas", [-3.5, 1, -2], [3.5, 2, 5], wood)
    a.box("familier", "planche_bas_avant", [-2.25, 1, -4.5], [2.25, 2, -2], wood)
    a.box("familier", "cuir", [-3.25, 2, -1.5], [3.25, 4.5, 4.75], leather)
    a.box("familier", "cuir_avant", [-2, 2, -4], [2, 4, -1.5], leather)
    a.box("familier", "embout", [-0.75, 1.5, -7.5], [0.75, 3, -4], brass)
    a.box("familier", "embout_bague", [-1, 1.25, -5], [1, 3.25, -4.25], wood2)
    a.box("familier", "poignee_bas", [-0.75, 1.25, 5], [0.75, 1.75, 8.5], wood2)
    # planche du haut avec les yeux (charnière à l'avant : elle pompe)
    a.bone("planche", [0, 4.5, -4], "familier")  # pas « h_ » : ne doit pas suivre le regard
    a.role("head", "planche")
    a.box("planche", "planche_haut", [-3.5, 4.5, -2], [3.5, 5.5, 5], wood)
    a.box("planche", "planche_haut_avant", [-2.25, 4.5, -4.5], [2.25, 5.5, -2], wood)
    a.box("planche", "poignee_haut", [-0.75, 5, 5], [0.75, 5.5, 8.5], wood2, rot=[-10, 0, 0], origin=[0, 5, 5])
    a.box("planche", "clou_g", [-2.5, 5.5, 3.5], [-1.75, 5.75, 4.25], brass)
    a.box("planche", "clou_d", [1.75, 5.5, 3.5], [2.5, 5.75, 4.25], brass)
    for sx in (-1, 1):
        x = sx * 1.4
        a.box("planche", f"oeil_{sx}", [x - 1, 5.5, -3], [x + 1, 7.5, -1.5], white)
        a.box("planche", f"pupille_{sx}", [x - 0.45 + sx * 0.2, 5.75, -3.15], [x + 0.45 + sx * 0.2, 6.9, -2.9], pupil)
    a.box("planche", "joue_g", [-3, 5.5, -2.25], [-2.25, 5.85, -1.5], a.mat("#F08A6A"))
    a.box("planche", "joue_d", [2.25, 5.5, -2.25], [3, 5.85, -1.5], a.mat("#F08A6A"))
    # bouffée de braises devant l'embout (rôle « tail » : elle ondule)
    n = limb(a, "braises", [0, 2.25, -7.5], "tail")
    for i, (x, y, z, s, m) in enumerate(((0, 2.5, -8.5, 1.2, ember), (-1, 3.25, -9.75, 0.9, ember2),
                                         (1, 1.75, -10.25, 0.8, ember), (0.25, 3.5, -11.25, 0.6, ember2))):
        a.box(n, f"braise_{i}", [x - s / 2, y - s / 2, z - s / 2], [x + s / 2, y + s / 2, z + s / 2], m,
              rot=[45, 45, 0])


@design
def salamandre(a: Atelier):
    skin, lava, belly = a.mat("#B83020", "stripes", "#E86A20"), a.mat("#FFB020", "glow"), a.mat("#F0C890")
    face = a.face("#B83020", eye="#FFE070", eyes="round", glow_eyes=True, mouth="#3A0808")
    base(a, 2)
    a.box("familier", "corps", [-2.5, 2, -4], [2.5, 5, 6], skin)
    a.box("familier", "ventre", [-1.75, 1.75, -3.5], [1.75, 2, 5.5], belly)
    for i, z in enumerate((-2.5, 0.5, 3.5)):
        a.box("familier", f"crete_{i}", [-0.4, 5, z], [0.4, 6.5, z + 1.5], lava)
    head(a, [0, 4, -4])
    a.box("h_head", "tete", [-3, 2, -9], [3, 6.5, -3.5], skin, front=face)
    crown(a, [0, 6.5, -6])
    a.box("crown", "flamme", [-0.75, 6.5, -7], [0.75, 9, -5.5], lava)
    limb(a, "tail", [0, 3.5, 6], "tail")
    a.box("tail", "queue", [-1.5, 2.5, 6], [1.5, 4.5, 11], skin)
    a.bone("tail2", [0, 3.5, 11], "tail")
    a.role("tail", "tail2")
    a.box("tail2", "queue2", [-1, 3, 11], [1, 4.25, 15], skin)
    a.box("tail2", "queue_flamme", [-1, 4.25, 13.5], [1, 7, 15], lava)
    for name, x, z in (("leg_FL", -3, -2.5), ("leg_FR", 3, -2.5), ("leg_BL", -3, 4), ("leg_BR", 3, 4)):
        n = limb(a, name, [x, 2.5, z], "legs")
        a.box(n, n, [x - 1, 0, z - 1], [x + 1, 2.5, z + 1], skin)


@design
def fournaise(a: Atelier):
    cobble, dark, fire = a.mat("#7A7A7A", "speck", "#5E5E5E"), a.mat("#3A3A3A"), a.mat("#FF8A20", "glow")
    face = a.face("#7A7A7A", eye="#FFD040", eyes="carved")
    base(a, 3)
    head(a, [0, 9, 0])
    a.box("h_head", "four", [-5.5, 3, -5], [5.5, 14, 5], cobble, front=face)
    a.box("h_head", "foyer", [-3.5, 4, -5.25], [3.5, 7, -5], fire)
    a.box("h_head", "socle", [-6, 3, -5.5], [6, 4, 5.5], dark)
    crown(a, [2.5, 14, 2])
    a.box("crown", "cheminee", [1, 14, 0.5], [4, 19, 3.5], cobble)
    a.box("crown", "fumee_1", [1.5, 19.5, 1], [3.5, 21, 3], a.mat("#9A9AA0"))
    a.box("crown", "fumee_2", [2, 21.5, 1.5], [3.5, 22.5, 3], a.mat("#C0C0C8"))
    a.box("h_head", "braise", [-4.5, 14, -3], [-2.5, 15, -1], fire)
    for side, sx in (("L", -1), ("R", 1)):
        n = limb(a, "arm_" + side, [sx * 5.5, 10, 0], "arms")
        x0, x1 = (-8, -5.5) if sx < 0 else (5.5, 8)
        a.box(n, n, [x0, 6, -1.25], [x1, 10.5, 1.25], dark)
    legs(a, dark, xs=(-3, 3), h=3, w=2.5)


# ================================================================ CHIMISTE

@design
def fiolin(a: Atelier):
    glass, potion, cork = a.mat("#C8F0F8", "glow"), a.mat("#E048B0", "liquid", "#B02888"), a.mat("#B08050")
    face = a.face("#E048B0", eye="#3A0A2A", cheek="#FF9AD8", mouth="#3A0A2A")
    base(a)
    head(a, [0, 4.5, 0])
    a.box("h_head", "panse", [-3.5, 1, -3.5], [3.5, 7.5, 3.5], potion, front=face)
    a.box("h_head", "verre", [-3.75, 4.5, -3.75], [3.75, 8, 3.75], glass)
    crown(a, [0, 8, 0])
    a.box("crown", "col", [-1.25, 8, -1.25], [1.25, 10, 1.25], glass)
    a.box("crown", "bouchon", [-1.5, 10, -1.5], [1.5, 11.5, 1.5], cork)
    for i, (x, y, z) in enumerate(((2.5, 11, 0), (-2, 12.5, 1), (1, 14, -1))):
        a.box("crown", f"bulle_{i}", [x - 0.4, y, z - 0.4], [x + 0.4, y + 0.8, z + 0.4], a.mat("#FFC8F0", "glow"))
    legs(a, cork, xs=(-1.5, 1.5), h=1, w=1.25)


@design
def chaudronnet(a: Atelier):
    iron, potion, bubble = a.mat("#2E2E36"), a.mat("#5AE04A", "glow"), a.mat("#B8FFA8", "glow")
    face = a.face("#2E2E36", eye="#5AE04A", eyes="round", glow_eyes=True, mouth="#5AE04A")
    base(a, 1.5)
    head(a, [0, 5, 0])
    a.box("h_head", "chaudron", [-4.5, 1.5, -4.5], [4.5, 8, 4.5], iron, front=face)
    a.box("h_head", "rebord", [-5, 8, -5], [5, 9, 5], iron)
    crown(a, [0, 9, 0])
    a.box("crown", "potion", [-4, 8.5, -4], [4, 9.25, 4], potion)
    for i, (x, z, s) in enumerate(((-1.5, -1, 1.5), (2, 1, 1), (-0.5, 2.5, 0.8))):
        a.box("crown", f"bulle_{i}", [x - s / 2, 9.25, z - s / 2], [x + s / 2, 9.25 + s, z + s / 2], bubble)
    a.box("crown", "louche", [2.5, 8.5, -2], [3.25, 13, -1.25], a.mat("#8A6238"), rot=[0, 0, -20], origin=[2.9, 9, -1.6])
    for i, x in enumerate((-3.5, 3.5)):
        n = limb(a, f"leg_{'L' if x < 0 else 'R'}", [x, 1.5, 0], "legs")
        a.box(n, n, [x - 0.75, 0, -0.75], [x + 0.75, 1.5, 0.75], iron)


@design
def slime_alchimique(a: Atelier):
    slime, core = a.mat("#7AE05A", "glow"), a.mat("#A048E0", "liquid", "#7A28B0")
    face = a.face("#7AE05A", eye="#1A3A10", cheek="#C0FF90", mouth="#1A3A10")
    base(a)
    head(a, [0, 5, 0])
    a.box("h_head", "slime", [-5, 0, -5], [5, 10, 5], slime, front=face)
    a.box("h_head", "fiole", [-1.5, 2.5, -1], [1.5, 6.5, 2], core)
    a.box("h_head", "fiole_col", [-0.75, 6.5, -0.25], [0.75, 8, 1.25], a.mat("#C8F0F8", "glow"))
    crown(a, [0, 10, 0])
    a.box("crown", "goutte", [-1, 10, -1], [1, 11.5, 1], slime)
    a.box("crown", "goutte_2", [2.5, 10, 1], [3.5, 10.75, 2], slime)


@design
def grand_alchimiste(a: Atelier):
    robe, robe2, trim, skin = a.mat("#2E5A4A"), a.mat("#244A3C"), a.mat("#E8C048"), a.mat("#E0C8A8")
    hat = a.mat("#2E4A3E")
    face = a.face("#E0C8A8", eye="#1A1A1A", eyes="dot", cheek="#E09080", mouth="#5A3A2A")
    base(a, 0)
    # robe en cloche : large en bas, plus étroite en haut
    a.box("familier", "robe_bas", [-3.75, 0, -3.25], [3.75, 4, 3.25], robe2)
    a.box("familier", "robe_haut", [-3, 4, -2.5], [3, 9, 2.5], robe)
    a.box("familier", "bordure", [-3.85, 0, -3.35], [3.85, 0.75, 3.35], trim)
    a.box("familier", "ceinture", [-3.1, 5.5, -2.6], [3.1, 6.25, 2.6], trim)
    head(a, [0, 11, 0])
    a.box("h_head", "tete", [-2.5, 9, -2.5], [2.5, 13.5, 2.5], skin, front=face)
    a.box("h_head", "barbe", [-2, 7, -2.75], [2, 10, -2.5], a.mat("#F0F0F0"))
    # chapeau pointu à large bord, pointe pliée
    crown(a, [0, 13.5, 0])
    a.box("crown", "bord", [-4.5, 13.25, -4.5], [4.5, 14, 4.5], hat)
    a.box("crown", "cone_1", [-2.75, 14, -2.75], [2.75, 16.5, 2.75], hat)
    a.box("crown", "cone_2", [-1.75, 16.5, -1.75], [1.75, 18.5, 1.75], hat, rot=[0, 0, 10], origin=[0, 16.5, 0])
    a.box("crown", "pointe", [-0.75, 18, -0.75], [0.75, 20, 0.75], hat, rot=[0, 0, 30], origin=[0, 18.5, 0])
    a.box("crown", "ruban", [-2.85, 14, -2.85], [2.85, 14.75, 2.85], trim)
    # manches tombantes, une fiole tenue dans la main droite, une autre qui flotte
    for side, sx in (("L", -1), ("R", 1)):
        n = limb(a, "arm_" + side, [sx * 3, 8.5, 0], "arms")
        a.box(n, n, [sx * 3 - 0.9, 4.5, -1], [sx * 3 + 0.9, 8.75, 1], robe, rot=[-20, 0, sx * 12], origin=[sx * 3, 8.5, 0])
    a.box("arm_R", "fiole", [3.25, 3, -2.75], [4.75, 4.75, -1.25], a.mat("#E048B0", "liquid"))
    a.box("arm_R", "fiole_col", [3.65, 4.75, -2.35], [4.35, 5.5, -1.65], a.mat("#C8F0F8", "glow"))
    n = limb(a, "potion_0", [-6, 11, -1], "wings")
    a.box(n, n, [-6.75, 10, -1.75], [-5.25, 11.5, -0.25], a.mat("#48A8E0", "liquid"))
    a.box(n, n + "_col", [-6.4, 11.5, -1.4], [-5.6, 12.25, -0.6], a.mat("#C8F0F8", "glow"))

# ================================================================ ENCHANTEUR

@design
def lapin_lapis(a: Atelier):
    """Lapin taillé dans le lapis-lazuli (veines dorées), assis, qui serre une gemme de lapis."""
    lapis, lapis2 = a.mat("#2A4EB8", "speck", "#3A64D0"), a.mat("#3458C4", "speck", "#4A76E0")
    belly, inner = a.mat("#7EA2F0"), a.mat("#A8C4FF")
    gem, rune, fleck = a.mat("#3A7AFF", "glow"), a.mat("#C8A0FF", "glow"), a.mat("#F2C230", "glow")
    face = a.face("#3458C4", eye="#F8F0D0", eyes="round", glow_eyes=True, cheek="#A8C4FF", mouth="#1A2A6A")
    base(a)
    rond(a, "familier", "corps", [-2.75, 1.5, -2], [2.75, 7.5, 3.5], lapis, k=0.6)
    a.box("familier", "ventre", [-1.75, 2, -2.1], [1.75, 6.5, -1.9], belly)
    for i, (x, y, z) in enumerate(((-2.8, 5, 1), (2.8, 3.5, 2.25), (-1, 7.55, 2), (1.5, 2.5, 3.55))):
        a.box("familier", f"veine_{i}", [x - 0.3, y - 0.3, z - 0.6], [x + 0.3, y + 0.3, z + 0.6], fleck)
    for side, x in (("L", -2), ("R", 2)):
        n = limb(a, "leg_" + side, [x, 2, 1], "legs")
        a.box(n, "cuisse_" + side, [x - 1.25, 1, -0.5], [x + 1.25, 4, 3.5], lapis2)
        a.box(n, "pied_" + side, [x - 1, 0, -2.75], [x + 1, 1, 1.5], lapis2)
    head(a, [0, 8, 0])
    a.box("h_head", "tete", [-2.5, 7.25, -2.75], [2.5, 11.5, 1.75], lapis2, front=face)
    a.box("h_head", "museau", [-1, 7.5, -3.1], [1, 8.75, -2.75], belly)
    a.box("h_head", "veine_tete", [1.2, 10.9, -2.85], [2.2, 11.3, -2.7], fleck)
    crown(a, [0, 11.5, 0])
    for side, sx in (("g", -1), ("d", 1)):
        x = sx * 1.25
        a.box("crown", "oreille_" + side, [x - 0.85, 11.25, -0.75], [x + 0.85, 16.5, 0.5], lapis2,
              rot=[10, 0, sx * -10], origin=[x, 11.5, 0])
        a.box("crown", "oreille_in_" + side, [x - 0.45, 11.75, -0.8], [x + 0.45, 15.5, -0.7], inner,
              rot=[10, 0, sx * -10], origin=[x, 11.5, 0])
    a.box("crown", "rune_1", [-4.5, 14, -1], [-3.5, 15, 0], rune, rot=[0, 45, 45])
    a.box("crown", "rune_2", [3.5, 12.5, 1], [4.25, 13.25, 1.75], rune, rot=[0, 45, 45])
    # pattes avant serrées sur la gemme
    for side, sx in (("L", -1), ("R", 1)):
        n = limb(a, "arm_" + side, [sx * 2, 6, -1.5], "arms")
        a.box(n, n, [sx * 2 - 0.6, 3.5, -3.25], [sx * 2 + 0.6, 6.25, -1.75], lapis2,
              rot=[-25, 0, sx * 20], origin=[sx * 2, 6, -1.5])
    a.box("familier", "gemme", [-1.1, 3.5, -4.25], [1.1, 5.7, -2.25], gem, rot=[0, 45, 0])
    limb(a, "tail", [0, 3, 3.5], "tail")
    a.box("tail", "pompon", [-1.25, 2, 3.25], [1.25, 4.5, 5.25], a.mat("#F4F8FF"))


@design
def orbe_xp(a: Atelier):
    outer, mid = a.mat("#7ED61E", "glow"), a.mat("#B8F030", "glow")
    inner, spark = a.mat("#F0FF8A", "glow"), a.mat("#FFFFD0", "glow")
    face = a.face("#B8F030", eye="#1E4A08", eyes="round", cheek="#F0FF8A", mouth="#1E4A08")
    base(a, 5)
    head(a, [0, 7, 0])
    # boule arrondie : écorce verte aux arêtes, cœur jaune-vert qui ressort au milieu de chaque face
    a.box("h_head", "boule_x", [-3.5, 4.5, -2.5], [3.5, 9.5, 2.5], outer)
    a.box("h_head", "boule_y", [-2.5, 3.5, -2.5], [2.5, 10.5, 2.5], outer)
    a.box("h_head", "boule_z", [-2.5, 4.5, -3.5], [2.5, 9.5, 3.5], outer)
    a.box("h_head", "coeur_x", [-3.6, 5.5, -1.5], [3.6, 8.5, 1.5], mid)
    a.box("h_head", "coeur_y", [-1.5, 3.4, -1.5], [1.5, 10.6, 1.5], mid)
    a.box("h_head", "visage", [-2.25, 4.75, -3.6], [2.25, 9.25, -3.4], mid, front=face)
    a.box("h_head", "reflet", [-2.75, 9.25, -2.75], [-1.25, 10.25, -1.25], inner)
    crown(a, [0, 7, 0])
    # petites orbes en orbite, étincelle à quatre branches au-dessus
    for i, (x, y, z, r) in enumerate(((-5.5, 9, 1, 1.5), (5, 5, -1.5, 1.2), (1, 2.5, 5, 1))):
        a.box("crown", f"orbite_{i}", [x - r / 2, y - r / 2, z - r / 2], [x + r / 2, y + r / 2, z + r / 2],
              (inner, mid, outer)[i], rot=[45, 45, 0])
    a.box("crown", "etincelle_v", [-0.3, 11.5, -0.3], [0.3, 14.5, 0.3], spark)
    a.box("crown", "etincelle_h", [-1.5, 12.7, -0.3], [1.5, 13.3, 0.3], spark)


@design
def chouette_arcane(a: Atelier):
    feather, dark, beak = a.mat("#3A3A7A"), a.mat("#2A2A5A"), a.mat("#F2C230")
    belly, disk = a.mat("#9A9AD8", "checker", "#8A8AC8"), a.mat("#5A5AA8")
    face = a.face("#5A5AA8", eye="#F2C230", eyes="round", glow_eyes=True)
    base(a, 2)
    # corps en œuf, ventre clair
    a.box("familier", "corps", [-3, 2, -2.5], [3, 8.5, 3], feather)
    a.box("familier", "corps_bas", [-2.5, 1.5, -2], [2.5, 2, 2.5], feather)
    a.box("familier", "ventre", [-2, 2.5, -2.75], [2, 7.5, -2.5], belly)
    # grosse tête ronde, disque facial plus clair, bec
    head(a, [0, 9, 0])
    a.box("h_head", "tete_x", [-3.5, 8.5, -2.5], [3.5, 13, 2.5], feather)
    a.box("h_head", "tete_z", [-3, 8.5, -3], [3, 13, 3], disk, front=face)
    a.box("h_head", "bec", [-0.5, 9.75, -3.5], [0.5, 11, -3], beak)
    crown(a, [0, 13, 0])
    for i, x in enumerate((-2.5, 2.5)):
        a.box("crown", f"aigrette_{i}", [x - 0.6, 12.5, -1], [x + 0.6, 15, 0.5], dark,
              rot=[0, 0, -20 if x < 0 else 20], origin=[x, 13, 0])
    # ailes repliées le long du corps
    for side, sx in (("L", -1), ("R", 1)):
        n = limb(a, "wing_" + side, [sx * 3, 8, 0.5], "wings")
        a.box(n, n, [sx * 3 - 0.5, 2.5, -1.5], [sx * 3 + 0.5, 8, 2.75], dark, rot=[0, 0, sx * 8], origin=[sx * 3, 8, 0.5])
    legs(a, beak, xs=(-1.25, 1.25), h=1.5, w=1)


@design
def table_vivante(a: Atelier):
    obsidian, cloth = a.mat("#1E1A2E", "speck", "#2E2840"), a.mat("#B02A3A")
    diamond, cover, pages = a.mat("#5ADCE8", "glow"), a.mat("#8A3A20"), a.mat("#F4ECD8", "stripes", "#E0D4B8")
    face = a.face("#F4ECD8", eye="#5A2A8A", eyes="round", glow_eyes=True, cheek="#E8A0A0", mouth="#5A2A8A")
    base(a, 0)
    a.box("familier", "table", [-5, 0, -5], [5, 7, 5], obsidian)
    a.box("familier", "nappe", [-5.25, 6, -5.25], [5.25, 7.5, 5.25], cloth)
    for i, (x, z) in enumerate(((-4.5, -4.5), (3.5, -4.5), (-4.5, 3.5), (3.5, 3.5))):
        a.box("familier", f"diamant_{i}", [x, 7.5, z], [x + 1, 8.5, z + 1], diamond)
    head(a, [0, 12, 0])
    a.box("h_head", "page_g", [-5, 11, -3], [0, 11.5, 3], pages, rot=[0, 0, 15], origin=[0, 11, 0])
    a.box("h_head", "page_d", [0, 11, -3], [5, 11.5, 3], pages, rot=[0, 0, -15], origin=[0, 11, 0])
    a.box("h_head", "visage", [-2, 11.5, -3.25], [2, 15, -2.75], pages, front=face)
    a.box("h_head", "reliure", [-0.4, 10.5, -3], [0.4, 11.25, 3], cover)
    crown(a, [0, 15, 0])
    for i, (x, y, z) in enumerate(((-4, 16, 0), (4, 17, 1), (0, 18.5, 3))):
        a.box("crown", f"glyphe_{i}", [x - 0.5, y - 0.5, z - 0.5], [x + 0.5, y + 0.5, z + 0.5], a.mat("#8AE8FF", "glow"),
              rot=[45, 0, 45])


# ================================================================ PÊCHEUR

@design
def poissonnet(a: Atelier):
    scale_, fin, belly = a.mat("#3A8AE0", "checker", "#2E78C8"), a.mat("#F07A30"), a.mat("#D8F0FF")
    face = a.face("#3A8AE0", eye="#0A1A2A", cheek="#80C0FF", mouth="#0A1A2A")
    base(a, 6)
    head(a, [0, 6, 0])
    a.box("h_head", "corps", [-2, 3, -4], [2, 9, 4], scale_, front=face)
    a.box("h_head", "ventre", [-1.75, 2.75, -3.5], [1.75, 3, 3.5], belly)
    crown(a, [0, 9, 0])
    a.box("crown", "nageoire_dorsale", [-0.25, 9, -1.5], [0.25, 11, 2.5], fin, rot=[-15, 0, 0])
    for side, sx in (("L", -1), ("R", 1)):
        n = limb(a, "wing_" + side, [sx * 2, 5, -1], "wings")
        x0, x1 = (-4.5, -2) if sx < 0 else (2, 4.5)
        a.box(n, n, [x0, 4.5, -1.5], [x1, 5, 1], fin)
    limb(a, "tail", [0, 6, 4], "tail")
    a.box("tail", "queue", [-0.25, 3, 4], [0.25, 9, 7.5], fin)

@design
def crabichon(a: Atelier):
    shell, claw, leg = a.mat("#D8402A", "speck", "#E8603A"), a.mat("#E85A3A"), a.mat("#B8301E")
    base(a, 2.5)
    head(a, [0, 4.5, 0])
    # pas de visage peint : ses yeux sont au bout des pédoncules, seule une bouche souriante sur l'avant
    a.box("h_head", "carapace", [-4.5, 2.5, -3], [4.5, 6.5, 3], shell)
    mouth = a.mat("#3A0A08")
    a.box("h_head", "bouche", [-1, 3.75, -3.1], [1, 4.25, -3], mouth)
    for i, x in enumerate((-1.5, 1)):
        a.box("h_head", f"bouche_coin_{i}", [x, 4.25, -3.1], [x + 0.5, 4.75, -3], mouth)
    # petit chapeau de pêcheur (ciré jaune) posé sur la carapace, derrière les yeux
    hat, band = a.mat("#F2C230"), a.mat("#2A4A8A")
    a.box("h_head", "chapeau_bord", [-2.5, 6.5, -1.25], [2.5, 7, 2.75], hat)
    a.box("h_head", "chapeau", [-1.5, 7, -0.25], [1.5, 8.75, 1.75], hat)
    a.box("h_head", "chapeau_ruban", [-1.55, 7, -0.3], [1.55, 7.5, 1.8], band)
    crown(a, [0, 6.5, -2])
    for i, x in enumerate((-1.5, 1.5)):
        a.box("crown", f"pedoncule_{i}", [x - 0.25, 6.5, -2.5], [x + 0.25, 8.5, -2], leg)
        a.box("crown", f"oeil_{i}", [x - 0.6, 8.5, -2.85], [x + 0.6, 9.75, -1.65], a.mat("#F8F8F8"))
        a.box("crown", f"pupille_{i}", [x - 0.3, 8.8, -2.95], [x + 0.3, 9.4, -2.85], a.mat("#101010"))
    for side, sx in (("L", -1), ("R", 1)):
        n = limb(a, "arm_" + side, [sx * 4.5, 4.5, -2], "arms")
        x0 = -8 if sx < 0 else 4.5
        a.box(n, n, [x0, 4, -3], [x0 + 3.5, 5, -2], leg)
        cx = x0 if sx < 0 else x0 + 2
        a.box(n, n + "_pince", [cx - 0.5, 3.5, -6], [cx + 2, 6.5, -3], claw)
        a.box(n, n + "_doigt", [cx - 0.25, 5, -7], [cx + 0.75, 6.5, -6], claw)
    for i, (x, z) in enumerate(((-4, -1), (-4.5, 1), (4, -1), (4.5, 1))):
        n = limb(a, f"leg_{i}", [x, 2.5, z], "legs")
        a.box(n, n, [x - 0.4, 0, z - 0.4], [x + 0.4, 2.75, z + 0.4], leg, rot=[0, 0, 25 if x < 0 else -25])


@design
def dauphin(a: Atelier):
    """Dauphin élancé en plein bond : corps fuselé, rostre fin avec un sourire, yeux sur les côtés,
    nageoires en ailes, souffle d'eau au-dessus de l'évent."""
    skin, skin2 = a.mat("#5E86B4"), a.mat("#6E96C2")
    belly, dark = a.mat("#DCE8F2"), a.mat("#3E5E88")
    eye, shine, smile = a.mat("#101828"), a.mat("#FFFFFF"), a.mat("#2A3E5E")
    spray, drop = a.mat("#9ADCFF", "glow"), a.mat("#E8F8FF", "glow")
    base(a, 4)
    # corps fuselé : buste, ventre clair, arrière qui s'affine et descend un peu
    rond(a, "familier", "buste", [-2.25, 3, -4.5], [2.25, 7.25, 2.5], skin, k=0.75)
    a.box("familier", "ventre", [-1.6, 2.75, -4], [1.6, 3.5, 2], belly)
    rond(a, "familier", "arriere", [-1.75, 3.5, 2.25], [1.75, 6.5, 6.5], skin, k=0.6, rot=[8, 0, 0],
         origin=[0, 5, 2.25])
    a.box("familier", "dorsale", [-0.4, 6.75, -1.25], [0.4, 9.75, 0.75], dark, rot=[-35, 0, 0], origin=[0, 7, -0.25])
    a.box("familier", "dorsale_bout", [-0.35, 8.5, 1], [0.35, 9.25, 2.25], dark, rot=[-35, 0, 0], origin=[0, 7, -0.25])
    # tête : melon arrondi, rostre fin, mâchoire claire, sourire, yeux sur les côtés
    a.bone("tete", [0, 5, -4.5], "familier")
    a.role("head", "tete")
    rond(a, "tete", "melon", [-2, 3.25, -8], [2, 7, -4], skin2, k=0.6)
    a.box("tete", "front", [-1.4, 4.5, -8.6], [1.4, 6.5, -7.5], skin2)
    a.box("tete", "rostre", [-0.85, 4.25, -11], [0.85, 5.1, -8], skin)
    a.box("tete", "machoire", [-0.75, 3.5, -10.75], [0.75, 4.25, -7.5], belly)
    a.box("tete", "sourire", [-0.9, 4.15, -10.8], [0.9, 4.35, -7.75], smile)
    for sx in (-1, 1):
        a.box("tete", f"sourire_coin_{sx}", [sx * 0.9 - 0.1, 4.25, -7.9], [sx * 0.9 + 0.1, 4.75, -7.4], smile)
        a.box("tete", f"oeil_{sx}", [sx * 2 - 0.15, 5.1, -7.1], [sx * 2 + 0.15, 6.1, -6.1], eye)
        a.box("tete", f"reflet_{sx}", [sx * 2.1 - 0.1, 5.75, -7.05], [sx * 2.1 + 0.1, 6.05, -6.75], shine)
    a.box("tete", "joue_g", [-2.05, 4.5, -6], [-1.95, 5, -5.25], a.mat("#F0A0B8"))
    a.box("tete", "joue_d", [1.95, 4.5, -6], [2.05, 5, -5.25], a.mat("#F0A0B8"))
    crown(a, [0, 7, -5.5], parent="tete")
    a.box("crown", "jet_1", [-0.4, 7, -5.9], [0.4, 9, -5.1], spray)
    a.box("crown", "jet_2", [-1.5, 9, -6], [-0.4, 9.9, -5], spray)
    a.box("crown", "jet_3", [0.4, 9.2, -6], [1.5, 10.1, -5], spray)
    a.box("crown", "goutte", [-0.35, 10.6, -5.85], [0.35, 11.3, -5.15], drop)
    # nageoires pectorales (battent comme des ailes)
    for side, sx in (("L", -1), ("R", 1)):
        n = limb(a, "wing_" + side, [sx * 2, 3.75, -2.5], "wings")
        x0, x1 = (sx * 5, sx * 2) if sx < 0 else (2, 5)
        a.box(n, n, [x0, 3.4, -3.25], [x1, 4.1, -1.25], dark, rot=[0, sx * 25, sx * -25], origin=[sx * 2, 3.75, -2.5])
    # pédoncule et nageoire caudale à plat
    limb(a, "tail", [0, 4.75, 6.25], "tail")
    a.box("tail", "queue", [-1, 3.75, 6], [1, 5.5, 9.5], skin, rot=[15, 0, 0], origin=[0, 4.75, 6.25])
    n = limb(a, "tail2", [0, 4, 9.25], "tail", parent="tail")
    a.box(n, "caudale_g", [-4, 3.6, 9.75], [-0.25, 4.25, 12], dark, rot=[15, 15, 0], origin=[0, 4, 9.25])
    a.box(n, "caudale_d", [0.25, 3.6, 9.75], [4, 4.25, 12], dark, rot=[15, -15, 0], origin=[0, 4, 9.25])
    a.box(n, "caudale_coeur", [-1, 3.55, 9], [1, 4.3, 11], dark, rot=[15, 0, 0], origin=[0, 4, 9.25])


@design
def kraken(a: Atelier):
    skin, sucker = a.mat("#8A2A48", "speck", "#A83A5A"), a.mat("#F0B0C0")
    hat = a.mat("#F4F4F4")
    face = a.face("#8A2A48", eye="#F2C230", eyes="round", glow_eyes=True, mouth="#3A0A18")
    base(a)
    head(a, [0, 13, 0])
    a.box("h_head", "tete", [-5, 8, -5], [5, 17, 5], skin, front=face)
    a.box("h_head", "front", [-4, 17, -4], [4, 19, 4], skin)
    crown(a, [0, 19, 0])
    a.box("crown", "casquette", [-3, 19, -3], [3, 20.5, 3], hat)
    a.box("crown", "visiere", [-3, 19, -5], [3, 19.5, -3], a.mat("#2A2A40"))
    a.box("crown", "ancre", [-0.5, 19.25, -3.25], [0.5, 20.25, -3], a.mat("#F2C230"))
    import math
    for i in range(8):
        ang = i * math.pi / 4
        x, z = round(4.5 * math.cos(ang), 2), round(4.5 * math.sin(ang), 2)
        n = limb(a, f"tentacule_{i}", [x, 8.5, z], "tail")
        a.box(n, n, [x - 1, 2, z - 1], [x + 1, 9, z + 1], skin)
        a.box(n, n + "_bout", [x - 0.6, 0, z - 0.6], [x + 0.6, 2, z + 0.6], sucker)


# ================================================================ DÉFENSEUR

@design
def ecuyer(a: Atelier):
    """Petit écuyer à la lance : la hampe dépasse largement (allonge), fanion rouge au bout."""
    tunic, helm, skin = a.mat("#3A6AD8", "checker", "#F2C230"), a.mat("#B8BEC8"), a.mat("#E8C8A0")
    belt, boots, wood = a.mat("#5A3A20"), a.mat("#5A4A3A"), a.mat("#8A6238")
    pennant, steel = a.mat("#D83030"), a.mat("#D8DEE8")
    face = a.face("#E8C8A0", eye="#1A1A1A", cheek="#F0A090", mouth="#7A3A2A")
    base(a)
    a.box("familier", "tunique", [-2.5, 2, -1.75], [2.5, 7, 1.75], tunic)
    a.box("familier", "ceinture", [-2.6, 3.5, -1.85], [2.6, 4.25, 1.85], belt)
    head(a, [0, 9.5, 0])
    a.box("h_head", "tete", [-2.75, 7, -2.75], [2.75, 12, 2.75], skin, front=face)
    crown(a, [0, 12, 0])
    a.box("crown", "heaume", [-3, 10.5, -3], [3, 13.5, 3], helm)
    a.box("crown", "nasal", [-0.4, 9.25, -3.25], [0.4, 12, -3], helm)
    a.box("crown", "plumet", [-0.5, 13.5, -1], [0.5, 16, 1.5], pennant, rot=[-20, 0, 0])
    for side, sx in (("L", -1), ("R", 1)):
        n = limb(a, "arm_" + side, [sx * 3, 6.5, 0], "arms")
        a.box(n, n, [sx * 3 - 0.6, 3, -0.6], [sx * 3 + 0.6, 6.75, 0.6], tunic)
    # lance tenue droite et penchée vers l'avant, bien plus longue que lui
    r, o = [-20, 0, 0], [3, 3, 0]
    a.box("arm_R", "poing", [2.4, 2.5, -0.7], [3.6, 3.5, 0.7], skin)
    a.box("arm_R", "hampe", [2.65, -1, -0.35], [3.35, 17, 0.35], wood, rot=r, origin=o)
    a.box("arm_R", "fer", [2.5, 17, -0.5], [3.5, 19.5, 0.5], steel, rot=r, origin=o)
    a.box("arm_R", "fer_pointe", [2.75, 19.5, -0.25], [3.25, 20.5, 0.25], steel, rot=r, origin=o)
    a.box("arm_R", "fanion", [3.35, 14, -0.1], [6.5, 16.5, 0.1], pennant, rot=r, origin=o)
    a.box("arm_R", "fanion_bande", [3.35, 14.8, -0.15], [6.5, 15.7, 0.15], a.mat("#F2C230"), rot=r, origin=o)
    legs(a, boots, xs=(-1.25, 1.25), h=2, w=1.5)


@design
def pavois(a: Atelier):
    """Pavois vivant : grand bouclier violet à couronne dorée, bras qui le tiennent, petits écus
    lumineux qui tournent autour de lui (le rempart qu'il dresse pour ses alliés)."""
    field, rim, boss = a.mat("#5B2D9E"), a.mat("#B8BEC8"), a.mat("#D8DEE8")
    gold, star = a.mat("#F0A800"), a.mat("#FFD94D", "glow")
    wood, aura = a.mat("#6A4426"), a.mat("#B376E5", "glow")
    face = a.face("#5B2D9E", eye="#F8F0FF", eyes="round", glow_eyes=True, cheek="#E060A0", mouth="#F8F0FF")
    base(a, 1)
    head(a, [0, 7, 0])
    # écu : champ violet, bordure d'acier, sommet en pointe, visage en haut, couronne dorée au centre
    a.box("h_head", "champ", [-4, 1.5, -0.75], [4, 12, 0.75], field)
    a.box("h_head", "visage", [-3, 7.5, -0.85], [3, 11.5, -0.75], field, front=face)
    a.box("h_head", "pointe", [-2.5, 0.25, -0.75], [2.5, 1.5, 0.75], field)
    a.box("h_head", "bord_g", [-4.5, 1, -1], [-3.75, 12.5, 1], rim)
    a.box("h_head", "bord_d", [3.75, 1, -1], [4.5, 12.5, 1], rim)
    a.box("h_head", "bord_haut", [-4.5, 12, -1], [4.5, 12.75, 1], rim)
    a.box("h_head", "bord_bas", [-3, -0.25, -1], [3, 0.5, 1], rim)
    a.box("h_head", "renfort", [-4, 0.5, -1], [-2.5, 1.5, 1], rim)
    a.box("h_head", "renfort_d", [2.5, 0.5, -1], [4, 1.5, 1], rim)
    a.box("h_head", "couronne_base", [-2, 3.25, -1.1], [2, 4, -0.75], gold)
    for i, x in enumerate((-1.75, -0.4, 1.05)):
        a.box("h_head", f"couronne_pic_{i}", [x, 4, -1.1], [x + 0.7, 5.5 if i == 1 else 4.9, -0.75], gold)
    a.box("h_head", "couronne_joyau", [-0.35, 3.4, -1.25], [0.35, 3.9, -1.05], a.mat("#C8256A", "glow"))
    a.box("h_head", "umbo", [-0.75, 5.75, -1.1], [0.75, 6.75, -0.75], boss)
    a.box("h_head", "sangle", [-3, 5, 0.75], [3, 6, 1.25], wood)
    # bras qui dépassent sur les côtés et le tiennent par la tranche
    for side, sx in (("L", -1), ("R", 1)):
        n = limb(a, "arm_" + side, [sx * 4.5, 6, 0], "arms")
        a.box(n, n, [sx * 4.5 - 0.6 if sx > 0 else sx * 6, 5.4, -0.6], [sx * 6 if sx > 0 else sx * 4.5 + 0.6, 6.6, 0.6],
              a.mat("#E8C8A0"))
    # petits écus lumineux en orbite (rôle « crown » : tour complet quand il est content)
    crown(a, [0, 7, 0])
    for i in range(4):
        ang = math.radians(45 + i * 90)
        x, z = 7 * math.cos(ang), 7 * math.sin(ang)
        y = 9 if i % 2 else 4
        a.box("crown", f"ecu_{i}", [x - 0.75, y - 1, z - 0.15], [x + 0.75, y + 1, z + 0.15], aura,
              rot=[0, -math.degrees(ang) + 90, 0], origin=[x, y, z])
        a.box("crown", f"etoile_{i}", [x - 0.3, y - 0.3, z - 0.3], [x + 0.3, y + 0.3, z + 0.3], star,
              rot=[45, 45, 0], origin=[x, y, z])
    legs(a, a.mat("#3A2A4A"), xs=(-1.75, 1.75), h=1, w=1.25, z=1.5)


@design
def chien_garde(a: Atelier):
    fur, light, plate, collar = a.mat("#C89A60"), a.mat("#E0C090"), a.mat("#A8AEB8"), a.mat("#3A2A20")
    face = a.face("#C89A60", eye="#140A04", eyes="dot", cheek="#E0B080")
    base(a, 4)
    a.box("familier", "corps", [-2.5, 4.5, -3.5], [2.5, 8.5, 4.5], fur)
    a.box("familier", "poitrail", [-2, 4, -4], [2, 8, -3], light)
    # caparaçon qui retombe sur les flancs
    a.box("familier", "armure_dos", [-2.75, 8.25, -2.5], [2.75, 9, 3.5], plate)
    for side, x in (("g", -2.75), ("d", 2.5)):
        a.box("familier", "armure_" + side, [x, 6, -2.5], [x + 0.25, 8.25, 3.5], plate)
    head(a, [0, 9, -3.5])
    a.box("h_head", "tete", [-2.25, 8, -7.5], [2.25, 12, -3.5], fur, front=face)
    a.box("h_head", "museau", [-1.25, 8, -9], [1.25, 10, -7.5], light)
    a.box("h_head", "truffe", [-0.5, 9.25, -9.25], [0.5, 10, -9], collar)
    a.box("h_head", "collier", [-2.5, 7.5, -4.25], [2.5, 8.5, -3], collar)
    crown(a, [0, 12, -5.5])
    for i, x in enumerate((-1.5, 1.5)):
        a.box("crown", f"oreille_{i}", [x - 0.6, 11.75, -5.5], [x + 0.6, 14, -4.75], a.mat("#8A6238"),
              rot=[0, 0, -15 if x < 0 else 15], origin=[x, 12, -5])
    limb(a, "tail", [0, 8, 4.5], "tail")
    a.box("tail", "queue", [-0.5, 8, 4.5], [0.5, 9, 8.5], fur, rot=[-40, 0, 0], origin=[0, 8.5, 4.5])
    for name, x, z in (("leg_FL", -1.5, -2.5), ("leg_FR", 1.5, -2.5), ("leg_BL", -1.5, 3.5), ("leg_BR", 1.5, 3.5)):
        n = limb(a, name, [x, 4.5, z], "legs")
        a.box(n, n, [x - 0.75, 0.75, z - 0.75], [x + 0.75, 4.75, z + 0.75], fur)
        a.box(n, n + "_patte", [x - 0.9, 0, z - 1.1], [x + 0.9, 0.75, z + 0.9], light)


@design
def chevalier_spectral(a: Atelier):
    ghost, armor, cape = a.mat("#A8E8F0", "glow"), a.mat("#6A7A8A"), a.mat("#3A2A6A")
    blade, dark = a.mat("#D8F8FF", "glow"), a.mat("#4A5868")
    face = a.face("#2A3A4A", eye="#8AF0FF", eyes="visor", glow_eyes=True)
    base(a, 6)
    a.box("familier", "torse", [-2.75, 7, -2], [2.75, 13, 2], armor)
    a.box("familier", "cape", [-3, 3, 2], [3, 13, 2.6], cape, rot=[8, 0, 0], origin=[0, 13, 2.3])
    for side, sx in (("g", -1), ("d", 1)):
        a.box("familier", "epauliere_" + side, [sx * 3 - 1.25, 11.5, -2.25], [sx * 3 + 1.25, 13.5, 2.25], dark)
    # bas du corps spectral qui s'effile
    a.box("familier", "spectre_1", [-2.25, 4, -1.5], [2.25, 7, 1.5], ghost)
    a.box("familier", "spectre_2", [-1.5, 1.5, -1], [1.5, 4, 1], ghost)
    a.box("familier", "spectre_3", [-0.75, -0.5, -0.5], [0.75, 1.5, 0.5], ghost)
    head(a, [0, 14, 0])
    a.box("h_head", "heaume", [-2.25, 13, -2.25], [2.25, 17.5, 2.25], armor, front=face)
    crown(a, [0, 17.5, 0])
    a.box("crown", "cimier", [-0.4, 17.5, -2], [0.4, 19.25, 2], ghost)
    # bras le long du corps, épée tenue en travers
    for side, sx in (("L", -1), ("R", 1)):
        n = limb(a, "arm_" + side, [sx * 3.25, 11.5, 0], "arms")
        a.box(n, n, [sx * 3.25 - 0.8, 7, -0.8], [sx * 3.25 + 0.8, 11.5, 0.8], armor)
    a.box("arm_R", "garde", [2.25, 6.75, -1.75], [4.25, 7.5, -1], a.mat("#F2C230"), rot=[0, 0, -30], origin=[3.25, 7, -1.4])
    a.box("arm_R", "lame", [2.9, 7.5, -1.75], [3.6, 17, -1], blade, rot=[0, 0, -30], origin=[3.25, 7, -1.4])

# ================================================================ BÂTISSEUR

@design
def marteau_piqueur(a: Atelier):
    """Marteau-piqueur jaune de chantier : poignées en T, burin d'acier, bouts de bitume qui volent."""
    body, dark = a.mat("#F2B830"), a.mat("#2A2A30")
    steel, steel2 = a.mat("#B8C0CA"), a.mat("#7A828E")
    hazard = a.mat("#F2B830", "stripes", "#2A2A30")
    asphalt, sherd = a.mat("#3A3A40", "speck", "#5A5A62"), a.mat("#B8643A")
    hose = a.mat("#1E1E24", "stripes", "#3A3A44")
    face = a.face("#F2B830", eye="#1A1408", eyes="round", cheek="#F07A3A", mouth="#5A3A08")
    base(a, 0)
    # burin et mandrin : la pointe sert de « pied »
    a.box("familier", "burin", [-0.6, 0, -0.6], [0.6, 3.5, 0.6], steel)
    a.box("familier", "pointe", [-0.35, -0.75, -0.35], [0.35, 0, 0.35], steel2)
    a.box("familier", "mandrin", [-1.5, 3, -1.5], [1.5, 4.25, 1.5], steel2)
    head(a, [0, 7, 0])
    a.box("h_head", "carter", [-2.75, 4.25, -2.5], [2.75, 10.5, 2.5], body, front=face)
    a.box("h_head", "bande", [-2.85, 4.25, -2.6], [2.85, 5.25, 2.6], hazard)
    a.box("h_head", "moteur", [-2.25, 7, 2.5], [2.25, 10, 3.75], dark)
    for i, y in enumerate((7.5, 8.5, 9.5)):
        a.box("h_head", f"ailette_{i}", [-2.4, y, 3.75], [2.4, y + 0.4, 4.1], steel2)
    crown(a, [0, 10.5, 0])
    a.box("crown", "colonne", [-0.75, 10.5, -0.75], [0.75, 12, 0.75], steel2)
    a.box("crown", "guidon", [-5, 12, -0.6], [5, 13, 0.6], steel)
    for side, x in (("g", -5.75), ("d", 3.75)):
        a.box("crown", "poignee_" + side, [x, 11.75, -0.85], [x + 2, 13.25, 0.85], dark)
    a.box("crown", "gachette", [3.75, 11.25, -0.4], [4.5, 11.75, 0.4], a.mat("#D83030"))
    # bras qui tiennent le guidon
    for side, sx in (("L", -1), ("R", 1)):
        n = limb(a, "arm_" + side, [sx * 2.75, 9, 0], "arms")
        a.box(n, n, [sx * 2.75 - 0.6, 8.5, -0.6], [sx * 2.75 + 0.6, 12, 0.6], body,
              rot=[0, 0, sx * -25], origin=[sx * 2.75, 9, 0])
    # tuyau d'air qui part du dos et ondule
    parent = "h_head"
    for i, (frm, to) in enumerate((([-0.6, 6, 3.75], [0.6, 7.2, 6.5]), ([-0.6, 3.5, 6], [0.6, 7.2, 7.2]),
                                   ([-0.6, 3, 7], [0.6, 4.2, 10]))):
        n = limb(a, f"tuyau_{i}", [0, (frm[1] + to[1]) / 2, frm[2]], "tail", parent=parent)
        a.box(n, n, frm, to, hose)
        parent = n
    # éclats de route qui volent autour du burin, dont un tesson de poterie
    for i, (x, y, z, s, m) in enumerate(((-2.5, 1.25, -1.5, 1.2, asphalt), (2.25, 0.75, -1, 1, asphalt),
                                         (-1.5, 0.5, 2, 0.9, asphalt), (2.5, 2, 1.75, 1.1, sherd))):
        a.box("familier", f"eclat_{i}", [x - s / 2, y - s / 3, z - s / 2], [x + s / 2, y + s / 3, z + s / 2], m,
              rot=[20 * (i + 1), 35 * i, 15])


@design
def betonniere(a: Atelier):
    """Bétonnière orange : cuve arrondie inclinée à bandes en spirale (elle tourne quand elle est
    contente), châssis à deux roues, béton frais au bord."""
    drum, drum2 = a.mat("#F07A2A"), a.mat("#C85A1A")
    frame, wheel = a.mat("#5A6270"), a.mat("#2A2A30")
    concrete, rim = a.mat("#9A9A98", "speck", "#B8B8B4"), a.mat("#3A3A40")
    face = a.face("#F2C230", eye="#1A1408", eyes="round", cheek="#F07A3A", mouth="#5A3A08")
    base(a, 0)
    # châssis : bloc moteur jaune avec le visage, longerons, timon, deux roues
    a.box("familier", "moteur", [-2.75, 2.25, -5.5], [2.75, 6.75, -2.25], a.mat("#F2C230"), front=face)
    a.box("familier", "moteur_grille", [-2, 6.75, -5], [2, 7.25, -2.75], frame)
    a.box("familier", "chassis", [-3, 1.75, -2.5], [3, 2.75, 5], frame)
    a.box("familier", "pied_avant", [-0.6, 0, -4.5], [0.6, 2.25, -3.5], frame)
    a.box("familier", "timon", [-0.5, 2, 5], [0.5, 2.75, 9], frame)
    a.box("familier", "timon_poignee", [-1.5, 2, 8.5], [1.5, 2.75, 9.25], wheel)
    for side, x in (("L", -3.75), ("R", 3.75)):
        n = limb(a, "leg_" + side, [x, 2, 2.5], "legs")
        a.box(n, n, [x - 0.6, 0, 1], [x + 0.6, 4, 4], wheel)
        a.box(n, n + "_b", [x - 0.6, 0.5, 0.5], [x + 0.6, 3.5, 4.5], wheel)
        a.box(n, n + "_moyeu", [x - 0.75, 1.5, 2], [x + 0.75, 2.5, 3], frame)
    # cuve inclinée vers l'avant (rôle « crown » : elle pivote, et fait un tour complet quand il est content)
    crown(a, [0, 7.5, 2], parent="familier")
    r, o = [-30, 0, 0], [0, 7.5, 2]
    a.box("crown", "cuve_fond", [-2.25, 3, 0], [2.25, 4, 4.5], drum2, rot=r, origin=o)
    rond(a, "crown", "cuve", [-3.5, 4, -1.25], [3.5, 10, 5.25], drum, k=1, rot=r, origin=o)
    rond(a, "crown", "cuve_col", [-2.5, 10, -0.25], [2.5, 11.75, 4.25], drum, k=0.75, rot=r, origin=o)
    rond(a, "crown", "ouverture", [-2, 11.75, 0.25], [2, 12.25, 3.75], rim, k=0.5, rot=r, origin=o)
    a.box("crown", "beton", [-1.25, 12, 0.75], [1.25, 12.5, 3.25], concrete, rot=r, origin=o)
    a.box("crown", "coulure", [-1.25, 9.75, -0.5], [0.25, 12.5, 0.25], concrete, rot=r, origin=o)
    # couronne dentée autour de la cuve, et deux pales qui dépassent de l'ouverture
    rond(a, "crown", "couronne", [-3.8, 6.25, -1.55], [3.8, 7.5, 5.55], frame, k=1.1, rot=r, origin=o)
    for i in range(8):
        ang = math.radians(i * 45)
        cx, cz = 3.9 * math.cos(ang), 2 + 3.9 * math.sin(ang)
        a.box("crown", f"dent_{i}", [cx - 0.35, 6.4, cz - 0.35], [cx + 0.35, 7.35, cz + 0.35], wheel, rot=r, origin=o)
    a.box("crown", "bande_haut", [-2.6, 9, -0.35], [2.6, 9.5, 4.35], drum2, rot=r, origin=o)


@design
def grue(a: Atelier):
    yellow, steel, cable = a.mat("#F2B020", "stripes", "#202020"), a.mat("#6A6E78"), a.mat("#303034")
    face = a.face("#F2B020", eye="#1A1408", cheek="#FF8A40", mouth="#1A1408")
    base(a)
    a.box("familier", "chenille_g", [-4, 0, -3.5], [-1.5, 2, 3.5], steel)
    a.box("familier", "chenille_d", [1.5, 0, -3.5], [4, 2, 3.5], steel)
    head(a, [0, 5, 0])
    a.box("h_head", "cabine", [-3, 2, -3], [3, 8, 3], yellow, front=face)
    a.box("h_head", "mat", [-1, 8, -1], [1, 17, 1], yellow)
    crown(a, [0, 17, 0])
    a.box("crown", "fleche", [-1, 16, -11], [1, 17.5, 4], yellow)
    a.box("crown", "contrepoids", [-1.75, 15, 3], [1.75, 17.5, 5.5], steel)
    limb(a, "tail", [0, 16, -10], "tail", "crown")
    a.box("tail", "cable", [-0.2, 10, -10.2], [0.2, 16, -9.8], cable)
    a.box("tail", "crochet", [-0.75, 9, -10.75], [0.75, 10.5, -9.25], steel)


@design
def architecte(a: Atelier):
    suit, helmet, skin = a.mat("#3A4A6A"), a.mat("#F2B020"), a.mat("#E0C0A0")
    paper, blue = a.mat("#3A6AD8", "checker", "#5A8AF0"), a.mat("#F4F4F8")
    face = a.face("#E0C0A0", eye="#1A1A1A", eyes="dot", cheek="#E0A090", mouth="#5A3A2A")
    base(a, 6)
    a.box("familier", "torse", [-4, 6, -2.5], [4, 13.5, 2.5], suit)
    a.box("familier", "gilet", [-4.1, 6, -2.6], [4.1, 8, 2.6], a.mat("#F27A20", "stripes", "#E8E8E8"))
    head(a, [0, 15.5, 0])
    a.box("h_head", "tete", [-3, 13.5, -3], [3, 18.5, 3], skin, front=face)
    crown(a, [0, 18.5, 0])
    a.box("crown", "casque", [-3.25, 18, -3.25], [3.25, 20.5, 3.25], helmet)
    a.box("crown", "visiere", [-3.25, 18, -4.75], [3.25, 18.75, -3.25], helmet)
    arms(a, suit, 12.5, 4, length=2.5, thick=2, parent="familier")
    a.box("arm_L", "plan", [-8, 6, -4], [-6.5, 7.5, 3], paper)
    a.box("arm_L", "plan_bout", [-8.1, 5.9, 3], [-6.4, 7.6, 3.4], blue)
    a.box("arm_R", "metre", [6.5, 7.5, -2], [8, 9, -0.5], helmet)
    legs(a, a.mat("#2A2A3A"), xs=(-2, 2), h=6, w=2.5)


# ================================================================ PRESTIGE

@design
def phenix(a: Atelier):
    plume, fire, gold = a.mat("#E83A20"), a.mat("#FFB020", "glow"), a.mat("#F2C230", "glow")
    face = a.face("#E83A20", eye="#2A0A04", cheek="#FF9A50", mouth="#2A0A04")
    base(a, 5)
    a.box("familier", "corps", [-2.5, 5, -2.5], [2.5, 10, 3.5], plume)
    head(a, [0, 11, -1])
    a.box("h_head", "tete", [-2, 10, -4.5], [2, 14, -0.5], plume, front=face)
    a.box("h_head", "bec", [-0.75, 11, -5.75], [0.75, 12.25, -4.5], gold)
    crown(a, [0, 14, -2])
    for i, (z, h) in enumerate(((-3, 4), (-1.5, 5), (0, 3.5))):
        a.box("crown", f"aigrette_{i}", [-0.4, 14, z], [0.4, 14 + h, z + 1], fire, rot=[-20, 0, 0], origin=[0, 14, z])
    for side, sx in (("L", -1), ("R", 1)):
        n = limb(a, "wing_" + side, [sx * 2.5, 9, 0], "wings")
        for k, (w, c) in enumerate(((9, plume), (7, fire), (5, gold))):
            x0, x1 = (sx * (2.5 + w), sx * 2.5) if sx < 0 else (2.5, 2.5 + w)
            a.box(n, f"{n}_{k}", [x0, 8.5 - k * 0.75, -1 + k], [x1, 9.25 - k * 0.75, 3 + k], c)
    limb(a, "tail", [0, 6, 3.5], "tail")
    for k, (r, c) in enumerate(((-20, fire), (-35, gold), (-5, plume))):
        a.box("tail", f"queue_{k}", [-0.75 + (k - 1) * 1.25, 5.5, 3.5], [0.75 + (k - 1) * 1.25, 6.25, 14 - k * 2], c,
              rot=[r, (k - 1) * 12, 0], origin=[0, 6, 3.5])
    for side, x in (("L", -1), ("R", 1)):
        n = limb(a, "leg_" + side, [x, 5, 0.5], "legs")
        a.box(n, n, [x - 0.4, 2, 0.1], [x + 0.4, 5, 0.9], gold)


@design
def astrolabe(a: Atelier):
    sun, core = a.mat("#FFB020", "speck", "#FFD94D"), a.mat("#FFF0A0", "glow")
    brass, brass2 = a.mat("#C89030"), a.mat("#F0A800", "stripes", "#B07818")
    face = a.face("#FFC23A", eye="#5A2A00", eyes="round", cheek="#FF8A3D", mouth="#5A2A00")
    base(a, 4)
    head(a, [0, 11, 0])
    # soleil au centre
    a.box("h_head", "soleil_x", [-3.5, 8.5, -2.5], [3.5, 13.5, 2.5], sun)
    a.box("h_head", "soleil_y", [-2.5, 7.5, -2.5], [2.5, 14.5, 2.5], sun)
    a.box("h_head", "soleil_z", [-2.5, 8.5, -3.5], [2.5, 13.5, 3.5], sun, front=face)
    for i, (x, y) in enumerate(((-3.5, 14.5), (3.5, 14.5), (-3.5, 7.5), (3.5, 7.5))):
        a.box("h_head", f"rayon_{i}", [x - 0.5, y - 0.5, -0.5], [x + 0.5, y + 0.5, 0.5], core, rot=[0, 0, 45])
    # anneau de l'écliptique (horizontal, tourne avec la couronne) et ses planètes
    crown(a, [0, 11, 0], parent="familier")
    n, r = 20, 8
    seg = 2 * math.pi * r / n + 0.3
    for k in range(n):
        ang = 360 * k / n
        x, z = r * math.sin(math.radians(ang)), -r * math.cos(math.radians(ang))
        a.box("crown", f"ecliptique_{k}", [x - seg / 2, 10.6, z - 0.4], [x + seg / 2, 11.4, z + 0.4],
              brass2 if k % 5 == 0 else brass, rot=[0, -ang, 0], origin=[x, 11, z])
    for i, (ang, size, col) in enumerate(((60, 2, "#B376E5"), (170, 1.5, "#2BC4E8"), (285, 2.5, "#E060A0"))):
        x, z = r * math.sin(math.radians(ang)), -r * math.cos(math.radians(ang))
        a.box("crown", f"planete_{i}", [x - size / 2, 11.4, z - size / 2], [x + size / 2, 11.4 + size, z + size / 2],
              a.mat(col, "speck"))
    a.box("crown", "anneau_planete", [r * math.sin(math.radians(285)) - 2, 12.4, -r * math.cos(math.radians(285)) - 0.3],
          [r * math.sin(math.radians(285)) + 2, 12.8, -r * math.cos(math.radians(285)) + 0.3], core)
    # méridien (vertical, de biais pour laisser voir le visage), balancé par les « bras »
    mer = limb(a, "arm_R", [0, 11, 0], "arms")
    n2, r2 = 18, 6.5
    seg2 = 2 * math.pi * r2 / n2 + 0.3
    for k in range(n2):
        ang = 360 * k / n2
        y, z = 11 + r2 * math.cos(math.radians(ang)), r2 * math.sin(math.radians(ang))
        # cercle dans le plan yz, puis le tout tourné de 50° autour de y
        c, s_ = math.cos(math.radians(50)), math.sin(math.radians(50))
        a.box(mer, f"meridien_{k}", [z * s_ - 0.35, y - seg2 / 2, z * c - 0.35], [z * s_ + 0.35, y + seg2 / 2, z * c + 0.35],
              brass, rot=[ang + 90, 50, 0], origin=[z * s_, y, z * c])
    # étoile au sommet et pied de l'instrument
    a.box(mer, "etoile_v", [-0.4, 17.5, -0.4], [0.4, 21, 0.4], core)
    a.box(mer, "etoile_h", [-1.75, 18.85, -0.4], [1.75, 19.65, 0.4], core)
    a.box("familier", "tige", [-0.6, 4, -0.6], [0.6, 7.5, 0.6], brass)
    a.box("familier", "socle", [-3, 2.5, -3], [3, 4, 3], a.mat("#3A1A6A", "speck", "#B376E5"))
    a.box("familier", "socle_bas", [-2, 1.5, -2], [2, 2.5, 2], brass)


@design
def kitsune(a: Atelier):
    fur, fur2, ice = a.mat("#F4F8FF"), a.mat("#D6E4F2"), a.mat("#8AE0FF", "glow")
    mark, nose = a.mat("#3AA8E8"), a.mat("#2A3A5A")
    face = a.face("#F4F8FF", eye="#2A6ACC", eyes="round", glow_eyes=True, cheek="#8AE0FF", mouth="#2A3A5A")
    base(a, 5)
    a.box("familier", "corps", [-2.25, 5, -3.5], [2.25, 9, 3.5], fur)
    a.box("familier", "collerette", [-2.75, 6.5, -4], [2.75, 10, -2], fur2)
    head(a, [0, 10, -3.5])
    a.box("h_head", "tete", [-2.75, 9, -8], [2.75, 13.5, -3.5], fur, front=face)
    a.box("h_head", "museau", [-1.25, 9, -10], [1.25, 10.75, -8], fur)
    a.box("h_head", "truffe", [-0.5, 10.25, -10.25], [0.5, 11, -9.75], nose)
    a.box("h_head", "marque", [-0.4, 12.75, -8.1], [0.4, 13.4, -7.9], mark)
    crown(a, [0, 13.5, -5.5])
    for side, x in (("g", -1.75), ("d", 1.75)):
        a.box("crown", "oreille_" + side, [x - 1, 13.5, -6.5], [x + 1, 16.5, -5], fur)
        a.box("crown", "oreille_bout_" + side, [x - 0.6, 16.5, -6.2], [x + 0.6, 17.5, -5.3], ice)
    # neuf queues en éventail, chacune ondule avec un léger décalage (rôle « tail »)
    for k in range(9):
        ang = -72 + k * 18
        n = limb(a, f"queue_{k}", [0, 8, 3.5], "tail")
        lean = -35 + abs(ang) * 0.15
        a.box(n, n, [-0.9, 8, 3], [0.9, 15, 4.8], fur, rot=[lean, 0, ang], origin=[0, 8, 3.5])
        a.box(n, n + "_touffe", [-1.25, 12.5, 2.65], [1.25, 16.5, 5.15], fur2, rot=[lean, 0, ang], origin=[0, 8, 3.5])
        a.box(n, n + "_givre", [-1, 16.5, 2.9], [1, 18.5, 4.9], ice, rot=[lean, 0, ang], origin=[0, 8, 3.5])
    for name, x, z in (("leg_FL", -1.4, -2.5), ("leg_FR", 1.4, -2.5), ("leg_BL", -1.4, 2.5), ("leg_BR", 1.4, 2.5)):
        n = limb(a, name, [x, 5, z], "legs")
        a.box(n, n, [x - 0.7, 1, z - 0.7], [x + 0.7, 5, z + 0.7], fur)
        a.box(n, n + "_patte", [x - 0.8, 0, z - 0.8], [x + 0.8, 1, z + 0.8], mark)


@design
def licorne(a: Atelier):
    """Licorne astrale : grosse tête ronde de poney (grands yeux noirs sur les côtés, bout du museau sombre),
    corne crème torsadée, crinière et queue arc-en-ciel pastel, robe blanc rosé, sabots gris."""
    coat, coat2 = a.mat("#F6EAF6"), a.mat("#EAD8EE")
    muzzle, nostril, mouth = a.mat("#F6E0EC"), a.mat("#B47C96"), a.mat("#D6A6BE")
    hoof, eye, shine = a.mat("#5A5466"), a.mat("#1E1A28"), a.mat("#FFFFFF")
    blush, inner = a.mat("#F6B4CC"), a.mat("#F4C4D8")
    horn, horn2 = a.mat("#F4E6BE"), a.mat("#E2C98E")
    rainbow = [a.mat(c) for c in ("#F7A6C4", "#F9C8A0", "#F6E6A0", "#B2E8C4", "#A8D0F6", "#C8B0F2")]
    base(a, 6)
    # corps trapu de poney, poitrail et croupe arrondis
    a.box("familier", "corps", [-3, 6, -4.5], [3, 11.5, 5], coat)
    a.box("familier", "poitrail", [-2.5, 6.75, -5.25], [2.5, 11, -4.5], coat)
    a.box("familier", "croupe", [-2.5, 7, 5], [2.5, 11.25, 5.6], coat)
    a.box("familier", "ventre", [-2.6, 5.8, -3.5], [2.6, 6.1, 4], coat2)
    # cou court et épais, droit, dans le prolongement du poitrail : la tête est posée dessus
    a.box("familier", "cou", [-2, 10, -6], [2, 15.5, -2.75], coat)
    # crinière : une bande arc-en-ciel continue, de l'arrière de la tête au garrot (couleurs de haut en bas)
    for i in range(6):
        y1 = 19.1 - i * 1.45
        w = 1.25 + 0.06 * i
        a.box("familier", f"criniere_{i}", [-w, y1 - 1.45, -2.95], [w, y1, -1.9], rainbow[i])
    a.box("familier", "criniere_bas", [-1.4, 9.8, -2.6], [1.4, 10.4, -1.2], rainbow[5])
    # marque astrale : petite étoile dorée sur chaque flanc de la croupe
    star = a.mat("#F6D46A", "glow")
    for sx in (-1, 1):
        x0 = 3 if sx > 0 else -3.15
        a.box("familier", f"etoile_{sx}", [x0, 8.6, 2.6], [x0 + 0.15, 9.4, 3.4], star)
        a.box("familier", f"etoile_h_{sx}", [x0, 8.85, 2.2], [x0 + 0.15, 9.15, 3.8], star)
        a.box("familier", f"etoile_v_{sx}", [x0, 8.2, 2.85], [x0 + 0.15, 9.8, 3.15], star)
    # tête : grosse, presque cubique, museau clair au bout sombre, yeux sur les côtés
    head(a, [0, 15, -4.5])
    a.box("h_head", "tete", [-2.5, 13.5, -10], [2.5, 18.5, -2.75], coat)
    # museau rose pâle un peu plus étroit, arrondi sur le dessus, deux narines et une bouche discrète
    a.box("h_head", "museau", [-1.9, 13.5, -12], [1.9, 16.25, -10], muzzle)
    a.box("h_head", "museau_haut", [-1.6, 16.25, -11.6], [1.6, 16.7, -10], muzzle)
    for sx in (-1, 1):
        a.box("h_head", f"narine_{sx}", [sx * 0.95 - 0.35, 14.9, -12.08], [sx * 0.95 + 0.35, 15.6, -11.98], nostril)
    a.box("h_head", "bouche", [-0.8, 13.95, -12.06], [0.8, 14.2, -11.98], mouth)
    for sx in (-1, 1):
        x0 = 2.45 if sx > 0 else -2.65
        a.box("h_head", f"oeil_{sx}", [x0, 15.2, -8.75], [x0 + 0.2, 17.4, -6.75], eye)
        a.box("h_head", f"reflet_{sx}", [x0 + (0.02 if sx > 0 else -0.02), 16.55, -8.55],
              [x0 + 0.2 + (0.02 if sx > 0 else -0.02), 17.15, -7.95], shine)
        a.box("h_head", f"joue_{sx}", [x0, 14.25, -7.75], [x0 + 0.2, 14.85, -6.5], blush)
        a.box("h_head", f"oreille_{sx}", [sx * 1.6 - 0.6, 18.5, -5.1], [sx * 1.6 + 0.6, 20, -4.1], coat,
              rot=[0, 0, sx * -12], origin=[sx * 1.6, 18.5, -4.6])
        a.box("h_head", f"oreille_in_{sx}", [sx * 1.6 - 0.3, 18.6, -5.2], [sx * 1.6 + 0.3, 19.6, -5.05], inner,
              rot=[0, 0, sx * -12], origin=[sx * 1.6, 18.5, -4.6])
    # corne droite, anneaux crème alternés (effet torsadé), tous sur le même axe légèrement penché en avant
    crown(a, [0, 18.5, -7.75])
    for i, (w, h) in enumerate(((1.7, 1.2), (1.35, 1.15), (1.05, 1.1), (0.75, 1.05), (0.45, 0.9))):
        y0 = 18.4 + sum(hh for _, hh in ((1.7, 1.2), (1.35, 1.15), (1.05, 1.1), (0.75, 1.05))[:i])
        a.box("crown", f"corne_{i}", [-w / 2, y0, -7.75 - w / 2], [w / 2, y0 + h, -7.75 + w / 2],
              horn if i % 2 == 0 else horn2, rot=[-8, 0, 0], origin=[0, 18.5, -7.75])
    a.box("crown", "meche_1", [-1.4, 17.6, -6.9], [1.4, 18.9, -5.6], rainbow[0])
    a.box("crown", "meche_2", [-1.1, 16.9, -7.3], [0.6, 18.2, -6.7], rainbow[1])
    # queue arc-en-ciel en cascade
    limb(a, "tail", [0, 10.5, 5.5], "tail")
    for i in range(5):
        y = 9.6 - i * 1.6
        w = 1.0 + 0.15 * i
        a.box("tail", f"queue_{i}", [-w, y - 0.9, 5.4 + i * 0.35], [w, y + 0.9, 7.2 + i * 0.35], rainbow[(i + 2) % 6],
              rot=[22, 0, 0], origin=[0, 10.5, 5.5])
    for name, x, z in (("leg_FL", -1.9, -3), ("leg_FR", 1.9, -3), ("leg_BL", -1.9, 3.8), ("leg_BR", 1.9, 3.8)):
        n = limb(a, name, [x, 6.5, z], "legs")
        a.box(n, n, [x - 1, 1.2, z - 1], [x + 1, 6.5, z + 1], coat)
        a.box(n, n + "_sabot", [x - 1.1, 0, z - 1.1], [x + 1.1, 1.25, z + 1.1], hoof)


@design
def leviathan(a: Atelier):
    scale_, belly, fin = a.mat("#1A4A8A", "checker", "#2A5A9A"), a.mat("#8AD8E8"), a.mat("#3AE0C8", "glow")
    face = a.face("#1A4A8A", eye="#F8F04A", eyes="round", glow_eyes=True, mouth="#0A1A30")
    base(a, 8)
    head(a, [0, 11, -4])
    a.box("h_head", "tete", [-3.5, 8.5, -9], [3.5, 14, -2.5], scale_, front=face)
    a.box("h_head", "machoire", [-2.75, 7.5, -10], [2.75, 9.5, -6], belly)
    crown(a, [0, 14, -5])
    for side, x in (("l", -3.5), ("r", 3.5)):
        a.box("crown", "nageoire_" + side, [x - 0.4, 12, -6], [x + 0.4, 16.5, -2], fin,
              rot=[-20, 0, 18 if x < 0 else -18], origin=[x, 13, -4])
    a.box("crown", "crete", [-0.4, 14, -7], [0.4, 17.5, -2], fin, rot=[-15, 0, 0], origin=[0, 14, -4])
    parent, z, y, s = "familier", -2.5, 11, 5.5
    for i in range(6):
        n = f"seg_{i}"
        a.bone(n, [0, y, z], parent)
        a.role("tail", n)
        a.box(n, n, [-s / 2, y - s / 2, z], [s / 2, y + s / 2, z + 4], scale_)
        a.box(n, n + "_ventre", [-s / 2 + 0.5, y - s / 2 - 0.25, z + 0.5], [s / 2 - 0.5, y - s / 2, z + 3.5], belly)
        a.box(n, n + "_aileron", [-0.4, y + s / 2, z + 0.5], [0.4, y + s / 2 + 2, z + 3], fin)
        parent, z, y, s = n, z + 4, y - 1, max(2.0, s - 0.6)


@design
def faucheur(a: Atelier):
    robe, void, bone = a.mat("#1A1424"), a.mat("#3A1A5A", "glow"), a.mat("#E8E4D8")
    blade = a.mat("#B8F0FF", "glow")
    face = a.face("#0A0810", eye="#B7FF5A", eyes="round", glow_eyes=True)
    base(a, 6)
    a.box("familier", "robe", [-3.5, 1, -2.5], [3.5, 12, 3], robe)
    a.box("familier", "robe_bas", [-2.5, -2, -1.5], [2.5, 1, 2], void)
    head(a, [0, 14, 0])
    a.box("h_head", "capuche", [-3.5, 11, -3], [3.5, 17.5, 3.5], robe, front=face)
    a.box("h_head", "pointe", [-1.5, 17.5, 0], [1.5, 19.5, 3], robe, rot=[-20, 0, 0], origin=[0, 17.5, 1.5])
    crown(a, [0, 19.5, 0])
    a.box("crown", "orbe", [-0.6, 19.5, -2.5], [0.6, 20.7, -1.3], void)
    arms(a, robe, 10, 3.5, length=2, thick=1.75, parent="familier")
    a.box("arm_R", "manche", [5, 1, -0.4], [6, 18, 0.4], bone)
    a.box("arm_R", "lame", [0, 16, -0.4], [6, 17.5, 0.4], blade, rot=[0, 0, -12], origin=[5.5, 17, 0])


@design
def nida(a: Atelier):
    stone, stone2 = a.mat("#6A6478", "speck", "#8A82A0"), a.mat("#4E4A5C", "speck", "#6A6478")
    gold, rift = a.mat("#F0A800", "glow"), a.mat("#B376E5", "glow")
    rift_dark = a.mat("#5B2D9E", "glow")
    face = a.face("#6A6478", eye="#FFD94D", eyes="visor", glow_eyes=True)
    base(a, 0)
    # torse massif, cœur de brèche au milieu
    a.box("familier", "torse", [-4, 5, -2.5], [4, 12, 2.5], stone)
    a.box("familier", "ventre", [-3, 3.5, -2], [3, 5, 2], stone2)
    a.box("familier", "coeur", [-1.25, 8, -2.75], [1.25, 10.5, -2.5], rift)
    a.box("familier", "fissure_1", [-2.5, 6, -2.6], [-1.75, 9, -2.5], rift_dark)
    a.box("familier", "fissure_2", [-3.5, 9.5, -2.6], [-2, 10.25, -2.5], rift_dark)
    head(a, [0, 12, 0])
    a.box("h_head", "tete", [-2.5, 12, -2.5], [2.5, 16, 2], stone, front=face)
    crown(a, [0, 16, 0])
    # couronne brisée : une pointe manque, côté gauche
    a.box("crown", "bandeau", [-2.75, 16, -2.75], [2.75, 16.75, 2.25], gold)
    for i, x in enumerate((0, 2)):
        a.box("crown", f"pointe_{i}", [x - 0.5, 16.75, -2.75], [x + 0.5, 18.25 - i * 0.5, -2.25], gold)
    a.box("crown", "pointe_cassee", [-2.5, 16.75, -2.75], [-1.5, 17.25, -2.25], gold)
    # bras droit : épaulière, gros avant-bras, bracelet d'or, poing
    arm = limb(a, "arm_R", [4, 11, 0], "arms")
    a.box(arm, "epaule", [4, 9.5, -2], [7, 12.5, 2], stone2)
    a.box(arm, "bras", [4.5, 4, -1.5], [7, 9.5, 1.5], stone)
    a.box(arm, "bracelet", [4.25, 4.5, -1.75], [7.25, 5.75, 1.75], gold)
    a.box(arm, "poing", [4.25, 1, -2], [7.25, 4, 2], stone2)
    a.box(arm, "rune", [7.05, 6.5, -0.5], [7.25, 8.5, 0.5], rift)
    # épaule gauche arrachée : moignon fissuré d'où s'échappe la brèche
    a.box("familier", "moignon", [-5.5, 9.5, -1.75], [-4, 11.5, 1.75], stone2)
    a.box("familier", "moignon_breche", [-5.75, 9.75, -1.25], [-5.5, 11.25, 1.25], rift)
    wisp = limb(a, "wing_L", [-6, 10.5, 0], "wings")
    for i, (x, y, z, r) in enumerate(((-6.75, 10.5, 0, 1), (-8, 11.5, 0.75, 0.75), (-9, 10, -0.5, 0.6), (-10, 11.25, 0.25, 0.4))):
        a.box(wisp, f"eclat_{i}", [x - r / 2, y - r / 2, z - r / 2], [x + r / 2, y + r / 2, z + r / 2],
              rift if i % 2 == 0 else rift_dark, rot=[45, 45, 0])
    legs(a, stone2, xs=(-2, 2), h=3.5, w=2.5)


# ---------------------------------------------------------------- façons de se déplacer (walk sur mesure)
# Familiers au sol concernés : à lister aussi dans familiers_sol.ANIMATIONS_SOL.
WALK_STYLES = {
    # par bonds
    "caillou": lambda a: walk_hop(a, 4, 0.6),
    "coquelicot": lambda a: walk_hop(a, 5, 0.6),
    "epouvantail": lambda a: walk_hop(a, 5, 0.7),
    "pepite": lambda a: walk_hop(a, 4, 0.55),
    "lapin_lapis": lambda a: walk_hop(a, 6, 0.6),
    "lievre_neiges": lambda a: walk_hop(a, 6, 0.6),
    "mimique": lambda a: walk_hop(a, 4, 0.65),
    "gland": lambda a: walk_hop(a, 4, 0.55),
    "champinou": lambda a: walk_hop(a, 4, 0.7, startle=True),
    # grandes foulées
    "chien_garde": lambda a: walk_stride(a, 34, 0.7),
    "ecuyer": lambda a: walk_stride(a, 34, 0.7, arms=10),
    "architecte": lambda a: walk_stride(a, 42, 0.9, arms=40, twist=8, lean=4),
    "licorne": lambda a: walk_stride(a, 32, 0.8),
}


def _with_walk(design_fn, style):
    def wrapped(a):
        design_fn(a)
        style(a)
    wrapped.__name__ = design_fn.__name__
    wrapped.__doc__ = design_fn.__doc__
    return wrapped


for _pid, _style in WALK_STYLES.items():
    DESIGNS[_pid] = _with_walk(DESIGNS[_pid], _style)

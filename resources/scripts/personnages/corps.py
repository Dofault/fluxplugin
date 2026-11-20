"""Constructeur de personnages humanoïdes de l'orchestre : morphologie libre (jambes, torse, tête, bras),
visage peint, coiffures et chapeaux, bras qui visent un point de l'instrument, baguettes tenues en main.

Os : root > jambe_gauche, jambe_droite, corps > tete, bras_gauche > bras_gauche_pose, bras_droit > ...
"""
from voxel import T, aim, apply, dist, key, pal, rgb, rot_matrix, stamp, tr

EYE_W, PUPIL = rgb("#E9DDC9"), rgb("#302A27")
SKINS = {
    "clair": pal("#B77F68", "#D69F83", "#E9B99B"),
    "pale": pal("#BE9482", "#E0BBA3", "#F0D3BE"),
    "hale": pal("#9C6A50", "#BD8A68", "#D6A683"),
    "brun": pal("#6B4838", "#8C6248", "#A87B5C"),
    "olive": pal("#8E7256", "#B0926E", "#CBB088"),
}
SIDES = (("gauche", -1), ("droit", 1))


def rows_fill(g, color, rows):
    for y in rows:
        if 0 <= y < len(g):
            g[y] = [color] * len(g[0])


class Corps:
    def __init__(self, m, skin, legs, boots, torso, head, arm, belt=None, splay=4, head_rot=None):
        """legs = (l, h, p, écart, palette) ; boots = (h, palette, avancée) ; torso = (l, h, p, palette) ;
        head = (l, h, p) ; arm = (l, p, palette) ; belt = (h, palette, palette de boucle ou None)."""
        self.m, self.skin = m, SKINS[skin] if isinstance(skin, str) else skin
        self.lw, self.lh, self.ld, self.gap, self.lpal = legs
        self.bh, self.bpal, self.bfront = boots
        self.tw, self.th, self.td, self.tpal = torso
        self.hw, self.hh, self.hd = head
        self.aw, self.ad, self.apal = arm
        self.belt = belt
        self.hips = self.bh + self.lh
        self.waist = self.hips + (belt[0] if belt else 0)
        self.top = self.waist + self.th                    # dessus du torse = dessous de la tête
        self.head_top = self.top + self.hh
        self.front = -self.td / 2                          # face avant du torse
        self.face_z = -self.hd / 2
        self.sx = self.tw / 2 + self.aw / 2                # x du pivot d'épaule
        self.sy = self.top - 1.5
        self.splay = splay
        m.bone("jambe_gauche", (-(self.gap + self.lw) / 2, self.hips, 0), cube_rot=(0, splay, 0) if splay else None)
        m.bone("jambe_droite", ((self.gap + self.lw) / 2, self.hips, 0), cube_rot=(0, -splay, 0) if splay else None)
        m.bone("corps", (0, self.hips, 0))
        m.bone("tete", (0, self.top, 0), "corps", cube_rot=head_rot)
        self.hand, self.pose = {}, {}

    # ------------------------------------------------------------------ jambes
    def jambes(self, sole=None, cuff=None, paint=None, boot_paint=None):
        m, kP, kB = self.m, key(self.lpal), key(self.bpal)
        sole = sole or tuple(int(c * 0.78) for c in self.bpal[0][:3]) + (255,)

        def leg(f, g):
            if f not in ("up", "down"):
                g[0] = [self.lpal[0]] * len(g[0])
            if f == "north" and len(g) >= 4:
                stamp(g, ["l"] * min(3, len(g) - 2), kP, 0, 1)
            if paint:
                paint(f, g)

        def boot(f, g):
            w, h = len(g[0]), len(g)
            if f in ("north", "south", "east", "west"):
                g[-1] = [sole] * w
                if cuff:
                    g[0] = [cuff] * w
            if f == "north" and h >= 2:
                g[0 if not cuff else min(1, h - 2)][1:w - 1] = [self.bpal[1]] * (w - 2)
            if f == "up":
                for y in range(min(self.bfront, h)):
                    g[y] = [self.bpal[1]] * w
                    g[y][0] = g[y][-1] = self.bpal[0]
                if h > 1 and w > 2:
                    g[min(1, self.bfront - 1)][1] = self.bpal[2]
            if boot_paint:
                boot_paint(f, g)
        for side, s in SIDES:
            cx = s * (self.gap + self.lw) / 2
            x0, x1 = cx - self.lw / 2, cx + self.lw / 2
            jb = "jambe_" + side + ("e" if side == "droit" else "")
            m.box(jb, jb, (x0, self.bh, -self.ld / 2), (x1, self.hips, self.ld / 2),
                  self.lpal, leg, top=0)
            out = 1 if self.lw <= 3 else 0
            bx0, bx1 = (x0 - out, x1) if s < 0 else (x0, x1 + out)
            m.box(jb, "botte_" + side, (bx0, 0, -self.ld / 2 - self.bfront), (bx1, self.bh, self.ld / 2),
                  self.bpal, boot, noise=0)

    # ------------------------------------------------------------------ torse
    def torse(self, front=None, back=None, extra=None, paint=None, buckle_x=None, **opts):
        m, k = self.m, dict(key(self.tpal), **(extra or {}))

        def p(f, g):
            if f == "north" and front:
                stamp(g, front, k)
            if f == "south" and back:
                stamp(g, back, k)
            if paint:
                paint(f, g)
        m.box("corps", "torse", (-self.tw / 2, self.waist, -self.td / 2), (self.tw / 2, self.top, self.td / 2),
              self.tpal, p, **opts)
        if self.belt:
            bh, bp, buckle = self.belt

            def b(f, g):
                if f == "north" and buckle:
                    x = (len(g[0]) // 2 - 1) if buckle_x is None else buckle_x
                    for y in range(len(g)):
                        g[y][x] = buckle[2] if y == 0 else buckle[0]
                        g[y][x + 1] = buckle[1]
            m.box("corps", "ceinture", (-self.tw / 2, self.hips, -self.td / 2), (self.tw / 2, self.waist, self.td / 2),
                  bp, b, top=0 if bh < 2 else 1, bot=0 if bh < 2 else 1, noise=0.15)

    def jupe(self, h, p, grow=1, paint=None, name="jupe"):
        """Pan de tunique sous la ceinture, plus large que le torse (les jambes restent à l'intérieur)."""
        self.m.box("corps", name, (-self.tw / 2 - grow, self.hips - h, -self.td / 2 - grow / 2),
                   (self.tw / 2 + grow, self.hips, self.td / 2 + grow / 2), p, paint, top=0)

    # ------------------------------------------------------------------ tête
    def tete(self, eye="#65463A", hair=None, beard=None, stache=None, cheek="#D98C7E", mouth="#9A6451",
             eyes="std", brows=False, paint=None, noise=0.05):
        """Visage peint sur la face avant. eyes : std, clos, rond ; beard / stache : palettes de poils."""
        S, w, h = self.skin, self.hw, self.hh
        iris = rgb(eye)

        def p(f, g):
            if f == "north":
                ey, wide = h - 5, w >= 7
                cols = ((1, 2), (w - 3, w - 2)) if wide else ((1,), (w - 2,))
                for y in range(h):
                    for x in range(w):
                        g[y][x] = S[2] if y < ey else S[1]
                    if y >= h - 2:
                        g[y][0] = g[y][-1] = S[0]
                g[h - 1] = [S[0], S[0]] + [S[1]] * (w - 4) + [S[0], S[0]]
                for i, c in enumerate(cols):
                    dark = c[-1] if i == 0 else c[0]
                    if eyes == "clos":
                        for x in c:
                            g[ey + 1][x] = PUPIL
                    else:
                        for x in c:
                            g[ey][x] = g[ey + 1][x] = EYE_W
                        g[ey][dark], g[ey + 1][dark] = PUPIL, iris
                        if eyes == "rond" and wide:   # lunettes : cerclage autour de l'œil
                            for x in c:
                                g[ey - 1][x] = g[ey + 2][x] = rgb("#8A6A3E")
                    if brows and hair:
                        for x in c:
                            g[ey - 1][x] = hair[0]
                if cheek:
                    g[h - 3][cols[0][0]] = g[h - 3][cols[1][-1]] = rgb(cheek)
                mid = (w - 2) // 2
                if mouth:
                    g[h - 2][mid] = g[h - 2][mid + 1] = rgb(mouth)
                if beard:
                    for y in (h - 2, h - 1):
                        g[y] = [beard[1]] * w
                    g[h - 1] = [beard[0]] * w
                    g[h - 3][0] = g[h - 3][-1] = beard[1]
                    if mouth:
                        g[h - 2][mid] = g[h - 2][mid + 1] = rgb(mouth)
                    g[h - 2][1] = g[h - 2][w - 2] = beard[2]
                if stache:
                    for x in range(mid - 1, mid + 3):
                        g[h - 3][x] = stache[1]
                    g[h - 3][mid - 1] = g[h - 3][mid + 2] = stache[0]
            elif f == "south" and hair:
                for y in range(h - 2):
                    g[y] = [hair[0]] * w
            elif beard and f in ("east", "west"):
                for y in (h - 2, h - 1):
                    g[y] = [beard[0 if y == h - 1 else 1]] * len(g[0])
            if paint:
                paint(f, g)
        self.m.box("tete", "tete", (-w / 2, self.top, -self.hd / 2), (w / 2, self.head_top, self.hd / 2), S, p,
                   noise=noise)

    def cheveux(self, p, front=2, side=4, back=None, fringe="mld m  dm", open_cols=2, above=1, band=None, noise=0.15):
        """Coque de cheveux gonflée de 0,5. front : rangées pleines sur le front (à partir du haut de la coque),
        fringe : rangée de mèches sous elles (' ' = trou), side / back : hauteur sur les côtés et la nuque,
        open_cols : colonnes dégagées près du visage, band : (rangée, palette) bandeau peint."""
        back = back or side
        hgt = max(side, back) + above
        w, d, k = self.hw + 1, self.hd + 1, key(p)
        y1 = self.head_top + above
        blank = " " * 40

        def paint(f, g):
            n = len(g[0])
            if f == "north":
                fr = (fringe * 3)[:n] if fringe else blank[:n]
                stamp(g, [fr], k, 0, front)
                for y in range(front + 1, len(g)):
                    g[y] = [T] * n
            elif f in ("east", "west"):
                for y in range(len(g)):
                    cut = open_cols if y >= front + 1 else 0
                    if y >= side + above:
                        cut = n
                    elif y == side + above - 1 and n > cut + 3:   # bas des pattes : une mèche détachée
                        cut += 1
                    for x in range(cut):
                        g[y][x if f == "west" else n - 1 - x] = T
            elif f == "south":
                for y in range(back + above, len(g)):
                    g[y] = [T] * n
            elif f == "down":
                for y in range(len(g) - 2, len(g)):
                    g[y] = [T] * n
            if band and f != "up" and f != "down":
                for x in range(n):
                    if g[band[0]][x] != T:
                        g[band[0]][x] = band[1][1 if f in ("north", "east") else 0]
        self.m.box("tete", "cheveux", (-w / 2, y1 - hgt, -d / 2), (w / 2, y1, d / 2), p, paint, noise=noise)

    # ------------------------------------------------------------------ chapeaux
    def bord(self, p, y, h=1, grow=1.0, front=0, name="chapeau_bord", paint=None, rot=None, **o):
        """Plateau débordant de la tête (bord de chapeau, bandeau de casquette)."""
        w, d = self.hw / 2 + 0.5 + grow, self.hd / 2 + 0.5 + grow
        return self.m.box("tete", name, (-w, y, -d - front), (w, y + h, d), p, paint, rot=rot,
                          origin=(0, y, 0) if rot else None, **o)

    def calotte(self, p, y, h, shrink=0.0, name="chapeau", paint=None, rot=None, dz=0, **o):
        w, d = self.hw / 2 + 0.5 - shrink, self.hd / 2 + 0.5 - shrink
        return self.m.box("tete", name, (-w, y, -d + dz), (w, y + h, d + dz), p, paint, rot=rot,
                          origin=(0, y, 0) if rot else None, **o)

    # ------------------------------------------------------------------ bras
    def bras(self, tl, tr_, cuff=None, bare=0, band=None, lmin=5, lmax=11, paint=None, hand=None):
        """Bras droits qui visent les points tl / tr_ (monde). cuff : couleur de poignet, bare : rangées de peau
        (manches retroussées), band : (rangée depuis le bas, couleur) bracelet."""
        m, S = self.m, self.skin
        for (side, s), target in zip(SIDES, (tl, tr_)):
            piv = (s * self.sx, self.sy, 0)
            D = dist(piv, target)
            L = int(round(max(lmin, min(lmax, D))))
            if abs(D - L) > 1.2:
                print(f"  ! {m.name} bras {side} : cible à {D:.1f}, bras de {L}")
            rot = aim(piv, target)
            m.bone("bras_" + side, piv, "corps")
            m.bone("bras_" + side + "_pose", piv, "bras_" + side, rotation=rot)
            m.arm_len[side] = L
            self.pose[side] = rot_matrix(*rot)

            def p(f, g, s=s):
                n = len(g)
                if f not in ("up", "down"):
                    for i in range(bare):
                        g[n - 1 - i] = [S[0] if (f in ("west", "south") or i == 0) else S[1]] * len(g[0])
                    if bare and n - 1 - bare >= 0:
                        g[n - 1 - bare] = [self.apal[2]] * len(g[0])
                    if cuff:
                        g[n - 1] = [cuff] * len(g[0])
                        if n > 2:
                            g[n - 2] = [self.apal[0]] * len(g[0])
                    if band:
                        g[n - 1 - band[0]] = [band[1]] * len(g[0])
                if paint:
                    paint(f, g)
            x = s * self.sx
            m.box("bras_" + side + "_pose", "bras_" + side, (x - self.aw / 2, self.sy + 1.5 - L - 0.5, -self.ad / 2),
                  (x + self.aw / 2, self.sy + 1.5, self.ad / 2), self.apal, p, bot=0)
            hw_, hd_ = max(2, self.aw - 1), max(2, self.ad - 1)
            c = (x, self.sy - L, 0)
            self.hand[side] = c
            m.box("bras_" + side + "_pose", "main_" + side, (x - hw_ / 2, c[1] - 1, -hd_ / 2),
                  (x + hw_ / 2, c[1] + 1, hd_ / 2), hand or S, noise=0)

    def baton(self, side, direction, length, p, thick=1, head=None, name="baguette", paint=None, head_paint=None):
        """Baguette tenue dans la main, pointant dans une direction monde. head : (taille, palette) de la tête.
        Renvoie le bout (coordonnées de repos de l'os de pose), à suivre avec m.point(...)."""
        n = dist(direction, (0, 0, 0))
        d = apply(tr(self.pose[side]), [v / n for v in direction])
        c = self.hand[side]
        rot = aim((0, 0, 0), d)
        bone = "bras_" + side + "_pose"
        self.m.box(bone, name + "_" + side, (c[0] - thick / 2, c[1] - length, c[2] - thick / 2),
                   (c[0] + thick / 2, c[1] + 1, c[2] + thick / 2), p, paint, rot=rot, origin=c, noise=0)
        if head:
            hs, hp = head[:2]
            hh = head[2] if len(head) > 2 else hs
            self.m.box(bone, name + "_tete_" + side, (c[0] - hs / 2, c[1] - length - hh + 0.5, c[2] - hs / 2),
                       (c[0] + hs / 2, c[1] - length + 0.5, c[2] + hs / 2), hp, head_paint, rot=rot, origin=c, noise=0)
            length += hh - 0.5
        return [c[i] + d[i] * length for i in range(3)]

    def bout(self, side, tip, anim=None, p=0.0):
        return self.m.point("bras_" + side + "_pose", tip, anim, p)

    def tip_at(self, side, tip, target):
        """Position monde d'un point de l'os de pose quand le bras vise target (corps immobile, sans étirement)."""
        s = -1 if side == "gauche" else 1
        piv = (s * self.sx, self.sy, 0)
        w = apply(rot_matrix(*aim(piv, target)), [tip[i] - piv[i] for i in range(3)])
        return [piv[i] + w[i] for i in range(3)]

    def contact(self, side, tip, t0, direction, gap):
        """Amplitude A telle que le bout touche (gap(point) = 0) quand la main vise t0 + A * direction."""
        lo, hi = 0.0, 8.0
        for _ in range(40):
            mid = (lo + hi) / 2
            if gap(self.tip_at(side, tip, [t0[i] + mid * direction[i] for i in range(3)])) > 0:
                lo = mid
            else:
                hi = mid
        return lo

    def avance(self, side, dz):
        """Avance un pied (jambe et botte glissées en z)."""
        for e in self.m.elements:
            if e["_bone"] == "jambe_" + side + ("e" if side == "droit" else ""):
                e["from"][2] += dz
                e["to"][2] += dz
                e["origin"][2] += dz


class Inst:
    """Instrument : cubes décrits dans un repère local (pivot + rotation fixe), dans un os à lui."""

    def __init__(self, m, bone, pivot, rot=(0, 0, 0), parent="corps"):
        self.m, self.bone, self.pivot, self.rot = m, bone, list(pivot), rot
        self.R = rot_matrix(*rot)
        m.bone(bone, pivot, parent, cube_rot=rot if any(rot) else None)

    def box(self, name, a, b, p, paint=None, bone=None, **o):
        P = self.pivot
        return self.m.box(bone or self.bone, name, [P[i] + a[i] for i in range(3)], [P[i] + b[i] for i in range(3)],
                          p, paint, rot=self.rot if any(self.rot) else None, origin=self.pivot, **o)

    def sub(self, bone):
        """Os enfant (pièce mobile) dont les cubes gardent le repère de l'instrument."""
        self.m.bone(bone, self.pivot, self.bone)
        return bone

    def w(self, x, y=0, z=0):
        v = apply(self.R, (x, y, z))
        return [self.pivot[i] + v[i] for i in range(3)]

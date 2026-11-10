#!/usr/bin/env python3
"""Animations du Rapace-Liane (2026-11-06) : idle, walk, run, fly, attack, special_attack, land, roar.

Chaque animation est décrite par des POSES (inclinaison du corps, ouverture des ailes, position des pieds...),
interpolées dans le temps, puis échantillonnées toutes les 0,05 s. Pour chaque image, un petit moteur calcule la
rotation de chaque os :
- corps, cou, tête, queue, mâchoire : rotations données directement ;
- ailes : chaque section (épaule, avant-bras, main) vise une direction et une orientation des feuilles, mélange
  de quatre formes (repliée au repos, ouverte debout, ouverte en vol, rabattue en piqué), plus un battement ;
- pattes : cinématique inverse à deux segments (hanche → talon → cheville), talon toujours vers l'arrière comme
  un oiseau, pieds posés sur une cible au sol (ils restent plantés quand le corps bouge) ou repliés sous le corps.

Conventions : angles « réels » ici (pitch négatif = vers l'avant/le bas), convertis à l'écriture dans la
convention de Blockbench/BetterModel (rotation lue en (-x, -y, z), position en (-x, y, z), cf. apercu_anim).
"""
import math

import rapace_liane as RL
from golem_fonderie import euler, mul, rot

add, sub, sc, dot, cross, norm, lerp, frame = RL.add, RL.sub, RL.sc, RL.dot, RL.cross, RL.norm, RL.lerp, RL.frame
I3 = [[1, 0, 0], [0, 1, 0], [0, 0, 1]]
DT = 0.05


def tr(M):
    return [[M[j][i] for j in range(3)] for i in range(3)]


def app(M, v):
    return [sum(M[i][k] * v[k] for k in range(3)) for i in range(3)]


def ypr(pitch=0.0, yaw=0.0, roll=0.0):
    return mul(rot(0, yaw, 0), mul(rot(0, 0, roll), rot(pitch, 0, 0)))


def axis_angle(axis, deg):
    x, y, z = norm(axis)
    a = math.radians(deg)
    c, s, C = math.cos(a), math.sin(a), 1 - math.cos(a)
    return [[c + x * x * C, x * y * C - z * s, x * z * C + y * s],
            [y * x * C + z * s, c + y * y * C, y * z * C - x * s],
            [z * x * C - y * s, z * y * C + x * s, c + z * z * C]]


def between(a, b):
    """Plus petite rotation qui amène la direction a sur la direction b."""
    a, b = norm(a), norm(b)
    ax = cross(a, b)
    s = math.sqrt(dot(ax, ax))
    if s < 1e-8:
        return I3
    return axis_angle(ax, math.degrees(math.atan2(s, dot(a, b))))


def align(d0, t0, d1, t1):
    """Rotation qui amène le repère (direction d0, feuilles vers t0) sur (d1, t1)."""
    return mul(frame(d1, t1), tr(frame(d0, t0)))


# --------------------------------------------------------------------------- pose de base : rapace à l'affût
BASE = {
    "root_y": 0, "root_z": 0,
    "body_x": 0, "body_y": -2.0, "body_z": 2.0, "body_pitch": -30, "body_yaw": 0, "body_roll": 0,
    "chest_pitch": -3, "chest_yaw": 0, "chest_roll": 0,
    "neck_pitch": 22, "neck_yaw": 0, "neck_roll": 0,
    "head_pitch": 8, "head_yaw": 0, "head_roll": 0,
    "jaw": 0, "tail_pitch": 0, "tail_yaw": 0,
}
for _s in "RL":
    BASE.update({f"{_s}w_open": 0.12, f"{_s}w_fly": 0, f"{_s}w_dive": 0, f"{_s}w_flap": 0, f"{_s}w_lag": 0,
                 f"{_s}f_x": 0, f"{_s}f_y": 0, f"{_s}f_z": 0, f"{_s}f_pitch": 0, f"{_s}f_tuck": 0, f"{_s}claw": 0})
SIDES = (("R", "Right", 1), ("L", "Left", -1))


def both(**kw):
    """Même valeur pour les deux côtés : both(w_open=0.5) → Rw_open, Lw_open."""
    out = {}
    for k, v in kw.items():
        for s in "RL":
            out[s + k] = v
    return out


def keyposes(keys, length):
    """keys : [(t, {param: valeur}, 'ease'|'lin')], chaque paramètre absent d'une clé garde sa valeur précédente.
    Renvoie une fonction t → pose complète."""
    resolved = []
    cur = dict(BASE)
    for k in keys:
        t, vals = k[0], k[1]
        cur = dict(cur)
        cur.update(vals)
        resolved.append((t, cur, k[2] if len(k) > 2 else "ease"))

    def f(t):
        t = max(0.0, min(length, t))
        for (t0, p0, _), (t1, p1, mode) in zip(resolved, resolved[1:]):
            if t0 <= t <= t1:
                u = 0 if t1 == t0 else (t - t0) / (t1 - t0)
                if mode == "ease":
                    u = u * u * (3 - 2 * u)
                return {key: p0[key] + (p1[key] - p0[key]) * u for key in p0}
        return dict(resolved[-1][1])
    return f


# --------------------------------------------------------------------------- moteur : pose → rotations des os
class Rig:
    def __init__(self, S):
        self.S = S
        P = S.pts
        self.piv = {n: v["pivot"] for n, v in S.nodes.items()}
        self.parent = {n: v["parent"] for n, v in S.nodes.items()}
        self.hip = self.piv["Body"]
        self.U = P["U"]
        self.legs, self.wings = {}, {}
        for _, side, s in SIDES:
            H, A, F = P[f"{side}_H"], P[f"{side}_A"], P[f"{side}_F"]
            self.legs[side] = (H, A, F, math.dist(H, A), math.dist(A, F))
            sh, el, wr, tip, t0 = (P[f"{side}_{k}"] for k in ("sh", "el", "wr", "tip", "trail"))
            fold = [sub(el, sh), sub(wr, el), sub(tip, wr)]
            # directions des trois sections (épaule, avant-bras, main) et orientation des feuilles, par forme
            shapes = {
                "fold": (fold, [t0] * 3),
                "open": ([[s, 0.75, 0.3], [s, 0.35, 0.45], [s, -0.2, 0.55]], [[0, -1, 0.45]] * 3),
                "fly": ([[s, -0.1, 0.25], [s, -0.2, 0.05], [s, -0.6, -0.05]], [[0, -1, 0.15]] * 3),
                "dive": ([[0.65 * s, -0.5, 0.55], [0.3 * s, -1, 0.25], [0.12 * s, -1, 0.05]], [[0, 0, -1]] * 3),
            }
            self.wings[side] = (fold, t0, {k: ([norm(d) for d in v[0]], [norm(t) for t in v[1]])
                                           for k, v in shapes.items()})

    def wing(self, side, s, p, key):
        fold, t0, shapes = self.wings[side]
        wo, wf, wd = p[key + "w_open"], p[key + "w_fly"], p[key + "w_dive"]
        wfold = max(0.0, 1 - wo - wf - wd)
        weights = {"fold": wfold, "open": wo, "fly": wf, "dive": wd}
        # battement : autour de l'axe avant du corps debout, autour de l'axe du torse (U) en vol
        flap_axis = norm(add(sc([0, 0, s], wfold + wo), sc(self.U, -s * (wf + wd))))
        out, prev = [], I3
        for i in range(3):
            d = norm([sum(weights[k] * shapes[k][0][i][j] for k in weights) for j in range(3)])
            t = norm([sum(weights[k] * shapes[k][1][i][j] for k in weights) for j in range(3)])
            Rb = align(fold[i], t0, d, t)
            flap = p[key + "w_flap"] + p[key + "w_lag"] * i * 0.6
            Rb = mul(axis_angle(flap_axis, flap), Rb)
            out.append(mul(tr(prev), Rb))
            prev = Rb
        return out

    def leg(self, side, p, key, Wb, Pb):
        H, A, F, l1, l2 = self.legs[side]
        Hw = add(app(Wb, sub(H, self.hip)), Pb)
        tuck = p[key + "f_tuck"]
        ground = add(F, [p[key + "f_x"], p[key + "f_y"], p[key + "f_z"]])
        # repliée : sous le ventre, vers la queue (repère du corps)
        tucked = add(app(Wb, sub(add(F, [0, 11 + p[key + "f_y"], 7 + p[key + "f_z"]]), self.hip)), Pb)
        Fw = lerp(ground, tucked, tuck)
        bend = norm(lerp([0, 0.15, 1], app(Wb, [0, 0.3, 1]), tuck))
        d = sub(Fw, Hw)
        D = max(abs(l1 - l2) + 0.2, min(l1 + l2 - 0.05, math.sqrt(dot(d, d))))
        dh = norm(d)
        a = (l1 * l1 - l2 * l2 + D * D) / (2 * D)
        h = math.sqrt(max(0.0, l1 * l1 - a * a))
        perp = norm(sub(bend, sc(dh, dot(bend, dh))))
        Aw = add(Hw, sc(dh, a), sc(perp, h))
        Wt = between(sub(A, H), sub(Aw, Hw))
        Wa = between(app(Wt, sub(F, A)), sub(Fw, Aw))
        Wa = mul(Wa, Wt)
        Wf = mul(rot(0, p["body_yaw"], 0), rot(p[key + "f_pitch"] + tuck * p["body_pitch"], 0, 0))
        return [mul(tr(Wb), Wt), mul(tr(Wt), Wa), mul(tr(Wa), Wf), rot(-p[key + "claw"], 0, 0)]

    def solve(self, p):
        """Rotations locales (réelles) et positions des os pour une pose."""
        loc, pos = {}, {}
        pos["Root"] = [0, p["root_y"], p["root_z"]]
        Wb = ypr(p["body_pitch"], p["body_yaw"], p["body_roll"])
        loc["Body"] = Wb
        pos["Body"] = [p["body_x"], p["body_y"], p["body_z"]]
        Pb = add(self.hip, pos["Body"], pos["Root"])
        loc["Chest"] = ypr(p["chest_pitch"], p["chest_yaw"], p["chest_roll"])
        loc["Neck"] = ypr(p["neck_pitch"], p["neck_yaw"], p["neck_roll"])
        loc["Head"] = ypr(p["head_pitch"], p["head_yaw"], p["head_roll"])
        loc["Beak_Lower"] = rot(-p["jaw"], 0, 0)
        loc["Tail"] = ypr(p["tail_pitch"], p["tail_yaw"], 0)
        for key, side, s in SIDES:
            for bone, R in zip((f"{side}_Wing", f"{side}_Wing_Forearm", f"{side}_Wing_Hand"),
                               self.wing(side, s, p, key)):
                loc[bone] = R
            for bone, R in zip((f"{side}_Leg", f"{side}_Tarsus", f"{side}_Foot", f"{side}_Claws"),
                               self.leg(side, p, key, Wb, Pb)):
                loc[bone] = R
        return loc, pos


def build(S, uid):
    """Liste des animations au format Blockbench, prêtes à mettre dans data['animations']."""
    rig = Rig(S)
    n = [0]

    def anim(name, length, loop, posef):
        times = [round(min(length, i * DT), 4) for i in range(int(round(length / DT)) + 1)]
        tracks = {}
        last = {}
        for t in times:
            loc, pos = rig.solve(posef(t))
            for bone, R in loc.items():
                x, y, z = euler(R)
                v = [-x, -y, z]
                if bone in last:                      # pas de saut de 360° entre deux images
                    v = [a + 360 * round((b - a) / 360) for a, b in zip(v, last[bone])]
                last[bone] = v
                tracks.setdefault(bone, {}).setdefault("rotation", []).append((t, v))
            for bone, v in pos.items():
                tracks.setdefault(bone, {}).setdefault("position", []).append((t, [-v[0], v[1], v[2]]))
        animators = {}
        for bone, channels in tracks.items():
            frames = []
            for channel, pts in channels.items():
                for t, (x, y, z) in pts:
                    n[0] += 1
                    frames.append({"channel": channel, "data_points": [{"x": f"{x:.3f}", "y": f"{y:.3f}",
                                                                        "z": f"{z:.3f}"}],
                                   "uuid": uid(f"kf/{name}/{n[0]}"), "time": t, "color": -1,
                                   "interpolation": "linear"})
            animators[uid("group/" + bone)] = {"name": bone, "type": "bone", "keyframes": frames}
        return {"uuid": uid("anim/" + name), "name": name, "loop": loop, "override": False, "length": length,
                "snapping": 20, "selected": False, "anim_time_update": "", "blend_weight": "", "start_delay": "",
                "loop_delay": "", "animators": animators}

    TAU = 2 * math.pi
    sin = math.sin
    out = []

    # ------------------------------------------------------------------ idle : à l'affût, 4 s en boucle
    def snap(t, keys):
        """Mouvement sec : la valeur tient, puis saute en 0,1 s vers la suivante (tête d'oiseau)."""
        v = keys[0][1]
        for (t0, a), (t1, b) in zip(keys, keys[1:]):
            if t >= t1:
                v = b
            elif t >= t1 - 0.1:
                u = (t - (t1 - 0.1)) / 0.1
                v = a + (b - a) * u * u * (3 - 2 * u)
                break
            else:
                v = a
                break
        return v

    L = 4.0
    yaw_k = [(0, 0), (0.9, 18), (1.8, 9), (2.6, -14), (3.3, -14), (3.9, 0), (L, 0)]
    roll_k = [(0, 0), (1.8, 0), (2.6, 9), (3.3, 0), (L, 0)]
    pitch_k = [(0, 0), (0.9, -4), (2.6, 3), (3.9, 0), (L, 0)]

    def idle(t):
        p = dict(BASE)
        ph = t / L
        b = (1 - math.cos(TAU * ph * 2)) / 2           # deux respirations par boucle
        p["chest_pitch"] += 1.6 * b
        p["body_y"] += 0.45 * b
        p["neck_pitch"] += 3.5 * sin(TAU * ph)           # le cou se rétracte puis se détend
        p["head_pitch"] += -3.5 * sin(TAU * ph) + snap(t, pitch_k)
        p["head_yaw"] = snap(t, yaw_k)
        p["head_roll"] = snap(t, roll_k)
        p["neck_yaw"] = 0.3 * p["head_yaw"]
        for s, k in (("R", 0.0), ("L", 0.37)):
            p[s + "w_open"] += 0.05 * sin(TAU * (ph + k))
            p[s + "w_flap"] = 2.0 * sin(TAU * (2 * ph + k))
        p["tail_yaw"] = 3 * sin(TAU * ph + 1)
        p["tail_pitch"] = 1.5 * sin(TAU * 2 * ph)
        grip = max(0.0, sin(TAU * (ph - 0.5) * 2)) if 0.5 <= ph < 1.0 else 0
        p["Rclaw"] = 6 * grip
        p["Lclaw"] = 6 * max(0.0, sin(TAU * ph * 2)) if ph < 0.5 else 0
        return p
    out.append(anim("idle", L, "loop", idle))

    # ------------------------------------------------------------------ walk : marche lourde de rapace, 1,6 s
    L = 1.6
    STRIDE = 6.5

    def foot_cycle(u, stance=0.62, lift=5.0):
        """Pied pour la phase u : appui (recule avec le sol), puis levé lourd vers l'avant, orteils repliés."""
        u %= 1.0
        if u < stance:
            v = u / stance
            return 0.0, -STRIDE + 2 * STRIDE * v, 0.0, 0.0
        v = (u - stance) / (1 - stance)
        e = v * v * (3 - 2 * v)
        return lift * sin(math.pi * v), STRIDE - 2 * STRIDE * e, -28 * sin(math.pi * v), 28 * sin(math.pi * v)

    def walk(t):
        p = dict(BASE)
        ph = t / L
        for s, off in (("R", 0.0), ("L", 0.5)):
            y, z, pitch, claw = foot_cycle(ph + off)
            p.update({s + "f_y": y, s + "f_z": z, s + "f_pitch": pitch, s + "claw": claw})
            p[s + "w_open"] = 0.24 + 0.04 * sin(TAU * (2 * ph + off))
            p[s + "w_flap"] = 3 * sin(TAU * (2 * ph + off + 0.15))
        plant = (1 + math.cos(TAU * 2 * ph)) / 2         # le corps tombe sur chaque appui
        p["body_y"] += -1.3 * plant + 0.25 * sin(TAU * 3 * ph)
        p["body_x"] = 1.0 * sin(TAU * ph)               # le poids passe sur la patte d'appui
        p["body_roll"] = -3.5 * sin(TAU * ph)
        p["body_yaw"] = 3 * sin(TAU * ph + 0.4)
        p["body_pitch"] += 2 * plant
        # tête stabilisée puis projetée : le cou recule pendant l'appui et se détend vers l'avant
        p["neck_pitch"] += 6 * math.cos(TAU * 2 * ph + 0.6)
        p["head_pitch"] += -5 * math.cos(TAU * 2 * ph + 0.6) - 2 * plant
        p["head_yaw"] = 4 * sin(TAU * ph + 1.1)
        p["tail_yaw"] = -6 * sin(TAU * ph)
        p["tail_pitch"] = 3 * plant
        return p
    out.append(anim("walk", L, "loop", walk))

    # ------------------------------------------------------------------ run : bonds de chasse, 0,8 s
    L = 0.8

    def run(t):
        p = dict(BASE)
        ph = t / L
        p["body_pitch"] = -44
        p["neck_pitch"] = 26
        p["head_pitch"] = 12
        p["chest_pitch"] = -2
        for s, off in (("R", 0.0), ("L", 0.06)):        # les deux pattes poussent ensemble, à peine décalées
            u = (ph + off) % 1
            if u < 0.42:
                v = u / 0.42
                y, z, pitch, claw = 0, -8 + 17 * v, 6 * v, 0
            else:
                v = (u - 0.42) / 0.58
                e = v * v * (3 - 2 * v)
                y, z, pitch, claw = 6.5 * sin(math.pi * v), 9 - 17 * e, -30 * sin(math.pi * v), 35 * sin(math.pi * v)
            p.update({s + "f_y": y, s + "f_z": z, s + "f_pitch": pitch, s + "claw": claw})
            p[s + "w_open"] = 0.5
            p[s + "w_flap"] = 22 * sin(TAU * (ph + off - 0.25))
            p[s + "w_lag"] = 10 * sin(TAU * (ph + off - 0.4))
        u = ph % 1
        p["body_y"] = -2.0 - 3.2 * sin(math.pi * u / 0.42) if u < 0.42 else -2.0 + 3.0 * sin(math.pi * (u - 0.42) / 0.58)
        p["body_z"] = 2.0
        p["body_pitch"] += 4 * sin(TAU * ph)
        p["head_pitch"] += -3 * sin(TAU * ph)
        p["tail_pitch"] = -6 + 4 * sin(TAU * ph + 1)
        return p
    out.append(anim("run", L, "loop", run))

    # ------------------------------------------------------------------ fly : vol battu, 1 s
    L = 1.0

    def fly(t):
        p = dict(BASE)
        ph = t / L
        beat = math.cos(TAU * ph)                        # 1 = ailes en haut
        p.update({"root_y": 10, "body_pitch": -72 + 4 * beat, "body_y": 3 + 1.6 * sin(TAU * ph - 1.2), "body_z": 3,
                  "chest_pitch": 0, "neck_pitch": 44 - 3 * beat, "head_pitch": 20 + 2 * beat,
                  "tail_pitch": -6 + 4 * sin(TAU * ph), "tail_yaw": 3 * sin(TAU * ph * 0.5)})
        for s in "RL":
            p.update({s + "w_open": 0, s + "w_fly": 1, s + "w_flap": 38 * beat,
                      s + "w_lag": 18 * math.cos(TAU * (ph - 0.12)), s + "f_tuck": 1, s + "f_pitch": 55,
                      s + "claw": 40})
        return p
    out.append(anim("fly", L, "loop", fly))

    # ------------------------------------------------------------------ attack : coup de serres, 1,1 s
    out.append(anim("attack", 1.1, "once", keyposes([
        (0.0, {}),
        # le corps s'abaisse, les ailes se replient, la patte droite revient sous le corps
        (0.18, {"body_y": -5, "body_z": 3, "body_pitch": -38, "neck_pitch": 14, "head_pitch": 12, **both(w_open=0.02),
                "Rf_y": 3, "Rf_z": 4, "Rf_pitch": -15, "Rclaw": 25}),
        # bond en avant sur la patte gauche, battement brusque, serres ouvertes vers la cible
        (0.32, {"body_y": -1.5, "body_z": -5, "body_pitch": -46, "neck_pitch": 30, "head_pitch": 4,
                **both(w_open=0.65, w_flap=30, w_lag=10), "Rf_y": 8, "Rf_z": -13, "Rf_pitch": 40, "Rclaw": -20,
                "tail_pitch": 10}, "lin"),
        # lacération : la patte frappe, les serres se referment, les ailes redescendent
        (0.45, {"body_z": -6.5, "body_y": -2.5, "Rf_y": 4, "Rf_z": -17, "Rf_pitch": 18, "Rclaw": 35,
                **both(w_flap=-18, w_lag=-8), "head_pitch": 10}, "lin"),
        (0.62, {"body_z": -4, "Rf_y": 2.5, "Rf_z": -7, "Rf_pitch": 0, "Rclaw": 30, **both(w_open=0.4, w_flap=0,
                                                                                         w_lag=0)}),
        # recul, retour à l'affût
        (0.85, {"body_z": 3.5, "body_y": -2.5, "body_pitch": -32, "Rf_y": 1.5, "Rf_z": 0, "Rclaw": 0,
                "neck_pitch": 22, "head_pitch": 8, **both(w_open=0.25)}),
        (1.1, BASE),
    ], 1.1)))

    # ------------------------------------------------------------------ special_attack : envol puis piqué, 2,6 s
    out.append(anim("special_attack", 2.6, "once", keyposes([
        (0.0, {}),
        # préparation : très bas, ailes qui s'écartent, tête vers la cible, serres grandes ouvertes
        (0.45, {"body_y": -5.5, "body_pitch": -40, "neck_pitch": 12, "head_pitch": 16, **both(w_open=0.85, w_flap=22),
                **both(claw=-20)}),
        (0.7, {"body_y": -6, "head_pitch": 18, **both(w_flap=26)}),
        # envol : poussée des deux pattes, puissant battement vers le bas, pattes qui se replient
        (0.85, {"root_y": 12, "body_y": 0, "body_pitch": -56, "neck_pitch": 30, "head_pitch": 14,
                **both(w_open=0.5, w_fly=0.5, w_flap=-40, w_lag=-15, f_tuck=0.6, f_pitch=30, claw=20),
                "tail_pitch": -8}, "lin"),
        (1.1, {"root_y": 28, "root_z": 3, "body_pitch": -72, "neck_pitch": 44, "head_pitch": 18,
               **both(w_open=0, w_fly=1, w_flap=34, w_lag=12, f_tuck=1, f_pitch=55, claw=35)}),
        # piqué : ailes à demi rabattues, corps qui plonge, tête projetée, serres déployées en avant
        (1.35, {"root_y": 22, "root_z": 1, "body_pitch": -88, "neck_pitch": 34, "head_pitch": 10,
                **both(w_fly=0.35, w_dive=0.65, w_flap=6, w_lag=0, f_tuck=0.55, f_pitch=20, claw=-25)}),
        (1.5, {"root_y": 9, "root_z": 0, "body_pitch": -78, **both(w_flap=-4, f_tuck=0.25, claw=-30)}, "lin"),
        # impact : ailes écartées pour freiner, pattes et serres en avant, corps écrasé, tête qui claque vers le bas
        (1.62, {"root_y": 0, "body_y": -6.5, "body_z": -3, "body_pitch": -46, "neck_pitch": 8, "head_pitch": -6,
                **both(w_fly=0, w_dive=0, w_open=1, w_flap=34, f_tuck=0, f_y=0, f_z=-3, f_pitch=0, claw=32)},
         "lin"),
        (1.75, {"body_y": -7, "head_pitch": 2, **both(w_flap=22)}),
        # récupération : posture basse, ailes qui se replient, la tête se relève lentement
        (2.1, {"body_y": -5.5, "body_z": -1, **both(w_open=0.55, w_flap=8, f_z=-1.5, claw=12), "neck_pitch": 14,
               "head_pitch": 6}),
        (2.6, BASE),
    ], 2.6)))

    # ------------------------------------------------------------------ land : atterrissage, 1,2 s
    out.append(anim("land", 1.2, "once", keyposes([
        (0.0, {"root_y": 20, "body_pitch": -72, "body_y": 3, "body_z": 3, "neck_pitch": 44, "head_pitch": 20,
               **both(w_open=0, w_fly=1, w_flap=20, f_tuck=1, f_pitch=55, claw=40)}),
        # freinage : le corps se redresse, les ailes s'ouvrent vers l'avant, les pattes se projettent vers le sol
        (0.32, {"root_y": 8, "body_pitch": -24, "body_y": 1, "neck_pitch": 18, "head_pitch": 10,
                **both(w_fly=0.2, w_open=0.8, w_flap=32, w_lag=-10, f_tuck=0, f_y=5, f_z=-6, f_pitch=25, claw=-15),
                "tail_pitch": -12}),
        # contact : flexion des articulations, queue qui stabilise
        (0.55, {"root_y": 0, "body_y": -5.5, "body_pitch": -36, **both(w_fly=0, w_open=0.75, w_flap=12, w_lag=0,
                                                                       f_y=0, f_z=0, f_pitch=0, claw=10),
                "tail_pitch": 8, "head_pitch": 2}, "lin"),
        (0.75, {"body_y": -6, "tail_pitch": -4, **both(w_open=0.5, w_flap=4)}),
        (1.2, BASE),
    ], 1.2)))

    # ------------------------------------------------------------------ roar : cri et menace, 2,4 s
    def roar_base():
        return keyposes([
            (0.0, {}),
            (0.5, {"body_pitch": -32, "body_y": -2.5, "neck_pitch": 34, "head_pitch": 20, **both(w_open=0.5)}),
            (0.8, {"body_pitch": -30, "body_y": -2.0, "chest_pitch": 1, "neck_pitch": 46, "head_pitch": 26,
                   "jaw": 30, **both(w_open=1, w_flap=18, claw=12)}),
            (1.6, {"jaw": 27, "head_pitch": 28}),
            (1.9, {"jaw": 0, "body_pitch": -36, "body_y": -3.5, "neck_pitch": 16, "head_pitch": 6, "chest_pitch": -3,
                   **both(w_open=0.45, w_flap=6, claw=0)}),
            (2.4, BASE),
        ], 2.4)
    rb = roar_base()

    def roar(t):
        p = rb(t)
        if 0.8 <= t <= 1.7:                              # tremblement du cri
            k = math.sin(math.pi * (t - 0.8) / 0.9)
            p["head_roll"] += 4 * k * sin(TAU * t * 7)
            p["neck_yaw"] += 2.5 * k * sin(TAU * t * 5 + 1)
            for s in "RL":
                p[s + "w_flap"] += 3 * k * sin(TAU * t * 6)
        return p
    out.append(anim("roar", 2.4, "once", roar))
    return out

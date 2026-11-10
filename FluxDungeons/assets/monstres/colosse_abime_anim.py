#!/usr/bin/env python3
"""Animations du Colosse d'Abîme (2026-11-06) : idle, walk, attack_bite, attack_stomp, attack_ultimate (le Cataclysme
abyssal), intro.

Écrites en valeurs « réelles » (rotation en degrés dans les axes du modèle : x positif relève l'avant, z positif relève
le côté +x ; position en unités de dessin), converties à l'écriture dans la convention Blockbench / BetterModel
(rotation lue en (-x, -y, z), position en (-x, y, z), mise à l'échelle du modèle). Le détail des moments de jeu
(dégâts, effets, sons) est dans docs/technique/boss/colosse-abime.md : le garder à jour avec ce fichier.

Principe : tout est lourd. Accélérations progressives (courbes lissées), petits décalages entre la carapace, la tête et
la queue, impacts absorbés par le corps. Les pattes sont des os de Root, pas du corps : le corps peut descendre ou
basculer sans que les pieds quittent le sol.
"""
import math

Z = (0, 0, 0)
TAU = 2 * math.pi
# remontée des pattes avant en fin de mort (bord du pied au ras du sol), réglée sur le rendu
LEG_LIFT = {"front": 1.3, "back": 0.6}
# hauteur de la tête en fin de mort : la mâchoire repose sur le sol (réglée sur le rendu, cf. strips)
HEAD_REST = 3.50
LEGS = ("Leg_front_right", "Leg_front_left", "Leg_back_right", "Leg_back_left")


def build(uid, k):
    """Animations au format Blockbench ; k = échelle du modèle (les positions sont dessinées avant réduction)."""
    n = [0]

    def anim(name, length, loop, tracks):
        animators = {}
        for bone, channels in tracks.items():
            frames = []
            for channel, pts in channels.items():
                for p in pts:
                    t, v = p[0], p[1]
                    if channel == "rotation":
                        v = (-v[0], -v[1], v[2])
                    elif channel == "position":
                        v = (-v[0] * k, v[1] * k, v[2] * k)
                    n[0] += 1
                    frames.append({"channel": channel, "data_points": [{"x": f"{v[0]:.3f}", "y": f"{v[1]:.3f}",
                                                                        "z": f"{v[2]:.3f}"}],
                                   "uuid": uid(f"kf/{name}/{n[0]}"), "time": round(t, 4), "color": -1,
                                   "interpolation": p[2] if len(p) > 2 else "catmullrom"})
            animators[uid("group/" + bone)] = {"name": bone, "type": "bone", "keyframes": frames}
        return {"uuid": uid("anim/" + name), "name": name, "loop": loop, "override": False, "length": length,
                "snapping": 20, "selected": False, "anim_time_update": "", "blend_weight": "", "start_delay": "",
                "loop_delay": "", "animators": animators}

    def wave(length, f, step=0.1, t0=0.0, t1=None):
        """Courbe échantillonnée (interpolation linéaire entre des points serrés) de t0 à t1."""
        t1 = length if t1 is None else t1
        count = max(1, int(round((t1 - t0) / step)))
        return [(round(t0 + (t1 - t0) * i / count, 4), f(t0 + (t1 - t0) * i / count), "linear") for i in range(count + 1)]

    def jitter(t0, t1, amp, freq, axis=2, step=0.05):
        """Vibration contenue (pas d'effet électrique) : amplitude qui monte puis retombe dans [t0, t1]."""
        def f(t):
            env = math.sin(math.pi * (t - t0) / (t1 - t0))
            v = [0.0, 0.0, 0.0]
            v[axis] = amp * env * math.sin(TAU * freq * (t - t0))
            return tuple(v)
        return wave(t1, f, step, t0, t1)

    S = math.sin
    out = []

    # ------------------------------------------------------------------ idle : respiration lente, 4 s en boucle
    L = 4.0
    out.append(anim("idle", L, "loop", {
        "Body": {"position": wave(L, lambda t: (0, 0.3 * (1 - math.cos(TAU * t / L)) / 2, 0), 0.2)},
        "Head": {"rotation": wave(L, lambda t: (1.0 * S(TAU * 2 * t / L), 3.0 * S(TAU * t / L + 0.6), 0), 0.2)},
        "Tail": {"rotation": wave(L, lambda t: (0, 2.0 * S(TAU * t / L - 0.8), 0), 0.2)},
    }))

    # ------------------------------------------------------------------ walk : marche lourde en diagonale, 2,8 s
    L = 2.8
    phase = {"Leg_front_left": 0.0, "Leg_back_right": 0.06, "Leg_front_right": 0.5, "Leg_back_left": 0.56}
    side = {"Leg_front_right": 1, "Leg_back_right": 1, "Leg_front_left": -1, "Leg_back_left": -1}

    def leg(bone):
        def stage(t):
            u = (t / L + phase[bone]) % 1.0
            if u < 0.65:                          # appui : le pied recule avec le sol, posé
                return u / 0.65, 0.0, None
            v = (u - 0.65) / 0.35                 # levé : le pied monte un peu, avance, se repose
            return v, math.sin(math.pi * v), v * v * (3 - 2 * v)

        def rotation(t):
            a, lift, e = stage(t)
            swing = 7 - 14 * a if e is None else -7 + 14 * e
            return (swing, 0, 2.0 * lift * side[bone])

        def position(t):
            a, lift, e = stage(t)
            swing = 7 - 14 * a if e is None else -7 + 14 * e
            return (0, 2.2 * lift + 1.15 * abs(swing) / 7, 0)
        return {"rotation": wave(L, rotation), "position": wave(L, position)}

    tracks = {b: leg(b) for b in LEGS}
    tracks.update({
        # le poids tombe sur chaque paire posée, la carapace roule à peine vers les appuis, en retard
        "Body": {"position": wave(L, lambda t: (0, -0.45 * (1 + math.cos(TAU * 2 * t / L)) / 2, 0)),
                 "rotation": wave(L, lambda t: (0.5 * S(TAU * 2 * t / L + 0.5), 0, 1.2 * S(TAU * t / L)))},
        "Shell": {"rotation": wave(L, lambda t: (0, 0, 0.5 * S(TAU * t / L - 0.6)))},
        # la tête avance quand les pattes avant passent, se stabilise, regarde un peu autour
        "Head": {"position": wave(L, lambda t: (0, 0, -0.7 * (1 + math.cos(TAU * 2 * (t / L - 0.82))) / 2)),
                 "rotation": wave(L, lambda t: (1.2 * S(TAU * 2 * t / L + 1.0), 4.0 * S(TAU * t / L + 0.3), 0))},
        "Tail": {"rotation": wave(L, lambda t: (0, -5.0 * S(TAU * t / L - 0.8), 0))},
        "Tail_tip": {"rotation": wave(L, lambda t: (0, -4.0 * S(TAU * t / L - 1.5), 0))},
    })
    out.append(anim("walk", L, "loop", tracks))

    # ------------------------------------------------------------------ attack_bite : morsure, 1,4 s (coup à 0,62 s)
    lin = "linear"
    out.append(anim("attack_bite", 1.4, "once", {
        "Head": {"position": [(0, Z), (0.35, (0, 0, 2.5)), (0.42, (0, 0, 2.2)), (0.55, (0, 0, -6), lin),
                              (0.62, (0, 0, -5.6)), (0.78, (0, 0, -3.2)), (1.1, (0, 0, -0.8)), (1.4, Z)],
                 "rotation": [(0, Z), (0.35, (4, 0, 0)), (0.55, (-6, 0, 0), lin), (0.62, (-3, 0, 0)),
                              (0.78, (-1, 0, 0)), (1.4, Z)]},
        "Jaw": {"rotation": [(0, Z), (0.35, (-10, 0, 0)), (0.5, (-24, 0, 0)), (0.56, (-26, 0, 0)),
                             (0.62, Z, lin), (0.7, (-2, 0, 0)), (0.8, Z), (1.4, Z)]},
        "Body": {"rotation": [(0, Z), (0.35, (1.5, 0, 0)), (0.55, (-1.5, 0, 0)), (0.62, (-1, 0, 0)), (1.4, Z)],
                 "position": [(0, Z), (0.35, (0, 0, 0.4)), (0.55, (0, 0, -1)), (0.62, (0, -0.4, -0.8)),
                              (0.9, (0, -0.1, -0.3)), (1.4, Z)]},
        "Tail": {"rotation": [(0, Z), (0.62, Z), (0.75, (0, 3, 0)), (0.95, (0, -1.5, 0)), (1.4, Z)]},
    }))

    # ------------------------------------------------------------------ attack_stomp : piétinement, 2,3 s (impact 1,0 s)
    out.append(anim("attack_stomp", 2.3, "once", {
        "Leg_front_right": {"position": [(0, Z), (0.6, (0, 10.5, 0)), (0.85, (0, 11.2, 0)), (1.0, Z, lin), (2.3, Z)],
                            "rotation": [(0, Z), (0.6, (22, 0, 4)), (0.85, (24, 0, 4)), (1.0, Z, lin), (2.3, Z)]},
        # le poids passe sur les trois autres pattes : le côté droit se relève, puis l'impact écrase le corps
        "Body": {"rotation": [(0, Z), (0.6, (1.5, 0, 4.5)), (0.85, (1.5, 0, 4.5)), (1.0, (-1.5, 0, -1.5), lin),
                              (1.15, (-0.5, 0, 0.8)), (1.35, (0, 0, -0.3)), (2.3, Z)],
                 "position": [(0, Z), (0.6, (0, 0.4, 0)), (0.85, (0, 0.4, 0)), (1.0, (0, -1.4, 0), lin),
                              (1.15, (0, -0.7, 0)), (1.5, (0, -0.2, 0)), (2.3, Z)]},
        "Shell": {"rotation": [(0, Z), (1.0, Z), (1.05, (0, 0, 1.2), lin), (1.12, (0, 0, -0.9), lin),
                               (1.2, (0, 0, 0.5), lin), (1.3, (0, 0, -0.2), lin), (1.45, Z, lin), (2.3, Z)],
                  "position": [(0, Z), (1.0, Z), (1.05, (0, -0.5, 0), lin), (1.15, (0, 0.2, 0), lin),
                               (1.3, Z, lin), (2.3, Z)]},
        "Head": {"rotation": [(0, Z), (0.6, (-3, 0, 0)), (1.0, (-3, 0, 0)), (1.05, (3, 0, 0), lin), (1.3, Z),
                              (2.3, Z)],
                 "position": [(0, Z), (0.6, (0, 0, 0.5)), (1.0, (0, 0, 0.5)), (1.4, Z), (2.3, Z)]},
        "Tail": {"rotation": [(0, Z), (0.6, (0, -4, 0)), (1.0, (0, -4, 0)), (1.1, (0, 3, 0)), (1.4, (0, -1, 0)),
                              (2.3, Z)]},
        "Tail_tip": {"rotation": [(0, Z), (1.0, Z), (1.15, (0, 4, 0)), (1.5, (0, -1.5, 0)), (2.3, Z)]},
    }))

    # ------------------------------------------------------------------ attack_ultimate : le Cataclysme abyssal, 8 s
    # La tortue ne frappe pas et ne saute pas : tout son poids passe dans le sol. 0-1,5 s ancrage très bas et silence ;
    # 1,5-3 s les plaques de la carapace s'entrouvrent sous la pression, le cœur s'expose ; 3-4 s l'énergie descend
    # vers les pattes, dernier abaissement, contraction, immobilité ; 4-4,5 s séisme (tout le corps cogne le sol) ;
    # 4,5-6 s trois vagues de piliers (effets du jeu), petites répliques ; 6-6,7 s dernière pulsation ;
    # 6,7-8 s récupération lente, le boss est épuisé, retour exact à la pose de repos.
    def plate(lift, out_x=0.0, out_z=0.0, rx=0.0, rz=0.0):
        """Plaque qui s'entrouvre de quelques unités, se contracte, saute au séisme puis redescend lentement."""
        def at(f):
            return (rx * f, 0, rz * f)

        def pos(f, jolt=0.0):
            return (out_x * f, lift * f + jolt, out_z * f)
        return {"position": [(0, Z), (1.5, Z), (2.3, pos(0.45)), (3.0, pos(1.0)), (3.6, pos(1.0)), (3.85, pos(0.75)),
                             (4.0, pos(0.75)), (4.07, pos(1.2, 0.4), lin), (4.3, pos(1.05)), (4.55, pos(1.0)),
                             (6.05, pos(1.0)), (6.15, pos(1.15, 0.2), lin), (6.4, pos(1.0)), (6.7, pos(1.0)),
                             (7.6, Z), (8.0, Z)],
                "rotation": [(0, Z), (1.5, Z), (3.0, at(1.0)), (3.85, at(0.75)), (4.07, at(1.2), lin),
                             (4.4, at(1.0)), (6.7, at(1.0)), (7.6, Z), (8.0, Z)]}

    ult = {
        "Plate_top": plate(2.4, out_z=0.6, rx=6, rz=-3),
        "Plate_shoulder_right": plate(1.6, out_x=1.4, rz=-8),
        "Plate_shoulder_left": plate(1.6, out_x=-1.4, rz=8),
        "Plate_flank_right": plate(0.5, out_x=1.8, rz=-6),
        "Plate_flank_left": plate(0.5, out_x=-1.8, rz=6),
        "Plate_rear": plate(0.7, out_z=1.6, rx=6),
        # le cœur monte dans la brèche et pulse ; la luminosité est pilotée par le jeu (événements, cf. fiche)
        "glow_heart": {"position": [(0, Z), (1.5, Z), (3.0, (0, 0.6, 0)), (3.2, (0, 0.9, 0)), (3.4, (0, 0.6, 0)),
                                    (3.6, (0, 1.0, 0)), (3.85, (0, 0.7, 0)), (4.07, (0, 1.2, 0), lin),
                                    (4.4, (0, 0.8, 0)), (6.0, (0, 0.8, 0)), (6.15, (0, 1.3, 0), lin),
                                    (6.45, (0, 0.6, 0)), (7.6, Z), (8.0, Z)]},
        # la tête regarde devant, puis descend et rentre ; réaction au séisme ; ressort lentement, un peu basse
        "Head": {"position": [(0, Z), (0.6, Z), (1.4, (0, 0, 2.6)), (4.0, (0, 0, 2.8)), (4.12, (0, -0.3, 2.4), lin),
                              (4.5, (0, 0, 2.8)), (6.7, (0, 0, 2.8)), (7.8, Z), (8.0, Z)],
                 "rotation": [(0, Z), (0.3, (2, 0, 0)), (0.6, (2, 0, 0)), (1.4, (-6, 0, 0)), (4.0, (-6, 0, 0)),
                              (4.12, (-1, 0, 0), lin), (4.45, (-6, 0, 0)), (6.7, (-6, 0, 0)), (7.2, (-8, 0, 0)),
                              (7.8, Z), (8.0, Z)]},
        "Jaw": {"rotation": [(0, Z), (4.0, Z), (4.12, (-6, 0, 0), lin), (4.4, Z), (8.0, Z)]},
        # le corps descend tout son poids, un dernier cran à 3,7 s, cogne à 4 s, répliques, puis se relève avec effort
        "Body": {"position": [(0, Z), (0.6, Z), (1.4, (0, -1.8, 0)), (3.0, (0, -1.8, 0)), (3.7, (0, -2.6, 0)),
                              (4.0, (0, -2.6, 0)), (4.06, (0, -3.6, 0), lin), (4.25, (0, -3.0, 0)),
                              (4.45, (0, -3.3, 0)), (4.5, (0, -3.3, 0)), (4.55, (0, -3.45, 0), lin),
                              (4.7, (0, -3.3, 0)), (5.0, (0, -3.3, 0)), (5.05, (0, -3.45, 0), lin),
                              (5.2, (0, -3.3, 0)), (5.5, (0, -3.3, 0)), (5.55, (0, -3.45, 0), lin),
                              (5.7, (0, -3.3, 0)), (6.05, (0, -3.3, 0)), (6.12, (0, -3.7, 0), lin),
                              (6.4, (0, -3.3, 0)), (6.7, (0, -3.3, 0)), (7.3, (0, -2.4, 0)), (7.5, (0, -2.2, 0)),
                              (7.9, Z), (8.0, Z)]},
        "Shell": {"position": [(0, Z), (3.6, Z), (3.85, (0, -0.3, 0)), (4.0, (0, -0.3, 0)), (8.0, Z)],
                  "rotation": [(0, Z)] + jitter(4.0, 4.7, 1.5, 9) + jitter(4.5, 4.75, 0.5, 8) +
                              jitter(5.0, 5.25, 0.45, 8) + jitter(5.5, 5.75, 0.4, 8) + jitter(6.05, 6.5, 0.8, 9) +
                              [(8.0, Z)]},
        # la queue se rapproche, est projetée vers l'arrière par l'inertie du choc, puis revient
        "Tail": {"position": [(0, Z), (1.4, (0, 0, -1.2)), (4.0, (0, 0, -1.2)), (4.15, (0, 0, 0.6), lin),
                              (4.5, (0, 0, -1.0)), (6.7, (0, 0, -1.0)), (7.8, Z), (8.0, Z)],
                 "rotation": [(0, Z), (1.4, (6, 0, 0)), (4.0, (6, 0, 0)), (4.15, (-8, 0, 0), lin), (4.5, (5, 0, 0)),
                              (6.7, (5, 0, 0)), (7.8, Z), (8.0, Z)]},
        "Tail_tip": {"rotation": [(0, Z), (4.05, Z), (4.25, (-10, 0, 0)), (4.6, (3, 0, 0)), (5.0, Z), (8.0, Z)]},
    }
    # les pattes s'écartent pour une posture très stable ; le bord du pied reste au ras du sol
    for bone in LEGS:
        s = 1 if "right" in bone else -1
        a = 4.0 if "front" in bone else 3.0
        up = 0.35 if "front" in bone else 0.3
        ult[bone] = {"rotation": [(0, Z), (0.6, Z), (1.4, (0, 0, a * s)), (6.7, (0, 0, a * s)), (7.6, Z), (8.0, Z)],
                     "position": [(0, Z), (0.6, Z), (1.4, (0, up, 0)), (6.7, (0, up, 0)), (7.6, Z), (8.0, Z)]}
    out.append(anim("attack_ultimate", 8.0, "once", ult))

    # ------------------------------------------------------------------ intro : réveil puis rugissement, 9 s
    out.append(anim("intro", 9.0, "once", {
        "Head": {"position": [(0, (0, 0, 4)), (1.5, (0, 0, 4)), (3.0, (0, 0, 1.5)), (4.6, Z), (7.0, Z),
                              (7.3, (0, 0, -1.5)), (8.2, (0, 0, -2)), (8.7, Z), (9.0, Z)],
                 "rotation": [(0, Z), (1.15, Z), (1.3, (-2, 0, 0)), (1.5, Z), (1.8, (0, -10, 0)), (2.6, (0, 8, 0)),
                              (3.0, (0, 6, 0)), (4.6, (3, 3, 0)), (5.4, (2, 0, 0)), (5.8, (-2, 0, 0)),
                              (7.0, (-2, 0, 0)), (7.3, (5, 0, 0)), (8.2, (5, 0, 0)), (8.7, Z), (9.0, Z)]},
        "Jaw": {"rotation": [(0, Z), (1.8, Z), (2.3, (-4, 0, 0)), (3.0, (-3, 0, 0)), (3.6, Z), (7.0, Z),
                             (7.4, (-18, 0, 0)), (8.2, (-20, 0, 0)), (8.6, Z), (9.0, Z)]},
        "Body": {"position": [(0, (0, -1.5, 0)), (3.0, (0, -1.5, 0)), (4.6, Z), (6.0, (0, 0.3, 0)), (6.8, Z),
                              (9.0, Z)],
                 "rotation": [(0, Z), (3.0, Z), (3.6, (1.5, 0, 0)), (4.6, Z), (9.0, Z)]},
        "Shell": {"rotation": [(0, Z)] + jitter(1.0, 1.4, 0.6, 6) + [(4.0, Z)] + jitter(7.4, 8.2, 0.5, 7) +
                              [(9.0, Z)]},
        "Tail": {"rotation": [(0, (0, 8, 0)), (3.0, (0, 8, 0)), (4.8, Z), (9.0, Z)]},
        "glow_eyes": {"position": [(0, (0, 0, 0.9)), (1.5, (0, 0, 0.9)), (3.0, Z), (9.0, Z)]},
        "glow_heart": {"position": [(0, (0, -0.8, 0)), (3.0, (0, -0.8, 0)), (4.8, (0, 0.2, 0)), (6.0, Z),
                                    (7.6, (0, 0.4, 0)), (8.8, Z), (9.0, Z)]},
    }))
    return out

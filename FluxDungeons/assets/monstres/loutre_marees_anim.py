#!/usr/bin/env python3
"""Animations de la Loutre des Marées (2026-11-10).

Phase terrestre : idle, walk, attack_claw, attack_tail, attack_slide, death_land.
Transition (mi-vie) : transition_dive (casse le sol et plonge sur place).
Phase aquatique : swim, swim_idle (flotte sur le dos), attack_wave, attack_spin, attack_spit, ultimate_breach, death_water.

Valeurs « réelles » : rotation en degrés dans les axes du modèle (x positif relève l'avant d'un os, et avance le pied
d'une patte ; y positif tourne l'avant vers la gauche du monstre (-x) ; z positif relève le côté droit (+x)) ;
position en pixels du modèle livré (16 = 1 bloc, BL). Converties à l'écriture dans la convention Blockbench /
BetterModel (rotation lue en (-x, -y, z), position en (-x, y, z)).

Tous les instants clés tombent sur un tick (multiples de 0,05 s). Les moments de jeu (dégâts, zones, déplacement de
l'entité, sons, particules) sont dans docs/technique/boss/loutre-marees.md : les garder à jour avec ce fichier.
`mesures()` relève tick par tick les instants qui dépendent de la géométrie (tête dans l'eau, immersion complète,
point le plus bas) pour les reporter dans la fiche.

Repères de pose (calés sur les mesures) :
- surface de l'eau = sol du modèle (y = 0) ; en phase aquatique l'entité est posée à la surface (swim_idle, attaques,
  ultime) ou sous l'eau (swim) ;
- SURFACE_Y : décalage de Body qui fait flotter la loutre sur le dos, ventre hors de l'eau ;
- BELLY_Y : décalage de Body qui pose le ventre au sol (glissade).
"""
import math
import uuid

Z = (0, 0, 0)
TAU = 2 * math.pi
BL = 16.0                    # pixels par bloc
SURFACE_Y = -11.0            # flotte sur le dos (swim_idle)
SWIM_DEPTH = -2.5 * BL       # fin de la plongée : corps à l'horizontale, 2,5 blocs sous la surface
BELLY_Y = -6.6               # ventre au sol (glissade, ventre-à-plat de l'ultime)
LEGS = ("Leg_front_right", "Leg_front_left", "Leg_back_right", "Leg_back_left")
TAIL = ("Tail_1", "Tail_2", "Tail_3", "Tail_4")
lin = "linear"


def smooth(a, b, t):
    """0 avant a, 1 après b, courbe lissée entre les deux."""
    if t <= a:
        return 0.0
    if t >= b:
        return 1.0
    u = (t - a) / (b - a)
    return u * u * (3 - 2 * u)


def bump(a, b, t):
    """0 hors de [a, b], monte puis redescend (cloche lissée)."""
    if t <= a or t >= b:
        return 0.0
    return math.sin(math.pi * (t - a) / (b - a)) ** 2


def build(uid):
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
                        v = (-v[0], v[1], v[2])
                    n[0] += 1
                    frames.append({"channel": channel, "data_points": [{"x": f"{v[0]:.3f}", "y": f"{v[1]:.3f}",
                                                                        "z": f"{v[2]:.3f}"}],
                                   "uuid": uid(f"kf/{name}/{n[0]}"), "time": round(t, 4), "color": -1,
                                   "interpolation": p[2] if len(p) > 2 else "catmullrom"})
            animators[uid("group/" + bone)] = {"name": bone, "type": "bone", "keyframes": frames}
        return {"uuid": uid("anim/" + name), "name": name, "loop": loop, "override": False, "length": length,
                "snapping": 20, "selected": False, "anim_time_update": "", "blend_weight": "", "start_delay": "",
                "loop_delay": "", "animators": animators}

    def wave(length, f, step=0.05, t0=0.0, t1=None):
        """Courbe échantillonnée tick par tick (linéaire entre des points serrés) de t0 à t1."""
        t1 = length if t1 is None else t1
        count = max(1, int(round((t1 - t0) / step)))
        return [(round(t0 + (t1 - t0) * i / count, 4), tuple(f(t0 + (t1 - t0) * i / count)), lin)
                for i in range(count + 1)]

    def track(length, **fs):
        """Os : {canal: fonction du temps}, échantillonnés."""
        return {ch: wave(length, f) for ch, f in fs.items()}

    def whiskers(length, base=0.0, amp=4.0, freq=1.5, fast=None):
        """Mouvement secondaire permanent des moustaches (jamais figées) ; `fast` = (début, fin) de frémissement."""
        def f(sign):
            def g(t):
                a = base + amp * math.sin(TAU * freq * t / length)        # freq entière : la boucle retombe pile
                if fast:
                    a += 3.5 * bump(*fast, t) * math.sin(TAU * 12 * t)
                return (0, -a * sign, 2 * math.sin(TAU * t / length) * sign)
            return g
        return {"Whiskers_right": track(length, rotation=f(1)), "Whiskers_left": track(length, rotation=f(-1))}

    S, C = math.sin, math.cos
    out = []

    # ================================================================== PHASE TERRESTRE
    # ------------------------------------------------------------------ idle : 4 s en boucle (80 ticks)
    # Respiration, regard qui balaie, queue qui balance en vague, moustaches qui frémissent ; fidget de 2,4 à 3,2 s :
    # elle relève le museau et renifle (moustaches qui vibrent), puis reprend sa garde.
    L = 4.0

    def idle_head(t):
        sniff = bump(2.4, 3.2, t)
        return (3 * S(TAU * t / 2 + 0.5) + 12 * sniff + 1.5 * sniff * S(TAU * 8 * t),
                10 * S(TAU * t / L) * (1 - sniff), 0)
    tr = {
        "Chest": track(L, rotation=lambda t: (1.2 * S(TAU * t / 2), 0, 0)),
        "Hips": track(L, rotation=lambda t: (-0.6 * S(TAU * t / 2), 0, 0)),
        "Head": track(L, rotation=idle_head),
        "Jaw": track(L, rotation=lambda t: (4 * bump(2.4, 3.2, t), 0, 0)),           # gueule qui se ferme en reniflant
    }
    for i, b in enumerate(TAIL):
        tr[b] = track(L, rotation=lambda t, i=i: (1.5 * S(TAU * t / 2 - 0.5 * i), (4 + i) * S(TAU * t / L - 0.6 * i), 0))
    tr.update(whiskers(L, fast=(2.4, 3.2)))
    out.append(anim("idle", L, "loop", tr))

    # ------------------------------------------------------------------ walk : 0,8 s en boucle (16 ticks)
    # Allure bondissante de mustélidé : les pattes avant poussent ensemble (0-0,4 s) puis les arrière (0,4-0,8 s) ;
    # le dos se voûte quand les pattes arrière reviennent sous le corps, s'allonge quand les avant s'étirent. Pieds en
    # appui immobiles par rapport au sol à 1,4 bloc par seconde (pas de patinage), cf. fiche.
    L = 0.8
    SW, LEG_L = 28.0, 9.5            # amplitude des pattes (degrés) et longueur épaule-sol (pixels)

    def stance_angle(u):
        """Angle de la paire en appui : avant de 0 à 0,5, arrière de 0,5 à 1."""
        return SW * C(TAU * u) if u < 0.5 else SW * C(TAU * (u - 0.5))

    def front(off):
        def rotation(t):
            u = (t / L + off) % 1.0
            return (SW * C(TAU * u), 0, 0)

        def paw(t):
            u = (t / L + off) % 1.0
            return (-45 * math.sin(math.pi * (u - 0.5) / 0.5) if u >= 0.5 else 0, 0, 0)
        return rotation, paw

    def back(off):
        def rotation(t):
            u = (t / L + off) % 1.0
            return (SW * C(TAU * (u - 0.5)), 0, 0)

        def foot(t):
            u = (t / L + off) % 1.0
            return (-40 * math.sin(math.pi * u / 0.5) if u < 0.5 else 0, 0, 0)
        return rotation, foot

    tr = {}
    for side, off in (("right", 0.0), ("left", 0.04)):
        r, p = front(off)
        tr[f"Leg_front_{side}"] = track(L, rotation=r)
        tr[f"Paw_front_{side}"] = track(L, rotation=p)
        r, p = back(off)
        tr[f"Leg_back_{side}"] = track(L, rotation=r)
        tr[f"Foot_back_{side}"] = track(L, rotation=p)
    tr.update({
        "Body": track(L, position=lambda t: (0, -LEG_L * (1 - C(math.radians(stance_angle(t / L % 1.0)))), 0),
                      rotation=lambda t: (2 * S(TAU * t / L), 0, 0)),
        "Chest": track(L, rotation=lambda t: (5 * C(TAU * t / L), 0, 0)),
        "Hips": track(L, rotation=lambda t: (-5 * C(TAU * t / L), 0, 0)),
        "Head": track(L, rotation=lambda t: (-4 * C(TAU * t / L) - 2 * S(TAU * t / L) + 2 * S(2 * TAU * t / L), 0, 0)),
    })
    for i, b in enumerate(TAIL):
        tr[b] = track(L, rotation=lambda t, i=i: ((5 + 2 * i) * S(TAU * (t / L - 0.12 * (i + 1))), 0, 0))
    tr.update(whiskers(L, base=-6, amp=2, freq=1))
    out.append(anim("walk", L, "loop", tr))

    # ------------------------------------------------------------------ attack_claw : combo de griffes, 1,6 s (32 ticks)
    # Se dresse (avant du corps relevé, pattes avant levées), patte droite armée haut et dehors ; coup de droite qui
    # traverse vers l'intérieur (impact 0,6 s, tick 12), arrêt sur image ; patte gauche armée, coup de gauche (impact
    # 0,95 s, tick 19), arrêt ; retour au repos à 1,6 s.
    tr = {
        # tout le corps se cabre sur les pattes arrière (le verrou au sol garde les pieds arrière posés)
        "Body": {"position": [(0, Z), (0.5, (0, 0, 1)), (0.6, (0, 0, -1.5), lin), (0.7, (0, 0, -1.5)),
                              (0.95, (0, 0, -2.5), lin), (1.05, (0, 0, -2.5)), (1.45, Z), (1.6, Z)],
                 "rotation": [(0, Z), (0.5, (26, 0, 0)), (0.6, (17, 0, 0), lin), (0.7, (18, 0, 0)), (0.85, (24, 0, 0)),
                              (0.95, (15, 0, 0), lin), (1.05, (16, 0, 0)), (1.45, Z), (1.6, Z)]},
        "Chest": {"rotation": [(0, Z), (0.5, (18, 0, 0)), (0.6, (12, -8, 0), lin), (0.7, (13, -8, 0)),
                               (0.85, (18, 5, 0)), (0.95, (11, 9, 0), lin), (1.05, (12, 8, 0)), (1.45, Z), (1.6, Z)]},
        "Hips": {"rotation": [(0, Z), (0.5, (-6, 0, 0)), (1.05, (-5, 0, 0)), (1.45, Z), (1.6, Z)]},
        "Head": {"rotation": [(0, Z), (0.5, (-38, 0, 0)), (0.6, (-28, 6, 0), lin), (0.85, (-36, -3, 0)),
                              (0.95, (-26, -7, 0), lin), (1.45, Z), (1.6, Z)]},
        "Jaw": {"rotation": [(0, Z), (0.4, (-14, 0, 0)), (0.6, (-18, 0, 0)), (0.95, (-18, 0, 0)), (1.3, Z), (1.6, Z)]},
        "Leg_front_right": {"rotation": [(0, Z), (0.5, (98, 0, 30)), (0.55, (95, 0, 28)), (0.6, (16, 0, -20), lin),
                                         (0.7, (18, 0, -18)), (0.85, (30, 0, 0)), (1.0, (28, 0, 0)), (1.45, Z),
                                         (1.6, Z)]},
        "Paw_front_right": {"rotation": [(0, Z), (0.5, (-30, 0, 0)), (0.6, (12, 0, 0), lin), (0.85, Z), (1.6, Z)]},
        "Leg_front_left": {"rotation": [(0, Z), (0.5, (32, 0, 0)), (0.7, (34, 0, 0)), (0.85, (98, 0, -30)),
                                        (0.9, (95, 0, -28)), (0.95, (16, 0, 20), lin), (1.05, (18, 0, 18)),
                                        (1.45, Z), (1.6, Z)]},
        "Paw_front_left": {"rotation": [(0, Z), (0.7, Z), (0.85, (-30, 0, 0)), (0.95, (12, 0, 0), lin), (1.2, Z),
                                       (1.6, Z)]},
        "Tail_1": {"rotation": [(0, Z), (0.5, (-26, 10, 0)), (0.6, (-20, -8, 0)), (0.95, (-18, 8, 0)), (1.3, (0, -2, 0)),
                                (1.6, Z)]},
        "Leg_back_right": {"rotation": [(0, Z), (0.5, (-20, 0, 0)), (0.6, (-11, 0, 0), lin), (0.7, (-13, 0, 0)),
                                        (0.85, (-19, 0, 0)), (0.95, (-10, 0, 0), lin), (1.05, (-11, 0, 0)), (1.45, Z),
                                        (1.6, Z)]},
        "Leg_back_left": {"rotation": [(0, Z), (0.5, (-20, 0, 0)), (0.6, (-11, 0, 0), lin), (0.7, (-13, 0, 0)),
                                       (0.85, (-19, 0, 0)), (0.95, (-10, 0, 0), lin), (1.05, (-11, 0, 0)), (1.45, Z),
                                       (1.6, Z)]},
        "Tail_3": {"rotation": [(0, Z), (0.6, (0, 8, 0)), (0.75, (0, -10, 0)), (1.05, (0, 10, 0)), (1.6, Z)]},
    }
    tr.update(whiskers(1.6, base=8, amp=3, freq=2))
    out.append(anim("attack_claw", 1.6, "once", tr))

    # ------------------------------------------------------------------ attack_tail : queue en arc, 2,2 s (44 ticks)
    # Télégraphe 1 s : elle s'abaisse, regarde par-dessus son épaule, queue levée qui fouette d'impatience. Bond en
    # tournant sur elle-même (240° vers la droite en 0,3 s), la queue balaie un arc puis frappe le sol (impact 1,4 s,
    # tick 28) ; arrêt ; elle se retourne vers sa cible (240° en sens inverse) et revient au repos à 2,2 s.
    def spin(t):
        if t < 1.0:
            return 0.0
        if t <= 1.3:
            return -240 * smooth(1.0, 1.3, t) ** 0.9
        if t <= 1.5:
            return -240.0
        return -240 * (1 - smooth(1.5, 2.15, t))

    def tail_pose(i):
        lift = (-38, -18, -14, -10)[i]
        slam = (30, 20, 14, 8)[i]

        def f(t):
            x = lift * smooth(0.0, 0.8, t) * (1 - smooth(1.1, 1.3, t))          # levée, puis tendue pendant le tour
            x += slam * smooth(1.3, 1.4, t) * (1 - smooth(1.55, 2.1, t))        # frappe au sol
            y = 12 * bump(0.3, 0.95, t) * S(TAU * 2.5 * t - 0.6 * i)           # fouette d'impatience
            return (x, y, 0)
        return f
    tr = {
        "Body": track(2.2, rotation=lambda t: (0, spin(t), 0),
                      position=lambda t: (0, -1.2 * smooth(0.0, 0.8, t) * (1 - smooth(0.95, 1.0, t))
                                          + 3.5 * bump(1.0, 1.4, t) - 0.4 * bump(1.38, 1.6, t), 0)),
        "Hips": track(2.2, rotation=lambda t: (0, 14 * smooth(0.2, 0.8, t) * (1 - smooth(1.0, 1.2, t)), 0)),
        "Head": track(2.2, rotation=lambda t: (-6 * smooth(0, 0.8, t) * (1 - smooth(1.6, 2.1, t)),
                                               -32 * smooth(0.1, 0.7, t) * (1 - smooth(1.0, 1.25, t)), 0)),
        "Jaw": track(2.2, rotation=lambda t: (-12 * bump(0.6, 1.6, t), 0, 0)),
    }
    for i, b in enumerate(TAIL):
        tr[b] = track(2.2, rotation=tail_pose(i))
    tr.update(whiskers(2.2, base=6, amp=3, freq=3))
    out.append(anim("attack_tail", 2.2, "once", tr))

    # ------------------------------------------------------------------ attack_slide : glissade sur le ventre, 2,6 s
    # (52 ticks). Elle se ramasse et se dandine (0-0,6 s), se jette sur le ventre (0,6-0,75 s, tick 15 : départ du
    # déplacement par le jeu), glisse pattes avant rabattues et pattes arrière tendues (0,75-1,75 s), freine en plantant
    # les pattes avant (1,75-2 s, fin du déplacement au tick 35), se relève (2-2,6 s). Le modèle ne se déplace pas :
    # c'est l'entité que le jeu pousse en avant (cf. fiche).
    def slide(t):
        return smooth(0.6, 0.75, t) * (1 - smooth(1.75, 2.05, t))
    brake = lambda t: bump(1.7, 2.3, t)                                        # noqa: E731
    tr = {
        "Body": track(2.6, position=lambda t: (0, -2 * smooth(0, 0.5, t) * (1 - smooth(0.6, 0.7, t))
                                               + BELLY_Y * slide(t) + 1.0 * brake(t),
                                               2 * smooth(0, 0.5, t) * (1 - smooth(0.6, 0.75, t))),
                      rotation=lambda t: (-4 * slide(t) + 6 * brake(t), 3 * S(TAU * 3 * t) * slide(t),
                                          3 * S(TAU * 2 * t) * slide(t))),
        "Chest": track(2.6, rotation=lambda t: (-6 * smooth(0, 0.5, t) * (1 - smooth(0.6, 0.75, t)) + 8 * brake(t),
                                                0, 0)),
        "Hips": track(2.6, rotation=lambda t: (0, 9 * S(TAU * 3.33 * t) * bump(0.05, 0.6, t), 0)),
        "Head": track(2.6, rotation=lambda t: (-8 * smooth(0, 0.5, t) * (1 - smooth(0.6, 0.75, t)) + 8 * slide(t),
                                               0, 0)),
        "Jaw": track(2.6, rotation=lambda t: (-8 * slide(t), 0, 0)),
    }
    for side in ("right", "left"):
        tr[f"Leg_front_{side}"] = track(2.6, rotation=lambda t: (-96 * slide(t) + 45 * brake(t), 0, 0))
        tr[f"Paw_front_{side}"] = track(2.6, rotation=lambda t: (10 * slide(t), 0, 0))
        tr[f"Leg_back_{side}"] = track(2.6, rotation=lambda t: (-95 * slide(t), 0, 0))
        tr[f"Foot_back_{side}"] = track(2.6, rotation=lambda t: (5 * slide(t), 0, 0))
    for i, b in enumerate(TAIL):
        tr[b] = track(2.6, rotation=lambda t, i=i: ((-24 if i == 0 else 0) * slide(t),
                                                   (3 + 2 * i) * S(TAU * 2 * t - 0.7 * i) * slide(t), 0))
    tr.update(whiskers(2.6, base=-14, amp=3, freq=4))
    out.append(anim("attack_slide", 2.6, "once", tr))

    # ------------------------------------------------------------------ death_land : mort au sol, 3 s (60 ticks), tenue
    # Touchée, elle vacille (0-0,5 s), les pattes cèdent (0,5-1,2 s), elle bascule sur le flanc gauche (1,2-1,8 s),
    # petit rebond, se pose (2,3 s) ; pose finale tenue.
    fall = lambda t: smooth(1.2, 1.8, t) - 0.08 * bump(1.8, 2.3, t)           # noqa: E731
    tr = {
        "Body": track(3.0, rotation=lambda t: (0, 0, -6 * bump(0, 0.6, t) + 84 * fall(t)),
                      position=lambda t: (0, -1.5 * smooth(0.4, 1.2, t) - 4.6 * fall(t), 0)),
        "Chest": track(3.0, rotation=lambda t: (-8 * smooth(0.5, 1.2, t) * (1 - fall(t)), 0, 0)),
        "Head": track(3.0, rotation=lambda t: (-14 * smooth(1.2, 2.0, t), 14 * S(TAU * 4 * t) * bump(0, 0.6, t),
                                               10 * smooth(1.4, 2.2, t))),
        "Jaw": track(3.0, rotation=lambda t: (-16 * smooth(0, 0.4, t) + 4 * smooth(1.8, 2.6, t), 0, 0)),
    }
    for leg in LEGS:
        s = 1 if "right" in leg else -1
        tr[leg] = track(3.0, rotation=lambda t, s=s: (10 * smooth(1.4, 2.3, t), 0,
                                                      (8 * s) * smooth(0.5, 1.2, t) - 32 * smooth(1.5, 2.3, t)))
    for i, b in enumerate(TAIL):
        tr[b] = track(3.0, rotation=lambda t, i=i: (-8 * smooth(1.4, 2.3, t), -6 * smooth(1.6, 2.4, t),
                                                   -10 * smooth(1.5, 2.3, t) if i == 0 else 0))
    tr["Whiskers_right"] = track(3.0, rotation=lambda t: (0, -3 * S(TAU * 3 * t) * (1 - smooth(1.5, 2.5, t)),
                                                          -12 * smooth(1.5, 2.5, t)))
    tr["Whiskers_left"] = track(3.0, rotation=lambda t: (0, 3 * S(TAU * 3 * t) * (1 - smooth(1.5, 2.5, t)),
                                                         12 * smooth(1.5, 2.5, t)))
    out.append(anim("death_land", 3.0, "hold", tr))

    # ================================================================== TRANSITION (mi-vie)
    # ------------------------------------------------------------------ transition_dive : 3,6 s (72 ticks)
    # 0-0,6 s colère : recule, secoue la tête, moustaches hérissées, gueule ouverte. 0,6-1,5 s se dresse au maximum,
    # pattes avant au-dessus de la tête, queue tendue. 1,65 s (tick 33) premier impact (fissures). Rebond, se redresse
    # à moitié. 2,3 s (tick 46) second impact de tout son poids (le sol cède). Elle s'enfonce, bascule tête la première
    # et plonge à la verticale sur place, coup de queue final, puis se remet à l'horizontale 2,5 blocs sous la surface
    # (fin à 3,6 s, tick 72 : pose de départ de swim, l'entité doit être descendue de 2,5 blocs).
    REAR, REAR2 = 55.0, 40.0

    def dive_pitch(t):
        if t < 0.6:
            return 0.0
        if t < 1.5:
            return REAR * smooth(0.6, 1.4, t)
        if t < 1.65:
            return REAR + (-4 - REAR) * ((t - 1.5) / 0.15) ** 2                   # chute accélérée
        if t < 2.15:
            return -4 + 10 * bump(1.65, 1.85, t) + (REAR2 + 4) * smooth(1.85, 2.15, t)
        if t < 2.3:
            return REAR2 + (-8 - REAR2) * ((t - 2.15) / 0.15) ** 2
        if t < 2.45:
            return -8.0
        if t < 3.05:
            return -8 - 77 * smooth(2.45, 3.05, t)                                 # tête la première
        if t < 3.3:
            return -85.0
        return -85 + 85 * smooth(3.3, 3.6, t)                                     # se remet à l'horizontale

    def rear_lift(p):
        """Montée du corps qui garde les pieds arrière au sol quand le corps se cabre de p degrés (calée sur mesures)."""
        a = math.radians(max(0.0, p))
        return 12.0 * math.sin(a) - 15.6 * (1 - math.cos(a)) * 0.35

    def dive_y(t):
        p = dive_pitch(t)
        y = rear_lift(p) if t < 2.3 else 0.0
        y += -1.5 * bump(1.6, 1.75, t) - 3.0 * bump(2.25, 2.45, t)                  # compression aux impacts
        if t >= 2.3:
            y += -6 * smooth(2.3, 2.6, t) + (-3.0 * BL + 6) * smooth(2.6, 3.3, t) ** 1.4
            y += (SWIM_DEPTH + 3.0 * BL) * smooth(3.3, 3.6, t)
        return y

    def raised(t):
        """Pattes et queue levées : 1 au sommet de chaque montée."""
        return smooth(0.6, 1.4, t) * (1 - smooth(1.5, 1.6, t)) + smooth(1.8, 2.15, t) * (1 - smooth(2.15, 2.25, t)) * 0.85

    def diving(t):
        return smooth(2.45, 2.9, t)
    tr = {
        "Body": track(3.6, rotation=lambda t: (dive_pitch(t), 0, 0),
                      position=lambda t: (0, dive_y(t), 2.5 * bump(0, 0.9, t))),
        "Head": track(3.6, rotation=lambda t: (-6 * smooth(0, 0.2, t) - 35 * smooth(0.6, 1.4, t) * (1 - smooth(1.5, 1.65, t))
                                               - 25 * smooth(1.85, 2.15, t) * (1 - smooth(2.15, 2.3, t))
                                               + 4 * diving(t),
                                               18 * S(TAU * 3.3 * t) * bump(0.05, 0.6, t), 0)),
        "Jaw": track(3.6, rotation=lambda t: (-18 * smooth(0, 0.15, t) * (1 - smooth(2.4, 2.7, t)), 0, 0)),
        "Chest": track(3.6, rotation=lambda t: (6 * raised(t), 0, 0)),
    }
    for side in ("right", "left"):
        s = 1 if side == "right" else -1
        tr[f"Leg_front_{side}"] = track(3.6, rotation=lambda t, s=s: (
            110 * raised(t) + 35 * (smooth(1.55, 1.65, t) * (1 - smooth(1.8, 1.95, t)) + smooth(2.2, 2.3, t)
                                    * (1 - diving(t))) - 84 * diving(t), 0, 12 * s * raised(t)))
        tr[f"Paw_front_{side}"] = track(3.6, rotation=lambda t: (20 * raised(t) - 12 * diving(t), 0, 0))
        tr[f"Leg_back_{side}"] = track(3.6, rotation=lambda t: (-dive_pitch(t) if t < 2.45 else
                                                               -8 + 8 - 60 * diving(t), 0, 0))
        tr[f"Foot_back_{side}"] = track(3.6, rotation=lambda t: (20 * diving(t), 0, 0))

    def dive_tail(i):
        def f(t):
            up = (-55, -15, -10, -8)[i] * raised(t)
            slam = (18, 12, 8, 4)[i] * (bump(1.55, 1.85, t) + bump(2.2, 2.5, t))
            straight = (-30, -8, 0, 0)[i] * diving(t)
            flick = (0, 0, 30, 30)[i] * (bump(3.0, 3.15, t) - bump(3.15, 3.35, t))
            return (up + slam + straight + flick, 0, 0)
        return f
    for i, b in enumerate(TAIL):
        tr[b] = track(3.6, rotation=dive_tail(i))
    tr["Whiskers_right"] = track(3.6, rotation=lambda t: (0, 4 * S(TAU * 9 * t) * bump(0, 0.6, t) + 18 * diving(t),
                                                          22 * smooth(0, 0.2, t) * (1 - diving(t))))
    tr["Whiskers_left"] = track(3.6, rotation=lambda t: (0, -4 * S(TAU * 9 * t) * bump(0, 0.6, t) - 18 * diving(t),
                                                         -22 * smooth(0, 0.2, t) * (1 - diving(t))))
    out.append(anim("transition_dive", 3.6, "once", tr))

    # ================================================================== PHASE AQUATIQUE
    # ------------------------------------------------------------------ swim : nage sous l'eau, 1,2 s en boucle
    # (24 ticks). Corps horizontal, ondulation de haut en bas qui part des épaules et grandit jusqu'au bout de la queue,
    # pattes avant plaquées contre le poitrail, pattes arrière palmées qui battent, moustaches couchées en arrière.
    L = 1.2
    tr = {
        "Body": track(L, position=lambda t: (0, 1.0 * S(TAU * t / L + 1.2), 0),
                      rotation=lambda t: (1.5 * S(TAU * t / L + 0.6), 0, 0)),
        "Chest": track(L, rotation=lambda t: (4 * S(TAU * t / L), 0, 0)),
        "Hips": track(L, rotation=lambda t: (6 * S(TAU * t / L - 0.8), 0, 0)),
        "Head": track(L, rotation=lambda t: (-3 * S(TAU * t / L) - 4, 0, 0)),
    }
    for i, (b, a) in enumerate(zip(TAIL, (10, 12, 14, 14))):
        base = (-30, -8, 0, 0)[i]
        tr[b] = track(L, rotation=lambda t, i=i, a=a, base=base: (base + a * S(TAU * t / L - 1.6 - 0.8 * i), 0, 0))
    for side in ("right", "left"):
        tr[f"Leg_front_{side}"] = track(L, rotation=lambda t: (-84 + 3 * S(TAU * t / L), 0, 0))
        tr[f"Paw_front_{side}"] = track(L, rotation=lambda t: (-12, 0, 0))
        off = 0.0 if side == "right" else 0.15
        tr[f"Leg_back_{side}"] = track(L, rotation=lambda t, off=off: (-60 + 16 * S(TAU * (t / L + off) - 1.6), 0, 0))
        tr[f"Foot_back_{side}"] = track(L, rotation=lambda t, off=off: (20 + 18 * S(TAU * (t / L + off) - 2.4), 0, 0))
    tr.update(whiskers(L, base=-18, amp=2, freq=1))
    out.append(anim("swim", L, "loop", tr))

    # ------------------------------------------------------------------ swim_idle : flotte sur le dos, 3 s en boucle
    # (60 ticks). Pose iconique de la loutre de mer : sur le dos à la surface, pattes avant croisées sur le ventre,
    # pieds palmés qui pagaient, tête relevée qui suit les joueurs du regard ; léger bercement de la houle.
    L = 3.0
    tr = {
        "Body": track(L, rotation=lambda t: (2 * S(TAU * t / L), 0, 180 + 3 * S(TAU * t / L + 1.0)),
                      position=lambda t: (0, SURFACE_Y + 0.8 * S(TAU * t / L), 0)),
        "Head": track(L, rotation=lambda t: (-30, 18 * S(TAU * t / L), 0)),
        "Chest": track(L, rotation=lambda t: (-6, 0, 0)),
        "Hips": track(L, rotation=lambda t: (-4 + 1.5 * S(TAU * 2 * t / L), 0, 0)),
    }
    for side in ("right", "left"):
        s = 1 if side == "right" else -1
        tr[f"Leg_front_{side}"] = track(L, rotation=lambda t, s=s: (45 + 3 * S(TAU * t / L), 0, -38 * s))
        tr[f"Paw_front_{side}"] = track(L, rotation=lambda t: (40, 0, 0))
        off = 0.0 if side == "right" else 0.5
        tr[f"Leg_back_{side}"] = track(L, rotation=lambda t, off=off: (-25 + 18 * S(TAU * 2 * (t / L + off)), 0, 0))
        tr[f"Foot_back_{side}"] = track(L, rotation=lambda t, off=off: (15 * S(TAU * 2 * (t / L + off) - 0.8), 0, 0))
    for i, b in enumerate(TAIL):
        tr[b] = track(L, rotation=lambda t, i=i: ((-30, -6, 0, 0)[i], 6 * S(TAU * t / L - 0.5 * i), 0))
    tr.update(whiskers(L, amp=4, freq=2))
    out.append(anim("swim_idle", L, "loop", tr))

    # ------------------------------------------------------------------ attack_wave : vague, 2 s (40 ticks)
    # À l'endroit, ventre vers le fond (pose de swim) : elle se ramasse et fixe sa cible (0-0,5 s), sort l'avant du
    # corps de l'eau en levant les pattes avant (0,5-0,9 s), frappe l'eau de tout le poitrail (1,0 s, tick 20 : départ
    # de la vague devant elle), puis revient à la pose de nage (1,4-2,0 s).
    UP_Y = 0.0

    def roll(t):
        return 0.0
    rise = lambda t: smooth(0.5, 0.9, t) * (1 - smooth(0.93, 1.0, t))              # noqa: E731
    slam = lambda t: bump(0.93, 1.3, t)                                            # noqa: E731

    def on_back(t):
        return 0.0
    tr = {
        "Body": track(2.0, rotation=lambda t: (-6 * slam(t), 0, roll(t)),
                      position=lambda t: (0, SURFACE_Y * on_back(t) + UP_Y * (1 - on_back(t)) + 5 * rise(t)
                                          - 3 * slam(t), 0)),
        "Chest": track(2.0, rotation=lambda t: (-6 * on_back(t) + 35 * rise(t) - 10 * slam(t), 0, 0)),
        "Hips": track(2.0, rotation=lambda t: (-4 * on_back(t) + 6 * slam(t), 0, 0)),
        "Head": track(2.0, rotation=lambda t: (-30 * on_back(t) - 25 * rise(t) + 6 * slam(t), 0, 0)),
        "Jaw": track(2.0, rotation=lambda t: (-16 * bump(0.5, 1.3, t), 0, 0)),
    }
    for side in ("right", "left"):
        s = 1 if side == "right" else -1
        # pattes de la pose de nage (avant plaquées, arrière tendues), levées hors de l'eau puis abattues
        tr[f"Leg_front_{side}"] = track(2.0, rotation=lambda t, s=s: (-84 + 189 * rise(t) + 124 * slam(t), 0, 0))
        tr[f"Paw_front_{side}"] = track(2.0, rotation=lambda t: (-12 + 32 * rise(t), 0, 0))
        tr[f"Leg_back_{side}"] = track(2.0, rotation=lambda t: (-60, 0, 0))
    for i, b in enumerate(TAIL):
        tr[b] = track(2.0, rotation=lambda t, i=i: ((-30, -6, 0, 0)[i] + (10, 8, 6, 4)[i] * slam(t),
                                                   6 * S(TAU * 1.5 * t - 0.5 * i), 0))
    tr.update(whiskers(2.0, base=6, amp=3, freq=3))
    out.append(anim("attack_wave", 2.0, "once", tr))

    # ------------------------------------------------------------------ pose de nage commune
    # Les animations jouées pendant la nage (sorts lancés à la surface) partent de swim et y reviennent : rotation et
    # position de chaque os à l'instant t de swim, plus un ajout propre à l'animation. Leur durée est un multiple de
    # 1,2 s (la boucle de swim) : la phase d'ondulation retombe pile à la fin.
    SW = 1.2

    def swim_rot(bone, t):
        u = TAU * t / SW
        if bone == "Body":
            return (1.5 * S(u + 0.6), 0, 0)
        if bone == "Chest":
            return (4 * S(u), 0, 0)
        if bone == "Hips":
            return (6 * S(u - 0.8), 0, 0)
        if bone == "Head":
            return (-3 * S(u) - 4, 0, 0)
        if bone in TAIL:
            i = TAIL.index(bone)
            return ((-30, -8, 0, 0)[i] + (10, 12, 14, 14)[i] * S(u - 1.6 - 0.8 * i), 0, 0)
        if bone.startswith("Leg_front"):
            return (-84 + 3 * S(u), 0, 0)
        if bone.startswith("Paw_front"):
            return (-12, 0, 0)
        off = 0.0 if bone.endswith("right") else 0.15
        if bone.startswith("Leg_back"):
            return (-60 + 16 * S(u + TAU * off - 1.6), 0, 0)
        if bone.startswith("Foot_back"):
            return (20 + 18 * S(u + TAU * off - 2.4), 0, 0)
        if bone.startswith("Whiskers"):
            sign = 1 if bone.endswith("right") else -1
            a = -18 + 2 * S(u)
            return (0, -a * sign, 2 * S(u) * sign)
        return Z

    SWIM_BONES = ("Body", "Chest", "Hips", "Head", "Jaw") + TAIL + tuple(f"{p}_{s}" for p in ("Leg_front", "Paw_front", "Leg_back", "Foot_back")
                                                         for s in ("right", "left")) + ("Whiskers_right", "Whiskers_left")

    def swimlike(name, length, rot=None, pos=None):
        """Animation = swim + ajouts : rot[os](t) et pos[os](t) s'ajoutent à la pose de nage."""
        rot, pos = rot or {}, pos or {}
        tr = {}
        for b in SWIM_BONES:
            extra = rot.get(b)
            ch = {"rotation": lambda t, b=b, extra=extra: tuple(x + y for x, y in zip(swim_rot(b, t), extra(t) if extra else Z))}
            if b == "Body" or b in pos:
                pe = pos.get(b)
                base = (lambda t: (0, 1.0 * S(TAU * t / SW + 1.2), 0)) if b == "Body" else (lambda t: Z)
                ch["position"] = lambda t, base=base, pe=pe: tuple(x + y for x, y in zip(base(t), pe(t) if pe else Z))
            tr[b] = track(length, **ch)
        out.append(anim(name, length, "once", tr))

    # ------------------------------------------------------------------ attack_spin : tourbillon, 1,2 s (24 ticks)
    # Sort whirlpool. Le jeu la fait pivoter sur elle-même (l'animation ne tourne pas le corps : un tour de 720° dans
    # l'animation serait « dévissé » par la suivante) ; elle se couche sur le flanc vers l'intérieur du virage, queue
    # enroulée qui balaie l'eau, tête levée, gueule ouverte.
    whirl = lambda t: bump(0.0, 1.2, t)                                            # noqa: E731
    swimlike("attack_spin", 1.2, rot={
        "Body": lambda t: (0, 0, 24 * whirl(t)),
        "Chest": lambda t: (8 * whirl(t), -12 * whirl(t), 0),
        "Head": lambda t: (14 * whirl(t), -10 * whirl(t), 0),
        "Jaw": lambda t: (-16 * whirl(t), 0, 0),
        "Tail_1": lambda t: (-10 * whirl(t), 28 * whirl(t), 0),
        "Tail_2": lambda t: (0, 24 * whirl(t), 0),
        "Tail_3": lambda t: (0, 20 * whirl(t) + 8 * S(TAU * 3 * t) * whirl(t), 0),
        "Tail_4": lambda t: (0, 14 * whirl(t) + 10 * S(TAU * 3 * t - 0.8) * whirl(t), 0),
    }, pos={"Body": lambda t: (0, 2 * whirl(t), 0)})

    # ------------------------------------------------------------------ attack_spit : bulles, 2,4 s (48 ticks)
    # Sort bubble_barrage. Sort le poitrail de l'eau (0-0,5 s), trois crachats, tête qui part en avant et gueule
    # grande ouverte (ticks 12, 18 et 24 : départ de chaque bulle), puis replonge dans la nage (1,4-2,4 s).
    up = lambda t: smooth(0.1, 0.5, t) * (1 - smooth(1.3, 1.9, t))                # noqa: E731

    def spit(t):
        return sum(bump(k - 0.12, k + 0.12, t) for k in (0.6, 0.9, 1.2))
    jaw = {"Jaw": lambda t: (-8 * up(t) - 26 * spit(t), 0, 0)}
    swimlike("attack_spit", 2.4, rot={
        "Body": lambda t: (14 * up(t), 0, 0),
        "Chest": lambda t: (16 * up(t), 0, 0),
        "Head": lambda t: (-18 * up(t) - 14 * spit(t), 0, 0),
        "Leg_front_right": lambda t: (60 * up(t), 0, 0),
        "Leg_front_left": lambda t: (60 * up(t), 0, 0),
        **jaw,
    }, pos={"Body": lambda t: (0, 5 * up(t), -1.5 * spit(t))})

    # ------------------------------------------------------------------ ultimate_breach : le Jaillissement, 5 s (100 ticks)
    # L'entité reste à la surface. 0-0,3 s elle roule sur le ventre et plonge ; 0,3-2 s elle tourne en cercle sous la
    # surface (rayon 1,5 bloc), de plus en plus vite (deux tours) ; 2-2,6 s elle jaillit à la verticale en vrille (deux
    # tours en sens inverse) jusqu'à 6 blocs au-dessus de l'eau (apogée tick 52) ; 2,6-2,85 s suspension, corps cambré,
    # pattes écartées ; chute à plat ventre, impact à 3,15 s (tick 63) ; sonnée, elle roule sur le dos (3,3-3,8 s) et
    # flotte, étourdie, jusqu'à 5 s (fenêtre de vulnérabilité). Pose de fin = swim_idle. Rotation totale nulle (pas de
    # tour à défaire au passage à l'animation suivante).
    UNDER_Y = -36.0
    R = 1.5 * BL

    def yaw(t):
        if t < 0.3:
            return 0.0
        if t <= 2.0:
            u = (t - 0.3) / 1.7
            return -720 * u * u                       # accélère : deux tours
        if t <= 2.6:
            return -720 + 720 * smooth(2.0, 2.6, t)  # vrille en sens inverse, revient à 0
        return 0.0

    def radius(t):
        return R * smooth(0.3, 0.8, t) * (1 - smooth(1.8, 2.0, t))

    def ult_pitch(t):
        if t < 0.3:
            return -20 * smooth(0, 0.3, t)
        if t < 2.0:
            return -20 * (1 - smooth(0.3, 0.6, t))
        if t < 2.6:
            return 80 * smooth(2.0, 2.15, t)
        if t < 2.85:
            return 80 - 88 * smooth(2.6, 2.85, t)
        return -8 * (1 - smooth(3.15, 3.4, t))

    def ult_y(t):
        if t < 2.0:
            return UNDER_Y * smooth(0.0, 0.35, t)
        if t < 2.6:
            return UNDER_Y + (6 * BL - UNDER_Y) * (1 - (1 - (t - 2.0) / 0.6) ** 2)    # freine en montant
        if t < 2.85:
            return 6 * BL - 3 * smooth(2.6, 2.85, t)
        if t < 3.15:
            return 6 * BL - 3 + (BELLY_Y - 6 * BL + 3) * ((t - 2.85) / 0.3) ** 2        # chute accélérée
        if t < 3.3:
            return BELLY_Y - 3 * bump(3.15, 3.3, t)
        back = BELLY_Y + (SURFACE_Y - BELLY_Y) * smooth(3.3, 3.8, t) + 0.8 * S(TAU * (t - 3.8) / 1.2) * smooth(3.8, 4.2, t)
        return back * (1 - smooth(4.4, 4.95, t))                              # se remet à l'endroit (pose de nage)

    def ult_roll(t):
        if t < 0.3:
            return 0.0
        if t < 2.0:
            return -22 * smooth(0.5, 1.0, t) * (1 - smooth(1.8, 2.0, t))     # penche vers le centre du cercle
        if t < 3.3:
            return 0.0
        return 180 * smooth(3.3, 3.8, t) * (1 - smooth(4.4, 4.95, t))

    def circle_pos(t):
        a = math.radians(yaw(t)) if t <= 2.0 else math.radians(-720)
        r = radius(t)
        return (r * (1 - C(a)) - r, ult_y(t), r * S(a))

    spread = lambda t: smooth(2.55, 2.75, t) * (1 - smooth(3.2, 3.5, t))         # noqa: E731
    tuck = lambda t: (1 - smooth(2.55, 2.75, t)) + smooth(4.4, 4.95, t)          # noqa: E731
    float_ = lambda t: smooth(3.3, 3.8, t) * (1 - smooth(4.4, 4.95, t))         # noqa: E731
    start_back = lambda t: 0.0                                                   # noqa: E731
    on_back_u = lambda t: max(start_back(t), float_(t))                           # noqa: E731
    tr = {
        "Body": track(5.0, rotation=lambda t: (ult_pitch(t), yaw(t), ult_roll(t)), position=circle_pos),
        "Chest": track(5.0, rotation=lambda t: (-6 * on_back_u(t) + 12 * spread(t), 0, 0)),
        "Hips": track(5.0, rotation=lambda t: (-4 * on_back_u(t) - 12 * spread(t), 0, 0)),
        "Head": track(5.0, rotation=lambda t: (-30 * on_back_u(t) + 10 * spread(t),
                                               14 * S(TAU * 1.3 * t) * smooth(3.8, 4.1, t), 8 * S(TAU * 0.9 * t) * smooth(3.8, 4.1, t))),
        "Jaw": track(5.0, rotation=lambda t: (-20 * bump(2.0, 3.3, t) - 8 * smooth(3.8, 4.2, t) * (1 - smooth(4.6, 5.0, t)), 0, 0)),
    }
    for side in ("right", "left"):
        s = 1 if side == "right" else -1
        tr[f"Leg_front_{side}"] = track(5.0, rotation=lambda t, s=s: (
            45 * on_back_u(t) - 84 * tuck(t) + 40 * spread(t), 0, -38 * s * on_back_u(t) + 30 * s * spread(t)))
        tr[f"Paw_front_{side}"] = track(5.0, rotation=lambda t: (40 * on_back_u(t) - 12 * tuck(t), 0, 0))
        tr[f"Leg_back_{side}"] = track(5.0, rotation=lambda t, s=s: (
            -25 * on_back_u(t) - 60 * tuck(t) - 40 * spread(t), 0, 30 * s * spread(t)))
        tr[f"Foot_back_{side}"] = track(5.0, rotation=lambda t: (20 * tuck(t), 0, 0))
    for i, b in enumerate(TAIL):
        tr[b] = track(5.0, rotation=lambda t, i=i: (
            (-30, -6, 0, 0)[i] * max(on_back_u(t), tuck(t)) + (-35, -15, -10, -8)[i] * spread(t)
            + (0, 0, 10, 10)[i] * S(TAU * 3 * t) * tuck(t), 0, 0))
    tr["Whiskers_right"] = track(5.0, rotation=lambda t: (0, -18 * tuck(t) + 3 * S(TAU * 2 * t), -14 * smooth(3.8, 4.2, t)))
    tr["Whiskers_left"] = track(5.0, rotation=lambda t: (0, 18 * tuck(t) - 3 * S(TAU * 2 * t), 14 * smooth(3.8, 4.2, t)))
    out.append(anim("ultimate_breach", 5.0, "once", tr))

    # ------------------------------------------------------------------ death_water : mort dans l'eau, 4 s (80 ticks),
    # tenue. Depuis swim_idle : spasme (0-0,4 s), les membres se relâchent, la tête part en arrière (0,4-1,4 s), puis
    # elle coule lentement en penchant la tête vers le fond (1,4-4 s), jusqu'à 2,5 blocs sous la surface.
    sink = lambda t: smooth(1.4, 4.0, t)                                            # noqa: E731
    limp = lambda t: smooth(0.4, 1.4, t)                                            # noqa: E731
    tr = {
        "Body": track(4.0, rotation=lambda t: (-12 * sink(t), 0, 180 * smooth(0, 0.6, t) + 12 * S(TAU * 5 * t) * bump(0, 0.4, t) + 22 * sink(t)),
                      position=lambda t: (0, SURFACE_Y * smooth(0, 0.6, t) + (-2.5 * BL - SURFACE_Y) * sink(t), 0)),
        "Chest": track(4.0, rotation=lambda t: (-6 * (1 - limp(t)), 0, 0)),
        "Hips": track(4.0, rotation=lambda t: (-4 * (1 - limp(t)), 0, 0)),
        "Head": track(4.0, rotation=lambda t: (-30 + 38 * limp(t), 10 * S(TAU * 6 * t) * bump(0, 0.4, t), 8 * limp(t))),
        "Jaw": track(4.0, rotation=lambda t: (-22 * smooth(0, 0.2, t) + 6 * limp(t), 0, 0)),
    }
    for side in ("right", "left"):
        s = 1 if side == "right" else -1
        tr[f"Leg_front_{side}"] = track(4.0, rotation=lambda t, s=s: (45 - 25 * limp(t), 0, -38 * s * (1 - limp(t)) + 15 * s * limp(t)))
        tr[f"Paw_front_{side}"] = track(4.0, rotation=lambda t: (40 * (1 - limp(t)), 0, 0))
        tr[f"Leg_back_{side}"] = track(4.0, rotation=lambda t, s=s: (-25 + 10 * S(TAU * 6 * t) * bump(0, 0.4, t) + 15 * limp(t), 0, 20 * s * limp(t)))
    for i, b in enumerate(TAIL):
        tr[b] = track(4.0, rotation=lambda t, i=i: ((-30, -6, 0, 0)[i] * (1 - limp(t)) + 6 * limp(t), 4 * limp(t), 0))
    tr["Whiskers_right"] = track(4.0, rotation=lambda t: (0, 4 * S(TAU * 6 * t) * bump(0, 0.4, t), -14 * limp(t)))
    tr["Whiskers_left"] = track(4.0, rotation=lambda t: (0, -4 * S(TAU * 6 * t) * bump(0, 0.4, t), 14 * limp(t)))
    out.append(anim("death_water", 4.0, "hold", tr))
    return out


# --------------------------------------------------------------------------- mesures pour la fiche
def mesures(data, only=None):
    """Par animation et par tick : point le plus bas et le plus haut du modèle, plus bas et plus haut de la tête
    (en blocs, 0 = sol ou surface de l'eau), et position du bout du museau et du bout de la queue."""
    import os
    import sys
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import paths  # noqa: F401  (scripts/influcard on the path)
    from apercu_anim import rot_matrix, mul, apply, sample

    by_uuid = {e["uuid"]: e for e in data["elements"]}
    res = {}
    for a in data["animations"]:
        if only and a["name"] not in only:
            continue
        frames = {an["name"]: an["keyframes"] for an in a["animators"].values()}
        rows = []
        for tick in range(int(round(a["length"] * 20)) + 1):
            t = tick / 20
            pts = {}

            def walk(group, B, c, bone_path):
                o = group["origin"]
                kf = frames.get(group["name"], [])
                r = sample(kf, "rotation", t) or [0, 0, 0]
                p = sample(kf, "position", t) or [0, 0, 0]
                Bn = mul(B, rot_matrix(-r[0], -r[1], r[2]))
                shift = apply(B, [o[i] + [-p[0], p[1], p[2]][i] for i in range(3)])
                cn = [shift[i] + c[i] - apply(Bn, o)[i] for i in range(3)]
                for child in group.get("children", []):
                    if isinstance(child, dict):
                        walk(child, Bn, cn, bone_path + [child["name"]])
                    elif child in by_uuid:
                        e = by_uuid[child]
                        M = mul(Bn, rot_matrix(*(e.get("rotation") or [0, 0, 0])))
                        eo = e["origin"]
                        q0 = [apply(Bn, eo)[i] + cn[i] for i in range(3)]
                        for cx in (e["from"][0], e["to"][0]):
                            for cy in (e["from"][1], e["to"][1]):
                                for cz in (e["from"][2], e["to"][2]):
                                    q = apply(M, [cx - eo[0], cy - eo[1], cz - eo[2]])
                                    pts.setdefault(group["name"], []).append(
                                        (e["name"], [q[i] + q0[i] for i in range(3)]))
            for g in data["outliner"]:
                walk(g, [[1, 0, 0], [0, 1, 0], [0, 0, 1]], [0, 0, 0], [g["name"]])
            allp = [p for v in pts.values() for _, p in v]
            head = [p for _, p in pts.get("Head", [])] + [p for _, p in pts.get("Jaw", [])]
            nose = [p for n, p in pts.get("Head", []) if n == "truffe"]
            tip = [p for n, p in pts.get("Tail_4", [])]
            mid = lambda q: [round(sum(v[i] for v in q) / len(q) / 16, 2) for i in range(3)]   # noqa: E731
            rows.append({"tick": tick, "low": round(min(p[1] for p in allp) / 16, 2),
                         "high": round(max(p[1] for p in allp) / 16, 2),
                         "head_low": round(min(p[1] for p in head) / 16, 2),
                         "head_high": round(max(p[1] for p in head) / 16, 2),
                         "nose": mid(nose), "tail_tip": mid(tip)})
        res[a["name"]] = rows
    return res


# --------------------------------------------------------------------------- verrou au sol
# Animations terrestres dont le point le plus bas doit toucher le sol à chaque tick (pieds posés, ventre ou flanc au
# sol), avec les fenêtres (en secondes) où le corps a le droit de quitter le sol ou d'y entrer.
GROUND_LOCK = {
    "idle": (), "walk": (), "attack_claw": (), "attack_tail": ((0.95, 1.5),), "attack_slide": (),
    "death_land": (), "transition_dive": ((2.25, 99),),
}


def ground_lock(data):
    """Recale la hauteur de Body tick par tick pour que le point le plus bas touche le sol (y = 0), hors fenêtres
    libres ; la correction est lissée sur les bords des fenêtres pour ne pas sauter."""
    import os
    import sys
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import paths  # noqa: F401  (scripts/influcard on the path)
    from apercu_anim import sample
    lows = mesures(data, only=set(GROUND_LOCK))
    for a in data["animations"]:
        if a["name"] not in GROUND_LOCK:
            continue
        free = GROUND_LOCK[a["name"]]
        body = next((an for an in a["animators"].values() if an["name"] == "Body"), None)
        if body is None:                       # animation sans piste Body : on la crée sur l'os Body du modèle
            def find(g):
                for c in g.get("children", []):
                    if isinstance(c, dict):
                        if c["name"] == "Body":
                            return c["uuid"]
                        f = find(c)
                        if f:
                            return f
            body = {"name": "Body", "type": "bone", "keyframes": []}
            a["animators"][find(data["outliner"][0])] = body
        kf = body["keyframes"]
        rows = lows[a["name"]]
        corr = []
        for r in rows:
            t = r["tick"] / 20
            inside = any(f0 <= t <= f1 for f0, f1 in free)
            corr.append(None if inside else -r["low"] * 16)
        # bords des fenêtres libres : la correction rejoint 0 en douceur sur 3 ticks
        filled = []
        for i, c in enumerate(corr):
            if c is not None:
                filled.append(c)
                continue
            prev = next((corr[j] for j in range(i - 1, -1, -1) if corr[j] is not None), 0.0)
            nxt = next((corr[j] for j in range(i + 1, len(corr)) if corr[j] is not None), 0.0)
            dp = i - next((j for j in range(i - 1, -1, -1) if corr[j] is not None), i - 99)
            dn = next((j for j in range(i + 1, len(corr)) if corr[j] is not None), i + 99) - i
            filled.append(prev * max(0, 1 - dp / 3) + nxt * max(0, 1 - dn / 3))
        if a["loop"] == "loop":
            filled[-1] = filled[0]
        new = []
        for r, c in zip(rows, filled):
            t = r["tick"] / 20
            p = sample(kf, "position", t) or [0, 0, 0]
            new.append({"channel": "position", "data_points": [{"x": f"{p[0]:.3f}", "y": f"{p[1] + c:.3f}",
                                                                "z": f"{p[2]:.3f}"}],
                        "uuid": str(uuid.uuid5(uuid.NAMESPACE_URL, f"loutre_marees/lock/{a['name']}/{r['tick']}")),
                        "time": round(t, 4), "color": -1,
                        "interpolation": "linear"})
        body["keyframes"] = [k for k in kf if k["channel"] != "position"] + new

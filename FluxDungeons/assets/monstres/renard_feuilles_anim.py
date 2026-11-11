#!/usr/bin/env python3
"""Animations of the Sylvan Fox (Renard sylvestre, boss of the herbalist dungeon, 2026-11-11).

Model: renard_feuilles.py (the leaf fox). Locomotion (idle, walk, run), basic bite (attack), hurt, the entrance
(awaken), the four abilities (claws, seed, roots, realm) and the end of the ultimate (realm_end). No death animation.

Every timing that matters to the game (impacts, launch, warnings, waves) is in EVENTS below, written in
docs/technique/boss/renard-sylvestre.md and read by FluxDungeons (mob/FoxPowers.java, spells/herboriste/*.yml): keep
the three in step. Ticks are 1/20 s; every key moment falls on a tick.

Values as in hamster_moissonneur_anim.py: rotation in degrees in the model's axes (x > 0 lifts the front of a bone:
it lowers a tail bone's tip and moves a foot forwards, a negative x opens the jaw and lowers the head, a positive x
lays an ear back; y > 0 turns the front of a bone to the fox's left (-x) and swings a tail tip to its right; z > 0
lifts its right side and moves a hanging foot to its right), position in pixels (16 = 1 block). Written in the
Blockbench / BetterModel convention by build().

Legs are children of the body: whenever the body moves or pitches, `planted()` gives each leg the opposite offset
and pitch so that its foot stays where it was (a crouch bends the body, not the ground).
"""
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import paths  # noqa: F401,E402
from hamster_moissonneur_anim import Anim, T, _app, _rot, bump, keys, pose, smooth  # noqa: E402

TAU = 2 * math.pi
S, C = math.sin, math.cos
lin = "linear"
SIDES = ("right", "left")
LEGS = tuple(f"{k}_{s}" for k in ("front", "back") for s in SIDES)
FRONT = ("front_right", "front_left")
TAIL = ("Tail_base", "Tail_mid", "Tail_tip")

# Key ticks read by the game (FoxPowers): keep in step with docs/technique/boss/renard-sylvestre.md.
EVENTS = {
    "attack": {"bite": 3},
    "awaken": {"leaves": 0, "roots": 10, "rise": 30, "out": 50, "settled": 70, "turn": 70, "growl": 76, "guard": 85},
    "claws": {"crouch": 0, "raise": 8, "swipe_1": 13, "impact_1": 17, "impact_2": 22, "recover": 22, "end": 30},
    "seed": {"aim": 0, "form": 8, "launch": 20, "end": 33},
    "roots": {"anchor": 0, "slam": 12, "wave_end": 24, "warn": 24, "trap": 36, "sink": 44, "end": 54},
    "realm": {"stop": 0, "charge": 10, "eyes": 18, "roar": 36, "wave": 43, "wave_end": 58},
    "realm_end": {"end": 20},
}


def shake(t, amp, freq=11.0):
    """Deterministic tremor."""
    return amp * (S(TAU * freq * t) * 0.6 + S(TAU * freq * 1.7 * t + 1.3) * 0.4)


def tk(*pts):
    """keys() over ticks instead of seconds: tk((0, v0), (8, v1), ...) -> f(t)."""
    return lambda t: keys(t * 20, list(pts))


def v3(f):
    """Scalar curve to (value, 0, 0)."""
    return lambda t: (f(t), 0, 0)


# =========================================================================== rig
class Rig:
    def __init__(self, model):
        self.o = {}

        def walk(g):
            for c in g.get("children", []):
                if isinstance(c, dict):
                    self.o[c["name"]] = list(c["origin"])
                    walk(c)
        walk(model["outliner"][0])


def planted(anim, rig, swing=None, knee=None, extra=None, follow=None):
    """Legs that keep their feet on the ground whatever the body does: each leg gets the offset that brings its top
    back where it was and the opposite of the body's pitch and roll, plus its own `swing` (rotation f(t) per leg),
    `knee` (rotation of its paw) and `extra` (position added, pixels). `follow` f(t) in [0, 1]: 1 = the legs simply
    follow the body (no compensation: a fox still rising out of the ground)."""
    body = anim.tracks.get("Body", {})
    rot_f = body.get("rotation", lambda t: (0, 0, 0))
    pos_f = body.get("position", lambda t: (0, 0, 0))
    ob = rig.o["Body"]
    swing, knee, extra = swing or {}, knee or {}, extra or {}
    follow = follow or (lambda t: 0.0)
    for leg in LEGS:
        ol = rig.o[f"Leg_{leg}"]

        def pos(t, ol=ol, leg=leg):
            r, p = rot_f(t), pos_f(t)
            R = _rot(*r)
            Rt = [[R[j][i] for j in range(3)] for i in range(3)]
            shift = [ob[i] + p[i] for i in range(3)]
            Ro = _app(R, ob)
            cn = [shift[i] - Ro[i] for i in range(3)]
            want = _app(Rt, [ol[i] - cn[i] for i in range(3)])
            e = extra.get(leg, lambda t: (0, 0, 0))(t)
            k = 1 - follow(t)
            return tuple((want[i] - ol[i]) * k + e[i] for i in range(3))

        def rot(t, leg=leg):
            r = rot_f(t)
            s = swing.get(leg, lambda t: (0, 0, 0))(t)
            k = 1 - follow(t)
            return (s[0] - r[0] * k, s[1] - r[1] * k, s[2] - r[2] * k)
        anim.pos(f"Leg_{leg}", pos)
        anim.rot(f"Leg_{leg}", rot)
        if leg in knee:
            anim.rot(f"Paw_{leg}", knee[leg])


def tail(anim, pitch, yaw=lambda t: 0.0, lag=0.06, share=(0.45, 0.33, 0.22)):
    """The three tail bones share a pitch and a yaw; each segment follows the one before with a small lag (a fluid
    tail, never a stiff stick)."""
    for i, b in enumerate(TAIL):
        d = lag * i
        anim.rot(b, lambda t, d=d, k=share[i]: (pitch(max(0, t - d)) * k, yaw(max(0, t - d)) * k, 0))


def ears(anim, back, twitch_r=lambda t: 0.0, twitch_l=lambda t: 0.0):
    """Both ears laid back by `back` degrees (0 = up), plus a small separate twitch."""
    anim.rot("Ear_right", lambda t: (back(t) + twitch_r(t), 0, 0))
    anim.rot("Ear_left", lambda t: (back(t) + twitch_l(t), 0, 0))


# =========================================================================== animations
def animations(rig):
    out = []

    # ------------------------------------------------------------------ idle: 4 s loop
    a = Anim("idle", 80, loop=True)
    a.pos("Body", lambda t: (0, 0.25 * S(TAU * t / 2), 0))
    a.scale("Body", lambda t: (1 + 0.008 * S(TAU * t / 2), 1 + 0.008 * S(TAU * t / 2), 1))
    a.rot("h_Head", lambda t: (-3 + 2.5 * S(TAU * t / 4 + 0.6), 5 * S(TAU * t / 4), 0))
    ears(a, lambda t: 4 * S(TAU * t / 4),
         twitch_r=lambda t: 22 * bump(1.2, 1.5, t), twitch_l=lambda t: 18 * bump(3.0, 3.3, t) - 10 * bump(2.0, 2.4, t))
    tail(a, lambda t: -8 + 4 * S(TAU * t / 4 + 1), lambda t: 16 * S(TAU * t / 4))
    planted(a, rig)
    out.append(a)

    # ------------------------------------------------------------------ walk: trot, 12-tick cycle (0.6 s)
    L = 0.6
    a = Anim("walk", 12, loop=True)
    a.pos("Body", lambda t: (0, -0.6 + 0.5 * C(TAU * 2 * t / L), 0))
    a.rot("Body", lambda t: (1.5 * S(TAU * t / L), 0, 1.2 * S(TAU * t / L + 0.5)))
    a.rot("Neck", lambda t: (-4, 0, 0))
    a.rot("h_Head", lambda t: (-2 - 1.5 * S(TAU * t / L), 0, -1.2 * S(TAU * t / L + 0.5)))
    a.pos("h_Head", lambda t: (0, 0, -0.5))
    ears(a, lambda t: 14 + 4 * S(TAU * 2 * t / L))
    tail(a, lambda t: -6 + 3 * S(TAU * 2 * t / L), lambda t: 8 * S(TAU * t / L))
    # diagonal pairs: front right with back left, front left with back right
    off = {"front_right": 0.0, "back_left": 0.0, "front_left": 0.5, "back_right": 0.5}
    swing, knee = {}, {}
    for leg, o in off.items():
        amp = 26 if leg.startswith("front") else 22
        swing[leg] = lambda t, o=o, amp=amp: (amp * S(TAU * (t / L + o)), 0, 0)
        # the knee bends while the leg swings forwards (foot lifted), straight while planted
        knee[leg] = lambda t, o=o: (-34 * max(0.0, C(TAU * (t / L + o))) ** 1.5 + 4, 0, 0)
    planted(a, rig, swing, knee)
    out.append(a)

    # ------------------------------------------------------------------ run: bounding gallop, 8-tick cycle (0.4 s)
    L = 0.4
    a = Anim("run", 8, loop=True)
    a.pos("Body", lambda t: (0, -1.4 + 0.9 * S(TAU * t / L), 0))
    a.rot("Body", lambda t: (-3 + 4.5 * S(TAU * t / L + 1.4), 0, 0))
    a.rot("Neck", lambda t: (-7 - 3 * S(TAU * t / L + 1.4), 0, 0))
    a.pos("h_Head", lambda t: (0, 0, -1.0))
    a.rot("h_Head", lambda t: (-5, 0, 0))
    ears(a, lambda t: 32)
    tail(a, lambda t: 12 + 4 * S(TAU * t / L), lambda t: 4 * S(TAU * t / L))
    off = {"front_right": 0.0, "front_left": 0.08, "back_right": 0.5, "back_left": 0.58}
    swing, knee = {}, {}
    for leg, o in off.items():
        amp = 38 if leg.startswith("front") else 34
        swing[leg] = lambda t, o=o, amp=amp: (amp * S(TAU * (t / L + o)), 0, 0)
        knee[leg] = lambda t, o=o: (-48 * max(0.0, C(TAU * (t / L + o))) ** 1.3 + 6, 0, 0)
    planted(a, rig, swing, knee)
    out.append(a)

    # ------------------------------------------------------------------ attack: the basic bite (14 ticks, bite at 3)
    a = Anim("attack", 14)
    a.pos("Body", tk((0, (0, 0, 0)), (2, (0, -0.8, -1.4)), (4, (0, -0.8, -1.8)), (14, (0, 0, 0))))
    a.rot("Body", tk((0, (0, 0, 0)), (3, (-4, 0, 0)), (14, (0, 0, 0))))
    a.rot("Neck", tk((0, (0, 0, 0)), (2, (-8, 0, 0)), (5, (-6, 0, 0)), (14, (0, 0, 0))))
    a.rot("h_Head", tk((0, (4, 0, 0)), (2, (-10, 0, 0)), (4, (-6, 0, 0)), (14, (0, 0, 0))))
    a.rot("Jaw", tk((0, (0, 0, 0)), (2, (-32, 0, 0)), (3, (0, 0, 0)), (5, (-6, 0, 0)), (8, (0, 0, 0))))
    ears(a, tk((0, 10), (2, 30), (8, 24), (14, 4)))
    tail(a, tk((0, -6), (3, 4), (14, -6)))
    planted(a, rig)
    out.append(a)

    # ------------------------------------------------------------------ hurt (8 ticks)
    a = Anim("hurt", 8)
    a.pos("Body", tk((0, (0, 0, 0)), (2, (0, -0.4, 0.8)), (8, (0, 0, 0))))
    a.rot("h_Head", tk((0, (0, 0, 0)), (2, (8, 0, -4)), (8, (0, 0, 0))))
    ears(a, tk((0, 0), (2, 34), (8, 0)))
    tail(a, tk((0, 0), (2, -14), (8, 0)))
    planted(a, rig)
    out.append(a)

    # ------------------------------------------------------------------ awaken: the entrance (85 ticks)
    # 0-30 under the ground (the leaves gather above it), 30-50 it rises out of the soil front first, 50-70 sets its
    # four feet and looks at the entrance, 70-85 turns its head to the players, growls (76), takes its guard.
    a = Anim("awaken", 85)
    a.pos("Body", tk((0, (0, -44, 3)), (30, (0, -44, 3)), (44, (0, -4, 0)), (50, (0, 0.6, 0)), (56, (0, -1.4, 0)),
                     (62, (0, 0, 0)), (78, (0, 0, 0)), (85, (0, -1.0, 0))))
    a.rot("Body", tk((0, (26, 0, 0)), (30, (26, 0, 0)), (44, (12, 0, 0)), (52, (-3, 0, 0)), (60, (0, 0, 0)),
                     (78, (0, 0, 0)), (85, (-2, 0, 0))))
    a.rot("Neck", tk((0, (-10, 0, 0)), (40, (-4, 0, 0)), (56, (-8, 0, 0)), (70, (-8, 0, 0)), (85, (-4, 0, 0))))
    a.rot("h_Head", tk((0, (12, 0, 0)), (40, (8, 0, 0)), (56, (-8, 22, 0)), (70, (-6, 24, 0)), (80, (-4, 0, 0)),
                       (85, (-6, 0, 0))))
    a.rot("Jaw", tk((0, (0, 0, 0)), (74, (0, 0, 0)), (77, (-14, 0, 0)), (82, (-6, 0, 0)), (85, (0, 0, 0))))
    ears(a, tk((0, 36), (44, 30), (54, -6), (70, -4), (76, 26), (85, 18)))
    tail(a, tk((0, 20), (44, 14), (56, 0), (70, -16), (85, -22)), tk((0, 0), (60, 0), (70, 10), (78, -6), (85, 0)))
    # legs: while rising the front legs reach for the edge, then all four are set down one after the other
    swing = {
        "front_right": tk((0, (40, 0, 0)), (36, (40, 0, 0)), (44, (12, 0, 0)), (52, (0, 0, 0))),
        "front_left": tk((0, (46, 0, 0)), (38, (46, 0, 0)), (46, (14, 0, 0)), (54, (0, 0, 0))),
        "back_right": tk((0, (-20, 0, 0)), (44, (-20, 0, 0)), (54, (0, 0, 0))),
        "back_left": tk((0, (-24, 0, 0)), (46, (-24, 0, 0)), (56, (0, 0, 0))),
    }
    knee = {
        "front_right": tk((0, (-30, 0, 0)), (40, (-30, 0, 0)), (50, (0, 0, 0))),
        "front_left": tk((0, (-36, 0, 0)), (42, (-36, 0, 0)), (52, (0, 0, 0))),
        "back_right": tk((0, (-10, 0, 0)), (50, (-10, 0, 0)), (56, (0, 0, 0))),
        "back_left": tk((0, (-14, 0, 0)), (52, (-14, 0, 0)), (58, (0, 0, 0))),
    }
    # while it rises out of the ground the legs follow the body; planted once it stands (50-56)
    planted(a, rig, swing, knee, follow=lambda t: 1 - smooth(2.3, 2.8, t))
    out.append(a)

    # ------------------------------------------------------------------ claws: Griffes sylvestres (30 ticks)
    # 0-8 crouch, 8-13 weight back and the right paw rises, 13-17 first swipe (right paw, diagonal from its right to
    # its left, impact 17), 17-22 second swipe (left paw, wider, from its left to its right, impact 22), 22-30 recover
    a = Anim("claws", 30)
    a.pos("Body", tk((0, (0, 0, 0)), (8, (0, -1.3, 0)), (13, (-0.8, -1.0, 1.1)), (17, (0.4, -1.4, -1.6)),
                     (20, (0.8, -1.2, -1.2)), (22, (-0.6, -1.5, -2.0)), (30, (0, 0, 0))))
    a.rot("Body", tk((0, (0, 0, 0)), (8, (-3, 0, 0)), (13, (7, 0, 5)), (17, (-6, 4, -3)), (19, (5, -2, -4)),
                     (22, (-7, -5, 4)), (30, (0, 0, 0))))
    a.rot("Neck", tk((0, (0, 0, 0)), (8, (-6, 0, 0)), (13, (-4, 0, 0)), (17, (-8, 0, 0)), (22, (-9, 0, 0)),
                     (30, (0, 0, 0))))
    a.rot("h_Head", tk((0, (0, 0, 0)), (8, (-6, 0, 0)), (13, (-2, -4, 0)), (17, (-6, 12, 0)), (22, (-7, -14, 0)),
                       (30, (0, 0, 0))))
    a.rot("Jaw", tk((0, (0, 0, 0)), (14, (0, 0, 0)), (17, (-12, 0, 0)), (19, (-4, 0, 0)), (22, (-14, 0, 0)),
                    (26, (0, 0, 0))))
    ears(a, tk((0, 0), (6, 28), (24, 28), (30, 4)))
    tail(a, tk((0, -4), (8, -14), (22, -12), (30, -6)), tk((0, 0), (13, -14), (17, 6), (19, 16), (22, -18), (30, 0)))
    swing = {
        "front_right": tk((0, (0, 0, 0)), (8, (10, 0, 0)), (13, (82, 0, -16)), (15, (48, 0, 2)), (17, (-8, 0, 22)),
                          (19, (-2, 0, 6)), (30, (0, 0, 0))),
        "front_left": tk((0, (0, 0, 0)), (8, (10, 0, 0)), (17, (6, 0, 0)), (19, (86, 0, 18)), (20, (52, 0, 0)),
                         (22, (-8, 0, -28)), (24, (-2, 0, -8)), (30, (0, 0, 0))),
        "back_right": tk((0, (0, 0, 0)), (8, (-6, 0, 0)), (30, (0, 0, 0))),
        "back_left": tk((0, (0, 0, 0)), (8, (-6, 0, 0)), (30, (0, 0, 0))),
    }
    knee = {
        "front_right": tk((0, (0, 0, 0)), (8, (-16, 0, 0)), (13, (-40, 0, 0)), (16, (-12, 0, 0)), (17, (8, 0, 0)),
                         (22, (0, 0, 0))),
        "front_left": tk((0, (0, 0, 0)), (8, (-16, 0, 0)), (17, (-10, 0, 0)), (19, (-42, 0, 0)), (21, (-10, 0, 0)),
                         (22, (10, 0, 0)), (27, (0, 0, 0))),
    }
    planted(a, rig, swing, knee)
    out.append(a)

    # ------------------------------------------------------------------ seed: Graine prédatrice (33 ticks)
    # 0-8 still, ears up, tail rising, the mouth opens a little; 8-18 the seed forms before its snout (jaw ajar,
    # small tremor); 18-21 dry snap of the head, mouth wide open, the seed leaves at 20; 21-33 recover
    a = Anim("seed", 33)
    a.pos("Body", tk((0, (0, 0, 0)), (8, (0, -0.4, 0.4)), (18, (0, -0.2, 0.8)), (20, (0, -0.8, -1.4)), (33, (0, 0, 0))))
    a.rot("Body", tk((0, (0, 0, 0)), (18, (2, 0, 0)), (20, (-4, 0, 0)), (33, (0, 0, 0))))
    a.rot("Neck", tk((0, (0, 0, 0)), (8, (4, 0, 0)), (18, (6, 0, 0)), (20, (-8, 0, 0)), (33, (0, 0, 0))))
    a.rot("h_Head", lambda t: add(tk((0, (0, 0, 0)), (8, (6, 0, 0)), (18, (8, 0, 0)), (20, (-12, 0, 0)), (24, (-6, 0, 0)),
                                     (33, (0, 0, 0)))(t), (shake(t, 0.8) * bump(0.4, 0.9, t), 0, 0)))
    a.pos("h_Head", tk((0, (0, 0, 0)), (18, (0, 0, 0.4)), (20, (0, 0, -1.3)), (26, (0, 0, 0))))
    a.rot("Jaw", tk((0, (0, 0, 0)), (8, (-8, 0, 0)), (18, (-10, 0, 0)), (19, (-34, 0, 0)), (21, (-4, 0, 0)), (25, (0, 0, 0))))
    ears(a, tk((0, 4), (6, -10), (18, -10), (20, 14), (33, 4)))
    tail(a, tk((0, -6), (8, -16), (18, -22), (20, -8), (23, -16), (33, -6)), tk((0, 0), (20, 0), (22, -10), (33, 0)))
    planted(a, rig)
    out.append(a)

    # ------------------------------------------------------------------ roots: Étreinte des racines (54 ticks)
    # 0-8 anchoring (lower, front paws closer), 8-12 both front paws rise and strike the ground together (12),
    # 12-24 pressed down while the wave runs, 24-44 holds and watches the traps (they close at 36), 44-54 recover
    a = Anim("roots", 54)
    a.pos("Body", lambda t: add(tk((0, (0, 0, 0)), (8, (0, -1.6, 0.4)), (10, (0, -0.6, 0.6)), (12, (0, -2.6, -0.4)),
                                   (24, (0, -2.3, -0.2)), (44, (0, -1.6, 0)), (54, (0, 0, 0)))(t),
                                (0, shake(t, 0.15) * bump(0.6, 1.2, t), 0)))
    a.rot("Body", tk((0, (0, 0, 0)), (8, (-4, 0, 0)), (10, (9, 0, 0)), (12, (-7, 0, 0)), (24, (-6, 0, 0)), (34, (-4, 0, 0)),
                     (36, (-7, 0, 0)), (44, (-3, 0, 0)), (54, (0, 0, 0))))
    a.rot("Neck", tk((0, (0, 0, 0)), (8, (-8, 0, 0)), (10, (-2, 0, 0)), (12, (-12, 0, 0)), (24, (-8, 0, 0)), (30, (-2, 0, 0)),
                     (34, (-2, 0, 0)), (36, (-10, 0, 0)), (44, (-4, 0, 0)), (54, (0, 0, 0))))
    a.rot("h_Head", tk((0, (0, 0, 0)), (8, (-10, 0, 0)), (12, (-14, 0, 0)), (24, (-6, 0, 0)), (30, (0, 0, 0)),
                       (36, (-8, 0, 0)), (44, (-2, 0, 0)), (54, (0, 0, 0))))
    a.rot("Jaw", tk((0, (0, 0, 0)), (10, (0, 0, 0)), (12, (-12, 0, 0)), (16, (0, 0, 0)), (34, (0, 0, 0)), (36, (-10, 0, 0)),
                    (40, (0, 0, 0))))
    ears(a, tk((0, 4), (8, 24), (44, 24), (54, 4)))
    tail(a, tk((0, -6), (8, -16), (10, -24), (12, -8), (16, -18), (44, -16), (54, -6)),
         lambda t: 10 * S(TAU * t / 1.2) * bump(1.2, 2.2, t))
    swing = {
        "front_right": tk((0, (0, 0, 0)), (8, (6, 0, -6)), (10, (70, 0, -6)), (12, (-4, 0, -6)), (44, (-2, 0, -4)),
                          (54, (0, 0, 0))),
        "front_left": tk((0, (0, 0, 0)), (8, (6, 0, 6)), (10, (70, 0, 6)), (12, (-4, 0, 6)), (44, (-2, 0, 4)),
                         (54, (0, 0, 0))),
    }
    knee = {
        "front_right": tk((0, (0, 0, 0)), (8, (-8, 0, 0)), (10, (-38, 0, 0)), (12, (2, 0, 0)), (44, (0, 0, 0))),
        "front_left": tk((0, (0, 0, 0)), (8, (-8, 0, 0)), (10, (-38, 0, 0)), (12, (2, 0, 0)), (44, (0, 0, 0))),
    }
    planted(a, rig, swing, knee)
    out.append(a)

    # ------------------------------------------------------------------ realm: Royaume de la sylve (58 ticks)
    # 0-10 sudden stop, head down, tail down, legs set wide; 10-35 the head rises slowly, the tail spreads, the coat
    # shivers (eyes lit at 18); 35-43 chest up, mouth wide open, the roar (36); 43-58 the wave runs out, it lowers into
    # its guard (the arena stays its realm for 100 ticks, then realm_end)
    a = Anim("realm", 58)
    a.pos("Body", lambda t: add(tk((0, (0, 0, 0)), (10, (0, -1.2, 0)), (34, (0, -0.6, 0.4)), (38, (0, 1.6, 1.2)),
                                   (46, (0, 1.2, 1.0)), (58, (0, -0.8, 0)))(t),
                                (0, shake(t, 0.18) * smooth(0.5, 1.6, t) * (1 - smooth(1.7, 1.8, t)), 0)))
    a.rot("Body", lambda t: add(tk((0, (0, 0, 0)), (10, (-3, 0, 0)), (34, (2, 0, 0)), (38, (11, 0, 0)), (46, (9, 0, 0)),
                                   (58, (-1, 0, 0)))(t),
                                (0, 0, shake(t, 0.9, 13) * smooth(0.5, 1.6, t) * (1 - smooth(1.7, 1.8, t)))))
    a.scale("Body", lambda t: (1 + 0.012 * S(TAU * 7 * t) * bump(0.5, 1.75, t), 1 + 0.012 * S(TAU * 7 * t + 1) * bump(0.5, 1.75, t), 1))
    a.rot("Neck", tk((0, (0, 0, 0)), (10, (-10, 0, 0)), (34, (2, 0, 0)), (38, (10, 0, 0)), (46, (8, 0, 0)), (58, (-4, 0, 0))))
    a.rot("h_Head", tk((0, (0, 0, 0)), (10, (-12, 0, 0)), (34, (6, 0, 0)), (38, (18, 0, 0)), (46, (16, 0, 0)),
                       (58, (-4, 0, 0))))
    a.rot("Jaw", tk((0, (0, 0, 0)), (34, (-4, 0, 0)), (37, (-38, 0, 0)), (44, (-36, 0, 0)), (48, (0, 0, 0))))
    ears(a, tk((0, 4), (10, 10), (30, -8), (36, 36), (46, 36), (58, 20)),
         twitch_r=lambda t: shake(t, 3) * bump(0.5, 1.7, t), twitch_l=lambda t: shake(t + 0.3, 3) * bump(0.5, 1.7, t))
    tail(a, tk((0, -6), (10, 6), (34, -26), (38, -36), (46, -34), (58, -16)),
         lambda t: 14 * S(TAU * t / 0.9) * bump(0.5, 1.8, t))
    a.scale("Tail_tip", lambda t: (1 + 0.16 * smooth(0.5, 1.7, t) * (1 - smooth(2.3, 2.9, t)),) * 3)
    a.scale("Tail_mid", lambda t: (1 + 0.08 * smooth(0.5, 1.7, t) * (1 - smooth(2.3, 2.9, t)),) * 3)
    swing = {
        "front_right": tk((0, (0, 0, 0)), (10, (0, 0, 6)), (58, (0, 0, 6))),
        "front_left": tk((0, (0, 0, 0)), (10, (0, 0, -6)), (58, (0, 0, -6))),
        "back_right": tk((0, (0, 0, 0)), (10, (0, 0, 5)), (58, (0, 0, 5))),
        "back_left": tk((0, (0, 0, 0)), (10, (0, 0, -5)), (58, (0, 0, -5))),
    }
    planted(a, rig, swing)
    out.append(a)

    # ------------------------------------------------------------------ realm_end (20 ticks): the realm fades
    a = Anim("realm_end", 20)
    a.pos("Body", tk((0, (0, -0.8, 0)), (20, (0, 0, 0))))
    a.rot("Neck", tk((0, (-4, 0, 0)), (8, (-10, 0, 0)), (20, (0, 0, 0))))
    a.rot("h_Head", tk((0, (-4, 0, 0)), (8, (-12, 0, 0)), (14, (-4, 6, -8)), (17, (-4, -6, 8)), (20, (0, 0, 0))))
    ears(a, tk((0, 20), (12, 10), (20, 0)))
    tail(a, tk((0, -16), (20, -6)), tk((0, 0), (12, 0), (16, 12), (20, 0)))
    planted(a, rig, {"front_right": tk((0, (0, 0, 6)), (20, (0, 0, 0))), "front_left": tk((0, (0, 0, -6)), (20, (0, 0, 0))),
                     "back_right": tk((0, (0, 0, 5)), (20, (0, 0, 0))), "back_left": tk((0, (0, 0, -5)), (20, (0, 0, 0)))})
    out.append(a)
    return out


def add(*vs):
    return tuple(sum(v[i] for v in vs) for i in range(3))


# =========================================================================== output
def build(model, uid):
    """Blockbench animations of the model, sampled every tick."""
    rig = Rig(model)
    out, n = [], [0]
    for a in animations(rig):
        values = a.sample()
        if a.loop:
            for chans in values.values():
                for vs in chans.values():
                    vs[-1] = vs[0]
        animators = {}
        for bone, chans in values.items():
            frames = []
            for ch, vs in chans.items():
                for tick, v in enumerate(vs):
                    if ch == "rotation":
                        v = (-v[0], -v[1], v[2])
                    elif ch == "position":
                        v = (-v[0], v[1], v[2])
                    n[0] += 1
                    frames.append({"channel": ch, "data_points": [{"x": f"{v[0]:.3f}", "y": f"{v[1]:.3f}", "z": f"{v[2]:.3f}"}],
                                   "uuid": uid(f"kf/{a.name}/{n[0]}"), "time": round(T(tick), 4), "color": -1,
                                   "interpolation": lin})
            animators[uid("group/" + bone)] = {"name": bone, "type": "bone", "keyframes": frames}
        out.append({"uuid": uid("anim/" + a.name), "name": a.name, "loop": "loop" if a.loop else "once",
                    "override": False, "length": a.length, "snapping": 20, "selected": False, "anim_time_update": "",
                    "blend_weight": "", "start_delay": "", "loop_delay": "", "animators": animators,
                    "_values": values})
    return out


def measure(model, anims):
    """Per animation and tick: lowest point (blocks), lowest foot, front of the snout."""
    res = {}
    for a in anims:
        rows = []
        for tick in range(int(round(a["length"] * 20)) + 1):
            _, pts = pose(model, a["_values"], tick)
            feet = {b[4:]: round(min(p[1] for p in ps) / 16, 2) for b, ps in pts.items() if b.startswith("Paw_")}
            hd = pts.get("h_Head", []) + pts.get("Jaw", [])
            snout = min(hd, key=lambda p: p[2])
            body = [p[1] for b, ps in pts.items() if not b.startswith(("Leg_", "Paw_")) for p in ps]
            rows.append({"tick": tick, "low": round(min(p[1] for ps in pts.values() for p in ps) / 16, 2),
                         "body_low": round(min(body) / 16, 2), "feet": feet, "snout": [round(v / 16, 2) for v in snout]})
        res[a["name"]] = rows
    return res


def strips(model, anims, atlases, sizes, folder, count=10, only=None):
    from taupe_champs_anim import strips as s
    s(model, anims, atlases, sizes, folder, count=count, only=only)


def finish(anims):
    for a in anims:
        a.pop("_values", None)
    return anims

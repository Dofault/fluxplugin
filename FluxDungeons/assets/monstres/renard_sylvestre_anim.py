#!/usr/bin/env python3
"""Animations of the Sylvan Fox (Renard sylvestre, boss of the herbalist dungeon), for the model of renard_sylvestre.py.

Locomotion (idle, walk, run), basic bite (attack), hurt, the entrance (awaken), the four abilities (claws, seed, roots,
realm) and the end of the ultimate (realm_end). No death animation.

Every timing that matters to the game (impacts, launch, warnings, waves) is in EVENTS below, written in
docs/technique/boss/renard-sylvestre.md and read by FluxDungeons (mob/FoxPowers.java, spells/herboriste/*.yml): keep
the three in step. Ticks are 1/20 s; every key moment falls on a tick.

Locomotion follows a real fox:
- walk is its trot: diagonal pairs, each foot on the ground half of the cycle and moving back under the body at the
  speed of the ground (walk-speed of the monster's file: no sliding), wrist folded while the leg comes forward, the
  body lowest at mid stance, a light sway of the spine, the head held level and still, the tail trailing with a lag;
- run is its gallop: both hind legs push almost together, a flight stretched out (front legs reaching, hind legs
  trailing), the front legs land one after the other, then a gathered flight (hind feet coming forward under the
  belly); the body pitches around its middle, the neck cancels it so the gaze stays on the prey, ears flat, the tail
  streams behind as a rudder.

Values: rotation in degrees in the model's axes (x > 0 lifts the front of a bone: it lowers a tail bone's tip and
moves a foot forwards, a negative x opens the jaw and lowers the head, a positive x lays an ear back; y > 0 turns the
front of a bone to the fox's left (-x) and swings a tail tip to its right; z > 0 lifts its right side and moves a
hanging foot to its right), position in pixels (16 = 1 block). Written in the Blockbench / BetterModel convention by
build().

Rig (renard_sylvestre.py): a front leg is one bone from the shoulder (Leg_front_*) and its foot (Paw_front_*, pivot at
the wrist); a hind leg is the thigh (Leg_back_*, hip) and the shank with the foot (Paw_back_*, pivot at the hock).
Legs are children of the body: whenever the body moves or pitches, `planted()` gives each leg the opposite offset and
pitch so that its foot stays where it was (a crouch bends the body, not the ground).
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

# Ground speed the gaits are drawn for, pixels per second: walk-speed of mobs/herboriste/renard_sylvestre.yml
# (1.6 blocks per second), and the sprint of FoxPowers (+55 %).
WALK_SPEED = 1.6 * 16
RUN_SPEED = WALK_SPEED * 1.55


def shake(t, amp, freq=11.0):
    """Deterministic tremor."""
    return amp * (S(TAU * freq * t) * 0.6 + S(TAU * freq * 1.7 * t + 1.3) * 0.4)


def tk(*pts):
    """keys() over ticks instead of seconds: tk((0, v0), (8, v1), ...) -> f(t)."""
    return lambda t: keys(t * 20, list(pts))


def add(*vs):
    return tuple(sum(v[i] for v in vs) for i in range(3))


def cycle(*pts):
    """Periodic curve over a phase in [0, 1): points (phase, value, "lin" | "ease"), the mode being the way the curve
    reaches that point from the one before. The last point joins the first one of the next cycle."""
    pts = sorted(pts)
    first = pts[0]
    ring = pts + [(first[0] + 1.0, first[1], first[2])]

    def f(u):
        u = (u - first[0]) % 1.0 + first[0]
        for (u0, v0, _), (u1, v1, mode) in zip(ring, ring[1:]):
            if u <= u1:
                k = (u - u0) / (u1 - u0) if u1 > u0 else 1.0
                if mode != "lin":
                    k = k * k * (3 - 2 * k)
                return v0 + (v1 - v0) * k
        return first[1]
    return f


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

    def reach(self, leg):
        """Height of the leg's pivot above the ground: the radius its foot turns on."""
        return self.o[f"Leg_{leg}"][1]


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


def tail(anim, pitch, yaw=lambda t: 0.0, lag=0.06, share=(0.45, 0.33, 0.22), wrap=None):
    """The three tail bones share a pitch and a yaw; each segment follows the one before with a small lag (a fluid
    tail, never a stiff stick). `wrap`: length of a looping animation, the lag then turns around the loop."""
    for i, b in enumerate(TAIL):
        d = lag * i

        def at(t, d=d):
            return (t - d) % wrap if wrap else max(0, t - d)
        anim.rot(b, lambda t, at=at, k=share[i]: (pitch(at(t)) * k, yaw(at(t)) * k, 0))


def ears(anim, back, twitch_r=lambda t: 0.0, twitch_l=lambda t: 0.0, turn_r=lambda t: 0.0, turn_l=lambda t: 0.0):
    """Both ears laid back by `back` degrees (0 = up), plus a separate twitch and a swivel (y) of each."""
    anim.rot("Ear_right", lambda t: (back(t) + twitch_r(t), turn_r(t), 0))
    anim.rot("Ear_left", lambda t: (back(t) + twitch_l(t), turn_l(t), 0))


def steps(rig, L, offsets, duty, speed, front, back):
    """Leg tracks of a gait whose feet do not slide. Each leg: on the ground for `duty` of the cycle, turning back at
    the speed of the ground (`speed` pixels per second under a pivot `reach` high), then the swing described by
    `front` / `back`: (follow-through after lift-off, reach before touch-down, fold of the paw, in degrees, and the
    lift of the whole leg in pixels: the shoulder blade or the hip rising, a leg of one bone cannot shorten). The leg
    goes on back a little after lift-off, folds, reaches forward further than where it lands and comes back to meet
    the ground (a real leg is already moving backwards when it touches). Returns (swing, knee, extra)."""
    swing, knee, extra = {}, {}, {}
    for leg, off in offsets.items():
        through, ahead, fold, lift = front if leg.startswith("front") else back
        half = math.degrees(math.atan(speed * L * duty / 2 / rig.reach(leg)))
        air = 1 - duty
        angle = cycle((0.0, half, "ease"), (duty, -half, "lin"),
                      (duty + air * 0.22, -half - through, "ease"),
                      (duty + air * 0.80, half + ahead, "ease"))
        bend = cycle((0.0, 0.0, "ease"), (duty * 0.85, 4.0, "ease"),
                     (duty + air * 0.42, fold, "ease"),
                     (duty + air * 0.86, fold * 0.18, "ease"))
        swing[leg] = lambda t, f=angle, off=off: (f(t / L + off), 0, 0)
        knee[leg] = lambda t, f=bend, off=off: (f(t / L + off), 0, 0)
        rise = cycle((0.0, 0.0, "ease"), (duty, 0.0, "lin"), (duty + air * 0.45, lift, "ease"))
        extra[leg] = lambda t, f=rise, off=off: (0, f(t / L + off), 0)
    return swing, knee, extra


# =========================================================================== animations
def animations(rig):
    out = []

    # ------------------------------------------------------------------ idle: 4 s loop, a fox on watch
    # breathes, shifts its weight, looks to one side then snaps to the other (a fox moves its head in jumps, never in
    # a slow sweep), sniffs twice, each ear swivels on its own towards a sound, the tail sways and its tip flicks
    a = Anim("idle", 80, loop=True)
    breath = lambda t: S(TAU * t / 2)                                                          # noqa: E731
    a.pos("Body", lambda t: (0.35 * S(TAU * t / 4), 0.22 * breath(t), 0))
    a.rot("Body", lambda t: (0, 0, 0.7 * S(TAU * t / 4)))
    a.scale("Body", lambda t: (1 + 0.012 * breath(t), 1 + 0.010 * breath(t), 1))
    look = tk((0, 0), (10, 0), (14, 13), (34, 12), (38, -11), (58, -12), (62, -2), (70, 0), (80, 0))
    nod = tk((0, -3), (10, -3), (14, -1), (34, -1), (38, -4), (58, -4), (62, -7), (65, -4), (68, -7), (72, -3), (80, -3))
    tilt = tk((0, 0), (14, 0), (18, 5), (32, 5), (38, 0), (80, 0))
    a.rot("Neck", lambda t: (-1 + 0.6 * breath(t), look(t) * 0.35, 0))
    a.rot("h_Head", lambda t: (nod(t), look(t) * 0.65, tilt(t)))
    ears(a, lambda t: 2 + 1.5 * breath(t),
         twitch_r=lambda t: 20 * bump(1.15, 1.40, t) + 12 * bump(3.05, 3.25, t),
         twitch_l=lambda t: 18 * bump(0.30, 0.52, t) + 22 * bump(2.45, 2.70, t),
         turn_r=tk((0, 0), (20, 0), (24, -24), (36, -24), (40, 0), (80, 0)),
         turn_l=tk((0, 0), (46, 0), (50, 26), (60, 26), (64, 0), (80, 0)))
    tail(a, lambda t: -4 + 3 * S(TAU * t / 4 + 1), lambda t: 13 * S(TAU * t / 4), lag=0.22, wrap=4.0)
    a.rot("Tail_tip", lambda t: ((-4 + 3 * S(TAU * (t - 0.44) / 4 + 1)) * 0.22 - 10 * bump(2.0, 2.3, t),
                                 13 * S(TAU * (t - 0.44) / 4) * 0.22 + 14 * bump(2.0, 2.3, t) - 12 * bump(2.3, 2.6, t), 0))
    planted(a, rig)
    out.append(a)

    # ------------------------------------------------------------------ walk: the fox trot, 12-tick cycle (0.6 s)
    L = 0.6
    a = Anim("walk", 12, loop=True)
    trot = {"front_right": 0.0, "back_left": 0.0, "front_left": 0.5, "back_right": 0.5}
    # mid stance of a pair at phases 0.25 and 0.75: the body is lowest there and highest when the pairs change
    a.pos("Body", lambda t: (0.35 * S(TAU * t / L), -0.55 - 0.45 * C(TAU * 2 * t / L), 0))
    a.rot("Body", lambda t: (0.9 * S(TAU * 2 * t / L + 0.6), 2.2 * S(TAU * t / L), 1.3 * S(TAU * t / L + 0.4)))
    # the neck and the head give the sway back: the nose keeps its line
    a.rot("Neck", lambda t: (-5 - 0.6 * S(TAU * 2 * t / L + 0.6), -1.2 * S(TAU * t / L), 0))
    a.rot("h_Head", lambda t: (-2 - 0.5 * S(TAU * 2 * t / L + 0.6), -1.0 * S(TAU * t / L), -1.3 * S(TAU * t / L + 0.4)))
    a.pos("h_Head", lambda t: (0, 0, -0.5))
    ears(a, lambda t: 8 + 3 * S(TAU * 2 * t / L + 1.2))
    # the tail hangs level behind and trails the hips: sway opposite to the spine, a soft bounce per step
    tail(a, lambda t: 14 + 3 * S(TAU * 2 * t / L + 2.0), lambda t: -9 * S(TAU * t / L), lag=0.07, wrap=L)
    swing, knee, lift = steps(rig, L, trot, 0.5, WALK_SPEED, front=(8, 6, -62, 2.6), back=(7, 5, -34, 1.2))
    planted(a, rig, swing, knee, lift)
    out.append(a)

    # ------------------------------------------------------------------ run: the gallop, 8-tick cycle (0.4 s)
    # phases: hind legs on the ground 0.00-0.42 (left then right), stretched flight around 0.46, front legs
    # 0.50-0.92 (right then left), gathered flight around 0.96
    L = 0.4
    a = Anim("run", 8, loop=True)
    gallop = {"back_left": 0.0, "back_right": 0.10, "front_right": 0.50, "front_left": 0.60}
    height = cycle((0.06, -2.6, "ease"), (0.30, -1.5, "ease"), (0.46, 0.5, "ease"), (0.58, -2.2, "ease"),
                   (0.80, -1.8, "ease"), (0.96, -0.6, "ease"))
    # hind push: the chest rises; front landing: the croup rises
    pitch = cycle((0.02, -1.0, "ease"), (0.26, 7.0, "ease"), (0.50, 1.5, "ease"), (0.74, -7.5, "ease"))
    reach = cycle((0.0, 0.0, "ease"), (0.40, -1.6, "ease"), (0.70, 0.8, "ease"))
    a.pos("Body", lambda t: (0, height(t / L), reach(t / L)))
    a.rot("Body", lambda t: (pitch(t / L), 0, 1.0 * S(TAU * t / L)))
    a.rot("Neck", lambda t: (-9 - pitch(t / L) * 0.55, 0, 0))
    a.rot("h_Head", lambda t: (-3 - pitch(t / L) * 0.40, 0, -1.0 * S(TAU * t / L)))
    a.pos("h_Head", lambda t: (0, -0.4, -1.2 + 0.5 * S(TAU * (t / L - 0.2))))
    flat = lambda t: 34 + 5 * S(TAU * 2 * t / L)                                             # noqa: E731
    ears(a, flat, turn_r=lambda t: 8, turn_l=lambda t: -8)
    # streaming behind, nearly level: it follows the croup late, a wave running down to the tip
    tail(a, lambda t: 40 + 9 * S(TAU * (t / L - 0.30)), lambda t: 5 * S(TAU * t / L), lag=0.05, share=(0.42, 0.36, 0.22),
         wrap=L)
    swing, knee, lift = steps(rig, L, gallop, 0.32, RUN_SPEED, front=(26, 30, -80, 4.0), back=(30, 26, -56, 2.0))
    planted(a, rig, swing, knee, lift)
    out.append(a)

    # ------------------------------------------------------------------ attack: the basic bite (14 ticks, bite at 3)
    # coils (0-1), snaps forward with the jaws open (2), they close on the prey (3), then shakes its catch from side
    # to side as a fox does (4-9) and comes back
    a = Anim("attack", 14)
    a.pos("Body", tk((0, (0, 0, 0)), (1, (0, -0.6, 0.9)), (3, (0, -1.0, -2.2)), (6, (0, -0.8, -1.6)), (14, (0, 0, 0))))
    a.rot("Body", tk((0, (0, 0, 0)), (1, (2, 0, 0)), (3, (-5, 0, 0)), (14, (0, 0, 0))))
    a.rot("Neck", tk((0, (0, 0, 0)), (1, (5, 0, 0)), (3, (-10, 0, 0)), (6, (-7, 0, 0)), (14, (0, 0, 0))))
    a.rot("h_Head", tk((0, (0, 0, 0)), (1, (6, 0, 0)), (2, (-8, 0, 0)), (3, (-9, 0, 0)), (5, (-6, 9, -10)),
                       (7, (-6, -9, 10)), (9, (-5, 5, -5)), (11, (-2, 0, 0)), (14, (0, 0, 0))))
    a.pos("h_Head", tk((0, (0, 0, 0)), (1, (0, 0, 0.6)), (3, (0, 0, -1.6)), (9, (0, 0, -0.6)), (14, (0, 0, 0))))
    a.rot("Jaw", tk((0, (0, 0, 0)), (1, (-10, 0, 0)), (2, (-36, 0, 0)), (3, (0, 0, 0)), (9, (-3, 0, 0)), (11, (0, 0, 0))))
    ears(a, tk((0, 6), (2, 32), (9, 26), (14, 4)))
    tail(a, tk((0, 0), (1, 8), (3, -14), (8, -6), (14, 0)), tk((0, 0), (5, -10), (7, 10), (9, -5), (14, 0)))
    planted(a, rig, {"back_right": tk((0, (0, 0, 0)), (3, (-5, 0, 0)), (14, (0, 0, 0))),
                     "back_left": tk((0, (0, 0, 0)), (3, (-5, 0, 0)), (14, (0, 0, 0)))})
    out.append(a)

    # ------------------------------------------------------------------ hurt (8 ticks)
    # flinches away from the blow: hindquarters drop, head pulled back and aside, ears flat, tail tucked, a front paw
    # leaves the ground
    a = Anim("hurt", 8)
    a.pos("Body", tk((0, (0, 0, 0)), (2, (0.5, -1.0, 1.2)), (8, (0, 0, 0))))
    a.rot("Body", tk((0, (0, 0, 0)), (2, (4, 0, -3)), (8, (0, 0, 0))))
    a.rot("Neck", tk((0, (0, 0, 0)), (2, (6, -6, 0)), (8, (0, 0, 0))))
    a.rot("h_Head", tk((0, (0, 0, 0)), (2, (6, -10, -8)), (5, (2, -4, -3)), (8, (0, 0, 0))))
    a.rot("Jaw", tk((0, (0, 0, 0)), (2, (-14, 0, 0)), (5, (0, 0, 0))))
    ears(a, tk((0, 0), (2, 40), (6, 24), (8, 0)))
    tail(a, tk((0, 0), (2, 24), (8, 0)), tk((0, 0), (2, 8), (8, 0)))
    planted(a, rig, {"front_left": tk((0, (0, 0, 0)), (2, (16, 0, 0)), (5, (6, 0, 0)), (8, (0, 0, 0)))},
            {"front_left": tk((0, (0, 0, 0)), (2, (-40, 0, 0)), (6, (0, 0, 0)))})
    out.append(a)

    # ------------------------------------------------------------------ awaken: the entrance (85 ticks)
    # 0-30 under the ground (the leaves gather above it), 30-50 it rises out of the soil front first, 50-58 sets its
    # four feet, 58-68 shakes the earth off its coat, 70-85 turns its head to the players, growls (76), takes its guard
    a = Anim("awaken", 85)
    off = lambda t: shake(t, 1.0, 9) * bump(2.9, 3.4, t)                                      # noqa: E731
    a.pos("Body", tk((0, (0, -50, 3)), (30, (0, -50, 3)), (44, (0, -4, 0)), (50, (0, 0.6, 0)), (56, (0, -1.4, 0)),
                     (62, (0, 0, 0)), (78, (0, 0, 0)), (85, (0, -1.0, 0))))
    a.rot("Body", lambda t: add(tk((0, (26, 0, 0)), (30, (26, 0, 0)), (44, (12, 0, 0)), (52, (-3, 0, 0)), (60, (0, 0, 0)),
                                   (78, (0, 0, 0)), (85, (-2, 0, 0)))(t), (0, 0, 7 * off(t))))
    a.rot("Neck", tk((0, (-10, 0, 0)), (40, (-4, 0, 0)), (56, (-8, 0, 0)), (70, (-8, 0, 0)), (85, (-4, 0, 0))))
    a.rot("h_Head", lambda t: add(tk((0, (12, 0, 0)), (40, (8, 0, 0)), (56, (-8, 22, 0)), (70, (-6, 24, 0)),
                                     (74, (-4, 0, 0)), (80, (-4, 0, 0)), (85, (-6, 0, 0)))(t), (0, 0, -12 * off(t))))
    a.rot("Jaw", tk((0, (0, 0, 0)), (74, (0, 0, 0)), (77, (-16, 0, 0)), (82, (-7, 0, 0)), (85, (0, 0, 0))))
    ears(a, tk((0, 36), (44, 30), (54, -4), (70, -2), (76, 28), (85, 18)),
         twitch_r=lambda t: 10 * off(t), twitch_l=lambda t: -10 * off(t))
    tail(a, tk((0, 30), (44, 20), (56, 4), (70, -10), (85, -14)),
         lambda t: tk((0, 0), (60, 0), (70, 10), (78, -6), (85, 0))(t) + 16 * off(t))
    # legs: while rising the front legs reach for the edge, then all four are set down one after the other
    swing = {
        "front_right": tk((0, (40, 0, 0)), (36, (40, 0, 0)), (44, (12, 0, 0)), (52, (0, 0, 0))),
        "front_left": tk((0, (46, 0, 0)), (38, (46, 0, 0)), (46, (14, 0, 0)), (54, (0, 0, 0))),
        "back_right": tk((0, (-20, 0, 0)), (44, (-20, 0, 0)), (54, (0, 0, 0))),
        "back_left": tk((0, (-24, 0, 0)), (46, (-24, 0, 0)), (56, (0, 0, 0))),
    }
    knee = {
        "front_right": tk((0, (-40, 0, 0)), (40, (-40, 0, 0)), (50, (0, 0, 0))),
        "front_left": tk((0, (-46, 0, 0)), (42, (-46, 0, 0)), (52, (0, 0, 0))),
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
    a.pos("Body", tk((0, (0, 0, 0)), (8, (0, -1.6, 0)), (13, (-0.8, -1.2, 1.3)), (17, (0.4, -1.6, -1.8)),
                     (20, (0.8, -1.3, -1.3)), (22, (-0.6, -1.7, -2.2)), (30, (0, 0, 0))))
    a.rot("Body", tk((0, (0, 0, 0)), (8, (-3, 0, 0)), (13, (7, 0, 5)), (17, (-6, 5, -3)), (19, (5, -2, -4)),
                     (22, (-7, -6, 4)), (30, (0, 0, 0))))
    a.rot("Neck", tk((0, (0, 0, 0)), (8, (-6, 0, 0)), (13, (-4, 0, 0)), (17, (-8, 0, 0)), (22, (-9, 0, 0)),
                     (30, (0, 0, 0))))
    a.rot("h_Head", tk((0, (0, 0, 0)), (8, (-6, 0, 0)), (13, (-2, -4, 0)), (17, (-6, 12, 0)), (22, (-7, -14, 0)),
                       (30, (0, 0, 0))))
    a.rot("Jaw", tk((0, (0, 0, 0)), (14, (0, 0, 0)), (17, (-14, 0, 0)), (19, (-5, 0, 0)), (22, (-16, 0, 0)),
                    (26, (0, 0, 0))))
    ears(a, tk((0, 0), (6, 30), (24, 30), (30, 4)))
    tail(a, tk((0, 0), (8, 8), (13, -6), (22, -4), (30, 0)), tk((0, 0), (13, -16), (17, 8), (19, 18), (22, -22), (30, 0)))
    swing = {
        "front_right": tk((0, (0, 0, 0)), (8, (10, 0, 0)), (13, (82, 0, -16)), (15, (48, 0, 2)), (17, (-8, 0, 22)),
                          (19, (-2, 0, 6)), (30, (0, 0, 0))),
        "front_left": tk((0, (0, 0, 0)), (8, (10, 0, 0)), (17, (6, 0, 0)), (19, (86, 0, 18)), (20, (52, 0, 0)),
                         (22, (-8, 0, -28)), (24, (-2, 0, -8)), (30, (0, 0, 0))),
        "back_right": tk((0, (0, 0, 0)), (8, (-6, 0, 0)), (30, (0, 0, 0))),
        "back_left": tk((0, (0, 0, 0)), (8, (-6, 0, 0)), (30, (0, 0, 0))),
    }
    # the wrist folds while the paw is raised and opens flat at the impact
    knee = {
        "front_right": tk((0, (0, 0, 0)), (8, (-16, 0, 0)), (13, (-60, 0, 0)), (16, (-20, 0, 0)), (17, (10, 0, 0)),
                          (22, (0, 0, 0))),
        "front_left": tk((0, (0, 0, 0)), (8, (-16, 0, 0)), (17, (-10, 0, 0)), (19, (-62, 0, 0)), (21, (-20, 0, 0)),
                         (22, (12, 0, 0)), (27, (0, 0, 0))),
    }
    planted(a, rig, swing, knee)
    out.append(a)

    # ------------------------------------------------------------------ seed: Graine prédatrice (33 ticks)
    # 0-8 still, ears up, tail rising, the mouth opens a little; 8-18 the seed forms before its snout (jaw ajar,
    # small tremor); 18-21 dry snap of the head, mouth wide open, the seed leaves at 20; 21-33 recover
    a = Anim("seed", 33)
    a.pos("Body", tk((0, (0, 0, 0)), (8, (0, -0.4, 0.4)), (18, (0, -0.2, 0.9)), (20, (0, -0.9, -1.5)), (33, (0, 0, 0))))
    a.rot("Body", tk((0, (0, 0, 0)), (18, (2, 0, 0)), (20, (-4, 0, 0)), (33, (0, 0, 0))))
    a.rot("Neck", tk((0, (0, 0, 0)), (8, (4, 0, 0)), (18, (6, 0, 0)), (20, (-8, 0, 0)), (33, (0, 0, 0))))
    a.rot("h_Head", lambda t: add(tk((0, (0, 0, 0)), (8, (6, 0, 0)), (18, (8, 0, 0)), (20, (-12, 0, 0)), (24, (-6, 0, 0)),
                                     (33, (0, 0, 0)))(t), (shake(t, 0.8) * bump(0.4, 0.9, t), 0, 0)))
    a.pos("h_Head", tk((0, (0, 0, 0)), (18, (0, 0, 0.4)), (20, (0, 0, -1.4)), (26, (0, 0, 0))))
    a.rot("Jaw", tk((0, (0, 0, 0)), (8, (-9, 0, 0)), (18, (-12, 0, 0)), (19, (-38, 0, 0)), (21, (-5, 0, 0)), (25, (0, 0, 0))))
    ears(a, tk((0, 4), (6, -10), (18, -10), (20, 16), (33, 4)))
    tail(a, tk((0, 0), (8, -8), (18, -14), (20, 4), (23, -8), (33, 0)), tk((0, 0), (20, 0), (22, -10), (33, 0)))
    planted(a, rig)
    out.append(a)

    # ------------------------------------------------------------------ roots: Étreinte des racines (54 ticks)
    # 0-8 anchoring (lower, front paws closer), 8-12 both front paws rise and strike the ground together (12),
    # 12-24 pressed down while the wave runs, 24-44 holds and watches the traps (they close at 36), 44-54 recover
    a = Anim("roots", 54)
    a.pos("Body", lambda t: add(tk((0, (0, 0, 0)), (8, (0, -1.8, 0.4)), (10, (0, -0.6, 0.8)), (12, (0, -3.0, -0.4)),
                                   (24, (0, -2.6, -0.2)), (44, (0, -1.8, 0)), (54, (0, 0, 0)))(t),
                                (0, shake(t, 0.15) * bump(0.6, 1.2, t), 0)))
    a.rot("Body", tk((0, (0, 0, 0)), (8, (-4, 0, 0)), (10, (10, 0, 0)), (12, (-7, 0, 0)), (24, (-6, 0, 0)), (34, (-4, 0, 0)),
                     (36, (-7, 0, 0)), (44, (-3, 0, 0)), (54, (0, 0, 0))))
    a.rot("Neck", tk((0, (0, 0, 0)), (8, (-8, 0, 0)), (10, (-2, 0, 0)), (12, (-12, 0, 0)), (24, (-8, 0, 0)), (30, (-2, 0, 0)),
                     (34, (-2, 0, 0)), (36, (-10, 0, 0)), (44, (-4, 0, 0)), (54, (0, 0, 0))))
    a.rot("h_Head", tk((0, (0, 0, 0)), (8, (-10, 0, 0)), (12, (-14, 0, 0)), (24, (-6, 0, 0)), (27, (0, 14, 0)),
                       (31, (0, -14, 0)), (34, (0, 0, 0)), (36, (-8, 0, 0)), (44, (-2, 0, 0)), (54, (0, 0, 0))))
    a.rot("Jaw", tk((0, (0, 0, 0)), (10, (0, 0, 0)), (12, (-14, 0, 0)), (16, (0, 0, 0)), (34, (0, 0, 0)), (36, (-12, 0, 0)),
                    (40, (0, 0, 0))))
    ears(a, tk((0, 4), (8, 26), (24, 26), (27, 0), (34, 0), (36, 26), (44, 20), (54, 4)))
    tail(a, tk((0, 0), (8, 8), (10, -16), (12, 10), (16, -8), (44, -6), (54, 0)),
         lambda t: 10 * S(TAU * t / 1.2) * bump(1.2, 2.2, t))
    swing = {
        "front_right": tk((0, (0, 0, 0)), (8, (6, 0, -6)), (10, (70, 0, -6)), (12, (-4, 0, -6)), (44, (-2, 0, -4)),
                          (54, (0, 0, 0))),
        "front_left": tk((0, (0, 0, 0)), (8, (6, 0, 6)), (10, (70, 0, 6)), (12, (-4, 0, 6)), (44, (-2, 0, 4)),
                         (54, (0, 0, 0))),
    }
    knee = {
        "front_right": tk((0, (0, 0, 0)), (8, (-8, 0, 0)), (10, (-56, 0, 0)), (12, (4, 0, 0)), (44, (0, 0, 0))),
        "front_left": tk((0, (0, 0, 0)), (8, (-8, 0, 0)), (10, (-56, 0, 0)), (12, (4, 0, 0)), (44, (0, 0, 0))),
    }
    planted(a, rig, swing, knee)
    out.append(a)

    # ------------------------------------------------------------------ realm: Royaume de la sylve (58 ticks)
    # 0-10 sudden stop, head down, tail down, legs set wide; 10-35 the head rises slowly, the tail spreads, the coat
    # shivers (eyes lit at 18); 35-43 chest up (as far as its planted front legs allow), muzzle to the sky, mouth wide open, the roar (36); 43-58 the wave runs
    # out, it lowers into its guard (the arena stays its realm for 100 ticks, then realm_end)
    a = Anim("realm", 58)
    quiver = lambda t: smooth(0.5, 1.6, t) * (1 - smooth(1.7, 1.8, t))                        # noqa: E731
    a.pos("Body", lambda t: add(tk((0, (0, 0, 0)), (10, (0, -1.4, 0)), (34, (0, -0.6, 0.4)), (38, (0, 0.4, 1.4)),
                                   (46, (0, 0.3, 1.2)), (58, (0, -0.8, 0)))(t), (0, shake(t, 0.18) * quiver(t), 0)))
    a.rot("Body", lambda t: add(tk((0, (0, 0, 0)), (10, (-3, 0, 0)), (34, (2, 0, 0)), (38, (8, 0, 0)), (46, (7, 0, 0)),
                                   (58, (-1, 0, 0)))(t), (0, 0, shake(t, 0.9, 13) * quiver(t))))
    a.scale("Body", lambda t: (1 + 0.012 * S(TAU * 7 * t) * bump(0.5, 1.75, t), 1 + 0.012 * S(TAU * 7 * t + 1) * bump(0.5, 1.75, t), 1))
    a.rot("Neck", tk((0, (0, 0, 0)), (10, (-10, 0, 0)), (34, (2, 0, 0)), (38, (12, 0, 0)), (46, (10, 0, 0)), (58, (-4, 0, 0))))
    a.rot("h_Head", lambda t: add(tk((0, (0, 0, 0)), (10, (-12, 0, 0)), (34, (6, 0, 0)), (38, (22, 0, 0)), (46, (20, 0, 0)),
                                     (58, (-4, 0, 0)))(t), (0, 0, shake(t, 1.2, 15) * bump(1.8, 2.3, t))))
    a.rot("Jaw", tk((0, (0, 0, 0)), (34, (-4, 0, 0)), (37, (-40, 0, 0)), (44, (-38, 0, 0)), (48, (0, 0, 0))))
    ears(a, tk((0, 4), (10, 10), (30, -8), (36, 38), (46, 38), (58, 20)),
         twitch_r=lambda t: shake(t, 3) * bump(0.5, 1.7, t), twitch_l=lambda t: shake(t + 0.3, 3) * bump(0.5, 1.7, t))
    tail(a, tk((0, 0), (10, 18), (34, -16), (38, -24), (46, -22), (58, -8)),
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
    # the head drops, it shakes itself from the nose to the tail and stands at ease again
    a = Anim("realm_end", 20)
    wave = lambda t: shake(t, 1.0, 8) * bump(0.4, 0.9, t)                                     # noqa: E731
    a.pos("Body", tk((0, (0, -0.8, 0)), (20, (0, 0, 0))))
    a.rot("Body", lambda t: (0, 0, 5 * wave(t - 0.05)))
    a.rot("Neck", tk((0, (-4, 0, 0)), (8, (-10, 0, 0)), (20, (0, 0, 0))))
    a.rot("h_Head", lambda t: add(tk((0, (-4, 0, 0)), (8, (-12, 0, 0)), (20, (0, 0, 0)))(t), (0, 0, 12 * wave(t))))
    ears(a, tk((0, 20), (12, 10), (20, 0)), twitch_r=lambda t: 8 * wave(t), twitch_l=lambda t: -8 * wave(t))
    tail(a, tk((0, -8), (20, 0)), lambda t: 14 * wave(t - 0.1))
    planted(a, rig, {"front_right": tk((0, (0, 0, 6)), (20, (0, 0, 0))), "front_left": tk((0, (0, 0, -6)), (20, (0, 0, 0))),
                     "back_right": tk((0, (0, 0, 5)), (20, (0, 0, 0))), "back_left": tk((0, (0, 0, -5)), (20, (0, 0, 0)))})
    out.append(a)
    return out


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
    """Per animation and tick: lowest point (blocks), lowest point of the body, lowest point of each foot."""
    res = {}
    for a in anims:
        rows = []
        for tick in range(int(round(a["length"] * 20)) + 1):
            _, pts = pose(model, a["_values"], tick, hidden=("Eyes_lit_empty",))
            feet = {b[4:]: round(min(p[1] for p in ps) / 16, 2) for b, ps in pts.items() if b.startswith("Paw_")}
            body = [p[1] for b, ps in pts.items() if not b.startswith(("Leg_", "Paw_")) for p in ps]
            rows.append({"tick": tick, "low": round(min(p[1] for ps in pts.values() for p in ps) / 16, 2),
                         "body_low": round(min(body) / 16, 2), "feet": feet})
        res[a["name"]] = rows
    return res


def strips(model, anims, atlases, sizes, folder, count=10, only=None):
    from taupe_champs_anim import strips as s
    s(model, anims, atlases, sizes, folder, count=count, only=only)


def finish(anims):
    for a in anims:
        a.pop("_values", None)
    return anims

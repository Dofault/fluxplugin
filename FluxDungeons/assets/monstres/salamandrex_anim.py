#!/usr/bin/env python3
"""Animations of the Salamandrex, the emerald salamander (2026-11-11): sleep and awakening (cinematic), idle and its
variations, locomotion (run, strafes, retreat, hops), melee bite, hurt, and the four attacks (Rush Emeraude, Feinte du
Predateur, Eclat Venimeux, Furie du Predateur) with the phase change.

Every timing that matters to the game (lock, launch, impact, throw, vulnerable windows) is in EVENTS, written in
docs/technique/boss/salamandrex.md and read by FluxDungeons (mob/SalamanderPowers.java, spells/miner/*.yml): keep
the three in step. Ticks are 1/20 s.

Values as in hamster_moissonneur_anim.py: rotation in degrees in the model's axes (x > 0 lifts the front of a bone:
the head looks up, the jaw closes; on a tail bone the tip goes down; y > 0 turns the front of a bone to the
monster's left, which swings a sprawled right leg forward and a tail tip to its right; z > 0 lifts its right side),
position in pixels (16 = 1 block). Converted to the Blockbench / BetterModel convention by build().

Legs are children of Root, sprawled sideways like a real salamander's: a leg swings forward and back around the
vertical axis at its shoulder or hip, lifts by turning around the length axis, and flexes (elbow or knee up, lower
segment back towards the ground). After the tracks are built, `settle()` puts every planted leg exactly on the ground
(the whole chain: shoulder, elbow, foot) and keeps the body above it.

Locomotion animations are written for the speeds of SalamanderPowers: run 4.6 blocks/s (8-tick stride), strafe 3.6
blocks/s, retreat 3.2 blocks/s.

Usage: salamandrex_anim.py [--strips <folder>] [--only name,name]   (rewrites salamandrex.bbmodel next to it)
"""
import json
import math
import os
import sys
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import paths  # noqa: F401,E402
from hamster_moissonneur_anim import Anim, T, bump, keys, pose, smooth  # noqa: E402

TAU = 2 * math.pi
S, C = math.sin, math.cos
lin = "linear"
SIDES = (("right", 1), ("left", -1))
LEGS = {"front_right": ("Leg_front_right", "Forearm_right", 1, "front"), "front_left": ("Leg_front_left", "Forearm_left", -1, "front"),
        "back_right": ("Leg_back_right", "Shin_right", 1, "back"), "back_left": ("Leg_back_left", "Shin_left", -1, "back")}
TAILS = tuple(f"Tail_{i}" for i in range(1, 12))
# share of a whole tail bend taken by each segment (small at the thick base, more towards the tip)
TAIL_W = [0.45, 0.55, 0.65, 0.75, 0.85, 0.95, 1.0, 1.0, 1.0, 1.0, 1.0]
TAIL_W = [3 * w / sum(TAIL_W) for w in TAIL_W]      # a tail value bends the whole tail by 3 x that angle

# Key ticks read by the game (SalamanderPowers): keep in step with docs/technique/boss/salamandrex.md.
EVENTS = {
    "awaken": {"eyes_open": 46, "head_rises": 60, "growl": 95, "crouch": 106, "leap": 116, "land": 126, "turn": 132,
               "roar": 156, "end": 180},
    "rush": {"lock": 8, "launch": 10, "run_end": 26, "end": 34},
    "rush_skid": {"end": 24},
    "feint_right": {"crouch": 12, "leap": 16, "land": 26, "bite": 32, "back_hop": 36, "end": 44},
    "spit": {"jaw_open": 4, "charge": 8, "throw": 18, "end": 30},
    "fury": {"hop_1": 24, "land_1": 33, "hop_2": 36, "land_2": 45, "hop_3": 48, "land_3": 57, "lock": 64,
             "pounce": 70, "impact": 78, "end": 100},
    "frenzy": {"roar": 14, "burst": 18, "end": 40},
    "attack": {"bite": 6, "end": 12},
}
EVENTS["feint_left"] = EVENTS["feint_right"]


# =========================================================================== helpers
def wave(t, period, phase=0.0):
    return S(TAU * (t / period + phase))


def shake(t, amp, freq=13.0):
    """Deterministic tremor (the same every time)."""
    return amp * (S(TAU * freq * t) * 0.6 + S(TAU * freq * 1.7 * t + 1.3) * 0.4)


def lids(a, closed):
    """Eyelids: `closed(t)` from 0 (hidden in the orbit) to 1 (over the eye)."""
    import salamandrex as M
    for name, s in SIDES:
        n = M.lid_normal(s)
        a.pos(f"Lid_{name}", lambda t, n=n: tuple(v * M.LID_OUT * closed(t) for v in n))


def blink(at, length=0.3):
    """Closed-ness of one blink starting at `at` seconds."""
    return lambda t: bump(at, at + length, t) ** 0.5


def tail(a, bend_y=lambda t, k: 0.0, bend_x=lambda t, k: 0.0):
    """Tail tracks: per segment k (0 = base) a side swing (y) and a lift (x, > 0 = tip down)."""
    for k, bone in enumerate(TAILS):
        a.rot(bone, lambda t, k=k: (bend_x(t, k) * TAIL_W[k], bend_y(t, k) * TAIL_W[k], 0))


def tail_wave(amp, period, lag=0.07, base=0.0, phase=0.0):
    """Travelling side wave: each segment a little later than the previous one."""
    return lambda t, k: base + amp * S(TAU * (t / period - k * lag + phase))


class Legs:
    """Leg poses of an animation: for each leg, f(t) -> (swing, lift, flex) in degrees and planted(t) -> bool.
    swing > 0 = foot forward, lift > 0 = foot up, flex > 0 = joint bent (body lower, elbow or knee up)."""

    def __init__(self, a):
        self.a, self.f, self.planted = a, {}, {}

    def set(self, leg, f, planted=lambda t: True):
        self.f[leg], self.planted[leg] = f, planted

    def write(self):
        for leg, (upper, lower, s, kind) in LEGS.items():
            f = self.f.get(leg, lambda t: (0.0, 0.0, 0.0))
            self.a.rot(upper, lambda t, f=f, s=s: (0, s * f(t)[0], s * (f(t)[1] + f(t)[2])))
            k = 1.5 if kind == "front" else 1.3
            self.a.rot(lower, lambda t, f=f, s=s, k=k: (0, 0, -s * f(t)[2] * k))
        self.a.planted = {leg: self.planted.get(leg, lambda t: True) for leg in LEGS}


def stance(flex=0.0):
    return lambda t: (0.0, 0.0, flex)


def gait(period, swing, lift, duty=0.55, flex=0.0, reverse=False, offsets=None):
    """Diagonal trot: front right with back left, front left with back right. Planted while the foot goes back under
    the body (`duty` of the cycle), swung forward with a lift. `reverse`: the same steps backwards (retreat)."""
    offsets = offsets or {"front_right": 0.0, "back_left": 0.0, "front_left": 0.5, "back_right": 0.5}
    out = {}
    for leg, off in offsets.items():
        def phase(t, off=off):
            u = (t / period + off) % 1.0
            return 1.0 - u if reverse else u

        def f(t, phase=phase):
            u = phase(t)
            if u < duty:
                return (swing - 2 * swing * u / duty, 0.0, flex)
            v = (u - duty) / (1 - duty)
            return (-swing + 2 * swing * smooth(0, 1, v), lift * S(math.pi * v), flex + lift * 0.4 * S(math.pi * v))

        def planted(t, phase=phase):
            return phase(t) < duty - 0.02
        out[leg] = (f, planted)
    return out


def apply_gait(L, g):
    for leg, (f, p) in g.items():
        L.set(leg, f, p)


def blend_legs(g1, g2, k):
    """Leg poses going from g1 to g2 as k(t) goes 0 -> 1; planted when both say so (or the closer one)."""
    out = {}
    for leg in LEGS:
        f1, p1 = g1[leg]
        f2, p2 = g2[leg]

        def f(t, f1=f1, f2=f2):
            w = k(t)
            return tuple(a + (b - a) * w for a, b in zip(f1(t), f2(t)))

        def p(t, p1=p1, p2=p2):
            return p1(t) if k(t) < 0.5 else p2(t)
        out[leg] = (f, p)
    return out


def still(flex=0.0, f=None):
    f = f or stance(flex)
    return {leg: (f, lambda t: True) for leg in LEGS}


def airborne(t0, t1, tuck=18.0, flex0=0.0):
    """All legs off the ground between t0 and t1 (seconds), tucked up."""
    def f(t):
        u = bump(t0, t1, t)
        return (0.0, tuck * u, flex0 * (1 - u))

    def p(t):
        return not (t0 < t < t1)
    return {leg: (f, p) for leg in LEGS}


# =========================================================================== animations
def animations():
    A = []

    def new(name, ticks, loop=False):
        a = Anim(name, ticks, loop)
        A.append(a)
        return a

    # ---------------------------------------------------------------- sleep: curled low, eyes shut, slow breath
    SLEEP_BODY = -3.2

    def sleep_pose(a, k=lambda t: 1.0, breath=True):
        a.pos("Body", lambda t: (0, SLEEP_BODY * k(t) + (0.35 * wave(t, 4.0) if breath else 0) * k(t), 0))
        a.rot("Body", lambda t: (-1.5 * k(t), 4 * k(t), 0))
        a.rot("Head", lambda t: (-13 * k(t) + 1.2 * wave(t, 4.0, 0.2) * k(t), -10 * k(t), -6 * k(t)))
        tail(a, bend_y=lambda t, k_: 16 * k(t), bend_x=lambda t, k_: (8 if k_ < 3 else -2) * k(t))

    a = new("sleep", 80, loop=True)
    sleep_pose(a)
    lids(a, lambda t: 1.0)
    L = Legs(a)
    for leg in LEGS:
        L.set(leg, lambda t, leg=leg: (-12 if leg.startswith("front") else 10, 0.0, 14.0))
    L.write()

    # ---------------------------------------------------------------- awaken (cinematic, 180 ticks)
    EA = EVENTS["awaken"]
    a = new("awaken", EA["end"])
    t_open, t_rise, t_growl, t_crouch, t_leap, t_land, t_turn, t_roar = (T(EA[k]) for k in
                                                                         ("eyes_open", "head_rises", "growl", "crouch", "leap", "land", "turn", "roar"))
    up = lambda t: smooth(t_rise, T(100), t)                    # noqa: E731  curled -> standing
    crouch = lambda t: bump(t_crouch, T(EA["leap"] + 2), t) + bump(t_land, T(134), t) * 0.9   # noqa: E731
    air = lambda t: bump(t_leap, t_land, t)                     # noqa: E731

    def body_y(t):
        y = SLEEP_BODY * (1 - up(t)) + 0.35 * wave(t, 4.0) * (1 - up(t)) - 2.6 * crouch(t) + 5.0 * air(t)
        y += -1.2 * bump(T(140), T(180), t)                    # combat stance, a little lower
        return (0, y, 0)
    a.pos("Body", body_y)
    a.rot("Body", lambda t: (-1.5 * (1 - up(t)) - 4 * crouch(t) + 3 * air(t), 4 * (1 - up(t)),
                             -10 * air(t) + 4 * bump(t_land, T(134), t)))
    a.rot("Head", lambda t: (keys(t, [(0, -13), (T(50), -12), (t_rise, -11), (T(90), 6), (T(104), 2), (t_crouch, -6),
                                      (t_leap, 2), (t_land, -8), (T(134), 0), (T(144), -6), (T(150), -4), (t_roar, 9),
                                      (T(166), 8), (T(174), -5), (T(180), -5)])
                             + shake(t, 1.2) * bump(t_roar, T(166), t),
                             keys(t, [(0, -10), (T(50), -10), (T(56), 6), (T(66), 4), (T(90), 0), (t_turn, 0),
                                      (T(138), 8), (T(146), 0)]),
                             -6 * (1 - smooth(T(50), T(70), t)) + 5 * air(t)))
    a.rot("Jaw", lambda t: (-12 * bump(T(88), T(104), t) - 30 * bump(T(150), T(170), t)
                            - 4 * bump(T(96), T(100), t) * S(TAU * 6 * t), 0, 0))
    # eyes: shut, a first slit, then wide open on the player
    lids(a, lambda t: keys(t, [(0, 1), (T(40), 1), (T(44), 0.55), (T(48), 0.6), (t_open + T(6), 0.0), (T(150), 0.0),
                               (T(152), 0.35), (T(154), 0.0)]))
    tail(a, bend_y=lambda t, k: 16 * (1 - smooth(t_rise, T(96), t))
         + 22 * S(TAU * (t / 1.6 - k * 0.08)) * bump(T(70), T(104), t)
         - 34 * S(TAU * (t / 0.55 - k * 0.09)) * bump(t_leap, T(136), t)
         + 10 * S(TAU * (t / 0.7 - k * 0.08)) * bump(T(146), T(162), t),
         bend_x=lambda t, k: (8 if k < 3 else -2) * (1 - smooth(t_rise, T(96), t)) - 10 * smooth(T(70), T(90), t)
         * (1 - smooth(T(104), T(116), t)) - 8 * air(t))
    L = Legs(a)
    for leg, (upper, lower, s, kind) in LEGS.items():
        rest = (-12 if kind == "front" else 10, 0.0, 14.0)

        def f(t, rest=rest, kind=kind, leg=leg):
            k = up(t)
            sw = rest[0] * (1 - k) + (4 if kind == "front" else -4) * smooth(T(80), T(104), t) * (1 - smooth(T(140), T(160), t))
            fl = rest[2] * (1 - k) + 18 * crouch(t) + 8 * bump(T(140), T(180), t)
            li = 26 * air(t)
            sw += (-14 if kind == "front" else 14) * air(t)
            # one paw after the other settles under the body while it rises
            step = {"front_right": T(64), "back_left": T(72), "front_left": T(80), "back_right": T(88)}
            li += 9 * bump(step[leg], step[leg] + 0.35, t)
            return (sw, li, fl)
        L.set(leg, f, lambda t: not (T(EA["leap"]) < t < T(EA["land"])))
    L.write()

    # ---------------------------------------------------------------- idle: breath, head, tail always alive
    a = new("idle", 120, loop=True)
    a.pos("Body", lambda t: (0, 0.35 * wave(t, 3.0), 0))
    a.rot("Body", lambda t: (0.6 * wave(t, 3.0, 0.25), 1.5 * wave(t, 6.0), 0.8 * wave(t, 6.0, 0.3)))
    a.rot("Head", lambda t: (-2 + 1.5 * wave(t, 3.0, 0.1) + 1.0 * wave(t, 2.0, 0.4), 4 * wave(t, 6.0, 0.15) - 1.5 * wave(t, 2.0),
                             2.5 * wave(t, 6.0, 0.6)))
    a.rot("Jaw", lambda t: (-2 * bump(T(30), T(50), t), 0, 0))
    lids(a, blink(T(84), 0.3))
    tail(a, bend_y=tail_wave(9, 3.0, lag=0.06, base=0), bend_x=lambda t, k: 1.5 * wave(t, 3.0, k * 0.05))
    L = Legs(a)
    for leg, (upper, lower, s, kind) in LEGS.items():
        L.set(leg, lambda t, s=s: (0.0, 0.0, 1.2 + 1.2 * wave(t, 3.0, 0.25 * s)))
    L.write()

    # ---------------------------------------------------------------- idle variations (played in combat pauses)
    a = new("idle_snap", 16)
    a.rot("Head", lambda t: (keys(t, [(0, -2), (T(2), 4), (T(9), 3), (T(16), -2)]),
                             keys(t, [(0, 0), (T(2), 16), (T(9), 14), (T(16), 0)]), keys(t, [(0, 0), (T(2), -4), (T(16), 0)])))
    a.rot("Body", lambda t: (0, 3 * bump(0, T(14), t), 0))
    tail(a, bend_y=lambda t, k: -8 * bump(T(2), T(16), t))
    still_legs(a)

    a = new("idle_tail", 20)
    tail(a, bend_y=lambda t, k: 30 * S(TAU * (t / 0.5 - k * 0.1)) * bump(0, T(20), t), bend_x=lambda t, k: -4 * bump(0, T(14), t))
    a.rot("Body", lambda t: (0, -3 * bump(T(2), T(12), t), 0))
    still_legs(a)

    a = new("idle_jaw", 24)
    a.rot("Jaw", lambda t: (keys(t, [(0, 0), (T(6), -26), (T(16), -24), (T(20), 0)]), 0, 0))
    a.rot("Head", lambda t: (keys(t, [(0, -2), (T(6), 6), (T(16), 5), (T(22), -2)]), 0, 0))
    still_legs(a)

    a = new("idle_shift", 20)
    a.rot("Body", lambda t: (0, 0, -2 * bump(0, T(20), t)))
    L = Legs(a)
    L.set("front_right", lambda t: (6 * smooth(T(4), T(12), t) - 6 * smooth(T(14), T(20), t), 12 * bump(T(4), T(12), t), 0.0),
          lambda t: not (T(4) < t < T(12)))
    L.set("back_left", lambda t: (-4 * bump(T(8), T(18), t), 8 * bump(T(10), T(16), t), 0.0), lambda t: not (T(10) < t < T(16)))
    L.write()

    a = new("idle_crouch", 30)
    k = lambda t: bump(0, T(30), t) ** 0.7     # noqa: E731
    a.pos("Body", lambda t: (0, -2.4 * k(t), 0))
    a.rot("Body", lambda t: (-3 * k(t), 0, 0))
    a.rot("Head", lambda t: (-6 * k(t), 0, 0))
    lids(a, lambda t: 0.25 * k(t))
    tail(a, bend_y=lambda t, k_: 0, bend_x=lambda t, k_: -6 * k(t))
    apply_legs(a, still(f=lambda t: (0.0, 0.0, 14 * k(t))))

    # ---------------------------------------------------------------- locomotion
    def locomotion(name, ticks, period, swing, lift, flex, lean, roll, reverse=False, tail_amp=14, side=0):
        a = new(name, ticks, loop=True)
        a.pos("Body", lambda t: (side * 0.5 * S(TAU * t / (period / 2)), -1.2 + 0.5 * abs(S(TAU * t / period)), 0))
        a.rot("Body", lambda t: (lean, 6 * S(TAU * t / period), roll + 2 * S(TAU * t / period + 0.6)))
        # the head stays steady: it turns against the body
        a.rot("Head", lambda t: (-lean * 0.8 - 2, -5 * S(TAU * t / period), -roll * 0.6))
        tail(a, bend_y=lambda t, k: -tail_amp * S(TAU * (t / period - k * 0.09)) + side * -10,
             bend_x=lambda t, k: -2)
        apply_legs(a, gait(period, swing, lift, flex=flex, reverse=reverse))
        return a

    locomotion("run", 8, 0.4, 30, 26, 8, -4, 0)
    locomotion("strafe_right", 10, 0.5, 16, 24, 7, -2, -7, side=1, tail_amp=10)
    locomotion("strafe_left", 10, 0.5, 16, 24, 7, -2, 7, side=-1, tail_amp=10)
    locomotion("retreat", 12, 0.6, 20, 20, 9, 2, 0, reverse=True, tail_amp=10)

    # hops: crouch (0-3), air (3-9), landing absorbed (9-12)
    def hop(name, side, ticks=12):
        a = new(name, ticks)
        air = lambda t: bump(T(3), T(9), t)          # noqa: E731
        cr = lambda t: bump(0, T(4), t) + bump(T(8), T(12), t) * 0.8   # noqa: E731
        a.pos("Body", lambda t: (0, -2.2 * cr(t) + 3.5 * air(t), 0))
        a.rot("Body", lambda t: (-3 * cr(t) + 4 * air(t) * (1 if side == 0 else 0), 0, -side * 12 * air(t)))
        a.rot("Head", lambda t: (-3 * cr(t), side * -6 * air(t), side * 6 * air(t)))
        tail(a, bend_y=lambda t, k: -side * 26 * air(t) * (k + 1) / 11 + 10 * S(TAU * (t / 0.3 - k * 0.1)) * bump(T(8), T(12), t),
             bend_x=lambda t, k: -6 * air(t))
        g = airborne(T(3), T(9), tuck=20)
        L = {leg: (lambda t, f=f: (f(t)[0], f(t)[1], f(t)[2] + 16 * cr(t)), p) for leg, (f, p) in g.items()}
        apply_legs(a, L)
        return a

    hop("hop_right", 1)
    hop("hop_left", -1)
    hop("leap", 0)

    # ---------------------------------------------------------------- melee bite
    EB = EVENTS["attack"]
    a = new("attack", EB["end"])
    bt = T(EB["bite"])
    a.rot("Head", lambda t: (keys(t, [(0, 0), (T(3), 6), (bt, -10), (T(12), 0)]), 0, 0))
    a.pos("Head", lambda t: (0, 0, keys(t, [(0, 0), (T(3), 1), (bt, -3.5), (T(12), 0)])))
    a.rot("Jaw", lambda t: (keys(t, [(0, 0), (T(4), -32), (bt, 0), (T(12), 0)]), 0, 0))
    a.rot("Body", lambda t: (-3 * bump(0, T(12), t), 0, 0))
    a.pos("Body", lambda t: (0, -1.2 * bump(0, T(12), t), -1.5 * bump(T(2), T(10), t)))
    tail(a, bend_y=lambda t, k: 8 * S(TAU * (t / 0.4 - k * 0.1)) * bump(0, T(12), t))
    still_legs(a, flex=lambda t: 6 * bump(0, T(12), t))

    a = new("hurt", 8)
    a.rot("Body", lambda t: (2 * bump(0, T(8), t), -4 * bump(0, T(8), t), 4 * bump(0, T(6), t)))
    a.rot("Head", lambda t: (6 * bump(0, T(8), t), 8 * bump(0, T(8), t), 0))
    a.rot("Jaw", lambda t: (-10 * bump(0, T(7), t), 0, 0))
    lids(a, lambda t: 0.6 * bump(0, T(6), t))
    tail(a, bend_y=lambda t, k: 12 * bump(0, T(8), t))
    still_legs(a)

    # ---------------------------------------------------------------- Rush Emeraude
    ER = EVENTS["rush"]
    a = new("rush", ER["end"])
    lk, ln, re = T(ER["lock"]), T(ER["launch"]), T(ER["run_end"])
    wind = lambda t: smooth(0, lk, t) * (1 - smooth(ln, T(12), t))    # noqa: E731
    run = lambda t: smooth(ln - 0.05, T(12), t) * (1 - smooth(re, T(29), t))   # noqa: E731
    brake = lambda t: bump(re - 0.05, T(ER["end"]), t)                # noqa: E731
    a.pos("Body", lambda t: (0, -2.8 * wind(t) - 1.5 * run(t) - 1.2 * brake(t) + 0.5 * abs(S(TAU * t / 0.3)) * run(t), 0))
    a.rot("Body", lambda t: (-5 * wind(t) - 5 * run(t) + 6 * brake(t), 5 * S(TAU * t / 0.3) * run(t) + 10 * brake(t),
                             -8 * brake(t)))
    a.rot("Head", lambda t: (-9 * wind(t) - 6 * run(t) + 6 * brake(t), -4 * S(TAU * t / 0.3) * run(t) - 8 * brake(t), 0))
    a.rot("Jaw", lambda t: (-14 * run(t), 0, 0))
    lids(a, lambda t: 0.3 * wind(t))
    tail(a, bend_y=lambda t, k: -18 * S(TAU * (t / 0.3 - k * 0.09)) * run(t) - 26 * brake(t) * (k + 2) / 12,
         bend_x=lambda t, k: -7 * wind(t) - 3 * run(t))
    g_run = gait(0.3, 34, 26, flex=10)
    g_crouch = still(f=lambda t: (0.0, 0.0, 22 * wind(t) + 14 * brake(t)))
    blended = blend_legs(g_crouch, g_run, run)
    apply_legs(a, {leg: (lambda t, f=f: (f(t)[0] + (18 if leg.startswith("front") else -6) * brake(t), f(t)[1], f(t)[2]), p)
                   for leg, (f, p) in blended.items()})

    # missed: skids, shakes its head, open to a counter-attack
    a = new("rush_skid", EVENTS["rush_skid"]["end"])
    a.pos("Body", lambda t: (0, -1.8 * bump(0, T(24), t), 0))
    a.rot("Body", lambda t: (3 * bump(0, T(10), t), 14 * bump(0, T(14), t), -6 * bump(0, T(12), t)))
    a.rot("Head", lambda t: (-4 * bump(0, T(24), t), shake(t, 9, 5) * bump(T(6), T(22), t), shake(t, 5, 4) * bump(T(6), T(22), t)))
    a.rot("Jaw", lambda t: (-10 * bump(T(4), T(20), t), 0, 0))
    lids(a, lambda t: 0.5 * bump(T(8), T(12), t) + 0.5 * bump(T(16), T(19), t))
    tail(a, bend_y=lambda t, k: 24 * bump(0, T(18), t) * (k + 2) / 12)
    still_legs(a, flex=lambda t: 12 * bump(0, T(24), t), swing=lambda t, leg: (14 if leg.startswith("front") else -8) * bump(0, T(18), t))

    # ---------------------------------------------------------------- Feinte du Predateur (right / left)
    for name, side in (("feint_right", 1), ("feint_left", -1)):
        EF = EVENTS[name]
        a = new(name, EF["end"])
        cr = lambda t: bump(T(10), T(17), t)                                    # noqa: E731
        air = lambda t: bump(T(EF["leap"]), T(EF["land"]), t)                     # noqa: E731
        land = lambda t: bump(T(EF["land"] - 1), T(31), t)                       # noqa: E731
        bite = lambda t: bump(T(29), T(35), t)                                  # noqa: E731
        back = lambda t: bump(T(EF["back_hop"]), T(EF["end"]), t)                # noqa: E731
        app = lambda t: 1 - smooth(T(10), T(13), t)                             # noqa: E731
        a.pos("Body", lambda t: (0, -1.2 * app(t) - 2.6 * cr(t) + 4.5 * air(t) - 2.2 * land(t) + 2.5 * back(t)
                                 + 0.5 * abs(S(TAU * t / 0.4)) * app(t), -1.5 * bite(t)))
        a.rot("Body", lambda t, side=side: (-4 * app(t) - 4 * cr(t) + 4 * air(t) - 3 * bite(t) + 5 * back(t),
                                            6 * S(TAU * t / 0.4) * app(t) - side * 18 * air(t), -side * 14 * air(t) + side * 5 * land(t)))
        a.rot("Head", lambda t, side=side: (-4 * app(t) - 4 * cr(t) + 4 * bite(t) - 8 * bump(T(31), T(36), t),
                                            side * 10 * air(t) - side * 8 * land(t), side * 6 * air(t)))
        a.pos("Head", lambda t: (0, 0, -3.5 * bump(T(30), T(35), t)))
        a.rot("Jaw", lambda t: (keys(t, [(0, 0), (T(28), 0), (T(31), -34), (T(EF["bite"]), 0), (T(44), 0)]), 0, 0))
        lids(a, lambda t: 0.3 * cr(t))
        tail(a, bend_y=lambda t, k, side=side: -14 * S(TAU * (t / 0.4 - k * 0.09)) * app(t)
             - side * 34 * air(t) * (k + 2) / 12 + side * 20 * land(t) * (k + 1) / 11,
             bend_x=lambda t, k: -6 * air(t) - 4 * back(t))
        run_l = gait(0.4, 30, 24, flex=8)
        crouch_l = still(f=lambda t: (0.0, 0.0, 18 * cr(t) + 16 * land(t) + 6 * bite(t)))
        legs = blend_legs(run_l, crouch_l, lambda t: smooth(T(9), T(12), t))
        air_l = airborne(T(EF["leap"]), T(EF["land"]), tuck=22)
        back_l = airborne(T(EF["back_hop"] + 1), T(EF["end"] - 2), tuck=14)
        final = {}
        for leg in LEGS:
            f0, p0 = legs[leg]
            fa, pa = air_l[leg]
            fb, pb = back_l[leg]
            final[leg] = (lambda t, f0=f0, fa=fa, fb=fb: tuple(x + y + z for x, y, z in zip(f0(t), fa(t), fb(t))),
                          lambda t, p0=p0, pa=pa, pb=pb: p0(t) and pa(t) and pb(t))
        apply_legs(a, final)

    # ---------------------------------------------------------------- Eclat Venimeux
    ES = EVENTS["spit"]
    a = new("spit", ES["end"])
    th = T(ES["throw"])
    a.rot("Head", lambda t: (keys(t, [(0, 0), (T(10), 15), (T(16), 17), (th, -8), (T(22), -6), (T(30), 0)]), 0,
                             shake(t, 1.0) * bump(T(8), th, t)))
    a.pos("Head", lambda t: (0, 0, keys(t, [(0, 0), (T(16), 1.2), (th, -2.5), (T(26), 0)])))
    a.rot("Jaw", lambda t: (keys(t, [(0, 0), (T(ES["jaw_open"]), -4), (T(14), -24), (T(17), -22), (th, -34), (T(22), -20),
                                     (T(28), 0)]) - 3 * S(TAU * t / 0.15) * bump(T(9), T(17), t), 0, 0))
    # throat pulse: the whole front of the body breathes in quickly while it charges
    a.pos("Body", lambda t: (0, 0.6 * S(TAU * t / 0.2) * bump(T(8), T(17), t) - 0.8 * bump(T(0), T(30), t), 0.8 * bump(T(16), T(22), t)))
    a.rot("Body", lambda t: (3 * bump(T(2), T(17), t) - 3 * bump(T(17), T(24), t), 0, 0))
    lids(a, lambda t: 0.2 * bump(T(8), T(17), t))
    tail(a, bend_y=lambda t, k: 6 * S(TAU * (t / 0.8 - k * 0.07)), bend_x=lambda t, k: 4 * bump(T(14), T(24), t))
    still_legs(a, flex=lambda t: 4 * bump(0, T(30), t), swing=lambda t, leg: (-6 if leg.startswith("front") else 0) * bump(T(16), T(26), t))

    # ---------------------------------------------------------------- Furie du Predateur (ultimate)
    EU = EVENTS["fury"]
    a = new("fury", EU["end"])
    acc = lambda t: smooth(0, T(20), t) * (1 - smooth(T(22), T(24), t))     # noqa: E731
    hops = [(T(EU[f"hop_{i}"]), T(EU[f"land_{i}"])) for i in (1, 2, 3)]

    def hop_air(t):
        return sum(bump(h0 + 0.1, l0, t) for h0, l0 in hops)

    def hop_cr(t):
        return sum(bump(h0 - 0.12, h0 + 0.12, t) + bump(l0 - 0.05, l0 + 0.15, t) * 0.8 for h0, l0 in hops)
    con = lambda t: bump(T(58), T(71), t) ** 0.8            # noqa: E731  final contraction
    pounce = lambda t: bump(T(EU["pounce"]), T(EU["impact"]), t)   # noqa: E731
    slam = lambda t: bump(T(EU["impact"] - 1), T(88), t)     # noqa: E731
    rec = lambda t: smooth(T(84), T(100), t)                # noqa: E731
    low = lambda t: smooth(T(78), T(80), t) * (1 - rec(t))  # noqa: E731
    a.pos("Body", lambda t: (0, -3.2 * acc(t) + shake(t, 0.25) * acc(t) - 2.0 * hop_cr(t) + 5.0 * hop_air(t) - 3.6 * con(t)
                             + 7.0 * pounce(t) - 3.8 * slam(t) - 1.6 * low(t) + 0.5 * S(TAU * t / 0.35) * low(t), 0))
    a.rot("Body", lambda t: (-4 * acc(t) + 6 * hop_air(t) - 6 * con(t) + 8 * pounce(t) - 6 * slam(t), 0,
                             shake(t, 1.0) * acc(t)))
    a.rot("Head", lambda t: (8 * acc(t) - 4 * hop_air(t) - 8 * con(t) - 6 * pounce(t) - 6 * slam(t) - 6 * low(t)
                             + 6 * bump(T(64), T(70), t), 0, 0))
    a.rot("Jaw", lambda t: (-36 * acc(t) - 12 * hop_air(t) - 30 * pounce(t) - 14 * slam(t) - 8 * low(t), 0, 0))
    lids(a, lambda t: 0.3 * con(t))
    tail(a, bend_y=lambda t, k: 8 * S(TAU * (t / 0.6 - k * 0.08)) * acc(t) + 22 * S(TAU * (t / 0.3 - k * 0.1)) * hop_air(t)
         + 6 * S(TAU * (t / 1.2 - k * 0.06)) * low(t),
         bend_x=lambda t, k: (-16 if k > 3 else -6) * acc(t) - 6 * hop_air(t) - 10 * pounce(t) + 6 * slam(t)
         + 4 * low(t) * (k + 1) / 11)
    leg_fury = {}
    for leg in LEGS:
        front = leg.startswith("front")

        def f(t, front=front):
            fl = 20 * acc(t) + 16 * hop_cr(t) + 26 * con(t) + 22 * slam(t) + 12 * low(t)
            sw = (-16 if front else 16) * pounce(t) + (12 if front else -8) * slam(t)
            li = 18 * hop_air(t) + 24 * pounce(t)
            return (sw, li, fl)

        def p(t):
            if hop_air(t) > 0.05 or pounce(t) > 0.05:
                return False
            return True
        leg_fury[leg] = (f, p)
    apply_legs(a, leg_fury)

    # ---------------------------------------------------------------- Furie emeraude (phase change at 40 %)
    EZ = EVENTS["frenzy"]
    a = new("frenzy", EZ["end"])
    dn = lambda t: smooth(0, T(10), t) * (1 - smooth(T(30), T(40), t))      # noqa: E731
    roar = lambda t: bump(T(EZ["roar"] - 2), T(32), t)                        # noqa: E731
    a.pos("Body", lambda t: (0, -2.6 * dn(t) + shake(t, 0.3) * roar(t), 0))
    a.rot("Body", lambda t: (-4 * dn(t) + 5 * roar(t), 0, shake(t, 1.5) * roar(t)))
    a.rot("Head", lambda t: (-8 * dn(t) + 20 * roar(t) + shake(t, 2) * roar(t), 0, 0))
    a.rot("Jaw", lambda t: (-38 * roar(t), 0, 0))
    lids(a, lambda t: 0.4 * bump(0, T(12), t))
    tail(a, bend_y=lambda t, k: 26 * S(TAU * (t / 0.35 - k * 0.1)) * roar(t), bend_x=lambda t, k: -12 * roar(t))
    still_legs(a, flex=lambda t: 18 * dn(t))
    return A


def still_legs(a, flex=None, swing=None):
    flex = flex or (lambda t: 0.0)
    swing = swing or (lambda t, leg: 0.0)
    apply_legs(a, {leg: (lambda t, leg=leg: (swing(t, leg), 0.0, flex(t)), lambda t: True) for leg in LEGS})


def apply_legs(a, g):
    L = Legs(a)
    for leg, (f, p) in g.items():
        L.set(leg, f, p)
    L.write()


# =========================================================================== settle and writing
def settle(model, a, values):
    """Planted legs: the whole chain (shoulder or hip, elbow or knee, foot) moved so that its lowest point is on the
    ground; lifted legs keep the correction blended from their planted neighbours. The body never goes under the
    ground (0.6 pixel above at least)."""
    n = a.ticks + 1
    chains = {leg: (upper, lower) for leg, (upper, lower, s, kind) in LEGS.items()}
    leg_bones = {b for c in chains.values() for b in c}
    corr = {leg: [None] * n for leg in LEGS}
    lift = [0.0] * n
    for tick in range(n):
        _, pts = pose(model, values, tick)
        low_body = min(p[1] for b, ps in pts.items() if b not in leg_bones for p in ps)
        if low_body < 0.6:
            lift[tick] = 0.6 - low_body
        t = T(tick)
        for leg, (upper, lower) in chains.items():
            if a.planted[leg](t):
                corr[leg][tick] = -min(p[1] for b in (upper, lower) for p in pts.get(b, []))
    body = values.setdefault("Body", {})
    body.setdefault("position", [(0, 0, 0)] * n)
    body["position"] = [(x, y + d, z) for (x, y, z), d in zip(body["position"], lift)]
    for leg, (upper, lower) in chains.items():
        cs = corr[leg]
        known = [i for i, v in enumerate(cs) if v is not None]
        filled = []
        for i in range(n):
            if cs[i] is not None:
                filled.append(cs[i])
                continue
            prev = [j for j in known if j < i]
            nxt = [j for j in known if j > i]
            if prev and nxt:
                j0, j1 = prev[-1], nxt[0]
                filled.append(cs[j0] + (cs[j1] - cs[j0]) * (i - j0) / (j1 - j0))
            elif prev:
                filled.append(cs[prev[-1]])
            elif nxt:
                filled.append(cs[nxt[0]])
            else:
                filled.append(0.0)
        vals = values.setdefault(upper, {})
        vals.setdefault("position", [(0, 0, 0)] * n)
        vals["position"] = [(x, y + c, z) for (x, y, z), c in zip(vals["position"], filled)]
    if a.loop:
        for bone, chans in values.items():
            for ch, vs in chans.items():
                vs[-1] = vs[0]


def build(model, uid):
    out, n = [], [0]
    for a in animations():
        values = a.sample()
        settle(model, a, values)
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
    """Lowest point, highest point and mouth (front of the muzzle) per tick, in blocks."""
    res = {}
    for a in anims:
        rows = []
        for tick in range(int(round(a["length"] * 20)) + 1):
            els, pts = pose(model, a["_values"], tick)
            allp = [p for ps in pts.values() for p in ps]
            head = pts.get("Head", [])
            front = min(p[2] for p in head)
            rows.append({"tick": tick, "low": round(min(p[1] for p in allp) / 16, 2), "high": round(max(p[1] for p in allp) / 16, 2),
                         "mouth": [round(sum(p[i] for p in head if p[2] < front + 2) / max(1, len([p for p in head if p[2] < front + 2])) / 16, 2)
                                   for i in range(3)]})
        res[a["name"]] = rows
    return res


if __name__ == "__main__":
    args = sys.argv[1:]
    strips_dir = only = None
    if "--strips" in args:
        i = args.index("--strips")
        strips_dir = args[i + 1]
        del args[i:i + 2]
    if "--only" in args:
        i = args.index("--only")
        only = set(args[i + 1].split(","))
        del args[i:i + 2]
    import salamandrex as M
    P, groups, dims, data, atlases, sizes = M.build()
    uid = lambda name: str(uuid.uuid5(M.NS, f"{M.IDENT}/{name}"))   # noqa: E731
    anims = build(data, uid)
    if strips_dir:
        from hamster_moissonneur_anim import strips
        strips(data, anims, atlases, sizes, strips_dir, count=10, only=only)
    for a in anims:
        a.pop("_values", None)
    data["animations"] = anims
    path = os.path.join(HERE, M.IDENT + ".bbmodel")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    print(f"{len(anims)} animations écrites dans {path} : " + ", ".join(f"{a['name']} ({round(a['length'] * 20)})" for a in anims))

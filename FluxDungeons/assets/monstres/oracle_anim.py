#!/usr/bin/env python3
"""Animations of the Oracle (2026-11-19): idle, two idle gestures, walk.

Every timing is written in docs/technique/boss/oracle.md: keep both in step. Ticks are 1/20 s.

He is an old mage, serene: nothing bounces, nothing hurries. The model's rest pose is the idle pose (right forearm
lifted, palm open under the orb), every animation starts and ends on it. The orb is the only thing that never
stops: it floats, turns and breathes over the palm, a little late on the hand that carries it.

Arms are posed by joint angles, as the Oversized Evoker's (same arm skeleton, cf. evocateur_demesure_anim.py: `P`,
`arm`). Other values as there: rotation in degrees in the model's axes (x > 0 lifts the front of a bone; y > 0 turns
its front to his left (-x); z > 0 lifts his right side (+x)), position in pixels of the delivered model.

The orb: `Orb` carries everything (its position is the float and the lag), `glow_orb_outer` is a flat open cube
(looks the same every quarter turn), `glow_orb_inner` and `glow_orb_core` stand on a corner (the same every third of
a turn), `glow_mote_1..4` circle it (position tracks, from their place at rest), `glow_spark_1..2` wait inside the
core at almost no size and are let out by the gestures. Scale is animated on those bones only: it is the pulse of an
energy, not a volume growing.

The game plays `idle` in a loop and a gesture over it now and then; `idle` then starts again from its first tick,
which is why the gestures bring every bone back to the pose of tick 0 of `idle` (motes included).
"""
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import paths  # noqa: F401,E402
from golem_fonderie import mul  # noqa: E402
import evocateur_demesure_anim as EA  # noqa: E402
from evocateur_demesure_anim import P, build, kp, kt  # noqa: E402,F401
from hamster_moissonneur_anim import Anim, T, bump, finish, smooth, strips, uid_for  # noqa: E402,F401
import oracle as O  # noqa: E402

TAU = 2 * math.pi
S, C = math.sin, math.cos
U = O.SCALE                                       # pixels of the delivered model per drawing unit

BASE = {s: (O.ARMS[s][0], mul(O.ARMS[s][0], O.ARMS[s][1]), O.ARMS[s][2]) for s in (1, -1)}
OPEN = P((12, 0, 8), (46, 0, 0), curl=20)         # right arm at rest: forearm lifted, palm open (baked)
HANG = P((-4, 0, 9), (14, 0, 3), curl=15)         # left arm at rest: hanging, relaxed (baked)


def arm(anim, side, f):
    EA.arm(anim, side, f, BASE)


# --------------------------------------------------------------------------- the orb
def mote_at(k, angle, reach=1.0, rise=0.0):
    """Offset of mote k from the orb's centre (pixels): on its tilted circle at `angle` degrees past its place at
    rest, `reach` times as far, `rise` higher."""
    size, _, dist, deg, up = O.MOTES[k]
    a = math.radians(deg + angle)
    tilt = (0.35, -0.5, 0.6, -0.3)[k]             # each circle leans its own way
    r = dist * reach
    return (U * r * C(a), U * (up + tilt * r * S(a - math.radians(deg)) + rise), U * r * S(a))


def motes(anim, angle, reach=lambda k, t: 1.0, rise=lambda k, t: 0.0, size=lambda k, t: 1.0):
    """Position and size tracks of the four motes; angle(k, t) in degrees past the place at rest."""
    for k in range(len(O.MOTES)):
        home = mote_at(k, 0)

        def pos(t, k=k, home=home):
            p = mote_at(k, angle(k, t), reach(k, t), rise(k, t))
            return tuple(a - b for a, b in zip(p, home))
        anim.pos(f"glow_mote_{k + 1}", pos)
        anim.scale(f"glow_mote_{k + 1}", lambda t, k=k: (max(0.01, size(k, t)),) * 3)


def orb(anim, turn, core=lambda t: 1.0, outer=lambda t: (1.0, 1.0, 1.0), inner=lambda t: 1.0):
    """Spin and size of the three cubes; turn(t) -> (outer quarter turns, inner thirds, core thirds) so far."""
    anim.rot("glow_orb_outer", lambda t: (0, 90.0 * turn(t)[0], 0))
    anim.rot("glow_orb_inner", lambda t: (0, 120.0 * turn(t)[1], 0))
    anim.rot("glow_orb_core", lambda t: (0, 120.0 * turn(t)[2], 0))
    anim.scale("glow_orb_core", lambda t: (core(t),) * 3)
    anim.scale("glow_orb_outer", outer)
    anim.scale("glow_orb_inner", lambda t: (inner(t),) * 3)


def sparks(anim, windows):
    """Sparks let out of the core: windows[k] = tuple of (from tick, to tick, drift x, drift z): each rises above
    the orb, shrinking away at the top. Outside its windows a spark waits inside the core."""
    for k in (0, 1):
        def state(t, k=k):
            for a, b, dx, dz in windows[k]:
                if T(a) < t < T(b):
                    u = (t - T(a)) / (T(b) - T(a))
                    return u, dx, dz
            return None

        def pos(t, state=state):
            st = state(t)
            if not st:
                return (0, 0, 0)
            u, dx, dz = st
            return (U * dx * u, U * (0.8 + 3.0 * smooth(0, 1, u)), U * dz * u)

        def scale(t, state=state):
            st = state(t)
            return (max(0.01, math.sin(math.pi * st[0]) ** 0.7) if st else 0.01,) * 3
        anim.pos(f"glow_spark_{k + 1}", pos)
        anim.scale(f"glow_spark_{k + 1}", scale)


def cloth(anim, sway, period=5.0):
    """Beard, hat and robe panels following the body, late and small."""
    anim.rot("Beard", lambda t: (0.8 * sway * S(TAU * t / period - 0.9), 0, 0))
    anim.rot("Skirt_back", lambda t: (1.0 * sway * S(TAU * t / period - 1.0), 0, 0))
    anim.rot("Tabard", lambda t: (1.0 * sway * S(TAU * t / period - 0.8), 0, 0))
    anim.rot("Coat_right", lambda t: (0, 0, 0.6 * sway * S(TAU * t / period - 1.2)))
    anim.rot("Coat_left", lambda t: (0, 0, -0.6 * sway * S(TAU * t / period - 1.2)))


# =========================================================================== animations
def animations():
    A = []

    # ------------------------------------------------------------------ idle: 10 s loop (200 ticks)
    # Two breaths of 5 s: the torso leans back a hair, the shoulders follow 0.4 s later, the head stays level and
    # drifts 2 degrees each way over the loop. Twice per loop (0-2.1 s up, 2.1-5 s down, again from 5 s) the right
    # hand lifts a little, the wrist opening 3 degrees: the orb follows 0.3 s late and the motes close in while the
    # hand rises. The orb bobs three times per loop on its own.
    def lift(t):
        u = (t / 5.0) % 1.0
        return smooth(0, 0.42, u) - smooth(0.42, 1, u)

    def rising(t):
        return bump(0, 0.42, (t / 5.0) % 1.0)

    def breath(t, late=0.0):
        return S(TAU * (t - late) / 5.0)

    a = Anim("idle", 200, loop=True)
    a.rot("Torso", lambda t: (0.7 * breath(t), 0, 0))
    a.pos("Body", lambda t: (0, 0.2 * (1 - C(TAU * t / 5.0)) / 2, 0))
    a.rot("Head", lambda t: (-0.7 * breath(t) + 0.6 * S(TAU * t / 10.0), 2.0 * S(TAU * t / 10.0), 0))
    a.rot("Hat", lambda t: (0, 0, 0.5 * S(TAU * t / 10.0 - 0.8)))
    arm(a, 1, lambda t: P((12 + 0.8 * breath(t, 0.4), 0, 8), (46 + 4 * lift(t), 0, 0), (3 * lift(t - 0.2), 0, 0), 20))
    arm(a, -1, lambda t: P((-4 + 0.6 * breath(t, 0.4), 0, 9), (14 + 1.2 * breath(t, 0.8), 0, 3),
                           curl=15 + 4 * S(TAU * t / 10.0)))
    cloth(a, 1.0)
    a.pos("Orb", lambda t: (0.25 * S(TAU * 2 * t / 10.0 + 1.0),
                            0.7 * S(TAU * 3 * t / 10.0) + 0.9 * (lift(t - 0.3) - lift(t)),
                            0.25 * S(TAU * 1 * t / 10.0)))
    orb(a, lambda t: (t / 10.0, -t / 10.0, 2 * t / 10.0),
        core=lambda t: 1.0 + 0.14 * S(TAU * 4 * t / 10.0),
        outer=lambda t: (1 + 0.07 * S(TAU * 3 * t / 10.0), 1 + 0.07 * S(TAU * 3 * t / 10.0 + 2.1),
                         1 + 0.07 * S(TAU * 4 * t / 10.0 + 4.0)),
        inner=lambda t: 1 + 0.08 * S(TAU * 2 * t / 10.0 + 1.0))
    turns = (1, -2, 2, -1)                        # full circles per loop, each mote its own
    motes(a, lambda k, t: 360.0 * turns[k] * t / 10.0,
          reach=lambda k, t: (1 + 0.12 * S(TAU * (k + 2) * t / 10.0)) * (1 - 0.22 * rising(t)),
          size=lambda k, t: 0.78 + 0.22 * C(TAU * (k % 3 + 1) * t / 10.0 + 1.3 * k) if k else 1.0)
    sparks(a, ((), ()))
    A.append(a)

    # ------------------------------------------------------------------ idle_magic: 3.6 s (72 ticks)
    # 0-14   he lowers his head towards his hand; the hand rises, the wrist turns inwards; the core swells
    # 14-40  he rolls his wrist (in at 14, out at 22, in at 32) while the palm draws one small circle (16-42);
    #        the orb follows 0.15 s late, its outer cube stretched; the motes race (fastest around tick 34);
    #        two cyan sparks leave the core (24-38, 29-44)
    # 40-60  the hand comes to rest, the orb finds its shape again, the motes slow down
    # 60-72  the head comes back, everything is on the idle pose again
    a = Anim("idle_magic", 72)
    w = TAU / 1.3

    def env(t):
        return bump(T(14), T(44), t)

    a.rot("Torso", lambda t: (-1.5 * bump(0, 3.4, t), -1.5 * bump(0, 3.4, t), 0))
    a.rot("Head", lambda t: (kt(t, ((0, 0), (14, -9), (46, -9), (60, -3), (72, 0))),
                             kt(t, ((0, 0), (14, -11), (48, -11), (72, 0))),
                             kt(t, ((0, 0), (14, -3), (48, -3), (72, 0)))))
    a.rot("Hat", lambda t: (0, 0, 1.2 * bump(T(4), T(30), t) - 1.0 * bump(T(50), T(72), t)))
    arm(a, 1, lambda t: P((12 + 2.5 * env(t) * S(w * (t - 0.7)), 0, 8 + 2.5 * env(t) * C(w * (t - 0.7))),
                          (46 + kt(t, ((0, 0), (14, 9), (40, 9), (58, 2), (72, 0))), 0, 0),
                          (3 * bump(0, 1.0, t), kt(t, ((0, 0), (14, 18), (22, -22), (32, 22), (40, -10), (54, 0),
                                                      (72, 0))), 0),
                          20 + 12 * bump(T(14), T(46), t)))
    arm(a, -1, lambda t: P((-4 - 2 * bump(0, 3.4, t), 0, 9), (14 + 3 * bump(0, 3.4, t), 0, 3), curl=15))
    cloth(a, 0.0)
    a.rot("Beard", lambda t: (2.0 * bump(T(2), T(24), t) - 1.5 * bump(T(54), T(72), t), 0, 0))
    a.pos("Orb", lambda t: (1.5 * env(t - 0.15) * C(w * (t - 0.85)),
                            1.1 * bump(T(8), T(54), t) + 0.5 * env(t) * S(TAU * t / 0.9),
                            -1.5 * env(t - 0.15) * S(w * (t - 0.85))))

    def spun(t):
        return smooth(T(4), T(64), t)
    orb(a, lambda t: (2 * spun(t), -3 * spun(t), 6 * spun(t)),
        core=lambda t: kt(t, ((0, 1), (14, 1.35), (40, 1.45), (58, 1.05), (72, 1))),
        outer=lambda t: (1 + 0.35 * bump(T(16), T(44), t), 1 - 0.08 * bump(T(16), T(44), t), 1),
        inner=lambda t: 1 + 0.15 * bump(T(10), T(50), t))
    laps = (2, -3, 3, -2)
    motes(a, lambda k, t: 360.0 * laps[k] * spun(t),
          reach=lambda k, t: 1 - 0.3 * bump(T(2), T(20), t) + 0.12 * bump(T(24), T(56), t))
    sparks(a, (((24, 38, 0.5, -0.3),), ((29, 44, -0.6, 0.2),)))
    A.append(a)

    # ------------------------------------------------------------------ idle_gaze: 3.6 s (72 ticks)
    # 0-16   he tilts his head towards his hand, slowly; the hand hardly moves
    # 22-46  one deeper pulse of the orb (core largest at tick 32); two motes and a spark rise above it (26-56)
    # 48-66  he raises his head again
    # 66-72  still, on the idle pose
    a = Anim("idle_gaze", 72)
    a.rot("Torso", lambda t: (-0.8 * bump(0, 3.3, t), -1.0 * bump(0, 3.3, t), 0))
    a.rot("Head", lambda t: (kt(t, ((0, 0), (16, -8), (48, -8), (66, 0), (72, 0))),
                             kt(t, ((0, 0), (16, -9), (48, -9), (66, 0), (72, 0))),
                             kt(t, ((0, 0), (16, -5), (48, -5), (66, 0), (72, 0)))))
    a.rot("Hat", lambda t: (0, 0, 1.0 * bump(T(4), T(30), t) - 0.8 * bump(T(48), T(72), t)))
    arm(a, 1, lambda t: P((12, 0, 8), (46 + 1.5 * bump(T(10), T(60), t), 0, 0), (2 * bump(T(10), T(60), t), 0, 0), 20))
    arm(a, -1, lambda t: HANG)
    cloth(a, 0.0)
    a.rot("Beard", lambda t: (1.8 * bump(T(2), T(26), t) - 1.2 * bump(T(48), T(72), t), 0, 0))
    a.pos("Orb", lambda t: (0, 0.6 * S(TAU * t / 3.6) + 0.5 * bump(T(22), T(46), t), 0))
    orb(a, lambda t: (t / 3.6, -t / 3.6, t / 3.6),
        core=lambda t: kt(t, ((0, 1), (22, 1), (32, 1.65), (46, 1.1), (58, 1), (72, 1))),
        outer=lambda t: (1 + 0.14 * bump(T(22), T(48), t),) * 3,
        inner=lambda t: 1 + 0.14 * bump(T(24), T(50), t))
    way = (1, -1, 1, -1)
    motes(a, lambda k, t: 360.0 * way[k] * smooth(0, 3.6, t),
          rise=lambda k, t: (2.4, 0, 1.8, 0)[k] * bump(T(26 + 2 * k), T(56 + 2 * k), t),
          reach=lambda k, t: 1 - (0.45, 0, 0.35, 0)[k] * bump(T(26 + 2 * k), T(56 + 2 * k), t))
    sparks(a, (((30, 50, 0.2, 0.2),), ()))
    A.append(a)

    # ------------------------------------------------------------------ walk: 4.8 s loop (96 ticks) = 4 cycles of 1.2 s
    # Slow and sure (about 1.25 blocks per second at the drawn size): legs 18 degrees each way, the hips turn with
    # the leading leg, the shoulders the other way, the head stays ahead. The right arm keeps the orb in front of
    # him and hardly moves; the left arm swings with the opposite leg. Each panel of the robe follows late. The
    # loop is four strides long so that the orb can keep its slow turn; its float takes away part of the steps.
    L = 1.2
    a = Anim("walk", 96, loop=True)

    def ph(t):
        return TAU * t / L
    for s, name in ((1, "Leg_right"), (-1, "Leg_left")):
        a.rot(name, lambda t, s=s: (18 * s * S(ph(t)), 0, 0))
        a.pos(name, lambda t, s=s: (0, 1.0 * max(0.0, s * C(ph(t))), 0))
    a.pos("Body", lambda t: (0, 0.45 * C(2 * ph(t)), 0))
    a.rot("Body", lambda t: (0, 2.5 * S(ph(t)), 1.0 * S(ph(t))))
    a.rot("Torso", lambda t: (-1.5, -5 * S(ph(t)), -1.0 * S(ph(t))))
    a.rot("Head", lambda t: (1.5, 2.5 * S(ph(t)), 0))
    a.rot("Hat", lambda t: (0, 0, 0.6 * S(ph(t) - 0.9)))
    arm(a, 1, lambda t: P((12 - 1.5 * S(ph(t)), 0, 8), (46 + 1.5 * S(ph(t) - 0.6), 0, 0), (1, 0, 0), 20))
    arm(a, -1, lambda t: P((-4 + 12 * S(ph(t)), 0, 9), (18 + 6 * S(ph(t) - 0.6), 0, 3), curl=20))
    a.rot("Beard", lambda t: (1.0 * S(2 * ph(t) - 1.0), 0, 0))
    a.rot("Tabard", lambda t: (3 + 2 * C(2 * ph(t) - 0.5), 0, 0))
    a.rot("Skirt_back", lambda t: (-4 - 2 * S(2 * ph(t) - 0.6), 0, 0))
    a.rot("Coat_right", lambda t: (3 * S(ph(t) - 0.7), 0, 1))
    a.rot("Coat_left", lambda t: (-3 * S(ph(t) - 0.7), 0, -1))
    a.pos("Orb", lambda t: (0.2 * S(TAU * t / 4.8), 0.6 * S(TAU * 2 * t / 4.8) - 0.3 * C(2 * ph(t) - 0.5), 0))
    orb(a, lambda t: (t / 4.8, -t / 4.8, t / 4.8),
        core=lambda t: 1.0 + 0.14 * S(TAU * 2 * t / 4.8),
        outer=lambda t: (1 + 0.07 * S(TAU * 2 * t / 4.8), 1 + 0.07 * S(TAU * 2 * t / 4.8 + 2.1),
                         1 + 0.07 * S(TAU * 3 * t / 4.8 + 4.0)),
        inner=lambda t: 1 + 0.08 * S(TAU * t / 4.8 + 1.0))
    motes(a, lambda k, t: 360.0 * way[k] * t / 4.8,
          reach=lambda k, t: 1 + 0.1 * S(TAU * (k + 1) * t / 4.8),
          size=lambda k, t: 0.8 + 0.2 * C(TAU * (k % 2 + 1) * t / 4.8 + 1.3 * k) if k else 1.0)
    sparks(a, ((), ()))
    A.append(a)
    return A


# evocateur_demesure_anim.build samples its own module's animations(): give it ours
def build(model, uid):  # noqa: F811
    saved = EA.animations
    EA.animations = animations
    try:
        return EA.build(model, uid)
    finally:
        EA.animations = saved

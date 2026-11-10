#!/usr/bin/env python3
"""Animations of the Copper T-Rex (2026-11-10).

Every timing that matters to the game (events, holds, windows) is written in docs/technique/boss/trex-cuivre.md and
read by FluxDungeons from spells/mineur/*.yml (CopperPowers): keep the three in step. Ticks are 1/20 s.

Same conventions and tools as hamster_moissonneur_anim.py ("real" values: rotation in degrees in the model's axes,
x > 0 lifts the front of a bone and moves a foot forwards, y > 0 turns its front to the monster's left, z > 0 lifts
its right side; position in pixels of the delivered model).

The hind legs hang from Root, not from Body (so that they stay planted while the body twists): `settle()` moves
them with the body (the hips stay attached) and bends each leg (thigh forwards, shin backwards: a crouch) until its
foot touches the ground in the windows where it is planted, or never sinks under it elsewhere. The body itself never
goes under the ground; in the "lock" windows (lying dead) its lowest point touches it.
"""
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import paths  # noqa: F401,E402
import hamster_moissonneur_anim as H  # noqa: E402  (shared builder, posing, strips)
from hamster_moissonneur_anim import Anim, T, bump, keys, smooth  # noqa: E402

TAU = 2 * math.pi
S, C = math.sin, math.cos
LEGS = ("Leg_right", "Leg_left")
SHIN = {"Leg_right": "Shin_right", "Leg_left": "Shin_left"}
TAIL = ("Tail_1", "Tail_2", "Tail_3", "Tail_4", "Tail_5")
ARMS = ("Arm_right", "Arm_left")
FLEX = (-18.0, 60.0)        # bend of a leg (thigh +f, shin -2f) allowed to reach the ground


def K(*pts):
    """keys() over (tick, value) points."""
    pp = [(T(a), b) for a, b in pts]
    return lambda t: keys(t, pp)


def animations():
    """Every animation, plus per animation the windows where a leg is in the air (default: always planted), the
    lock windows (body on the ground) and whether the legs follow the body's position."""
    A, AIR, LOCK, FREE = [], {}, {}, set()

    def tail(a, f):
        """f(t, i) -> rotation of Tail_i (i = 0 at the base)."""
        for i, b in enumerate(TAIL):
            a.rot(b, lambda t, i=i: f(t, i))

    def arms(a, f):
        """f(t, s) -> rotation, s = 1 right, -1 left."""
        a.rot("Arm_right", lambda t: f(t, 1))
        a.rot("Arm_left", lambda t: f(t, -1))

    def gait(a, L, swing, knee, lift, name):
        """Biped walk: each thigh swings from +swing (foot forwards) to -swing while planted (half a cycle), then
        comes forward again lifted, knee bent (shin back). Right leg first, left half a cycle later."""
        air = {}
        for leg, off in (("Leg_right", 0.0), ("Leg_left", 0.5)):
            def u_of(t, off=off):
                return (t / L + off) % 1.0

            def thigh(t, u_of=u_of):
                u = u_of(t)
                if u < 0.5:
                    return (swing - 2 * swing * u / 0.5, 0, 0)
                v = (u - 0.5) / 0.5
                return (-swing + 2 * swing * smooth(0, 1, v) + lift * S(math.pi * v), 0, 0)

            def shin(t, u_of=u_of):
                u = u_of(t)
                if u < 0.5:
                    return (0, 0, 0)
                return (-knee * S(math.pi * (u - 0.5) / 0.5), 0, 0)
            a.rot(leg, thigh)
            a.rot(SHIN[leg], shin)
            start = ((0.5 - off) % 1.0) * L
            wins = [(start + k * L + 0.03, start + k * L + 0.5 * L - 0.03) for k in (-1, 0)]
            air[leg] = [(max(0.0, x), min(L, y)) for x, y in wins if y > 0 and x < L]
        AIR[name] = air

    # ------------------------------------------------------------------ idle: 4 s loop (80 ticks)
    # Slow breathing (2 s), looks right then left, sniffs, a short growl (jaw), claws twitch, tail sways gently.
    a = Anim("idle", 80, loop=True)
    a.pos("Body", lambda t: (0, 0.4 * (1 - C(TAU * t / 2)) / 2, 0))
    a.rot("Body", lambda t: (0.8 * S(TAU * t / 2), 0, 0))
    a.rot("Neck", lambda t: (-0.6 * S(TAU * t / 2 - 0.6), 3 * bump(0.5, 1.6, t) - 3 * bump(2.2, 3.3, t), 0))
    a.rot("Head", lambda t: (3 * bump(1.7, 2.2, t) + 2 * bump(3.4, 3.9, t), 10 * bump(0.5, 1.6, t) - 10 * bump(2.2, 3.3, t),
                             -2 * bump(0.5, 1.6, t) + 2 * bump(2.2, 3.3, t)))
    a.rot("Jaw", lambda t: (-8 * bump(3.4, 3.9, t) - 2 * bump(1.75, 2.15, t), 0, 0))
    a.rot("Arm_right", lambda t: (6 * bump(1.0, 1.3, t), 0, 0))
    a.rot("Arm_left", lambda t: (6 * bump(2.9, 3.2, t), 0, 0))
    tail(a, lambda t, i: (0.6 * S(TAU * t / 2 - 0.3 * i), (1.5 + 0.6 * i) * S(TAU * t / 4 - 0.4 * i), 0))
    A.append(a)

    # ------------------------------------------------------------------ walk: 1.4 s loop (28 ticks)
    # Biped walk: right foot lands at 0, left at 14. Weight: the body drops a little at each landing, rolls over the
    # planted leg and twists; the tail swings the other way, the head bobs, the little arms swing.
    L = 1.4
    a = Anim("walk", 28, loop=True)
    gait(a, L, 16, 30, 10, "walk")
    u = lambda t: t / L                                                                      # noqa: E731
    a.pos("Body", lambda t: (0, -0.6 * (1 + C(4 * math.pi * u(t))) / 2, 0))
    a.rot("Body", lambda t: (1.0 * C(4 * math.pi * u(t)), 2.5 * S(TAU * u(t)), 2 * S(TAU * u(t))))
    a.rot("Neck", lambda t: (-1.5 * C(4 * math.pi * u(t) + 0.5), -2 * S(TAU * u(t)), -1 * S(TAU * u(t))))
    a.rot("Head", lambda t: (2 * C(4 * math.pi * u(t) + 1.2), -0.8 * S(TAU * u(t)), 0))
    a.rot("Jaw", lambda t: (-1.5 - 1.5 * bump(0.6, 1.0, t), 0, 0))
    tail(a, lambda t, i: (1.5 * C(4 * math.pi * u(t) - 0.6 - 0.3 * i), -(2 + i) * S(TAU * u(t) - 0.5 - 0.35 * i), 0))
    arms(a, lambda t, s: (8 * S(4 * math.pi * u(t) + 0.3), 0, 0))
    A.append(a)

    # ------------------------------------------------------------------ detect: 0.6 s (12 ticks)
    # It has spotted its prey: head snaps up and a little to the side, jaw half open, a twitch of the body.
    a = Anim("detect", 12)
    a.rot("Head", lambda t: (9 * bump(0, T(12), t), 6 * bump(0, T(10), t), 0))
    a.rot("Neck", lambda t: (6 * bump(0, T(12), t), 0, 0))
    a.rot("Jaw", lambda t: (-12 * bump(T(1), T(11), t), 0, 0))
    a.rot("Body", lambda t: (2 * bump(0, T(12), t), 0, 0))
    tail(a, lambda t, i: (-2 * bump(0, T(12), t), 0, 0))
    A.append(a)

    # ------------------------------------------------------------------ turn: 1 s (20 ticks)
    # Turning on the spot without sliding: head first, then torso, then pelvis; two small steps (right 2-10, left
    # 10-18); the tail swings the other way.
    a = Anim("turn", 20)
    a.rot("Head", lambda t: (0, 10 * bump(0, T(16), t), 0))
    a.rot("Neck", lambda t: (0, 6 * bump(T(1), T(18), t), 0))
    a.rot("Body", lambda t: (0, 12 * bump(T(2), T(20), t), 0))
    tail(a, lambda t, i: (0, -(4 + 2 * i) * bump(T(3 + i), T(20), t), 0))
    a.rot("Leg_right", lambda t: (9 * bump(T(2), T(10), t), 0, 0))
    a.rot("Shin_right", lambda t: (-18 * bump(T(2), T(10), t), 0, 0))
    a.rot("Leg_left", lambda t: (9 * bump(T(10), T(18), t), 0, 0))
    a.rot("Shin_left", lambda t: (-18 * bump(T(10), T(18), t), 0, 0))
    AIR["turn"] = {"Leg_right": [(T(3), T(9))], "Leg_left": [(T(11), T(17))]}
    A.append(a)

    # ------------------------------------------------------------------ roar: 1.5 s (30 ticks)
    # Head up, jaw wide open, neck stretched, chest raised, tail steady and a little up, arms out; held 8-22 with a
    # tremble (the roar), then back.
    a = Anim("roar", 30)
    r = K((0, 0), (8, 1), (22, 1), (30, 0))
    hold = lambda t: bump(T(6), T(24), t)                                                    # noqa: E731
    a.rot("Body", lambda t: (7 * r(t), 0, 0))
    a.pos("Body", lambda t: (0, 0.8 * r(t), 0))
    a.rot("Neck", lambda t: (14 * r(t), 0, 0))
    a.rot("Head", lambda t: (16 * r(t) + 1.5 * S(TAU * 12 * t) * hold(t), 0, 2 * S(TAU * 5 * t) * hold(t)))
    a.rot("Jaw", lambda t: (-30 * r(t), 0, 0))
    arms(a, lambda t, s: (-15 * r(t), 0, 10 * s * r(t)))
    tail(a, lambda t, i: (-(4 if i == 0 else 2) * r(t), 0, 0))
    A.append(a)

    # ------------------------------------------------------------------ attack: 0.6 s (12 ticks), plain melee snap
    a = Anim("attack", 12)
    op = K((0, 0), (4, 1), (6, 0), (12, 0))
    lunge = lambda t: bump(T(3), T(11), t)                                                   # noqa: E731
    a.rot("Jaw", lambda t: (-22 * op(t), 0, 0))
    a.rot("Head", lambda t: (6 * op(t) - 10 * lunge(t), 0, 0))
    a.rot("Neck", lambda t: (-6 * lunge(t), 0, 0))
    a.pos("Body", lambda t: (0, 0, -2 * lunge(t)))
    A.append(a)

    # ------------------------------------------------------------------ bite: Crushing Bite, 1.8 s (36 ticks)
    # Wind-up 0-9 (head pulled back and up, jaw opening, neck drawn in, arms folded), strike 9-12 (head and neck
    # thrown forwards, body lunges, jaws slam shut at 12 = bite_hit), grinding shake 12-20, recovery 14-36 (the
    # punish window).
    a = Anim("bite", 36)
    w = K((0, 0), (9, 1), (10, 1), (12, 0))
    s_ = K((9, 0), (12, 1), (16, 1), (30, 0))
    a.rot("Head", lambda t: (16 * w(t) - 12 * s_(t) + 6 * bump(T(18), T(32), t), 0, 6 * S(TAU * 10 * t) * bump(T(12), T(22), t)))
    a.rot("Neck", lambda t: (10 * w(t) - 14 * s_(t), 0, 0))
    a.pos("Neck", lambda t: (0, 0, 2 * w(t)))
    a.rot("Body", lambda t: (3 * w(t) - 6 * s_(t), 0, 0))
    a.pos("Body", lambda t: (0, -0.5 * s_(t), 1.5 * w(t) - 4 * s_(t)))
    a.rot("Jaw", lambda t: (-34 * K((0, 0), (9, 1), (10, 1), (12, 0))(t) + 2 * S(TAU * 10 * t) * bump(T(12), T(20), t), 0, 0))
    arms(a, lambda t, s: (-20 * w(t) - 10 * s_(t), 0, 0))
    tail(a, lambda t, i: (-3 * w(t) + 3 * s_(t), 0, 0))
    A.append(a)

    # ------------------------------------------------------------------ tail_sweep: Tectonic Sweep, 2.6 s (52 ticks)
    # Wind-up 0-14: feet planted, pelvis and torso twist to its left (the tail goes to its right side), tail lowered.
    # Strike 14-22: the whole body twists the other way, the tail whips through a wide arc behind it, from its right
    # to its left, its tip last (tail_hit_start 15, tail_hit_end 22). Recovery 22-52: back in line, a step to steady.
    a = Anim("tail_sweep", 52)
    by = K((0, 0), (14, 22), (22, -40), (32, -34), (52, 0))
    a.rot("Body", lambda t: (-2 * bump(0, T(22), t), by(t), 4 * bump(T(14), T(26), t)))
    a.rot("Neck", lambda t: (0, -0.4 * by(t), 0))
    a.rot("Head", lambda t: (0, -0.3 * by(t), 0))
    for i, b in enumerate(TAIL):
        yaw = K((0, 0), (14, 8 + 2 * i), (17 + i, -(8 + 3 * i)), (24 + i, -(12 + 3 * i)), (52, 0))
        pitch = K((0, 0), (14, 4), (18, 10), (30, 8), (52, 0)) if i == 0 else K((0, 0), (52, 0))
        a.rot(b, lambda t, yaw=yaw, pitch=pitch: (pitch(t), yaw(t), 0))
    a.rot("Leg_left", lambda t: (8 * bump(T(32), T(42), t), 0, 0))
    a.rot("Shin_left", lambda t: (-16 * bump(T(32), T(42), t), 0, 0))
    AIR["tail_sweep"] = {"Leg_left": [(T(33), T(41))]}
    arms(a, lambda t, s: (-10 * bump(T(10), T(30), t), 0, 0))
    A.append(a)

    # ------------------------------------------------------------------ Drill Charge: windup / run / stop
    # charge_windup, 1.2 s (24 ticks): stares at the player (0-8), then lowers its head, crouches, digs its claws
    # (right foot scrapes 14-19), ready to burst (pose held at 24 = start of charge_run).
    a = Anim("charge_windup", 24)
    c_ = K((8, 0), (20, 1), (24, 1))
    a.rot("Head", lambda t: (7 * bump(0, T(9), t) - 20 * c_(t), 0, 0))
    a.rot("Jaw", lambda t: (-8 * bump(0, T(9), t) - 6 * c_(t), 0, 0))
    a.rot("Neck", lambda t: (-12 * c_(t), 0, 0))
    a.rot("Body", lambda t: (-8 * c_(t), 0, 0))
    a.pos("Body", lambda t: (0, -2.5 * c_(t), 1.5 * c_(t)))
    tail(a, lambda t, i: (-2 * c_(t), 0, 0))
    arms(a, lambda t, s: (-15 * c_(t), 0, 0))
    a.rot("Leg_right", lambda t: (-7 * bump(T(14), T(19), t), 0, 0))
    a.rot("Shin_right", lambda t: (-10 * bump(T(14), T(19), t), 0, 0))
    AIR["charge_windup"] = {"Leg_right": [(T(15), T(18))]}
    A.append(a)

    # charge_run: 0.5 s loop (10 ticks), head down like a ram, long fast strides, tail straight back.
    L = 0.5
    a = Anim("charge_run", 10, loop=True)
    gait(a, L, 28, 45, 14, "charge_run")
    a.pos("Body", lambda t: (0, -2.5 + 0.8 * C(4 * math.pi * t / L), 1.5))
    a.rot("Body", lambda t: (-8 + 1.5 * C(4 * math.pi * t / L), 0, 2 * S(TAU * t / L)))
    a.rot("Neck", lambda t: (-12, 0, 0))
    a.rot("Head", lambda t: (-20 + 1.5 * C(4 * math.pi * t / L + 1), 0, 0))
    a.rot("Jaw", lambda t: (-6, 0, 0))
    tail(a, lambda t, i: (-2, 2 * S(TAU * t / L - 0.4 * i), 0))
    arms(a, lambda t, s: (-15, 0, 0))
    A.append(a)

    # charge_stop, 1.2 s (24 ticks): brakes hard (0-8, the body pitches forwards), head comes up, shakes it (10-22).
    a = Anim("charge_stop", 24)
    a.rot("Body", lambda t: (K((0, -8), (4, -14), (10, -4), (24, 0))(t), 0, 0))
    a.pos("Body", lambda t: (0, K((0, -2.5), (4, -3), (12, -0.5), (24, 0))(t), K((0, 1.5), (6, -1), (16, 0))(t)))
    a.rot("Head", lambda t: (K((0, -20), (4, -26), (12, -4), (24, 0))(t), 9 * S(TAU * 3 * t) * bump(T(10), T(22), t), 0))
    a.rot("Neck", lambda t: (K((0, -12), (6, -14), (14, 0))(t), 0, 0))
    a.rot("Jaw", lambda t: (K((0, -6), (8, -14), (16, 0))(t), 0, 0))
    tail(a, lambda t, i: (K((0, -2), (4, -8), (12, 0))(t), 0, 0))
    arms(a, lambda t, s: (K((0, -15), (12, 0))(t), 0, 0))
    A.append(a)

    # ------------------------------------------------------------------ stun: 1.5 s (30 ticks)
    # Dazed (charge into a wall, absorption interrupted): head droops and wobbles, neck slack, legs unsteady (the
    # body sways and sinks), tail drops; recovers 22-30.
    a = Anim("stun", 30)
    d = K((0, 0), (6, 1), (22, 1), (30, 0))
    a.rot("Head", lambda t: (-18 * d(t), 0, 6 * S(TAU * 1.5 * t) * d(t)))
    a.rot("Neck", lambda t: (-14 * d(t), 4 * S(TAU * 1.2 * t) * d(t), 0))
    a.rot("Jaw", lambda t: (-10 * d(t), 0, 0))
    a.rot("Body", lambda t: (-3 * d(t), 0, 4 * S(TAU * 1.2 * t) * d(t)))
    a.pos("Body", lambda t: (0, -1.5 * d(t), 0))
    tail(a, lambda t, i: (3 * d(t), 0, 0))
    arms(a, lambda t, s: (12 * d(t), 0, 0))
    A.append(a)

    # ------------------------------------------------------------------ ultimate: Cataclysmic Resonance, 6.5 s
    # (130 ticks). Preparation 0-30 (still, legs apart, head down, chest tight, tail up), accumulation 30-50 (rises,
    # head up, body trembling), release: a hop 50-54 and both feet slam the ground at 54 (ultimate_ground_impact),
    # eruptions 60-110 (low stance, trembling, jaw open: roars at the waves), end 110-130 (relaxes).
    a = Anim("ultimate", 130)
    p = K((0, 0), (30, 1))
    acc = lambda t: bump(T(30), T(50), t) + smooth(T(30), T(50), t) * (1 - smooth(T(50), T(52), t))   # noqa: E731
    a.pos("Body", lambda t: (0, K((0, 0), (30, -2), (48, 1.5), (52, 2.5), (54, -3), (60, -2), (110, -2), (130, 0))(t), 0))
    a.rot("Body", lambda t: (K((0, 0), (30, -6), (48, 6), (52, 8), (54, -6), (60, -4), (110, -4), (130, 0))(t), 0,
                             1.2 * S(TAU * 8 * t) * bump(T(60), T(110), t) + 0.8 * S(TAU * 15 * t) * acc(t) * 0.5))
    a.rot("Head", lambda t: (K((0, 0), (30, -14), (48, 18), (54, -10), (60, -6), (110, -6), (130, 0))(t)
                             + 1.2 * S(TAU * 15 * t) * bump(T(30), T(50), t), 0, 0))
    a.rot("Neck", lambda t: (K((0, 0), (30, -8), (48, 10), (54, -6), (110, -4), (130, 0))(t), 0, 0))
    a.rot("Jaw", lambda t: (K((0, 0), (30, -6), (48, -14), (54, -28), (66, -20), (110, -12), (130, 0))(t)
                            - 8 * bump(T(70), T(76), t) - 8 * bump(T(84), T(90), t) - 10 * bump(T(98), T(104), t), 0, 0))
    tail(a, lambda t, i: (K((0, 0), (30, -6), (50, -8), (54, 6), (60, 3), (110, 3), (130, 0))(t) * (1 - 0.1 * i), 0, 0))
    arms(a, lambda t, s: (K((0, 0), (30, 10), (48, -20), (54, 15), (110, 10), (130, 0))(t), 0, 0))
    spread = K((0, 0), (30, 1), (110, 1), (130, 0))
    a.rot("Leg_right", lambda t: (0, 0, 6 * spread(t)))
    a.rot("Leg_left", lambda t: (0, 0, -6 * spread(t)))
    for leg in LEGS:
        a.pos(leg, lambda t: (0, 3 * bump(T(49), T(54), t), 0))
    AIR["ultimate"] = {leg: [(T(50), T(53.5))] for leg in LEGS}
    A.append(a)

    # ------------------------------------------------------------------ hurt (small, 6 ticks) and hurt_2 (big, 10)
    a = Anim("hurt", 6)
    a.rot("Head", lambda t: (5 * bump(0, T(6), t), 0, 0))
    a.rot("Neck", lambda t: (3 * bump(0, T(6), t), 0, 0))
    a.pos("Body", lambda t: (0, 0, 0.8 * bump(0, T(6), t)))
    tail(a, lambda t, i: (0, 2 * bump(0, T(6), t), 0))
    A.append(a)
    a = Anim("hurt_2", 10)
    a.rot("Body", lambda t: (4 * bump(0, T(10), t), 0, 0))
    a.pos("Body", lambda t: (0, 0, 2 * bump(0, T(10), t)))
    a.rot("Head", lambda t: (6 * bump(0, T(9), t), 8 * bump(0, T(10), t), 0))
    a.rot("Neck", lambda t: (5 * bump(0, T(10), t), 0, 0))
    a.rot("Jaw", lambda t: (-10 * bump(0, T(8), t), 0, 0))
    tail(a, lambda t, i: (0, -3 * bump(0, T(10), t), 0))
    A.append(a)

    # ------------------------------------------------------------------ absorb: Copper Absorption, 2 s (40 ticks)
    # Lowers its head and arms to the vein (0-10), absorbs it, its body vibrating (10-32, gain at 32), rises (32-40).
    a = Anim("absorb", 40)
    lo = K((0, 0), (10, 1), (32, 1), (40, 0))
    vib = lambda t: bump(T(10), T(32), t)                                                    # noqa: E731
    a.rot("Body", lambda t: (-10 * lo(t), 0, 1.2 * S(TAU * 10 * t) * vib(t)))
    a.pos("Body", lambda t: (0, -2 * lo(t), 0))
    a.rot("Neck", lambda t: (-20 * lo(t), 0, 0))
    a.rot("Head", lambda t: (-18 * lo(t) + 1 * S(TAU * 12 * t) * vib(t), 0, 0))
    a.rot("Jaw", lambda t: (-12 * lo(t), 0, 0))
    tail(a, lambda t, i: (-3 * lo(t), 0, 0))
    arms(a, lambda t, s: (20 * lo(t), 0, 0))
    A.append(a)

    # ------------------------------------------------------------------ phase_shift (Copper Awakening), 2 s (40)
    # Roar head up (0-24), then lifts its right foot and stamps it at 30.
    a = Anim("phase_shift", 40)
    r = K((0, 0), (8, 1), (22, 1), (28, 0.2), (40, 0))
    a.rot("Body", lambda t: (7 * r(t), 0, 0))
    a.pos("Body", lambda t: (0, 0.8 * r(t) - 1.0 * bump(T(29), T(36), t), 0))
    a.rot("Neck", lambda t: (14 * r(t), 0, 0))
    a.rot("Head", lambda t: (16 * r(t) + 1.5 * S(TAU * 12 * t) * bump(T(6), T(24), t), 0, 0))
    a.rot("Jaw", lambda t: (-30 * r(t), 0, 0))
    arms(a, lambda t, s: (-12 * r(t), 0, 8 * s * r(t)))
    tail(a, lambda t, i: (-3 * r(t), 0, 0))
    a.rot("Leg_right", lambda t: (8 * bump(T(23), T(30), t), 0, 0))
    a.rot("Shin_right", lambda t: (-20 * bump(T(23), T(30), t), 0, 0))
    a.pos("Leg_right", lambda t: (0, 3 * bump(T(23), T(30), t), 0))
    AIR["phase_shift"] = {"Leg_right": [(T(24), T(29.5))]}
    A.append(a)

    # phase_shift_3 (Deep Resonance), 2.5 s (50): head low (0-12), deep roar head up (12-36), both feet slam at 40.
    a = Anim("phase_shift_3", 50)
    low = K((0, 0), (12, 1), (18, 0), (50, 0))
    r = K((0, 0), (12, 0), (20, 1), (34, 1), (40, 0.2), (50, 0))
    a.rot("Body", lambda t: (-6 * low(t) + 8 * r(t), 0, 0))
    a.pos("Body", lambda t: (0, -1.5 * low(t) + 0.8 * r(t) + 2 * bump(T(36), T(40), t) - 2.5 * bump(T(40), T(46), t), 0))
    a.rot("Neck", lambda t: (-10 * low(t) + 14 * r(t), 0, 0))
    a.rot("Head", lambda t: (-16 * low(t) + 18 * r(t) + 2 * S(TAU * 10 * t) * bump(T(18), T(36), t), 0, 0))
    a.rot("Jaw", lambda t: (-8 * low(t) - 32 * r(t), 0, 0))
    arms(a, lambda t, s: (-14 * r(t), 0, 10 * s * r(t)))
    tail(a, lambda t, i: (-4 * r(t) + 3 * bump(T(40), T(46), t), 0, 0))
    for leg in LEGS:
        a.pos(leg, lambda t: (0, 2.5 * bump(T(35), T(40), t), 0))
    AIR["phase_shift_3"] = {leg: [(T(36), T(39.5))] for leg in LEGS}
    A.append(a)

    # ------------------------------------------------------------------ death: 3.5 s (70 ticks)
    # Last roar (0-14), collapse onto its left side (14-44: legs give way, body rolls and falls, head and tail come
    # down; on the ground from 40), the resonance dies out (44-60), still to the end (70).
    a = Anim("death", 70)
    f = K((14, 0), (44, 1))
    a.rot("Body", lambda t: (K((0, 0), (14, 6), (44, -4))(t), 0, -26 * f(t)))
    a.pos("Body", lambda t: (0, -20 * f(t), 0))
    a.rot("Neck", lambda t: (K((0, 0), (14, 14), (44, -10))(t), 0, -8 * f(t)))
    a.rot("Head", lambda t: (K((0, 0), (14, 18), (44, -16), (52, -20))(t), 0, -14 * f(t)))
    a.rot("Jaw", lambda t: (K((0, 0), (10, -30), (30, -20), (52, -8))(t), 0, 0))
    tail(a, lambda t, i: (K((0, 0), (14, -2), (44, 6 + 2 * i))(t), 0, -10 * f(t)))
    arms(a, lambda t, s: (30 * f(t), 0, 0))
    for leg, s in (("Leg_right", 1), ("Leg_left", -1)):
        a.rot(leg, lambda t: (40 * f(t), 0, -26 * f(t)))
        a.rot(SHIN[leg], lambda t: (-70 * f(t), 0, 0))
        a.pos(leg, lambda t, s=s: (-5 * f(t), -16 * f(t), 0))
    AIR["death"] = {leg: [(T(14), T(70))] for leg in LEGS}
    LOCK["death"] = [(T(42), T(70))]
    FREE.add("death")
    A.append(a)

    # ------------------------------------------------------------------ victory: 1.5 s (30 ticks)
    # Head up with a short growl (0-16), stamps its right foot at 20, back to its predator stance.
    a = Anim("victory", 30)
    a.rot("Head", lambda t: (14 * bump(0, T(16), t), 0, 0))
    a.rot("Neck", lambda t: (8 * bump(0, T(16), t), 0, 0))
    a.rot("Jaw", lambda t: (-16 * bump(T(2), T(14), t), 0, 0))
    a.rot("Leg_right", lambda t: (8 * bump(T(14), T(20), t), 0, 0))
    a.rot("Shin_right", lambda t: (-18 * bump(T(14), T(20), t), 0, 0))
    a.pos("Leg_right", lambda t: (0, 2.5 * bump(T(14), T(20), t), 0))
    a.pos("Body", lambda t: (0, -0.8 * bump(T(20), T(26), t), 0))
    AIR["victory"] = {"Leg_right": [(T(15), T(19.5))]}
    A.append(a)

    return A, AIR, LOCK, FREE


# =========================================================================== settling
def _leg_models(model):
    """For each leg: a model holding only that leg (thigh and shin bones), to pose it quickly."""
    out = {}

    def find(gr, name):
        for ch in gr.get("children", []):
            if isinstance(ch, dict):
                if ch["name"] == name:
                    return ch
                f = find(ch, name)
                if f is not None:
                    return f
        return None
    for leg in LEGS:
        g = find({"children": model["outliner"]}, leg)
        ids = set()

        def collect(gr):
            for ch in gr["children"]:
                if isinstance(ch, dict):
                    collect(ch)
                else:
                    ids.add(ch)
        collect(g)
        out[leg] = {"elements": [e for e in model["elements"] if e["uuid"] in ids],
                    "outliner": [{"name": "Root", "origin": [0, 0, 0], "children": [g]}]}
    return out


def _low(leg_model, values, tick):
    _, pts = H.pose(leg_model, values, tick)
    return min(p[1] for ps in pts.values() for p in ps)


def settle(model, anim, values, air, lock, follow, legs_cache):
    n = anim.ticks + 1
    inside = lambda wins, t: any(a - 1e-6 <= t <= b + 1e-6 for a, b in wins)                # noqa: E731
    body = values.setdefault("Body", {})
    body.setdefault("position", [(0, 0, 0)] * n)
    # the hips stay attached: the legs move with the body
    if follow:
        for leg in LEGS:
            v = values.setdefault(leg, {})
            v.setdefault("position", [(0, 0, 0)] * n)
            v["position"] = [tuple(a + b for a, b in zip(p, q)) for p, q in zip(v["position"], body["position"])]
    # the body (all but the legs) never under the ground, on it while locked
    for tick in range(n):
        _, pts = H.pose(model, values, tick)
        low = min(p[1] for b, ps in pts.items() if b not in LEGS and b not in SHIN.values() for p in ps)
        dy = -low if inside(lock, T(tick)) or low < 0 else 0.0
        if dy:
            x, y, z = body["position"][tick]
            body["position"][tick] = (x, y + dy, z)
    # each leg: bend (thigh +f, shin -2f) until its foot is on the ground (planted) or above it (in the air)
    for leg in LEGS:
        shin = SHIN[leg]
        lv = values.setdefault(leg, {})
        sv = values.setdefault(shin, {})
        lv.setdefault("rotation", [(0, 0, 0)] * n)
        sv.setdefault("rotation", [(0, 0, 0)] * n)
        lm = legs_cache[leg]
        wins = air.get(leg, [])
        for tick in range(n):
            planted = not inside(wins, T(tick))
            base_l, base_s = lv["rotation"][tick], sv["rotation"][tick]

            def low_at(fl):
                tmp = {leg: dict(lv), shin: dict(sv)}
                tmp[leg]["rotation"] = list(lv["rotation"])
                tmp[shin]["rotation"] = list(sv["rotation"])
                tmp[leg]["rotation"][tick] = (base_l[0] + fl, base_l[1], base_l[2])
                tmp[shin]["rotation"][tick] = (base_s[0] - 2 * fl, base_s[1], base_s[2])
                return _low(lm, tmp, tick)
            l0 = low_at(0.0)
            if not planted and l0 >= 0:
                continue
            lo_f, hi_f = FLEX
            if low_at(lo_f) > 0:
                fl = lo_f
            elif low_at(hi_f) < 0:
                fl = hi_f
            else:
                for _ in range(14):
                    mid = (lo_f + hi_f) / 2
                    if low_at(mid) < 0:
                        lo_f = mid
                    else:
                        hi_f = mid
                fl = (lo_f + hi_f) / 2
            lv["rotation"][tick] = (base_l[0] + fl, base_l[1], base_l[2])
            sv["rotation"][tick] = (base_s[0] - 2 * fl, base_s[1], base_s[2])
    if anim.loop:
        for bone, chans in values.items():
            for ch, vs in chans.items():
                vs[-1] = vs[0]


# =========================================================================== writing
def build(model, uid):
    """Blockbench animations of the model (sampled every tick), settled."""
    anims, air, lock, free = animations()
    cache = _leg_models(model)
    out, n = [], [0]
    for a in anims:
        values = a.sample()
        settle(model, a, values, air.get(a.name, {}), lock.get(a.name, []), a.name not in free, cache)
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
                                   "interpolation": "linear"})
            animators[uid("group/" + bone)] = {"name": bone, "type": "bone", "keyframes": frames}
        out.append({"uuid": uid("anim/" + a.name), "name": a.name, "loop": "loop" if a.loop else "once",
                    "override": False, "length": a.length, "snapping": 20, "selected": False, "anim_time_update": "",
                    "blend_weight": "", "start_delay": "", "loop_delay": "", "animators": animators,
                    "_values": values})
    return out


def strips(model, anims, atlases, sizes, folder, count=10, only=None):
    """One PNG per animation (profile above, three quarters below), as the hamster's."""
    H.hidden_at = lambda name, tick: ()
    H.strips(model, anims, atlases, sizes, folder, count=count, only=only)


def measure(model, anims, bone="Head"):
    """Per animation and tick: lowest point, and the front of the snout (blocks, model frame)."""
    res = {}
    for a in anims:
        rows = []
        for tick in range(int(round(a["length"] * 20)) + 1):
            _, pts = H.pose(model, a["_values"], tick)
            allp = [p for ps in pts.values() for p in ps]
            hd = pts.get(bone, [])
            front = min(hd, key=lambda p: p[2]) if hd else (0, 0, 0)
            rows.append({"tick": tick, "low": round(min(p[1] for p in allp) / 16, 2),
                         "snout": [round(v / 16, 2) for v in front]})
        res[a["name"]] = rows
    return res


finish = H.finish
uid_for = H.uid_for

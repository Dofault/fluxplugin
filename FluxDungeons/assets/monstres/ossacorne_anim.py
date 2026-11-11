#!/usr/bin/env python3
"""Animations of the Ossacorne (2026-11-11): awakening (cinematic), idle, walk, four attacks and their small extras.

Every timing that matters to the game (impacts, throws, sweep angles, vulnerable windows) is written in
docs/technique/boss/ossacorne.md and read by FluxDungeons (mob/BonePowers.java, spells/defenseur/*.yml): keep the
three in step. Ticks are 1/20 s; every key moment falls on a tick.

Values as in taupe_champs_anim.py: rotation in degrees in the model's axes (x > 0 lifts the front of a bone, so a
negative x opens the jaw and lowers the head, and lowers the tip of a tail bone when positive; y > 0 turns the front
of a bone to the monster's left (-x), which swings a tail bone's tip to its right (+x); z > 0 lifts its right side),
position in pixels (16 = 1 block). Written in the Blockbench / BetterModel convention by build().

Legs are children of Root, not of the body: each leg is solved every tick (two-bone inverse kinematics in the side
plane) so that its top follows the shoulder or hip wherever the body goes while its foot stays where the animation
puts it. Planted feet never slide, the body really weighs on the joints.
"""
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import paths  # noqa: F401,E402
from hamster_moissonneur_anim import Anim as _Anim, T, _app, _mul, _rot, bump, keys, pose, smooth  # noqa: E402


class Anim(_Anim):
    """Positions given in pixels of the full-size drawing, scaled to the delivered model (K)."""

    def pos(self, bone, f):
        return self.set(bone, "position", lambda t: tuple(v * K for v in f(t)))

TAU = 2 * math.pi
K = 1.0                            # size of the delivered model against the drawing (set by build): every position
#                                    written below in pixels of the full-size drawing is multiplied by it
WALK_SPEED = 2.2                   # blocks per second of the walk (walk-speed of the monster's file)
S, C = math.sin, math.cos
lin = "linear"
SIDES = ("right", "left")
LEGS = tuple(f"{k}_{s}" for k in ("front", "back") for s in SIDES)
TAILS = tuple(f"Tail_{i}" for i in range(1, 13))
PLATES = tuple(f"Plate_{i}" for i in range(1, 9))
# Share of a whole tail swing taken by each vertebra (small at the base, even towards the tip).
TAIL_W = [0.5, 0.6, 0.7, 0.8, 0.9, 1, 1, 1, 1, 1, 1, 1]
TAIL_W = [w / sum(TAIL_W) for w in TAIL_W]
# Plates: the big ones above the shoulders move more than the small ones at the back.
PLATE_AMP = [1.0, 1.0, 0.9, 0.8, 0.65, 0.5, 0.4, 0.35]

# Key ticks read by the game (BonePowers): keep in step with docs/technique/boss/ossacorne.md.
EVENTS = {
    "awaken": {"rumble": 0, "jaw_twitch": 18, "skull_rises": 30, "legs_unfold": 60, "roar": 122, "settled": 190},
    "charge": {"lock": 12, "launch": 20, "run_end": 36, "impact": 36, "vulnerable": 42},
    "tail_sweep": {"hips_turn": 16, "sweep_start": 22, "sweep_end": 32, "vulnerable": 40},
    "bone_volley": {"fragment_1": 6, "fragment_2": 9, "fragment_3": 12, "lock": 15, "throw_1": 20, "throw_2": 24,
                    "throw_3": 28},
    "ossuary": {"slam_front": 40, "slam_back": 42, "spikes_1": 44, "spikes_2": 48, "spikes_3": 52, "roar": 62,
                "wave": 64, "vulnerable": 80},
}


def shake(t, amp, freq=11.0):
    """Deterministic tremor (no randomness: the same every time)."""
    return amp * (S(TAU * freq * t) * 0.6 + S(TAU * freq * 1.7 * t + 1.3) * 0.4)


def pulse(u, amp, rate=9.0, freq=9.0):
    """Damped vibration started at u = 0 (u in seconds since the impact)."""
    return 0.0 if u < 0 else amp * math.exp(-rate * u) * S(TAU * freq * u)


# =========================================================================== rig: bones and leg solver
class Rig:
    """Pivots of the delivered model, and the world position of the leg joints under the body's tracks."""

    def __init__(self, model):
        self.o = {}

        def walk(g):
            for c in g.get("children", []):
                if isinstance(c, dict):
                    self.o[c["name"]] = list(c["origin"])
                    walk(c)
        walk(model["outliner"][0])
        self.legs = {}
        for leg in LEGS:
            kind, side = leg.split("_")
            top, mid, low = self.o[f"Leg_{leg}"], self.o[f"Shin_{leg}"], self.o[f"Foot_{leg}"]
            a = math.hypot(mid[1] - top[1], mid[2] - top[2])
            b = math.hypot(low[1] - mid[1], low[2] - mid[2])
            bend = (mid[2] - top[2]) * (low[1] - mid[1]) - (mid[1] - top[1]) * (low[2] - mid[2])
            self.legs[leg] = {"top": top, "mid": mid, "low": low, "a": a, "b": b, "bend": 1 if bend > 0 else -1,
                              "parent": "Chest" if kind == "front" else "Hips", "h": top[1] - low[1]}

    def carried(self, values, t, parent, p):
        """World position at time t of point p carried by Body then `parent` (Chest or Hips)."""
        def apply(bone, q):
            v = values.get(bone, {})
            r = v["rotation"](t) if "rotation" in v else (0, 0, 0)
            m = v["position"](t) if "position" in v else (0, 0, 0)
            o = self.o[bone]
            d = _app(_rot(*r), [q[i] - o[i] for i in range(3)])
            return [d[i] + o[i] + m[i] for i in range(3)]
        q = apply(parent, p)
        return apply("Body", q)


def arg(y, z):
    return math.degrees(math.atan2(y, z))


def solve_leg(rig, leg, top, foot, splay=0.0, toe=0.0):
    """Rotations (and the top's shift) that put the top joint at `top` and the low joint at `foot` (world, side
    plane), the foot keeping its rest orientation plus `toe`; `splay` turns the whole leg outwards (degrees)."""
    L = rig.legs[leg]
    a, b = L["a"], L["b"]
    t0, m0, w0 = L["top"], L["mid"], L["low"]
    dy, dz = foot[1] - top[1], foot[2] - top[2]
    d = max(abs(a - b) + 0.2, min(a + b - 0.05, math.hypot(dy, dz)))
    base = math.atan2(dy, dz)
    k = max(-1.0, min(1.0, (a * a + d * d - b * b) / (2 * a * d)))
    best = None
    for sgn in (1, -1):
        ang = base + sgn * math.acos(k)
        my, mz = top[1] + a * math.sin(ang), top[2] + a * math.cos(ang)
        ey, ez = top[1] + d * math.sin(base), top[2] + d * math.cos(base)
        bend = (mz - top[2]) * (ey - my) - (my - top[1]) * (ez - mz)
        if (1 if bend > 0 else -1) == L["bend"]:
            best = (my, mz, ey, ez)
    my, mz, ey, ez = best
    a1 = arg(my - top[1], mz - top[2]) - arg(m0[1] - t0[1], m0[2] - t0[2])
    a2 = arg(ey - my, ez - mz) - arg(w0[1] - m0[1], w0[2] - m0[2])
    leg_x = -a1
    shin_x = -a2 - leg_x
    foot_x = toe - leg_x - shin_x
    s = 1 if leg.endswith("right") else -1
    dx = top[0] - t0[0]
    lean = -math.degrees(math.asin(max(-1, min(1, dx / max(4.0, L["h"])))))
    leg_z = lean + splay * s
    return {"pos": (dx, top[1] - t0[1], top[2] - t0[2]), "leg": (leg_x, 0, leg_z), "shin": (shin_x, 0, 0),
            "foot": (foot_x, 0, -leg_z)}


def legs(anim, rig, foot=None, splay=None, toe=None):
    """Leg tracks of an animation: `foot(leg, t)` = (dx, dy, dz) of the low joint from rest (planted = (0, 0, 0)),
    `splay(leg, t)` outwards turn, `toe(leg, t)` foot tilt. Solved once per tick, after the body's tracks."""
    foot = foot or (lambda leg, t: (0, 0, 0))
    splay = splay or (lambda leg, t: 0.0)
    toe = toe or (lambda leg, t: 0.0)
    cache = {}

    def sol(leg, t):
        key = (leg, round(t * 20))
        if key not in cache:
            L = rig.legs[leg]
            top = rig.carried(anim.tracks, t, L["parent"], L["top"])
            off = foot(leg, t)
            w = [L["low"][i] + off[i] * K for i in range(3)]
            cache[key] = solve_leg(rig, leg, top, w, splay(leg, t), toe(leg, t))
        return cache[key]
    for leg in LEGS:
        anim.set(f"Leg_{leg}", "position", lambda t, leg=leg: sol(leg, t)["pos"])     # world units already
        anim.rot(f"Leg_{leg}", lambda t, leg=leg: sol(leg, t)["leg"])
        anim.rot(f"Shin_{leg}", lambda t, leg=leg: sol(leg, t)["shin"])
        anim.rot(f"Foot_{leg}", lambda t, leg=leg: sol(leg, t)["foot"])


def tail(anim, yaw=None, pitch=None, delay=0.05):
    """Tail tracks: yaw(i, t) and pitch(i, t) local angles of vertebra i (0 = base); every vertebra reads the curve
    `delay` seconds after the previous one (the movement runs down the tail)."""
    for i, bone in enumerate(TAILS):
        anim.rot(bone, lambda t, i=i: (pitch(i, t - i * delay) if pitch else 0.0, yaw(i, t - i * delay) if yaw else 0.0, 0.0))


def plates(anim, f):
    """Plate tracks: f(k, t) -> (x, y, z) for plate k (0 = front)."""
    for k, bone in enumerate(PLATES):
        anim.rot(bone, lambda t, k=k: f(k, t))


def idle_tail(i, t):
    """Lateral wave of the idle (4 s): left at 0, back through the middle, the tip past the right at 2 s."""
    return -26 * 1.2 * TAIL_W[i] * C(TAU * t / 4)


def idle_tail_pitch(i, t):
    return 0.6 * S(TAU * t / 2)


# =========================================================================== animations
def animations(rig):
    A = []

    # ------------------------------------------------------------------ idle: 4 s loop
    # Nothing breathes in a skeleton: the rib cage rises and falls by 1 degree, the skull looks left (0.8 s), back,
    # down (2.4 s), up a little (3.2 s), the neck follows it 0.2 s late; the jaw parts 2.5 degrees at 2.6-3.2 s; the
    # tail waves all the time (left at 0, tip past the right at 2 s, 19 to 22 degrees at the tip), plates tremble
    # 0.6 to 1.6 degrees, the weight moves from one side to the other.
    a = Anim("idle", 80, loop=True)
    head_y = lambda t: keys(t % 4, ((0, 0), (0.8, 4), (1.6, 0), (4, 0)))                      # noqa: E731
    head_x = lambda t: keys(t % 4, ((0, 0), (1.6, 0), (2.4, -3), (3.2, 1), (4, 0)))           # noqa: E731
    a.rot("Chest", lambda t: (1.0 * S(TAU * t / 4), 0, 0))
    a.pos("Chest", lambda t: (0, 0.25 * (1 - C(TAU * t / 4)), 0))
    a.rot("Body", lambda t: (0, 0, 0.7 * S(TAU * t / 4)))
    a.rot("Neck", lambda t: (0.4 * head_x(t - 0.2), 0.4 * head_y(t - 0.2), 0))
    a.rot("Head", lambda t: (0.7 * head_x(t), 0.7 * head_y(t), 0))
    a.rot("Jaw", lambda t: (-2.5 * bump(2.6, 3.2, t), 0, 0))
    tail(a, yaw=idle_tail, pitch=idle_tail_pitch)
    plates(a, lambda k, t: (0.6 * PLATE_AMP[k] * S(TAU * t / 2 + k * 0.8), 0, 1.6 * PLATE_AMP[k] * S(TAU * t / 1 + k * 0.7)))
    legs(a, rig)
    A.append(a)

    # ------------------------------------------------------------------ idle extras (played now and then by the game)
    # idle_snap 1.5 s: the jaw opens 7 degrees (0-8), snaps shut at 10 (overshoot, bone on bone), head jerks.
    a = Anim("idle_snap", 30)
    a.rot("Jaw", lambda t: (keys(t, ((0, 0), (T(8), -7), (T(10), 0.8), (T(14), 0), (T(30), 0))), 0, 0))
    a.rot("Head", lambda t: (2 * bump(T(9), T(16), t), 0, 0))
    a.rot("Neck", lambda t: (1 * bump(T(10), T(18), t), 0, 0))
    tail(a, yaw=lambda i, t: idle_tail(i, 0) * (1 - 0.0 * t))
    legs(a, rig)
    A.append(a)
    # idle_sniff 2 s: neck stretches forward and turns to its left 10 degrees, three quick sniffs (head pitch), back.
    a = Anim("idle_sniff", 40)
    env = lambda t: smooth(0, T(8), t) * (1 - smooth(T(30), T(40), t))                             # noqa: E731
    a.rot("Neck", lambda t: (4 * env(t), 4 * env(t), 0))
    a.rot("Head", lambda t: (-2 * env(t) + 2.5 * max(0, S(TAU * t / 0.3)) * bump(T(10), T(28), t), 6 * env(t), 2 * env(t)))
    a.rot("Jaw", lambda t: (-1.5 * env(t), 0, 0))
    tail(a, yaw=lambda i, t: idle_tail(i, 0) + 10 * TAIL_W[i] * env(t))
    legs(a, rig)
    A.append(a)
    # idle_shift 2 s: the weight goes to the left legs, the front right foot lifts and is set down 2 pixels further
    # forward then back (claws curl at 10-16), the hind left claws scrape (22-30).
    a = Anim("idle_shift", 40)
    a.rot("Body", lambda t: (0, 0, -2 * bump(0, T(40), t)))
    a.pos("Body", lambda t: (-0.6 * bump(0, T(40), t), 0, 0))

    def shift_foot(leg, t):
        if leg == "front_right":
            return (0, 3 * bump(T(6), T(16), t), -2 * bump(T(6), T(34), t))
        return (0, 0, 0)

    def shift_toe(leg, t):
        if leg == "front_right":
            return 14 * bump(T(8), T(18), t)
        if leg == "back_left":
            return 8 * bump(T(22), T(30), t)
        return 0.0
    tail(a, yaw=lambda i, t: idle_tail(i, 0) - 6 * TAIL_W[i] * bump(0, T(40), t))
    legs(a, rig, foot=shift_foot, toe=shift_toe)
    A.append(a)

    # ------------------------------------------------------------------ walk: 0.8 s loop, WALK_SPEED blocks per second
    # A predator's lope (faster since the 2026-11-11 play test): diagonal pairs (front left with hind right, front
    # right with hind left), the hind foot of each pair a little later than the front one: shoulders and pelvis never
    # move together. Feet planted 60 % of the cycle, lifted 5 pixels, and they slide back exactly as fast as it walks
    # (stride from WALK_SPEED); the body dips at each front impact, rolls 2 degrees, the skull dips after the impacts,
    # the tail swings against the body, plates shake at each impact.
    L = 0.8
    a = Anim("walk", 16, loop=True)
    duty, lift = 0.6, 5.0
    stride = WALK_SPEED * 16 * duty * L / 2 / K          # half stride, in pixels of the drawing
    offs = {"front_left": 0.0, "back_right": 0.06, "front_right": 0.5, "back_left": 0.56}

    def phase(leg, t):
        return (t / L + offs[leg]) % 1.0

    def walk_foot(leg, t):
        u = phase(leg, t)
        if u < duty:
            return (0, 0, -stride + 2 * stride * u / duty)
        v = (u - duty) / (1 - duty)
        return (0, lift * S(math.pi * v), stride - 2 * stride * smooth(0, 1, v))

    def walk_toe(leg, t):
        u = phase(leg, t)
        if u < duty:
            return 0.0
        return -16 * S(math.pi * (u - duty) / (1 - duty))
    a.pos("Body", lambda t: (0, -1.2 * (0.5 + 0.5 * C(TAU * 2 * t / L)), 0))
    a.rot("Body", lambda t: (-2.5, 0, 0))
    a.rot("Chest", lambda t: (0, 1.5 * S(TAU * t / L), 2.0 * S(TAU * t / L)))
    a.rot("Hips", lambda t: (0, -1.2 * S(TAU * t / L - 0.7), -2.0 * S(TAU * t / L - 0.7)))
    a.rot("Neck", lambda t: (-0.8 * (0.5 + 0.5 * C(TAU * 2 * (t - 0.08) / L)), -1.0 * S(TAU * (t - 0.08) / L), 0))
    a.rot("Head", lambda t: (-1.5 * (0.5 + 0.5 * C(TAU * 2 * t / L - 0.5)), -1.0 * S(TAU * t / L), 0))
    a.rot("Jaw", lambda t: (-1.0 * (0.5 + 0.5 * C(TAU * 2 * t / L)), 0, 0))
    tail(a, yaw=lambda i, t: -12 * TAIL_W[i] * S(TAU * t / L), pitch=lambda i, t: 0.8 * S(TAU * 2 * t / L), delay=0.04)
    plates(a, lambda k, t: (pulse((t % (L / 2)) - 0.02 * k, 2.0 * PLATE_AMP[k]), 0,
                            pulse((t % (L / 2)) - 0.02 * k, 1.0 * PLATE_AMP[k], freq=7)))
    legs(a, rig, foot=walk_foot, toe=walk_toe)
    A.append(a)

    # ------------------------------------------------------------------ charge: 2.7 s (Charge funeraire)
    # 0-14 the skull drops 22 degrees (horns forward), the jaw parts, the body sinks 3 pixels, the hind feet step 3
    # pixels back, the tail lifts and straightens, plates lean back 6 degrees. 14-20 the hind legs push: the body
    # lurches forward, the front feet leave the ground. 20-36 the rush (the game moves it 0.6 block a tick and steers
    # it towards its target): fast gallop, head kept low, tail swinging, plates shaking. 36-44 stop and impact: the
    # skull jolts (horns shake), the front feet brace, the tail overshoots. 44-54 back to the guard (vulnerable from 42).
    a = Anim("charge", 54)
    hold = lambda t: smooth(0, T(14), t) * (1 - smooth(T(42), T(54), t))                         # noqa: E731
    run = lambda t: smooth(T(18), T(21), t) * (1 - smooth(T(34), T(37), t))                     # noqa: E731
    a.pos("Body", lambda t: (0, -3 * hold(t) + 2 * bump(T(14), T(22), t) + 0.8 * abs(S(TAU * t / 0.3)) * run(t)
                             - 1.2 * bump(T(36), T(44), t), -3 * bump(T(14), T(22), t) + 2 * bump(T(36), T(44), t)))
    a.rot("Body", lambda t: (-4 * hold(t) + 3 * bump(T(14), T(20), t) - 2 * bump(T(36), T(42), t), 0, 1.5 * S(TAU * t / 0.3) * run(t)))
    a.rot("Neck", lambda t: (-6 * hold(t) + 2 * bump(T(38), T(46), t), 0, 0))
    a.rot("Head", lambda t: (-16 * hold(t) + 7 * bump(T(36), T(42), t) - 2 * bump(T(41), T(48), t),
                             3 * S(TAU * t / 0.12) * bump(T(36), T(44), t), 0))
    a.rot("Jaw", lambda t: (-6 * hold(t) - 3 * bump(T(36), T(44), t), 0, 0))
    a.rot("Horn_right", lambda t: (shake(t, 1.2, 17) * bump(T(36), T(44), t), 0, 0))
    a.rot("Horn_left", lambda t: (shake(t + 0.05, 1.2, 17) * bump(T(36), T(44), t), 0, 0))
    tail(a, yaw=lambda i, t: 22 * TAIL_W[i] * S(TAU * t / 0.6) * run(t) + idle_tail(i, 0) * (1 - hold(t)),
         pitch=lambda i, t: (-6 if i == 0 else 3.5 if i >= 5 else 0) * hold(t) + 3 * TAIL_W[i] * 12 * bump(T(38), T(50), t) * 0.25,
         delay=0.04)
    plates(a, lambda k, t: (6 * PLATE_AMP[k] * hold(t) + shake(t + k * 0.03, 1.6 * PLATE_AMP[k]) * bump(T(14), T(40), t), 0,
                            shake(t + k * 0.05, 1.0, 9) * bump(T(14), T(40), t)))

    def charge_foot(leg, t):
        if leg.startswith("back"):
            back = 3 * hold(t)
            g = TAU * (t / 0.3 + (0.15 if leg.endswith("right") else 0.65))
            return (0, 2.5 * max(0, S(g)) * run(t), back + 6 * C(g) * run(t))
        lift_off = 2.5 * bump(T(15), T(22), t)
        g = TAU * (t / 0.3 + (0 if leg.endswith("right") else 0.5))
        brace = -3 * bump(T(36), T(46), t)
        return (0, lift_off + 3 * max(0, S(g)) * run(t), -6 * C(g) * run(t) + brace)
    legs(a, rig, foot=charge_foot, toe=lambda leg, t: -12 * bump(T(15), T(22), t) if leg.startswith("front") else 0.0)
    A.append(a)

    # ------------------------------------------------------------------ charge_stun: 2 s (rammed a wall: vulnerable)
    # The skull recoils 9 degrees (0-8), the jaw drops 12 degrees, the plates rattle, the head sways, dazed.
    a = Anim("charge_stun", 40)
    a.rot("Head", lambda t: (9 * bump(0, T(10), t) + 2 * S(TAU * t / 0.8) * bump(T(6), T(36), t),
                             7 * S(TAU * t / 1.1) * bump(T(6), T(38), t), 3 * S(TAU * t / 0.9) * bump(T(6), T(36), t)))
    a.rot("Neck", lambda t: (4 * bump(T(1), T(12), t), 2 * S(TAU * (t - 0.1) / 1.1) * bump(T(6), T(38), t), 0))
    a.rot("Jaw", lambda t: (-12 * bump(0, T(24), t), 0, 0))
    a.pos("Body", lambda t: (0, -1 * bump(0, T(8), t), 2 * bump(0, T(8), t)))
    plates(a, lambda k, t: (shake(t + k * 0.04, 3 * PLATE_AMP[k], 13) * (1 - smooth(0, T(24), t)), 0, 0))
    tail(a, yaw=lambda i, t: idle_tail(i, 0) + 10 * TAIL_W[i] * S(TAU * t / 1.0) * bump(0, T(40), t))
    legs(a, rig, foot=lambda leg, t: (0, 0, -2 * bump(0, T(14), t)) if leg.startswith("front") else (0, 0, 0))
    A.append(a)

    # ------------------------------------------------------------------ tail_sweep: 2.8 s (Balayage des vertebres)
    # 0-16 anticipation: feet planted, pelvis turns 14 degrees to its left, the tail coils to its left (tip folded),
    # skull turned to its right (where the sweep goes), plates tremble. 16-22 the pelvis swings back, the base of the
    # tail goes first. 22-32 the sweep: the tail runs from its left to its right (about 120 degrees with the pelvis),
    # lowered to the players' height, every vertebra a little after the previous one. 32-40 overshoot, the pelvis
    # compensates, the skull comes back. 40-56 slow return (vulnerable).
    a = Anim("tail_sweep", 56)
    hips = lambda t: keys(t, ((0, 0), (T(16), -14), (T(20), -12), (T(26), 18), (T(34), 22), (T(42), 10), (T(56), 0)))   # noqa: E731
    whip = lambda t: keys(t, ((0, 0), (T(16), -36), (T(22), -40), (T(30), 46), (T(34), 52), (T(42), 40), (T(56), 0)))   # noqa: E731
    low = lambda t: smooth(T(12), T(20), t) * (1 - smooth(T(36), T(52), t))                    # noqa: E731
    a.rot("Hips", lambda t: (0, hips(t), 0))
    a.rot("Chest", lambda t: (0, -0.35 * hips(t), 0))
    a.pos("Body", lambda t: (0, -1.5 * smooth(0, T(10), t) * (1 - smooth(T(40), T(56), t)), 0))
    a.rot("Neck", lambda t: (0, keys(t, ((0, 0), (T(14), -6), (T(28), -6), (T(46), 0))), 0))
    a.rot("Head", lambda t: (-2 * low(t), keys(t, ((0, 0), (T(14), -10), (T(28), -12), (T(48), 0))), 0))
    a.rot("Jaw", lambda t: (-4 * bump(T(10), T(40), t), 0, 0))
    curl = lambda i, t: (-7 if i >= 8 else 0) * bump(T(4), T(24), t)                           # noqa: E731
    tail(a, yaw=lambda i, t: TAIL_W[i] * whip(t) + curl(i, t) + idle_tail(i, 0) * (1 - smooth(0, T(16), t)),
         pitch=lambda i, t: (6 if i == 0 else 4 if i >= 5 else 1) * low(t), delay=0.0175)
    plates(a, lambda k, t: (shake(t + k * 0.04, 1.4 * PLATE_AMP[k]) * bump(0, T(18), t)
                            + pulse(t - T(30), 2.5 * PLATE_AMP[k]), 0, 0))
    legs(a, rig, splay=lambda leg, t: 3 * smooth(0, T(10), t) * (1 - smooth(T(40), T(56), t)) if leg.startswith("front") else 0.0)
    A.append(a)

    # ------------------------------------------------------------------ bone_volley: 2.2 s (Traque des ossements)
    # Quick since the 2026-11-11 play test. 0-6 the skull rises 7 degrees towards the far target, the jaw opens 8,
    # the tail lifts, the last three plates tremble. 6, 9, 12: a fragment tears off plate 8, 7, then 6 (each plate
    # kicks back 7 degrees; the game shows the fragment). 15-20 lock: jaw at 14 degrees. 20, 24, 28: the three throws
    # (the skull jerks forward 4 degrees, the jaw snaps). 32-44 back to rest.
    a = Anim("bone_volley", 44)
    throws = (T(20), T(24), T(28))
    jerk = lambda t: sum(bump(x - T(1), x + T(4), t) for x in throws)                            # noqa: E731
    a.rot("Neck", lambda t: (keys(t, ((0, 0), (T(6), 3), (T(32), 3), (T(42), 0))), 0, 0))
    a.rot("Head", lambda t: (keys(t, ((0, 0), (T(6), 7), (T(15), 6), (T(19), 4), (T(32), 4), (T(42), 0))) - 4 * jerk(t), 0, 0))
    a.rot("Jaw", lambda t: (keys(t, ((0, 0), (T(6), -8), (T(15), -8), (T(19), -14), (T(32), -12), (T(40), 0))) + 4 * jerk(t), 0, 0))
    a.rot("Body", lambda t: (2 * smooth(0, T(6), t) * (1 - smooth(T(32), T(42), t)), 0, 0))
    tail(a, yaw=lambda i, t: idle_tail(i, 0) * (1 - bump(0, T(44), t)) + 4 * TAIL_W[i] * S(TAU * t / 1.1),
         pitch=lambda i, t: (-5 if i == 0 else 0) * smooth(0, T(6), t) * (1 - smooth(T(32), T(42), t)))
    tear = {7: T(6), 6: T(9), 5: T(12)}

    def volley_plate(k, t):
        if k < 5:
            return (shake(t + k * 0.05, 0.6 * PLATE_AMP[k]) * bump(0, T(32), t), 0, 0)
        grow = smooth(0, tear[k], t)
        return (shake(t + k * 0.07, 3.0 * grow, 14) * (1 - smooth(tear[k], tear[k] + T(3), t)) - 7 * bump(tear[k] - T(1), tear[k] + T(6), t),
                0, shake(t + k * 0.03, 1.5 * grow, 12) * (1 - smooth(tear[k], tear[k] + T(3), t)))
    plates(a, volley_plate)
    legs(a, rig)
    A.append(a)

    # ------------------------------------------------------------------ ossuary: 6 s (ultimate: Regne du fossoyeur)
    # 0-24 legs spread, the body drops 5 pixels, skull almost on the ground, jaw wide, tail rising behind, plates lean
    # back. 24-40 it trembles harder and harder, the rib cage lifts, plates rattle, the tail stiffens upwards.
    # 36-42 it rises 2 pixels and the four feet strike the ground: front at 40, hind at 42 (the game raises the bone
    # spikes in three groups at 44, 48 and 52). 60-72 the skull snaps up 28 degrees, the roar (62), the tail cracks
    # upwards: the shockwave leaves at 64. 80-120 it stays low, then everything returns (vulnerable from 80).
    a = Anim("ossuary", 120)
    p1 = lambda t: smooth(0, T(24), t) * (1 - smooth(T(80), T(116), t))                        # noqa: E731
    grow = lambda t: smooth(T(24), T(40), t) * (1 - smooth(T(40), T(60), t))                   # noqa: E731
    roar = lambda t: smooth(T(60), T(64), t) * (1 - smooth(T(74), T(92), t))                   # noqa: E731
    slam = lambda t: 2 * bump(T(34), T(40), t) - 3 * bump(T(40), T(52), t)                    # noqa: E731
    a.pos("Body", lambda t: (0, -5 * p1(t) + slam(t), 0))
    a.rot("Body", lambda t: (-3 * p1(t) + 2 * roar(t), 0, shake(t, 1.6) * grow(t)))
    a.rot("Chest", lambda t: (3 * grow(t) + 2 * roar(t) + shake(t + 0.1, 1.0, 13) * grow(t), 0, 0))
    a.rot("Neck", lambda t: (-6 * p1(t) + 14 * roar(t), 0, 0))
    a.rot("Head", lambda t: (-10 * p1(t) - 2 * bump(T(40), T(48), t) + 24 * roar(t), shake(t, 1.0) * grow(t), 0))
    a.rot("Jaw", lambda t: (-18 * p1(t) - 12 * roar(t), 0, 0))
    snap = lambda i, t: bump(T(62), T(72), t)                                                   # noqa: E731
    tail(a, yaw=lambda i, t: idle_tail(i, 0) * (1 - p1(t)) + shake(t + i * 0.02, 1.5) * grow(t),
         pitch=lambda i, t: (-10 if i == 0 else -2) * p1(t) - (4 if i == 0 else 1) * grow(t) - (12 if i == 0 else 3) * snap(i, t),
         delay=0.025)
    plates(a, lambda k, t: (8 * PLATE_AMP[k] * p1(t) + shake(t + k * 0.05, 4 * PLATE_AMP[k], 13) * grow(t)
                            + pulse(t - T(40), 3 * PLATE_AMP[k]) + pulse(t - T(64), 2.5 * PLATE_AMP[k]), 0,
                            shake(t + k * 0.03, 2 * PLATE_AMP[k], 11) * grow(t)))

    def ult_foot(leg, t):
        front = leg.startswith("front")
        up = 4 * bump(T(34), T(40), t) if front else 3 * bump(T(35), T(42), t)
        return (0, up, (-2 if front else 2) * p1(t))
    legs(a, rig, foot=ult_foot, splay=lambda leg, t: (6 if leg.startswith("front") else 4) * p1(t))
    A.append(a)

    # ------------------------------------------------------------------ attack: 0.8 s (melee, played by the game)
    # The skull drops and the jaw opens (0-5), the horns jab forward with a bite at 7 (the hit lands then), recoil.
    a = Anim("attack", 16)
    a.rot("Neck", lambda t: (keys(t, ((0, 0), (T(5), -4), (T(7), 3), (T(16), 0))), 0, 0))
    a.rot("Head", lambda t: (keys(t, ((0, 0), (T(5), -9), (T(7), 2), (T(10), 1), (T(16), 0))), 0, 0))
    a.rot("Jaw", lambda t: (keys(t, ((0, 0), (T(5), -14), (T(7), 0.5), (T(16), 0))), 0, 0))
    a.pos("Body", lambda t: (0, -0.8 * bump(0, T(12), t), keys(t, ((0, 0), (T(5), 1.5), (T(7), -2.5), (T(16), 0)))))
    plates(a, lambda k, t: (pulse(t - T(7), 1.8 * PLATE_AMP[k]), 0, 0))
    tail(a, yaw=lambda i, t: idle_tail(i, 0))
    legs(a, rig)
    A.append(a)

    # ------------------------------------------------------------------ hurt: 0.4 s
    a = Anim("hurt", 8)
    a.rot("Head", lambda t: (5 * bump(0, T(8), t), -4 * bump(0, T(8), t), 0))
    a.rot("Jaw", lambda t: (-5 * bump(0, T(6), t), 0, 0))
    a.rot("Body", lambda t: (0, 0, 2 * bump(0, T(8), t)))
    plates(a, lambda k, t: (pulse(t, 2.0 * PLATE_AMP[k], freq=12), 0, 0))
    tail(a, yaw=lambda i, t: idle_tail(i, 0))
    legs(a, rig)
    A.append(a)

    # ------------------------------------------------------------------ awaken: 9.5 s (cinematic)
    # 0-30 lying very low (body 9 pixels down, legs folded, skull on the ground, jaw ajar, tail flat), dust, a tremor
    # runs through it, the jaw twitches at 18. 30-60 the skull rises first, the neck follows, the jaw opens 18 degrees,
    # the head turns 10 degrees to its right (towards the camera). 60-110 the legs unfold, the front first, then the
    # pelvis; plates tremble; the tail rises from its base, vertebra after vertebra. 110-150 legs spread, skull forward
    # (horns to the front), jaw wide (roar at 122), rib cage shaking, plates lean back, a wide sideways swing of the
    # tail. 150-190 the jaw closes, the skull dips then fixes the target, the tail settles: exactly the idle at 0.
    a = Anim("awaken", 190)
    rise_front = lambda t: smooth(T(60), T(92), t)                                              # noqa: E731
    rise_back = lambda t: smooth(T(74), T(108), t)                                              # noqa: E731
    lying = lambda t: 1 - 0.5 * (rise_front(t) + rise_back(t))                                 # noqa: E731
    power = lambda t: smooth(T(110), T(122), t) * (1 - smooth(T(142), T(162), t))              # noqa: E731
    skull = lambda t: smooth(T(30), T(56), t)                                                   # noqa: E731
    a.pos("Body", lambda t: (0, -9 * lying(t), 0))
    a.rot("Body", lambda t: (-3 * lying(t) + 3 * (rise_front(t) - rise_back(t)) + shake(t, 0.5) * bump(T(4), T(30), t), 0, 0))
    a.rot("Chest", lambda t: (shake(t + 0.2, 1.2, 13) * bump(T(112), T(146), t) + 2 * power(t), 0, 0))
    a.rot("Neck", lambda t: (-6 * (1 - smooth(T(34), T(64), t)) - 4 * power(t), -4 * smooth(T(48), T(64), t) * (1 - smooth(T(150), T(180), t)), 0))
    a.rot("Head", lambda t: (-4 * (1 - skull(t)) + 4 * skull(t) * (1 - smooth(T(100), T(120), t)) - 6 * power(t)
                             - 3 * bump(T(158), T(184), t),
                             -6 * smooth(T(46), T(60), t) * (1 - smooth(T(150), T(182), t)), 0))
    a.rot("Jaw", lambda t: (-2.5 * (1 - skull(t)) - 1.5 * bump(T(18), T(22), t) - 18 * bump(T(36), T(110), t) ** 0.5 * (1 - power(t))
                            - 30 * power(t) * bump(T(110), T(150), t) ** 0.3, 0, 0))
    a.rot("Horn_right", lambda t: (0.6 * shake(t, 1, 15) * bump(T(120), T(140), t), 0, 0))
    a.rot("Horn_left", lambda t: (0.6 * shake(t + 0.1, 1, 15) * bump(T(120), T(140), t), 0, 0))
    settle = lambda t: smooth(T(160), T(188), t)                                                # noqa: E731
    sweep = lambda t: 20 * S(TAU * (t - T(118)) / 1.6) * bump(T(118), T(160), t)                 # noqa: E731

    def wake_tail_pitch(i, t):
        up = smooth(T(80) + i * T(2), T(104) + i * T(2), t)
        flat = (8 if i == 0 else 5) * (1 - up)
        return flat + idle_tail_pitch(i, 0) * settle(t)

    def wake_tail_yaw(i, t):
        return TAIL_W[i] * sweep(t) + idle_tail(i, 0) * settle(t) + 4 * TAIL_W[i] * S(TAU * (t - T(164)) / 1.2) * bump(T(162), T(186), t)
    tail(a, yaw=wake_tail_yaw, pitch=wake_tail_pitch, delay=0.0)
    plates(a, lambda k, t: (shake(t + k * 0.05, 1.5 * PLATE_AMP[k], 13) * bump(T(62), T(112), t) + 4 * PLATE_AMP[k] * power(t), 0,
                            shake(t + k * 0.03, 1.0, 11) * bump(T(62), T(112), t)))
    legs(a, rig, splay=lambda leg, t: (4 if leg.startswith("front") else 0) * power(t))
    A.append(a)
    return A


# =========================================================================== writing
def head_clear(model, values, ticks, margin=0.3):
    """The skull and the jaw never go through the ground: on a tick where they would, the neck lifts just enough
    (the head then rests on the ground, as the brief asks: 'presque au niveau du sol')."""
    neck = values.setdefault("Neck", {})
    neck.setdefault("rotation", [(0, 0, 0)] * (ticks + 1))
    for tick in range(ticks + 1):
        for _ in range(4):
            _, pts = pose(model, values, tick)
            low = min(p[1] for b in ("Head", "Jaw", "Horn_right", "Horn_left") for p in pts.get(b, []))
            if low >= margin:
                break
            x, y, z = neck["rotation"][tick]
            neck["rotation"][tick] = (x + math.degrees((margin - low) / (34.0 * K)) * 1.1, y, z)


def build(model, uid, scale=1.0):
    """Blockbench animations of the model, sampled every tick (`scale`: size of the model against the drawing)."""
    global K
    K = scale
    rig = Rig(model)
    out, n = [], [0]
    for a in animations(rig):
        values = a.sample()
        head_clear(model, values, a.ticks)
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
    """Per animation and tick: lowest and highest point (blocks), lowest point of each foot, front of the skull."""
    res = {}
    for a in anims:
        rows = []
        for tick in range(int(round(a["length"] * 20)) + 1):
            _, pts = pose(model, a["_values"], tick)
            allp = [p for ps in pts.values() for p in ps]
            body = [p[1] for b, ps in pts.items() if not b.startswith(("Leg_", "Shin_", "Foot_")) for p in ps]
            feet = {b[5:]: round(min(p[1] for p in ps) / 16, 2) for b, ps in pts.items() if b.startswith("Foot_")}
            hd = pts.get("Head", []) + pts.get("Jaw", [])
            snout = min(hd, key=lambda p: p[2])
            rows.append({"tick": tick, "low": round(min(p[1] for p in allp) / 16, 2),
                         "body_low": round(min(body) / 16, 2), "high": round(max(p[1] for p in allp) / 16, 2),
                         "feet": feet, "snout": [round(v / 16, 2) for v in snout]})
        res[a["name"]] = rows
    return res


def strips(model, anims, atlases, sizes, folder, count=10, only=None):
    from taupe_champs_anim import strips as s
    s(model, anims, atlases, sizes, folder, count=count, only=only)


def finish(anims):
    for a in anims:
        a.pop("_values", None)
    return anims

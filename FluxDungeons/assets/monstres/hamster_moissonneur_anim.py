#!/usr/bin/env python3
"""Animations of the Harvest Hamster (2026-11-10).

Every timing that matters to the game (events, holds, windows) is written in docs/technique/boss/hamster-moissonneur.md
and read by FluxDungeons from spells/moisson/*.yml: keep the three in step. Ticks are 1/20 s; every key moment falls
on a tick.

"Real" values, as in loutre_marees_anim.py: rotation in degrees in the model's axes (x > 0 lifts the front of a bone
and moves a foot forwards; y > 0 turns the front to the monster's left (-x); z > 0 lifts its right side (+x)),
position in pixels of the delivered model (16 = 1 block), scale as a factor. Written in the Blockbench / BetterModel
convention (rotation read as (-x, -y, z), position as (-x, y, z)).

After the tracks are built, `settle()` fixes the height of the body and of each leg tick by tick: a planted foot
touches the ground (y = 0) and never sinks, the body never goes under the ground, and in the "lock" windows (rolling,
lying dead) its lowest point touches it.

Cheeks: two sets of bones, Cheek_* (full) and Cheek_*_empty (the same cheeks at 62 %, cf. EMPTY_CHEEK of the model).
FluxDungeons shows one set at a time; CHEEK_STATE says, per animation, when it switches (the same ticks as the spells),
and the preview follows it.
"""
import math
import os
import sys
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import paths  # noqa: F401,E402  (scripts/influcard on the path)

TAU = 2 * math.pi
S, C = math.sin, math.cos
lin = "linear"
LEGS = ("Leg_front_right", "Leg_front_left", "Leg_back_right", "Leg_back_left")
FRONT = LEGS[:2]
BACK = LEGS[2:]
CHEEKS_FULL = ("Cheek_right", "Cheek_left")
CHEEKS_EMPTY = ("Cheek_right_empty", "Cheek_left_empty")
LID_CLOSED = 8.0           # eyelid scale y that covers the whole eye
EMPTY = 1 / 0.62           # empty cheeks scaled by this = the full cheeks

# Cheek set shown at the start of each animation and switches inside it (tick, "full" | "empty"); None = whatever the
# boss shows when it starts (the preview draws the full set). Kept in step with the spells (HarvestPowers).
CHEEK_STATE = {
    "harvest_eat": ("empty", ((45, "full"),)),
    "harvest_eat_short": ("empty", ((22, "full"),)),
    "harvest_spit": ("full", ((24, "empty"),)),
    "great_feast": ("empty", ((60, "full"), (108, "empty"))),
}


def smooth(a, b, t):
    """0 before a, 1 after b, eased in between."""
    if t <= a:
        return 0.0
    if t >= b:
        return 1.0
    u = (t - a) / (b - a)
    return u * u * (3 - 2 * u)


def bump(a, b, t):
    """0 outside [a, b], rises then falls back (eased bell)."""
    if t <= a or t >= b:
        return 0.0
    return math.sin(math.pi * (t - a) / (b - a)) ** 2


def keys(t, pts):
    """Piecewise eased curve through (time, value) points; values may be numbers or tuples."""
    if t <= pts[0][0]:
        return pts[0][1]
    for (t0, v0), (t1, v1) in zip(pts, pts[1:]):
        if t <= t1:
            k = smooth(t0, t1, t)
            if isinstance(v0, tuple):
                return tuple(a + (b - a) * k for a, b in zip(v0, v1))
            return v0 + (v1 - v0) * k
    return pts[-1][1]


def T(tick):
    return tick / 20.0


def add3(*vs):
    return tuple(sum(v[i] for v in vs) for i in range(3))


def sc(v):
    return (v, v, v)


# =========================================================================== builder
class Anim:
    """Tracks of one animation: {bone: {channel: f(t) -> (x, y, z)}}, sampled every tick when written."""

    def __init__(self, name, ticks, loop=False):
        self.name, self.ticks, self.loop = name, ticks, loop
        self.length = ticks / 20.0
        self.tracks = {}

    def set(self, bone, channel, f):
        self.tracks.setdefault(bone, {})[channel] = f
        return self

    def rot(self, bone, f):
        return self.set(bone, "rotation", f)

    def pos(self, bone, f):
        return self.set(bone, "position", f)

    def scale(self, bone, f):
        return self.set(bone, "scale", f)

    def sample(self):
        """{bone: {channel: [values per tick]}} (real values)."""
        out = {}
        for bone, chans in self.tracks.items():
            out[bone] = {ch: [tuple(float(v) for v in f(T(i))) for i in range(self.ticks + 1)] for ch, f in chans.items()}
        return out


def gait(L, offsets, duty, stride, lift, swing_rot=10.0):
    """Leg tracks of a gait: stance from -stride to +stride (the foot goes back under the body), swing back with a
    lift. Returns ({leg: (pos f, rot f)}, {leg: planted windows in seconds})."""
    tracks, planted = {}, {}
    for leg, off in offsets.items():
        def phase(t, off=off):
            return (t / L + off) % 1.0

        def pos(t, off=off):
            u = phase(t, off)
            if u < duty:
                return (0, 0, -stride + 2 * stride * u / duty)
            v = (u - duty) / (1 - duty)
            return (0, lift * math.sin(math.pi * v), stride - 2 * stride * smooth(0, 1, v))

        def rot(t, off=off):
            u = phase(t, off)
            if u < duty:
                return (-6 * (u / duty - 0.5), 0, 0)
            v = (u - duty) / (1 - duty)
            return (swing_rot * math.sin(math.pi * v), 0, 0)
        tracks[leg] = (pos, rot)
        wins = []
        # stance windows (in seconds) over one cycle, cut at the loop
        start = (-off) % 1.0
        for k in (-1, 0, 1):
            a, b = (start + k) * L, (start + k + duty) * L
            a, b = max(0.0, a), min(L, b)
            if b > a:
                wins.append((a + 0.051, b - 0.051))
        planted[leg] = wins
    return tracks, planted


# =========================================================================== animations
def animations():
    """Every animation, plus per animation the windows where each leg is planted and where the whole body must touch
    the ground."""
    A, PLANT, LOCK = [], {}, {}

    def both(anim, bones, channel, f):
        for b in bones:
            anim.set(b, channel, f)

    def mirror(anim, right, left, channel, f):
        """f(t, s) with s = 1 for the right bone, -1 for the left one."""
        anim.set(right, channel, lambda t: f(t, 1))
        anim.set(left, channel, lambda t: f(t, -1))

    def cheeks(anim, f, sets=("full", "empty")):
        bones = (CHEEKS_FULL if "full" in sets else ()) + (CHEEKS_EMPTY if "empty" in sets else ())
        for b in bones:
            anim.scale(b, f)

    def blink(anim, *ticks):
        """Quick blinks (2 ticks closed)."""
        def f(t):
            k = max(bump(T(b - 1), T(b + 3), t) for b in ticks)
            return (1, 1 + (LID_CLOSED - 1) * k, 1)
        both(anim, ("Eyelid_right", "Eyelid_left"), "scale", f)

    def lids(anim, f):
        """f(t) = 0 open .. 1 closed."""
        both(anim, ("Eyelid_right", "Eyelid_left"), "scale", lambda t: (1, 1 + (LID_CLOSED - 1) * f(t), 1))

    # ------------------------------------------------------------------ idle: 4 s loop (80 ticks)
    # Breathing every 2 s, the head looks around (right, then left), the ears twitch one at a time, a blink, a sniff
    # (head up, nose and muzzle wiggling), a tiny puff of the cheeks, a shift of its weight. Every value back to 0 at
    # the loop.
    a = Anim("idle", 80, loop=True)
    a.pos("Body", lambda t: (0, 0.35 * (1 - C(TAU * t / 2)) / 2, 0))
    a.rot("Body", lambda t: (0.8 * S(TAU * t / 2), 0, 1.6 * bump(3.4, 4.0, t)))
    a.rot("Neck", lambda t: (-0.6 * S(TAU * t / 2 - 0.6), 0, 0))
    a.rot("Head", lambda t: (5 * bump(1.85, 2.5, t), 13 * bump(0.6, 1.8, t) - 11 * bump(2.6, 3.5, t),
                             -2.5 * bump(0.6, 1.8, t) + 2 * bump(2.6, 3.5, t)))
    a.pos("Nose", lambda t: (0, 0.3 * bump(1.9, 2.45, t) * S(TAU * 9 * t), 0))
    a.rot("Muzzle", lambda t: (2 * bump(1.9, 2.45, t) * S(TAU * 9 * t + 1), 0, 0))
    mirror(a, "Eye_right", "Eye_left", "position", lambda t, s: (0.3 * (bump(0.6, 1.8, t) - bump(2.6, 3.5, t)), 0, 0))
    a.rot("Ear_right", lambda t: (-3 * bump(2.0, 2.4, t), 0, -7 * bump(0.55, 0.85, t) + 4 * bump(3.3, 3.6, t)))
    a.rot("Ear_left", lambda t: (-8 * bump(2.1, 2.5, t) + 3 * bump(3.35, 3.65, t), 0, 3 * bump(0.6, 0.9, t)))
    cheeks(a, lambda t: sc(1 + 0.05 * bump(3.0, 3.3, t)))
    blink(a, 22, 63)
    A.append(a)

    # ------------------------------------------------------------------ walk: 0.6 s loop (12 ticks)
    # Lateral sequence walk (back left, front left, back right, front right, a quarter apart), quick little steps,
    # the body waddles and bobs, the head stays steady. Planted feet do not slide at 1.6 blocks a second.
    L = 0.6
    a = Anim("walk", 12, loop=True)
    legs, planted = gait(L, {"Leg_back_left": 0.0, "Leg_front_left": 0.25, "Leg_back_right": 0.5,
                             "Leg_front_right": 0.75}, duty=0.65, stride=5.0, lift=2.0)
    for leg, (p, r) in legs.items():
        a.pos(leg, p).rot(leg, r)
    PLANT["walk"] = planted
    a.pos("Body", lambda t: (0, 0.3 * C(TAU * 2 * t / L), 0))
    a.rot("Body", lambda t: (0.8 * S(TAU * 2 * t / L), 1.4 * S(TAU * t / L), 1.8 * S(TAU * t / L)))
    a.rot("Neck", lambda t: (-0.8 * S(TAU * 2 * t / L), -1.2 * S(TAU * t / L), -1.5 * S(TAU * t / L)))
    a.rot("Head", lambda t: (-2, 0, 0))
    both(a, ("Ear_right", "Ear_left"), "rotation", lambda t: (3 * S(TAU * 2 * t / L - 1), 0, 0))
    mirror(a, "Cheek_right", "Cheek_left", "rotation", lambda t, s: (0, 0, 1.5 * S(TAU * t / L - 0.8) * s))
    mirror(a, "Cheek_right_empty", "Cheek_left_empty", "rotation", lambda t, s: (0, 0, 1.5 * S(TAU * t / L - 0.8) * s))
    A.append(a)

    # ------------------------------------------------------------------ run: 0.35 s loop (7 ticks)
    # Half bound: the front pair, then the back pair; body low, pitching, head down and forward, ears laid back so
    # they do not flap. Planted feet do not slide at 5 blocks a second.
    L = 0.35
    a = Anim("run", 7, loop=True)
    legs, planted = gait(L, {"Leg_front_right": 0.0, "Leg_front_left": 0.06, "Leg_back_right": 0.5,
                             "Leg_back_left": 0.56}, duty=0.4, stride=5.6, lift=2.8, swing_rot=22)
    for leg, (p, r) in legs.items():
        a.pos(leg, p).rot(leg, r)
    PLANT["run"] = planted
    a.pos("Body", lambda t: (0, -1.2 + 0.8 * C(TAU * 2 * t / L), 0))
    a.rot("Body", lambda t: (-3 + 4.5 * S(TAU * t / L), 0, 1.2 * S(TAU * t / L)))
    a.rot("Neck", lambda t: (-4.5 * S(TAU * t / L), 0, -1 * S(TAU * t / L)))
    a.rot("Head", lambda t: (-8, 0, 0))
    both(a, ("Ear_right", "Ear_left"), "rotation", lambda t: (32 + 2 * S(TAU * t / L), 0, 0))
    cheeks(a, lambda t: (1, 1 - 0.03 * C(TAU * 2 * t / L), 1))
    A.append(a)

    # ------------------------------------------------------------------ turn: 0.6 s (12 ticks)
    # Turning on the spot (the game turns the entity): little steps of the diagonal pairs, the head leads the turn.
    a = Anim("turn", 12)
    step = {"Leg_front_right": 0, "Leg_back_left": 0, "Leg_front_left": 1, "Leg_back_right": 1}
    for leg, k in step.items():
        w = (T(1 + 5 * k), T(6 + 5 * k))
        a.pos(leg, lambda t, w=w, leg=leg: (0.8 * bump(*w, t) * (1 if "right" in leg else -1), 1.8 * bump(*w, t), 0))
    PLANT["turn"] = {leg: [(0, T(1 + 5 * k)), (T(6 + 5 * k), 0.6)] for leg, k in step.items()}
    a.rot("Body", lambda t: (0, 5 * bump(0, 0.6, t), 1.5 * S(TAU * t / 0.3) * bump(0, 0.6, t)))
    a.rot("Head", lambda t: (0, 9 * bump(0, 0.55, t), 0))
    A.append(a)

    # ------------------------------------------------------------------ attack: 0.6 s (12 ticks), melee bite
    # The game hits at once: a 2 tick wind up, snap of the jaw at tick 3, back to rest.
    a = Anim("attack", 12)
    a.pos("Body", lambda t: (0, 0, keys(t, [(0, 0), (T(2), 1.2), (T(4), -1.5), (T(12), 0)])))
    a.pos("Neck", lambda t: (0, 0, keys(t, [(0, 0), (T(2), 0.8), (T(4), -3), (T(12), 0)])))
    a.rot("Head", lambda t: (keys(t, [(0, 0), (T(2), 8), (T(4), -12), (T(12), 0)]), 0, 0))
    a.rot("Jaw", lambda t: (keys(t, [(0, 0), (T(2), -22), (T(3), -24), (T(5), 0), (T(12), 0)]), 0, 0))
    both(a, ("Ear_right", "Ear_left"), "rotation", lambda t: (24 * bump(0, T(10), t), 0, 0))
    A.append(a)

    # ------------------------------------------------------------------ hurt / hurt_2: 0.3 s (6 ticks)
    # Small recoil, head thrown back, body contracted, ears down, a blink; hurt_2 turns the head the other way.
    for name, side in (("hurt", 1), ("hurt_2", -1)):
        a = Anim(name, 6)
        k = lambda t: bump(0, T(6), t) if t < T(2) else 1 - smooth(T(2), T(6), t)    # noqa: E731
        a.pos("Body", lambda t, k=k: (0, 0, 1.6 * k(t)))
        a.rot("Body", lambda t, k=k, side=side: (4 * k(t), 0, 2 * side * k(t)))
        a.scale("Body", lambda t, k=k: (1 + 0.03 * k(t), 1 - 0.05 * k(t), 1 - 0.03 * k(t)))
        a.rot("Head", lambda t, k=k, side=side: (9 * k(t), 10 * side * k(t), 0))
        both(a, ("Ear_right", "Ear_left"), "rotation", lambda t, k=k: (30 * k(t), 0, 0))
        lids(a, lambda t, k=k: 0.8 * k(t))
        A.append(a)

    # ------------------------------------------------------------------ stun: 2 s (40 ticks), recover: 0.8 s (16 ticks)
    def dizzy(a, t0, t1):
        """Dizzy between t0 and t1 (seconds): head circling, body swaying, eyes half shut, ears drooping."""
        k = lambda t: smooth(t0, t0 + 0.2, t) * (1 - smooth(t1 - 0.25, t1, t))        # noqa: E731
        a.rot("Body", lambda t: (0, 0, 4 * k(t) * S(TAU * (t - t0) / 0.9)))
        a.rot("Head", lambda t: (6 * k(t) * S(TAU * (t - t0) / 0.6) - 6 * k(t), 0, 7 * k(t) * C(TAU * (t - t0) / 0.6)))
        mirror(a, "Ear_right", "Ear_left", "rotation", lambda t, s: (15 * k(t), 0, -25 * k(t) * s))
        lids(a, lambda t: 0.55 * k(t))
        a.rot("Jaw", lambda t: (-6 * k(t), 0, 0))
        a.pos("Body", lambda t: (0, -1.5 * k(t), 0))

    a = Anim("stun", 40)
    dizzy(a, 0, 2.0)
    A.append(a)

    def recover(a, t0):
        """Shakes its head twice and stands up straight, from t0 (0.8 s)."""
        a.rot("Head", lambda t: (0, 14 * bump(t0, t0 + 0.5, t) * S(TAU * (t - t0) / 0.25), 0))
        a.rot("Neck", lambda t: (0, 0, 3 * bump(t0, t0 + 0.5, t) * S(TAU * (t - t0) / 0.25)))
        mirror(a, "Ear_right", "Ear_left", "rotation", lambda t, s: (0, 0, -10 * bump(t0, t0 + 0.5, t) * S(TAU * (t - t0) / 0.25) * s))
        a.scale("Body", lambda t: (1, 1 + 0.03 * bump(t0 + 0.5, t0 + 0.8, t), 1))

    a = Anim("recover", 16)
    recover(a, 0)
    A.append(a)

    # ------------------------------------------------------------------ harvest_seek: 0.5 s (10 ticks)
    # It has spotted the crops (the game turns it towards them): ears up, a sniff, the muzzle goes down to the
    # ground, ends with the head low, ready to run.
    a = Anim("harvest_seek", 10)
    a.rot("Head", lambda t: (keys(t, [(0, 0), (T(3), 7), (T(10), -9)]), 0, 0))
    a.pos("Nose", lambda t: (0, 0.35 * bump(T(1), T(6), t) * S(TAU * 10 * t), 0))
    both(a, ("Ear_right", "Ear_left"), "rotation", lambda t: (keys(t, [(0, 0), (T(2), -14), (T(10), -8)]), 0, 0))
    a.rot("Body", lambda t: (keys(t, [(0, 0), (T(10), -3)]), 0, 0))
    A.append(a)

    # ------------------------------------------------------------------ harvest_eat: 2.25 s (45 ticks) / short: 22
    # Head down in the crops, front paws closer to them; each bite: the mouth opens, the head dips, snaps shut
    # (bite at the listed ticks: the game removes crops then), chews a little, short pause. The empty cheeks swell
    # a step after each bite, up to the size of the full cheeks (the game then shows the full set).
    def eat(name, ticks, bites):
        a = Anim(name, ticks)
        end = T(ticks)
        down = lambda t: smooth(0, T(5), t) * (1 - smooth(end - T(5), end, t))      # noqa: E731
        a.rot("Body", lambda t: (-7 * down(t), 0, 0))
        mirror(a, "Leg_front_right", "Leg_front_left", "position", lambda t, s: (-0.8 * s * down(t), 0, -1.6 * down(t)))

        def head(t):
            dip = sum(bump(T(b - 4), T(b + 2), t) for b in bites)
            chew = sum(bump(T(b + 1), T(b + 6), t) for b in bites)
            return (-24 * down(t) - 8 * dip + 1.5 * chew * S(TAU * 6 * t), 3 * S(TAU * t / 1.5) * down(t), 0)
        a.rot("Head", head)
        a.rot("Jaw", lambda t: (-20 * sum(bump(T(b - 4), T(b), t) for b in bites)
                                - 5 * sum(bump(T(b + 1), T(b + 6), t) * (0.5 + 0.5 * S(TAU * 8 * t)) for b in bites), 0, 0))
        a.pos("Nose", lambda t: (0, 0.3 * down(t) * S(TAU * 7 * t), 0))
        mirror(a, "Ear_right", "Ear_left", "rotation",
               lambda t, s: (-6 * down(t) + 6 * sum(bump(T(b), T(b + 4), t) for b in bites), 0, -3 * s * down(t)))

        def swell(t):
            k = sum(smooth(T(b + 1), T(b + 4), t) for b in bites) / len(bites)
            return sc(1 + (EMPTY - 1) * k)
        cheeks(a, swell, ("empty",))
        PLANT[name] = {leg: [(0, end)] for leg in LEGS}
        A.append(a)
        return a

    eat("harvest_eat", 45, (6, 14, 22, 30, 38))
    eat("harvest_eat_short", 22, (6, 14))

    # ------------------------------------------------------------------ harvest_spit: 2.3 s (46 ticks)
    # 0-12 wind up: the head jerks up (the game turns it to the player), the body rears back, the full cheeks swell
    # even more, a big breath, the mouth opens. 12-26 three thrusts: salvoes at ticks 14, 18 and 22 (projectiles
    # from the mouth), the cheeks empty a third at a time; tick 24: the game shows the empty set. 26-46 recovery:
    # leaning forward, panting, two shakes of the head, back to rest.
    a = Anim("harvest_spit", 46)
    salvoes = (14, 18, 22)
    thrust = lambda t: sum(bump(T(s - 2), T(s + 3), t) for s in salvoes)              # noqa: E731
    a.pos("Body", lambda t: (0, 0, keys(t, [(0, 0), (T(10), 2.2), (T(12), 2.0), (T(24), 0.6), (T(30), -0.6), (T(46), 0)])
                             + 0.8 * thrust(t)))
    a.rot("Body", lambda t: (keys(t, [(0, 0), (T(10), 6), (T(13), 2), (T(26), -2), (T(32), -6), (T(42), -4), (T(46), 0)]), 0, 0))
    a.scale("Body", lambda t: (1 + 0.03 * bump(T(2), T(14), t), 1 + 0.045 * bump(T(2), T(14), t)
                               + 0.02 * bump(T(28), T(42), t) * S(TAU * 5 * t), 1 + 0.02 * bump(T(2), T(14), t)))
    a.pos("Neck", lambda t: (0, 0, -2.6 * thrust(t)))
    a.rot("Head", lambda t: (keys(t, [(0, 0), (T(3), 12), (T(12), 6), (T(14), -6), (T(24), -6), (T(30), -12), (T(44), 0)])
                             - 6 * thrust(t), 13 * bump(T(34), T(44), t) * S(TAU * (t - T(34)) / 0.25), 0))
    a.rot("Jaw", lambda t: (keys(t, [(0, 0), (T(4), -4), (T(12), -14), (T(13), -30), (T(23), -30), (T(27), -6),
                                     (T(30), -4), (T(42), -4), (T(46), 0)])
                            - 3 * bump(T(28), T(42), t) * (0.5 + 0.5 * S(TAU * 5 * t)), 0, 0))
    both(a, ("Ear_right", "Ear_left"), "rotation",
         lambda t: (keys(t, [(0, 0), (T(4), -12), (T(12), -10), (T(14), 18), (T(24), 14), (T(30), 8), (T(46), 0)]), 0, 0))
    cheeks(a, lambda t: sc(keys(t, [(0, 1.0), (T(2), 1.0), (T(12), 1.25), (T(14), 1.25), (T(16), 1.0), (T(18), 1.0),
                                     (T(20), 0.8), (T(22), 0.8), (T(24), 0.62)])), ("full",))
    PLANT["harvest_spit"] = {leg: [(0, T(46))] for leg in LEGS}
    A.append(a)

    # ------------------------------------------------------------------ harvest_none: 1.4 s (28 ticks)
    # Nothing left: it sniffs the ground left and right, looks up at the player, stamps a front paw twice, ears
    # flicked back, a little huff of the cheeks.
    a = Anim("harvest_none", 28)
    a.rot("Head", lambda t: (keys(t, [(0, 0), (T(3), -22), (T(13), -22), (T(16), 5), (T(26), 3), (T(28), 0)]),
                             15 * bump(T(2), T(14), t) * S(TAU * (t - T(2)) / 0.6), 0))
    a.pos("Nose", lambda t: (0, 0.35 * bump(T(2), T(14), t) * S(TAU * 10 * t), 0))
    a.rot("Body", lambda t: (-5 * bump(0, T(15), t), 0, 0))
    a.pos("Leg_front_right", lambda t: (0, 2.6 * (bump(T(18), T(21), t) + bump(T(21), T(24), t)), 0))
    PLANT["harvest_none"] = {leg: [(0, T(28))] for leg in LEGS if leg != "Leg_front_right"}
    PLANT["harvest_none"]["Leg_front_right"] = [(0, T(17.5)), (T(24.5), T(28))]
    both(a, ("Ear_right", "Ear_left"), "rotation", lambda t: (26 * bump(T(16), T(27), t), 0, 0))
    cheeks(a, lambda t: sc(1 + 0.08 * bump(T(19), T(25), t)))
    a.rot("Jaw", lambda t: (-4 * bump(T(20), T(25), t), 0, 0))
    A.append(a)

    # ------------------------------------------------------------------ roll: 3.2 s (64 ticks)
    # 0-12 wind up (lowered, legs drawn in, head down, ears flat), 12-18 curls into a ball (legs and head tucked in,
    # cheeks pressed), 18-43 rolls forwards faster and faster (the game moves it; three full turns at tick 43), 43-49
    # unrolls (legs out, head pops up, squash), 49-64 dizzy shake, back to rest.
    a = Anim("roll", 64)
    w0, w1 = 16.0, 67.2                    # degrees per tick at tick 18 and 43 (three turns from tick 12)

    def angle(t):
        tick = t * 20
        if tick <= 12:
            return -10 * smooth(0, T(12), t)
        if tick <= 18:
            return -10 - 30 * smooth(T(12), T(18), t)
        if tick <= 43:
            u = (tick - 18) / 25
            return -40 - 25 * (w0 * u + (w1 - w0) * u * u / 2)
        return -1080 - 12 * bump(T(43), T(49), t)
    curl = lambda t: smooth(T(10), T(17), t) * (1 - smooth(T(43), T(47), t))          # noqa: E731
    gather = lambda t: smooth(0, T(12), t) * (1 - smooth(T(43), T(48), t))           # noqa: E731
    a.rot("Body", lambda t: (angle(t), 0, 0))
    a.pos("Body", lambda t: (0, -1.5 * gather(t), 0))
    a.scale("Body", lambda t: (1 + 0.02 * curl(t) + 0.05 * bump(T(43), T(48), t), 1 - 0.04 * gather(t) - 0.07 * bump(T(43), T(48), t),
                               1 - 0.08 * curl(t) + 0.05 * bump(T(43), T(48), t)))
    for leg in LEGS:
        fz = 1 if leg in FRONT else -1
        sx = 1 if "right" in leg else -1
        a.pos(leg, lambda t, fz=fz, sx=sx: (-1.5 * sx * curl(t), 5.5 * curl(t), 2.5 * fz * gather(t) + 1.0 * fz * curl(t)))
        a.rot(leg, lambda t, fz=fz: (35 * fz * curl(t), 0, 0))
    a.pos("Neck", lambda t: (0, -1.0 * curl(t), 3.0 * curl(t)))
    a.rot("Head", lambda t: (-18 * gather(t) - 20 * curl(t) + 10 * bump(T(44), T(50), t)
                             , 14 * bump(T(50), T(62), t) * S(TAU * (t - T(50)) / 0.3), 0))
    both(a, ("Ear_right", "Ear_left"), "rotation", lambda t: (40 * gather(t) - 12 * bump(T(44), T(50), t), 0, 0))
    cheeks(a, lambda t: sc(1 - 0.1 * curl(t)))
    lids(a, lambda t: 0.5 * curl(t) + 0.35 * bump(T(49), T(62), t))
    PLANT["roll"] = {leg: [(0, T(9)), (T(48), T(64))] for leg in LEGS}
    LOCK["roll"] = [(T(16), T(46))]
    A.append(a)

    # ------------------------------------------------------------------ roll_crash: 3.1 s (62 ticks)
    # It rolled into a wall: unrolls with a jolt backwards (0-6), dizzy (6-46), shakes it off (46-62).
    a = Anim("roll_crash", 62)
    a.rot("Body", lambda t: (keys(t, [(0, -30), (T(3), 14), (T(6), 0)]) if t < T(6) else 0, 0,
                             4 * smooth(T(6), T(9), t) * (1 - smooth(T(42), T(46), t)) * S(TAU * (t - T(6)) / 0.9)))
    a.scale("Body", lambda t: (1 + 0.05 * bump(0, T(6), t), 1 - 0.08 * bump(0, T(6), t), 1 + 0.05 * bump(0, T(6), t)))
    for leg in LEGS:
        a.pos(leg, lambda t: (0, 5.5 * (1 - smooth(0, T(4), t)), 0))
    a.pos("Neck", lambda t: (0, 0, 3 * (1 - smooth(0, T(4), t))))
    d = lambda t: smooth(T(6), T(10), t) * (1 - smooth(T(42), T(46), t))             # noqa: E731
    a.rot("Head", lambda t: (-38 * (1 - smooth(0, T(5), t)) + 6 * d(t) * S(TAU * (t - T(6)) / 0.6) - 6 * d(t),
                             14 * bump(T(46), T(56), t) * S(TAU * (t - T(46)) / 0.25), 7 * d(t) * C(TAU * (t - T(6)) / 0.6)))
    mirror(a, "Ear_right", "Ear_left", "rotation", lambda t, s: (40 * (1 - smooth(0, T(6), t)) + 15 * d(t), 0, -25 * d(t) * s))
    lids(a, lambda t: max(0.6 * (1 - smooth(0, T(6), t)), 0.55 * d(t)))
    a.rot("Jaw", lambda t: (-6 * d(t), 0, 0))
    a.pos("Body", lambda t: (0, -1.5 * d(t), 0))
    PLANT["roll_crash"] = {leg: [(T(4), T(62))] for leg in LEGS}
    LOCK["roll_crash"] = [(0, T(3))]
    A.append(a)

    # ------------------------------------------------------------------ leap: 2.1 s (42 ticks)
    # 0-9 crouch (back legs more), 9-12 push (launch at tick 9: the game moves the entity along its arc until tick
    # 24), 12-24 compact in the air, front first at the end, 24 landing (shockwave), 24-30 squash, 30-42 recovery.
    a = Anim("leap", 42)
    crouch = lambda t: smooth(0, T(9), t) * (1 - smooth(T(9), T(11), t))             # noqa: E731
    air = lambda t: smooth(T(9), T(12), t) * (1 - smooth(T(22), T(25), t))           # noqa: E731
    land = lambda t: bump(T(24), T(32), t)                                            # noqa: E731
    a.pos("Body", lambda t: (0, -3.4 * crouch(t) + 1.5 * air(t) - 3.4 * land(t), 0))
    a.rot("Body", lambda t: (6 * crouch(t) + keys(t, [(0, 0), (T(9), 0), (T(12), -8), (T(18), 5), (T(23), -12),
                                                   (T(26), 0)]) * (1 if T(9) <= t <= T(26) else 0), 0, 0))
    a.scale("Body", lambda t: (1 + 0.08 * land(t), 1 - 0.13 * land(t), 1 + 0.05 * land(t)))
    for leg in BACK:
        a.rot(leg, lambda t: (-22 * air(t) - 38 * bump(T(8), T(12), t), 0, 0))
        a.pos(leg, lambda t: (0, 3.0 * air(t), 0))
    mirror(a, "Leg_front_right", "Leg_front_left", "rotation", lambda t, s: (32 * air(t), 0, -12 * s * land(t)))
    for leg in FRONT:
        a.pos(leg, lambda t: (0, 3.0 * air(t), 0))
    a.rot("Head", lambda t: (-10 * crouch(t) + 5 * air(t) - 8 * land(t), 9 * bump(T(30), T(41), t) * S(TAU * (t - T(30)) / 0.3), 0))
    a.rot("Jaw", lambda t: (-12 * bump(T(10), T(22), t), 0, 0))
    both(a, ("Ear_right", "Ear_left"), "rotation",
         lambda t: (25 * crouch(t) + keys(t, [(0, 0), (T(9), 0), (T(12), 42), (T(18), 10), (T(24), -14), (T(27), 32),
                                             (T(36), 0)]) * (1 if t >= T(9) else 0), 0, 0))
    cheeks(a, lambda t: sc(1 + 0.09 * land(t) * C(TAU * (t - T(24)) / 0.2)))
    PLANT["leap"] = {leg: [(0, T(8.5)), (T(25), T(42))] for leg in LEGS}
    LOCK["leap"] = []
    A.append(a)

    # ------------------------------------------------------------------ roots: 3.25 s (65 ticks)
    # 0-18 concentration (low, feet planted wide, eyes shut, ears up and still), 18-34 the head rises, eyes open at
    # 32, 34-38 rears a little and slams both front paws (roots_slam at tick 38: the roots rise), 38-65 watches them,
    # back to rest.
    a = Anim("roots", 65)
    low = lambda t: smooth(0, T(8), t) * (1 - smooth(T(55), T(65), t))               # noqa: E731
    a.pos("Body", lambda t: (0, -2.0 * low(t), 0))
    a.rot("Body", lambda t: (keys(t, [(0, 0), (T(34), 0), (T(36.5), 13), (T(38), -4), (T(46), -2), (T(65), 0)]), 0, 0))
    for leg in LEGS:
        sx = 1 if "right" in leg else -1
        a.rot(leg, lambda t, sx=sx: (0, 0, -6 * sx * low(t)))
    a.rot("Head", lambda t: (keys(t, [(0, 0), (T(8), -6), (T(18), -6), (T(32), 12), (T(36), 8), (T(38), -14), (T(44), -6),
                                      (T(65), 0)]), 0, 0))
    lids(a, lambda t: smooth(T(3), T(8), t) * (1 - smooth(T(30), T(33), t)))
    both(a, ("Ear_right", "Ear_left"), "rotation",
         lambda t: (keys(t, [(0, 0), (T(8), -10), (T(34), -10), (T(38), 20), (T(46), 0)]) + 2 * bump(T(46), T(60), t) * S(TAU * 4 * t), 0, 0))
    cheeks(a, lambda t: sc(1 + 0.04 * bump(T(4), T(34), t) * (0.5 + 0.5 * S(TAU * 3 * t))))
    PLANT["roots"] = {leg: [(0, T(65))] for leg in BACK}
    PLANT["roots"].update({leg: [(0, T(34)), (T(38), T(65))] for leg in FRONT})
    A.append(a)

    # ------------------------------------------------------------------ great_feast: 7.5 s (150 ticks), the ultimate
    # 0-25 announcement (stops, looks at the player, lowers its head slowly, ears up), 25-60 absorption (front paws
    # forward on the ground, leaning, mouth ajar breathing in; empty cheeks swell, the game shows the full set at 60),
    # 60-85 overload (rises a little, trembles, the full cheeks swell to 150 %, ears quivering), 85-110 three waves at
    # ticks 88, 96 and 104 (mouth wide open, head thrust, cheeks empty a third at a time; empty set at 108), 110-150
    # exhausted (slumped, head down, ears drooping, panting), back to rest.
    a = Anim("great_feast", 150)
    waves = (88, 96, 104)
    wave = lambda t: sum(bump(T(w - 3), T(w + 4), t) for w in waves)                  # noqa: E731
    slump = lambda t: smooth(T(108), T(116), t) * (1 - smooth(T(135), T(150), t))     # noqa: E731
    absorb = lambda t: smooth(T(25), T(32), t) * (1 - smooth(T(58), T(64), t))       # noqa: E731
    tremble = lambda t: smooth(T(60), T(66), t) * (1 - smooth(T(84), T(86), t))      # noqa: E731
    a.pos("Body", lambda t: (0.35 * tremble(t) * S(TAU * 10 * t), -1.0 * smooth(0, T(25), t) * (1 - smooth(T(60), T(66), t))
                             + 1.0 * tremble(t) - 3.0 * slump(t), 1.2 * wave(t)))
    situp = lambda t: smooth(T(62), T(70), t) * (1 - smooth(T(83), T(87), t))        # noqa: E731
    a.rot("Body", lambda t: (-8 * absorb(t) + 4 * tremble(t) + 26 * situp(t) - 2 * wave(t) - 6 * slump(t), 0, 0))
    mirror(a, "Leg_front_right", "Leg_front_left", "rotation",
           lambda t, s: (-30 * situp(t) + 6 * situp(t) * S(TAU * 3 * t + s), 0, -8 * s * situp(t)))
    for leg in BACK:
        a.rot(leg, lambda t: (-26 * situp(t), 0, 0))
    a.scale("Body", lambda t: (1 - 0.03 * tremble(t), 1 - 0.02 * tremble(t) + 0.03 * slump(t) * S(TAU * 2 * t),
                               1 - 0.04 * tremble(t)))
    mirror(a, "Leg_front_right", "Leg_front_left", "position", lambda t, s: (0.8 * s * absorb(t), 0, -1.8 * absorb(t)))
    a.rot("Head", lambda t: (-22 * situp(t) + keys(t, [(0, 0), (T(25), -16), (T(32), -20), (T(58), -20), (T(66), 8), (T(85), 8), (T(88), -6),
                                      (T(104), -6), (T(110), -10), (T(118), -22), (T(135), -18), (T(150), 0)]) - 8 * wave(t), 0, 0))
    a.pos("Neck", lambda t: (0, 0, -2.5 * wave(t)))
    a.rot("Jaw", lambda t: (-8 * absorb(t) - 32 * wave(t) - (5 + 3 * S(TAU * 3 * t)) * slump(t), 0, 0))
    mirror(a, "Ear_right", "Ear_left", "rotation",
           lambda t, s: (keys(t, [(0, 0), (T(12), -12), (T(108), -12), (T(116), 22), (T(140), 18), (T(150), 0)]),
                         0, 4 * tremble(t) * S(TAU * 10 * t + s) - 25 * s * slump(t)))
    lids(a, lambda t: 0.5 * slump(t) + 0.8 * bump(T(124), T(127), t))
    cheeks(a, lambda t: sc(1 + (EMPTY - 1) * smooth(T(27), T(58), t)), ("empty",))
    cheeks(a, lambda t: sc(keys(t, [(0, 1.0), (T(60), 1.0), (T(84), 1.5), (T(87), 1.5), (T(89), 1.25), (T(95), 1.25),
                                     (T(97), 0.95), (T(103), 0.95), (T(106), 0.62)])), ("full",))
    PLANT["great_feast"] = {leg: [(0, T(150))] for leg in BACK}
    PLANT["great_feast"].update({leg: [(0, T(61)), (T(87), T(150))] for leg in FRONT})
    A.append(a)

    # ------------------------------------------------------------------ death: 3.5 s (70 ticks)
    # 0-12 struck (recoil, head up, ears down, still standing), 12-32 the legs give way, head lowers, starts to lean,
    # 32-40 falls on its left side, small bounce (death_impact at 40), 46-60 a last breath, the eyes close, 60-70 still.
    a = Anim("death", 70)
    tip = lambda t: keys(t, [(0, 0), (T(12), 0), (T(32), 22), (T(39), 84), (T(41), 78), (T(45), 84)])  # noqa: E731
    a.rot("Body", lambda t: (keys(t, [(0, 0), (T(4), 7), (T(12), 4), (T(32), -6), (T(40), 0)]), 0, tip(t)))
    a.pos("Body", lambda t: (0, keys(t, [(0, 0), (T(12), 0), (T(32), -4)]), keys(t, [(0, 0), (T(4), 2), (T(12), 1.5), (T(32), 0)])))
    a.scale("Body", lambda t: (1, 1 + 0.035 * bump(T(48), T(58), t), 1))
    for leg in LEGS:
        fz = 1 if leg in FRONT else -1
        a.rot(leg, lambda t, fz=fz: (26 * fz * smooth(T(34), T(46), t), 0, 0))
    a.rot("Head", lambda t: (keys(t, [(0, 0), (T(4), 12), (T(12), 8), (T(32), -15), (T(40), -8), (T(60), -10)]), 0,
                             keys(t, [(0, 0), (T(32), 0), (T(42), -10)])))
    both(a, ("Ear_right", "Ear_left"), "rotation", lambda t: (35 * smooth(0, T(5), t), 0, 0))
    a.rot("Jaw", lambda t: (-6 * smooth(T(40), T(48), t), 0, 0))
    lids(a, lambda t: 0.3 * smooth(T(12), T(30), t) + 0.7 * smooth(T(52), T(60), t))
    cheeks(a, lambda t: sc(1 - 0.06 * smooth(T(46), T(60), t)))
    PLANT["death"] = {leg: [(0, T(30))] for leg in LEGS}
    LOCK["death"] = [(T(31), T(70))]
    A.append(a)

    # ------------------------------------------------------------------ spawn: 3 s (60 ticks), entrance
    # 0-20 runs in (the game moves it), 20-24 stops short, 24-34 sniffs the ground, 34-38 notices the crops (head up,
    # ears up), 38 the game turns it to the player, 42-56 sits up on its haunches, front paws off the ground, a nod at
    # 50, 56-60 back on all fours.
    a = Anim("spawn", 60)
    L = 0.35
    legs, planted = gait(L, {"Leg_front_right": 0.0, "Leg_front_left": 0.06, "Leg_back_right": 0.5,
                             "Leg_back_left": 0.56}, duty=0.4, stride=5.6, lift=2.8, swing_rot=22)
    runk = lambda t: 1 - smooth(T(18), T(22), t)                                      # noqa: E731
    for leg, (p, r) in legs.items():
        a.pos(leg, lambda t, p=p: tuple(v * runk(t) for v in p(t % L)))
        a.rot(leg, lambda t, r=r: tuple(v * runk(t) for v in r(t % L)))
    rear = lambda t: smooth(T(42), T(48), t) * (1 - smooth(T(54), T(58), t))         # noqa: E731
    a.rot("Body", lambda t: ((-3 + 4.5 * S(TAU * t / L)) * runk(t) + 7 * bump(T(19), T(26), t) + 30 * rear(t), 0, 0))
    a.pos("Body", lambda t: (0, (-1.2 + 0.8 * C(TAU * 2 * t / L)) * runk(t), 0))
    mirror(a, "Leg_front_right", "Leg_front_left", "rotation", lambda t, s: (-24 * rear(t), 0, -6 * s * rear(t)))
    for leg in BACK:                    # back legs stay upright under the sitting body
        a.rot(leg, lambda t, r=legs[leg][1]: (r(t % L)[0] * runk(t) - 30 * rear(t), 0, 0))
    a.rot("Head", lambda t: (-8 * runk(t) - 20 * bump(T(23), T(35), t) + 8 * bump(T(34), T(40), t) - 26 * rear(t)
                             - 9 * bump(T(48), T(52), t), 0, 0))
    a.pos("Nose", lambda t: (0, 0.35 * bump(T(24), T(34), t) * S(TAU * 10 * t), 0))
    both(a, ("Ear_right", "Ear_left"), "rotation",
         lambda t: (32 * runk(t) - 16 * bump(T(33), T(44), t) - 6 * rear(t), 0, 0))
    PLANT["spawn"] = {leg: [w for w in planted[leg] if w[1] < T(18)] + [(T(22), T(60))] for leg in BACK}
    PLANT["spawn"].update({leg: [w for w in planted[leg] if w[1] < T(18)] + [(T(22), T(42)), (T(58), T(60))] for leg in FRONT})
    A.append(a)

    # ------------------------------------------------------------------ victory: 2.5 s (50 ticks)
    # Nobody left: two happy hops, ears flopping, then sits up and nibbles, back down.
    a = Anim("victory", 50)
    hop = lambda t: bump(T(4), T(12), t) + bump(T(14), T(22), t)                       # noqa: E731
    sit = lambda t: smooth(T(26), T(31), t) * (1 - smooth(T(43), T(48), t))           # noqa: E731
    a.pos("Body", lambda t: (0, 3.5 * hop(t), 0))
    a.rot("Body", lambda t: (4 * hop(t) + 28 * sit(t), 0, 0))
    for leg in LEGS:
        a.rot(leg, lambda t, leg=leg: ((22 if leg in FRONT else -16) * hop(t) - (22 if leg in FRONT else 28) * sit(t), 0, 0))
    a.rot("Head", lambda t: (-24 * sit(t) + 4 * hop(t), 0, 0))
    a.rot("Jaw", lambda t: (-5 * sit(t) * (0.5 + 0.5 * S(TAU * 7 * t)), 0, 0))
    a.pos("Nose", lambda t: (0, 0.3 * sit(t) * S(TAU * 9 * t), 0))
    mirror(a, "Ear_right", "Ear_left", "rotation", lambda t, s: (-20 * hop(t) + 10 * bump(T(10), T(16), t), 0, -6 * s * hop(t)))
    cheeks(a, lambda t: sc(1 + 0.05 * sit(t) * (0.5 + 0.5 * S(TAU * 7 * t))))
    lids(a, lambda t: 0.6 * bump(T(30), T(42), t))
    PLANT["victory"] = {leg: [(0, T(4)), (T(12), T(14)), (T(22), T(50))] for leg in BACK}
    PLANT["victory"].update({leg: [(0, T(4)), (T(12), T(14)), (T(22), T(27)), (T(47), T(50))] for leg in FRONT})
    A.append(a)

    # ------------------------------------------------------------------ phase_shift: 1.5 s (30 ticks)
    # Change of phase: it shakes itself like a wet animal (fast decreasing roll, head and ears lagging), then
    # stands tall, head up, ears up.
    a = Anim("phase_shift", 30)
    shake = lambda t: (1 - smooth(0, T(16), t)) * smooth(0, T(2), t)                  # noqa: E731
    a.rot("Body", lambda t: (0, 0, 10 * shake(t) * S(TAU * t / 0.2)))
    a.rot("Neck", lambda t: (0, 0, -7 * shake(t) * S(TAU * t / 0.2 - 1.2)))
    a.rot("Head", lambda t: (12 * bump(T(16), T(30), t), 0, 0))
    mirror(a, "Ear_right", "Ear_left", "rotation", lambda t, s: (-14 * bump(T(16), T(30), t), 0, 18 * shake(t) * S(TAU * t / 0.2 - 2)))
    mirror(a, "Cheek_right", "Cheek_left", "rotation", lambda t, s: (0, 0, 6 * shake(t) * S(TAU * t / 0.2 - 2.2)))
    mirror(a, "Cheek_right_empty", "Cheek_left_empty", "rotation", lambda t, s: (0, 0, 6 * shake(t) * S(TAU * t / 0.2 - 2.2)))
    a.scale("Body", lambda t: (1, 1 + 0.04 * bump(T(16), T(30), t), 1))
    A.append(a)

    # ------------------------------------------------------------------ groom: 2.4 s (48 ticks), ambient
    # Sits back on its haunches and washes its face with both front paws (the hamster's own toilet), eyes shut,
    # then a shake of the head and back on all fours.
    a = Anim("groom", 48)
    sit = lambda t: smooth(T(2), T(9), t) * (1 - smooth(T(38), T(45), t))            # noqa: E731
    rub = lambda t: smooth(T(9), T(12), t) * (1 - smooth(T(34), T(38), t))           # noqa: E731
    a.rot("Body", lambda t: (28 * sit(t), 0, 0))
    for leg in BACK:
        a.rot(leg, lambda t: (-28 * sit(t), 0, 0))
    mirror(a, "Leg_front_right", "Leg_front_left", "rotation",
           lambda t, s: (-34 * sit(t) - 30 * rub(t) - 14 * rub(t) * (0.5 + 0.5 * S(TAU * 3 * t + (0 if s > 0 else 2.2))), 0,
                         14 * s * rub(t)))
    mirror(a, "Leg_front_right", "Leg_front_left", "position", lambda t, s: (-1.2 * s * rub(t), 1.5 * rub(t), -1.5 * rub(t)))
    a.rot("Head", lambda t: (-24 * sit(t) - 6 * rub(t) * S(TAU * 3 * t), 6 * rub(t) * S(TAU * 1.5 * t),
                             10 * bump(T(38), T(46), t) * S(TAU * (t - T(38)) / 0.2)))
    lids(a, lambda t: rub(t))
    both(a, ("Ear_right", "Ear_left"), "rotation", lambda t: (14 * rub(t), 0, 0))
    cheeks(a, lambda t: sc(1 + 0.04 * rub(t) * S(TAU * 3 * t)))
    PLANT["groom"] = {leg: [(0, T(48))] for leg in BACK}
    PLANT["groom"].update({leg: [(0, T(3)), (T(45), T(48))] for leg in FRONT})
    A.append(a)

    # ------------------------------------------------------------------ sniff_air: 1.2 s (24 ticks), ambient
    # Lifts its head high, sniffs the air (nose twitching fast), ears forward, looks left then right.
    a = Anim("sniff_air", 24)
    up = lambda t: smooth(0, T(5), t) * (1 - smooth(T(19), T(24), t))                # noqa: E731
    a.rot("Body", lambda t: (6 * up(t), 0, 0))
    a.rot("Head", lambda t: (20 * up(t), 14 * bump(T(6), T(13), t) - 14 * bump(T(12), T(19), t), 0))
    a.pos("Nose", lambda t: (0, 0.4 * up(t) * S(TAU * 11 * t), 0))
    a.rot("Muzzle", lambda t: (3 * up(t) * S(TAU * 11 * t + 1), 0, 0))
    both(a, ("Ear_right", "Ear_left"), "rotation", lambda t: (-16 * up(t), 0, 0))
    blink(a, 20)
    A.append(a)

    return A, PLANT, LOCK


# =========================================================================== posing (with scale)
def _rot(x, y, z):
    from apercu import rot_matrix
    return rot_matrix(x, y, z)


def _mul(A, B):
    return [[sum(A[i][k] * B[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


def _app(M, v):
    return [sum(M[i][k] * v[k] for k in range(3)) for i in range(3)]


def pose(model, values, tick, hidden=()):
    """Cubes of the model at this tick of `values` ({bone: {channel: [real values per tick]}}): each element gets
    its final matrix and pivot (_M, _q); hidden bones are left out. Returns (elements, {bone: [points]})."""
    by_uuid = {e["uuid"]: e for e in model["elements"]}
    out, pts = [], {}

    def walk(group, B, c, hide):
        name = group["name"]
        o = group["origin"]
        v = values.get(name, {})
        r = v["rotation"][tick] if "rotation" in v else (0, 0, 0)
        p = v["position"][tick] if "position" in v else (0, 0, 0)
        s = v["scale"][tick] if "scale" in v else (1, 1, 1)
        R = _mul(_rot(r[0], r[1], r[2]), [[s[0], 0, 0], [0, s[1], 0], [0, 0, s[2]]])
        Bn = _mul(B, R)
        shift = _app(B, [o[i] + p[i] for i in range(3)])
        cn = [shift[i] + c[i] - _app(Bn, o)[i] for i in range(3)]
        hide = hide or name in hidden
        for child in group.get("children", []):
            if isinstance(child, dict):
                walk(child, Bn, cn, hide)
            elif child in by_uuid and not hide:
                e = dict(by_uuid[child])
                M = _rot(*(e.get("rotation") or [0, 0, 0]))
                eo = e["origin"]
                e["_M"] = _mul(Bn, M)
                e["_q"] = [_app(Bn, eo)[i] + cn[i] for i in range(3)]
                e["_bone"] = name
                out.append(e)
                for cx in (e["from"][0], e["to"][0]):
                    for cy in (e["from"][1], e["to"][1]):
                        for cz in (e["from"][2], e["to"][2]):
                            q = _app(e["_M"], [cx - eo[0], cy - eo[1], cz - eo[2]])
                            pts.setdefault(name, []).append([q[i] + e["_q"][i] for i in range(3)])

    I3 = [[1, 0, 0], [0, 1, 0], [0, 0, 1]]
    for g in model["outliner"]:
        walk(g, I3, [0, 0, 0], False)
    return out, pts


def bone_world(model, values, tick, bone):
    """Matrix of the parent of `bone` at this tick (to turn a world offset into a position in its parent)."""
    def find(g, B):
        name = g["name"]
        v = values.get(name, {})
        r = v["rotation"][tick] if "rotation" in v else (0, 0, 0)
        s = v["scale"][tick] if "scale" in v else (1, 1, 1)
        Bn = _mul(B, _mul(_rot(*r), [[s[0], 0, 0], [0, s[1], 0], [0, 0, s[2]]]))
        for ch in g.get("children", []):
            if isinstance(ch, dict):
                if ch["name"] == bone:
                    return Bn
                f = find(ch, Bn)
                if f is not None:
                    return f
        return None
    for g in model["outliner"]:
        f = find(g, [[1, 0, 0], [0, 1, 0], [0, 0, 1]])
        if f is not None:
            return f


def settle(model, anim, values, plant, lock):
    """Body: never under the ground, touching it in the lock windows; planted legs: lowest point exactly on the
    ground (blended over 2 ticks at the edges of their windows)."""
    n = anim.ticks + 1
    inside = lambda wins, t: any(a - 1e-6 <= t <= b + 1e-6 for a, b in wins)       # noqa: E731
    body = values.setdefault("Body", {})
    body.setdefault("position", [(0, 0, 0)] * n)
    for tick in range(n):
        t = T(tick)
        _, pts = pose(model, values, tick, hidden=CHEEKS_EMPTY)
        nonleg = [p[1] for b, ps in pts.items() if b not in LEGS for p in ps]
        allp = [p[1] for ps in pts.values() for p in ps]
        dy = 0.0
        if inside(lock, t):
            dy = -min(allp)
        elif min(nonleg) < 0:
            dy = -min(nonleg)
        if dy:
            x, y, z = body["position"][tick]
            body["position"][tick] = (x, y + dy, z)
    for leg in LEGS:
        wins = plant.get(leg)
        if not wins:
            continue
        vals = values.setdefault(leg, {})
        vals.setdefault("position", [(0, 0, 0)] * n)
        corr = []
        for tick in range(n):
            if not inside(wins, T(tick)):
                corr.append(None)
                continue
            d = 0.0
            for _ in range(2):
                tmp = dict(values)
                tmp[leg] = dict(vals)
                x, y, z = vals["position"][tick]
                tmp[leg]["position"] = list(vals["position"])
                tmp[leg]["position"][tick] = (x, y + d, z)
                _, pts = pose(model, tmp, tick, hidden=CHEEKS_EMPTY)
                low = min(p[1] for p in pts[leg])
                B = bone_world(model, tmp, tick, leg)
                d -= low / max(0.3, B[1][1])
            corr.append(d)
        filled = []
        for i, cv in enumerate(corr):
            if cv is not None:
                filled.append(cv)
                continue
            prev = [(j, corr[j]) for j in range(i - 1, max(-1, i - 3), -1) if corr[j] is not None]
            nxt = [(j, corr[j]) for j in range(i + 1, min(n, i + 3)) if corr[j] is not None]
            v = 0.0
            if prev:
                v += prev[0][1] * max(0, 1 - (i - prev[0][0]) / 3)
            if nxt:
                v += nxt[0][1] * max(0, 1 - (nxt[0][0] - i) / 3)
            filled.append(v)
        if anim.loop:
            filled[-1] = filled[0]
        vals["position"] = [(x, y + c, z) for (x, y, z), c in zip(vals["position"], filled)]
    if anim.loop:
        for bone, chans in values.items():
            for ch, vs in chans.items():
                vs[-1] = vs[0]


# =========================================================================== writing
def build(model, uid):
    """Blockbench animations of the model (sampled every tick), with settle() applied."""
    anims, plant, lock = animations()
    out, n = [], [0]
    for a in anims:
        values = a.sample()
        settle(model, a, values, plant.get(a.name, {leg: [(0, a.length)] for leg in LEGS}), lock.get(a.name, []))
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


def hidden_at(name, tick):
    start, switches = CHEEK_STATE.get(name, ("full", ()))
    state = start
    for at, st in switches:
        if tick >= at:
            state = st
    return CHEEKS_EMPTY if state == "full" else CHEEKS_FULL


def measure(model, anims):
    """Per animation: lowest point by tick and the mouth (front of the muzzle) at each tick, in blocks."""
    res = {}
    for a in anims:
        vals = a["_values"]
        rows = []
        for tick in range(int(round(a["length"] * 20)) + 1):
            _, pts = pose(model, vals, tick, hidden=hidden_at(a["name"], tick))
            allp = [p for ps in pts.values() for p in ps]
            mz = pts.get("Muzzle", [])
            front = min(p[2] for p in mz)
            mouth = [p for p in mz if p[2] < front + 1.5]
            rows.append({"tick": tick, "low": round(min(p[1] for p in allp) / 16, 2),
                         "high": round(max(p[1] for p in allp) / 16, 2),
                         "mouth": [round(sum(p[i] for p in mouth) / len(mouth) / 16, 2) for i in range(3)]})
        res[a["name"]] = rows
    return res


def strips(model, anims, atlases, sizes, folder, count=10, only=None):
    """One PNG per animation (profile above, three quarters below), fixed framing, ground line."""
    import paths  # noqa: F401
    import apercu as A
    from bbkit import combined, flat_elements
    from png_util import write
    A.transpose = _inverse          # scaled bones: the renderer needs a true inverse, not a transpose
    os.makedirs(folder, exist_ok=True)
    flat = dict(model, elements=flat_elements(model, sizes))
    combo = combined(atlases, sizes)
    size = 170
    for a in anims:
        if only and a["name"] not in only:
            continue
        n = int(round(a["length"] * 20))
        ticks = sorted({round(n * i / (count - 1)) for i in range(count)})
        frames = [pose(flat, a["_values"], tk, hidden=hidden_at(a["name"], tk))[0] for tk in ticks]
        pts = []
        for fe in frames:
            for e in fe:
                for cx in (e["from"][0], e["to"][0]):
                    for cy in (e["from"][1], e["to"][1]):
                        for cz in (e["from"][2], e["to"][2]):
                            q = _app(e["_M"], [cx - e["origin"][0], cy - e["origin"][1], cz - e["origin"][2]])
                            pts.append([q[i] + e["_q"][i] for i in range(3)])
        rows = []
        for yaw, pitch in ((90, 0), (32, 14)):
            imgs = [A.render(fe, combo, yaw, pitch, size=size, frame_points=pts) for fe in frames]
            rows += [sum((im[y] for im in imgs), []) for y in range(size)]
        write(os.path.join(folder, f"anim_{a['name']}.png"), size * len(frames), 2 * size, rows)
        print(f"{a['name']}: ticks {ticks}")


def _inverse(M):
    a, b, c = M[0]
    d, e, f = M[1]
    g, h, i = M[2]
    det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
    if abs(det) < 1e-12:
        return [[M[j][k] for j in range(3)] for k in range(3)]
    return [[(e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det],
            [(f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det],
            [(d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det]]


def finish(anims):
    """Removes the helper values before writing the model."""
    for a in anims:
        a.pop("_values", None)
    return anims


def uid_for(ns, ident):
    return lambda name: str(uuid.uuid5(ns, f"{ident}/{name}"))

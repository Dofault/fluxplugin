#!/usr/bin/env python3
"""Animations of the Sylvan Spider (L'Araignée Sylvestre, boss of the forester dungeon), for araignee_sylvestre.py.

Locomotion (idle, walk, run and its two transitions pounce / halt, shuffle when it turns on the spot), behaviour
(watch, probe, threat), the entrance (dormant, awaken), the four attacks (bite, volley, snare, grove and the exhausted
window after it), hurt and death (played on a stand-in model once the boss is dead: the victory never waits).

Every timing that matters to the game is in EVENTS below, written in docs/technique/boss/araignee-sylvestre.md and read
by FluxDungeons (mob/SpiderPowers.java, spells/sylviculteur/tisseuse_*.yml): keep the three in step. Ticks are 1/20 s.

How it is built: an animation is a function of time giving a frame (the pose of every part, see `frame`): the body's
offset and rotation, the abdomen, the head, the chelicerae, the pedipalps, the opening of each bark plate, and the
place of each of the eight feet. The legs are then solved by inverse kinematics (`solve`): each leg is two bones (upper
segment from the hip, lower segment from the knee), turned so that the tip of the lower segment lands exactly where the
frame puts the foot, the knee bending up and out. A foot left where it is never moves, whatever the body does: no
sliding, the body really weighs on its legs. Gaits move the planted feet back at the speed of the ground (`WALK_SPEED`,
`RUN_SPEED`, same as SpiderPowers): walk and run do not slide either.

The abdomen hangs on the body with some inertia (`lag`): it follows the body's moves late, more in the run.

Values: rotation in degrees in the model's axes (x > 0 lifts the front of a bone, so it lowers a plate's rear edge and
a negative x opens a plate or raises the abdomen; y > 0 turns the front to the spider's left; z > 0 lifts its right
side and opens the right chelicera outwards), positions in pixels (16 = 1 block), front towards -z. Written in the
Blockbench / BetterModel convention by build().
"""
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import paths  # noqa: F401,E402
from golem_fonderie import app, euler, mul, rot  # noqa: E402
from hamster_moissonneur_anim import Anim, T, bump, keys, pose, smooth  # noqa: E402

TAU = 2 * math.pi
S, C = math.sin, math.cos
SIDES = ("right", "left")
LEGS = tuple(f"{n}_{s}" for n in (1, 2, 3, 4) for s in SIDES)
PLATES = 6

# Key ticks read by the game (SpiderPowers): keep in step with docs/technique/boss/araignee-sylvestre.md.
EVENTS = {
    "awaken": {"first_leg": 20, "second_leg": 28, "contraction": 30, "eyes": 38, "unfold": 42, "mandibles": 66,
               "plates": 70, "threat": 80, "scream": 86, "guard": 110},
    "bite": {"warn": 2, "lock": 12, "lunge": 15, "impact": 18, "end": 34},
    "volley": {"plates": 6, "thorns": 10, "shot_1": 22, "shot_2": 28, "shot_3": 34, "close": 40, "end": 56},
    "snare": {"zones": 6, "red": 20, "cast": 28, "webs": 32, "end": 60},
    "grove": {"root": 0, "crown": 30, "weave": 50, "strike_1": 80, "strike_2": 92, "strike_3": 104, "fold": 115,
              "eyes_off": 120, "end": 140},
    "exhausted": {"eyes_on": 56, "end": 60},
    "pounce": {"launch": 5, "end": 8},
    "death": {"eyes_off": 40, "down": 50, "end": 70},
}

# Ground speeds the gaits are drawn for, pixels per second: walk 1.4 blocks a second, run 3.05 (SpiderPowers moves
# it at exactly these speeds). One cycle covers 1.68 blocks in both gaits, two groups of feet a cycle: one plant of a
# group every 0.84 block, the step sounds follow it.
WALK_L, WALK_SPEED = 1.2, 1.4 * 16
RUN_L, RUN_SPEED = 0.55, 3.05 * 16
# the two alternating groups of four legs (alternating tetrapod, as real spiders walk)
GROUP_A = ("1_left", "2_right", "3_left", "4_right")


def tk(*pts):
    """keys() over ticks instead of seconds: tk((0, v0), (8, v1), ...) -> f(t)."""
    return lambda t: keys(t * 20, list(pts))


def b(a, z, t):
    """bump() over ticks."""
    return bump(a / 20, z / 20, t)


def sm(a, z, t):
    """smooth() over ticks."""
    return smooth(a / 20, z / 20, t)


def tremor(t, amp, freq=13.0, seed=0.0):
    return amp * (S(TAU * freq * t + seed) * 0.6 + S(TAU * freq * 1.7 * t + 1.3 + seed) * 0.4)


def lerp(a, b_, k):
    if isinstance(a, (tuple, list)):
        return tuple(x + (y - x) * k for x, y in zip(a, b_))
    return a + (b_ - a) * k


def vadd(*vs):
    return tuple(sum(v[i] for v in vs) for i in range(3))


# =========================================================================== rig
class Rig:
    """Bone pivots, and for each leg its hip, knee and foot (tip of the lower segment) at rest."""

    def __init__(self, model):
        self.o = {}

        def walk(g):
            for c in g.get("children", []):
                if isinstance(c, dict):
                    self.o[c["name"]] = list(c["origin"])
                    walk(c)
        walk(model["outliner"][0])
        by_name = {e["name"]: e for e in model["elements"]}
        self.hip, self.knee, self.foot, self.a, self.b, self.out = {}, {}, {}, {}, {}, {}
        for leg in LEGS:
            n, side = leg.split("_")
            sd = "d" if side == "right" else "g"
            e = by_name[f"patte_{n}_{sd}_bas"]
            M = rot(*(e.get("rotation") or [0, 0, 0]))
            ey = [M[0][1], M[1][1], M[2][1]]
            ln = e["to"][1] - e["from"][1]
            c = e["origin"]
            H, K = self.o[f"Leg_{leg}"], self.o[f"Leg_{leg}_low"]
            F = [c[i] - ey[i] * ln / 2 for i in range(3)]
            self.hip[leg], self.knee[leg], self.foot[leg] = H, K, F
            self.a[leg], self.b[leg] = dist(H, K), dist(K, F)
            h = [F[0] - H[0], 0, F[2] - H[2]]
            n_ = math.hypot(h[0], h[2])
            self.out[leg] = (h[0] / n_, 0.0, h[2] / n_)     # horizontal, from the hip towards the foot


def dist(p, q):
    return math.sqrt(sum((p[i] - q[i]) ** 2 for i in range(3)))


def norm(v):
    n = math.sqrt(sum(x * x for x in v))
    return [x / n for x in v] if n > 1e-9 else [0.0, 0.0, 0.0]


def cross(u, v):
    return [u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0]]


def dot(u, v):
    return sum(u[i] * v[i] for i in range(3))


def transpose(M):
    return [[M[j][i] for j in range(3)] for i in range(3)]


def frame3(e1, plane):
    """Orthonormal frame (columns): e1, the normal of the plane of e1 and `plane`, and their cross product."""
    e1 = norm(e1)
    n = norm(cross(e1, plane))
    e3 = cross(e1, n)
    return [[e1[i], n[i], e3[i]] for i in range(3)]


def solve(rig, leg, target):
    """Two-bone IK in the body's frame: rotations (upper bone around the hip, lower bone around the knee, in the upper
    bone's frame) bringing the foot onto `target`, the knee bent up and out. Returns (R1, R2, shortfall in pixels)."""
    H, K, F = rig.hip[leg], rig.knee[leg], rig.foot[leg]
    a, b_ = rig.a[leg], rig.b[leg]
    v = [target[i] - H[i] for i in range(3)]
    d = math.sqrt(dot(v, v))
    lo, hi = abs(a - b_) + 0.5, a + b_ - 0.4
    short = max(0.0, d - hi, lo - d)
    d = max(lo, min(hi, d))
    u = norm(v)
    T_ = [H[i] + u[i] * d for i in range(3)]                       # reachable target
    hint = [K[i] + rig.out[leg][i] * 3.0 + (6.0 if i == 1 else 0.0) - H[i] for i in range(3)]
    side = norm([hint[i] - u[i] * dot(hint, u) for i in range(3)])
    x = (a * a - b_ * b_ + d * d) / (2 * d)
    h = math.sqrt(max(0.0, a * a - x * x))
    K2 = [H[i] + u[i] * x + side[i] * h for i in range(3)]
    A0 = frame3([K[i] - H[i] for i in range(3)], [F[i] - K[i] for i in range(3)])
    A1 = frame3([K2[i] - H[i] for i in range(3)], [T_[i] - K2[i] for i in range(3)])
    R1 = mul(A1, transpose(A0))
    B0 = frame3([F[i] - K[i] for i in range(3)], [K[i] - H[i] for i in range(3)])
    B1 = frame3([T_[i] - K2[i] for i in range(3)], [K2[i] - H[i] for i in range(3)])
    W2 = mul(B1, transpose(B0))
    R2 = mul(transpose(R1), W2)
    return R1, R2, short


# =========================================================================== frames
def frame(**kw):
    """A pose: body (offset px, rotation deg), abd (rotation of the abdomen), head (rotation), head_pos, chel (opening of
    both chelicerae, deg, outwards), chel_x (both swung forwards), palp_r / palp_l (rotations, the left one given as if
    it were the right one), plates (opening of each plate, deg), feet {leg: offset of the foot from rest, px}."""
    f = {"body": (0, 0, 0), "body_rot": (0, 0, 0), "abd": (0, 0, 0), "head": (0, 0, 0), "head_pos": (0, 0, 0),
         "chel": 0.0, "chel_x": 0.0, "palp_r": (0, 0, 0), "palp_l": (0, 0, 0), "plates": (0.0,) * PLATES, "feet": {}}
    f.update(kw)
    return f


REST = frame()


def mix(f, g, k):
    """Frame between f (k = 0) and g (k = 1)."""
    out = {}
    for key in f:
        if key == "feet":
            out[key] = {leg: lerp(f["feet"].get(leg, (0, 0, 0)), g["feet"].get(leg, (0, 0, 0)), k) for leg in LEGS}
        else:
            out[key] = lerp(f[key], g[key], k)
    return out


def feet(fn):
    """{leg: offset} built from fn(leg)."""
    return {leg: fn(leg) for leg in LEGS}


def arc(p, q, u, lift):
    """Foot moving from offset p to offset q (u from 0 to 1), lifted `lift` pixels in the middle."""
    k = u * u * (3 - 2 * u)
    o = lerp(p, q, k)
    return (o[0], o[1] + lift * math.sin(math.pi * max(0.0, min(1.0, u))), o[2])


def step(t, t0, t1, p, q, lift):
    """Foot at p before tick t0, stepping to q between t0 and t1, at q after."""
    u = (t * 20 - t0) / (t1 - t0)
    if u <= 0:
        return tuple(p)
    if u >= 1:
        return tuple(q)
    return arc(p, q, u, lift)


def outward(rig, leg, px, y=0.0, fwd=0.0):
    """Offset of `px` pixels away from the hip (horizontally), `y` up, `fwd` forwards (-z)."""
    o = rig.out[leg]
    return (o[0] * px, y, o[2] * px - fwd)


def folded(rig, leg, share=0.45):
    """Foot drawn in towards its hip by `share` of its reach (a spider folded on itself)."""
    H, F = rig.hip[leg], rig.foot[leg]
    return ((H[0] - F[0]) * share, 0.0, (H[2] - F[2]) * share)


def gait_feet(rig, t, L, speed, duty, lift, offsets, front_reach=0.0, tuck=0.0, shift=None):
    """Feet of a gait whose planted feet move back at the speed of the ground. `offsets` {leg: phase}, `front_reach`
    extra forward overshoot of the front legs in the air, `tuck` share of the reach drawn in, `shift` {leg: offset}."""
    stride = speed * duty * L
    out = {}
    for leg in LEGS:
        u = (t / L + offsets[leg]) % 1.0
        base = folded(rig, leg, tuck) if tuck else (0.0, 0.0, 0.0)
        if shift and leg in shift:
            base = vadd(base, shift[leg])
        front = leg.startswith("1_")
        up = lift * (1.35 if front else 1.0)
        if u < duty:
            z = -stride / 2 + stride * u / duty
            out[leg] = (base[0], base[1], base[2] + z)
        else:
            v = (u - duty) / (1 - duty)
            k = v * v * (3 - 2 * v)
            over = (front_reach if front else 0.0) * math.sin(math.pi * v)
            z = stride / 2 - stride * k - over
            out[leg] = (base[0], base[1] + up * math.sin(math.pi * v), base[2] + z)
    return out


def tetrapod(ripple=0.035):
    """Phases of the alternating tetrapod: group A at 0, group B at 0.5, each group stepping from back to front."""
    return {leg: (0.0 if leg in GROUP_A else 0.5) + ripple * (4 - int(leg[0])) for leg in LEGS}


# =========================================================================== animations
def animations(rig):
    out = []

    def add(name, ticks, fn, loop=False, lag=0.12, lag_gain=0.75):
        out.append((name, ticks, loop, fn, lag, lag_gain))

    # ------------------------------------------------------------------ idle: 5 s, a spider on watch
    # insect breathing: small quick settles of the body, not a slow swell; two legs lift and plant again at their own
    # moment, the front right one taps the ground twice; the chelicerae part a little, the pedipalps feel the air;
    # the head jerks a few degrees (an insect moves its gaze by jolts)
    def idle(t):
        settle = -0.35 * b(4, 9, t) - 0.3 * b(46, 50, t) - 0.25 * b(78, 83, t)
        look = tk((0, 0), (14, 0), (16, 4), (40, 4), (42, -3), (70, -3), (72, 0), (100, 0))(t)
        return frame(
            body=(0.15 * S(TAU * t / 5), settle + 0.12 * S(TAU * t / 1.25), 0),
            body_rot=(0.25 * S(TAU * t / 2.5), look * 0.25, 0.4 * S(TAU * t / 5 + 1)),
            abd=(-1.2 * b(22, 34, t) - 0.9 * b(66, 76, t) + 0.6 * S(TAU * t / 2.5 + 2), 0.6 * S(TAU * t / 5), 0),
            head=(-1.5 * b(30, 44, t), look, 0),
            chel=6 * b(32, 42, t) + 3 * b(86, 89, t) + 2 * b(90, 93, t),
            palp_r=(10 * S(TAU * t / 2.5) * b(0, 50, t), 0, 6 * b(20, 40, t)),
            palp_l=(10 * S(TAU * t / 2.5 + 1.5) * b(50, 100, t), 0, 6 * b(60, 80, t)),
            plates=tuple(0.8 * b(22 + i, 34 + i, t) for i in range(PLATES)),
            feet={"2_left": step(t, 16, 25, (0, 0, 0), (0, 0, 0), 4.0),
                  "3_right": step(t, 52, 61, (0, 0, 0), (0, 0, 0), 3.5),
                  "1_right": vadd(step(t, 68, 74, (0, 0, 0), (0, 0, -1.5), 4.5), step(t, 76, 82, (0, 0, 0), (0, 0, 1.5), 4.5)),
                  "4_left": step(t, 88, 96, (0, 0, 0), (0, 0, 0), 3.0)})
    add("idle", 100, idle, loop=True, lag=0.2, lag_gain=0.6)

    # ------------------------------------------------------------------ walk: 24 ticks, the hunter's progress
    # alternating tetrapod, each group back to front, every foot on the ground 62 % of the cycle (moving back exactly
    # at the speed of the ground); the body sinks a little on each plant (twice a cycle) and rolls with the groups;
    # front legs lift higher and reach further; the abdomen follows late
    phases = tetrapod()

    def walk(t):
        u = t / WALK_L
        return frame(
            body=(0, -0.6 - 0.55 * C(TAU * 2 * u), 0.4 * S(TAU * 2 * u)),
            body_rot=(0.6 * S(TAU * 2 * u + 0.8), 0, 1.4 * S(TAU * u)),
            abd=(1.0 * S(TAU * 2 * u - 0.6), -1.8 * S(TAU * u - 0.7), 0),
            head=(-0.6 * S(TAU * 2 * u + 0.8), 0, -1.0 * S(TAU * u)),
            chel=2 + 2 * S(TAU * u),
            palp_r=(12 * S(TAU * u), 0, 4), palp_l=(12 * S(TAU * u + math.pi), 0, 4),
            feet=gait_feet(rig, t, WALK_L, WALK_SPEED, 0.62, 4.5, phases, front_reach=3.0))
    add("walk", int(WALK_L * 20), walk, loop=True, lag=0.14, lag_gain=0.8)

    # ------------------------------------------------------------------ run: 11 ticks, the charge
    # body down, abdomen close to the ground, legs folded more (feet drawn in), front legs thrown far ahead, the hind
    # ones push off far behind, the body surges forward on each push; the abdomen swings hard
    run_phase = tetrapod(0.05)
    run_shift = {leg: ((0, 0, -5.0) if leg.startswith("1_") else (0, 0, 4.0) if leg.startswith("4_") else (0, 0, 0))
                 for leg in LEGS}

    def run(t):
        u = t / RUN_L
        return frame(
            body=(0, -3.6 - 0.9 * C(TAU * 2 * u), -1.2 * S(TAU * 2 * u)),
            body_rot=(-2.5 + 1.6 * S(TAU * 2 * u + 0.4), 0, 1.8 * S(TAU * u)),
            abd=(7.5 + 3.5 * S(TAU * 2 * u - 1.2), -3.0 * S(TAU * u - 0.9), 0),
            head=(2.0 - 1.4 * S(TAU * 2 * u + 0.4), 0, -1.8 * S(TAU * u)),
            chel=8, chel_x=-6,
            palp_r=(-20, 0, 10), palp_l=(-20, 0, 10),
            feet=gait_feet(rig, t, RUN_L, RUN_SPEED, 0.45, 5.5, run_phase, front_reach=5.0, tuck=0.12, shift=run_shift))
    add("run", int(RUN_L * 20), run, loop=True, lag=0.08, lag_gain=1.1)

    # ------------------------------------------------------------------ pounce: 8 ticks, walk -> run (a spring let go)
    # 0-5 the body drops and the abdomen tucks in, knees rising over the planted feet; 5-8 it springs forwards and ends
    # on the first pose of the run
    start = run(0)
    crouch = frame(body=(0, -4.2, 2.0), body_rot=(-3, 0, 0), abd=(9, 0, 0), head=(2, 0, 0), chel=6, chel_x=-4,
                   palp_r=(-15, 0, 8), palp_l=(-15, 0, 8))

    def pounce(t):
        if t * 20 <= 5:
            return mix(REST, crouch, sm(0, 5, t))
        f = mix(crouch, start, sm(5, 8, t))
        f["body"] = vadd(f["body"], (0, 1.5 * b(5, 8, t), -3.0 * b(5, 8, t)))
        f["feet"] = {leg: vadd(f["feet"][leg], (0, 4.0 * b(5, 8, t), 0)) for leg in LEGS}
        return f
    add("pounce", 8, pounce, lag=0.08, lag_gain=1.2)

    # ------------------------------------------------------------------ halt: 10 ticks, run -> still
    # the front legs brace, the abdomen swings on forwards by its weight, every foot steps back to its place
    def halt(t):
        f = mix(start, REST, sm(0, 10, t))
        f["abd"] = vadd(f["abd"], (-9 * b(0, 9, t), 0, 0))
        f["body_rot"] = vadd(f["body_rot"], (-3 * b(0, 8, t), 0, 0))
        f["feet"] = {leg: vadd(f["feet"][leg], (0, 3.5 * b(0 if leg in GROUP_A else 3, 7 if leg in GROUP_A else 10, t), 0))
                     for leg in LEGS}
        return f
    add("halt", 10, halt, lag=0.1, lag_gain=1.2)

    # ------------------------------------------------------------------ shuffle: 12 ticks loop, turning on the spot
    # the two groups lift in turn, small steps, the body still: plays while the spider turns without walking
    def shuffle(t):
        u = t / 0.6
        return frame(body=(0, -0.4 - 0.3 * C(TAU * 2 * u), 0), chel=3,
                     feet={leg: (0, 3.0 * max(0.0, S(TAU * (u + (0 if leg in GROUP_A else 0.5)))), 0) for leg in LEGS})
    add("shuffle", 12, shuffle, loop=True)

    # ------------------------------------------------------------------ watch: 30 ticks, it freezes and weighs its prey
    stillness = frame(body=(0, -1.2, -0.6), body_rot=(-1.5, 0, 0), abd=(-3, 0, 0), head=(-2, 0, 0), chel=7,
                      palp_r=(-18, 0, 6), palp_l=(-18, 0, 6))

    def watch(t):
        k = sm(0, 4, t) * (1 - sm(24, 30, t))
        return mix(REST, stillness, k)
    add("watch", 30, watch)

    # ------------------------------------------------------------------ probe: 36 ticks, a feint towards a still prey
    # both front legs rise, reach forwards and tap the ground ahead (left, then right), then come back; no attack
    def probe(t):
        lift = {"1_right": vadd(step(t, 4, 12, (0, 0, 0), (0, 2, -6), 9), step(t, 20, 28, (0, 0, 0), (0, -2, 6), 6)),
                "1_left": vadd(step(t, 8, 16, (0, 0, 0), (0, 0, -7), 9), step(t, 24, 32, (0, 0, 0), (0, 0, 7), 6))}
        k = b(2, 32, t)
        return frame(body=(0, -1.0 * k, -2.0 * k), body_rot=(-2.5 * k, 0, 0), abd=(-4 * k, 0, 0), head=(-3 * k, 0, 0),
                     chel=10 * k + 3 * b(14, 17, t), palp_r=(-25 * k, 0, 8 * k), palp_l=(-25 * k, 0, 8 * k), feet=lift)
    add("probe", 36, probe)

    # ------------------------------------------------------------------ threat: 40 ticks, it rears up to intimidate
    # the front rises, both front legs lift high and wide, the chelicerae open, then it drops back down with a stamp
    def threat(t):
        k = sm(2, 12, t) * (1 - sm(28, 36, t))
        high = lambda leg: outward(rig, leg, 3.0, 15.0, 3.0)                                  # noqa: E731
        fl = {leg: lerp((0, 0, 0), high(leg), k) for leg in ("1_right", "1_left")}
        fl.update({leg: lerp((0, 0, 0), (0, 5.0, -1.0), k) for leg in ("2_right", "2_left")})
        return frame(body=(0, 3.0 * k, 1.5 * k), body_rot=(12 * k, 0, 1.5 * S(TAU * t * 2) * k), abd=(10 * k, 0, 0),
                     head=(-4 * k, 0, 0), chel=26 * k + 4 * S(TAU * 9 * t) * k, chel_x=-10 * k,
                     palp_r=(-35 * k, 0, 14 * k), palp_l=(-35 * k, 0, 14 * k),
                     plates=tuple(4 * k for _ in range(PLATES)), feet=fl)
    add("threat", 40, threat)

    # ------------------------------------------------------------------ dormant: a heap of dead wood (loop, still)
    def folded_feet(share=0.45):
        return {leg: folded(rig, leg, share) for leg in LEGS}
    dormant_f = frame(body=(0, -4.2, 0), body_rot=(3, 0, 2), abd=(-7, 0, -2), head=(-9, 0, 0), chel=-4, chel_x=14,
                      palp_r=(30, 0, -6), palp_l=(30, 0, -6), feet=folded_feet())
    add("dormant", 20, lambda t: dormant_f, loop=True)

    # ------------------------------------------------------------------ awaken: 110 ticks, the weaver wakes up
    # 0-20 dead wood; 20 one leg unfolds slowly, 28 a second answers, 30 the abdomen contracts, 38 the eyes light up;
    # 42-76 the other legs unfold one by one, out of step, the body rises; 66 the chelicerae open and close; 70 the hind
    # legs spread, the front ones lift, the plates shiver; 80-92 the threat: front legs thrust forwards, body lowered,
    # chelicerae spread, scream at 86; 100-110 it steps into its guard
    order = (("1_right", 20, 38, 3.5), ("4_left", 28, 42, 3.0), ("2_right", 42, 54, 4.0), ("3_left", 46, 58, 4.0),
             ("1_left", 50, 62, 5.0), ("2_left", 56, 68, 4.0), ("3_right", 60, 72, 4.0), ("4_right", 64, 76, 3.5))

    def awaken(t):
        f = dict(dormant_f)
        rise = tk((0, -4.2), (30, -4.2), (44, -3.0), (72, 0.0), (80, 1.5), (84, 1.5), (90, -2.0), (100, -2.0), (108, 0.0))(t)
        f["body"] = (0, rise, tk((0, 0), (82, 0), (88, -2.5), (100, -2.5), (108, 0))(t))
        f["body_rot"] = (tk((0, 3), (44, 2), (72, 0), (82, 0), (88, -4), (100, -4), (108, 0))(t) + tremor(t, 0.5) * b(20, 40, t),
                         0, tk((0, 2), (60, 0))(t))
        f["abd"] = (tk((0, -7), (30, -7), (33, -2), (36, -7), (66, -7), (78, -4), (100, -4), (108, 0))(t) + 1.5 * tremor(t, 1) * b(70, 80, t),
                    0, tk((0, -2), (50, 0))(t))
        f["head"] = (tk((0, -9), (36, -9), (60, -2), (82, -2), (88, -7), (100, -7), (108, 0))(t), 0, 0)
        f["chel"] = tk((0, -4), (60, -4), (66, 22), (70, 0), (74, 4), (82, 4), (88, 36), (98, 36), (106, 2), (110, 0))(t) \
            + 3 * tremor(t, 1, 17) * b(88, 98, t)
        f["chel_x"] = tk((0, 14), (60, 14), (70, 0), (84, 0), (88, -12), (98, -12), (108, 0))(t)
        pr = tk((0, 30), (40, 30), (60, 0), (80, 0), (86, -30), (100, -30), (108, 0))(t)
        f["palp_r"] = (pr + 6 * S(TAU * t * 1.5) * b(40, 80, t), 0, tk((0, -6), (60, 4), (108, 0))(t))
        f["palp_l"] = (pr + 6 * S(TAU * t * 1.5 + 1) * b(40, 80, t), 0, tk((0, -6), (60, 4), (108, 0))(t))
        f["plates"] = tuple(3.5 * abs(tremor(t, 1, 15, i)) * b(70, 82, t) for i in range(PLATES))
        ft = {}
        for leg, a, z, lift in order:
            p = folded(rig, leg)
            slow = 1.6 if leg == "1_right" else 1.0
            ft[leg] = step(t, a, z, p, (0, 0, 0), lift * slow)
        for leg in ("4_right", "4_left"):                     # hind legs spread wide, then back in guard
            ft[leg] = vadd(ft[leg], step(t, 70 if leg == "4_left" else 74, 80 if leg == "4_left" else 84,
                                         (0, 0, 0), outward(rig, leg, 3.0), 2.5))
            ft[leg] = vadd(ft[leg], step(t, 100, 108, (0, 0, 0), outward(rig, leg, -3.0), 2.0))
        for leg in ("1_right", "1_left"):                     # front legs lift, thrust forwards, plant ahead, step back
            ft[leg] = vadd(ft[leg], (0, 4.0 * b(70, 82, t), 0),
                           step(t, 80, 88, (0, 0, 0), (0, 0, -9), 10.0),
                           step(t, 100, 108, (0, 0, 0), (0, 0, 9), 4.0))
        f["feet"] = ft
        return f
    add("awaken", 110, awaken)

    # ------------------------------------------------------------------ bite: 34 ticks (Ancient bite)
    # 0-15 wind-up: lower, the front of the body inches forwards, abdomen up, chelicerae open slowly; 15-18 the lunge:
    # the body thrown forwards, front legs stabbing ahead, head snapping down, chelicerae wide (16) and shut (18 = hit);
    # 18-34 recovery: pulls back, chelicerae quiver, front feet step back home
    def bite(t):
        lunge = tk((0, 0), (15, -1.5), (18, -12), (22, -11), (30, 0), (34, 0))(t)
        f = frame(
            body=(0, tk((0, 0), (12, -3.0), (15, -3.2), (18, -4.5), (24, -3.0), (32, 0))(t), lunge),
            body_rot=(tk((0, 0), (12, -3), (15, -2), (18, -7), (24, -5), (32, 0))(t), 0, 0),
            abd=(tk((0, 0), (12, -8), (15, -8), (18, -15), (24, -10), (32, 0))(t), 0, 0),
            head=(tk((0, 0), (12, 6), (15, 7), (18, -14), (22, -10), (32, 0))(t), 0, 0),
            chel=tk((0, 0), (14, 30), (16, 46), (18, -6), (20, -2), (32, 0))(t) + 4 * tremor(t, 1, 19) * b(20, 30, t),
            chel_x=tk((0, 0), (14, -6), (16, -14), (18, 6), (26, 0))(t),
            palp_r=(tk((0, 0), (14, -30), (18, 10), (30, 0))(t), 0, tk((0, 0), (14, 14), (30, 0))(t)),
            palp_l=(tk((0, 0), (14, -30), (18, 10), (30, 0))(t), 0, tk((0, 0), (14, 14), (30, 0))(t)),
            feet={leg: vadd(step(t, 14, 18, (0, 0, 0), (0, 0, -9), 5.0), step(t, 24, 31, (0, 0, 0), (0, 0, 9), 3.5))
                  for leg in ("1_right", "1_left")})
        return f
    add("bite", 34, bite, lag=0.1, lag_gain=1.0)

    # ------------------------------------------------------------------ volley: 56 ticks (Needles of the forest)
    # 0-22 hind legs dug in, the front rises with both front legs off the ground, the abdomen rears up and its plates
    # part (thorns show between them, displays of the game); shots at 22, 28, 34, the abdomen contracting on each one;
    # 34-56 plates close, abdomen down, front legs back on the ground
    shots = (22, 28, 34)

    def volley(t):
        kick = sum(b(s - 1, s + 4, t) for s in shots)
        up = sm(2, 14, t) * (1 - sm(38, 50, t))
        f = frame(
            body=(0, 1.2 * up, 1.0 * up), body_rot=(6 * up + 1.5 * kick, 0, 0),
            abd=(-16 * sm(2, 18, t) * (1 - sm(40, 52, t)) + 7 * kick, 0, 0),
            head=(-3 * up, 0, 0), chel=14 * up + 8 * kick, chel_x=-4 * up,
            palp_r=(-20 * up, 0, 8 * up), palp_l=(-20 * up, 0, 8 * up),
            plates=tuple(26 * sm(6 + i, 18 + i, t) * (1 - sm(38 + i, 48 + i, t)) + 4 * kick for i in range(PLATES)),
            feet={leg: vadd((0, (6.0 if leg.startswith("1_") else 3.0) * up + 3.0 * kick * leg.startswith("1_"),
                             -2.0 * up if leg.startswith("1_") else 0.0)) for leg in ("1_right", "1_left", "2_right", "2_left")})
        return f
    add("volley", 56, volley, lag=0.12, lag_gain=0.9)

    # ------------------------------------------------------------------ snare: 60 ticks (Web of roots)
    # 0-26 legs spread one by one, abdomen rears, hind legs dug in, front legs lift and open wide, pedipalps and
    # chelicerae twitch; 28 the cast: the abdomen jerks to fling the fibres; 32 the webs lie on the ground; 40-60 legs
    # put down and drawn back, abdomen down
    def snare(t):
        up = sm(4, 22, t) * (1 - sm(42, 56, t))
        ft = {}
        for i, leg in enumerate(("2_right", "2_left", "3_right", "3_left")):
            ft[leg] = vadd(step(t, 2 + 3 * i, 10 + 3 * i, (0, 0, 0), outward(rig, leg, 4.0), 3.0),
                           step(t, 44 + 2 * i, 52 + 2 * i, (0, 0, 0), outward(rig, leg, -4.0), 3.0))
        for leg in ("1_right", "1_left"):
            ft[leg] = lerp((0, 0, 0), outward(rig, leg, 5.0, 11.0, 2.0), up)
        jerk = b(27, 31, t)
        return frame(
            body=(0, 1.0 * up + 1.2 * jerk, 0), body_rot=(4 * up, 0, 0),
            abd=(-20 * up + 12 * jerk - 6 * b(31, 38, t), 0, 0),
            head=(-2 * up, 0, 0), chel=(10 + 6 * S(TAU * 7 * t)) * up, chel_x=-6 * up,
            palp_r=((-25 + 12 * S(TAU * 5 * t)) * up, 0, 12 * up), palp_l=((-25 + 12 * S(TAU * 5 * t + 2)) * up, 0, 12 * up),
            plates=tuple(6 * up + 5 * jerk for _ in range(PLATES)), feet=ft)
    add("snare", 60, snare, lag=0.12, lag_gain=0.9)

    # ------------------------------------------------------------------ grove: 140 ticks (Domain of the Old Woods)
    # 0-30 rooting: every foot stabs down in turn and sinks a little, body low, abdomen up, plates part; 30-50 the crown:
    # widest stance, abdomen raised high, plates open wide; 50-75 weaving: the abdomen flings the network, a tremor
    # runs through it; 75-115 three strikes (80, 92, 104), front legs hammering, chelicerae snapping; 115-140 it folds
    # back into its guard
    strikes = (80, 92, 104)

    def grove(t):
        root_ = sm(0, 20, t) * (1 - sm(115, 135, t))
        crown = sm(30, 46, t) * (1 - sm(112, 132, t))
        hit = sum(b(s - 2, s + 4, t) for s in strikes)
        ready = sum(b(s - 8, s - 1, t) for s in strikes)
        ft = {}
        for i, leg in enumerate(LEGS):
            stab = step(t, 2 + 1.5 * i, 7 + 1.5 * i, (0, 0, 0), (0, -1.2, 0), 2.5)
            wide = step(t, 30 + 2 * i, 38 + 2 * i, (0, 0, 0), outward(rig, leg, 5.0), 3.5)
            back = step(t, 116 + 2 * i, 124 + 2 * i, (0, 0, 0), vadd(outward(rig, leg, -5.0), (0, 1.2, 0)), 3.0)
            ft[leg] = vadd(stab, wide, back)
        for leg in ("1_right", "1_left"):
            ft[leg] = vadd(ft[leg], (0, 7.0 * ready - 1.0 * hit, -2.0 * ready))
        return frame(
            body=(0, -4.0 * root_ + 3.0 * crown - 1.5 * hit, 0),
            body_rot=(2.0 * root_ + 3.0 * crown + tremor(t, 0.7) * b(50, 76, t), 0, 0),
            abd=(-10 * root_ - 18 * crown + 10 * b(49, 54, t) + tremor(t, 2.0, 11) * b(54, 76, t) + 4 * hit, 0, 0),
            head=(-5 * root_ - 4 * hit, 0, 0),
            chel=20 * root_ + 10 * crown + 14 * ready - 18 * hit,
            chel_x=-6 * crown,
            palp_r=(-30 * crown + 10 * S(TAU * 3 * t) * crown, 0, 14 * crown),
            palp_l=(-30 * crown + 10 * S(TAU * 3 * t + 1) * crown, 0, 14 * crown),
            plates=tuple(14 * root_ + 18 * crown + 2.5 * abs(tremor(t, 1, 14, i)) * crown for i in range(PLATES)),
            feet=ft)
    add("grove", 140, grove, lag=0.14, lag_gain=0.9)

    # ------------------------------------------------------------------ exhausted: 60 ticks, the opening after the ultimate
    # it slumps, legs splayed, abdomen dragging, heavy breaths; 40-60 it gathers itself back into its guard
    def exhausted(t):
        k = sm(0, 8, t) * (1 - sm(42, 58, t))
        breath = S(TAU * t / 1.4)
        ft = {}
        for i, leg in enumerate(LEGS):
            ft[leg] = vadd(step(t, 1 + i, 7 + i, (0, 0, 0), outward(rig, leg, 3.0), 2.0),
                           step(t, 42 + i, 50 + i, (0, 0, 0), outward(rig, leg, -3.0), 3.0))
        return frame(body=(0, (-3.4 + 0.8 * breath) * k, 0), body_rot=(-2 * k + 1.0 * breath * k, 0, 2 * k),
                     abd=((7 + 2 * breath) * k, 0, -3 * k), head=(-4 * k, 0, 4 * k), chel=10 * k, chel_x=14 * k,
                     palp_r=(25 * k, 0, -4 * k), palp_l=(25 * k, 0, -4 * k), feet=ft)
    add("exhausted", 60, exhausted, lag=0.2, lag_gain=0.7)

    # ------------------------------------------------------------------ hurt: 8 ticks
    def hurt(t):
        k = b(0, 8, t)
        return frame(body=(0, -1.2 * k, 1.5 * k), body_rot=(2 * k, 0, 3 * k), abd=(4 * k, 0, -3 * k), head=(5 * k, 0, 0),
                     chel=12 * k, palp_r=(-15 * k, 0, 0), palp_l=(-15 * k, 0, 0))
    add("hurt", 8, hurt, lag=0.08, lag_gain=1.0)

    # ------------------------------------------------------------------ death: 70 ticks (on a stand-in once it is dead)
    # it stops dead, front legs fold, the body sinks, the chelicerae open one last time, the abdomen tips over, the hind
    # legs give way, all eight legs curl in towards the body out of order, the eyes go out (40), it lies on its belly
    curl = {"1_right": (6, 22), "1_left": (10, 26), "2_right": (30, 44), "2_left": (24, 40), "3_right": (34, 50),
            "3_left": (28, 46), "4_right": (38, 54), "4_left": (42, 58)}

    def death(t):
        ft = {}
        for leg, (a, z) in curl.items():
            give = outward(rig, leg, 3.5) if leg[0] in "34" else (0, 0, 0)
            p = step(t, 16 if leg[0] in "34" else a, 26 if leg[0] in "34" else z, (0, 0, 0), give, 1.0)
            ft[leg] = vadd(p, step(t, a, z, (0, 0, 0), vadd(folded(rig, leg, 0.5), (-give[0], 0, -give[2])), 6.0))
        return frame(
            body=(0, tk((0, 0), (3, 0.8), (14, -1.5), (36, -3.0), (50, -4.6), (54, -4.0), (58, -4.6))(t), 0),
            body_rot=(tk((0, 0), (3, 2), (20, -3), (50, 1), (58, 0))(t), 0, tk((0, 0), (24, 0), (44, 7), (58, 6))(t)),
            abd=(tk((0, 0), (20, -4), (36, 8), (50, 12), (58, 11))(t), tk((0, 0), (24, 0), (44, -6))(t),
                 tk((0, 0), (24, 0), (40, -14), (52, -12))(t)),
            head=(tk((0, 0), (16, 4), (30, -3), (50, -4))(t), 0, tk((0, 0), (40, 0), (50, 5))(t)),
            chel=tk((0, 0), (12, 0), (20, 42), (30, 40), (46, 8), (60, 4))(t) + 3 * tremor(t, 1, 21) * b(20, 34, t),
            chel_x=tk((0, 0), (20, -10), (46, 18))(t),
            palp_r=(tk((0, 0), (20, -30), (50, 30))(t), 0, 0), palp_l=(tk((0, 0), (24, -30), (54, 30))(t), 0, 0),
            plates=tuple(tk((0, 0), (32, 0), (35, 14 + 3 * i), (40, 6 + i), (60, 4))(t) for i in range(PLATES)),
            feet=ft)
    add("death", 70, death, lag=0.1, lag_gain=1.0)
    return out


# =========================================================================== tracks
def realize(rig, name, ticks, loop, fn, lag, lag_gain):
    """Anim of a frame function: body, abdomen (with its inertia), head, mouth, plates, and the legs by IK."""
    a = Anim(name, ticks, loop=loop)
    a.short = 0.0
    length = ticks / 20.0
    ob = rig.o["Body"]
    cache = {}

    def at(t):
        t = round(t * 20) / 20
        if t not in cache:
            cache[t] = fn(t)
        return cache[t]

    def before(t):
        p = t - lag
        if loop:
            p %= length
        return at(max(0.0, p))

    a.pos("Body", lambda t: at(t)["body"])
    a.rot("Body", lambda t: at(t)["body_rot"])

    def abdomen(t):
        f, g = at(t), before(t)
        r = [f["abd"][i] + lag_gain * (g["body_rot"][i] - f["body_rot"][i]) for i in range(3)]
        # the body going up leaves the heavy rear behind (it tips down), going down lifts it
        r[0] += lag_gain * 1.3 * (f["body"][1] - g["body"][1])
        r[1] += lag_gain * 0.6 * (f["body"][0] - g["body"][0])
        return tuple(r)
    a.rot("Abdomen", abdomen)
    a.rot("Head", lambda t: at(t)["head"])
    a.pos("Head", lambda t: at(t)["head_pos"])
    a.rot("Chelicera_right", lambda t: (at(t)["chel_x"], 0, at(t)["chel"]))
    a.rot("Chelicera_left", lambda t: (at(t)["chel_x"], 0, -at(t)["chel"]))
    a.rot("Pedipalp_right", lambda t: at(t)["palp_r"])
    a.rot("Pedipalp_left", lambda t: (at(t)["palp_l"][0], -at(t)["palp_l"][1], -at(t)["palp_l"][2]))
    for i in range(PLATES):
        a.rot(f"Plate_{i + 1}", lambda t, i=i: (-at(t)["plates"][i], 0, 0))

    legs = {}

    def leg_pose(t, leg):
        key = (round(t * 20), leg)
        if key not in legs:
            f = at(t)
            Rb = rot(*f["body_rot"])
            pb = f["body"]
            w = [rig.foot[leg][i] + f["feet"].get(leg, (0, 0, 0))[i] for i in range(3)]
            local = app(transpose(Rb), [w[i] - ob[i] - pb[i] for i in range(3)])
            target = [local[i] + ob[i] for i in range(3)]
            R1, R2, short = solve(rig, leg, target)
            a.short = max(a.short, short)
            legs[key] = (tuple(euler(R1)), tuple(euler(R2)))
        return legs[key]
    for leg in LEGS:
        a.rot(f"Leg_{leg}", lambda t, leg=leg: leg_pose(t, leg)[0])
        a.rot(f"Leg_{leg}_low", lambda t, leg=leg: leg_pose(t, leg)[1])
    return a


def unwrap(vs):
    """Euler angles kept continuous from one tick to the next (no jump of 360 degrees)."""
    out = [list(vs[0])]
    for v in vs[1:]:
        w = list(v)
        for i in range(3):
            while w[i] - out[-1][i] > 180:
                w[i] -= 360
            while w[i] - out[-1][i] < -180:
                w[i] += 360
        out.append(w)
    return [tuple(v) for v in out]


# =========================================================================== output
def build(model, uid):
    """Blockbench animations of the model, sampled every tick."""
    rig = Rig(model)
    out, n = [], [0]
    for name, ticks, loop, fn, lag, gain in animations(rig):
        a = realize(rig, name, ticks, loop, fn, lag, gain)
        values = a.sample()
        for chans in values.values():
            if "rotation" in chans:
                chans["rotation"] = unwrap(chans["rotation"])
        if loop:
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
                                   "uuid": uid(f"kf/{name}/{n[0]}"), "time": round(T(tick), 4), "color": -1,
                                   "interpolation": "linear"})
            animators[uid("group/" + bone)] = {"name": bone, "type": "bone", "keyframes": frames}
        out.append({"uuid": uid("anim/" + name), "name": name, "loop": "loop" if loop else "once",
                    "override": False, "length": a.length, "snapping": 20, "selected": False, "anim_time_update": "",
                    "blend_weight": "", "start_delay": "", "loop_delay": "", "animators": animators,
                    "_values": values, "_short": a.short})
    return out


def report(model, anims, show=False):
    """Checks: the lowest point of the model (a foot tip may dip a pixel when it digs in) and of the body, and how far a
    foot fell short of its target (0 = every foot exactly where the animation puts it)."""
    bad = []
    for a in anims:
        lows, body = [], []
        for tick in range(int(round(a["length"] * 20)) + 1):
            _, pts = pose(model, a["_values"], tick)
            lows.append(min(p[1] for ps in pts.values() for p in ps))
            body.append(min(p[1] for bn, ps in pts.items() if not bn.startswith("Leg_") for p in ps))
        line = (f"  {a['name']:10s} {a['length'] * 20:4.0f} ticks  plus bas {min(lows) / 16:+.2f} bloc  "
                f"corps {min(body) / 16:+.2f}  pied manqué {a['_short']:.1f} px")
        if min(lows) < -2.5 or a["_short"] > 1.5:
            bad.append(line)
        if show:
            print(line)
    for line in bad:
        print("ATTENTION", line)


def strips(model, anims, atlases, sizes, folder, count=10, only=None):
    from taupe_champs_anim import strips as s
    s(model, anims, atlases, sizes, folder, count=count, only=only)


def finish(anims):
    for a in anims:
        a.pop("_values", None)
        a.pop("_short", None)
    return anims

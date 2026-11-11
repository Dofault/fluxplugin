#!/usr/bin/env python3
"""Animations of the Field Mole, King Taupérion (2026-11-11).

Every timing that matters to the game (impacts, hidden underground, sweep angles) is written in
docs/technique/boss/taupe-champs.md and read by FluxDungeons (mob/MolePowers.java, spells/taupe/*.yml): keep the three
in step. Ticks are 1/20 s; every key moment falls on a tick.

Values as in hamster_moissonneur_anim.py: rotation in degrees in the model's axes (x > 0 lifts the front of a bone;
y > 0 turns its front to the monster's left (-x); z > 0 lifts its right side (+x)), position in pixels (16 = 1
block). Written in the Blockbench / BetterModel convention (rotation read as (-x, -y, z), position as (-x, y, z)).

The drill spins about its own axis, tilted 9 degrees down (head 6, drill 3, baked in the cubes): a plain z rotation
would make its point wobble, so each tick of a spin is the exact rotation about that axis, turned into angles.

Underground: `burrow` ends with the whole model under the ground (Root 52 pixels down), `emerge` starts there, the
ultimate ends there. FluxDungeons hides the model's bones while the mole travels underground.
"""
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import paths  # noqa: F401,E402
from golem_fonderie import euler, mul, rot  # noqa: E402
from hamster_moissonneur_anim import (Anim, T, _app, _inverse, bump, finish, keys, pose, smooth,  # noqa: E402,F401
                                      uid_for)

TAU = 2 * math.pi
S, C = math.sin, math.cos
lin = "linear"
ARMS = ("Arm_front_right", "Arm_front_left")
PAWS = ("Paw_front_right", "Paw_front_left")
LEGS = ("Leg_back_right", "Leg_back_left")
BONES = ("Body", "Head", "Drill", "Tail") + ARMS + PAWS + LEGS
UNDER = -52.0                      # Root height that puts the whole model under the ground (pixels)
DEEP = -84.0                       # the same with the body reared up, drill pointing at the sky (start of emerge)
TILT = rot(-9, 0, 0)               # drill axis: nose 9 degrees down
TILT_T = [[TILT[j][i] for j in range(3)] for i in range(3)]


def spin(deg):
    """Rotation of the Drill bone turning the drill by `deg` about its own axis (real angles)."""
    return tuple(euler(mul(TILT, mul(rot(0, 0, deg), TILT_T))))


def spin_track(ticks, speed):
    """Angle of the drill at each tick (list), from its speed in degrees per tick at time t (seconds)."""
    out, a = [], 0.0
    for i in range(ticks + 1):
        out.append(a)
        a += speed(T(i))
    return out


def drill(anim, speed=None, angle=None):
    """Drill track from a speed (deg per tick, function of t) or an angle function."""
    if angle is None:
        track = spin_track(anim.ticks, speed)
        anim.rot("Drill", lambda t: spin(track[min(anim.ticks, int(round(t * 20)))]))
    else:
        anim.rot("Drill", lambda t: spin(angle(t)))


def mirror(anim, right, left, channel, f):
    anim.set(right, channel, lambda t: f(t, 1))
    anim.set(left, channel, lambda t: f(t, -1))


def shake(t, amp, freq=11.0):
    """Deterministic tremor (no randomness: the same every time)."""
    return amp * (S(TAU * freq * t) * 0.6 + S(TAU * freq * 1.7 * t + 1.3) * 0.4)


# =========================================================================== animations
def animations():
    A = []

    # ------------------------------------------------------------------ idle: 4 s loop
    # Slow breathing of the big belly, the drill turns one full turn, the head sways a little, a paw scratches the
    # ground now and then, the tail twitches.
    a = Anim("idle", 80, loop=True)
    a.pos("Body", lambda t: (0, 0.5 * (1 - C(TAU * t / 2)) / 2, 0))
    a.rot("Body", lambda t: (0.8 * S(TAU * t / 2), 0, 0.8 * S(TAU * t / 4)))
    a.rot("Head", lambda t: (-1.5 * S(TAU * t / 4), 6 * S(TAU * t / 4), 0))
    drill(a, angle=lambda t: 360 * t / 4)
    mirror(a, "Paw_front_right", "Paw_front_left", "rotation",
           lambda t, s: (-14 * (bump(1.0, 1.6, t) if s == 1 else bump(2.8, 3.4, t)), 0, 0))
    mirror(a, "Arm_front_right", "Arm_front_left", "position",
           lambda t, s: (0, 1.2 * (bump(1.0, 1.6, t) if s == 1 else bump(2.8, 3.4, t)),
                         3 * (bump(1.0, 1.6, t) if s == 1 else bump(2.8, 3.4, t))))
    a.rot("Tail", lambda t: (0, 10 * bump(2.0, 2.5, t) * S(TAU * 4 * t), 0))
    A.append(a)

    # ------------------------------------------------------------------ walk: 1.2 s loop, 1.4 blocks per second
    # Leaning forwards, the shovels carry the weight: each one reaches forwards, plants and pulls the body (claws
    # scraping the ground), the other one in opposite phase; the body rolls from one shoulder to the other; small hind
    # legs shuffle; the drill rocks back and forth.
    L = 1.2
    a = Anim("walk", 24, loop=True)
    stride, duty = 8.0, 0.6

    def paw_z(t, off):
        u = (t / L + off) % 1.0
        if u < duty:
            return -stride + 2 * stride * u / duty
        v = (u - duty) / (1 - duty)
        return stride - 2 * stride * smooth(0, 1, v)

    def paw_lift(t, off):
        u = (t / L + off) % 1.0
        return 0.0 if u < duty else 3.5 * S(math.pi * (u - duty) / (1 - duty))

    offs = {"Arm_front_right": 0.0, "Arm_front_left": 0.5, "Leg_back_right": 0.5, "Leg_back_left": 0.0}
    for bone, off in offs.items():
        big = bone.startswith("Arm")
        a.pos(bone, lambda t, off=off, big=big: (0, paw_lift(t, off) * (1 if big else 0.5),
                                                 paw_z(t, off) * (1 if big else 0.55)))
    for paw, off in (("Paw_front_right", 0.0), ("Paw_front_left", 0.5)):
        # claws dig in at the start of the stance, rake backwards along it
        a.rot(paw, lambda t, off=off: (-10 + 14 * (((t / L + off) % 1.0) / duty if ((t / L + off) % 1.0) < duty else 1), 0, 0))
    a.rot("Body", lambda t: (-4, 2 * S(TAU * t / L), 3 * S(TAU * t / L)))
    a.pos("Body", lambda t: (0, 0.6 * abs(S(TAU * t / L)), 0))
    a.rot("Head", lambda t: (-3, -3 * S(TAU * t / L), -2 * S(TAU * t / L)))
    drill(a, angle=lambda t: 35 * S(TAU * 2 * t / L))
    a.rot("Tail", lambda t: (0, 8 * S(TAU * t / L), 0))
    A.append(a)

    # ------------------------------------------------------------------ hurt: 0.4 s
    a = Anim("hurt", 8)
    a.rot("Body", lambda t: (3 * bump(0, 0.4, t), 0, -3 * bump(0, 0.4, t)))
    a.rot("Head", lambda t: (8 * bump(0, 0.4, t), 6 * bump(0, 0.4, t), 0))
    drill(a, angle=lambda t: -40 * bump(0, 0.4, t))
    A.append(a)

    # ------------------------------------------------------------------ detect: 1.8 s (spots a prey, drill revs)
    # Head up (0-10), the drill revs up with a roar (10-28), back down (28-36).
    a = Anim("detect", 36)
    a.rot("Head", lambda t: (keys(t, ((0, 0), (T(10), 14), (T(28), 12), (T(36), 0))), 0, 0))
    a.rot("Body", lambda t: (keys(t, ((0, 0), (T(10), 5), (T(28), 4), (T(36), 0))) + shake(t, 0.6) * bump(T(10), T(28), t), 0, 0))
    drill(a, speed=lambda t: 4 + 26 * bump(T(8), T(32), t))
    A.append(a)

    # ------------------------------------------------------------------ charge_windup: 1.4 s
    # The body lowers (0-10), the shovels plant themselves in the ground (6-14), the drill starts turning and speeds up;
    # a short still pause (16-24: the announced moment), then it leans in, ready to rush (24-28). The rush starts at 28.
    a = Anim("charge_windup", 28)
    low = lambda t: smooth(0, T(10), t)
    a.pos("Body", lambda t: (0, -3.5 * low(t), 0))
    a.rot("Body", lambda t: (-6 * low(t) - 3 * smooth(T(24), T(28), t), 0, 0))
    a.rot("Head", lambda t: (-6 * low(t), 0, 0))
    mirror(a, "Arm_front_right", "Arm_front_left", "position",
           lambda t, s: (0, 2.5 * bump(T(4), T(12), t), keys(t, ((0, 0), (T(8), -4), (T(14), 2), (T(28), 3)))))
    mirror(a, "Paw_front_right", "Paw_front_left", "rotation",
           lambda t, s: (keys(t, ((0, 0), (T(8), 10), (T(14), -16), (T(28), -16))), 0, 0))
    a.rot("Tail", lambda t: (8 * low(t), 0, 0))
    drill(a, speed=lambda t: 2 + 30 * smooth(T(4), T(24), t))
    A.append(a)

    # ------------------------------------------------------------------ charge_run: 0.5 s loop
    a = Anim("charge_run", 10, loop=True)
    a.pos("Body", lambda t: (0, -3 + 0.8 * abs(S(TAU * t / 0.5)), 0))
    a.rot("Body", lambda t: (-9, 0, 2.5 * S(TAU * t / 0.5)))
    a.rot("Head", lambda t: (-7, 0, 0))
    for bone, off in (("Arm_front_right", 0.0), ("Arm_front_left", 0.5), ("Leg_back_right", 0.5), ("Leg_back_left", 0.0)):
        a.pos(bone, lambda t, off=off: (0, 2.5 * max(0, S(TAU * (t / 0.5 + off))), 7 * C(TAU * (t / 0.5 + off))))
    drill(a, angle=lambda t: 720 * t / 0.5)
    A.append(a)

    # ------------------------------------------------------------------ charge_stop: 1.2 s (brakes, drill winds down)
    a = Anim("charge_stop", 24)
    a.pos("Body", lambda t: (0, -3 * (1 - smooth(T(6), T(20), t)), 0))
    a.rot("Body", lambda t: (-9 * (1 - smooth(T(4), T(20), t)) + 4 * bump(0, T(8), t), 0, 0))
    a.rot("Head", lambda t: (-7 * (1 - smooth(T(8), T(22), t)), 0, 0))
    mirror(a, "Arm_front_right", "Arm_front_left", "position", lambda t, s: (0, 0, -5 * (1 - smooth(0, T(14), t))))
    mirror(a, "Paw_front_right", "Paw_front_left", "rotation", lambda t, s: (-18 * (1 - smooth(T(6), T(18), t)), 0, 0))
    drill(a, speed=lambda t: 36 * (1 - smooth(0, T(22), t)))
    A.append(a)

    # ------------------------------------------------------------------ stun: 1.5 s (rammed a wall)
    a = Anim("stun", 30)
    a.rot("Body", lambda t: (6 * bump(0, T(6), t), 0, 0))
    a.rot("Head", lambda t: (10 * bump(0, T(8), t) + 3 * S(TAU * t / 0.7) * bump(T(6), T(30), t),
                             10 * S(TAU * t / 0.9) * bump(T(6), T(30), t), 0))
    drill(a, angle=lambda t: 25 * S(TAU * t / 0.5) * (1 - smooth(T(20), T(30), t)))
    A.append(a)

    # ------------------------------------------------------------------ burrow: 2.2 s
    # The drill tips down towards the ground (0-10) and speeds up, the shovels scrape and throw the earth back in quick
    # alternate strokes (6-34), the body dives head first (14-40) until all of it is under the ground at 40.
    # Event 24: the earth bursts around it (game: ring of earth). Hidden by the game at 40.
    a = Anim("burrow", 44)
    a.rot("Head", lambda t: (-26 * smooth(0, T(10), t), 0, 0))
    a.rot("Body", lambda t: (-8 * smooth(0, T(10), t) - 16 * smooth(T(14), T(36), t), 0, shake(t, 1.2) * bump(T(6), T(36), t)))
    a.pos("Root", lambda t: (0, UNDER * smooth(T(14), T(40), t), -4 * smooth(T(14), T(40), t)))
    mirror(a, "Arm_front_right", "Arm_front_left", "position",
           lambda t, s: (0, 1.5 * max(0, S(TAU * (t / 0.3 + (0 if s == 1 else 0.5)))) * bump(T(6), T(34), t),
                         5 * S(TAU * (t / 0.3 + (0 if s == 1 else 0.5))) * bump(T(6), T(34), t)))
    mirror(a, "Paw_front_right", "Paw_front_left", "rotation",
           lambda t, s: (-14 * bump(T(6), T(34), t) + 12 * S(TAU * (t / 0.3 + (0 if s == 1 else 0.5))) * bump(T(6), T(34), t), 0, 0))
    a.rot("Tail", lambda t: (-20 * smooth(T(20), T(40), t), 0, 0))
    drill(a, speed=lambda t: 6 + 40 * smooth(0, T(20), t))
    A.append(a)

    # ------------------------------------------------------------------ emerge: 2 s
    # Deep under the ground, drill pointing up (0); the drill breaks through at 6, the body bursts out at 8 (game: impact,
    # shockwave), jumps (to 12), lands at 20 (game: small shock), shakes the earth off and settles (20-40).
    a = Anim("emerge", 40)
    a.pos("Root", lambda t: (0, keys(t, ((0, DEEP), (T(5), DEEP + 22), (T(8), -10), (T(12), 8), (T(20), 0), (T(40), 0))), 0))
    a.rot("Body", lambda t: (keys(t, ((0, 40), (T(8), 30), (T(12), 12), (T(20), -4), (T(28), 2), (T(40), 0))), 0,
                             shake(t, 2.5, 7) * bump(T(22), T(36), t)))
    a.rot("Head", lambda t: (keys(t, ((0, 20), (T(10), 14), (T(20), -6), (T(40), 0))), 0, 0))
    mirror(a, "Arm_front_right", "Arm_front_left", "position",
           lambda t, s: (0, keys(t, ((0, 0), (T(8), 6), (T(14), 4), (T(20), 0), (T(40), 0))), keys(t, ((0, 0), (T(8), -6), (T(20), 0)))))
    mirror(a, "Paw_front_right", "Paw_front_left", "rotation",
           lambda t, s: (keys(t, ((0, 0), (T(8), 30), (T(20), -6), (T(40), 0))), 0, 0))
    a.rot("Tail", lambda t: (keys(t, ((0, -25), (T(12), 10), (T(24), 0))), 0, 0))
    drill(a, speed=lambda t: 40 * (1 - smooth(T(10), T(34), t)) + 3)
    A.append(a)

    # ------------------------------------------------------------------ sweep: 2.3 s
    # Shovels planted wide (0-10), head and drill turned to its right and tilted (6-16), drill speeds up, then a wide
    # sweep in front of it from its right to its left (22-32: head yaw -60 to +60), recovery (32-46).
    # Sweep angle (degrees, > 0 = its left): SWEEP(t) below, also used by the game (linear from tick 22 to 32).
    a = Anim("sweep", 46)

    def yaw(t):
        return keys(t, ((0, 0), (T(16), -60), (T(22), -62))) if t <= T(22) else \
            (-62 + 124 * (t - T(22)) / T(10) if t <= T(32) else keys(t, ((T(32), 62), (T(38), 66), (T(46), 0))))
    a.rot("Head", lambda t: (-8 * bump(0, T(46), t), yaw(t), -12 * smooth(T(6), T(16), t) * (1 - smooth(T(32), T(44), t))
))
    a.rot("Body", lambda t: (-4 * bump(0, T(46), t), yaw(t) * 0.25, 0))
    mirror(a, "Arm_front_right", "Arm_front_left", "position",
           lambda t, s: (2 * s * smooth(0, T(10), t) * (1 - smooth(T(38), T(46), t)), -1.5 * smooth(0, T(10), t) * (1 - smooth(T(38), T(46), t)), 0))
    mirror(a, "Paw_front_right", "Paw_front_left", "rotation",
           lambda t, s: (-14 * smooth(0, T(10), t) * (1 - smooth(T(38), T(46), t)), 0, 0))
    drill(a, speed=lambda t: 4 + 40 * smooth(T(8), T(22), t) * (1 - smooth(T(32), T(44), t)))
    A.append(a)

    # ------------------------------------------------------------------ ultimate: 6.5 s (seismic drill)
    # Still (0-20), the drill accelerates the whole time; the body crouches and trembles (20-46); the head rises
    # (40-46) and slams the drill into the ground at 50 (game: underground shockwave); drill buried, spinning hard,
    # body shuddering (50-108; game: eruptions at 70, 85, 100); then it sinks head first (108-130): under the ground at
    # 130 (game: hidden, then the last, powerful surge).
    a = Anim("ultimate", 130)
    a.pos("Body", lambda t: (0, -2.5 * smooth(T(20), T(36), t), 0))
    a.rot("Body", lambda t: (keys(t, ((0, 0), (T(36), -4), (T(46), 6), (T(50), -12), (T(108), -12), (T(126), -30)))
                             + shake(t, 1.6) * bump(T(20), T(108), t), 0, shake(t + 0.13, 1.4) * bump(T(20), T(108), t)))
    a.rot("Head", lambda t: (keys(t, ((0, 0), (T(40), -4), (T(46), 18), (T(50), -34), (T(108), -34), (T(126), -40))), 0, 0))
    a.pos("Root", lambda t: (0, -5 * smooth(T(46), T(50), t) + (UNDER + 5) * smooth(T(108), T(130), t), 0))
    mirror(a, "Arm_front_right", "Arm_front_left", "position",
           lambda t, s: (1.5 * s * smooth(T(10), T(24), t), keys(t, ((0, 0), (T(44), 0), (T(47), 3), (T(50), -1), (T(108), -1))), 0))
    mirror(a, "Paw_front_right", "Paw_front_left", "rotation",
           lambda t, s: (keys(t, ((0, 0), (T(20), -12), (T(50), -20), (T(108), -20), (T(130), -30))), 0, 0))
    a.rot("Tail", lambda t: (keys(t, ((0, 0), (T(46), 10), (T(50), -15), (T(108), -10), (T(130), -25))), 0, 0))
    drill(a, speed=lambda t: 3 + 55 * smooth(0, T(48), t))
    A.append(a)

    # ------------------------------------------------------------------ phase_shift: 2 s (enraged under 50 %)
    # Rears up (0-14, shovels lifted, drill screaming), slams both shovels into the ground at 28 (game: shock), settles.
    a = Anim("phase_shift", 40)
    a.rot("Body", lambda t: (keys(t, ((0, 0), (T(14), 16), (T(24), 18), (T(28), -6), (T(34), 2), (T(40), 0))), 0, 0))
    a.pos("Body", lambda t: (0, keys(t, ((0, 0), (T(14), 4), (T(24), 5), (T(28), -2), (T(40), 0))), 0))
    mirror(a, "Arm_front_right", "Arm_front_left", "position",
           lambda t, s: (0, keys(t, ((0, 0), (T(14), 9), (T(24), 11), (T(28), 0), (T(40), 0))), keys(t, ((0, 0), (T(14), -5), (T(28), -2), (T(40), 0)))))
    mirror(a, "Paw_front_right", "Paw_front_left", "rotation",
           lambda t, s: (keys(t, ((0, 0), (T(14), 35), (T(24), 40), (T(28), -15), (T(40), 0))), 0, 0))
    a.rot("Head", lambda t: (keys(t, ((0, 0), (T(14), 10), (T(28), -8), (T(40), 0))), 0, 0))
    drill(a, speed=lambda t: 5 + 45 * bump(T(4), T(36), t))
    A.append(a)
    return A


# =========================================================================== writing
def build(model, uid):
    """Blockbench animations of the model, sampled every tick."""
    out, n = [], [0]
    for a in animations():
        values = a.sample()
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
    """Per animation and tick: lowest and highest point of the model, and where the drill point is (blocks)."""
    res = {}
    for a in anims:
        rows = []
        for tick in range(int(round(a["length"] * 20)) + 1):
            _, pts = pose(model, a["_values"], tick)
            allp = [p for ps in pts.values() for p in ps]
            dr = pts.get("Drill", [])
            tip = min(dr, key=lambda p: p[2]) if dr else (0, 0, 0)
            rows.append({"tick": tick, "low": round(min(p[1] for p in allp) / 16, 2),
                         "high": round(max(p[1] for p in allp) / 16, 2),
                         "tip": [round(v / 16, 2) for v in tip]})
        res[a["name"]] = rows
    return res


def strips(model, anims, atlases, sizes, folder, count=10, only=None):
    """One PNG per animation (profile above, three quarters below), fixed framing, ground line."""
    import apercu as A
    from bbkit import combined, flat_elements
    from png_util import write
    A.transpose = _inverse
    os.makedirs(folder, exist_ok=True)
    flat = dict(model, elements=flat_elements(model, sizes))
    combo = combined(atlases, sizes)
    size = 170
    for a in anims:
        if only and a["name"] not in only:
            continue
        n = int(round(a["length"] * 20))
        ticks = sorted({round(n * i / (count - 1)) for i in range(count)})
        frames = [pose(flat, a["_values"], tk)[0] for tk in ticks]
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

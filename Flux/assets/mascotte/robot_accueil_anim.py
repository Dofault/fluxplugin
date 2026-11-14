#!/usr/bin/env python3
"""Animations of the welcome robot (2026-11-14): idle, walk, talk, greet, appear, vanish.

Values as in the monster scripts: rotation in degrees in the model's axes (x > 0 lifts the front of a bone, so swings
a hanging limb forwards; y > 0 turns its front to the robot's left (-x); z > 0 lifts its right side (+x)), position in
pixels (16 = 1 block). Sampled every tick (1/20 s) and written in the Blockbench / BetterModel convention.

  idle   3 s loop    soft bob, the head looks around, the antenna sways
  walk   0.8 s loop  1.2 blocks per second: legs and arms swing in opposition, the body bobs twice per cycle
  talk   2 s loop    the head nods and tilts, the right hand explains, the antenna quivers on each nod
  greet  2 s once    little hop, right arm up and three waves of the forearm
  appear 0.5 s once  pops out of nothing (scale) ; vanish 0.4 s once: shrinks to nothing
"""
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.normpath(os.path.join(HERE, "..", "..", "..", "FluxDungeons", "assets", "monstres")))
import paths  # noqa: F401,E402
from hamster_moissonneur_anim import Anim, T, bump, finish, keys, smooth, uid_for  # noqa: E402,F401

TAU = 2 * math.pi
S, C = math.sin, math.cos
WALK_SPEED = 1.2                    # blocks per second the walk loop is drawn for (config of FluxMascot follows it)


def animations():
    A = []

    # ------------------------------------------------------------------ idle
    a = Anim("idle", 60, loop=True)
    a.pos("Body", lambda t: (0, 0.35 * S(TAU * t / 3), 0))
    a.rot("Head", lambda t: (1.5 * S(TAU * t / 3), 7 * S(TAU * t / 3 + 0.6), 2 * S(TAU * t / 1.5)))
    a.rot("Antenna", lambda t: (5 * S(TAU * t / 1.5), 0, 4 * S(TAU * t / 3)))
    a.rot("RightArm", lambda t: (3 * S(TAU * t / 3), 0, 1.5 * S(TAU * t / 3)))
    a.rot("LeftArm", lambda t: (-3 * S(TAU * t / 3), 0, -1.5 * S(TAU * t / 3)))
    A.append(a)

    # ------------------------------------------------------------------ walk
    L = 0.8
    a = Anim("walk", 16, loop=True)
    w = lambda t: TAU * t / L                                   # noqa: E731
    a.rot("RightLeg", lambda t: (28 * S(w(t)), 0, 0))
    a.rot("LeftLeg", lambda t: (-28 * S(w(t)), 0, 0))
    a.pos("RightLeg", lambda t: (0, 0.9 * max(0.0, C(w(t))), 0))          # the foot lifts while it comes forwards
    a.pos("LeftLeg", lambda t: (0, 0.9 * max(0.0, -C(w(t))), 0))
    a.pos("Body", lambda t: (0, 0.5 * abs(S(w(t))) - 0.2, 0))
    a.rot("Body", lambda t: (-4, 3 * S(w(t)), 2.5 * S(w(t))))
    a.rot("RightArm", lambda t: (-24 * S(w(t)), 0, 0))
    a.rot("LeftArm", lambda t: (24 * S(w(t)), 0, 0))
    a.rot("RightForearm", lambda t: (10 + 8 * S(w(t) - 0.6), 0, 0))
    a.rot("LeftForearm", lambda t: (10 - 8 * S(w(t) - 0.6), 0, 0))
    a.rot("Head", lambda t: (3 + 1.5 * S(2 * w(t)), -3 * S(w(t)), -2 * S(w(t))))
    a.rot("Antenna", lambda t: (-8 * S(2 * w(t) - 0.8), 0, 5 * S(w(t))))
    A.append(a)

    # ------------------------------------------------------------------ talk
    a = Anim("talk", 40, loop=True)
    nod = lambda t: bump(0.0, 0.5, t) + 0.6 * bump(0.7, 1.1, t) + bump(1.3, 1.8, t)   # noqa: E731
    a.pos("Body", lambda t: (0, 0.3 * nod(t), 0))
    a.rot("Body", lambda t: (-1.5 * nod(t), 2 * S(TAU * t / 2), 0))
    a.rot("Head", lambda t: (-5 * nod(t), 4 * S(TAU * t / 2), 5 * S(TAU * t / 2 + 1.0)))
    a.rot("Antenna", lambda t: (9 * nod(t) * C(TAU * 3 * t), 0, 6 * S(TAU * t)))
    a.rot("RightArm", lambda t: (38 + 10 * S(TAU * t), 0, 6 + 4 * S(TAU * t / 2)))
    a.rot("RightForearm", lambda t: (48 + 22 * S(TAU * t + 0.9), 0, 0))
    a.rot("LeftArm", lambda t: (8 + 5 * S(TAU * t / 2), 0, -2))
    a.rot("LeftForearm", lambda t: (6 * bump(0.9, 1.6, t), 0, 0))
    A.append(a)

    # ------------------------------------------------------------------ greet
    a = Anim("greet", 40)
    a.pos("Root", lambda t: (0, 3.0 * bump(0.35, 0.75, t), 0))
    a.rot("Body", lambda t: (0, 0, -3 * smooth(0.4, 0.7, t) * (1 - smooth(1.7, 2.0, t))))
    a.rot("Head", lambda t: (-4 * bump(0.4, 0.9, t), 0, -8 * smooth(0.5, 0.8, t) * (1 - smooth(1.7, 2.0, t))))
    up = lambda t: smooth(0.45, 0.8, t) * (1 - smooth(1.7, 2.0, t))                     # noqa: E731
    a.rot("RightArm", lambda t: (0, 0, 128 * up(t)))
    a.rot("RightForearm", lambda t: (0, 0, up(t) * (18 + 24 * S(TAU * (t - 0.8) / 0.3) * bump(0.75, 1.75, t))))
    a.rot("LeftArm", lambda t: (0, 0, -8 * up(t)))
    a.rot("Antenna", lambda t: (14 * bump(0.3, 0.9, t) * C(TAU * 4 * t), 0, 0))
    A.append(a)

    # ------------------------------------------------------------------ appear / vanish (scale of the whole robot)
    a = Anim("appear", 10)

    def pop(t):
        v = keys(t, [(0.0, 0.0), (0.25, 1.15), (0.4, 0.95), (0.5, 1.0)])
        return (v, v, v)
    a.scale("Root", pop)
    A.append(a)
    a = Anim("vanish", 8)

    def shrink(t):
        v = keys(t, [(0.0, 1.0), (0.15, 1.12), (0.4, 0.0)])
        return (v, v, v)
    a.scale("Root", shrink)
    A.append(a)
    return A


def build(uid):
    """Blockbench animations, sampled every tick."""
    out, n = [], 0
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
                    n += 1
                    frames.append({"channel": ch, "data_points": [{"x": f"{v[0]:.3f}", "y": f"{v[1]:.3f}", "z": f"{v[2]:.3f}"}],
                                   "uuid": uid(f"kf/{a.name}/{n}"), "time": round(T(tick), 4), "color": -1,
                                   "interpolation": "linear"})
            animators[uid("group/" + bone)] = {"name": bone, "type": "bone", "keyframes": frames}
        out.append({"uuid": uid("anim/" + a.name), "name": a.name, "loop": "loop" if a.loop else "once",
                    "override": False, "length": a.length, "snapping": 20, "selected": False, "anim_time_update": "",
                    "blend_weight": "", "start_delay": "", "loop_delay": "", "animators": animators,
                    "_values": values})
    return out

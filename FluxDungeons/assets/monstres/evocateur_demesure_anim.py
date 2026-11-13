#!/usr/bin/env python3
"""Animations of the Oversized Evoker (2026-11-14).

Every timing that matters to the game (snap of the pinch, release of the expansion, vexes, growth, wave) is written
in docs/technique/boss/evocateur-demesure.md: keep both in step. Ticks are 1/20 s; every key moment falls on a tick.

He is an enchanter, calm and arrogant: precise wrists and fingers, the head leads, the torso follows, long holds.
The model's size never changes here: growing and shrinking are the entity's scale, driven by the game at the ticks
of the sheet (`ultimate_decree`, `shrink_back`).

Arms are posed by joint angles, not by bone angles: the rest pose is baked in the cubes with rotated arms, so a bone
turned about the model's axes would not bend an elbow about its own axis. A pose is, in right-hand terms (the left
arm mirrors it), shoulder (x, y, z), elbow (x, y, z), wrist (x, y, z), finger curl, thumb:
shoulder x > 0 raises the arm forwards, z > 0 away from the body, y > 0 turns it inwards; elbow x > 0 bends it;
wrist y turns the hand about the forearm (80 = palms facing each other); curl 0 = open hand, 100 = fist.
`arm()` turns a pose into the rotations of the five bones of an arm.

Other values as in hamster_moissonneur_anim.py: rotation in degrees in the model's axes (x > 0 lifts the front of a
bone; y > 0 turns its front to his left (-x); z > 0 lifts his right side (+x)), position in pixels (16 = 1 block).

The three cubes of the palm (`glow_focus_1..3`) turn about the vertical; standing on a corner they look the same
every 120 degrees, so a loop turns them by a multiple of 120. They are the only bones whose scale is animated: he
snuffs them when his right hand does something else, and lets them come back afterwards.
"""
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import paths  # noqa: F401,E402
from golem_fonderie import euler, mul, rot  # noqa: E402
from hamster_moissonneur_anim import Anim, T, bump, finish, keys, smooth, strips, uid_for  # noqa: E402,F401

TAU = 2 * math.pi
S, C = math.sin, math.cos
lin = "linear"


def transpose(M):
    return [[M[j][i] for j in range(3)] for i in range(3)]


# --------------------------------------------------------------------------- arms
def P(sh, el, wr=(0, 0, 0), curl=35, thumb=0):
    return tuple(sh) + tuple(el) + tuple(wr) + (curl, thumb)


GUARD = P((28, -30, 26), (72, 0, 0))                 # right arm at rest: forward, palm up under the cubes (baked)
REST = P((-8, 0, 15), (20, 0, 4), curl=-10)          # left arm at rest: low and apart, fingers spread (baked)
DOWN = P((5, 0, 9), (12, 0, 0), curl=20)             # an arm hanging along the body

_BAKED = None


def baked():
    """Baked orientation of each arm in the model: {side: (shoulder, shoulder * elbow, finger curl)}."""
    global _BAKED
    if _BAKED is None:
        import evocateur_demesure as E
        _BAKED = {s: (E.ARMS[s][0], mul(E.ARMS[s][0], E.ARMS[s][1]), E.ARMS[s][2]) for s in (1, -1)}
    return _BAKED


def arm_rotations(side, p):
    """Rotations of Arm, Forearm, Hand, Fingers, Thumb (model axes) that put the arm in pose p."""
    def m(v):
        return (v[0], side * v[1], side * v[2])
    Rsh, Rel, Rwr = rot(*m(p[0:3])), rot(*m(p[3:6])), rot(*m(p[6:9]))
    sh_b, B, curl_b = baked()[side]
    Bt = transpose(B)
    d_arm = mul(Rsh, transpose(sh_b))
    d_fore = mul(transpose(d_arm), mul(mul(Rsh, Rel), Bt))
    d_hand = mul(B, mul(Rwr, Bt))
    d_fing = mul(B, mul(rot(p[9] - curl_b, 0, 0), Bt))
    d_thumb = mul(B, mul(rot(p[10], 0, 0), Bt))
    return [tuple(euler(d)) for d in (d_arm, d_fore, d_hand, d_fing, d_thumb)]


def arm(anim, side, f):
    """Tracks of one arm from f(t) -> pose (right-hand terms)."""
    name = "right" if side == 1 else "left"
    cache = {}

    def at(t):
        if t not in cache:
            cache[t] = arm_rotations(side, f(t))
        return cache[t]
    for i, bone in enumerate(("Arm", "Forearm", "Hand", "Fingers", "Thumb")):
        anim.rot(f"{bone}_{name}", lambda t, i=i: at(t)[i])


def kp(t, pts):
    """keys() over poses given at ticks."""
    return keys(t, tuple((T(k), v) for k, v in pts))


def kt(t, pts):
    """keys() over values given at ticks."""
    return keys(t, tuple((T(k), v) for k, v in pts))


# --------------------------------------------------------------------------- cubes of the palm
SPIN = (40.0, -60.0, 90.0)          # degrees per second of each cube outside loops


def focus(anim, vis=lambda t: 1.0, steps=None):
    """Spin, bob and size of the three cubes; `steps`: thirds of a turn over a loop (seamless)."""
    for k in range(3):
        bone = f"glow_focus_{k + 1}"
        if steps:
            anim.rot(bone, lambda t, k=k: (0, 120.0 * steps[k] * t / anim.length, 0))
        else:
            anim.rot(bone, lambda t, k=k: (0, SPIN[k] * t, 0))
        anim.pos(bone, lambda t, k=k: (0, 0.3 * S(TAU * round(anim.length / 2 + 0.5) * t / anim.length + 1.7 * k)
                                       if anim.loop else 0.0, 0))
        anim.scale(bone, lambda t, k=k: (max(0.01, vis(t)),) * 3)


def snuff(t, hidden_by, back_from, length):
    """1 -> 0 over the first ticks, 0 -> 1 over the last ones (ticks)."""
    return 1 - smooth(0, T(hidden_by), t) + smooth(T(back_from), length, t)


def robe(anim, lift=lambda t: 0.0, flutter=0.0):
    """Robe lifted by magic: lift(t) 0..1 opens every panel outwards, with a small quick flutter."""
    def fl(t, ph):
        return flutter * lift(t) * S(TAU * 3 * t + ph)
    anim.rot("Tabard", lambda t: (8 * lift(t) + fl(t, 0), 0, 0))
    anim.rot("Skirt_back", lambda t: (-9 * lift(t) + fl(t, 1), 0, 0))
    anim.rot("Coat_right", lambda t: (0, 0, 6 * lift(t) + fl(t, 2)))
    anim.rot("Coat_left", lambda t: (0, 0, -6 * lift(t) + fl(t, 3)))
    anim.rot("Flap_right", lambda t: (6 * lift(t) + fl(t, 4), 0, 3 * lift(t)))
    anim.rot("Flap_left", lambda t: (6 * lift(t) + fl(t, 5), 0, -3 * lift(t)))
    anim.rot("Sash_right", lambda t: (0, 0, 14 * lift(t) + 2 * fl(t, 6)))
    anim.rot("Sash_left", lambda t: (0, 0, -14 * lift(t) + 2 * fl(t, 7)))


# =========================================================================== animations
def animations():
    A = []

    # ------------------------------------------------------------------ idle: 12 s loop (240 ticks)
    # The fighting stance is the rest pose: right hand forward under the turning cubes, left arm low. Slow breathing
    # (4 s). Four small gestures, each alone, calm in between:
    #   2.0-4.2 s  he turns his right wrist and closes his fingers a little: the cubes shrink, then come back
    #   5.5-7.0 s  he tilts his head, studying his opponent
    #   8.0-10.0 s he shifts his weight onto his right leg, torso and robe follow
    #   10.3-11.7 s he looks down at what stands at his feet
    a = Anim("idle", 240, loop=True)
    a.rot("Torso", lambda t: (0.7 * S(TAU * t / 4), 0, -2.2 * bump(8.0, 10.0, t)))
    a.pos("Body", lambda t: (0.7 * bump(8.0, 10.0, t), 0.2 * (1 - C(TAU * t / 4)) / 2, 0))
    a.rot("Body", lambda t: (0, 0, 1.6 * bump(8.0, 10.0, t)))
    a.rot("Head", lambda t: (-4 * bump(2.2, 4.0, t) - 10 * bump(10.3, 11.7, t), 8 * bump(5.5, 7.0, t),
                             9 * bump(5.5, 7.0, t)))
    arm(a, 1, lambda t: P((28 + 0.8 * S(TAU * t / 4), -30, 26), (72 + 6 * bump(2.0, 4.2, t), 0, 0),
                          (0, keys(t, ((2.0, 0), (2.7, 35), (3.5, -20), (4.2, 0))), 0), 35 + 35 * bump(2.6, 4.0, t)))
    arm(a, -1, lambda t: P((-8 + 0.6 * S(TAU * t / 4), 0, 15), (20, 0, 4), curl=-10 + 6 * (1 + S(TAU * t / 6))))
    a.rot("Skirt_back", lambda t: (1.0 * S(TAU * t / 4 - 1.0), 0, 0))
    a.rot("Tabard", lambda t: (1.0 * S(TAU * t / 4 - 0.8), 0, 0))
    a.rot("Sash_left", lambda t: (2 * S(TAU * t / 4 - 1.5), 0, 1.5 * S(TAU * t / 6) - 3 * bump(8.2, 10.2, t)))
    a.rot("Sash_right", lambda t: (2 * S(TAU * t / 4 - 1.2), 0, 1.5 * S(TAU * t / 6 + 1) - 3 * bump(8.2, 10.2, t)))
    a.rot("Coat_left", lambda t: (0, 0, -1.5 * bump(8.1, 10.1, t)))
    focus(a, vis=lambda t: 1 - 0.45 * bump(2.6, 4.0, t), steps=(4, -6, 9))
    A.append(a)

    # ------------------------------------------------------------------ walk: 1 s loop (20 ticks), 1.6 blocks per second
    # Upright and unhurried. Legs swing 18 degrees each way, the hips turn with the leading leg, the shoulders the
    # other way, the head stays on its target. The right hand keeps its magic in front of him, the left arm swings a
    # little. Each panel of the robe follows the leg behind it, late.
    L = 1.0
    a = Anim("walk", 20, loop=True)

    def ph(t, L=L):
        return TAU * t / L
    for s, name in ((1, "Leg_right"), (-1, "Leg_left")):
        a.rot(name, lambda t, s=s: (18 * s * S(ph(t)), 0, 0))
        a.pos(name, lambda t, s=s: (0, 1.2 * max(0.0, s * C(ph(t))), 0))
    a.pos("Body", lambda t: (0, 0.5 * C(2 * ph(t)), 0))
    a.rot("Body", lambda t: (0, 3 * S(ph(t)), 1.2 * S(ph(t))))
    a.rot("Torso", lambda t: (-2, -5 * S(ph(t)), -1.2 * S(ph(t))))
    a.rot("Head", lambda t: (2, 2 * S(ph(t)), 0))
    arm(a, 1, lambda t: P((28 - 2.5 * S(ph(t)), -30, 26), (72 + 2 * S(ph(t) - 0.6), 0, 0)))
    arm(a, -1, lambda t: P((-8 + 9 * S(ph(t)), 0, 15), (20 + 5 * S(ph(t) - 0.6), 0, 4), curl=-5))
    a.rot("Tabard", lambda t: (3 + 2 * C(2 * ph(t) - 0.5), 0, 0))
    a.rot("Flap_right", lambda t: (3 + 5 * max(0.0, S(ph(t) - 0.5)), 0, 0))
    a.rot("Flap_left", lambda t: (3 + 5 * max(0.0, -S(ph(t) - 0.5)), 0, 0))
    a.rot("Skirt_back", lambda t: (-4 - 2 * S(2 * ph(t) - 0.6), 0, 0))
    a.rot("Coat_right", lambda t: (3 * S(ph(t) - 0.7), 0, 1))
    a.rot("Coat_left", lambda t: (-3 * S(ph(t) - 0.7), 0, -1))
    a.rot("Sash_right", lambda t: (-3 + 6 * S(ph(t) - 0.9), 0, 2 * S(2 * ph(t))))
    a.rot("Sash_left", lambda t: (-3 - 6 * S(ph(t) - 0.9), 0, -2 * S(2 * ph(t))))
    focus(a, steps=(1, -1, 2))
    A.append(a)

    # ------------------------------------------------------------------ run: 0.6 s loop (12 ticks), 4.4 blocks per second
    # He leans into it without losing his bearing: longer strides, the head level and on the target, the right hand
    # pulled in with its cubes, the left arm pumping, the back of the robe lifted and the sides flaring.
    L = 0.6
    a = Anim("run", 12, loop=True)

    def ph(t, L=L):
        return TAU * t / L
    for s, name in ((1, "Leg_right"), (-1, "Leg_left")):
        a.rot(name, lambda t, s=s: (30 * s * S(ph(t)), 0, 0))
        a.pos(name, lambda t, s=s: (0, 2.5 * max(0.0, s * C(ph(t))) + 1.8 * abs(S(ph(t))), 0))
    a.pos("Body", lambda t: (0, 0.9 * C(2 * ph(t)), -1.0))
    a.rot("Body", lambda t: (-9, 5 * S(ph(t)), 1.5 * S(ph(t))))
    a.rot("Torso", lambda t: (-4, -9 * S(ph(t)), -1.5 * S(ph(t))))
    a.rot("Head", lambda t: (12, 4 * S(ph(t)), 0))
    arm(a, 1, lambda t: P((20 - 8 * S(ph(t)), -18, 18), (86 + 5 * S(ph(t) - 0.6), 0, 0)))
    arm(a, -1, lambda t: P((-5 + 22 * S(ph(t)), 0, 10), (55 + 10 * S(ph(t) - 0.6), 0, 0), curl=40))
    a.rot("Tabard", lambda t: (6 + 5 * C(2 * ph(t) - 0.5), 0, 0))
    a.rot("Flap_right", lambda t: (4 + 9 * max(0.0, S(ph(t) - 0.5)), 0, 2))
    a.rot("Flap_left", lambda t: (4 + 9 * max(0.0, -S(ph(t) - 0.5)), 0, -2))
    a.rot("Skirt_back", lambda t: (-16 - 3 * S(2 * ph(t) - 0.6), 0, 0))
    a.rot("Coat_right", lambda t: (-8 + 4 * S(ph(t) - 0.7), 0, 5 + 1.5 * S(2 * ph(t))))
    a.rot("Coat_left", lambda t: (-8 - 4 * S(ph(t) - 0.7), 0, -5 - 1.5 * S(2 * ph(t))))
    a.rot("Sash_right", lambda t: (-18 + 6 * S(ph(t) - 0.9), 0, 6 + 3 * S(2 * ph(t))))
    a.rot("Sash_left", lambda t: (-18 - 6 * S(ph(t) - 0.9), 0, -6 - 3 * S(2 * ph(t))))
    focus(a, steps=(1, -1, 1))
    A.append(a)

    # ------------------------------------------------------------------ intro: 6.5 s (130 ticks)
    # 0-24    still, turned a little away, arms down, head bowed: he was waiting
    # 24-50   the head rises and turns to the player first (24-38), then the shoulders (30-46), then the hips (34-50)
    # 46-62   the right arm rises, elbow bending, palm up at chest height; he looks at his own hand (56-64)
    # 64-84   he turns his wrist slowly; the cubes appear one after the other (66, 72, 78)
    # 90-104  he closes his hand, the cubes go out; the head turns back to the player, tilted (100-112)
    # 112-124 two small flicks of the wrist: come closer
    # 120-130 the hand opens into the fighting stance, the cubes come back
    a = Anim("intro", 130)
    a.rot("Body", lambda t: (0, kt(t, ((0, -12), (34, -12), (50, 0))), 0))
    a.rot("Torso", lambda t: (kt(t, ((0, -2), (46, -2), (66, 1), (120, 1), (130, 0))) + 0.5 * S(TAU * t / 4),
                              kt(t, ((0, -6), (30, -6), (46, 0))), 0))

    def head_yaw(t):                      # where the head looks, 0 = straight at the player
        want = kt(t, ((0, -18), (24, -18), (38, 0), (56, 0), (64, -16), (98, -16), (110, 0), (124, 0), (130, -9)))
        return want + 9 - kt(t, ((0, -12), (34, -12), (50, 0))) - kt(t, ((0, -6), (30, -6), (46, 0)))
    a.rot("Head", lambda t: (kt(t, ((0, -12), (24, -12), (38, 4), (56, 4), (64, -8), (98, -8), (110, 3), (124, 3),
                                    (130, 0))),
                             head_yaw(t), kt(t, ((0, -2), (98, 0), (110, 7), (124, 7), (130, 0)))))
    palm = P((30, -18, 14), (80, 0, 0))
    arm(a, 1, lambda t: P(*[kp(t, ((0, DOWN), (46, DOWN), (62, palm), (112, palm), (124, GUARD), (130, GUARD)))[i:i + 3]
                            for i in (0, 3)],
                          (22 * bump(T(112), T(118), t) + 22 * bump(T(118), T(124), t),
                           kt(t, ((0, 0), (64, 0), (72, -40), (84, 30), (92, 0))), 0),
                          kt(t, ((0, 20), (46, 20), (62, 35), (90, 35), (104, 95), (112, 95), (120, 25), (130, 35)))))
    arm(a, -1, lambda t: kp(t, ((0, DOWN), (46, DOWN), (66, P((-4, 0, 20), (16, 0, 4), curl=0)), (120, REST),
                                (130, REST))))
    a.rot("Skirt_back", lambda t: (1.0 * S(TAU * t / 4 - 1.0), 0, 0))
    a.rot("Sash_left", lambda t: (2 * S(TAU * t / 4 - 1.5), 0, 0))
    a.rot("Sash_right", lambda t: (2 * S(TAU * t / 4 - 1.2), 0, 0))
    for k, (on, off) in enumerate(((66, 100), (72, 96), (78, 92))):
        bone = f"glow_focus_{k + 1}"
        a.rot(bone, lambda t, k=k: (0, SPIN[k] * t, 0))
        a.scale(bone, lambda t, on=on, off=off: (max(0.01, smooth(T(on), T(on + 6), t) - smooth(T(off), T(off + 6), t)
                                                     + smooth(T(121), T(129), t)),) * 3)
    A.append(a)

    # ------------------------------------------------------------------ hurt, hurt_2: 0.4 s (8 ticks)
    # Bothered, not shaken: the torso gives a little, one shoulder goes back, the head flinches.
    for name, s in (("hurt", 1), ("hurt_2", -1)):
        a = Anim(name, 8)
        a.rot("Torso", lambda t, s=s: (4 * bump(0, 0.4, t), -5 * s * bump(0, 0.4, t), 0))
        a.rot("Head", lambda t, s=s: (3 * bump(0, 0.35, t), 6 * s * bump(0, 0.35, t), -3 * s * bump(0, 0.35, t)))
        arm(a, 1, lambda t, s=s: P((28 + (8 if s == 1 else 2) * bump(0, 0.4, t), -30, 26), (72, 0, 0)))
        arm(a, -1, lambda t, s=s: P((-8 + (10 if s == -1 else 2) * bump(0, 0.4, t), 0, 15), (20, 0, 4), curl=-10))
        a.rot("Skirt_back", lambda t: (-3 * bump(0.05, 0.4, t), 0, 0))
        focus(a)
        A.append(a)

    # ------------------------------------------------------------------ attack_pinch: 1.5 s (30 ticks)
    # 0-4    eyes on the target, the right hand comes back to the chest, the cubes go out
    # 4-11   the arm reaches for the target, torso and right shoulder with it; thumb and fingers close on the pinch
    # 12-14  SNAP: the hand shuts and the wrist turns inwards (impact at tick 13)
    # 14-16  held
    # 16-30  the hand comes back without hurry, a flick of the wrist, the head tilts to look at the result
    a = Anim("attack_pinch", 30)
    chest = P((20, -25, 16), (105, 0, 0), (0, 30, 0), curl=30)
    reach = P((62, -12, 8), (22, 0, 0), (10, 0, 0), curl=10)
    pinch = P((64, -10, 8), (18, 0, 0), (10, 0, 0), curl=55, thumb=35)
    snap = P((64, -10, 8), (20, 0, 0), (10, 38, 0), curl=100, thumb=50)
    back = P((34, -26, 22), (80, 0, 0), (0, 0, 0), curl=45)
    arm(a, 1, lambda t: tuple(v + (20 * bump(T(18), T(24), t) if i == 6 else 0) for i, v in enumerate(
        kp(t, ((0, GUARD), (4, chest), (8, reach), (11, pinch), (12, pinch), (14, snap), (16, snap), (24, back),
               (29, GUARD), (30, GUARD))))))
    fold = P((10, 10, 6), (70, 0, 0), curl=30)
    arm(a, -1, lambda t: kp(t, ((0, REST), (4, REST), (11, fold), (16, fold), (28, REST), (30, REST))))
    a.rot("Torso", lambda t: (kt(t, ((0, 0), (4, 1), (11, -6), (14, -7), (16, -7), (28, 0), (30, 0))),
                              kt(t, ((0, 0), (4, -2), (11, 8), (16, 8), (28, 0), (30, 0))), 0))
    a.rot("Head", lambda t: (kt(t, ((0, 0), (3, -4), (16, -4), (28, 0), (30, 0))),
                             kt(t, ((0, 0), (3, 9), (11, 2), (16, 2), (28, 0), (30, 0))),
                             7 * bump(T(17), T(28), t)))
    a.rot("Tabard", lambda t: (4 * bump(T(8), T(22), t), 0, 0))
    a.rot("Skirt_back", lambda t: (-3 * bump(T(9), T(24), t), 0, 0))
    focus(a, vis=lambda t: snuff(t, 4, 24, 1.5))
    A.append(a)

    # ------------------------------------------------------------------ attack_expand: 2.3 s (46 ticks)
    # 0-9    feet apart, both hands come up in front, palms facing: he holds a sphere nobody sees yet
    # 9-23   the hands part slowly, wrists turning opposite ways; the sphere grows between them (the game's effect)
    # 23-27  RELEASE: the arms fly open, the torso goes forward (wave at tick 26)
    # 27-36  arms held wide, eyes on the target
    # 36-46  the arms come down, wrists turning to the ground: he settles the new size
    a = Anim("attack_expand", 46)
    hold = P((50, 12, 4), (50, 0, 0), (0, 80, 0), curl=20)
    wide = P((52, -8, 20), (46, 0, 0), (0, 80, 0), curl=15)
    flung = P((40, -35, 48), (14, 0, 0), (0, 20, 0), curl=0)
    low = P((14, -10, 26), (24, 0, 0), (-25, 20, 0), curl=10)

    def both(t, s):
        p = list(kp(t, ((0, GUARD if s == 1 else REST), (9, hold), (23, wide), (27, flung), (36, flung), (42, low),
                        (46, GUARD if s == 1 else REST))))
        p[6] += 14 * s * S(TAU * 2 * t) * bump(T(9), T(23), t)       # wrists turning opposite ways
        return tuple(p)
    arm(a, 1, lambda t: both(t, 1))
    arm(a, -1, lambda t: both(t, -1))
    spread = lambda t: kt(t, ((0, 0), (9, 1), (36, 1), (46, 0)))    # noqa: E731
    a.rot("Leg_right", lambda t: (0, 0, 5 * spread(t)))
    a.rot("Leg_left", lambda t: (0, 0, -5 * spread(t)))
    a.pos("Body", lambda t: (0, -0.6 * spread(t), 0))
    a.rot("Torso", lambda t: (kt(t, ((0, 0), (9, 2), (23, 4), (27, -7), (36, -5), (46, 0))), 0, 0))
    a.rot("Head", lambda t: (kt(t, ((0, 0), (9, 3), (23, 3), (27, 6), (36, 4), (46, 0))),
                             kt(t, ((0, 0), (6, 9), (38, 9), (46, 0))), 0))
    robe(a, lift=lambda t: 0.5 * bump(T(9), T(40), t) + 0.5 * bump(T(23), T(36), t), flutter=1.5)
    focus(a, vis=lambda t: snuff(t, 4, 40, 2.3))
    A.append(a)

    # ------------------------------------------------------------------ attack_summon: 3.1 s (62 ticks)
    # 0-6    eyes on the target; the left hand opens and turns palm up, the right hand comes to the chest
    # 6-20   the left hand rises, its wrist turning slowly: the rift opens above the palm; the right hand comes under it
    # 20-36  both hands part, shoulders with them, the torso straightens: the rift widens, the vexes come out (24, 28, 32)
    # 36-43  ORDER: the left hand goes forward, cocks back, and flicks at the target (tick 43)
    # 47-54  the left hand closes: the rift shuts
    # 54-62  arms back down, a small nod
    a = Anim("attack_summon", 62)
    l_up = P((35, -20, 14), (75, 0, 0), curl=10)
    l_high = P((58, -25, 20), (70, 0, 0), curl=5)
    l_wide = P((58, -32, 32), (62, 0, 0), curl=0)
    l_cock = P((66, -8, 10), (40, 0, 0), (-30, 0, 0), curl=10)
    l_flick = P((72, -5, 8), (16, 0, 0), (35, 0, 0), curl=0)
    l_shut = P((50, -15, 14), (60, 0, 0), curl=95, thumb=40)

    def left(t):
        p = list(kp(t, ((0, REST), (6, l_up), (20, l_high), (36, l_wide), (40, l_cock), (43, l_flick), (47, l_flick),
                        (54, l_shut), (61, REST), (62, REST))))
        p[7] += kt(t, ((0, 0), (6, 0), (13, -45), (20, 20), (30, 0)))            # slow turn of the wrist
        return tuple(p)
    r_chest = P((15, -20, 12), (95, 0, 0), curl=30)
    r_under = P((42, 14, 4), (66, 0, 0), (0, 30, 0), curl=15)
    r_wide = P((40, -22, 22), (62, 0, 0), (0, -10, 0), curl=10)
    r_out = P((36, -34, 26), (58, 0, 0), (0, -32, 0), curl=10)
    arm(a, -1, left)
    arm(a, 1, lambda t: kp(t, ((0, GUARD), (6, r_chest), (20, r_under), (36, r_wide), (43, r_out), (50, r_out),
                               (61, GUARD), (62, GUARD))))
    a.rot("Torso", lambda t: (kt(t, ((0, 0), (20, 0), (36, 3), (43, -3), (50, 0), (62, 0))),
                              kt(t, ((0, 0), (20, -6), (36, -3), (43, -9), (54, 0), (62, 0))), 0))
    a.rot("Head", lambda t: (kt(t, ((0, 0), (5, 2), (54, 2), (62, 0))) - 5 * bump(T(54), T(62), t),
                             kt(t, ((0, 0), (5, 9), (54, 9), (62, 0))), 0))
    robe(a, lift=lambda t: 0.4 * bump(T(8), T(50), t), flutter=1.2)
    focus(a, vis=lambda t: snuff(t, 4, 56, 3.1))
    A.append(a)

    # ------------------------------------------------------------------ ultimate_decree: 6.8 s (136 ticks)
    # 0-10    he stops, stands tall, arms down, head bowed to the player; 10-16 perfectly still
    # 16-36   the arms open slowly, palms out, the head rises, the robe lifts (runes at tick 16)
    # 36-60   GROWTH (the game scales the entity from tick 36 to tick 60): arms wider and higher, chest out
    # 60-68   the right arm goes up above him, palm to the sky, the head follows it
    # 68-76   the wrist turns slowly; 77-79 the hand shuts (tick 78): the sizes of the whole room are his
    # 80-84   the fist rises a little more; 84-88 it comes down to the chest (wave at tick 88)
    # 88-110  held, bent over his fist, then he straightens
    # 110-128 he looks down at the players, satisfied; 128-136 back to the fighting stance, cubes back
    a = Anim("ultimate_decree", 136)
    still = P((3, 0, 6), (8, 0, 0), curl=15)
    open1 = P((10, -20, 55), (10, 0, 0), (0, -60, 0), curl=5)
    open2 = P((16, -25, 80), (5, 0, 0), (0, -60, 0), curl=0)
    sky = P((150, -10, 32), (25, 0, 0), (0, 0, 0), curl=5)
    sky2 = P((158, -10, 30), (18, 0, 0), (0, 70, 0), curl=100, thumb=45)
    slam = P((40, -15, 10), (62, 0, 0), (0, 40, 0), curl=100, thumb=45)
    l_side = P((6, -10, 44), (10, 0, 0), (0, 60, 0), curl=0)

    def right(t):
        p = list(kp(t, ((0, GUARD), (10, still), (16, still), (36, open1), (60, open2), (68, sky), (76, sky),
                        (79, sky2), (84, sky2), (88, slam), (104, slam), (128, GUARD), (136, GUARD))))
        p[7] += 70 * smooth(T(68), T(76), t) * (1 - smooth(T(76), T(79), t))      # slow turn before the grasp
        p[9] = kt(t, ((0, 35), (10, 15), (36, 5), (76, 5), (77, 5), (79, 100), (104, 100), (124, 30), (136, 35)))
        return tuple(p)
    arm(a, 1, right)
    arm(a, -1, lambda t: kp(t, ((0, REST), (10, still), (16, still), (36, open1), (60, open2), (68, l_side),
                                (110, l_side), (128, REST), (136, REST))))
    a.pos("Body", lambda t: (0, kt(t, ((0, 0), (36, 0), (60, 0.5), (84, 0.8), (88, -1.0), (104, -0.6), (124, 0),
                                       (136, 0))), 0))
    a.rot("Torso", lambda t: (kt(t, ((0, 0), (10, 1), (36, 3), (60, 5), (84, 7), (88, -8), (100, -7), (118, 2),
                                     (136, 0))), 0, 0))
    a.rot("Head", lambda t: (kt(t, ((0, 0), (10, -10), (16, -10), (36, 4), (60, 10), (68, 20), (79, 20), (88, -6),
                                    (104, -6), (112, -12), (128, -12), (136, 0))),
                             kt(t, ((0, 0), (10, 9), (60, 9), (68, 2), (84, 2), (100, 9), (128, 9), (136, 0))), 0))
    robe(a, lift=lambda t: kt(t, ((0, 0), (16, 0), (36, 0.7), (60, 1.0), (84, 0.8), (88, 1.0), (110, 0.3), (128, 0),
                                  (136, 0))), flutter=1.5)
    focus(a, vis=lambda t: snuff(t, 6, 128, 6.8))
    A.append(a)

    # ------------------------------------------------------------------ shrink_back: 2.5 s (50 ticks)
    # 0-16   both hands come together in front of him, palms facing, the head bows
    # 12-36  SHRINKING (the game scales the entity back from tick 12 to tick 36): the hands close in
    # 36-50  the robe settles, the arms go back to the fighting stance
    a = Anim("shrink_back", 50)
    close = P((44, 8, 6), (66, 0, 0), (0, 80, 0), curl=20)
    tight = P((40, 20, 2), (74, 0, 0), (0, 80, 0), curl=40)
    for s in (1, -1):
        arm(a, s, lambda t, s=s: kp(t, ((0, GUARD if s == 1 else REST), (16, close), (36, tight), (48, GUARD if s == 1
                                                                                                 else REST),
                                        (50, GUARD if s == 1 else REST))))
    a.rot("Torso", lambda t: (kt(t, ((0, 0), (16, -3), (36, -5), (50, 0))), 0, 0))
    a.rot("Head", lambda t: (kt(t, ((0, 0), (16, -8), (36, -10), (50, 0))), kt(t, ((0, 0), (10, 9), (40, 9), (50, 0))), 0))
    robe(a, lift=lambda t: 0.6 * bump(T(4), T(40), t), flutter=1.2)
    focus(a, vis=lambda t: snuff(t, 4, 44, 2.5))
    A.append(a)
    return A


# =========================================================================== export
def unwrap(vs):
    """Keeps successive angles within 180 degrees of each other (no spin through a wrap between two ticks)."""
    out = [vs[0]]
    for v in vs[1:]:
        out.append(tuple(c - 360.0 * round((c - p) / 360.0) for c, p in zip(v, out[-1])))
    return out


def build(model, uid):
    """Blockbench animations of the model, sampled every tick."""
    out, n = [], [0]
    for a in animations():
        values = a.sample()
        animators = {}
        for bone, chans in values.items():
            frames = []
            for ch, vs in chans.items():
                if ch == "rotation":
                    vs = chans[ch] = unwrap(vs)
                    jump = max((abs(c - p) for v, w in zip(vs, vs[1:]) for c, p in zip(w, v)), default=0)
                    if jump > 60:
                        print(f"warning: {a.name}/{bone} turns {jump:.0f} degrees in one tick")
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

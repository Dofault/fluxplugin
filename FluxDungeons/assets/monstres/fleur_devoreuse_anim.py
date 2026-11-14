#!/usr/bin/env python3
"""Animations of the Devouring Flower (2026-11-14).

Every timing that matters to the game (snap of the bite, the three thorn shots, the roots striking, the bloom) is
written in docs/technique/boss/fleur-devoreuse.md: keep both in step. Ticks are 1/20 s; key moments fall on a tick.

A rooted plant: nothing ever moves the bulb. Two registers: slow, uneven, barely visible at rest; sudden and
exaggerated in a fight, with long wind-ups before short strikes. The mouth, the tongue and the petals carry the
expression. The game turns the head towards its prey on top of these animations (bone `h_Tete`).

An animation is described by channels, in the part's own terms; `rig()` turns them into bone tracks:
  head      (x, y, z) degrees in the model's axes: x > 0 lifts the mouth, y > 0 turns it to its left (-x), z rolls
  head_pos  (x, y, z) pixels: z < 0 throws the head forward (the neck stretches with `stretch`)
  stem      (low, high, neck, sway): each segment leaning, < 0 forwards; sway rolls the whole plant
  body      pixels the stem rises out of the bulb (breathing, shudder)
  jaw       (up, low): degrees each jaw opens (< 0 closes: -11 = teeth meshed)
  mouth     (x, y, z) scale of the whole mouth (throat, jaws, teeth, tongue, inner petals), never of the corolla
  outer     (i) -> (open, tip): outer petal i opens backwards (> 0) or closes on the mouth (< 0); curl of its tip
  inner     (i) -> open: inner petal flaring out (> 0) or hugging the mouth (< 0)
  sepal     (i) -> raised: sepals standing up behind the corolla
  tongue    (out, lift, side, curl): length (1 = rest, 0.12 = swallowed, 2 = thrown out), base lifting, sideways
            sweep, curl of the tip (< 0 hangs lower)
  leaf      (side) -> (lift, tip): side 0 the big leaf (-x), 1 the small one
  root      (i) -> (lift, tip): root i rising from the bulb, its tip curling up
Secondary motion comes from the rig, not from each animation: petals answer one after the other (25 ms apart), the
tongue trails behind the head's accelerations, its tip later still.
"""
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import paths  # noqa: F401,E402
from golem_fonderie import euler, mul, rot  # noqa: E402
from hamster_moissonneur_anim import Anim, T, bump, finish, keys, smooth, strips, uid_for  # noqa: E402,F401
import fleur_devoreuse as F  # noqa: E402

TAU = 2 * math.pi
S = math.sin
FR = F.frames()
TIPPED = (1, 2, 5)                  # outer petals with a recurved tip
TWO_SEGMENTS = (1, 2, 3)            # roots with a knee


def transpose(M):
    return [[M[j][i] for j in range(3)] for i in range(3)]


def local(bone, x, y=0.0, z=0.0):
    """Rotation given about the bone's own rest axes, as angles in the model's axes."""
    R = FR[bone]
    return tuple(euler(mul(R, mul(rot(x, y, z), transpose(R)))))


def K(t, *pts):
    """Eased curve through (tick, value) points."""
    return keys(t, tuple((T(k), v) for k, v in pts))


def win(t, a, b, c, d):
    """0 before tick a, 1 from b to c, 0 after d."""
    return smooth(T(a), T(b), t) - smooth(T(c), T(d), t)


def hit(t, tick, fall=5):
    """A kick: up in one tick, down in `fall` ticks."""
    k = t * 20 - tick
    return 0.0 if k < -1 or k > fall else (k + 1 if k < 0 else 1 - k / fall)


def clamp(v, lim):
    return max(-lim, min(lim, v))


Z3 = lambda t: (0.0, 0.0, 0.0)  # noqa: E731


def rig(a, head=Z3, head_pos=Z3, stem=lambda t: (0, 0, 0, 0), stretch=lambda t: 1.0, body=lambda t: 0.0,
        jaw=lambda t: (0, 0), mouth=lambda t: (1, 1, 1), outer=lambda t, i: (0, 0), inner=lambda t, i: 0,
        sepal=lambda t, i: 0, tongue=lambda t: (1, 0, 0, 0), leaf=lambda t, s: (0, 0), root=lambda t, i: (0, 0)):
    a.rot("h_Tete", head)
    a.pos("h_Tete", head_pos)
    a.rot("Tige_basse", lambda t: (stem(t)[0], 0, stem(t)[3]))
    a.pos("Tige_basse", lambda t: (0, body(t), 0))
    a.rot("Tige_haute", lambda t: (stem(t)[1], 0, 0))
    a.rot("Cou", lambda t: (stem(t)[2], 0, 0))
    a.scale("Cou", lambda t: (1, 1 + 0.3 * (stretch(t) - 1), stretch(t)))
    a.rot("Machoire_haut", lambda t: local("Machoire_haut", jaw(t)[0]))
    a.rot("Machoire_bas", lambda t: local("Machoire_bas", -jaw(t)[1]))
    a.scale("Gueule", mouth)
    for i in range(1, 6):
        d = 0.025 * i
        a.rot(f"Petale_ext_{i}", lambda t, i=i, d=d: local(f"Petale_ext_{i}", outer(t - d, i)[0]))
        if i in TIPPED:
            a.rot(f"Petale_ext_{i}_pointe", lambda t, i=i, d=d: local(f"Petale_ext_{i}_pointe", outer(t - d, i)[1]))
        a.rot(f"Petale_int_{i}", lambda t, i=i, d=d: local(f"Petale_int_{i}", inner(t - d, i)))
        a.rot(f"Sepale_{i}", lambda t, i=i, d=d: local(f"Sepale_{i}", -sepal(t - d, i)))

    def lag(t, back):
        h0, h1 = head(t), head(t - back)
        return clamp(-0.7 * (h0[0] - h1[0]), 22), clamp(-0.7 * (h0[1] - h1[1]), 22)
    a.scale("Langue", lambda t: (1, 1, max(0.05, tongue(t)[0])))
    a.rot("Langue", lambda t: local("Langue", tongue(t)[1] * 0.5 + lag(t, 0.1)[0] * 0.3,
                                    tongue(t)[2] * 0.5 + lag(t, 0.1)[1] * 0.3))
    a.rot("Langue_milieu", lambda t: local("Langue_milieu", tongue(t)[1] * 0.5 + lag(t, 0.15)[0] * 0.5,
                                           tongue(t)[2] * 0.5 + lag(t, 0.15)[1] * 0.5))
    a.rot("Langue_pointe", lambda t: local("Langue_pointe", tongue(t)[3] + lag(t, 0.2)[0], lag(t, 0.2)[1]))
    for s, name in enumerate(("g", "d")):
        a.rot(f"Feuille_{name}", lambda t, s=s, name=name: local(f"Feuille_{name}", -leaf(t, s)[0]))
        a.rot(f"Feuille_{name}_pointe", lambda t, s=s, name=name: local(f"Feuille_{name}_pointe", -leaf(t, s)[1]))
    for i in range(1, 6):
        if i in TWO_SEGMENTS:
            a.rot(f"Racine_{i}", lambda t, i=i: local(f"Racine_{i}", -root(t, i)[0]))
            a.rot(f"Racine_{i}_pointe", lambda t, i=i: local(f"Racine_{i}_pointe", -root(t, i)[1]))
        else:
            a.rot(f"Racine_{i}", lambda t, i=i: local(f"Racine_{i}", -root(t, i)[0] - 0.5 * root(t, i)[1]))
    return a


# =========================================================================== animations
def animations():
    A = []

    # ------------------------------------------------------------------ sleep: 4 s loop (80 ticks)
    # Before the fight: folded up and inert. Head hanging, petals closed on the mouth, teeth meshed, tongue swallowed,
    # leaves drooping. Only a very slow breath shows it is alive.
    a = Anim("sleep", 80, loop=True)

    def br(t):
        return S(TAU * t / 4)
    rig(a, head=lambda t: (-36 + 1.2 * br(t), 0, 3),
        stem=lambda t: (-5, -12 + 0.8 * br(t), -10, 0),
        jaw=lambda t: (-11, -11),
        outer=lambda t, i: (-40 + 2 * br(t - 0.3 * i), -30),
        inner=lambda t, i: -14, sepal=lambda t, i: -10,
        tongue=lambda t: (0.12, 0, 0, 0),
        leaf=lambda t, s: (-22 + 1.5 * br(t - 0.5 - s), -10))
    A.append(a)

    # ------------------------------------------------------------------ awaken: 5 s (100 ticks), once
    #   0-24   asleep, nothing moves
    #  24-44   first signs: the front root tightens (24-40), the leaves tremble, the stem rises out of the bulb,
    #          the head lifts a little, the petals begin to part
    #  44-76   the monstrous opening: the head rises for good, the petals open one after the other, the jaws part far
    #          wider than at rest (72), the tongue slides out (60-68) then drops (74), a jolt at full gape (72-80)
    #  76-92   the roar: mouth wide open, petals shaking, a last squeeze of the tongue (90)
    #  92-100  back to its fighting stance
    a = Anim("awaken", 100)
    rig(a, head=lambda t: (K(t, (0, -36), (24, -36), (44, -28), (62, 6), (72, 14), (76, 10), (92, 10), (100, 0)),
                           0, K(t, (0, 3), (60, 0)) + 4 * S(TAU * 5 * t) * win(t, 71, 73, 78, 81)
                           + 1.5 * S(TAU * 5 * t) * win(t, 76, 78, 90, 93)),
        stem=lambda t: (K(t, (0, -5), (24, -5), (44, -2), (70, 6), (100, 0)),
                        K(t, (0, -12), (24, -12), (44, -8), (70, 6), (100, 0)),
                        K(t, (0, -10), (24, -10), (44, -6), (72, 4), (100, 0)), 0),
        body=lambda t: 1.5 * bump(T(24), T(44), t),
        jaw=lambda t: (K(t, (0, -11), (44, -11), (58, 6), (72, 26), (92, 26), (100, 0)),
                       K(t, (0, -11), (44, -11), (58, 10), (72, 36), (92, 34), (100, 0))),
        mouth=lambda t: (1 + 0.25 * win(t, 44, 72, 92, 100),) * 2 + (1 + 0.1 * win(t, 44, 72, 92, 100),),
        outer=lambda t, i: (K(t, (0, -40), (24, -40), (44, -30), (72, 22), (92, 20), (100, 0))
                            + 5 * S(TAU * 5 * t + i) * win(t, 76, 78, 90, 93),
                            K(t, (0, -30), (44, -26), (72, 10), (100, 0))),
        inner=lambda t, i: K(t, (0, -14), (44, -12), (72, 16), (92, 16), (100, 0)),
        sepal=lambda t, i: K(t, (0, -10), (44, -5), (72, 25), (92, 25), (100, 0)),
        tongue=lambda t: (K(t, (0, .12), (50, .12), (60, 1.0), (68, 1.55), (76, 1.4), (88, 1.5), (92, 1.2), (100, 1)),
                          K(t, (0, 0), (60, 6), (68, 10), (74, -12), (88, -10), (100, 0)), 0,
                          K(t, (0, 0), (66, 10), (74, -28), (90, -20), (94, -32), (100, 0))),
        leaf=lambda t, s: (K(t, (0, -22), (24, -22), (44, -12), (72, 18), (100, 0))
                           + 3 * S(TAU * 5 * t + 2 * s) * bump(T(26), T(46), t),
                           K(t, (0, -10), (72, 8), (100, 0))),
        root=lambda t, i: ((14 if i == 1 else 6) * bump(T(24 + 2 * i), T(38 + 2 * i), t) + 8 * win(t, 70, 76, 90, 96),
                           (20 if i == 1 else 6) * bump(T(26 + 2 * i), T(40 + 2 * i), t)))
    A.append(a)

    # ------------------------------------------------------------------ idle: 12 s loop (240 ticks)
    # A plant that never sleeps: it breathes (4 s), sways (12 s) and tilts its head (6 s), each part on its own
    # period so nothing ever moves together. Three small gestures, each alone:
    #   1.9-4.1 s   it licks a tooth: jaws ajar, the tongue comes out, sweeps to one side then the other
    #   7.0-9.6 s   it straightens up and looks around, petals a little wider
    #  10.2-10.7 s  a dry clack of its jaws
    # Leaves swing as in a draught (3 s and 4 s), a root tightens now and then.
    a = Anim("idle", 240, loop=True)

    def look(t):
        return bump(7.0, 9.6, t)

    def lick(t):
        return bump(1.9, 4.1, t)

    def clack(t):
        return bump(10.2, 10.7, t)
    rig(a, head=lambda t: (1.5 * br(t) + 7 * look(t) - 4 * lick(t), 5 * S(TAU * t / 12) + 1.5 * S(TAU * t / 4 + 1),
                           3 * S(TAU * t / 6 + 0.5)),
        stem=lambda t: (1.2 * S(TAU * t / 4 - 0.6), 1.5 * S(TAU * t / 4 - 1.0) + 3 * look(t),
                        1.2 * S(TAU * t / 4 - 1.4), 1.5 * S(TAU * t / 6)),
        body=lambda t: 0.4 * (1 + br(t)) / 2,
        jaw=lambda t: (1.5 * (1 + br(t)) / 2 + 5 * lick(t) - 7 * clack(t), 2 * (1 + br(t)) / 2 + 7 * lick(t) - 7 * clack(t)),
        outer=lambda t, i: (3 * S(TAU * t / 4 - 0.5 * i) + 4 * look(t), 2 * S(TAU * t / 6 + i)),
        inner=lambda t, i: 2 * S(TAU * t / 4 - 0.4 * i - 1),
        sepal=lambda t, i: 3 * S(TAU * t / 6 + i),
        tongue=lambda t: (1 + 0.35 * bump(2.0, 4.0, t), 14 * bump(2.2, 3.6, t),
                          keys(t, ((2.0, 0), (2.6, 16), (3.3, -12), (4.0, 0))),
                          12 * bump(2.3, 3.4, t) + 4 * S(TAU * t / 3)),
        leaf=lambda t, s: ((3 * S(TAU * t / 3), 4 * S(TAU * t / 3 - 1)) if s == 0
                           else (3 * S(TAU * t / 4 + 2), 4 * S(TAU * t / 4 + 1))),
        root=lambda t, i: ((5 * bump(5.0, 6.2, t), 8 * bump(5.1, 6.4, t)) if i == 2
                           else (0, 10 * bump(9.0, 10.2, t)) if i == 5
                           else (0, 6 * bump(0.6, 1.6, t)) if i == 1 else (0, 0)))
    A.append(a)

    # ------------------------------------------------------------------ idle_lick: 1.8 s (36 ticks), now and then
    # It tastes the air: head tilted, jaws ajar, the tongue comes far out and sweeps across its teeth.
    a = Anim("idle_lick", 36)
    rig(a, head=lambda t: (-5 * bump(0, 1.8, t), 0, 7 * bump(0.1, 1.7, t)),
        jaw=lambda t: (7 * bump(0, 1.8, t), 10 * bump(0, 1.8, t)),
        outer=lambda t, i: (5 * bump(0, 1.8, t), 0),
        tongue=lambda t: (1 + 0.6 * bump(0.1, 1.7, t), 16 * bump(0.2, 1.5, t),
                          K(t, (0, 0), (10, -20), (22, 22), (36, 0)), 16 * bump(0.3, 1.5, t)))
    A.append(a)

    # ------------------------------------------------------------------ idle_snap: 0.7 s (14 ticks), now and then
    # A warning: the jaws part and clack shut (6), the head nods with it.
    a = Anim("idle_snap", 14)
    rig(a, head=lambda t: (K(t, (0, 0), (4, 5), (6, -5), (14, 0)), 0, 0),
        jaw=lambda t: (K(t, (0, 0), (4, 9), (6, -11), (9, -9), (14, 0)), K(t, (0, 0), (4, 12), (6, -11), (9, -9), (14, 0))),
        outer=lambda t, i: (K(t, (0, 0), (4, 6), (7, -6), (14, 0)), 0),
        tongue=lambda t: (K(t, (0, 1), (4, 0.5), (10, 0.6), (14, 1)), 0, 0, 0))
    A.append(a)

    # ------------------------------------------------------------------ hurt: 0.4 s (8 ticks)
    a = Anim("hurt", 8)
    rig(a, head=lambda t: (7 * bump(0, 0.4, t), 0, -4 * bump(0, 0.4, t)),
        jaw=lambda t: (6 * bump(0, 0.4, t),) * 2,
        outer=lambda t, i: (-8 * bump(0, 0.4, t), 0),
        leaf=lambda t, s: (-6 * bump(0, 0.4, t), 0))
    A.append(a)

    # ------------------------------------------------------------------ bite: 2.3 s (46 ticks), hit at 23
    #   0-18   wind-up, slow: the head leans back like a compressed spring, the jaws part, the petals spread, the
    #          tongue is sucked in at once (6), the sepals stand up, the front roots grip the ground
    #  18-21   the strike: the stem whips forward, the neck stretches by half, the mouth grows by 40 %,
    #          the jaws gape (32 and 38 degrees), the petals burst open, the tongue is thrown out
    #  21-23   the jaws slam shut: HIT at 23
    #  23-30   it holds its bite, head low, petals folding back on the mouth, the tongue whips down
    #  30-46   it straightens up, past its stance then back, and gets its breath back
    a = Anim("bite", 46)
    rig(a, head=lambda t: (K(t, (0, 0), (14, 18), (18, 20), (21, 18), (24, 14), (30, 10), (38, 4), (46, 0)), 0,
                           1.2 * S(TAU * 5 * t) * win(t, 14, 15, 18, 19)),
        head_pos=lambda t: (0, 0, K(t, (0, 0), (16, 2), (21, -18), (24, -17), (33, 0))),
        stem=lambda t: (K(t, (0, 0), (14, 9), (18, 10), (22, -11), (26, -11), (36, 2), (46, 0)),
                        K(t, (0, 0), (14, 12), (18, 13), (22, -13), (26, -12), (36, 3), (46, 0)),
                        K(t, (0, 0), (14, 10), (18, 12), (22, -4), (28, -3), (38, 2), (46, 0)), 0),
        stretch=lambda t: 1 + 0.5 * bump(T(17), T(30), t),
        jaw=lambda t: (K(t, (0, 0), (12, 16), (18, 20), (21, 32), (23, -10), (25, -11), (32, -8), (40, 2), (46, 0)),
                       K(t, (0, 0), (12, 20), (18, 24), (21, 38), (23, -8), (25, -11), (32, -8), (40, 3), (46, 0))),
        mouth=lambda t: (lambda m: (1 + 0.4 * m, 1 + 0.42 * m, 1 + 0.25 * m))(
            K(t, (0, 0), (16, 0.1), (21, 1), (23, 0.5), (26, 0.1), (32, 0))),
        outer=lambda t, i: (K(t, (0, 0), (14, 16), (18, 18), (21, 36), (24, 10), (28, -12), (38, 3), (46, 0)),
                            K(t, (0, 0), (21, 14), (26, -10), (46, 0))),
        inner=lambda t, i: K(t, (0, 0), (16, 12), (21, 26), (24, -6), (34, 0)),
        sepal=lambda t, i: K(t, (0, 0), (14, 26), (22, 30), (30, 0)),
        tongue=lambda t: (K(t, (0, 1), (6, 0.2), (18, 0.15), (21, 1.4), (24, 1.3), (30, 1.15), (40, 1.0)),
                          K(t, (0, 0), (18, 0), (21, 26), (24, -8), (30, -8), (40, 0)), 0,
                          K(t, (0, 0), (21, 18), (25, -34), (32, -12), (40, 4), (46, 0))),
        leaf=lambda t, s: (K(t, (0, 0), (14, 14), (21, -10), (30, -6), (46, 0)), K(t, (0, 0), (16, 10), (22, -14), (46, 0))),
        root=lambda t, i: ((K(t, (0, 0), (16, 6), (22, -3), (30, 0)), 12 * bump(T(14), T(26), t)) if i in (1, 2) else (0, 0)))
    A.append(a)

    # ------------------------------------------------------------------ thorns: 3 s (60 ticks), shots at 26, 34, 42
    #   0-22   it takes aim: the head rears up, the petals tighten, the jaws part, the tongue vanishes (8), sepals and
    #          leaves stand up; a seed glows in its throat (drawn by the game)
    #  26, 34, 42  three spits: each time the head jerks forward, the jaws and petals burst open and fall back;
    #          the tongue shows between two shots (31, 39)
    #  44-60   leaves fall back, the head comes down, the tongue slides out again, a clack of the jaws (54)
    a = Anim("thorns", 60)

    def charge(t):
        return win(t, 0, 22, 44, 58)

    def kick(t):
        return hit(t, 26) + hit(t, 34) + hit(t, 42)

    def peek(t):
        return bump(T(29), T(33), t) + bump(T(37), T(41), t)
    rig(a, head=lambda t: (14 * charge(t) - 12 * kick(t), 0, 0),
        head_pos=lambda t: (0, 0, -3 * kick(t)),
        stem=lambda t: (4 * charge(t), 8 * charge(t) - 5 * kick(t), 6 * charge(t) - 6 * kick(t), 0),
        jaw=lambda t: (10 * charge(t) + 8 * kick(t) - 8 * bump(T(52), T(57), t),
                       14 * charge(t) + 10 * kick(t) - 8 * bump(T(52), T(57), t)),
        mouth=lambda t: (1 + 0.1 * kick(t),) * 3,
        outer=lambda t, i: (-14 * charge(t) + 16 * kick(t), -8 * charge(t)),
        inner=lambda t, i: -6 * charge(t) + 12 * kick(t),
        sepal=lambda t, i: 30 * charge(t) + 8 * kick(t),
        tongue=lambda t: (1 - 0.85 * smooth(0, T(8), t) + 0.4 * peek(t) + 0.85 * smooth(T(46), T(58), t), 0, 0,
                          -10 * peek(t)),
        leaf=lambda t, s: (26 * charge(t) - 10 * kick(t), 14 * charge(t)),
        root=lambda t, i: (0, 6 * charge(t)))
    A.append(a)

    # ------------------------------------------------------------------ roots: 3.5 s (70 ticks), strike at 30
    #   0-26   it bows towards the ground: the head comes down, the petals tighten, the mouth hangs ajar, the tongue
    #          dangles, the leaves flatten; its roots stir one after the other (4-30)
    #  22-30   all its roots rise together (28) and strike the ground: STRIKE at 30, the plant shudders
    #  30-54   it stays bowed, roots tense, tips up, while the roots it called break out further away (the game)
    #  54-70   roots slacken, leaves rise, the head comes back up, the tongue goes back in
    a = Anim("roots", 70)

    def low(t):
        return win(t, 0, 26, 54, 68)
    rig(a, head=lambda t: (-20 * low(t) - 6 * bump(T(28), T(36), t), 0, 2 * S(TAU * 2.5 * t) * low(t)),
        stem=lambda t: (-3 * low(t), -8 * low(t), -8 * low(t), 0),
        body=lambda t: -1.5 * bump(T(29), T(36), t),
        jaw=lambda t: (4 * low(t), 8 * low(t)),
        outer=lambda t, i: (-16 * low(t), -10 * low(t)),
        inner=lambda t, i: -8 * low(t), sepal=lambda t, i: -6 * low(t),
        tongue=lambda t: (1 + 0.2 * low(t), -10 * low(t), 0, -28 * low(t)),
        leaf=lambda t, s: (-22 * low(t), -12 * low(t)),
        root=lambda t, i: (8 * bump(T(4 + 3 * i), T(16 + 3 * i), t)
                           + K(t, (20, 0), (28, 18), (31, -4), (36, 6), (54, 6), (66, 0)),
                           K(t, (20, 0), (28, 24), (31, -6), (40, 16), (54, 14), (66, 0))))
    A.append(a)

    # ------------------------------------------------------------------ bloom (ultimate): 6.5 s (130 ticks), burst at 52
    #   0-28   the charge: the head bows, the petals fold onto the mouth one after the other, the jaws part more and
    #          more, the tongue disappears, leaves and sepals stand up and tighten, the roots curl round the bulb,
    #          the whole plant trembles harder and harder
    #  30-46   the transformation: the petals spread as far as they go, the mouth grows by more than half, the lower
    #          jaw drops to 50 degrees, the neck stretches, the tongue shoots out (42), the leaves rise like claws
    #  46-58   it holds the gape; BURST at 52: petals and leaves shake violently, the head recoils
    #  58-108  exhaustion: the mouth stays open then slackens, the tongue drops heavily (64), the petals droop, the
    #          head sinks (90), leaves limp, roots loose: its weak moment
    # 108-130  it pulls itself together
    a = Anim("bloom", 130)

    def shake(t):
        return S(TAU * 5 * t) * win(t, 50, 52, 58, 61)

    def mouth_k(t):
        return K(t, (0, 0), (30, 0.05), (46, 1), (58, 1), (72, 0.3), (90, 0))
    rig(a, head=lambda t: (K(t, (0, 0), (28, -26), (34, -22), (46, 16), (52, 22), (58, 16), (74, -6), (90, -30),
                             (108, -28), (130, 0)), 0, 2.5 * shake(t)),
        head_pos=lambda t: (0, 0, K(t, (30, 0), (44, -5), (58, -4), (74, 0))),
        stem=lambda t: (K(t, (0, 0), (28, -4), (46, 6), (60, 4), (90, -6), (108, -6), (130, 0)),
                        K(t, (0, 0), (28, -14), (46, 13), (60, 10), (90, -14), (108, -13), (130, 0)),
                        K(t, (0, 0), (28, -12), (46, 6), (60, 4), (90, -12), (108, -11), (130, 0)), 0),
        stretch=lambda t: 1 + 0.25 * win(t, 30, 46, 58, 72),
        body=lambda t: 0.6 * S(TAU * 5 * t) * win(t, 6, 28, 28, 32),
        jaw=lambda t: (K(t, (0, 0), (28, 14), (34, 16), (46, 34), (58, 34), (70, 22), (90, 12), (108, 10), (130, 0)),
                       K(t, (0, 0), (28, 20), (46, 50), (58, 50), (70, 42), (90, 30), (108, 24), (130, 0))),
        mouth=lambda t: (1 + 0.6 * mouth_k(t), 1 + 0.65 * mouth_k(t), 1 + 0.3 * mouth_k(t)),
        outer=lambda t, i: (K(t, (0, 0), (28, -38), (32, -38), (46, 42), (58, 42), (72, 16), (90, -6), (108, -8), (130, 0))
                            + 7 * shake(t + 0.03 * i),
                            K(t, (0, 0), (28, -24), (46, 20), (58, 20), (90, -12), (130, 0))),
        inner=lambda t, i: K(t, (0, 0), (28, -10), (46, 30), (58, 30), (80, 6), (130, 0)),
        sepal=lambda t, i: K(t, (0, 0), (28, 35), (46, 40), (58, 40), (80, -8), (130, 0)),
        tongue=lambda t: (K(t, (0, 1), (10, 0.3), (28, 0.1), (36, 0.1), (42, 2.0), (58, 1.9), (70, 1.6), (108, 1.4), (130, 1)),
                          K(t, (0, 0), (36, 0), (42, 12), (52, 8), (64, -26), (108, -22), (130, 0)), 0,
                          K(t, (0, 0), (42, 24), (52, 10), (64, -44), (108, -40), (130, 0))),
        leaf=lambda t, s: (K(t, (0, 0), (28, 30), (46, 46), (58, 46), (74, -20), (108, -24), (130, 0)) + 5 * shake(t + 0.1 * s),
                           K(t, (0, 0), (28, 14), (46, 24), (58, 24), (74, -16), (108, -18), (130, 0))),
        root=lambda t, i: (K(t, (0, 0), (28, -5), (46, 12), (58, 12), (74, 0)),
                           K(t, (0, 0), (28, 26), (46, -6), (58, -6), (74, 4), (130, 0))))
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
                if all(max(abs(c - r) for c, r in zip(v, (1, 1, 1) if ch == "scale" else (0, 0, 0))) < 1e-4 for v in vs):
                    continue            # a channel that never leaves the rest pose: not written
                for tick, v in enumerate(vs):
                    if ch == "rotation":
                        v = (-v[0], -v[1], v[2])
                    elif ch == "position":
                        v = (-v[0], v[1], v[2])
                    n[0] += 1
                    frames.append({"channel": ch, "data_points": [{"x": f"{v[0]:.3f}", "y": f"{v[1]:.3f}", "z": f"{v[2]:.3f}"}],
                                   "uuid": uid(f"kf/{a.name}/{n[0]}"), "time": round(T(tick), 4), "color": -1,
                                   "interpolation": "linear"})
            if frames:
                animators[uid("group/" + bone)] = {"name": bone, "type": "bone", "keyframes": frames}
        out.append({"uuid": uid("anim/" + a.name), "name": a.name, "loop": "loop" if a.loop else "once",
                    "override": False, "length": a.length, "snapping": 20, "selected": False, "anim_time_update": "",
                    "blend_weight": "", "start_delay": "", "loop_delay": "", "animators": animators,
                    "_values": values})
    return out

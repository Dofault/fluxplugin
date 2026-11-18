#!/usr/bin/env python3
"""Generates "RPG" (rpg), an adventure theme for the RPG universe in the spirit of the note block MMORPG soundtracks
(Wynncraft as the reference: driving octave bass, rippling arpeggios, rim-shot drums, a singable fantasy tune).
Original composition, no quote of any existing piece. Engine in score.py.

E minor at 4/4, 140 to the quarter (a bar lasts 1.71 s). The harmony stays in natural minor with the major V (B7)
for the pull home, the bVI-bVII-i of adventure themes (C D Em), the relative major for the heroic part and one
Phrygian bII (F) in the bridge for the mystery.

Layers, one role each: flute = the tune; bells = the tune an octave above in the climax; pling = sixteenth
arpeggios (the ripple); guitar = off-beat strums; iron xylophone = answers and the bridge tune; harp = second voice
and sweeps; bass = galloping roots and octaves; didgeridoo = the deep floor under the bass; drums = kick, rim snare,
hat.

Form (the intro plays once, then the rest loops, about 1 min 43 s):

    intro  L'Appel          drone, bells announce the head of the theme, the ripple starts, the drums roll in
    a      La Route         the theme (16 bars), second phrase climbs to the top F#
    b      Les Plaines      relative G major, broad heroic tune, xylophone answers, gallop bass
    c      Les Ruines       half time, mystery: the theme's head turned over on the xylophone, Phrygian F,
                            heartbeat kick, then a build-up and a snare roll
    a2     L'Épopée         the theme with bells an octave above, second voice, descant, full drums
    coda   Le Retour        a last phrase over Em C Am B7, a fill leads back to the road (a)

    python3 gen_rpg.py [output folder]      (default: ../src/main/resources/songs)

Never edit songs/rpg.yml by hand: change this script and run it again.
"""
import random
import sys

import score
from score import Piece, Section, chord, copy, descant, drums, harmony_below, lowest, run

TRACKS = {
    'theme':      ('flute', 0.72),
    'cloches':    ('bell', 0.24),          # the theme an octave above (climax)
    'appel':      ('bell', 0.34),          # the call in the intro
    'reponse':    ('iron_xylophone', 0.5),  # answers to the flute, bridge tune
    'tierce':     ('harp', 0.3),           # second voice under the theme
    'contrechant': ('chime', 0.14),
    'ondes':      ('pling', 0.22),         # the sixteenth ripple
    'harpe':      ('harp', 0.28),          # rising sweeps
    'guitare':    ('guitar', 0.3),
    'basse':      ('bass', 0.75),
    'grave':      ('didgeridoo', 0.4),     # deep floor under the bass
    'grosse':     ('basedrum', 0.5),
    'caisse':     ('snare', 0.26),
    'roulement':  ('snare', 0.2),
    'charley':    ('hat', 0.14),
}
PIECE = Piece('rpg', 'RPG', bpm=140, steps_per_beat=4, beats_per_bar=4, tracks=TRACKS,
              melodic={'theme', 'reponse'})
L = PIECE.L
LEADS = ('theme', 'reponse', 'appel')
GALLOP = [(0, 'r'), (2, 'o'), (3, 'r'), (4, 'r'), (6, 'o'), (8, 'r'), (10, 'o'), (11, 'r'), (12, 'r'), (14, 'f')]
PUMP = [(0, 'r'), (2, 'o'), (4, 'r'), (6, 'o'), (8, 'r'), (10, 'o'), (12, 'r'), (14, 'f')]


# ------------------------------------------------------------------ accompaniment of this piece

def ripple(sec, track, bars=None, low=59):
    """Sixteenth arpeggio of the chord: up over an octave and a half, back down (root, third, fifth, octave...)."""
    _, hi = sec.piece.range(track)
    for start, size, sym in sec.slots_in(bars):
        root, iv = chord(sym)
        base = lowest(root, low)
        up = [base + x for x in (0, iv[1], iv[2], 12, 12 + iv[1], 12 + iv[2])]
        up = [n - 12 if n > hi else n for n in up]
        cycle = up + up[-2:0:-1]
        for k in range(size):
            sec.hit(track, start + k, {cycle[k % len(cycle)]})


def deep(sec, track, bars=None, steps=(0,)):
    """The root as low as the didgeridoo goes: the floor under the bass."""
    lo, _ = sec.piece.range(track)
    for start, size, sym in sec.slots_in(bars):
        root, _ = chord(sym)
        for s in steps:
            if s < size:
                sec.hit(track, start + s, {lowest(root, lo + 4)})


def sweep(sec, track, bar, step, count, low=59):
    _, hi = sec.piece.range(track)
    root, iv = chord(sec.chord_at(bar * L + step))
    n, notes = lowest(root, low), []
    while len(notes) < count and n <= hi:
        notes += [n + x for x in iv[:3] if n + x <= hi]
        n += 12
    for k, x in enumerate(notes[:count]):
        sec.hit(track, bar * L + step + k, {x})


def beat(sec, bars, full=True):
    """The adventure groove: kick on 1, the 'and' of 2 and 3, rim snare on 2 and 4, hat on the off-beats."""
    drums(sec, 'grosse', (0, 6, 8) if full else (0, 8), bars)
    drums(sec, 'caisse', (4, 12), bars)
    drums(sec, 'charley', (2, 6, 10, 14) if full else (2, 10), bars)


def fill(sec, bar, roll_from=8):
    """A snare fill at the end of a phrase, crescendo through two tracks."""
    drums(sec, 'roulement', range(roll_from, 12), [bar])
    drums(sec, 'caisse', (12, 13, 14, 15), [bar])


def give_way(sec):
    """The accompaniment never strikes a semitone against a lead note at the same moment (unless both belong to the
    chord): the accompanist leaves that note out."""
    for lead in LEADS:
        for step, ns in sec.tracks.get(lead, {}).items():
            pcs = score.tones(sec.chord_at(step))
            for t, hits in sec.tracks.items():
                if t in LEADS or sec.piece.instrument(t) in score.DRUMS or step not in hits:
                    continue
                bad = {a for a in hits[step] for b in ns
                       if abs(a - b) % 12 in (1, 11) and not {a % 12, b % 12} <= pcs}
                hits[step] -= bad
                if not hits[step]:
                    del hits[step]


# ------------------------------------------------------------------ the theme

# 16 bars. The head (E B E F# G) is the motto of the piece: it opens both phrases, the intro's call, the coda.
# First phrase climbs from E to the octave and falls on the leading tone D#; the second climbs to the top F# over
# C D B7 and lands on the high E.
THEME = ['E5:3 B4:1 E5:2 F#5:2 G5:4 F#5:2 E5:2', 'G5:3 E5:1 G5:2 A5:2 B5:4 A5:2 G5:2',
         'D6:6 B5:2 G5:4 A5:2 B5:2', 'A5:6 F#5:2 D5:4 E5:2 F#5:2',
         'E5:3 B4:1 E5:2 F#5:2 G5:4 B5:2 E6:2', 'E6:4 D6:2 C6:2 B5:4 G5:4',
         'A5:3 C6:1 E6:2 D6:2 C6:4 B5:2 A5:2', 'B5:4 A5:2 F#5:2 D#5:4 F#5:2 B5:2',
         'E5:3 B4:1 E5:2 F#5:2 G5:4 F#5:2 E5:2', 'G5:3 E5:1 G5:2 A5:2 B5:4 C6:2 D6:2',
         'D6:6 B5:2 G5:2 A5:2 B5:2 D6:2', 'F#6:6 E6:2 D6:4 A5:4',
         'E6:6 D6:2 C6:4 G5:4', 'D6:3 C6:1 B5:2 A5:2 F#5:4 A5:4',
         'B5:4 D#6:4 F#6:6 D#6:2', 'E6:12 .:4']
THEME_CHORDS = 'Em Cmaj7 G D Em Cmaj7 Am B7 Em Cmaj7 G D C D B7 Em'
MOTTO = 'E6:6 B5:2 E6:4 F#6:4'


def intro(rng):
    s = Section(PIECE, 'intro', 'Em Cmaj7 Em Cmaj7 Am Cmaj7 B7sus B7')
    s.write('appel', MOTTO + ' | G6:8 E6:8 | ' + MOTTO + ' | G6:8 B6:8')
    s.write('reponse', 'E5:4 B4:4 C5:4 E5:4 | G4:4 B4:4 E5:4 G4:4 | A4:8 F#4:4 E4:4 | F#4:8 .:8', bar=4)
    s.write('theme', '.:8 B4:2 D#5:2 F#5:2 A5:2', bar=7)
    deep(s, 'grave', steps=(0, 8))
    ripple(s, 'ondes', bars=range(2, 8))
    score.bass(s, 'basse', [(0, 'r'), (8, 'r')], bars=range(4, 6))
    score.bass(s, 'basse', PUMP, bars=range(6, 8))
    drums(s, 'grosse', (0, 8), range(2, 6))
    drums(s, 'charley', (2, 6, 10, 14), range(4, 8))
    drums(s, 'grosse', (0, 6, 8), [6])
    drums(s, 'caisse', (4, 12), [6])
    drums(s, 'roulement', range(0, 8, 2), [7])
    drums(s, 'roulement', range(8, 12), [7])
    drums(s, 'caisse', (12, 13, 14, 15), [7])
    sweep(s, 'harpe', 3, 10, 6)
    run(s, 'contrechant', 1, 8, 'B7', [2, 1, 2, 2, 3], 6, down=True)
    return s


def section_a(rng):
    s = Section(PIECE, 'a', THEME_CHORDS)
    s.write('theme', ' | '.join(THEME))
    # the xylophone answers in the long notes of the tune
    s.write('reponse', '.:8 A4:2 B4:2 C5:2 D5:2', bar=3)
    s.write('reponse', '.:8 F#4:2 A4:2 D5:2 E5:2', bar=11)
    ripple(s, 'ondes')
    score.strum(s, 'guitare', pattern=(2, 6, 10, 14), low=52)
    score.bass(s, 'basse', PUMP)
    deep(s, 'grave', steps=(0,))
    beat(s, range(16))
    fill(s, 7, roll_from=12)
    fill(s, 15)
    sweep(s, 'harpe', 7, 8, 6)
    score.stars(s, 'contrechant', (3, 7, 11), rng, [8, 12], chance=1)
    return s


def section_b(rng):
    s = Section(PIECE, 'b', 'C D G Em C D Bm Em Am D7 G C Am B7 Em,C B7')
    s.write('theme', 'G5:8 E5:4 G5:4 | A5:8 F#5:4 D5:4 | B5:6 A5:2 G5:4 D5:4 | E5:12 .:4 '
                     '| G5:4 A5:2 B5:2 C6:4 E6:4 | F#6:6 E6:2 D6:8 | D6:6 C#6:2 B5:4 F#5:4 | G5:6 F#5:2 E5:8 '
                     '| E5:4 A5:2 B5:2 C6:4 E6:4 | D6:6 C6:2 A5:4 F#5:4 | B5:4 D6:2 G5:2 B5:4 D6:4 | E6:6 D6:2 C6:4 G5:4 '
                     '| A5:3 B5:1 C6:4 E6:6 C6:2 | D#6:4 B5:4 F#6:4 D#6:4 | E6:6 B5:2 C6:4 E6:4 | F#6:8 D#6:4 B5:4')
    # answers where the flute holds
    s.write('reponse', '.:12 B4:1 C5:1 D5:1 E5:1', bar=2)
    s.write('reponse', '.:8 B4:2 G4:2 E4:2 G4:2', bar=3)
    s.write('reponse', '.:8 B4:2 C#5:2 D5:2 F#5:2', bar=6)
    s.write('reponse', '.:8 G4:2 B4:2 E5:2 G4:2', bar=7)
    ripple(s, 'ondes', low=55)
    score.strum(s, 'guitare', pattern=(0, 4, 6, 10, 12), low=50)
    score.bass(s, 'basse', GALLOP)
    deep(s, 'grave', steps=(0, 8))
    beat(s, range(16))
    drums(s, 'grosse', (0, 3, 6, 8, 11), range(8, 16))
    fill(s, 7, roll_from=12)
    fill(s, 15)
    score.stars(s, 'contrechant', (3, 7, 11), rng, [8, 12], chance=1)
    sweep(s, 'harpe', 15, 8, 6)
    return s


def section_c(rng):
    s = Section(PIECE, 'c', 'Em Cmaj7 Am B7 Em Cmaj7 Fmaj7 B7')
    # the theme's head turned over, slow, on the xylophone
    s.write('reponse', 'B4:8 G4:4 E4:4 | G4:8 B4:4 E5:4 | C5:8 A4:4 E4:4 | D#5:8 F#4:4 A4:4 '
                       '| G4:8 B4:4 E5:4 | E5:8 C5:4 B4:4 | A4:8 C5:4 E5:4 | D#5:8 B4:4 A4:4')
    # the flute comes back in the second half, high and long
    s.write('theme', 'B5:16 | G5:8 E5:8 | A5:8 C6:8 | B5:8 .:4 B4:2 D#5:2', bar=4)
    deep(s, 'grave', steps=(0, 8))
    score.shimmer(s, 'ondes', range(4), every=2)
    ripple(s, 'ondes', bars=range(4, 8), low=55)
    score.bass(s, 'basse', [(0, 'r'), (8, 'r')], bars=range(4))
    score.bass(s, 'basse', PUMP, bars=range(4, 8))
    drums(s, 'grosse', (0, 3), range(4))           # heartbeat
    drums(s, 'charley', (8,), range(4))
    drums(s, 'grosse', (0, 8), range(4, 7))
    drums(s, 'charley', (2, 6, 10, 14), range(4, 7))
    drums(s, 'roulement', range(0, 16, 2), [6])
    drums(s, 'roulement', range(0, 12), [7])
    drums(s, 'caisse', (12, 13, 14, 15), [7])
    drums(s, 'grosse', (0, 4, 8), [7])
    sweep(s, 'harpe', 7, 8, 8)
    return s


def section_a2(rng):
    s = Section(PIECE, 'a2', THEME_CHORDS)
    s.write('theme', ' | '.join(THEME))
    copy(s, 'theme', 'cloches', transpose=12)
    harmony_below(s, 'tierce', 'theme')
    descant(s, 'contrechant', 'G6', range(16), per_bar=2)
    ripple(s, 'ondes')
    score.strum(s, 'guitare', pattern=(0, 4, 6, 10, 12, 14), low=52)
    score.bass(s, 'basse', GALLOP)
    deep(s, 'grave', steps=(0, 8))
    beat(s, range(16))
    drums(s, 'grosse', (0, 3, 6, 8, 11), range(16))
    drums(s, 'charley', (0, 4, 8, 12), range(8, 16))
    fill(s, 7, roll_from=12)
    fill(s, 15)
    return s


def coda(rng):
    s = Section(PIECE, 'coda', 'Em Cmaj7 Am B7')
    s.write('theme', 'E6:6 B5:2 E6:4 F#6:4 | E6:8 G5:4 B5:4 | C6:8 A5:4 E5:4 | D#5:4 F#5:4 B5:4 A5:4')
    s.write('reponse', 'E4:4 B4:4 E5:8 | .:16 | A4:4 C5:4 E5:8 | .:16')
    ripple(s, 'ondes')
    score.strum(s, 'guitare', pattern=(2, 6, 10, 14), low=52)
    score.bass(s, 'basse', PUMP)
    deep(s, 'grave', steps=(0, 8))
    beat(s, range(3))
    drums(s, 'grosse', (0, 4, 8), [3])
    drums(s, 'roulement', range(0, 12), [3])
    drums(s, 'caisse', (12, 13, 14, 15), [3])
    sweep(s, 'harpe', 3, 8, 8)
    return s


def strong(sec, step):
    return (0, 8)


def main():
    rng = random.Random(2026_11_18)
    sections = [intro(rng), section_a(rng), section_b(rng), section_c(rng), section_a2(rng), coda(rng)]
    for sec in sections:
        give_way(sec)
    problems = score.check(sections, strong, quiet={'contrechant', 'harpe'})
    path = score.write_song(score.songs_folder(sys.argv, __file__), PIECE, sections, [
        "RPG : thème d'aventure de l'univers RPG, mi mineur à 4/4, 140 à la noire. Appel (intro, une fois), route,",
        'plaines, ruines, épopée, retour. Composition originale.',
    ], 'gen_rpg.py', 'a')
    score.summary(path, PIECE, sections, 'a', problems)


if __name__ == '__main__':
    main()

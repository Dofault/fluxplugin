#!/usr/bin/env python3
"""Generates "Les Lumières de Resaac", the theme of Resaac, the main town of the server (Haussmann boulevards with a
touch of fantasy). Engine in score.py.

The town in music: Belle Époque elegance (minor sixth chords, passing diminished sevenths, a chromatic chanson bass,
a habanera in the café) and magic through whole-tone colours (augmented chords, whole-tone runs over dominants).
The common thread is the town clock: its tick-tock opens the piece and walks along the boulevard, stops while the
lanterns dream, and strikes midnight before the loop.

The lanterns section plays the head of the theme around the augmented triad: Eb major, B major, then G again
(keys a major third apart). Each key is joined to the next by one moving note (the D of Abm6 rises to the D# of B,
the A# of Em6 to the B of G), so the town seems to turn on itself without a seam.

Form, 4/4 at 96 (a bar lasts 2.5 s). The intro plays once, then the loop (about 2 min 50 s):

    intro  L'Horloge de la place     tick-tock, the carillon plays the head of the theme, a whole-tone run
    a      Le Boulevard              the theme on the flute, oom-pah of bass and guitar, glockenspiel descant (G)
    b      Les Lanternes             the theme on bells around the augmented triad (Eb, B, G), the clock stops
    c      Le Café des Rêveurs       E minor chanson over a falling chromatic bass, habanera, flutes in thirds
                                     like an accordion, spoons clinking in cups
    a2     Le Grand Boulevard        the theme again with bells an octave above, harp in thirds, drums, and a
                                     closing bVI-bVII-I (Eb, F, G)
    coda   Minuit                    the carillon strikes, a whole-tone run leads back to the boulevard

    python3 gen_resaac.py [output folder]      (default: ../src/main/resources/songs)

Never edit songs/resaac.yml by hand: change this script and run it again.
"""
import random
import sys

import score
from score import Piece, Section, copy, descant, drums, harmony_below, ring, run

TRACKS = {
    'theme':          ('flute', 0.72),
    'chanson':        ('flute', 0.6),
    'seconde':        ('flute', 0.28),       # second flute a third below: the accordion of the café
    'lanterne':       ('bell', 0.36),
    'lanterne_harpe': ('harp', 0.4),
    'cloches':        ('bell', 0.24),        # the theme an octave above, grand boulevard
    'harmonie':       ('harp', 0.34),
    'contrechant':    ('bell', 0.18),
    'echo':           ('harp', 0.14),
    'carillon':       ('bell', 0.34),        # the town clock
    'magie':          ('chime', 0.16),       # whole-tone runs
    'etoiles':        ('chime', 0.11),
    'arpege':         ('pling', 0.16),
    'guitare':        ('guitar', 0.4),
    'basse':          ('bass', 0.7),
    'basse_douce':    ('bass', 0.5),
    'tictac':         ('hat', 0.07),
    'tasse':          ('hat', 0.045),        # a spoon in a cup
    'grosse':         ('basedrum', 0.42),
    'caisse':         ('snare', 0.2),
    'balai':          ('snare', 0.07),       # brushes
    'charley':        ('hat', 0.08),
    'roulement':      ('snare', 0.26),
}
PIECE = Piece('resaac', 'Les Lumières de Resaac', bpm=96, steps_per_beat=4, beats_per_bar=4, tracks=TRACKS,
              melodic={'theme', 'chanson', 'lanterne', 'carillon'})
L = PIECE.L
SHARPS = ['C', 'C#', 'D', 'D#', 'E', 'F', 'F#', 'G', 'G#', 'A', 'Bb', 'B']

OOM = [(0, 'r'), (8, 'f')]                 # oom-pah: bass on 1 and 3
PAH = (4, 12)                              # guitar on 2 and 4
HABANERA = (0, 6, 8)


def eight(r, iv):
    """Picked guitar in eighths: root, fifth, octave, tenth, twelfth, tenth, octave, fifth."""
    return [r, r + iv[2], r + 12, r + 12 + iv[1], r + 12 + iv[2], r + 12 + iv[1], r + 12, r + iv[2]]


def clock(sec, bars):
    """Tick-tock of the town clock, on every beat."""
    for b in bars:
        for k, s in enumerate(range(0, L, 4)):
            sec.hit('tictac', b * L + s, {74 if k % 2 == 0 else 67})


def cups(sec, bars, rng):
    """Now and then a spoon against a cup, somewhere in the café."""
    for b in bars:
        if rng.random() < 0.55:
            s = rng.randrange(L)
            sec.hit('tasse', b * L + s, {rng.randrange(72, 78)})
            if rng.random() < 0.4:
                sec.hit('tasse', b * L + s + 1, {rng.randrange(72, 78)})


# The theme of Resaac (G major), 16 bars. G+ and Cm6 are its colours: the augmented chord of the fantasy, the minor
# sixth of the Parisian chanson.
THEME = ['B4:3 C5:1 D5:4 G5:6 F#5:2', 'D#5:3 E5:1 D#5:4 B4:8', 'E5:3 F#5:1 G5:4 C6:6 B5:2',
         'A5:2 G5:2 Eb5:4 G5:6 F#5:2', 'G5:3 A5:1 B5:4 D6:6 C6:2', 'B5:4 G#5:4 E5:6 D5:2',
         'E5:3 F#5:1 G5:4 C#6:6 B5:2', 'A5:4 F#5:2 D5:2 C5:8',
         'B4:3 C5:1 D5:4 G5:6 F#5:2', 'D#5:3 E5:1 D#5:4 B4:8', 'E5:3 F#5:1 G5:4 E6:6 D6:2',
         'C#6:4 Bb5:2 G5:2 E5:8', 'D5:3 E5:1 G5:4 B5:6 A5:2', 'G#5:4 B5:4 E6:6 D6:2',
         'C6:4 A5:4 F#5:4 A5:2 C6:2', 'B5:6 A5:2 G5:8']
THEME_CHORDS = 'G G+ C Cm6 G E7 A7 D7 G G+ C C#dim7 G E7 Am7,D7 G'


def intro(rng):
    s = Section(PIECE, 'intro', 'G C D7 D7#5', SHARPS)
    clock(s, range(4))
    s.write('carillon', 'B5:4 C6:4 D6:4 G6:4 | E6:4 D6:4 C6:4 G5:4 | A5:8 F#5:8 | .:16')
    ring(s, 'carillon', 2, 0, 'D6+F#6')
    score.bass(s, 'basse_douce', [(0, 'r')])
    score.arpeggio(s, 'arpege', [2], every=2)
    # the augmented fifth (A#) of the last chord rises to the B that opens the theme
    run(s, 'magie', 3, 0, 'D6', [2], 12)
    run(s, 'arpege', 3, 4, 'D4', [2], 12)
    return s


def section_a(rng):
    s = Section(PIECE, 'a', THEME_CHORDS, SHARPS)
    s.write('theme', ' | '.join(THEME))
    score.bass(s, 'basse', OOM)
    score.strum(s, 'guitare', pattern=PAH, low=52)
    clock(s, range(8))
    descant(s, 'contrechant', 'D6', range(8, 16), per_bar=2)
    score.arpeggio(s, 'arpege', range(8, 16), every=2)
    for b in (1, 9):                                     # the augmented chord sparkles
        run(s, 'magie', b, 8, 'G5', [2], 6)
    run(s, 'magie', 7, 8, 'D6', [2], 8)                  # whole-tone dominant
    drums(s, 'balai', (4, 12), range(16))
    drums(s, 'charley', (2, 6, 10, 14), range(8, 16))
    return s


def section_b(rng):
    s = Section(PIECE, 'b', 'Eb Eb+ Ab Abm6 B B+ E Em6 G G+ C Cm6 C Am F#m7b5 B7')
    head = ' | '.join(THEME[:4])
    s.write('lanterne', head, bar=0, transpose=-4)
    s.write('lanterne', head, bar=4, transpose=4)
    s.write('lanterne', head, bar=8)
    s.write('lanterne', 'E5:4 G5:4 C6:8 | C6:3 B5:1 A5:4 E5:8 | F#5:4 A5:4 C6:4 E6:4 | D#6:8 B5:4 A5:4', bar=12)
    copy(s, 'lanterne', 'lanterne_harpe')
    copy(s, 'lanterne_harpe', 'echo', delay=3)
    # lanterns floating in eighths; the last eighth of each bar stays free for the tune's passing note, and the
    # end of each phrase breathes
    score.arpeggio(s, 'arpege', range(16), every=2)
    for b in range(16):
        for st in ((12, 14) if b % 4 == 3 else (14,)):
            s.tracks['arpege'].pop(b * L + st, None)
    score.bass(s, 'basse_douce', [(0, 'r'), (8, 'f')])
    for b in (1, 5, 9):                                  # Eb+, B+, G+
        run(s, 'magie', b, 0, score.SPELLING[(score.chord(s.bar_chords[b])[0]) % 12] + '5', [2], 12, every=1)
    score.stars(s, 'etoiles', range(16), rng, [0, 4, 6, 8, 12, 10], chance=0.5)
    return s


def section_c(rng):
    s = Section(PIECE, 'c', 'Em B Em7 A Cmaj7 B7 Em E7 Am D7 Gmaj7 Cmaj7 F#m7b5 B7 Am7 D7', SHARPS)
    s.write('chanson', 'B5:6 C6:2 B5:4 G5:4 | F#5:6 G5:2 F#5:4 D#5:4 | E5:4 G5:4 B5:4 D6:4 | C#6:6 B5:2 A5:8 '
                       '| G5:6 A5:2 B5:4 E5:4 | D#5:4 F#5:4 A5:4 B5:4 | G5:12 F#5:4 | E5:4 G#5:4 B5:4 D6:4 '
                       '| C6:6 D6:2 C6:4 A5:4 | F#5:7 G5:1 A5:4 C6:4 | B5:6 A5:2 G5:4 F#5:4 | E5:6 F#5:2 G5:4 B5:4 '
                       '| A5:6 B5:2 C6:4 A5:4 | D#6:7 C6:1 B5:4 A5:4 | G5:6 A5:2 E5:4 C5:4 | C6:4 A5:4 F#5:4 D5:4')
    harmony_below(s, 'seconde', 'chanson')
    # the chanson bass falls a semitone at a time, in a habanera rhythm
    score.line_bass(s, 'basse', 'E2 D#2 D2 C#2 C2 B1 E2 G#1 A1 D2 G1 C2 F#1 B1 A1 D2', HABANERA)
    score.pick(s, 'guitare', shape=eight)
    drums(s, 'balai', (4, 12), range(16))
    cups(s, range(16), rng)
    return s


def section_a2(rng):
    s = Section(PIECE, 'a2', THEME_CHORDS.rsplit(' ', 2)[0] + ' Eb,F G', SHARPS)
    s.write('theme', ' | '.join(THEME[:14] + ['G5:4 Bb5:4 A5:4 C6:4', 'D6:4 B5:4 G5:8']))
    copy(s, 'theme', 'cloches', transpose=12)
    harmony_below(s, 'harmonie', 'theme')
    score.bass(s, 'basse', OOM + [(12, 'o')])
    score.strum(s, 'guitare', pattern=PAH, low=52)
    score.arpeggio(s, 'arpege', range(16), every=4)
    ring(s, 'carillon', 0, 0, 'G5+D6')
    ring(s, 'carillon', 8, 0, 'G5+D6')
    for b in (1, 9):
        run(s, 'magie', b, 8, 'G5', [2], 6)
    run(s, 'magie', 7, 8, 'D6', [2], 8)
    drums(s, 'grosse', (0, 8), range(16))
    drums(s, 'caisse', (4, 12), [b for b in range(16) if b != 7])
    drums(s, 'charley', (2, 6, 10, 14), range(16))
    drums(s, 'roulement', (8, 10, 12, 13, 14, 15), [7])
    drums(s, 'caisse', (4,), [7])
    return s


def coda(rng):
    s = Section(PIECE, 'coda', 'C Cm6 G D7#5', SHARPS)
    s.write('carillon', 'G6:4 E6:4 C6:8 | Eb6:4 C6:4 A5:8 | .:16 | .:16')
    for st in (0, 4, 8, 12):                             # midnight strikes
        ring(s, 'carillon', 2, st, 'G5+D6')
    clock(s, range(4))
    score.bass(s, 'basse_douce', [(0, 'r')])
    run(s, 'magie', 3, 0, 'D6', [2], 12)
    run(s, 'arpege', 3, 4, 'D4', [2], 12)
    return s


def strong(sec, step):
    return (0, 8)


def main():
    rng = random.Random(2026_11_12)
    sections = [intro(rng), section_a(rng), section_b(rng), section_c(rng), section_a2(rng), coda(rng)]
    problems = score.check(sections, strong, quiet={'echo', 'etoiles', 'magie'})
    path = score.write_song(score.songs_folder(sys.argv, __file__), PIECE, sections, [
        'Thème de Resaac (ville principale, haussmannienne avec une touche de fantaisie) : sol majeur à 4/4, horloge de',
        'la ville, boulevard, lanternes autour de la triade augmentée (mi bémol, si, sol), café en mi mineur, grand',
        'boulevard, minuit.',
    ], 'gen_resaac.py', 'a')
    score.summary(path, PIECE, sections, 'a', problems)


if __name__ == '__main__':
    main()

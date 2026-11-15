#!/usr/bin/env python3
"""Generates "Les Avenues de Resaac" (resaac2), the walking theme of Resaac seen as a large modern town with
Haussmann architecture. Engine in score.py.

The brief: a Japanese RPG town theme (the warmth and tunefulness of the Pokémon town themes, without quoting any of
them), playable with note blocks only. D major at 4/4, 112 to the quarter (a bar lasts 2.14 s). No accordion, no
waltz, no café cliché: the town is modern, the elegance comes from the harmony (major sevenths, the IV-V-iii-vi
"royal road", a borrowed minor iv, bVI-bVII-I at the top of the reprise).

Layers, one role each: flute = the tune; "piano" (the harp note block) = broken chords and answers; guitar = warmth
(soft off-beat strums, picking, one answer); chime and high harp = sparkles at phrase ends; bass = roots with a
little movement; drums = barely there.

Form (the whole piece loops, about 2 min 51 s):

    intro  L'Arrivée            piano riff alone, then the flute sketches the theme                    0:00
    a      Les Avenues          the theme (16 bars): two 8-bar phrases, the second climbs and sighs
                                on the minor iv                                                        0:17
    b      Les Passants         development on IV-V-iii-vi: the flute calls, piano then guitar answer,
                                then one long climbing phrase, chimes on the phrase ends               0:51
    c      La Grande Place      B minor, contemplative: the piano sings alone over slow guitar (the
                                fountain), then the flute takes over in long notes                     1:26
    a2     Les Grands Boulevards the theme with a second voice in thirds, bells an octave above,
                                picked guitar, light drums; bVI-bVII lifts it to the top F#            2:00
    coda   Le Soir              the top F# falls back, the piano riff returns and leads to the intro   2:34

    python3 gen_resaac2.py [output folder]      (default: ../src/main/resources/songs)

Never edit songs/resaac2.yml by hand: change this script and run it again.
"""
import random
import sys

import score
from score import Piece, Section, chord, copy, descant, drums, harmony_below, lowest, midi, run

TRACKS = {
    'theme':        ('flute', 0.7),
    'place':        ('harp', 0.62),        # the piano singing alone on the square
    'reponse':      ('harp', 0.5),         # piano answers to the flute
    'tierce':       ('harp', 0.26),        # second voice under the theme (reprise)
    'cloches':      ('bell', 0.16),        # the theme an octave above (reprise)
    'contrechant':  ('bell', 0.14),
    'piano':        ('harp', 0.3),
    'riff':         ('harp', 0.46),        # the piano riff that opens and closes the piece
    'guitare':      ('guitar', 0.34),
    'guitare_solo': ('guitar', 0.5),
    'harpe':        ('harp', 0.2),         # quick rising sweeps
    'cascade':      ('chime', 0.15),
    'etoiles':      ('chime', 0.1),
    'basse':        ('bass', 0.55),
    'basse_douce':  ('bass', 0.42),
    'grosse':       ('basedrum', 0.24),
    'balai':        ('snare', 0.06),
    'charley':      ('hat', 0.05),
}
PIECE = Piece('resaac2', 'Les Avenues de Resaac', bpm=112, steps_per_beat=4, beats_per_bar=4, tracks=TRACKS,
              melodic={'theme', 'place'})
L = PIECE.L
LEADS = ('theme', 'place', 'reponse', 'guitare_solo', 'riff')
PENTA_DOWN = [2, 2, 3, 2, 3]               # D major pentatonic falling from F#


# ------------------------------------------------------------------ accompaniment of this piece

def bassline(sec, track, pattern, over=None, bars=None):
    """Root, fifth ('f') and octave ('o') of each chord. over: bar -> bass note of a slash chord (A/C#, D/F#); the
    'f' of such a bar is the chord's root."""
    lo, hi = sec.piece.range(track)
    for start, size, sym in sec.slots_in(bars):
        root, iv = chord(sym)
        slash = (over or {}).get(start // L)
        if slash:
            r = lowest(score.NAMES[slash[0]] + (1 if slash.endswith('#') else 0), lo + 2)
            f = lowest(root, r)
        else:
            r = lowest(root, lo + 2)
            f = r + iv[2]
        o = r + 12 if r + 12 <= hi else r
        notes = {'r': r, 'f': f if f <= hi else f - 12, 'o': o}
        for s, which in pattern:
            if s < size:
                sec.hit(track, start + s, {notes[which]})


def broken(sec, track, pattern, bars=None, low=57, every=2):
    """Broken chords: pattern of chord degrees, one per `every` steps (None = breath). r 3 5, 7 (seventh or ninth,
    the octave on a plain triad), R T F = root, third, fifth an octave above."""
    _, hi = sec.piece.range(track)
    for start, size, sym in sec.slots_in(bars):
        root, iv = chord(sym)
        base = lowest(root, low)
        deg = {'r': base, '3': base + iv[1], '5': base + iv[2], '7': base + (iv[3] if len(iv) > 3 else 12),
               'R': base + 12, 'T': base + 12 + iv[1], 'F': base + 12 + iv[2]}
        for k, s in enumerate(range(0, size, every)):
            d = pattern[k % len(pattern)]
            if d is not None:
                n = deg[d]
                sec.hit(track, start + s, {n - 12 if n > hi else n})


def shells(sec, track, steps, bars=None, low=59):
    """Third and seventh (or fifth) of the chord, struck together: the piano staying out of the way."""
    for start, size, sym in sec.slots_in(bars):
        root, iv = chord(sym)
        third = lowest((root + iv[1]) % 12, low)
        top = lowest((root + (iv[3] if len(iv) > 3 else iv[2])) % 12, third + 1)
        for s in steps:
            if s < size:
                sec.hit(track, start + s, {third, top})


def sweep(sec, track, bar, step, count, low=62):
    """A quick rising arpeggio of the chord, one note per step."""
    _, hi = sec.piece.range(track)
    root, iv = chord(sec.chord_at(bar * L + step))
    n, notes = lowest(root, low), []
    while len(notes) < count:
        notes += [n + x for x in iv[:3] if n + x <= hi]
        n += 12
        if n > hi:
            break
    for k, x in enumerate(notes[:count]):
        sec.hit(track, bar * L + step + k, {x})


def eight(r, iv):
    """Picked guitar in eighths: root, fifth, octave, tenth, twelfth, tenth, octave, fifth."""
    return [r, r + iv[2], r + 12, r + 12 + iv[1], r + 12 + iv[2], r + 12 + iv[1], r + 12, r + iv[2]]


def slow(r, iv):
    """Slow picking in quarters: root, fifth, tenth, fifth."""
    return [r, r + iv[2], r + 12 + iv[1], r + iv[2]]


def give_way(sec):
    """The accompaniment never strikes a semitone against a note the lead strikes at the same moment (unless both
    belong to the chord): the accompanist leaves that note out."""
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

# 16 bars in D. First phrase: a falling sigh answered a third lower, then a climb back. Second phrase: the same
# start, but it climbs to D6, touches C natural (D7) and sighs B -> Bb on the borrowed minor iv before closing.
THEME = ['F#5:6 A5:2 E5:6 D5:2', 'C#5:4 E5:4 A5:6 E5:2', 'D5:6 F#5:2 B5:6 A5:2', 'A5:4 E5:4 C#5:8',
         'B4:2 D5:2 F#5:4 G5:2 F#5:2 D5:4', 'A5:6 F#5:2 D5:8', 'G5:2 F#5:2 E5:2 D5:2 E5:4 B5:4',
         'A5:8 .:2 A4:2 D5:2 E5:2',
         'F#5:6 A5:2 E5:6 D5:2', 'C#5:4 E5:4 A5:6 B5:2', 'D6:6 C#6:2 B5:4 A5:4', 'G5:6 E5:2 F#5:4 C6:4',
         'B5:6 A5:2 Bb5:6 G5:2', 'A5:4 E5:2 F#5:2 D5:4 F#5:4', 'E5:2 F#5:2 G5:2 B5:2 A5:4 C#6:4', 'D6:12 .:4']
THEME_CHORDS = 'Dadd9 A Bm7 F#m7 Gmaj7 D Em7 Asus,A7 Dadd9 A Bm7 Am7,D7 Gmaj7,Gm6 F#m7,Bm7 Em7,A7 D'
SLASH = {1: 'C#', 5: 'F#', 9: 'C#'}        # A/C#, D/F#
PICKUP = 'A4:2 D5:2 E5:2'
RIFF = 'D4:2 A4:2 D5:2 E5:2 F#5:4 E5:2 A4:2 | D4:2 G4:2 B4:2 D5:2 F#5:4 D5:2 B4:2'


def intro(rng):
    s = Section(PIECE, 'intro', 'Dadd9 Gmaj7 Dadd9 Gmaj7 Bm7 Gmaj7 Em7 A7')
    s.write('riff', RIFF + ' | ' + RIFF)
    s.write('theme', 'F#5:8 D5:8 | B4:8 D5:4 F#5:4 | E5:12 G5:4 | A5:8 .:2 ' + PICKUP, bar=4)
    broken(s, 'piano', ['r', '5', '7', 'T', None, '7', '5', None], bars=range(4, 8))
    bassline(s, 'basse_douce', [(0, 'r')], bars=range(2, 8))
    bassline(s, 'basse_douce', [(8, 'f')], bars=range(4, 8))
    sweep(s, 'harpe', 3, 10, 6)
    run(s, 'cascade', 1, 12, 'F#7', PENTA_DOWN, 4, down=True)
    score.stars(s, 'etoiles', range(4, 8), rng, [4, 12], chance=0.6)
    return s


def section_a(rng):
    s = Section(PIECE, 'a', THEME_CHORDS)
    s.write('theme', ' | '.join(THEME))
    broken(s, 'piano', ['r', '5', '7', 'T', None, '7', '5', None])
    # the piano fills the long notes of the tune, the guitar answers before the second phrase
    s.write('reponse', '.:10 F#4:2 A4:2 D5:2', bar=5)
    s.write('reponse', '.:4 F#4:2 A4:2 D5:2 E5:2 F#5:4', bar=15)
    s.write('guitare_solo', '.:2 E3:2 A3:2 D4:2 C#4:8', bar=7)
    score.strum(s, 'guitare', pattern=(4, 12), low=50, bars=range(8))
    score.strum(s, 'guitare', pattern=(4, 10, 12), low=50, bars=range(8, 16))
    bassline(s, 'basse', [(0, 'r'), (6, 'f'), (8, 'o'), (12, 'f')], SLASH)
    drums(s, 'balai', (4, 12), range(16))
    drums(s, 'charley', (2, 6, 10, 14), range(8, 16))
    drums(s, 'grosse', (0, 10), range(8, 15))
    run(s, 'cascade', 15, 4, 'F#7', PENTA_DOWN, 6, every=2, down=True)
    score.stars(s, 'etoiles', (3, 7, 11), rng, [8, 10, 12], chance=1)
    return s


def section_b(rng):
    s = Section(PIECE, 'b', 'Gmaj7 A7 F#m7 Bm7 Gmaj7 A7 Dmaj7 D7 Gmaj7 A7 F#m7 Bm7 Em7 Gm6 Asus A7')
    s.write('theme', 'B5:6 A5:2 F#5:4 D5:4 | E5:6 G5:2 C#6:8 | .:16 | .:16 '
                     '| B5:6 D6:2 F#6:4 E6:4 | C#6:6 B5:2 A5:8 | .:16 | .:12 A5:2 B5:2 '
                     '| D6:6 B5:2 F#5:4 G5:4 | A5:6 G5:2 E5:4 C#5:4 | C#5:2 E5:2 A5:4 C#6:6 B5:2 | A5:4 F#5:4 D5:8 '
                     '| G5:2 A5:2 B5:4 D6:6 E6:2 | E6:6 D6:2 Bb5:4 G5:4 | A5:4 B5:4 D6:8 | C#6:12 .:4')
    s.write('reponse', 'C#5:2 E5:2 A4:4 C#5:2 E5:2 F#5:4 | D5:6 C#5:2 B4:8', bar=2)
    s.write('guitare_solo', 'F#3:2 A3:2 D4:4 C#4:2 A3:2 F#3:4 | A3:2 C4:2 D4:4 F#4:8', bar=6)
    shells(s, 'piano', (0, 6), bars=[0, 1, 4, 5])
    shells(s, 'piano', (0,), bars=[6, 7])
    broken(s, 'piano', ['r', '5', '7', 'T', 'F', 'T', '7', '5'], bars=range(8, 16))
    score.pick(s, 'guitare', shape=eight, bars=[0, 1, 2, 3, 4, 5] + list(range(8, 16)))
    bassline(s, 'basse', [(0, 'r'), (6, 'r'), (8, 'f'), (14, 'f')])
    drums(s, 'grosse', (0, 10), range(15))
    drums(s, 'balai', (4, 12), range(16))
    drums(s, 'charley', (2, 6, 10, 14), range(8, 16))
    # chimes on the phrase ends
    score.stars(s, 'etoiles', (1, 5, 11, 15), rng, [8, 10, 12], chance=1)
    score.stars(s, 'etoiles', (9, 13), rng, [12, 14], chance=1)
    sweep(s, 'harpe', 15, 10, 6)
    return s


def section_c(rng):
    s = Section(PIECE, 'c', 'Bm7 E7 Gmaj7 D Em7 F#7 Bm7 Am7,D7 Gmaj7 Gm6 F#m7 B7 Em7 C Asus A7')
    s.write('place', 'F#5:8 D5:4 B4:4 | G#4:4 B4:4 E5:6 D5:2 | D5:8 B4:4 F#4:4 | A4:12 .:4 '
                     '| G4:4 B4:4 E5:6 D5:2 | C#5:6 A#4:2 F#4:4 C#5:4 | D5:8 F#5:8 | E5:4 C5:4 D5:4 F#5:4')
    s.write('theme', 'B5:8 D6:4 F#6:4 | E6:6 D6:2 Bb5:8 | A5:8 C#6:4 E6:4 | D#6:6 C#6:2 B5:8 '
                     '| G5:4 B5:4 E6:6 D6:2 | C6:4 G5:4 E5:4 G5:4 | A5:8 D6:8 | C#6:4 B5:2 A5:4 ' + PICKUP, bar=8)
    score.pick(s, 'guitare', shape=slow, bars=range(8))
    score.pick(s, 'guitare', shape=eight, bars=range(8, 16))
    broken(s, 'piano', ['r', '5', '7', 'T', None, '7', '5', None], bars=range(8, 16))
    bassline(s, 'basse_douce', [(0, 'r')], {3: 'F#'}, bars=range(8))
    bassline(s, 'basse_douce', [(0, 'r'), (8, 'f')], bars=range(8, 16))
    # the fountain: sweeps of harp where the piano breathes, drops of chime
    sweep(s, 'harpe', 3, 8, 6)
    sweep(s, 'harpe', 11, 10, 6)
    score.stars(s, 'etoiles', range(16), rng, [2, 6, 10, 14], chance=0.6)
    return s


def section_a2(rng):
    s = Section(PIECE, 'a2', THEME_CHORDS.rsplit(' ', 1)[0] + ' Bb,C')
    s.write('theme', ' | '.join(THEME[:15] + ['D6:6 Bb5:2 E6:6 C6:2']))
    # second voice under the tune, except the low pickup (it would fall under the harp)
    s.tracks['_tune'] = {x: ns for x, ns in s.tracks['theme'].items() if min(ns) >= midi('C#5')}
    PIECE.tracks['_tune'] = TRACKS['theme']
    harmony_below(s, 'tierce', '_tune')
    del s.tracks['_tune'], PIECE.tracks['_tune']
    for step in [x for x in s.tracks['theme'] if x >= 8 * L]:          # bells join for the second phrase
        s.hit('cloches', step, {n + 12 for n in s.tracks['theme'][step]})
    descant(s, 'contrechant', 'A5', range(8), per_bar=2)
    broken(s, 'piano', ['r', '5', 'R', 'T', 'F', 'T', 'R', '5'])
    s.write('reponse', '.:10 F#4:2 A4:2 D5:2', bar=5)
    s.write('guitare_solo', '.:2 E3:2 A3:2 D4:2 C#4:8', bar=7)
    score.pick(s, 'guitare', shape=eight)
    bassline(s, 'basse', [(0, 'r'), (6, 'f'), (8, 'o'), (12, 'f')], SLASH)
    drums(s, 'grosse', (0, 10), range(16))
    drums(s, 'balai', (4, 12), range(16))
    drums(s, 'charley', (2, 6, 10, 14), range(16))
    drums(s, 'balai', (13, 14, 15), [7, 15])
    score.stars(s, 'etoiles', (3, 11, 13), rng, [8, 10, 12], chance=1)
    return s


def coda(rng):
    s = Section(PIECE, 'coda', 'D Gmaj7 D Gm6 Dadd9 Gmaj7 Em7 A7')
    # the top of the piece, then the tune falls back to the street
    s.write('theme', 'F#6:8 D6:4 A5:4 | B5:8 F#5:8 | A5:8 F#5:4 D5:4 | E5:8 .:8')
    copy(s, 'theme', 'cloches', transpose=12)
    run(s, 'cascade', 0, 8, 'F#7', PENTA_DOWN, 8, down=True)
    broken(s, 'piano', ['r', '5', '7', 'T', None, '7', '5', None], bars=range(4))
    s.write('riff', RIFF, bar=4)
    s.write('riff', 'E4:2 G4:2 B4:2 D5:2 E5:4 B4:4 | A3:2 E4:2 G4:2 C#5:2 E5:8', bar=6)
    bassline(s, 'basse', [(0, 'r'), (8, 'f')], {2: 'F#'}, bars=range(4))
    bassline(s, 'basse_douce', [(0, 'r')], bars=range(4, 8))
    score.strum(s, 'guitare', pattern=(4, 12), low=50, bars=range(4))
    drums(s, 'balai', (4, 12), range(2))
    sweep(s, 'harpe', 3, 10, 6)
    score.stars(s, 'etoiles', (5, 7), rng, [8, 12], chance=1)
    return s


def strong(sec, step):
    return (0, 8)


def main():
    rng = random.Random(2026_11_15)
    sections = [intro(rng), section_a(rng), section_b(rng), section_c(rng), section_a2(rng), coda(rng)]
    for sec in sections:
        give_way(sec)
    problems = score.check(sections, strong, quiet={'etoiles', 'cascade'})
    path = score.write_song(score.songs_folder(sys.argv, __file__), PIECE, sections, [
        'Thème de promenade de Resaac (grande ville moderne haussmannienne), façon ville de RPG japonais : ré majeur',
        'à 4/4, 112 à la noire. Arrivée, avenues (thème), passants, grande place, grands boulevards, soir.',
        'Tout le morceau boucle.',
    ], 'gen_resaac2.py', 'intro')
    score.summary(path, PIECE, sections, 'intro', problems)


if __name__ == '__main__':
    main()

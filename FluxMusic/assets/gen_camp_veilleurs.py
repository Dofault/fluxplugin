#!/usr/bin/env python3
"""Generates "Le Camp des Veilleurs", the music of the rpg lobby (world flux_rpg, the camp of the Veilleurs who guard
the Breaches, see docs/lore/histoire.md).

Unlike gen_songs.py (one template for every boss song), this piece is written section by section on a shared step
grid (engine in score.py, shared with gen_resaac.py), so tracks can be derived from each other:

- the melody's echo is the melody itself, a dotted eighth later and much softer (a delay effect);
- the harmony under the melody and the high descant are computed from the chords (nearest chord tone, smooth
  voice leading);
- the camp fire crackles (pitched hi-hat ticks and rare wood pops, seeded so the file never changes by itself);
- the camp bell rings and a far bell answers it;
- volume changes (fade in, drum build) are made by moving notes between copies of a track at other volumes.

Form, 6/8 at 62 (a bar lasts about 1.9 s), the intro plays once, then the loop (about 2 min 12 s):

    intro  Braises             embers, wind and the camp bell; a horn call from far away
    a      Le Feu de camp      the Veilleurs' theme on the flute, guitar picked like around a fire (D dorian)
    b      La Ronde des gardes a dancing tune on the banjo, flute descant, light drums (F major)
    c      Les Brèches         the theme turned upside down, Neapolitan Eb over a D pedal, a heartbeat; then a
                               3/4 hemiola build (three beats across the 6/8 bar)
    d      Le Serment          the theme in D major, flute + bells an octave above + harp in thirds, march drums;
                               halfway it rises a whole tone to E
    e      Retour au calme     E major falls to C (chromatic mediant), the fire again, back to D dorian

A checker prints every melody note that is not in its chord on a strong beat, and every minor second / major
seventh struck at the same time between two pitched tracks, before writing anything.

    python3 gen_camp_veilleurs.py [output folder]      (default: ../src/main/resources/songs)

Never edit songs/camp_veilleurs.yml by hand: change this script and run it again.
"""
import random
import sys

import score
from score import Piece, Section, copy, descant, drums, harmony_below

# name: (instrument, volume). Order = order in the file.
TRACKS = {
    'theme':        ('flute', 0.72),
    'theme_doux':   ('flute', 0.45),
    'descant':      ('flute', 0.28),
    'ronde':        ('banjo', 0.62),
    'breche':       ('iron_xylophone', 0.55),
    'cloches':      ('bell', 0.26),          # the theme doubled an octave above in the oath
    'harmonie':     ('harp', 0.38),
    'echo':         ('harp', 0.17),
    'arpege':       ('pling', 0.2),
    'arpege_doux':  ('harp', 0.22),
    'lueur':        ('bit', 0.09),           # shimmer of the breaches
    'etoiles':      ('chime', 0.13),
    'cloche':       ('bell', 0.3),           # the camp bell
    'cloche_loin':  ('bell', 0.1),           # the far bell answering it
    'guitare':      ('guitar', 0.42),
    'basse':        ('bass', 0.75),
    'basse_douce':  ('bass', 0.5),
    'vent':         ('didgeridoo', 0.28),
    'braises':      ('hat', 0.07),           # fire crackle
    'tison':        ('basedrum', 0.1),       # wood pop in the fire
    'coeur':        ('basedrum', 0.36),      # heartbeat of the breach
    'grosse':       ('basedrum', 0.58),
    'caisse':       ('snare', 0.3),
    'caisse_douce': ('snare', 0.12),
    'roulement_1':  ('snare', 0.12),
    'roulement_2':  ('snare', 0.22),
    'roulement_3':  ('snare', 0.36),
    'charley':      ('hat', 0.11),
}
PIECE = Piece('camp_veilleurs', 'Le Camp des Veilleurs', bpm=62, steps_per_beat=6, beats_per_bar=2,
              tracks=TRACKS, melodic={'theme', 'theme_doux', 'ronde', 'breche'})
L = PIECE.L
SPELLING_E = score.SPELLING[:3] + ['D#'] + score.SPELLING[4:]     # the oath in E has a D#


# ------------------------------------------------------------------ patterns of a 6/8 bar (12 sixteenths)

BASS = {
    'long': [(0, 'r')],
    'two': [(0, 'r'), (6, 'f')],
    'rock': [(0, 'r'), (4, 'f'), (6, 'o'), (10, 'f')],
    'gallop': [(0, 'r'), (2, 'r'), (3, 'r'), (4, 'r'), (6, 'r'), (8, 'r'), (9, 'r'), (10, 'f')],
    'hemiola': [(0, 'r'), (4, 'r'), (8, 'f')],
}
STAR_STEPS = [0, 3, 6, 9, 2, 8]


def bass(sec, track, style, bars=None):
    score.bass(sec, track, BASS[style], bars)


def pick(sec, track, bars=None, slow=False):
    """Fingerpicked guitar, like around a fire (slow: root, octave, octave)."""
    score.pick(sec, track, bars, (lambda r, iv: [r, r + 12, r + 12]) if slow else None)


def strum(sec, track):
    score.strum(sec, track)


def pedal(sec, track, note, bars):
    score.pedal(sec, track, note, bars, every=6)


def drone(sec, track, bars):
    score.drone(sec, track, bars, steps=(0, 6))


def arpeggio(sec, track, bars, hemiola=False):
    score.arpeggio(sec, track, bars, short=hemiola)


def shimmer(sec, track, bars):
    score.shimmer(sec, track, bars)


def stars(sec, track, bars, rng, chance=0.6):
    """A few chime notes from the chord: stars over the camp."""
    score.stars(sec, track, bars, rng, STAR_STEPS, chance)


def bell(sec, track, bar, step, notes):
    score.ring(sec, track, bar, step, notes)


def fire(sec, bars, rng, density=3.0):
    """Crackle of the camp fire: pitched hat ticks, sometimes in pairs, and rare wood pops."""
    for b in bars:
        count = max(0, round(rng.gauss(density, 1)))
        for _ in range(count):
            s = rng.randrange(sec.L)
            sec.hit('braises', b * sec.L + s, {rng.randrange(60, 77)})
            if rng.random() < 0.3:
                sec.hit('braises', b * sec.L + s + 1, {rng.randrange(62, 78)})
        if rng.random() < 0.18:
            sec.hit('tison', b * sec.L + rng.randrange(sec.L), {rng.randrange(66, 76)})


# ------------------------------------------------------------------ the piece

# The Veilleurs' theme (D dorian), 16 bars of 6/8. Bar 5 is a Dm7, bar 15 an A7.
THEME = ('D5:4 E5:2 F5:4 A5:2 | G5:6 E5:4 C5:2 | D5:4 F5:2 Bb5:4 A5:2 | G5:6 E5:2 F5:2 G5:2 '
         '| A5:4 D6:2 C6:4 A5:2 | C6:6 A5:4 F5:2 | B5:4 A5:2 G5:4 D5:2 | E5:4 C#5:2 A4:6 '
         '| D5:4 E5:2 F5:4 A5:2 | G5:4 C6:2 E6:4 D6:2 | D6:6 Bb5:4 F5:2 | A5:4 G5:2 F5:4 A5:2 '
         '| G5:4 Bb5:2 D6:4 C6:2 | A5:6 F5:4 D5:2 | E5:4 G5:2 E5:4 C#5:2 | D5:12')


def intro(rng):
    s = Section(PIECE, 'intro', 'Dm Dm Bb Bb Gm7 Gm A A')
    drone(s, 'vent', range(8))
    fire(s, range(8), rng, density=3.5)
    # the camp bell, answered from far away; it follows the chords like a carillon
    for b, notes in ((0, 'A5+D6'), (2, 'Bb5+D6'), (4, 'G5+D6'), (6, 'A5+E6')):
        bell(s, 'cloche', b, 0, notes)
        bell(s, 'cloche_loin', b, 3, notes)
    pick(s, 'arpege_doux', range(2, 8), slow=False)
    bass(s, 'basse_douce', 'long', range(4, 8))
    # a horn call, far away: the head of the theme
    s.write('theme_doux', 'D5:4 E5:2 F5:6 | D5:12 | E5:4 F5:2 E5:6 | C#5:12', bar=4)
    copy(s, 'theme_doux', 'echo', delay=3)
    return s


def section_a(rng):
    s = Section(PIECE, 'a', 'Dm C Bb C Dm7 F G A Dm C Bb F Gm Dm A7 Dm')
    s.write('theme', THEME)
    copy(s, 'theme', 'echo', delay=3)
    pick(s, 'guitare')
    bass(s, 'basse', 'two', range(8))
    bass(s, 'basse', 'rock', range(8, 16))
    fire(s, range(16), rng, density=1.5)
    stars(s, 'etoiles', range(8, 16), rng)
    drums(s, 'coeur', (0, 6), range(8, 16))
    return s


def section_b(rng):
    s = Section(PIECE, 'b', 'F C Dm Bb F C Bb C Dm Am Bb F Gm Dm E7 A')
    s.write('ronde', 'F5:2 A5:2 C6:2 A5:4 F5:2 | G5:2 E5:2 C5:2 E5:4 G5:2 | A5:2 F5:2 D5:2 F5:2 A5:2 D6:2 '
                     '| D6:4 C6:2 Bb5:6 | A5:2 C6:2 F6:2 C6:4 A5:2 | E6:4 D6:2 C6:4 G5:2 | D6:4 C6:2 Bb5:4 F5:2 '
                     '| E5:2 G5:2 C6:2 E6:6 | D6:4 A5:2 F5:4 A5:2 | C6:4 A5:2 E5:4 A5:2 '
                     '| Bb5:2 A5:2 Bb5:2 D6:4 Bb5:2 | C6:6 A5:6 | G5:2 Bb5:2 D6:2 Bb5:4 G5:2 '
                     '| F5:2 A5:2 D6:2 A5:4 F5:2 | G#5:2 B5:2 E6:2 D6:4 B5:2 | C#6:6 A5:4 E5:2')
    descant(s, 'descant', 'C6', range(8), per_bar=1)
    descant(s, 'descant', 'A5', range(8, 16), per_bar=2)
    strum(s, 'guitare')
    bass(s, 'basse', 'rock')
    arpeggio(s, 'arpege', range(8, 16))
    drums(s, 'grosse', (0, 6), range(16))
    drums(s, 'caisse_douce', (6,), range(16))
    drums(s, 'charley', (3, 9), range(8))
    drums(s, 'charley', (2, 3, 5, 8, 9, 11), range(8, 16))
    return s


def section_c(rng):
    s = Section(PIECE, 'c', 'Dm Eb Dm Eb Bb Gm Eb A Bb C Asus A')
    # the theme upside down and broken, then its head, as heard from inside a breach
    s.write('breche', 'A4:4 D5:2 F5:6 | G5:4 F5:2 Eb5:6 | D5:4 E5:2 F5:4 A5:2 | Bb5:6 G5:6 '
                      '| F5:4 D5:2 Bb4:6 | D5:4 Bb4:2 G4:6 | G4:4 Bb4:2 Eb5:6 | C#5:6 E5:6 '
                      '| D5:4 F5:4 Bb5:4 | E5:4 G5:4 C6:4 | D5:4 E5:4 A5:4 | C#6:4 E6:4 A5:4')
    copy(s, 'breche', 'echo', delay=3)
    pedal(s, 'basse', 'D2', range(4))                     # D pedal under the Neapolitan Eb
    bass(s, 'basse', 'two', range(4, 8))
    bass(s, 'basse', 'hemiola', range(8, 12))
    pedal(s, 'vent', 'D2', range(4))
    drone(s, 'vent', range(4, 8))
    shimmer(s, 'lueur', range(8))
    stars(s, 'etoiles', range(8), rng, chance=0.8)
    drums(s, 'coeur', (0, 2), range(8))                  # boom-boom
    # hemiola build: three beats across the bar, louder and louder, then a roll
    arpeggio(s, 'arpege', range(8, 12), hemiola=True)
    drums(s, 'grosse', (0, 4, 8), range(8, 12))
    drums(s, 'roulement_1', (0, 4, 8), [8])
    drums(s, 'roulement_2', (0, 4, 8), [9])
    drums(s, 'roulement_2', (0, 2, 4, 6, 8, 10), [10])
    drums(s, 'roulement_2', range(0, 6), [11])
    drums(s, 'roulement_3', range(6, 12), [11])
    return s


def section_d(rng):
    s = Section(PIECE, 'd', 'D C Bb C D G A B E D C D E A B7 E', SPELLING_E)
    s.write('theme', 'D5:4 E5:2 F#5:4 A5:2 | G5:6 E5:4 C5:2 | D5:4 F5:2 Bb5:4 F5:2 | G5:4 E5:2 G5:4 C6:2 '
                     '| D6:4 A5:2 F#5:4 A5:2 | B5:4 A5:2 G5:4 B5:2 | C#6:4 B5:2 A5:4 E5:2 | F#5:4 B5:2 D#6:6 '
                     '| E5:4 F#5:2 G#5:4 B5:2 | A5:6 F#5:4 D5:2 | E5:4 G5:2 C6:4 G5:2 | A5:4 F#5:2 A5:2 B5:2 D6:2 '
                     '| E6:4 B5:2 G#5:4 B5:2 | C#6:4 B5:2 A5:4 C#6:2 | D#6:4 C#6:2 B5:4 A5:2 | G#5:4 B5:2 E6:6')
    copy(s, 'theme', 'cloches', transpose=12)
    harmony_below(s, 'harmonie', 'theme')
    bell(s, 'cloche', 0, 0, 'A5+D6')
    bell(s, 'cloche', 8, 0, 'B5+E6')                    # the rise to E
    strum(s, 'guitare')
    bass(s, 'basse', 'gallop')
    arpeggio(s, 'arpege', range(16))
    drums(s, 'grosse', (0, 6), range(16))
    drums(s, 'caisse', (6,), [b for b in range(16) if b not in (7, 15)])
    drums(s, 'caisse_douce', (9, 10, 11), [b for b in range(16) if b not in (7, 15)])
    drums(s, 'charley', (0, 2, 4, 6, 8, 10), range(16))
    for b in (7, 15):                                     # fills into the rise and into the calm
        drums(s, 'roulement_2', (0, 2, 4, 6), [b])
        drums(s, 'roulement_3', (7, 8, 9, 10, 11), [b])
    return s


def section_e(rng):
    s = Section(PIECE, 'e', 'C Am Bb A Dm C Bb A')
    s.write('theme_doux', 'E5:6 G5:6 | A5:12 | F5:6 D5:6 | E5:6 C#5:6 | D5:4 E5:2 F5:4 A5:2 | G5:6 E5:6 | D5:12 '
                          '| C#5:6 E5:6')
    copy(s, 'theme_doux', 'echo', delay=3)
    bell(s, 'cloche', 0, 0, 'G5+C6')
    bell(s, 'cloche_loin', 0, 3, 'G5+C6')
    pick(s, 'guitare', slow=True)
    bass(s, 'basse_douce', 'long')
    fire(s, range(8), rng, density=2.5)
    stars(s, 'etoiles', range(8), rng, chance=0.5)
    return s


# ------------------------------------------------------------------ checks and output

def strong(sec, step):
    # the build of the breaches is in 3/4 (hemiola): three strong beats across the bar
    return (0, 4, 8) if sec.name == 'c' and step // L >= 8 else (0, 6)


def allowed(sec, step, t1, t2):
    # Eb over the D pedal at the opening of the breaches: wanted
    return sec.name == 'c' and step // L < 4 and bool({t1, t2} & {'basse', 'vent'})


def main():
    rng = random.Random(2026_11_11)
    sections = [intro(rng), section_a(rng), section_b(rng), section_c(rng), section_d(rng), section_e(rng)]
    problems = score.check(sections, strong, quiet={'echo', 'etoiles', 'lueur'}, allowed=allowed)
    path = score.write_song(score.songs_folder(sys.argv, __file__), PIECE, sections, [
        'Lobby RPG (camp des Veilleurs, monde flux_rpg) : ré dorien à 6/8, thème des Veilleurs, ronde des gardes,',
        'les Brèches (thème renversé, napolitaine sur pédale de ré, hémiole), le serment (ré majeur puis mi), retour au calme.',
    ], 'gen_camp_veilleurs.py', 'a')
    score.summary(path, PIECE, sections, 'a', problems)


if __name__ == '__main__':
    main()

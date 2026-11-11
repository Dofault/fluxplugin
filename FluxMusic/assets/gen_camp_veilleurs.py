#!/usr/bin/env python3
"""Generates "Le Camp des Veilleurs", the music of the rpg lobby (world flux_rpg, the camp of the Veilleurs who guard
the Breaches, see docs/lore/histoire.md).

Unlike gen_songs.py (one template for every boss song), this piece is written section by section on a shared step
grid, so tracks can be derived from each other:

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
import os
import random
import re
import sys

STEPS_PER_BEAT = 6          # a step is a sixteenth note of 6/8
BEATS_PER_BAR = 2
L = STEPS_PER_BEAT * BEATS_PER_BAR
BPM = 62                    # dotted quarter notes per minute

NAMES = {'C': 0, 'D': 2, 'E': 4, 'F': 5, 'G': 7, 'A': 9, 'B': 11}
RANGE = {
    'harp': (54, 78), 'pling': (54, 78), 'bit': (54, 78), 'banjo': (54, 78), 'iron_xylophone': (54, 78),
    'bass': (30, 54), 'didgeridoo': (30, 54), 'guitar': (42, 66), 'flute': (66, 90), 'cow_bell': (66, 90),
    'bell': (78, 102), 'chime': (78, 102), 'xylophone': (78, 102),
    'basedrum': (54, 78), 'snare': (54, 78), 'hat': (54, 78),
}
DRUMS = {'basedrum', 'snare', 'hat'}
# One spelling for the whole piece (D dorian, F, D and E major): C#, F#, G#, D# come as sharps, Bb and Eb as flats.
SPELLING = ['C', 'C#', 'D', 'Eb', 'E', 'F', 'F#', 'G', 'G#', 'A', 'Bb', 'B']
SPELLING_E = SPELLING[:3] + ['D#'] + SPELLING[4:]     # the oath in E has a D#

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
# Tracks whose notes are checked against the chords on strong beats.
MELODIC = {'theme', 'theme_doux', 'ronde', 'breche'}


# ------------------------------------------------------------------ notes and chords

def midi(note):
    m = re.match(r'^([A-G])([#b]?)(-?\d)$', note)
    if not m:
        raise ValueError('bad note ' + note)
    return (int(m.group(3)) + 1) * 12 + NAMES[m.group(1)] + {'#': 1, 'b': -1, '': 0}[m.group(2)]


def chord(symbol):
    """'F#m7' -> (root pitch class, intervals)."""
    m = re.match(r'^([A-G])([#b]?)(.*)$', symbol)
    root = (NAMES[m.group(1)] + {'#': 1, 'b': -1, '': 0}[m.group(2)]) % 12
    iv = {'': [0, 4, 7], 'm': [0, 3, 7], '7': [0, 4, 7, 10], 'm7': [0, 3, 7, 10], 'maj7': [0, 4, 7, 11],
          'sus': [0, 5, 7], 'sus2': [0, 2, 7], 'dim': [0, 3, 6], 'add9': [0, 4, 7, 14]}[m.group(3)]
    return root, iv


def tones(symbol):
    root, iv = chord(symbol)
    return {(root + x) % 12 for x in iv}


def lowest(pc, low):
    return low + (pc - low) % 12


# ------------------------------------------------------------------ sections

class Section:
    def __init__(self, name, chords, spelling=None):
        self.name = name
        self.chords = chords.split()
        self.bars = len(self.chords)
        self.spelling = spelling or SPELLING
        self.tracks = {}            # track -> {step: set(midi)}

    @property
    def length(self):
        return self.bars * L

    def chord_at(self, step):
        return self.chords[min(step // L, self.bars - 1)]

    def hit(self, track, step, notes):
        if track not in TRACKS:
            raise ValueError('unknown track ' + track)
        if 0 <= step < self.length:
            self.tracks.setdefault(track, {}).setdefault(step, set()).update(notes)

    def write(self, track, line, bar=0, delay=0, transpose=0, octave_fit=True):
        """Places a written line ("D5:4 E5:2 | ...") from a bar, possibly delayed and transposed.
        The whole line moves by octaves into the instrument's range."""
        events = parse(line, f'{self.name}/{track}')
        if octave_fit:
            events = fit(events, TRACKS[track][0], f'{self.name}/{track}')
        for step, notes in events:
            self.hit(track, bar * L + step + delay, {n + transpose for n in notes})
        return events

    def render(self, track):
        hits = self.tracks[track]
        bars = []
        for b in range(self.bars):
            steps = sorted(s for s in hits if b * L <= s < (b + 1) * L)
            out = []
            cursor = b * L
            if not steps or steps[0] > cursor:
                first = steps[0] if steps else (b + 1) * L
                out.append(token(None, first - cursor))
                cursor = first
            for k, s in enumerate(steps):
                end = steps[k + 1] if k + 1 < len(steps) else (b + 1) * L
                out.append(token(hits[s], end - s, self.spelling, drum=TRACKS[track][0] in DRUMS))
            bars.append(' '.join(out))
        return ' | '.join(bars)


def parse(line, where):
    events, bar_start, step = [], 0, 0
    for bar_no, bar in enumerate(line.split('|'), 1):
        for t in bar.split():
            body, _, d = t.partition(':')
            d = int(d) if d else 1
            if body not in ('.', '-'):
                events.append((step, [midi(n) for n in body.split('+')]))
            step += d
        if step - bar_start != L:
            raise ValueError(f'{where}: bar {bar_no} lasts {step - bar_start} steps instead of {L}')
        bar_start = step
    return events


def fit(events, instrument, where):
    lo, hi = RANGE[instrument]
    notes = [n for _, ns in events for n in ns]
    if not notes or instrument in DRUMS:
        return events
    for k in (0, -1, 1, -2, 2, -3, 3):
        if min(notes) + 12 * k >= lo and max(notes) + 12 * k <= hi:
            return [(s, [n + 12 * k for n in ns]) for s, ns in events]
    raise ValueError(f'{where}: {min(notes)}..{max(notes)} does not fit {instrument} {lo}..{hi}')


def token(notes, duration, spelling=SPELLING, drum=False):
    if not notes:
        body = '.'
    elif drum and notes == {None}:
        body = 'x'
    else:
        body = '+'.join(spelling[n % 12] + str(n // 12 - 1) for n in sorted(notes))
    return body if duration == 1 else f'{body}:{duration}'


# ------------------------------------------------------------------ accompaniment, derived from the chords

def pick(sec, track, bars=None, slow=False):
    """Fingerpicked guitar: root, fifth, octave, tenth, octave, fifth (eighth notes), like around a fire."""
    lo, hi = RANGE[TRACKS[track][0]]
    for b in bars if bars is not None else range(sec.bars):
        root, iv = chord(sec.chords[b])
        r = lowest(root, lo)
        shape = [r, r + 7, r + 12, r + 12 + iv[1], r + 12, r + 7]
        shape = [n - 12 if n > hi else n for n in shape]
        if slow:
            shape = shape[::2]
        step = L // len(shape)
        for k, n in enumerate(shape):
            sec.hit(track, b * L + k * step, {n})


def strum(sec, track, bars=None, pattern=(0, 4, 6, 10)):
    """Strummed guitar, a close triad in the middle of the guitar."""
    for b in bars if bars is not None else range(sec.bars):
        root, iv = chord(sec.chords[b])
        r = lowest(root, 50)
        triad = sorted(n - 12 if n > 66 else n for n in (r + x for x in iv[:3]))
        for s in pattern:
            sec.hit(track, b * L + s, set(triad))


def bass(sec, track, style, bars=None):
    lo, _ = RANGE[TRACKS[track][0]]
    for b in bars if bars is not None else range(sec.bars):
        root, iv = chord(sec.chords[b])
        r = lowest(root, lo + 2)
        f, o = r + 7, r + 12
        if o > 54:
            o -= 12
        pat = {
            'long': [(0, r)],
            'two': [(0, r), (6, f)],
            'rock': [(0, r), (4, f), (6, o), (10, f)],
            'gallop': [(0, r), (2, r), (3, r), (4, r), (6, r), (8, r), (9, r), (10, f)],
            'hemiola': [(0, r), (4, r), (8, f)],
        }[style]
        for s, n in pat:
            sec.hit(track, b * L + s, {n})


def pedal(sec, track, note, bars, every=6):
    for b in bars:
        for s in range(0, L, every):
            sec.hit(track, b * L + s, {midi(note)})


def arpeggio(sec, track, bars, every=1, hemiola=False):
    """Chord tones up and down over two octaves (sixteenths)."""
    lo, hi = RANGE[TRACKS[track][0]]
    for b in bars:
        root, iv = chord(sec.chords[b])
        n = lowest(root, lo + 2)
        up = []
        while len(up) < 6 and n <= hi:
            for x in iv[:3]:
                if n + x <= hi and len(up) < 6:
                    up.append(n + x)
            n += 12
        cycle = up + up[-2:0:-1] if not hemiola else up[:4]
        for k, s in enumerate(range(0, L, every)):
            sec.hit(track, b * L + s, {cycle[k % len(cycle)]})


def shimmer(sec, track, bars):
    """Two chord tones alternating at every step: the trembling light of a breach."""
    lo, _ = RANGE[TRACKS[track][0]]
    for b in bars:
        root, iv = chord(sec.chords[b])
        a, c = lowest((root + iv[1]) % 12, lo + 12), lowest((root + iv[2]) % 12, lo + 12)
        for s in range(L):
            sec.hit(track, b * L + s, {a if s % 2 == 0 else c})


def drone(sec, track, bars):
    lo, _ = RANGE[TRACKS[track][0]]
    for b in bars:
        root, _ = chord(sec.chords[b])
        for s in (0, 6):
            sec.hit(track, b * L + s, {lowest(root, lo + 6)})


def drums(sec, track, steps, bars, note=None):
    for b in bars:
        for s in steps:
            sec.hit(track, b * L + s, {note})


def stars(sec, track, bars, rng, chance=0.6):
    """A few chime notes from the chord, at random steps: stars over the camp."""
    lo, hi = RANGE[TRACKS[track][0]]
    for b in bars:
        if rng.random() > chance:
            continue
        pcs = sorted(tones(sec.chords[b]))
        s = rng.choice([0, 3, 6, 9, 2, 8])
        n = lowest(rng.choice(pcs), lo + rng.choice([0, 7, 12]))
        if n > hi:
            n -= 12
        sec.hit(track, b * L + s, {n})
        if rng.random() < 0.35:     # a second twinkle a moment later
            sec.hit(track, b * L + s + 2, {n + 12 if n + 12 <= hi else n - 12})


def fire(sec, bars, rng, density=3.0):
    """Crackle of the camp fire: pitched hat ticks, sometimes in pairs, and rare wood pops."""
    for b in bars:
        count = max(0, round(rng.gauss(density, 1)))
        for _ in range(count):
            s = rng.randrange(L)
            sec.hit('braises', b * L + s, {rng.randrange(60, 77)})
            if rng.random() < 0.3:
                sec.hit('braises', b * L + s + 1, {rng.randrange(62, 78)})
        if rng.random() < 0.18:
            sec.hit('tison', b * L + rng.randrange(L), {rng.randrange(66, 76)})


def bell(sec, track, bar, step, notes):
    sec.hit(track, bar * L + step, {midi(n) for n in notes.split('+')})


def harmony_below(sec, track, source):
    """For each melody note: the nearest chord tone at least a minor third below it (the second voice)."""
    for step, notes in list(sec.tracks[source].items()):
        top = max(notes)
        pcs = tones(sec.chord_at(step))
        cand = next((top - d for d in range(3, 10) if (top - d) % 12 in pcs), None)
        if cand is not None:
            sec.hit(track, step, {cand})
    lo, hi = RANGE[TRACKS[track][0]]
    hits = sec.tracks[track]
    notes = [n for ns in hits.values() for n in ns]
    for k in (0, -1, -2, 1):
        if min(notes) + 12 * k >= lo and max(notes) + 12 * k <= hi:
            sec.tracks[track] = {s: {n + 12 * k for n in ns} for s, ns in hits.items()}
            return
    raise ValueError(f'{sec.name}/{track}: harmony does not fit')


def descant(sec, track, start, bars, per_bar=1):
    """A high voice moving to the nearest chord tone (common tone kept): long notes above the tune."""
    lo, hi = RANGE[TRACKS[track][0]]
    cur = midi(start)
    for b in bars:
        for half in range(per_bar):
            pcs = tones(sec.chords[b])
            cands = [n for n in range(cur - 5, cur + 6) if n % 12 in pcs and lo <= n <= hi]
            cur = min(cands, key=lambda n: (abs(n - cur), n))
            sec.hit(track, b * L + half * (L // per_bar), {cur})


def copy(sec, source, track, delay=0, transpose=0):
    lo, hi = RANGE[TRACKS[track][0]]
    hits = sec.tracks[source]
    notes = [n for ns in hits.values() for n in ns]
    k = next(k for k in (0, -1, 1, -2, 2) if min(notes) + transpose + 12 * k >= lo
             and max(notes) + transpose + 12 * k <= hi)
    for s, ns in hits.items():
        sec.hit(track, s + delay, {n + transpose + 12 * k for n in ns})


# ------------------------------------------------------------------ the piece

# The Veilleurs' theme (D dorian), 16 bars of 6/8. Bar 5 is a Dm7, bar 15 an A7.
THEME = ('D5:4 E5:2 F5:4 A5:2 | G5:6 E5:4 C5:2 | D5:4 F5:2 Bb5:4 A5:2 | G5:6 E5:2 F5:2 G5:2 '
         '| A5:4 D6:2 C6:4 A5:2 | C6:6 A5:4 F5:2 | B5:4 A5:2 G5:4 D5:2 | E5:4 C#5:2 A4:6 '
         '| D5:4 E5:2 F5:4 A5:2 | G5:4 C6:2 E6:4 D6:2 | D6:6 Bb5:4 F5:2 | A5:4 G5:2 F5:4 A5:2 '
         '| G5:4 Bb5:2 D6:4 C6:2 | A5:6 F5:4 D5:2 | E5:4 G5:2 E5:4 C#5:2 | D5:12')


def intro(rng):
    s = Section('intro', 'Dm Dm Bb Bb Gm7 Gm A A')
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
    s = Section('a', 'Dm C Bb C Dm7 F G A Dm C Bb F Gm Dm A7 Dm')
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
    s = Section('b', 'F C Dm Bb F C Bb C Dm Am Bb F Gm Dm E7 A')
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
    s = Section('c', 'Dm Eb Dm Eb Bb Gm Eb A Bb C Asus A')
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
    s = Section('d', 'D C Bb C D G A B E D C D E A B7 E', SPELLING_E)
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
    s = Section('e', 'C Am Bb A Dm C Bb A')
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

def check(sections):
    problems = 0
    for sec in sections:
        pitched = {t: h for t, h in sec.tracks.items() if TRACKS[t][0] not in DRUMS}
        for t, hits in pitched.items():
            lo, hi = RANGE[TRACKS[t][0]]
            for s, ns in hits.items():
                for n in ns:
                    if not lo <= n <= hi:
                        print(f'  RANGE {sec.name}/{t} bar {s // L + 1}: {n} outside {lo}..{hi}')
                        problems += 1
            if t in MELODIC:
                for s, ns in hits.items():
                    strong = (0, 4, 8) if sec.name == 'c' and s // L >= 8 else (0, 6)
                    if s % L in strong and not all(n % 12 in tones(sec.chord_at(s)) for n in ns):
                        print(f'  CHORD {sec.name}/{t} bar {s // L + 1} step {s % L}: not in {sec.chord_at(s)}')
                        problems += 1
        steps = {s for h in pitched.values() for s in h}
        for s in sorted(steps):
            sounding = [(t, n) for t, h in pitched.items() for n in h.get(s, ())
                        if t not in ('echo', 'etoiles', 'lueur')]
            for i, (t1, a) in enumerate(sounding):
                for t2, b in sounding[i + 1:]:
                    pedal_point = sec.name == 'c' and s // L < 4 and {t1, t2} & {'basse', 'vent'}
                    if t1 != t2 and abs(a - b) % 12 in (1, 11) and not pedal_point:   # Eb over the D pedal: wanted
                        print(f'  CLASH {sec.name} bar {s // L + 1} step {s % L}: {t1} {a} / {t2} {b}')
                        problems += 1
    return problems


def output(sections, folder):
    q = lambda s: '"' + s + '"'
    used = {t for sec in sections for t in sec.tracks}
    lines = ['# Lobby RPG (camp des Veilleurs, monde flux_rpg) : ré dorien à 6/8, thème des Veilleurs, ronde des gardes,',
             '# les Brèches (thème renversé, napolitaine sur pédale de ré, hémiole), le serment (ré majeur puis mi), retour au calme.',
             '# Généré par FluxMusic/assets/gen_camp_veilleurs.py : modifier le script, pas ce fichier.',
             'title: "Le Camp des Veilleurs"', 'author: "Influencraft"', f'bpm: {BPM}',
             f'steps-per-beat: {STEPS_PER_BEAT}', f'beats-per-bar: {BEATS_PER_BAR}', 'loop: true', 'loop-from: a',
             '', 'tracks:']
    for t, (instrument, volume) in TRACKS.items():
        if t in used:
            lines.append(f'  {t}: {{ instrument: {instrument}, volume: {volume} }}')
    lines += ['', 'sections:']
    for sec in sections:
        lines.append(f'  {sec.name}:')
        for t in TRACKS:
            if t in sec.tracks:
                lines.append(f'    {t}: {q(sec.render(t))}')
        # every track lasts the whole section (the reader warns otherwise)
    lines += ['', 'order: [' + ', '.join(s.name for s in sections) + ']', '']
    path = os.path.join(folder, 'camp_veilleurs.yml')
    with open(path, 'w', encoding='utf-8') as f:
        f.write('\n'.join(lines))
    return path


def main():
    folder = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), '..', 'src', 'main',
                                                                 'resources', 'songs')
    rng = random.Random(2026_11_11)
    sections = [intro(rng), section_a(rng), section_b(rng), section_c(rng), section_d(rng), section_e(rng)]
    problems = check(sections)
    path = output(sections, folder)
    step = 60 / (BPM * STEPS_PER_BEAT)
    loop_bars = sum(s.bars for s in sections[1:])
    print(f'{path}: {sum(s.bars for s in sections)} bars, loop {loop_bars} bars = {loop_bars * L * step:.0f} s, '
          f'{sum(len(ns) for s in sections for h in s.tracks.values() for ns in h.values())} notes, '
          f'{problems} remark(s)')


if __name__ == '__main__':
    main()

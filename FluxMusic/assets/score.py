"""Shared engine of the hand-composed FluxMusic pieces (gen_camp_veilleurs.py, gen_resaac.py).

A piece is written section by section on a grid of steps. Every track of a section is a map step -> notes, so tracks
can be derived from each other (echo, harmony, descant, doubling) before being written as a FluxMusic score
(songs/<id>.yml, read by ScoreReader).

- Piece: tempo, meter, tracks (name -> instrument, volume), the tracks checked against the chords.
- Section: its chords, one per bar, or several separated by commas sharing the bar ("Am7,D7" = two halves).
- Helpers lay accompaniment out from the chords (bass, picking, strumming, arpeggios, drones, stars...).
- check() prints, before anything is written: notes out of range, melody notes outside their chord on a strong
  beat, minor seconds / major sevenths struck together between two tracks (unless both notes are in the chord).

Nothing here knows a given piece: patterns and strong beats come from the piece's script.
"""
import os
import re

NAMES = {'C': 0, 'D': 2, 'E': 4, 'F': 5, 'G': 7, 'A': 9, 'B': 11}
# Range (lowest, highest MIDI) of each note block instrument (two octaves around its F#).
RANGE = {
    'harp': (54, 78), 'pling': (54, 78), 'bit': (54, 78), 'banjo': (54, 78), 'iron_xylophone': (54, 78),
    'bass': (30, 54), 'didgeridoo': (30, 54), 'guitar': (42, 66), 'flute': (66, 90), 'cow_bell': (66, 90),
    'bell': (78, 102), 'chime': (78, 102), 'xylophone': (78, 102),
    'basedrum': (54, 78), 'snare': (54, 78), 'hat': (54, 78),
}
DRUMS = {'basedrum', 'snare', 'hat'}
# Default spelling: C#, F#, G# as sharps, Bb and Eb as flats.
SPELLING = ['C', 'C#', 'D', 'Eb', 'E', 'F', 'F#', 'G', 'G#', 'A', 'Bb', 'B']
QUALITIES = {'': [0, 4, 7], 'm': [0, 3, 7], '7': [0, 4, 7, 10], 'm7': [0, 3, 7, 10], 'maj7': [0, 4, 7, 11],
             'sus': [0, 5, 7], 'sus2': [0, 2, 7], 'dim': [0, 3, 6], 'add9': [0, 4, 7, 14], 'm6': [0, 3, 7, 9],
             'dim7': [0, 3, 6, 9], 'm7b5': [0, 3, 6, 10], '+': [0, 4, 8], '7#5': [0, 4, 8, 10], '6': [0, 4, 7, 9]}


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
    return root, QUALITIES[m.group(3)]


def tones(symbol):
    root, iv = chord(symbol)
    return {(root + x) % 12 for x in iv}


def lowest(pc, low):
    """Lowest MIDI note of pitch class pc at or above low."""
    return low + (pc - low) % 12


# ------------------------------------------------------------------ piece and sections

class Piece:
    def __init__(self, song_id, title, bpm, steps_per_beat, beats_per_bar, tracks, melodic):
        self.id = song_id
        self.title = title
        self.bpm = bpm
        self.steps_per_beat = steps_per_beat
        self.beats_per_bar = beats_per_bar
        self.L = steps_per_beat * beats_per_bar
        self.tracks = tracks          # name -> (instrument, volume), order = order in the file
        self.melodic = melodic        # tracks checked against the chords

    def instrument(self, track):
        if track not in self.tracks:
            raise ValueError('unknown track ' + track)
        return self.tracks[track][0]

    def range(self, track):
        return RANGE[self.instrument(track)]


class Section:
    def __init__(self, piece, name, chords, spelling=None):
        self.piece = piece
        self.name = name
        self.L = piece.L
        self.bar_chords = chords.split()
        self.bars = len(self.bar_chords)
        self.spelling = spelling or SPELLING
        self.slots = []               # (start step, length, chord)
        for b, token in enumerate(self.bar_chords):
            parts = token.split(',')
            size = self.L // len(parts)
            for k, sym in enumerate(parts):
                self.slots.append((b * self.L + k * size, size, sym))
        self.tracks = {}              # track -> {step: set(midi)}

    @property
    def length(self):
        return self.bars * self.L

    def chord_at(self, step):
        for start, size, sym in self.slots:
            if start <= step < start + size:
                return sym
        return self.slots[-1][2]

    def slots_in(self, bars):
        bars = set(bars if bars is not None else range(self.bars))
        return [s for s in self.slots if s[0] // self.L in bars]

    def hit(self, track, step, notes):
        self.piece.instrument(track)
        if 0 <= step < self.length:
            self.tracks.setdefault(track, {}).setdefault(step, set()).update(notes)

    def write(self, track, line, bar=0, delay=0, transpose=0, octave_fit=True):
        """Places a written line ("D5:4 E5:2 | ...") from a bar, possibly delayed and transposed.
        The whole line moves by octaves into the instrument's range."""
        events = parse(line, self.L, f'{self.name}/{track}')
        if octave_fit:
            events = fit(events, self.piece.instrument(track), f'{self.name}/{track}', transpose)
        for step, notes in events:
            self.hit(track, bar * self.L + step + delay, {n + transpose for n in notes})
        return events

    def render(self, track):
        L = self.L
        hits = self.tracks[track]
        drum = self.piece.instrument(track) in DRUMS
        bars = []
        for b in range(self.bars):
            steps = sorted(s for s in hits if b * L <= s < (b + 1) * L)
            out = []
            cursor = b * L
            if not steps or steps[0] > cursor:
                first = steps[0] if steps else (b + 1) * L
                out.append(token(None, first - cursor))
            for k, s in enumerate(steps):
                end = steps[k + 1] if k + 1 < len(steps) else (b + 1) * L
                out.append(token(hits[s], end - s, self.spelling, drum=drum))
            bars.append(' '.join(out))
        return ' | '.join(bars)


def parse(line, L, where):
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


def fit(events, instrument, where, transpose=0):
    lo, hi = RANGE[instrument]
    notes = [n + transpose for _, ns in events for n in ns]
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

def pick(sec, track, bars=None, shape=None):
    """Picked guitar over each chord: shape(root, intervals) gives the notes, spread evenly over the chord.
    Default: root, fifth, octave, tenth, octave, fifth."""
    lo, hi = sec.piece.range(track)
    shape = shape or (lambda r, iv: [r, r + 7, r + 12, r + 12 + iv[1], r + 12, r + 7])
    for start, size, sym in sec.slots_in(bars):
        root, iv = chord(sym)
        notes = [n - 12 if n > hi else n for n in shape(lowest(root, lo), iv)]
        step = size // len(notes)
        for k, n in enumerate(notes):
            sec.hit(track, start + k * step, {n})


def strum(sec, track, bars=None, pattern=(0, 4, 6, 10), low=50):
    """A close triad from `low`, struck at the pattern's steps (from the start of each chord)."""
    _, hi = sec.piece.range(track)
    for start, size, sym in sec.slots_in(bars):
        root, iv = chord(sym)
        r = lowest(root, low)
        triad = sorted(n - 12 if n > hi else n for n in (r + x for x in iv[:3]))
        for s in pattern:
            if s < size:
                sec.hit(track, start + s, set(triad))


def bass(sec, track, pattern, bars=None):
    """pattern: [(step, 'r' root | 'f' fifth | 'o' octave | 't' third)], from the start of each chord."""
    lo, hi = sec.piece.range(track)
    for start, size, sym in sec.slots_in(bars):
        root, iv = chord(sym)
        r = lowest(root, lo + 2)
        o = r + 12 if r + 12 <= hi else r
        notes = {'r': r, 'f': r + iv[2] if len(iv) > 2 else r + 7, 'o': o, 't': r + iv[1]}
        for s, which in pattern:
            if s < size:
                sec.hit(track, start + s, {notes[which]})


def line_bass(sec, track, notes, steps, bars=None):
    """A bass line written by hand: one note per bar (a descending line under the chords), struck at `steps`."""
    for b, n in zip(bars if bars is not None else range(sec.bars), notes.split()):
        for s in steps:
            sec.hit(track, b * sec.L + s, {midi(n)})


def pedal(sec, track, note, bars, every):
    for b in bars:
        for s in range(0, sec.L, every):
            sec.hit(track, b * sec.L + s, {midi(note)})


def arpeggio(sec, track, bars, every=1, short=False):
    """Chord tones up and down over two octaves; short = the first four up only."""
    lo, hi = sec.piece.range(track)
    for start, size, sym in sec.slots_in(bars):
        root, iv = chord(sym)
        n = lowest(root, lo + 2)
        up = []
        while len(up) < 6 and n <= hi:
            for x in iv[:3]:
                if n + x <= hi and len(up) < 6:
                    up.append(n + x)
            n += 12
        cycle = up + up[-2:0:-1] if not short else up[:4]
        for k, s in enumerate(range(0, size, every)):
            sec.hit(track, start + s, {cycle[k % len(cycle)]})


def shimmer(sec, track, bars, every=1):
    """Two chord tones alternating quickly: a trembling light."""
    lo, _ = sec.piece.range(track)
    for start, size, sym in sec.slots_in(bars):
        root, iv = chord(sym)
        a, c = lowest((root + iv[1]) % 12, lo + 12), lowest((root + iv[2]) % 12, lo + 12)
        for k, s in enumerate(range(0, size, every)):
            sec.hit(track, start + s, {a if k % 2 == 0 else c})


def drone(sec, track, bars, steps):
    lo, _ = sec.piece.range(track)
    for start, size, sym in sec.slots_in(bars):
        root, _ = chord(sym)
        for s in steps:
            if s < size:
                sec.hit(track, start + s, {lowest(root, lo + 6)})


def drums(sec, track, steps, bars, note=None):
    for b in bars:
        for s in steps:
            sec.hit(track, b * sec.L + s, {note})


def stars(sec, track, bars, rng, steps, chance=0.6, twinkle=2):
    """A few notes of the chord at random steps, sometimes doubled an octave away a moment later."""
    lo, hi = sec.piece.range(track)
    for b in bars:
        if rng.random() > chance:
            continue
        s = rng.choice(steps)
        pcs = sorted(tones(sec.chord_at(b * sec.L + s)))
        n = lowest(rng.choice(pcs), lo + rng.choice([0, 7, 12]))
        if n > hi:
            n -= 12
        sec.hit(track, b * sec.L + s, {n})
        if rng.random() < 0.35:
            sec.hit(track, b * sec.L + s + twinkle, {n + 12 if n + 12 <= hi else n - 12})


def ring(sec, track, bar, step, notes):
    sec.hit(track, bar * sec.L + step, {midi(n) for n in notes.split('+')})


def run(sec, track, bar, step, start, intervals, count, every=1, down=False):
    """A quick run of `count` notes from `start` repeating the interval pattern (whole tone: [2]). At the edge of
    the instrument it turns back, like a glissando falling back in sparkles."""
    lo, hi = sec.piece.range(track)
    n = midi(start)
    while n > hi:
        n -= 12
    while n < lo:
        n += 12
    sign = -1 if down else 1
    for k in range(count):
        sec.hit(track, bar * sec.L + step + k * every, {n})
        nxt = n + sign * intervals[k % len(intervals)]
        if not lo <= nxt <= hi:
            sign = -sign
            nxt = n + sign * intervals[k % len(intervals)]
        n = nxt


def harmony_below(sec, track, source):
    """For each melody note: the nearest chord tone at least a minor third below it (the second voice)."""
    for step, notes in list(sec.tracks[source].items()):
        top = max(notes)
        pcs = tones(sec.chord_at(step))
        cand = next((top - d for d in range(3, 10) if (top - d) % 12 in pcs), None)
        if cand is not None:
            sec.hit(track, step, {cand})
    lo, hi = sec.piece.range(track)
    hits = sec.tracks[track]
    notes = [n for ns in hits.values() for n in ns]
    for k in (0, -1, -2, 1):
        if min(notes) + 12 * k >= lo and max(notes) + 12 * k <= hi:
            sec.tracks[track] = {s: {n + 12 * k for n in ns} for s, ns in hits.items()}
            return
    raise ValueError(f'{sec.name}/{track}: harmony does not fit')


def descant(sec, track, start, bars, per_bar=1):
    """A high voice moving to the nearest chord tone (common tone kept): long notes above the tune."""
    lo, hi = sec.piece.range(track)
    cur = midi(start)
    for b in bars:
        for half in range(per_bar):
            step = b * sec.L + half * (sec.L // per_bar)
            pcs = tones(sec.chord_at(step))
            cands = [n for n in range(cur - 5, cur + 6) if n % 12 in pcs and lo <= n <= hi]
            cur = min(cands, key=lambda n: (abs(n - cur), n))
            sec.hit(track, step, {cur})


def copy(sec, source, track, delay=0, transpose=0):
    """The source track again on another track (echo, doubling), moved by octaves into its range."""
    lo, hi = sec.piece.range(track)
    hits = sec.tracks[source]
    notes = [n for ns in hits.values() for n in ns]
    k = next(k for k in (0, -1, 1, -2, 2) if min(notes) + transpose + 12 * k >= lo
             and max(notes) + transpose + 12 * k <= hi)
    for s, ns in hits.items():
        sec.hit(track, s + delay, {n + transpose + 12 * k for n in ns})


# ------------------------------------------------------------------ checks and output

def check(sections, strong, quiet=(), allowed=lambda sec, step, t1, t2: False):
    """strong(sec, step) -> steps of the bar that are strong beats; quiet: tracks never checked for clashes."""
    problems = 0
    for sec in sections:
        piece, L = sec.piece, sec.L
        pitched = {t: h for t, h in sec.tracks.items() if piece.instrument(t) not in DRUMS}
        for t, hits in pitched.items():
            lo, hi = piece.range(t)
            for s, ns in hits.items():
                for n in ns:
                    if not lo <= n <= hi:
                        print(f'  RANGE {sec.name}/{t} bar {s // L + 1}: {n} outside {lo}..{hi}')
                        problems += 1
            if t in piece.melodic:
                for s, ns in hits.items():
                    if s % L in strong(sec, s) and not all(n % 12 in tones(sec.chord_at(s)) for n in ns):
                        print(f'  CHORD {sec.name}/{t} bar {s // L + 1} step {s % L}: not in {sec.chord_at(s)}')
                        problems += 1
        for s in sorted({s for h in pitched.values() for s in h}):
            sounding = [(t, n) for t, h in pitched.items() for n in h.get(s, ()) if t not in quiet]
            for i, (t1, a) in enumerate(sounding):
                for t2, b in sounding[i + 1:]:
                    if t1 == t2 or abs(a - b) % 12 not in (1, 11):
                        continue
                    if {a % 12, b % 12} <= tones(sec.chord_at(s)):
                        continue                  # both in the chord: its own colour (major seventh), not a clash
                    if not allowed(sec, s, t1, t2):
                        print(f'  CLASH {sec.name} bar {s // L + 1} step {s % L}: {t1} {a} / {t2} {b}')
                        problems += 1
    return problems


def write_song(folder, piece, sections, comments, script, loop_from):
    q = lambda s: '"' + s + '"'
    used = {t for sec in sections for t in sec.tracks}
    lines = [f'# {c}' for c in comments]
    lines += [f'# Généré par FluxMusic/assets/{script} : modifier le script, pas ce fichier.',
              f'title: "{piece.title}"', 'author: "Influencraft"', f'bpm: {piece.bpm}',
              f'steps-per-beat: {piece.steps_per_beat}', f'beats-per-bar: {piece.beats_per_bar}', 'loop: true',
              f'loop-from: {loop_from}', '', 'tracks:']
    for t, (instrument, volume) in piece.tracks.items():
        if t in used:
            lines.append(f'  {t}: {{ instrument: {instrument}, volume: {volume} }}')
    lines += ['', 'sections:']
    for sec in sections:
        lines.append(f'  {sec.name}:')
        for t in piece.tracks:
            if t in sec.tracks:
                lines.append(f'    {t}: {q(sec.render(t))}')
    lines += ['', 'order: [' + ', '.join(s.name for s in sections) + ']', '']
    path = os.path.join(folder, piece.id + '.yml')
    with open(path, 'w', encoding='utf-8') as f:
        f.write('\n'.join(lines))
    return path


def summary(path, piece, sections, loop_from, problems):
    step = 60 / (piece.bpm * piece.steps_per_beat)
    names = [s.name for s in sections]
    loop_bars = sum(s.bars for s in sections[names.index(loop_from):])
    notes = sum(len(ns) for s in sections for h in s.tracks.values() for ns in h.values())
    print(f'{path}: {sum(s.bars for s in sections)} bars, loop {loop_bars} bars = '
          f'{loop_bars * piece.L * step:.0f} s, {notes} notes, {problems} remark(s)')


def songs_folder(argv, script_file):
    return argv[1] if len(argv) > 1 else os.path.join(os.path.dirname(os.path.abspath(script_file)), '..', 'src',
                                                      'main', 'resources', 'songs')

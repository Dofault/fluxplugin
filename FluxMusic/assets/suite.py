"""Shared writer of the ambient song suites (gen_rpg_suite.py, gen_resaac_suite.py), on top of score.py.

A song is a few sections written as a score: one line per bar, "chord | tune | answer" (tune and answer optional,
'-' = rest bar). The accompaniment comes from a groove (bass, chords, arpeggios, drums for a given meter) played at an
energy level:

    0  calm: roots, slow arpeggio, deep drone, no drums
    1  the groove: bass pattern, chords, arpeggios, light drums
    2  full: plus the tune doubled an octave above, a second voice under it, fills at phrase ends

Tunes are written in any octave: each melodic track is moved by octaves once for the whole song (never section by
section, so the tune keeps its register), then checked by score.check (ranges, tune notes in the chord on strong
beats, semitones struck together).

Ambient songs play once each in a shuffled list (Ambience): every song ends on a held chord (end()).
"""
import os
import random

import score
from score import Piece, Section, chord, lowest

METERS = {  # name -> steps per beat, beats per bar, strong steps
    '4/4': (4, 4, (0, 8)),
    '3/4': (4, 3, (0,)),
    '6/8': (6, 2, (0, 6)),
    'shuffle': (3, 4, (0, 6)),   # 4/4 on a triplet grid: swung eighths are steps 0 and 2 of a beat
}

R, O, F, T = 'r', 'o', 'f', 't'
GROOVES = {
    # bass (step, root/octave/fifth/third), chords, arpeggio steps ('16' = every step), kick, snare, hat
    'adventure': dict(bass=[(0, R), (2, O), (4, R), (6, O), (8, R), (10, O), (12, R), (14, F)],
                      comp=(2, 6, 10, 14), arp='16', kick=(0, 6, 8), snare=(4, 12), hat=(2, 6, 10, 14)),
    'gallop': dict(bass=[(0, R), (2, O), (3, R), (4, R), (6, O), (8, R), (10, O), (11, R), (12, R), (14, F)],
                   comp=(0, 4, 6, 10, 12), arp='16', kick=(0, 3, 6, 8, 11), snare=(4, 12), hat=(2, 6, 10, 14)),
    'march': dict(bass=[(0, R), (4, F), (8, R), (12, F)], comp=(0, 8), arp=tuple(range(0, 16, 2)),
                  kick=(0, 8), snare=(4, 10, 12, 14, 15), hat=(0, 4, 8, 12)),
    'heavy': dict(bass=[(0, R), (3, R), (6, R), (8, R), (11, R), (14, F)], comp=(0, 6, 12),
                  arp=tuple(range(0, 16, 2)), kick=(0, 3, 10), snare=(8,), hat=(0, 4, 8, 12)),
    'town': dict(bass=[(0, R), (2, O), (4, F), (6, O), (8, R), (10, O), (12, F), (14, O)], comp=(2, 6, 10, 14),
                 arp=tuple(range(0, 16, 2)), kick=(0, 8), snare=(4, 12), hat=(2, 6, 10, 14)),
    'stroll': dict(bass=[(0, R), (4, F), (8, O), (12, F)], comp=(4, 12), arp=tuple(range(0, 16, 2)),
                   kick=(0, 10), snare=(4, 12), hat=(2, 6, 10, 14)),
    'waltz': dict(bass=[(0, R)], comp=(4, 8), arp=(0, 2, 4, 6, 8, 10), kick=(0,), snare=(), hat=(4, 8)),
    'jig': dict(bass=[(0, R), (6, F)], comp=(2, 4, 8, 10), arp=(0, 2, 4, 6, 8, 10), kick=(0, 6), snare=(6,),
                hat=(2, 4, 8, 10)),
    'shuffle': dict(bass=[(0, R), (3, F), (6, O), (9, F)], comp=(2, 5, 8, 11), arp=(0, 2, 3, 5, 6, 8, 9, 11),
                    kick=(0, 6), snare=(3, 9), hat=(0, 2, 3, 5, 6, 8, 9, 11)),
}
CALM_BASS = {'4/4': [(0, R), (8, F)], '3/4': [(0, R)], '6/8': [(0, R)], 'shuffle': [(0, R), (6, F)]}

VOLUMES = {'lead': 0.7, 'lead2': 0.2, 'harmony': 0.26, 'counter': 0.42, 'arp': 0.2, 'comp': 0.24, 'bass': 0.66,
           'deep': 0.34, 'kick': 0.42, 'snare': 0.22, 'roll': 0.16, 'hat': 0.11, 'sparkle': 0.12, 'pick': 0.2}
# optional roles, only played by the songs that give them an instrument (the band of the music pets):
#   pick     picked eighth notes over the chord (root, fifth, octave, tenth), from energy 1
#   sparkle  a few chord notes at random steps (seeded by the song id: the file never changes), from energy 1
EIGHTHS = {'4/4': 2, '3/4': 2, '6/8': 2, 'shuffle': None}   # steps of an eighth note; shuffle: swung (0, 2) per beat
MELODIC = ('lead', 'counter', 'lead2', 'harmony')
# FluxMusic's effects (Song(effects=True), 2026-11-19): chorus on the basses and the octave doubling, the answer and
# the second voice placed a little left and right (fixed stereo, never an orbit), the tune's long notes held, a glide
# into a long note reached by a leap, the bass falling an octave on the last chord.
EFFECT_OPTIONS = {'bass': {'chorus': 12}, 'deep': {'chorus': 8}, 'lead2': {'chorus': 10}, 'counter': {'pan': -0.6},
                  'harmony': {'pan': 0.4}}
HELD = 6            # a tune note of at least this many steps is held (~)
LEAP = (5, 12)      # semitones of a leap up into a held note that the note before slides through (>)
DRUM_INSTRUMENT = {'kick': 'basedrum', 'snare': 'snare', 'roll': 'snare', 'hat': 'hat'}


def transpose_chord(sym, n):
    if not n:
        return sym
    root, iv = chord(sym)
    name = sym[1:]
    if name[:1] in '#b':
        name = name[1:]
    return score.SPELLING[(root + n) % 12] + name


class Song:
    def __init__(self, song_id, title, bpm, meter, groove, instruments, volumes=None, comment='', effects=False):
        spb, bpb, self.strong = METERS[meter]
        self.meter, self.groove, self.comment = meter, GROOVES[groove], comment
        vol = dict(VOLUMES, **(volumes or {}))
        self.effects = effects
        tracks = {role: (inst, vol[role], EFFECT_OPTIONS.get(role, {}) if effects else {})
                  for role, inst in instruments.items()}
        if effects:
            tracks['chute'] = ('bass', 0.5, {'chorus': 15})
        for role in ('kick', 'snare', 'roll', 'hat'):
            tracks.setdefault(role, (DRUM_INSTRUMENT[role], vol[role]))
        self.piece = Piece(song_id, title, bpm, spb, bpb, tracks, melodic={'lead'})
        self.L = self.piece.L
        self.rng = random.Random(song_id)
        self.sections = []
        self.doubled = set()       # sections where lead2 and harmony follow the tune

    def has(self, role):
        return role in self.piece.tracks

    # ---------------------------------------------------------------- writing

    def section(self, name, text, energy=1, transpose=0, groove=None, fill=True, double=None):
        bars = [[c.strip() for c in line.split('|')] for line in text.strip().splitlines() if line.strip()]
        chords = ' '.join(','.join(transpose_chord(c, transpose) for c in b[0].split(',')) for b in bars)
        sec = Section(self.piece, name, chords)
        for col, track in ((1, 'lead'), (2, 'counter')):
            line = [b[col] if len(b) > col and b[col] not in ('', '-') else f'.:{self.L}' for b in bars]
            if any(len(b) > col and b[col] not in ('', '-') for b in bars):
                sec.write(track, ' | '.join(line), octave_fit=False, transpose=transpose)
        self.accompany(sec, energy, groove)
        if fill and energy >= 1:
            self.fill(sec, sec.bars - 1)
            if sec.bars >= 8 and energy >= 2:
                self.fill(sec, 3, light=True)
        if double if double is not None else energy >= 2:
            self.doubled.add(name)
        self.sections.append(sec)
        return sec

    def accompany(self, sec, energy, groove=None, bars=None):
        g = GROOVES[groove] if groove else self.groove
        L = self.L
        bars = list(bars if bars is not None else range(sec.bars))
        if energy == 0:
            score.bass(sec, 'bass', CALM_BASS[self.meter], bars=bars)
            if self.has('arp'):
                steps = g['arp'] if g['arp'] != '16' else tuple(range(0, L, 2))
                self.arp(sec, bars, steps[::2] if len(steps) > 4 else steps)
        else:
            score.bass(sec, 'bass', g['bass'], bars=bars)
            if self.has('arp'):
                self.arp(sec, bars, tuple(range(L)) if g['arp'] == '16' else g['arp'])
            if self.has('comp'):
                score.strum(sec, 'comp', bars=bars, pattern=g['comp'], low=self.comp_low())
            if self.has('pick'):
                self.pick(sec, bars)
            if self.has('sparkle'):
                score.stars(sec, 'sparkle', bars, self.rng, tuple(range(L // 2, L, 2)), chance=0.5 + 0.2 * energy)
            score.drums(sec, 'snare', g['snare'], bars)
            score.drums(sec, 'hat', g['hat'], bars)
            score.drums(sec, 'kick', g['kick'] if energy >= 2 else g['kick'][:1] + tuple(
                s for s in g['kick'] if s == L // 2), bars)
        if self.has('deep'):
            for start, size, sym in sec.slots_in(bars):
                root, _ = chord(sym)
                lo, _ = self.piece.range('deep')
                sec.hit('deep', start, {lowest(root, lo + 4)})

    def pick(self, sec, bars):
        """Picked eighths over each chord: root, fifth, octave, tenth, octave, fifth..."""
        lo, hi = self.piece.range('pick')
        e = EIGHTHS[self.meter]
        beat = self.piece.steps_per_beat
        for start, size, sym in sec.slots_in(bars):
            root, iv = chord(sym)
            r = lowest(root, lo)
            cycle = [r, r + 7, r + 12, r + 12 + iv[1], r + 12, r + 7]
            cycle = [n - 12 if n > hi else n for n in cycle]
            steps = range(0, size, e) if e else [b + x for b in range(0, size, beat) for x in (0, 2)]
            for k, s in enumerate(steps):
                sec.hit('pick', start + s, {cycle[k % len(cycle)]})

    def comp_low(self):
        lo, hi = self.piece.range('comp')
        return min(lo + 8, hi - 12)

    def arp(self, sec, bars, steps):
        """Broken chord up and down, one note per listed step."""
        lo, hi = self.piece.range('arp')
        for start, size, sym in sec.slots_in(bars):
            root, iv = chord(sym)
            base = lowest(root, lo + 3)
            up = [base + x for x in (0, iv[1], iv[2], 12, 12 + iv[1], 12 + iv[2])]
            up = [n - 12 if n > hi else n for n in up]
            cycle = up + up[-2:0:-1]
            k = 0
            for s in steps:
                if s < size:
                    sec.hit('arp', start + s, {cycle[k % len(cycle)]})
                    k += 1

    def fill(self, sec, bar, light=False):
        L = self.L
        start = L * 3 // 4 if not light else L - L // 8
        if not light:
            score.drums(sec, 'roll', range(L // 2, start), [bar])
        score.drums(sec, 'snare', range(start, L), [bar])

    def intro(self, text, energy=0, name='intro'):
        return self.section(name, text, energy, fill=energy >= 1, double=False)

    def end(self, chord_sym, note, bars=2, name='fin', transpose=0):
        """The song ends: the tonic chord struck once and held, sparkle upwards, a last kick."""
        L = self.L
        sec = Section(self.piece, name, ' '.join([transpose_chord(chord_sym, transpose)] * bars))
        events = score.parse(' | '.join([f'{note}:{L}'] + [f'.:{L}'] * (bars - 1)), L, name)
        for step, ns in events:
            sec.hit('lead', step, {n + transpose for n in ns})
        score.bass(sec, 'bass', [(0, R)], bars=[0])
        if self.has('deep'):
            lo, _ = self.piece.range('deep')
            sec.hit('deep', 0, {lowest(chord(sec.chord_at(0))[0], lo + 4)})
        if self.has('comp'):
            score.strum(sec, 'comp', bars=[0], pattern=(0,), low=self.comp_low())
        if self.has('arp'):
            self.arp(sec, [0], tuple(range(0, min(L, 12), 2)))
        score.drums(sec, 'kick', (0,), [0])
        score.drums(sec, 'snare', (0,), [0])
        if self.effects:
            top = lowest(chord(sec.chord_at(0))[0], 42)
            sec.hit('chute', 0, {top})
            sec.fx.setdefault('chute', {})[0] = ('>', -12)
        self.doubled.add(name)
        self.sections.append(sec)
        return sec

    # ---------------------------------------------------------------- derived tracks, fitting, output

    def _fit(self, track):
        if not self.has(track):
            return
        notes = [n for s in self.sections for ns in s.tracks.get(track, {}).values() for n in ns]
        if not notes:
            return
        lo, hi = self.piece.range(track)
        for k in (0, -1, 1, -2, 2, -3, 3):
            if min(notes) + 12 * k >= lo and max(notes) + 12 * k <= hi:
                for s in self.sections:
                    if track in s.tracks:
                        s.tracks[track] = {x: {n + 12 * k for n in ns} for x, ns in s.tracks[track].items()}
                return
        if track in ('lead', 'counter'):
            raise ValueError(f'{self.piece.id}/{track}: {min(notes)}..{max(notes)} does not fit {lo}..{hi}')
        # a derived voice wider than the instrument: centred, the notes outside folded back by an octave
        k = round(((lo + hi) / 2 - (min(notes) + max(notes)) / 2) / 12)
        fold = lambda n: n + 12 * k + (12 if n + 12 * k < lo else -12 if n + 12 * k > hi else 0)
        for s in self.sections:
            if track in s.tracks:
                s.tracks[track] = {x: {fold(n) for n in ns} for x, ns in s.tracks[track].items()}

    def _derive(self):
        for s in self.sections:
            if s.name not in self.doubled or 'lead' not in s.tracks:
                continue
            for step, ns in s.tracks['lead'].items():
                top = max(ns)
                if self.has('lead2'):
                    s.hit('lead2', step, {top + 12})
                if self.has('harmony'):
                    pcs = score.tones(s.chord_at(step))
                    cand = next((top - d for d in range(3, 10) if (top - d) % 12 in pcs), None)
                    if cand is not None:
                        s.hit('harmony', step, {cand})

    def _give_way(self):
        leads = ('lead', 'counter')
        for sec in self.sections:
            for lead in leads:
                for step, ns in sec.tracks.get(lead, {}).items():
                    pcs = score.tones(sec.chord_at(step))
                    for t, hits in sec.tracks.items():
                        if t in leads or self.piece.instrument(t) in score.DRUMS or step not in hits:
                            continue
                        bad = {a for a in hits[step] for b in ns
                               if abs(a - b) % 12 in (1, 11) and not {a % 12, b % 12} <= pcs}
                        hits[step] -= bad
                        if not hits[step]:
                            del hits[step]

    def _effects(self):
        """Held notes and glides on the tune (relative marks: they survive the octave fitting)."""
        for sec in self.sections:
            hits = sec.tracks.get('lead', {})
            steps = sorted(hits)
            fx = sec.fx.setdefault('lead', {})
            for k, st in enumerate(steps):
                if len(hits[st]) != 1 or st in fx:
                    continue
                length = (steps[k + 1] if k + 1 < len(steps) else sec.length) - st
                if length >= HELD:
                    fx[st] = '~'
                    if k > 0 and len(hits[steps[k - 1]]) == 1 and steps[k - 1] not in fx \
                            and st - steps[k - 1] <= 4:
                        jump = next(iter(hits[st])) - next(iter(hits[steps[k - 1]]))
                        if LEAP[0] <= jump <= LEAP[1]:
                            fx[steps[k - 1]] = ('>', jump)

    def build(self, folder, script, loop_from=None):
        if self.effects:
            self._effects()
        self._fit('lead')
        self._fit('counter')
        self._derive()
        self._fit('lead2')
        self._fit('harmony')
        self._give_way()
        problems = score.check(self.sections, lambda sec, step: self.strong, quiet={'sparkle'})
        first = loop_from or self.sections[0].name
        path = score.write_song(folder, self.piece, self.sections, [self.comment], script, first)
        seconds = sum(s.bars for s in self.sections) * self.L * 60 / (self.piece.bpm * self.piece.steps_per_beat)
        print(f'{self.piece.id:22} {self.piece.title:32} {seconds:5.0f} s  {problems} remark(s)')
        return problems

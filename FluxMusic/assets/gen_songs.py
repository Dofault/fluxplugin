#!/usr/bin/env python3
"""Generates the boss songs (one per job, for the dungeons) and the town song of FluxMusic.

Melodies and chord progressions are written by hand below; the accompaniment (bass, guitar, arpeggios,
drone, drums) is laid out from the chords in the style chosen for each song, inside the range of each
instrument. Melodies are moved by whole octaves into the range of the instrument that plays them.

    python3 gen_songs.py [output folder]      (default: ../src/main/resources/songs)

Never edit the generated songs/boss_*.yml and songs/ville.yml by hand: change this script and run it again.
"""
import os
import re
import sys

NAMES = {'C': 0, 'D': 2, 'E': 4, 'F': 5, 'G': 7, 'A': 9, 'B': 11}
# Range (lowest, highest MIDI) of each note block instrument.
RANGE = {
    'harp': (54, 78), 'pling': (54, 78), 'bit': (54, 78), 'banjo': (54, 78), 'iron_xylophone': (54, 78),
    'bass': (30, 54), 'didgeridoo': (30, 54), 'guitar': (42, 66), 'flute': (66, 90), 'cow_bell': (66, 90),
    'bell': (78, 102), 'chime': (78, 102), 'xylophone': (78, 102),
}
SHARP = ['C', 'C#', 'D', 'D#', 'E', 'F', 'F#', 'G', 'G#', 'A', 'A#', 'B']
FLAT = ['C', 'Db', 'D', 'Eb', 'E', 'F', 'Gb', 'G', 'Ab', 'A', 'Bb', 'B']
NOTE = re.compile(r'^([A-G])([#b]?)(-?\d)$')

# Meters: steps per beat, beats per bar.
METERS = {'4/4': (4, 4), '12/8': (3, 4), '3/4': (2, 3)}


def midi(note):
    m = NOTE.match(note)
    if not m:
        raise ValueError('bad note ' + note)
    pc = NAMES[m.group(1)] + (1 if m.group(2) == '#' else -1 if m.group(2) == 'b' else 0)
    return (int(m.group(3)) + 1) * 12 + pc


def name(n, flats):
    return (FLAT if flats else SHARP)[n % 12] + str(n // 12 - 1)


def chord(symbol):
    """'F#m' -> (root pitch class, [intervals]); qualities: '', m, 7, m7, dim, sus."""
    m = re.match(r'^([A-G])([#b]?)(.*)$', symbol)
    root = NAMES[m.group(1)] + (1 if m.group(2) == '#' else -1 if m.group(2) == 'b' else 0)
    q = m.group(3)
    intervals = {'': [0, 4, 7], 'm': [0, 3, 7], '7': [0, 4, 7, 10], 'm7': [0, 3, 7, 10],
                 'dim': [0, 3, 6], 'sus': [0, 5, 7], 'maj7': [0, 4, 7, 11]}[q]
    return root % 12, intervals


def lowest(pc, low):
    """Lowest MIDI note of pitch class pc at or above low."""
    return low + (pc - low) % 12


# ------------------------------------------------------------------ textures (one bar each)

def tok(notes, dur, flats):
    body = '+'.join(name(n, flats) for n in notes) if notes else '.'
    return body if dur == 1 else f'{body}:{dur}'


def bass(style, sym, L, i, flats):
    root, iv = chord(sym)
    r = lowest(root, 31)
    o, f = r + 12, r + 7
    t = lambda n, d: tok([n], d, flats)
    if L == 16:
        return {
            'pump': ' '.join(t(r, 2) + ' ' + t(o, 2) for _ in range(4)),
            'gallop': ' '.join(f'{t(r, 2)} {t(r, 1)} {t(r, 1)}' for _ in range(4)),
            'drive': ' '.join(t(r, 2) for _ in range(8)),
            'long': f'{t(r, 8)} {t(f, 8)}',
            'half': f'{t(r, 6)} {t(r, 2)} {t(f, 8)}',
            'walk': ' '.join(t(n, 4) for n in (r, r + iv[1], f, r + iv[1])),
        }[style]
    if L == 12:
        return {
            'rock': ' '.join(f'{t(r, 2)} {t(o, 1)}' for _ in range(4)),
            'long': f'{t(r, 6)} {t(f, 6)}',
        }[style]
    return {'waltz': f'{t(r if i % 2 == 0 else f, 2)} .:4'}[style]   # 3/4


def guitar(style, sym, L, flats):
    root, iv = chord(sym)
    r = lowest(root, 45)
    triad = [n - 12 if n > 66 else n for n in (r + x for x in iv)]
    low = lowest(root, 43)
    spread = [low + x for x in iv[:3]] + [low + 12]     # arpeggio: root to octave, within the guitar's range
    power = [lowest(root, 42), lowest(root, 42) + 7, lowest(root, 42) + 12]
    C = lambda d: tok(triad, d, flats)
    P = lambda d: tok(power, d, flats)
    if L == 16:
        return {
            'stabs': f'{C(3)} {C(3)} {C(2)} .:8',
            'power': f'{P(3)} {P(3)} {P(2)} {P(3)} {P(3)} {P(2)}',
            'chug': ' '.join(f'{P(2)} {P(1)} {P(1)}' for _ in range(4)),
            'off': ' '.join(f'.:2 {C(2)}' for _ in range(4)),
            'held': C(16),
            'arp': ' '.join(tok([n], 2, flats) for n in [spread[0], spread[1], spread[2], spread[3],
                                                         spread[2], spread[1], spread[0], spread[1]]),
        }[style]
    if L == 12:
        return {
            'strum': ' '.join(f'{C(2)} {C(1)}' for _ in range(4)),
            'held': C(12),
            'arp': ' '.join(tok([n], 1, flats) for n in [spread[0], spread[1], spread[2], spread[3], spread[2],
                                                         spread[1]] * 2),
        }[style]
    return {'waltz': f'.:2 {C(2)} {C(2)}'}[style]   # 3/4


def arpeggio(instrument, sym, L, flats):
    root, iv = chord(sym)
    lo, hi = RANGE[instrument]
    tones = []
    n = lowest(root, lo + 3)
    while len(tones) < 4:
        for x in iv[:3]:
            if len(tones) < 4:
                tones.append(n + x)
        n += 12
    tones = [t for t in tones if t <= hi] or tones[:1]
    cycle = tones + tones[-2:0:-1]                      # up then down
    if L == 6:
        return ' '.join(tok([cycle[k % len(cycle)]], 1, flats) for k in range(6))
    return ' '.join(tok([cycle[k % len(cycle)]], 1, flats) for k in range(L))


def drone(sym, L, flats):
    root, _ = chord(sym)
    return tok([lowest(root, 31)], L, flats)


DRUMS = {
    16: {
        'rock': ('x:6 x:2 x:8', '.:4 x:8 x:4', ' '.join(['x:2'] * 8)),
        'double': (' '.join(['x:2'] * 8), '.:4 x:8 x:4', ' '.join(['x:4'] * 4)),
        'gallop': (' '.join(['x:2 x x'] * 4), '.:4 x:8 x:4', ' '.join(['x:4'] * 4)),
        'half': ('x:6 x:2 .:8', '.:8 x:8', ' '.join(['x:2'] * 8)),
        'march': (' '.join(['x:4'] * 4), 'x:2 x x x:2 x:2 x:2 x x x:2 x:2', '.:16'),
        'sparse': ('x:16', '.:16', '.:4 x:4 .:4 x:4'),
        'fill': ('x:4 x:4 x:2 x:2 x:2 x:2', '.:8 x x x x x x x x', '.:16'),
        'soft': ('x:8 x:8', '.:16', '.:4 x:4 .:4 x:4'),
        'clap': ('x:8 x:8', '.:4 x:8 x:4', ' '.join(['x:2'] * 8)),   # hands clapping on 2 and 4
        'none': ('.:16', '.:16', '.:16'),
    },
    12: {
        'rock': ('x:6 x:6', '.:3 x:6 x:3', ' '.join(['x:2 x'] * 4)),
        'gallop': (' '.join(['x:2 x'] * 4), '.:3 x:6 x:3', ' '.join(['x:3'] * 4)),
        'half': ('x:6 x:6', '.:6 x:6', ' '.join(['x:2 x'] * 4)),
        'sparse': ('x:12', '.:12', '.:3 x:3 .:3 x:3'),
        'fill': ('x:3 x:3 x:2 x x:2 x', '.:6 x x x x x x', '.:12'),
        'soft': ('x:6 x:6', '.:12', '.:3 x:3 .:3 x:3'),
        'none': ('.:12', '.:12', '.:12'),
    },
    6: {
        'waltz': ('.:6', '.:6', '.:2 x:2 x:2'),
        'none': ('.:6', '.:6', '.:6'),
        'sparse': ('.:6', '.:6', '.:2 x:4'),
        'fill': ('.:6', '.:2 x x x:2', '.:6'),
    },
}


# ------------------------------------------------------------------ melodies

def fit(melody, instrument, where):
    """Moves a melody (bars separated by |) by whole octaves into the instrument's range."""
    lo, hi = RANGE[instrument]
    notes = [midi(n) for n in re.findall(r'[A-G][#b]?-?\d', melody)]
    for k in (0, -1, 1, -2, 2):
        if min(notes) + 12 * k >= lo and max(notes) + 12 * k <= hi:
            return re.sub(r'([A-G][#b]?)(-?\d)', lambda m: m.group(1) + str(int(m.group(2)) + k), melody)
    raise ValueError(f'{where}: melody {name(min(notes), False)}..{name(max(notes), False)} does not fit {instrument}')


def check_bars(line, L, where):
    for b, bar in enumerate(line.split('|'), 1):
        total = 0
        for t in bar.split():
            total += int(t.split(':')[1]) if ':' in t else 1
        if total != L:
            raise ValueError(f'{where}: bar {b} lasts {total} steps instead of {L}')


# ------------------------------------------------------------------ songs

def build(spec):
    spb, beats = METERS[spec['meter']]
    L = spb * beats
    flats = spec.get('flats', False)
    prog = {k: v.split() for k, v in spec['prog'].items()}
    prog['intro'] = [prog['a'][0]] * 2
    prog['a2'] = prog['a']
    tracks = {
        'lead': (spec['lead'], 0.8), 'chant': (spec['b_lead'], 0.7), 'echo': (spec['echo'], 0.3),
        'arpege': (spec['arp'], 0.32), 'guitare': ('guitar', 0.55), 'basse': ('bass', 0.85),
        'grosse': ('basedrum', 0.65), 'caisse': ('snare', 0.33), 'charley': ('hat', 0.17),
    }
    if spec.get('drone', True):
        tracks['grondement'] = ('didgeridoo', 0.45)
    tracks.update({k: (v[0], v[1]) for k, v in spec.get('volumes', {}).items()})

    sections = {}
    for sec in ('intro', 'a', 'b', 'a2'):
        chords = prog[sec]
        bars = len(chords)
        out = {}
        style = spec['styles']['b' if sec == 'b' else 'a']
        if sec == 'a':
            out['lead'] = fit(spec['mel_a'], spec['lead'], spec['id'] + '/a')
        if sec == 'a2':
            out['lead'] = fit(spec['mel_a'], spec['lead'], spec['id'] + '/a2')
            out['echo'] = fit(spec['mel_a'], spec['echo'], spec['id'] + '/echo')
        if sec == 'b':
            out['chant'] = fit(spec['mel_b'], spec['b_lead'], spec['id'] + '/b')
        if sec in ('intro', 'b', 'a2') or spec.get('arp_always'):
            out['arpege'] = ' | '.join(arpeggio(spec['arp'], c, L, flats) for c in chords)
        if sec != 'intro' and style.get('guitar'):
            out['guitare'] = ' | '.join(guitar(style['guitar'], c, L, flats) for c in chords)
        out['basse'] = ' | '.join(bass(style['bass'], c, L, i, flats) for i, c in enumerate(chords))
        if 'grondement' in tracks:
            out['grondement'] = ' | '.join(drone(c, L, flats) for c in chords)
        if not spec.get('fills', True):
            names = [style['drums']] * bars                   # calm songs: no drum roll at the end of a section
        elif sec == 'intro':
            names = ['sparse'] * (bars - 1) + ['fill']
        else:
            names = [style['drums']] * (bars - 1) + ['fill']
        parts = [DRUMS[L][n] for n in names]
        for k, track in enumerate(('grosse', 'caisse', 'charley')):
            line = ' | '.join(p[k] for p in parts)
            if 'x' in line:
                out[track] = line
        for track, line in out.items():
            check_bars(line, L, f"{spec['id']}/{sec}/{track}")
        sections[sec] = out
    return spec, spb, beats, tracks, sections


def yaml(spec, spb, beats, tracks, sections):
    q = lambda s: '"' + s.replace('"', '\\"') + '"'
    lines = [f"# {spec['comment']}",
             '# Généré par FluxMusic/assets/gen_songs.py : modifier le script, pas ce fichier.',
             f"title: {q(spec['title'])}", 'author: "Influencraft"', f"bpm: {spec['bpm']}",
             f'steps-per-beat: {spb}', f'beats-per-bar: {beats}', 'loop: true', 'loop-from: a', '', 'tracks:']
    used = {t for s in sections.values() for t in s}
    for t, (instrument, volume) in tracks.items():
        if t in used:
            lines.append(f'  {t}: {{ instrument: {instrument}, volume: {volume} }}')
    lines += ['', 'sections:']
    for sec, out in sections.items():
        lines.append(f'  {sec}:')
        for t, line in out.items():
            lines.append(f'    {t}: {q(line)}')
    lines += ['', 'order: [intro, a, b, a2]', '']
    return '\n'.join(lines)


SONGS = [
    dict(id='boss_mineur', title='La Veine Écarlate', bpm=160, meter='4/4',
         comment='Boss du Mineur (Mine de Cuivre) : mi phrygien, pioches de xylophone de fer, 160 BPM.',
         lead='bit', b_lead='flute', echo='bell', arp='iron_xylophone',
         prog={'a': 'Em F Em D Em F D B', 'b': 'C D Em Em C D B B'},
         mel_a='E5:2 E5:2 B4:2 E5:2 G5:4 F#5:2 E5:2 | F5:4 E5:2 D5:2 C5:4 A4:4 | B4:2 E5:2 G5:2 B5:2 A5:4 G5:4 '
               '| F#5:6 E5:2 D5:4 A4:4 | E5:2 E5:2 B4:2 E5:2 G5:4 A5:2 B5:2 | C6:4 B5:2 A5:2 F5:4 A5:4 '
               '| G5:4 F#5:2 E5:2 D5:4 F#5:4 | D#5:4 F#5:4 B5:8',
         mel_b='G5:8 E5:4 G5:4 | A5:8 F#5:4 D5:4 | B5:12 G5:4 | E5:16 | G5:8 E5:4 C6:4 | A5:8 B5:4 C6:4 '
               '| B5:8 A5:4 F#5:4 | D#5:16',
         styles={'a': dict(bass='gallop', guitar='power', drums='rock'),
                 'b': dict(bass='long', guitar='held', drums='half')}),
    dict(id='boss_rochiste', title='Le Colosse de Granit', bpm=112, meter='4/4', flats=True,
         comment='Boss du Rochiste (Carrière Grondante) : ré mineur lourd, demi-temps, grondement de didgeridoo.',
         lead='pling', b_lead='harp', echo='bell', arp='harp',
         prog={'a': 'Dm Dm Bb C Dm Dm Gm A', 'b': 'Bb F C Dm Bb F Gm A'},
         mel_a='D5:6 F5:2 A5:8 | G5:4 F5:4 E5:4 D5:4 | F5:6 D5:2 Bb4:8 | C5:4 E5:4 G5:8 | A5:6 G5:2 F5:4 A5:4 '
               '| D6:8 C6:4 A5:4 | Bb5:6 A5:2 G5:4 D5:4 | E5:4 C#5:4 A4:8',
         mel_b='D5:8 F5:8 | C5:8 A4:8 | E5:8 G5:8 | F5:12 E5:4 | D5:8 Bb4:8 | A4:8 C5:8 | Bb4:8 D5:8 | C#5:8 E5:8',
         volumes={'grondement': ('didgeridoo', 0.6)},
         styles={'a': dict(bass='half', guitar='power', drums='half'),
                 'b': dict(bass='long', guitar='held', drums='sparse')}),
    dict(id='boss_deblayeur', title='La Fureur de Taupérion', bpm=170, meter='4/4', flats=True,
         comment='Boss du Déblayeur (Galeries de Taupérion) : sol mineur sautillant, xylophone, 170 BPM.',
         lead='xylophone', b_lead='pling', echo='cow_bell', arp='banjo', drone=False,
         prog={'a': 'Gm Gm Eb D Gm Gm Eb D', 'b': 'Cm D Gm Gm Cm D Eb D'},
         mel_a='G5:2 .:2 G5:2 A5:2 Bb5:2 .:2 A5:2 G5:2 | D6:2 .:2 C#6:2 D6:2 Bb5:4 G5:4 '
               '| Eb6:2 .:2 D6:2 C6:2 Bb5:2 .:2 G5:4 | A5:2 F#5:2 A5:2 C6:2 D6:8 '
               '| G5:2 .:2 G5:2 A5:2 Bb5:2 .:2 C6:2 D6:2 | G6:4 F6:2 D6:2 Bb5:4 D6:4 '
               '| C6:2 Bb5:2 G5:2 Bb5:2 A5:4 F#5:4 | F#5:4 A5:4 D6:8',
         mel_b='C5:4 Eb5:4 G5:8 | F#5:4 A5:4 D5:8 | G5:2 F5:2 D5:4 Bb4:8 | G4:16 | C5:4 Eb5:4 G5:4 C6:4 '
               '| A5:8 F#5:8 | G5:4 Eb5:4 Bb4:8 | D5:4 F#5:4 A5:8',
         styles={'a': dict(bass='drive', guitar='off', drums='rock'),
                 'b': dict(bass='pump', guitar='stabs', drums='half')}),
    dict(id='boss_cultivateur', title='Le Moissonneur', bpm=120, meter='12/8',
         comment='Boss du Cultivateur (Champ du Moissonneur) : la mineur à 12/8, banjo et flûte, faux qui balance.',
         lead='banjo', b_lead='flute', echo='chime', arp='harp',
         prog={'a': 'Am Am G Am F G E E', 'b': 'F G Am Am Dm E Am E'},
         mel_a='A4:2 C5 E5:2 A5 G5:2 E5 C5:2 E5 | A5:3 B5:2 A5 G5:3 E5:3 | D5:2 G5 B5:2 G5 D5:2 B4 G4:3 '
               '| C5:2 E5 A5:2 E5 C5:3 A4:3 | F5:2 A5 C6:2 A5 F5:2 E5 F5:3 | G5:3 D5:3 B4:3 D5:3 '
               '| E5:2 G#5 B5:2 G#5 E5:2 D5 C5:3 | B4:3 G#4:3 B4:6',
         mel_b='C6:6 A5:6 | B5:6 G5:6 | A5:2 B5 C6:2 B5 A5:6 | E5:12 | F5:2 A5 D6:2 A5 F5:6 | G#5:6 B5:6 '
               '| C6:2 B5 A5:2 G#5 A5:6 | B5:12',
         styles={'a': dict(bass='rock', guitar='strum', drums='gallop'),
                 'b': dict(bass='long', guitar='arp', drums='half')}),
    dict(id='boss_herboriste', title='La Reine des Ronces', bpm=132, meter='4/4',
         comment="Boss de l'Herboriste : mi mineur harmonique, mystérieux, harpe et carillon.",
         lead='harp', b_lead='flute', echo='chime', arp='pling', arp_always=True,
         prog={'a': 'Em Em C B Em Em Am B', 'b': 'C D Bm Em C D B B'},
         mel_a='B4:4 E5:2 F#5:2 G5:6 F#5:2 | E5:4 D#5:4 E5:8 | G5:4 A5:2 B5:2 C6:6 B5:2 | A5:4 F#5:4 D#5:8 '
               '| B5:4 G5:2 E5:2 B5:4 C6:2 B5:2 | A5:2 G5:2 F#5:2 G5:2 E5:8 | C6:4 B5:2 A5:2 E5:4 A5:4 '
               '| F#5:4 A5:4 B5:2 A5:2 F#5:2 D#5:2',
         mel_b='E5:8 G5:8 | F#5:8 A5:8 | B5:12 A5:4 | G5:16 | E6:8 C6:8 | D6:8 A5:8 | B5:4 D#6:4 F#6:8 | F#5:16',
         styles={'a': dict(bass='long', guitar='arp', drums='half'),
                 'b': dict(bass='long', guitar='held', drums='sparse')}),
    dict(id='boss_sylviculteur', title='Le Gardien Sylvestre', bpm=144, meter='4/4',
         comment='Boss du Sylviculteur : fa dièse mineur épique, grands arpèges de guitare, flûte des bois.',
         lead='flute', b_lead='harp', echo='chime', arp='harp',
         prog={'a': 'F#m D A E F#m D E C#', 'b': 'Bm F#m D E Bm F#m D C#'},
         mel_a='C#5:4 F#5:4 A5:4 G#5:2 F#5:2 | A5:6 B5:2 A5:4 F#5:4 | E5:4 A5:4 C#6:4 B5:2 A5:2 | G#5:6 F#5:2 E5:8 '
               '| F#5:4 A5:2 C#6:2 F#6:6 E6:2 | D6:4 C#6:2 B5:2 A5:4 F#5:4 | B5:4 G#5:2 E5:2 G#5:4 B5:4 '
               '| C#6:4 G#5:4 F5:4 C#5:4',
         mel_b='D5:4 F#5:4 B5:8 | A5:4 F#5:4 C#5:8 | D5:4 F#5:4 A5:8 | G#5:8 E5:8 | F#5:4 B5:4 D6:8 | C#6:8 A5:8 '
               '| F#5:4 A5:4 D6:4 C#6:4 | F5:8 G#5:8',
         styles={'a': dict(bass='gallop', guitar='arp', drums='gallop'),
                 'b': dict(bass='long', guitar='arp', drums='half')}),
    dict(id='boss_fondeur', title='Le Cœur de la Forge', bpm=150, meter='4/4', flats=True,
         comment='Boss du Fondeur : fa mineur, enclume de xylophone de fer, guitare saccadée, 150 BPM.',
         lead='bit', b_lead='bell', echo='flute', arp='iron_xylophone', arp_always=True,
         prog={'a': 'Fm Fm Db Eb Fm Fm Db C', 'b': 'Db Eb Cm Fm Db Eb C C'},
         mel_a='F5:2 F5:2 Ab5:2 F5:2 C6:4 Bb5:2 Ab5:2 | G5:4 Ab5:2 G5:2 F5:8 | Ab5:4 F5:2 Db5:2 Ab5:4 Bb5:2 C6:2 '
               '| Bb5:4 G5:4 Eb5:8 | F5:2 Ab5:2 C6:2 F6:2 Eb6:4 C6:4 | Db6:4 C6:2 Bb5:2 Ab5:4 G5:4 '
               '| F5:4 Ab5:4 Db6:4 C6:2 Bb5:2 | C6:4 G5:4 E5:4 C5:4',
         mel_b='F6:8 Ab6:8 | G6:8 Bb6:8 | Eb6:4 G6:4 C7:8 | Ab6:16 | F6:4 Ab6:4 Db7:8 | Bb6:8 G6:8 '
               '| E6:4 G6:4 C7:4 G6:4 | E6:16',
         volumes={'arpege': ('iron_xylophone', 0.28)},
         styles={'a': dict(bass='drive', guitar='chug', drums='double'),
                 'b': dict(bass='half', guitar='power', drums='half')}),
    dict(id='boss_enchanteur', title="L'Arcaniste", bpm=138, meter='4/4',
         comment="Boss de l'Enchanteur : si mineur harmonique, arpèges de carillon comme des runes qui s'allument.",
         lead='pling', b_lead='harp', echo='bell', arp='chime', arp_always=True,
         prog={'a': 'Bm G Em F# Bm G A F#', 'b': 'G A D Bm Em F# Bm F#'},
         mel_a='B4:2 D5:2 F#5:2 B5:2 A#5:4 B5:4 | G5:4 F#5:2 E5:2 D5:4 B4:4 | E5:2 G5:2 B5:2 E6:2 D6:4 B5:4 '
               '| C#6:6 A#5:2 F#5:8 | D6:2 C#6:2 B5:2 F#5:2 D6:4 B5:4 | G5:2 B5:2 D6:4 C#6:4 B5:4 '
               '| A5:4 E5:2 A5:2 C#6:4 E6:4 | F#6:4 E6:2 C#6:2 A#5:8',
         mel_b='D5:8 B4:8 | C#5:8 E5:8 | F#5:12 E5:4 | D5:16 | G5:8 E5:8 | A#4:8 C#5:8 | B4:4 D5:4 F#5:8 | F#5:16',
         volumes={'arpege': ('chime', 0.25)},
         styles={'a': dict(bass='pump', guitar='off', drums='rock'),
                 'b': dict(bass='long', guitar='held', drums='half')}),
    dict(id='boss_pecheur', title='Le Léviathan des Marées', bpm=126, meter='12/8',
         comment='Boss du Pêcheur (Piscine des Marées) : ré dorien à 12/8, arpèges de harpe comme la houle.',
         lead='flute', b_lead='bell', echo='chime', arp='harp', arp_always=True, flats=True,
         prog={'a': 'Dm C Dm C Bb C Dm A', 'b': 'Bb F C Dm Bb F Gm A'},
         mel_a='D5:3 F5:2 A5 D6:3 C6:3 | G5:6 E5:3 C5:3 | F5:2 G5 A5:2 B5 A5:3 F5:3 | E5:2 F5 G5:2 E5 C5:6 '
               '| D5:3 F5:2 Bb5 D6:6 | C6:2 Bb5 A5:2 G5 E5:6 | F5:2 E5 D5:2 F5 A5:6 | C#6:6 A5:6',
         mel_b='F6:6 D6:6 | C6:6 A5:6 | E6:6 G6:6 | F6:12 | D6:3 F6:3 Bb6:6 | A6:6 F6:6 | G6:3 Bb6:3 D6:6 | C#6:12',
         styles={'a': dict(bass='rock', guitar='strum', drums='rock'),
                 'b': dict(bass='long', guitar='held', drums='half')}),
    dict(id='boss_defenseur', title='Le Seigneur des Ossements', bpm=168, meter='4/4',
         comment='Boss du Défenseur (Fosse aux Ossements) : do dièse mineur, os de xylophone, tambours de marche.',
         lead='xylophone', b_lead='bit', echo='bit', arp='iron_xylophone',
         prog={'a': 'C#m A B G# C#m A F#m G#', 'b': 'A B C#m C#m A B G# G#'},
         mel_a='C#6:2 C#6:2 E6:2 C#6:2 G#6:4 F#6:2 E6:2 | E6:4 C#6:4 A5:8 '
               '| D#6:2 D#6:2 F#6:2 D#6:2 B6:4 A6:2 F#6:2 | G#6:4 D#6:4 C6:4 G#5:4 '
               '| C#6:2 E6:2 G#6:2 C#7:2 B6:4 G#6:4 | A6:4 G#6:2 F#6:2 E6:4 C#6:4 | F#6:4 A6:4 C#7:4 A6:4 '
               '| G#6:4 D#6:4 C6:4 D#6:4',
         mel_b='E5:8 C#5:8 | D#5:8 F#5:8 | E5:4 G#5:4 C#6:8 | B5:16 | A5:8 E5:8 | F#5:8 D#5:8 | G#5:8 D#5:8 | G#4:16',
         styles={'a': dict(bass='gallop', guitar='power', drums='double'),
                 'b': dict(bass='half', guitar='stabs', drums='march')}),
]

# The town: a musette waltz for the boulevards of Utopia (3/4, 8th notes as steps).
TOWN = dict(id='ville', title="Les Boulevards d'Utopia", bpm=160, meter='3/4', flats=True, drone=False,
            comment="Musique de ville : valse musette en fa majeur pour les boulevards haussmanniens d'Utopia.",
            lead='flute', b_lead='harp', echo='pling', arp='harp',
            prog={'a': 'F F C7 C7 C7 C7 F F F F Bb Bb F C7 F F',
                  'b': 'Dm Dm A7 A7 A7 A7 Dm Dm Gm Gm Dm Dm A7 A7 Dm C7'},
            mel_a='A5:4 G5 F5 | C6:4 A5:2 | G5:4 F5 E5 | Bb5:4 G5:2 | E5:2 G5:2 C6:2 | Bb5:2 A5:2 G5:2 | A5:4 F5:2 '
                  '| F5:6 | A5:4 G5 F5 | C6:2 D6:2 C6:2 | D6:4 C6 Bb5 | Bb5:2 D6:2 F6:2 | F6:4 C6:2 | E6:2 D6:2 Bb5:2 '
                  '| C6:2 A5:2 G5:2 | F5:6',
            mel_b='F5:4 E5 D5 | A5:4 F5:2 | E5:4 D5 C#5 | G5:4 E5:2 | C#5:2 E5:2 A5:2 | G5:2 F5:2 E5:2 | F5:4 D5:2 '
                  '| D5:6 | D5:2 G5:2 Bb5:2 | A5:2 G5:2 F5:2 | F5:4 A5:2 | F5:2 E5:2 D5:2 | C#5:2 E5:2 G5:2 '
                  '| A5:4 G5:2 | F5:2 E5:2 D5:2 | E5:2 G5:2 Bb5:2',
            volumes={'charley': ('hat', 0.15), 'echo': ('pling', 0.25)},
            styles={'a': dict(bass='waltz', guitar='waltz', drums='waltz'),
                    'b': dict(bass='waltz', guitar='waltz', drums='waltz')})


# More town songs: one mood of Utopia each, warm and friendly (a community meeting up), major keys, no drone.
TOWNS = [
    dict(id='ville_marche', title='Jour de Marché', bpm=120, meter='4/4', drone=False, fills=False,
         comment='Musique de ville : matinée de marché sur la place, sol majeur enjoué, flûte et banjo.',
         lead='flute', b_lead='banjo', echo='chime', arp='pling',
         prog={'a': 'G C G D G C D G', 'b': 'Em C G D Em C Am D'},
         mel_a='D5:2 G5:2 B5:2 G5:2 A5:4 G5:4 | E5:4 G5:2 E5:2 C5:8 | B4:2 D5:2 G5:2 B5:2 D6:4 B5:4 '
               '| A5:6 G5:2 F#5:4 D5:4 | D5:2 G5:2 B5:2 G5:2 C6:4 B5:4 | A5:2 G5:2 E5:2 G5:2 C6:8 '
               '| B5:4 A5:2 G5:2 F#5:4 A5:4 | G5:12 .:4',
         mel_b='G5:4 E5:4 B4:8 | C5:4 E5:4 G5:8 | D5:4 B4:4 G4:8 | A4:4 D5:4 F#5:8 | E5:2 F#5:2 G5:4 B5:8 '
               '| A5:4 G5:4 E5:8 | C5:4 E5:4 A5:4 G5:4 | F#5:8 D5:8',
         volumes={'grosse': ('basedrum', 0.4), 'charley': ('hat', 0.13)},
         styles={'a': dict(bass='pump', guitar='off', drums='soft'),
                 'b': dict(bass='long', guitar='off', drums='soft')}),
    dict(id='ville_soir', title='Lanternes du Soir', bpm=84, meter='4/4', drone=False, fills=False, flats=True,
         comment="Musique de ville : soirée calme quand les lanternes s'allument, mi bémol majeur, harpe et cloches.",
         lead='harp', b_lead='flute', echo='bell', arp='chime',
         prog={'a': 'Eb Ab Bb Eb Cm Ab Bb Bb', 'b': 'Ab Bb Gm Cm Ab Bb Eb Eb'},
         mel_a='G5:8 Bb5:4 G5:4 | C6:8 Bb5:4 Ab5:4 | F5:8 D5:4 F5:4 | Eb5:16 | G5:8 C6:4 Bb5:4 | Ab5:8 C6:4 Eb6:4 '
               '| D6:8 C6:4 Bb5:4 | F5:16',
         mel_b='Eb6:8 C6:8 | D6:8 Bb5:8 | Bb5:4 G5:4 D6:8 | C6:16 | Ab5:4 C6:4 Eb6:8 | F6:8 D6:8 | Eb6:4 Bb5:4 G5:8 '
               '| Eb5:16',
         volumes={'arpege': ('chime', 0.2), 'grosse': ('basedrum', 0.35)},
         styles={'a': dict(bass='long', guitar='arp', drums='sparse'),
                 'b': dict(bass='long', guitar='arp', drums='none')}),
    dict(id='ville_port', title='Le Port de Commerce', bpm=100, meter='12/8', drone=False, fills=False,
         comment='Musique de ville : le Port de Commerce, ré majeur à 12/8 qui tangue, flûte, banjo et guitare.',
         lead='flute', b_lead='banjo', echo='bell', arp='harp',
         prog={'a': 'D G D A D G A D', 'b': 'Bm G D A Bm G Em A'},
         mel_a='A4:2 D5 F#5:2 A5 D6:3 A5:3 | B5:2 A5 G5:2 B5 D6:6 | A5:2 F#5 D5:2 F#5 A5:6 | G5:2 F#5 E5:2 C#5 E5:6 '
               '| F#5:2 G5 A5:2 B5 A5:3 F#5:3 | G5:2 B5 D6:2 B5 G5:6 | E5:2 F#5 G5:2 E5 C#5:3 E5:3 | D5:12',
         mel_b='F#5:6 D5:6 | G5:3 B5:3 D5:6 | A5:6 F#5:6 | E5:12 | D5:2 F#5 B5:2 F#5 D5:6 | B4:2 D5 G5:2 D5 B4:6 '
               '| E5:3 G5:3 B5:6 | C#5:12',
         volumes={'grosse': ('basedrum', 0.4), 'charley': ('hat', 0.13)},
         styles={'a': dict(bass='rock', guitar='strum', drums='soft'),
                 'b': dict(bass='long', guitar='strum', drums='soft')}),
    dict(id='ville_cafe', title='Café des Héros', bpm=112, meter='4/4', drone=False, fills=False, flats=True,
         comment='Musique de ville : terrasse de café sur la Place des Héros, fa majeur aux accords de septième, basse qui marche.',
         lead='pling', b_lead='flute', echo='bell', arp='harp',
         prog={'a': 'Fmaj7 Dm7 Gm7 C7 Fmaj7 Dm7 Gm7 C7', 'b': 'Bbmaj7 Bbm Am7 D7 Gm7 C7 Fmaj7 C7'},
         mel_a='A5:3 G5 F5:2 E5:2 F5:4 C5:4 | D5:2 F5:2 A5:2 C6:6 A5:4 | Bb5:3 A5 G5:2 F5:2 D5:8 '
               '| E5:4 G5:4 Bb5:4 A5:2 G5:2 | A5:3 G5 F5:2 E5:2 F5:4 A5:4 | C6:4 D6:2 C6:2 A5:8 '
               '| G5:2 A5:2 Bb5:2 D6:2 C6:4 Bb5:4 | G5:4 E5:4 C5:8',
         mel_b='D6:4 C6:4 A5:8 | Db6:4 C6:4 Bb5:8 | C6:4 A5:4 E5:8 | F#5:4 A5:4 C6:8 | Bb5:4 A5:4 G5:8 '
               '| E5:4 G5:4 Bb5:8 | A5:16 | G5:8 E5:8',
         volumes={'grosse': ('basedrum', 0.35), 'charley': ('hat', 0.13), 'arpege': ('harp', 0.22)},
         styles={'a': dict(bass='walk', guitar='off', drums='soft'),
                 'b': dict(bass='walk', guitar='off', drums='soft')}),
    dict(id='ville_guinguette', title='La Guinguette', bpm=132, meter='4/4', drone=False, fills=False,
         comment='Musique de ville : fête populaire où tout le monde se retrouve, do majeur, air à fredonner, mains qui battent la mesure.',
         lead='flute', b_lead='banjo', echo='pling', arp='pling',
         prog={'a': 'C F C G C F G C', 'b': 'Am F C G F C G G'},
         mel_a='G5:2 E5:2 G5:2 C6:2 E6:4 C6:4 | A5:2 C6:2 F6:4 E6:2 D6:2 C6:4 | G5:2 E5:2 G5:2 C6:2 D6:2 E6:2 C6:4 '
               '| D6:4 B5:4 G5:8 | E6:2 E6:2 D6:2 C6:2 G5:4 C6:4 | F6:2 F6:2 E6:2 D6:2 A5:4 D6:4 '
               '| C6:2 B5:2 A5:2 B5:2 D6:4 B5:4 | C6:12 .:4',
         mel_b='E5:2 A5:2 C6:4 B5:2 A5:2 E5:4 | F5:2 A5:2 C6:4 A5:8 | G5:2 E5:2 C5:4 E5:2 G5:2 C6:4 | B5:4 G5:4 D5:8 '
               '| A5:4 C6:4 F5:8 | E5:4 G5:4 C6:8 | D6:4 B5:4 G5:4 B5:4 | D6:16',
         volumes={'grosse': ('basedrum', 0.45), 'caisse': ('snare', 0.25), 'charley': ('hat', 0.12)},
         styles={'a': dict(bass='pump', guitar='off', drums='clap'),
                 'b': dict(bass='pump', guitar='off', drums='clap')}),
]


# Calm songs for the spawn: slow, no drums, major keys, welcoming.
CALM = {'guitare': ('guitar', 0.4), 'basse': ('bass', 0.6), 'arpege': ('harp', 0.25), 'echo': ('chime', 0.2)}
SPAWN = [
    dict(id='spawn_matin', title='Premier Matin', bpm=72, meter='4/4', drone=False, fills=False,
         comment='Spawn : premier matin à Utopia, do lydien (ré majeur sur do), harpe et flûte, sans batterie.',
         lead='harp', b_lead='flute', echo='chime', arp='harp',
         prog={'a': 'C D C D Am F G G', 'b': 'F G Em Am F G C C'},
         mel_a='E5:8 G5:4 C6:4 | F#5:8 A5:4 D6:4 | E6:8 D6:4 C6:4 | A5:16 | C6:8 B5:4 A5:4 | A5:8 G5:4 F5:4 '
               '| G5:4 B5:4 D6:8 | G5:16',
         mel_b='A5:8 C6:8 | B5:8 D6:8 | G5:8 B5:8 | A5:16 | C6:4 A5:4 F5:8 | D6:4 B5:4 G5:8 | E5:8 G5:8 | C6:16',
         volumes=CALM,
         styles={'a': dict(bass='long', guitar='arp', drums='none'),
                 'b': dict(bass='long', guitar='arp', drums='none')}),
    dict(id='spawn_brise', title='Brise sur Resaac', bpm=66, meter='12/8', drone=False, fills=False,
         comment='Spawn : une brise qui balance doucement, sol majeur à 12/8, flûte puis harpe.',
         lead='flute', b_lead='harp', echo='chime', arp='harp',
         prog={'a': 'G Em C D G Em C G', 'b': 'C D Bm Em C D G G'},
         mel_a='D5:3 G5:3 B5:6 | A5:3 G5:3 E5:6 | G5:3 E5:3 C5:6 | D5:3 F#5:3 A5:6 | B5:3 D6:3 B5:6 '
               '| A5:3 G5:3 E5:6 | E5:3 G5:3 C6:6 | B5:12',
         mel_b='E5:6 G5:6 | F#5:6 A5:6 | D5:6 F#5:6 | E5:12 | G5:3 E5:3 C5:6 | A5:3 F#5:3 D5:6 | B4:6 D5:6 | G5:12',
         volumes=CALM,
         styles={'a': dict(bass='long', guitar='arp', drums='none'),
                 'b': dict(bass='long', guitar='held', drums='none')}),
    dict(id='spawn_etoiles', title='Ciel Étoilé', bpm=64, meter='4/4', drone=False, fills=False,
         comment='Spawn : ciel étoilé, ré majeur, carillon qui scintille sur des arpèges légers.',
         lead='chime', b_lead='harp', echo='bell', arp='pling', arp_always=True,
         prog={'a': 'D A Bm G D A G A', 'b': 'G D Em A G D G A'},
         mel_a='F#6:8 A6:8 | E6:8 C#6:8 | D6:8 F#6:8 | B5:16 | A6:4 F#6:4 D6:8 | C#6:4 E6:4 A6:8 | G6:4 F#6:4 D6:8 '
               '| E6:16',
         mel_b='B4:8 D5:8 | A4:8 F#4:8 | G4:8 B4:8 | C#5:16 | D5:4 B4:4 G4:8 | F#4:4 A4:4 D5:8 | B4:8 G4:8 | A4:16',
         volumes={**CALM, 'lead': ('chime', 0.55), 'echo': ('bell', 0.15), 'arpege': ('pling', 0.2)},
         styles={'a': dict(bass='long', guitar='held', drums='none'),
                 'b': dict(bass='long', guitar='arp', drums='none')}),
    dict(id='spawn_jardin', title='Le Jardin du Sénat', bpm=96, meter='3/4', drone=False, fills=False, flats=True,
         comment='Spawn : promenade dans les jardins du Sénat, valse lente en fa majeur, flûte et harpe.',
         lead='flute', b_lead='harp', echo='chime', arp='harp',
         prog={'a': 'F F Bb Bb F F C C F F Bb Bb F C F F',
               'b': 'Dm Dm Bb Bb F F C C Dm Dm Bb Bb Gm C F F'},
         mel_a='A5:4 C6:2 | F5:6 | D6:4 C6:2 | Bb5:6 | A5:2 C6:2 F6:2 | E6:4 C6:2 | G5:4 Bb5:2 | G5:6 | A5:4 C6:2 '
               '| F6:6 | D6:2 F6:2 D6:2 | Bb5:6 | A5:4 F5:2 | G5:2 E5:2 G5:2 | F5:6 | .:6',
         mel_b='F5:4 A5:2 | D5:6 | F5:4 D5:2 | Bb4:6 | C5:2 F5:2 A5:2 | A5:6 | G5:4 E5:2 | C5:6 | D5:2 F5:2 A5:2 '
               '| A5:6 | F5:4 D5:2 | Bb4:6 | G5:4 Bb5:2 | G5:2 E5:2 C5:2 | F5:6 | C5:6',
         volumes={**CALM, 'charley': ('hat', 0.1)},
         styles={'a': dict(bass='waltz', guitar='waltz', drums='none'),
                 'b': dict(bass='waltz', guitar='waltz', drums='none')}),
    dict(id='spawn_fontaine', title='La Fontaine', bpm=80, meter='4/4', drone=False, fills=False,
         comment="Spawn : au bord de la fontaine, la majeur, arpèges de harpe comme l'eau qui coule.",
         lead='pling', b_lead='flute', echo='chime', arp='harp', arp_always=True,
         prog={'a': 'A F#m D E A F#m D A', 'b': 'D E C#m F#m D E A A'},
         mel_a='C#5:4 E5:4 A5:8 | F#5:4 E5:4 C#5:8 | D5:4 F#5:4 A5:4 F#5:4 | E5:16 | A5:4 G#5:4 F#5:4 E5:4 '
               '| C#5:8 E5:8 | F#5:4 E5:4 D5:4 B4:4 | A4:16',
         mel_b='F#5:8 A5:8 | G#5:8 B5:8 | E5:8 G#5:8 | A5:16 | D6:4 C#6:4 B5:8 | B5:4 A5:4 G#5:8 | A5:8 E5:8 | C#6:16',
         volumes={**CALM, 'lead': ('pling', 0.6), 'arpege': ('harp', 0.3)},
         styles={'a': dict(bass='long', guitar='held', drums='none'),
                 'b': dict(bass='long', guitar='arp', drums='none')}),
]


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                                             '..', 'src', 'main', 'resources', 'songs')
    for spec in SONGS + [TOWN] + TOWNS + SPAWN:
        text = yaml(*build(spec))
        with open(os.path.join(out, spec['id'] + '.yml'), 'w', encoding='utf-8') as f:
            f.write(text)
        print('written', spec['id'])


if __name__ == '__main__':
    main()

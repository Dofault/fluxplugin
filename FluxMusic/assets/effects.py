#!/usr/bin/env python3
"""FluxMusic's effects given to any song score (YAML), the way they were tuned on RPG 1 (2026-11-19):

- chorus on the bass (12 cents) and the didgeridoo (8 cents): thick lows;
- the voices beside the tune (quieter than it) placed a little left and right (fixed stereo, never an orbit), at most four of them,
  and only those heard less than 40 % of the song: a track panned all along sounds as if it came from one side only;
- the tune's long single notes held, wavering (~): 1.5 beat or more;
- a glide (>) into such a held note when the note just before is short and leaps up to it by a fourth to an octave.

A song that already carries any effect (a track with chorus or pan, a note with ~ or >) keeps them: they were placed
by hand or by its own generator (suite.py with effects=True, gen_rpg.py). Only the stereo of a track heard most of the
song is taken off, whoever placed it.

Used by score.write_song and gen_songs.py on every song they write, and on its own for the songs written by hand:

    python3 effects.py <song.yml> [...]      (in place; prints what it did)
"""
import math
import re
import sys

NOTE = re.compile(r'^([A-G])([#b]?)(-?\d)$')
LETTER = {'C': 0, 'D': 2, 'E': 4, 'F': 5, 'G': 7, 'A': 9, 'B': 11}
TRACK = re.compile(r'^(  )([A-Za-z0-9_]+)(:\s*)\{([^}]*)\}\s*$')
LINE = re.compile(r'^(\s+)([A-Za-z0-9_]+)(:\s*)"(.*)"\s*$')

CHORUS = {'bass': 12, 'didgeridoo': 8}
PERCUSSION = {'basedrum', 'snare', 'hat'}
LOWS = {'bass', 'didgeridoo'}
PANS = (-0.5, 0.4, -0.3, 0.3)
LEAD_NAMES = ('lead', 'theme', 'melodie', 'melody', 'chant', 'air', 'flute')
LEAP = (5, 12)


def midi(note):
    m = NOTE.match(note)
    if not m:
        return None
    return (int(m.group(3)) + 1) * 12 + LETTER[m.group(1)] + (1 if m.group(2) == '#' else -1 if m.group(2) == 'b' else 0)


def options(body):
    """'instrument: harp, volume: 0.8' -> {'instrument': 'harp', 'volume': '0.8'}"""
    out = {}
    for part in body.split(','):
        if ':' in part:
            k, v = part.split(':', 1)
            out[k.strip()] = v.strip()
    return out


def mark(line, spb):
    """Held notes and glides on one line of the tune; returns the new line and how many marks were placed."""
    held, short = math.ceil(1.5 * spb), spb
    tokens = line.split(' ')
    placed = 0

    def single(tok):
        body, _, d = tok.partition(':')
        if not body or '+' in body or '>' in body or body.endswith('~') or midi(body) is None:
            return None
        try:
            return body, int(d) if d else 1
        except ValueError:
            return None
    prev = None   # index of the previous note token (rests and bar lines in between break the link)
    for i, tok in enumerate(tokens):
        if tok in ('', '|'):
            continue
        note = single(tok)
        if note is None:
            prev = None
            continue
        body, d = note
        if d >= held:
            tokens[i] = f'{body}~:{d}'
            placed += 1
            if prev is not None:
                pbody, pd = single(tokens[prev]) or (None, 0)
                if pbody and pd <= short and LEAP[0] <= midi(body) - midi(pbody) <= LEAP[1]:
                    tokens[prev] = f'{pbody}>{body}' + (f':{pd}' if ':' in tokens[prev] else '')
                    placed += 1
            prev = None
        else:
            prev = i
    return ' '.join(tokens), placed


REST = {'.', '-', '|', ''}
#: A track heard during this share of the song or more stays in the middle: panned all along, it sounds as if it came
#: from one side only (owner, 2026-11-20: the guitar of the boss song). Stereo is for voices that come and go.
CENTRED_FROM = 0.4


def _expand(line, patterns, depth=0):
    """Tokens of a line, patterns written out (@name, @name*n, @name:d for a one-token pattern)."""
    out = []
    for tok in line.split():
        m = re.match(r'^@([A-Za-z0-9_]+)(?:\*(\d+))?(:\d+)?$', tok)
        if m and depth < 8:
            body = _expand(patterns.get(m.group(1), ''), patterns, depth + 1)
            if m.group(3) and len([t for t in body if t != '|']) == 1:
                body = [t.split(':')[0] + m.group(3) for t in body if t != '|']
            out += body * int(m.group(2) or 1)
        else:
            out.append(tok)
    return out


def _steps(tok):
    if tok == '|':
        return 0
    _, _, d = tok.partition(':')
    try:
        return int(d) if d else 1
    except ValueError:
        return 1


def presence(text):
    """Share of the song (in steps, sections counted as often as the order plays them) each track is heard in."""
    import yaml
    d = yaml.safe_load(text) or {}
    sections, patterns = d.get('sections') or {}, d.get('patterns') or {}
    order = d.get('order') or list(sections)
    heard, total = {}, 0
    for name in order:
        sec = sections.get(name) or {}
        lines = {t: _expand(str(v), patterns) for t, v in sec.items()}
        length = max((sum(_steps(t) for t in toks) for toks in lines.values()), default=0)
        total += length
        for t, toks in lines.items():
            if any(tok.split(':')[0] not in REST for tok in toks):
                heard[t] = heard.get(t, 0) + length
    return {t: n / total for t, n in heard.items()} if total else {}


def centre(text):
    """Takes the pan off the tracks heard most of the song; returns the text and the tracks put back in the middle."""
    share = presence(text)
    lines, moved = text.split('\n'), []
    block = None
    for i, l in enumerate(lines):
        if re.match(r'^[A-Za-z]', l):
            block = l.split(':')[0]
        m = TRACK.match(l) if block == 'tracks' else None
        if m and re.search(r'\bpan\s*:', m.group(4)) and share.get(m.group(2), 0) >= CENTRED_FROM:
            body = re.sub(r',?\s*pan\s*:\s*-?[0-9.]+', '', m.group(4)).strip().strip(',').strip()
            lines[i] = f'{m.group(1)}{m.group(2)}{m.group(3)}{{ {body} }}'
            moved.append(f'{m.group(2)} ({share[m.group(2)]:.0%})')
    return '\n'.join(lines), moved


def apply(text):
    """The song's text with the effects, and a short report (None when nothing changed). A song that placed its own
    effects keeps them, except the stereo of a track heard most of the song (see {@link CENTRED_FROM})."""
    report = None
    if not (re.search(r'(chorus|pan)\s*:', text) or re.search(r'[A-G][#b]?-?\d(~|>)', text)):
        text, report = _add(text)
    text, moved = centre(text)
    if moved:
        report = (report + '; ' if report else '') + 'centred: ' + ', '.join(moved)
    return text, report


def _add(text):
    lines = text.split('\n')
    spb = int(re.search(r'^steps-per-beat:\s*(\d+)', text, re.M).group(1)) if 'steps-per-beat' in text else 4
    tracks, where = {}, {}
    block = None
    for i, l in enumerate(lines):
        if re.match(r'^[A-Za-z]', l):
            block = l.split(':')[0]
        m = TRACK.match(l) if block == 'tracks' else None
        if m:
            o = options(m.group(4))
            tracks[m.group(2)] = (o.get('instrument', 'harp').lower(), float(o.get('volume', 1)))
            where[m.group(2)] = i
    melodic = [t for t, (inst, _) in tracks.items() if inst not in PERCUSSION | LOWS]
    if not melodic:
        return text, None
    named = [t for t in melodic if t.lower() in LEAD_NAMES]
    lead = named[0] if named else max(melodic, key=lambda t: (tracks[t][0] != 'guitar', tracks[t][1]))
    extra = {}
    for t, (inst, _) in tracks.items():
        if inst in CHORUS:
            extra[t] = f'chorus: {CHORUS[inst]}'
    # beside the tune = quieter than it: a louder track is a tune of its own and stays in the middle
    share = presence(text)
    beside = sorted((t for t in melodic if t != lead and 0.15 <= tracks[t][1] < tracks[lead][1]
                     and share.get(t, 0) < CENTRED_FROM),
                    key=lambda t: -tracks[t][1])
    for t, p in zip(beside, PANS):
        extra[t] = f'pan: {p}'
    for t, add in extra.items():
        i = where[t]
        m = TRACK.match(lines[i])
        lines[i] = f'{m.group(1)}{m.group(2)}{m.group(3)}{{ {m.group(4).strip()}, {add} }}'
    # the tune: its lines in the sections, and the patterns only it uses
    uses = {}
    block = None
    for l in lines:
        if re.match(r'^[A-Za-z]', l):
            block = l.split(':')[0]
        m = LINE.match(l)
        if m and block == 'sections':
            for ref in re.findall(r'@([A-Za-z0-9_]+)', m.group(4)):
                uses.setdefault(ref, set()).add(m.group(2))
    own = {p for p, who in uses.items() if who == {lead}}
    marks = 0
    block = None
    for i, l in enumerate(lines):
        if re.match(r'^[A-Za-z]', l):
            block = l.split(':')[0]
        m = LINE.match(l)
        if not m:
            continue
        if (block == 'sections' and m.group(2) == lead) or (block == 'patterns' and m.group(2) in own):
            new, n = mark(m.group(4), spb)
            if n:
                lines[i] = f'{m.group(1)}{m.group(2)}{m.group(3)}"{new}"'
                marks += n
    report = f'tune {lead}: {marks} mark(s); ' + ', '.join(f'{t} {v}' for t, v in extra.items())
    return '\n'.join(lines), report


def main(paths):
    for p in paths:
        with open(p, encoding='utf-8') as f:
            text = f.read()
        new, report = apply(text)
        if report is None:
            print(f'{p}: nothing to change')
            continue
        with open(p, 'w', encoding='utf-8') as f:
            f.write(new)
        print(f'{p}: {report}')


if __name__ == '__main__':
    main(sys.argv[1:])

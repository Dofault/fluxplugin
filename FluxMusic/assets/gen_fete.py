#!/usr/bin/env python3
"""Generates the songs of the music pets (fête de la musique, 21 June): joyful, major keys, all original.

Every song uses the same band of ten pets, one instrument (one track role) each, so any song of the playlist can be
played by the pets standing together, each pet playing its own track:

    flute           lead      the tune                 (Flûte)
    bell            lead2     the tune an octave up    (Cloche)
    xylophone       counter   answers between phrases  (Xylophone)
    iron_xylophone  harmony   second voice             (Vibraphone)
    harp            arp       broken chords            (Harpe)
    guitar          comp      strummed chords          (Guitare)
    banjo           pick      picked eighths           (Banjo)
    bass            bass      bass line                (Contrebasse)
    pling           sparkle   chord sparkles           (Pling)
    basedrum, snare, hat      kit                      (Batterie)

    fete_place      Place à la Musique       C major, town groove, up to D major for the last theme
    fete_lampions   Le Bal des Lampions      F major waltz
    fete_ronde      La Ronde des Familiers   G major jig at 6/8
    fete_soleil     Soleil de Juin           D major shuffle
    fete_choeur     Tous en Chœur            Bb major march, up to C major for the last theme

Each song ends on a held chord (played once in a shuffled list). Every track plays in every song (checked below).

    python3 gen_fete.py [output folder]      (default: ../src/main/resources/songs)

Never edit the produced songs/fete_*.yml by hand: change this script and run it again.
"""
import sys

import score
from suite import Song

SCRIPT = 'gen_fete.py'
BAND = {'lead': 'flute', 'lead2': 'bell', 'counter': 'xylophone', 'harmony': 'iron_xylophone', 'arp': 'harp',
        'comp': 'guitar', 'pick': 'banjo', 'bass': 'bass', 'sparkle': 'pling'}
VOLUMES = {'lead': 0.68, 'lead2': 0.18, 'counter': 0.4, 'harmony': 0.24, 'arp': 0.18, 'comp': 0.22, 'pick': 0.18,
           'bass': 0.62, 'sparkle': 0.13, 'kick': 0.36, 'snare': 0.2, 'roll': 0.14, 'hat': 0.1}
EVERY_TRACK = set(BAND) | {'kick', 'snare', 'hat'}


def song(song_id, title, bpm, meter, groove, comment):
    return Song(song_id, title, bpm, meter, groove, dict(BAND), VOLUMES, effects=True,
                comment=comment + ' Composition originale, jouée par les familiers instruments.')


def place():
    s = song('fete_place', 'Place à la Musique', 132, '4/4', 'town',
             "Place à la Musique : ouverture de la fête, do majeur entraînant, monte en ré majeur pour la fin.")
    A = '''
        C      | E5:4 G5:4 C6:6 G5:2
        F      | A5:4 C6:4 F5:4 A5:4
        G7     | B5:2 C6:2 D6:4 G5:4 F5:4
        C      | E5:12 .:4                  | .:12 G5:2 C6:2
        Am     | C6:4 A5:4 E5:4 A5:4
        F,G7   | F5:4 A5:4 G5:4 B5:4
        Dm7,G7 | A5:4 F5:4 D5:4 F5:4
        C      | E5:4 D5:2 E5:2 C5:8        | .:8 E6:2 D6:2 C6:2 G5:2
    '''
    B = '''
        F      | A5:6 G5:2 F5:4 A5:4
        C      | G5:6 E5:2 C5:4 E5:4
        Dm7    | F5:4 A5:4 C6:4 D6:4
        G7     | B5:8 .:8                   | .:8 D6:2 C6:2 B5:2 G5:2
        Em7    | G5:4 B5:4 E6:4 D6:4
        A7     | C#6:4 A5:4 E5:4 G5:4
        Dm7,G7 | D6:4 C6:4 B5:4 G5:4
        G7     | D6:6 C6:2 B5:4 G5:4
    '''
    s.intro('C | - \n G7 | -', 1)
    s.section('a1', A, 1)
    s.section('a2', A, 2)
    s.section('b1', B, 1)
    s.section('a3', A, 2)
    s.section('pont', 'A7 | E5:4 G5:4 A5:4 C#6:4', 2, double=False)
    s.section('a4', A, 2, transpose=2)
    s.end('C', 'C5', transpose=2)
    return s


def lampions():
    s = song('fete_lampions', 'Le Bal des Lampions', 168, '3/4', 'waltz',
             "Le Bal des Lampions : valse de bal populaire sous les guirlandes, fa majeur.")
    A = '''
        F    | C5:4 F5:4 A5:4
        F    | C6:8 A5:4
        C7   | G5:4 Bb5:4 G5:4
        C7   | E5:8 C5:4
        C7   | G5:4 Bb5:4 C6:4
        C7   | E6:8 D6:2 C6:2
        F    | A5:4 C6:4 A5:4
        F    | F5:8 .:4         | .:8 C6:2 A5:2
    '''
    B = '''
        Bb   | D6:4 C6:4 Bb5:4
        Bb   | F5:8 Bb5:4
        F    | C6:4 A5:4 F5:4
        F    | C5:8 F5:4         | .:8 A5:2 C6:2
        G7   | B5:4 D6:4 B5:4
        C7   | Bb5:8 G5:4
        C7   | E5:4 G5:4 Bb5:4
        F    | A5:8 .:4         | .:8 G5:2 Bb5:2
    '''
    s.intro('F | - \n C7 | - \n F | - \n C7 | -', 0)
    s.section('a1', A, 1)
    s.section('b1', B, 1)
    s.section('a2', A, 2)
    s.section('b2', B, 2)
    s.section('a3', A, 1)
    s.section('b3', B, 2)
    s.section('a4', A, 2)
    s.end('F', 'F5')
    return s


def ronde():
    s = song('fete_ronde', 'La Ronde des Familiers', 104, '6/8', 'jig',
             "La Ronde des Familiers : gigue en sol majeur, on danse en rond autour des musiciens.")
    A = '''
        G      | B5:4 G5:2 D5:4 G5:2
        C      | E5:4 G5:2 C6:6
        D7     | A5:4 F#5:2 D5:4 F#5:2
        G      | G5:6 .:6               | .:6 D6:2 B5:2 G5:2
        Em     | G5:4 E5:2 B5:4 G5:2
        Am     | A5:4 C6:2 E6:4 C6:2
        D7     | D6:4 C6:2 A5:4 F#5:2
        G      | G5:6 .:6               | .:6 B5:2 C6:2 D6:2
    '''
    B = '''
        C      | E6:4 D6:2 C6:4 G5:2
        G      | D6:4 B5:2 G5:4 B5:2
        Am     | C6:4 A5:2 E5:4 A5:2
        D7     | F#5:6 .:6              | .:6 A5:2 C6:2 D6:2
        C      | G5:4 C6:2 E6:4 C6:2
        G      | D6:4 B5:2 G5:4 D5:2
        A7,D7  | C#6:4 A5:2 C6:4 A5:2
        G      | G5:6 .:6               | .:6 D6:2 E6:2 D6:2
    '''
    s.intro('G | - \n D7 | -', 1)
    s.section('a1', A, 1)
    s.section('a2', A, 2)
    s.section('b1', B, 1)
    s.section('b2', B, 2)
    s.section('a3', A, 1)
    s.section('b3', B, 2)
    s.section('a4', A, 2)
    s.end('G', 'G5')
    return s


def soleil():
    s = song('fete_soleil', 'Soleil de Juin', 108, 'shuffle', 'shuffle',
             "Soleil de Juin : balade chaloupée en ré majeur, l'après-midi au soleil.")
    A = '''
        D      | F#5:3 A5:3 D6:6
        G      | B5:2 A5:1 G5:3 D5:6
        A7     | C#6:3 E6:3 A5:6
        D      | F#5:9 .:3              | .:6 A5:2 B5:1 D6:3
        Bm     | D6:3 B5:3 F#5:6
        G      | G5:2 A5:1 B5:3 D6:6
        Em7,A7 | E5:3 G5:3 A5:3 C#6:3
        D      | D6:6 .:6               | .:6 F#6:2 E6:1 D6:3
    '''
    B = '''
        G      | B5:3 D6:3 G5:6
        D      | A5:3 F#5:3 D5:6
        Em     | G5:2 F#5:1 E5:3 B5:6
        A7     | A5:9 .:3               | .:6 C#6:2 D6:1 E6:3
        G      | B5:3 G5:3 D6:6
        F#m    | C#6:3 A5:3 F#5:6
        Em7,A7 | G5:3 E5:3 E5:3 C#6:3
        A7     | A5:6 .:6               | .:6 E6:2 C#6:1 A5:3
    '''
    s.intro('D | - \n A7 | -', 0)
    s.section('a1', A, 1)
    s.section('b1', B, 1)
    s.section('a2', A, 2)
    s.section('b2', B, 2)
    s.section('a3', A, 2)
    s.end('D', 'D5')
    return s


def choeur():
    s = song('fete_choeur', 'Tous en Chœur', 120, '4/4', 'march',
             "Tous en Chœur : grande marche de fanfare en si bémol majeur, monte en do majeur pour le final.")
    A = '''
        Bb     | F5:4 Bb5:4 D6:6 Bb5:2
        Eb     | G5:4 Bb5:4 Eb6:4 G5:4
        F7     | A5:2 Bb5:2 C6:4 F5:4 A5:4
        Bb     | Bb5:12 .:4                | .:12 D6:2 F6:2
        Gm     | G5:4 Bb5:4 D6:4 Bb5:4
        Eb,F7  | Eb6:4 D6:4 C6:4 A5:4
        Cm7,F7 | Bb5:4 G5:4 A5:4 C6:4
        Bb     | Bb5:8 .:8                 | .:8 F6:2 Eb6:2 D6:2 C6:2
    '''
    B = '''
        Eb     | G5:6 Bb5:2 Eb6:8
        Bb     | D6:6 C6:2 Bb5:8
        Cm7    | C6:4 Eb6:4 G5:4 Bb5:4
        F7     | A5:8 .:8                  | .:8 C6:2 D6:2 Eb6:2 F6:2
        Eb     | Bb5:4 G5:4 Eb6:4 G5:4
        D7     | F#5:4 A5:4 D6:4 C6:4
        Gm,C7  | Bb5:4 G5:4 E5:4 G5:4
        F7     | C6:6 Bb5:2 A5:4 F5:4
    '''
    s.intro('Bb | - \n F7 | -', 1)
    s.section('a1', A, 1)
    s.section('a2', A, 2)
    s.section('b1', B, 1)
    s.section('b2', B, 2)
    s.section('pont', 'G7 | D6:4 B5:4 G5:4 F5:4', 2, double=False)
    s.section('a3', A, 2, transpose=2)
    s.end('Bb', 'Bb5', transpose=2)
    return s


def main():
    folder = score.songs_folder(sys.argv, __file__)
    total = 0
    for make in (place, lampions, ronde, soleil, choeur):
        s = make()
        total += s.build(folder, SCRIPT)
        played = {t for sec in s.sections for t in sec.tracks}
        missing = EVERY_TRACK - played
        if missing:
            print(f'  MISSING {s.piece.id}: {", ".join(sorted(missing))} never plays')
            total += 1
    print(f'{total} remark(s) in all')


if __name__ == '__main__':
    main()

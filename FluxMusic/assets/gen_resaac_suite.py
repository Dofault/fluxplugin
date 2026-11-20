#!/usr/bin/env python3
"""Generates the ambient songs of Resaac (world resaac), shuffled with the town's two themes (resaac, resaac2).
Handheld monster-catching RPG town spirit: major keys, the square-wave "bit" note block on the tune, a bass bouncing
in octaves, chords borrowed from the jazz of those soundtracks (II7, III7, the borrowed minor iv, ii-V turnarounds)
and a key change before the last theme. All original, no quote of any existing piece. Writer in suite.py.

    resaac_bonjour     Bonjour, Resaac             C major, bouncing town groove, up to D major
    resaac_fleurs      Le Marché aux Fleurs        F major stroll, flute, borrowed Bbm
    resaac_route       La Route des Platanes       G major route theme, 144, up to A major
    resaac_the         Le Salon de Thé             Eb major shuffle, the minor iv (Abm)
    resaac_toits       Les Toits de Resaac         A major waltz under the stars
    resaac_gare        La Gare                     D major shuffle, trains coming and going
    resaac_parc        Le Parc                     E major at 6/8, a walk in the park
    resaac_academie    L'Académie                  Bb major, ii-V turnarounds, a little studious
    resaac_fete        Jour de Fête                G major at 150, up to A major
    resaac_quais       Crépuscule sur les Quais    D major, major sevenths, warm evening

    python3 gen_resaac_suite.py [output folder]      (default: ../src/main/resources/songs)

Never edit the produced songs/resaac_*.yml by hand: change this script and run it again.
"""
import sys

import score
from suite import Song

SCRIPT = 'gen_resaac_suite.py'
BASE = {'lead': 'bit', 'lead2': 'flute', 'harmony': 'pling', 'counter': 'flute', 'arp': 'harp', 'comp': 'guitar',
        'bass': 'bass'}
SOFT = {'bass': 0.58, 'kick': 0.3, 'snare': 0.16, 'roll': 0.12, 'hat': 0.08, 'lead': 0.62, 'counter': 0.38,
        'lead2': 0.18}


def kit(**changes):
    return {k: v for k, v in dict(BASE, **changes).items() if v}


def bonjour():
    s = Song('resaac_bonjour', 'Bonjour, Resaac', 126, '4/4', 'town', kit(), SOFT,
             effects=True, comment="Bonjour, Resaac : la ville au matin, do majeur sautillant, monte en ré majeur pour la fin. "
                     "Composition originale.")
    A = '''
        C      | G5:6 E5:2 C5:4 E5:4
        F      | F5:2 G5:2 A5:4 C6:6 A5:2
        C,Am   | G5:4 E5:4 C5:4 E5:4
        D7,G7  | F#5:4 A5:4 G5:4 F5:4
        C      | G5:6 E5:2 C6:4 E6:4
        F,Fm   | C6:4 A5:2 F5:2 Ab5:4 C6:4
        C,G7   | E5:4 G5:4 F5:4 D5:4
        C      | C5:8 .:8
    '''
    B = '''
        Am     | A5:4 C6:4 E6:4 D6:2 C6:2
        F      | C6:4 A5:2 F5:2 A5:8         | .:8 F5:2 G5:2 A5:2 C6:2
        Dm7    | F5:4 A5:4 C6:6 A5:2
        G7     | B5:4 G5:2 F5:2 D5:4 B4:4    | .:8 D5:2 E5:2 F5:2 G5:2
        Em7    | B5:4 G5:2 E5:2 G5:4 B5:4
        A7     | C#6:4 A5:2 E5:2 G5:4 A5:4
        Dm7,G7 | A5:4 F5:4 G5:4 B5:4
        G7     | D6:6 C6:2 B5:4 G5:4
    '''
    s.intro('C | - \n G7 | -', 1)
    s.section('a1', A, 1)
    s.section('a2', A, 2)
    s.section('b1', B, 1)
    s.section('a3', A, 1)
    s.section('b2', B, 2)
    s.section('pont', 'A7 | E5:4 G5:4 A5:4 C#6:4', 2, double=False)
    s.section('a4', A, 2, transpose=2)
    s.end('C', 'C5', transpose=2)
    return s


def fleurs():
    s = Song('resaac_fleurs', 'Le Marché aux Fleurs', 108, '4/4', 'stroll',
             kit(lead='flute', lead2='chime', counter='bit'), SOFT,
             effects=True, comment="Le Marché aux Fleurs : promenade en fa majeur, flûte, si bémol mineur emprunté. "
                     "Composition originale.")
    A = '''
        F       | A5:6 G5:2 F5:4 C5:4
        Bb      | D5:4 F5:4 Bb5:6 A5:2
        Gm7,C7  | G5:4 A5:2 Bb5:2 C6:4 E5:4
        F       | F5:12 .:4                  | .:12 A4:2 C5:2
        Dm      | A5:6 G5:2 F5:4 D5:4
        Bb      | F5:4 G5:4 Bb5:6 D6:2
        Gm7,C7  | D6:4 C6:2 Bb5:2 G5:4 E5:4
        F       | F5:8 .:8                   | .:8 C5:2 D5:2 E5:2 G5:2
    '''
    B = '''
        Am      | E5:4 A5:4 C6:4 B5:2 A5:2
        D7      | A5:4 F#5:4 D6:6 C6:2
        Gm7     | Bb5:4 A5:2 G5:2 D5:4 F5:4
        C7      | E5:4 G5:4 Bb5:8
        Am      | C6:6 B5:2 A5:4 E5:4
        D7      | F#5:6 A5:2 C6:4 D6:4
        Bb,Bbm  | D6:4 Bb5:4 Db6:4 Bb5:4
        C7      | C6:4 Bb5:4 G5:4 E5:4
    '''
    s.intro('F | - \n Bb | - \n F | - \n C7 | -', 0)
    s.section('a1', A, 1)
    s.section('b1', B, 1)
    s.section('a2', A, 2)
    s.section('b2', B, 2)
    s.section('a3', A, 2)
    s.end('F', 'F5')
    return s


def route():
    s = Song('resaac_route', 'La Route des Platanes', 144, '4/4', 'town', kit(), SOFT,
             effects=True, comment="La Route des Platanes : thème de route, sol majeur à 144, monte en la majeur. "
                     "Composition originale.")
    A = '''
        G      | B4:2 D5:2 G5:4 D6:4 B5:4
        C      | C6:2 B5:2 A5:2 G5:2 E5:4 G5:4
        D      | F#5:2 G5:2 A5:4 D6:4 C6:2 A5:2
        G      | B5:6 A5:2 G5:8
        Em     | E5:2 G5:2 B5:4 E6:4 D6:2 B5:2
        C      | E6:2 D6:2 C6:2 B5:2 C6:4 E6:4
        A7,D7  | C#6:4 A5:4 C6:4 F#5:4
        G      | G5:8 .:4 D5:4
    '''
    B = '''
        C      | E5:4 G5:4 C6:6 B5:2
        Bm     | D6:4 B5:4 F#5:8             | .:8 B4:2 C#5:2 D5:2 F#5:2
        Am7    | E5:4 A5:4 C6:4 G5:4
        D7     | F#5:4 A5:4 D6:8
        C      | G5:4 C6:4 E6:6 D6:2
        B7     | D#6:6 B5:2 F#5:4 A5:4
        Em,A7  | G5:4 B5:4 A5:4 C#6:4
        D7     | D6:4 C6:4 A5:4 F#5:4
    '''
    s.intro('G | - \n D7 | -', 1)
    s.section('a1', A, 1)
    s.section('b1', B, 1)
    s.section('a2', A, 2)
    s.section('b2', B, 2)
    s.section('pont', 'E7 | G#5:4 B5:4 D6:4 E6:4', 2, double=False)
    s.section('a3', A, 2, transpose=2)
    s.end('G', 'G5', transpose=2)
    return s


def the():
    s = Song('resaac_the', 'Le Salon de Thé', 96, 'shuffle', 'shuffle', kit(lead2='bell'), SOFT,
             effects=True, comment="Le Salon de Thé : mi bémol majeur en rythme ternaire, accords de septième, la bémol mineur "
                     "emprunté. Composition originale.")
    A = '''
        Eb       | G5:2 F5:1 Eb5:3 Bb5:6
        Cm7      | C6:3 Bb5:2 G5:1 Eb5:6
        Fm7,Bb7  | Ab5:3 G5:2 F5:1 D5:3 F5:3
        Eb       | Eb5:9 .:3                    | .:6 G5:2 Bb5:1 Eb6:3
        Ab       | C6:2 Bb5:1 Ab5:3 Eb5:3 Ab5:3
        Gm7,C7   | Bb5:2 A5:1 G5:3 E5:3 G5:3
        Fm7,Bb7  | F5:3 Ab5:3 Bb5:3 Ab5:3
        Eb       | G5:6 Eb5:3 .:3                | .:9 Bb5:3
    '''
    B = '''
        Ab       | Eb6:3 C6:2 Ab5:1 Eb5:6
        Abm      | Eb6:3 B5:2 Ab5:1 Eb5:6
        Gm7      | D6:3 Bb5:2 G5:1 F5:3 D5:3
        C7       | E5:3 G5:3 Bb5:3 C6:3
        Fm7      | Ab5:2 G5:1 F5:3 C6:6
        Bb7      | D6:2 C6:1 Bb5:3 F5:3 Ab5:3
        Eb,C7    | G5:3 Bb5:3 C6:3 Bb5:3
        Fm7,Bb7  | Ab5:3 C6:3 D6:3 Bb5:3
    '''
    s.intro('Eb | - \n Bb7 | -', 0)
    s.section('a1', A, 1)
    s.section('b1', B, 1)
    s.section('a2', A, 2)
    s.section('b2', B, 2)
    s.section('a3', A, 2)
    s.end('Eb', 'Eb5')
    return s


def toits():
    s = Song('resaac_toits', 'Les Toits de Resaac', 144, '3/4', 'waltz',
             kit(lead='flute', lead2='chime', counter='bit', arp='pling', harmony='harp'), SOFT,
             effects=True, comment="Les Toits de Resaac : valse sous les étoiles, la majeur, ré mineur emprunté. "
                     "Composition originale.")
    A = '''
        A     | E5:4 A5:4 C#6:4
        A     | E6:8 C#6:4
        D     | F#5:4 A5:4 D6:4
        D     | F#6:8 D6:4
        E     | E6:4 D6:2 B5:2 G#5:4
        E7    | B5:8 D6:4
        A     | C#6:4 B5:4 A5:4
        F#m   | A5:12
        D     | F#5:4 A5:4 D6:4
        Bm    | D6:8 F#5:4
        C#m   | E6:8 C#6:4
        F#m   | C#6:12
        Bm7   | B5:4 D6:4 F#6:4
        E7    | E6:8 D6:4
        A     | C#6:4 E6:4 A5:4
        E7    | G#5:8 B5:4
    '''
    B = '''
        F#m   | A5:8 C#6:4
        F#m   | F#5:12
        D     | A5:4 D6:4 F#6:4
        D     | F#6:8 E6:4
        Bm    | D6:8 B5:4
        Bm    | F#5:12
        E     | G#5:4 B5:4 E6:4
        E7    | D6:12
        C#m   | E6:8 C#6:4
        F#m   | C#6:8 A5:4
        D     | D6:4 C#6:4 B5:4
        Bm    | B5:12
        Dm    | F5:4 A5:4 D6:4
        Dm    | D6:8 A5:4
        E     | B5:4 G#5:4 E5:4
        E7    | E5:8 G#5:4
    '''
    s.intro('A | - \n D | - \n A | - \n E | -', 0)
    s.section('a1', A, 1)
    s.section('b1', B, 1)
    s.section('a2', A, 2)
    s.section('b2', B, 2)
    s.end('A', 'A5')
    return s


def gare():
    s = Song('resaac_gare', 'La Gare', 120, 'shuffle', 'shuffle', kit(), SOFT,
             effects=True, comment="La Gare : ré majeur en rythme ternaire, trains qui vont et viennent, accord de III7. "
                     "Composition originale.")
    A = '''
        D       | F#5:2 A5:1 D6:3 A5:2 F#5:1 D5:3
        G       | G5:2 B5:1 D6:3 B5:2 G5:1 D5:3
        Em7,A7  | E5:2 G5:1 B5:3 C#6:2 B5:1 A5:3
        D       | F#5:6 .:3 A4:3
        Bm      | B5:2 A5:1 F#5:3 D6:3 B5:3
        G,Gm    | B5:3 G5:3 Bb5:3 G5:3
        D,A7    | A5:2 F#5:1 D5:3 E5:2 G5:1 A5:3
        D       | D6:6 .:6                      | .:6 A5:2 F#5:1 D5:3
    '''
    B = '''
        F#7     | A#5:3 C#6:3 E6:3 C#6:3
        Bm      | D6:6 B5:3 F#5:3
        E7      | G#5:3 B5:3 D6:3 E6:3
        A7      | C#6:6 A5:3 G5:3
        G       | B5:2 A5:1 G5:3 D5:3 G5:3
        F#m     | A5:2 G#5:1 F#5:3 C#5:3 F#5:3
        Em7,A7  | G5:3 B5:3 A5:3 C#6:3
        A7      | E6:3 D6:2 C#6:1 A5:6
    '''
    s.intro('D | - \n A7 | -', 1)
    s.section('a1', A, 1)
    s.section('a2', A, 2)
    s.section('b1', B, 1)
    s.section('a3', A, 1)
    s.section('b2', B, 2)
    s.section('a4', A, 2)
    s.end('D', 'D5')
    return s


def parc():
    s = Song('resaac_parc', 'Le Parc', 88, '6/8', 'jig', kit(lead='flute', lead2='chime', counter='bit'), SOFT,
             effects=True, comment="Le Parc : promenade à 6/8 en mi majeur, flûte, la mineur emprunté. Composition originale.")
    A = '''
        E        | G#5:4 B5:2 E6:4 B5:2
        A        | C#6:4 B5:2 A5:4 E5:2
        B7       | D#6:4 C#6:2 B5:4 A5:2
        E        | G#5:6 E5:6
        C#m      | E5:4 G#5:2 C#6:4 B5:2
        A        | A5:4 B5:2 C#6:4 E6:2
        F#m7,B7  | F#5:4 A5:2 B5:4 A5:2
        E        | E5:6 .:6                     | .:6 G#5:2 B5:2 E6:2
    '''
    B = '''
        A        | E6:6 C#6:6
        G#m      | B5:6 D#6:6
        C#m      | E6:4 D#6:2 C#6:6
        F#7      | A#5:6 C#6:6
        A        | A5:4 C#6:2 E6:4 C#6:2
        Am       | A5:4 C6:2 E6:4 C6:2
        E,C#m    | G#5:4 B5:2 C#6:4 B5:2
        F#7,B7   | A#5:4 C#6:2 B5:4 A5:2
    '''
    s.intro('E | - \n B7 | -', 0)
    s.section('a1', A, 1)
    s.section('a2', A, 2)
    s.section('b1', B, 1)
    s.section('a3', A, 1)
    s.section('b2', B, 2)
    s.section('a4', A, 2)
    s.end('E', 'E5')
    return s


def academie():
    s = Song('resaac_academie', "L'Académie", 116, '4/4', 'stroll', kit(), SOFT,
             effects=True, comment="L'Académie : si bémol majeur, enchaînements ii-V, un peu studieux. Composition originale.")
    A = '''
        Bb       | F5:2 Bb5:2 D6:4 Bb5:4 F5:4
        Eb,Ebm   | G5:4 Bb5:4 Gb5:4 Bb5:4
        Dm7,G7   | F5:4 A5:2 C6:2 B5:4 G5:4
        Cm7,F7   | Eb6:4 C6:4 A5:4 F5:4
        Bb       | D6:4 C6:2 Bb5:2 F5:4 Bb5:4
        G7       | B5:4 D6:4 F6:6 D6:2
        Cm7,F7   | Eb6:4 D6:2 C6:2 A5:4 C6:4
        Bb       | Bb5:8 .:8                   | .:8 D5:2 F5:2 A5:2 C6:2
    '''
    B = '''
        Gm       | D6:6 Bb5:2 G5:8
        Cm       | Eb6:6 C6:2 G5:8              | .:8 C5:2 D5:2 Eb5:2 G5:2
        F7       | F5:4 A5:4 C6:4 Eb6:4
        Bb       | D6:12 .:4
        Eb       | G5:4 Bb5:4 Eb6:6 D6:2
        Am7b5,D7 | C6:4 Eb6:4 D6:4 F#5:4
        Gm       | Bb5:4 A5:2 G5:2 D6:8
        C7,F7    | E5:4 G5:4 A5:4 C6:4
    '''
    s.intro('Bb | - \n Gm | - \n Cm7 | - \n F7 | -', 0)
    s.section('a1', A, 1)
    s.section('b1', B, 1)
    s.section('a2', A, 2)
    s.section('b2', B, 2)
    s.section('a3', A, 2)
    s.end('Bb', 'Bb5')
    return s


def fete():
    s = Song('resaac_fete', 'Jour de Fête', 150, '4/4', 'town', kit(), SOFT,
             effects=True, comment="Jour de Fête : sol majeur à 150, monte en la majeur pour la reprise. Composition originale.")
    A = '''
        G       | G5:2 A5:2 B5:2 D6:2 B5:4 G5:4
        C       | E5:2 G5:2 C6:4 E6:4 C6:4
        Am7,D7  | C6:2 B5:2 A5:2 G5:2 F#5:4 A5:4
        G       | G5:4 D5:4 B4:4 D5:4
        G       | G5:2 A5:2 B5:2 D6:2 D6:4 B5:4
        E7      | E6:4 D6:2 B5:2 G#5:8
        Am7,D7  | A5:4 C6:4 D6:4 C6:4
        G       | B5:4 A5:2 F#5:2 G5:8
    '''
    B = '''
        Em      | E6:4 B5:4 G5:4 B5:4
        C       | C6:4 G5:4 E5:4 G5:4            | .:12 E5:2 F#5:2
        D       | D6:4 A5:4 F#5:4 A5:4
        G       | G5:4 B5:4 D6:8                 | .:8 G5:2 F#5:2 E5:2 D5:2
        Em      | E6:6 D6:2 B5:4 G5:4
        C,Cm    | E6:4 C6:4 Eb6:4 C6:4
        G,E7    | D6:4 B5:4 G#5:4 B5:4
        A7,D7   | C#6:4 A5:4 C6:4 A5:4
    '''
    s.intro('G | - \n D7 | -', 1)
    s.section('a1', A, 1)
    s.section('b1', B, 1)
    s.section('a2', A, 2)
    s.section('b2', B, 2)
    s.section('pont', 'E7 | G#5:4 B5:4 D6:4 E6:4', 2, double=False)
    s.section('a3', A, 2, transpose=2)
    s.section('b3', B, 2, transpose=2)
    s.end('G', 'G5', transpose=2)
    return s


def quais():
    s = Song('resaac_quais', 'Crépuscule sur les Quais', 92, '4/4', 'stroll',
             kit(lead='flute', lead2='bell', counter='bit'), SOFT,
             effects=True, comment="Crépuscule sur les Quais : soirée chaleureuse en ré majeur, accords de septième majeure. "
                     "Composition originale.")
    A = '''
        Dmaj7      | F#5:6 E5:2 D5:4 A5:4
        Bm7        | B5:6 A5:2 F#5:8
        Gmaj7      | G5:4 B5:4 D6:6 C#6:2
        Em7,A7     | B5:4 G5:4 A5:8
        Dmaj7      | F#5:6 E5:2 D5:4 C#6:4
        F#7        | C#6:6 A#5:2 F#5:8              | .:8 A#4:2 C#5:2 E5:2 F#5:2
        Gmaj7,Gm6  | B5:4 D6:4 Bb5:4 E5:4
        Asus,A7    | A5:8 G5:4 E5:4
    '''
    B = '''
        Bm         | D6:6 C#6:2 B5:8
        G          | B5:6 A5:2 G5:4 D5:4
        Em7        | E5:4 G5:4 B5:6 D6:2
        F#7        | C#6:8 A#5:8                    | .:8 F#5:2 E5:2 C#5:2 A#4:2
        Bm         | B5:4 D6:4 F#6:6 E6:2
        Gmaj7      | D6:6 B5:2 G5:8
        Em7,A7     | G5:4 B5:4 C#6:4 E6:4
        D          | D6:12 .:4
    '''
    s.intro('Dmaj7 | - \n Bm7 | - \n Gmaj7 | - \n A7 | -', 0)
    s.section('a1', A, 1)
    s.section('b1', B, 1)
    s.section('a2', A, 2)
    s.section('b2', B, 2)
    s.end('D', 'D5')
    return s


def main():
    folder = score.songs_folder(sys.argv, __file__)
    total = 0
    for make in (bonjour, fleurs, route, the, toits, gare, parc, academie, fete, quais):
        total += make().build(folder, SCRIPT)
    print(f'{total} remark(s) in all')


if __name__ == '__main__':
    main()

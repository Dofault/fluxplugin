#!/usr/bin/env python3
"""Generates RPG 2 to RPG 10 (rpg2..rpg10), the ambient songs of the rpg lobby (world flux_rpg), shuffled with RPG 1
(gen_rpg.py) and the camp theme. Note block MMORPG soundtrack spirit (Wynncraft as the reference): driving bass,
arpeggios, rim drums, fantasy tunes in minor and modal keys. All original. Writer in suite.py.

    rpg2   D dorian, gallop          the ride across the plains
    rpg3   A minor jig at 6/8        a road song, banjo answers
    rpg4   C minor march             the army of the Watchers, low guitar line in the bridge
    rpg5   D major with bVII         bright heroic exploration
    rpg6   E Phrygian dominant       desert ruins, half time
    rpg7   G minor fast waltz        a dance of shadows, Neapolitan Ab
    rpg8   A dorian shuffle          the coast road, banjo tune
    rpg9   Bb major anthem           rises a tone (C major) for the last theme
    rpg10  E minor gallop            the hunt, arpeggiated pling tune

    python3 gen_rpg_suite.py [output folder]      (default: ../src/main/resources/songs)

Never edit the produced songs/rpg*.yml by hand: change this script and run it again.
"""
import sys

import score
from suite import Song

SCRIPT = 'gen_rpg_suite.py'
BASE = {'lead': 'flute', 'lead2': 'bell', 'harmony': 'harp', 'counter': 'iron_xylophone', 'arp': 'pling',
        'comp': 'guitar', 'bass': 'bass', 'deep': 'didgeridoo'}


def kit(**changes):
    return {k: v for k, v in dict(BASE, **changes).items() if v}


def rpg2():
    s = Song('rpg2', 'RPG 2', 150, '4/4', 'gallop', kit(),
             comment='RPG 2 : chevauchée dans les plaines, ré dorien, galop, 150 à la noire. Composition originale.')
    A = '''
        Dm    | D5:2 A4:2 D5:2 E5:2 F5:4 E5:2 D5:2
        C     | E5:2 C5:2 E5:2 F5:2 G5:4 F5:2 E5:2
        Dm,G  | F5:4 A5:4 B5:4 G5:4
        Am    | A5:6 G5:2 E5:8
        Dm    | D5:2 A4:2 D5:2 E5:2 F5:4 A5:2 D6:2
        C     | C6:4 G5:2 E5:2 G5:4 C6:4
        G,Am  | B5:4 D6:4 C6:4 A5:4
        Dm    | D6:6 C6:2 A5:8
    '''
    B = '''
        F     | A5:6 C6:2 F5:8
        C     | G5:6 E5:2 C5:8
        Dm    | F5:4 E5:2 D5:2 A5:8
        G     | B5:4 A5:2 G5:2 D5:8
        F     | C6:6 A5:2 F5:4 A5:4
        C     | E6:6 D6:2 C6:4 G5:4
        Bb,C  | D6:4 Bb5:4 C6:4 E6:4
        A7    | C#6:8 A5:4 E5:4
    '''
    C = '''
        Dm    | A5:8 F5:4 D5:4
        Em    | G5:8 E5:4 B4:4
        F     | A5:8 C6:4 A5:4
        Em    | G5:8 B5:8         | .:8 B4:2 A4:2 G4:2 E4:2
        Dm    | A5:4 D6:4 F6:6 E6:2
        C     | E6:8 C6:4 G5:4
        Bb    | D6:8 Bb5:4 F5:4   | .:8 F4:2 G4:2 A4:2 Bb4:2
        A7    | E5:4 G5:4 C#6:4 E6:4
    '''
    s.intro('Dm | - \n C | - \n Dm | - \n C | -', 0)
    s.section('a1', A, 1)
    s.section('a2', A, 2)
    s.section('b1', B, 1)
    s.section('c', C, 1, groove='heavy')
    s.section('b2', B, 2)
    s.section('a3', A, 2)
    s.end('Dm', 'D5')
    return s


def rpg3():
    s = Song('rpg3', 'RPG 3', 92, '6/8', 'jig', kit(lead2='xylophone', counter='banjo', arp='harp'),
             comment="RPG 3 : chanson de route, la mineur à 6/8, gigue, le banjo répond à la flûte. Composition originale.")
    A = '''
        Am    | A5:2 E5:2 A5:2 C6:4 B5:2
        G     | B5:2 G5:2 D5:2 G5:4 A5:2
        Am    | C6:2 B5:2 A5:2 E5:4 A5:2
        E     | G#5:6 B5:4 G#5:2
        Am    | A5:2 E5:2 A5:2 C6:4 D6:2
        C     | E6:4 D6:2 C6:4 B5:2
        Dm,E  | F5:2 A5:2 D6:2 B5:2 G#5:2 E5:2
        Am    | A5:6 E5:2 A4:4
    '''
    B = '''
        C     | G5:2 E5:2 G5:2 C6:4 G5:2
        G     | B5:2 G5:2 B5:2 D6:4 B5:2
        F     | A5:2 C6:2 A5:2 F5:4 A5:2
        C,G   | G5:6 D5:4 G5:2
        Am    | E5:2 A5:2 C6:2 E6:4 C6:2
        F     | F6:4 E6:2 C6:4 A5:2
        Dm,G  | D6:2 C6:2 A5:2 B5:4 D6:2
        E7    | E6:6 G#5:2 B5:2 D6:2
    '''
    C = '''
        Am    | E6:6 C6:6
        Am    | -                 | A4:2 C5:2 E5:2 A5:2 G5:2 E5:2
        F     | F6:6 C6:6
        F     | -                 | A4:2 C5:2 F5:2 A5:2 G5:2 F5:2
        G     | D6:6 B5:6
        G     | -                 | G4:2 B4:2 D5:2 G5:2 F#5:2 D5:2
        E     | B5:6 G#5:6
        E7    | E5:4 G#5:4 B5:4   | .:6 E5:2 D5:2 B4:2
    '''
    s.intro('Am | - \n E | -', 1)
    s.section('a1', A, 1)
    s.section('a2', A, 2)
    s.section('b1', B, 1)
    s.section('c', C, 1)
    s.section('a3', A, 2)
    s.section('b2', B, 2)
    s.section('a4', A, 2)
    s.end('Am', 'A5')
    return s


def rpg4():
    s = Song('rpg4', 'RPG 4', 112, '4/4', 'march', kit(counter='guitar', arp='pling', comp='harp', harmony='pling'),
             comment="RPG 4 : la marche des Veilleurs, do mineur, caisse claire de marche, ligne grave de guitare au "
                     "pont. Composition originale.")
    A = '''
        Cm    | C5:6 D5:2 Eb5:4 G5:4
        Ab    | Ab5:6 G5:2 Eb5:4 C5:4
        Bb    | D5:6 F5:2 Bb5:8
        G     | G5:6 F5:2 D5:4 B4:4
        Cm    | C5:6 D5:2 Eb5:4 G5:4
        Ab    | C6:6 Bb5:2 Ab5:4 Eb5:4
        Fm,G  | F5:4 Ab5:4 G5:4 B5:4
        Cm    | C6:12 .:4
    '''
    B = '''
        Eb    | G5:4 Bb5:4 Eb6:6 D6:2
        Bb    | D6:4 C6:2 Bb5:2 F5:8
        Cm    | Eb6:4 D6:2 C6:2 G5:4 C6:4
        Ab    | C6:8 Ab5:8
        Eb    | G5:4 Bb5:4 Eb6:6 F6:2
        Bb    | F6:8 D6:4 Bb5:4
        Ab,Bb | Eb6:4 C6:4 D6:4 F6:4
        G     | D6:8 B5:8
    '''
    C = '''
        Cm    | G5:16                | C3:4 D3:4 Eb3:4 G3:4
        Bb    | F5:16                | Bb2:4 C3:4 D3:4 F3:4
        Ab    | Eb5:16               | Ab2:4 Bb2:4 C3:4 Eb3:4
        G     | D5:8 B4:8            | G2:4 A2:4 B2:4 D3:4
        Cm    | G5:8 C6:8            | C3:4 D3:4 Eb3:4 G3:4
        Bb    | F5:8 Bb5:8           | Bb2:4 C3:4 D3:4 F3:4
        Ab    | Eb6:8 C6:8           | Ab2:4 Bb2:4 C3:4 Eb3:4
        G     | D6:4 B5:4 G5:4 F5:4  | G3:4 F3:4 Eb3:4 D3:4
    '''
    s.intro('Cm | - \n Ab | - \n Bb | - \n G | -', 1)
    s.section('a1', A, 1)
    s.section('b1', B, 1)
    s.section('c', C, 1, groove='heavy')
    s.section('a2', A, 2)
    s.section('b2', B, 2)
    s.end('Cm', 'C5')
    return s


def rpg5():
    s = Song('rpg5', 'RPG 5', 140, '4/4', 'adventure', kit(lead2='chime'),
             comment="RPG 5 : exploration héroïque et lumineuse, ré majeur avec septième degré abaissé, 140 à la "
                     "noire. Composition originale.")
    A = '''
        D     | F#5:2 A5:2 D6:4 A5:4 F#5:2 A5:2
        C     | G5:2 C6:2 E6:4 C6:4 G5:2 E5:2
        G     | D6:6 B5:2 G5:4 B5:4
        A     | A5:6 C#6:2 E6:8
        D     | F#6:6 E6:2 D6:4 A5:4
        C     | E6:6 D6:2 C6:4 G5:4
        G,A   | B5:4 D6:4 C#6:4 E6:4
        D     | D6:12 .:4
    '''
    B = '''
        Bm    | B5:6 A5:2 F#5:8
        G     | G5:6 A5:2 B5:8
        Em    | E5:4 G5:4 B5:6 A5:2
        F#7   | A#5:8 C#6:4 F#5:4
        Bm    | D6:6 C#6:2 B5:8
        G     | B5:6 A5:2 G5:4 D6:4
        Em,A  | E6:4 D6:4 C#6:4 A5:4
        A7    | E5:4 G5:4 A5:4 C#6:4
    '''
    C = '''
        G     | D6:8 B5:8
        D     | A5:8 F#5:8           | .:8 D5:2 E5:2 F#5:2 A5:2
        Em    | G5:8 B5:8
        Bm    | F#5:12 .:4           | .:8 B4:2 C#5:2 D5:2 F#5:2
        C     | E6:8 G5:8
        G     | D6:8 B5:8            | .:8 G5:2 A5:2 B5:2 D6:2
        A     | C#6:8 E6:8
        A7    | E6:4 C#6:4 A5:4 G5:4
    '''
    s.intro('D | - \n C | - \n G | - \n A | -', 0)
    s.section('a1', A, 1)
    s.section('a2', A, 2)
    s.section('b1', B, 1)
    s.section('c', C, 1)
    s.section('a3', A, 2)
    s.end('D', 'D6')
    return s


def rpg6():
    s = Song('rpg6', 'RPG 6', 100, '4/4', 'heavy', kit(lead='pling', lead2='flute', counter='flute',
                                                       arp='iron_xylophone'),
             comment="RPG 6 : ruines du désert, mi phrygien dominant (accord de fa sur mi), demi-temps lourd, "
                     "didgeridoo. Composition originale.")
    A = '''
        E     | E5:4 F5:2 G#5:2 B5:6 A5:2
        F     | A5:4 G#5:2 A5:2 C6:8
        Dm    | F5:4 E5:2 D5:2 A5:8
        E     | G#5:6 F5:2 E5:8
        Am    | A5:4 B5:2 C6:2 E6:6 D6:2
        G     | D6:4 C6:2 B5:2 G5:8
        F     | C6:4 A5:2 G#5:2 F5:4 A5:4
        E     | G#5:4 F5:2 E5:2 B4:8
    '''
    B = '''
        Am    | A5:1 B5:1 C6:2 B5:2 A5:2 E5:4 A5:4
        G     | G5:1 A5:1 B5:2 A5:2 G5:2 D5:4 G5:4
        F     | F5:1 G5:1 A5:2 G5:2 F5:2 C5:4 F5:4
        E     | E5:2 F5:2 G#5:2 A5:2 B5:8
        Am    | C6:1 B5:1 A5:2 B5:2 C6:2 E6:8
        Dm    | D6:1 E6:1 F6:2 E6:2 D6:2 A5:8
        F     | C6:2 A5:2 F5:2 A5:2 C6:4 A5:4
        E     | B5:4 G#5:4 E5:8
    '''
    C = '''
        E     | E5:16
        F     | F5:16                | .:8 A5:2 C6:2 A5:2 F5:2
        E     | G#5:16
        Dm    | A5:16                | .:8 D6:2 F5:2 A5:2 D6:2
        E     | B5:8 G#5:8
        F     | C6:8 A5:8
        Dm    | D6:8 F5:8
        E     | E6:8 B5:8
    '''
    s.intro('E | - \n F | - \n E | - \n F | -', 0)
    s.section('a1', A, 1)
    s.section('b1', B, 1, groove='adventure')
    s.section('c', C, 0)
    s.section('a2', A, 2)
    s.section('b2', B, 2, groove='adventure')
    s.end('E', 'E5')
    return s


def rpg7():
    s = Song('rpg7', 'RPG 7', 168, '3/4', 'waltz', kit(),
             comment="RPG 7 : valse rapide des ombres, sol mineur à 3/4, accord napolitain de la bémol. "
                     "Composition originale.")
    A = '''
        Gm    | G5:4 Bb5:4 D6:4
        Gm    | D6:8 C6:2 Bb5:2
        Cm    | C6:4 Eb6:4 G5:4
        Cm    | G5:8 Eb5:4
        D     | F#5:4 A5:4 D6:4
        D7    | C6:8 A5:2 F#5:2
        Gm    | Bb5:4 A5:2 Bb5:2 D6:4
        Gm    | G5:12
        Eb    | G5:4 Bb5:4 Eb6:4
        Eb    | Eb6:8 D6:2 C6:2
        Cm    | C6:4 Eb6:4 C6:4
        Cm    | G5:8 A5:2 Bb5:2
        D     | A5:4 D6:4 F#6:4
        D7    | F#6:4 D6:4 C6:4
        Gm    | Bb5:8 A5:4
        D7    | F#5:12
    '''
    B = '''
        Bb    | D6:8 F5:4
        F     | C6:8 A5:4
        Gm    | Bb5:8 D6:4
        Eb    | G5:12
        Bb    | F5:4 Bb5:4 D6:4
        F     | F6:8 Eb6:4
        Gm    | D6:4 C6:4 Bb5:4
        Cm    | Eb6:8 C6:4
        Ab    | Eb6:8 C6:4
        Ab    | Ab5:12
        Gm    | G5:4 Bb5:4 D6:4
        Eb    | Eb6:8 D6:2 C6:2
        Cm    | C6:8 Bb5:2 A5:2
        D     | A5:12
        D7    | F#5:4 A5:4 C6:4
        D7    | D6:12
    '''
    s.intro('Gm | - \n Gm | - \n D | - \n D7 | -', 0)
    s.section('a1', A, 1)
    s.section('a2', A, 2)
    s.section('b1', B, 1)
    s.section('a3', A, 2)
    s.section('b2', B, 2)
    s.end('Gm', 'G5')
    return s


def rpg8():
    s = Song('rpg8', 'RPG 8', 104, 'shuffle', 'shuffle', kit(lead='banjo', lead2='flute', counter='flute'),
             comment="RPG 8 : la route de la côte, la dorien en rythme ternaire, air de banjo. Composition originale.")
    A = '''
        Am    | A4:2 C5:1 E5:3 A5:3 G5:2 E5:1
        D     | F#5:3 E5:2 D5:1 A4:3 D5:3
        Am    | C5:2 D5:1 E5:3 C5:3 A4:3
        G     | B4:3 D5:3 G5:6
        Am    | A4:2 C5:1 E5:3 A5:3 G5:2 E5:1
        D     | F#5:3 A5:2 F#5:1 D5:6
        Em,G  | E5:3 B4:3 D5:3 G5:3
        Am    | A5:6 E5:3 .:3
    '''
    B = '''
        C     | E5:3 G5:3 C6:4 B5:2
        G     | B5:3 A5:2 G5:1 D5:6
        Am    | C6:3 B5:2 A5:1 E5:3 A5:3
        D     | F#5:9 .:3
        F     | C6:3 A5:3 F5:3 A5:3
        G     | B5:3 G5:3 D5:3 G5:3
        Em,Am | E5:2 G5:1 B5:3 C6:2 B5:1 A5:3
        E7    | G#5:6 E5:3 D5:3
    '''
    C = '''
        Am    | -                                  | E5:6 A5:6
        Am    | A5:2 B5:1 C6:2 B5:1 A5:3 E5:3
        G     | -                                  | D5:6 G5:6
        G     | G5:2 A5:1 B5:2 A5:1 G5:3 D5:3
        F     | -                                  | C5:6 F5:6
        F     | F5:2 G5:1 A5:2 G5:1 F5:3 C6:3
        E     | -                                  | B4:6 E5:6
        E7    | G#5:3 B5:3 D6:3 E6:3
    '''
    s.intro('Am | - \n D | -', 1)
    s.section('a1', A, 1)
    s.section('a2', A, 2)
    s.section('b1', B, 1)
    s.section('c', C, 1)
    s.section('a3', A, 2)
    s.section('b2', B, 2)
    s.end('Am', 'A5')
    return s


def rpg9():
    s = Song('rpg9', 'RPG 9', 132, '4/4', 'adventure', kit(),
             comment="RPG 9 : hymne héroïque, si bémol majeur, le dernier thème monte d'un ton (do majeur). "
                     "Composition originale.")
    A = '''
        Bb    | D5:4 F5:4 Bb5:6 C6:2
        F     | C6:4 A5:2 F5:2 A5:8
        Gm    | Bb5:6 A5:2 G5:4 D5:4
        Eb    | Eb5:6 F5:2 G5:8
        Bb    | F5:4 Bb5:4 D6:6 Eb6:2
        F     | C6:6 Bb5:2 A5:4 F5:4
        Eb,F  | G5:4 Bb5:4 C6:4 A5:4
        Bb    | Bb5:12 .:4
    '''
    B = '''
        Gm    | D6:6 C6:2 Bb5:4 G5:4
        Eb    | G5:6 F5:2 Eb5:4 Bb5:4
        Cm    | C6:6 Bb5:2 G5:4 Eb5:4
        F     | F5:6 G5:2 A5:8
        Gm    | Bb5:4 C6:4 D6:6 C6:2
        Eb    | Eb6:6 D6:2 Bb5:8
        Cm,F  | C6:4 Eb6:4 C6:4 A5:4
        F7    | F5:4 A5:4 C6:4 Eb6:4
    '''
    s.intro('Bb | - \n F | - \n Gm | - \n Eb | -', 0)
    s.section('a1', A, 1)
    s.section('b1', B, 1)
    s.section('a2', A, 2)
    s.section('b2', B, 2)
    s.section('pont', 'G7 | G5:4 B5:4 D6:4 F6:4', 2, double=False)
    s.section('a3', A, 2, transpose=2)
    s.end('Bb', 'Bb5', transpose=2)
    return s


def rpg10():
    s = Song('rpg10', 'RPG 10', 160, '4/4', 'gallop', kit(lead='pling', lead2='flute', arp='bit'),
             comment="RPG 10 : la chasse, mi mineur, galop à 160, air arpégé au pling. Composition originale.")
    A = '''
        Em    | E5:2 G5:2 B5:2 E6:2 B5:2 G5:2 E5:2 G5:2
        D     | F#5:2 A5:2 D6:2 E6:2 D6:2 A5:2 F#5:2 A5:2
        C     | G5:4 E5:2 G5:2 C6:4 B5:2 A5:2
        B7    | B5:6 A5:2 F#5:4 D#5:4
        Em    | E6:4 D6:2 B5:2 G5:4 B5:4
        Am    | A5:4 C6:2 E6:2 C6:4 A5:4
        C,D   | E6:4 C6:4 D6:4 A5:4
        Em    | B5:4 G5:2 F#5:2 E5:8
    '''
    B = '''
        G     | D6:6 B5:2 G5:4 B5:4
        D     | A5:6 F#5:2 D5:4 F#5:4
        Em    | G5:4 B5:4 E6:6 D6:2
        C     | E6:8 C6:4 G5:4
        G     | B5:4 D6:4 B5:6 A5:2
        D     | A5:4 F#5:4 D6:8
        C,Am  | E6:4 G5:4 C6:4 E6:4
        B7    | D#6:8 B5:4 F#5:4
    '''
    C = '''
        Em    | B5:8 E6:8
        C     | E6:8 G5:8
        Am    | C6:8 A5:8
        B7    | B5:8 F#5:8
        Em    | G5:8 B5:8
        C     | C6:8 E6:8
        D     | D6:8 A5:8
        B7    | D#6:4 B5:4 A5:4 F#5:4
    '''
    s.intro('Em | - \n Em | -', 1)
    s.section('a1', A, 1)
    s.section('a2', A, 2)
    s.section('b1', B, 1)
    s.section('c', C, 1, groove='heavy')
    s.section('a3', A, 2)
    s.section('b2', B, 2)
    s.end('Em', 'E5')
    return s


def main():
    folder = score.songs_folder(sys.argv, __file__)
    total = 0
    for make in (rpg2, rpg3, rpg4, rpg5, rpg6, rpg7, rpg8, rpg9, rpg10):
        total += make().build(folder, SCRIPT)
    print(f'{total} remark(s) in all')


if __name__ == '__main__':
    main()

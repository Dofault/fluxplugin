#!/usr/bin/env python3
"""Generates "L'Horloge Brisée" (boss_temps), a boss theme about time breaking down. Original composition in the
spirit of the tragic boss themes of handheld dungeon-crawler RPGs (urgent ostinato, minor key, a lament, a soaring
reprise), without quoting any of them. Writer in suite.py.

B minor at 4/4, 168 to the quarter. A clock (hat) ticks in time from the start, falls silent during the lament,
then limps in threes against the bar when time breaks; the bII (C major) and a diminished chord carry the menace.

    intro  the clock alone, the drone, the sixteenth ostinato starts
    a1/a2  the urgent theme, then doubled by the bells with a second voice
    b      the lament: long notes over a falling line, no clock
    c      time breaks: the xylophone turns in threes against the bar over Bm-C, the clock limps, then the tune
           returns at the top of the flute
    a3     the theme at full force, loops back to a1

    python3 gen_boss_temps.py [output folder]      (default: ../src/main/resources/songs)

Never edit songs/boss_temps.yml by hand: change this script and run it again.
"""
import sys

import score
from suite import Song

TICK3 = (0, 3, 6, 9, 12, 15)


def main():
    s = Song('boss_temps', "L'Horloge Brisée", 168, '4/4', 'gallop',
             {'lead': 'flute', 'lead2': 'bell', 'harmony': 'harp', 'counter': 'iron_xylophone', 'arp': 'pling',
              'comp': 'guitar', 'bass': 'bass', 'deep': 'didgeridoo', 'tick': 'hat'},
             volumes={'tick': 0.22, 'arp': 0.24, 'bass': 0.72},
             comment="L'Horloge Brisée : thème de boss, le temps qui se brise, si mineur à 168. "
                     "Composition originale.")
    A = '''
        Bm     | B5:2 F#5:2 B5:2 C#6:2 D6:4 C#6:2 B5:2
        G      | B5:2 G5:2 B5:2 C#6:2 D6:4 E6:2 D6:2
        A      | C#6:6 A5:2 E5:4 A5:4
        F#     | A#5:6 F#5:2 C#6:8
        Bm     | B5:2 F#5:2 B5:2 C#6:2 D6:4 F#6:4
        G      | D6:4 E6:2 D6:2 B5:4 G5:4
        Em,F#  | E6:4 G5:4 F#5:4 A#5:4
        Bm     | B5:12 .:4
    '''
    B = '''
        G      | B5:8 D6:4 B5:4
        A      | C#6:8 E6:4 C#6:4
        F#m    | A5:8 C#6:4 F#6:4
        Bm     | F#6:8 D6:4 B5:4
        Em     | E6:8 G5:4 B5:4
        F#7    | A#5:8 C#6:4 E6:4
        G,Em   | D6:4 B5:4 G5:4 E5:4
        F#7    | F#5:4 A#5:4 C#6:4 E6:4
    '''
    turn = lambda a, b, c, end: ' '.join(f'{n}:1' for n in [a, b, c] * 4) + f' {end}'
    C = f'''
        Bm     | -              | {turn('B4', 'D5', 'F#5', 'B4:2 C#5:2')}
        C      | -              | {turn('C5', 'E5', 'G5', 'C5:2 B4:2')}
        Bm     | -              | {turn('B4', 'D5', 'F#5', 'B4:2 C#5:2')}
        C#dim  | -              | {turn('C#5', 'E5', 'G5', 'F#5:2 E5:2')}
        Bm     | F#6:8 D6:8
        C      | E6:8 G5:8
        G      | D6:8 B5:8
        F#7    | C#6:4 A#5:4 F#5:4 E5:4
    '''
    intro = s.intro('Bm | - \n Bm | - \n G | - \n F# | -', 0)
    a1 = s.section('a1', A, 1)
    a2 = s.section('a2', A, 2)
    s.section('b', B, 1, groove='heavy')
    c = s.section('c', C, 1, groove='heavy')
    a3 = s.section('a3', A, 2)
    # the clock: in time, silent in the lament, limping in threes when time breaks
    score.drums(intro, 'tick', (0, 4, 8, 12), range(4))
    for sec in (a1, a2, a3):
        score.drums(sec, 'tick', (0, 4, 8, 12), range(sec.bars))
    score.drums(c, 'tick', TICK3, range(8))
    problems = s.build(score.songs_folder(sys.argv, __file__), 'gen_boss_temps.py', loop_from='a1')
    print(f'{problems} remark(s)')


if __name__ == '__main__':
    main()

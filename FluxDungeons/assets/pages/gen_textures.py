#!/usr/bin/env python3
"""Pixel icons of the stats on the rpg pages, drawn here, never taken from a texture pack.

Writes FluxDungeons/src/main/resources/pages/img/ico_<stat>.png (16 x 16 scaled 2 times, nearest neighbour: the page
engine draws them as they are). ClassPages copies them next to the pages. Needs Pillow (a venv):
<venv>/python3 gen_textures.py
"""
import os

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.normpath(os.path.join(HERE, "..", "..", "src", "main", "resources", "pages", "img"))


def hexc(h, a=255):
    return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5)) + (a,)


def save(img, name, k):
    os.makedirs(OUT, exist_ok=True)
    img.resize((img.width * k, img.height * k), Image.NEAREST).save(os.path.join(OUT, name + ".png"))


# 16 x 16 icons: '.' empty, letters from the palette
PAL = {"k": "#1a1018", "r": "#d8383a", "R": "#ff7a6a", "w": "#ffffff", "s": "#c8c8d0", "S": "#8a8a96", "b": "#6b4a2b",
       "g": "#f0a800", "G": "#ffd94d", "v": "#8a4fd0", "V": "#c9a0ff", "c": "#3fa9f5", "C": "#9ad8ff", "y": "#7a6040",
       "e": "#2ecf7b", "E": "#9af0b8"}
ICONS = {
    "max_health": [
        "................", "................", "..kkk.....kkk...", ".kRRrk...krRRrk.", ".kRwRrk.krrRRrk.", ".kRRrrrkrrrrrrk.",
        ".krrrrrrrrrrrrk.", ".krrrrrrrrrrrrk.", "..krrrrrrrrrrk..", "...krrrrrrrrk...", "....krrrrrrk....", ".....krrrrk.....",
        "......krrk......", ".......kk.......", "................", "................"],
    "physical_attack": [
        "............kkk.", "...........kssk.", "..........ksssk.", ".........ksssk..", "........ksssk...", ".......ksssk....",
        "......ksssk.....", "..kk.ksssk......", "..kgkssSk.......", "...kgSSk........", "...kbgk.........", "..kbkkgk........",
        ".kbk..kgk.......", "kbk....kk.......", "kk..............", "................"],
    "magic_power": [
        ".......kk.......", "......kVVk......", "......kVvk......", ".kk..kVVvvk..kk.", ".kVkkVVwVvvkkvk.", "..kVVVwwVvvvvk..",
        "...kVVVVVvvvk...", "....kVVvvvvk....", "...kVVvvvvvvk...", "..kVVvkkkkvvvk..", ".kVvkk....kkvvk.", ".kkk........kkk.",
        "................", "................", "................", "................"],
    "physical_defense": [
        "................", "..kkkkkkkkkkkk..", "..kSSSSSSSSSSk..", "..kSsssssssSSk..", "..kSswsssssSSk..", "..kSssssssssSk..",
        "..kSssssssssSk..", "..kSssssssssSk..", "...kSssssssSk...", "...kSssssssSk...", "....kSssssSk....", ".....kSssSk.....",
        "......kSSk......", ".......kk.......", "................", "................"],
    "crit_chance": [
        "................", ".....kkkkkk.....", "...kkrrrrrrkk...", "..krrwwwwwwrrk..", "..krwrrrrrrwrk..", ".krwrkkkkkkrwrk.",
        ".krwrkGGGGkrwrk.", ".krwrkGggGkrwrk.", ".krwrkGggGkrwrk.", ".krwrkGGGGkrwrk.", ".krwrkkkkkkrwrk.", "..krwrrrrrrwrk..",
        "..krrwwwwwwrrk..", "...kkrrrrrrkk...", ".....kkkkkk.....", "................"],
    "attack_speed": [
        "................", ".........kkkk...", "........kGGGk...", ".......kGGgk....", "......kGGgk.....", ".....kGGgkkkk...",
        "....kGGggggGk...", "...kkkkgGGGk....", "......kGGgk.....", ".....kGGgk......", "....kGGgk.......", "....kGgk........",
        "...kGgk.........", "...kkk..........", "................", "................"],
    "max_mana": [
        "................", "......kkkk......", ".....kCCck......", "....kCCcck......", "...kCCccck......", "...kCcccck......",
        "..kCwcccccck....", "..kCcccccccck...", ".kCcccccccccck..", ".kCwccccccccck..", ".kCcccccccccck..", "..kCccccccccck..",
        "...kCcccccccck..", "....kkkkkkkk....", "................", "................"],
    "xp": [
        "................", ".......kk.......", "......kEEk......", "......kEek......", ".kkkkkEEeekkkkk.", ".kEEEEEeeeeeeek.",
        "..keEEeeeeeeek..", "...keeeeeeeek...", "....keeeeeeek...", "...keeekkeeek...", "...keekk.kkeek..", "..keek.....keek.",
        "..kkk.......kkk.", "................", "................", "................"],
}


def icon(rows):
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch != ".":
                im.putpixel((x, y), hexc(PAL[ch]))
    return im


if __name__ == "__main__":
    for name, rows in ICONS.items():
        assert len(rows) == 16 and all(len(x) == 16 for x in rows), name
        save(icon(rows), "ico_" + name, 2)
    print("written into", OUT)

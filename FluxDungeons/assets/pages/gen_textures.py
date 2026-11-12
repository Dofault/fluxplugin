#!/usr/bin/env python3
"""Pixel textures and icons of the rpg pages (Codex des Destins look), drawn here, never taken from a texture pack.

Writes FluxDungeons/src/main/resources/pages/img/ : tex_stone, tex_parchment, tex_wood, tex_iron (16 x 16 tiles scaled
4 times, nearest neighbour: the page engine draws them as they are) and the stat icons ico_<stat> (16 x 16 scaled 2
times). ClassPages copies them next to the pages. Needs Pillow (a venv): <venv>/python3 gen_textures.py
"""
import os
import random

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.normpath(os.path.join(HERE, "..", "..", "src", "main", "resources", "pages", "img"))
r = random.Random(1616)


def hexc(h, a=255):
    return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5)) + (a,)


def save(img, name, k):
    os.makedirs(OUT, exist_ok=True)
    img.resize((img.width * k, img.height * k), Image.NEAREST).save(os.path.join(OUT, name + ".png"))


def jitter(c, d):
    return tuple(max(0, min(255, v + r.randint(-d, d))) for v in c[:3]) + (c[3],)


def stone():
    """Dark slate bricks with a violet hint: two rows of 8 x 4 bricks, offset, mortar lines, chipped corners."""
    im = Image.new("RGBA", (16, 16))
    mortar, bases = hexc("#18141f"), [hexc("#3a3448"), hexc("#332e40"), hexc("#403a50"), hexc("#2e2a3a")]
    for y in range(16):
        for x in range(16):
            row = y // 4
            off = 4 if row % 2 else 0
            bx = (x + off) % 8
            if y % 4 == 3 or bx == 7:
                im.putpixel((x, y), mortar)
                continue
            base = bases[((x + off) // 8 + row * 3) % 4]
            c = jitter(base, 5)
            if y % 4 == 0 and bx < 6:
                c = tuple(min(255, v + 14) for v in c[:3]) + (255,)        # light from above
            if bx == 6 or y % 4 == 2:
                c = tuple(max(0, v - 10) for v in c[:3]) + (255,)
            im.putpixel((x, y), c)
    return im


def parchment():
    im = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            c = jitter(hexc("#e6d3a8"), 6)
            if r.random() < 0.08:
                c = jitter(hexc("#d4bc88"), 5)
            if r.random() < 0.015:
                c = hexc("#b89a64")
            im.putpixel((x, y), c)
    return im


def wood():
    """Dark oak planks: four boards of 4 pixels, grain lines, a nail head."""
    im = Image.new("RGBA", (16, 16))
    tones = [hexc("#5c3b22"), hexc("#53351f"), hexc("#61402a"), hexc("#4f321d")]
    for y in range(16):
        for x in range(16):
            board = y // 4
            c = jitter(tones[board], 4)
            if y % 4 == 3:
                c = hexc("#2c1b0f")
            elif y % 4 == 0:
                c = tuple(min(255, v + 12) for v in c[:3]) + (255,)
            elif (x * 7 + board * 5) % 11 == 0:
                c = tuple(max(0, v - 16) for v in c[:3]) + (255,)
            im.putpixel((x, y), c)
    for (x, y) in ((2, 1), (13, 9)):
        im.putpixel((x, y), hexc("#9a8f80"))
    return im


def iron():
    im = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            c = jitter(hexc("#5e5a66"), 6)
            if x in (0, 15) or y in (0, 15):
                c = hexc("#2a2730")
            elif x == 1 or y == 1:
                c = hexc("#8a8594")
            im.putpixel((x, y), c)
    for (x, y) in ((3, 3), (12, 3), (3, 12), (12, 12)):
        im.putpixel((x, y), hexc("#c8c2d0"))
        im.putpixel((x + 1, y + 1), hexc("#3a3640"))
    return im


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
    save(stone(), "tex_stone", 4)
    save(parchment(), "tex_parchment", 4)
    save(wood(), "tex_wood", 4)
    save(iron(), "tex_iron", 4)
    for name, rows in ICONS.items():
        assert len(rows) == 16 and all(len(x) == 16 for x in rows), name
        save(icon(rows), "ico_" + name, 2)
    print("written into", OUT)

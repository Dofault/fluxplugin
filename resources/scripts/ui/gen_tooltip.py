"""Tooltip of every item (vanilla sprites gui/sprites/tooltip, pack « influstatique »), 2026-11-10.

Vanilla draws both sprites 12 px around the text (3 px padding + 9 px margin) and leaves the margin transparent:
the fill starts at INSET px from the edge, so the visible margin around the text is 12 - INSET px. No frame (the
former gold outline): frame.png stays, fully transparent, else the vanilla frame comes back.
Background: dark violet vertical gradient (the menus' violet, 2026-11-16), rounded corners, a faint lighter edge. nine_slice + stretch_inner, so the
gradient stretches with the tooltip height.

    <python with Pillow> gen_tooltip.py <dir gui/sprites/tooltip> [preview.png]
"""
import json, os, sys
from PIL import Image, ImageDraw

SIZE = 100
INSET = 3          # visible margin around the text = 12 - INSET
RADIUS = 3
BORDER = 8         # nine_slice border, must cover INSET + RADIUS
# 2026-11-16 (owner's trial): the menus' dark violet (fluxui ground 44, 35, 57; edge 127, 102, 163) at the brightness of
# the former charcoal (top 32, 32, 37, bottom 15, 15, 18, edge 88, 88, 98): only the colour changes
TOP = (36, 28, 46)     # gradient, top (violet kept 2026-11-16 after trying pétrole 13, 40, 44 and bleu nuit 24, 31, 63)
BOTTOM = (17, 13, 22)  # gradient, bottom
ALPHA = 238
EDGE = (97, 78, 125, 150)  # faint outline


def mask(inset, radius):
    m = Image.new("L", (SIZE * 4, SIZE * 4), 0)
    ImageDraw.Draw(m).rounded_rectangle((inset * 4, inset * 4, (SIZE - inset) * 4 - 1, (SIZE - inset) * 4 - 1),
                                        radius * 4, fill=255)
    return m.resize((SIZE, SIZE), Image.LANCZOS)


def background():
    grad = Image.new("RGBA", (SIZE, SIZE))
    for y in range(SIZE):
        t = min(1, max(0, (y - INSET) / (SIZE - 2 * INSET - 1)))
        c = tuple(round(TOP[i] + (BOTTOM[i] - TOP[i]) * t) for i in range(3))
        for x in range(SIZE):
            grad.putpixel((x, y), c + (ALPHA,))
    outer, inner = mask(INSET, RADIUS), mask(INSET + 1, RADIUS - 1)
    out = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    edge = Image.new("RGBA", (SIZE, SIZE), EDGE)
    out.paste(edge, (0, 0), outer)
    fill = grad.copy()
    fill.putalpha(Image.eval(inner, lambda v: v * ALPHA // 255))
    out.alpha_composite(fill)
    return out


def meta(border):
    return {"gui": {"scaling": {"type": "nine_slice", "width": SIZE, "height": SIZE, "border": border,
                                "stretch_inner": True}}}


def main():
    dest = sys.argv[1]
    bg = background()
    bg.save(os.path.join(dest, "background.png"))
    Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0)).save(os.path.join(dest, "frame.png"))
    for name in ("background", "frame"):
        with open(os.path.join(dest, name + ".png.mcmeta"), "w") as f:
            json.dump(meta(BORDER), f, indent=4)
    if len(sys.argv) > 2:  # preview, 4x, on a game-like backdrop
        back = Image.new("RGBA", (SIZE + 40, SIZE + 40), (110, 110, 110, 255))
        back.alpha_composite(bg, (20, 20))
        back.resize(((SIZE + 40) * 4, (SIZE + 40) * 4), Image.NEAREST).save(sys.argv[2])


if __name__ == "__main__":
    main()

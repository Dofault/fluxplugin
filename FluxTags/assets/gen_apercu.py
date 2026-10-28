"""FluxTags: preview of the tag styles (2026-10-28), to choose one before generating every tag.

Each style shown on the job tag « Rochiste » at its 6 tiers (every 50 levels: Commun 50 ... Fabuleux 300), plus a rank
tag and the « Bêta » tag.

    <venv with Pillow>/python3 gen_apercu.py <preview.png>
"""
import os
import sys

from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from tag_styles import RARITIES, bordure, gemme, plein, text, text_width  # noqa: E402

WHITE = (255, 255, 255, 255)


def sheet(path):
    styles = [("1. Bordure", bordure), ("2. Plein", plein), ("3. Gemme", gemme)]
    scale = 4
    rows = []
    for title, fn in styles:
        tags = [fn("Rochiste", colour, i) for i, (_, colour) in enumerate(RARITIES)]
        tags.append(fn("Éminent", "4e72e6", 3))
        tags.append(fn("Bêta", "b376e5", 2))
        rows.append((title, tags))
    width = max(sum(t.width * scale + 12 for t in tags) for _, tags in rows) + 24
    height = len(rows) * (9 * scale + 40) + 20
    out = Image.new("RGBA", (width, height), (40, 32, 55, 255))
    y = 14
    for title, tags in rows:
        label = Image.new("RGBA", (text_width(title) + 2, 7), (0, 0, 0, 0))
        text(label, title, label.width // 2, 0, colour=WHITE)
        out.alpha_composite(label.resize((label.width * 2, label.height * 2), Image.NEAREST), (12, y))
        x = 12
        for t in tags:
            out.alpha_composite(t.resize((t.width * scale, t.height * scale), Image.NEAREST), (x, y + 18))
            x += t.width * scale + 12
        y += 9 * scale + 40
    out.save(path)


if __name__ == "__main__":
    sheet(sys.argv[1])

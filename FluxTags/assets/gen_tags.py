#!/usr/bin/env python3
"""FluxTags: the ItemsAdder pack « fluxtags » (2026-10-28) and the background of the /tags menu.

Every tag is drawn in the style « plaque » since 2026-10-30 (tag_styles.plaque, 14 px high, from the « Éminent » tag
drawn by the owner), and fluxtags replaces the hand-made pack influtags:
  rang_<rank>          one per rank (FluxOffering ranks.yml, its colour)
  metier_<job>_<1..6>  one per EcoJobs job and tier (every 50 levels: Commun ... Fabuleux), with the tier gem
  beta, createur       special tags (permission)
  celibataire, en_couple, marie, divorce   tags of FluxFamily (fluxtags.api.Tags)
  LEGACY               the glyphs of influtags under their former ids (staff, ranks, towns, countries, rarities), so
                       every %img_<id>% of the other plugins keeps working once influtags is removed
Each FluxTags tag is in the pack:
  <id>                 an ItemsAdder glyph for %fluxtags_tag% in the chat and TAB (CHAT_H px high)
  menu sheet           every tag once in fluxtags:font/menu.png, read by 7 fonts of their own (2026-11-11):
                       fluxtags:r<1..6> places it on line 1..6 of the /tags menu (chest rows 0 to 5, gen_fonds.TAG_X)
                       and fluxtags:p on the rank line of the profile menu (PROFILE_Y), character 0xE000 + index
                       (third value of glyphs.yml). They replace 552 ItemsAdder glyphs (<id>_r<n>, rang_<rank>_p),
                       each read on its own by the client at every resource reload.
Background of /tags: gen_fonds.background (« rubans »).

    <venv with Pillow>/python3 gen_tags.py <plugins dir of the server>
Writes plugins/ItemsAdder/contents/fluxtags (textures, config.yml), contents/fluxtags_fonts (menu sheet and fonts, a
raw pack folder) and plugins/FluxTags/glyphs.yml (image sizes and sheet index),
then run /iazip. Moving from influtags: see keep_codepoints (raw characters used by plugins without PlaceholderAPI).
"""
import json
import os
import sys

from PIL import Image

import yaml

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from tag_styles import RARITIES, plaque  # noqa: E402
from gen_fonds import LIFT, ROWS, TAG_H, background, tag_top  # noqa: E402

SPECIALS = [("beta", "Bêta", "b376e5"), ("createur", "Créateur", "2bc4e8"),
            # FluxFamily (2026-10-30): relationship status (one tag « situation » showing one of the three) and divorced
            ("celibataire", "Célibataire", "8a9bb0"), ("en_couple", "En couple", "e060a0"), ("marie", "Marié", "f0a800"),
            ("divorce", "Divorcé", "a33a50")]
# glyphs of the former pack influtags, same ids: (id, name, colour); ranks are added from ranks.yml
LEGACY = [
    # staff (colours of the charter, names of the former images)
    ("guide", "Guide", "03d04a"), ("moderateur", "Modo", "00bfff"), ("supermoderateur", "S-Modo", "ba55d3"),
    ("operateur", "Opérateur", "ff4500"), ("admin", "Admin", "c62828"), ("dev", "Dev", "2bc4e8"),
    # town levels (Lands)
    ("lotissement", "Lotissement", "c2b280"), ("quartier", "Quartier", "9acd32"), ("banlieue", "Banlieue", "3cb371"),
    ("arrondissement", "Arrondissement", "20b2aa"), ("ville", "Ville", "4682b4"),
    ("agglomeration", "Agglomération", "9370db"), ("metropole", "Métropole", "e63946"),
    # country levels (id « nation » kept for the glyph, level renamed État on 2026-10-31: players only say « pays »)
    ("region", "Région", "40e0d0"), ("nation", "État", "1e90ff"), ("puissance", "Puissance", "ff8c00"),
    ("superpuissance", "Superpuissance", "b22222"),
    # rarities (ids of the former AdvancedEnchantments groups), colours of the job tiers
] + [(rid, name, colour) for rid, (name, colour) in zip(
    ["simple", "unique", "elite", "ultimate", "legendary", "fabled"], RARITIES)]
CHAT_H = 10        # height in the chat and in item lores (chosen in game on 2026-10-30), y_position CHAT_Y
CHAT_Y = 9
PROFILE_Y = 76     # window y of the rank tag of the profile (row 3 of the identity card, above the sections, vanilla theme)


def glyph(gid, path, height, y):
    return ["  %s:" % gid, "    show_in_gui: false", "    suggest_in_command: false", '    path: "tags/%s.png"' % path,
            "    scale_ratio: %d" % height, "    y_position: %d" % y]


SHEET_COLS = 16
MENU_BASE = 0xE000


def menu_fonts(plugins, images):
    """The menu sheet (every tag in a cell, padded with transparency: a glyph's width stops at its last opaque
    column) and its 7 fonts, one bitmap provider each, in a raw pack folder (copied as it is by ItemsAdder)."""
    root = os.path.join(plugins, "ItemsAdder", "contents", "fluxtags_fonts", "resourcepack", "assets", "fluxtags")
    os.makedirs(os.path.join(root, "textures", "font"), exist_ok=True)
    os.makedirs(os.path.join(root, "font"), exist_ok=True)
    cw, ch = max(im.width for im in images), max(im.height for im in images)
    rows = -(-len(images) // SHEET_COLS)
    sheet = Image.new("RGBA", (cw * SHEET_COLS, ch * rows), (0, 0, 0, 0))
    for k, im in enumerate(images):
        sheet.paste(im, ((k % SHEET_COLS) * cw, (k // SHEET_COLS) * ch + ch - im.height))
    sheet.save(os.path.join(root, "textures", "font", "menu.png"))
    chars = ["".join(chr(MENU_BASE + r * SHEET_COLS + c) if r * SHEET_COLS + c < len(images) else "\u0000"
                     for c in range(SHEET_COLS)) for r in range(rows)]
    # the cell is scaled to TAG_H as a whole: a tag shorter than the cell sits at its bottom, as tall as before
    tops = {"r%d" % (row + 1): tag_top(row) for row in range(ROWS)}
    tops["p"] = PROFILE_Y
    for name, top in tops.items():
        provider = {"type": "bitmap", "file": "fluxtags:font/menu.png", "ascent": 13 - top, "height": TAG_H,
                    "chars": chars}
        with open(os.path.join(root, "font", name + ".json"), "w", encoding="utf-8") as f:
            json.dump({"providers": [provider]}, f, ensure_ascii=False, separators=(",", ":"))


def keep_codepoints(plugins, ids):
    """Gives fluxtags:<id> the character influtags:<id> had in ItemsAdder's cache: plugins without PlaceholderAPI
    (CustomFishing, influrewards) write those characters as they are. Run before the first /iazip without influtags."""
    path = os.path.join(plugins, "ItemsAdder", "storage", "font_images_unicode_cache.yml")
    if not os.path.exists(path):
        return 0
    lines = open(path, encoding="utf-8").read().split("\n")
    old = {}
    for line in lines:
        if line.startswith("influtags:"):
            key, _, char = line.partition(": ")
            old[key[len("influtags:"):]] = char
    moved = [i for i in ids if i in old]
    if not moved:
        return 0
    drop = {"fluxtags:" + i for i in moved} | {"influtags:" + i for i in moved}
    lines = [l for l in lines if l.partition(": ")[0] not in drop]
    lines = [l for l in lines if l] + ["fluxtags:%s: %s" % (i, old[i]) for i in moved]
    open(path, "w", encoding="utf-8").write("\n".join(lines) + "\n")
    return len(moved)


def jobs(plugins):
    folder = os.path.join(plugins, "EcoJobs", "jobs")
    out = []
    for f in sorted(os.listdir(folder)):
        if f.endswith(".yml") and not f.startswith("_"):
            name = yaml.safe_load(open(os.path.join(folder, f))).get("name", f[:-4])
            out.append((f[:-4], strip(name)))
    return out


def ranks(plugins):
    data = yaml.safe_load(open(os.path.join(plugins, "FluxOffering", "ranks.yml")))
    data = data.get("ranks", data)
    return [(k, v["name"], v.get("colour", "#c0c0c0").lstrip("#")) for k, v in data.items()
            if isinstance(v, dict) and "name" in v]


def strip(text):
    import re
    return re.sub(r"&#[0-9A-Fa-f]{6}|&[0-9a-fk-or]", "", text)


def main(plugins):
    dest = os.path.join(plugins, "ItemsAdder", "contents", "fluxtags")
    tex = os.path.join(dest, "textures", "tags")
    os.makedirs(tex, exist_ok=True)
    tags = []
    rank_list = ranks(plugins)
    for rid, name, colour in rank_list:
        tags.append(("rang_" + rid, plaque(name, colour)))
    for jid, name in jobs(plugins):
        for tier, (_, colour) in enumerate(RARITIES):
            tags.append(("metier_%s_%d" % (jid, tier + 1), plaque(name, colour, tier)))
    for sid, name, colour in SPECIALS:
        tags.append((sid, plaque(name, colour)))
    lines = ["# Generated by plugins/fluxplugin/FluxTags/assets/gen_tags.py: do not edit by hand.",
             "info:", "  namespace: fluxtags", "", "font_images:"]
    for gid, im in tags:
        im.save(os.path.join(tex, gid + ".png"))
        lines += glyph(gid, gid, CHAT_H, CHAT_Y)
    menu_fonts(plugins, [im for _, im in tags])
    legacy = [rid for rid, _, _ in rank_list if rid not in {i for i, _, _ in LEGACY}]
    lines.append("  # former influtags glyphs, same ids (%img_<id>%)")
    for rid in legacy:  # rank glyphs: the image of rang_<rank>
        lines += glyph(rid, "rang_" + rid, CHAT_H, CHAT_Y)
    for lid, name, colour in LEGACY:
        plaque(name, colour).save(os.path.join(tex, lid + ".png"))
        lines += glyph(lid, lid, CHAT_H, CHAT_Y)
    os.makedirs(os.path.join(dest, "textures", "gui"), exist_ok=True)
    widest = max(round(im.width * TAG_H / im.height) for _, im in tags)
    try:  # old-theme background (the vanilla theme draws its own): kept as it is when its source cannot be read
        background(widest).save(os.path.join(dest, "textures", "gui", "menu_tags.png"))
    except OSError as e:
        print("menu_tags.png not redrawn (%s), existing file kept" % e)
    lines += ["  menu_tags:", "    show_in_gui: false", "    suggest_in_command: false",
              '    path: "gui/menu_tags.png"', "    scale_ratio: 221", "    y_position: %d" % (13 + LIFT)]
    open(os.path.join(dest, "config.yml"), "w").write("\n".join(lines) + "\n")
    # size of every tag image file: FluxTags places the menu images with it
    data = os.path.join(plugins, "FluxTags")
    os.makedirs(data, exist_ok=True)
    open(os.path.join(data, "glyphs.yml"), "w").write(
        "# Generated by FluxTags/assets/gen_tags.py: size in px of each tag image file [width, height].\n"
        + "".join("%s: [%d, %d, %d]\n" % (gid, im.width, im.height, k) for k, (gid, im) in enumerate(tags)))
    kept = keep_codepoints(plugins, legacy + [i for i, _, _ in LEGACY])
    print(len(tags), "tags,", len(legacy) + len(LEGACY), "former influtags glyphs (%d characters kept) ->" % kept, dest)


if __name__ == "__main__":
    main(sys.argv[1])

#!/usr/bin/env python3
"""Preview of a menu with the vanilla theme, from its YAML (same rules as Flux's MenuLoader.themed): bare window,
name on the plate, a frame under every slot the menu uses, former buttons replaced, item pictures when found.

    <venv with Pillow, PyYAML>/python3 preview_menu.py <plugins dir> <out.png> <menu.yml> [page id]
"""
import os
import sys

import yaml
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import gen_ui  # noqa: E402  (game slot positions, sprites)
import ui  # noqa: E402

TEXTURE_DIRS = []


def slots_of(raw, shift, top):
    out = []
    if raw is None:
        return out
    parts = raw if isinstance(raw, list) else str(raw).split(",")
    for part in parts:
        p = str(part).strip()
        if not p:
            continue
        if "-" in p[1:]:
            a, b = p.split("-", 1)
            rng = range(min(int(a), int(b)), max(int(a), int(b)) + 1)
        else:
            rng = [int(p)]
        for s in rng:
            out.append(min(top - 1, s + shift * 9) if s < top else s)
    return out


def texture(material):
    m = (material or "").lower()
    if m.startswith("itemsadder:"):
        ns, iid = m.split(":")[1:3]
        cands = [os.path.join(d, ns) for d in TEXTURE_DIRS]
        for c in cands:
            for root, _, files in os.walk(c):
                for f in files:
                    if f[:-4] == iid or f[:-4] == iid.replace("btn_", ""):
                        return os.path.join(root, f)
        return None
    name = m.replace("minecraft:", "")
    for d in TEXTURE_DIRS:
        for root, _, files in os.walk(os.path.join(d, "itemshopplus")):
            if name + ".png" in files:
                return os.path.join(root, name + ".png")
    return None


def render(plugins, menu_file, page_id=None):
    TEXTURE_DIRS.append(os.path.join(plugins, "ItemsAdder", "contents"))
    yml = yaml.safe_load(open(menu_file, encoding="utf-8"))
    flux_cfg = yaml.safe_load(open(os.path.join(plugins, "Flux", "config.yml"), encoding="utf-8"))
    menu_id = os.path.basename(menu_file)[:-4]
    theme = yml.get("theme") or ((flux_cfg.get("theme") or {}).get("menus") or {}).get(menu_id) or {}
    mapping = {k.lower(): v for k, v in ((flux_cfg.get("theme") or {}).get("buttons") or {}).items()}
    rows = yml.get("rows", 6)
    top = rows * 9

    def used(sh):
        out = set()
        for sec in [yml] + list((yml.get("pages") or {}).values()):
            for l in (sec.get("lists") or {}).values():
                out |= set(slots_of(l.get("slots"), sh, top))
            for it in (sec.get("items") or {}).values():
                if isinstance(it, dict):
                    out |= set(slots_of(it.get("slots", it.get("slot")), sh, top))
            for w in (sec.get("widgets") or {}).values():
                if isinstance(w, dict) and w.get("type") == "pager":
                    out |= {w["prev"], w["next"]}
        return out

    def span(sl):
        chest = [s // 9 for s in sl if s < top]
        rws = chest + [6 + min(3, (s - top) // 9) for s in sl if s >= top]
        first, last = (min(chest) if chest else 5), (max(rws) if rws else 5)
        if yml.get("player-inventory", "hidden") != "hidden":
            last = 9
        return first, max(first, last)

    if isinstance(theme.get("shift"), int):
        shift = theme["shift"]
    else:
        f0, l0 = span(used(0))
        shift = rows - 1 - l0 if l0 < rows - 1 else 0
    first, last = span(used(shift))
    generic = os.path.join(HERE, "menu_vanilla.png")
    if theme.get("background"):
        im = gen_ui.BACKGROUNDS[theme["background"][3:]](generic)
    else:
        im = gen_ui.bg_window(generic, first, last)
    d = ImageDraw.Draw(im, "RGBA")
    if theme.get("title"):
        ui.write(im, theme["title"], 88, 5 + 18 * first, gen_ui.INK, None)
    sections = [yml]
    if page_id and page_id in (yml.get("pages") or {}):
        sections.append(yml["pages"][page_id])
    framed, drawn = set(), []
    skip = set(slots_of(theme.get("no-frame"), shift, top))
    for sec in sections:
        for lid, l in (sec.get("lists") or {}).items():
            for s in slots_of(l.get("slots"), shift, top):
                framed.add(s)
        for key, it in (sec.get("items") or {}).items():
            mat = mapping.get(str(it.get("material", "")).lower(), it.get("material", ""))
            ss = slots_of(it.get("slots", it.get("slot")), shift, top)
            if "fluxui:btn_" not in mat and "invisible" not in mat:
                framed.update(ss)
            for s in ss:
                drawn.append((s, mat))
    dark = theme.get("frames", "vanilla") == "dark"
    if theme.get("frames", "vanilla") != "none":
        sprite = gen_ui.sprite_slot("dark" if dark else "vanilla")
        for s in framed - skip:
            x, y = gen_ui.game_objet(s, rows)
            im.alpha_composite(sprite, (x - 1, y - 1))
    for s, mat in drawn:
        p = texture(mat)
        x, y = gen_ui.game_objet(s, rows)
        if p:
            t = Image.open(p).convert("RGBA")
            if t.size != (16, 16):
                t = t.resize((16, 16), Image.NEAREST if t.width < 16 else Image.LANCZOS)
            im.alpha_composite(t, (x, y))
    for sec in sections:          # list slots: a grey placeholder dot
        for lid, l in (sec.get("lists") or {}).items():
            for s in slots_of(l.get("slots"), shift, top):
                x, y = gen_ui.game_objet(s, rows)
                d.rectangle((x + 5, y + 5, x + 10, y + 10), fill=(110, 110, 110, 255))
    return im


if __name__ == "__main__":
    plugins, out, menu = sys.argv[1:4]
    render(plugins, menu, sys.argv[4] if len(sys.argv) > 4 else None).resize((528, 663), Image.NEAREST).save(out)

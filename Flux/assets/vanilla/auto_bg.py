"""Backgrounds of the themed menus (2026-10-30): the window fitted to the rows the menu uses, a slot frame under its
content and its name engraved on the plate, all in one picture (fluxui:bg_auto_<menu>, and bg_auto_<menu>__<page>
for a page that adds slots or rows). Flux then only shows the picture.

Same rules as Flux's MenuLoader (themed, framed, rowSpan, shift): change both together.
"""
import glob
import os

import yaml
from PIL import ImageDraw

import ui

INK = (64, 64, 64, 255)


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
            try:
                rng = [int(p)]
            except ValueError:
                continue
        for s in rng:
            out.append(min(top - 1, s + shift * 9) if s < top else s)
    return out


class Section:
    """Buttons (material, slots, generated or not) and list slots of a menu or a page."""

    def __init__(self, sec, shift, top, extra_widgets=None):
        self.buttons, self.lists = [], []
        for key, it in (sec.get("items") or {}).items():
            if isinstance(it, dict):
                self.buttons.append((str(key), str(it.get("material", "")), slots_of(it.get("slots", it.get("slot")), shift, top)))
        for l in (sec.get("lists") or {}).values():
            if isinstance(l, dict):
                self.lists.append(slots_of(l.get("slots"), shift, top))
        for widgets in [sec.get("widgets") or {}] + ([extra_widgets] if extra_widgets else []):
            for key, w in widgets.items():
                if not isinstance(w, dict):
                    continue
                if w.get("type") == "pager":       # its click areas (generated, never framed)
                    p, n = slots_of(w.get("prev"), shift, top), slots_of(w.get("next"), shift, top)
                    self.buttons.append(("__" + key, "invisible", p + [p[0] + 1] + [n[0] - 1] + n))
                elif w.get("type") == "bar" and isinstance(w.get("tooltip"), dict):
                    self.buttons.append(("__" + key, "invisible", slots_of(w["tooltip"].get("slots"), shift, top)))

    def all_slots(self):
        out = [s for _, _, ss in self.buttons for s in ss]
        for l in self.lists:
            out += l
        return out


def row_span(slot_sets, top, hidden):
    first, last = 5, 0
    for s in slot_sets:
        row = s // 9 if s < top else 6 + min(3, (s - top) // 9)
        if s < top:
            first = min(first, row)
        last = max(last, row)
    if not hidden:
        last = 9
    return first, max(first, last)


def framed(section, mapping, content, skip, force):
    out = set()
    for l in section.lists:
        out |= set(l)
    for key, mat, ss in section.buttons:
        m = mapping.get(mat.lower(), mat).lower()
        picture = m.startswith("itemsadder:") and m[len("itemsadder:"):].split(":", 1)[0] not in content
        if picture or "invisible" in m or key.startswith("__"):
            continue
        out |= set(ss)
    return (out | set(force)) - set(skip)


def load_pages(menu_file, yml):
    pages = dict(yml.get("pages") or {})
    for rel in yml.get("page-files") or []:
        f = os.path.join(os.path.dirname(menu_file), rel)
        if os.path.isfile(f):
            pages.update((yaml.safe_load(open(f, encoding="utf-8")) or {}).get("pages") or {})
    return pages


def build(plugins, window, generic):
    """{picture name: Image} for every themed menu without its own background."""
    cfg = yaml.safe_load(open(os.path.join(plugins, "Flux", "config.yml"), encoding="utf-8"))
    theme_cfg = cfg.get("theme") or {}
    mapping = {}
    restyled = os.path.join(plugins, "Flux", "theme-buttons.yml")
    if os.path.isfile(restyled):
        mapping.update({k.lower(): v for k, v in (yaml.safe_load(open(restyled, encoding="utf-8")) or {}).items()})
    mapping.update({k.lower(): v for k, v in (theme_cfg.get("buttons") or {}).items()})
    content = {n.lower() for n in theme_cfg.get("content-namespaces") or []}
    files = {}
    for f in glob.glob(os.path.join(plugins, "*", "menus", "*.yml")):
        if "/FluxMenu/" in f:
            continue
        files.setdefault(os.path.basename(f)[:-4], f)
    out = {}
    for mid, theme in (theme_cfg.get("menus") or {}).items():
        if mid not in files or not isinstance(theme, dict) or theme.get("background"):
            continue
        yml = yaml.safe_load(open(files[mid], encoding="utf-8")) or {}
        rows = yml.get("rows", 6)
        top = rows * 9
        hidden = str(yml.get("player-inventory", "hidden")).lower() == "hidden"
        pages = load_pages(files[mid], yml)
        if isinstance(theme.get("shift"), int):
            shift = theme["shift"]
        else:
            plain = Section(yml, 0, top)
            slots = plain.all_slots()
            for p in pages.values():
                slots += Section(p, 0, top).all_slots()
            _, last = row_span(slots, top, hidden)
            shift = rows - 1 - last if last < rows - 1 else 0
        menu = Section(yml, shift, top, theme.get("widgets"))
        skip = slots_of(theme.get("no-frame"), shift, top)
        force = slots_of(theme.get("frame"), shift, top)
        base_frames = framed(menu, mapping, content, skip, force)
        base_span = row_span(menu.all_slots(), top, hidden)

        def picture(span, frames):
            im = window(generic, span[0], span[1])
            d = ImageDraw.Draw(im, "RGBA")
            if theme.get("title"):
                ui.write(im, theme["title"], 88, 5 + 18 * span[0], INK, None)
            if not hidden:
                frames = set(frames) | set(range(top, top + 36))
            if theme.get("frames", "vanilla") != "none":
                kind = "dark" if theme.get("frames") == "dark" else "vanilla"
                for s in sorted(frames):
                    ui.slot(d, s, kind)
            return im

        out[f"auto_{mid}"] = picture(base_span, base_frames)
        for pid, p in pages.items():
            page = Section(p, shift, top)
            span = row_span(menu.all_slots() + page.all_slots(), top, hidden)
            frames = base_frames | framed(page, mapping, content, skip, [])
            if span != base_span or frames != base_frames:
                out[f"auto_{mid}__{str(pid).lower()}"] = picture(span, frames)
    return out

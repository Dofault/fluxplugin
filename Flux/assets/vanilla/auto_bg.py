"""Backgrounds of the themed menus (2026-10-30): the window fitted to the rows the menu uses, its content and its
name on the plate, all in one picture (fluxui:bg_auto_<menu>, and bg_auto_<menu>__<page> for a page that adds slots
or rows). Flux then only shows the picture.

Inventory style (2026-11-15): no slot frame under the content any more. Lists and groups of framed slots sit in dark
wells (one per rectangle of the group; a lone button, or a lone cell stuck to a grid, stays on the ground); a well side is pulled in by 1 px where a
used slot stands right next to it. When a hidden-inventory menu uses both the chest and the inventory rows, the
inventory's separator line runs between them. Title white with its shadow.

Same rules as Flux's MenuLoader (themed, framed, rowSpan, shift): change both together.
"""
import glob
import os

import yaml
from PIL import ImageDraw

import ui

INK = (64, 64, 64, 255)


def block_of(row):
    """Rows that touch: the chest (0-5), the inventory (6-8), the hotbar (9)."""
    return 0 if row < 6 else (1 if row < 9 else 2)


def cell(s, top):
    """(row, column) of a slot: chest rows 0-5, inventory rows 6-8, hotbar 9."""
    if s < top:
        return s // 9, s % 9
    i = s - top
    return 6 + min(3, i // 9), i % 9


def rectangles(cells):
    """Rectangles (r0, c0, r1, c1) covering a set of cells: runs of each row, stacked when they match."""
    rows = {}
    for r, c in cells:
        rows.setdefault(r, []).append(c)
    runs = []
    for r in sorted(rows):
        cs = sorted(rows[r])
        start = prev = cs[0]
        for c in cs[1:] + [None]:
            if c is not None and c == prev + 1:
                prev = c
                continue
            runs.append([r, start, r, prev])
            if c is not None:
                start = prev = c
    out = []
    for r0, c0, r1, c1 in runs:
        for o in out:
            if o[1] == c0 and o[3] == c1 and o[2] == r0 - 1 and block_of(o[2]) == block_of(r0):
                o[2] = r0
                break
        else:
            out.append([r0, c0, r1, c1])
    return out


def groups(cells):
    """Connected groups of cells (4 neighbours, inside one block of rows)."""
    left, out = set(cells), []
    while left:
        stack, g = [left.pop()], set()
        while stack:
            r, c = stack.pop()
            g.add((r, c))
            for n in ((r + 1, c), (r - 1, c), (r, c + 1), (r, c - 1)):
                if n in left and block_of(n[0]) == block_of(r):
                    left.remove(n)
                    stack.append(n)
        out.append(g)
    return out


def wells(d, framed_slots, list_slots, used, top, rows, pad=2, im=None):
    """Dark wells around the framed content, 1 px off any used slot right next to a side. The rectangles of one
    group are drawn as one shape (their union, one outline), never as overlapping wells."""
    cells = {cell(s, top) for s in framed_slots}
    busy = {cell(s, top) for s in used}
    by_cell = {cell(s, top): s for s in framed_slots}
    for g in groups(cells):
        if len(g) == 1:
            continue                       # a lone button or list cell stands on the ground (no slot tile)
        # a lone cell stuck to a grid stays on the ground (no tab on the well, no slot-sized well)
        rects = [r for r in rectangles(g) if (r[0], r[1]) != (r[2], r[3])]
        drawn = {(r, c) for r0, c0, r1, c1 in rects for r in range(r0, r1 + 1) for c in range(c0, c1 + 1)}
        near = lambda cs: any(x in busy and x not in drawn for x in cs)
        boxes = []
        for r0, c0, r1, c1 in rects:
            a, b = ui.objet(by_cell[(r0, c0)], rows), ui.objet(by_cell[(r1, c1)], rows)
            x0, y0, x1, y1 = a[0] - 1 - pad, a[1] - 1 - pad, b[0] + 16 + pad, b[1] + 16 + pad
            if near([(r, c0 - 1) for r in range(r0, r1 + 1)]):
                x0 += 1
            if near([(r, c1 + 1) for r in range(r0, r1 + 1)]):
                x1 -= 1
            if block_of(r0 - 1) == block_of(r0) and near([(r0 - 1, c) for c in range(c0, c1 + 1)]):
                y0 += 1
            if block_of(r1 + 1) == block_of(r1) and near([(r1 + 1, c) for c in range(c0, c1 + 1)]):
                y1 -= 1
            boxes.append((max(x0, 6), y0, min(x1, ui.W - 7), y1))    # never against the window's frame
        union_well(im, boxes)


def union_well(im, boxes):
    """One well over several boxes: their union filled, one black edge around it, the shade line under it."""
    from PIL import Image
    mask = Image.new("L", im.size, 0)
    md = ImageDraw.Draw(mask)
    for b in boxes:
        md.rounded_rectangle(b, radius=3, fill=255)
    m = mask.load()
    px = im.load()
    w, h = im.size
    inside = lambda x, y: 0 <= x < w and 0 <= y < h and m[x, y]
    for y in range(h):
        for x in range(w):
            if not m[x, y]:
                if inside(x, y - 1) and inside(x - 1, y - 1) and inside(x + 1, y - 1):
                    px[x, y] = ui.SHADE_LINE
                continue
            edge = not (inside(x - 1, y) and inside(x + 1, y) and inside(x, y - 1) and inside(x, y + 1))
            px[x, y] = ui.OUTLINE if edge else ui.WELL


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
    # plugins/<plugin>/menus, and plugins/<plugin>/<part>/menus for a plugin split in parts (FluxCommerce: auctionhouse/,
    # trade/); without the second pattern the market menus had no picture, so no slot frames (2026-11-08)
    for f in sorted(glob.glob(os.path.join(plugins, "*", "menus", "*.yml"))) + \
            sorted(glob.glob(os.path.join(plugins, "*", "*", "menus", "*.yml"))):
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

        def picture(span, frames, sections):
            im = window(generic, span[0], span[1], rows=rows, title=theme.get("title"))
            d = ImageDraw.Draw(im, "RGBA")
            used = set()
            list_slots = set()
            for sec in sections:
                used |= set(sec.all_slots())
                for l in sec.lists:
                    list_slots |= set(l)
            if not hidden:
                frames = set(frames) | set(range(top, top + 36))
                list_slots |= set(range(top, top + 36))
            if theme.get("frames", "vanilla") != "none":
                wells(d, frames, list_slots & set(frames), used, top, rows, im=im)
            if hidden and any(s < top for s in used) and any(s >= top for s in used):
                lift = 18 * (6 - rows)
                ui.separator(im, (ui.objet(top - 1, rows)[1] + 16 + ui.objet(top, rows)[1]) // 2 - 1)
            return im

        out[f"auto_{mid}"] = picture(base_span, base_frames, [menu])
        for pid, p in pages.items():
            page = Section(p, shift, top)
            span = row_span(menu.all_slots() + page.all_slots(), top, hidden)
            frames = base_frames | framed(page, mapping, content, skip, [])
            if span != base_span or frames != base_frames:
                out[f"auto_{mid}__{str(pid).lower()}"] = picture(span, frames, [menu, page])
    return out

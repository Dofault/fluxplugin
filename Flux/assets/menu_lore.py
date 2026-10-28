"""Lore of a menu button in the tags of Flux's style.yml, for the menu generators (assets/*.py).

    from menu_lore import lore
    lore(["Niveau : &f%x%", "Le niveau de la ville augmente", "ses limites."], [(GAUCHE, "Ouvrir")])
    -> ["@kv Niveau | %x%", "@desc Le niveau de la ville augmente ses limites.", "@left Ouvrir"]

Rules, in this order, for each description line:
  - ""                          paragraph break (the next line never continues the previous one)
  - "@..."                      a tag, kept as is
  - green / red / gold first    @ok / @ko / @info
  - "Key : value"               @kv (key starting with a capital or a digit)
  - "%..._filtre_/tri_/filter_line_...%" alone: @option
  - another colour first        @text (keeps its colour)
  - anything else               @desc; a line going on with the previous sentence (it starts with a lower-case
                                letter, or the previous one stops on a lower-case letter or a comma) is joined to
                                it, Flux wraps it to the width (a lower-case line after a « Key : value » ends
                                that value). The text colour written inside a line becomes <base>.
Actions: (icon, label) pairs or "icon label" strings; the icon tells the click (%img_left_click%, Maj + ...).
"""
import re

TEXT = "&#B376E5"
OK = ("#2ecf7b", "#7be0a1")
KO = ("#f0716f", "#ff5c5c", "#a89ba0")
INFO = ("#ffd94d", "#ff4d6d")
TEXT_COLOURS = ("#b376e5", "#ffd6de", "&f", "")

CODES = re.compile(r"^((?:&r|&#[0-9A-Fa-f]{6}|&[0-9a-fk-orA-FK-OR]|\s)*)")
COLOUR = re.compile(r"&#[0-9A-Fa-f]{6}|&[0-9a-fA-F]")
KV = re.compile(r"^\s*([A-ZÀ-Ý0-9][^&%:|]{0,39}?) : (.+)$", re.S)
ACTION = re.compile(r"^(?:&r|&#[0-9A-Fa-f]{6}|&[0-9a-fk-orA-FK-OR]|\s)*(Maj \+ |⇧\s*)?%img_(left|right)_click%\s*(.*)$", re.S)
EDGE = r"(?:<base>|&#[0-9A-Fa-f]{6}|&[0-9a-fk-or]|\s)+"


def _colour(codes):
    found = COLOUR.findall(codes)
    if not found:
        return ""
    c = found[-1].lower()
    return c[1:] if c.startswith("&#") else c


def _based(text, base):
    return re.sub(re.escape(base), "<base>", text, flags=re.I)


def _continues(previous, body, key_value):
    first = re.sub("^" + EDGE, "", body)[:1]
    if first.islower():
        return True
    last = re.sub(EDGE + "$", "", previous)[-1:]
    return not key_value and (last.islower() or last == ",")


def _tag(line, base):
    """(tag, kind) of one description line."""
    if line.startswith("@"):
        return line, "tag"
    codes = CODES.match(line).group(1)
    body = line[len(codes):]
    colour = _colour(codes)
    if colour in OK:
        return "@ok " + body, "state"
    if colour in KO:
        return "@ko " + body, "state"
    if colour in INFO:
        return "@info " + body, "state"
    if colour not in TEXT_COLOURS:
        return "@text " + line, "text"
    if not codes.strip() and body.startswith("%") and body.endswith("%") and body.count("%") == 2:
        if re.search(r"_(filtre|tri|filter_line)_", body):
            return "@option " + body, "option"
        return "@desc " + body, "desc"
    kv = KV.match(body)
    if kv and not kv.group(1).strip().endswith("."):
        value = kv.group(2)
        return "@kv %s | %s" % (kv.group(1).strip(), value[2:] if value.startswith("&f") else value), "kv"
    return "@desc " + _based(body, base), "desc"


def action(icon, label=None):
    """One action line: (icon, label), or the whole « icon label » text."""
    text = icon if label is None else icon + label
    a = ACTION.match(text)
    if not a:
        return "@raw " + text
    click = ("shift-" if a.group(1) else "") + a.group(2)
    return "@%s %s" % (click, CODES.sub("", a.group(3), count=1).strip())


def lore(desc=(), actions=(), style=None, base=TEXT):
    """Tags of a button: description lines (see the rules above), then the actions."""
    out = ["@style " + style] if style else []
    joinable = False
    for line in desc:
        if not line:
            joinable = False
            continue
        tag, kind = _tag(line, base)
        body = line[len(CODES.match(line).group(1)):]
        if joinable and kind in ("desc", "kv") and out:
            previous_kv = out[-1].startswith("@kv ")
            if (previous_kv and kind == "desc" and body[:1].islower()) or \
                    (not previous_kv and _continues(out[-1], body, kind == "kv")):
                out[-1] += " " + _based(body, base)
                continue
        out.append(tag)
        joinable = kind in ("desc", "kv")
    for a in actions:
        out.append(action(*a) if isinstance(a, tuple) else action(a))
    return out

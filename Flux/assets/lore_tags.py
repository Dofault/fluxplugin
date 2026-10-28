"""Converts hand-framed lore lines ("&#FFD94D│ ...", "└ %img_left_click% ...") into the style tags of Flux
(@desc, @kv, @item, @text, @ok, @ko, @info, @option, @left, @right, @shift-left, @shift-right, @raw), so the look
comes from plugins/Flux/style.yml.

Used two ways:
  - by every generator (assets/*.py) on the text it writes: lore_tags.convert_yaml(text)
  - once on hand-written menus: python3 lore_tags.py <file.yml>... (rewrites in place, prints a summary)

A lore is converted only when one of its lines starts with a frame (│ ├ └); its unframed lines become @raw.
"""
import json
import re
import sys

# palettes: colour of plain text, of states; a palette other than the default adds "@style <variant>"
PALETTES = {
    None: {"text": {"#b376e5", "&f", ""}, "ok": {"#2ecf7b"}, "ko": {"#f0716f", "#ff5c5c"}, "info": {"#ffd94d"},
           "base": "&#B376E5"},
    "outils": {"text": {"#ffd6de", "&f", ""}, "ok": {"#7be0a1"}, "ko": {"#a89ba0"}, "info": {"#ff4d6d"},
               "base": "&#FFD6DE"},
}
FRAME_COLOURS = {"#a33a50": "outils"}

FRAME = re.compile(r"^((?:&#[0-9A-Fa-f]{6}|&[0-9a-fk-orA-FK-OR])*)([│├└])(?: |$)(.*)$", re.S)
CODES = re.compile(r"^((?:&r|&#[0-9A-Fa-f]{6}|&[0-9a-fk-orA-FK-OR]|\s)*)")
ACTION = re.compile(r"^(?:&r|&#[0-9A-Fa-f]{6}|&[0-9a-fk-orA-FK-OR]|\s)*(Maj \+ |⇧\s*)?%img_(left|right)_click%\s*(.*)$", re.S)
KV = re.compile(r"^\s*([A-ZÀ-Ý0-9][^&%:|]{0,39}?) : (.+)$", re.S)
COLOUR = re.compile(r"&#[0-9A-Fa-f]{6}|&[0-9a-fA-F]")


def _last_colour(codes):
    found = COLOUR.findall(codes)
    if not found:
        return ""
    c = found[-1].lower()
    return c[1:] if c.startswith("&#") else c


def _strip_codes(text):
    return CODES.sub("", text, count=1)


def _based(body, palette):
    """The old text colour written in the middle of a line (« back to the text after a value ») becomes <base>: back
    to the colour of the block, whatever the style makes it."""
    base = PALETTES[palette]["base"]
    return re.sub(re.escape(base), "<base>", body, flags=re.I)


def _line(rest, palette):
    """One framed line (frame removed) to a tag, None to drop it."""
    if not rest.strip():
        return None
    if rest.startswith("@"):
        return rest
    a = ACTION.match(rest)
    if a:
        click = ("shift-" if a.group(1) else "") + a.group(2)
        return "@%s %s" % (click, _strip_codes(a.group(3)).strip())
    codes = CODES.match(rest).group(1)
    body = rest[len(codes):]
    colour = _last_colour(codes)
    p = PALETTES[palette]
    if colour in p["ok"]:
        return "@ok " + body
    if colour in p["ko"]:
        return "@ko " + body
    if colour in p["info"]:
        return "@info " + body
    if colour in p["text"]:
        if not codes.strip() and body.startswith("%"):
            if re.search(r"_(filtre|tri|filter_line)_", body):
                return "@option " + body
            if re.match(r"^%[^%]*_description%$", body):
                return "@desc " + body
            return "@text " + body
        if body.startswith("➤") or body.startswith("&8➤"):
            return "@item " + _strip_codes(body.lstrip("➤ ").lstrip("&8➤ "))
        kv = KV.match(body)
        if kv and not kv.group(1).strip().endswith("."):
            value = kv.group(2)
            if value.startswith("&f"):
                value = value[2:]
            return "@kv %s | %s" % (kv.group(1).strip(), value)
        return "@desc " + _based(body, palette)
    return "@text " + rest


def convert_lines(lines):
    """A lore (list of strings) in tags; returned as is when it has no framed line."""
    framed = [FRAME.match(l) for l in lines]
    if not any(framed):
        return _tidy(lines)
    palette = None
    for m in framed:
        if m and _last_colour(m.group(1)) in FRAME_COLOURS:
            palette = FRAME_COLOURS[_last_colour(m.group(1))]
    out = ["@style " + palette] if palette else []
    for line, m in zip(lines, framed):
        if m:
            tag = _line(m.group(3), palette)
            if tag is None:
                continue
            # a sentence left open on the previous description line goes on with this one
            rest = m.group(3)
            body = rest[len(CODES.match(rest).group(1)):]
            if out and out[-1].startswith("@desc ") and _continues(out[-1], body, tag.startswith("@kv ")) \
                    and (tag.startswith("@desc ") or tag.startswith("@kv ")):
                out[-1] += " " + _based(body, palette)
                continue
            out.append(tag)
        elif line.startswith("@"):
            out.append(line)  # already a tag (written by the generator)
        elif line.strip():
            out.append("@raw " + line)
    return _join_sentences(out)


def _tidy(lines):
    """A lore already in tags: old text colour inside descriptions as <base>, open sentences joined (idempotent)."""
    if not any(l.startswith("@desc ") for l in lines):
        return lines
    palette = next((l[7:].strip() for l in lines if l.startswith("@style ")), None)
    palette = palette if palette in PALETTES else None
    out = []
    for l in lines:
        if l.startswith("@desc "):
            body = _based(l[6:], palette)
            if out and out[-1].startswith("@desc ") and _continues(out[-1], body, False):
                out[-1] += " " + body
                continue
            l = "@desc " + body
        out.append(l)
    return out


def _continues(previous, body, key_value):
    """Does this line go on with the sentence of the previous description line? Yes when it starts with a lower-case
    letter, or when the previous one stops on a lower-case letter or a comma and this one is not a « Key : value »."""
    first = re.sub(r"^(?:<base>|&#[0-9A-Fa-f]{6}|&[0-9a-fk-or]|\s)+", "", body)[:1]
    if first.islower():
        return True
    last = re.sub(r"(?:<base>|&#[0-9A-Fa-f]{6}|&[0-9a-fk-or]|\s)+$", "", previous)[-1:]
    return not key_value and (last.islower() or last == ",")


def _join_sentences(tags):
    """A @desc line starting with a lower-case letter right after a @kv line ends that value's sentence."""
    out = []
    for t in tags:
        if t.startswith("@desc ") and out and out[-1].startswith("@kv ") and _strip_codes(t[6:])[:1].islower():
            out[-1] = out[-1] + " " + t[6:]
            continue
        out.append(t)
    return out


# ---------------------------------------------------------------- YAML text

ITEM = re.compile(r'^(\s*)- (.*)$')


def _scalar(raw):
    raw = raw.rstrip()
    if raw.startswith('"'):
        return json.loads(raw)
    if raw.startswith("'"):
        return raw[1:-1].replace("''", "'")
    return raw


def _quote(s):
    return json.dumps(s, ensure_ascii=False)


def convert_yaml(text):
    """Every block list under a « lore: » key converted (other content untouched)."""
    lines = text.split("\n")
    out, i = [], 0
    while i < len(lines):
        line = lines[i]
        out.append(line)
        m = re.match(r"^(\s*)lore:\s*$", line)
        i += 1
        if not m:
            continue
        indent = len(m.group(1))
        block = []
        while i < len(lines):
            it = ITEM.match(lines[i])
            if not it or len(it.group(1)) < indent:
                break
            block.append((it.group(1), _scalar(it.group(2))))
            i += 1
        if not block:
            continue
        converted = convert_lines([v for _, v in block])
        for v in converted:
            out.append("%s- %s" % (block[0][0], _quote(v)))
    return "\n".join(out)


def convert_file(path):
    text = open(path, encoding="utf-8").read()
    new = convert_yaml(text)
    if new != text:
        open(path, "w", encoding="utf-8").write(new)
        return True
    return False


if __name__ == "__main__":
    for path in sys.argv[1:]:
        print(("converted " if convert_file(path) else "unchanged ") + path)

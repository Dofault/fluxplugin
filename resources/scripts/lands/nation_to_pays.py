"""Lands locale: player word « nation » -> « pays » (masculine), commands /n and /nations -> /pays.
Keys, <v:...>/<t:...> tokens, %placeholders% and the words of a command (/v nation leave) are left as they are."""
import re
import sys

KEY = re.compile(r"^(\s*(?:- )?[A-Za-z0-9_.\-]+:)(?=\s|$)")
PROTECT = re.compile(r"<[vt]:[^<>]*>|%[^%\s]+%|[\w-]*nations?[._][\w.-]+|/[A-Za-z]+(?: [A-Za-z][\w-]*)+|/[A-Za-z]+")
RULES = [
    (r"\bune nouvelle nation\b", "un nouveau pays"), (r"\bUne nouvelle nation\b", "Un nouveau pays"),
    (r"\bde la nation\b", "du pays"), (r"\bDe la nation\b", "Du pays"),
    (r"\bà la nation\b", "au pays"), (r"\bÀ la nation\b", "Au pays"),
    (r"\bla nation\b", "le pays"), (r"\bLa nation\b", "Le pays"),
    (r"\bune nation\b", "un pays"), (r"\bUne nation\b", "Un pays"),
    (r"\bta nation\b", "ton pays"), (r"\bTa nation\b", "Ton pays"),
    (r"\bsa nation\b", "son pays"), (r"\bSa nation\b", "Son pays"),
    (r"\bma nation\b", "mon pays"), (r"\bMa nation\b", "Mon pays"),
    (r"\bcette nation\b", "ce pays"), (r"\bCette nation\b", "Ce pays"),
    (r"\baucune nation\b", "aucun pays"), (r"\bAucune nation\b", "Aucun pays"),
    (r"\bnouvelle nation\b", "nouveau pays"), (r"\bNouvelle nation\b", "Nouveau pays"),
    (r"\bnations alliées\b", "pays alliés"), (r"\bnations ennemies\b", "pays ennemis"),
    (r"\bnations supprimées\b", "pays supprimés"),
    (r"\bnations\b", "pays"), (r"\bNations\b", "Pays"), (r"\bNATIONS\b", "PAYS"),
    (r"\bnation\b", "pays"), (r"\bNation\b", "Pays"), (r"\bNATION\b", "PAYS"),
]
COMMANDS = [(re.compile(r"/nations?\b"), "/pays"), (re.compile(r"(?<![\w\\/])/n(?=[ \"<]|$)"), "/pays")]


def words(text):
    kept = []

    def hide(m):
        kept.append(m.group(0))
        return "\x00%d\x00" % (len(kept) - 1)

    t = PROTECT.sub(hide, text)
    for a, b in RULES:
        t = re.sub(a, b, t)
    return re.sub("\x00(\\d+)\x00", lambda m: kept[int(m.group(1))], t)


def line(raw):
    if raw.lstrip().startswith("#"):
        return raw
    for rx, rep in COMMANDS:
        raw = rx.sub(rep, raw)
    m = KEY.match(raw)
    if m:
        return m.group(1) + words(raw[m.end():])
    return words(raw)


src, dst = sys.argv[1], sys.argv[2]
out = [line(l) for l in open(src, encoding="utf-8").read().split("\n")]
open(dst, "w", encoding="utf-8").write("\n".join(out))

#!/usr/bin/env python3
"""Moves the pets' texts from their former own palette to the server's (2026-11-13).

    python3 restyle_palette.py <file or folder>...

Former palette: beige #EADBC8 text, cream #FFF4E0 values, slate #6B7A8F secondary and a "» " bullet before each
effect, coral #FF7A59 accents, teal #5FB3B3 gains, red #D9534F errors. Server palette: violet #B376E5 text, white
values, grey secondary, gold #FFD94D names and actions, green #2ECF7B gains, red #FF5C5C errors. Pet names keep their
rarity colour. Rewrites .yml, .py and .java files in place; prints the files changed.
"""
import os
import re
import sys

RULES = [
    (re.compile(r"&#6B7A8F»\s?", re.I), ""),
    (re.compile(r"&#EADBC8", re.I), "&#B376E5"),
    (re.compile(r"&#FFF4E0", re.I), "&f"),
    (re.compile(r"&#6B7A8F", re.I), "&7"),
    (re.compile(r"&#FF7A59", re.I), "&#FFD94D"),
    (re.compile(r"&#5FB3B3", re.I), "&#2ECF7B"),
    (re.compile(r"&#D9534F", re.I), "&#FF5C5C"),
]


def files(paths):
    for p in paths:
        if os.path.isdir(p):
            for root, _, names in os.walk(p):
                if "target" in root.split(os.sep) or "__pycache__" in root:
                    continue
                for n in names:
                    if n.endswith((".yml", ".py", ".java")) and n != os.path.basename(__file__):
                        yield os.path.join(root, n)
        else:
            yield p


def main():
    for f in files(sys.argv[1:]):
        text = open(f, encoding="utf-8").read()
        new = text
        for rule, repl in RULES:
            new = rule.sub(repl, new)
        if new != text:
            open(f, "w", encoding="utf-8").write(new)
            print(f)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Audit of the ItemsAdder resource pack of a server: what each content folder costs the client at load
and which of its font images / items are referenced nowhere else.

Usage: python3 scripts/pack/audit_pack.py [server_dir] [--ns NAME] [--unused]
  server_dir defaults to serveurs/04_labs. Read-only: it never deletes anything.

A reference is any occurrence of the id as a whole token (``%img_<id>%``, ``<ns>:<id>``, ``:<id>:``, plain
``<id>``) in the server's plugin configs (outside the namespace's own folder), in the Flux sources, or in
the Typewriter pages. Ids built at runtime (``"rang_" + name``) only show up through their prefix: the
report lists the unreferenced ids grouped by prefix, check those families by hand before archiving.
"""
import os, re, sys, collections

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TEXT_EXT = {".yml", ".yaml", ".json", ".txt", ".properties", ".conf", ".toml", ".java", ".kt", ".js", ".sk", ".mcfunction"}
SKIP_DIRS = {"output", "storage", "build", "logs", "cache", "libraries", "dynmap", "bluemap", "web", ".git", "target",
             "node_modules", "backups", "data", "playerdata", "userdata", "players"}
TOKEN = re.compile(r"[A-Za-z0-9_\-]+")


def definitions(contents):
    """namespace -> {"font_images": set, "items": set}, from every yml of the folder (top-level sections)."""
    out = collections.defaultdict(lambda: {"font_images": set(), "items": set()})
    for ns in sorted(os.listdir(contents)):
        base = os.path.join(contents, ns)
        if not os.path.isdir(base):
            continue
        out[ns]
        for dp, dn, fn in os.walk(base):
            for f in fn:
                if not f.endswith((".yml", ".yaml")):
                    continue
                section = None
                for line in open(os.path.join(dp, f), encoding="utf-8", errors="replace"):
                    if re.match(r"^\S", line):
                        section = line.split(":")[0].strip()
                    elif section in ("font_images", "items"):
                        m = re.match(r"^  ([A-Za-z0-9_\-]+):\s*$", line)
                        if m:
                            out[ns][section].add(m.group(1))
    return out


def corpus(paths):
    """file path -> token set, for every text file under the given roots."""
    files = {}
    for root in paths:
        for dp, dn, fn in os.walk(root):
            dn[:] = [d for d in dn if d not in SKIP_DIRS]
            for f in fn:
                if os.path.splitext(f)[1] not in TEXT_EXT:
                    continue
                p = os.path.join(dp, f)
                try:
                    if os.path.getsize(p) > 20_000_000:
                        continue
                    toks = set(TOKEN.findall(open(p, encoding="utf-8", errors="replace").read()))
                except OSError:
                    continue
                toks |= {t[4:] for t in toks if t.startswith("img_")}
                files[p] = toks
    return files


def cost(contents, ns):
    pngs = size = 0
    for dp, dn, fn in os.walk(os.path.join(contents, ns)):
        for f in fn:
            p = os.path.join(dp, f)
            size += os.path.getsize(p)
            pngs += f.endswith(".png")
    return pngs, size


def family(i):
    return re.sub(r"(_[a-z]?\d+)+$", "", i)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    server = os.path.abspath(args[0]) if args else os.path.join(ROOT, "serveurs", "04_labs")
    only = sys.argv[sys.argv.index("--ns") + 1] if "--ns" in sys.argv else None
    plugins = os.path.join(server, "plugins")
    contents = os.path.join(plugins, "ItemsAdder", "contents")
    defs = definitions(contents)
    roots = [plugins, os.path.join(ROOT, "plugins", "fluxplugin")]
    files = corpus(roots)
    rows = []
    for ns, d in defs.items():
        if only and ns != only:
            continue
        own = os.path.join(contents, ns) + os.sep
        other = set()
        for p, t in files.items():
            if not p.startswith(own):
                other |= t
        unused = {k: sorted(i for i in d[k] if i not in other) for k in d}
        pngs, size = cost(contents, ns)
        nsref = ns in other
        rows.append((ns, len(d["font_images"]), len(unused["font_images"]), len(d["items"]), len(unused["items"]),
                     pngs, size, nsref, unused))
    rows.sort(key=lambda r: -r[1])
    print(f"{'namespace':28} {'glyphs':>7} {'unused':>7} {'items':>6} {'unused':>7} {'png':>5} {'Ko':>7}  ns cité")
    for ns, g, gu, it, iu, pngs, size, nsref, _ in rows:
        print(f"{ns:28} {g:7} {gu:7} {it:6} {iu:7} {pngs:5} {size // 1024:7}  {'oui' if nsref else 'NON'}")
    print("\nChaque glyphe = 2 lectures d'image au chargement client (polices default + uniform).")
    if "--unused" in sys.argv:
        for ns, *_, unused in rows:
            for kind, ids in unused.items():
                if not ids:
                    continue
                fam = collections.Counter(family(i) for i in ids)
                print(f"\n[{ns}] {kind} non cités ({len(ids)}) par famille :")
                for f, n in fam.most_common():
                    print(f"  {f}* x{n}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Read-only checks of FluxPW's tables (flux_pw_warps, flux_pw_links) on a server, for the test plan of fluxpw.md.

    python3 check_store.py <labs|semirp|semilab> snapshot <file.json>   normalised copy of both tables
    python3 check_store.py <labs|semirp|semilab> import                 rows expected after the Labs test import
    python3 check_store.py compare <a.json> <b.json>                    same data? (SQLite / MariaDB round trip)

The engine is read from plugins/Flux/config.yml (database.type). SQLite: flux.db (and its -wal) is copied to a
temporary folder first, the live file is never opened. MySQL/MariaDB: read with the mariadb client, credentials of
the same config section. Nothing is ever written.
"""
import json
import os
import shutil
import sqlite3
import subprocess
import sys
import tempfile

ROOT = "/home/influencraft/serveurs"
FOLDERS = {"semirp": "03_semirp", "labs": "04_labs", "semilab": "05_semilab"}
TABLES = ("flux_pw_warps", "flux_pw_links")


def database(server):
    """{type, prefix, ...} of the database section of Flux's config (flat keys, no YAML library needed)."""
    conf, section = {}, False
    for line in open(os.path.join(ROOT, FOLDERS[server], "plugins", "Flux", "config.yml"), encoding="utf-8"):
        if line.startswith("database:"):
            section = True
            continue
        if section and line and not line[0].isspace() and not line.startswith("#"):
            break
        if section and ":" in line and not line.strip().startswith("#"):
            k, v = line.strip().split(":", 1)
            conf[k.strip()] = v.split(" #")[0].strip().strip('"')
    return conf


def rows(server):
    """{table: [row dict...]} sorted, values as text (the same for both engines)."""
    conf = database(server)
    prefix = conf.get("table-prefix", "")
    out = {}
    if conf.get("type", "sqlite").lower() in ("mysql", "mariadb"):
        for t in TABLES:
            cmd = ["mariadb", "-h", conf.get("host", "localhost"), "-P", conf.get("port", "3306"), "-u", conf.get("user", "root"),
                   "--batch", "--raw", conf.get("name", "influencraft"), "-e", f"SELECT * FROM {prefix}{t}"]
            if conf.get("password"):
                cmd.insert(1, "-p" + conf["password"])
            res = subprocess.run(cmd, capture_output=True, text=True)
            if res.returncode:
                sys.exit(res.stderr.strip())
            lines = res.stdout.rstrip("\n").split("\n")
            head = lines[0].split("\t") if lines and lines[0] else []
            out[t] = [{h: (None if v == "NULL" else v) for h, v in zip(head, l.split("\t"))} for l in lines[1:]]
    else:
        src = os.path.join(ROOT, FOLDERS[server], "plugins", "Flux", conf.get("file", "flux.db"))
        tmp = tempfile.mkdtemp()
        for suffix in ("", "-wal"):
            if os.path.exists(src + suffix):
                shutil.copy(src + suffix, os.path.join(tmp, "flux.db" + suffix))
        c = sqlite3.connect(os.path.join(tmp, "flux.db"))
        c.row_factory = sqlite3.Row
        for t in TABLES:
            try:
                out[t] = [{k: (None if r[k] is None else str(r[k])) for k in r.keys()} for r in c.execute(f"SELECT * FROM {prefix}{t}")]
            except sqlite3.OperationalError as e:
                sys.exit(f"{prefix}{t}: {e}")
        c.close()
        shutil.rmtree(tmp)
    for t in out:
        for r in out[t]:
            for k, v in r.items():
                # 1.0 (SQLite) and 1 (MariaDB) are the same number
                try:
                    if v is not None and "." in v:
                        r[k] = repr(float(v))
                except ValueError:
                    pass
        out[t].sort(key=lambda r: json.dumps(r, sort_keys=True))
    return out


def check_import(server):
    """Rows of the Labs test fixture (plugins/PlayerWarps/data.yml of 2026-10-30) after the migration."""
    data = rows(server)
    warps = {w["name"]: w for w in data["flux_pw_warps"]}
    links = data["flux_pw_links"]
    expect = {
        "vertuosa": {"owner": "d7d64130-708e-4b72-a82c-9e16e80ef0cb", "owner_name": "Amandinou621", "world": "valdor",
                     "category": "boutique", "status": "OPENED", "password": None, "admission": "100", "visits": "9",
                     "rating": "5", "featured_until": "1791917810657", "created": "1791657895767",
                     "icon": "NETHERITE_SWORD", "description": "Vente de tout type d'item pour le rankup", "display_name": None},
        "une_adresse": {"owner": "e37414db-a735-42f5-855c-eeb88d63e359", "owner_name": "Entr0pik", "world": "world",
                        "category": "all", "status": "OPENED", "password": None, "admission": "0", "visits": "0",
                        "rating": "0", "icon": None, "description": None, "display_name": None},
    }
    kinds = {}
    for l in links:
        kinds.setdefault((l["warp"], l["kind"]), []).append(l["player"])
    ok = True
    for name, fields in expect.items():
        w = warps.get(name)
        if w is None:
            print("MISSING warp", name)
            ok = False
            continue
        for k, v in fields.items():
            if w.get(k) != v:
                print(f"{name}.{k}: {w.get(k)!r}, expected {v!r}")
                ok = False
    v_id, u_id = "ff8c3b0e-3b56-46f2-b317-1d9af70fbd06", "0a25b586-0be3-40e8-901f-a03718833666"
    for key, n in {(v_id, "review"): 1, (v_id, "visit"): 5, (u_id, "block"): 1, (v_id, "favorite"): 1}.items():
        if len(kinds.get(key, [])) != n:
            print(f"links {key[1]} of {key[0][:8]}: {len(kinds.get(key, []))}, expected {n}")
            ok = False
    print(f"{len(warps)} warp(s), {len(links)} link(s) in total (fixture alone: 2 warps, 8 links)")
    print("import OK" if ok else "import NOT as expected")


def main():
    a = sys.argv[1:]
    if len(a) == 3 and a[0] == "compare":
        x, y = json.load(open(a[1])), json.load(open(a[2]))
        for t in TABLES:
            same = x.get(t) == y.get(t)
            print(t, len(x.get(t, [])), "/", len(y.get(t, [])), "rows,", "identical" if same else "DIFFERENT")
            if not same:
                for r in x.get(t, []):
                    if r not in y.get(t, []):
                        print("  only in", a[1], r)
                for r in y.get(t, []):
                    if r not in x.get(t, []):
                        print("  only in", a[2], r)
        return
    if len(a) >= 2 and a[0] in FOLDERS:
        if a[1] == "snapshot" and len(a) == 3:
            json.dump(rows(a[0]), open(a[2], "w"), indent=1, sort_keys=True, ensure_ascii=False)
            print("written", a[2])
            return
        if a[1] == "import":
            check_import(a[0])
            return
    sys.exit(__doc__)


if __name__ == "__main__":
    main()

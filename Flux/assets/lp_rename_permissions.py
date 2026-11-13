#!/usr/bin/env python3
"""Moves the LuckPerms groups of a server from the former permission nodes of the suite to the flux* ones.

One table for the whole suite (RENAMES below, most precise prefix first): playerwarps.* -> fluxpw.* (FluxPW,
2026-10-30, Labs done), then the review of 2026-11-13 (influpet.* -> fluxpets.*, parrainage.* -> fluxreferral.*...).
Same suffix, same value, same context (server=...), then the former node is removed. Run once per server, the day
the new jars run there (Labs: as soon as they are deployed; production: docs/technique/flux-mise-en-prod.md).

    python3 lp_rename_permissions.py <semirp|labs|semilab> [--dry-run]

Exports the LuckPerms data first (plugins/LuckPerms/audit-perms-avant-<date>.json.gz, the backup to restore with
"lp import" if needed), sends the commands through serveurs/00_scripts/rcon.py, exports again
(audit-perms-apres-<date>.json.gz) and checks that every node moved. Users' own nodes are listed, not moved.
"""
import gzip
import json
import os
import subprocess
import sys
import time

ROOT = "/home/influencraft/serveurs"
FOLDERS = {"semirp": "03_semirp", "labs": "04_labs", "semilab": "05_semilab", "events": "06_events"}

# (former prefix, new prefix): a node starting with the former prefix gets the new one, the rest of it unchanged.
RENAMES = [
    ("playerwarps.", "fluxpw."),
    ("influenchantsplitter.command", "fluxenchant.split"),
    ("influenchantsplitter.table", "fluxenchant.split.table"),
    ("influenchantsplitter.reload", "fluxenchant.admin"),
    ("influbridge.bottlexp", "fluxtools.bottlexp"),
    ("influbridge.repair", "fluxtools.repair"),
    ("influbridge.", "fluxwebsite."),
    ("fluxitems.admin", "fluxtools.admin"),
    ("fluxmenu.", "flux."),
    ("influpet.", "fluxpets."),
    ("influcasino.", "fluxcasino."),
    ("influrecompenses.", "fluxcalendar."),
    ("parrainage.", "fluxreferral."),
    ("supermarket.", "fluxshop."),
    ("offrandes.", "fluxoffering."),
    ("sortingchest.", "fluxsorter."),
    ("influtri.", "fluxsorter."),
    ("influshop.", "fluxshop."),
]


def renamed(key):
    """New node of a former one, None when the node is not renamed."""
    low = key.lower()
    for old, new in RENAMES:
        if low.startswith(old):
            return new + low[len(old):]
    return None


def rcon(server, command):
    subprocess.run(["python3", os.path.join(ROOT, "00_scripts", "rcon.py"), server, command], capture_output=True, text=True)


def export(server, name):
    name += time.strftime("-%Y%m%d-%H%M%S")
    path = os.path.join(ROOT, FOLDERS[server], "plugins", "LuckPerms", name + ".json.gz")
    rcon(server, "lp export " + name)
    for _ in range(60):
        time.sleep(1)
        if os.path.exists(path):
            time.sleep(1)
            return json.load(gzip.open(path))
    sys.exit("LuckPerms export not written: " + path)


def group_nodes(data):
    """(group, key, context json, value) of every group node with a former name."""
    out = set()
    for group, obj in data.get("groups", {}).items():
        for n in obj.get("nodes", []):
            if renamed(n["key"]) is not None:
                out.add((group, n["key"], json.dumps(n.get("context") or {}, sort_keys=True), n.get("value", True)))
    return out


def main():
    if len(sys.argv) < 2 or sys.argv[1] not in FOLDERS:
        sys.exit(__doc__)
    server, dry = sys.argv[1], "--dry-run" in sys.argv
    before = export(server, "audit-perms-avant")
    commands = []
    for group, key, context, value in sorted(group_nodes(before)):
        ctx = " ".join(f"{k}={v}" for k, v in json.loads(context).items())
        commands.append(f"lp group {group} permission set {renamed(key)} {'true' if value else 'false'} {ctx}".strip())
        commands.append(f"lp group {group} permission unset {key} {ctx}".strip())
    for user, obj in before.get("users", {}).items():
        for n in obj.get("nodes", []):
            if renamed(n["key"]) is not None:
                print("user node, to move by hand:", obj.get("username", user), n["key"], "->", renamed(n["key"]), n.get("context"))
    print(len(commands) // 2, "group node(s) to move")
    if dry or not commands:
        for c in commands:
            print("  " + c)
        return
    for c in commands:
        rcon(server, c)
        time.sleep(0.15)
    after = export(server, "audit-perms-apres")
    left = group_nodes(after)
    expected = {(g, renamed(k), c, v) for g, k, c, v in group_nodes(before)}
    present = set()
    for group, obj in after.get("groups", {}).items():
        for n in obj.get("nodes", []):
            present.add((group, n["key"].lower(), json.dumps(n.get("context") or {}, sort_keys=True), n.get("value", True)))
    missing = expected - present
    print("left with a former name:", len(left), "| missing under the new name:", len(missing))
    if left or missing:
        sys.exit("NOT complete, check the groups (backup: audit-perms-avant-<date>.json.gz)")
    print("done")


if __name__ == "__main__":
    main()

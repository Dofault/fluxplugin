#!/usr/bin/env python3
"""Moves the LuckPerms groups of a server from the former warp permissions (playerwarps.*) to FluxPW's (fluxpw.*).

Same suffix, same value, same context (server=...), then the former node is removed. Run once per server, the day
FluxPW replaces PlayerWarps there (done on Labs on 2026-10-30; production: docs/technique/flux-mise-en-prod.md).

    python3 lp_playerwarps_to_fluxpw.py <semirp|labs|semilab> [--dry-run]

Exports the LuckPerms data first (plugins/LuckPerms/audit-fluxpw-avant-<date>.json.gz, the backup to restore with
"lp import" if needed), sends the commands through serveurs/00_scripts/rcon.py, exports again
(audit-fluxpw-apres-<date>.json.gz) and checks that every node moved. Users' own nodes are listed, not moved.
Exports are named with the date and time: an earlier backup is never overwritten.
"""
import gzip
import json
import os
import subprocess
import sys
import time

ROOT = "/home/influencraft/serveurs"
FOLDERS = {"semirp": "03_semirp", "labs": "04_labs", "semilab": "05_semilab", "events": "06_events"}
OLD, NEW = "playerwarps.", "fluxpw."


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


def nodes(data, prefix):
    out = set()
    for group, obj in data.get("groups", {}).items():
        for n in obj.get("nodes", []):
            if n["key"].lower().startswith(prefix):
                out.add((group, n["key"][len(prefix):].lower(), json.dumps(n.get("context") or {}, sort_keys=True),
                         n.get("value", True), n["key"]))
    return out


def main():
    if len(sys.argv) < 2 or sys.argv[1] not in FOLDERS:
        sys.exit(__doc__)
    server, dry = sys.argv[1], "--dry-run" in sys.argv
    before = export(server, "audit-fluxpw-avant")
    commands = []
    for group, suffix, context, value, key in sorted(nodes(before, OLD)):
        ctx = " ".join(f"{k}={v}" for k, v in json.loads(context).items())
        commands.append(f"lp group {group} permission set {NEW}{suffix} {'true' if value else 'false'} {ctx}".strip())
        commands.append(f"lp group {group} permission unset {key} {ctx}".strip())
    for user, obj in before.get("users", {}).items():
        for n in obj.get("nodes", []):
            if n["key"].lower().startswith(OLD):
                print("user node, to move by hand:", obj.get("username", user), n["key"], n.get("context"))
    print(len(commands) // 2, "group node(s) to move")
    if dry or not commands:
        for c in commands:
            print("  " + c)
        return
    for c in commands:
        rcon(server, c)
        time.sleep(0.15)
    after = export(server, "audit-fluxpw-apres")
    left = nodes(after, OLD)
    moved = {n[:4] for n in nodes(after, NEW)}
    missing = {n[:4] for n in nodes(before, OLD)} - moved
    print("left as playerwarps.*:", len(left), "| missing in fluxpw.*:", len(missing))
    if left or missing:
        sys.exit("NOT complete, check the groups (backup: audit-fluxpw-avant-<date>.json.gz)")
    print("done")


if __name__ == "__main__":
    main()

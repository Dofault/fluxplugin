#!/usr/bin/env python3
"""Moves the LuckPerms groups of a server to FluxCommerce's permissions (fluxcommerce.trade.*, .ah.*, .chestshop.*).

Knows the nodes of the plugins FluxCommerce replaces, whichever a server still has:
- production (third-party plugins): axtrade.*, auctionhouse.*, quickshop.*
- Labs (the three Flux plugins merged into FluxCommerce on 2026-11-01): fluxtrade.*, fluxauctionhouse.*, fluxchestshop.*

  axtrade.trade, fluxtrade.use                   -> fluxcommerce.trade.use (only when set: true by default)
  axtrade.toggle.other, fluxtrade.staff          -> fluxcommerce.trade.staff (history and cancelling of trades)
  axtrade.admin, fluxtrade.admin                 -> fluxcommerce.trade.admin
  axtrade.toggle                                 -> removed (part of fluxcommerce.trade.use)
  auctionhouse.slots.<rank>                      -> fluxcommerce.ah.slots.<number> (auction-slots of AuctionHouse's
                                                    permissions.yml)
  fluxauctionhouse.slots.<n>, .duration.<...>    -> fluxcommerce.ah.slots.<n>, .duration.<...>
  auctionhouse.ah, fluxauctionhouse.use          -> fluxcommerce.ah.use
  auctionhouse.moderator, auctionhouse.admin,
  fluxauctionhouse.admin                         -> fluxcommerce.ah.admin and fluxcommerce.staff (bans: they could ban
                                                    from the auction house, the ban now covers all commerce)
  quickshop.player, fluxchestshop.player         -> fluxcommerce.chestshop.create
  fluxchestshop.use, .admin, .limit.<n>          -> fluxcommerce.chestshop.use, .admin, .limit.<n>
  quickshop.togglefreeze, fluxchestshop.togglefreeze -> removed (QuickShop feature, gone)

Same value, same context (server=...), then the former node is removed. Run once per server, the day FluxCommerce
replaces the former plugins there (Labs: 2026-11-01; production: docs/technique/flux-mise-en-prod.md). On semirp,
LuckPerms is the MariaDB base shared by the network.

    python3 lp_to_fluxcommerce.py <semirp|labs|semilab> [--dry-run]

Exports the LuckPerms data first (plugins/LuckPerms/audit-fluxcommerce-avant-<date>.json.gz, the backup to restore
with "lp import" if needed), sends the commands through serveurs/00_scripts/rcon.py, exports again and checks that no
known former node is left on a group. Users' own nodes and unknown nodes are listed, not moved.
"""
import gzip
import json
import os
import subprocess
import sys
import time

import yaml

ROOT = "/home/influencraft/serveurs"
FOLDERS = {"semirp": "03_semirp", "labs": "04_labs", "semilab": "05_semilab", "events": "06_events"}
OLD = ("axtrade.", "auctionhouse.", "quickshop.", "fluxtrade.", "fluxauctionhouse.", "fluxchestshop.")
FIXED = {
    "axtrade.trade": ["fluxcommerce.trade.use"],
    "axtrade.toggle.other": ["fluxcommerce.trade.staff"],
    "axtrade.admin": ["fluxcommerce.trade.admin"],
    "axtrade.toggle": [],
    "fluxtrade.use": ["fluxcommerce.trade.use"],
    "fluxtrade.staff": ["fluxcommerce.trade.staff"],
    "fluxtrade.admin": ["fluxcommerce.trade.admin"],
    "auctionhouse.ah": ["fluxcommerce.ah.use"],
    "auctionhouse.moderator": ["fluxcommerce.ah.admin", "fluxcommerce.staff"],
    "auctionhouse.admin": ["fluxcommerce.ah.admin", "fluxcommerce.staff"],
    "fluxauctionhouse.use": ["fluxcommerce.ah.use"],
    "fluxauctionhouse.admin": ["fluxcommerce.ah.admin", "fluxcommerce.staff"],
    "quickshop.player": ["fluxcommerce.chestshop.create"],
    "quickshop.togglefreeze": [],
    "fluxchestshop.player": ["fluxcommerce.chestshop.create"],
    "fluxchestshop.togglefreeze": [],
    "fluxchestshop.use": ["fluxcommerce.chestshop.use"],
    "fluxchestshop.admin": ["fluxcommerce.chestshop.admin"],
}
# same suffix under the new prefix
PREFIXES = {"fluxauctionhouse.slots.": "fluxcommerce.ah.slots.", "fluxauctionhouse.duration.": "fluxcommerce.ah.duration.",
            "fluxchestshop.limit.": "fluxcommerce.chestshop.limit."}

_slots = None


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


def slots_of(server):
    """auction-slots of AuctionHouse's permissions.yml: {'auctionhouse.slots.citoyen': 10, ...} (read when needed)."""
    global _slots
    if _slots is not None:
        return _slots
    path = os.path.join(ROOT, FOLDERS[server], "plugins", "AuctionHouse", "permissions.yml")
    _slots = {}
    if not os.path.exists(path):
        return _slots

    def walk(prefix, node):
        for k, v in (node or {}).items():
            key = f"{prefix}.{k}" if prefix else str(k)
            if isinstance(v, dict):
                walk(key, v)
            else:
                _slots[key.lower()] = int(v)
    with open(path, encoding="utf-8") as f:
        walk("", (yaml.safe_load(f) or {}).get("auction-slots"))
    return _slots


def targets(server, key):
    """New nodes for a former one ([] = removed), None when unknown."""
    k = key.lower()
    if k in FIXED:
        return FIXED[k]
    for old, new in PREFIXES.items():
        if k.startswith(old):
            return [new + k[len(old):]]
    if k.startswith("auctionhouse.slots."):
        n = slots_of(server).get(k)
        return None if n is None else [f"fluxcommerce.ah.slots.{n}"]
    return None


def nodes(data):
    out = set()
    for group, obj in data.get("groups", {}).items():
        for n in obj.get("nodes", []):
            if n["key"].lower().startswith(OLD):
                out.add((group, n["key"], json.dumps(n.get("context") or {}, sort_keys=True), n.get("value", True)))
    return out


def main():
    if len(sys.argv) < 2 or sys.argv[1] not in FOLDERS:
        sys.exit(__doc__)
    server, dry = sys.argv[1], "--dry-run" in sys.argv
    before = export(server, "audit-fluxcommerce-avant")
    commands, unknown = [], []
    for group, key, context, value in sorted(nodes(before)):
        ctx = " ".join(f"{k}={v}" for k, v in json.loads(context).items())
        new = targets(server, key)
        if new is None:
            unknown.append((group, key))
            continue
        for node in new:
            commands.append(f"lp group {group} permission set {node} {'true' if value else 'false'} {ctx}".strip())
        commands.append(f"lp group {group} permission unset {key} {ctx}".strip())
    for user, obj in before.get("users", {}).items():
        for n in obj.get("nodes", []):
            if n["key"].lower().startswith(OLD):
                print("user node, to move by hand:", obj.get("username", user), n["key"], n.get("context"))
    for group, key in unknown:
        print("no FluxCommerce node for", group, key, "(left as is)")
    print(len(commands), "command(s)")
    if dry or not commands:
        for c in commands:
            print("  " + c)
        return
    for c in commands:
        rcon(server, c)
        time.sleep(0.15)
    after = export(server, "audit-fluxcommerce-apres")
    left = {n for n in nodes(after) if targets(server, n[1]) is not None}
    print("former nodes left:", len(left))
    if left:
        sys.exit("NOT complete, check the groups (backup: audit-fluxcommerce-avant-<date>.json.gz)")
    print("done")


if __name__ == "__main__":
    main()

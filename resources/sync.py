#!/usr/bin/env python3
"""Copies what the server runs but no plugin jar carries into this folder, so it is kept in git:

    python3 resources/sync.py [server root]        (default: /home/influencraft)

  itemsadder/<namespace>/   our ItemsAdder packs, from the Labs server (plugins/ItemsAdder/contents)
  bettermodel/              our BetterModel models (.bbmodel: geometry, textures, animations), from Labs
  scripts/                  the scripts that generate models, textures and pages (server root scripts/)
  blockbench/               hand-made Blockbench models kept at the server root (rank statues)

Run it before committing whenever a model, a texture or a pack changed on Labs. Each folder is mirrored: a file
removed on the server is removed here. Labs is the source: it is what goes to production with the suite.

Not copied: packs and models bought from other authors (THIRD_PARTY_PACKS, THIRD_PARTY_MODELS: their licences
forbid redistribution; keep the original archives), ItemsAdder's own files, backups (*.old.*), Python caches, and
scripts/communication (video pipeline with its virtualenv, kept elsewhere).
To restore: copy itemsadder/<namespace> back to plugins/ItemsAdder/contents, bettermodel/*.bbmodel to
plugins/BetterModel/models, then /iazip and /bm reload.
"""
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = sys.argv[1] if len(sys.argv) > 1 else "/home/influencraft"
LABS = os.path.join(ROOT, "serveurs", "04_labs", "plugins")

THIRD_PARTY_PACKS = ["_iainternal", "livingroom", "shizuart_furnitures", "fishing_dock_furniture",
                     "hyronic_ae_configuration", "rpgblocs", "npc"]
# EliteMobs models kept as a support for the dungeon maps
THIRD_PARTY_MODELS = ["deep_mines_specter*", "em_id_*", "*tnt_model_the_deep_mines*"]
SKIPPED_SCRIPTS = ["communication/", "__pycache__/", "*.pyc"]


def mirror(src, dst, excludes):
    os.makedirs(dst, exist_ok=True)
    args = ["rsync", "-a", "--delete", "--delete-excluded", "--exclude=*.old.*", "--exclude=__pycache__/"]
    args += ["--exclude=" + e for e in excludes]
    subprocess.run(args + [src.rstrip("/") + "/", dst.rstrip("/") + "/"], check=True)


def main():
    mirror(os.path.join(LABS, "ItemsAdder", "contents"), os.path.join(HERE, "itemsadder"),
           ["/" + p + "/" for p in THIRD_PARTY_PACKS])
    mirror(os.path.join(LABS, "BetterModel", "models"), os.path.join(HERE, "bettermodel"), THIRD_PARTY_MODELS)
    mirror(os.path.join(ROOT, "scripts"), os.path.join(HERE, "scripts"), SKIPPED_SCRIPTS)
    subprocess.run(["rsync", "-a", "--delete", "--include=*.bbmodel", "--exclude=*", ROOT.rstrip("/") + "/",
                    os.path.join(HERE, "blockbench") + "/"], check=True)
    for name in ("itemsadder", "bettermodel", "scripts", "blockbench"):
        size = subprocess.run(["du", "-sh", os.path.join(HERE, name)], capture_output=True, text=True).stdout.split()[0]
        print(f"{name:12} {size}")


if __name__ == "__main__":
    main()

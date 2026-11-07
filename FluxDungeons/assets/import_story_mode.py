#!/usr/bin/env python3
"""Imports the 10 Story Mode dungeons of EliteMobs as the 10 job dungeons of the server.

Usage: python3 -I import_story_mode.py <plugins/FluxDungeons folder>

The pack must be unzipped in <plugins/FluxDungeons>/maps/story-mode. One dungeon per job (every job but the
builder and the chemist), from the easiest to the hardest; translations in translations/<world>.fr.yml.
Run it again after changing this table or a translation: tuned values of the dungeon files are replaced by the
ones below (this table is the reference).
"""
import os
import subprocess
import sys

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))

# job id, EliteMobs world, level, French name, description, players, time limit, lives, reward minutes
DUNGEONS = [
    ("cultivateur", "em_id_the_cave", 3, "&#9AD29ALa Grotte bouillonnante",
     "Une grotte où bout une chaudière vivante. Le premier donjon, idéal pour découvrir ta classe.", 1, 4, "20m", 4, 6),
    ("sylviculteur", "em_id_the_climb", 6, "&#9AD29AL'Ascension",
     "Gravis une montagne gelée gardée par des morts qui refusent de rester couchés.", 1, 5, "35m", 5, 10),
    ("pecheur", "em_id_the_bridge", 9, "&#87CEFALe Pont ancien",
     "Traverse le pont et affronte son gardien millénaire.", 1, 4, "20m", 4, 8),
    ("fondeur", "em_id_the_city", 12, "&#FF922BLa Cité naine",
     "Fraie-toi un chemin dans les rues d'une cité naine tombée dans la folie.", 1, 5, "40m", 5, 14),
    ("defenseur", "em_id_the_palace", 15, "&#FFD94DLe Palais",
     "Prends d'assaut le palais et abats son seigneur.", 1, 4, "20m", 4, 10),
    ("mineur", "em_id_the_mines", 18, "&#B376E5Les Mines",
     "Une mine naine envahie par les créatures de Daine, la Tisseuse d'âmes. Descends jusqu'au cœur de la mine et vaincs ses gardiens.", 1, 5, "40m", 5, 16),
    ("rochiste", "em_id_the_quarry", 21, "&#A0A0A0La Carrière",
     "Une ancienne carrière naine sous la montagne. Remets le monte-charge en marche et descends.", 1, 5, "40m", 5, 18),
    ("deblayeur", "em_id_the_deep_mines", 24, "&#8B5A2BLes Mines profondes",
     "Sous tes pieds s'ouvre la mine la plus profonde jamais creusée. Quelque chose t'y poursuit.", 1, 5, "45m", 5, 20),
    ("herboriste", "em_id_the_nether_wastes", 27, "&#E63946Les Terres désolées",
     "Traverse les terres désolées du Nether et leurs créatures.", 1, 5, "45m", 5, 22),
    ("enchanteur", "em_id_the_nether_bell", 30, "&#C8256ALa Cloche du Nether",
     "Aventure-toi au plus profond du Nether, là où sonne la cloche.", 1, 5, "50m", 5, 25),
]


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    plugin = sys.argv[1]
    pack = os.path.join(plugin, "maps", "story-mode")
    for job, world, level, name, desc, pmin, pmax, time, lives, minutes in DUNGEONS:
        cmd = [sys.executable, "-I", os.path.join(HERE, "import_elitemobs.py"), pack, world, plugin,
               "--level", str(level), "--id", job, "--job", job]
        tr = os.path.join(HERE, "translations", world.replace("em_id_", "") + ".fr.yml")
        if os.path.exists(tr):
            cmd += ["--translations", tr]
        out = subprocess.run(cmd, capture_output=True, text=True)
        print(out.stdout.strip() or out.stderr.strip())
        path = os.path.join(plugin, "dungeons", f"{job}.yml")
        with open(path, encoding="utf-8") as f:
            text = f.read()
        head, body = text.split("\n", 1)
        d = yaml.safe_load(body)
        d.update({"name": name, "description": [desc], "players": {"min": pmin, "max": pmax}, "recommended-level": level,
                  "time-limit": time, "lives": lives, "reward": {"minutes": minutes, "xp": 40 * level}, "job": job})
        with open(path, "w", encoding="utf-8") as f:
            f.write(head + "\n" + yaml.safe_dump(d, allow_unicode=True, sort_keys=False, width=160))


if __name__ == "__main__":
    main()

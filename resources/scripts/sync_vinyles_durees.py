#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Bascule les jukebox_song des vinyles du son vanilla vers le .ogg custom depose.

Usage : python3 scripts/sync_vinyles_durees.py [--dry-run]

Tant qu'un vinyle n'a pas sa musique, sa song pointe vers un son vanilla de secours
(minecraft:music_disc.*). Des qu'un .ogg est depose dans sounds/vinyles/<id>.ogg, ce script
fait les deux changements que cela implique :

  1. sound_event      -> influvinyles:<id>, le son genere par ItemsAdder a la place du vanilla
  2. length_in_seconds -> la duree reelle du .ogg

Le point 2 est le plus sensible : le serveur ne mesure jamais la duree d'un fichier, il se fie
a length_in_seconds pour couper la musique, liberer le jukebox et piloter la sortie du
comparateur. Trop courte, le morceau est tronque ; trop longue, le jukebox tourne dans le vide.

A relancer a chaque fois qu'un .ogg est ajoute ou remplace.
"""
import json, os, struct, sys

SOUNDS = "/home/influencraft/serveurs/03_semirp/plugins/ItemsAdder/contents/influvinyles/sounds/vinyles"
SONGS = "/home/influencraft/serveurs/03_semirp/Utopia/datapacks/influvinyles/data/influvinyles/jukebox_song"


def duree_ogg(path):
    """Duree en secondes d'un Ogg Vorbis / Opus : dernier granule / frequence d'echantillonnage."""
    data = open(path, "rb").read()
    if not data.startswith(b"OggS"):
        raise ValueError("pas un fichier Ogg")

    if b"OpusHead" in data[:65536]:
        rate = 48000  # les granules Opus sont toujours comptes a 48 kHz
    else:
        i = data.find(b"\x01vorbis")
        if i < 0:
            raise ValueError("entete Vorbis/Opus introuvable")
        rate = struct.unpack("<I", data[i + 12:i + 16])[0]

    # derniere page Ogg du fichier -> granule position = nombre total d'echantillons
    j = data.rfind(b"OggS")
    if j < 0 or rate == 0:
        raise ValueError("page Ogg finale illisible")
    granule = struct.unpack("<q", data[j + 6:j + 14])[0]
    return granule / float(rate)


def main():
    dry = "--dry-run" in sys.argv
    modifies = sans_ogg = 0

    for nom in sorted(os.listdir(SONGS)):
        if not nom.endswith(".json"):
            continue
        vid = nom[:-5]
        ogg = os.path.join(SOUNDS, vid + ".ogg")
        if not os.path.exists(ogg):
            print("  son vanilla    %s" % vid)
            sans_ogg += 1
            continue

        chemin = os.path.join(SONGS, nom)
        song = json.load(open(chemin, encoding="utf-8"))
        try:
            duree = round(duree_ogg(ogg), 2)
        except ValueError as e:
            print("  ILLISIBLE      %s (%s)" % (vid, e))
            continue

        son = {"sound_id": "influvinyles:%s" % vid}
        if song.get("length_in_seconds") == duree and song.get("sound_event") == son:
            print("  deja a jour    %-26s %ss" % (vid, duree))
            continue

        ancien = song.get("sound_event", {}).get("sound_id", "?")
        print("  %s %-26s %ss  %s -> %ss  influvinyles:%s"
              % ("(dry-run)" if dry else "bascule      ", vid,
                 song.get("length_in_seconds"), ancien, duree, vid))
        if not dry:
            song["sound_event"] = son
            song["length_in_seconds"] = duree
            with open(chemin, "w", encoding="utf-8") as f:
                json.dump(song, f, indent=2, ensure_ascii=False)
                f.write("\n")
        modifies += 1

    print("\n%d song(s) %s, %d encore sur un son vanilla"
          % (modifies, "a basculer" if dry else "basculees", sans_ogg))


if __name__ == "__main__":
    main()

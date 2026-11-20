#!/usr/bin/env python3
"""Fait signer les skins des habitants par Mojang, via le service MineSkin (envoi anonyme, skins non listés) :
Typewriter n'affiche un skin que s'il a sa texture signée. Résultat dans skins.json ({id: {texture, signature}}),
lu par scripts/typewriter/gen_pnj_ville.py. Un skin déjà signé et inchangé (même fichier) n'est pas renvoyé.

Usage : python3 envoyer_skins.py        (une trentaine de secondes par dizaine de skins, le service limite le débit)
"""
import hashlib
import json
import subprocess
import time
from pathlib import Path

HERE = Path(__file__).parent
OUT = HERE / "skins.json"
done = json.loads(OUT.read_text()) if OUT.exists() else {}
for png in sorted(HERE.glob("[0-9][0-9]_*.png")):
    ident = png.stem
    digest = hashlib.sha256(png.read_bytes()).hexdigest()
    if done.get(ident, {}).get("file") == digest:
        continue
    for attempt in range(6):
        raw = subprocess.run(["curl", "-s", "-m", "60", "-X", "POST", "https://api.mineskin.org/v2/generate",
                              "-H", "User-Agent: skin-uploader/1.0", "-H", "Accept: application/json",
                              "-F", "variant=classic", "-F", f"name={ident[:20]}", "-F", "visibility=unlisted",
                              "-F", f"file=@{png};type=image/png"], capture_output=True, text=True).stdout
        try:
            r = json.loads(raw)
        except ValueError:
            r = {}
        if r.get("success"):
            data = r["skin"]["texture"]["data"]
            done[ident] = {"texture": data["value"], "signature": data["signature"], "file": digest}
            OUT.write_text(json.dumps(done, indent=1))
            print("ok", ident, flush=True)
            time.sleep(7)
            break
        wait = 8 * (attempt + 1)
        print("attente", ident, (raw or "")[:160].replace("\n", " "), flush=True)
        time.sleep(wait)
    else:
        raise SystemExit(f"échec : {ident}")
print(len(done), "skins signés")

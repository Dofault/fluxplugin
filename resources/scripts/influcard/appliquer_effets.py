#!/usr/bin/env python3
"""Applique effets_familiers.EFFETS aux familiers (InfluPet) déjà générés, sans relancer gen_familiers.py.

Usage : appliquer_effets.py <dossier plugins/InfluPet/pets> (ex-EcoPets/pets)
Fusionne aussi effets_familiers.CHAINS dans plugins/libreforge/chains.yml (même id = remplacé) et écrit
effets_familiers.JAVA dans plugins/InfluPet/effets.yml (effets codés dans InfluPet, ex-InfluCard).
Ne touche que level-placeholders, effects-description, rewards-description, level-up-messages, effects et
level-xp-requirements ; garde l'en-tête de commentaires et tout le reste du fichier.

Niveaux : ramenés à MAX_LEVEL (10) en gardant le même nombre total de cartes jusqu'au niveau max
(gen_familiers.level_curve sur la somme des paliers actuels). Familiers désactivés (FAMILIERS_DESACTIVES) :
fichier renommé en _<id>.yml (ignoré par InfluPet), et inversement s'ils sont réactivés.
"""
import os
import sys

import yaml

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from effets_familiers import CHAINS, EFFETS, FAMILIERS_DESACTIVES, JAVA, MAX_LEVEL, TRAINEES  # noqa: E402
from familiers_sol import AU_SOL  # noqa: E402


class Dumper(yaml.SafeDumper):
    def ignore_aliases(self, data):
        return True


def level_curve(total):
    """Paliers (MAX_LEVEL - 1) en cartes, proportionnels à n^1.2 + 3, au moins 2, croissants, somme = total
    (même forme que gen_familiers.level_curve)."""
    f = [n ** 1.2 + 3 for n in range(1, MAX_LEVEL)]
    reqs = []
    for x in f:
        reqs.append(max(reqs[-1] if reqs else 2, int(round(total * x / sum(f)))))
    for _ in range(100000):
        diff = total - sum(reqs)
        if diff == 0:
            break
        if diff > 0:
            i = max(k for k in range(len(reqs)) if k == len(reqs) - 1 or reqs[k] < reqs[k + 1])
            reqs[i] += 1
        else:
            cand = [k for k in range(1, len(reqs)) if reqs[k] > max(reqs[k - 1], 2)]
            if not cand:
                break
            reqs[cand[-1]] -= 1
    return reqs


pets_dir = sys.argv[1]
for pid in FAMILIERS_DESACTIVES:
    on, off = os.path.join(pets_dir, pid + ".yml"), os.path.join(pets_dir, "_" + pid + ".yml")
    if os.path.exists(on):
        os.replace(on, off)
        print(f"désactivé : {pid}")
for name in os.listdir(pets_dir):
    pid = name[1:-4]
    if name.startswith("_") and name.endswith(".yml") and pid in EFFETS and pid not in FAMILIERS_DESACTIVES:
        os.replace(os.path.join(pets_dir, name), os.path.join(pets_dir, pid + ".yml"))
        print(f"réactivé : {pid}")
for pid, (desc, placeholders, effects) in EFFETS.items():
    path = os.path.join(pets_dir, pid + ".yml")
    if not os.path.exists(path):
        print(f"absent : {pid}")
        continue
    text = open(path, encoding="utf-8").read()
    header = ""
    for l in text.splitlines():
        if not l.startswith("#"):
            break
        header += l + "\n"
    data = yaml.safe_load(text)
    data["level-placeholders"] = placeholders
    data["effects-description"] = {1: desc}
    data["rewards-description"] = {1: desc}
    data["level-up-messages"] = {1: desc}
    data["effects"] = effects
    # au sol ou en l'air (familiers_sol.AU_SOL), lu par InfluPet
    data.pop("vol", None)
    data["flying"] = pid not in AU_SOL
    # traînée du familier (lue par InfluPet, pets.Pet) : celles de effets_familiers.TRAINEES, aucune sinon
    t = TRAINEES.get(pid)
    data["trail"] = ({"enabled": True, "particle": t["particule"], "gap": t["intervalle"], "amount": t["nombre"],
                      "y-offset": t["hauteur"]} if t else
                     {"enabled": False, "particle": "flame", "gap": 2, "amount": 1, "y-offset": 0.0})
    reqs = data.get("level-xp-requirements") or []
    if len(reqs) != MAX_LEVEL - 1:
        data["level-xp-requirements"] = level_curve(int(sum(reqs)))
    body = yaml.dump(data, Dumper=Dumper, allow_unicode=True, sort_keys=False, width=120)
    open(path, "w", encoding="utf-8").write(header + ("\n" if header else "") + body)
    print(f"ok : {pid}")

# chaînes libreforge : plugins/libreforge/chains.yml, à côté d'InfluPet
chains_path = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(pets_dir))), "libreforge", "chains.yml")
text = open(chains_path, encoding="utf-8").read()
data = yaml.safe_load(text) or {}
chains = [c for c in data.get("chains", []) if c.get("id") not in CHAINS]
chains += [{"id": cid, "effects": effects} for cid, effects in CHAINS.items()]
data["chains"] = chains
header = ""
for l in text.splitlines():
    if not l.startswith("#"):
        break
    header += l + "\n"
open(chains_path, "w", encoding="utf-8").write(
    header + "\n" + yaml.dump(data, Dumper=Dumper, allow_unicode=True, sort_keys=False, width=120))
print(f"chaînes : {', '.join(CHAINS)} -> {chains_path}")

# effets codés dans InfluPet : plugins/InfluPet/effects.yml (clés en anglais depuis le 2026-10-08, converties
# depuis effets_familiers.JAVA, écrit en français)
plugins_dir = os.path.dirname(os.path.dirname(os.path.abspath(pets_dir)))
# InfluPet (ex-InfluCard, 2026-10-07) : son dossier, ou l'ancien tant qu'il n'a pas encore démarré sous ce nom
java_dir = os.path.join(plugins_dir, "InfluPet")
if not os.path.isdir(java_dir) and os.path.isdir(os.path.join(plugins_dir, "InfluCard")):
    java_dir = os.path.join(plugins_dir, "InfluCard")
java_path = os.path.join(java_dir, "effects.yml" if java_dir.endswith("InfluPet") else "familiers.yml")
EFFECT_IDS = {"xp-pierre": "stone-xp", "sans-mise-a-jour": "no-block-update", "replant-cacao": "cocoa-replant",
              "poudre-os-instant": "instant-bone-meal", "beton-instant": "instant-concrete",
              "cuisson-rapide": "fast-smelting", "livraison-four": "furnace-delivery", "usure-savante": "enchant-mastery",
              "lapis-econome": "lapis-saver", "niveaux-econome": "level-saver", "resistance-allies": "ally-resistance",
              "neige-poudreuse": "powder-snow"}
EFFECT_KEYS = {"familier": "pet", "blocs": "blocks", "recharge": "cooldown", "message-fin": "end-message",
               "intervalle-ticks": "interval-ticks", "rayon": "radius"}
java_en = {EFFECT_IDS.get(eid, eid): {EFFECT_KEYS.get(k, k): (v.replace("%secondes%", "%seconds%") if isinstance(v, str) else v)
                                      for k, v in cfg.items()} for eid, cfg in JAVA.items()}
with open(java_path, "w", encoding="utf-8") as f:
    f.write("# Effets de familiers codés dans InfluPet (impossibles en config libreforge).\n"
            "# Généré par scripts/influcard/appliquer_effets.py depuis effets_familiers.JAVA : modifier le script,\n"
            "# pas ce fichier. Relu par /influpet reload.\n"
            "# min = valeur au niveau 1, max = valeur au niveau max, linéaire entre les deux.\n\n")
    f.write(yaml.dump({"max-level": MAX_LEVEL, "effects": java_en}, Dumper=Dumper, allow_unicode=True,
                      sort_keys=False, width=120))
print(f"effets InfluPet : {', '.join(JAVA)} -> {java_path}")

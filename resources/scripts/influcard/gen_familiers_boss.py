#!/usr/bin/env python3
"""Familiers légendaires de boss (familiers_boss.py) sur un serveur, directement dans ses dossiers :

    gen_familiers_boss.py <dossier plugins du serveur> [--apercu <png>]

- le familier EST le modèle BetterModel du boss, à sa taille du donjon (2026-11-15 : plus de copie réduite) ; le
  boss et son familier restent liés, une retouche du modèle du boss se voit sur les deux. Hauteur du siège et
  taille (size, distances de suivi) mesurées sur ce modèle ;
- ItemsAdder/contents/fluxpetboss/ : carte de chaque légendaire (face 32 x 32 au cadre légendaire, dos commun
  fluxpet:item/carte_dos, même modèle fin que les autres cartes) ;
- FluxPets/pets/<id>.yml : le familier (monture dès le niveau MOUNT_LEVEL, SLOTS emplacements, sans effet) ;
- FluxPets/content/cards.yml, pets.yml et boosters.yml : cartes, familiers et boosters de carrière mis à jour sur
  place (chances de tirage.py, contenu et description du booster), courbes de niveau de tous les familiers de
  carrière recalculées (la carte du légendaire prend sa part des tirages).

L'effet de la taupe (creuser en 5 x 5) est un effet codé de FluxPets (effects.yml, `dig`), pas posé ici.
Après coup : portraits.py (portraits des menus), /iazip, /bm reload, /influpet reload.
"""
import json
import os
import re
import sys

import yaml

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import apercu  # noqa: E402
import bbrender  # noqa: E402
from familiers_boss import (ATTACK_LEVEL, BOSSES, CARD_NAMESPACE, EFFECT_LINES, LEGENDARY_COLOR,  # noqa: E402
                            MOUNT_JUMP, MOUNT_LEVEL, MOUNT_SPEED, MOUNTS, SLOTS, SPEED_START, card_entry)
from png_util import read, write  # noqa: E402
from tirages import (CAREER_DRAWS, RARITIES, SERIES_WEIGHTS, UNLOCK_TARGET, cards_to_max, level_curve,  # noqa: E402
                     pet_draw_rate)

ROOT = sys.argv[1]
APERCU = sys.argv[sys.argv.index("--apercu") + 1] if "--apercu" in sys.argv else None
SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)), "sources")
MODELS = os.path.join(ROOT, "BetterModel", "models")
PETS = os.path.join(ROOT, "FluxPets")
IA = os.path.join(ROOT, "ItemsAdder", "contents", CARD_NAMESPACE)
JOB_NAMES = {"mineur": "Mineur", "rochiste": "Rochiste", "deblayeur": "Déblayeur", "cultivateur": "Cultivateur",
             "herboriste": "Herboriste", "sylviculteur": "Bûcheron", "fondeur": "Fondeur",
             "enchanteur": "Enchanteur", "pecheur": "Pêcheur", "defenseur": "Défenseur", "batisseur": "Bâtisseur"}
RARITY_COLORS = {"simple": "#AAAAAA", "unique": "#37C44A", "elite": "#3283A8",
                 "ultimate": "#B376E5", "legendary": "#F0A800", "fabled": "#C8256A"}


def ensure(path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    return path


def rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


# --------------------------------------------------------------------------- taille du boss

def bounds(elements):
    lo = [min(min(e["from"][i], e["to"][i]) for e in elements) for i in range(3)]
    hi = [max(max(e["from"][i], e["to"][i]) for e in elements) for i in range(3)]
    return lo, hi


def boss_size(boss):
    """Hauteur et longueur (plus grande dimension au sol) du modèle du boss, en blocs."""
    data = json.load(open(os.path.join(MODELS, boss[2] + ".bbmodel"), encoding="utf-8"))
    lo, hi = bounds([e for e in data["elements"] if e.get("type", "cube") == "cube"])
    size = [(hi[i] - lo[i]) / 16 for i in range(3)]
    return size[1], max(size[0], size[2])


def has_animation(boss, name):
    """The boss's model has that animation (its run: played by FluxPets in place of the walk when the pet goes fast)."""
    data = json.load(open(os.path.join(MODELS, boss[2] + ".bbmodel"), encoding="utf-8"))
    return any(a.get("name") == name for a in data.get("animations", []))


def remove_old_copy(pid):
    """Copie réduite d'avant le 2026-11-15 (familier_<id>.bbmodel) : plus utilisée."""
    old = os.path.join(MODELS, f"familier_{pid}.bbmodel")
    if os.path.exists(old):
        os.remove(old)


# --------------------------------------------------------------------------- carte

FIRE_FRAME = {"2a1210": 0, "ffe28c": 1, "b0681e": 2, "f2b33d": 3}  # contour, clair, moyen, accent du gabarit


def frame(color):
    r, g, b = rgb(color)
    shade = lambda k: (int(r * k), int(g * k), int(b * k), 255)
    tint = lambda k: (int(r + (255 - r) * k), int(g + (255 - g) * k), int(b + (255 - b) * k), 255)
    return shade(0.16), tint(0.55), shade(0.62), (r, g, b, 255)


def card_texture(boss, art):
    """Face de la carte : cadre légendaire, fond sombre à la couleur du boss, illustration de trois quarts,
    couronne dans le bandeau (légendaire de boss) et 5 pastilles de rareté sur 6."""
    _, _, tpl = read(os.path.join(SRC, "carte_modele.png"))
    fr = frame(LEGENDARY_COLOR)
    br, bg_, bb = rgb(boss[5])
    bg = (int(br * 0.22), int(bg_ * 0.18), int(bb * 0.24), 255)
    bg2 = (int(br * 0.34), int(bg_ * 0.28), int(bb * 0.36), 255)
    bar = (int(br * 0.15), int(bg_ * 0.12), int(bb * 0.16), 255)
    out = [[(0, 0, 0, 0)] * 32 for _ in range(32)]
    for y in range(32):
        for x in range(32):
            p = tpl[y][x]
            if p[3] == 0:
                continue
            key = p.hex()[:6]
            if 25 <= y <= 28 and 7 <= x <= 24:
                out[y][x] = bar
            elif key in FIRE_FRAME:
                c = fr[FIRE_FRAME[key]]
                if FIRE_FRAME[key] == 1 and (x + y) % 4 == 0:  # reflets dorés sur le cadre
                    c = (255, 240, 170, 255)
                out[y][x] = c
            elif 3 <= y <= 22 and 7 <= x <= 24:
                out[y][x] = bg
            else:
                out[y][x] = tuple(p)
    for (x, y) in ((8, 6), (22, 4), (9, 18), (23, 14), (16, 3)):
        out[y][x] = bg2
    for y in range(20):
        for x in range(18):
            if art[y][x] is not None:
                out[3 + y][7 + x] = tuple(art[y][x])
    # couronne (3 x 3) à gauche du bandeau
    gold, light = rgb("#F0A800") + (255,), rgb("#FFD94D") + (255,)
    for j, row in enumerate(["1.1", "121", "222"]):
        for i, ch in enumerate(row):
            if ch != ".":
                out[25 + j][8 + i] = light if ch == "1" else gold
    level = RARITIES.index("legendary") + 1
    for i in range(6):
        x = 13 + i * 2
        c = fr[3] if i < level else (fr[0][0] + 30, fr[0][1] + 30, fr[0][2] + 30, 255)
        out[26][x] = c
        out[27][x] = c
    return out


def card_model(pid):
    front, back = f"{CARD_NAMESPACE}:item/carte_{pid}", "fluxpet:item/carte_dos"
    edge = {"uv": [2, 0, 2.5, 16], "texture": "#front"}
    third = {"rotation": [0, 0, 0], "translation": [0, 3, 1], "scale": [0.55, 0.55, 0.55]}
    first = {"rotation": [0, -90, 25], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]}
    return {
        "textures": {"front": front, "back": back, "particle": front},
        "elements": [{"from": [2, 0, 7.75], "to": [14, 16, 8.25],
                      "faces": {"south": {"uv": [2, 0, 14, 16], "texture": "#front"},
                                "north": {"uv": [14, 0, 2, 16], "texture": "#back"},
                                "east": edge, "west": edge,
                                "up": {"uv": [2, 0, 14, 0.5], "texture": "#front"},
                                "down": {"uv": [2, 15.5, 14, 16], "texture": "#front"}}}],
        "display": {"thirdperson_righthand": third, "thirdperson_lefthand": third,
                    "firstperson_righthand": first, "firstperson_lefthand": first,
                    "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
                    "head": {"rotation": [0, 180, 0], "translation": [0, 13, 7], "scale": [1, 1, 1]},
                    "fixed": {"rotation": [0, 180, 0], "scale": [1, 1, 1]},
                    "gui": {"rotation": [0, 0, 0], "scale": [1, 1, 1]}},
    }


def clasp(kind):
    """Attache dorée 16 x 16 qui relie les cases d'un familier à plusieurs emplacements (menu des familiers) :
    bande dorée à mi-hauteur, embout arrondi et étoile du côté libre (left / right), bande pleine au milieu."""
    dark, gold, light, star = (90, 52, 0, 255), (240, 168, 0, 255), (255, 217, 77, 255), (255, 246, 214, 255)
    px = [[(0, 0, 0, 0)] * 16 for _ in range(16)]
    lo, hi = (4, 16) if kind == "left" else (0, 12) if kind == "right" else (0, 16)
    for x in range(lo, hi):
        px[6][x], px[9][x] = dark, dark
        px[7][x], px[8][x] = light, gold
    if kind != "middle":
        cap = lo if kind == "left" else hi - 1
        step = -1 if kind == "left" else 1
        for dy in range(5, 11):
            px[dy][cap] = dark
        for dy in range(6, 10):
            px[dy][cap + step] = dark
            px[dy][cap] = gold
        px[7][cap] = light
        # étoile à 4 branches près de l'embout
        cx = cap - step * 3
        for ddx, ddy in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)):
            px[7 + ddy][cx + ddx] = star
    return px


# --------------------------------------------------------------------------- contenu de FluxPets

class NoAlias(yaml.SafeDumper):
    """Pas d'ancres YAML (&id001) : les listes répétées sont écrites en entier."""

    def ignore_aliases(self, data):
        return True


def dump(path, data, header):
    with open(path, "w", encoding="utf-8") as f:
        f.write(header)
        yaml.dump(data, f, Dumper=NoAlias, allow_unicode=True, sort_keys=False, width=1000)


# Rider's seat of each boss pet, in blocks: the top of the model's real surface under the rider (cubes as they are
# posed, sampled within 0.45 block of the model's centre: where his body is), measured on 2026-11-15 after the owner
# found himself inside the mole. The first table (2026-11-15 morning) only read the cubes on the centre axis and
# missed tilted cubes, the hamster's head and the Ossacorne's back plates. Humanoids carry him on their head (their
# shoulders are inside it at the centre). A boss missing here sits at 60 % of its height.
SEATS = {"trex_cuivre": 2.50, "colosse_abime": 2.50, "taupe_perion": 2.35, "hamster_moissonneur": 2.40,
         "fleur_devoreuse": 2.70, "araignee_sylvestre": 1.90, "golem_fonderie": 3.20, "evocateur_demesure": 4.47,
         "loutre_marees": 1.53, "ossacorne": 2.60}
SEAT_RATIO = 0.6
# Mount speed of a boss pet when it is not the common one (blocks a tick): the otter twice as fast (owner, 2026-11-15).
MOUNT_SPEEDS = {"loutre_marees": 2 * MOUNT_SPEED, "araignee_sylvestre": 2 * MOUNT_SPEED}


def mount_section(pid, height):
    """Section mount du familier : siège, vitesse (qui grandit avec le niveau), attaque, sons, nage."""
    speed = MOUNT_SPEEDS.get(pid, MOUNT_SPEED)
    m = MOUNTS[pid]
    name, anim, ticks, impacts, reach, angle, damage, per_level, cooldown, sound, hit, particle = m["attack"]
    fx = m.get("fx", {})
    voices, vmin, vmax = m["voice"]
    out = {"level": MOUNT_LEVEL, "seat": SEATS.get(pid, round(height * SEAT_RATIO, 2)), "speed": speed,
           "speed-start": round(speed * SPEED_START, 3), "jump": MOUNT_JUMP,
           "attack": {"level": ATTACK_LEVEL, "name": name, "animation": anim, "ticks": ticks, "impacts": impacts,
                      "range": reach, "angle": angle, "damage": damage, "damage-per-level": per_level,
                      "cooldown": cooldown, "slow": 0.4, "sound": sound, "hit-sound": hit, "particle": particle, **fx},
           "sounds": {"step": m["step"], "walk": {"cycle": m["walk"][0], "at": m["walk"][1]},
                      "run": {"cycle": m["run"][0], "at": m["run"][1]},
                      "voice": voices, "voice-min": vmin, "voice-max": vmax,
                      "jump": f"entity.horse.jump|0.8|{m['jump']}", "land": f"entity.horse.land|0.8|{m['jump']}"}}
    if "swim" in m:
        anim, idle, strokes, (cycle, at) = m["swim"]
        out["swim"] = {"animation": anim, **({"idle": idle} if idle else {})}
        out["sounds"]["swim-step"] = strokes
        out["sounds"]["swim"] = {"cycle": cycle, "at": at}
    return out


def pet_file(boss, reqs, height, length):
    pid, name, model, job, dungeon, color, lore, walk = boss
    desc = ["&#B376E5Légendaire du donjon " + JOB_NAMES[job] + ".",
            "&#B376E5Monture (clic droit dessus), plus rapide à chaque niveau."
            + (" &#B376E5Nage." if "swim" in MOUNTS[pid] else ""),
            f"&#B376E5Attaque &f{MOUNTS[pid]['attack'][0]}&#B376E5 dès le niveau &f{ATTACK_LEVEL}&#B376E5 (clic gauche).",
            f"&#B376E5Prend &f{SLOTS}&#B376E5 emplacements de familier."]
    by_level = {1: desc}
    if pid in EFFECT_LINES:
        at, lines = EFFECT_LINES[pid]
        by_level[at] = desc + lines
    data = {
        "name": f"&{LEGENDARY_COLOR}{name}",
        "description": "&#B376E5Grandit uniquement avec ses cartes",
        "entity-texture": f"bettermodel:{model}",
        "flying": False,
        "icon": f"itemsadder:{CARD_NAMESPACE}__carte_{pid}",
        # FluxPets : légendaire de boss (familiers_boss.py)
        "slots": SLOTS,
        "size": round(length, 1),
        "walk-animation": walk,
        "reveal-animation": "idle",
        **({"run-animation": "run"} if has_animation(boss, "run") else {}),
        "mount": mount_section(pid, height),
        "trail": {"enabled": False, "particle": "flame", "gap": 2, "amount": 1, "y-offset": 0.0},
        "level-xp-requirements": reqs,
        "xp-gain-methods": [],
        "level-placeholders": [],
        "effects-description": by_level,
        "rewards-description": by_level,
        "level-up-messages": by_level,
        "level-up-effects": [],
        "effects": [],
        "conditions": [],
        "activate-conditions": [],
        "spawn-egg": {"enabled": False, "item": "paper", "name": f"&{LEGENDARY_COLOR}{name}", "lore": [],
                      "craftable": False, "recipe": [], "withdrawable": False},
    }
    dump(os.path.join(PETS, "pets", pid + ".yml"), data,
         f"# {name} (légendaire de boss, carrière : {JOB_NAMES[job]}, donjon : {dungeon}).\n"
         "# Généré par scripts/influcard/gen_familiers_boss.py (données : familiers_boss.py).\n"
         "# Adopté en battant le boss du donjon, ou dans un booster de sa carrière.\n\n")


def replace_curve(path, reqs):
    """Remplace la liste level-xp-requirements d'un fichier de familier, sans toucher au reste."""
    text = open(path, encoding="utf-8").read()
    block = "level-xp-requirements:\n" + "".join(f"- {r}\n" for r in reqs)
    new, n = re.subn(r"level-xp-requirements:\n(?:- .*\n)+", block, text, count=1)
    if n != 1:
        raise SystemExit(f"level-xp-requirements introuvable dans {path}")
    open(path, "w", encoding="utf-8").write(new)


def main():
    ia_items = {}
    previews = []
    for kind in ("left", "middle", "right"):
        write(ensure(os.path.join(IA, "textures", "item", f"slot_{kind}.png")), 16, 16, clasp(kind))
        ia_items[f"slot_{kind}"] = {"display_name": " ", "resource": {"material": "PAPER", "generate": True,
                                                                     "textures": [f"item/slot_{kind}.png"]}}
    content = os.path.join(PETS, "content")
    cards = yaml.safe_load(open(os.path.join(content, "cards.yml"), encoding="utf-8"))
    pets = yaml.safe_load(open(os.path.join(content, "pets.yml"), encoding="utf-8"))
    boosters = yaml.safe_load(open(os.path.join(content, "boosters.yml"), encoding="utf-8"))
    career = {}  # job -> [(pid, rarity)] familiers de carrière actifs
    for cid, c in cards["cards"].items():
        pet = c.get("pet", cid)
        if pets["pets"]["list"].get(pet, {}).get("enabled", True) and c.get("rarity") != "legendary":
            job = next((j for j, n in JOB_NAMES.items() if n == c.get("series")), None)
            if job:
                career.setdefault(job, []).append((pet, c["rarity"]))

    for boss in BOSSES:
        pid, name, model, job = boss[:4]
        height, length = boss_size(boss)
        remove_old_copy(pid)
        elements, tex = bbrender.load(os.path.join(MODELS, model + ".bbmodel"))
        sq = apercu.render(elements, tex, 35, 20, size=20, bg=None, margin=1)
        art = [row[1:19] for row in sq]
        face = card_texture(boss, art)
        write(ensure(os.path.join(IA, "textures", "item", f"carte_{pid}.png")), 32, 32, face)
        with open(ensure(os.path.join(IA, "models", "item", f"carte_{pid}.json")), "w", encoding="utf-8") as f:
            json.dump(card_model(pid), f, indent=2)
        ia_items[f"carte_{pid}"] = {"display_name": " ", "resource": {"material": "PAPER", "generate": False,
                                                                       "model_path": f"item/carte_{pid}"}}
        series = [r for _, r in career.get(job, [])] + ["legendary"]
        reqs = level_curve(cards_to_max(series, "legendary", SERIES_WEIGHTS, CAREER_DRAWS, UNLOCK_TARGET))
        pet_file(boss, reqs, height, length)
        cards["cards"][pid] = card_entry(boss, JOB_NAMES[job])
        pets["pets"]["list"][pid] = {"rarity": "legendary", "enabled": True}
        # courbes des familiers de la carrière : la carte du légendaire prend sa part des tirages
        for other, rarity in career.get(job, []):
            replace_curve(os.path.join(PETS, "pets", other + ".yml"),
                          level_curve(cards_to_max(series, rarity, SERIES_WEIGHTS, CAREER_DRAWS, UNLOCK_TARGET)))
        # booster de la carrière : contenu, description, chances. Le légendaire de boss s'y adopte (1 sur 300,
        # tirages.UNLOCK_TARGET ; retiré puis remis le 2026-11-16 à la demande du propriétaire) ; sa monture s'achète
        # aussi à la boutique de donjon une fois le boss vaincu. FluxPets écrit la chance de chaque familier.
        b = boosters["boosters"][f"metier_{job}"]
        for entry in (f"pet:{pid}", f"card:{pid}"):
            if entry not in b["contents"]:
                kind = entry.split(":")[0]
                last = max(i for i, e in enumerate(b["contents"]) if e.startswith(kind + ":"))
                b["contents"].insert(last + 1, entry)
        line = f"%adopted:{pid}% &{LEGENDARY_COLOR}{name}"
        if line not in b["lore"]:
            last = max(i for i, l in enumerate(b["lore"]) if l.startswith("%adopted:"))
            b["lore"].insert(last + 1, line)
        members = career.get(job, []) + [(pid, "legendary")]
        target = {p: UNLOCK_TARGET[r] for p, r in members}
        b["pet-chance"] = round(pet_draw_rate(target, b["cards"]) * 100, 4)
        weights = {r: 0 for r in RARITIES}
        for p, r in members:
            weights[r] += int(round(target[p] * 1_000_000))
        b["pet-rarity-weights"] = weights
        b["rarity-weights"] = dict(zip(RARITIES, SERIES_WEIGHTS))
        previews.append(face)
        print(f"{pid}: {height:.2f} blocs de haut, {length:.1f} de long, {sum(reqs)} cartes jusqu'au niveau max")

    # carrières sans légendaire de boss (Bâtisseur) : nouvelles chances aussi
    for job, members in career.items():
        if any(b[3] == job for b in BOSSES) or f"metier_{job}" not in boosters["boosters"]:
            continue
        b = boosters["boosters"][f"metier_{job}"]
        target = {p: UNLOCK_TARGET[r] for p, r in members}
        b["pet-chance"] = round(pet_draw_rate(target, b["cards"]) * 100, 4)
        weights = {r: 0 for r in RARITIES}
        for p, r in members:
            weights[r] += int(round(target[p] * 1_000_000))
        b["pet-rarity-weights"] = weights
        b["rarity-weights"] = dict(zip(RARITIES, SERIES_WEIGHTS))
        for other, rarity in members:
            replace_curve(os.path.join(PETS, "pets", other + ".yml"),
                          level_curve(cards_to_max([r for _, r in members], rarity, SERIES_WEIGHTS, CAREER_DRAWS,
                                                   UNLOCK_TARGET)))

    head = "# Généré par scripts/influcard/gen_familiers.py + merge_config.py (légendaires de boss :\n" \
           "# gen_familiers_boss.py) ; modifié aussi par /influpet menu.\n\n"
    dump(os.path.join(content, "cards.yml"), cards,
         "# Cartes (une par familier, liée par « pet ») et XP donnée au familier par carte, selon sa rareté.\n" + head)
    dump(os.path.join(content, "pets.yml"), pets,
         "# Familiers obtenables dans les boosters (rareté, obtenable). Définitions : pets/<id>.yml.\n" + head)
    dump(os.path.join(content, "boosters.yml"), boosters,
         "# Boosters : un par carrière (seul moyen d'adopter ses familiers), un Prestige, 4 paliers (comme les\n"
         "# caisses). pet-chance = % de chance qu'un tirage soit un familier plutôt qu'une carte.\n" + head)
    with open(ensure(os.path.join(IA, "config.yml")), "w", encoding="utf-8") as f:
        f.write("# Cartes des familiers légendaires de boss, généré par scripts/influcard/gen_familiers_boss.py.\n"
                "# Nom, description et comportement : plugins/FluxPets/content/cards.yml.\n\n")
        yaml.safe_dump({"info": {"namespace": CARD_NAMESPACE}, "items": ia_items}, f, allow_unicode=True,
                       sort_keys=False)
    if APERCU:
        scale = 6
        W = len(previews) * 34 * scale
        img = [[(46, 42, 56, 255)] * W for _ in range(34 * scale)]
        for n, px in enumerate(previews):
            for y in range(32):
                for x in range(32):
                    if px[y][x][3]:
                        for dy in range(scale):
                            for dx in range(scale):
                                img[(y + 1) * scale + dy][(n * 34 + x + 1) * scale + dx] = px[y][x]
        write(APERCU, W, 34 * scale, img)
    print(len(BOSSES), "légendaires de boss")


if __name__ == "__main__":
    main()

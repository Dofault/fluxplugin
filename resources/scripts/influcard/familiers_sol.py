"""Familiers qui marchent au sol (InfluPet, clé « flying: false ») et adaptation de leur modèle BetterModel.

Les modèles générés (atelier.py, modeles.build_animations) sont pensés pour flotter : géométrie qui ne démarre
pas forcément à 0, idle / walk qui font monter le familier, sleep qui le fait descendre, walk qui le penche.
Posé au sol, il semblait voler un peu. Pour les familiers de AU_SOL :
- la géométrie est descendue (ou remontée) pour que le bas de la partie la plus basse soit à 0 (l'os
  « hitbox », boîte de collision invisible, n'est pas compté) ;
- idle, walk et sleep n'ont plus de mouvement vertical ni de bascule de l'os racine : il reste posé, ce sont
  ses pattes, sa queue, sa tête qui bougent ; happy garde son saut ;
- forme propre à certains familiers (ALLONGER) : le ver couché à plat au lieu d'être dressé en pente.

Choix au sol / en l'air (2026-10-08) selon ce que représente le familier, pas selon son animation : au sol
ce qui marche, rampe ou se pose (animaux terrestres, vers, golems, personnages, objets, plantes, minéraux) ;
en l'air ce qui vole, nage ou relève de la magie (ailes, esprits, spectres, créatures marines, orbes).

Utilisé par gen_familiers.py (à la génération), appliquer_effets.py (clé flying des fichiers) et poser_au_sol.py
(modèles déjà générés).
"""
import copy

AU_SOL = {
    # animaux terrestres
    "cabri", "castor", "chien_garde", "crabichon", "fourmi", "kitsune", "lapin_lapis", "licorne", "lievre_neiges",
    "ouistiti", "salamandre",
    # vers
    "ver_sables",
    # golems et personnages
    "architecte", "colosse", "ecuyer", "epouvantail", "sylvain", "titan",
    # objets et machines
    "betonniere", "fournaise", "grue", "marteau_piqueur", "mimique", "pavois", "soufflet", "table_vivante",
    "wagonnet",
    # plantes et minéraux
    "caillou", "carottin", "champinou", "coquelicot", "gland", "pepite", "souche",
}

FLAT_ANIMATIONS = {"idle", "walk", "sleep"}
# animations sur mesure déjà pensées pour le sol (bonds, foulées : le corps ne descend jamais sous le sol),
# gardées telles quelles (cf. familiers_design, anim_overrides)
ANIMATIONS_SOL = {pid: {"walk"} for pid in (
    "carottin", "cabri", "caillou", "coquelicot", "epouvantail", "pepite", "lapin_lapis", "lievre_neiges",
    "mimique", "gland", "champinou", "chien_garde", "ecuyer", "architecte", "licorne")}
ROOT = "familier"


def au_sol(pid):
    return pid in AU_SOL


# ---------------------------------------------------------------- géométrie

def _groups(outliner):
    """Tous les os (dict) du modèle, à plat."""
    out = []

    def walk(nodes):
        for n in nodes:
            if isinstance(n, dict):
                out.append(n)
                walk(n.get("children", []))

    walk(outliner)
    return out


def _elements_of(group, by_uuid, deep=True):
    """Éléments d'un os (et de ses sous-os si deep)."""
    out = []
    for c in group.get("children", []):
        if isinstance(c, dict):
            if deep:
                out += _elements_of(c, by_uuid, True)
        elif c in by_uuid:
            out.append(by_uuid[c])
    return out


def _shift(elements, groups, dy):
    for e in elements:
        e["from"][1] = round(e["from"][1] + dy, 4)
        e["to"][1] = round(e["to"][1] + dy, 4)
        if "origin" in e:
            e["origin"][1] = round(e["origin"][1] + dy, 4)
    for g in groups:
        if "origin" in g:
            g["origin"][1] = round(g["origin"][1] + dy, 4)


def _bottom(elements):
    return min(min(e["from"][1], e["to"][1]) for e in elements)


def _all_groups(group):
    return [group] + [g for c in group.get("children", []) if isinstance(c, dict) for g in _all_groups(c)]


def allonger_ver(model):
    """Ver des Sables : chaque segment posé à plat (bas à 0), la tête juste relevée (bas à 1 px)."""
    by_uuid = {e["uuid"]: e for e in model["elements"]}
    groups = {g["name"]: g for g in _groups(model["outliner"])}
    for name, g in groups.items():
        if name.startswith("seg_"):
            own = _elements_of(g, by_uuid, deep=False)
            if own:
                _shift(own, [g], -_bottom(own))
    head = groups.get("h_head")
    if head:
        els = _elements_of(head, by_uuid)
        _shift(els, _all_groups(head), 1.0 - _bottom(els))


ALLONGER = {"ver_sables": allonger_ver}


def _poser_geometrie(model):
    hitbox = set()
    for g in _groups(model["outliner"]):
        if g.get("name") == "hitbox":
            hitbox |= {c for c in g.get("children", []) if not isinstance(c, dict)}
    elements = [e for e in model["elements"] if e.get("uuid") not in hitbox and e.get("name") != "hitbox"]
    dy = -_bottom(elements)
    groups = [g for g in _groups(model["outliner"]) if g.get("name") != "hitbox"]
    _shift(elements, groups, dy)
    return dy


# ---------------------------------------------------------------- animations

def _poser_animations(model, keep=()):
    roots = {g["uuid"] for g in model["outliner"] if isinstance(g, dict) and g.get("name") == ROOT}
    changed = 0
    for animation in model.get("animations", []):
        if animation.get("name") not in FLAT_ANIMATIONS or animation.get("name") in keep:
            continue
        for uuid, animator in animation.get("animators", {}).items():
            if uuid not in roots and animator.get("name") != ROOT:
                continue
            for keyframe in animator.get("keyframes", []):
                channel = keyframe.get("channel")
                for point in keyframe.get("data_points", []):
                    if channel == "position" and str(point.get("y", "0")).strip() not in ("0", "0.0", "0.000"):
                        point["y"] = "0.000"
                        changed += 1
                    elif channel == "rotation" and any(str(point.get(k, "0")).strip() not in ("0", "0.0", "0.000")
                                                       for k in ("x", "z")):
                        # pas de bascule avant / sur le côté de tout le corps (la rotation y reste)
                        point["x"] = "0.000"
                        point["z"] = "0.000"
                        changed += 1
    return changed


def poser(model, pid):
    """Modèle (dict .bbmodel) adapté au sol pour ce familier ; renvoie une copie. Sans effet hors AU_SOL."""
    if pid not in AU_SOL:
        return model
    model = copy.deepcopy(model)
    if pid in ALLONGER:
        ALLONGER[pid](model)
    _poser_geometrie(model)
    _poser_animations(model, ANIMATIONS_SOL.get(pid, ()))
    return model

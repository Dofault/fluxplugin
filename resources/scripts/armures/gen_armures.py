#!/usr/bin/env python3
"""Génère les armures de carrière (namespace ItemsAdder « influarmures ») : une tenue par carrière.

Source des formes : le pack « Just 3d armors » (modèles 3D d'objets, un par matériau vanilla). Chaque tenue
pioche ses 4 pièces dans ces formes et les repeint avec sa propre gamme de couleurs (teinte de la série de
boosters de la carrière). Toutes les tenues ont les mêmes statistiques (matériau de base commun, MATERIAU).

Ce qui est produit dans <sortie> (par défaut le dossier ItemsAdder de Labs) :
- models/item/base/*.json   : géométrie d'origine, une fois par forme utilisée
- models/item/<carrière>_<pièce>.json : modèle enfant (géométrie + textures de la tenue)
- textures/item/*.png       : textures 3D repeintes
- textures/armor/*.png      : couches d'armure portée (plastron, jambières, bottes), repeintes depuis vanilla
- config.yml                : équipements + objets ItemsAdder

Le casque porté est le modèle 3D lui-même (equippable sans asset : le client affiche l'objet sur la tête).
Les trois autres pièces portées passent par les couches d'armure : aucun client vanilla ne peut les afficher
en 3D (le pack d'origine le fait par OptiFine / EMF uniquement).

Usage : gen_armures.py <zip du pack> [--sortie <dossier>] [--apercu <png>] [--sans-ecrire]
"""
import argparse
import colorsys
import json
import os
import shutil
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import png_io  # noqa: E402
import rendu  # noqa: E402

RACINE = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SORTIE = os.path.join(RACINE, "serveurs/04_labs/plugins/ItemsAdder/contents/influarmures")
# couches d'armure vanilla (64x32), livrées avec BetterModel
COUCHES = os.path.join(RACINE, "serveurs/04_labs/plugins/BetterModel/armors/armors")
NS = "influarmures"
MATERIAU = "DIAMOND"  # mêmes statistiques pour toutes les tenues

PIECES = ("helmet", "chestplate", "leggings", "boots")
SUFFIXE = {"helmet": "helm", "chestplate": "chest", "leggings": "leg", "boots": "boot"}
SLOT = {"helmet": "HEAD", "chestplate": "CHEST", "leggings": "LEGS", "boots": "FEET"}
COUCHE_VANILLA = {"leather": "leather", "chain": "chainmail", "iron": "iron", "gold": "gold", "dia": "diamond",
                  "nether": "netherite", "copper": "copper", "turtle": "iron"}
# centre de la cavité du casque (unités du modèle) et taille en pixels de joueur d'une unité, cf. display_tete
TETE = {"leather": ((3, 4, 3), 10 / 6), "turtle": ((5, 4, 4), 1.25)}
TETE_DEFAUT = ((6.5, 4, 4), 1.25)


def rampe(*stops):
    return [(p, tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))) for p, h in stops]


# gammes neutres, partagées entre tenues pour une pièce « de matière » (pantalon de toile, bottes de cuir...)
NEUTRES = {
    "acier": rampe((0, "#14181F"), (0.4, "#3F4A57"), (0.75, "#8A98A6"), (1, "#E6EDF2")),
    "cuir": rampe((0, "#1F130B"), (0.4, "#56361D"), (0.75, "#8F6235"), (1, "#D9B27C")),
    "toile": rampe((0, "#3A3226"), (0.4, "#8C7F67"), (0.75, "#CFC3A6"), (1, "#F7F0DC")),
    "denim": rampe((0, "#0F1A30"), (0.4, "#2C456F"), (0.75, "#5477A8"), (1, "#B9D0EC")),
}

# carrière -> libellé, couleur de série, formes (casque, plastron, jambières, bottes), gamme principale,
# gamme d'accent (détails d'une autre teinte dans la texture d'origine), gamme par pièce si différente
TENUES = {
    "mineur": dict(
        nom="Mineur", de="de ", couleur="#4FD6E8", formes=("copper", "iron", "iron", "iron"),
        main=rampe((0, "#10161D"), (0.35, "#33414E"), (0.62, "#66798A"), (0.84, "#4FD6E8"), (1, "#DCFBFF")),
        accent=rampe((0, "#8A5A10"), (0.5, "#F0A800"), (1, "#FFF2A8")), pieces={"boots": "cuir"},
        texte=("Sa lampe frontale a vu plus de galeries", "que la plupart des cartes.")),
    "rochiste": dict(
        nom="Rochiste", de="de ", couleur="#A8AEB8", formes=("nether", "nether", "nether", "nether"),
        main=rampe((0, "#15161A"), (0.4, "#474B54"), (0.75, "#A8AEB8"), (1, "#F3F5F8")),
        accent=rampe((0, "#6B3A1C"), (0.5, "#D98A4E"), (1, "#FFD9B0")), pieces={"leggings": "cuir"},
        texte=("Taillée dans la pierre, pour ceux", "qui la brisent du matin au soir.")),
    "deblayeur": dict(
        nom="Déblayeur", de="de ", couleur="#D9C27A", formes=("leather", "leather", "chain", "copper"),
        main=rampe((0, "#2B1D12"), (0.4, "#80603A"), (0.75, "#D9C27A"), (1, "#FFF4CC")),
        accent=rampe((0, "#0E4A45"), (0.5, "#3FB8A8"), (1, "#C9FFF4")), pieces={"leggings": "acier", "boots": "cuir"},
        texte=("Lunettes baissées, pelle à la main :", "le sable n'a qu'à bien se tenir.")),
    "cultivateur": dict(
        nom="Cultivateur", de="de ", couleur="#E8C547", formes=("turtle", "leather", "leather", "leather"),
        main=rampe((0, "#3D2B10"), (0.4, "#A97A1F"), (0.75, "#E8C547"), (1, "#FFF7C7")),
        accent=rampe((0, "#1E4A14"), (0.5, "#6FBF3A"), (1, "#D9FFB0")), pieces={"chestplate": "denim", "leggings": "cuir"},
        texte=("Un chapeau de paille, une salopette", "et la saison peut commencer.")),
    "herboriste": dict(
        nom="Herboriste", de="d'", couleur="#6CD46C", formes=("copper", "leather", "leather", "leather"),
        main=rampe((0, "#0F2E1A"), (0.4, "#2F7D3C"), (0.75, "#6CD46C"), (1, "#E6FFD9")),
        accent=rampe((0, "#7A2A5A"), (0.5, "#F28FC5"), (1, "#FFE0F2")), pieces={"leggings": "toile"},
        texte=("Un bourgeon pousse sur le casque :", "elle sent encore la rosée.")),
    "sylviculteur": dict(
        nom="Bûcheron", de="de ", couleur="#B07A45", formes=("leather", "copper", "copper", "gold"),
        main=rampe((0, "#21130A"), (0.4, "#6B4423"), (0.75, "#B07A45"), (1, "#F2D5A8")),
        accent=rampe((0, "#143A14"), (0.5, "#3F8F3A"), (1, "#BFEFA8")),
        pieces={"chestplate": rampe((0, "#0E2412"), (0.4, "#2C5E2E"), (0.75, "#5E9A4E"), (1, "#D2EDB8"))},
        texte=("La tenue de ceux qui connaissent", "chaque arbre par son écorce.")),
    "fondeur": dict(
        nom="Fondeur", de="de ", couleur="#FF8A3D", formes=("iron", "nether", "iron", "nether"),
        main=rampe((0, "#0D0A0B"), (0.45, "#3B2B2B"), (0.7, "#B5391A"), (0.88, "#FF8A3D"), (1, "#FFE38A")),
        accent=rampe((0, "#8A5A10"), (0.5, "#FFD94D"), (1, "#FFF9D6")), pieces={},
        texte=("Noircie par la suie, encore rougie", "par la chaleur des fourneaux.")),
    "enchanteur": dict(
        nom="Enchanteur", de="d'", couleur="#8C7BFF", formes=("dia", "dia", "dia", "dia"),
        main=rampe((0, "#130E38"), (0.4, "#4A3BA8"), (0.75, "#8C7BFF"), (0.9, "#C9BDFF"), (1, "#FFE9A8")),
        accent=rampe((0, "#8A5A10"), (0.5, "#F0A800"), (1, "#FFF2A8")), pieces={},
        texte=("Chaque rune gravée dessus murmure", "un sort que personne n'a fini d'écrire.")),
    "pecheur": dict(
        nom="Pêcheur", de="de ", couleur="#4FA8FF", formes=("turtle", "copper", "copper", "copper"),
        main=rampe((0, "#0A1D3D"), (0.4, "#1F5FA8"), (0.75, "#4FA8FF"), (1, "#DFF2FF")),
        accent=rampe((0, "#7A4A12"), (0.5, "#C98A3A"), (1, "#F5D9A8")),
        pieces={k: rampe((0, "#4A3408"), (0.4, "#C79212"), (0.75, "#FFD94D"), (1, "#FFF9D6")) for k in ("helmet", "boots")},
        texte=("Chapeau ciré et bottes jaunes,", "par tous les temps.")),
    "defenseur": dict(
        nom="Défenseur", de="de ", couleur="#E04848", formes=("gold", "gold", "gold", "gold"),
        main=rampe((0, "#290A0E"), (0.4, "#8E1F28"), (0.75, "#E04848"), (0.9, "#F08A5A"), (1, "#FFE08A")),
        accent=rampe((0, "#8A5A10"), (0.5, "#F0A800"), (1, "#FFF2A8")), pieces={"leggings": "acier"},
        texte=("Son panache se voit de loin,", "les monstres aussi l'ont appris.")),
    "batisseur": dict(
        nom="Bâtisseur", de="de ", couleur="#F2A33A", formes=("iron", "copper", "iron", "iron"),
        main=rampe((0, "#3A1E08"), (0.4, "#B5651D"), (0.75, "#F2A33A"), (1, "#FFF0C7")),
        accent=rampe((0, "#3A3A3A"), (0.5, "#C8C8C8"), (1, "#FFFFFF")), pieces={"leggings": "denim", "boots": "cuir"},
        texte=("Casque de chantier vissé sur la tête,", "plans sous le bras.")),
}
NOM_PIECE = {"helmet": "Casque", "chestplate": "Plastron", "leggings": "Jambières", "boots": "Bottes"}


# --------------------------------------------------------------------------- couleurs

def lum(c):
    return (0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]) / 255


def dans_rampe(r, t):
    t = min(1.0, max(0.0, t))
    for (p0, c0), (p1, c1) in zip(r, r[1:]):
        if t <= p1:
            k = (t - p0) / (p1 - p0) if p1 > p0 else 0
            return tuple(int(round(c0[i] + (c1[i] - c0[i]) * k)) for i in range(3))
    return r[-1][1]


def bornes(vals):
    """2e et 98e centiles : une poignée de pixels extrêmes ne doit pas écraser le reste de la gamme."""
    v = sorted(vals)
    lo, hi = v[int(0.02 * (len(v) - 1))], v[int(0.98 * (len(v) - 1))]
    return (lo, hi) if hi - lo > 0.04 else (lo - 0.3, lo + 0.3)


def repeindre(px, main, accent, gamma=1.0):
    """Repeint une texture en gardant son ombrage : la luminance de chaque pixel choisit sa place dans la gamme.
    Les pixels d'une autre teinte que la matière dominante (plume, lunettes, boucle...) passent sur l'accent."""
    opaques = [c for row in px for c in row if c[3] >= 26]
    hsv = [colorsys.rgb_to_hsv(c[0] / 255, c[1] / 255, c[2] / 255) for c in opaques]
    satures = [h for h in hsv if h[1] > 0.25 and h[2] > 0.15]
    teinte = None
    if len(satures) > 0.3 * len(hsv):
        histo = [0.0] * 36
        for h in satures:
            histo[int(h[0] * 36) % 36] += h[1]
        teinte = (max(range(36), key=lambda k: histo[k - 1] + histo[k] + histo[(k + 1) % 36]) + 0.5) / 36

    def est_accent(c):
        h, s, v = colorsys.rgb_to_hsv(c[0] / 255, c[1] / 255, c[2] / 255)
        if teinte is None:
            return s > 0.4 and v > 0.3
        return s > 0.3 and v > 0.2 and min(abs(h - teinte), 1 - abs(h - teinte)) > 0.09
    lm = [lum(c) for c in opaques if not est_accent(c)] or [0.5]
    la = [lum(c) for c in opaques if est_accent(c)] or [0.5]
    (m0, m1), (a0, a1) = bornes(lm), bornes(la)
    out = []
    for row in px:
        ligne = []
        for c in row:
            if c[3] < 26:
                ligne.append((0, 0, 0, 0))
            elif est_accent(c):
                ligne.append(dans_rampe(accent, 0.15 + 0.85 * (lum(c) - a0) / (a1 - a0)) + (255,))
            else:
                ligne.append(dans_rampe(main, max(0.0, (lum(c) - m0) / (m1 - m0)) ** gamma) + (c[3] if c[3] < 250 else 255,))
        out.append(ligne)
    return out


def gamme(tenue, piece):
    g = tenue["pieces"].get(piece, tenue["main"])
    return NEUTRES[g] if isinstance(g, str) else g


# --------------------------------------------------------------------------- modèles

def nom_source(forme, piece):
    return "copper_chestp" if (forme, piece) == ("copper", "chestplate") else f"{forme}_{SUFFIXE[piece]}"


def coins(model):
    """Coins des cubes, rotations comprises."""
    pts = []
    for (a, b, M, _, o, _, _) in rendu.cubes(model, {}):
        for x in (a[0], b[0]):
            for y in (a[1], b[1]):
                for z in (a[2], b[2]):
                    p = rendu._mul(M, [x - o[0], y - o[1], z - o[2]])
                    pts.append([p[i] + o[i] for i in range(3)])
    return pts


def display_cadre(pts, rx, ry, taille):
    """Transformation d'affichage qui centre le modèle et le fait tenir dans « taille » unités une fois tourné
    (Minecraft tourne autour du centre du modèle, dans l'ordre Y puis X)."""
    R = rendu._matmul(rendu._rot("x", rx), rendu._rot("y", ry))
    q = [rendu._mul(R, [p[i] - 8 for i in range(3)]) for p in pts]
    lo = [min(p[i] for p in q) for i in range(3)]
    hi = [max(p[i] for p in q) for i in range(3)]
    s = min(4.0, taille / max(hi[0] - lo[0], hi[1] - lo[1]))
    return {"rotation": [rx, ry, 0], "translation": [round(-s * (lo[i] + hi[i]) / 2, 2) for i in range(3)],
            "scale": [round(s, 3)] * 3}


def display_tete(forme):
    """Casque porté : le client place le centre du modèle (8, 8, 8) au centre de la tête, 16 unités = 10 pixels
    de joueur. On vise une coque de 10 pixels de large (comme un casque vanilla), cavité centrée sur la tête."""
    centre, px_par_unite = TETE.get(forme, TETE_DEFAUT)
    s = round(1.6 * px_par_unite, 3)
    return {"translation": [round(-s * (centre[i] - 8), 2) for i in range(3)], "scale": [s] * 3}


def displays(model, forme, piece):
    pts = coins(model)
    d = dict(model.get("display", {}))
    d["gui"] = display_cadre(pts, 25, 145, 15)
    d["ground"] = display_cadre(pts, 0, 0, 7)
    d["ground"]["translation"][1] += 2
    d["fixed"] = display_cadre(pts, 0, 180, 14)
    fp = display_cadre(pts, 5, 160, 9)
    fp["translation"] = [round(fp["translation"][0] + 1, 2), round(fp["translation"][1] + 3, 2), fp["translation"][2]]
    d["firstperson_righthand"] = d["firstperson_lefthand"] = fp
    if piece == "helmet":
        d["head"] = display_tete(forme)
    return d


# --------------------------------------------------------------------------- couches portées

BOITES = {  # boîte UV vanilla (64x32) : u, v, largeur, hauteur, profondeur
    "tete": (0, 0, 8, 8, 8), "corps": (16, 16, 8, 12, 4), "bras": (40, 16, 4, 12, 4), "jambe": (0, 16, 4, 12, 4)}
GAMMA_COUCHES = 0.65  # les couches vanilla sont plus sombres que les textures 3D : on les remonte
ZONES = {"tete": (0, 0, 64, 16), "jambe": (0, 16, 16, 32), "corps": (16, 16, 40, 32), "bras": (40, 16, 64, 32)}


def couche(forme, fichier):
    return png_io.read(os.path.join(COUCHES, COUCHE_VANILLA[forme], fichier + ".png"))[2]


def couches_portees(tenue):
    """layer_1 = tête + corps + bras (plastron) + jambes (bottes) ; layer_2 = jambières. Chaque zone vient de
    la forme de sa pièce, repeinte avec la gamme de cette pièce."""
    f = dict(zip(PIECES, tenue["formes"]))
    vide = [[(0, 0, 0, 0)] * 64 for _ in range(32)]
    l1 = [row[:] for row in vide]
    for zone, piece in (("tete", "helmet"), ("corps", "chestplate"), ("bras", "chestplate"), ("jambe", "boots")):
        src = repeindre(couche(f[piece], "armor"), gamme(tenue, piece), tenue["accent"], GAMMA_COUCHES)
        x0, y0, x1, y1 = ZONES[zone]
        for y in range(y0, y1):
            l1[y][x0:x1] = src[y][x0:x1]
    l2 = repeindre(couche(f["leggings"], "leggings"), gamme(tenue, "leggings"), tenue["accent"], GAMMA_COUCHES)
    return l1, l2


# --------------------------------------------------------------------------- aperçu

def boite(nom, a, b, tex, gonfle=0.0, miroir=False):
    """Cube de mannequin en coordonnées joueur (pixels, y vers le haut), texturé par boîte UV vanilla."""
    u, v, w, h, d = BOITES[nom]
    k = 16 / 64  # les UV de modèle sont en seizièmes de la texture (complétée en 64x64 pour l'aperçu)
    faces = {"up": (u + d, v, u + d + w, v + d), "down": (u + d + w, v, u + d + 2 * w, v + d),
             "east": (u, v + d, u + d, v + d + h), "north": (u + d, v + d, u + d + w, v + d + h),
             "west": (u + d + w, v + d, u + 2 * d + w, v + d + h),
             "south": (u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h)}
    if miroir:
        faces = {n: (r[2], r[1], r[0], r[3]) for n, r in faces.items()}
        faces["east"], faces["west"] = faces["west"], faces["east"]
    return {"from": [a[i] - gonfle for i in range(3)], "to": [b[i] + gonfle for i in range(3)],
            "faces": {n: {"uv": [c * k for c in r], "texture": "#t"} for n, r in faces.items()}}, tex


PEAU = (201, 160, 130, 255)


def mannequin(tenue, modele_casque, tex_casque):
    """Joueur gris-chair portant la tenue : couches d'armure gonflées comme en jeu + casque 3D sur la tête."""
    l1, l2 = couches_portees(tenue)
    carre = lambda px: px + [[(0, 0, 0, 0)] * 64 for _ in range(32)]
    l1, l2 = carre(l1), carre(l2)
    peau = [[PEAU] * 64 for _ in range(64)]
    sombre = [[(60, 62, 74, 255)] * 64 for _ in range(64)]
    parts = [("tete", (-4, 24, -4), (4, 32, 4)), ("corps", (-4, 12, -2), (4, 24, 2)),
             ("bras", (-8, 12, -2), (-4, 24, 2)), ("bras", (4, 12, -2), (8, 24, 2)),
             ("jambe", (-4, 0, -2), (0, 12, 2)), ("jambe", (0, 0, -2), (4, 12, 2))]
    scene = []
    for n, (nom, a, b) in enumerate(parts):
        mir = n in (3, 5)
        calques = [(peau if nom in ("tete", "bras") else sombre, 0.0)]
        if nom in ("corps", "bras"):
            calques.append((l1, 1.0))
        if nom in ("corps", "jambe"):
            calques.append((l2, 0.5))
        if nom == "jambe":
            calques.append((l1, 1.0))
        for tex, g in calques:
            e, t = boite(nom, a, b, tex, g, mir)
            scene += rendu.cubes({"elements": [e]}, {"t": t})
    # casque : même calcul que le client (display « head »), 1 unité de modèle = 0,625 x échelle pixels
    d = modele_casque["display"]["head"]
    s = d["scale"][0] * 0.625
    for (a, b, M, Mt, o, faces, _) in rendu.cubes(modele_casque, tex_casque):
        f = lambda p: [(p[i] - 8) * s + d["translation"][i] * 0.625 + (0, 28, 0)[i] for i in range(3)]
        scene.append((f(a), f(b), M, Mt, f(o), faces, (0, 0, 0)))
    return scene


# --------------------------------------------------------------------------- génération

def construire(zip_path):
    z = zipfile.ZipFile(zip_path)
    base = "assets/minecraft/"
    bases, enfants, textures, couches = {}, {}, {}, {}
    for job, t in TENUES.items():
        for piece, forme in zip(PIECES, t["formes"]):
            src = nom_source(forme, piece)
            model = json.loads(z.read(f"{base}models/item/{src}.json"))
            tex = {}
            for cle, chemin in model["textures"].items():
                nom = chemin.split("/")[-1]
                suff = "" if nom == src else "_" + nom.replace(src.split("_")[0] + "_", "")
                cible = f"{job}_{piece}{suff}"
                if cible not in textures:
                    px = png_io.decode(z.read(f"{base}textures/item/{nom}.png"))[2]
                    # texture secondaire (tige du casque de cuivre...) : entièrement sur la gamme d'accent
                    textures[cible] = repeindre(px, t["accent"] if suff else gamme(t, piece), t["accent"])
                tex[cle] = cible
            if src not in bases:
                bases[src] = {"texture_size": model.get("texture_size", [16, 16]),
                              "textures": {k: f"{NS}:item/{v}" for k, v in tex.items()},
                              "elements": model["elements"], "display": displays(model, forme, piece)}
            enfants[f"{job}_{piece}"] = {"parent": f"{NS}:item/base/{src}",
                                         "textures": {**{k: f"{NS}:item/{v}" for k, v in tex.items()},
                                                      "particle": f"{NS}:item/{job}_{piece}"}}
        couches[job] = couches_portees(t)
    return bases, enfants, textures, couches


def config():
    y = ["# Fichier généré par scripts/armures/gen_armures.py : ne pas modifier à la main, relancer le script.",
         "info:", f"  namespace: {NS}", "", "categories:", "  armures_carrieres:", "    enabled: true",
         f'    icon: "{NS}:mineur_helmet"', '    name: "Armures de carrière"',
         '    permission: "ia.menu.seecategory.armures_carrieres"', "    items:"]
    y += [f'      - "{NS}:{job}_{p}"' for job in TENUES for p in PIECES]
    y += ["", "# Couches portées (plastron, jambières, bottes). Le casque n'en a pas : il s'affiche en 3D.",
          "equipments:"]
    for job in TENUES:
        y += [f"  {job}:", "    type: armor", f"    layer_1: armor/{job}_layer_1", f"    layer_2: armor/{job}_layer_2"]
    y += ["", "items:"]
    for job, t in TENUES.items():
        c = t["couleur"]
        for p in PIECES:
            y += [f"  {job}_{p}:",
                  f'    display_name: "<{c}><b>{NOM_PIECE[p]} {t["de"]}{t["nom"]}</b></{c}>"',
                  "    lore:", '      - ""',
                  f'      - "<gray><italic>{t["texte"][0]}"', f'      - "<gray><italic>{t["texte"][1]}"', '      - ""',
                  f'      - "<gray>Tenue de carrière : </gray><{c}>{t["nom"]}</{c}>"',
                  "    resource:", f"      material: {MATERIAU}_{p.upper()}", "      generate: false",
                  f"      model_path: item/{job}_{p}", "    equipment:"]
            if p != "helmet":
                y.append(f"      id: {NS}:{job}")
            y += [f"      slot: {SLOT[p]}", ""]
    return "\n".join(y)


def ecrire(sortie, bases, enfants, textures, couches):
    for d in ("models", "textures"):
        shutil.rmtree(os.path.join(sortie, d), ignore_errors=True)
    for d in ("models/item/base", "textures/item", "textures/armor"):
        os.makedirs(os.path.join(sortie, d))
    for nom, m in bases.items():
        json.dump(m, open(os.path.join(sortie, "models/item/base", nom + ".json"), "w"), separators=(",", ":"))
    for nom, m in enfants.items():
        json.dump(m, open(os.path.join(sortie, "models/item", nom + ".json"), "w"), indent=1)
    for nom, px in textures.items():
        png_io.write(os.path.join(sortie, "textures/item", nom + ".png"), px)
    for job, (l1, l2) in couches.items():
        png_io.write(os.path.join(sortie, "textures/armor", f"{job}_layer_1.png"), l1)
        png_io.write(os.path.join(sortie, "textures/armor", f"{job}_layer_2.png"), l2)
    open(os.path.join(sortie, "config.yml"), "w").write(config())


def apercu(path, bases, enfants, textures, jobs=None, taille=190):
    """Une ligne par tenue : porté de face, porté de dos, puis les 4 objets tels qu'en main."""
    images = []
    for job in jobs or TENUES:
        t = TENUES[job]

        def piece(p):
            m = bases[enfants[f"{job}_{p}"]["parent"].split("/")[-1]]
            tex = {k: textures[v.split("/")[-1]] for k, v in enfants[f"{job}_{p}"]["textures"].items()}
            return m, tex
        scene = mannequin(t, *piece("helmet"))
        cadre = [[x, y, z] for x in (-10, 10) for y in (0, 38) for z in (-8, 8)]
        images += [rendu.render(scene, 30, 12, size=taille * 2, frame=cadre),
                   rendu.render(scene, 210, 12, size=taille * 2, frame=cadre)]
        for p in PIECES:
            images.append(rendu.render(rendu.cubes(*piece(p)), 35, 20, size=taille))
    png_io.write(path, png_io.sheet(images, 6))


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("zip")
    ap.add_argument("--sortie", default=SORTIE)
    ap.add_argument("--apercu")
    ap.add_argument("--tenues", nargs="*")
    ap.add_argument("--sans-ecrire", action="store_true")
    a = ap.parse_args()
    resultat = construire(a.zip)
    if a.apercu:
        apercu(a.apercu, *resultat[:3], jobs=a.tenues)
    if not a.sans_ecrire:
        ecrire(a.sortie, *resultat)
        print(f"{len(resultat[1])} objets, {len(resultat[2])} textures, {len(resultat[0])} formes -> {a.sortie}")

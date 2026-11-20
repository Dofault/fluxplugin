#!/usr/bin/env python3
"""Absolute Zero set ("Zero"): a pale cryomancer outfit made of four light accessories.

    <venv with Pillow and numpy>/python3 gen_zero_bbmodel.py [--deploy] [preview folder]

Without --deploy nothing leaves the preview folder (previews, atlas, list of cubes, zero.bbmodel and one editable
Blockbench file per piece). With --deploy it writes to Labs:
- plugins/FluxArmor/models/zero.bbmodel: mantle with its cuffs, shoes (then /fluxarmor build, /iazip);
- contents/influoutils: the worn diadem casque_zero and the 3D inventory models of the other pieces.

The four pieces leave the body showing between them; the lozenge crystal is the sign of the collection:
- Diadem of Absolute Zero: a tiara open at the back, its middle one step higher, a lozenge on the brow, two thin ice
  fragments rising towards the back (the left one shorter and steeper);
- Mantle of the Eternal Mists: frosted cloth over the shoulders and the top of the chest, a low collar open in front,
  a short cape widening towards the bottom, the lozenge on the chest, a silver plate on the right shoulder, a clasp on
  the left;
- Cuffs of the Polar Heart: frozen leather on the forearms under a silver band, a tongue over the back of the hand,
  an ice stone painted on the outside;
- Sollerets of the Silent Winter: thin sole, raised toe, a snowy cuff, a silver buckle and an ice rune painted on.

The cuffs are worn on the arms, which only the chest slot of Minecraft draws: in game they come with the mantle.
Light comes from the top left. Emissive pixels: the heart of the three lozenges. Grids and faces: setkit.py.
"""
from setkit import Cube, lit, mirror, paint, run, setup, stamp

PALETTE = {"W": "#DCEAF0", "G": "#C4D1D6", "B": "#AFCBD8", "M": "#93B4C9",  # snow, silver, frost, a step between
           "K": "#789EBA", "A": "#496C8C", "Y": "#B9E8F2"}  # glacier, slate, pale cyan

# ---------------------------------------------------------------------------------------- painters


def cloth(w, h):
    """Frosted cloth: soft folds low on the tall pieces, light along the top and the lit side."""
    def pixel(x, y):
        if h >= 3 and y == h - 1:
            return "K"
        if w >= 4 and h >= 5 and x % 4 == 2 and y >= h * 0.45:
            return "M"
        if y == 0 and x < w * 0.6 or w >= 3 and x == 0 and y < h * 0.4:
            return "W"
        return "B"
    return paint(w, h, pixel)


def collar(w, h):
    return paint(w, h, lambda x, y: "B" if y == 0 else ("A" if w >= 3 and x == w - 1 else "K"))


def silver(w, h):
    """Old silver: light on one corner of the top edge, cool shade below, no hard contrast."""
    def pixel(x, y):
        if y == 0 and x <= max(0, w // 3):
            return "W"
        if h >= 2 and y == h - 1 or w >= 3 and x == w - 1:
            return "K"
        return "G"
    return paint(w, h, pixel)


def ice(w, h):
    """Naturally formed ice fragment: silver white, one lit streak."""
    return paint(w, h, lambda x, y: "W" if y == 0 and x % 3 != 2 else ("B" if h >= 2 and y == h - 1 else "G"))


def leather(w, h):
    """Frozen leather: matt slate, glacier where the light falls."""
    def pixel(x, y):
        if y == 0 and x < (w + 1) // 2:
            return "K"
        return "A"
    return paint(w, h, pixel)


def boot(w, h):
    def pixel(x, y):
        if (x, y) == (0, 0) and w * h >= 4:
            return "B"
        if h >= 3 and y == h - 1 or w >= 3 and x == w - 1:
            return "A"
        return "K"
    return paint(w, h, pixel)


def snow(w, h):
    """Rimed fur of the shoe cuffs: snow broken by frost blue, never a flat white band."""
    def pixel(x, y):
        if h >= 2 and y == h - 1:
            return "B" if x % 2 else "G"
        return "B" if (x + 2 * y) % 5 == 0 else "W"
    return paint(w, h, pixel)


setup("zero", PALETTE, {
    "cloth": (cloth, "W", "K"), "collar": (collar, "B", "A"), "silver": (silver, "W", "K"), "ice": (ice, "W", "B"),
    "leather": (leather, "K", "A"), "boot": (boot, "B", "A"), "snow": (snow, "W", "G"),
    "sole": (lambda w, h: ["A" * w] * h, "B", "A"), "crystal": (lambda w, h: paint(w, h, lambda x, y: "W" if (x, y) == (0, 0) else "Y"), "Y", "K")})

# ---------------------------------------------------------------------------------------- pieces


def hat():
    rise_right = dict(rot=(15, 0, 0), origin=[-4.5, 31.5, 0])
    rise_left = dict(rot=(22, 0, 0), origin=[4.5, 31.5, 0])
    return lit([
        # Tiara open at the back: the middle one step higher than its flanks, two bars along the temples.
        Cube("bandeau_centre", [-2, 30, 4], [2, 32, 5], "cloth", north=None),
        Cube("bandeau_droit", [-5, 29, 4], [-2, 31, 5], "cloth", north=None),
        Cube("bandeau_gauche", [2, 29, 4], [5, 31, 5], "cloth", north=None),
        Cube("tempe_droite", [-5, 29, -1], [-4, 31, 4], "cloth", east=None),
        Cube("tempe_gauche", [4, 29, -1], [5, 31, 4], "cloth", west=None),
        # Lozenge on the brow: glacier frame, cyan heart.
        Cube("losange_frontal", [-1.5, 29.5, 5], [1.5, 32.5, 6], "crystal", rot=(0, 0, 45), south=["KKK", "KyK", "KKA"], north=None,
             west="K", east="A", up="K", down="A"),
        # Thin ice fragments rising towards the back, the left one shorter and steeper.
        Cube("fragment_droit", [-5, 31, -4], [-4, 32, 0], "ice", **rise_right),
        Cube("fragment_gauche", [4, 31, -3], [5, 32, 0], "ice", **rise_left),
    ])


def torso():
    yoke = stamp(cloth(9, 5), 0, 4, "KKMBBBMKK")
    return lit([
        # Low collar open in front.
        Cube("col_dos", [-5, 24, -5], [5, 26, -4], "collar", south=None),
        Cube("col_droit", [-5, 24, -4], [-4, 26, 2], "collar", east=None),
        Cube("col_gauche", [4, 24, -4], [5, 26, 2], "collar", west=None),
        # Frosted cloth over the top of the chest.
        Cube("etole", [-4.5, 19, 2.5], [4.5, 24, 3.5], "cloth", south=yoke, north=None),
        # Short cape, one step wider towards the bottom.
        Cube("cape_haut", [-4.5, 19, -3.5], [4.5, 24, -2.5], "cloth", south=None),
        Cube("cape_bas", [-5.5, 14, -4.5], [5.5, 19, -3.5], "cloth"),
        # The lozenge of the collection on the chest, a silver clasp on the left.
        Cube("losange", [-1.5, 19.5, 3.5], [1.5, 22.5, 4.5], "crystal", rot=(0, 0, 45), south=["KYK", "YyY", "KYA"], north=None,
             west="K", east="A", up="K", down="A"),
        Cube("attache", [3.5, 21, 3.5], [4.5, 24, 4.5], "silver", north=None),
    ])


def shoulder():
    return Cube("epauliere", [-10, 22, -3.5], [-5, 25, 3.5], "cloth")  # above the arm, beside the collar


def cuff():
    """Cuff of frozen leather under a silver band, a tongue over the back of the hand, an ice stone painted outside."""
    stone = stamp(leather(5, 4), 1, 1, ["BBB", "ByB", "BBB"])
    return [
        Cube("manchette", [-8.5, 14, -2.5], [-3.5, 18, 2.5], "leather", west=stone),
        Cube("manchette_languette", [-8.5, 12, -1.5], [-7.5, 14, 1.5], "leather"),
        Cube("manchette_bande", [-9, 17, -3], [-3, 18, 3], "silver"),
    ]


def right_arm():
    """The right shoulder carries a silver plate."""
    return lit([shoulder(), Cube("epauliere_plaque", [-9, 25, -1], [-6, 26, 1], "silver")] + cuff())


def left_arm():
    return lit([mirror(c) for c in [shoulder()] + cuff()])


def right_boot():
    """Raised toe, snowy cuff, the buckle and an ice rune painted on."""
    rune = stamp(boot(5, 3), 1, 0, [" B ", "ByB", " B "])
    return lit([
        Cube("semelle", [-4.5, 0, -2.5], [0.5, 1, 4.5], "sole"),
        Cube("botte", [-4.5, 1, -2.5], [0.5, 4, 2.5], "boot", west=rune),
        Cube("pied", [-4.5, 1, 2.5], [0.5, 3, 4.5], "boot"),
        Cube("pointe", [-3.5, 2, 4.5], [-0.5, 4, 5.5], "boot"),
        Cube("revers", [-5, 4, -3], [1, 6, 3], "snow", south=stamp(snow(6, 2), 2, 0, ["WG", "GK"])),
    ])


GROUPS = {"plastron_torse": torso, "plastron_bras_droit": right_arm, "plastron_bras_gauche": left_arm,
          "jambieres_torse": lambda: [], "jambieres_jambe_droite": lambda: [], "bottes_jambe_droite": right_boot}


def mantle_only(model, head):
    keep = lambda cubes: [c for c in cubes if not c.style.startswith("manchette")]
    return {"plastron_torse": model["plastron_torse"], "plastron_bras_droit": keep(model["plastron_bras_droit"]),
            "plastron_bras_gauche": keep(model["plastron_bras_gauche"])}


def cuffs_only(model, head):
    keep = lambda cubes: [c for c in cubes if c.style.startswith("manchette")]
    return {"plastron_bras_droit": keep(model["plastron_bras_droit"]), "plastron_bras_gauche": keep(model["plastron_bras_gauche"])}


if __name__ == "__main__":
    run(__doc__, GROUPS, hat, {"casque": (-3.0, 0.55), "plastron": (0, 0.55), "bottes": (0, 0.9)},
        {"cryomancer_diademe_zero_absolu": "tete", "cryomancer_mantelet_brumes": mantle_only,
         "cryomancer_manchettes_coeur_polaire": cuffs_only, "cryomancer_solerets_hiver": "bottes"})

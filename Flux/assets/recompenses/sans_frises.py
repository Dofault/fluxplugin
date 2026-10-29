"""Menu Récompenses sans ses deux frises (2026-10-29) : la connexion et le temps de jeu partent dans le menu Fidélité
(FluxRewards, /fidelite), /recompenses ne garde que la boîte des récompenses en attente.

Coupe le fond retouché à la main par l'utilisateur sous la grande étoile (rangée HAUT) et y recolle le bas du cadre
du même fond (BAS) : rien n'est redessiné, seule la partie des frises disparaît.

Usage : python sans_frises.py <fond complet png> <fond coupé png> [aperçu agrandi]  (Pillow requis)
"""
import sys

from PIL import Image

HAUT = 138       # première rangée retirée : sous la pointe basse de l'étoile, avant la boîte des frises
BAS = (200, 210)  # bas du cadre recollé (fin du dégradé et bordure)


def couper(src):
    im = Image.open(src).convert("RGBA")
    out = Image.new("RGBA", im.size, (0, 0, 0, 0))
    out.alpha_composite(im.crop((0, 0, im.width, HAUT)))
    out.alpha_composite(im.crop((0, BAS[0], im.width, BAS[1])), (0, HAUT))
    return out


if __name__ == "__main__":
    im = couper(sys.argv[1])
    im.save(sys.argv[2])
    if len(sys.argv) > 3:
        vue = Image.new("RGBA", im.size, (60, 60, 60, 255))
        vue.alpha_composite(im)
        vue.resize((im.width * 3, im.height * 3), Image.NEAREST).save(sys.argv[3])

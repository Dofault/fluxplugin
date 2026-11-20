#!/usr/bin/env python3
"""Chances de tirage des boosters et courbes de niveau des familiers : une seule source pour
gen_familiers.py (familiers de carrière et de prestige) et gen_familiers_boss.py (légendaires de boss).

- UNLOCK_TARGET : chance d'adopter un familier par booster de SA série (carrière ou Prestige).
- SERIES_WEIGHTS : poids des raretés des cartes dans un booster de carrière.
- level_curve(total) : cartes demandées par niveau pour atteindre le niveau max avec `total` cartes.
- cards_to_max(...) : nombre moyen de cartes d'un familier dans BOOSTERS_TO_MAX boosters de sa série.
"""
from effets_familiers import MAX_LEVEL

RARITIES = ["simple", "unique", "elite", "ultimate", "legendary", "fabled"]

# Chance d'adopter le familier par booster de sa série. 2026-11-14 : mythique 1 sur 100 (1 sur 300 avant), les
# paliers du dessous suivent la même échelle (chaque palier deux fois plus rare que le précédent), et le légendaire
# de boss (un par carrière qui a un donjon) prend le 1 sur 300.
UNLOCK_TARGET = {"simple": 0.20, "unique": 0.04, "elite": 0.02, "ultimate": 0.01,
                 "legendary": 1 / 300, "fabled": 0.001}
# Prestige (légendaires et fabuleux de toutes les carrières) : chances d'avant le 2026-11-14, inchangées.
PRESTIGE_UNLOCK = {"legendary": 0.0025, "fabled": 0.001}

# Boosters de carrière : 4 cartes de commune à ultime, plus la carte du légendaire de boss (poids 150 depuis le
# 2026-11-14 : 1,5 % des tirages, de quoi monter le légendaire au niveau max en 400 boosters comme les autres).
SERIES_WEIGHTS = (2500, 3818, 2318, 1364, 150, 5)
PRESTIGE_WEIGHTS = (400, 280, 170, 100, 45, 15)

# Tous les familiers atteignent le niveau max en BOOSTERS_TO_MAX boosters de leur série, en moyenne.
BOOSTERS_TO_MAX = 400
CAREER_DRAWS, PRESTIGE_DRAWS = 8, 5


def pet_draw_rate(targets, n):
    """Un seul familier au maximum par ouverture : chance par tirage pour que P(au moins un familier sur n
    tirages) = somme des chances voulues ; le familier est ensuite choisi au prorata de ces chances."""
    total = sum(targets.values())
    return 1 - (1 - total) ** (1 / n)


def level_curve(total):
    """Cartes demandées pour chaque niveau (2 au niveau 2), palier n proportionnel à n^1.2 + 3, somme = total."""
    f = [n ** 1.2 + 3 for n in range(1, MAX_LEVEL)]
    total = int(round(total))
    reqs = []
    for v in f:
        reqs.append(max(reqs[-1] if reqs else 2, int(round(total * v / sum(f)))))
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


def cards_to_max(series, rarity, weights, draws, unlock):
    """Cartes moyennes d'un familier de cette rareté dans BOOSTERS_TO_MAX boosters de sa série.
    series : raretés des familiers de la série (une entrée par familier)."""
    w = dict(zip(RARITIES, weights))
    present = set(series)
    card_share = w[rarity] / sum(w[r] for r in present) / series.count(rarity)
    pet_total = sum(unlock[r] for r in series)
    return BOOSTERS_TO_MAX * (draws - pet_total) * card_share

#!/usr/bin/env python3
"""One-time bootstrap of FluxValue's sources.yml: reads what exists today and writes the weight of every source.

    gen_values.py <EcoJobs jobs dir> <tranchee.yml> <metiers.xlsx> <dir with data/minecraft from the server jar> <out.yml>

Once written, sources.yml is the source of truth and is edited by hand: this script exists so the starting values can
be checked and rebuilt, never run it over a file that has been tuned.

Weight scale: 1 point = what the Rochiste earned per XP (7050 points in 2 minutes of optimal play, every job).
A job paying x XP with the workbook multiplier m gives x * m * 10 points, which keeps today's money and time per level.
"""
import glob, json, os, re, sys, unicodedata, zipfile
import xml.etree.ElementTree as ET
import yaml

JOBS_DIR, TRANCHEE, WORKBOOK, DATA, OUT = sys.argv[1:6]
FROZEN = {'defenseur'}  # hand-tuned job: its XP in EcoJobs is not generated
REFERENCE_HP_POINTS = 4.23  # points per max health of a mob: today's Defenseur (0.423 BitBlock per HP at level 100)

# What breaking a block gives with Fortune III, in optimal play. Anything else gives itself.
# Several items of one line are alternatives (silk touch or not), never a joint harvest.
BLOCK_YIELDS = {
    'stone': {'STONE': 1, 'COBBLESTONE': 1},
    'deepslate': {'DEEPSLATE': 1, 'COBBLED_DEEPSLATE': 1},
    'coal_ore': {'COAL': 2.2}, 'deepslate_coal_ore': {'COAL': 2.2},
    'iron_ore': {'RAW_IRON': 2.2}, 'deepslate_iron_ore': {'RAW_IRON': 2.2},
    'copper_ore': {'RAW_COPPER': 7.7}, 'deepslate_copper_ore': {'RAW_COPPER': 7.7},
    'gold_ore': {'RAW_GOLD': 2.2}, 'deepslate_gold_ore': {'RAW_GOLD': 2.2},
    'redstone_ore': {'REDSTONE': 6}, 'deepslate_redstone_ore': {'REDSTONE': 6},
    'lapis_ore': {'LAPIS_LAZULI': 14.3}, 'deepslate_lapis_ore': {'LAPIS_LAZULI': 14.3},
    'diamond_ore': {'DIAMOND': 2.2}, 'deepslate_diamond_ore': {'DIAMOND': 2.2},
    'emerald_ore': {'EMERALD': 2.2}, 'deepslate_emerald_ore': {'EMERALD': 2.2},
    'nether_gold_ore': {'GOLD_NUGGET': 8.8},
    'nether_quartz_ore': {'QUARTZ': 2.2},
    'glowstone': {'GLOWSTONE_DUST': 3.9, 'GLOWSTONE': 1},
    'gilded_blackstone': {'GOLD_NUGGET': 3.5, 'GILDED_BLACKSTONE': 1},
    'budding_amethyst': {},
    'grass_block': {'GRASS_BLOCK': 1, 'DIRT': 1}, 'mycelium': {'MYCELIUM': 1, 'DIRT': 1},
    'podzol': {'PODZOL': 1, 'DIRT': 1}, 'crimson_nylium': {'CRIMSON_NYLIUM': 1, 'NETHERRACK': 1},
    'warped_nylium': {'WARPED_NYLIUM': 1, 'NETHERRACK': 1},
    'snow': {'SNOWBALL': 1}, 'snow_block': {'SNOW_BLOCK': 1, 'SNOWBALL': 4},
    'gravel': {'GRAVEL': 1, 'FLINT': 1}, 'clay': {'CLAY': 1, 'CLAY_BALL': 4},
    # crops: the seed planted back is taken off, Fortune III counted
    'wheat': {'WHEAT': 1}, 'potatoes': {'POTATO': 3.42}, 'carrots': {'CARROT': 3.42}, 'beetroots': {'BEETROOT': 1},
    'sweet_berry_bush': {'SWEET_BERRIES': 2.5}, 'cave_vines': {'GLOW_BERRIES': 1}, 'cave_vines_plant': {'GLOW_BERRIES': 1},
    'melon': {'MELON_SLICE': 7.4, 'MELON': 1}, 'cocoa': {'COCOA_BEANS': 1.5}, 'nether_wart': {'NETHER_WART': 3.5},
    'chorus_plant': {'CHORUS_FRUIT': 0.5}, 'vine': {'VINE': 1}, 'kelp': {'KELP': 1},
    'open_eyeblossom': {'OPEN_EYEBLOSSOM': 1}, 'closed_eyeblossom': {'CLOSED_EYEBLOSSOM': 1},
    'red_mushroom_block': {'RED_MUSHROOM': 0.6, 'RED_MUSHROOM_BLOCK': 1}, 'brown_mushroom_block': {'BROWN_MUSHROOM': 0.6, 'BROWN_MUSHROOM_BLOCK': 1},
    'mushroom_stem': {'MUSHROOM_STEM': 1},
    'sculk_catalyst': {'SCULK_CATALYST': 1},
}

# Workbook label -> entity (same table as the former /sync-offrande).
MOB_LABELS = {
    'zombie': 'zombie', 'araignee': 'spider', 'squelette': 'skeleton', 'creeper': 'creeper', 'noye': 'drowned',
    'husk': 'husk', 'stray': 'stray', 'zombie villageois': 'zombie_villager', 'phantom': 'phantom',
    'enderman': 'enderman', 'bogged': 'bogged', 'silverfish': 'silverfish', 'pillager': 'pillager',
    'araignee venimeuse': 'cave_spider', 'slime': 'slime', 'sorciere': 'witch', 'gardien': 'guardian',
    'vindicator': 'vindicator', 'breeze': 'breeze', 'evoker': 'evoker', 'ravageur': 'ravager',
    'gardien ancien': 'elder_guardian', 'piglin zombifie': 'zombified_piglin', 'piglin': 'piglin',
    'magma cube': 'magma_cube', 'ghast': 'ghast', 'hoglin': 'hoglin', 'blaze': 'blaze',
    'wither squelette': 'wither_skeleton', 'zoglin': 'zoglin', 'piglin brute': 'piglin_brute',
    'endermite': 'endermite', 'shulker': 'shulker', 'wither': 'wither', 'warden': 'warden', 'ender dragon': 'ender_dragon',
    'parched': 'parched', 'creaking': 'creaking', 'zombie nautilus': 'zombie_nautilus', 'camel husk': 'camel_husk',
}
HP = {'zombie': 20, 'zombie_villager': 20, 'husk': 20, 'drowned': 20, 'skeleton': 20, 'stray': 20, 'bogged': 16, 'parched': 16,
      'wither_skeleton': 20, 'creeper': 20, 'spider': 16, 'cave_spider': 12, 'enderman': 40, 'endermite': 8, 'witch': 26,
      'slime': 16, 'magma_cube': 16, 'silverfish': 8, 'blaze': 20, 'ghast': 10, 'phantom': 20, 'pillager': 24, 'vindicator': 24,
      'evoker': 24, 'vex': 14, 'ravager': 100, 'illusioner': 32, 'guardian': 30, 'elder_guardian': 80, 'shulker': 30,
      'hoglin': 40, 'zoglin': 40, 'piglin': 16, 'zombified_piglin': 20, 'piglin_brute': 50, 'warden': 500, 'breeze': 30,
      'wither': 300, 'ender_dragon': 200, 'creaking': 1, 'zombie_nautilus': 15, 'camel_husk': 32}
# Peaceful animals: no job pays them, but offerings ask for what they drop. Weight in points per kill, to review.
PASSIVE = {'cow': 40, 'pig': 40, 'sheep': 40, 'chicken': 25, 'rabbit': 60, 'mooshroom': 80, 'squid': 40, 'glow_squid': 80,
           'turtle': 300, 'armadillo': 300, 'cod': 40, 'salmon': 40, 'pufferfish': 60, 'tropical_fish': 40, 'goat': 60}
# Items no job and no mob gives. Points per item (about 58.75 points per second of optimal play), to review.
OTHERS = {
    'AMETHYST_SHARD': 30, 'EGG': 20, 'HONEYCOMB': 150, 'HONEY_BOTTLE': 150, 'FEATHER': 20, 'WHITE_WOOL': 25, 'LEATHER': 40,
    'SEA_PICKLE': 120, 'SMALL_DRIPLEAF': 120, 'BIG_DRIPLEAF': 120, 'SHROOMLIGHT': 120, 'WEEPING_VINES': 25, 'TWISTING_VINES': 25,
    'MANGROVE_ROOTS': 30, 'MUD': 12, 'BASALT': 8, 'DRIPSTONE_BLOCK': 8, 'POINTED_DRIPSTONE': 16, 'TORCHFLOWER': 300,
    'PITCHER_PLANT': 300, 'SPORE_BLOSSOM': 300, 'GLASS_BOTTLE': 1, 'NAUTILUS_SHELL': 1500, 'TURTLE_EGG': 600, 'BEEHIVE': 800,
    'BAMBOO': 3, 'SUGAR_CANE': 6, 'ICE': 8, 'BLUE_ICE': 700, 'SCULK_VEIN': 10, 'LILY_PAD': 176, 'NETHER_SPROUTS': 100,
    'MOSS_BLOCK': 30, 'WATER_BUCKET': 0.1, 'PINK_PETALS': 12,
    'SLIME_BALL': 60, 'MAGMA_CREAM': 120, 'OMINOUS_BOTTLE': 3000, 'TURTLE_SCUTE': 400, 'ARMADILLO_SCUTE': 300, 'TRIDENT': 6000,
    'NETHER_STAR': 7614, 'DRAGON_BREATH': 600, 'DRAGON_HEAD': 8460, 'ECHO_SHARD': 800, 'WHEAT_SEEDS': 0.3, 'BEETROOT_SEEDS': 0.3,
    'APPLE': 60, 'MILK_BUCKET': 10, 'LAVA_BUCKET': 30, 'CRYING_OBSIDIAN': 400, 'PALE_MOSS_BLOCK': 30, 'RESIN_CLUMP': 80,
    'OCHRE_FROGLIGHT': 300, 'VERDANT_FROGLIGHT': 300, 'PEARLESCENT_FROGLIGHT': 300, 'POISONOUS_POTATO': 30, 'AMETHYST_CLUSTER': 120,
    'OAK_SAPLING': 10, 'BIRCH_SAPLING': 10, 'SPRUCE_SAPLING': 10, 'JUNGLE_SAPLING': 15, 'ACACIA_SAPLING': 15, 'DARK_OAK_SAPLING': 15,
    'CHERRY_SAPLING': 15, 'MANGROVE_PROPAGULE': 15, 'PALE_OAK_SAPLING': 15, 'NETHERITE_UPGRADE_SMITHING_TEMPLATE': 30000,
    'DIAMOND_HORSE_ARMOR': 1500, 'DIAMOND_NAUTILUS_ARMOR': 1500,
}
# Items that are another one with a tool or water: they follow it.
SAME_VALUE = {'FARMLAND': 'DIRT', 'EXPOSED_COPPER': 'COPPER_BLOCK', 'WEATHERED_COPPER': 'COPPER_BLOCK', 'OXIDIZED_COPPER': 'COPPER_BLOCK',
              'COD_BUCKET': 'COD', 'SALMON_BUCKET': 'SALMON', 'PUFFERFISH_BUCKET': 'PUFFERFISH', 'TROPICAL_FISH_BUCKET': 'TROPICAL_FISH'}
for wood in ('OAK', 'SPRUCE', 'BIRCH', 'JUNGLE', 'ACACIA', 'DARK_OAK', 'CHERRY', 'MANGROVE', 'PALE_OAK'):
    SAME_VALUE[f'STRIPPED_{wood}_LOG'] = f'{wood}_LOG'
    SAME_VALUE[f'STRIPPED_{wood}_WOOD'] = f'{wood}_WOOD'
for wood in ('CRIMSON', 'WARPED'):
    SAME_VALUE[f'STRIPPED_{wood}_STEM'] = f'{wood}_STEM'
    SAME_VALUE[f'STRIPPED_{wood}_HYPHAE'] = f'{wood}_HYPHAE'
SAME_VALUE['STRIPPED_BAMBOO_BLOCK'] = 'BAMBOO_BLOCK'
for colour in ('WHITE', 'LIGHT_GRAY', 'GRAY', 'BLACK', 'BROWN', 'RED', 'ORANGE', 'YELLOW', 'LIME', 'GREEN', 'CYAN', 'LIGHT_BLUE', 'BLUE', 'PURPLE', 'MAGENTA', 'PINK'):
    SAME_VALUE[f'{colour}_CONCRETE'] = f'{colour}_CONCRETE_POWDER'
# Enchantment on a book, per level (Enchanteur, level 30 table, 22 % useful levels per enchant, Labs 2026-09-16).
NETHERITE_TEMPLATE_POINTS = 30000


def norm(txt):
    txt = re.sub(r'\(.*?\)', ' ', str(txt))
    txt = unicodedata.normalize('NFD', txt).encode('ascii', 'ignore').decode()
    return re.sub(r'\s+', ' ', txt).strip().lower()


def mob_weights(path):
    ns = '{http://schemas.openxmlformats.org/spreadsheetml/2006/main}'
    rel = '{http://schemas.openxmlformats.org/officeDocument/2006/relationships}'
    z = zipfile.ZipFile(path)
    rels = {e.get('Id'): e.get('Target').lstrip('/') for e in ET.fromstring(z.read('xl/_rels/workbook.xml.rels'))}
    sheets = {norm(s.get('name')): rels[s.get(rel + 'id')] for s in ET.fromstring(z.read('xl/workbook.xml')).iter(ns + 'sheet')}
    shared = [''.join(t.text or '' for t in si.iter(ns + 't')) for si in ET.fromstring(z.read('xl/sharedStrings.xml')).iter(ns + 'si')]
    target = sheets['defenseur']
    out = {}
    for row in ET.fromstring(z.read(target if target.startswith('xl/') else 'xl/' + target)).iter(ns + 'row'):
        cells = {}
        for c in row.iter(ns + 'c'):
            v = c.find(ns + 'v')
            if v is not None:
                cells[re.match('[A-Z]+', c.get('r')).group()] = shared[int(v.text)] if c.get('t') == 's' else v.text
        label = norm(cells.get('A', ''))
        if label in MOB_LABELS and 'B' in cells:
            out[MOB_LABELS[label]] = float(cells['B'])
    return out


def loot(entity):
    """Average drops of a mob with Looting III: {ITEM: count per kill}."""
    path = f'{DATA}/data/minecraft/loot_table/entities/{entity}.json'
    if not os.path.exists(path):
        return {}
    out = {}
    for pool in json.load(open(path)).get('pools', []):
        rolls = pool.get('rolls', 1)
        rolls = rolls if isinstance(rolls, (int, float)) else (rolls.get('min', 1) + rolls.get('max', 1)) / 2
        chance = pool_chance(pool.get('conditions', []))
        entries = pool.get('entries', [])
        total = sum(e.get('weight', 1) for e in entries) or 1
        for e in entries:
            if e.get('type') != 'minecraft:item':
                continue
            count = 1.0
            for f in e.get('functions', []):
                kind = f.get('function')
                c = f.get('count')
                if kind == 'minecraft:set_count':
                    count = (c.get('min', 0) + c.get('max', 0)) / 2 if isinstance(c, dict) else c
                elif kind == 'minecraft:enchanted_count_increase':
                    count += 3 * ((c.get('min', 0) + c.get('max', 0)) / 2 if isinstance(c, dict) else c)
            item = e['name'].split(':')[1].upper()
            rate = rolls * chance * pool_chance(e.get('conditions', [])) * e.get('weight', 1) / total * count
            out[item] = out.get(item, 0) + rate
    return out


def pool_chance(conditions):
    chance = 1.0
    for c in conditions:
        kind = c.get('condition', '')
        if kind == 'minecraft:killed_by_player':
            continue
        if kind not in ('minecraft:random_chance', 'minecraft:random_chance_with_enchanted_bonus'):
            return 0.0  # variant of the mob (baby, sheared, on fire...): not what is farmed
        if kind == 'minecraft:random_chance':
            chance *= c.get('chance', 1) if isinstance(c.get('chance'), (int, float)) else 1
        elif kind == 'minecraft:random_chance_with_enchanted_bonus':
            ec = c.get('enchanted_chance', {})
            chance *= ec.get('base', 0) + 2 * ec.get('per_level_above_first', 0) if isinstance(ec, dict) else ec
    return chance


def clean(v):
    return round(v, 3)


def k_of(effect):
    m = re.search(r'round\(([\d.]+) \* %level%', str(effect['args']['amount']))
    return float(m.group(1)) if m else None


def tranchee_area():
    """Blocks that Tranchee breaks in a cube and whose harvest is really multiplied (ores and wood: a vein or a trunk is 1 wide)."""
    area = set()
    for e in yaml.safe_load(open(TRANCHEE))['effects']:
        for b in (e.get('args') or {}).get('whitelist', []) or []:
            if b.endswith('_ore') or 'log' in b or 'wood' in b or 'leaves' in b or 'bamboo' in b:
                continue
            area.add(b)
    return area


AREA = tranchee_area()
out = {'jobs': {}, 'mobs': {}, 'animals': {}, 'fishing': {}, 'others': {}}

for path in sorted(glob.glob(JOBS_DIR + '/*.yml')):
    job = os.path.basename(path)[:-4]
    if job.startswith('_') or job in FROZEN:
        continue
    data = yaml.safe_load(open(path))
    methods, effects = data['xp-gain-methods'], data['effects']
    k0 = k_of(effects[0])
    ratio = 10 * k0 / float(methods[0].get('multiplier', methods[0].get('value', 1)))  # points per XP of this job
    req = data['level-xp-requirements']
    section = {'points-per-xp-today': clean(ratio),
               'curve': {'base': clean(req[0] * ratio), 'exponent': 1.5 if job == 'chimiste' else 3},
               'sources': {}}
    placeholder_k = {}
    for p in data.get('level-placeholders') or []:
        m_k = re.search(r'round\(([\d.]+) \* %level%\)', str(p.get('value')))
        if m_k and str(p.get('id')).startswith('money'):
            placeholder_k[float(m_k.group(1))] = p['id']
    shared = None
    for m in methods:
        conds = m.get('conditions') or []
        if conds:
            shared = conds
    if shared:
        section['conditions'] = shared
    for m in methods:
        fixed = 'value' in m
        xp = float(m['value'] if fixed else m.get('multiplier', 1))
        filters = dict(m.get('filters') or {})
        blocks = filters.pop('blocks', None)
        trigger = m['trigger']
        if blocks:
            for b in blocks:
                entry = {'weight': clean(xp * ratio), 'trigger': trigger}
                if filters != {'player_placed': False}:
                    entry['filters'] = filters
                y = BLOCK_YIELDS.get(b, {b.upper(): 1})
                if b.endswith('_ore'):
                    y = dict(y, **{b.upper(): 1})  # silk touch
                if trigger == 'click_block':
                    entry['yields'] = {}
                    section['sources'][f'{b}_click'] = dict(entry, blocks=[b])
                    continue
                if y != {b.upper(): 1}:
                    entry['yields'] = y
                if b in AREA:
                    entry['area'] = True
                section['sources'][b] = entry
        else:
            sid = m.get('display-label') and norm(m['display-label']).replace(' ', '_') or trigger
            entry = {'weight': clean(xp * ratio), 'trigger': trigger, 'yields': {}}
            if fixed:
                entry['fixed-value'] = True
            place = placeholder_k.get(round(xp * ratio / 10, 4)) or next((v for k, v in placeholder_k.items() if abs(k - xp * ratio / 10) < 1e-3), None)
            if place:
                entry['placeholder'] = place
            if filters:
                entry['filters'] = filters
            if m.get('display-label'):
                entry['label'] = m['display-label']
            section['sources'][sid] = entry
    out['jobs'][job] = section

weights = mob_weights(WORKBOOK)
for entity, hp in HP.items():
    if entity not in weights and entity not in ('vex', 'illusioner'):
        continue
    w = hp * REFERENCE_HP_POINTS * weights.get(entity, 1)
    drops = {k: v for k, v in loot(entity).items() if v >= 0.01}
    out['mobs'][entity] = {'weight': clean(w), 'rarity': weights.get(entity, 1), 'yields': {k: clean(v) for k, v in drops.items()}}

for entity, w in PASSIVE.items():
    drops = {k: v for k, v in loot(entity).items() if v >= 0.01 and not k.startswith('COOKED_')}
    out['animals'][entity] = {'weight': w, 'yields': {k: clean(v) for k, v in drops.items()}}

# Fishing: every catch is one action; what it gives depends on the rod. Shares with the best rod (table of 2026-09-17).
pecheur = yaml.safe_load(open(JOBS_DIR + '/pecheur.yml'))
species = sorted({re.sub('^(common|unique|epic|mythic|legendary)_', '', x) for m in pecheur['xp-gain-methods']
                  for x in m.get('filters', {}).get('custom_fish_type', []) if re.match('^(common|unique|epic|mythic|legendary)_', x)})
out['fishing'] = {
    'catch-weight': clean(7050 / 12.4),  # 12.4 catches in 2 minutes (workbook)
    'rarities': {'common': 0.615, 'unique': 0.20, 'epic': 0.10, 'mythic': 0.03, 'legendary': 0.006},
    'species': species,
}
# One enchant at the table at level 30 = 30 x the weight per level of the Enchanteur; levels of each enchantment per use
# from the Monte-Carlo of the table (.claude/skills/sync-offrande/enchsim.py).
out['enchantments'] = {
    'use-weight': 1410,
    'levels-per-use': {'bane_of_arthropods': 0.16588, 'knockback': 0.09855, 'punch': 0.02867, 'fire_protection': 0.14404,
                       'blast_protection': 0.07238, 'projectile_protection': 0.00496, 'impaling': 0.07272,
                       'channeling': 0.0099, 'riptide': 0.0503},
}
out['others'] = OTHERS
out['same-value-as'] = SAME_VALUE

header = """# Poids de tout ce qui se farme : la seule source de valeur du serveur (généré une fois par assets/gen_values.py,
# édité à la main ensuite). Tout est en POINTS : 7050 points = 2 minutes de jeu optimal (economy.yml), quel que soit le métier.
#
# jobs.<métier>.sources.<bloc> :
#   weight : points d'une action (XP du métier et argent en découlent)
#   yields : objets obtenus par action (Fortune III comptée), plusieurs lignes = au choix (soie ou non) ; absent = le bloc lui-même
#   area   : true = Tranchée multiplie la récolte (facteur dans economy.yml), jamais l'XP du métier
# mobs.<créature> : weight = PV x 4,23 x rareté du classeur, yields = butin moyen (Butin III), le poids est partagé entre les butins
# others : objets qu'aucun métier ne donne (points par objet)
# same-value-as : objet qui n'est qu'un autre passé à l'outil ou à l'eau, il le suit
# Tout objet fabriqué (planches, lingots, blocs...) n'est PAS ici : sa valeur vient des vraies recettes du serveur.
"""
with open(OUT, 'w') as f:
    f.write(header)
    yaml.safe_dump(out, f, allow_unicode=True, sort_keys=False, width=140)
print('written', OUT, {k: len(v) for k, v in out.items()}, 'sources', sum(len(j['sources']) for j in out['jobs'].values()))

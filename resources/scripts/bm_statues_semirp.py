"""Copies BetterModel des 12 statues de rang pour SemiRP, calées sur les statues
statiques du Sénat (item_display Typewriter, page "statue", lue sans la modifier).

- Source : 04_labs/plugins/BetterModel/models/statue_*.bbmodel (avec animations).
- Taille x1,5 : Typewriter affiche l'item ItemsAdder (géométrie réduite de moitié,
  cf. bb2ia_statues.py) à l'échelle 3, et `bm test` n'a pas d'argument de taille.
- Orientation intégrée au modèle : `bm test` lancé depuis la console force yaw 0.
  item_display : vanilla tourne l'item de 180° + yaw de l'entité ;
  BetterModel : inverse X/Z (180°) + rotation du groupe racine.
  => racine = 0° si yaw Typewriter = 0, 180° si yaw = ±180.
- Position : pieds du modèle = y Typewriter - 3 (échelle 3 x 1 bloc sous le centre de l'item).

Relancer après toute modif d'un .bbmodel de Labs ou d'une position Typewriter,
puis `bm reload` et `iazip` sur SemiRP.
"""
import json, glob, os

ROOT = '/home/influencraft/serveurs'
SRC = f'{ROOT}/04_labs/plugins/BetterModel/models'
DST = f'{ROOT}/03_semirp/plugins/BetterModel/models'
PAGE = f'{ROOT}/03_semirp/plugins/Typewriter/pages/WbT69ahpZBiEmtm.json'
TW_SCALE = 3.0
K = TW_SCALE / 2  # le modèle ItemsAdder est à demi-taille
ANIM = 'apparition'

page = json.load(open(PAGE))
defs = {e['id']: e for e in page['entries']}
inst = {e['name']: e for e in page['entries'] if e['type'] == 'item_display_instance'}
for e in page['entries']:
    if e['type'] == 'scale3d_data':
        assert all(v == TW_SCALE for v in e['scale'].values()), e['scale']

m3 = lambda v: [round(x * K, 5) for x in v]

def scale_outliner(nodes):
    for n in nodes:
        if isinstance(n, dict):
            n['origin'] = m3(n.get('origin', [0, 0, 0]))
            scale_outliner(n.get('children', []))

os.makedirs(DST, exist_ok=True)
cmds = []
for src in sorted(glob.glob(f'{SRC}/statue_*.bbmodel')):
    key = os.path.basename(src)[:-8]            # statue_01_inconnu
    rang = key.split('_', 2)[2]                 # inconnu
    loc = inst[rang]['spawnLocation']
    yaw = loc['yaw'] % 360
    assert yaw in (0, 180), f'{rang}: yaw {loc["yaw"]} non géré'
    d = json.load(open(src))
    for e in d['elements']:
        e['from'], e['to'] = m3(e['from']), m3(e['to'])
        if 'origin' in e: e['origin'] = m3(e['origin'])
        if e.get('inflate'): e['inflate'] = round(e['inflate'] * K, 5)
    scale_outliner(d['outliner'])
    root = next(n for n in d['outliner'] if isinstance(n, dict))
    root['rotation'] = [0, 0 if yaw == 0 else 180, 0]
    for a in d.get('animations', []):
        for an in a.get('animators', {}).values():
            for kf in an.get('keyframes', []):
                if kf['channel'] == 'position':
                    for dp in kf['data_points']:
                        for ax in 'xyz':
                            dp[ax] = str(round(float(dp[ax]) * K, 5))
    json.dump(d, open(f'{DST}/{key}.bbmodel', 'w'), separators=(',', ':'))
    x, y, z = loc['x'], loc['y'] - TW_SCALE, loc['z']
    fmt = lambda v: f'{v:g}'
    cmds.append(f'{rang:12} bm test {key} {ANIM} %player_name% {fmt(x)} {fmt(y)} {fmt(z)}')

print('\n'.join(cmds))

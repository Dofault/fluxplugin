import json, glob, os, base64, sys
NS = 'influstatue'
BASE = '/home/influencraft/serveurs/04_labs/plugins/ItemsAdder/contents/' + NS
SCALE, OFF = 0.5, (8.0, -8.0, 8.0)
r = lambda v: round(v, 4)
def tp(p): return [r(p[i] * SCALE + OFF[i]) for i in range(3)]
for src in sorted(glob.glob(BASE + '/BBModels/statue_*.bbmodel')):
    d = json.load(open(src)); mid = os.path.basename(src)[:-8]
    texdir = f'{BASE}/textures/item/{mid}'; os.makedirs(texdir, exist_ok=True)
    texmap, uvs = {}, {}
    for i, t in enumerate(d['textures']):
        name = t['name'][:-4]
        open(f'{texdir}/{name}.png', 'wb').write(base64.b64decode(t['source'].split(',', 1)[1]))
        texmap[str(i)] = f'{NS}:item/{mid}/{name}'
        uvs[i] = (16 / t['uv_width'], 16 / t['uv_height'])
    texmap['particle'] = texmap['0']
    els = []
    for e in d['elements']:
        if e.get('visibility') is False or e.get('export') is False: continue
        inf = e.get('inflate', 0)
        fr = tp([e['from'][k] - inf for k in range(3)]); to = tp([e['to'][k] + inf for k in range(3)])
        for v in fr + to:
            if not -16 <= v <= 32: sys.exit(f'{mid}: hors limites {v}')
        o = {'from': fr, 'to': to}
        rot = e.get('rotation', [0, 0, 0])
        if any(rot):
            o['rotation'] = {'origin': tp(e['origin']), 'x': r(rot[0]), 'y': r(rot[1]), 'z': r(rot[2])}
        faces = {}; tex = None
        for fn, f in e['faces'].items():
            tex = f['texture']; su, sv = uvs[tex]
            u = f['uv']; fo = {'uv': [r(u[0]*su), r(u[1]*sv), r(u[2]*su), r(u[3]*sv)], 'texture': f'#{tex}'}
            if f.get('rotation'): fo['rotation'] = f['rotation']
            faces[fn] = fo
        if tex == 1: o['light_emission'] = 15   # texture "lueur" (emissive dans Blockbench)
        o['faces'] = faces
        els.append(o)
    os.makedirs(f'{BASE}/models', exist_ok=True)
    json.dump({'textures': texmap, 'elements': els}, open(f'{BASE}/models/{mid}.json', 'w'), separators=(', ', ': '))
    miny = min(e['from'][1] for e in els)
    print(mid, len(els), 'minY', miny, 'translation', r(2 * (8 - miny) / 16))

"""Scan region files of resaac: per column, blocks for y in [Y0, Y1). Saves pickle {(x,z): bytes of palette ids}, palette."""
import os, pickle, struct, sys, zlib
sys.path.insert(0, '/home/influencraft/plugins/fluxplugin/FluxDungeons/assets/maps')
sys.path.insert(0, '/home/influencraft/serveurs/00_scripts')
from maplib import _nbt
REG = '/home/influencraft/serveurs/04_labs/world/dimensions/minecraft/resaac/region'
SY0, SY1 = -2, 8            # sections: y -32 .. 127
def scan(rx, rz):
    out = f'r.{rx}.{rz}.pkl'
    if os.path.exists(out): return
    data = open(f'{REG}/r.{rx}.{rz}.mca', 'rb').read()
    names, ids = [], {}
    cols = {}
    for i in range(1024):
        off = struct.unpack('>I', b'\0' + data[i*4:i*4+3])[0]
        if not off: continue
        o = off * 4096
        ln = struct.unpack('>I', data[o:o+4])[0]
        if data[o+4] != 2: continue
        ch = _nbt(zlib.decompress(data[o+5:o+4+ln]))
        cx, cz = ch['xPos'], ch['zPos']
        grid = {}
        for sec in ch.get('sections', []):
            sy = sec['Y']
            if not (SY0 <= sy < SY1): continue
            bs = sec.get('block_states')
            if not bs: continue
            pal = []
            for p in bs['palette']:
                n = p['Name'].replace('minecraft:', '')
                pr = p.get('Properties') or {}
                if 'type' in pr and n.endswith('_slab'): n += ':' + pr['type']
                if pr.get('half') and (n.endswith('_stairs') or n.endswith('trapdoor')): n += ':' + pr['half']
                if n not in ids: ids[n] = len(names); names.append(n)
                pal.append(ids[n])
            if len(pal) == 1:
                grid[sy] = [pal[0]] * 4096; continue
            bits = max(4, (len(pal)-1).bit_length()); per = 64 // bits; mask = (1 << bits) - 1
            arr = bs['data']; sec_ids = []
            for v in arr:
                v &= 0xFFFFFFFFFFFFFFFF
                for _ in range(per):
                    sec_ids.append(pal[v & mask] if (v & mask) < len(pal) else 0); v >>= bits
            grid[sy] = sec_ids[:4096]
        if not grid: continue
        air = ids.setdefault('air', len(names)) if 'air' in ids else None
        if 'air' not in ids: ids['air'] = len(names); names.append('air')
        a = ids['air']
        for lz in range(16):
            for lx in range(16):
                col = []
                for sy in range(SY0, SY1):
                    g = grid.get(sy)
                    if g is None: col += [a] * 16
                    else: col += g[lx + lz*16::256]
                cols[(cx*16+lx, cz*16+lz)] = col
    pickle.dump((names, cols), open(out, 'wb'))
    print(out, len(cols), len(names), flush=True)
for a in sys.argv[1:]:
    rx, rz = a.split(',')
    scan(int(rx), int(rz))

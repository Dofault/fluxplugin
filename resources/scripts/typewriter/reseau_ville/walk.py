import pickle, glob, sys, collections, re
sys.path.insert(0, '/home/influencraft/scripts/influcard')
from png_util import write
Y0 = -32
names = []; nid = {}; cols = {}
for f in glob.glob('r.*.pkl'):
    n, c = pickle.load(open(f, 'rb'))
    m = []
    for x in n:
        if x not in nid: nid[x] = len(names); names.append(x)
        m.append(nid[x])
    for k, col in c.items(): cols[k] = bytes(0) if False else [m[v] for v in col]
PASS_RE = re.compile(r'^(air|cave_air|void_air|light|.*_carpet|pink_petals|wildflowers|leaf_litter|short_grass|tall_grass|fern|large_fern|short_dry_grass|tall_dry_grass|bush|firefly_bush|dandelion|poppy|blue_orchid|allium|azure_bluet|.*_tulip|oxeye_daisy|cornflower|lily_of_the_valley|torchflower|sunflower|lilac|rose_bush|peony|.*_button|.*_sign|.*_banner|torch|wall_torch|soul_torch|soul_wall_torch|redstone_torch|redstone_wall_torch|glow_lichen|vine|rail|powered_rail|.*_pressure_plate|snow|dead_bush|sugar_cane|.*_sapling|azalea_sapling|tripwire|string|lever|redstone_wire|.*_mushroom|sweet_berry_bush|cobweb|structure_void|.*_door.*|.*fence_gate.*|open_eyeblossom|closed_eyeblossom|cactus_flower|spore_blossom|hanging_roots|pale_hanging_moss|sculk_vein|resin_clump|ladder|scaffolding|small_dripleaf|big_dripleaf_stem|moss_carpet|pale_moss_carpet|.*coral_fan|.*coral_wall_fan|.*_coral|seagrass|kelp.*)$')
LIQ = {'water', 'lava', 'bubble_column'}
kind = []   # 0 passable, 1 solid full, 2 bottom slab, 3 liquid, 4 solid no-stand (fence/wall/barrier...)
for n in names:
    b = n.split(':')[0]
    if b in LIQ: kind.append(3)
    elif PASS_RE.match(b) and not b.endswith('trapdoor'): kind.append(0)
    elif n.endswith('_slab:bottom'): kind.append(2)
    elif re.search(r'(_fence|_wall|barrier|iron_bars|glass_pane|chain|lantern|_head|_skull|flower_pot|candle|end_rod|lightning_rod|bamboo)$', b) or b.startswith('potted_'): kind.append(4)
    else: kind.append(1)
def k(x, y, z):
    c = cols.get((x, z))
    if c is None: return 4
    i = y - Y0
    if i < 0 or i >= len(c): return 0 if i >= len(c) else 4
    return kind[c[i]]
def stand(x, y, z):
    """height of the floor when one can stand with feet in block y (None otherwise)."""
    f = k(x, y, z)
    if f == 2:      # bottom slab in feet block
        return y + 0.5 if k(x, y+1, z) == 0 and k(x, y+2, z) == 0 else None
    if f != 0: return None
    if k(x, y+1, z) != 0: return None
    return float(y) if k(x, y-1, z) == 1 else None
def flood(start, limit=400):
    sx, sy, sz = start
    seen = {(sx, sz, sy): 0}
    q = collections.deque([(sx, sy, sz)])
    while q:
        x, y, z = q.popleft(); h = stand(x, y, z)
        for dx, dz in ((1,0),(-1,0),(0,1),(0,-1)):
            nx, nz = x+dx, z+dz
            if abs(nx-sx) > limit or abs(nz-sz) > limit: continue
            for ny in (y+1, y, y-1, y-2):
                nh = stand(nx, ny, nz)
                if nh is None: continue
                if nh - h > 1.01: continue
                if ny > y and k(x, y+2, z) != 0: continue
                if (nx, nz, ny) not in seen:
                    seen[(nx, nz, ny)] = seen[(x, z, y)] + 1; q.append((nx, ny, nz))
                break
    return seen
if __name__ == '__main__':
    import os
    seen = pickle.load(open('reach.pkl','rb')) if os.path.exists('reach.pkl') else flood((0, 4, 16))
    pickle.dump(seen, open('reach.pkl', 'wb'))
    xs = [p[0] for p in seen]; zs = [p[1] for p in seen]; ys = [p[2] for p in seen]
    print(len(seen), min(xs), max(xs), min(zs), max(zs), min(ys), max(ys))
    R = 420; S = 2
    import collections as C
    print(sorted(C.Counter(p[2] for p in seen).items()))
    img = [[(20, 20, 24, 255)] * (2*R*S) for _ in range(2*R*S)]
    def put(x, z, c):
        if -R <= x < R and -R <= z < R:
            for j in range(S):
                for i in range(S): img[(z+R)*S+j][(x+R)*S+i] = c
    for (x, z), c in cols.items():
        if not (-R <= x < R and -R <= z < R): continue
        top = None
        for i in range(len(c)-1, -1, -1):
            if names[c[i]] != 'air' and names[c[i]] != 'light' and names[c[i]] != 'barrier': top = i; break
        if top is None: continue
        n = names[c[top]]; y = top + Y0
        if 'water' in n: col = (40, 80, 170, 255)
        elif 'leaves' in n: col = (30, 80, 40, 255)
        elif 'grass' in n or 'moss' in n: col = (60, 110, 60, 255)
        else:
            g = max(40, min(200, 70 + y * 2)); col = (g, g, g, 255)
        put(x, z, col)
    for (x, z, y), d in seen.items():
        put(x, z, (255, max(0, min(255, 220 - y * 6)), max(0, min(255, 60 + y * 8)), 255))
    for i in range(-R, R, 50):
        for j in range(-R, R):
            pass
    write('map.png', 2*R*S, 2*R*S, img)

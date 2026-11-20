import pickle, sys, re, math, collections, json
sys.path.insert(0, '/home/influencraft/scripts/influcard')
from png_util import write
cells = pickle.load(open('cells.pkl','rb'))
PAVED = re.compile(r'andesite|^stone|tuff|light_gray_concrete$|dirt_path|cobble|smooth_stone|stone_brick')
P = {k: v[0] for k, v in cells.items() if PAVED.search(v[1].split(':')[0])}
N4 = ((1,0),(-1,0),(0,1),(0,-1))
def comp(start, ok):
    seen = {start}; q = collections.deque([start])
    while q:
        x, z = q.popleft()
        for dx, dz in N4:
            n = (x+dx, z+dz)
            if n in ok and n not in seen and abs(ok[n]-ok[(x,z)]) <= 1: seen.add(n); q.append(n)
    return seen
start = min(P, key=lambda p: (p[0])**2 + (p[1]-16)**2)
P = {k: P[k] for k in comp(start, P)}
# clearance: Chebyshev distance to the nearest non-street cell (or height break)
clear = {}
for (x, z), y in P.items():
    r = 0
    while r < 6:
        r += 1
        if any(abs(P.get((x+dx, z+dz), 999) - y) > 2 for dx in range(-r, r+1) for dz in (-r, r)) or \
           any(abs(P.get((x+dx, z+dz), 999) - y) > 2 for dz in range(-r, r+1) for dx in (-r, r)):
            r -= 1; break
    clear[(x, z)] = r
cand = sorted((k for k in P if clear[k] >= 2), key=lambda k: (-clear[k], k))
SP = 14
nodes = []
for k in cand:
    if all((k[0]-n[0])**2 + (k[1]-n[1])**2 >= SP*SP for n in nodes): nodes.append(k)
walk = {k: P[k] for k in P if clear[k] >= 1}
def dist(a, limit):
    d = {a: 0}; q = collections.deque([a])
    while q:
        c = q.popleft()
        if d[c] >= limit: continue
        for dx, dz in N4:
            n = (c[0]+dx, c[1]+dz)
            if n in walk and n not in d and abs(walk[n]-walk[c]) <= 1: d[n] = d[c]+1; q.append(n)
    return d
edges = set()
for a in nodes:
    d = dist(a, 60)
    near = sorted((math.dist(a, b), b) for b in nodes if b != a and math.dist(a, b) <= 30)
    k = 0
    for e, b in near:
        if b in d and d[b] <= 1.45 * e + 4:       # manhattan steps vs euclid
            edges.add((min(a, b), max(a, b))); k += 1
            if k >= 4: break
adj = collections.defaultdict(set)
for a, b in edges: adj[a].add(b); adj[b].add(a)
root = min(nodes, key=lambda p: p[0]**2 + (p[1]-16)**2)
main = {root}; q = [root]
while q:
    c = q.pop()
    for n in adj[c]:
        if n not in main: main.add(n); q.append(n)
nodes = [n for n in nodes if n in main]
edges = [(a, b) for a, b in edges if a in main and b in main]
print(len(P), len(nodes), len(edges))
json.dump({'nodes': [[x, P[(x, z)], z] for x, z in nodes], 'edges': [[nodes.index(a), nodes.index(b)] for a, b in edges]}, open('net.json', 'w'))
x0,x1,z0,z1 = -130,140,-60,200; S=4
img=[[(20,20,24,255)]*((x1-x0)*S) for _ in range((z1-z0)*S)]
def px(x, z, c, r=0):
    for j in range(-r, S+r):
        for i in range(-r, S+r):
            X, Z = (x-x0)*S+i, (z-z0)*S+j
            if 0 <= X < (x1-x0)*S and 0 <= Z < (z1-z0)*S: img[Z][X] = c
for (x,z),(y,f) in cells.items():
    if x0<=x<x1 and z0<=z<z1: px(x, z, (95, 95, 70, 255) if (x,z) in P else (30, 70, 40, 255))
for a, b in edges:
    n = int(math.dist(a, b) * 2)
    for i in range(n+1):
        px(round(a[0]+(b[0]-a[0])*i/n), round(a[1]+(b[1]-a[1])*i/n), (60, 200, 255, 255))
for n in nodes: px(n[0], n[1], (255, 60, 60, 255), 3)
px(0, 16, (255, 255, 255, 255), 4)
write('net.png',(x1-x0)*S,(z1-z0)*S,img)

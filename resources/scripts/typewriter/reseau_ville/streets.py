import pickle, collections, sys
import walk as W
seen = pickle.load(open('reach.pkl', 'rb'))
ZMAX = 194
cells = {}
for (x, z, y) in seen:
    if z > ZMAX: continue
    c = W.cols[(x, z)]
    i = y - W.Y0
    # outdoor: nothing but passable / leaves above the head
    out = all(W.kind[v] == 0 or 'leaves' in W.names[v] for v in c[i+2:])
    if not out: continue
    floor = W.names[c[i]] if W.kind[c[i]] == 2 else W.names[c[i-1]]
    cells[(x, z)] = (y, floor)        # one outdoor cell per column (highest wins is fine)
pickle.dump(cells, open('cells.pkl', 'wb'))
cnt = collections.Counter(f.split(':')[0] for y, f in cells.values())
print(len(cells)); print(cnt.most_common(60))
print(sorted(collections.Counter(y for y, f in cells.values()).items()))

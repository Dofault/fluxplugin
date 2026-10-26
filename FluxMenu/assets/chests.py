import sys
sys.path.insert(0, sys.argv[3])
import png
src,outdir=sys.argv[1],sys.argv[2]
w,h,rows=png.read(src)
crop=lambda r: r[:176*4]
for n in range(1,7):
    top=[crop(rows[y]) for y in range(n*18+17)]
    bottom=[crop(rows[y]) for y in range(126,222)]
    img=top+bottom
    # largeur réelle vue par Minecraft (dernière colonne non transparente)
    last=max(x for x in range(176) for r in img if r[x*4+3]!=0)
    png.write(f"{outdir}/chest_{n}.png",176,len(img),img)
    print(n, 'rangées ->', 176, 'x', len(img), 'largeur utile', last+1)
# texture vanilla remplacée : entièrement transparente, même taille
png.write(f"{outdir}/generic_54_transparent.png",w,h,[bytes(w*4)]*h)

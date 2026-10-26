import zlib,struct
def read(path):
    d=open(path,'rb').read(); w,h=struct.unpack('>II',d[16:24]); depth,ctype=d[24],d[25]
    assert (ctype==6 and depth==8) or ctype==3, 'RGBA 8 bits ou palette attendu'
    i=8; idat=b''; plte=b''; trns=b''
    while i<len(d):
        n,=struct.unpack('>I',d[i:i+4]); t=d[i+4:i+8]
        if t==b'IDAT': idat+=d[i+8:i+8+n]
        elif t==b'PLTE': plte=d[i+8:i+8+n]
        elif t==b'tRNS': trns=d[i+8:i+8+n]
        i+=12+n
    raw=zlib.decompress(idat)
    if ctype==3:
        stride=(w*depth+7)//8; bpp=1
    else:
        bpp=4; stride=w*bpp
    rows=[]; prev=bytearray(stride); p=0
    for y in range(h):
        f=raw[p]; p+=1; line=bytearray(raw[p:p+stride]); p+=stride
        for x in range(stride):
            a=line[x-bpp] if x>=bpp else 0; b=prev[x]; c=prev[x-bpp] if x>=bpp else 0
            if f==1: line[x]=(line[x]+a)&255
            elif f==2: line[x]=(line[x]+b)&255
            elif f==3: line[x]=(line[x]+(a+b)//2)&255
            elif f==4:
                pa=abs(b-c); pb=abs(a-c); pc=abs(a+b-2*c)
                line[x]=(line[x]+(a if pa<=pb and pa<=pc else (b if pb<=pc else c)))&255
        rows.append(bytes(line)); prev=line
    if ctype==3:
        out=[]
        for line in rows:
            px=bytearray()
            for x in range(w):
                bit=x*depth; v=(line[bit//8]>>(8-depth-bit%8))&((1<<depth)-1)
                px+=plte[v*3:v*3+3]+bytes([trns[v] if v<len(trns) else 255])
            out.append(bytes(px))
        rows=out
    return w,h,rows
def write(path,w,h,rows):
    out=b''.join(b'\x00'+r for r in rows)
    ch=lambda t,data: struct.pack('>I',len(data))+t+data+struct.pack('>I',zlib.crc32(t+data)&0xffffffff)
    open(path,'wb').write(b'\x89PNG\r\n\x1a\n'+ch(b'IHDR',struct.pack('>IIBBBBB',w,h,8,6,0,0,0))+ch(b'IDAT',zlib.compress(out,9))+ch(b'IEND',b''))

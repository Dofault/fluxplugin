// ---- Buste du profil (Flux /profil) -------------------------------------------------------------------------
// Le buste est fait de 4 têtes de joueur (tête, torse, bras droit, bras gauche) que des modèles d'objet mettent en
// forme et en place (plugins/fluxplugin/Flux/assets/buste/gen_buste.py, qui écrit aussi buste_constantes.glsl).
// Chaque pièce a son quart de tour marqueur : la normale d'une face, ramenée dans le repère du buste, dit de quelle
// pièce il s'agit ; ce pixel du cube de tête (reconnu à sa zone de texture, le chapeau à partir de u = 32) prend
// alors le pixel correspondant de la pièce dans le skin (torse, bras, veste, manches). Une tête ordinaire ne colle
// à aucun marqueur (angle de vue du buste inhabituel) et reste intacte. Menus seulement (projection orthographique).

// face d'une boîte : 0 dessus, 1 dessous, 2 droite (-x), 3 avant (+z), 4 gauche (+x), 5 arrière (-z)
vec3 busteNormale(int f) {
    return f == 0 ? vec3(0, 1, 0) : f == 1 ? vec3(0, -1, 0) : f == 2 ? vec3(-1, 0, 0)
         : f == 3 ? vec3(0, 0, 1) : f == 4 ? vec3(1, 0, 0) : vec3(0, 0, -1);
}

vec3 busteAxeU(int f) {
    return f == 2 ? vec3(0, 0, 1) : f == 4 ? vec3(0, 0, -1) : f == 5 ? vec3(-1, 0, 0) : vec3(1, 0, 0);
}

vec3 busteAxeV(int f) {
    return f <= 1 ? vec3(0, 0, 1) : vec3(0, -1, 0);
}

// rectangle (x, y, largeur, hauteur) de la face f d'une boîte de texture (u0, v0, w, h, d)
vec4 busteRect(int f, vec2 o, float w, float h, float d) {
    if (f == 0) return vec4(o.x + d, o.y, w, d);
    if (f == 1) return vec4(o.x + d + w, o.y, w, d);
    if (f == 2) return vec4(o.x, o.y + d, d, h);
    if (f == 3) return vec4(o.x + d, o.y + d, w, h);
    if (f == 4) return vec4(o.x + d + w, o.y + d, d, h);
    return vec4(o.x + 2.0 * d + w, o.y + d, w, h);
}

int busteFace(vec3 n) {
    for (int f = 0; f < 6; f++) if (dot(busteNormale(f), n) > 0.5) return f;
    return 3;
}

// Coordonnées de texture du buste pour ce pixel, false s'il n'en fait pas partie. masque : pixel à ne pas dessiner ;
// lumiere : éclairage de la face, la lumière venant de devant le joueur (celle des menus vient d'en haut et de côté,
// pensée pour les blocs vus en biais : elle laissait le visage dans l'ombre).
bool busteUV(out vec2 uv, out bool masque, out float lumiere) {
    uv = vec2(0.0);
    masque = false;
    lumiere = 1.0;
    if (ProjMat[2][3] != 0.0) return false;
    ivec2 ts = textureSize(Sampler0, 0);
    if (ts.x != 64 || (ts.y != 64 && ts.y != 32)) return false;
    vec2 px = origTexCoord0 * vec2(ts);
    if (px.y >= 16.0) return false;
    bool chapeau = px.x >= 32.0;
    vec2 l = vec2(chapeau ? px.x - 32.0 : px.x, px.y);
    int f;
    if (l.y < 8.0) {
        if (l.x >= 8.0 && l.x < 16.0) f = 0;
        else if (l.x >= 16.0 && l.x < 24.0) f = 1;
        else return false;
    } else {
        f = l.x < 8.0 ? 2 : l.x < 16.0 ? 3 : l.x < 24.0 ? 4 : 5;
    }
    vec4 r = busteRect(f, vec2(0.0), 8.0, 8.0, 8.0);
    float fu = (l.x - r.x) / r.z, fv = (l.y - r.y) / r.w;
    vec3 m = BUSTE_VUE_INV * normalize(vNormal);
    vec3 am = abs(m);
    if (max(am.x, max(am.y, am.z)) < 0.98) return false;
    vec3 n = busteNormale(f);
    int piece = -1;
    for (int k = 0; k < 4; k++) if (dot(BUSTE_ROT[k] * n, m) > 0.98) piece = k;
    if (piece < 0) return false;
    mat3 R = BUSTE_ROT[piece];
    vec3 p = R * (n + busteAxeU(f) * (2.0 * fu - 1.0) + busteAxeV(f) * (2.0 * fv - 1.0));
    int ft = busteFace(R * n);
    lumiere = ft == 3 ? 1.0 : ft == 0 ? 0.9 : ft == 2 || ft == 4 ? 0.72 : 0.55;
    bool fin = length(texture(Sampler0, vec2(54.5 / 64.0, 20.5 / float(ts.y))).rgb) == 0.0 && ts.y == 64;
    float bras = fin ? 3.0 : 4.0;
    bool ancien = ts.y == 32;
    if (ancien && chapeau && piece > 0) {
        masque = true;  // skin 64 x 32 : ni veste ni manches
        return true;
    }
    vec2 o;
    vec3 taille;
    if (piece == 0) { o = chapeau ? vec2(32.0, 0.0) : vec2(0.0); taille = vec3(8.0, 8.0, 8.0); }
    else if (piece == 1) { o = chapeau ? vec2(16.0, 32.0) : vec2(16.0, 16.0); taille = vec3(8.0, BUSTE_CUT, 4.0); }
    else if (piece == 2 || ancien) { o = chapeau ? vec2(40.0, 32.0) : vec2(40.0, 16.0); taille = vec3(bras, BUSTE_CUT, 4.0); }
    else { o = chapeau ? vec2(48.0, 48.0) : vec2(32.0, 48.0); taille = vec3(bras, BUSTE_CUT, 4.0); }
    vec4 rt = busteRect(ft, o, taille.x, taille.y, taille.z);
    vec2 t = vec2((dot(p, busteAxeU(ft)) + 1.0) * 0.5, (dot(p, busteAxeV(ft)) + 1.0) * 0.5);
    uv = (rt.xy + clamp(t * rt.zw, vec2(0.01), rt.zw - 0.01)) / vec2(ts);
    return true;
}

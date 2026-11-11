"""Recolours the ring of RPE-portals (the 3D window FluxPortal draws its portals with) from gold to the server's violet
and pink (2026-11-11). Portals is GPL-3.0: changing its shader is allowed. A newer RPE build overwrites its own
files, so run this again after any update of RPE-portals or RP Engine:

    python3 recolor_rpe_ring.py <plugins dir of the server>
    then, in game or by rcon: /rp reload ; then 04_labs/scripts/sync-rpe-pack.sh ; then /iazip

Idempotent: a file already recoloured is left as it is.
"""
import os
import sys

# gold / orange of the original -> violet, pink, lilac (charter: #B376E5, #E060A0, #5B2D9E)
SWAPS = [
    ("vec3(1.0, 0.5, 0.12) * exp(-abs(d) * 18.0)", "vec3(0.62, 0.30, 1.0) * exp(-abs(d) * 18.0)"),
    ("vec3(1.0, 0.55, 0.15) * exp(-abs(d) * 45.0)", "vec3(0.70, 0.40, 1.0) * exp(-abs(d) * 45.0)"),
    ("vec3(1.0, 0.92, 0.7) * exp(-abs(d) * 150.0)", "vec3(0.92, 0.85, 1.0) * exp(-abs(d) * 150.0)"),
    ("mix(vec3(1.0, 0.85, 0.5), vec3(1.0, 0.4, 0.08), along)", "mix(vec3(0.95, 0.75, 1.0), vec3(0.88, 0.30, 0.65), along)"),
    ("vec3(1.0, 0.6, 0.2) * inner", "vec3(0.75, 0.45, 1.0) * inner"),
    ("vec3(1.0, 0.45, 0.1) * halo", "vec3(0.60, 0.28, 0.95) * halo"),
    ("vec3(1.0, 0.62, 0.2) * smoothstep(rs * 1.6, rs * 0.8, d)", "vec3(0.88, 0.38, 0.70) * smoothstep(rs * 1.6, rs * 0.8, d)"),
    ("mix(vec3(1.0, 0.55, 0.15), vec3(1.0, 0.95, 0.8), smoothstep(0.1, 0.8, facing))", "mix(vec3(0.70, 0.40, 1.0), vec3(0.95, 0.90, 1.0), smoothstep(0.1, 0.8, facing))"),
    ("mix(vec3(1.0, 0.4, 0.08), vec3(1.0, 0.9, 0.6), sk)", "mix(vec3(0.88, 0.30, 0.65), vec3(0.95, 0.85, 1.0), sk)"),
    ("vec3(1.0, 0.45, 0.1) * lip", "vec3(0.60, 0.28, 0.95) * lip"),
]
MARK = "// FLUX: ring recoloured violet / pink (FluxPortal/assets/recolor_rpe_ring.py)"


def main(plugins):
    path = os.path.join(plugins, "RPEngine", "content", "portals", "resourcepack", "portals_v88", "assets", "minecraft",
                        "shaders", "core", "item.fsh")
    src = open(path, encoding="utf-8").read()
    if MARK in src:
        print("already recoloured:", path)
        return
    missing = [a for a, _ in SWAPS if a not in src]
    if missing:
        sys.exit("the shader changed, check these lines by hand: " + "; ".join(missing))
    for a, b in SWAPS:
        src = src.replace(a, b)
    lines = src.split("\n")
    lines.insert(1, MARK)
    open(path, "w", encoding="utf-8").write("\n".join(lines))
    print("recoloured:", path)


if __name__ == "__main__":
    main(sys.argv[1])

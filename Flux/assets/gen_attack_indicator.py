"""Empty sprites that hide the vanilla attack recharge indicator (crosshair and hotbar), for the attack cadence of
Flux (combat.attack-cadence). Written over the sprites of the pack influstatique, which had its own styled
indicator (originals kept in attack_indicator_influstatique/, copy them back if the cadence is turned off).

    python3 gen_attack_indicator.py <ItemsAdder/contents/influstatique>
"""
import os
import struct
import sys
import zlib

SPRITES = {
    "crosshair_attack_indicator_full": (16, 16),
    "crosshair_attack_indicator_background": (16, 4),
    "crosshair_attack_indicator_progress": (16, 4),
    "hotbar_attack_indicator_background": (18, 18),
    "hotbar_attack_indicator_progress": (18, 18),
}


def empty_png(w, h):
    def chunk(kind, data):
        c = kind + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)
    raw = b"".join(b"\x00" + b"\x00\x00\x00\x00" * w for _ in range(h))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def main(pack):
    out = os.path.join(pack, "resourcepack", "assets", "minecraft", "textures", "gui", "sprites", "hud")
    os.makedirs(out, exist_ok=True)
    for name, (w, h) in SPRITES.items():
        with open(os.path.join(out, name + ".png"), "wb") as f:
            f.write(empty_png(w, h))
    print("written:", out)


if __name__ == "__main__":
    main(sys.argv[1])

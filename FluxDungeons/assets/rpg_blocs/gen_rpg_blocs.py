#!/usr/bin/env python3
"""RPG look of the dungeon maps: every full block of a texture pack turned into an ItemsAdder block (REAL_NOTE, a note
block state), so the dungeons can wear the pack while the same vanilla blocks keep their usual look everywhere else.

The textures are not in this repository (third-party pack, no redistribution): the pack's zip is read where it lies.

  gen_rpg_blocs.py build <pack.zip> [server]   writes plugins/ItemsAdder/contents/rpgblocs/ (configs, models,
                                               textures) on the server, then `iazip` has to run;
  gen_rpg_blocs.py states [server]             after `iazip`: reads the note block states ItemsAdder gave each block
                                               (its generated pack) and writes plugins/FluxDungeons/rpg_blocs.yml,
                                               read by the map scripts (maplib, `Map.textures = "rpg"`).
  gen_rpg_blocs.py bedrock [server] [--rpg <pack.zip>]
                                               Bedrock players (Geyser): writes plugins/FluxDungeons/bedrock/, a Geyser
                                               block mapping (each rpgblocs note block state -> a Bedrock block) and a
                                               small Bedrock resource pack. By default each block wears Bedrock's own
                                               vanilla texture (stone looks like stone); --rpg puts the pack's in.

The pack is a 1.8 pack (pack_format 1): TABLE maps today's block ids onto its file names. Only full, opaque cubes can be
note blocks; blocks that give light, hold items or need their own behaviour (sea lantern, glass, ice that breaks under
a boss) stay vanilla, by design.
"""
import json
import os
import re
import struct
import sys
import zipfile
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, *[".."] * 5))
SERVERS = {"labs": "04_labs", "semilab": "05_semilab"}
NS = "rpgblocs"
# Bedrock kept the 1.8 file names of the block textures (textures/blocks/<name>.png in the vanilla Bedrock pack), the
# names TABLE already uses: the same table serves both games. Exceptions (renamed on Bedrock) go here.
BEDROCK_NAMES = {}

# today's id -> (model kind, textures of the 1.8 pack)
#   all: one texture; column: (side, end); bottom_top: (side, top, bottom)
TABLE = {
    # stone
    "stone": ("all", "stone"), "cobblestone": ("all", "cobblestone"), "mossy_cobblestone": ("all", "cobblestone_mossy"),
    "stone_bricks": ("all", "stonebrick"), "mossy_stone_bricks": ("all", "stonebrick_mossy"),
    "cracked_stone_bricks": ("all", "stonebrick_cracked"), "chiseled_stone_bricks": ("all", "stonebrick_carved"),
    "andesite": ("all", "stone_andesite"), "polished_andesite": ("all", "stone_andesite_smooth"),
    "diorite": ("all", "stone_diorite"), "polished_diorite": ("all", "stone_diorite_smooth"),
    "granite": ("all", "stone_granite"), "polished_granite": ("all", "stone_granite_smooth"),
    "bedrock": ("all", "bedrock"), "obsidian": ("all", "obsidian"), "bricks": ("all", "brick"),
    "smooth_stone": ("bottom_top", "stone_slab_side", "stone_slab_top", "stone_slab_top"),
    # earth
    "dirt": ("all", "dirt"), "coarse_dirt": ("all", "coarse_dirt"), "clay": ("all", "clay"), "gravel": ("all", "gravel"),
    "sand": ("all", "sand"), "red_sand": ("all", "red_sand"),
    "podzol": ("bottom_top", "dirt_podzol_side", "dirt_podzol_top", "dirt"),
    "mycelium": ("bottom_top", "mycelium_side", "mycelium_top", "dirt"),
    "snow_block": ("all", "snow"), "packed_ice": ("all", "ice_packed"),
    "sandstone": ("bottom_top", "sandstone_normal", "sandstone_top", "sandstone_bottom"),
    "chiseled_sandstone": ("bottom_top", "sandstone_carved", "sandstone_top", "sandstone_top"),
    "cut_sandstone": ("bottom_top", "sandstone_smooth", "sandstone_top", "sandstone_top"),
    "red_sandstone": ("bottom_top", "red_sandstone_normal", "red_sandstone_top", "red_sandstone_bottom"),
    "chiseled_red_sandstone": ("bottom_top", "red_sandstone_carved", "red_sandstone_top", "red_sandstone_top"),
    "cut_red_sandstone": ("bottom_top", "red_sandstone_smooth", "red_sandstone_top", "red_sandstone_top"),
    "terracotta": ("all", "hardened_clay"),
    # sea
    "prismarine": ("all", "prismarine_rough"), "prismarine_bricks": ("all", "prismarine_bricks"),
    "dark_prismarine": ("all", "prismarine_dark"), "sponge": ("all", "sponge"), "wet_sponge": ("all", "sponge_wet"),
    # wood
    "oak_planks": ("all", "planks_oak"), "spruce_planks": ("all", "planks_spruce"), "birch_planks": ("all", "planks_birch"),
    "jungle_planks": ("all", "planks_jungle"), "acacia_planks": ("all", "planks_acacia"),
    "dark_oak_planks": ("all", "planks_big_oak"),
    "oak_log": ("column", "log_oak", "log_oak_top"), "spruce_log": ("column", "log_spruce", "log_spruce_top"),
    "birch_log": ("column", "log_birch", "log_birch_top"), "jungle_log": ("column", "log_jungle", "log_jungle_top"),
    "acacia_log": ("column", "log_acacia", "log_acacia_top"), "dark_oak_log": ("column", "log_big_oak", "log_big_oak_top"),
    "bookshelf": ("column", "bookshelf", "planks_oak"), "hay_block": ("column", "hay_block_side", "hay_block_top"),
    "melon": ("column", "melon_side", "melon_top"),
    # nether, end
    "netherrack": ("all", "netherrack"), "soul_sand": ("all", "soul_sand"), "nether_bricks": ("all", "nether_brick"),
    "end_stone": ("all", "end_stone"),
    "quartz_block": ("bottom_top", "quartz_block_side", "quartz_block_top", "quartz_block_bottom"),
    "chiseled_quartz_block": ("column", "quartz_block_chiseled", "quartz_block_chiseled_top"),
    "quartz_pillar": ("column", "quartz_block_lines", "quartz_block_lines_top"),
    # ores and metal blocks
    "coal_ore": ("all", "coal_ore"), "iron_ore": ("all", "iron_ore"), "gold_ore": ("all", "gold_ore"),
    "diamond_ore": ("all", "diamond_ore"), "emerald_ore": ("all", "emerald_ore"), "lapis_ore": ("all", "lapis_ore"),
    "redstone_ore": ("all", "redstone_ore"), "nether_quartz_ore": ("all", "quartz_ore"),
    "coal_block": ("all", "coal_block"), "iron_block": ("all", "iron_block"), "gold_block": ("all", "gold_block"),
    "diamond_block": ("all", "diamond_block"), "emerald_block": ("all", "emerald_block"),
    "lapis_block": ("all", "lapis_block"), "redstone_block": ("all", "redstone_block"),
}
COLORS = {"white": "white", "orange": "orange", "magenta": "magenta", "light_blue": "light_blue", "yellow": "yellow",
          "lime": "lime", "pink": "pink", "gray": "gray", "light_gray": "silver", "cyan": "cyan", "purple": "purple",
          "blue": "blue", "brown": "brown", "green": "green", "red": "red", "black": "black"}
for c, old in COLORS.items():
    TABLE[f"{c}_wool"] = ("all", f"wool_colored_{old}")
    TABLE[f"{c}_terracotta"] = ("all", f"hardened_clay_stained_{old}")

NAMES = {"stone": "Pierre", "cobblestone": "Pierre taillée", "prismarine": "Prismarine", "spruce_planks": "Planches de sapin"}


def server_dir(server):
    return os.path.join(ROOT, "serveurs", SERVERS[server])


def build(pack, server):
    z = zipfile.ZipFile(pack)
    files = {n.split("textures/blocks/")[-1]: n for n in z.namelist() if "/textures/blocks/" in n}
    out = os.path.join(server_dir(server), "plugins", "ItemsAdder", "contents", NS)
    rp = os.path.join(out, "resourcepack", "assets", NS)
    for sub in ("textures/block", "models/block"):
        os.makedirs(os.path.join(rp, sub), exist_ok=True)
    os.makedirs(os.path.join(out, "configs"), exist_ok=True)
    missing, made = [], []
    for bid, (kind, *tex) in sorted(TABLE.items()):
        if any(t + ".png" not in files for t in tex):
            missing.append(bid)
            continue
        for t in set(tex):
            with open(os.path.join(rp, "textures", "block", t + ".png"), "wb") as f:
                f.write(z.read(files[t + ".png"]))
            if t + ".png.mcmeta" in files:      # animated (prismarine)
                with open(os.path.join(rp, "textures", "block", t + ".png.mcmeta"), "wb") as f:
                    f.write(z.read(files[t + ".png.mcmeta"]))
        ref = [f"{NS}:block/{t}" for t in tex]
        if kind == "all":
            model = {"parent": "minecraft:block/cube_all", "textures": {"all": ref[0]}}
        elif kind == "column":
            model = {"parent": "minecraft:block/cube_column", "textures": {"side": ref[0], "end": ref[1]}}
        else:
            model = {"parent": "minecraft:block/cube_bottom_top", "textures": {"side": ref[0], "top": ref[1], "bottom": ref[2]}}
        with open(os.path.join(rp, "models", "block", bid + ".json"), "w") as f:
            json.dump(model, f, indent=1)
        made.append(bid)
    lines = ["# Generated by plugins/fluxplugin/FluxDungeons/assets/rpg_blocs/gen_rpg_blocs.py: do not edit, rerun it.",
             "# Blocks of the RPG texture pack, placed only in dungeon maps (FluxDungeons, rpg_blocs.yml); the vanilla",
             "# block of the same name keeps its usual look everywhere else.",
             "info:", f"  namespace: {NS}", "items:"]
    for bid in made:
        name = NAMES.get(bid, bid.replace("_", " ").capitalize())
        lines += [f"  {bid}:",
                  f"    display_name: '&f{name} &7(donjon RPG)'",
                  "    permission: rpgblocs.admin",
                  "    resource:",
                  "      material: PAPER",
                  "      generate: false",
                  f"      model_path: block/{bid}",
                  "    specific_properties:",
                  "      block:",
                  "        placed_model:",
                  "          type: REAL_NOTE",
                  "          break_particles: ITEM",
                  "        cancel_drop: true",
                  "        hardness: 2"]
    with open(os.path.join(out, "configs", "blocs.yml"), "w") as f:
        f.write("\n".join(lines) + "\n")
    print(f"{len(made)} blocks written into {out}")
    if missing:
        print("not in the pack:", ", ".join(missing))


def states(server):
    """Note block state of each block, from the blockstates of the pack ItemsAdder generated."""
    base = server_dir(server)
    path = os.path.join(base, "plugins", "ItemsAdder", "output", "generated.zip")
    z = zipfile.ZipFile(path)
    variants = {}
    with open(path, "rb") as raw:
        for i in z.infolist():
            if not i.filename.endswith("blockstates/note_block.json"):
                continue
            # the pack is protected from unzip (wrong sizes and CRC): read the deflated bytes by hand
            raw.seek(i.header_offset)
            head = raw.read(30)
            n, e = struct.unpack("<HH", head[26:30])
            raw.seek(i.header_offset + 30 + n + e)
            data = raw.read(i.compress_size)
            if i.compress_type == zipfile.ZIP_DEFLATED:
                data = zlib.decompressobj(-15).decompress(data)
            try:
                variants.update(json.loads(data).get("variants", {}))
            except ValueError:
                pass
    found = {}
    for key, v in variants.items():
        v = v[0] if isinstance(v, list) else v
        mdl = v.get("model", "")
        m = re.match(rf"{NS}:block/(\w+)$", mdl)
        if m:
            props = dict(p.split("=") for p in key.split(","))
            found[m.group(1)] = "note_block[" + ",".join(f"{k}={props[k]}" for k in sorted(props)) + "]"
    if not found:
        sys.exit("no rpgblocs state in the generated pack: run `iazip` on the server first")
    path = os.path.join(base, "plugins", "FluxDungeons", "rpg_blocs.yml")
    lines = ["# Look RPG des maps de donjon : bloc vanilla -> état du bloc de note ItemsAdder (rpgblocs:<bloc>).",
             "# Écrit par plugins/fluxplugin/FluxDungeons/assets/rpg_blocs/gen_rpg_blocs.py states (après /iazip) ;",
             "# lu par les scripts de map (assets/maps/maplib.py, Map.textures = \"rpg\"). Ne pas modifier à la main.",
             "blocks:"]
    lines += [f"  {bid}: '{found[bid]}'" for bid in sorted(found)]
    with open(path, "w") as f:
        f.write("\n".join(lines) + "\n")
    print(f"{len(found)} states written to {path}")
    absent = sorted(set(TABLE) - set(found))
    if absent:
        print("without a state (not generated?):", ", ".join(absent))


def bedrock(server, rpg_pack=None):
    """Geyser custom block mapping + Bedrock resource pack, from the states of rpg_blocs.yml.

    Geyser turns a Java block state into a Bedrock block: here every rpgblocs note block state becomes one permutation
    of a custom block whose faces use a texture short name of our pack (terrain_texture.json). Those short names point
    to Bedrock's vanilla texture files (no image shipped: stone shows Bedrock's stone), or with --rpg to the RPG pack's
    images copied into our pack. Requires Geyser with `enable-custom-content: true`; the mapping goes in Geyser's
    `custom_mappings/` folder, the pack in its `packs/` folder. Untested until Geyser is installed.
    """
    import uuid
    base = server_dir(server)
    states = {}
    for line in open(os.path.join(base, "plugins", "FluxDungeons", "rpg_blocs.yml")):
        m = re.match(r"\s+(\w+): 'note_block\[(.+)\]'", line)
        if m:
            states[m.group(1)] = m.group(2)
    out = os.path.join(base, "plugins", "FluxDungeons", "bedrock")
    pack = os.path.join(out, "pack")
    os.makedirs(os.path.join(pack, "textures"), exist_ok=True)
    z = zipfile.ZipFile(rpg_pack) if rpg_pack else None
    files = {n.split("textures/blocks/")[-1]: n for n in z.namelist() if "/textures/blocks/" in n} if z else {}
    terrain, overrides = {}, {}

    def short(tex):
        key = f"{NS}_{tex}"
        if z and tex + ".png" in files:
            os.makedirs(os.path.join(pack, "textures", "blocks", NS), exist_ok=True)
            with open(os.path.join(pack, "textures", "blocks", NS, tex + ".png"), "wb") as f:
                f.write(z.read(files[tex + ".png"]))
            terrain[key] = {"textures": f"textures/blocks/{NS}/{tex}"}
        else:
            terrain[key] = {"textures": f"textures/blocks/{BEDROCK_NAMES.get(tex, tex)}"}
        return key

    for bid, state in sorted(states.items()):
        if bid not in TABLE:
            continue
        kind, *tex = TABLE[bid]
        opaque = {"render_method": "opaque"}
        if kind == "all":
            mats = {"*": {"texture": short(tex[0]), **opaque}}
        elif kind == "column":
            end = short(tex[1])
            mats = {"*": {"texture": short(tex[0]), **opaque}, "up": {"texture": end, **opaque}, "down": {"texture": end, **opaque}}
        else:
            mats = {"*": {"texture": short(tex[0]), **opaque}, "up": {"texture": short(tex[1]), **opaque},
                    "down": {"texture": short(tex[2]), **opaque}}
        overrides[state] = {"material_instances": mats}
    mapping = {"format_version": 1, "blocks": {"minecraft:note_block": {
        "name": NS, "display_name": "Bloc de donjon", "unit_cube": True, "only_override_states": True,
        "place_air": True, "included_in_creative_inventory": False,
        "material_instances": {"*": {"texture": short("noteblock"), "render_method": "opaque"}},
        "state_overrides": overrides}}}
    with open(os.path.join(out, f"geyser_{NS}.json"), "w") as f:
        json.dump(mapping, f, indent=1)
    with open(os.path.join(pack, "textures", "terrain_texture.json"), "w") as f:
        json.dump({"resource_pack_name": NS, "texture_name": "atlas.terrain", "padding": 8, "num_mip_levels": 4,
                   "texture_data": terrain}, f, indent=1)
    ns = uuid.uuid5(uuid.NAMESPACE_URL, "influencraft/rpgblocs")
    with open(os.path.join(pack, "manifest.json"), "w") as f:
        json.dump({"format_version": 2, "header": {
            "name": "Influencraft - blocs de donjon", "description": "Blocs des donjons RPG pour Bedrock (Geyser)",
            "uuid": str(uuid.uuid5(ns, "header")), "version": [1, 0, 0], "min_engine_version": [1, 20, 80]},
            "modules": [{"type": "resources", "uuid": str(uuid.uuid5(ns, "resources")), "version": [1, 0, 0]}]}, f, indent=1)
    zpath = os.path.join(out, f"{NS}.mcpack")
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as zz:
        for d, _, fs in os.walk(pack):
            for fn in fs:
                full = os.path.join(d, fn)
                zz.write(full, os.path.relpath(full, pack))
    print(f"{len(overrides)} states mapped for Bedrock ({'RPG' if z else 'vanilla'} textures): {out}")


if __name__ == "__main__":
    if len(sys.argv) >= 3 and sys.argv[1] == "build":
        build(sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else "labs")
    elif len(sys.argv) >= 2 and sys.argv[1] == "bedrock":
        a = sys.argv[2:]
        rpg = a[a.index("--rpg") + 1] if "--rpg" in a else None
        srv = next((x for x in a if x in SERVERS), "labs")
        bedrock(srv, rpg)
    elif len(sys.argv) >= 2 and sys.argv[1] == "states":
        states(sys.argv[2] if len(sys.argv) > 2 else "labs")
    else:
        sys.exit(__doc__)

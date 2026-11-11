#!/usr/bin/env python3
"""Builds src/main/resources/item_sprites.json: vanilla item id -> sprite of the atlas that draws it.

Text can show any texture of the block or item atlas inline (<sprite:block/stone>, <sprite:items:item/diamond>),
nothing in the resource pack. This maps every item of the client to the texture that best stands for it:
 - flat items: layer0 of their item model (items atlas, or blocks atlas when it points at a block texture);
 - block items: the side of their block model (side, all, front, end, top...), always in the blocks atlas.
Items drawn by a special renderer (chests, beds, heads, shields...) fall back to their particle texture.

Usage: gen_item_sprites.py <client-26.x.jar>   (e.g. serveurs/04_labs/plugins/RPEPortals/cache/client-26.2.jar)
"""
import json
import sys
import zipfile
from pathlib import Path

OUT = Path(__file__).resolve().parents[2] / "src/main/resources/item_sprites.json"
# Texture keys of a block model, the most representative first.
BLOCK_KEYS = ["side", "all", "front", "texture", "cross", "plant", "crop", "end", "top", "wool", "pattern",
              "pane", "edge", "rail", "torch", "layer0", "particle"]


def main(jar_path):
    jar = zipfile.ZipFile(jar_path)
    names = set(jar.namelist())

    def load(path):
        return json.loads(jar.read(path)) if path in names else None

    def key(ref):
        return ref.replace("minecraft:", "")

    def textures(model_ref, depth=0):
        """Textures of a model, parents merged, #references resolved."""
        model = load(f"assets/minecraft/models/{key(model_ref)}.json")
        if model is None or depth > 10:
            return {}
        merged = dict(textures(model["parent"], depth + 1)) if "parent" in model else {}
        merged.update(model.get("textures", {}))
        for _ in range(5):
            for k, v in list(merged.items()):
                if isinstance(v, str) and v.startswith("#"):
                    merged[k] = merged.get(v[1:], v)
        return merged

    def first_model(node):
        """First plain model reference of an item model definition."""
        if isinstance(node, dict):
            if node.get("type") in ("minecraft:model", "model") and "model" in node:
                return node["model"]
            for k in ("model", "fallback", "on_false", "on_true", "cases", "entries", "base"):
                found = first_model(node.get(k)) if k in node else None
                if found:
                    return found
        if isinstance(node, list):
            for n in node:
                found = first_model(n)
                if found:
                    return found
        return None

    def exists(texture):
        return f"assets/minecraft/textures/{key(texture)}.png" in names

    out = {}
    for path in sorted(names):
        if not (path.startswith("assets/minecraft/items/") and path.endswith(".json")):
            continue
        item = path.rsplit("/", 1)[1][:-5]
        model = first_model(load(path).get("model"))
        if model is None:
            continue
        tex = textures(model)
        chosen = None
        if key(model).startswith("item/") and "layer0" in tex:
            chosen = tex["layer0"]
        else:
            for k in BLOCK_KEYS:
                if isinstance(tex.get(k), str) and not tex[k].startswith("#") and exists(tex[k]):
                    chosen = tex[k]
                    break
        if chosen is None or not exists(chosen):
            continue
        sprite = key(chosen)
        out[item] = sprite if sprite.startswith("block/") else "items:" + sprite
    OUT.write_text(json.dumps(out, indent=0, sort_keys=True) + "\n", encoding="utf-8")
    print(f"{len(out)} items -> {OUT}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "/home/influencraft/serveurs/04_labs/plugins/RPEPortals/cache/client-26.2.jar")

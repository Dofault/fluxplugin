#!/usr/bin/env python3
"""Item models of the class showcase's hotbar icons (rpg.Showcase, /classe in the rpg lobby).

    python3 gen_class_icons.py <ItemsAdder/contents/fluxdungeons>

One item definition per class, resourcepack/assets/fluxdungeons/items/class_<id>.json: the class's apprentice weapon
(equipment.yml, class_<id>_weapon) as it looks everywhere, and nothing at all in a hand. The class a viewer holds in
his hotbar is then never drawn over the fight he watches. No texture nor geometry of our own: the weapon's model is
reused (a vanilla item's, or the model tree of its own item definition in the same pack).
"""
import json
import sys
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
RPG = HERE.parent.parent / "src/main/resources/rpg"
HANDS = ["firstperson_righthand", "firstperson_lefthand", "thirdperson_righthand", "thirdperson_lefthand"]


def weapon_model(look, assets):
    """The model tree of a weapon: its own item definition's when it has one, else its vanilla item's."""
    model = look.get("model")
    if model:
        namespace, name = model.split(":", 1)
        own = assets / namespace / "items" / f"{name}.json"
        if own.exists():
            return json.loads(own.read_text())["model"]
        print(f"  ! {model}: no item definition in the pack, vanilla item used")
    return {"type": "minecraft:model", "model": f"minecraft:item/{look['material'].lower()}"}


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    assets = Path(sys.argv[1]) / "resourcepack/assets"
    out = assets / "fluxdungeons/items"
    out.mkdir(parents=True, exist_ok=True)
    classes = yaml.safe_load((RPG / "classes.yml").read_text())
    items = yaml.safe_load((RPG / "equipment.yml").read_text())["items"]
    for class_id in classes:
        weapon = items.get(f"class_{class_id}_weapon")
        if not weapon:
            print(f"  ! {class_id}: no apprentice weapon")
            continue
        definition = {"model": {
            "type": "minecraft:select",
            "property": "minecraft:display_context",
            "cases": [{"when": HANDS, "model": {"type": "minecraft:empty"}}],
            "fallback": weapon_model(weapon["look"], assets),
        }}
        (out / f"class_{class_id}.json").write_text(json.dumps(definition, indent=1) + "\n")
        print(f"  class_{class_id}.json")


if __name__ == "__main__":
    main()

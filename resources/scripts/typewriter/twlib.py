"""Outils communs des pages Typewriter générées par script (gen_lobby_rpg.py, gen_accueil.py) : entrées complétées avec
les valeurs par défaut des blueprints des extensions, identifiants stables (hash du nom), écriture dans pages/ et
staging/. Après écriture : `typewriter reload` sur le serveur.

ATTENTION (2026-11-12) : Typewriter garde le staging (ce que montre le panel du site) en mémoire et le réécrit à
l'arrêt du serveur. Une page écrite serveur allumé marche tout de suite (pages/ + reload) mais disparaît du staging,
donc du panel, au redémarrage suivant. Écrire serveur arrêté, ou recopier les pages dans staging/ juste avant que
Typewriter démarre (avant la ligne « [Typewriter] Loaded ... entries » du démarrage).
"""
import copy
import hashlib
import json
import zipfile
from pathlib import Path

VERSION = "0.9.0-beta-176"


class Page:
    def __init__(self, plugins, file, chapter, world, type_="manifest"):
        self.tw = Path(plugins) / "Typewriter"
        self.file, self.chapter, self.world, self.type = file, chapter, world, type_
        self.entries = []
        self.blueprints = {}
        for jar in sorted((self.tw / "extensions").glob("*.jar")):
            with zipfile.ZipFile(jar) as z:
                if "extension.json" in z.namelist():
                    for e in json.loads(z.read("extension.json"))["entries"]:
                        self.blueprints.setdefault(e["id"], e["dataBlueprint"])

    def ident(self, key):
        alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        n = int(hashlib.sha256((f"influ-{self.file.replace('_', '-')}:" + key).encode()).hexdigest(), 16)
        out = ""
        for _ in range(15):
            n, r = divmod(n, len(alphabet))
            out += alphabet[r]
        return out

    def add(self, type_, key, **fields):
        bp = self.blueprints.get(type_)
        if bp is None:
            raise SystemExit(f"type inconnu (extension absente ?) : {type_}")
        e = default(bp)
        e["id"] = self.ident(key)
        e["name"] = key
        for f, v in fields.items():
            if f not in bp["fields"]:
                raise SystemExit(f"{type_}.{f} n'existe pas")
            e[f] = v
        e["type"] = type_
        e["_genericBlueprint"] = None
        self.entries.append(e)
        return e["id"]

    def glyph(self, ident):
        """Character of an ItemsAdder font image (`namespace:id`), read in ItemsAdder's cache: run /iazip first."""
        cache = self.tw.parent / "ItemsAdder" / "storage" / "font_images_unicode_cache.yml"
        for line in cache.read_text(encoding="utf-8").splitlines():
            key, _, value = line.partition(": ")
            if key == ident and value:
                return value
        raise SystemExit(f"{ident} absent du cache ItemsAdder : lancer /iazip d'abord")

    def pos(self, x, y, z, yaw=0.0, pitch=0.0):
        return {"world": self.world, "x": float(x), "y": float(y), "z": float(z), "yaw": float(yaw), "pitch": float(pitch)}

    def write(self):
        page = {"id": self.ident("page/" + self.file), "name": self.file, "type": self.type, "entries": self.entries,
                "chapter": self.chapter, "priority": 0, "version": VERSION}
        for folder in ("pages", "staging"):
            (self.tw / folder).mkdir(exist_ok=True)
            (self.tw / folder / f"{self.file}.json").write_text(json.dumps(page, ensure_ascii=False, indent=2) + "\n")
        print(f"{self.file}.json : {len(self.entries)} entrées")


def default(b):
    if "default" in b:
        return copy.deepcopy(b["default"])
    k = b.get("kind")
    if k == "object":
        return {n: default(v) for n, v in b["fields"].items()}
    if k == "list":
        return []
    if k == "map":
        return {}
    if k == "enum":
        return b["values"][0]
    if k == "primitive":
        return {"string": "", "integer": 0, "double": 0.0, "boolean": False}.get(b["type"], None)
    if k == "custom":
        if b.get("editor") == "ref":
            return ""
        return default(b["shape"]) if "shape" in b else None
    if k == "algebraic":
        case = next(iter(b["cases"]))
        return {"case": case, "value": default(b["cases"][case])}
    return None

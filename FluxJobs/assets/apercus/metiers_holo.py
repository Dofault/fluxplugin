"""Visual of the job menu (FluxJobs metiers.yml) in the holographic direction of the calendar, next to the current
vanilla look, with sample content (Mineur selected). Preview only."""
import os, sys, colorsys
V = "/home/influencraft/plugins/fluxplugin/Flux/assets/vanilla"
sys.path.insert(0, V); sys.path.insert(0, os.path.dirname(V))
import gen_ui, ui
from PIL import Image, ImageDraw, ImageFont

MC = sys.argv[1] + "/assets/minecraft/textures/"
OUT = sys.argv[2]
IA = "/home/influencraft/serveurs/04_labs/plugins/ItemsAdder/contents/"
rgb = ui.rgb


def tex(path):
    im = Image.open(MC + path + ".png").convert("RGBA")
    return im.crop((0, 0, 16, 16)) if im.height > 16 else im


JOBS = ["item/diamond_pickaxe", "block/cobblestone", "item/iron_shovel", "item/wheat", "block/poppy", "item/iron_axe",
        "block/furnace_front", "item/brewing_stand", "block/enchanting_table_top", "item/fishing_rod", "item/iron_sword",
        "block/bricks"]
ACTIONS = ["block/coal_ore", "block/copper_ore", "block/iron_ore", "block/gold_ore", "block/redstone_ore",
           "block/lapis_ore", "block/diamond_ore", "block/emerald_ore", "block/deepslate_coal_ore",
           "block/deepslate_iron_ore", "block/deepslate_gold_ore", "block/deepslate_diamond_ore", "block/nether_gold_ore",
           "block/nether_quartz_ore", "block/ancient_debris_side", "block/amethyst_cluster" if False else "block/raw_iron_block"]
GRID = [15, 16, 17, 24, 25, 26, 33, 34, 35, 42, 43, 44]
PETS = [9, 18, 27, 36]

# ---------------------------------------------------------------- holographic recolouring (same as the calendar)
ANCHORS = [(0, rgb("#1A1830")), (40, rgb("#24223F")), (55, rgb("#33365A")), (75, rgb("#3F4166")),
           (85, rgb("#4B4E6E")), (100, rgb("#55597E")), (139, rgb("#5B5F86")), (170, rgb("#8A93B8")),
           (230, None), (255, rgb("#E9EEFF"))]
TABS = {(254, 255, 125): "#E9EEFF", (255, 213, 66): "#7FE8FF", (233, 134, 28): "#B48CFF", (189, 89, 17): "#4B4E6E",
        (140, 51, 12): "#4B4E6E"}


def iridescent(x, y):
    r, g, b = colorsys.hsv_to_rgb(((x + y) % 48) / 48, 0.18, 0.86)
    return int(r * 255), int(g * 255), int(b * 255), 255


def holo(im):
    im = im.copy()
    px = im.load()
    for y in range(im.height):
        for x in range(im.width):
            p = px[x, y]
            if p[3] == 0:
                continue
            if p[:3] in TABS:
                px[x, y] = rgb(TABS[p[:3]]); continue
            if max(p[:3]) - min(p[:3]) > 14:
                continue                                  # coloured parts (buttons...) stay
            l = sum(p[:3]) // 3
            if 185 <= l <= 210:                           # the window's light grey: iridescent sheen
                px[x, y] = iridescent(x, y); continue
            best = min((a for a in ANCHORS if a[1] is not None), key=lambda a: abs(a[0] - l))
            px[x, y] = best[1][:3] + (p[3],)
    return im


def band(x, x0, x1):
    r, g, b = colorsys.hsv_to_rgb((0.48 + 0.35 * (x - x0) / max(1, x1 - x0)) % 1, 0.55, 0.92)
    return int(r * 255), int(g * 255), int(b * 255), 255


def compose(bg, holo_look):
    k = 3
    view = bg.copy()
    d = ImageDraw.Draw(view)
    # level bar fill (Flux widget): 62 % of the bar, then the level written over it
    x0, y0, x1 = 6, 109, 169
    fill_to = x0 + 1 + int((x1 - x0 - 2) * 0.62)
    for x in range(x0 + 1, fill_to):
        col = band(x, x0, x1) if holo_look else rgb("#3E8E2E")
        d.line([(x, y0 + 1), (x, y0 + gen_ui.BAR_H)], fill=col)
    if holo_look:
        d.line([(x0 + 1, y0 + 1), (fill_to - 1, y0 + 1)], fill=(255, 255, 255, 120))
    ui.write(view, "NIVEAU 42 · 62 %", 88, y0 + 3, rgb("#FFFFFF"), shadow=rgb("#3A2A7A") if holo_look else rgb("#282828"))
    # job name in the field, title on the plate
    ui.write(view, "MINEUR", 77, 23, rgb("#FFFFFF"), shadow=rgb("#3A2A7A") if holo_look else rgb("#282828"))
    # portrait of the job (influmetier)
    portrait = Image.open(IA + "influmetier/textures/metier/mineur.png").convert("RGBA").resize((64, 64), Image.NEAREST)
    view.alpha_composite(portrait, (34, 39))
    if holo_look:                                         # selected job: lit frame around its slot
        x, y = ui.objet(15)
        d.rectangle((x - 2, y - 2, x + 17, y + 17), outline=rgb("#7FE8FF"))
    view = view.resize((view.width * k, view.height * k), Image.NEAREST)
    def put(img, case):
        x, y = ui.objet(case)
        y = y - 1 if case >= 54 else y                    # inventory rows sit 1 px higher in game
        view.alpha_composite(img.resize((16 * k, 16 * k), Image.NEAREST), (x * k, y * k))
    for case, t in zip(GRID, JOBS):
        put(tex(t), case)
    for case, t in zip(range(54, 54 + len(ACTIONS)), ACTIONS):
        put(tex(t), case)
    put(Image.open(IA + "fluxui/textures/btn/btn_back_orange.png").convert("RGBA"), 0)
    put(Image.open(IA + "fluxui/textures/btn/btn_info_" + ("purple" if holo_look else "blue") + ".png").convert("RGBA"), 8)
    put(Image.open(IA + "fluxui/textures/btn/btn_check_green.png").convert("RGBA"), 7)
    return view


generic = os.path.join(V, "menu_vanilla.png")
bg = gen_ui.bg_metiers(generic)
now = compose(bg, False)
new = compose(holo(bg), True)
gap, lab = 30, 30
board = Image.new("RGBA", (now.width * 2 + gap * 3, now.height + lab + gap * 2), (44, 44, 48, 255))
dr = ImageDraw.Draw(board)
font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 18)
for i, (name, im) in enumerate((("Actuel (vanilla)", now), ("Holographique", new))):
    x = gap + i * (now.width + gap)
    dr.text((x, gap - 4), name, fill=(235, 235, 235, 255), font=font)
    board.alpha_composite(im, (x, gap + lab))
os.makedirs(OUT, exist_ok=True)
board.save(os.path.join(OUT, "metiers_holo_comparaison.png"))
new.save(os.path.join(OUT, "metiers_holo.png"))
print("ok")

package fr.influencraft.flux.menu;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemFlag;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/** Lecture d'un fichier menus/<id>.yml. Les erreurs non bloquantes passent par {@code warn}. */
public final class MenuLoader {

    private MenuLoader() {
    }

    // ---------------------------------------------------------------- vanilla theme (section theme:)

    /** Buttons of the former theme and their vanilla-theme replacement ({@code theme.buttons} of Flux's config). */
    private static volatile java.util.Map<String, String> themeButtons = java.util.Map.of();
    /** ItemsAdder namespaces whose pictures are content, framed like vanilla items (pet portraits, cards...). */
    private static volatile java.util.Set<String> contentNamespaces = java.util.Set.of();
    /** Rows added to every chest slot of the menu being loaded ({@code theme.shift}). */
    private static final ThreadLocal<int[]> SHIFT = new ThreadLocal<>();

    /** Menus converted from Flux's config ({@code theme.menus.<id>}), same keys as a {@code theme:} section. */
    private static volatile java.util.Map<String, ConfigurationSection> themeMenus = java.util.Map.of();

    public static void configureTheme(java.util.Map<String, String> buttons, ConfigurationSection menus,
                                      List<String> content) {
        themeButtons = java.util.Map.copyOf(buttons);
        contentNamespaces = content.stream().map(n -> n.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toUnmodifiableSet());
        java.util.Map<String, ConfigurationSection> m = new java.util.HashMap<>();
        if (menus != null) for (String k : menus.getKeys(false)) {
            ConfigurationSection c = menus.getConfigurationSection(k);
            if (c != null) m.put(k.toLowerCase(Locale.ROOT), c);
        }
        themeMenus = java.util.Map.copyOf(m);
    }

    public static Menu load(String id, File file, Consumer<String> warn) {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection theme = yml.getConfigurationSection("theme");
        if (theme == null) theme = themeMenus.get(id.toLowerCase(Locale.ROOT));
        if (theme == null) return loadNow(id, file, yml, null, warn);
        // theme.shift: the whole content moves down, slots of the file stay as written. "auto" (default): a menu
        // that uses only the chest and stops before its last row moves down to end on it (centred on the screen)
        int rows = yml.getInt("rows", 6);
        int shift;
        if (theme.isInt("shift")) {
            shift = theme.getInt("shift");
        } else {
            Menu plain = loadNow(id, file, yml, null, msg -> { });
            int[] span = rowSpan(plain);
            shift = span[1] < rows - 1 ? rows - 1 - span[1] : 0;
        }
        SHIFT.set(new int[]{shift * 9, rows * 9});
        try {
            return loadNow(id, file, yml, theme, warn);
        } finally {
            SHIFT.remove();
        }
    }

    /**
     * Menu with the vanilla theme: bare window as default title, the menu name on the plate (engraved text), a slot
     * frame under every slot it uses (buttons of the theme and invisible items excepted), former buttons replaced.
     */
    private static Menu themed(Menu m, ConfigurationSection theme) {
        List<MenuButton> buttons = m.buttons().stream().map(MenuLoader::rebutton).toList();
        List<Widget> widgets = new ArrayList<>();
        String name = theme.getString("title", "");
        // the window fits the rows used: the menu's own content, and each page (drawer) its content added, so the
        // window grows when a drawer opens
        int[] span = rowSpan(m.buttons(), m.lists(), List.of(), List.of(), m);
        String background = theme.getString("background", "bg_window_*");
        String title = window(background, span);
        int plateY = 5 + 18 * span[0];
        // picture made for this menu by gen_ui.py (auto_bg.py): window, slot frames and name already in it
        String auto = "bg_auto_" + m.id().toLowerCase(Locale.ROOT);
        boolean pictured = !theme.isSet("background") && glyph(auto);
        if (pictured) title = ":offset_-8::" + auto + ":";
        if (!name.isBlank() && !pictured) widgets.add(new Widget.Text("__title", 88, plateY, Widget.Align.CENTER, name, null, true, List.of()));
        // slot frames are never drawn in the title any more (2026-10-30): they are in the menu's picture (auto_bg.py)
        boolean frames = false;
        boolean dark = theme.getString("frames", "vanilla").equalsIgnoreCase("dark");
        Set<Integer> skip = new java.util.HashSet<>();
        for (String raw : strings(theme, "no-frame")) addSlots(raw, skip);
        // frame: slots framed whatever stands on them (a grid mixing pictures and vanilla items)
        Set<Integer> force = new java.util.TreeSet<>();
        for (String raw : strings(theme, "frame")) addSlots(raw, force);
        if (frames && m.inventoryMode() != InventoryMode.HIDDEN) {
            // the player's own inventory shows under the menu: its 36 slots need their frames too
            int[] inv = new int[36];
            for (int i = 0; i < 36; i++) inv[i] = m.topSize() + i;
            widgets.add(new Widget.Slots("__inventory", inv, false, List.of()));
        }
        if (frames) {
            java.util.TreeSet<Integer> used = new java.util.TreeSet<>();
            for (int s : framed(buttons, m.lists(), skip)) used.add(s);
            used.addAll(force);
            if (!used.isEmpty()) widgets.add(new Widget.Slots("__frames", used.stream().mapToInt(Integer::intValue).toArray(),
                    dark, List.of()));
        }
        widgets.addAll(m.widgets());
        java.util.Map<String, Page> pages = new java.util.LinkedHashMap<>();
        for (var e : m.pages().entrySet()) {
            Page p = e.getValue();
            List<MenuButton> pb = p.buttons().stream().map(MenuLoader::rebutton).toList();
            List<Widget> pw = new ArrayList<>();
            if (frames) {
                int[] used = framed(pb, p.lists(), skip);
                if (used.length > 0) pw.add(new Widget.Slots("__frames", used, dark, List.of()));
            }
            pw.addAll(p.widgets());
            // pages drew their own former backgrounds and animations: replaced by the menu's, unless kept
            boolean keep = theme.getBoolean("keep-page-titles", false);
            String pageAuto = auto + "__" + p.id().toLowerCase(Locale.ROOT);
            String pageTitle = keep && p.title() != null && !p.title().isBlank() ? p.title()
                    : pictured ? ":offset_-8::" + (glyph(pageAuto) ? pageAuto : auto) + ":"
                    : window(background, rowSpan(m.buttons(), m.lists(), p.buttons(), p.lists(), m));
            pages.put(e.getKey(), new Page(p.id(), pageTitle, keep ? p.frames() : List.of(), p.frameInterval(), pb,
                    theme.getBoolean("keep-paths", true) ? p.paths() : List.of(),
                    p.lists(), List.copyOf(pw)));
        }
        boolean layers = theme.getBoolean("keep-title-layers", false);
        return new Menu(m.id(), title, m.rows(), m.commands(), m.overrideCommands(), m.permission(), m.inventoryMode(),
                m.vanillaBackground(), m.updateInterval(), m.openActions(), m.closeActions(), buttons,
                java.util.Map.copyOf(pages), theme.getBoolean("keep-paths", true) ? m.paths() : List.of(),
                theme.getBoolean("keep-title-loop", false) ? m.loopTitles() : List.of(),
                m.loopInterval(), layers ? m.titleLayers() : List.of(), m.lists(),
                m.inputSlots(), m.inputChangeActions(), m.inventoryClickActions(), List.copyOf(widgets));
    }

    /**
     * Title of a fitted window: {@code background} with its {@code *} replaced by "first_last" (the bare window of a
     * full menu is bg_window).
     */
    private static String window(String background, int[] span) {
        String id = !background.contains("*") ? background
                : background.equals("bg_window_*") && span[0] == 0 && span[1] == 9 ? "bg_window"
                : background.replace("*", span[0] + "_" + span[1]);
        return ":offset_-8::" + id + ":";
    }

    /**
     * First chest row and last row used by these buttons and lists: 0-5 chest, 6-8 inventory, 9 hotbar. A menu showing
     * the player's inventory always goes down to the hotbar.
     */
    private static int[] rowSpan(List<MenuButton> b1, List<ListSpec> l1, List<MenuButton> b2, List<ListSpec> l2, Menu m) {
        int top = m.topSize();
        int first = 5, last = 0;
        java.util.List<int[]> all = new ArrayList<>();
        for (MenuButton b : b1) all.add(b.slots());
        for (MenuButton b : b2) all.add(b.slots());
        for (ListSpec l : l1) all.add(l.slots());
        for (ListSpec l : l2) all.add(l.slots());
        for (int[] slots : all) for (int s : slots) {
            int row = s < top ? s / 9 : 6 + Math.min(3, (s - top) / 9);
            if (s < top) first = Math.min(first, row);
            last = Math.max(last, row);
        }
        if (m.inventoryMode() != InventoryMode.HIDDEN) last = 9;
        if (last < first) last = first;
        return new int[]{first, last};
    }

    /** Rows used by a menu and all its pages (auto shift). */
    private static int[] rowSpan(Menu m) {
        List<MenuButton> pb = new ArrayList<>();
        List<ListSpec> pl = new ArrayList<>();
        for (Page p : m.pages().values()) {
            pb.addAll(p.buttons());
            pl.addAll(p.lists());
        }
        return rowSpan(m.buttons(), m.lists(), pb, pl, m);
    }

    private static boolean glyph(String id) {
        try {
            return fr.influencraft.flux.api.items.CustomItems.available() && fr.influencraft.flux.api.items.CustomItems.glyphExists(id);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static MenuButton rebutton(MenuButton b) {
        String to = b.material() == null ? null : themeButtons.get(b.material().toLowerCase(Locale.ROOT));
        if (to == null) return b;
        return new MenuButton(b.id(), to, b.name(), b.lore(), b.amount(), b.slots(), b.priority(), b.glow(),
                b.customModelData(), b.itemModel(), b.flags(), b.hideTooltip(), b.confirm(), b.viewRequirements(),
                b.clickRequirements(), b.denyActions(), b.actions());
    }

    /**
     * Slots to frame: the content, never the buttons. Every list slot (elements, and the "?" of an empty list), every
     * item that is not a picture: vanilla items, heads. Pictures of ItemsAdder (back, arrows, icons) are buttons and
     * stand on the window without a frame, as invisible items.
     */
    private static int[] framed(List<MenuButton> buttons, List<ListSpec> lists, Set<Integer> skip) {
        java.util.TreeSet<Integer> out = new java.util.TreeSet<>();
        for (ListSpec l : lists) for (int s : l.slots()) out.add(s);
        for (MenuButton b : buttons) {
            String mat = b.material() == null ? "" : b.material().toLowerCase(Locale.ROOT);
            boolean picture = mat.startsWith("itemsadder:")
                    && !contentNamespaces.contains(mat.substring("itemsadder:".length()).split(":", 2)[0]);
            if (picture || mat.contains("invisible") || b.id().startsWith("__")) continue;
            for (int s : b.slots()) out.add(s);
        }
        out.removeAll(skip);
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    private static Menu loadNow(String id, File file, YamlConfiguration yml, ConfigurationSection theme,
                                Consumer<String> warn) {
        String where = "menus/" + id + ".yml";

        int rows = yml.getInt("rows", 6);
        if (rows < 1 || rows > 6) {
            warn.accept(where + " : rows doit valoir 1 à 6 (lu " + rows + "), 6 retenu.");
            rows = 6;
        }
        InventoryMode mode = InventoryMode.parse(yml.getString("player-inventory", "hidden"));
        int topSize = rows * 9;
        // deposit zone: slots of the chest where the player puts real items (needs his inventory)
        List<Integer> inputs = new ArrayList<>();
        try {
            for (String raw : strings(yml, "input-slots")) addSlots(raw, inputs);
        } catch (IllegalArgumentException e) {
            warn.accept(where + " : input-slots : " + e.getMessage());
        }
        inputs.removeIf(s -> s < 0 || s >= topSize);
        if (!inputs.isEmpty() && mode != InventoryMode.INTERACTIVE) {
            if (mode == InventoryMode.HIDDEN) warn.accept(where + " : input-slots needs the player's inventory, player-inventory: interactive used");
            mode = InventoryMode.INTERACTIVE;
        }
        int maxSlot = mode == InventoryMode.HIDDEN ? topSize + 36 : topSize;

        List<MenuButton> buttons = new ArrayList<>(buttons(yml.getConfigurationSection("items"), where + " > items",
                maxSlot, topSize, mode, warn));
        List<Widget> widgets = new ArrayList<>(widgets(yml.getConfigurationSection("widgets"), where + " > widgets", warn));
        // widgets declared with the theme (theme.menus.<id>.widgets of Flux's config): generated menus stay untouched
        if (theme != null) widgets.addAll(widgets(theme.getConfigurationSection("widgets"), where + " > theme.widgets", warn));
        buttons.addAll(widgetButtons(widgets, maxSlot, topSize, mode, yml.getConfigurationSection("widgets"), where, warn));
        if (theme != null) buttons.addAll(widgetButtons(widgets, maxSlot, topSize, mode,
                theme.getConfigurationSection("widgets"), where, warn).stream().filter(b -> b.id().contains("_tooltip")).toList());

        java.util.Map<String, Page> pages = new java.util.LinkedHashMap<>();
        // themed menu: its background replaces the former title (theme.background, the bare window by default)
        String defaultTitle = theme != null ? ":offset_-8::" + theme.getString("background", "bg_window") + ":"
                : yml.getString("title", "");
        pages(yml.getConfigurationSection("pages"), where, defaultTitle, maxSlot, topSize, mode, pages, warn);
        // Pages rangées dans d'autres fichiers (chemin relatif au dossier du menu, préfixe _ pour
        // que Flux ne les charge pas comme des menus) : même format, section pages: à la racine.
        for (String rel : strings(yml, "page-files")) {
            File extra = new File(file.getParentFile(), rel);
            if (!extra.isFile()) {
                warn.accept(where + " : page-files « " + rel + " » introuvable");
                continue;
            }
            YamlConfiguration other = YamlConfiguration.loadConfiguration(extra);
            pages(other.getConfigurationSection("pages"), "menus/" + rel, defaultTitle, maxSlot, topSize, mode, pages, warn);
        }

        Menu built = new Menu(id,
                defaultTitle,
                rows,
                strings(yml, "commands"),
                yml.getBoolean("override-commands", false),
                yml.getString("permission", ""),
                mode,
                yml.getBoolean("vanilla-background", false),
                Math.max(0, yml.getInt("update-interval", 0)),
                strings(yml, "open-actions"),
                strings(yml, "close-actions"),
                List.copyOf(buttons),
                java.util.Map.copyOf(pages),
                paths(yml.getConfigurationSection("paths"), where + " > paths", maxSlot, warn),
                strings(yml, "title-loop.frames"),
                Math.max(1, yml.getInt("title-loop.interval", 1)),
                titleLayers(yml.getConfigurationSection("title-layers"), where + " > title-layers", warn),
                lists(yml.getConfigurationSection("lists"), where + " > lists", maxSlot, warn),
                inputs.stream().mapToInt(Integer::intValue).toArray(),
                strings(yml, "input-change-actions"),
                strings(yml, "inventory-click-actions"),
                widgets);
        return theme == null ? built : themed(built, theme);
    }

    private static List<ListSpec> lists(ConfigurationSection section, String where, int maxSlot, Consumer<String> warn) {
        if (section == null) return List.of();
        List<ListSpec> out = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            ConfigurationSection l = section.getConfigurationSection(key);
            if (l == null) continue;
            String at = where + "." + key;
            List<Integer> slots = new ArrayList<>();
            try {
                for (String raw : strings(l, "slots")) addSlots(raw, slots);
            } catch (IllegalArgumentException e) {
                warn.accept(at + " : " + e.getMessage() + " (liste ignorée)");
                continue;
            }
            slots.removeIf(s -> {
                boolean bad = s < 0 || s >= maxSlot;
                if (bad) warn.accept(at + " : case " + s + " hors du menu (ignorée)");
                return bad;
            });
            ConfigurationSection item = l.getConfigurationSection("item");
            if (slots.isEmpty() || item == null) {
                warn.accept(at + " : slots et item obligatoires (liste ignorée)");
                continue;
            }
            out.add(new ListSpec(l.getString("source", key).toLowerCase(Locale.ROOT), slots.stream().mapToInt(Integer::intValue).toArray(),
                    item, l.getConfigurationSection("empty"), at));
        }
        return List.copyOf(out);
    }

    /** A button built from a template section (lists): same rules as a menu button, warnings kept quiet. */
    static MenuButton templateButton(String key, ConfigurationSection s, int maxSlot, int topSize, InventoryMode mode,
                                     String at, Consumer<String> warn) {
        try {
            return button(key, s, maxSlot, topSize, mode, at, warn);
        } catch (IllegalArgumentException e) {
            warn.accept(at + " : " + e.getMessage());
            return null;
        }
    }

    // ---------------------------------------------------------------- widgets (vanilla theme, Widget / UiTitle)

    private static List<Widget> widgets(ConfigurationSection section, String where, Consumer<String> warn) {
        if (section == null) return List.of();
        List<Widget> out = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            ConfigurationSection w = section.getConfigurationSection(key);
            if (w == null) continue;
            String at = where + "." + key;
            List<Requirement> req = requirements(w, "view-requirements", at, warn);
            String type = w.getString("type", "").toLowerCase(Locale.ROOT);
            try {
                out.add(switch (type) {
                    case "text" -> new Widget.Text(key, w.getInt("x"), w.getInt("y"), align(w.getString("align", "left")),
                            w.getString("text", ""), w.getString("colour", w.getString("color", null)),
                            w.getBoolean("ink", false), req);
                    case "slots" -> {
                        List<Integer> sl = new ArrayList<>();
                        for (String raw : strings(w, "slots")) addSlots(raw, sl);
                        yield new Widget.Slots(key, sl.stream().mapToInt(Integer::intValue).toArray(),
                                w.getBoolean("dark", false), req);
                    }
                    case "image" -> new Widget.Image(key, w.getString("glyph", ""), w.getInt("x"), w.getInt("width"), req);
                    case "bar" -> new Widget.Bar(key, w.getInt("x"), w.getInt("y"), w.getInt("width"),
                            w.getString("progress", "0"), w.getString("max", "1"), w.getString("colour", "green"),
                            w.getString("text", null), w.getBoolean("frame", false), req);
                    case "pager" -> new Widget.Pager(key, w.getString("list", ""), shifted(w.getInt("prev")),
                            shifted(w.getInt("next")), req);
                    case "lit" -> new Widget.Lit(key, shifted(w.getInt("slot")), req);
                    case "raw" -> new Widget.Raw(key, w.getInt("x", 0), w.getString("text", ""), req);
                    default -> throw new IllegalArgumentException("type « " + type + " » inconnu (text, image, bar, pager, lit, slots)");
                });
            } catch (IllegalArgumentException e) {
                warn.accept(at + " : " + e.getMessage() + " (widget ignoré)");
            }
        }
        return List.copyOf(out);
    }

    private static Widget.Align align(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "center", "centre" -> Widget.Align.CENTER;
            case "right" -> Widget.Align.RIGHT;
            default -> Widget.Align.LEFT;
        };
    }

    /**
     * Buttons a widget brings with it, built as if written in the file: the click areas of the pager (invisible,
     * 2 slots each, only when there is a page that way; the buttons are drawn by UiTitle) and the tooltip of a bar
     * (invisible items over {@code tooltip.slots}).
     */
    private static List<MenuButton> widgetButtons(List<Widget> widgets, int maxSlot, int topSize, InventoryMode mode,
                                                  ConfigurationSection section, String where, Consumer<String> warn) {
        if (widgets.isEmpty()) return List.of();
        YamlConfiguration gen = new YamlConfiguration();
        for (Widget w : widgets) {
            if (w instanceof Widget.Pager p) {
                String pre = "%flux_list_" + p.list() + "_";
                pagerButton(gen, p.id() + "_prev", List.of(String.valueOf(p.prev()), String.valueOf(p.prev() + 1)),
                        pre + "has_prev%", "[list] " + p.list() + " prev");
                pagerButton(gen, p.id() + "_next", List.of(String.valueOf(p.next() - 1), String.valueOf(p.next())),
                        pre + "has_next%", "[list] " + p.list() + " next");
            } else if (w instanceof Widget.Bar b && section != null) {
                ConfigurationSection tip = section.getConfigurationSection(b.id() + ".tooltip");
                if (tip == null) continue;
                String k = "__" + b.id() + "_tooltip.";
                gen.set(k + "material", "itemsadder:influitems:invisible");
                gen.set(k + "slots", tip.get("slots"));
                gen.set(k + "name", tip.getString("name", " "));
                gen.set(k + "lore", tip.getStringList("lore"));
                gen.set(k + "priority", 10);
            }
        }
        return buttons(gen, where + " > widgets", maxSlot, topSize, mode, warn);
    }

    /** Invisible click area of a pager button (the button itself is drawn in the title): 2 slots. */
    private static void pagerButton(YamlConfiguration gen, String id, List<String> slots, String can, String action) {
        String k = "__" + id + ".";
        gen.set(k + "material", "itemsadder:influitems:invisible");
        gen.set(k + "slots", slots);
        gen.set(k + "hide-tooltip", true);
        gen.set(k + "view-requirements", List.of("placeholder: " + can + " == true"));
        gen.set(k + "actions.left", List.of(action));
    }

    private static List<TitleLayer> titleLayers(ConfigurationSection section, String where, Consumer<String> warn) {
        if (section == null) return List.of();
        List<TitleLayer> out = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            ConfigurationSection l = section.getConfigurationSection(key);
            if (l == null) continue;
            String at = where + "." + key;
            String text = l.getString("text", "");
            if (text.isEmpty()) {
                warn.accept(at + " : text vide, calque ignoré");
                continue;
            }
            out.add(new TitleLayer(key, text, requirements(l, "view-requirements", at, warn)));
        }
        return List.copyOf(out);
    }

    private static void pages(ConfigurationSection pageSection, String where, String defaultTitle, int maxSlot,
                              int topSize, InventoryMode mode, java.util.Map<String, Page> pages, Consumer<String> warn) {
        if (pageSection == null) return;
        for (String key : pageSection.getKeys(false)) {
            ConfigurationSection p = pageSection.getConfigurationSection(key);
            if (p == null) continue;
            String at = where + " > pages." + key;
            String id = key.toLowerCase(Locale.ROOT);
            if (pages.containsKey(id)) warn.accept(at + " : page déjà définie, remplacée");
            List<Widget> widgets = widgets(p.getConfigurationSection("widgets"), at + " > widgets", warn);
            List<MenuButton> pageButtons = new ArrayList<>(
                    buttons(p.getConfigurationSection("items"), at + " > items", maxSlot, topSize, mode, warn));
            pageButtons.addAll(widgetButtons(widgets, maxSlot, topSize, mode, p.getConfigurationSection("widgets"), at, warn));
            pages.put(id, new Page(id,
                    p.getString("title", defaultTitle),
                    strings(p, "animation.frames"),
                    Math.max(1, p.getInt("animation.interval", 1)),
                    List.copyOf(pageButtons),
                    paths(p.getConfigurationSection("paths"), at + " > paths", maxSlot, warn),
                    lists(p.getConfigurationSection("lists"), at + " > lists", maxSlot, warn),
                    widgets));
        }
    }

    private static final java.util.Set<String> COLORS = java.util.Set.of("vert", "orange", "rouge", "or", "bleu", "rose");

    private static List<PathSpec> paths(ConfigurationSection section, String where, int maxSlot, Consumer<String> warn) {
        if (section == null) return List.of();
        List<PathSpec> out = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            ConfigurationSection p = section.getConfigurationSection(key);
            if (p == null) continue;
            String at = where + "." + key;
            List<Integer> slots = new ArrayList<>();
            java.util.Set<Integer> waypoints = new java.util.HashSet<>();
            List<Integer> nodesTmp = new ArrayList<>();
            try {
            for (String raw : strings(p, "nodes")) addSlots(raw, nodesTmp);
            for (String raw : strings(p, "slots")) {
                // « ~11 » : case de passage (virage), pas une étape.
                for (String part : raw.replace("[", "").replace("]", "").split(",")) {
                    String t = part.trim();
                    if (t.startsWith("~")) {
                        List<Integer> one = new ArrayList<>();
                        addSlots(t.substring(1), one);
                        waypoints.addAll(one);
                        slots.addAll(one);
                    } else if (!t.isEmpty()) {
                        addSlots(t, slots);
                    }
                }
            }
            } catch (IllegalArgumentException e) {
                // Une case illisible n'invalide que ce chemin, jamais tout le chargement.
                warn.accept(at + " : " + e.getMessage() + " (chemin ignoré)");
                continue;
            }
            slots.removeIf(s -> {
                boolean bad = s < 0 || s >= maxSlot;
                if (bad) warn.accept(at + " : case " + s + " hors du menu (ignorée)");
                return bad;
            });
            if (slots.isEmpty()) {
                warn.accept(at + " : aucune case (slots), chemin ignoré");
                continue;
            }
            List<int[]> branches = new ArrayList<>();
            for (String raw : strings(p, "branches")) {
                String[] parts = raw.replace("[", "").replace("]", "").split("[,>\\s-]+");
                try {
                    branches.add(new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[parts.length - 1].trim())});
                } catch (RuntimeException e) {
                    warn.accept(at + " : branche illisible « " + raw + " » (écrire « 11 > 20 »)");
                }
            }
            List<Integer> nodes = new ArrayList<>();
            for (String raw : strings(p, "nodes")) addSlots(raw, nodes);
            String color = p.getString("color", "vert").toLowerCase(Locale.ROOT);
            if (!COLORS.contains(color)) {
                warn.accept(at + " : couleur « " + color + " » inconnue (vert, orange, rouge, or, bleu, rose), vert retenu");
                color = "vert";
            }
            out.add(new PathSpec(key, List.copyOf(slots), List.copyOf(branches), p.getString("progress", "0"),
                    Math.max(0, p.getInt("cycle", 0)), color, p.getBoolean("start", true), p.getBoolean("end", false),
                    List.copyOf(nodes), Math.max(0, p.getInt("offset", 0)), java.util.Set.copyOf(waypoints),
                    p.getString("states", "")));
        }
        return List.copyOf(out);
    }

    private static List<MenuButton> buttons(ConfigurationSection items, String where, int maxSlot, int topSize,
                                            InventoryMode mode, Consumer<String> warn) {
        List<MenuButton> buttons = new ArrayList<>();
        if (items == null) return List.of();
        for (String key : items.getKeys(false)) {
            ConfigurationSection s = items.getConfigurationSection(key);
            if (s == null) continue;
            String at = where + "." + key;
            try {
                MenuButton b = button(key, s, maxSlot, topSize, mode, at, warn);
                if (b != null) buttons.add(b);
            } catch (IllegalArgumentException e) {
                warn.accept(at + " : " + e.getMessage() + " (bouton ignoré)");
            }
        }
        return List.copyOf(buttons);
    }

    private static MenuButton button(String key, ConfigurationSection s, int maxSlot, int topSize,
                                     InventoryMode mode, String at, Consumer<String> warn) {
        Set<Integer> slots = new LinkedHashSet<>();
        for (String raw : strings(s, "slots")) addSlots(raw, slots);
        if (s.isSet("slot")) addSlots(s.getString("slot"), slots);
        if (slots.isEmpty()) throw new IllegalArgumentException("aucune case (slots)");

        List<Integer> kept = new ArrayList<>();
        for (int slot : slots) {
            if (slot < 0 || slot >= maxSlot) {
                if (slot >= topSize && slot < topSize + 36 && mode != InventoryMode.HIDDEN) {
                    warn.accept(at + " : case " + slot + " dans l'inventaire du joueur, possible seulement en player-inventory: hidden (case ignorée)");
                } else {
                    warn.accept(at + " : case " + slot + " hors du menu (0-" + (maxSlot - 1) + ", case ignorée)");
                }
                continue;
            }
            kept.add(slot);
        }
        if (kept.isEmpty()) return null;

        EnumMap<ClickKind, List<String>> actions = new EnumMap<>(ClickKind.class);
        ConfigurationSection a = s.getConfigurationSection("actions");
        if (a != null) {
            for (String clickKey : a.getKeys(false)) {
                ClickKind kind = null;
                for (ClickKind k : ClickKind.values()) {
                    if (k.key.equalsIgnoreCase(clickKey)) kind = k;
                }
                if (kind == null) {
                    warn.accept(at + " : clic inconnu actions." + clickKey + " (left, right, shift-left, shift-right, middle, drop, any)");
                    continue;
                }
                actions.put(kind, strings(a, clickKey));
            }
        }

        Set<ItemFlag> flags = EnumSet.noneOf(ItemFlag.class);
        for (String f : strings(s, "flags")) {
            try {
                flags.add(ItemFlag.valueOf(f.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                warn.accept(at + " : flag inconnu " + f);
            }
        }

        return new MenuButton(key,
                s.getString("material", "STONE"),
                s.getString("name", null),
                strings(s, "lore"),
                s.getString("amount", null),
                kept.stream().mapToInt(Integer::intValue).toArray(),
                s.getInt("priority", 0),
                s.getBoolean("glow", false),
                s.isInt("custom-model-data") ? s.getInt("custom-model-data") : null,
                s.getString("item-model", null),
                flags,
                s.getBoolean("hide-tooltip", false),
                // blank: no confirmation (a list template can turn it off for some elements)
                s.getString("confirm", "").isBlank() ? null : s.getString("confirm"),
                requirements(s, "view-requirements", at, warn),
                requirements(s, "click-requirements", at, warn),
                strings(s, "deny-actions"),
                actions);
    }

    private static List<Requirement> requirements(ConfigurationSection s, String path, String at, Consumer<String> warn) {
        List<Requirement> list = new ArrayList<>();
        for (String line : strings(s, path)) {
            try {
                list.add(Requirement.parse(line));
            } catch (IllegalArgumentException e) {
                warn.accept(at + " : " + e.getMessage());
            }
        }
        return List.copyOf(list);
    }

    /** A chest slot moved down by {@code theme.shift} rows (slots of the player's inventory never move). */
    private static int shifted(int slot) {
        int[] shift = SHIFT.get();
        if (shift == null || slot >= shift[1]) return slot;
        return Math.min(shift[1] - 1, slot + shift[0]);
    }

    /** "12", "0-8" ou une liste mêlant les deux. */
    private static void addSlots(String raw, java.util.Collection<Integer> out) {
        if (raw == null) return;
        for (String part : raw.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            int dash = p.indexOf('-', 1);
            try {
                if (dash > 0) {
                    int from = Integer.parseInt(p.substring(0, dash).trim());
                    int to = Integer.parseInt(p.substring(dash + 1).trim());
                    for (int i = Math.min(from, to); i <= Math.max(from, to); i++) out.add(shifted(i));
                } else {
                    out.add(shifted(Integer.parseInt(p)));
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("case illisible « " + p + " »");
            }
        }
    }

    /** Liste de chaînes, qu'elle soit écrite en liste YAML ou en simple valeur. */
    static List<String> strings(ConfigurationSection s, String path) {
        if (s.isList(path)) {
            List<String> out = new ArrayList<>();
            for (Object o : s.getList(path, List.of())) out.add(String.valueOf(o));
            return List.copyOf(out);
        }
        String single = s.getString(path);
        return single == null ? List.of() : List.of(single);
    }
}

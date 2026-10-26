package fr.influencraft.fluxmenu.menu;

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

    public static Menu load(String id, File file, Consumer<String> warn) {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        String where = "menus/" + id + ".yml";

        int rows = yml.getInt("rows", 6);
        if (rows < 1 || rows > 6) {
            warn.accept(where + " : rows doit valoir 1 à 6 (lu " + rows + "), 6 retenu.");
            rows = 6;
        }
        InventoryMode mode = InventoryMode.parse(yml.getString("player-inventory", "hidden"));
        int topSize = rows * 9;
        int maxSlot = mode == InventoryMode.HIDDEN ? topSize + 36 : topSize;

        List<MenuButton> buttons = buttons(yml.getConfigurationSection("items"), where + " > items",
                maxSlot, topSize, mode, warn);

        java.util.Map<String, Page> pages = new java.util.LinkedHashMap<>();
        String defaultTitle = yml.getString("title", "");
        pages(yml.getConfigurationSection("pages"), where, defaultTitle, maxSlot, topSize, mode, pages, warn);
        // Pages rangées dans d'autres fichiers (chemin relatif au dossier du menu, préfixe _ pour
        // que FluxMenu ne les charge pas comme des menus) : même format, section pages: à la racine.
        for (String rel : strings(yml, "page-files")) {
            File extra = new File(file.getParentFile(), rel);
            if (!extra.isFile()) {
                warn.accept(where + " : page-files « " + rel + " » introuvable");
                continue;
            }
            YamlConfiguration other = YamlConfiguration.loadConfiguration(extra);
            pages(other.getConfigurationSection("pages"), "menus/" + rel, defaultTitle, maxSlot, topSize, mode, pages, warn);
        }

        return new Menu(id,
                yml.getString("title", ""),
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
                titleLayers(yml.getConfigurationSection("title-layers"), where + " > title-layers", warn));
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
            pages.put(id, new Page(id,
                    p.getString("title", defaultTitle),
                    strings(p, "animation.frames"),
                    Math.max(1, p.getInt("animation.interval", 1)),
                    buttons(p.getConfigurationSection("items"), at + " > items", maxSlot, topSize, mode, warn),
                    paths(p.getConfigurationSection("paths"), at + " > paths", maxSlot, warn)));
        }
    }

    private static final java.util.Set<String> COLORS = java.util.Set.of("vert", "or", "bleu", "rose");

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
                warn.accept(at + " : couleur « " + color + " » inconnue (vert, or, bleu, rose), vert retenu");
                color = "vert";
            }
            out.add(new PathSpec(key, List.copyOf(slots), List.copyOf(branches), p.getString("progress", "0"),
                    Math.max(0, p.getInt("cycle", 0)), color, p.getBoolean("start", true), p.getBoolean("end", false),
                    List.copyOf(nodes), Math.max(0, p.getInt("offset", 0)), java.util.Set.copyOf(waypoints)));
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
                s.getString("confirm", null),
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
                    for (int i = Math.min(from, to); i <= Math.max(from, to); i++) out.add(i);
                } else {
                    out.add(Integer.parseInt(p));
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

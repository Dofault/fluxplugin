package fr.influencraft.fluxdungeons.rpg;

import fr.influencraft.fluxdungeons.pages.DungeonPages;
import fr.influencraft.fluxdungeons.pages.PageKit;
import fr.influencraft.fluxdungeons.rpg.core.ClassDef;
import fr.influencraft.fluxdungeons.rpg.core.CombatMath;
import fr.influencraft.fluxdungeons.rpg.core.StatRegistry;
import fr.influencraft.fluxdungeons.rpg.core.StatSheet;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The HTML pages of the classes and of the character sheet, shown full screen through Flux's {@code Screens}
 * (MC GUI): written into {@code <pages>/fluxdungeons/} from classes.yml, stats.yml and the lang file each time the rpg
 * configs load, in the plain Minecraft look shared with the dungeon selector ({@link PageKit}). Generated files: change
 * this class or the texts ({@code class-pages} in the lang file), never the files. What depends on the player (his
 * class, level, stats) comes from the {@code fluxrpg_*} placeholders, filled by the page engine when the page opens.
 * Buttons run {@code /classe choisir <class>}.
 */
final class ClassPages {

    static final String FOLDER = PageKit.FOLDER;
    static final String CLASSES = FOLDER + "/classes", SHEET = FOLDER + "/personnage", HUB = FOLDER + "/heros";
    /** The class colours, a stylesheet of its own next to the shared one. */
    private static final String COLORS = "classes.css";
    /** Pixel icons of the stats (assets/pages/gen_textures.py). */
    private static final List<String> ICONS = List.of("ico_xp", "ico_max_health", "ico_physical_attack", "ico_magic_power",
            "ico_physical_defense", "ico_crit_chance", "ico_attack_speed", "ico_max_mana");
    /** The stats on the first page, in this order. */
    private static final List<String> HUB_STATS = List.of("max_health", "physical_attack", "magic_power", "physical_defense",
            "crit_chance", "attack_speed");

    /** The comparison bars of the class cards, from the class stats at the top level. */
    private static final List<String> AXES = List.of("health", "power", "defense", "mana", "range");
    /** Stats shown on a class page (when not zero), in this order. */
    private static final List<String> SHOWN = List.of("max_health", "physical_attack", "magic_power", "physical_defense",
            "magic_defense", "max_mana", "mana_regen", "attack_speed", "crit_chance", "crit_damage", "dodge", "healing_power",
            "movement_speed");
    private static final Map<String, String> DEFAULT_COLORS = Map.of("warrior", "#FF5C5C", "archer", "#2ECF7B",
            "mage", "#6FA8FF", "healer", "#FFD27A", "cryomancer", "#74B6DC", "pyromancer", "#FF8A3C", "druid", "#73C25C");

    /** Where the pages take their texts, pictures and colours (the plugin; a stub in tests). */
    interface Source extends PageKit.Source {
        /** The class's colour, null for the default one. */
        String color(String classId);

        String statName(String stat);
    }

    private final Source source;

    ClassPages(Source source) {
        this.source = source;
    }

    /** Writes every page into {@code <root>/fluxdungeons/} (only the files whose content changed: the page engine reloads what changed). */
    void write(Path root, RpgConfig.Set set, long cooldownHours) {
        if (root == null || set == null) return;
        try {
            Path dir = PageKit.prepare(root, source);
            PageKit.save(dir.resolve(COLORS), colors(set));
            for (String icon : ICONS) PageKit.copy(source, dir.resolve("img/" + icon + ".png"), "pages/img/" + icon + ".png");
            PageKit.save(dir.resolve("heros.html"), hub(set));
            PageKit.save(dir.resolve("classes.html"), classes(set));
            for (ClassDef c : set.classes().values()) {
                if (c.hidden()) continue;   // a class being tried out: chosen by command only
                PageKit.save(dir.resolve("class_" + c.id() + ".html"), classPage(set, c));
                PageKit.save(dir.resolve("confirm_" + c.id() + ".html"), confirm(c, cooldownHours));
                image(dir.resolve("img/" + c.id() + ".png"), "pages/img/" + c.id() + ".png");
            }
            PageKit.save(dir.resolve("personnage.html"), sheet(set));
        } catch (IOException e) {
            source.warn("Class pages not written: " + e);
        }
    }

    // ------------------------------------------------------------------ pages

    /** The hero's book, first page of the rpg menu: who the player is, his main stats, then the four ways (classes). */
    private String hub(RpgConfig.Set set) {
        StringBuilder traits = new StringBuilder();
        for (String stat : HUB_STATS) {
            if (set.stats().get(stat) == null) continue;
            traits.append("<div class=\"trait\"><img src=\"img/ico_").append(stat).append(".png\"><b>%fluxrpg_stat_").append(stat)
                    .append("%</b><span>").append(esc(source.statName(stat))).append("</span></div>");
        }
        StringBuilder ways = new StringBuilder();
        int key = 1;
        for (ClassDef c : set.classes().values()) {
            if (c.hidden()) continue;   // a class being tried out: chosen by command only
            String id = c.id();
            ways.append("<a class=\"way c-").append(id).append(" is-%fluxrpg_is_").append(id).append("%\" href=\"class_").append(id)
                    .append(".html\" data-key=\"").append(key++).append("\">").append(weapon(id, "medium"))
                    .append("<b>").append(t("classes." + id + ".name")).append("</b><span>").append(t("class-pages.classes." + id + ".role"))
                    .append("</span><em>").append(t("class-pages.hub.yours")).append("</em></a>");
        }
        String body = bar(t("class-pages.hub.title"))
                + "<section class=\"well portrait\"><div class=\"slot large\"><img src=\"head:{player}\"></div>"
                + "<b class=\"name big\">{player}</b><div class=\"ribbon\">%fluxrpg_class% · %fluxrpg_level_text%</div>"
                + "<div class=\"gauges\">"
                + gauge("xp", "<img src=\"img/ico_xp.png\">" + t("class-pages.hub.xp"), "%fluxrpg_xp_text%", "%fluxrpg_xp_pct%")
                + gauge("hp", "<img src=\"img/ico_max_health.png\">" + t("class-pages.sheet.health"), "%fluxrpg_health% / %fluxrpg_health_max%", "%fluxrpg_health_pct%")
                + gauge("mp", "<img src=\"img/ico_max_mana.png\">" + t("class-pages.sheet.mana"), "%fluxrpg_mana% / %fluxrpg_mana_max%", "%fluxrpg_mana_pct%")
                + "</div></section>"
                + "<section class=\"well traits-box\"><h2>" + t("class-pages.hub.traits") + "</h2><div class=\"traits\">" + traits + "</div>"
                + "<div class=\"more\"><a class=\"btn\" href=\"personnage.html\" data-key=\"f\">" + t("class-pages.hub.all") + "</a></div></section>"
                + "<section class=\"well ways\"><h2>" + t("class-pages.hub.ways") + "</h2>" + ways
                + "<div class=\"choose\">" + t("class-pages.hub.choose") + "</div></section>"
                + footer("", "<a class=\"btn go\" href=\"" + DungeonPages.LIST.substring(FOLDER.length() + 1) + ".html\">"
                + t("class-pages.hub.dungeons") + " ›</a>");
        return page(source.raw("class-pages.hub.title"), "hub has-%fluxrpg_has_class%", body);
    }

    private String classes(RpgConfig.Set set) {
        StringBuilder cards = new StringBuilder();
        int key = 1;
        for (ClassDef c : set.classes().values()) {
            if (c.hidden()) continue;   // a class being tried out: chosen by command only
            String id = c.id();
            cards.append("<a class=\"card c-").append(id).append(" is-%fluxrpg_is_").append(id).append("%\" href=\"class_")
                    .append(id).append(".html\" data-key=\"").append(key++).append("\">")
                    .append("<em>").append(t("class-pages.hub.yours")).append("</em>").append(weapon(id, "large"))
                    .append("<h3>").append(t("classes." + id + ".name")).append("</h3>")
                    .append("<div class=\"role\">").append(t("class-pages.classes." + id + ".role")).append("</div>")
                    .append("<div class=\"stars\">").append(stars(id)).append("</div>")
                    .append("<div class=\"open\">").append(t("class-pages.see")).append(" ›</div></a>");
        }
        String body = bar(t("class-pages.title")) + "<main class=\"cards\">" + cards + "</main>" + footer("", "");
        return page(source.raw("class-pages.title"), "list has-%fluxrpg_has_class%", body);
    }

    private String classPage(RpgConfig.Set set, ClassDef c) {
        String id = c.id();
        int top = set.balance().maxLevel();
        Map<String, Map<String, Double>> all = new LinkedHashMap<>();
        Map<String, Double> best = new LinkedHashMap<>();
        for (ClassDef o : set.classes().values()) {
            if (o.hidden()) continue;   // a class being tried out: chosen by command only
            Map<String, Double> a = axes(set, o, top);
            all.put(o.id(), a);
            a.forEach((k, v) -> best.merge(k, v, Math::max));
        }
        StringBuilder bars = new StringBuilder();
        for (String axis : AXES) {
            double max = best.getOrDefault(axis, 0.0), v = all.get(id).getOrDefault(axis, 0.0);
            int pct = max <= 0 ? 0 : (int) Math.round(12 + 88 * v / max);
            bars.append("<div class=\"axis\"><span>").append(t("class-pages.axes." + axis)).append("</span><div class=\"bar\"><div class=\"fill\" style=\"width: ")
                    .append(pct).append("%\"></div></div></div>");
        }
        StatSheet low = StatSheet.compute(set.stats(), c.modifiers(1));
        StatSheet high = StatSheet.compute(set.stats(), c.modifiers(top));
        StringBuilder rows = new StringBuilder();
        for (String stat : SHOWN) {
            StatRegistry.Def d = set.stats().get(stat);
            if (d == null) continue;
            double a = low.get(stat), b = high.get(stat);
            if (Math.abs(a) < 1e-9 && Math.abs(b) < 1e-9) continue;
            rows.append("<tr><td>").append(esc(source.statName(stat))).append("</td><td>").append(RpgPlayers.total(d, a))
                    .append("</td><td class=\"hi\">").append(RpgPlayers.total(d, b)).append("</td></tr>");
        }
        StringBuilder skills = new StringBuilder();
        skills.append(skill(id, c.basicOnRight() ? "hold" : "left", t("class-pages.classes." + id + ".basic.name"), basicText(c), 0));
        for (String slot : ClassDef.SLOTS) {
            ClassDef.Skill s = c.skills().get(slot);
            if (s == null) continue;
            skills.append(skill(id, c.key(slot), t("class-pages.classes." + id + "." + slot + ".name"), skillText(c, s), s.mana()));
        }
        // the class's own mechanic (elemental classes), with its numbers
        if (!c.passive().isEmpty()) {
            skills.append(skill(id, "passive", t("class-pages.classes." + id + ".passive.name"), m("class-pages.classes." + id + ".passive.description",
                    vars(c.basic().type(), 0, 1, 1, 0, 0, c.basic().range(), c.passive())), -1));
        }
        String body = bar(t("classes." + id + ".name"))
                + "<section class=\"well hero\">" + weapon(id, "large") + "<div class=\"role\">" + t("class-pages.classes." + id + ".role") + "</div>"
                + "<p class=\"desc\">" + t("classes." + id + ".description") + "</p>" + bars
                + "<div class=\"axis\"><span>" + t("class-pages.difficulty") + "</span><span class=\"stars\">" + stars(id) + "</span></div></section>"
                + "<section class=\"well book\"><div class=\"cols\">"
                + "<div class=\"col\"><h4>" + t("class-pages.skills") + "</h4>" + skills + "<p class=\"note\">" + t("class-pages.skills-note") + "</p></div>"
                + "<div class=\"col\"><div class=\"pro\"><b>" + t("class-pages.strengths") + "</b> " + t("class-pages.classes." + id + ".strengths") + "</div>"
                + "<div class=\"con\"><b>" + t("class-pages.weaknesses") + "</b> " + t("class-pages.classes." + id + ".weaknesses") + "</div>"
                + "<h4>" + t("class-pages.stats") + "</h4><table><tr class=\"head\"><td>" + t("class-pages.stats-note") + "</td><td>"
                + esc(m("class-pages.stats-level", "%level%", "1")) + "</td><td class=\"hi\">" + esc(m("class-pages.stats-level", "%level%", String.valueOf(top)))
                + "</td></tr>" + rows + "</table></div></div></section>"
                + footer(back(), "<span class=\"owned\">" + t("class-pages.already") + "</span>"
                + "<a class=\"btn go choose\" href=\"confirm_" + id + ".html\" data-key=\"space\">" + t("class-pages.choose") + "</a>");
        return page(source.raw("classes." + id + ".name"), "detail c-" + id + " is-%fluxrpg_is_" + id + "%", body);
    }

    private String confirm(ClassDef c, long cooldownHours) {
        String id = c.id();
        String name = source.raw("classes." + id + ".name");
        String body = "<section class=\"panel box\">" + weapon(id, "medium")
                + "<h3>" + esc(m("class-pages.confirm-title", "%class%", name)) + "</h3>"
                + "<p class=\"first\">" + t("class-pages.confirm-first") + "</p>"
                + "<p class=\"change\">" + esc(m("class-pages.confirm-change", "%hours%", String.valueOf(cooldownHours))) + "</p>"
                + "<div class=\"row\"><a class=\"btn nav\" data-action=\"back\" data-key=\"q\">" + t("class-pages.cancel") + "</a>"
                + "<a class=\"btn go\" data-command=\"classe choisir " + id + "\" data-close data-sound=\"none\" data-key=\"space\">"
                + t("class-pages.confirm") + "</a></div></section>";
        return PageKit.html(m("class-pages.confirm-title", "%class%", name), "confirm c-" + id + " has-%fluxrpg_has_class%", body, COLORS);
    }

    private String sheet(RpgConfig.Set set) {
        StringBuilder cols = new StringBuilder();
        for (String category : List.of("offense", "defense", "resource", "utility")) {
            StringBuilder lines = new StringBuilder();
            for (StatRegistry.Def d : set.stats().all()) {
                if (!d.category().equals(category)) continue;
                lines.append("<div class=\"stat %fluxrpg_zero_").append(d.id()).append("%\"><span>").append(esc(source.statName(d.id())))
                        .append("</span><b>%fluxrpg_stat_").append(d.id()).append("%</b></div>");
            }
            cols.append("<div class=\"col\"><h4>").append(t("sheet." + category)).append("</h4>").append(lines).append("</div>");
        }
        String body = bar(t("class-pages.sheet.title"))
                + "<section class=\"well gauges\">"
                + "<div>" + gauge("xp", t("class-pages.hub.xp"), "%fluxrpg_xp_text%", "%fluxrpg_xp_pct%") + "</div>"
                + "<div>" + gauge("hp", t("class-pages.sheet.health"), "%fluxrpg_health% / %fluxrpg_health_max%", "%fluxrpg_health_pct%") + "</div>"
                + "<div>" + gauge("mp", t("class-pages.sheet.mana"), "%fluxrpg_mana% / %fluxrpg_mana_max%", "%fluxrpg_mana_pct%") + "</div></section>"
                + "<section class=\"well book\"><div class=\"cols\">" + cols + "</div></section>"
                + "<p class=\"empty\">" + t("class-pages.sheet.not-in-rpg") + "</p>"
                + footer(back(), "<a class=\"btn\" href=\"classes.html\">" + t("class-pages.sheet.change") + "</a>");
        return page(source.raw("class-pages.sheet.title"), "sheet has-%fluxrpg_has_class%", body);
    }

    // ------------------------------------------------------------------ pieces

    private String page(String title, String bodyClass, String body) {
        return PageKit.html(title, bodyClass, body, COLORS);
    }

    /** The title bar of a page, over its window. */
    private String bar(String title) {
        return PageKit.titleBar(title, t("class-pages.beta")) + "<section class=\"panel\"></section>";
    }

    /** The bottom line: buttons on the left and on the right, how to leave in the middle. */
    private String footer(String left, String right) {
        return "<footer class=\"footer\"><div class=\"hint\">" + t("class-pages.sneak") + "</div><div class=\"left\">" + left
                + "</div><div class=\"right\">" + right + "</div></footer>";
    }

    private String back() {
        return "<a class=\"btn nav\" data-action=\"back\" data-key=\"q\">‹ " + t("class-pages.back") + "</a>";
    }

    /** A class's weapon in an inventory slot. */
    private static String weapon(String classId, String size) {
        return "<div class=\"slot " + size + "\"><img src=\"img/" + classId + ".png\"></div>";
    }

    private static String gauge(String kind, String label, String value, String pct) {
        return "<div class=\"label\">" + label + "</div><div class=\"bar " + kind + "\"><div class=\"fill\" style=\"width: " + pct
                + "%\"></div><span class=\"txt\">" + value + "</span></div>";
    }

    private String skill(String classId, String slot, String name, String text, double mana) {
        String cost = mana < 0 ? "" : mana == 0 ? t("class-pages.free") : esc(m("class-pages.mana", "%mana%", num(mana)));
        return "<div class=\"skill\"><div class=\"k\">" + t("class-pages.keys." + slot) + "</div>"
                + "<div class=\"line\">" + name + "<span class=\"cost\">" + cost + "</span></div>"
                + "<p>" + esc(text) + "</p></div>";
    }

    private String stars(String classId) {
        int n = Math.max(1, Math.min(3, (int) Math.round(number(source.raw("class-pages.classes." + classId + ".difficulty"), 1))));
        return "★".repeat(n) + "<s>" + "★".repeat(3 - n) + "</s>";
    }

    private Map<String, Double> axes(RpgConfig.Set set, ClassDef c, int level) {
        StatSheet s = StatSheet.compute(set.stats(), c.modifiers(level));
        Map<String, Double> out = new LinkedHashMap<>();
        out.put("health", s.get("max_health"));
        double power = c.basic().type() == CombatMath.Type.MAGIC ? s.get("magic_power") : s.get("physical_attack");
        out.put("power", power * Math.max(0.1, c.basic().coefficient()) * Math.max(0.25, s.get("attack_speed")));
        out.put("defense", s.get("physical_defense") + s.get("magic_defense"));
        out.put("mana", s.get("max_mana"));
        out.put("range", c.basic().range());
        return out;
    }

    private String basicText(ClassDef c) {
        ClassDef.Attack b = c.basic();
        return m("class-pages.classes." + c.id() + ".basic.description", vars(b.type(), b.coefficient(), 1, b.targets(), 0, 0, b.range(), Map.of()));
    }

    private String skillText(ClassDef c, ClassDef.Skill s) {
        return m("class-pages.classes." + c.id() + "." + s.slot() + ".description",
                // heals scale with magic power (CombatMath.heal), whatever the skill's damage type
                vars(s.kind().equals("heal") ? CombatMath.Type.MAGIC : s.damage(), s.coefficient(), s.hits(), s.targets(), s.duration(), s.mana(), c.basic().range(), s.extra()));
    }

    private String[] vars(CombatMath.Type type, double coef, int hits, int targets, double duration, double mana, double range,
                          Map<String, Double> extra) {
        List<String> v = new ArrayList<>(List.of(
                "%coef%", num(coef * 100) + " %",
                "%power%", source.raw("class-pages.powers." + (type == CombatMath.Type.MAGIC ? "magic" : "physical")),
                "%hits%", String.valueOf(Math.max(1, hits)),
                "%targets%", String.valueOf(Math.max(1, targets)),
                "%duration%", num(duration),
                "%mana%", num(mana),
                "%range%", num(extra.getOrDefault("range", range))));
        for (Map.Entry<String, Double> e : extra.entrySet()) {
            v.add("%" + e.getKey() + "%");
            v.add(num(e.getValue()));
            v.add("%" + e.getKey() + "_pct%");
            v.add(num(e.getValue() * 100) + " %");
        }
        return v.toArray(String[]::new);
    }

    /** One accent colour per class (config rpg.class-colors). */
    private String colors(RpgConfig.Set set) {
        StringBuilder colors = new StringBuilder("/* class colours (config rpg.class-colors), generated */\n");
        for (String id : set.classes().keySet()) {
            String c = source.color(id);
            if (c == null) c = DEFAULT_COLORS.getOrDefault(id, "#B376E5");
            colors.append(".c-").append(id).append(" { --accent: ").append(c).append("; }\n");
        }
        return colors.toString();
    }

    /** The weapon picture of the jar, its flat background made transparent; an existing file is kept (replaceable). */
    private void image(Path target, String resource) throws IOException {
        if (Files.exists(target)) return;
        try (InputStream in = source.resource(resource)) {
            if (in == null) return;
            BufferedImage src = ImageIO.read(in);
            if (src == null) return;
            BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
            int bg = src.getRGB(0, 0) & 0xFFFFFF;
            for (int y = 0; y < src.getHeight(); y++) {
                for (int x = 0; x < src.getWidth(); x++) {
                    int rgb = src.getRGB(x, y) & 0xFFFFFF;
                    out.setRGB(x, y, rgb == bg ? 0 : 0xFF000000 | rgb);
                }
            }
            ImageIO.write(out, "png", target.toFile());
        }
    }

    /** A text of the lang file, colour codes removed, escaped for HTML. */
    private String t(String key) {
        return esc(source.raw(key));
    }

    /** A text of the lang file with variables, colour codes removed (not escaped). */
    private String m(String key, String... vars) {
        return plain(source.raw(key, vars));
    }

    private static String plain(String text) {
        return PageKit.plain(text);
    }

    private static String esc(String text) {
        return PageKit.esc(text);
    }

    static String num(double value) {
        double v = Math.round(value * 10) / 10.0;
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v).replace('.', ',');
    }

    private static double number(String text, double fallback) {
        try {
            return Double.parseDouble(text.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}

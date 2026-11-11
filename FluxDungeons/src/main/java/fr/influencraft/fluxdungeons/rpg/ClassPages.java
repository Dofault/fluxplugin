package fr.influencraft.fluxdungeons.rpg;

import fr.influencraft.fluxdungeons.rpg.core.ClassDef;
import fr.influencraft.fluxdungeons.rpg.core.CombatMath;
import fr.influencraft.fluxdungeons.rpg.core.StatRegistry;
import fr.influencraft.fluxdungeons.rpg.core.StatSheet;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The HTML pages of the classes and of the character sheet, shown full screen through Flux's {@link Screens}
 * (MC GUI): written into {@code <pages>/fluxdungeons/} from classes.yml, stats.yml and the lang file each time the rpg
 * configs load. Generated files: change this class or the texts ({@code class-pages} in the lang file), never the
 * files. What depends on the player (his class, level, stats) comes from the {@code fluxrpg_*} placeholders, filled
 * by the page engine when the page opens. Buttons run {@code /classe choisir <class>}.
 */
final class ClassPages {

    static final String FOLDER = "fluxdungeons";
    static final String CLASSES = FOLDER + "/classes", SHEET = FOLDER + "/personnage";

    /** The comparison bars of the class cards, from the class stats at the top level. */
    private static final List<String> AXES = List.of("health", "power", "defense", "mana", "range");
    /** Stats shown on a class page (when not zero), in this order. */
    private static final List<String> SHOWN = List.of("max_health", "physical_attack", "magic_power", "physical_defense",
            "magic_defense", "max_mana", "mana_regen", "attack_speed", "crit_chance", "crit_damage", "dodge", "healing_power",
            "movement_speed");
    private static final Map<String, String> DEFAULT_COLORS = Map.of("warrior", "#FF5C5C", "archer", "#2ECF7B",
            "mage", "#6FA8FF", "healer", "#FFD27A");

    /** Where the pages take their texts, pictures and colours (the plugin; a stub in tests). */
    interface Source {
        /** A text of the lang file, variables replaced. */
        String raw(String key, String... vars);

        /** A file of the jar, null when missing. */
        InputStream resource(String path);

        /** The class's colour, null for the default one. */
        String color(String classId);

        String statName(String stat);

        void warn(String message);
    }

    private final Source source;

    ClassPages(Source source) {
        this.source = source;
    }

    /** Writes every page into {@code <root>/fluxdungeons/} (only the files whose content changed: the page engine reloads what changed). */
    void write(Path root, RpgConfig.Set set, long cooldownHours) {
        if (root == null || set == null) return;
        Path dir = root.resolve(FOLDER);
        try {
            Files.createDirectories(dir.resolve("img"));
            save(dir.resolve("style.css"), css(set));
            save(dir.resolve("classes.html"), classes(set));
            for (ClassDef c : set.classes().values()) {
                save(dir.resolve("class_" + c.id() + ".html"), classPage(set, c));
                save(dir.resolve("confirm_" + c.id() + ".html"), confirm(c, cooldownHours));
                image(dir.resolve("img/" + c.id() + ".png"), "pages/img/" + c.id() + ".png");
            }
            save(dir.resolve("personnage.html"), sheet(set));
        } catch (IOException e) {
            source.warn("Class pages not written: " + e);
        }
    }

    // ------------------------------------------------------------------ pages

    private String classes(RpgConfig.Set set) {
        int top = set.balance().maxLevel();
        Map<String, Map<String, Double>> axes = new LinkedHashMap<>();
        Map<String, Double> best = new LinkedHashMap<>();
        for (ClassDef c : set.classes().values()) {
            Map<String, Double> a = axes(set, c, top);
            axes.put(c.id(), a);
            a.forEach((k, v) -> best.merge(k, v, Math::max));
        }
        StringBuilder cards = new StringBuilder();
        int key = 1;
        for (ClassDef c : set.classes().values()) {
            String id = c.id();
            StringBuilder bars = new StringBuilder();
            for (String axis : AXES) {
                double max = best.getOrDefault(axis, 0.0);
                double v = axes.get(id).getOrDefault(axis, 0.0);
                int pct = max <= 0 ? 0 : (int) Math.round(12 + 88 * v / max);
                bars.append("<div class=\"axis\"><span>").append(t("class-pages.axes." + axis))
                        .append("</span><div class=\"bar\"><i style=\"width: ").append(pct).append("%\"></i></div></div>");
            }
            cards.append("<a class=\"card c-").append(id).append(" is-%fluxrpg_is_").append(id).append("%\" href=\"class_")
                    .append(id).append(".html\" data-key=\"").append(key++).append("\">")
                    .append("<div class=\"topline\"></div><div class=\"ribbon\">").append(t("class-pages.yours")).append("</div>")
                    .append("<div class=\"glow\"></div><img class=\"weapon\" src=\"img/").append(id).append(".png\">")
                    .append("<h2>").append(t("classes." + id + ".name")).append("</h2>")
                    .append("<div class=\"role\">").append(t("class-pages.classes." + id + ".role")).append("</div>")
                    .append("<p class=\"desc\">").append(t("classes." + id + ".description")).append("</p>")
                    .append("<div class=\"axes\">").append(bars).append("</div>")
                    .append("<div class=\"foot\"><span class=\"stars\">").append(stars(id)).append("</span>")
                    .append("<span class=\"more\">").append(t("class-pages.see")).append(" ›</span></div></a>");
        }
        String body = "<header>" + titleBlock(t("class-pages.title"), t("class-pages.subtitle")) + badge() + "</header>"
                + "<main class=\"cards\">" + cards + "</main>"
                + "<footer><span>" + t("class-pages.sneak") + "</span>"
                + "<a class=\"btn ghost\" data-action=\"close\">" + t("class-pages.close") + "</a></footer>";
        return html(t("class-pages.title"), "list has-%fluxrpg_has_class%", body);
    }

    private String classPage(RpgConfig.Set set, ClassDef c) {
        String id = c.id();
        int top = set.balance().maxLevel();
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
        skills.append(skill(id, "left", t("class-pages.classes." + id + ".basic.name"), basicText(c), 0));
        for (String slot : ClassDef.SLOTS) {
            ClassDef.Skill s = c.skills().get(slot);
            if (s == null) continue;
            skills.append(skill(id, slot, t("class-pages.classes." + id + "." + slot + ".name"), skillText(c, s), s.mana()));
        }
        String body = "<header>" + titleBlock(t("classes." + id + ".name"), t("class-pages.classes." + id + ".role"))
                + badge() + "</header>"
                + "<main class=\"detail\">"
                + "<section class=\"hero\"><div class=\"topline\"></div><div class=\"glow\"></div><img class=\"weapon big\" src=\"img/" + id + ".png\">"
                + "<p class=\"desc\">" + t("classes." + id + ".description") + "</p>"
                + "<div class=\"kv\"><b>" + t("class-pages.difficulty") + "</b><span class=\"stars\">" + stars(id) + "</span></div>"
                + "<div class=\"pro\"><b>" + t("class-pages.strengths") + "</b><p>" + t("class-pages.classes." + id + ".strengths") + "</p></div>"
                + "<div class=\"con\"><b>" + t("class-pages.weaknesses") + "</b><p>" + t("class-pages.classes." + id + ".weaknesses") + "</p></div>"
                + "</section>"
                + "<section class=\"panel stats\"><h3>" + t("class-pages.stats") + "<small>" + t("class-pages.stats-note") + "</small></h3>"
                + "<table><tr class=\"head\"><td></td><td>" + esc(m("class-pages.stats-level", "%level%", "1")) + "</td><td class=\"hi\">"
                + esc(m("class-pages.stats-level", "%level%", String.valueOf(top))) + "</td></tr>" + rows + "</table></section>"
                + "<section class=\"panel skills\"><h3>" + t("class-pages.skills") + "</h3>" + skills
                + "<p class=\"note\">" + t("class-pages.skills-note") + "</p></section>"
                + "</main>"
                + "<footer><a class=\"btn ghost\" data-action=\"back\" data-key=\"q\">‹ " + t("class-pages.back") + "</a>"
                + "<span class=\"owned\">" + t("class-pages.already") + "</span>"
                + "<a class=\"btn primary choose\" href=\"confirm_" + id + ".html\" data-key=\"space\">" + t("class-pages.choose") + "</a></footer>";
        return html(t("classes." + id + ".name"), "detail-page c-" + id + " is-%fluxrpg_is_" + id + "%", body);
    }

    private String confirm(ClassDef c, long cooldownHours) {
        String id = c.id();
        String name = source.raw("classes." + id + ".name");
        String body = "<main class=\"confirm\"><div class=\"box\"><div class=\"topline\"></div><div class=\"glow\"></div>"
                + "<img class=\"weapon\" src=\"img/" + id + ".png\">"
                + "<h2>" + esc(m("class-pages.confirm-title", "%class%", name)) + "</h2>"
                + "<p class=\"first\">" + t("class-pages.confirm-first") + "</p>"
                + "<p class=\"change\">" + esc(m("class-pages.confirm-change", "%hours%", String.valueOf(cooldownHours))) + "</p>"
                + "<div class=\"row\"><a class=\"btn ghost\" data-action=\"back\" data-key=\"q\">" + t("class-pages.cancel") + "</a>"
                + "<a class=\"btn primary\" data-command=\"classe choisir " + id + "\" data-close data-sound=\"none\" data-key=\"space\">"
                + t("class-pages.confirm") + "</a></div></div></main>";
        return html(m("class-pages.confirm-title", "%class%", name), "confirm-page c-" + id + " has-%fluxrpg_has_class%", body);
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
            cols.append("<section class=\"panel\"><h3>").append(t("sheet." + category)).append("</h3>").append(lines).append("</section>");
        }
        String body = "<header>" + titleBlock(t("class-pages.sheet.title"), "%fluxrpg_class% · %fluxrpg_level_text%") + badge() + "</header>"
                + "<div class=\"gauges\">"
                + gauge("xp", "%fluxrpg_xp_text%", "%fluxrpg_xp_pct%")
                + gauge("hp", t("class-pages.sheet.health") + " %fluxrpg_health% / %fluxrpg_health_max%", "%fluxrpg_health_pct%")
                + gauge("mp", t("class-pages.sheet.mana") + " %fluxrpg_mana% / %fluxrpg_mana_max%", "%fluxrpg_mana_pct%")
                + "</div>"
                + "<p class=\"empty\">" + t("class-pages.sheet.not-in-rpg") + "</p>"
                + "<main class=\"sheet\">" + cols + "</main>"
                + "<footer><a class=\"btn ghost\" data-action=\"close\">" + t("class-pages.close") + "</a>"
                + "<a class=\"btn primary\" href=\"classes.html\">" + t("class-pages.sheet.change") + "</a></footer>";
        return html(t("class-pages.sheet.title"), "sheet-page has-%fluxrpg_has_class%", body);
    }

    // ------------------------------------------------------------------ pieces

    private String html(String title, String bodyClass, String body) {
        return "<!doctype html>\n<html lang=\"fr\"><head><meta charset=\"utf-8\"><title>" + esc(title) + "</title>"
                + "<!-- generated by FluxDungeons (rpg.ClassPages): edit the code or the lang file, not this file -->"
                + "<link rel=\"stylesheet\" href=\"style.css\"></head>\n<body class=\"" + bodyClass + "\">" + body + "</body></html>\n";
    }

    private String titleBlock(String title, String subtitle) {
        return "<div class=\"title\"><div class=\"kicker\">✦ RPG <em>" + t("class-pages.beta") + "</em></div><h1>" + title
                + "</h1><p>" + subtitle + "</p></div>";
    }

    /** The player's badge: head, name, class and level (placeholders). */
    private String badge() {
        return "<div class=\"badge\"><img src=\"head:{player}\"><div><b>{player}</b><span>%fluxrpg_class% · %fluxrpg_level_text%</span></div></div>";
    }

    private String gauge(String kind, String label, String pct) {
        return "<div class=\"gauge " + kind + "\"><div class=\"fill\" style=\"width: " + pct + "%\"></div><span>" + label + "</span></div>";
    }

    private String skill(String classId, String slot, String name, String text, double mana) {
        String cost = mana <= 0 ? t("class-pages.free") : esc(m("class-pages.mana", "%mana%", num(mana)));
        return "<div class=\"skill\"><div class=\"key k-" + slot + "\">" + t("class-pages.keys." + slot) + "</div>"
                + "<div class=\"txt\"><div class=\"line\"><b>" + name + "</b><span class=\"cost\">" + cost + "</span></div>"
                + "<p>" + esc(text) + "</p></div></div>";
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

    private String css(RpgConfig.Set set) {
        StringBuilder colors = new StringBuilder();
        for (String id : set.classes().keySet()) {
            String c = source.color(id);
            if (c == null) c = DEFAULT_COLORS.getOrDefault(id, "#B376E5");
            colors.append(".c-").append(id).append(" { --accent: ").append(c).append("; }\n");
        }
        try (InputStream in = source.resource("pages/style.css")) {
            if (in == null) throw new IOException("pages/style.css missing from the jar");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8) + "\n/* class colours (config rpg.class-colors) */\n" + colors;
        } catch (IOException e) {
            source.warn("Class pages: " + e.getMessage());
            return colors.toString();
        }
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

    private static void save(Path file, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (Files.exists(file) && java.util.Arrays.equals(Files.readAllBytes(file), bytes)) return;
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    /** A text of the lang file, colour codes removed, escaped for HTML. */
    private String t(String key) {
        return esc(source.raw(key));
    }

    /** A text of the lang file with variables, colour codes removed (not escaped). */
    private String m(String key, String... vars) {
        return plain(source.raw(key, vars));
    }

    static String plain(String text) {
        return text == null ? "" : text.replaceAll("&#[0-9A-Fa-f]{6}|&[0-9a-fk-orA-FK-OR]|§[0-9a-fk-orA-FK-OR]", "");
    }

    static String esc(String text) {
        return plain(text).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
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

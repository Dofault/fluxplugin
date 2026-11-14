package fr.influencraft.fluxdungeons.pages;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static fr.influencraft.fluxdungeons.pages.PageKit.esc;

/**
 * The dungeon selector, a full screen page (Flux's {@code Screens}): the dungeons on the left, the picture of the
 * chosen one in the middle, its details, the player's class and one button per difficulty level on the right. A page
 * cannot change what it shows, so there is one file per dungeon ({@code donjon_<id>.html}, the list links them) and
 * {@code donjons.html} is the first one. Buttons run {@code /dungeon play <id> <level>}; what depends on the player
 * comes from the {@code fluxrpg_*} placeholders. Pictures: {@code pages/dungeons/<id>.png} and {@code <id>_icon.png}
 * of the jar (assets/pages/gen_dungeon_art.py), a default one for a dungeon without.
 */
public final class DungeonPages {

    /** The page opened by {@code /dungeon}. */
    public static final String LIST = PageKit.FOLDER + "/donjons";
    private static final int PER_PAGE = 5;
    private static final String PREFIX = "donjon_";

    /** A dungeon as the page shows it; {@code name} keeps its colour codes, {@code levels} are the difficulty names. */
    public record Entry(String id, String name, List<String> description, int minPlayers, int maxPlayers, int level,
                        long minutes, int lives, List<String> levels) {
    }

    private final PageKit.Source source;

    public DungeonPages(PageKit.Source source) {
        this.source = source;
    }

    /** Writes the pages of these dungeons (in the order of the list) and removes those of dungeons that are gone. */
    public void write(Path root, List<Entry> dungeons, List<String> classes) {
        if (root == null) return;
        try {
            Path dir = PageKit.prepare(root, source);
            // every picture first: a page lists the icons of its neighbours
            Map<String, String> pictures = new HashMap<>(), icons = new HashMap<>();
            for (Entry e : dungeons) {
                pictures.put(e.id(), picture(dir, e.id(), ""));
                icons.put(e.id(), picture(dir, e.id(), "_icon"));
            }
            Set<String> kept = new HashSet<>();
            for (int i = 0; i < dungeons.size(); i++) {
                Entry e = dungeons.get(i);
                String page = page(dungeons, i, classes, pictures.get(e.id()), icons);
                PageKit.save(dir.resolve(PREFIX + e.id() + ".html"), page);
                if (i == 0) PageKit.save(dir.resolve("donjons.html"), page);
                kept.add(PREFIX + e.id() + ".html");
            }
            if (dungeons.isEmpty()) PageKit.save(dir.resolve("donjons.html"), empty());
            try (DirectoryStream<Path> old = Files.newDirectoryStream(dir, PREFIX + "*.html")) {
                for (Path f : old) if (!kept.contains(f.getFileName().toString())) Files.delete(f);
            }
        } catch (IOException e) {
            source.warn("Dungeon pages not written: " + e);
        }
    }

    /** Copies a dungeon's picture next to the pages and gives its path in a page; the default one without. */
    private String picture(Path dir, String id, String suffix) throws IOException {
        String name = "img/dj_" + id + suffix + ".png";
        if (PageKit.copy(source, dir.resolve(name), "pages/dungeons/" + id + suffix + ".png")) return name;
        String fallback = "img/dj_default" + suffix + ".png";
        PageKit.copy(source, dir.resolve(fallback), "pages/dungeons/default" + suffix + ".png");
        return fallback;
    }

    private String page(List<Entry> dungeons, int index, List<String> classes, String picture, Map<String, String> icons) {
        Entry e = dungeons.get(index);
        int pages = (dungeons.size() + PER_PAGE - 1) / PER_PAGE, current = index / PER_PAGE;
        StringBuilder entries = new StringBuilder();
        for (int i = current * PER_PAGE; i < Math.min(dungeons.size(), (current + 1) * PER_PAGE); i++) {
            Entry o = dungeons.get(i);
            entries.append("<a class=\"entry").append(i == index ? " on" : "").append("\" href=\"").append(PREFIX).append(o.id())
                    .append(".html\" style=\"--accent: ").append(PageKit.color(o.name(), "#FFFFFF")).append("\">")
                    .append("<img src=\"").append(icons.get(o.id())).append("\"><b class=\"big\">").append(esc(o.name())).append("</b><span>")
                    .append(esc(source.raw("dungeon-pages.entry", vars(o)))).append("</span><i>›</i></a>");
        }
        StringBuilder slots = new StringBuilder();
        for (String c : classes) {
            slots.append("<a class=\"slot is-%fluxrpg_is_").append(c).append("%\" href=\"class_").append(c).append(".html\"><img src=\"img/")
                    .append(c).append(".png\"></a>");
        }
        StringBuilder levels = new StringBuilder();
        List<String> names = e.levels();
        for (int i = 0; i < names.size(); i++) {
            String look = i == 0 ? "go" : i == names.size() - 1 ? "hard" : "mid";
            levels.append(button(look, e.id(), i + 1, esc(names.get(i))));
        }
        if (names.size() < 2) {
            levels.setLength(0);
            levels.append(button("go", e.id(), 1, t("dungeon-pages.enter")));
        }
        String color = PageKit.color(e.name(), "#FFD94D");
        String body = PageKit.titleBar(t("dungeon-pages.title"), t("class-pages.beta"))
                + "<section class=\"panel\"></section>"
                + "<nav class=\"entries\">" + entries + "</nav>"
                + "<div class=\"view\" style=\"--accent: " + color + "\"><img src=\"" + picture + "\"><h2>" + esc(e.name()) + "</h2></div>"
                + "<section class=\"well caption\"><p>" + esc(String.join(" ", e.description())) + "</p>"
                + "<div class=\"party\">" + t("dungeon-pages.party") + "</div></section>"
                + "<section class=\"well details\"><h3>" + esc(e.name()) + "</h3>"
                + "<div class=\"kind\">" + esc(source.raw("dungeon-pages.kind", vars(e))) + "</div>"
                + kv("dungeon-pages.level", String.valueOf(e.level()))
                + kv("dungeon-pages.time", esc(source.raw("dungeon-pages.time-value", vars(e))))
                + kv("dungeon-pages.lives", String.valueOf(e.lives()))
                + "<div class=\"rule\"></div><h4>" + t("dungeon-pages.class") + "</h4>"
                + "<div class=\"hero\">%fluxrpg_class% · %fluxrpg_level_text%</div>"
                + "<div class=\"slots\">" + slots + "</div><div class=\"noclass\">" + t("dungeon-pages.no-class") + "</div>"
                + "<div class=\"levels\"><h4>" + t(names.size() < 2 ? "dungeon-pages.start" : "dungeon-pages.difficulty") + "</h4>" + levels + "</div></section>"
                + "<footer class=\"footer\"><div class=\"where big\">"
                + esc(source.raw("dungeon-pages.page", "%page%", String.valueOf(current + 1), "%pages%", String.valueOf(pages))) + "</div>"
                + "<div class=\"hint\">" + t("class-pages.sneak") + "</div><div class=\"right\">"
                + (current > 0 ? turn(dungeons.get((current - 1) * PER_PAGE), "‹ " + t("dungeon-pages.prev")) : "")
                + (current + 1 < pages ? turn(dungeons.get((current + 1) * PER_PAGE), t("dungeon-pages.next") + " ›") : "")
                + "</div></footer>";
        return PageKit.html(source.raw("dungeon-pages.title"), "dungeons has-%fluxrpg_has_class%", body);
    }

    private String empty() {
        String body = PageKit.titleBar(t("dungeon-pages.title"), t("class-pages.beta")) + "<section class=\"panel\"></section>"
                + "<section class=\"well caption\"><p>" + t("dungeon-pages.none") + "</p></section>"
                + "<footer class=\"footer\"><div class=\"hint\">" + t("class-pages.sneak") + "</div></footer>";
        return PageKit.html(source.raw("dungeon-pages.title"), "dungeons", body);
    }

    private static String button(String look, String dungeon, int level, String label) {
        return "<a class=\"btn " + look + "\" data-command=\"dungeon play " + dungeon + " " + level + "\" data-close data-key=\"" + level + "\">"
                + label + "</a>";
    }

    private static String turn(Entry first, String label) {
        return "<a class=\"btn nav\" href=\"" + PREFIX + first.id() + ".html\">" + label + "</a>";
    }

    private String kv(String key, String value) {
        return "<div class=\"kv\"><span>" + t(key) + "</span><b>" + value + "</b></div>";
    }

    private static String[] vars(Entry e) {
        return new String[]{"%min%", String.valueOf(e.minPlayers()), "%max%", String.valueOf(e.maxPlayers()),
                "%level%", String.valueOf(e.level()), "%minutes%", String.valueOf(e.minutes())};
    }

    private String t(String key) {
        return esc(source.raw(key));
    }
}

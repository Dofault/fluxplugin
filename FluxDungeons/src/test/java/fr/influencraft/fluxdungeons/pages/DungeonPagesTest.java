package fr.influencraft.fluxdungeons.pages;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dungeon selector written with the French texts into {@code target/class-pages/} (next to the class pages, to be
 * opened with MC GUI's renderer): one page per dungeon, five dungeons listed per page, a button per difficulty level,
 * the pages of removed dungeons deleted.
 */
class DungeonPagesTest {

    @Test
    void onePagePerDungeonWithItsLevels() throws Exception {
        Map<String, Object> lang;
        try (InputStream in = DungeonPagesTest.class.getResourceAsStream("/lang/fr_FR.yml")) {
            lang = new Yaml().load(in);
        }
        DungeonPages pages = new DungeonPages(new PageKit.Source() {
            public String raw(String key, String... vars) {
                Object v = lang;
                for (String part : key.split("\\.")) v = v instanceof Map<?, ?> m ? m.get(part) : null;
                String text = v == null ? key : String.valueOf(v);
                for (int i = 0; i + 1 < vars.length; i += 2) text = text.replace(vars[i], vars[i + 1]);
                return text;
            }

            public InputStream resource(String path) {
                return DungeonPagesTest.class.getResourceAsStream("/" + path);
            }

            public void warn(String message) {
                throw new AssertionError(message);
            }
        });
        List<DungeonPages.Entry> dungeons = new ArrayList<>();
        String[][] sample = {{"roche", "&#B8B2AA&lLes Profondeurs de la Montagne"}, {"moisson", "&#F0A800&lLe Domaine des Cultivateurs"},
                {"pecheur", "&#43B7B0&lLe Sanctuaire des Abysses Gelées"}, {"fondeur", "&#FF8A1E&lLa Forge du Volcan"},
                {"mineur", "&#E3826C&lLa Mine de Cuivre"}, {"taupe", "&#96765A&lLes Galeries de Taupérion"},
                {"solo", "Essai <sans> couleur"}};
        for (String[] s : sample) {
            dungeons.add(new DungeonPages.Entry(s[0], s[1], List.of("Des cavernes naturelles au cœur d'une montagne où la roche s'est réveillée.",
                    "Traverse la caverne des piliers, franchis le gouffre puis affronte le Colosse dans les profondeurs."), 1, 4, 1, 35, 3,
                    s[0].equals("solo") ? List.of("Normal") : List.of("Facile", "Normal", "Difficile")));
        }
        Path root = Path.of("target", "class-pages");
        Path dir = root.resolve(PageKit.FOLDER);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("donjon_gone.html"), "old");
        pages.write(root, dungeons, List.of("warrior", "archer", "mage", "healer"));

        assertFalse(Files.exists(dir.resolve("donjon_gone.html")));
        assertEquals(Files.readString(dir.resolve("donjon_roche.html")), Files.readString(dir.resolve("donjons.html")));
        String roche = Files.readString(dir.resolve("donjon_roche.html"));
        assertTrue(roche.contains("data-command=\"dungeon play roche 3\""));
        assertTrue(roche.contains("img/dj_roche.png") && Files.exists(dir.resolve("img/dj_roche_icon.png")));
        // five per page: the sixth dungeon is on the second page, reached by the next button
        assertFalse(roche.contains("class=\"entry\" href=\"donjon_taupe.html\""));
        assertTrue(roche.contains("class=\"btn nav\" href=\"donjon_taupe.html\""));
        assertTrue(Files.readString(dir.resolve("donjon_taupe.html")).contains("class=\"btn nav\" href=\"donjon_roche.html\""));
        // a dungeon without picture or levels: the default picture, one button
        String solo = Files.readString(dir.resolve("donjon_solo.html"));
        assertTrue(solo.contains("img/dj_default.png") && solo.contains("Essai &lt;sans&gt; couleur"));
        assertTrue(solo.contains("dungeon play solo 1") && !solo.contains("dungeon play solo 2"));
        for (DungeonPages.Entry e : dungeons) {
            assertFalse(Files.readString(dir.resolve("donjon_" + e.id() + ".html")).contains("dungeon-pages."), e.id() + ": missing text");
        }
    }
}

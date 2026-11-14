package fr.influencraft.fluxdungeons.rpg;

import fr.influencraft.fluxdungeons.rpg.core.Balance;
import fr.influencraft.fluxdungeons.rpg.core.ClassDef;
import fr.influencraft.fluxdungeons.rpg.core.Node;
import fr.influencraft.fluxdungeons.rpg.core.StatRegistry;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The class pages written from the shipped configs and the French texts, into {@code target/class-pages/} (open them
 * with MC GUI's renderer to see them without a server). Every text variable must be filled: what is left between
 * percent signs is a placeholder of the page engine ({@code %fluxrpg_...%}) or a bug.
 */
class ClassPagesTest {

    private static final Pattern LEFT = Pattern.compile("%([a-z_]+)%");

    @Test
    void pagesAreWrittenWithEveryTextFilled() throws Exception {
        StatRegistry stats = StatRegistry.load(node("rpg/stats.yml"));
        Map<String, ClassDef> classes = ClassDef.load(node("rpg/classes.yml"), stats);
        Balance balance = Balance.load(node("rpg/balance.yml"), stats, classes.keySet());
        RpgConfig.Set set = new RpgConfig.Set(stats, classes, balance, null);
        Map<String, Object> lang = yaml("lang/fr_FR.yml");

        Path root = Path.of("target", "class-pages");
        // pages of an earlier run (a class hidden since) must not be found again
        if (Files.exists(root)) {
            try (var old = Files.walk(root)) {
                old.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
            }
        }
        new ClassPages(new ClassPages.Source() {
            public String raw(String key, String... vars) {
                Object v = lang;
                for (String part : key.split("\\.")) v = v instanceof Map<?, ?> m ? m.get(part) : null;
                String text = v == null ? key : String.valueOf(v);
                for (int i = 0; i + 1 < vars.length; i += 2) text = text.replace(vars[i], vars[i + 1]);
                return text;
            }

            public InputStream resource(String path) {
                return ClassPagesTest.class.getResourceAsStream("/" + path);
            }

            public String color(String classId) {
                return null;
            }

            public String statName(String stat) {
                return raw("stats." + stat);
            }

            public void warn(String message) {
                throw new AssertionError(message);
            }
        }).write(root, set, 24);

        Path dir = root.resolve(ClassPages.FOLDER);
        assertTrue(Files.exists(dir.resolve("classes.html")));
        assertTrue(Files.exists(dir.resolve("personnage.html")));
        assertTrue(Files.exists(dir.resolve("style.css")));
        for (String id : classes.keySet()) {
            if (classes.get(id).hidden()) {
                // a class being tried out has no page: chosen by command only
                assertFalse(Files.exists(dir.resolve("class_" + id + ".html")), id);
                continue;
            }
            for (String page : new String[]{"class_" + id + ".html", "confirm_" + id + ".html", "img/" + id + ".png"}) {
                assertTrue(Files.exists(dir.resolve(page)), page);
            }
            String html = Files.readString(dir.resolve("class_" + id + ".html"));
            Matcher m = LEFT.matcher(html);
            while (m.find()) assertTrue(m.group(1).startsWith("fluxrpg_"), id + ": text variable left: " + m.group());
            assertFalse(html.contains("class-pages."), id + ": missing text in the lang file");
            assertTrue(html.contains("classe choisir") || Files.readString(dir.resolve("confirm_" + id + ".html")).contains("classe choisir " + id));
        }
        assertEquals(3, classes.values().stream().filter(c -> !c.hidden()).count());
    }

    private static Node node(String path) {
        return Node.root(path, yaml(path), new ArrayList<>());
    }

    private static Map<String, Object> yaml(String path) {
        try (InputStream in = ClassPagesTest.class.getResourceAsStream("/" + path)) {
            if (in == null) throw new AssertionError("missing resource " + path);
            return new Yaml().load(in);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }
}

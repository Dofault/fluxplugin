package fr.influencraft.fluxdungeons.rpg.core;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The shipped configs of the rpg, loaded as the plugin loads them. */
final class Rpg {

    final StatRegistry stats;
    final Map<String, ClassDef> classes;
    final Balance balance;
    final Equipment equipment;

    private Rpg() throws ConfigException {
        stats = StatRegistry.load(node("stats.yml"));
        classes = ClassDef.load(node("classes.yml"), stats);
        balance = Balance.load(node("balance.yml"), stats, classes.keySet());
        equipment = Equipment.load(node("equipment.yml"), stats, classes.keySet());
    }

    static Rpg shipped() {
        try {
            return new Rpg();
        } catch (ConfigException e) {
            throw new AssertionError("shipped config refused: " + e.problems(), e);
        }
    }

    static Node node(String file) {
        try (InputStream in = Rpg.class.getResourceAsStream("/rpg/" + file)) {
            if (in == null) throw new AssertionError("missing resource rpg/" + file);
            return Node.root(file, new Yaml().load(in), new ArrayList<>());
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    static Node yaml(String text, List<String> problems) {
        return Node.root("test.yml", new Yaml().load(text), problems);
    }
}

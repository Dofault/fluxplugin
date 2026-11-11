package fr.influencraft.fluxdungeons.rpg.core;

import java.util.List;

/** A config of the rpg was refused: every problem found, with its path. The previous config stays in use. */
public final class ConfigException extends Exception {

    private final List<String> problems;

    public ConfigException(List<String> problems) {
        super(problems.size() + " problem(s): " + String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }

    /** Throws when the list holds anything. */
    public static void check(List<String> problems) throws ConfigException {
        if (!problems.isEmpty()) throw new ConfigException(problems);
    }
}

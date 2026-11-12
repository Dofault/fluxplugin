package fr.influencraft.fluxtournament;

import fr.influencraft.flux.api.value.Values.JobSource;

import java.util.List;
import java.util.Locale;

/**
 * What one tournament asks: its kind, its job, the resource drawn (resource, race) and the amount to reach (race).
 * Everything comes from FluxValue: the actions counted are the job's paid actions, with the job's own filters.
 */
record Plan(Kind kind, String job, Group target, List<Group> groups, int amount) {

    /** The kinds of tournament. */
    enum Kind {
        /** Score = FluxValue points of every paid action of the job: rare actions are worth more. */
        JOB,
        /** One resource of the job drawn at random, score = units gathered. */
        RESOURCE,
        /** One resource, the first players to gather {@code amount} win; ends when the podium is full. */
        RACE,
        /** Score = how many different resources of the job were gathered. */
        VARIETY;

        String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Kind of(String id) {
            for (Kind k : values()) if (k.id().equalsIgnoreCase(id)) return k;
            return null;
        }
    }

    /**
     * A resource of a job: one or several sources of FluxValue that are the same thing for a player (an ore and its
     * deepslate variant).
     *
     * @param key    stable id ({@code mineur/iron_ore})
     * @param name   text shown (a {@code <lang:...>} tag for a vanilla block, translated by each client)
     * @param icon   material of the icon
     * @param points FluxValue points of one unit (the cheapest source of the group)
     * @param sprite picture of the pack shown in texts instead of the icon's own sprite (a custom fish), or null
     */
    record Group(String key, String name, String icon, List<JobSource> sources, double points, String sprite) {
    }

    /** Groups counted: the target alone for resource and race, every group of the job otherwise. */
    List<Group> counted() {
        return target != null ? List.of(target) : groups;
    }

    /** True when every unit counts its FluxValue points instead of 1. */
    boolean byPoints() {
        return kind == Kind.JOB;
    }
}

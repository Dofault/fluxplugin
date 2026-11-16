package fr.influencraft.flux.api.jobs;

import org.bukkit.plugin.Plugin;

import java.util.function.Function;

/**
 * The icon of each job, as players see it in the Careers menu (2026-11-16): an item spec ({@code DIAMOND_PICKAXE},
 * {@code itemsadder:ns:id}) for {@code inventory.Items.of}. Given by the plugin that owns what players see of jobs
 * (FluxJobs, from its menu config), read by any other one (the dungeon shop of FluxDungeons): one place for a job's
 * icon in the whole suite.
 */
public final class JobIcons {

    private static volatile Function<String, String> source;
    private static volatile Plugin owner;

    private JobIcons() {
    }

    /** Called by the plugin that owns the jobs' look; {@code icons} gives null for a job it does not know. */
    public static void register(Plugin plugin, Function<String, String> icons) {
        owner = plugin;
        source = icons;
    }

    /** Icon spec of a job, null when nobody gives it (no FluxJobs, unknown job). */
    public static String icon(String job) {
        Function<String, String> s = source;
        Plugin p = owner;
        if (s == null || p == null || !p.isEnabled()) return null;
        return s.apply(job);
    }
}

package fr.influencraft.flux.api.jobs;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Things a job's paid actions can drop besides its pay, given by the plugin that owns them (FluxDungeons: the key of
 * the job's dungeon) and shown by the plugin that draws the jobs (FluxJobs: first in the paid actions, and a line on
 * each action). Neither plugin knows the other.
 */
public final class JobDrops {

    /** A line added to a paid action's description ({@code key: value}). */
    public record Line(String key, String value) {
    }

    public interface Source {
        /** Button shown first among the job's paid actions (what can drop and how), null when nothing drops. */
        ItemStack header(Player viewer, String job);

        /** Line added to one paid action (its drop chance), null when none. */
        default Line line(Player viewer, String job, JobAction action) {
            return null;
        }
    }

    private static final Map<Plugin, Source> SOURCES = new ConcurrentHashMap<>();

    private JobDrops() {
    }

    public static void register(Plugin owner, Source source) {
        SOURCES.put(owner, source);
    }

    public static void unregister(Plugin owner) {
        SOURCES.remove(owner);
    }

    /** Buttons to show first in a job's paid actions. */
    public static List<ItemStack> headers(Player viewer, String job) {
        List<ItemStack> out = new ArrayList<>();
        for (Source s : SOURCES.values()) {
            try {
                ItemStack item = s.header(viewer, job);
                if (item != null) out.add(item);
            } catch (RuntimeException ignored) {
                // a source failing never breaks the jobs menu
            }
        }
        return out;
    }

    /** Lines to add to one paid action. */
    public static List<Line> lines(Player viewer, String job, JobAction action) {
        List<Line> out = new ArrayList<>();
        for (Source s : SOURCES.values()) {
            try {
                Line line = s.line(viewer, job, action);
                if (line != null) out.add(line);
            } catch (RuntimeException ignored) {
                // same
            }
        }
        return out;
    }
}

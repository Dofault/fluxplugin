package fr.influencraft.fluxtournament;

import fr.influencraft.flux.api.value.Values;
import fr.influencraft.flux.api.value.Values.JobSource;
import fr.influencraft.fluxtournament.Plan.Group;
import fr.influencraft.fluxtournament.Plan.Kind;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Draws the next tournament from the config and FluxValue: a kind by weight, a job among those allowed for it (not
 * one of the last ones drawn), and for resource and race a resource a player can gather often enough in the time.
 * Nothing is written by hand: the actions, their filters and what they are worth are FluxValue's.
 */
final class Planner {

    /** Settings of one kind ({@code kinds.<kind>} in the config). */
    record KindSettings(double weight, List<String> jobs, double maxUnitPoints, int minGroups, double effortPoints,
                        int minAmount, int podium) {
    }

    private final Map<Kind, KindSettings> kinds = new EnumMap<>(Kind.class);
    private List<String> include = List.of();
    private Set<String> exclude = Set.of();
    private Set<String> excludeSources = Set.of();
    private Map<String, String> icons = Map.of();
    /** "job/source" -> {item, sprite}: resources with no vanilla item of their own (custom fish). */
    private Map<String, String[]> resourceIcons = Map.of();
    private int noRepeat = 2;
    private final Deque<String> recentJobs = new ArrayDeque<>();

    void configure(ConfigurationSection config) {
        kinds.clear();
        ConfigurationSection ks = config.getConfigurationSection("kinds");
        for (Kind k : Kind.values()) {
            ConfigurationSection s = ks == null ? null : ks.getConfigurationSection(k.id());
            if (s == null) continue;
            kinds.put(k, new KindSettings(Math.max(0, s.getDouble("weight", 1)), lower(s.getStringList("jobs")),
                    Math.max(1, s.getDouble("max-unit-points", 300)), Math.max(2, s.getInt("min-groups", 10)),
                    Math.max(1, s.getDouble("effort-points", 900)), Math.max(1, s.getInt("min-amount", 10)),
                    Math.max(1, s.getInt("podium", 3))));
        }
        include = lower(config.getStringList("jobs.include"));
        exclude = new HashSet<>(lower(config.getStringList("jobs.exclude")));
        excludeSources = new HashSet<>(lower(config.getStringList("jobs.exclude-sources")));
        noRepeat = Math.max(0, config.getInt("jobs.no-repeat", 2));
        Map<String, String> ic = new LinkedHashMap<>();
        ConfigurationSection is = config.getConfigurationSection("jobs.icons");
        if (is != null) for (String k : is.getKeys(false)) ic.put(k.toLowerCase(Locale.ROOT), is.getString(k));
        icons = ic;
        Map<String, String[]> ri = new LinkedHashMap<>();
        ConfigurationSection rs = config.getConfigurationSection("jobs.resource-icons");
        if (rs != null) {
            for (String k : rs.getKeys(false)) {
                ConfigurationSection r = rs.getConfigurationSection(k);
                if (r != null) ri.put(k.toLowerCase(Locale.ROOT), new String[]{r.getString("item"), r.getString("sprite")});
            }
        }
        resourceIcons = ri;
    }

    KindSettings settings(Kind kind) {
        return kinds.get(kind);
    }

    String jobIcon(String job) {
        return icons.getOrDefault(job, "EXPERIENCE_BOTTLE");
    }

    /** Jobs a kind may draw: its own list, else the global include list (or every FluxValue job), minus exclusions. */
    List<String> jobs(Kind kind) {
        KindSettings s = kinds.get(kind);
        List<String> base = s != null && !s.jobs().isEmpty() ? s.jobs() : !include.isEmpty() ? include : Values.jobs();
        List<String> out = new ArrayList<>();
        for (String j : base) if (!exclude.contains(j) && !groups(j).isEmpty()) out.add(j);
        return out;
    }

    // ---------------------------------------------------------------- drawing

    /** A tournament drawn at random, null when nothing can be drawn (no FluxValue, no job). */
    Plan draw() {
        List<Kind> pool = new ArrayList<>();
        for (Map.Entry<Kind, KindSettings> e : kinds.entrySet()) if (e.getValue().weight() > 0) pool.add(e.getKey());
        // a kind by weight; when it cannot be held, another one among the rest
        while (!pool.isEmpty()) {
            double total = 0;
            for (Kind k : pool) total += kinds.get(k).weight();
            double r = ThreadLocalRandom.current().nextDouble(total), acc = 0;
            Kind kind = pool.get(pool.size() - 1);
            for (Kind k : pool) {
                acc += kinds.get(k).weight();
                if (r < acc) {
                    kind = k;
                    break;
                }
            }
            pool.remove(kind);
            List<String> jobs = jobs(kind);
            List<String> fresh = new ArrayList<>(jobs);
            fresh.removeAll(recentJobs);
            if (fresh.isEmpty()) fresh = jobs;
            java.util.Collections.shuffle(fresh);
            for (String job : fresh) {
                Plan p = plan(kind, job, null);
                if (p != null) return p;
            }
        }
        return null;
    }

    /** Remembers the job of a tournament that started, so the next ones draw another one. */
    void started(Plan plan) {
        recentJobs.remove(plan.job());
        recentJobs.addFirst(plan.job());
        while (recentJobs.size() > noRepeat) recentJobs.removeLast();
    }

    /**
     * The plan of a kind on a job; {@code target} = group key or id to force (resource, race), null to draw one.
     * Null when the job cannot hold that kind (too few resources, none gathered often enough).
     */
    Plan plan(Kind kind, String job, String target) {
        KindSettings s = kinds.get(kind);
        if (s == null) s = new KindSettings(1, List.of(), 300, 10, 900, 10, 3);
        List<Group> all = groups(job);
        if (all.isEmpty()) return null;
        // a resource to show and gather: a block or a fish, not "brew" or "smelt" (those jobs only hold the job kind)
        List<Group> groups = all.stream().filter(g -> g.sources().stream().anyMatch(src -> !src.targets().isEmpty())).toList();
        return switch (kind) {
            case JOB -> new Plan(kind, job, null, all, 0);
            // amount = every resource of the job: the first player to gather them all ends the tournament
            case VARIETY -> groups.size() >= s.minGroups() ? new Plan(kind, job, null, groups, groups.size()) : null;
            case RESOURCE, RACE -> {
                List<Group> feasible = new ArrayList<>();
                for (Group g : groups) {
                    if (target != null && !g.key().equalsIgnoreCase(target) && !g.key().endsWith("/" + target.toLowerCase(Locale.ROOT))) continue;
                    if (target != null || feasible(kind, s, g)) feasible.add(g);
                }
                if (feasible.isEmpty()) yield null;
                Group g = feasible.get(ThreadLocalRandom.current().nextInt(feasible.size()));
                yield new Plan(kind, job, g, groups, kind == Kind.RACE ? raceAmount(s, g) : 0);
            }
        };
    }

    /**
     * A resource common enough: worth at most {@code max-unit-points} (resource), or a race amount of at least
     * {@code min-amount} units. FluxValue's points measure effort, so the same rule fits every job.
     */
    private boolean feasible(Kind kind, KindSettings s, Group g) {
        if (g.points() <= 0) return false;
        if (kind == Kind.RACE) return raceAmount(s, g) >= s.minAmount();
        return g.points() <= s.maxUnitPoints();
    }

    /** Units to gather in a race: {@code effort-points} of FluxValue, rounded to a readable number. */
    int raceAmount(KindSettings s, Group g) {
        return g.points() <= 0 ? 0 : nice(s.effortPoints() / g.points());
    }

    /** A readable amount: exact under 10, then rounded to 5, then to 10 (as the pass missions). */
    static int nice(double a) {
        if (a < 10) return Math.max(1, (int) Math.round(a));
        if (a < 100) return Math.max(10, (int) Math.round(a / 5) * 5);
        return (int) Math.round(a / 10) * 10;
    }

    // ---------------------------------------------------------------- resources of a job

    /** Resources of a job, sources that are the same thing for a player grouped (an ore and its deepslate form). */
    List<Group> groups(String job) {
        Map<String, List<JobSource>> byKey = new LinkedHashMap<>();
        for (JobSource s : Values.jobSources(job)) {
            if (excludeSources.contains(job + "/" + s.id().toLowerCase(Locale.ROOT))) continue;
            byKey.computeIfAbsent(s.trigger().replace("_cascade", "") + "|" + base(s), k -> new ArrayList<>()).add(s);
        }
        List<Group> out = new ArrayList<>();
        for (Map.Entry<String, List<JobSource>> e : byKey.entrySet()) {
            List<JobSource> sources = e.getValue();
            JobSource first = sources.get(0);
            String base = base(first);
            double points = Double.MAX_VALUE;
            for (JobSource s : sources) points = Math.min(points, s.weight());
            Material m = Material.matchMaterial(base);
            String name;
            if (m != null) name = "<lang:" + m.translationKey() + ">";
            else if (first.label() != null && !first.label().isBlank()) name = first.label();
            else name = words(first.id());
            String icon = icon(m);
            String sprite = null;
            for (JobSource s : sources) {
                String[] own = resourceIcons.get(job + "/" + s.id().toLowerCase(Locale.ROOT));
                if (own == null) continue;
                if (own[0] != null && Material.matchMaterial(own[0]) != null) icon = Material.matchMaterial(own[0]).name();
                if (own[1] != null && !own[1].isBlank()) sprite = own[1];
                break;
            }
            out.add(new Group(job + "/" + base, name, icon != null ? icon : jobIcon(job), List.copyOf(sources), points, sprite));
        }
        return out;
    }

    /** What a source is for a player: its first target without the deepslate / stripped forms, else its id. */
    private static String base(JobSource s) {
        String t = s.targets().isEmpty() || s.targets().get(0).contains(":") || s.targets().get(0).contains("_fish")
                || s.targets().get(0).endsWith("jellyfish") ? s.id() : s.targets().get(0);
        t = t.toLowerCase(Locale.ROOT).replace("minecraft:", "");
        if (t.startsWith("deepslate_") && Material.matchMaterial(t.substring(10)) != null) t = t.substring(10);
        // a wall coral fan is the coral fan, a vine's stem is the vine: one resource for a player
        if (t.contains("_wall_") && Material.matchMaterial(t.replace("_wall_", "_")) != null) t = t.replace("_wall_", "_");
        if (t.endsWith("_plant") && Material.matchMaterial(t.substring(0, t.length() - 6)) != null) t = t.substring(0, t.length() - 6);
        return t;
    }

    private static String icon(Material m) {
        if (m == null) return null;
        if (m.isItem()) return m.name();
        Material crop = CROP_ITEMS.get(m);
        if (crop != null) return crop.name();
        try {
            Material placed = m.createBlockData().getPlacementMaterial();
            if (placed.isItem() && !placed.isAir()) return placed.name();
        } catch (RuntimeException ignored) {
            // not a block
        }
        return null;
    }

    private static final Map<Material, Material> CROP_ITEMS = Map.of(Material.BEETROOTS, Material.BEETROOT,
            Material.MELON_STEM, Material.MELON_SLICE, Material.PUMPKIN_STEM, Material.PUMPKIN,
            Material.CARROTS, Material.CARROT, Material.POTATOES, Material.POTATO, Material.COCOA, Material.COCOA_BEANS,
            Material.SWEET_BERRY_BUSH, Material.SWEET_BERRIES);

    private static String words(String id) {
        String w = id.replace('_', ' ').replace('-', ' ');
        return w.isEmpty() ? id : Character.toUpperCase(w.charAt(0)) + w.substring(1);
    }

    private static List<String> lower(List<String> in) {
        List<String> out = new ArrayList<>();
        for (String s : in) out.add(s.toLowerCase(Locale.ROOT));
        return out;
    }
}

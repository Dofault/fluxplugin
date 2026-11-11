package fr.influencraft.fluxpass;

import fr.influencraft.flux.api.value.Values;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.Consumer;

/**
 * The categories of missions ({@code missions/<id>.yml}): when each renews, its missions and their variants, and what
 * each variant asks. A variant asks {@code effort / points of one action} (FluxValue), unless it sets an
 * {@code amount}: every mission of a category costs the same play time.
 */
final class Missions {

    enum Renewal { DAILY, MONTHLY, NONE }

    /** One way to do a mission: what counts (libreforge counters) and how much of it. */
    record Variant(String id, String name, String description, String icon, List<Map<?, ?>> counters,
                   int amount, String pricedBy) {

        String text() {
            return description.replace("{amount}", String.valueOf(amount));
        }
    }

    record Mission(String id, String name, String icon, int points, boolean premium, List<Variant> variants) {
    }

    record Category(String id, String name, String icon, int amount, int order, Renewal renewal, LocalTime time,
                    int day, int offsetDays, List<Mission> missions) {

        Mission mission(String id) {
            for (Mission m : missions) if (m.id().equals(id)) return m;
            return null;
        }

        /** Start of the current period (epoch millis): the last renewal; 0 for a category that never renews. */
        long period(long now) {
            ZonedDateTime at = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), ZoneId.systemDefault());
            return switch (renewal) {
                case NONE -> 0;
                case DAILY -> {
                    ZonedDateTime today = at.toLocalDate().atTime(time).atZone(at.getZone());
                    yield (at.isBefore(today) ? today.minusDays(1) : today).toInstant().toEpochMilli();
                }
                case MONTHLY -> {
                    ZonedDateTime thisMonth = monthly(at.toLocalDate().withDayOfMonth(1), at.getZone());
                    yield (at.isBefore(thisMonth) ? monthly(at.toLocalDate().withDayOfMonth(1).minusMonths(1), at.getZone())
                            : thisMonth).toInstant().toEpochMilli();
                }
            };
        }

        private ZonedDateTime monthly(LocalDate firstOfMonth, ZoneId zone) {
            int d = Math.min(Math.max(1, day), firstOfMonth.lengthOfMonth());
            return LocalDateTime.of(firstOfMonth.withDayOfMonth(d), time).atZone(zone);
        }

        /** When the missions of the current period open (the period start, later by the offset for weeks). */
        long opens(long now) {
            long p = period(now);
            return renewal == Renewal.MONTHLY ? p + offsetDays * 86_400_000L : p;
        }

        /** Next renewal (epoch millis), 0 when it never renews. */
        long next(long now) {
            long p = period(now);
            ZonedDateTime start = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(p), ZoneId.systemDefault());
            return switch (renewal) {
                case NONE -> 0;
                case DAILY -> start.plusDays(1).toInstant().toEpochMilli();
                case MONTHLY -> monthly(start.toLocalDate().withDayOfMonth(1).plusMonths(1), start.getZone()).toInstant().toEpochMilli();
            };
        }
    }

    private final List<Category> categories = new ArrayList<>();

    List<Category> all() {
        return categories;
    }

    Category category(String id) {
        for (Category c : categories) if (c.id().equalsIgnoreCase(id)) return c;
        return null;
    }

    /** Reads every {@code missions/*.yml} of the folder, amounts priced now (FluxValue may change them on reload). */
    static Missions load(File folder, Consumer<String> warn) {
        Missions m = new Missions();
        File[] files = folder.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) return m;
        Arrays.sort(files);
        for (File f : files) {
            String id = f.getName().substring(0, f.getName().length() - 4).toLowerCase(Locale.ROOT);
            if (id.startsWith("_")) continue;
            YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
            Renewal renewal;
            try {
                renewal = Renewal.valueOf(yml.getString("renewal.type", "none").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                warn.accept(f.getName() + ": renewal.type must be daily, monthly or none");
                renewal = Renewal.NONE;
            }
            LocalTime time;
            try {
                time = LocalTime.parse(yml.getString("renewal.time", "00:00"));
            } catch (RuntimeException e) {
                warn.accept(f.getName() + ": unreadable renewal.time (HH:mm)");
                time = LocalTime.MIDNIGHT;
            }
            double effort = yml.getDouble("effort", 600);
            int points = yml.getInt("points", 10);
            List<Mission> missions = new ArrayList<>();
            ConfigurationSection ms = yml.getConfigurationSection("missions");
            if (ms != null) {
                for (String mid : ms.getKeys(false)) {
                    ConfigurationSection s = ms.getConfigurationSection(mid);
                    if (s == null) continue;
                    String where = f.getName() + " > " + mid;
                    int missionPoints = s.getInt("points", points);
                    // effort-per-point: a mission costs play time in proportion to what it pays
                    double missionEffort = s.contains("effort") ? s.getDouble("effort")
                            : yml.contains("effort-per-point") ? missionPoints * yml.getDouble("effort-per-point") : effort;
                    List<Variant> variants = new ArrayList<>();
                    ConfigurationSection vs = s.getConfigurationSection("variants");
                    if (vs != null) {
                        for (String vid : vs.getKeys(false)) {
                            Variant v = variant(vid, vs.getConfigurationSection(vid), s.getString("icon", "PAPER"), missionEffort, where + " > " + vid, warn);
                            if (v != null) variants.add(v);
                        }
                    } else {
                        Variant v = variant(mid, s, s.getString("icon", "PAPER"), missionEffort, where, warn);
                        if (v != null) variants.add(v);
                    }
                    if (variants.isEmpty()) {
                        warn.accept(where + ": no variant with counters (mission ignored)");
                        continue;
                    }
                    missions.add(new Mission(mid, s.getString("name", mid), s.getString("icon", variants.get(0).icon()),
                            missionPoints, s.getBoolean("premium", false), List.copyOf(variants)));
                }
            }
            m.categories.add(new Category(id, yml.getString("name", id), yml.getString("icon", "PAPER"),
                    Math.max(1, yml.getInt("amount", 1)), yml.getInt("order", 0), renewal, time,
                    yml.getInt("renewal.day", 1), Math.max(0, yml.getInt("renewal.offset-days", 0)), List.copyOf(missions)));
        }
        m.categories.sort(Comparator.comparingInt(Category::order).thenComparing(Category::id));
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Variant variant(String id, ConfigurationSection s, String missionIcon, double effort,
                                   String where, Consumer<String> warn) {
        if (s == null) return null;
        List<Map<?, ?>> counters = s.getMapList("counters");
        if (counters.isEmpty()) return null;
        Map<String, Object> first = (Map<String, Object>) counters.get(0);
        String trigger = String.valueOf(first.getOrDefault("trigger", ""));
        Map<String, Object> filters = first.get("filters") instanceof Map<?, ?> fm ? (Map<String, Object>) fm : Map.of();
        String target = firstOf(filters, "blocks", "entities", "items", "custom_fish_type", "enchant", "text");
        if (target != null && filters.containsKey("custom_fish_type") && !filters.containsKey("blocks")) target = "customfishing:" + target;
        String icon = s.getString("icon", target != null ? iconOf(trigger, target, missionIcon) : missionIcon);
        // price: "<trigger> [target]" = the action FluxValue prices it by, when the counter alone does not say it
        // (gliding, riding and walking are all "move")
        String price = s.getString("price", "");
        if (!price.isBlank()) {
            String[] p = price.trim().split("\\s+", 2);
            trigger = p[0];
            target = p.length > 1 ? p[1] : null;
        }
        int amount = s.getInt("amount", 0);
        String pricedBy = "amount";
        if (amount <= 0) {
            double factor = s.getDouble("effort-factor", 1);
            OptionalDouble one = pointsOfOne(trigger, target);
            if (one.isPresent() && one.getAsDouble() > 0) {
                amount = nice(s.getDouble("effort", effort) * factor / one.getAsDouble());
                pricedBy = trigger + (target == null ? "" : " " + target) + " = " + Math.round(one.getAsDouble() * 100) / 100.0;
            } else if (s.getInt("default-amount", 0) > 0) {
                amount = s.getInt("default-amount");
                pricedBy = "default-amount (FluxValue cannot price " + trigger + (target == null ? "" : " " + target) + ")";
            } else {
                warn.accept(where + ": FluxValue cannot price " + trigger + (target == null ? "" : " " + target)
                        + " and no amount is set (asks 1): add its weight to FluxValue");
                amount = 1;
                pricedBy = "none";
            }
        }
        return new Variant(id, s.getString("name", ""), s.getString("description", ""), icon, List.copyOf(counters), amount, pricedBy);
    }

    /**
     * Points of one action. An item made or used (craft, smelt, consume...) is worth the item itself (its
     * ingredients, from the real recipes); anything else is worth the action (a break, a kill, a catch).
     */
    static OptionalDouble pointsOfOne(String trigger, String target) {
        if (target != null && ITEM_TRIGGERS.contains(trigger)) {
            // what the item costs a player (without Tranchee: sand or clay dug by area would be worth nothing)
            OptionalDouble item = Values.pointsWithoutArea(target.toUpperCase(Locale.ROOT));
            if (item.isPresent()) return item;
        }
        return Values.actionPoints(trigger, target);
    }

    private static final java.util.Set<String> ITEM_TRIGGERS = java.util.Set.of("craft", "smelt", "consume",
            "smith_item", "block_item_drop", "item_break");

    /** A readable amount: exact under 10, then rounded to 5, then to 10. */
    static int nice(double a) {
        if (a < 10) return Math.max(1, (int) Math.round(a));
        if (a < 100) return Math.max(10, (int) Math.round(a / 5) * 5);
        return (int) Math.round(a / 10) * 10;
    }

    private static String firstOf(Map<String, Object> filters, String... keys) {
        for (String k : keys) {
            if (filters.get(k) instanceof List<?> l && !l.isEmpty()) {
                return String.valueOf(l.get(0)).toLowerCase(Locale.ROOT).replace("minecraft:", "");
            }
        }
        return null;
    }

    /** The resource to show: the block or item itself, the spawn egg of a creature. */
    private static String iconOf(String trigger, String target, String fallback) {
        Material m = Material.matchMaterial(target);
        if (m != null && m.isItem()) return m.name();
        // a block that is no item (carrots, potatoes, cocoa...): the item it comes from
        if (m != null && m.isBlock()) {
            Material item = CROP_ITEMS.getOrDefault(m, m.createBlockData().getPlacementMaterial());
            if (item != null && item.isItem() && !item.isAir()) return item.name();
        }
        Material egg = Material.matchMaterial(target + "_spawn_egg");
        if (egg != null) return egg.name();
        return fallback;
    }

    /** Crops whose placement item is not the harvest (beetroots are planted from seeds). */
    private static final Map<Material, Material> CROP_ITEMS = Map.of(Material.BEETROOTS, Material.BEETROOT,
            Material.MELON_STEM, Material.MELON_SLICE, Material.PUMPKIN_STEM, Material.PUMPKIN);

    /** All the variants, for reports. */
    Map<String, List<Variant>> variantsByMission() {
        Map<String, List<Variant>> out = new LinkedHashMap<>();
        for (Category c : categories) for (Mission ms : c.missions()) out.put(c.id() + "/" + ms.id(), ms.variants());
        return out;
    }
}

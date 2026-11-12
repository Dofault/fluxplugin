package fr.influencraft.flux.api.value;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * What everything is worth, in one place. Every source of the server (a block, a mob, a catch) has a weight in
 * <b>points</b>, 1 point being a fixed amount of optimal play; items get theirs from their source or from their
 * recipes. Job XP, job money, shop prices, offering quantities and collection tiers all derive from it, so changing a
 * weight moves all of them. FluxValue is the provider; plugins of the suite use this class and keep working (with
 * their own numbers) when it is missing.
 *
 * <p>Items are named like the rest of the suite: a material name ({@code DIAMOND}), a namespaced id
 * ({@code customfishing:common_carp_fish}) or {@code enchant:<id>} for one level of an enchantment.</p>
 */
public final class Values {

    /**
     * One paid action of a job, exactly as FluxValue writes it into the job plugin: the same trigger, filters and
     * conditions, so counting it with {@code Effects.count} counts what the job pays (tournaments, quests).
     *
     * @param job        job id ({@code mineur})
     * @param id         source id in FluxValue ({@code iron_ore}, {@code poisson_commun})
     * @param label      label given in FluxValue, or null
     * @param trigger    libreforge trigger ({@code mine_block}, {@code catch_fish}...)
     * @param targets    blocks or custom fish ids it is limited to (empty: any)
     * @param weight     points of one action, or per unit of the trigger's value when not {@code fixedValue}
     * @param fixedValue true: one action is worth {@code weight}; false: {@code weight} x the trigger's value (health of
     *                   a mob, bottles brewed...)
     * @param filters    libreforge filters, targets included
     * @param conditions libreforge conditions (the job's tool...)
     */
    public record JobSource(String job, String id, String label, String trigger, List<String> targets, double weight,
                            boolean fixedValue, Map<String, Object> filters, List<?> conditions) {

        public JobSource {
            targets = targets == null ? List.of() : List.copyOf(targets);
            filters = filters == null ? Map.of() : Map.copyOf(filters);
            conditions = conditions == null ? List.of() : conditions;
        }

        /**
         * Counter config for {@code Effects.count}. {@code perPoint} false: {@code value} per unit counted (one block,
         * one catch; the trigger's units when the weight is per unit: blocks of a cascade, bottles); true: the points
         * of the action, times {@code value}.
         */
        public Map<String, Object> counter(double value, boolean perPoint) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("trigger", trigger);
            m.put(fixedValue ? "value" : "multiplier", perPoint ? weight * value : value);
            if (!filters.isEmpty()) m.put("filters", new LinkedHashMap<>(filters));
            if (!conditions.isEmpty()) m.put("conditions", conditions);
            return m;
        }
    }

    /** What FluxValue provides. */
    public interface Provider {
        /** Points of one item in optimal play, Tranchee included; empty when the item has no value. */
        OptionalDouble points(String item);

        /** Same without Tranchee: what the item costs a player who does not have it yet. */
        OptionalDouble pointsWithoutArea(String item);

        /** Job the effort of this item mostly goes to ("divers" when none). */
        String job(String item);

        /** Unit price the server shop pays, empty when the item has no value. */
        OptionalDouble sellPrice(String item);

        /** Unit price the server shop asks, empty when the item has no value. */
        OptionalDouble buyPrice(String item);

        /** Points earned per second of optimal play, in every job. */
        double pointsPerSecond();

        /**
         * Points of one action ({@code trigger} as in libreforge: {@code mine_block}, {@code kill}, {@code catch_fish},
         * {@code brew}...) on {@code target} (a block or a creature id, null when the trigger has none); empty when
         * nothing values it.
         */
        default OptionalDouble actionPoints(String trigger, String target) {
            return OptionalDouble.empty();
        }

        /** Money earned per second of optimal play by a level 100 player (the reference income of the server). */
        default double moneyPerSecond() {
            return 0;
        }

        /** Jobs FluxValue knows, in its order. */
        default List<String> jobs() {
            return List.of();
        }

        /** Paid actions of a job, empty when unknown. */
        default List<JobSource> jobSources(String job) {
            return List.of();
        }
    }

    private static volatile Provider provider;
    private static final List<Runnable> LISTENERS = new ArrayList<>();

    private Values() {
    }

    public static void provider(Provider p) {
        provider = p;
        changed();
    }

    public static boolean available() {
        return provider != null;
    }

    public static OptionalDouble points(String item) {
        Provider p = provider;
        return p == null ? OptionalDouble.empty() : p.points(item);
    }

    public static OptionalDouble pointsWithoutArea(String item) {
        Provider p = provider;
        return p == null ? OptionalDouble.empty() : p.pointsWithoutArea(item);
    }

    public static String job(String item) {
        Provider p = provider;
        return p == null ? "divers" : p.job(item);
    }

    public static OptionalDouble sellPrice(String item) {
        Provider p = provider;
        return p == null ? OptionalDouble.empty() : p.sellPrice(item);
    }

    public static OptionalDouble buyPrice(String item) {
        Provider p = provider;
        return p == null ? OptionalDouble.empty() : p.buyPrice(item);
    }

    /**
     * Points of one action: breaking a block ({@code mine_block}, {@code coal_ore}), killing a creature
     * ({@code kill}, {@code zombie}), a catch, a brew, a smelt... Empty when nothing values it. What a quest asks
     * comes from here: {@code amount = effort points / actionPoints}, so every quest costs the same play time.
     */
    public static OptionalDouble actionPoints(String trigger, String target) {
        Provider p = provider;
        return p == null || trigger == null ? OptionalDouble.empty() : p.actionPoints(trigger, target);
    }

    public static double pointsPerSecond() {
        Provider p = provider;
        return p == null ? 0 : p.pointsPerSecond();
    }

    /**
     * Money a level 100 player earns per second of optimal play: the unit to express a reward in ("as much as 5 minutes
     * of play") instead of a hard-coded amount. 0 without FluxValue.
     */
    public static double moneyPerSecond() {
        Provider p = provider;
        return p == null ? 0 : p.moneyPerSecond();
    }

    /** Jobs FluxValue knows (empty without FluxValue). */
    public static List<String> jobs() {
        Provider p = provider;
        return p == null ? List.of() : p.jobs();
    }

    /**
     * Paid actions of a job as FluxValue writes them into the job plugin (trigger, filters, conditions, weight): what
     * a tournament or a quest counts to follow the job exactly. Empty without FluxValue or for an unknown job.
     */
    public static List<JobSource> jobSources(String job) {
        Provider p = provider;
        return p == null || job == null ? List.of() : p.jobSources(job);
    }

    /** Runs {@code listener} each time values change (FluxValue started or reloaded). */
    public static synchronized void onChange(Runnable listener) {
        LISTENERS.add(listener);
    }

    /** Called by the provider once values were computed again. */
    public static synchronized void changed() {
        for (Runnable r : LISTENERS) r.run();
    }
}

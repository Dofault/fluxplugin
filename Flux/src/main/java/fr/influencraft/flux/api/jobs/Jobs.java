package fr.influencraft.flux.api.jobs;

import org.bukkit.entity.Player;

import java.util.List;

/**
 * Careers of the server, whatever plugin runs them (EcoJobs today, {@code providers.jobs} in Flux's config). Plugins
 * of the suite never call that plugin directly: changing it means writing one adapter in Flux ({@link Provider}).
 * Events: {@link JobXpGainEvent}, {@link JobLevelUpEvent}.
 */
public final class Jobs {

    /** Result of joining or leaving a job. */
    public enum Change { DONE, ALREADY, NOT_ALLOWED, NO_MONEY, UNKNOWN_JOB, FAILED }

    /** What an adapter provides. */
    public interface Provider {
        String name();

        List<String> ids();

        boolean exists(String job);

        /** Display name, colours included. */
        String displayName(String job);

        int maxLevel(String job);

        int level(Player player, String job);

        double xp(Player player, String job);

        /** XP needed to go from {@code level - 1} to {@code level}. */
        double xpForLevel(String job, int level);

        boolean active(Player player, String job);

        boolean canJoin(Player player, String job);

        /** Joining price shown to the player ("" when free). */
        String joinPrice(Player player, String job);

        String leavePrice(Player player, String job);

        /** Joins, paying the price of the job plugin. */
        Change join(Player player, String job);

        Change leave(Player player, String job);

        /** Gives XP, every multiplier applied (as if earned). */
        void giveXp(Player player, String job, double amount);

        /** XP multiplier of the player from permissions and boosters (1 = none), not counting pets. */
        double xpMultiplier(Player player);

        /** Paid actions of the job, money computed for the player's level. */
        List<JobAction> actions(Player player, String job);

        /** Money given when reaching this level (NaN when none or not computable). */
        double levelUpMoney(String job, int level);

        /** Console commands run when reaching this level ({@code %player%} kept). */
        List<String> levelUpCommands(String job, int level);

        /** True when the money of the actions grows with the level. */
        boolean moneyGrowsWithLevel(String job);

        /** Description of the rewards at the player's level (lines with colours). */
        List<String> rewards(Player player, String job);

        /** Opens the job plugin's own level menu; false when there is none. */
        boolean openLevels(Player player, String job);

        /** From now on, whenever the job plugin opens its own job list, {@code instead} opens ours. */
        void replaceNativeMenus(org.bukkit.plugin.Plugin owner, java.util.function.Consumer<Player> instead);
    }

    private static volatile Provider provider;

    private Jobs() {
    }

    /** Called by Flux. */
    public static void use(Provider p) {
        provider = p;
    }

    /** True when a job plugin is in use. */
    public static boolean available() {
        return provider != null;
    }

    /** The adapter in use; check {@link #available()} first. */
    public static Provider get() {
        Provider p = provider;
        if (p == null) throw new IllegalStateException("No job plugin (Flux providers.jobs)");
        return p;
    }

    public static String providerName() {
        Provider p = provider;
        return p == null ? "none" : p.name();
    }

    /** Progress towards the next level, 0 to 1. */
    public static double progress(Player player, String job) {
        Provider p = get();
        int level = p.level(player, job);
        if (level >= p.maxLevel(job)) return 1;
        double need = p.xpForLevel(job, level + 1);
        return need <= 0 ? 1 : Math.max(0, Math.min(1, p.xp(player, job) / need));
    }
}

package fr.influencraft.flux.adapters.ecojobs;

import com.willfp.eco.util.NumberUtils;
import com.willfp.ecojobs.api.EcoJobsAPI;
import com.willfp.ecojobs.api.event.PlayerJobExpGainEvent;
import com.willfp.ecojobs.api.event.PlayerJobLevelUpEvent;
import com.willfp.ecojobs.jobs.Job;
import com.willfp.ecojobs.jobs.JobXPAccumulatorKt;
import fr.influencraft.flux.api.jobs.JobAction;
import fr.influencraft.flux.api.jobs.JobLevelUpEvent;
import fr.influencraft.flux.api.jobs.JobXpGainEvent;
import fr.influencraft.flux.api.jobs.Jobs;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * EcoJobs behind {@link Jobs}: the only class of the suite that talks to EcoJobs. Job files are read from disk
 * ({@code EcoJobs/jobs/<job>.yml}): eco keeps its own config objects, not always in sync with Bukkit's.
 */
public final class EcoJobsJobs implements Jobs.Provider, Listener {

    private final Plugin flux;
    /** Gain read before the libreforge effects (pets: job_xp_multiplier), to deduce their bonus. Main thread only. */
    private PlayerJobExpGainEvent pendingEvent;
    private double pendingAmount;

    public EcoJobsJobs(Plugin flux) {
        this.flux = flux;
    }

    private static Job job(String id) {
        return id == null ? null : com.willfp.ecojobs.jobs.Jobs.getByID(id);
    }

    @Override
    public String name() {
        return "EcoJobs";
    }

    @Override
    public List<String> ids() {
        return com.willfp.ecojobs.jobs.Jobs.values().stream().map(Job::getId).toList();
    }

    @Override
    public boolean exists(String job) {
        return job(job) != null;
    }

    @Override
    public String displayName(String job) {
        Job j = job(job);
        return j == null ? job : j.getName();
    }

    @Override
    public int maxLevel(String job) {
        Job j = job(job);
        return j == null ? 0 : j.getMaxLevel();
    }

    @Override
    public int level(Player player, String job) {
        Job j = job(job);
        return j == null || player == null ? 0 : EcoJobsAPI.getJobLevel(player, j);
    }

    @Override
    public double xp(Player player, String job) {
        Job j = job(job);
        return j == null || player == null ? 0 : EcoJobsAPI.getJobXP(player, j);
    }

    @Override
    public double xpForLevel(String job, int level) {
        Job j = job(job);
        return j == null ? 0 : j.getExpForLevel(level);
    }

    @Override
    public boolean active(Player player, String job) {
        Job j = job(job);
        return j != null && EcoJobsAPI.hasJobActive(player, j);
    }

    @Override
    public boolean canJoin(Player player, String job) {
        Job j = job(job);
        return j != null && EcoJobsAPI.canJoinJob(player, j);
    }

    @Override
    public String joinPrice(Player player, String job) {
        Job j = job(job);
        return j == null ? "" : j.getJoinPrice().getDisplay(player);
    }

    @Override
    public String leavePrice(Player player, String job) {
        Job j = job(job);
        return j == null ? "" : j.getLeavePrice().getDisplay(player);
    }

    @Override
    public Jobs.Change join(Player player, String job) {
        Job j = job(job);
        if (j == null) return Jobs.Change.UNKNOWN_JOB;
        if (EcoJobsAPI.hasJobActive(player, j)) return Jobs.Change.ALREADY;
        if (!EcoJobsAPI.canJoinJob(player, j)) return Jobs.Change.NOT_ALLOWED;
        if (!j.getJoinPrice().canAfford(player, 1.0)) return Jobs.Change.NO_MONEY;
        // the price is charged by EcoJobs' own listener: never charge it here too
        EcoJobsAPI.joinJob(player, j);
        return EcoJobsAPI.hasJobActive(player, j) ? Jobs.Change.DONE : Jobs.Change.FAILED;
    }

    @Override
    public Jobs.Change leave(Player player, String job) {
        Job j = job(job);
        if (j == null) return Jobs.Change.UNKNOWN_JOB;
        if (!EcoJobsAPI.hasJobActive(player, j)) return Jobs.Change.ALREADY;
        if (!j.getLeavePrice().canAfford(player, 1.0)) return Jobs.Change.NO_MONEY;
        EcoJobsAPI.leaveJob(player, j);
        return EcoJobsAPI.hasJobActive(player, j) ? Jobs.Change.FAILED : Jobs.Change.DONE;
    }

    @Override
    public void giveXp(Player player, String job, double amount) {
        Job j = job(job);
        if (j != null) EcoJobsAPI.giveJobExperience(player, j, amount);
    }

    @Override
    public double xpMultiplier(Player player) {
        return JobXPAccumulatorKt.getJobExperienceMultiplier(player);
    }

    @Override
    public boolean openLevels(Player player, String job) {
        Job j = job(job);
        if (j == null) return false;
        j.getLevelGUI().open(player);
        return true;
    }

    /**
     * EcoJobs reopens its own job list in hard (back button of the level menu, JobLevelGUI.kt) and resyncs its
     * config on every load, so the native list is caught when it opens (title from gui.title, cut at the first
     * placeholder) and replaced.
     */
    @Override
    public void replaceNativeMenus(Plugin owner, java.util.function.Consumer<Player> instead) {
        Plugin ecoJobs = Bukkit.getPluginManager().getPlugin("EcoJobs");
        if (ecoJobs == null) return;
        String raw = YamlConfiguration.loadConfiguration(new File(ecoJobs.getDataFolder(), "config.yml")).getString("gui.title", "Jobs");
        String plain = fr.influencraft.flux.api.text.Texts.plain(fr.influencraft.flux.api.text.Texts.colors(raw));
        String prefix = plain.indexOf('%') >= 0 ? plain.substring(0, plain.indexOf('%')) : plain;
        if (prefix.isBlank()) return;
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler(ignoreCancelled = true)
            public void onOpen(org.bukkit.event.inventory.InventoryOpenEvent event) {
                if (!(event.getPlayer() instanceof Player player)) return;
                String title = fr.influencraft.flux.api.text.Texts.plain(event.getView().title());
                if (!title.startsWith(prefix)) return;
                event.setCancelled(true);
                Bukkit.getScheduler().runTask(owner, () -> instead.accept(player));
            }
        }, owner);
    }

    // ------------------------------------------------------------------ job files

    private static YamlConfiguration file(String job) {
        Plugin ecoJobs = Bukkit.getPluginManager().getPlugin("EcoJobs");
        if (ecoJobs == null) return new YamlConfiguration();
        return YamlConfiguration.loadConfiguration(new File(ecoJobs.getDataFolder(), "jobs/" + job + ".yml"));
    }

    @Override
    public List<String> rewards(Player player, String job) {
        if (job(job) == null) return List.of();
        YamlConfiguration config = file(job);
        int level = Math.max(1, level(player, job));
        ConfigurationSection section = config.getConfigurationSection("rewards-description");
        if (section == null) return List.of();
        // highest key <= level, else the smallest one
        int best = -1, smallest = Integer.MAX_VALUE;
        for (String key : section.getKeys(false)) {
            try {
                int k = Integer.parseInt(key);
                smallest = Math.min(smallest, k);
                if (k <= level && k > best) best = k;
            } catch (NumberFormatException ignored) {
                // not a level
            }
        }
        List<String> lines = config.getStringList("rewards-description." + (best >= 0 ? best : smallest));
        for (Map<?, ?> entry : config.getMapList("level-placeholders")) {
            Object id = entry.get("id"), value = entry.get("value");
            if (id == null || value == null) continue;
            String formatted = NumberUtils.format(NumberUtils.evaluateExpression(value.toString().replace("%level%", String.valueOf(level))));
            String token = "%" + id + "%";
            lines = lines.stream().map(line -> line.replace(token, formatted)).toList();
        }
        return lines;
    }

    /**
     * Paid actions: one per block when the job lists blocks, else one per XP gain method. Money is matched to each
     * line by identical filters first, then an unfiltered effect on the same trigger.
     */
    @Override
    public List<JobAction> actions(Player player, String job) {
        if (job(job) == null) return List.of();
        YamlConfiguration config = file(job);
        int level = Math.max(1, level(player, job));
        List<Map<?, ?>> money = config.getMapList("effects").stream().filter(e -> "give_money".equals(String.valueOf(e.get("id")))).toList();
        List<JobAction> out = new ArrayList<>();
        // block grid
        Map<String, Double> xpByBlock = new java.util.LinkedHashMap<>();
        Map<String, String> triggerByBlock = new java.util.HashMap<>();
        for (Map<?, ?> method : config.getMapList("xp-gain-methods")) {
            Object xp = method.get("multiplier") != null ? method.get("multiplier") : method.get("value");
            if (xp == null || !(method.get("filters") instanceof Map<?, ?> filters) || !(filters.get("blocks") instanceof List<?> blocks)) continue;
            for (Object b : blocks) {
                xpByBlock.putIfAbsent(b.toString(), Double.parseDouble(xp.toString()));
                triggerByBlock.putIfAbsent(b.toString(), String.valueOf(method.get("trigger")));
            }
        }
        if (!xpByBlock.isEmpty()) {
            Map<String, double[]> moneyByBlock = new java.util.HashMap<>();
            double[] catchAll = null;
            for (Map<?, ?> effect : money) {
                double[] m = money(effect, level);
                if (m == null) continue;
                if (effect.get("filters") instanceof Map<?, ?> filters && filters.get("blocks") instanceof List<?> blocks) {
                    for (Object b : blocks) moneyByBlock.putIfAbsent(b.toString(), m);
                } else {
                    catchAll = m;
                }
            }
            for (Map.Entry<String, Double> e : xpByBlock.entrySet()) {
                double[] m = moneyByBlock.getOrDefault(e.getKey(), catchAll);
                out.add(new JobAction(triggerByBlock.get(e.getKey()), null, List.of(e.getKey()), e.getValue(),
                        m == null ? null : m[0], m == null ? 1 : m[1]));
            }
            return out;
        }
        // one line per method
        for (Map<?, ?> method : config.getMapList("xp-gain-methods")) {
            Object trigger = method.get("trigger");
            Object xp = method.get("multiplier") != null ? method.get("multiplier") : method.get("value");
            if (trigger == null || xp == null) continue;
            double[] m = moneyFor(money, trigger.toString(), method.get("filters"), level);
            Object label = method.get("display-label") != null ? method.get("display-label") : method.get("display-name");
            out.add(new JobAction(trigger.toString(), label == null ? null : label.toString(), List.of(),
                    Double.parseDouble(xp.toString()), m == null ? null : m[0], m == null ? 1 : m[1]));
        }
        return out;
    }

    private static double[] moneyFor(List<Map<?, ?>> effects, String trigger, Object filters, int level) {
        if (filters != null) {
            for (Map<?, ?> e : effects) {
                if (e.get("triggers") instanceof List<?> t && t.contains(trigger) && Objects.equals(e.get("filters"), filters)) {
                    double[] m = money(e, level);
                    if (m != null) return m;
                }
            }
        }
        for (Map<?, ?> e : effects) {
            if (e.get("triggers") instanceof List<?> t && t.contains(trigger) && e.get("filters") == null) {
                double[] m = money(e, level);
                if (m != null) return m;
            }
        }
        for (Map<?, ?> e : effects) {
            if (e.get("triggers") instanceof List<?> t && t.contains(trigger)) {
                double[] m = money(e, level);
                if (m != null) return m;
            }
        }
        return effects.size() == 1 ? money(effects.get(0), level) : null;
    }

    /** {amount, every} of a give_money effect at this level, null when not computable here. */
    private static double[] money(Map<?, ?> effect, int level) {
        if (!(effect.get("args") instanceof Map<?, ?> args) || args.get("amount") == null) return null;
        String amount = args.get("amount").toString().replace("%level%", String.valueOf(level));
        if (amount.contains("%")) return null; // e.g. %v% (item_points)
        double every = args.get("every") == null ? 1
                : NumberUtils.evaluateExpression(args.get("every").toString().replace("%level%", String.valueOf(level)));
        return new double[]{NumberUtils.evaluateExpression(amount), every};
    }

    @Override
    public double levelUpMoney(String job, int level) {
        double total = Double.NaN;
        for (Map<?, ?> effect : file(job).getMapList("level-up-effects")) {
            if (!"give_money".equals(String.valueOf(effect.get("id"))) || !(effect.get("args") instanceof Map<?, ?> args)
                    || args.get("amount") == null) continue;
            String formula = args.get("amount").toString().replace("%level%", String.valueOf(level));
            if (formula.contains("%")) continue;
            double amount = NumberUtils.evaluateExpression(formula);
            total = Double.isNaN(total) ? amount : total + amount;
        }
        return total;
    }

    @Override
    public List<String> levelUpCommands(String job, int level) {
        List<String> out = new ArrayList<>();
        for (Map<?, ?> effect : file(job).getMapList("level-up-effects")) {
            if ("run_command".equals(String.valueOf(effect.get("id"))) && effect.get("args") instanceof Map<?, ?> args
                    && args.get("command") != null) {
                out.add(args.get("command").toString().replace("%level%", String.valueOf(level)));
            }
        }
        return out;
    }

    @Override
    public boolean moneyGrowsWithLevel(String job) {
        for (Map<?, ?> effect : file(job).getMapList("effects")) {
            if ("give_money".equals(String.valueOf(effect.get("id"))) && effect.get("args") instanceof Map<?, ?> a
                    && a.get("amount") != null && a.get("amount").toString().contains("%level%")) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void beforeEffects(PlayerJobExpGainEvent event) {
        pendingEvent = event;
        pendingAmount = event.getAmount();
    }

    /**
     * MONITOR: the gain is final (libreforge's job_xp_multiplier runs before). EcoJobs fires the event before writing
     * the XP, so the state after the gain is rebuilt here.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGain(PlayerJobExpGainEvent event) {
        Player player = event.getPlayer();
        Job job = event.getJob();
        double multiplier = JobXPAccumulatorKt.getJobExperienceMultiplier(player);
        if (pendingEvent == event && pendingAmount > 0) multiplier *= event.getAmount() / pendingAmount;
        pendingEvent = null;
        int level = EcoJobsAPI.getJobLevel(player, job);
        double required = job.getExpForLevel(level + 1);
        double xp = EcoJobsAPI.getJobXP(player, job) + event.getAmount();
        Bukkit.getPluginManager().callEvent(new JobXpGainEvent(player, job.getId(), event.getAmount(), (multiplier - 1.0) * 100.0,
                level, xp, required));
    }

    /** EcoJobs fires it before carrying the remaining XP over: the new state is read on the next tick. */
    @EventHandler
    public void onLevelUp(PlayerJobLevelUpEvent event) {
        Player player = event.getPlayer();
        Job job = event.getJob();
        Bukkit.getScheduler().runTask(flux, () -> {
            if (!player.isOnline()) return;
            int level = EcoJobsAPI.getJobLevel(player, job);
            Bukkit.getPluginManager().callEvent(new JobLevelUpEvent(player, job.getId(), level,
                    EcoJobsAPI.getJobXP(player, job), job.getExpForLevel(level + 1)));
        });
    }
}

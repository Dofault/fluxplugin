package fr.influencraft.fluxjobs;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.economy.Economy;
import fr.influencraft.flux.api.jobs.JobLevelUpEvent;
import fr.influencraft.flux.api.jobs.Jobs;
import fr.influencraft.flux.api.text.Texts;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The chat message of a job level (2026-11-19, in place of the job plugin's own): the money of the level when it gives
 * some, then the earning coefficient (a job pays its base amount x level / 100), then the paid actions command, in the
 * look of the announcements and tournaments, in the job colours (those of the XP boss bar). Texts: {@code level-up.*}
 * of the lang file.
 * <p>
 * Levels gained one after the other (the first levels go fast) make one message: it waits a little, then tells the
 * levels from the first to the last and their money added up.
 */
final class JobLevelUpMessage implements Listener {

    /** Ticks a level waits for the next one before the message is sent. */
    private static final long GATHER_TICKS = 40L;

    private final FluxPlugin plugin;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    private static final class Pending {
        final Player player;
        final String job;
        final int from;
        int to;
        double money;
        int task = -1;

        Pending(Player player, String job, int from) {
            this.player = player;
            this.job = job;
            this.from = from;
            this.to = from;
        }
    }

    JobLevelUpMessage(FluxPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLevelUp(JobLevelUpEvent event) {
        Player p = event.player();
        String key = p.getUniqueId() + ":" + event.job();
        Pending w = pending.computeIfAbsent(key, k -> new Pending(p, event.job(), event.level()));
        w.to = Math.max(w.to, event.level());
        double money = Jobs.available() ? Jobs.get().levelUpMoney(event.job(), event.level()) : Double.NaN;
        if (!Double.isNaN(money) && money > 0) w.money += money;
        if (w.task != -1) Bukkit.getScheduler().cancelTask(w.task);
        w.task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pending.remove(key);
            send(w);
        }, GATHER_TICKS).getTaskId();
    }

    private void send(Pending w) {
        if (!w.player.isOnline()) return;
        var m = plugin.messages();
        String job = Texts.plain(Jobs.get().displayName(w.job), w.player);
        String level = w.from == w.to ? String.valueOf(w.to) : w.from + " ➜ " + w.to;
        String[] vars = {"%job%", job, "%job_id%", w.job, "%level%", level,
                "%before%", coefficient(w.from - 1), "%after%", coefficient(w.to),
                "%money%", Economy.main() == null ? String.valueOf(w.money) : Economy.main().format(w.money)};
        m.send(w.player, "level-up.header", vars);
        if (w.money > 0) m.send(w.player, "level-up.money", vars);
        m.send(w.player, "level-up.coefficient", vars);
        m.send(w.player, "level-up.actions", vars);
        m.send(w.player, "level-up.footer", vars);
    }

    private static String coefficient(int level) {
        return String.format(Locale.FRANCE, "%.2f", Math.max(0, level) / 100.0);
    }
}

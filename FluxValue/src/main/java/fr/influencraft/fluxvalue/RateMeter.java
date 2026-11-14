package fr.influencraft.fluxvalue;

import fr.influencraft.flux.api.economy.BalanceChangeEvent;
import fr.influencraft.flux.api.economy.Currency;
import fr.influencraft.flux.api.economy.Economy;
import fr.influencraft.flux.api.text.Texts;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Testing tool ({@code /fluxvalue rate}): a boss bar with the money a player would make in 2 minutes at the pace of his
 * last seconds, whatever gave it (jobs, shop, rewards), read on {@link BalanceChangeEvent} like the money action bar.
 * The bar fills up to what the economy plans at the maximum level. Before a full window has passed, the time elapsed
 * since the meter started counts instead, so a reading comes at once.
 */
final class RateMeter implements Listener {

    private static final double WINDOW_REFERENCE_SECONDS = 120;

    private static final class Meter {
        final BossBar bar;
        final long started = System.currentTimeMillis();
        final Deque<double[]> gains = new ArrayDeque<>(); // {time, amount}, also touched by the async event

        Meter(BossBar bar) {
            this.bar = bar;
        }
    }

    private final Map<UUID, Meter> meters = new ConcurrentHashMap<>();
    private final Supplier<Model> model;
    private final Function<String, String> text;

    RateMeter(Supplier<Model> model, Function<String, String> text) {
        this.model = model;
        this.text = text;
    }

    /** Turns the meter on or off for this player; true when it is now on. */
    boolean toggle(Player player) {
        Meter current = meters.remove(player.getUniqueId());
        if (current != null) {
            player.hideBossBar(current.bar);
            return false;
        }
        Meter meter = new Meter(BossBar.bossBar(Texts.colors(" "), 0, BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS));
        meters.put(player.getUniqueId(), meter);
        player.showBossBar(meter.bar);
        return true;
    }

    /** Every half second, on the main thread. */
    void tick() {
        long now = System.currentTimeMillis();
        Model m = model.get();
        double window = m.meterWindowSeconds;
        Currency currency = Economy.main();
        for (Map.Entry<UUID, Meter> e : meters.entrySet()) {
            Player player = Bukkit.getPlayer(e.getKey());
            if (player == null) continue;
            Meter meter = e.getValue();
            double sum = 0;
            synchronized (meter.gains) {
                while (!meter.gains.isEmpty() && meter.gains.peekFirst()[0] < now - window * 1000) meter.gains.pollFirst();
                for (double[] g : meter.gains) sum += g[1];
            }
            double seen = Math.max(1, Math.min(window, (now - meter.started) / 1000.0));
            double rate = sum * WINDOW_REFERENCE_SECONDS / seen;
            double planned = m.moneyPer2Minutes * m.maxLevel / 100.0;
            String title = text.apply("rate-bar")
                    .replace("%money%", format(currency, rate, player))
                    .replace("%gain%", format(currency, sum, player))
                    .replace("%seconds%", String.valueOf(Math.round(seen)));
            meter.bar.name(Texts.colors(title));
            meter.bar.progress((float) Math.max(0, Math.min(1, rate / planned)));
        }
    }

    private static String format(Currency currency, double amount, Player player) {
        return currency == null ? String.valueOf(Math.round(amount)) : Texts.placeholders(currency.format(amount), player);
    }

    @EventHandler
    public void onBalance(BalanceChangeEvent event) {
        if (!event.deposit() || !isMainCurrency(event.currency()) || event.delta() <= 0) return;
        Meter meter = meters.get(event.player());
        if (meter == null) return;
        synchronized (meter.gains) {
            meter.gains.addLast(new double[]{System.currentTimeMillis(), event.delta()});
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        meters.remove(event.getPlayer().getUniqueId());
    }

    void stop() {
        for (Map.Entry<UUID, Meter> e : meters.entrySet()) {
            Player player = Bukkit.getPlayer(e.getKey());
            if (player != null) player.hideBossBar(e.getValue().bar);
        }
        meters.clear();
    }

    /** The server's money, whatever its id ({@code vault} when another plugin keeps it). */
    private static boolean isMainCurrency(String id) {
        var main = fr.influencraft.flux.api.economy.Economy.main();
        return main != null && main.id().equalsIgnoreCase(id);
    }
}

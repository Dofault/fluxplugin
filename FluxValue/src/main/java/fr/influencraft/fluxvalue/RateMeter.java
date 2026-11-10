package fr.influencraft.fluxvalue;

import fr.influencraft.flux.api.economy.BalanceChangeEvent;
import fr.influencraft.flux.api.economy.Currency;
import fr.influencraft.flux.api.economy.Economy;
import fr.influencraft.flux.api.client.Clients;
import fr.influencraft.flux.api.items.CustomItems;
import fr.influencraft.flux.api.util.Format;
import fr.influencraft.flux.api.text.Texts;
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
 * Testing tool ({@code /fluxvalue rate}): a "hype bar" in the action bar (the textured bar of FluxAFK) with the money a player would make in 2 minutes at the pace of his
 * last seconds, whatever gave it (jobs, shop, rewards), read on {@link BalanceChangeEvent} like the money action bar.
 * The bar fills up to what the economy plans at the maximum level.
 * Without the picture (pack not zipped, Bedrock player) a bar of text is drawn instead. Before a full window has passed, the time elapsed
 * since the meter started counts instead, so a reading comes at once.
 */
final class RateMeter implements Listener {

    private static final double WINDOW_REFERENCE_SECONDS = 120;

    private static final class Meter {
        final long started = System.currentTimeMillis();
        final Deque<double[]> gains = new ArrayDeque<>(); // {time, amount}, also touched by the async event
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
        if (meters.remove(player.getUniqueId()) != null) {
            player.sendActionBar(net.kyori.adventure.text.Component.empty());
            return false;
        }
        meters.put(player.getUniqueId(), new Meter());
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
            double ratio = planned <= 0 ? 0 : Math.max(0, Math.min(1, rate / planned));
            String line = text.apply("rate-bar")
                    .replace("%bar%", bar(player, m, ratio))
                    .replace("%percent%", String.valueOf(Math.round(ratio * 100)))
                    .replace("%money%", format(currency, rate, player))
                    .replace("%gain%", format(currency, sum, player))
                    .replace("%seconds%", String.valueOf(Math.round(seen)));
            player.sendActionBar(Texts.colors(line));
        }
    }

    /** The textured bar of FluxAFK when the pack has it, else a bar of text. */
    private static String bar(Player player, Model m, double ratio) {
        String glyph = Clients.bedrock(player) ? null : CustomItems.bar(m.meterGlyph, m.meterSteps, ratio);
        return glyph != null ? "&f" + glyph : Format.bar(ratio, 1.0, m.meterSteps, "&#B376E5", "&8");
    }

    private static String format(Currency currency, double amount, Player player) {
        return currency == null ? String.valueOf(Math.round(amount)) : Texts.placeholders(currency.format(amount), player);
    }

    @EventHandler
    public void onBalance(BalanceChangeEvent event) {
        if (!event.deposit() || !event.currency().equals("vault") || event.delta() <= 0) return;
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
        for (UUID id : meters.keySet()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) player.sendActionBar(net.kyori.adventure.text.Component.empty());
        }
        meters.clear();
    }
}

package fr.influencraft.fluxpass;

import fr.influencraft.flux.api.effects.Effects;
import fr.influencraft.flux.api.list.Lists;
import fr.influencraft.flux.api.util.Sounds;
import fr.influencraft.fluxpass.Missions.Category;
import fr.influencraft.fluxpass.Missions.Mission;
import fr.influencraft.fluxpass.Missions.Variant;
import fr.influencraft.fluxpass.PassStore.Draw;
import fr.influencraft.fluxpass.PassStore.PlayerPass;
import fr.influencraft.fluxpass.PassStore.Progress;
import fr.influencraft.fluxpass.Season.Track;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The rules of the pass: points and tiers, claims, the variant drawn for each mission, mission progress. Actions are
 * counted by the effect engine of Flux ({@link Effects#count}, libreforge triggers and filters): one counter per
 * counter config of each drawn variant, bound again at each renewal.
 */
final class PassService {

    enum ClaimResult { DONE, NOT_REACHED, ALREADY, PREMIUM, NO_REWARD }

    private final FluxPass plugin;
    private final PassStore store;
    Season season;
    Missions missions;
    String premiumPermission;
    private Map<String, Draw> draws = Map.of();
    private final List<Effects.Counter> counters = new ArrayList<>();
    private boolean engineReady;

    PassService(FluxPass plugin, PassStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    void configure(Season season, Missions missions, String premiumPermission) {
        this.season = season;
        this.missions = missions;
        this.premiumPermission = premiumPermission;
        if (draws.isEmpty()) draws = new java.util.HashMap<>(store.draws());
        drawMissing(System.currentTimeMillis());
        if (engineReady) bind();
    }

    /** Called once the effect engine runs: actions start counting. */
    void engineReady() {
        engineReady = true;
        bind();
    }

    void stop() {
        for (Effects.Counter c : counters) c.close();
        counters.clear();
    }

    // ---------------------------------------------------------------- draws and counters

    /** Draws a variant for each mission without one in its current period; true when something changed. */
    boolean drawMissing(long now) {
        boolean changed = false;
        for (Category c : missions.all()) {
            long period = c.period(now);
            for (Mission m : c.missions()) {
                String key = c.id() + "/" + m.id();
                Draw d = draws.get(key);
                if (d != null && d.period() == period && variant(m, d.variant()) != null) continue;
                Variant v = m.variants().size() == 1 ? m.variants().get(0)
                        : m.variants().get(ThreadLocalRandom.current().nextInt(m.variants().size()));
                Draw drawn = new Draw(period, v.id());
                draws.put(key, drawn);
                store.saveDraw(c.id(), m.id(), drawn);
                changed = true;
            }
        }
        return changed;
    }

    /** Draws a new variant for every mission of a category now (admin), progress of the period kept per variant. */
    void redraw(Category c) {
        for (Mission m : c.missions()) draws.remove(c.id() + "/" + m.id());
        drawMissing(System.currentTimeMillis());
        bind();
    }

    /** Checked every few seconds: a category renewed draws its variants again and counts them. */
    void tick() {
        if (drawMissing(System.currentTimeMillis())) {
            bind();
            for (Player p : Bukkit.getOnlinePlayers()) invalidate(p);
        }
    }

    private void bind() {
        stop();
        for (Category c : missions.all()) {
            for (Mission m : c.missions()) {
                Variant v = drawn(c, m);
                if (v == null) continue;
                int i = 0;
                for (Map<?, ?> config : v.counters()) {
                    Effects.Counter counter = Effects.count(plugin, c.id() + "_" + m.id() + "_" + v.id() + "_" + i++, config,
                            (player, amount) -> count(player, c, m, v, amount));
                    if (counter != null) counters.add(counter);
                    else plugin.getLogger().warning("Mission " + c.id() + "/" + m.id() + "/" + v.id() + ": counter not valid " + config);
                }
            }
        }
    }

    Variant drawn(Category c, Mission m) {
        Draw d = draws.get(c.id() + "/" + m.id());
        return d == null ? null : variant(m, d.variant());
    }

    private static Variant variant(Mission m, String id) {
        for (Variant v : m.variants()) if (v.id().equals(id)) return v;
        return null;
    }

    // ---------------------------------------------------------------- missions

    boolean open(Category c, long now) {
        return now >= c.opens(now) && season.running(now);
    }

    boolean premium(Player player) {
        return player.hasPermission(premiumPermission);
    }

    /** Progress of a mission for the current period and variant (a stale row starts again at 0). */
    Progress progress(PlayerPass pp, Category c, Mission m) {
        Variant v = drawn(c, m);
        long period = c.period(System.currentTimeMillis());
        String key = c.id() + "/" + m.id();
        Progress pr = pp.progress.get(key);
        String variant = v == null ? "" : v.id();
        if (pr == null || pr.period != period || !pr.variant.equals(variant)) {
            pr = new Progress(period, variant, 0, false);
            pp.progress.put(key, pr);
        }
        return pr;
    }

    private void count(Player player, Category c, Mission m, Variant v, double amount) {
        if (amount <= 0 || !open(c, System.currentTimeMillis())) return;
        if (m.premium() && !premium(player)) return;
        PlayerPass pp = store.cached(player.getUniqueId());
        if (pp == null || drawn(c, m) != v) return;
        Progress pr = progress(pp, c, m);
        if (pr.done) return;
        pr.value = Math.min(v.amount(), pr.value + amount);
        pr.dirty = true;
        if (pr.value >= v.amount()) complete(player, pp, c, m, pr);
        Lists.invalidate(player, PassMenu.MISSIONS);
    }

    /** Marks a mission done and pays its points. */
    void complete(Player player, PlayerPass pp, Category c, Mission m, Progress pr) {
        Variant v = drawn(c, m);
        pr.done = true;
        pr.value = v == null ? pr.value : v.amount();
        pr.dirty = true;
        store.saveProgress(pp);
        plugin.messages().send(player, "mission-done", "%mission%", v != null && !v.name().isEmpty() ? v.name() : m.name(),
                "%points%", String.valueOf(m.points()));
        Sounds.cue(player, "complete");
        addPoints(player, pp, m.points());
        invalidate(player);
    }

    // ---------------------------------------------------------------- tiers

    /** Adds points (or none: applies tiers that need 0) and climbs every tier they pay for. */
    void addPoints(Player player, PlayerPass pp, int points) {
        pp.points = Math.max(0, pp.points + points);
        int before = pp.tier;
        while (pp.tier < season.tiers && pp.points >= season.required(pp.tier + 1)) {
            pp.points -= season.required(pp.tier + 1);
            pp.tier++;
        }
        store.saveTier(pp, season.id);
        if (player != null && pp.tier > before && before > 0) {
            plugin.messages().send(player, "tier-up", "%tier%", String.valueOf(pp.tier));
            Sounds.cue(player, "complete");
        }
        if (player != null) invalidate(player);
    }

    void setTier(PlayerPass pp, int tier) {
        pp.tier = Math.max(0, Math.min(season.tiers, tier));
        pp.points = 0;
        store.saveTier(pp, season.id);
    }

    /** Points still needed for the next tier (0 at the last tier). */
    int required(PlayerPass pp) {
        return pp.tier >= season.tiers ? 0 : season.required(pp.tier + 1);
    }

    ClaimResult claim(Player player, int tier, Track track) {
        PlayerPass pp = store.cached(player.getUniqueId());
        Season.Reward reward = season.reward(tier, track);
        if (pp == null || reward == null) return ClaimResult.NO_REWARD;
        if (pp.tier < tier) return ClaimResult.NOT_REACHED;
        if (track == Track.PREMIUM && !premium(player)) return ClaimResult.PREMIUM;
        if (pp.claims.contains(PlayerPass.claimKey(tier, track))) return ClaimResult.ALREADY;
        if (!store.claim(pp, season.id, tier, track)) return ClaimResult.ALREADY;
        for (String command : reward.commands()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("%player%", player.getName()));
        }
        invalidate(player);
        return ClaimResult.DONE;
    }

    /** States of a tier for a player: claimed, waiting to be claimed, locked. */
    boolean claimed(PlayerPass pp, int tier, Track track) {
        return pp.claims.contains(PlayerPass.claimKey(tier, track));
    }

    PlayerPass player(UUID uuid) {
        return store.cached(uuid);
    }

    void invalidate(Player player) {
        PassMenu.invalidate(player);
    }
}

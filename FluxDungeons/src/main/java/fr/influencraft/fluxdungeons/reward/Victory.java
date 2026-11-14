package fr.influencraft.fluxdungeons.reward;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.economy.Currency;
import fr.influencraft.flux.api.items.CustomItems;
import fr.influencraft.flux.api.pets.Companions;
import fr.influencraft.flux.api.reward.Rewards;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.Format;
import fr.influencraft.fluxdungeons.dungeon.DungeonDefinition;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A won run, once the party is back in the rpg lobby (2026-11-14): a title, fireworks around each player for a few
 * seconds (they never hurt anybody), the summary in the chat (loot, tokens of the run), the boosters of the dungeon's
 * job ({@code reward.boosters} by difficulty, vanilla items: kept in {@code /recompenses} until he is in the semi-rp
 * world) and the boss's pet: adopted on the first victory, its card on the next ones.
 */
public final class Victory implements Listener {

    private static final String TAG = "fluxdungeons_firework";
    private static final Color[] COLOURS = {Color.fromRGB(0xFFD94D), Color.fromRGB(0xB376E5), Color.fromRGB(0xE060A0),
            Color.fromRGB(0x2ECF7B), Color.fromRGB(0x2BC4E8), Color.fromRGB(0xF0A800)};

    private final FluxPlugin plugin;
    private final DungeonTokens tokens;
    private int fireworks = 8;
    private long petDelay = 70;

    public Victory(FluxPlugin plugin, DungeonTokens tokens) {
        this.plugin = plugin;
        this.tokens = tokens;
    }

    public void reload(org.bukkit.configuration.ConfigurationSection c) {
        fireworks = c == null ? 8 : Math.max(0, c.getInt("fireworks", 8));
        petDelay = c == null ? 70 : Math.max(0, c.getLong("pet-delay-ticks", 70));
    }

    public void celebrate(DungeonDefinition def, int level, long seconds, List<Player> winners,
                          Map<UUID, List<String>> loot, Map<UUID, Integer> earned) {
        for (Player p : winners) {
            if (!p.isOnline()) continue;
            p.showTitle(Title.title(Texts.parse(raw("run.victory")),
                    Texts.parse(raw("run.victory-sub", "%time%", Format.duration(seconds))),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(4), Duration.ofMillis(800))));
            fireworks(p);
            List<String> found = loot.getOrDefault(p.getUniqueId(), List.of());
            plugin.messages().send(p, found.isEmpty() ? "run.loot-none" : "run.loot", "%items%", String.join("&7, ", found));
            int got = earned.getOrDefault(p.getUniqueId(), 0);
            Currency money = tokens.currency();
            if (got > 0 && money != null) plugin.messages().send(p, "run.tokens", "%amount%", money.format(got));
            boosters(p, def, level);
            if (!def.pet().isEmpty()) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> pet(p, def), petDelay);
            }
        }
    }

    private void boosters(Player p, DungeonDefinition def, int level) {
        int n = def.boosters(level);
        if (def.job().isEmpty() || n <= 0) return;
        ItemStack booster = CustomItems.item("fluxpets:booster_metier_" + def.job(), p);
        if (booster == null) return;
        booster.setAmount(n);
        String name = booster.hasItemMeta() && booster.getItemMeta().hasDisplayName()
                ? Texts.ampersand(booster.getItemMeta().displayName()) : def.job();
        Rewards.deliver(p, "dungeons", raw("run.boosters-label", "%dungeon%", def.name()), List.of(booster));
        plugin.messages().send(p, "run.boosters", "%amount%", String.valueOf(n), "%booster%", name);
    }

    /** The boss's pet: adopted the first time (FluxPets shows it), its card afterwards. */
    private void pet(Player p, DungeonDefinition def) {
        if (!p.isOnline() || !Companions.available()) return;
        String name = Companions.name(def.pet());
        if (name == null) return;
        if (Companions.adopt(p, def.pet())) {
            plugin.messages().send(p, "run.pet-adopted", "%pet%", name);
            return;
        }
        ItemStack card = Companions.card(def.pet());
        if (card == null) return;
        Rewards.deliver(p, "dungeons", raw("run.boosters-label", "%dungeon%", def.name()), List.of(card));
        plugin.messages().send(p, "run.pet-card", "%pet%", name);
    }

    /** Rockets around the player, one after the other, exploding right away. */
    private void fireworks(Player p) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < fireworks; i++) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline()) return;
                Location at = p.getLocation().add(r.nextDouble(-4, 4), r.nextDouble(2.5, 5), r.nextDouble(-4, 4));
                Firework fw = p.getWorld().spawn(at, Firework.class, f -> {
                    FireworkMeta meta = f.getFireworkMeta();
                    meta.addEffect(FireworkEffect.builder()
                            .with(FireworkEffect.Type.values()[r.nextInt(FireworkEffect.Type.values().length - 1)])
                            .withColor(COLOURS[r.nextInt(COLOURS.length)], COLOURS[r.nextInt(COLOURS.length)])
                            .withFade(Color.WHITE).flicker(r.nextBoolean()).trail(true).build());
                    meta.setPower(0);
                    f.setFireworkMeta(meta);
                    f.addScoreboardTag(TAG);
                    f.setPersistent(false);
                });
                Bukkit.getScheduler().runTaskLater(plugin, fw::detonate, 2L + r.nextInt(6));
            }, 4L + i * 6L);
        }
    }

    /** Our fireworks hurt nobody. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Firework fw && fw.getScoreboardTags().contains(TAG)) e.setCancelled(true);
    }

    private String raw(String key, String... vars) {
        return plugin.messages().raw(key, vars);
    }
}

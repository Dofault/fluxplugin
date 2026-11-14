package fr.influencraft.fluxdungeons.rpg;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.economy.Currency;
import fr.influencraft.flux.api.economy.Economy;
import fr.influencraft.flux.api.economy.Origin;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.items.CustomItems;
import fr.influencraft.flux.api.items.ItemCatalog;
import fr.influencraft.flux.api.items.ItemUses;
import fr.influencraft.flux.api.reward.Rewards;
import fr.influencraft.flux.api.trade.TradeWindow;
import fr.influencraft.flux.api.universe.Universes;
import fr.influencraft.flux.api.util.Format;
import fr.influencraft.flux.api.util.Sounds;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Potions of the rpg (2026-11-14): items {@code fluxrpg:potion_<id>} of {@code rpg/potions.yml}, a right click gives back
 * a share of the drinker's life or mana (one potion of a kind every {@code cooldown} seconds). Bought with dungeon
 * tokens at the potion merchant of the rpg lobby ({@code /potions}, a Typewriter NPC runs it), each one from its rpg
 * level: the bigger ones open as the character grows.
 */
public final class Potions {

    record Potion(String id, String kind, double share, int level, double price, String name, String icon) {
    }

    private final FluxPlugin plugin;
    private final RpgPlayers players;
    private final CombatEngine combat;
    private final Supplier<String> currency;
    private final Map<String, Potion> potions = new LinkedHashMap<>();
    private final Map<UUID, Map<String, Long>> used = new HashMap<>();
    private int cooldown = 8;

    Potions(FluxPlugin plugin, RpgPlayers players, CombatEngine combat, Supplier<String> currency) {
        this.plugin = plugin;
        this.players = players;
        this.combat = combat;
        this.currency = currency;
        ItemUses.register(plugin, RpgItems.NAMESPACE + ":potion_*", (p, item, id) -> drink(p, id));
    }

    /** Reads rpg/potions.yml (written from the jar when missing). */
    void load() {
        File file = new File(plugin.getDataFolder(), "rpg/potions.yml");
        if (!file.exists() && plugin.getResource("rpg/potions.yml") != null) plugin.saveResource("rpg/potions.yml", false);
        YamlConfiguration c = YamlConfiguration.loadConfiguration(file);
        cooldown = Math.max(0, c.getInt("cooldown", 8));
        potions.clear();
        ConfigurationSection all = c.getConfigurationSection("potions");
        if (all == null) return;
        for (String id : all.getKeys(false)) {
            ConfigurationSection p = all.getConfigurationSection(id);
            if (p == null) continue;
            potions.put(id, new Potion(id, p.getString("type", "health"), p.getDouble("share", 0.25),
                    Math.max(1, p.getInt("level", 1)), p.getDouble("price", 1), p.getString("name", id),
                    p.getString("icon", "itemsadder:fluxdonjon:potion_soin_1")));
        }
    }

    /** The potions as rpg items (called each time the rpg items are made again). */
    void addTo(ItemCatalog catalog) {
        for (Potion p : potions.values()) {
            YamlConfiguration y = new YamlConfiguration();
            y.set("material", p.icon());
            y.set("name", p.name());
            String what = plugin.messages().raw(p.kind().equals("mana") ? "potions.gives-mana" : "potions.gives-health",
                    "%share%", Format.decimal(p.share() * 100));
            y.set("lore", Lore.create().description(what)
                    .kv(plugin.messages().raw("potions.level"), p.level())
                    .kv(plugin.messages().raw("potions.cooldown"), cooldown + " s")
                    .right(plugin.messages().raw("potions.drink")).build());
            y.set("universe", RpgWorld.UNIVERSE);
            catalog.add("potion_" + p.id(), y);
        }
    }

    // ---------------------------------------------------------------- drinking

    private boolean drink(Player player, String itemId) {
        Potion potion = potions.get(itemId.substring(itemId.indexOf(':') + 1).replaceFirst("^potion_", ""));
        RpgPlayer r = players.get(player);
        if (potion == null || r == null || r.stats() == null) return false;
        long now = System.currentTimeMillis();
        Map<String, Long> mine = used.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
        long ready = mine.getOrDefault(potion.kind(), 0L) + cooldown * 1000L;
        if (now < ready) {
            plugin.messages().send(player, "potions.wait", "%seconds%", String.valueOf((ready - now + 999) / 1000));
            Sounds.cue(player, "error");
            return false;
        }
        if (potion.kind().equals("mana")) {
            double max = r.stats().get("max_mana");
            if (r.mana() >= max) {
                plugin.messages().send(player, "potions.full-mana");
                return false;
            }
            r.mana(r.mana() + max * potion.share());
            player.getWorld().spawnParticle(Particle.WITCH, player.getLocation().add(0, 1, 0), 18, 0.3, 0.5, 0.3, 0);
        } else {
            double max = combat.maxHealth(player);
            if (combat.health(player) >= max) {
                plugin.messages().send(player, "potions.full-health");
                return false;
            }
            combat.heal(player, max * potion.share(), true);
            player.getWorld().spawnParticle(Particle.HEART, player.getLocation().add(0, 1.8, 0), 4, 0.3, 0.2, 0.3, 0);
        }
        mine.put(potion.kind(), now);
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_GENERIC_DRINK, 0.8f, 1.2f);
        return true;
    }

    // ---------------------------------------------------------------- shop

    /** The merchant's window: every potion, the ones of a higher level shown but refused. */
    public void shop(Player player) {
        if (!RpgWorld.UNIVERSE.equals(Universes.of(player))) {
            plugin.messages().send(player, "potions.not-rpg");
            return;
        }
        List<TradeWindow.Offer> offers = new ArrayList<>();
        for (Potion p : potions.values()) offers.add(offer(p));
        if (!TradeWindow.buy(player, null, plugin.messages().raw("potions.title"), offers)) {
            plugin.messages().send(player, "potions.closed");
        }
    }

    private TradeWindow.Offer offer(Potion potion) {
        return new TradeWindow.Offer() {
            public ItemStack item() {
                ItemStack item = CustomItems.item(RpgItems.NAMESPACE + ":potion_" + potion.id());
                return item == null ? new ItemStack(org.bukkit.Material.POTION) : item;
            }

            public double price() {
                return potion.price();
            }

            public String currency() {
                return currency.get();
            }

            public void describe(Player viewer, Lore lore) {
                RpgPlayer r = players.get(viewer);
                int level = r == null ? 0 : r.level();
                if (level >= potion.level()) lore.ok(plugin.messages().raw("potions.level-ok"));
                else lore.ko(plugin.messages().raw("potions.level-needed", "%level%", String.valueOf(potion.level())));
            }

            public boolean buy(Player player, int bundles) {
                RpgPlayer r = players.get(player);
                if (r == null || r.level() < potion.level()) {
                    plugin.messages().send(player, "potions.level-needed", "%level%", String.valueOf(potion.level()));
                    return false;
                }
                Currency money = Economy.get(currency.get());
                double cost = potion.price() * bundles;
                if (money == null || !money.take(player, cost, Origin.of(plugin, "potion").detail(potion.name()))) {
                    plugin.messages().send(player, "potions.no-tokens");
                    return false;
                }
                ItemStack item = item();
                item.setAmount(bundles);
                Rewards.deliver(player, "dungeons", plugin.messages().raw("potions.title"), List.of(item));
                Sounds.cue(player, "success");
                return true;
            }
        };
    }

    void forget(UUID player) {
        used.remove(player);
    }
}

package fr.influencraft.fluxenchant;

import fr.influencraft.flux.api.items.CustomItems;
import fr.influencraft.flux.api.util.Sounds;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Mystery books: a right click on a book item (any custom item recognised by Flux, the ItemsAdder
 * {@code influlivres:livre_<rarity>} books on our server) consumes it and gives a random enchanted book of its rarity
 * (EcoEnchants command, as before), with a sound and a firework of the rarity's colour bursting on the player, seen by
 * everyone and harmless. The items themselves are unchanged, only their behaviour moved here.
 */
final class MysteryBooks implements Listener {

    private static final NamespacedKey FIREWORK = new NamespacedKey("fluxenchant", "book_firework");

    private final FluxEnchant plugin;
    /** custom item id -> rarity id */
    private final Map<String, String> books = new HashMap<>();

    MysteryBooks(FluxEnchant plugin) {
        this.plugin = plugin;
    }

    void load(ConfigurationSection section) {
        books.clear();
        ConfigurationSection items = section == null ? null : section.getConfigurationSection("items");
        if (items == null) return;
        for (String id : items.getKeys(false)) books.put(id.toLowerCase(Locale.ROOT), items.getString(id, "").toLowerCase(Locale.ROOT));
    }

    @EventHandler(priority = EventPriority.LOW)
    public void open(PlayerInteractEvent ev) {
        if (ev.getAction() != Action.RIGHT_CLICK_AIR && ev.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = ev.getItem();
        if (item == null || books.isEmpty() || !plugin.getConfig().getBoolean("mystery-books.enabled", true)) return;
        String id = CustomItems.id(item);
        String rarity = id == null ? null : books.get(id);
        if (rarity == null) return;
        ev.setCancelled(true);
        Player player = ev.getPlayer();
        EquipmentSlot hand = ev.getHand() == null ? EquipmentSlot.HAND : ev.getHand();
        ItemStack held = player.getInventory().getItem(hand);
        if (held == null || held.isEmpty()) return;
        held.setAmount(held.getAmount() - 1);
        player.getInventory().setItem(hand, held.getAmount() <= 0 ? null : held);

        String command = plugin.getConfig().getString("mystery-books.command", "ecoenchants giverandombook %player% %rarity%")
                .replace("%player%", player.getName()).replace("%rarity%", rarity);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        Sounds.play(player, plugin.getConfig().getString("mystery-books.sound", "@complete"));
        if (plugin.getConfig().getBoolean("mystery-books.firework", true)) firework(player, plugin.rarities().color(rarity));
    }

    /** A firework bursting right above the player, in the rarity's colour. */
    private void firework(Player player, Color color) {
        Location at = player.getLocation().add(0, 2.2, 0);
        Firework fw = player.getWorld().spawn(at, Firework.class, f -> {
            FireworkMeta meta = f.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE).withColor(color)
                    .withFade(Color.WHITE).withFlicker().withTrail().build());
            meta.setPower(0);
            f.setFireworkMeta(meta);
            f.getPersistentDataContainer().set(FIREWORK, PersistentDataType.BYTE, (byte) 1);
        });
        Bukkit.getScheduler().runTask(plugin, fw::detonate);
    }

    /** The book's firework never hurts anyone. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void harmless(EntityDamageByEntityEvent ev) {
        if (ev.getDamager() instanceof Firework fw && fw.getPersistentDataContainer().has(FIREWORK)) ev.setCancelled(true);
    }
}

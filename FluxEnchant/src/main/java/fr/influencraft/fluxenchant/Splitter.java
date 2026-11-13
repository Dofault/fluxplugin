package fr.influencraft.fluxenchant;

import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.Experience;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Splits an enchanted book into one book per enchantment ({@code /enchantsplit}, or a right click on an enchanting
 * table with the book in hand: messages then go to the action bar). Each book made costs a blank book and
 * {@code splitter.experience-cost} experience points (never in creative). Nothing is taken unless everything fits:
 * blank books, experience and room for every book are checked first.
 */
final class Splitter implements Listener {

    static final String PERMISSION = "fluxenchant.split";
    static final String PERMISSION_TABLE = "fluxenchant.split.table";

    private final FluxEnchant plugin;

    Splitter(FluxEnchant plugin) {
        this.plugin = plugin;
    }

    boolean command(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "players-only", false);
            return true;
        }
        if (!player.hasPermission(PERMISSION)) {
            send(player, "split.no-permission", false);
            return true;
        }
        split(player, false);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTableClick(PlayerInteractEvent event) {
        // fired once per hand: only the main hand counts
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getClickedBlock() == null || event.getClickedBlock().getType() != Material.ENCHANTING_TABLE) return;
        Player player = event.getPlayer();
        if (player.getInventory().getItemInMainHand().getType() != Material.ENCHANTED_BOOK) return;
        if (!plugin.getConfig().getBoolean("splitter.enchanting-table", true)) return;
        event.setCancelled(true);
        if (!player.hasPermission(PERMISSION_TABLE)) {
            send(player, "split.no-permission", true);
            return;
        }
        split(player, true);
    }

    private void split(Player player, boolean actionBar) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType() != Material.ENCHANTED_BOOK || !(held.getItemMeta() instanceof EnchantmentStorageMeta meta)) {
            send(player, "split.not-enchanted-book", actionBar);
            return;
        }
        Map<Enchantment, Integer> stored = meta.getStoredEnchants();
        int count = stored.size();
        String n = String.valueOf(count);
        if (count < 2) {
            send(player, "split.single", actionBar);
            return;
        }
        if (!player.getInventory().containsAtLeast(new ItemStack(Material.BOOK), count)) {
            send(player, "split.no-books", actionBar, "%books%", n);
            return;
        }
        List<ItemStack> results = new ArrayList<>(count);
        stored.forEach((enchantment, level) -> results.add(book(enchantment, level)));
        int missing = missingRoom(player, results, count);
        if (missing > 0) {
            send(player, "split.no-room", actionBar, "%missing%", String.valueOf(missing));
            return;
        }
        long cost = player.getGameMode() == GameMode.CREATIVE ? 0
                : (long) Math.max(0, plugin.getConfig().getInt("splitter.experience-cost", 100)) * count;
        if (Experience.total(player) < cost) {
            send(player, "split.no-xp", actionBar, "%cost%", String.valueOf(cost));
            return;
        }
        if (cost > 0) Experience.take(player, cost);
        player.getInventory().setItemInMainHand(null);
        player.getInventory().removeItem(new ItemStack(Material.BOOK, count));
        // room was checked above; the ground is only a fallback if the inventory changed in between
        fr.influencraft.flux.api.inventory.Inventories.giveOrDrop(player, results);
        send(player, "split.done", actionBar, "%books%", n, "%cost%", String.valueOf(cost));
    }

    /** Dry run on a copy of the storage slots, without the held book and the blank books: books that would not fit. */
    private static int missingRoom(Player player, List<ItemStack> results, int blankBooks) {
        Inventory copy = Bukkit.createInventory(null, 36);
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int slot = 0; slot < storage.length; slot++) {
            if (storage[slot] != null) copy.setItem(slot, storage[slot].clone());
        }
        copy.setItem(player.getInventory().getHeldItemSlot(), null);
        copy.removeItem(new ItemStack(Material.BOOK, blankBooks));
        int missing = 0;
        for (ItemStack book : results) missing += copy.addItem(book.clone()).size();
        return missing;
    }

    private static ItemStack book(Enchantment enchantment, int level) {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        book.editMeta(EnchantmentStorageMeta.class, m -> m.addStoredEnchant(enchantment, level, true));
        return book;
    }

    private void send(CommandSender to, String key, boolean actionBar, String... vars) {
        if (actionBar && to instanceof Player player) {
            String raw = plugin.messages().raw(key, vars);
            if (!raw.isEmpty()) player.sendActionBar(Texts.colors(raw.startsWith("!") ? raw.substring(1) : raw));
        } else {
            plugin.messages().send(to, key, vars);
        }
    }
}

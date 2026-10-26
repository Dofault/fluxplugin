package fr.influencraft.fluxtools.modules;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
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
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Module {@code enchant-splitter}: turns an enchanted book with several enchantments into one book per
 * enchantment. Each resulting book costs one blank book from the inventory and {@code experience-cost} XP points
 * (never charged in creative). Triggered by /enchantsplit or, when {@code enchanting-table} is on, by a right click
 * on an enchanting table with the book in hand (messages then go to the action bar).
 *
 * <p>Nothing is taken unless everything fits: blank books, XP and inventory room for every resulting book are all
 * checked first, so no book is ever dropped on the ground. Settings and MiniMessage texts are read from
 * {@code modules.enchant-splitter} on every use, so a config reload applies at once.</p>
 */
final class EnchantSplitter implements Listener, TabExecutor {

    static final String PERMISSION_COMMAND = "influenchantsplitter.command";
    static final String PERMISSION_TABLE = "influenchantsplitter.table";
    static final String PERMISSION_RELOAD = "influenchantsplitter.reload";

    private final JavaPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    EnchantSplitter(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission(PERMISSION_RELOAD)) {
                send(sender, "reload-no-permission", false);
                return true;
            }
            plugin.reloadConfig();
            send(sender, "reloaded", false);
            return true;
        }
        if (!(sender instanceof Player player)) {
            send(sender, "player-only", false);
            return true;
        }
        if (!player.hasPermission(PERMISSION_COMMAND)) {
            send(player, "no-permission", false);
            return true;
        }
        split(player, false);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && sender.hasPermission(PERMISSION_RELOAD) && "reload".startsWith(args[0].toLowerCase())) {
            return List.of("reload");
        }
        return List.of();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTableClick(PlayerInteractEvent event) {
        // fired once per hand: only the main hand counts
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getClickedBlock() == null || event.getClickedBlock().getType() != Material.ENCHANTING_TABLE) return;
        Player player = event.getPlayer();
        if (player.getInventory().getItemInMainHand().getType() != Material.ENCHANTED_BOOK) return;
        if (!settings().getBoolean("enchanting-table", true)) return;

        event.setCancelled(true);
        if (!player.hasPermission(PERMISSION_TABLE)) {
            send(player, "no-permission", true);
            return;
        }
        split(player, true);
    }

    private void split(Player player, boolean actionBar) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.getType() != Material.ENCHANTED_BOOK || !(held.getItemMeta() instanceof EnchantmentStorageMeta meta)) {
            send(player, "not-enchanted-book", actionBar);
            return;
        }
        Map<Enchantment, Integer> stored = meta.getStoredEnchants();
        int count = stored.size();
        TagResolver enchants = Placeholder.unparsed("enchants", String.valueOf(count));
        TagResolver books = Placeholder.unparsed("books", String.valueOf(count));
        if (count < 2) {
            send(player, "not-enough-enchants", actionBar, enchants);
            return;
        }
        if (!player.getInventory().containsAtLeast(new ItemStack(Material.BOOK), count)) {
            send(player, "not-enough-books", actionBar, books, enchants);
            return;
        }

        List<ItemStack> results = new ArrayList<>(count);
        stored.forEach((enchantment, level) -> results.add(singleBook(enchantment, level)));
        int missing = missingRoom(player, results, count);
        if (missing > 0) {
            send(player, "not-enough-space", actionBar, Placeholder.unparsed("missing", String.valueOf(missing)), enchants);
            return;
        }

        int cost = player.getGameMode() == GameMode.CREATIVE ? 0 : Math.max(0, settings().getInt("experience-cost", 100)) * count;
        TagResolver costTag = Placeholder.unparsed("cost", String.valueOf(cost));
        int points = player.calculateTotalExperiencePoints();
        if (points < cost) {
            send(player, "not-enough-xp", actionBar, costTag, enchants);
            return;
        }

        if (cost > 0) player.setExperienceLevelAndProgress(points - cost);
        player.getInventory().setItemInMainHand(null);
        player.getInventory().removeItem(new ItemStack(Material.BOOK, count));
        for (ItemStack book : results) {
            // room was checked above; the ground is only a fallback if the inventory changed in between
            player.getInventory().addItem(book).values()
                    .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left, drop -> drop.setPickupDelay(0)));
        }
        send(player, "success", actionBar, costTag, enchants, books);
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

    private static ItemStack singleBook(Enchantment enchantment, int level) {
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        book.editMeta(EnchantmentStorageMeta.class, meta -> meta.addStoredEnchant(enchantment, level, true));
        return book;
    }

    private ConfigurationSection settings() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("modules.enchant-splitter");
        return section != null ? section : plugin.getConfig().createSection("modules.enchant-splitter");
    }

    /** Sends {@code messages.<key>} (MiniMessage, empty = not sent) to the chat, or to the action bar. */
    private void send(CommandSender sender, String key, boolean actionBar, TagResolver... placeholders) {
        String raw = settings().getString("messages." + key, "");
        if (raw.isEmpty()) return;
        var message = miniMessage.deserialize(raw, placeholders);
        if (actionBar && sender instanceof Player player) {
            player.sendActionBar(message);
        } else {
            sender.sendMessage(message);
        }
    }
}

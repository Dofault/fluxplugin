package fr.influencraft.fluxenchant;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.fluxtools.api.EnchantMastery;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.ServicePriority;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * Enchantments of the suite: weight of the custom (EcoEnchants) enchantments per item, and mastery enchantments on
 * the crate tools of FluxTools ({@link Mastery}), mystery books ({@link MysteryBooks}). Later: the suite's own
 * enchantments.
 */
public final class FluxEnchant extends FluxPlugin {

    private final Rarities rarities = new Rarities();
    private final MysteryBooks books = new MysteryBooks(this);
    private Mastery mastery;

    @Override
    protected void enable() {
        saveDefaultConfig();
        int count = loadRarities();
        getLogger().info(count + " enchantment(s) read in EcoEnchants.");
        Bukkit.getPluginManager().registerEvents(new Weight(this), this);
        Bukkit.getPluginManager().registerEvents(books, this);
        if (Bukkit.getPluginManager().getPlugin("FluxTools") != null) {
            mastery = new Mastery(this);
            Bukkit.getServicesManager().register(EnchantMastery.class, mastery, this, ServicePriority.Normal);
        } else {
            getLogger().warning("FluxTools missing: no mastery enchantments, only the weight.");
        }
        command("fluxenchant").description("Enchantments administration.").permission("fluxenchant.admin")
                .usage(messages().raw("admin-usage")).executor(this::onCommand)
                .completer((sender, args) -> args.length == 1 ? List.of("reload", "info") : List.of()).register();
    }

    @Override
    protected void disable() {
        Bukkit.getServicesManager().unregisterAll(this);
    }

    /** Enchantments and rarity colours from EcoEnchants, mystery books from the config. */
    private int loadRarities() {
        File folder = new File(getDataFolder().getParentFile(), getConfig().getString("ecoenchants-folder", "EcoEnchants/enchants"));
        rarities.loadColors(new File(folder.getParentFile(), "rarity.yml"));
        books.load(getConfig().getConfigurationSection("mystery-books"));
        return rarities.load(folder, getConfig().getConfigurationSection("weights"));
    }

    Rarities rarities() {
        return rarities;
    }

    /** Weight an item can carry ({@code capacity.<kind>}, else {@code capacity.default}). */
    int capacity(ItemStack item) {
        return capacity(item == null ? null : item.getType());
    }

    /** Capacity of a crate tool (its description only knows its meta). */
    int capacityOf(ItemMeta meta) {
        return getConfig().getInt("capacity.tools", getConfig().getInt("capacity.default", 15));
    }

    private int capacity(Material type) {
        int def = getConfig().getInt("capacity.default", 15);
        if (type == null) return def;
        String name = type.name();
        boolean armor = name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE") || name.endsWith("_LEGGINGS")
                || name.endsWith("_BOOTS") || type == Material.ELYTRA;
        return getConfig().getInt("capacity." + (armor ? "armor" : "tools"), def);
    }

    private boolean onCommand(CommandSender sender, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "reload" -> {
                reload();
                messages().send(sender, "reloaded", "%count%", String.valueOf(loadRarities()));
            }
            case "info" -> {
                if (!(sender instanceof Player p)) return true;
                ItemStack hand = p.getInventory().getItemInMainHand();
                ItemMeta meta = hand.getItemMeta();
                int weight = meta == null ? 0 : mastery != null ? mastery.weight(meta) : new Weight(this).of(hand.getEnchantments());
                messages().send(p, "info", "%weight%", String.valueOf(weight), "%capacity%", String.valueOf(capacity(hand)));
            }
            default -> messages().send(sender, "admin-usage");
        }
        return true;
    }
}

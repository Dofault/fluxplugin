package fr.influencraft.fluxshop.shop;

import fr.influencraft.fluxshop.managers.LangManager;
import fr.influencraft.fluxshop.managers.PriceManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Éléments communs au menu d'achat et à l'éditeur des coffres magasins : icônes configurables
 * ({@code shop-menu.*}, matériau + {@code custom_model_data}), prix formatés, nom d'objet.
 */
public final class MenuItems {

    private final JavaPlugin plugin;
    private final LangManager lang;
    public MenuItems(JavaPlugin plugin, LangManager lang) {
        this.plugin = plugin;
        this.lang = lang;
    }

    /** Icon {@code shop-menu.<key>} of config.yml, or the given fallback. */
    public ItemStack icon(String key, Material fallback, int fallbackModelData, int amount) {
        return iconAt("shop-menu." + key, fallback, fallbackModelData, amount);
    }

    /** Icon at a full config.yml path ({@code material}, {@code custom-model-data}), or the given fallback. */
    public ItemStack iconAt(String path, Material fallback, int fallbackModelData, int amount) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection(path);
        Material material = fallback;
        int modelData = fallbackModelData;
        if (section != null) {
            Material configured = Material.matchMaterial(section.getString("material", fallback.name()));
            material = configured == null || !configured.isItem() ? fallback : configured;
            modelData = section.getInt("custom-model-data", fallbackModelData);
        }
        ItemStack stack = new ItemStack(material, Math.max(1, Math.min(amount, material.getMaxStackSize())));
        if (modelData > 0) {
            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                meta.setCustomModelData(modelData);
                stack.setItemMeta(meta);
            }
        }
        return stack;
    }

    public static ItemStack decorate(ItemStack stack, String name, List<String> lore) {
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(fr.influencraft.flux.api.text.Texts.wrapLines(lore));
            stack.setItemMeta(meta);
        }
        return stack;
    }


    /** Montant formaté suivi de l'icône BitBlock. */
    public String price(double amount) {
        // raw amount: item texts are not run through placeholders, the glyph is part of shop-menu-price
        return lang.get("shop-menu-price", Map.of("price", fr.influencraft.flux.api.util.Format.decimal(PriceManager.roundMoney(amount))));
    }

    /**
     * L'objet vendu tel qu'affiché dans un menu : sa copie, avec des lignes ajoutées sous son
     * propre lore. Un objet vanilla garde son nom d'origine, rendu par le client dans sa langue.
     */
    public static ItemStack withExtraLore(ShopEntry entry, List<String> extra) {
        ItemStack stack = entry.getItem();
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.addAll(extra);
            meta.setLore(fr.influencraft.flux.api.text.Texts.wrapLines(lore));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** Nom de l'objet : son nom custom s'il en a un, sinon le nom vanilla traduit côté client. */
    public static Component name(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
            return meta.displayName();
        }
        if (meta != null && meta.hasItemName()) {
            return meta.itemName();
        }
        return Component.translatable(item.getType().translationKey()).color(NamedTextColor.WHITE);
    }

    /** Pour les journaux console : matériau, et nom en clair pour un objet façonné. */
    public static String describe(ShopEntry entry) {
        ItemStack item = entry.getItem();
        if (entry.isPlain()) {
            return item.getType().name();
        }
        return item.getType().name() + " \"" + PlainTextComponentSerializer.plainText().serialize(name(item)) + "\"";
    }

    /** Envoie un message dont {@code %item%} est remplacé par le nom de l'objet. */
    public static void send(Player player, String message, ItemStack item) {
        int index = message.indexOf("%item%");
        if (index < 0) {
            player.sendMessage(message);
            return;
        }
        player.sendMessage(fr.influencraft.flux.api.text.Texts.colors(message.substring(0, index))
                .append(name(item))
                .append(fr.influencraft.flux.api.text.Texts.colors(message.substring(index + "%item%".length()))));
    }
}

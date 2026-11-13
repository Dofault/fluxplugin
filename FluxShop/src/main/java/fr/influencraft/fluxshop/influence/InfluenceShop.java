package fr.influencraft.fluxshop.influence;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.MenuView;
import fr.influencraft.flux.api.economy.Currency;
import fr.influencraft.flux.api.economy.Economy;
import fr.influencraft.flux.api.economy.Origin;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.items.CustomItems;
import fr.influencraft.flux.api.list.ListEntry;
import fr.influencraft.flux.api.list.Lists;
import fr.influencraft.flux.api.reward.Rewards;
import fr.influencraft.flux.api.util.Sounds;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Influence shop ({@code /shop}, menu {@code boutique}), merged from InfluShop on 2026-11-13. Its catalogue
 * ({@code influence.yml}) names each item by its suite id ({@code fluxcrate:key_tendance}, {@code customfishing:bone_rod},
 * {@code fluxsorter:sorter}...): the shop window shows the real item, never a copy that could drift from it. An entry
 * may instead run console commands (an item bound to its owner, a service), with an icon, a name and a description.
 * Paid in the currency of the catalogue (Influence), items delivered by Flux (inventory, else /recompenses).
 */
public final class InfluenceShop {

    public static final String MENU = "boutique";
    public static final String LIST = "boutique";

    /** One thing for sale. {@code item} null: a command entry with {@code icon}, {@code name}, {@code description}. */
    record Entry(String id, String item, int amount, double price, List<String> commands, String icon, String name,
                 List<String> description) {
    }

    private final FluxPlugin plugin;
    private final File file;
    private volatile Map<String, Entry> entries = Map.of();
    private volatile String currency = "influence";

    public InfluenceShop(FluxPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "influence.yml");
    }

    public void register() {
        load();
        Lists.register(plugin, LIST, query -> entries(query.viewer()));
    }

    public void load() {
        if (!file.exists()) plugin.saveResource("influence.yml", false);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        currency = y.getString("currency", "influence");
        Map<String, Entry> read = new LinkedHashMap<>();
        ConfigurationSection s = y.getConfigurationSection("entries");
        if (s != null) {
            for (String id : s.getKeys(false)) {
                ConfigurationSection e = s.getConfigurationSection(id);
                if (e == null) continue;
                String item = e.getString("item");
                List<String> commands = e.getStringList("commands");
                if ((item == null || item.isBlank()) && commands.isEmpty()) {
                    plugin.getLogger().warning("influence.yml: " + id + " has neither item nor commands, skipped.");
                    continue;
                }
                read.put(id, new Entry(id, item == null || item.isBlank() ? null : item, Math.max(1, e.getInt("amount", 1)),
                        Math.max(0, e.getDouble("price")), commands, e.getString("icon", "CHEST"), e.getString("name", id),
                        e.getStringList("description")));
            }
        }
        if (read.isEmpty()) plugin.getLogger().warning("influence.yml: the Influence shop sells nothing.");
        entries = read;
        Lists.invalidate(LIST);
    }

    private Currency money() {
        Currency c = Economy.get(currency);
        return c != null ? c : Economy.main();
    }

    /** What the window shows of an entry: the item itself, or the icon of a command entry. */
    private ItemStack shown(Entry e, Player viewer) {
        if (e.item() != null) {
            ItemStack item = CustomItems.item(e.item(), viewer);
            if (item == null) item = Items.of(e.item(), viewer);
            if (item != null) item.setAmount(Math.min(item.getMaxStackSize(), e.amount()));
            return item;
        }
        return Items.of(e.icon(), e.name(), Lore.create().description(e.description()).build(), viewer);
    }

    private List<ListEntry> entries(Player viewer) {
        List<ListEntry> out = new ArrayList<>();
        Currency money = money();
        double balance = money.balance(viewer);
        for (Entry e : entries.values()) {
            ItemStack item = shown(e, viewer);
            if (item == null) continue;
            List<String> extra = Lore.create()
                    .kv(text("influence.price"), money.format(e.price()))
                    .left(text("influence.buy"))
                    .build();
            if (balance < e.price()) extra = Lore.create().kv(text("influence.price"), money.format(e.price()))
                    .ko(text("influence.too-poor")).build();
            var meta = item.getItemMeta();
            if (meta != null) {
                List<net.kyori.adventure.text.Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
                if (!lore.isEmpty()) lore.add(net.kyori.adventure.text.Component.empty());
                lore.addAll(fr.influencraft.flux.api.text.Texts.lore(extra, viewer));
                meta.lore(lore);
                item.setItemMeta(meta);
            }
            out.add(ListEntry.of(e.id()).item(item).set("confirm", text("influence.confirm", "%price%", money.format(e.price()))));
        }
        return out;
    }

    /** {@code [shop] influence-buy <id>} (second click of the confirmation). */
    public void buy(Player player, MenuView view, String id) {
        Entry e = entries.get(id);
        if (e == null) return;
        Currency money = money();
        if (money.balance(player) < e.price()) {
            plugin.messages().send(player, "influence.not-enough", "%price%", money.format(e.price()));
            Sounds.cue(player, "error");
            return;
        }
        List<ItemStack> items = new ArrayList<>();
        if (e.item() != null) {
            ItemStack first = CustomItems.item(e.item(), player);
            if (first == null) first = Items.of(e.item(), player);
            if (first == null) {
                plugin.getLogger().warning("Influence shop: item " + e.item() + " of " + id + " unknown, nothing sold.");
                plugin.messages().send(player, "influence.unavailable");
                Sounds.cue(player, "error");
                return;
            }
            int left = e.amount();
            for (ItemStack stack = first; left > 0 && stack != null; stack = left > 0 ? CustomItems.item(e.item(), player) : null) {
                int size = Math.min(left, stack.getMaxStackSize());
                stack.setAmount(size);
                left -= size;
                items.add(stack);
            }
        }
        if (e.price() > 0 && !money.take(player, e.price(), Origin.of(plugin, "influence").detail(id).verb("bought").notifyPlayer())) {
            plugin.messages().send(player, "influence.failed");
            Sounds.cue(player, "error");
            return;
        }
        if (!items.isEmpty()) Rewards.deliver(player, "shop", "", items);
        for (String cmd : e.commands()) {
            String line = cmd.replace("%player%", player.getName()).replace("%uuid%", player.getUniqueId().toString());
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line.startsWith("/") ? line.substring(1) : line);
        }
        plugin.getLogger().info("[Influence shop] " + player.getName() + " bought " + id + " for " + e.price());
        Sounds.cue(player, "success");
        Lists.invalidate(player, LIST);
        if (view != null) view.refreshButtons();
    }

    public void open(Player player) {
        Lists.reset(player, LIST);
        FluxApi.open(player, MENU);
    }

    private String text(String key, String... vars) {
        return plugin.messages().raw(key, vars);
    }
}

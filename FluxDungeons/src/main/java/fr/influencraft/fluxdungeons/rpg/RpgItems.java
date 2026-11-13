package fr.influencraft.fluxdungeons.rpg;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.items.CustomItems;
import fr.influencraft.flux.api.items.ItemCatalog;
import fr.influencraft.fluxdungeons.rpg.core.Equipment;
import fr.influencraft.fluxdungeons.rpg.core.StatRegistry;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Items of the rpg universe as Flux items {@code fluxrpg:<id>}, built from equipment.yml: look from its {@code look}
 * section, marked for the rpg universe, description written from the numbers (stats, class, level, rarity, set).
 * Items already handed out follow every change of equipment.yml (Flux brings them up to date).
 */
public final class RpgItems {

    public static final String NAMESPACE = "fluxrpg";

    private final FluxPlugin plugin;
    private final ItemCatalog catalog;
    private Equipment equipment;
    private StatRegistry stats;

    RpgItems(FluxPlugin plugin) {
        this.plugin = plugin;
        this.catalog = ItemCatalog.create(plugin, NAMESPACE);
    }

    void load(RpgConfig.Set set) {
        equipment = set.equipment();
        stats = set.stats();
        catalog.clear();
        for (Equipment.Item item : equipment.items()) {
            YamlConfiguration y = new YamlConfiguration();
            Map<String, Object> look = item.look();
            y.set("material", look.get("material"));
            Object model = look.get("model");
            if (model != null && !String.valueOf(model).isBlank()) y.set("model", model);
            y.set("name", "&" + item.rarity().color() + look.getOrDefault("name", item.id()));
            y.set("lore", lore(item));
            y.set("universe", RpgWorld.UNIVERSE);
            y.set("unbreakable", true);
            y.set("flags", List.of("HIDE_UNBREAKABLE", "HIDE_ATTRIBUTES", "HIDE_ADDITIONAL_TOOLTIP", "HIDE_DYE"));
            catalog.add(item.id(), y);
        }
    }

    /** Description in the suite's style (Flux Lore): description, stats, classes, level, rarity, set bonuses. */
    private List<String> lore(Equipment.Item item) {
        fr.influencraft.flux.api.inventory.Lore lore = fr.influencraft.flux.api.inventory.Lore.create();
        if (!item.description().isEmpty()) lore.description(item.description());
        item.stats().forEach((stat, value) -> lore.kv(statName(stat), statValue(stat, value)));
        if (!item.classes().isEmpty()) {
            List<String> names = new ArrayList<>();
            for (String c : item.classes()) names.add(plugin.messages().raw("classes." + c + ".name"));
            lore.kv(plugin.messages().raw("item.classes"), String.join(", ", names));
        }
        lore.kv(plugin.messages().raw("item.level"), item.level());
        lore.kv(plugin.messages().raw("item.rarity"), "&" + item.rarity().color() + plugin.messages().raw("rarities." + item.rarity().id()));
        if (item.set() != null) {
            Equipment.ItemSet set = equipment.set(item.set());
            lore.info(plugin.messages().raw("item.set", "%set%", plugin.messages().raw("sets." + set.id())));
            set.bonuses().forEach((pieces, bonus) -> {
                List<String> parts = new ArrayList<>();
                bonus.forEach((stat, v) -> parts.add(statValue(stat, v) + " " + statName(stat)));
                lore.item(plugin.messages().raw("item.set-bonus", "%pieces%", String.valueOf(pieces)) + " &f" + String.join(", ", parts));
            });
        }
        return lore.build();
    }

    /** Name of a stat in the server's language (templated ones: "damage against" + family). */
    public String statName(String stat) {
        int dot = stat.indexOf('.');
        if (dot > 0) {
            String head = stat.substring(0, dot), tail = stat.substring(dot + 1);
            String group = head.equals("damage_vs") ? "families" : "elements";
            return plugin.messages().raw("stats." + head, "%what%", plugin.messages().raw(group + "." + tail));
        }
        return plugin.messages().raw("stats." + stat);
    }

    public String statValue(String stat, double value) {
        StatRegistry.Def d = stats == null ? null : stats.get(stat);
        String sign = value >= 0 ? "+" : "-";
        double v = Math.abs(value);
        if (d != null && d.kind() == StatRegistry.Kind.PERCENT) return sign + trim(v * 100) + " %";
        return sign + trim(v);
    }

    private static String trim(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }

    /** The rpg item id of a stack ({@code roc_helmet}), null when it is not one. */
    public static String idOf(ItemStack item) {
        String id = item == null || item.isEmpty() ? null : CustomItems.id(item);
        return id != null && id.startsWith(NAMESPACE + ":") ? id.substring(NAMESPACE.length() + 1) : null;
    }

    public ItemStack make(String id) {
        return CustomItems.item(NAMESPACE + ":" + id);
    }

    public Equipment equipment() {
        return equipment;
    }
}

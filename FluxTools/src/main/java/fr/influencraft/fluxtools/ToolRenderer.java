package fr.influencraft.fluxtools;

import fr.influencraft.flux.api.items.CustomItems;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.Format;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Makes a tool and rewrites its name, description and enchantments. */
final class ToolRenderer {

    /** Enchantments added by the player at the anvil, never removed by a rewrite. */
    static final List<String> BOOKS = List.of("incassable", "soulbound");

    private static final Set<String> WEAPONS = Set.of("epee", "arc", "masse");

    /** Fingerprint of the current config ({@link FluxTools}), written on every rendered description. */
    static int version;

    private final FluxTools plugin;

    ToolRenderer(FluxTools plugin) {
        this.plugin = plugin;
    }

    ItemStack create(Tool tool) {
        ItemStack item = ItemStack.of(tool.material);
        ToolState state = new ToolState();
        state.tool = tool.id;
        state.uid = UUID.randomUUID().toString();
        item.editMeta(meta -> {
            if (tool.durability > 0 && meta instanceof Damageable d) d.setMaxDamage(tool.durability);
            state.write(meta);
            enchant(meta, tool, state);
            decorate(meta, tool, state);
        });
        return item;
    }

    /** Writes the state in the item and its description; {@code enchants}: also its enchantments. */
    void update(ItemStack item, Tool tool, ToolState state, boolean enchants) {
        item.editMeta(meta -> {
            state.write(meta);
            if (enchants) enchant(meta, tool, state);
            decorate(meta, tool, state);
        });
    }

    private static void enchant(ItemMeta meta, Tool tool, ToolState state) {
        for (Enchantment ench : List.copyOf(meta.getEnchants().keySet())) {
            if (!BOOKS.contains(ench.getKey().getKey())) meta.removeEnchant(ench);
        }
        for (Map.Entry<String, Integer> e : tool.fixedEnchantments.entrySet()) {
            Enchantment ench = Tool.enchantment(e.getKey());
            if (ench != null && e.getValue() > 0) meta.addEnchant(ench, e.getValue(), true);
        }
        int level = tool.level(state.xp);
        for (String id : tool.levelledEnchantments.keySet()) {
            Enchantment ench = Tool.enchantment(id);
            int value = tool.enchantmentLevel(id, level);
            if (ench != null && value > 0) meta.addEnchant(ench, value, true);
        }
        // mastery enchantments (FluxEnchant), on top
        var mastery = fr.influencraft.fluxtools.api.Tools.mastery();
        if (mastery != null) mastery.apply(meta);
    }

    private void decorate(ItemMeta meta, Tool tool, ToolState state) {
        ToolState.markRendered(meta, version);
        // written on every rendering: tools already handed out get the model at their next rewrite
        meta.setItemModel(tool.model);
        int level = tool.level(state.xp);
        meta.customName(itemText(tool.hasLevels()
                ? tool.name + plugin.messages().raw("lore-name-level", "%level%", String.valueOf(level)) : tool.name));
        List<Component> lore = new ArrayList<>();
        if (tool.hasLevels()) {
            // the usual display (vanilla and EcoEnchants) is hidden: one line per enchantment, with its level
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            lore.addAll(enchantmentLines(meta, tool, level));
        }
        for (String line : tool.description) lore.add(itemText(line));
        if (!tool.repairable) lore.add(itemText(plugin.messages().raw("lore-unrepairable")));
        if (tool.hasLevels()) {
            lore.add(Component.empty());
            lore.addAll(levelLines(tool, state, level));
        }
        meta.lore(Texts.wrapLore(lore));
    }

    /** « Efficacité III/VII »: level unlocked / maximum, books and mastery enchantments below. */
    private static List<Component> enchantmentLines(ItemMeta meta, Tool tool, int level) {
        List<Component> lines = new ArrayList<>();
        for (String id : tool.levelledEnchantments.keySet()) {
            Enchantment ench = Tool.enchantment(id);
            if (ench != null) lines.add(enchantmentLine(ench, " " + Format.roman(tool.enchantmentLevel(id, level))
                    + "/" + Format.roman(tool.maxEnchantmentLevel(id))));
        }
        for (Enchantment ench : meta.getEnchants().keySet()) {
            if (BOOKS.contains(ench.getKey().getKey())) lines.add(enchantmentLine(ench, ""));
        }
        var mastery = fr.influencraft.fluxtools.api.Tools.mastery();
        if (mastery != null) for (String line : mastery.lines(meta)) lines.add(itemText(line));
        return lines;
    }

    private static Component enchantmentLine(Enchantment ench, String end) {
        return Component.text().color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                .append(Component.text().color(NamedTextColor.GRAY).append(ench.description()))
                .append(Texts.colors("&7" + end)).build();
    }

    /** Level, XP bar towards the next level, what the tool did. */
    private List<Component> levelLines(Tool tool, ToolState state, int level) {
        List<Component> lines = new ArrayList<>();
        boolean max = level >= tool.maxLevel();
        double done = state.xp - tool.threshold(level);
        double needed = max ? 0 : tool.threshold(level + 1) - tool.threshold(level);
        lines.add(itemText(max ? plugin.messages().raw("lore-level-max")
                : plugin.messages().raw("lore-level", "%level%", String.valueOf(level), "%max%", String.valueOf(tool.maxLevel()))));
        double ratio = max ? 1 : done / needed;
        String bar = bar(ratio);
        lines.add(itemText(max ? "&f" + bar : plugin.messages().raw("lore-xp", "%bar%", bar,
                "%xp%", Format.number(done), "%needed%", Format.number(needed))));
        boolean weapon = WEAPONS.contains(tool.family);
        lines.add(itemText(plugin.messages().raw(weapon ? "lore-kills" : "lore-blocks",
                "%count%", Format.number(weapon ? state.kills : state.blocks))));
        return lines;
    }

    /** The textured bar when the pack has it, else a bar of text. */
    private String bar(double ratio) {
        String glyph = CustomItems.bar(plugin.barGlyph(), plugin.barSteps(), ratio);
        return glyph != null ? glyph : Format.bar(ratio, 1, plugin.barSteps(), "&#FF4D6D", "&8");
    }

    /** Text of an item: without the italics Minecraft adds by default. */
    private static Component itemText(String text) {
        return Texts.colors(text).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}

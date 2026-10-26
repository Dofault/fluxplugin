package fr.influencraft.flux.menu;

import fr.influencraft.flux.api.list.ListEntry;
import fr.influencraft.flux.api.list.Lists;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the lists of a menu ({@link ListSpec}) into buttons for one render: the elements of the current page,
 * each drawn from the template with its {@code %entry_<key>%} values, and {@code material: entry} pointing at the
 * element's own item (served to {@link fr.influencraft.flux.ItemFactory} by {@link #entryItem}).
 */
public final class ListRenderer {

    /** Material prefix of an element's own item: {@code __fluxentry:<player>:<list>:<index>}. */
    public static final String ENTRY_MATERIAL = "__fluxentry:";
    private static final Pattern ENTRY = Pattern.compile("%entry_([a-z0-9_]+)%");
    private static final Map<String, ItemStack> ITEMS = new ConcurrentHashMap<>();
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
    private static Logger logger;

    private ListRenderer() {
    }

    public static void init(Logger log) {
        logger = log;
    }

    public static ItemStack entryItem(String key) {
        return ITEMS.get(key);
    }

    public static void forget(Player player) {
        String prefix = player.getUniqueId() + ":";
        ITEMS.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /** Buttons of every list in {@code specs} for this player (empty lists show their {@code empty} button). */
    public static List<MenuButton> buttons(Player player, Menu menu, List<ListSpec> specs) {
        if (specs.isEmpty()) return List.of();
        List<MenuButton> out = new ArrayList<>();
        for (ListSpec spec : specs) {
            List<ListEntry> entries = Lists.entries(player, spec.id());
            Lists.State state = Lists.state(player, spec.id());
            int size = spec.pageSize();
            int pages = Math.max(1, (entries.size() + size - 1) / size);
            int page = Math.min(state.page(), pages - 1);
            if (entries.isEmpty()) {
                if (spec.empty() != null) {
                    MenuButton b = build(spec, menu, spec.id() + "#empty", spec.empty(), Function.identity(), spec.slots()[0], false);
                    if (b != null) out.add(b);
                }
                continue;
            }
            for (int i = 0; i < size; i++) {
                int index = page * size + i;
                if (index >= entries.size()) break;
                ListEntry entry = entries.get(index);
                String itemKey = player.getUniqueId() + ":" + spec.id() + ":" + i;
                if (entry.item() != null) ITEMS.put(itemKey, entry.item());
                else ITEMS.remove(itemKey);
                Map<String, String> values = entry.values();
                int number = index + 1;
                Function<String, String> fill = text -> {
                    if (text.indexOf("%entry_") < 0) return text;
                    Matcher m = ENTRY.matcher(text);
                    StringBuilder sb = new StringBuilder();
                    while (m.find()) {
                        String key = m.group(1);
                        String v = key.equals("index") ? String.valueOf(number) : values.get(key);
                        m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : v));
                    }
                    m.appendTail(sb);
                    return sb.toString();
                };
                MenuButton b = build(spec, menu, spec.id() + "#" + i, spec.template(), fill, spec.slots()[i], entry.item() != null);
                if (b != null) out.add(withEntryItem(b, itemKey));
            }
        }
        return out;
    }

    private static MenuButton build(ListSpec spec, Menu menu, String key, ConfigurationSection template,
                                    Function<String, String> fill, int slot, boolean hasItem) {
        MemoryConfiguration copy = new MemoryConfiguration();
        copyInto(template, copy, fill);
        copy.set("slots", null);
        copy.set("slot", String.valueOf(slot));
        if (!copy.isSet("material")) copy.set("material", hasItem ? "entry" : "PAPER");
        return MenuLoader.templateButton(key, copy, menu.totalSlots(), menu.topSize(), menu.inventoryMode(), spec.where(),
                msg -> {
                    if (logger != null && WARNED.add(msg)) logger.warning(msg);
                });
    }

    /** {@code material: entry} becomes the element's item. */
    private static MenuButton withEntryItem(MenuButton b, String itemKey) {
        if (!b.material().equalsIgnoreCase("entry")) return b;
        return new MenuButton(b.id(), ENTRY_MATERIAL + itemKey, b.name(), b.lore(), b.amount(), b.slots(), b.priority(),
                b.glow(), b.customModelData(), b.itemModel(), b.flags(), b.hideTooltip(), b.confirm(),
                b.viewRequirements(), b.clickRequirements(), b.denyActions(), b.actions());
    }

    private static void copyInto(ConfigurationSection from, ConfigurationSection to, Function<String, String> fill) {
        for (String key : from.getKeys(false)) {
            Object v = from.get(key);
            if (v instanceof ConfigurationSection section) {
                copyInto(section, to.createSection(key), fill);
            } else if (v instanceof List<?> list) {
                List<Object> filled = new ArrayList<>(list.size());
                for (Object o : list) filled.add(o instanceof String s ? fill.apply(s) : o);
                to.set(key, filled);
            } else if (v instanceof String s) {
                to.set(key, fill.apply(s));
            } else {
                to.set(key, v);
            }
        }
    }
}

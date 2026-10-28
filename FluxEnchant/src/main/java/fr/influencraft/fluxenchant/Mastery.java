package fr.influencraft.fluxenchant;

import fr.influencraft.fluxtools.api.EnchantMastery;
import fr.influencraft.fluxtools.api.ToolInfo;
import fr.influencraft.fluxtools.api.Tools;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Mastery enchantments of the crate tools (hook {@link EnchantMastery} of FluxTools). A book added at the anvil to a
 * tool at its last level is written in the tool ({@code fluxenchant:mastery}, « id:max:xp » entries in order) at
 * level 0, inactive; the tool's XP then raises the last book added up to the book's level, and only a mastered tool
 * takes the next book. Weight: every custom enchantment of the tool, mastery ones included, inactive or not.
 */
final class Mastery implements EnchantMastery {

    record Entry(String id, int max, double xp) {
    }

    static final NamespacedKey KEY = new NamespacedKey("fluxenchant", "mastery");

    private final FluxEnchant plugin;
    private final Map<UUID, Long> lastRefusal = new HashMap<>();

    Mastery(FluxEnchant plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ entries

    static List<Entry> read(ItemMeta meta) {
        List<Entry> out = new ArrayList<>();
        String raw = meta == null ? null : meta.getPersistentDataContainer().get(KEY, PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) return out;
        for (String part : raw.split(";")) {
            String[] f = part.split(":");
            if (f.length != 3) continue;
            try {
                out.add(new Entry(f[0], Integer.parseInt(f[1]), Double.parseDouble(f[2])));
            } catch (NumberFormatException ignored) {
                // unreadable entry: dropped
            }
        }
        return out;
    }

    static void write(ItemMeta meta, List<Entry> entries) {
        List<String> parts = new ArrayList<>();
        for (Entry e : entries) parts.add(e.id() + ":" + e.max() + ":" + e.xp());
        meta.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, String.join(";", parts));
    }

    /** XP to master a book on this tool. */
    private double total(ToolInfo info) {
        return Math.max(1, info.maxXp() * plugin.getConfig().getDouble("mastery.xp-factor", 1.5));
    }

    /** Level reached with this XP (0 to max), the XP being spread as (k / max) ^ curve of the total. */
    private int level(Entry e, double total) {
        double curve = plugin.getConfig().getDouble("mastery.curve", 1.6);
        int level = 0;
        for (int k = 1; k <= e.max(); k++) if (e.xp() >= total * Math.pow((double) k / e.max(), curve)) level = k;
        return level;
    }

    /** Level of an entry without the tool info: from the total stored at the anvil (fraction of the XP). */
    private int level(Entry e, ItemMeta meta) {
        Double total = meta.getPersistentDataContainer().get(new NamespacedKey("fluxenchant", "total"), PersistentDataType.DOUBLE);
        return level(e, total == null ? Double.MAX_VALUE : total);
    }

    private static Enchantment enchantment(String id) {
        return Registry.ENCHANTMENT.get(NamespacedKey.minecraft(id));
    }

    // ------------------------------------------------------------------ weight

    /** Weight of a tool: its custom enchantments, plus the mastery books not active yet. */
    int weight(ItemMeta meta) {
        Set<String> ids = new LinkedHashSet<>();
        for (Enchantment e : meta.getEnchants().keySet()) ids.add(e.getKey().getKey());
        for (Entry e : read(meta)) ids.add(e.id());
        int w = 0;
        for (String id : ids) {
            Rarities.Info i = plugin.rarities().info(id);
            if (i != null) w += i.weight();
        }
        return w;
    }

    // ------------------------------------------------------------------ hook

    @Override
    public ItemStack anvil(Player player, ItemStack tool, ItemStack book, ToolInfo info) {
        if (!(book.getItemMeta() instanceof EnchantmentStorageMeta stored) || stored.getStoredEnchants().size() != 1) {
            return refuse(player, "refused-book");
        }
        Map.Entry<Enchantment, Integer> added = stored.getStoredEnchants().entrySet().iterator().next();
        Rarities.Info rarity = plugin.rarities().info(added.getKey());
        if (rarity == null) return refuse(player, "refused-book");
        List<String> tiers = plugin.getConfig().getStringList("mastery.tiers");
        if (!tiers.contains(info.tier().toLowerCase(Locale.ROOT))) return refuse(player, "refused-tier");
        if (!info.maxed()) return refuse(player, "refused-level", "%level%", String.valueOf(info.maxLevel()));
        ItemMeta meta = tool.getItemMeta();
        List<Entry> entries = read(meta);
        double total = total(info);
        for (Entry e : entries) {
            if (level(e, total) < e.max()) return refuse(player, "refused-mastering", "%enchant%", name(e.id()));
            if (e.id().equals(rarity.id())) return refuse(player, "refused-present");
        }
        if (meta.hasEnchant(added.getKey())) return refuse(player, "refused-present");
        if (!added.getKey().canEnchantItem(new ItemStack(tool.getType()))) return refuse(player, "refused-target");
        int weight = weight(meta) + rarity.weight();
        int capacity = plugin.capacity(tool);
        if (weight > capacity) {
            return refuse(player, "refused-weight", "%weight%", String.valueOf(weight), "%capacity%", String.valueOf(capacity));
        }
        ItemStack result = tool.clone();
        result.editMeta(m -> {
            List<Entry> next = new ArrayList<>(read(m));
            next.add(new Entry(rarity.id(), Math.max(1, added.getValue()), 0));
            write(m, next);
            m.getPersistentDataContainer().set(new NamespacedKey("fluxenchant", "total"), PersistentDataType.DOUBLE, total);
        });
        Tools.refresh(result);
        return result;
    }

    @Override
    public boolean gain(Player player, ItemStack tool, ToolInfo info, double xp) {
        ItemMeta meta = tool.getItemMeta();
        List<Entry> entries = read(meta);
        double total = total(info);
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            int before = level(e, total);
            if (before >= e.max()) continue;
            Entry now = new Entry(e.id(), e.max(), Math.min(total, e.xp() + xp));
            entries.set(i, now);
            int after = level(now, total);
            tool.editMeta(m -> {
                write(m, entries);
                m.getPersistentDataContainer().set(new NamespacedKey("fluxenchant", "total"), PersistentDataType.DOUBLE, total);
            });
            if (after == before) return false;
            plugin.messages().send(player, "level-up", "%enchant%", name(e.id()), "%level%", roman(after));
            if (after >= e.max()) plugin.messages().send(player, "mastered", "%enchant%", name(e.id()));
            fr.influencraft.flux.api.util.Sounds.cue(player, "complete");
            return true;
        }
        return false;
    }

    @Override
    public void apply(ItemMeta meta) {
        for (Entry e : read(meta)) {
            Enchantment ench = enchantment(e.id());
            if (ench == null) continue;
            int level = level(e, meta);
            if (level > 0) meta.addEnchant(ench, level, true);
            else meta.removeEnchant(ench);
        }
    }

    @Override
    public List<String> lines(ItemMeta meta) {
        List<String> out = new ArrayList<>();
        List<Entry> entries = read(meta);
        Double total = meta.getPersistentDataContainer().get(new NamespacedKey("fluxenchant", "total"), PersistentDataType.DOUBLE);
        for (Entry e : entries) {
            int level = level(e, meta);
            if (level >= e.max()) {
                out.add(plugin.messages().raw("line-mastered", "%enchant%", name(e.id()), "%level%", roman(level)));
            } else {
                int percent = total == null ? 0 : (int) Math.floor(100 * e.xp() / total);
                out.add(plugin.messages().raw("line", "%enchant%", name(e.id()), "%level%", roman(level),
                        "%max%", roman(e.max()), "%percent%", String.valueOf(percent)));
            }
        }
        int weight = weight(meta);
        if (weight > 0 || !entries.isEmpty()) {
            out.add(plugin.messages().raw("line-weight", "%weight%", String.valueOf(weight),
                    "%capacity%", String.valueOf(plugin.capacityOf(meta))));
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private static String roman(int n) {
        return n <= 0 ? "0" : fr.influencraft.flux.api.util.Format.roman(n);
    }

    /** Display name of an enchantment without its colours nor its leading « ✦ ». */
    String name(String id) {
        Rarities.Info i = plugin.rarities().info(id);
        String raw = i == null ? id : i.name();
        String plain = fr.influencraft.flux.api.text.Texts.plain(fr.influencraft.flux.api.text.Texts.colors(raw)).trim();
        return plain.startsWith("✦") ? plain.substring(1).trim() : plain;
    }

    /** Refusal at the anvil: null result, reason in the action bar (at most every 2 s). */
    private ItemStack refuse(Player player, String key, String... vars) {
        long now = System.currentTimeMillis();
        Long last = lastRefusal.get(player.getUniqueId());
        if (last == null || now - last > 2000) {
            lastRefusal.put(player.getUniqueId(), now);
            plugin.messages().actionBar(player, key, vars);
        }
        return null;
    }
}

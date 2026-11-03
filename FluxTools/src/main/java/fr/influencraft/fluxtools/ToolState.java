package fr.influencraft.fluxtools;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Progress of a tool, written in the item itself: the tool keeps its history when traded, sold or stored. The keys
 * keep the namespace and the (French) names of the former FluxItems plugin: tools already handed out stay recognised.
 */
final class ToolState {

    private static final String NAMESPACE = "fluxitems";
    private static final NamespacedKey TOOL = new NamespacedKey(NAMESPACE, "outil");
    private static final NamespacedKey UID = new NamespacedKey(NAMESPACE, "uid");
    private static final NamespacedKey BLOCKS = new NamespacedKey(NAMESPACE, "blocs");
    private static final NamespacedKey KILLS = new NamespacedKey(NAMESPACE, "tues");
    private static final NamespacedKey XP = new NamespacedKey(NAMESPACE, "xp");
    private static final NamespacedKey RENDERED = new NamespacedKey(NAMESPACE, "rendu");

    String tool;
    String uid;
    long blocks;
    long kills;
    /** Total tool XP; the level comes from it (thresholds of the tool). */
    double xp;

    /** Tool id carried by the item, or null. */
    static String toolId(ItemStack item) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(TOOL, PersistentDataType.STRING);
    }

    static ToolState read(ItemStack item) {
        String id = toolId(item);
        if (id == null) return null;
        PersistentDataContainer c = item.getItemMeta().getPersistentDataContainer();
        ToolState s = new ToolState();
        s.tool = id;
        s.uid = c.getOrDefault(UID, PersistentDataType.STRING, "");
        s.blocks = c.getOrDefault(BLOCKS, PersistentDataType.LONG, 0L);
        s.kills = c.getOrDefault(KILLS, PersistentDataType.LONG, 0L);
        s.xp = c.getOrDefault(XP, PersistentDataType.DOUBLE, 0.0);
        return s;
    }

    void write(ItemMeta meta) {
        PersistentDataContainer c = meta.getPersistentDataContainer();
        c.set(TOOL, PersistentDataType.STRING, tool);
        c.set(UID, PersistentDataType.STRING, uid);
        c.set(BLOCKS, PersistentDataType.LONG, blocks);
        c.set(KILLS, PersistentDataType.LONG, kills);
        c.set(XP, PersistentDataType.DOUBLE, xp);
    }

    /** Fingerprint of the config the item's description was written with. */
    static int renderedVersion(ItemStack item) {
        return item.getItemMeta().getPersistentDataContainer().getOrDefault(RENDERED, PersistentDataType.INTEGER, 0);
    }

    static void markRendered(ItemMeta meta, int version) {
        meta.getPersistentDataContainer().set(RENDERED, PersistentDataType.INTEGER, version);
    }
}

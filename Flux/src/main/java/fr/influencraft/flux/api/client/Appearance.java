package fr.influencraft.flux.api.client;

import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What other players see a player wear, over what he really wears: an item per equipment slot drawn in place of his
 * own (FluxCosmetics' outfits), shown even when the slot is empty. Only the packets sent to the others change: his
 * real items, their stats and durability, his own screen and inventory stay as they are.
 *
 * <p>{@link #set} from the server thread; the adapter reads {@link #look} on the network thread when the server
 * sends his equipment to someone. Java viewers only (Geyser draws Bedrock viewers from its own translation).</p>
 */
public final class Appearance {

    public interface Service {
        /** Sends his equipment, looks included, to every player who sees him. */
        void update(Player wearer);

        /** The item ready for the network thread (converted once, on the server thread). */
        Object convert(ItemStack item);
    }

    private record Look(ItemStack item, Object packetItem) {
    }

    private static volatile Service service;
    /** Looks by player entity id (what the equipment packets name), read by the network thread. */
    private static final Map<Integer, Map<EquipmentSlot, Look>> LOOKS = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> IDS = new ConcurrentHashMap<>();

    private Appearance() {
    }

    public static void bind(Service s) {
        service = s;
    }

    public static boolean available() {
        return service != null;
    }

    /** Others see {@code item} in that slot of his (null: his own item again). Server thread. */
    public static void set(Player wearer, EquipmentSlot slot, ItemStack item) {
        Service s = service;
        if (s == null) return;
        int id = wearer.getEntityId();
        IDS.put(wearer.getUniqueId(), id);
        Map<EquipmentSlot, Look> looks = LOOKS.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
        if (item == null || item.getType().isAir()) looks.remove(slot);
        else looks.put(slot, new Look(item.clone(), s.convert(item)));
        if (looks.isEmpty()) LOOKS.remove(id);
        s.update(wearer);
    }

    /** Every look of his removed (he left, or the plugin stops). */
    public static void clear(Player wearer) {
        Integer id = IDS.remove(wearer.getUniqueId());
        if (id == null) return;
        LOOKS.remove(id);
        Service s = service;
        if (s != null && wearer.isOnline()) s.update(wearer);
    }

    /** The item others see in that slot of his, null when it is his own (server thread). */
    public static ItemStack item(Player wearer, EquipmentSlot slot) {
        Map<EquipmentSlot, Look> looks = LOOKS.get(wearer.getEntityId());
        Look l = looks == null ? null : looks.get(slot);
        return l == null ? null : l.item().clone();
    }

    /** The slots of this entity that show a look, empty when none (network thread). */
    public static Map<EquipmentSlot, Object> looks(int entityId) {
        Map<EquipmentSlot, Look> looks = LOOKS.get(entityId);
        if (looks == null || looks.isEmpty()) return Map.of();
        Map<EquipmentSlot, Object> out = new EnumMap<>(EquipmentSlot.class);
        looks.forEach((slot, look) -> out.put(slot, look.packetItem()));
        return out;
    }
}

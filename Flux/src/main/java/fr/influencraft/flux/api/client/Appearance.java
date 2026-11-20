package fr.influencraft.flux.api.client;

import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a player is seen wearing, over what he really wears: an item per equipment slot drawn in place of his own
 * (FluxCosmetics' outfits), shown even when the slot is empty. Only packets change; his real items, their stats and
 * durability stay on the server, which handles every click.
 *
 * <ul>
 *   <li><b>The others</b> get the look in his equipment packets. While he is invisible, a look shows only over a real
 *   piece (an invisible player's armour shows anyway): over an empty slot it would give him away.</li>
 *   <li><b>His own screen</b> (third person, inventory figure, armour slots): his real item <b>with the look's visual
 *   parts only</b> ({@link #merge}: model, worn asset, colour, trim, head, glint), so its name, lore, enchantments
 *   and durability are his own item's. Over an empty slot: the look with its tooltip hidden.</li>
 *   <li><b>In creative</b> his own slots are never rewritten: a creative client sends the server the items it shows,
 *   it could have taken the look out of the slot. Items shown on his screen carry a mark ({@link #MARK}): Flux
 *   refuses a creative click with such an item.</li>
 *   <li>A click predicted by his client from what it shows (shift click, number keys, right click with armour...)
 *   may leave his slots drawn wrong: Flux sends them again the next tick (AppearanceWatch).</li>
 * </ul>
 * {@link #set} from the server thread; the adapter reads on the network thread. Java viewers only.
 */
public final class Appearance {

    public interface Service {
        /** Sends his equipment, looks included, to every player who sees him, and his own slots to him. */
        void update(Player wearer);

        /** The item ready for the network thread (converted once, on the server thread). */
        Object convert(ItemStack item);

        /** Sends {@code viewer} the equipment of {@code entity}, previews included. */
        void resend(Player viewer, org.bukkit.entity.LivingEntity entity);
    }

    /** A look: the item, its packet form for the others, and for his own empty slot (tooltip hidden). */
    private record Look(ItemStack item, Object packetItem, Object ownEmpty) {
    }

    /** Mark of the items drawn on a wearer's own screen (never real items). */
    public static final NamespacedKey MARK = new NamespacedKey("flux", "look");

    /** Visual parts of an item copied onto the real one on his screen; everything else (name, lore...) stays his. */
    private static final List<DataComponentType.Valued<?>> VISUALS = List.of(DataComponentTypes.ITEM_MODEL,
            DataComponentTypes.EQUIPPABLE, DataComponentTypes.DYED_COLOR, DataComponentTypes.CUSTOM_MODEL_DATA,
            DataComponentTypes.TRIM, DataComponentTypes.PROFILE, DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE);

    private static volatile Service service;
    /** Looks by player entity id (what the equipment packets name), read by the network thread. */
    private static final Map<Integer, Map<EquipmentSlot, Look>> LOOKS = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> IDS = new ConcurrentHashMap<>();
    /** Entity ids of invisible wearers; UUIDs of wearers in creative (their own slots never rewritten). */
    private static final Set<Integer> INVISIBLE = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> CREATIVE = ConcurrentHashMap.newKeySet();
    /** Previews: by viewer, the slots of an entity drawn for him alone (packet items; null value = empty slot). */
    private static final Map<UUID, Map<Integer, Map<EquipmentSlot, Object>>> PREVIEWS = new ConcurrentHashMap<>();

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
        else {
            ItemStack own = mark(item.clone());
            own.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().hideTooltip(true).build());
            looks.put(slot, new Look(item.clone(), s.convert(item), s.convert(own)));
        }
        if (looks.isEmpty()) {
            LOOKS.remove(id);
            IDS.remove(wearer.getUniqueId());
        }
        watch(wearer);
        s.update(wearer);
    }

    /** Every look of his removed (he left, or the plugin stops). */
    public static void clear(Player wearer) {
        Integer id = IDS.remove(wearer.getUniqueId());
        CREATIVE.remove(wearer.getUniqueId());
        if (id == null) return;
        LOOKS.remove(id);
        INVISIBLE.remove(id);
        Service s = service;
        if (s != null && wearer.isOnline()) s.update(wearer);
    }

    /** Whether he wears any look (server thread). */
    public static boolean wearing(Player wearer) {
        return IDS.containsKey(wearer.getUniqueId());
    }

    /** The item others see in that slot of his, null when it is his own (server thread). */
    public static ItemStack item(Player wearer, EquipmentSlot slot) {
        Map<EquipmentSlot, Look> looks = LOOKS.get(wearer.getEntityId());
        Look l = looks == null ? null : looks.get(slot);
        return l == null ? null : l.item().clone();
    }

    /**
     * Reads again what decides how his looks are drawn (invisibility, creative), and draws them again when it changed.
     * Server thread; called by Flux on potion effects and game mode changes.
     */
    public static void watch(Player wearer) {
        if (!IDS.containsKey(wearer.getUniqueId())) return;
        boolean invisible = wearer.isInvisible() || wearer.hasPotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY);
        boolean creative = wearer.getGameMode() == org.bukkit.GameMode.CREATIVE;
        boolean changed = invisible ? INVISIBLE.add(wearer.getEntityId()) : INVISIBLE.remove(wearer.getEntityId());
        changed |= creative ? CREATIVE.add(wearer.getUniqueId()) : CREATIVE.remove(wearer.getUniqueId());
        Service s = service;
        if (changed && s != null) s.update(wearer);
    }

    /**
     * What {@code viewer} alone sees an entity wear (the wardrobe's mannequin trying on what he edits): every armour
     * slot given is drawn with that item for him (null item: empty), the others see the entity's own equipment. Null
     * or empty {@code looks}: his preview of it ends. Server thread.
     */
    public static void preview(Player viewer, org.bukkit.entity.LivingEntity entity, Map<EquipmentSlot, ItemStack> looks) {
        Service s = service;
        if (s == null) return;
        if (looks == null || looks.isEmpty()) {
            Map<Integer, Map<EquipmentSlot, Object>> mine = PREVIEWS.get(viewer.getUniqueId());
            if (mine == null || mine.remove(entity.getEntityId()) == null) return;
            if (mine.isEmpty()) PREVIEWS.remove(viewer.getUniqueId());
        } else {
            Map<EquipmentSlot, Object> converted = new EnumMap<>(EquipmentSlot.class);
            looks.forEach((slot, item) -> converted.put(slot, item == null || item.getType().isAir() ? EMPTY : s.convert(item)));
            PREVIEWS.computeIfAbsent(viewer.getUniqueId(), k -> new ConcurrentHashMap<>()).put(entity.getEntityId(), converted);
        }
        if (viewer.isOnline()) s.resend(viewer, entity);
    }

    /** Every preview of this viewer ends (he left). */
    public static void endPreviews(Player viewer) {
        PREVIEWS.remove(viewer.getUniqueId());
    }

    /** An empty slot in a preview. */
    public static final Object EMPTY = new Object();

    /** The slots of an entity drawn for this viewer alone, empty when none (network thread). */
    public static Map<EquipmentSlot, Object> preview(UUID viewer, int entityId) {
        Map<Integer, Map<EquipmentSlot, Object>> mine = PREVIEWS.get(viewer);
        Map<EquipmentSlot, Object> p = mine == null ? null : mine.get(entityId);
        return p == null ? Map.of() : p;
    }

    // ------------------------------------------------------------------ network thread

    /** The looks others see on this entity, empty when none. An empty real slot keeps empty while he is invisible. */
    public static Map<EquipmentSlot, Object> looks(int entityId) {
        Map<EquipmentSlot, Look> looks = LOOKS.get(entityId);
        if (looks == null || looks.isEmpty()) return Map.of();
        Map<EquipmentSlot, Object> out = new EnumMap<>(EquipmentSlot.class);
        looks.forEach((slot, look) -> out.put(slot, look.packetItem()));
        return out;
    }

    /** Whether this wearer is invisible (his looks show only over real pieces). */
    public static boolean invisible(int entityId) {
        return INVISIBLE.contains(entityId);
    }

    /** What his own screen shows in a slot: a look (Bukkit item) to merge with the real item, empty when none or creative. */
    public static Map<EquipmentSlot, ItemStack> own(UUID player) {
        Integer id = IDS.get(player);
        if (id == null || CREATIVE.contains(player)) return Map.of();
        Map<EquipmentSlot, Look> looks = LOOKS.get(id);
        if (looks == null || looks.isEmpty()) return Map.of();
        Map<EquipmentSlot, ItemStack> out = new EnumMap<>(EquipmentSlot.class);
        looks.forEach((slot, look) -> out.put(slot, look.item()));
        return out;
    }

    /** His own empty slot: the look with its tooltip hidden (packet form), null when none. */
    public static Object ownEmpty(UUID player, EquipmentSlot slot) {
        Integer id = IDS.get(player);
        Map<EquipmentSlot, Look> looks = id == null ? null : LOOKS.get(id);
        Look l = looks == null ? null : looks.get(slot);
        return l == null ? null : l.ownEmpty();
    }

    /**
     * His real item as his own screen shows it under a look: a copy keeping its name, lore, enchantments, durability
     * and every other part, with the look's visual parts ({@link #VISUALS}: set as the look has them, removed when it
     * has none), marked. Pure item work, any thread.
     */
    public static ItemStack merge(ItemStack real, ItemStack look) {
        ItemStack out = real.clone();
        for (DataComponentType.Valued<?> type : VISUALS) copy(look, out, type);
        return mark(out);
    }

    private static <T> void copy(ItemStack from, ItemStack to, DataComponentType.Valued<T> type) {
        T value = from.getData(type);
        if (value != null) to.setData(type, value);
        else to.unsetData(type);
    }

    /** Whether an item was drawn by a look (never a real item: refuse it from a client). */
    public static boolean marked(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(MARK);
    }

    private static ItemStack mark(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.getPersistentDataContainer().set(MARK, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }
}

package fr.influencraft.flux.adapters.packetevents;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import fr.influencraft.flux.api.client.Appearance;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link Appearance} with PacketEvents: the equipment packets naming a player who wears looks are rewritten on their
 * way to the others, and the armour slots of his own inventory on their way to him (window 0: slots 5 to 8; the
 * player inventory numbering: 36 to 39): his real item with the look's visual parts ({@link Appearance#merge}), the
 * look with its tooltip hidden over an empty slot. Other listeners rewriting the same packets (HotbarOverlay) chain
 * with it: a wrapper made from an event starts from the last one used on it.
 */
public final class AppearanceOverlay extends PacketListenerAbstract implements Appearance.Service {

    private static final EquipmentSlot[] ARMOUR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    public AppearanceOverlay() {
        super(PacketListenerPriority.HIGH);
    }

    @Override
    public Object convert(org.bukkit.inventory.ItemStack item) {
        return SpigotConversionUtil.fromBukkitItemStack(item);
    }

    @Override
    public void update(Player wearer) {
        // the four armour slots, looks or his own items, to everybody who sees him
        List<Equipment> equipment = new ArrayList<>(ARMOUR.length);
        Map<EquipmentSlot, Object> looks = Appearance.looks(wearer.getEntityId());
        boolean invisible = Appearance.invisible(wearer.getEntityId());
        for (EquipmentSlot slot : ARMOUR) {
            org.bukkit.inventory.ItemStack real = wearer.getInventory().getItem(slot);
            Object look = looks.get(slot);
            // invisible: a look only over a real piece, else it would give him away
            boolean shown = look instanceof ItemStack && !(invisible && (real == null || real.getType().isAir()));
            ItemStack item = shown ? (ItemStack) look : SpigotConversionUtil.fromBukkitItemStack(real);
            equipment.add(new Equipment(slot(slot), item));
        }
        for (Player viewer : wearer.getTrackedBy()) {
            PacketEvents.getAPI().getPlayerManager().sendPacket(viewer,
                    new WrapperPlayServerEntityEquipment(wearer.getEntityId(), new ArrayList<>(equipment)));
        }
        // his own screen: the armour slots sent again, rewritten on their way. Slot by slot: updateInventory() sends
        // the open window only, which has no armour slot while a menu shows (the cosmetics menu changing a look)
        var user = PacketEvents.getAPI().getPlayerManager().getUser(wearer);
        if (user != null) {
            for (int slot = 36; slot <= 39; slot++) {
                org.bukkit.inventory.ItemStack real = wearer.getInventory().getItem(inventoryArmour(slot));
                user.sendPacket(new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPlayerInventory(
                        slot, SpigotConversionUtil.fromBukkitItemStack(real)));
            }
        }
        wearer.updateInventory();
    }

    @Override
    public void resend(Player viewer, org.bukkit.entity.LivingEntity entity) {
        var own = entity.getEquipment();
        if (own == null) return;
        List<Equipment> equipment = new ArrayList<>(ARMOUR.length);
        // the entity's own items: rewritten on their way (preview, looks) like any other equipment packet
        for (EquipmentSlot slot : ARMOUR) equipment.add(new Equipment(slot(slot), SpigotConversionUtil.fromBukkitItemStack(own.getItem(slot))));
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, new WrapperPlayServerEntityEquipment(entity.getEntityId(), equipment));
    }

    /** Window 0 slot of an armour slot (5 helmet to 8 boots), -1 for any other. */
    private static EquipmentSlot windowArmour(int slot) {
        return switch (slot) {
            case 5 -> EquipmentSlot.HEAD;
            case 6 -> EquipmentSlot.CHEST;
            case 7 -> EquipmentSlot.LEGS;
            case 8 -> EquipmentSlot.FEET;
            default -> null;
        };
    }

    /** Player inventory numbering (36 boots to 39 helmet). */
    private static EquipmentSlot inventoryArmour(int slot) {
        return switch (slot) {
            case 36 -> EquipmentSlot.FEET;
            case 37 -> EquipmentSlot.LEGS;
            case 38 -> EquipmentSlot.CHEST;
            case 39 -> EquipmentSlot.HEAD;
            default -> null;
        };
    }

    /** What his own slot shows for the real item the server sends: merged with the look, or the look over nothing. */
    private static ItemStack shown(java.util.UUID id, EquipmentSlot slot, ItemStack real, org.bukkit.inventory.ItemStack look) {
        if (real == null || real.isEmpty()) {
            Object empty = Appearance.ownEmpty(id, slot);
            return empty instanceof ItemStack s ? s.copy() : real;
        }
        return SpigotConversionUtil.fromBukkitItemStack(Appearance.merge(SpigotConversionUtil.toBukkitItemStack(real), look));
    }

    /** His own armour slots, rewritten on their way to him (never in creative: Appearance#own is empty then). */
    private static void own(PacketSendEvent event) {
        java.util.UUID id = event.getUser() == null ? null : event.getUser().getUUID();
        Map<EquipmentSlot, org.bukkit.inventory.ItemStack> looks = id == null ? Map.of() : Appearance.own(id);
        if (looks.isEmpty()) return;
        var type = event.getPacketType();
        if (type == PacketType.Play.Server.WINDOW_ITEMS) {
            var packet = new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems(event);
            if (packet.getWindowId() != 0) return;
            List<ItemStack> items = new ArrayList<>(packet.getItems());
            boolean changed = false;
            for (int slot = 5; slot <= 8 && slot < items.size(); slot++) {
                EquipmentSlot armour = windowArmour(slot);
                org.bukkit.inventory.ItemStack look = looks.get(armour);
                if (look != null) {
                    items.set(slot, shown(id, armour, items.get(slot), look));
                    changed = true;
                }
            }
            if (!changed) return;
            packet.setItems(items);
            event.markForReEncode(true);
        } else if (type == PacketType.Play.Server.SET_SLOT) {
            var packet = new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot(event);
            EquipmentSlot slot = packet.getWindowId() == 0 ? windowArmour(packet.getSlot())
                    : packet.getWindowId() == -2 ? inventoryArmour(packet.getSlot()) : null;
            org.bukkit.inventory.ItemStack look = slot == null ? null : looks.get(slot);
            if (look != null) {
                packet.setItem(shown(id, slot, packet.getItem(), look));
                event.markForReEncode(true);
            }
        } else if (type == PacketType.Play.Server.SET_PLAYER_INVENTORY) {
            var packet = new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPlayerInventory(event);
            EquipmentSlot slot = inventoryArmour(packet.getSlot());
            org.bukkit.inventory.ItemStack look = slot == null ? null : looks.get(slot);
            if (look != null) {
                packet.setStack(shown(id, slot, packet.getStack(), look));
                event.markForReEncode(true);
            }
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        var type = event.getPacketType();
        if (type == PacketType.Play.Server.WINDOW_ITEMS || type == PacketType.Play.Server.SET_SLOT
                || type == PacketType.Play.Server.SET_PLAYER_INVENTORY) {
            own(event);
            return;
        }
        if (type != PacketType.Play.Server.ENTITY_EQUIPMENT) return;
        WrapperPlayServerEntityEquipment w = new WrapperPlayServerEntityEquipment(event);
        // drawn for this viewer alone (the wardrobe's mannequin trying on what he edits): wins over everything
        java.util.UUID viewer = event.getUser() == null ? null : event.getUser().getUUID();
        Map<EquipmentSlot, Object> preview = viewer == null ? Map.of() : Appearance.preview(viewer, w.getEntityId());
        if (!preview.isEmpty()) {
            for (Equipment e : w.getEquipment()) {
                EquipmentSlot slot = bukkit(e.getSlot());
                if (!preview.containsKey(slot)) continue;
                Object look = preview.get(slot);
                e.setItem(look instanceof ItemStack s ? s : ItemStack.EMPTY);
            }
            event.markForReEncode(true);
            return;
        }
        Map<EquipmentSlot, Object> looks = Appearance.looks(w.getEntityId());
        if (looks.isEmpty()) return;
        boolean invisible = Appearance.invisible(w.getEntityId());
        boolean changed = false;
        for (Equipment e : w.getEquipment()) {
            Object look = looks.get(bukkit(e.getSlot()));
            // invisible: a look only over a real piece, else it would give him away
            if (invisible && (e.getItem() == null || e.getItem().isEmpty())) continue;
            if (look instanceof ItemStack s) {
                e.setItem(s);
                changed = true;
            }
        }
        if (changed) event.markForReEncode(true);
    }

    private static com.github.retrooper.packetevents.protocol.player.EquipmentSlot slot(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> com.github.retrooper.packetevents.protocol.player.EquipmentSlot.HELMET;
            case CHEST -> com.github.retrooper.packetevents.protocol.player.EquipmentSlot.CHEST_PLATE;
            case LEGS -> com.github.retrooper.packetevents.protocol.player.EquipmentSlot.LEGGINGS;
            case FEET -> com.github.retrooper.packetevents.protocol.player.EquipmentSlot.BOOTS;
            case OFF_HAND -> com.github.retrooper.packetevents.protocol.player.EquipmentSlot.OFF_HAND;
            case BODY -> com.github.retrooper.packetevents.protocol.player.EquipmentSlot.BODY;
            case SADDLE -> com.github.retrooper.packetevents.protocol.player.EquipmentSlot.SADDLE;
            default -> com.github.retrooper.packetevents.protocol.player.EquipmentSlot.MAIN_HAND;
        };
    }

    private static EquipmentSlot bukkit(com.github.retrooper.packetevents.protocol.player.EquipmentSlot slot) {
        return switch (slot) {
            case HELMET -> EquipmentSlot.HEAD;
            case CHEST_PLATE -> EquipmentSlot.CHEST;
            case LEGGINGS -> EquipmentSlot.LEGS;
            case BOOTS -> EquipmentSlot.FEET;
            case OFF_HAND -> EquipmentSlot.OFF_HAND;
            case BODY -> EquipmentSlot.BODY;
            case SADDLE -> EquipmentSlot.SADDLE;
            default -> EquipmentSlot.HAND;
        };
    }
}

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
 * way to the others (his own client is never sent his own equipment this way).
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
        for (EquipmentSlot slot : ARMOUR) {
            Object look = looks.get(slot);
            ItemStack item = look instanceof ItemStack s ? s
                    : SpigotConversionUtil.fromBukkitItemStack(wearer.getInventory().getItem(slot));
            equipment.add(new Equipment(slot(slot), item));
        }
        for (Player viewer : wearer.getTrackedBy()) {
            PacketEvents.getAPI().getPlayerManager().sendPacket(viewer,
                    new WrapperPlayServerEntityEquipment(wearer.getEntityId(), new ArrayList<>(equipment)));
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.ENTITY_EQUIPMENT) return;
        WrapperPlayServerEntityEquipment w = new WrapperPlayServerEntityEquipment(event);
        Map<EquipmentSlot, Object> looks = Appearance.looks(w.getEntityId());
        if (looks.isEmpty()) return;
        boolean changed = false;
        for (Equipment e : w.getEquipment()) {
            Object look = looks.get(bukkit(e.getSlot()));
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

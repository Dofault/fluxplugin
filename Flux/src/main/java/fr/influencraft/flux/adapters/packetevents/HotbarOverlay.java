package fr.influencraft.flux.adapters.packetevents;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemModel;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChangeGameState;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPlayerInventory;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import fr.influencraft.flux.api.client.Clients;
import fr.influencraft.flux.api.client.Hotbar;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link Hotbar} with PacketEvents: the nine shown items are sent as slots of the player's own inventory window, and
 * every packet of the server about those slots is rewritten with them while they are shown.
 */
public final class HotbarOverlay extends PacketListenerAbstract implements Hotbar.Service {

    /** The hotbar in the player's inventory window (id 0): slots 36 to 44. */
    private static final int FIRST = 36, SIZE = 9;

    private final Map<UUID, ItemStack[]> shown = new ConcurrentHashMap<>();
    private final Set<UUID> barsHidden = ConcurrentHashMap.newKeySet();

    public HotbarOverlay() {
        super(PacketListenerPriority.HIGH);
    }

    @Override
    public void show(Player player, org.bukkit.inventory.ItemStack[] items) {
        ItemStack[] fake = new ItemStack[SIZE];
        for (int i = 0; i < SIZE; i++) {
            org.bukkit.inventory.ItemStack item = items != null && i < items.length ? items[i] : null;
            fake[i] = item == null || item.isEmpty() ? ItemStack.EMPTY : SpigotConversionUtil.fromBukkitItemStack(item);
            if (item != null && !item.isEmpty()) keepModel(item, fake[i]);
        }
        shown.put(player.getUniqueId(), fake);
        for (int i = 0; i < SIZE; i++) {
            PacketEvents.getAPI().getPlayerManager().sendPacketSilently(player, new WrapperPlayServerSetSlot(0, 0, FIRST + i, fake[i].copy()));
        }
    }

    /**
     * The item's own model (a 3D weapon of the pack) carried over to the packet item: the client draws the plain
     * material without it, which is also what a client without the pack (Bedrock) shows. Said again here because the
     * converted item came out with the vanilla look in the class showcase (2026-11-20).
     */
    private static void keepModel(org.bukkit.inventory.ItemStack item, ItemStack fake) {
        net.kyori.adventure.key.Key model = item.getData(io.papermc.paper.datacomponent.DataComponentTypes.ITEM_MODEL);
        if (model == null) return;
        ResourceLocation location = new ResourceLocation(model.namespace(), model.value());
        ItemModel current = fake.getComponentOr(ComponentTypes.ITEM_MODEL, null);
        if (current != null && location.equals(current.getModelLocation())) return;
        if (!warned) {
            warned = true;
            org.bukkit.Bukkit.getLogger().warning("[Flux] Hotbar: item model " + model.asString() + " lost in the packet conversion, set again");
        }
        fake.setComponent(ComponentTypes.ITEM_MODEL, new ItemModel(location));
    }

    private static volatile boolean warned;

    @Override
    public void clear(Player player) {
        if (shown.remove(player.getUniqueId()) != null && player.isOnline()) player.updateInventory();
    }

    @Override
    public void bars(Player player, boolean visible) {
        if (Clients.bedrock(player)) return;
        if (visible ? !barsHidden.remove(player.getUniqueId()) : !barsHidden.add(player.getUniqueId())) return;
        if (!player.isOnline()) return;
        GameMode mode = visible ? player.getGameMode() : GameMode.CREATIVE;
        PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                new WrapperPlayServerChangeGameState(WrapperPlayServerChangeGameState.Reason.CHANGE_GAME_MODE, mode.getValue()));
        if (visible) {
            // his client worked out what a creative player may do: the server's word again
            player.setAllowFlight(player.getAllowFlight());
            player.updateInventory();
        }
    }

    /** A player left: nothing is kept about him. */
    public void forget(UUID player) {
        shown.remove(player);
        barsHidden.remove(player);
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (shown.isEmpty()) return;
        PacketTypeCommon type = event.getPacketType();
        if (type != PacketType.Play.Server.WINDOW_ITEMS && type != PacketType.Play.Server.SET_SLOT
                && type != PacketType.Play.Server.SET_PLAYER_INVENTORY) return;
        UUID id = event.getUser() == null ? null : event.getUser().getUUID();
        ItemStack[] fake = id == null ? null : shown.get(id);
        if (fake == null) return;
        if (type == PacketType.Play.Server.WINDOW_ITEMS) {
            WrapperPlayServerWindowItems packet = new WrapperPlayServerWindowItems(event);
            if (packet.getWindowId() != 0) return;
            List<ItemStack> items = new ArrayList<>(packet.getItems());
            for (int i = 0; i < SIZE && FIRST + i < items.size(); i++) items.set(FIRST + i, fake[i].copy());
            packet.setItems(items);
            event.markForReEncode(true);
        } else if (type == PacketType.Play.Server.SET_SLOT) {
            WrapperPlayServerSetSlot packet = new WrapperPlayServerSetSlot(event);
            // window 0 counts the hotbar from 36, the former "player inventory" window (-2) from 0
            int slot = packet.getWindowId() == 0 ? packet.getSlot() - FIRST : packet.getWindowId() == -2 ? packet.getSlot() : -1;
            if (slot < 0 || slot >= SIZE) return;
            packet.setItem(fake[slot].copy());
            event.markForReEncode(true);
        } else {
            WrapperPlayServerSetPlayerInventory packet = new WrapperPlayServerSetPlayerInventory(event);
            int slot = packet.getSlot();
            if (slot < 0 || slot >= SIZE) return;
            packet.setStack(fake[slot].copy());
            event.markForReEncode(true);
        }
    }
}

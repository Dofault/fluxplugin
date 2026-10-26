package fr.influencraft.flux.session;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Masque l'inventaire du joueur pendant qu'un menu « hidden » est ouvert, uniquement dans les
 * paquets envoyés au client (fil réseau) :
 * <ul>
 *   <li>contenu / case de NOTRE fenêtre : les 36 cases du joueur sont remplacées par les
 *       boutons du menu (ou vides) ;</li>
 *   <li>contenu / case de la fenêtre d'inventaire (id 0) et paquet « case d'inventaire du
 *       joueur » : supprimés, sinon le client les recopierait dans la zone du bas.</li>
 * </ul>
 * Le serveur continue de voir et de gérer le vrai inventaire (ramassage d'objets, plugins...),
 * le client reçoit le vrai contenu à la fermeture ({@code updateInventory}).
 */
public final class InventoryHider implements PacketListener {

    private static final int PLAYER_SLOTS = 36;

    private final MenuService service;

    public InventoryHider(MenuService service) {
        this.service = service;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type != PacketType.Play.Server.WINDOW_ITEMS
                && type != PacketType.Play.Server.SET_SLOT
                && type != PacketType.Play.Server.SET_PLAYER_INVENTORY) {
            return;
        }
        User user = event.getUser();
        UUID id = user == null ? null : user.getUUID();
        if (id == null) return;
        MenuSession session = service.hiddenSession(id);
        if (session == null) return;

        if (type == PacketType.Play.Server.SET_PLAYER_INVENTORY) {
            event.setCancelled(true);
            return;
        }

        if (type == PacketType.Play.Server.WINDOW_ITEMS) {
            WrapperPlayServerWindowItems packet = new WrapperPlayServerWindowItems(event);
            int window = packet.getWindowId();
            if (window == 0) {
                event.setCancelled(true);
                return;
            }
            if (!session.matchesWindow(window)) return;
            List<ItemStack> items = new ArrayList<>(packet.getItems());
            ItemStack[] fake = session.fakeBottom();
            int top = session.topSize();
            for (int i = 0; i < PLAYER_SLOTS && top + i < items.size(); i++) {
                items.set(top + i, copy(fake[i]));
            }
            packet.setItems(items);
            event.markForReEncode(true);
            return;
        }

        WrapperPlayServerSetSlot packet = new WrapperPlayServerSetSlot(event);
        int window = packet.getWindowId();
        if (window == 0 || window == -2) {
            event.setCancelled(true);
            return;
        }
        if (window == -1 || !session.matchesWindow(window)) return; // -1 : objet tenu à la souris
        int slot = packet.getSlot() - session.topSize();
        if (slot < 0 || slot >= PLAYER_SLOTS) return;
        packet.setItem(copy(session.fakeBottom()[slot]));
        event.markForReEncode(true);
    }

    private static ItemStack copy(ItemStack item) {
        return item == null ? ItemStack.EMPTY : item.copy();
    }
}

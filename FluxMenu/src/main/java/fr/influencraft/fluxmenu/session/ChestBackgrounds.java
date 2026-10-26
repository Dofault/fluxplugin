package fr.influencraft.fluxmenu.session;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenWindow;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.logging.Logger;

/**
 * Fonds de coffre dessinés dans le titre.
 *
 * <p>La texture vanilla des coffres (generic_54.png) est transparente dans notre pack de
 * ressources : sans rien de plus, toutes les fenêtres de coffre n'auraient plus de fond. À
 * l'ouverture de chaque fenêtre de coffre (1 à 6 rangées) qui n'est pas un menu FluxMenu
 * transparent, on ajoute donc au début du titre le glyphe ItemsAdder du fond correspondant
 * ({@code fluxchest_<rangées>}), encadré de décalages pour que le titre d'origine reste à sa
 * place : vrais coffres, menus DeluxeMenus et menus des autres plugins gardent leur apparence.
 */
public final class ChestBackgrounds implements PacketListener {

    /** Identifiants réseau des types generic_9x1 à generic_9x6 (stables depuis 1.14). */
    private static final int FIRST_GENERIC = 0;
    private static final int LAST_GENERIC = 5;

    /** Le titre est dessiné 8 px à droite du bord ; un glyphe de 176 px avance de 177. */
    private static final String SHORTCODE = ":offset_-8::fluxchest_%d::offset_-169:";

    private final MenuService service;
    private final Logger logger;
    private final AtomicReferenceArray<String> prefixes = new AtomicReferenceArray<>(6);
    private volatile boolean warned;

    public ChestBackgrounds(MenuService service, Logger logger) {
        this.service = service;
        this.logger = logger;
    }

    /** Après un /iareload ou /fluxmenu reload : les glyphes sont recalculés. */
    public void reset() {
        for (int i = 0; i < 6; i++) prefixes.set(i, null);
        warned = false;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.OPEN_WINDOW) return;
        WrapperPlayServerOpenWindow packet = new WrapperPlayServerOpenWindow(event);
        int type = packet.getType();
        if (type < FIRST_GENERIC || type > LAST_GENERIC) return;

        User user = event.getUser();
        UUID id = user == null ? null : user.getUUID();
        if (id != null) {
            MenuSession session = service.session(id);
            // Notre menu (inscrit avant l'ouverture) : transparent sauf vanilla-background: true.
            if (session != null && session.matchesWindow(packet.getContainerId())
                    && !session.menu().vanillaBackground()) {
                return;
            }
        }

        String prefix = prefix(type - FIRST_GENERIC + 1);
        if (prefix == null) return;
        Component title = packet.getTitle();
        packet.setTitle(Component.text()
                .append(Component.text(prefix, NamedTextColor.WHITE))
                .append(title == null ? Component.empty() : title)
                .build());
        event.markForReEncode(true);
    }

    private String prefix(int rows) {
        String cached = prefixes.get(rows - 1);
        if (cached != null) return cached;
        if (!Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) return null;
        String resolved;
        try {
            resolved = dev.lone.itemsadder.api.FontImages.FontImageWrapper
                    .replaceFontImages(String.format(SHORTCODE, rows));
        } catch (RuntimeException e) {
            return null; // ItemsAdder en cours de chargement : on réessaiera à la prochaine fenêtre
        }
        if (resolved.indexOf(':') >= 0) {
            if (!warned) {
                warned = true;
                logger.warning("Fond de coffre fluxchest_" + rows + " introuvable dans ItemsAdder ("
                        + resolved + ") : les coffres s'affichent sans fond. Pack fluxmenu chargé ? (/iareload)");
            }
            return null;
        }
        prefixes.set(rows - 1, resolved);
        return resolved;
    }
}

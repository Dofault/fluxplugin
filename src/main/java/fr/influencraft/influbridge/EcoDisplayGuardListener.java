package fr.influencraft.influbridge;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Empêche eco (dépendance partagée d'EcoJobs) de faire disparaître le lore des items
 * AdvancedEnchantments — même conflit que GC-spigot/AdvancedEnchantments#5164 : eco relit et
 * retouche le lore de CHAQUE item envoyé à CHAQUE joueur via son propre pipeline packet
 * ({@code Display.display()}/{@code revert()}, cf. son code source), y compris les items qui
 * ne lui appartiennent pas. AdvancedEnchantments n'utilise pas ce système (son lore est écrit
 * directement dans l'ItemMeta persistant), donc pas besoin qu'eco y touche.
 * <p>
 * eco s'injecte dans la pipeline Netty de chaque joueur comme handler nommé "eco_packets",
 * ajouté par son propre {@code PlayerJoinEvent} à priorité normale (addBefore "packet_handler").
 * On s'accroche donc ici sur un {@code PlayerJoinEvent} à priorité {@link EventPriority#MONITOR}
 * — garanti par Bukkit de s'exécuter après TOUS les listeners à priorité normale de TOUS les
 * plugins, indépendamment de l'ordre de chargement — et on insère deux handlers autour
 * d'"eco_packets" : un juste avant lui (déplacement "après" dans la liste de la pipeline, donc
 * traversé en premier côté sortant) qui capture le meta d'un item AdvancedEnchantments AVANT
 * qu'eco n'y touche, et un juste après lui (déplacement "avant" dans la liste) qui restaure ce
 * meta capturé APRÈS le passage d'eco. Le reste du trafic (items EcoJobs, etc.) n'est jamais
 * touché par ce guard.
 * <p>
 * No-op total si eco n'est pas présent/injecté pour ce joueur (pas de dépendance dure sur eco :
 * seul le nom du handler Netty "eco_packets" est utilisé, aucune classe eco importée).
 * <p>
 * Nécessite un redémarrage du serveur SemiRP pour être chargé ; jamais testé en jeu avant
 * déploiement (pas d'accès OS au process mcserver pour vérifier en direct).
 */
public final class EcoDisplayGuardListener implements Listener {

    private static final String ECO_HANDLER_NAME = "eco_packets";
    private static final String PRE_HANDLER_NAME = "influbridge_ae_pre";
    private static final String POST_HANDLER_NAME = "influbridge_ae_post";
    private static final String AE_NAMESPACE = "advancedenchantments";

    /** Sentinelle pour les entrées "pas un item AE" — ArrayDeque interdit les éléments null. */
    private static final Object NONE = new Object();

    /**
     * Relais pre -> post pour un même paquet. L'écriture d'un paquet traverse pre puis eco puis
     * post de façon synchrone dans le même appel (même thread, la boucle événementielle du
     * channel), donc une pile par thread suffit à faire correspondre les deux passages.
     */
    private static final ThreadLocal<Deque<Object>> STASH = ThreadLocal.withInitial(ArrayDeque::new);

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        inject(event.getPlayer());
    }

    private void inject(Player player) {
        Channel channel;
        try {
            channel = ((CraftPlayer) player).getHandle().connection.connection.channel;
        } catch (Throwable t) {
            return;
        }

        ChannelPipeline pipeline = channel.pipeline();
        if (pipeline.get(ECO_HANDLER_NAME) == null || pipeline.get(PRE_HANDLER_NAME) != null) {
            return;
        }

        pipeline.addAfter(ECO_HANDLER_NAME, PRE_HANDLER_NAME, new PreHandler());
        pipeline.addBefore(ECO_HANDLER_NAME, POST_HANDLER_NAME, new PostHandler());
    }

    private static boolean isAeItem(net.minecraft.world.item.ItemStack nmsItem) {
        if (nmsItem == null || nmsItem.isEmpty()) {
            return false;
        }
        org.bukkit.inventory.ItemStack mirror = CraftItemStack.asCraftMirror(nmsItem);
        if (!mirror.hasItemMeta()) {
            return false;
        }
        return mirror.getItemMeta().getPersistentDataContainer().getKeys().stream()
                .anyMatch(key -> key.getNamespace().equals(AE_NAMESPACE));
    }

    private static ItemMeta snapshotMeta(net.minecraft.world.item.ItemStack nmsItem) {
        return CraftItemStack.asCraftMirror(nmsItem).getItemMeta();
    }

    private static void restoreMeta(net.minecraft.world.item.ItemStack nmsItem, ItemMeta meta) {
        CraftItemStack.asCraftMirror(nmsItem).setItemMeta(meta);
    }

    private record ContentSnapshot(List<ItemMeta> slotMetas, ItemMeta carriedMeta) {
    }

    /** Positionné juste après "eco_packets" dans la pipeline -> traversé en premier côté sortant. */
    private static final class PreHandler extends ChannelDuplexHandler {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (msg instanceof ClientboundContainerSetSlotPacket packet) {
                var item = packet.getItem();
                STASH.get().push(isAeItem(item) ? snapshotMeta(item) : NONE);
            } else if (msg instanceof ClientboundContainerSetContentPacket packet) {
                List<ItemMeta> slotMetas = new ArrayList<>();
                for (var item : packet.items()) {
                    slotMetas.add(isAeItem(item) ? snapshotMeta(item) : null);
                }
                var carried = packet.carriedItem();
                ItemMeta carriedMeta = isAeItem(carried) ? snapshotMeta(carried) : null;
                STASH.get().push(new ContentSnapshot(slotMetas, carriedMeta));
            }
            super.write(ctx, msg, promise);
        }
    }

    /** Positionné juste avant "eco_packets" dans la pipeline -> traversé après lui côté sortant. */
    private static final class PostHandler extends ChannelDuplexHandler {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (msg instanceof ClientboundContainerSetSlotPacket packet) {
                Object snap = pop();
                if (snap instanceof ItemMeta meta) {
                    restoreMeta(packet.getItem(), meta);
                }
            } else if (msg instanceof ClientboundContainerSetContentPacket packet) {
                Object snap = pop();
                if (snap instanceof ContentSnapshot content) {
                    List<net.minecraft.world.item.ItemStack> items = packet.items();
                    for (int i = 0; i < items.size() && i < content.slotMetas().size(); i++) {
                        ItemMeta meta = content.slotMetas().get(i);
                        if (meta != null) {
                            restoreMeta(items.get(i), meta);
                        }
                    }
                    if (content.carriedMeta() != null) {
                        restoreMeta(packet.carriedItem(), content.carriedMeta());
                    }
                }
            }
            super.write(ctx, msg, promise);
        }

        private Object pop() {
            Deque<Object> stack = STASH.get();
            return stack.isEmpty() ? null : stack.pop();
        }
    }
}

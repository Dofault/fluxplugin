package fr.influencraft.fluxpets.effects;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTags;
import io.papermc.paper.event.server.ServerResourcesReloadedEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bonhomme de Neige : marcher sur la neige poudreuse sans s'enfoncer, comme avec des bottes en cuir, pendant
 * une durée limitée puis recharge.
 *
 * C'est le client qui décide s'il s'enfonce (PowderSnowBlock.canEntityWalkOnPowderSnow : tag d'entités
 * minecraft:powder_snow_walkable_mobs ou bottes en cuir aux pieds). On envoie donc à ce seul joueur les tags
 * d'entités du serveur, avec le joueur ajouté à ce tag ; à la fin, les tags d'origine. Côté serveur rien ne
 * change : le joueur reste au-dessus du bloc (pas « dedans », donc ni gel ni ralentissement).
 *
 * Le tag doit arriver AVANT que le joueur ne pose le pied sur la neige (sinon le client l'a déjà fait
 * s'enfoncer) : il est envoyé dès qu'une neige poudreuse est à portée sous ses pieds (« armé »), et la durée ne
 * démarre qu'au premier contact. S'il s'éloigne sans y avoir marché, le tag est retiré sans recharge. Sauter
 * en boucle ne prolonge rien : la durée court depuis le premier contact. À la fin de la durée il s'enfonce
 * comme n'importe qui.
 *
 * Seule classe des effets qui référence packetevents : chargée seulement si packetevents est actif.
 */
final class PowderSnow extends PetEffect {

    private static final ResourceLocation REGISTRY = ResourceLocation.minecraft("entity_type");
    private static final String WALKABLE = "minecraft:powder_snow_walkable_mobs";
    private static final int PERIOD = 2;
    private static final int NEAR = 2;

    /** Tag envoyé ; activeUntil : tick de fin (0 = armé, pas encore marché dessus). */
    private static final class State {
        long activeUntil;
    }

    private final Map<UUID, State> armed = new HashMap<>();

    PowderSnow(PetEffects manager) {
        super(manager, "powder-snow");
        Bukkit.getScheduler().runTaskTimer(manager.plugin(), this::update, 40L, PERIOD);
    }

    private void update() {
        long now = Bukkit.getCurrentTick();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            State state = armed.get(uuid);
            int level = level(player);
            if (level <= 0 || cooldownLeft(player) > 0) {
                if (state != null) {
                    send(player, false);
                }
                continue;
            }
            if (state == null) {
                if (snowNear(player)) {
                    send(player, true);
                }
                continue;
            }
            if (state.activeUntil == 0) {
                if (onSnow(player)) {
                    state.activeUntil = now + Math.max(1, Math.round(value(level) * 20));
                } else if (!snowNear(player)) {
                    send(player, false);
                }
            } else if (now >= state.activeUntil) {
                send(player, false);
                double recharge = getDouble("cooldown", 30);
                startCooldown(player, recharge);
                actionBar(player, "end-message", "%seconds%", String.valueOf(Math.round(recharge)));
            }
        }
    }

    /** Neige poudreuse dans les 5x5 blocs sous les pieds (un ou deux blocs plus bas). */
    private static boolean snowNear(Player player) {
        Location loc = player.getLocation();
        World world = loc.getWorld();
        int bx = loc.getBlockX();
        int by = loc.getBlockY();
        int bz = loc.getBlockZ();
        for (int dy = -2; dy <= -1; dy++) {
            for (int dx = -NEAR; dx <= NEAR; dx++) {
                for (int dz = -NEAR; dz <= NEAR; dz++) {
                    if (world.getType(bx + dx, by + dy, bz + dz) == Material.POWDER_SNOW) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Debout sur la neige poudreuse (juste sous les pieds). */
    private static boolean onSnow(Player player) {
        Location loc = player.getLocation();
        return loc.clone().subtract(0, 0.05, 0).getBlock().getType() == Material.POWDER_SNOW;
    }

    private void send(Player player, boolean walk) {
        if (walk) {
            armed.put(player.getUniqueId(), new State());
        } else {
            armed.remove(player.getUniqueId());
        }
        try {
            ClientVersion version = PacketEvents.getAPI().getPlayerManager().getClientVersion(player);
            List<WrapperPlayServerTags.Tag> tags = new ArrayList<>();
            for (Tag<EntityType> tag : Bukkit.getTags(Tag.REGISTRY_ENTITY_TYPES, EntityType.class)) {
                List<Integer> ids = new ArrayList<>();
                for (EntityType type : tag.getValues()) {
                    int id = id(type, version);
                    if (id >= 0) {
                        ids.add(id);
                    }
                }
                String key = tag.getKey().toString();
                if (walk && key.equals(WALKABLE)) {
                    ids.add(id(EntityType.PLAYER, version));
                }
                tags.add(new WrapperPlayServerTags.Tag(key, ids));
            }
            Map<ResourceLocation, List<WrapperPlayServerTags.Tag>> map = new HashMap<>();
            map.put(REGISTRY, tags);
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, new WrapperPlayServerTags(map));
        } catch (RuntimeException e) {
            manager.plugin().getLogger().warning("Powder snow: could not send tags to "
                    + player.getName() + ": " + e);
        }
    }

    private static int id(EntityType type, ClientVersion version) {
        if (type.getKey() == null) {
            return -1;
        }
        var mapped = EntityTypes.getByName(type.getKey().toString());
        return mapped == null ? -1 : mapped.getId(version);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        armed.remove(event.getPlayer().getUniqueId());
    }

    /** /minecraft:reload renvoie les tags d'origine à tout le monde : les joueurs armés sont désarmés. */
    @EventHandler
    public void onReload(ServerResourcesReloadedEvent event) {
        armed.clear();
    }

    @Override
    protected void shutdown() {
        for (UUID uuid : new ArrayList<>(armed.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                send(player, false);
            }
        }
        armed.clear();
    }
}

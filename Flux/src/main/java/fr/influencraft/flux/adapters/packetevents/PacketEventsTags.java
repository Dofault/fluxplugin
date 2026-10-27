package fr.influencraft.flux.adapters.packetevents;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTags;
import fr.influencraft.flux.api.client.ClientTags;
import org.bukkit.Bukkit;
import org.bukkit.Tag;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** packetevents behind {@link ClientTags}. */
public final class PacketEventsTags implements ClientTags.Provider {

    private static final ResourceLocation REGISTRY = ResourceLocation.minecraft("entity_type");

    @Override
    public void sendEntityTypeTags(Player player, Map<String, Set<EntityType>> extra) {
        ClientVersion version = PacketEvents.getAPI().getPlayerManager().getClientVersion(player);
        List<WrapperPlayServerTags.Tag> tags = new ArrayList<>();
        for (Tag<EntityType> tag : Bukkit.getTags(Tag.REGISTRY_ENTITY_TYPES, EntityType.class)) {
            List<Integer> ids = new ArrayList<>();
            for (EntityType type : tag.getValues()) {
                int id = id(type, version);
                if (id >= 0) ids.add(id);
            }
            String key = tag.getKey().toString();
            for (EntityType type : extra.getOrDefault(key, Set.of())) {
                int id = id(type, version);
                if (id >= 0) ids.add(id);
            }
            tags.add(new WrapperPlayServerTags.Tag(key, ids));
        }
        Map<ResourceLocation, List<WrapperPlayServerTags.Tag>> map = new HashMap<>();
        map.put(REGISTRY, tags);
        PacketEvents.getAPI().getPlayerManager().sendPacket(player, new WrapperPlayServerTags(map));
    }

    private static int id(EntityType type, ClientVersion version) {
        if (type.getKey() == null) return -1;
        var mapped = EntityTypes.getByName(type.getKey().toString());
        return mapped == null ? -1 : mapped.getId(version);
    }
}

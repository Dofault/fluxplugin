package fr.influencraft.flux.api.client;

import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Small resource packs laid over the server's own pack for some players only, and taken off again (Java players;
 * Bedrock players never get one). Flux serves them itself ({@code resource-overlays} of its config: port and host the
 * players download from; the port must be open in the firewall).
 *
 * <pre>
 *   ResourceOverlays.register(this, "rpg_hud", Map.of("assets/minecraft/textures/...png", bytes));
 *   ResourceOverlays.show(player, "rpg_hud");    // entering the rpg universe
 *   ResourceOverlays.hide(player, "rpg_hud");    // leaving it
 * </pre>
 * The client reloads its textures each time (a few seconds): show and hide them on rare moments (world change).
 */
public final class ResourceOverlays {

    /** Implemented by Flux: hands out the zip of an overlay and gives its address. */
    public interface Backend {
        /** Address of the overlay, null when the overlays are off. */
        URI publish(String id, byte[] zip, String sha1);
    }

    private record Overlay(Plugin owner, UUID uuid, URI uri, String sha1) {
    }

    private static volatile Backend backend;
    private static final Map<String, Overlay> OVERLAYS = new ConcurrentHashMap<>();
    /** Pack format of the client version the server runs (26.2: 88). */
    private static final int PACK_FORMAT = 88;

    private ResourceOverlays() {
    }

    public static void bind(Backend flux) {
        backend = flux;
    }

    /**
     * Declares an overlay made of these files (path in the pack -> content); {@code pack.mcmeta} is added. False when
     * the overlays are off (no port configured) or Flux is missing.
     */
    public static boolean register(Plugin owner, String id, Map<String, byte[]> files) {
        Backend b = backend;
        if (b == null) return false;
        try {
            byte[] zip = zip(id, files);
            String sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(zip));
            URI uri = b.publish(id, zip, sha1);
            if (uri == null) return false;
            OVERLAYS.put(id, new Overlay(owner, UUID.nameUUIDFromBytes(("flux-overlay:" + id).getBytes(StandardCharsets.UTF_8)), uri, sha1));
            return true;
        } catch (IOException | NoSuchAlgorithmException e) {
            owner.getLogger().warning("Overlay " + id + " not made: " + e);
            return false;
        }
    }

    /** Lays the overlay over the player's packs (nothing for a Bedrock player or an unknown overlay). */
    public static void show(Player player, String id) {
        Overlay o = OVERLAYS.get(id);
        if (o == null || Clients.bedrock(player)) return;
        player.sendResourcePacks(ResourcePackRequest.resourcePackRequest()
                .packs(ResourcePackInfo.resourcePackInfo(o.uuid(), o.uri(), o.sha1()))
                .required(false).replace(false).build());
    }

    public static void hide(Player player, String id) {
        Overlay o = OVERLAYS.get(id);
        if (o != null) player.removeResourcePacks(o.uuid());
    }

    /** True when this pack id is one of the overlays (a plugin reacting to packs loaded can skip its own). */
    public static boolean isOverlay(UUID pack) {
        for (Overlay o : OVERLAYS.values()) if (o.uuid().equals(pack)) return true;
        return false;
    }

    /** True when the overlay is declared and served. */
    public static boolean available(String id) {
        return OVERLAYS.containsKey(id);
    }

    public static void unregisterAll(Plugin owner) {
        OVERLAYS.values().removeIf(o -> o.owner().equals(owner));
    }

    private static byte[] zip(String id, Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            String meta = "{\"pack\":{\"pack_format\":" + PACK_FORMAT + ",\"min_format\":" + PACK_FORMAT + ",\"max_format\":9999,"
                    + "\"supported_formats\":[" + PACK_FORMAT + ",9999],\"description\":\"Flux overlay " + id + "\"}}";
            entry(z, "pack.mcmeta", meta.getBytes(StandardCharsets.UTF_8));
            // sorted and with a fixed time: the same files always make the same zip (same hash, client cache kept)
            for (String path : new java.util.TreeSet<>(files.keySet())) entry(z, path, files.get(path));
        }
        return out.toByteArray();
    }

    private static void entry(ZipOutputStream z, String path, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(path);
        e.setTime(0);
        z.putNextEntry(e);
        z.write(data);
        z.closeEntry();
    }
}

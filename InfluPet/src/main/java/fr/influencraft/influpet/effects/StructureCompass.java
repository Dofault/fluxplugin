package fr.influencraft.influpet.effects;

import fr.influencraft.influpet.Texts;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.generator.structure.Structure;
import org.bukkit.generator.structure.StructureType;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.StructureSearchResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Astrolabe Céleste : Maj + clic droit avec une boussole en main cherche la structure la plus proche (parmi les
 * types de la liste structure-types), l'annonce dans le tchat (nom, distance, direction, coordonnées à copier
 * d'un clic) et fait pointer les boussoles du joueur dessus. value du niveau = recharge en secondes.
 *
 * La recharge est longue (jusqu'à plus d'une heure) : elle est gardée dans les données du joueur en heure
 * réelle, pas en mémoire, pour survivre aux redémarrages et aux rechargements. Sans structure trouvée, pas de
 * recharge. La cible de la boussole (non gardée par le serveur entre deux connexions) y est gardée aussi et
 * reposée à la connexion et au changement de monde.
 *
 * La recherche se fait sur le fil principal (API Bukkit) : une par type de structure, d'où la liste limitée et
 * le rayon réglable ; une recherche lente est signalée dans le journal.
 */
final class StructureCompass extends PetEffect {

    private static final String[] DIRECTIONS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
    private static final long SLOW_MILLIS = 250;

    private final NamespacedKey readyKey;
    private final NamespacedKey targetKey;
    private Material item = Material.COMPASS;
    private int radius = 100;
    private final List<StructureType> types = new ArrayList<>();
    private final Map<String, String> names = new HashMap<>();
    private List<String> directions = List.of();
    private List<String> worlds = List.of();

    StructureCompass(PetEffects manager) {
        super(manager, "structure-compass");
        readyKey = new NamespacedKey(manager.plugin(), "structure_compass_ready");
        targetKey = new NamespacedKey(manager.plugin(), "structure_compass_target");
    }

    @Override
    protected void onLoad() {
        types.clear();
        names.clear();
        item = Material.matchMaterial(getString("item", "compass"));
        if (item == null) {
            item = Material.COMPASS;
        }
        radius = Math.max(1, getInt("radius", 100));
        directions = cfg == null ? List.of() : cfg.getStringList("directions");
        worlds = cfg == null ? List.of() : cfg.getStringList("worlds");
        if (cfg == null) {
            return;
        }
        Registry<StructureType> registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.STRUCTURE_TYPE);
        for (String id : cfg.getStringList("structure-types")) {
            NamespacedKey key = NamespacedKey.fromString(id.toLowerCase(Locale.ROOT));
            StructureType type = key == null ? null : registry.get(key);
            if (type == null) {
                manager.plugin().getLogger().warning("structure-compass: unknown structure type " + id);
            } else {
                types.add(type);
            }
        }
        ConfigurationSection section = cfg.getConfigurationSection("names");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                names.put(id.toLowerCase(Locale.ROOT), section.getString(id, id));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.isSneaking() || player.getInventory().getItemInMainHand().getType() != item) {
            return;
        }
        // Maj + clic droit sur une magnétite : la boussole s'y lie, on ne s'en mêle pas
        if (event.getClickedBlock() != null && event.getClickedBlock().getType() == Material.LODESTONE) {
            return;
        }
        int level = level(player);
        if (level <= 0) {
            return;
        }
        PersistentDataContainer data = player.getPersistentDataContainer();
        long left = data.getOrDefault(readyKey, PersistentDataType.LONG, 0L) - System.currentTimeMillis();
        if (left > 0) {
            actionBar(player, "cooldown-message", "%time%", duration(left));
            return;
        }
        World world = player.getWorld();
        if (!worlds.isEmpty() && worlds.stream().noneMatch(w -> w.equalsIgnoreCase(world.getName()))) {
            actionBar(player, "world-message");
            return;
        }
        StructureSearchResult found = nearest(player.getLocation());
        if (found == null) {
            actionBar(player, "none-message");
            return;
        }
        Location target = found.getLocation();
        double cooldown = value(level);
        data.set(readyKey, PersistentDataType.LONG, System.currentTimeMillis() + Math.round(cooldown * 1000));
        data.set(targetKey, PersistentDataType.STRING,
                world.getName() + ";" + target.getBlockX() + ";" + target.getBlockZ());
        player.setCompassTarget(target);
        announce(player, found.getStructure(), target, cooldown);
    }

    /** Structure la plus proche parmi les types retenus, null si aucune dans le rayon. */
    private StructureSearchResult nearest(Location origin) {
        long start = System.currentTimeMillis();
        StructureSearchResult best = null;
        double bestDistance = Double.MAX_VALUE;
        for (StructureType type : types) {
            StructureSearchResult result;
            try {
                result = origin.getWorld().locateNearestStructure(origin, type, radius, false);
            } catch (RuntimeException e) {
                continue;
            }
            if (result == null) {
                continue;
            }
            double distance = horizontal(origin, result.getLocation());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = result;
            }
        }
        long took = System.currentTimeMillis() - start;
        if (took > SLOW_MILLIS) {
            manager.plugin().getLogger().warning("structure-compass: search took " + took + " ms in "
                    + origin.getWorld().getName() + " (lower radius or structure-types in effects.yml).");
        }
        return best;
    }

    private void announce(Player player, Structure structure, Location target, double cooldown) {
        Location from = player.getLocation();
        String x = String.valueOf(target.getBlockX());
        String z = String.valueOf(target.getBlockZ());
        String msg = getString("found-message", "");
        if (msg != null && !msg.isEmpty()) {
            String coords = "[X: " + x + ", Z: " + z + "]";
            msg = msg.replace("%structure%", name(structure))
                    .replace("%distance%", String.valueOf(Math.round(horizontal(from, target))))
                    .replace("%direction%", direction(from, target))
                    .replace("%time%", duration(Math.round(cooldown * 1000)))
                    .replace("%coords%", coords);
            Component hover = Texts.of(getString("coords-hover", ""));
            player.sendMessage(Texts.of(msg).replaceText(b -> b.matchLiteral(coords).once()
                    .replacement((match, built) -> built
                            .clickEvent(ClickEvent.copyToClipboard(x + " ~ " + z))
                            .hoverEvent(HoverEvent.showText(hover)))));
        }
        player.playSound(from, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.2f);
        // traînée vers la structure, pour ce joueur seulement
        Vector step = new Vector(target.getX() - from.getX(), 0, target.getZ() - from.getZ());
        if (step.lengthSquared() > 1) {
            step.normalize().multiply(0.5);
            Location point = player.getEyeLocation().subtract(0, 0.4, 0);
            for (int i = 0; i < 24; i++) {
                point.add(step);
                player.spawnParticle(Particle.END_ROD, point, 1, 0, 0, 0, 0);
            }
        }
    }

    /** Nom affiché : celui de la section names (par id de structure), sinon l'id en clair. */
    private String name(Structure structure) {
        NamespacedKey key = RegistryAccess.registryAccess().getRegistry(RegistryKey.STRUCTURE).getKey(structure);
        String id = key == null ? "?" : key.getKey();
        String named = names.get(id);
        return named != null ? named : id.replace('_', ' ');
    }

    /** Direction de la structure vue du joueur (nord = -Z, est = +X), libellés de la liste directions. */
    private String direction(Location from, Location to) {
        double angle = Math.toDegrees(Math.atan2(to.getX() - from.getX(), -(to.getZ() - from.getZ())));
        int index = Math.floorMod(Math.round((float) angle / 45f), 8);
        return index < directions.size() ? directions.get(index) : DIRECTIONS[index];
    }

    private static double horizontal(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** « 1 h 05 min », « 12 min », « 45 s ». */
    private static String duration(long millis) {
        long seconds = Math.max(1, (millis + 999) / 1000);
        if (seconds < 60) {
            return seconds + " s";
        }
        long minutes = (seconds + 59) / 60;
        return minutes < 60 ? minutes + " min" : minutes / 60 + " h " + String.format("%02d", minutes % 60) + " min";
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        restoreTarget(event.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        restoreTarget(event.getPlayer());
    }

    private void restoreTarget(Player player) {
        String stored = player.getPersistentDataContainer().get(targetKey, PersistentDataType.STRING);
        if (stored == null) {
            return;
        }
        String[] parts = stored.split(";");
        if (parts.length != 3 || !parts[0].equals(player.getWorld().getName())) {
            return;
        }
        try {
            Location target = new Location(player.getWorld(), Integer.parseInt(parts[1]), 64, Integer.parseInt(parts[2]));
            // au tick suivant : à la connexion et au changement de monde le serveur repose la cible sur le spawn
            Bukkit.getScheduler().runTask(manager.plugin(), () -> {
                if (player.isOnline() && player.getWorld().equals(target.getWorld())) {
                    player.setCompassTarget(target);
                }
            });
        } catch (NumberFormatException ignored) {
            // donnée illisible : la boussole garde sa cible par défaut
        }
    }
}

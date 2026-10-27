package fr.influencraft.fluxshop.commands;

import fr.influencraft.fluxshop.managers.LangManager;
import fr.influencraft.fluxshop.managers.TpShopCooldownManager;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@code /supermarket tpshop [joueur]} : simple téléportation vers le point du magasin
 * ({@code tpshop.location}, posé par {@code /supermarket settpshop}). Utilisée par le menu
 * {@code /marche}. Les joueurs ont un délai d'attente immobile et un cooldown ; les admins non.
 */
public class TpShopCommand implements SupermarketCommand {

    private static final String LOCATION_PATH = "tpshop.location";

    private final JavaPlugin plugin;
    private final LangManager lang;
    private final TpShopCooldownManager cooldownManager;
    private final Set<UUID> pendingTeleports = new HashSet<>();

    public TpShopCommand(JavaPlugin plugin, LangManager lang, TpShopCooldownManager cooldownManager) {
        this.plugin = plugin;
        this.lang = lang;
        this.cooldownManager = cooldownManager;
    }

    @Override
    public String getName() {
        return "tpshop";
    }

    @Override
    public boolean execute(Player player, String[] args) {
        if (args.length == 1) {
            selfTeleport(player);
        } else if (args.length == 2) {
            adminTeleportOther(player, args[1]);
        } else {
            player.sendMessage(lang.get("shop-tpshop-usage"));
        }
        return true;
    }

    /** Enregistre la position actuelle du joueur comme point d'arrivée de /tpshop. */
    public void setLocation(Location location) {
        plugin.getConfig().set(LOCATION_PATH + ".world", location.getWorld().getName());
        plugin.getConfig().set(LOCATION_PATH + ".x", location.getX());
        plugin.getConfig().set(LOCATION_PATH + ".y", location.getY());
        plugin.getConfig().set(LOCATION_PATH + ".z", location.getZ());
        plugin.getConfig().set(LOCATION_PATH + ".yaw", (double) location.getYaw());
        plugin.getConfig().set(LOCATION_PATH + ".pitch", (double) location.getPitch());
        plugin.saveConfig();
    }

    /** @return le point d'arrivée, ou {@code null} s'il n'est pas défini ou que son monde n'est pas chargé. */
    private Location destination() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection(LOCATION_PATH);
        if (section == null) {
            return null;
        }
        World world = Bukkit.getWorld(section.getString("world", ""));
        if (world == null) {
            return null;
        }
        return new Location(world, section.getDouble("x"), section.getDouble("y"), section.getDouble("z"),
                (float) section.getDouble("yaw"), (float) section.getDouble("pitch"));
    }

    private void selfTeleport(Player player) {
        boolean isAdmin = player.hasPermission("supermarket.admin");
        if (!isAdmin && !player.hasPermission("supermarket.tpshop")) {
            player.sendMessage(lang.get("shop-no-permission"));
            return;
        }
        if (destination() == null) {
            player.sendMessage(lang.get("shop-tpshop-not-set"));
            return;
        }
        if (!isAdmin && cooldownManager.isOnCooldown(player)) {
            player.sendMessage(lang.get("shop-tpshop-cooldown", Map.of(
                    "time", TpShopCooldownManager.formatDuration(cooldownManager.getRemainingSeconds(player)))));
            return;
        }
        if (!isAdmin && pendingTeleports.contains(player.getUniqueId())) {
            player.sendMessage(lang.get("shop-tpshop-already-teleporting"));
            return;
        }

        long warmupSeconds = plugin.getConfig().getLong("tpshop-warmup-seconds", 3);
        if (isAdmin || warmupSeconds <= 0) {
            teleport(player, isAdmin);
            return;
        }
        startWarmup(player, warmupSeconds);
    }

    private void startWarmup(Player player, long warmupSeconds) {
        UUID uuid = player.getUniqueId();
        pendingTeleports.add(uuid);
        Location start = player.getLocation();

        player.sendMessage(lang.get("shop-tpshop-warmup-start", Map.of("time", String.valueOf(warmupSeconds))));

        new BukkitRunnable() {
            long remaining = warmupSeconds;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    pendingTeleports.remove(uuid);
                    cancel();
                    return;
                }
                if (hasMoved(player, start)) {
                    pendingTeleports.remove(uuid);
                    player.sendMessage(lang.get("shop-tpshop-warmup-cancelled"));
                    cancel();
                    return;
                }
                if (remaining <= 0) {
                    pendingTeleports.remove(uuid);
                    cancel();
                    teleport(player, false);
                    return;
                }
                player.sendActionBar(lang.get("shop-tpshop-warmup-actionbar", Map.of("time", String.valueOf(remaining))));
                remaining--;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private boolean hasMoved(Player player, Location start) {
        Location current = player.getLocation();
        if (current.getWorld() == null || !current.getWorld().equals(start.getWorld())) {
            return true;
        }
        return current.distanceSquared(start) > 0.09; // ~0.3 bloc de tolérance
    }

    private void teleport(Player player, boolean isAdmin) {
        Location destination = destination();
        if (destination == null) {
            player.sendMessage(lang.get("shop-tpshop-not-set"));
            return;
        }
        player.teleportAsync(destination).thenAccept(success -> {
            if (!success) {
                player.sendMessage(lang.get("shop-tpshop-failed"));
                return;
            }
            if (!isAdmin) {
                cooldownManager.markUsed(player);
            }
            player.sendMessage(lang.get("shop-tpshop-self-success"));
        });
    }

    private void adminTeleportOther(Player player, String targetName) {
        if (!player.hasPermission("supermarket.admin")) {
            player.sendMessage(lang.get("shop-no-permission"));
            return;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            player.sendMessage(lang.get("shop-player-not-found", Map.of("player", targetName)));
            return;
        }
        Location destination = destination();
        if (destination == null) {
            player.sendMessage(lang.get("shop-tpshop-not-set"));
            return;
        }
        target.teleportAsync(destination).thenAccept(success -> {
            if (!success) {
                player.sendMessage(lang.get("shop-tpshop-failed"));
                return;
            }
            player.sendMessage(lang.get("shop-tpshop-success", Map.of("player", target.getName())));
            target.sendMessage(lang.get("shop-tpshop-target-notice", Map.of("player", player.getName())));
        });
    }
}

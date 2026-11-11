package fr.influencraft.fluxdungeons.rpg;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.command.Commands;
import fr.influencraft.flux.api.universe.Universes;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.generator.ChunkGenerator;

/**
 * The lobby of the rpg universe: an empty world with a stone platform, created at start when missing (name
 * {@code rpg.world}, declared as {@code rpg} in Flux's universes.yml). {@code /rpg} goes there, {@code /rpg quitter}
 * back to where the player last stood in the vanilla universe. Falling into the void brings him back to the platform.
 */
public final class RpgWorld implements Listener {

    public static final String UNIVERSE = "rpg";
    private static final int PLATFORM_Y = 64, PLATFORM_RADIUS = 12;

    private static final class Empty extends ChunkGenerator {
    }

    private final FluxPlugin plugin;
    private final String name;
    private World world;

    public RpgWorld(FluxPlugin plugin, String name) {
        this.plugin = plugin;
        this.name = name;
    }

    public void start() {
        world = Bukkit.getWorld(name);
        if (world == null) {
            world = new WorldCreator(name).generator(new Empty()).generateStructures(false).createWorld();
            if (world == null) {
                plugin.getLogger().severe("Rpg lobby world " + name + " could not be created");
                return;
            }
            plugin.getLogger().info("Rpg lobby world " + name + " created");
        }
        rules(world);
        platform();
        if (!UNIVERSE.equals(Universes.of(world))) {
            plugin.getLogger().warning("World " + name + " is not in the \"" + UNIVERSE + "\" universe: add it to plugins/Flux/universes.yml");
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Commands.create(plugin, "rpg").description("Rpg lobby: go there, or back to the main world.")
                .usage("/rpg [quitter]").executor(this::command).register();
    }

    public World world() {
        return world;
    }

    public Location spawn() {
        return new Location(world, 0.5, PLATFORM_Y + 1, 0.5, 180, 0);
    }

    private static void rules(World w) {
        w.setGameRule(GameRules.SPAWN_MOBS, false);
        w.setGameRule(GameRules.SPAWN_MONSTERS, false);
        w.setGameRule(GameRules.SPAWN_PATROLS, false);
        w.setGameRule(GameRules.SPAWN_PHANTOMS, false);
        w.setGameRule(GameRules.SPAWN_WANDERING_TRADERS, false);
        w.setGameRule(GameRules.ADVANCE_WEATHER, false);
        w.setGameRule(GameRules.ADVANCE_TIME, false);
        w.setGameRule(GameRules.KEEP_INVENTORY, true);
        w.setGameRule(GameRules.IMMEDIATE_RESPAWN, true);
        w.setGameRule(GameRules.MOB_GRIEFING, false);
        w.setGameRule(GameRules.FALL_DAMAGE, false);
        w.setTime(6000);
        w.setStorm(false);
        w.setDifficulty(Difficulty.PEACEFUL);
        w.setSpawnLocation(0, PLATFORM_Y + 1, 0);
    }

    /** A round stone platform around 0,0, built once (left alone afterwards: the staff may decorate it). */
    private void platform() {
        if (world.getBlockAt(0, PLATFORM_Y, 0).getType() != Material.AIR) return;
        int r2 = PLATFORM_RADIUS * PLATFORM_RADIUS;
        for (int x = -PLATFORM_RADIUS; x <= PLATFORM_RADIUS; x++) {
            for (int z = -PLATFORM_RADIUS; z <= PLATFORM_RADIUS; z++) {
                int d2 = x * x + z * z;
                if (d2 > r2) continue;
                Block b = world.getBlockAt(x, PLATFORM_Y, z);
                b.setType(d2 > (PLATFORM_RADIUS - 1) * (PLATFORM_RADIUS - 1) ? Material.STONE_BRICKS : Material.STONE, false);
            }
        }
        plugin.getLogger().info("Rpg lobby platform built in " + name);
    }

    /** The void brings a player back to the platform (next tick: never teleport inside a damage event chain). */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onVoid(EntityDamageEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.VOID || !(e.getEntity() instanceof Player p) || p.getWorld() != world) return;
        e.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline() && p.getWorld() == world) {
                p.setFallDistance(0);
                p.teleport(spawn(), PlayerTeleportEvent.TeleportCause.PLUGIN);
            }
        });
    }

    private boolean command(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (world == null) {
            plugin.messages().send(p, "rpg.unavailable");
            return true;
        }
        if (Universes.transitioning(p)) return true;
        boolean leave = args.length > 0 && (args[0].equalsIgnoreCase("quitter") || args[0].equalsIgnoreCase("leave"));
        if (!leave) {
            if (UNIVERSE.equals(Universes.of(p.getWorld())) && p.getWorld() != world) {
                plugin.messages().send(p, "rpg.busy-elsewhere");
                return true;
            }
            p.teleport(spawn(), PlayerTeleportEvent.TeleportCause.COMMAND);
            return true;
        }
        if (!UNIVERSE.equals(Universes.of(p))) {
            plugin.messages().send(p, "rpg.not-in-rpg");
            return true;
        }
        if (p.getWorld() != world) {
            plugin.messages().send(p, "rpg.busy-elsewhere");
            return true;
        }
        Location back = Universes.returnLocation(p, Universes.VANILLA);
        if (back == null) back = Bukkit.getWorlds().get(0).getSpawnLocation();
        p.teleport(back, PlayerTeleportEvent.TeleportCause.COMMAND);
        return true;
    }
}

package fr.influencraft.fluxdungeons.api;

import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * What other plugins may ask FluxDungeons: is a player in a dungeon run (his inventory is then his dungeon
 * inventory, his life and stats are his class's), is a world the dungeon world. Pets, AFK, rewards and quests use it
 * to stay out of runs. Absent (false) when FluxDungeons does not run.
 */
public final class Dungeons {

    public interface Service {
        boolean playing(Player player);

        boolean dungeonWorld(World world);
    }

    private static volatile Service service;

    private Dungeons() {
    }

    public static void register(Service s) {
        service = s;
    }

    public static boolean playing(Player player) {
        Service s = service;
        return s != null && s.playing(player);
    }

    public static boolean dungeonWorld(World world) {
        Service s = service;
        return s != null && s.dungeonWorld(world);
    }
}

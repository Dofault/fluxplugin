package fr.influencraft.tri;

import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;
import nl.rutgerkok.blocklocker.BlockLockerAPIv2;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;

/**
 * Appels vers les plugins optionnels, isolés dans des classes chargées seulement
 * si le plugin est présent (BlockLocker et CoreProtect n'existent pas sur Labs).
 */
final class Hooks {

    private Hooks() {
    }

    static boolean isEnabled(String name) {
        Plugin p = Bukkit.getPluginManager().getPlugin(name);
        return p != null && p.isEnabled();
    }

    /** Vrai si BlockLocker verrouille ce bloc : un coffre verrouillé n'est jamais une cible. */
    static boolean isLocked(Block block) {
        return isEnabled("BlockLocker") && BlockLockerHook.isLocked(block);
    }

    static void logContainer(String user, Location location) {
        if (isEnabled("CoreProtect")) CoreProtectHook.log(user, location);
    }

    private static final class BlockLockerHook {
        static boolean isLocked(Block block) {
            return BlockLockerAPIv2.isProtected(block);
        }
    }

    private static final class CoreProtectHook {
        static void log(String user, Location location) {
            CoreProtectAPI api = ((CoreProtect) Bukkit.getPluginManager().getPlugin("CoreProtect")).getAPI();
            if (api != null && api.isEnabled()) api.logContainerTransaction(user, location);
        }
    }
}

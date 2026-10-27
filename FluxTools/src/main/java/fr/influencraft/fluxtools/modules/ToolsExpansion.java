package fr.influencraft.fluxtools.modules;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Placeholders {@code %fluxtools_...%} of the utility modules (formerly {@code %influbridge_...%}):
 * <ul>
 *   <li>{@code compass_north}: frame (1-32) of the HUD compass pointing north, from the player's yaw</li>
 *   <li>{@code empty_slots}: empty slots among the 36 storage slots (hotbar + main inventory). PlaceholderAPI's
 *   own {@code %player_empty_slots%} also counts armour, off-hand and the body slot.</li>
 * </ul>
 */
final class ToolsExpansion {

    /** Provider registered with Flux as {@code fluxtools} (resolved without PlaceholderAPI, exposed to it). */
    String request(Player player, String params) {
        if (params.equals("compass_north")) {
            return player == null ? "" : String.valueOf(compassFrame(player.getLocation().getYaw()));
        }
        if (params.equals("empty_slots")) {
            return player == null ? "" : String.valueOf(emptyStorageSlots(player));
        }
        return null;
    }

    /**
     * Same bearing-to-frame formula as CompassWaypointExtension (Typewriter), with a fixed heading of 180
     * (north in Minecraft's yaw convention: 0 = south, 90 = west, 180 = north, 270 = east). Returns 1-32.
     */
    private static int compassFrame(float yaw) {
        double yawNorm = ((yaw % 360.0) + 360.0) % 360.0;
        double relative = ((180.0 - yawNorm + 180.0) % 360.0 + 360.0) % 360.0;
        int frame = (int) Math.round(relative / 11.25) % 32;
        return frame + 1;
    }

    /** getStorageContents() only covers the 36 hotbar + main inventory slots, unlike getContents(). */
    private static int emptyStorageSlots(Player player) {
        int empty = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item == null || item.getType() == Material.AIR) empty++;
        }
        return empty;
    }
}

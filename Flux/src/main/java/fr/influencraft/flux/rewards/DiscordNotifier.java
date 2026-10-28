package fr.influencraft.flux.rewards;

import java.util.UUID;

/** Private Discord message to a player, with a button to stop these messages. Implemented over DiscordSRV. */
interface DiscordNotifier {

    /** Sends the message when the player's Discord account is linked; off the main thread. */
    void send(UUID player, String message, String buttonLabel);

    /** Loads the DiscordSRV implementation when DiscordSRV is installed, else null. */
    static DiscordNotifier create(org.bukkit.plugin.Plugin flux, RewardStore store, java.util.function.Supplier<String> doneText) {
        if (org.bukkit.Bukkit.getPluginManager().getPlugin("DiscordSRV") == null) return null;
        try {
            return new DiscordSrvNotifier(flux, store, doneText);
        } catch (LinkageError e) {
            flux.getLogger().warning("DiscordSRV found but its API is not the expected one, no Discord reminder: " + e);
            return null;
        }
    }
}

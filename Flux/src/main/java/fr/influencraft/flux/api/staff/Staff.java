package fr.influencraft.flux.api.staff;

import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.Sounds;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Alerts for the staff, from any plugin of the suite: {@code Staff.alert("referral-origin", "Player origin", "...")}
 * tells every online player with {@code flux.staff.alerts} (chat line and sound) and the console. Configured once in
 * Flux ({@code staff} section of {@code plugins/Flux/config.yml}): format, sound, and per topic a {@code chat} switch.
 * Safe from any thread. The message is Flux text ({@code &}/MiniMessage); escape what a player typed.
 */
public final class Staff {

    public static final String PERMISSION = "flux.staff.alerts";
    private static final String FORMAT = "&#FF5C5Cꜱᴛᴀꜰꜰ &8▸ &#FFD94D{title} &8» &#B376E5{message}";

    private static volatile ConfigurationSection config;

    private Staff() {
    }

    /** Called by Flux from its config (section {@code staff}). */
    public static void configure(ConfigurationSection section) {
        config = section;
    }

    public static void alert(String topic, String title, String message) {
        ConfigurationSection c = config;
        if (c != null && !c.getBoolean("topics." + topic + ".chat", true)) return;
        String format = c == null ? FORMAT : c.getString("format", FORMAT);
        Component text = Texts.parse(format.replace("{title}", title).replace("{message}", message));
        String sound = c == null ? "alert" : c.getString("sound", "alert");

        Runnable toStaff = () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!p.hasPermission(PERMISSION)) continue;
                p.sendMessage(text);
                if (sound.isEmpty()) continue;
                if (sound.contains(":") || sound.contains(" ")) Sounds.play(p, sound);
                else Sounds.cue(p, sound);
            }
            Bukkit.getConsoleSender().sendMessage(text);
        };
        Plugin flux = Bukkit.getPluginManager().getPlugin("Flux");
        if (Bukkit.isPrimaryThread() || flux == null) toStaff.run();
        else if (flux.isEnabled()) Bukkit.getScheduler().runTask(flux, toStaff);
    }
}

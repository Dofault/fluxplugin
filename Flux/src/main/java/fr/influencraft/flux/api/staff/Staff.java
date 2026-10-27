package fr.influencraft.flux.api.staff;

import fr.influencraft.flux.api.discord.Discord;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.Sounds;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;

/**
 * Alerts for the staff, from any plugin of the suite: {@code Staff.alert("referral-origin", "New player", "...")}
 * tells every online player with {@code flux.staff.alerts} (chat line and sound), the console, and the staff Discord
 * channel (webhook, see {@link Discord}). Configured once in Flux ({@code staff} section of
 * {@code plugins/Flux/config.yml}): format, sound, webhook, and per topic {@code chat}/{@code discord} switches.
 * Safe from any thread. The message is Flux text ({@code &}/MiniMessage); escape what a player typed.
 */
public final class Staff {

    public static final String PERMISSION = "flux.staff.alerts";

    private static volatile ConfigurationSection config;

    private Staff() {
    }

    /** Called by Flux from its config (section {@code staff}). */
    public static void configure(ConfigurationSection section) {
        config = section;
    }

    public static void alert(String topic, String title, String message) {
        alert(topic, title, message, 0x2BC4E8, List.of());
    }

    /**
     * @param color  colour of the Discord embed (0xRRGGBB)
     * @param fields extra details, shown as fields on Discord and appended to the chat line
     */
    public static void alert(String topic, String title, String message, int color, List<Discord.Field> fields) {
        ConfigurationSection c = config;
        ConfigurationSection t = c == null ? null : c.getConfigurationSection("topics." + topic);
        boolean chat = t == null || t.getBoolean("chat", true);
        String webhook = t != null && t.isString("discord") ? t.getString("discord") : c == null ? "staff" : c.getString("discord", "staff");

        StringBuilder line = new StringBuilder(message);
        for (Discord.Field f : fields) line.append(" &8| &7").append(f.name()).append(" : &f").append(f.value());
        String format = c == null ? "&#FF5C5Cꜱᴛᴀꜰꜰ &8▸ &#FFD94D{title} &8» &#B376E5{message}"
                : c.getString("format", "&#FF5C5Cꜱᴛᴀꜰꜰ &8▸ &#FFD94D{title} &8» &#B376E5{message}");
        Component text = Texts.parse(format.replace("{title}", title).replace("{message}", line.toString()));
        String sound = c == null ? "alert" : c.getString("sound", "alert");

        if (chat) {
            Plugin flux = Bukkit.getPluginManager().getPlugin("Flux");
            Runnable toPlayers = () -> {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!p.hasPermission(PERMISSION)) continue;
                    p.sendMessage(text);
                    if (!sound.isEmpty()) {
                        if (sound.startsWith("minecraft:") || sound.contains(" ")) Sounds.play(p, sound);
                        else Sounds.cue(p, sound);
                    }
                }
            };
            if (Bukkit.isPrimaryThread() || flux == null) toPlayers.run();
            else if (flux.isEnabled()) Bukkit.getScheduler().runTask(flux, toPlayers);
        }
        Bukkit.getConsoleSender().sendMessage(text);

        if (Discord.available(webhook)) {
            String plain = PlainTextComponentSerializer.plainText().serialize(Texts.parse(message));
            List<Discord.Field> plainFields = fields.stream()
                    .map(f -> new Discord.Field(f.name(), PlainTextComponentSerializer.plainText().serialize(Texts.parse(f.value())), f.inline()))
                    .toList();
            Discord.send(webhook, new Discord.Embed(title, plain, color, plainFields));
        }
    }
}

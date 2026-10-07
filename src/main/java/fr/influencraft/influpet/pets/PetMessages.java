package fr.influencraft.influpet.pets;

import fr.influencraft.influpet.Texts;
import org.bukkit.command.CommandSender;

/** Messages du système de familiers (section messages de familiers-reglages.yml). */
public final class PetMessages {

    private PetMessages() {
    }

    public static String format(String key, String... replacements) {
        String msg = Pets.settings() == null ? key : Pets.settings().getString("messages." + key);
        if (msg == null || msg.isEmpty()) {
            msg = key;
        }
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            msg = msg.replace(replacements[i], replacements[i + 1]);
        }
        return msg;
    }

    public static void send(CommandSender to, String key, String... replacements) {
        String prefix = Pets.settings() == null ? "" : Pets.settings().getString("messages.prefix");
        to.sendMessage(Texts.of((prefix == null ? "" : prefix) + format(key, replacements)));
    }
}

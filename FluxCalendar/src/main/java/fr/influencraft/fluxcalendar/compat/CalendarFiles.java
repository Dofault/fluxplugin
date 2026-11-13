// COMPAT 2026-11-13: calendars kept in files (joueurs/<uuid>.yml, Labs only, production never had them) - remove
// when the Labs testers logged in once after the move to the database, at the latest at go-live.
package fr.influencraft.fluxcalendar.compat;

import fr.influencraft.fluxcalendar.PlayerData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.UUID;

/** Reads a calendar from its former file, null when there is none (written to the database by the next save). */
public final class CalendarFiles {

    private CalendarFiles() {
    }

    public static PlayerData read(Plugin plugin, UUID uuid) {
        File f = new File(new File(plugin.getDataFolder(), "joueurs"), uuid + ".yml");
        if (!f.exists()) return null;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        return PlayerData.of(y.getInt("jours", 0), y.getString("dernier-jour"), y.getString("mois"), y.getString("debut"),
                y.getIntegerList("jours-recuperes"));
    }
}

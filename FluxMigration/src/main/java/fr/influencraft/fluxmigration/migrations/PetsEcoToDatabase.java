package fr.influencraft.fluxmigration.migrations;

import com.willfp.eco.core.data.PlayerProfile;
import com.willfp.eco.core.data.keys.PersistentDataKey;
import com.willfp.eco.core.data.keys.PersistentDataKeyType;
import fr.influencraft.fluxmigration.FluxMigration;
import fr.influencraft.fluxmigration.Migration;
import fr.influencraft.fluxpets.api.PetImport;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 2026-11-13: pets of the players, kept by InfluPet / FluxPets in eco's player profile ({@code influpet:<pet>_level},
 * {@code _xp}, {@code active_pets}, {@code active_pet}, {@code hide_pet}; EcoPets' {@code ecopets:} keys for a player
 * InfluPet never saw), copied into FluxPets' tables. Every player the server knows is read (eco loads his profile from
 * its storage, MySQL in production): may take a while, once. A player FluxPets already has is left as he is.
 * Remove once production ran it.
 */
public final class PetsEcoToDatabase implements Migration {

    private final FluxMigration plugin;

    public PetsEcoToDatabase(FluxMigration plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "2026-11-13_pets_eco_to_database";
    }

    @Override
    public String description() {
        return "FluxPets data in eco's player profile -> flux_pets_levels / flux_pets_players";
    }

    @Override
    public boolean ready() {
        return Bukkit.getPluginManager().isPluginEnabled("eco") && Bukkit.getPluginManager().isPluginEnabled("FluxPets")
                && PetImport.available() && !PetImport.ids().isEmpty();
    }

    private static NamespacedKey key(String ns, String value) {
        return new NamespacedKey(ns, value);
    }

    @Override
    public String run() {
        List<String> pets = PetImport.ids();
        Map<String, PersistentDataKey<Integer>> levels = new HashMap<>(), oldLevels = new HashMap<>();
        Map<String, PersistentDataKey<Double>> xps = new HashMap<>(), oldXps = new HashMap<>();
        for (String id : pets) {
            levels.put(id, new PersistentDataKey<>(key("influpet", id + "_level"), PersistentDataKeyType.INT, 0));
            xps.put(id, new PersistentDataKey<>(key("influpet", id + "_xp"), PersistentDataKeyType.DOUBLE, 0.0));
            oldLevels.put(id, new PersistentDataKey<>(key("ecopets", id + "_level"), PersistentDataKeyType.INT, 0));
            oldXps.put(id, new PersistentDataKey<>(key("ecopets", id + "_xp"), PersistentDataKeyType.DOUBLE, 0.0));
        }
        var activeList = new PersistentDataKey<>(key("influpet", "active_pets"), PersistentDataKeyType.STRING_LIST, List.<String>of());
        var active = new PersistentDataKey<>(key("influpet", "active_pet"), PersistentDataKeyType.STRING, "");
        var hidden = new PersistentDataKey<>(key("influpet", "hide_pet"), PersistentDataKeyType.BOOLEAN, false);
        var oldActive = new PersistentDataKey<>(key("ecopets", "active_pet"), PersistentDataKeyType.STRING, "");
        var oldHidden = new PersistentDataKey<>(key("ecopets", "hide_pet"), PersistentDataKeyType.BOOLEAN, false);
        int copied = 0, kept = 0, none = 0;
        for (OfflinePlayer p : Bukkit.getOfflinePlayers()) {
            if (PetImport.known(p.getUniqueId())) {
                kept++;
                continue;
            }
            PlayerProfile profile = PlayerProfile.load(p);
            Map<String, Integer> lv = new HashMap<>();
            Map<String, Double> xp = new HashMap<>();
            boolean influpet = false;
            for (String id : pets) {
                int l = orZero(profile.read(levels.get(id)));
                if (l > 0) influpet = true;
                lv.put(id, l);
                xp.put(id, orZero(profile.read(xps.get(id))));
            }
            if (!influpet) {
                // never seen by InfluPet: EcoPets' own keys
                for (String id : pets) {
                    lv.put(id, orZero(profile.read(oldLevels.get(id))));
                    xp.put(id, orZero(profile.read(oldXps.get(id))));
                }
            }
            lv.values().removeIf(v -> v <= 0);
            xp.keySet().retainAll(lv.keySet());
            if (lv.isEmpty()) {
                none++;
                continue;
            }
            List<String> act = new ArrayList<>(orEmpty(profile.read(activeList)));
            String single = influpet ? profile.read(active) : profile.read(oldActive);
            if (act.isEmpty() && single != null && !single.isEmpty()) act.add(single);
            act.retainAll(lv.keySet());
            Boolean hide = influpet ? profile.read(hidden) : profile.read(oldHidden);
            PetImport.put(p.getUniqueId(), lv, xp, act, hide != null && hide);
            copied++;
        }
        return copied + " player(s) copied, " + kept + " already there, " + none + " without pets";
    }

    private static int orZero(Integer v) {
        return v == null ? 0 : v;
    }

    private static double orZero(Double v) {
        return v == null ? 0 : v;
    }

    private static List<String> orEmpty(List<String> v) {
        return v == null ? List.of() : v;
    }
}

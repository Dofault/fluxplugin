package fr.influencraft.influlands;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Textures de tête des habitants, pour le matériau FluxMenu {@code head:<texture>} (FluxMenu
 * ne sait afficher que la tête du joueur qui regarde avec {@code player-head}). Joueur
 * connecté : texture lue sur son profil. Hors ligne : profil complété en tâche de fond
 * (cache Paper, puis Mojang), tête simple en attendant.
 */
final class Tetes {

    private final Map<UUID, String> textures = new ConcurrentHashMap<>();
    private final Set<UUID> enCours = ConcurrentHashMap.newKeySet();

    String materiau(UUID uuid) {
        String t = textures.get(uuid);
        if (t != null) return "head:" + t;
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) {
            t = texture(p.getPlayerProfile());
            if (t != null) {
                textures.put(uuid, t);
                return "head:" + t;
            }
        }
        demander(uuid);
        return "PLAYER_HEAD";
    }

    private void demander(UUID uuid) {
        if (!enCours.add(uuid)) return;
        PlayerProfile profil = Bukkit.createProfile(uuid);
        profil.update().whenComplete((complet, err) -> {
            if (complet != null) {
                String t = texture(complet);
                if (t != null) textures.put(uuid, t);
            }
            enCours.remove(uuid);
        });
    }

    private static String texture(PlayerProfile profil) {
        if (profil == null) return null;
        for (ProfileProperty prop : profil.getProperties()) {
            if ("textures".equals(prop.getName())) return prop.getValue();
        }
        return null;
    }
}

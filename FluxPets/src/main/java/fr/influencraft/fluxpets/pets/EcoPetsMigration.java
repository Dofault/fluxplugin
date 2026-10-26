package fr.influencraft.fluxpets.pets;

import com.willfp.eco.core.data.PlayerProfile;
import com.willfp.eco.core.data.keys.PersistentDataKey;
import com.willfp.eco.core.data.keys.PersistentDataKeyType;
import fr.influencraft.fluxpets.FluxPets;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Reprise d'EcoPets, sans EcoPets :
 * - fichiers : au premier démarrage, plugins/EcoPets/pets/*.yml est copié dans plugins/FluxPets/pets/ (même
 *   format), l'ancien dossier est laissé tel quel ;
 * - joueurs : à la connexion, une seule fois par joueur (clé influpet:migration_ecopets), les clés eco d'EcoPets
 *   (ecopets:<familier>_level, _xp, active_pet, hide_pet) sont recopiées dans celles de FluxPets. Rien n'est
 *   écrasé : un familier déjà présent côté FluxPets garde ses valeurs. Les clés EcoPets restent dans la base.
 */
final class EcoPetsMigration implements Listener {

    private final PersistentDataKey<Boolean> migrated = new PersistentDataKey<>(Pets.key("migration_ecopets"),
            PersistentDataKeyType.BOOLEAN, false);
    private final PersistentDataKey<String> oldActive = new PersistentDataKey<>(old("active_pet"),
            PersistentDataKeyType.STRING, "");
    private final PersistentDataKey<Boolean> oldHidden = new PersistentDataKey<>(old("hide_pet"),
            PersistentDataKeyType.BOOLEAN, false);
    private final Map<String, PersistentDataKey<Integer>> oldLevels = new HashMap<>();
    private final Map<String, PersistentDataKey<Double>> oldXp = new HashMap<>();
    private final FluxPets plugin;

    private EcoPetsMigration(FluxPets plugin) {
        this.plugin = plugin;
    }

    private static NamespacedKey old(String value) {
        return new NamespacedKey("ecopets", value);
    }

    static void copyPetFiles(FluxPets plugin) {
        File target = new File(plugin.getDataFolder(), "pets");
        File source = new File(plugin.getDataFolder().getParentFile(), "EcoPets/pets");
        if (target.exists() || !source.isDirectory()) {
            return;
        }
        File[] files = source.listFiles((dir, name) -> name.endsWith(".yml"));
        int count = 0;
        for (File file : files == null ? new File[0] : files) {
            try {
                Pets.copy(file, new File(target, file.getName()));
                count++;
            } catch (IOException e) {
                plugin.getLogger().warning("Could not copy " + file.getName() + ": " + e);
            }
        }
        target.mkdirs();
        plugin.getLogger().info(count + " pet files imported from plugins/EcoPets/pets.");
    }

    static void registerPlayerDataMigration(FluxPets plugin) {
        EcoPetsMigration migration = new EcoPetsMigration(plugin);
        Bukkit.getPluginManager().registerEvents(migration, plugin);
        Bukkit.getOnlinePlayers().forEach(migration::migrate);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        migrate(event.getPlayer());
    }

    private void migrate(Player player) {
        PlayerProfile profile = PlayerProfile.load(player);
        // aucun familier chargé (dossier vide, erreur) : on ne marque pas le joueur, la reprise se refera
        if (Pets.values().isEmpty() || Boolean.TRUE.equals(profile.read(migrated))) {
            return;
        }
        int pets = 0;
        for (Pet pet : Pets.values()) {
            Integer level = profile.read(oldLevels.computeIfAbsent(pet.id(), id ->
                    new PersistentDataKey<>(old(id + "_level"), PersistentDataKeyType.INT, 0)));
            if (level == null || level <= 0 || PetData.level(player, pet) > 0) {
                continue;
            }
            Double xp = profile.read(oldXp.computeIfAbsent(pet.id(), id ->
                    new PersistentDataKey<>(old(id + "_xp"), PersistentDataKeyType.DOUBLE, 0.0)));
            profile.write(pet.levelKey(), Math.min(level, pet.maxLevel()));
            profile.write(pet.xpKey(), xp == null ? 0.0 : xp);
            pets++;
        }
        String active = profile.read(oldActive);
        if (pets > 0 && PetData.active(player) == null && Pets.get(active) != null) {
            PetData.setActive(player, Pets.get(active));
        }
        if (pets > 0 && Boolean.TRUE.equals(profile.read(oldHidden))) {
            PetData.setHidden(player, true);
        }
        profile.write(migrated, true);
        if (pets > 0) {
            plugin.getLogger().info("EcoPets data imported for " + player.getName() + ": " + pets + ".");
        }
    }
}

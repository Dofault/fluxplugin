package fr.influencraft.influpet.pets;

import com.willfp.eco.core.EcoPlugin;
import com.willfp.eco.core.config.ConfigType;
import com.willfp.eco.core.config.TransientConfig;
import com.willfp.eco.core.config.interfaces.Config;
import com.willfp.libreforge.DispatcherKt;
import com.willfp.libreforge.HolderProviderKt;
import com.willfp.libreforge.ProvidedHolder;
import com.willfp.libreforge.SimpleProvidedHolder;
import fr.influencraft.influpet.InfluPet;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Système de familiers d'InfluPet, qui remplace EcoPets (même architecture : définitions dans pets/*.yml,
 * données des joueurs dans eco, effets fournis à libreforge par un holder du familier actif).
 *
 * Ne démarre pas si EcoPets est encore actif (les effets compteraient deux fois) ou si libreforge est absent.
 * libreforge n'est pas forcément dans plugins/ : les plugins eco (EcoJobs...) le chargent eux-mêmes au
 * démarrage, sous le nom de plugin « libreforge ».
 */
public final class Pets {

    private static final Map<String, Pet> PETS = new LinkedHashMap<>();
    private static InfluPet plugin;
    private static EcoPlugin libreforge;
    private static Config settings;
    private static boolean enabled;
    private static boolean providerRegistered;
    private static PetDisplay display;

    private Pets() {
    }

    static NamespacedKey key(String value) {
        return new NamespacedKey("influpet", value.toLowerCase(Locale.ROOT));
    }

    public static boolean enabled() {
        return enabled;
    }

    /** Démarrage (onEnable d'InfluPet, une fois). Renvoie false si le système n'a pas pu démarrer. */
    public static boolean enable(InfluPet influPet) {
        plugin = influPet;
        if (enabled) {
            return true;
        }
        if (Bukkit.getPluginManager().isPluginEnabled("EcoPets")) {
            plugin.getLogger().severe("EcoPets est encore actif : familiers d'InfluPet désactivés (retirer le jar "
                    + "d'EcoPets, ses données sont reprises au démarrage).");
            return false;
        }
        Plugin lf = Bukkit.getPluginManager().getPlugin("libreforge");
        if (!(lf instanceof EcoPlugin eco) || !lf.isEnabled()) {
            plugin.getLogger().warning("libreforge pas encore actif (chargé par les plugins eco) : familiers démarrés "
                    + "dès qu'il le sera.");
            return false;
        }
        libreforge = eco;
        settings = readSettings();
        PetData.registerKeys(settings.getBool("stockage-local"));
        EcoPetsMigration.copyPetFiles(plugin);
        if (!providerRegistered) {
            // comme EcoPets : un seul holder, le familier actif du joueur à son niveau
            HolderProviderKt.registerHolderProvider(dispatcher -> {
                if (!enabled || !(dispatcher.getDispatcher() instanceof Player player)) {
                    return Collections.emptyList();
                }
                PetLevel level = PetData.activeLevel(player);
                return level == null ? Collections.emptyList() : List.<ProvidedHolder>of(new SimpleProvidedHolder(level));
            });
            providerRegistered = true;
        }
        enabled = true;
        load();
        EcoPetsMigration.registerPlayerDataMigration(plugin);
        Bukkit.getPluginManager().registerEvents(new PetListener(), plugin);
        var command = plugin.getCommand("familiers");
        if (command != null) {
            PetCommands commands = new PetCommands();
            command.setExecutor(commands);
            command.setTabCompleter(commands);
        }
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                new PetPlaceholders().register();
            } catch (Throwable t) {
                plugin.getLogger().warning("Placeholders des familiers indisponibles : " + t);
            }
        }
        if (Bukkit.getPluginManager().isPluginEnabled("BetterModel")) {
            try {
                display = new PetDisplay();
            } catch (Throwable t) {
                plugin.getLogger().warning("Modèles des familiers indisponibles : " + t);
            }
        }
        return true;
    }

    /** Arrêt du plugin : retire les modèles affichés. */
    public static void disable() {
        if (display != null) {
            display.shutdown();
        }
        enabled = false;
    }

    /** (Re)lit les réglages et tous les familiers (démarrage, /influpet reload). */
    public static void load() {
        if (plugin == null || libreforge == null) {
            return;
        }
        settings = readSettings();
        PETS.clear();
        File folder = new File(plugin.getDataFolder(), "pets");
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".yml") && !name.startsWith("_"));
        if (files != null) {
            java.util.Arrays.sort(files);
            for (File file : files) {
                String id = file.getName().substring(0, file.getName().length() - 4).toLowerCase(Locale.ROOT);
                try {
                    PETS.put(id, new Pet(id, new TransientConfig(file, ConfigType.YAML), libreforge));
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("Familier " + id + " ignoré : " + e.getMessage());
                }
            }
        }
        plugin.getLogger().info(PETS.size() + " familiers chargés.");
        PetsMenu.update();
        if (display != null) {
            display.reload();
        }
        if (enabled) {
            HolderProviderKt.clearAllHolderCaches();
            Bukkit.getOnlinePlayers().forEach(Pets::refresh);
        }
    }

    public static Collection<Pet> values() {
        return Collections.unmodifiableCollection(PETS.values());
    }

    public static Pet get(String id) {
        return id == null || id.isEmpty() ? null : PETS.get(id.toLowerCase(Locale.ROOT));
    }

    public static List<String> ids() {
        return new ArrayList<>(PETS.keySet());
    }

    public static Config settings() {
        return settings;
    }

    public static InfluPet plugin() {
        return plugin;
    }

    /** Montures autorisées sur le serveur (réglage montures.actives) : rien n'est montable tant que c'est faux. */
    public static boolean mountsEnabled() {
        return settings != null && settings.getBool("montures.actives");
    }

    /** Effets du familier recalculés tout de suite pour ce joueur. */
    static void refresh(Player player) {
        if (enabled && player.isOnline()) {
            HolderProviderKt.refreshHolders(DispatcherKt.toDispatcher(player));
        }
    }

    /**
     * Réglages des familiers : section familiers de la config d'InfluPet (config.yml, textes de lang/, menus de
     * menus/), vue comme une config eco (mêmes chemins que l'ancien familiers-reglages.yml : gui, level-gui,
     * messages, level-up, pet-entity...).
     */
    private static Config readSettings() {
        var section = plugin.getConfig().getConfigurationSection("familiers");
        return section == null ? new TransientConfig() : new TransientConfig(section);
    }

    /** Copie un fichier (utilisé par la reprise d'EcoPets). */
    static void copy(File from, File to) throws IOException {
        Files.createDirectories(to.toPath().getParent());
        Files.copy(from.toPath(), to.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
    }
}

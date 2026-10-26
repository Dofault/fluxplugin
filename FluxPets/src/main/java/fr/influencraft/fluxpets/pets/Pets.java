package fr.influencraft.fluxpets.pets;

import com.willfp.eco.core.EcoPlugin;
import com.willfp.eco.core.config.ConfigType;
import com.willfp.eco.core.config.TransientConfig;
import com.willfp.eco.core.config.interfaces.Config;
import com.willfp.libreforge.DispatcherKt;
import com.willfp.libreforge.HolderProviderKt;
import com.willfp.libreforge.ProvidedHolder;
import com.willfp.libreforge.SimpleProvidedHolder;
import fr.influencraft.fluxpets.FluxPets;
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
 * Système de familiers d'FluxPets, qui remplace EcoPets (même architecture : définitions dans pets/*.yml,
 * données des joueurs dans eco, effets fournis à libreforge par un holder du familier actif).
 *
 * Ne démarre pas si EcoPets est encore actif (les effets compteraient deux fois) ou si libreforge est absent.
 * libreforge n'est pas forcément dans plugins/ : les plugins eco (EcoJobs...) le chargent eux-mêmes au
 * démarrage, sous le nom de plugin « libreforge ».
 */
public final class Pets {

    private static final Map<String, Pet> PETS = new LinkedHashMap<>();
    private static FluxPets plugin;
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

    /** Démarrage (onEnable d'FluxPets, une fois). Renvoie false si le système n'a pas pu démarrer. */
    public static boolean enable(FluxPets influPet) {
        plugin = influPet;
        if (enabled) {
            return true;
        }
        if (Bukkit.getPluginManager().isPluginEnabled("EcoPets")) {
            plugin.getLogger().severe("EcoPets is still enabled: FluxPets pets disabled (remove the EcoPets jar, "
                    + "its data is imported on startup).");
            return false;
        }
        Plugin lf = Bukkit.getPluginManager().getPlugin("libreforge");
        if (!(lf instanceof EcoPlugin eco) || !lf.isEnabled()) {
            plugin.getLogger().warning("libreforge not enabled yet (loaded by eco plugins): pets will start "
                    + "as soon as it is.");
            return false;
        }
        libreforge = eco;
        settings = readSettings();
        PetData.registerKeys(settings.getBool("local-storage"));
        EcoPetsMigration.copyPetFiles(plugin);
        saveDefaultPets();
        fr.influencraft.fluxpets.ConfigFiles.migratePetFiles(new java.io.File(plugin.getDataFolder(), "pets"),
                plugin.getLogger());
        if (!providerRegistered) {
            // comme EcoPets, un holder par familier actif à son niveau (plusieurs familiers actifs : leurs effets
            // s'appliquent ensemble)
            HolderProviderKt.registerHolderProvider(dispatcher -> {
                if (!enabled || !(dispatcher.getDispatcher() instanceof Player player)) {
                    return Collections.emptyList();
                }
                List<ProvidedHolder> holders = new ArrayList<>();
                for (PetLevel level : PetData.activeLevels(player)) {
                    holders.add(new SimpleProvidedHolder(level));
                }
                return holders;
            });
            providerRegistered = true;
        }
        enabled = true;
        load();
        EcoPetsMigration.registerPlayerDataMigration(plugin);
        Bukkit.getPluginManager().registerEvents(new PetListener(), plugin);
        var command = plugin.getCommand("pets");
        if (command != null) {
            PetCommands commands = new PetCommands();
            command.setExecutor(commands);
            command.setTabCompleter(commands);
        }
        if (Bukkit.getPluginManager().isPluginEnabled("BetterModel")) {
            try {
                display = new PetDisplay();
            } catch (Throwable t) {
                plugin.getLogger().warning("Pet models unavailable: " + t);
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
        loadChains();
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
                    plugin.getLogger().warning("Pet " + id + " skipped: " + e.getMessage());
                }
            }
        }
        plugin.getLogger().info(PETS.size() + " pets loaded.");
        PetView.clearCache();
        if (display != null) {
            display.reload();
        }
        if (enabled) {
            HolderProviderKt.clearAllHolderCaches();
            Bukkit.getOnlinePlayers().forEach(Pets::refresh);
        }
    }

    /**
     * Installation neuve (ni pets/, ni plugins/EcoPets/pets à reprendre) : les familiers livrés dans le jar
     * (pets/index.txt, version de la langue choisie dans i18n/<langue>/pets/ si elle existe) sont écrits dans pets/.
     */
    private static void saveDefaultPets() {
        File folder = new File(plugin.getDataFolder(), "pets");
        if (folder.exists()) {
            return;
        }
        String language = plugin.getConfig().getString("language", "en_US");
        String base = fr.influencraft.fluxpets.ConfigFiles.localized(language, "pets/");
        if (plugin.getResource(base + "index.txt") == null) {
            base = "pets/";
        }
        try (var index = plugin.getResource(base + "index.txt")) {
            if (index == null) {
                return;
            }
            folder.mkdirs();
            int count = 0;
            for (String name : new String(index.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\\R")) {
                name = name.trim();
                if (name.isEmpty()) {
                    continue;
                }
                try (var in = plugin.getResource(base + name)) {
                    if (in != null) {
                        java.nio.file.Files.copy(in, new File(folder, name).toPath());
                        count++;
                    }
                }
            }
            plugin.getLogger().info(count + " default pets written to pets/.");
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("Could not write the default pets: " + e);
        }
    }

    /**
     * Chaînes libreforge propres aux familiers (chains.yml d'FluxPets, même format que celui de libreforge :
     * chains: [{id, effects}]), enregistrées auprès de libreforge avant de compiler les familiers : un familier
     * peut les lancer avec run_chain sans toucher à plugins/libreforge/chains.yml.
     */
    private static void loadChains() {
        File file = new File(plugin.getDataFolder(), "chains.yml");
        if (!file.exists()) {
            return;
        }
        Config config = new TransientConfig(file, ConfigType.YAML);
        int count = 0;
        for (Config chain : config.getSubsections("chains")) {
            String id = chain.getString("id");
            if (id.isEmpty()) {
                continue;
            }
            var compiled = com.willfp.libreforge.effects.Effects.INSTANCE.compileChain(chain.getSubsections("effects"),
                    new com.willfp.libreforge.ViolationContext(libreforge, "FluxPets chain " + id));
            if (compiled != null) {
                com.willfp.libreforge.effects.Effects.INSTANCE.register(id, compiled);
                count++;
            }
        }
        if (count > 0) {
            plugin.getLogger().info(count + " libreforge chains registered from chains.yml.");
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

    /**
     * Placeholders %influpet_...%, enregistrés dès le démarrage du plugin (les familiers peuvent ne démarrer
     * qu'au premier tick).
     */
    public static void registerPlaceholders(FluxPets influPet) {
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return;
        }
        try {
            new PetPlaceholders(influPet).register();
        } catch (Throwable t) {
            influPet.getLogger().warning("Pet placeholders unavailable: " + t);
        }
    }

    public static FluxPets plugin() {
        return plugin;
    }

    /** Montures autorisées sur le serveur (réglage pet-settings.mounts.enabled) : rien n'est montable tant que c'est faux. */
    public static boolean mountsEnabled() {
        return settings != null && settings.getBool("mounts.enabled");
    }

    /** Effets du familier recalculés tout de suite pour ce joueur. */
    static void refresh(Player player) {
        if (enabled && player.isOnline()) {
            HolderProviderKt.refreshHolders(DispatcherKt.toDispatcher(player));
        }
    }

    /**
     * Réglages des familiers : section familiers de la config d'FluxPets (config.yml, textes de lang/, menus de
     * menus/), vue comme une config eco (mêmes chemins que l'ancien familiers-reglages.yml : gui, level-gui,
     * messages, level-up, pet-entity...).
     */
    private static Config readSettings() {
        var section = plugin.getConfig().getConfigurationSection("pet-settings");
        return section == null ? new TransientConfig() : new TransientConfig(section);
    }

    /** Copie un fichier (utilisé par la reprise d'EcoPets). */
    static void copy(File from, File to) throws IOException {
        Files.createDirectories(to.toPath().getParent());
        Files.copy(from.toPath(), to.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
    }
}

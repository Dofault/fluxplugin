package fr.influencraft.fluxtools.modules;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;

/**
 * EcoJobs (com.willfp.eco.core.EcoPlugin, via LibreforgePlugin) gère sa config à travers son
 * propre système ("getConfigYml()", confirmé par décompilation), pas via JavaPlugin.getConfig() —
 * ce dernier peut rester vide/désynchronisé du vrai config.yml puisque rien ne garantit qu'eco
 * appelle saveDefaultConfig()/reloadConfig() en interne (piège vécu le 2026-08-13 : la détection
 * du titre du menu natif via getConfig() ne matchait jamais rien, silencieusement). On lit donc le
 * fichier directement sur disque plutôt que de dépendre de ce chemin d'API.
 */
final class EcoJobsConfig {

    private EcoJobsConfig() {
    }

    static YamlConfiguration read() {
        Plugin ecoJobs = Bukkit.getPluginManager().getPlugin("EcoJobs");
        if (ecoJobs == null) {
            return new YamlConfiguration();
        }
        return YamlConfiguration.loadConfiguration(new File(ecoJobs.getDataFolder(), "config.yml"));
    }

    /**
     * Lit directement jobs/<jobId>.yml sur disque, même raison qu'en tête de fichier — ce
     * fichier n'est pas non plus garanti d'être synchronisé via l'API eco.
     */
    static YamlConfiguration readJob(String jobId) {
        Plugin ecoJobs = Bukkit.getPluginManager().getPlugin("EcoJobs");
        if (ecoJobs == null) {
            return new YamlConfiguration();
        }
        return YamlConfiguration.loadConfiguration(new File(ecoJobs.getDataFolder(), "jobs/" + jobId + ".yml"));
    }
}

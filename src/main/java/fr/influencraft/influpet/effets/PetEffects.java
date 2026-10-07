package fr.influencraft.influpet.effets;

import fr.influencraft.influpet.InfluPet;
import fr.influencraft.influpet.PetHook;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Effets de familiers impossibles en config libreforge, codés ici. Réglages : plugins/InfluPet/effets.yml
 * (généré par scripts/influcard/appliquer_effets.py), relus par /influpet reload. Un effet absent du fichier
 * est désactivé. Chargé seulement si EcoPets est actif (niveau du familier actif lu via PetHook).
 */
public final class PetEffects {

    private final InfluPet plugin;
    private final List<PetEffect> effects = new ArrayList<>();
    private final PlacedBlocks placed;
    private int maxLevel = 10;

    public PetEffects(InfluPet plugin) {
        this.plugin = plugin;
        placed = new PlacedBlocks(plugin);
        Bukkit.getPluginManager().registerEvents(placed, plugin);
        FurnaceOwners owners = new FurnaceOwners();
        Bukkit.getPluginManager().registerEvents(owners, plugin);
        add(new XpPierre(this, placed));
        add(new SansMiseAJour(this));
        add(new ReplantCacao(this));
        add(new PoudreOsInstant(this));
        add(new Rage(this));
        add(new BetonInstant(this));
        add(new CuissonRapide(this, owners));
        add(new LivraisonFour(this, owners));
        add(new UsureSavante(this, placed));
        add(new LapisEconome(this));
        add(new NiveauxEconome(this));
        add(new ResistanceAllies(this));
        if (Bukkit.getPluginManager().isPluginEnabled("packetevents")) {
            try {
                add(new NeigePoudreuse(this));
            } catch (Throwable t) {
                plugin.getLogger().warning("Familier neige poudreuse indisponible : " + t);
            }
        }
        reload();
    }

    private void add(PetEffect effect) {
        effects.add(effect);
        Bukkit.getPluginManager().registerEvents(effect, plugin);
    }

    /** Relit effets.yml (au démarrage et à chaque /influpet reload). */
    public void reload() {
        File file = new File(plugin.getDataFolder(), "effets.yml");
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        maxLevel = Math.max(1, cfg.getInt("niveau-max", 10));
        ConfigurationSection all = cfg.getConfigurationSection("effets");
        int active = 0;
        for (PetEffect effect : effects) {
            ConfigurationSection section = all == null ? null : all.getConfigurationSection(effect.key());
            effect.load(section);
            if (section != null) {
                active++;
            }
        }
        if (!file.exists()) {
            plugin.getLogger().warning("effets.yml absent : effets de familiers codés désactivés.");
        } else {
            plugin.getLogger().info(active + " effets de familiers codés chargés.");
        }
    }

    /** Arrêt du plugin : remet ce qui n'existe que côté client (lumières, neige poudreuse). */
    public void shutdown() {
        effects.forEach(PetEffect::shutdown);
    }

    InfluPet plugin() {
        return plugin;
    }

    int maxLevel() {
        return maxLevel;
    }

    /** Niveau (1 à niveau-max) du familier petId s'il est le familier actif du joueur, sinon 0. */
    int level(Player player, String petId) {
        if (petId == null || player == null) {
            return 0;
        }
        int level = PetHook.activeLevel(player, petId);
        return level <= 0 ? 0 : Math.min(level, maxLevel);
    }
}

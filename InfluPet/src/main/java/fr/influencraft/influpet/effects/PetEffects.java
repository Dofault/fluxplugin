package fr.influencraft.influpet.effects;

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
        add(new StoneXp(this, placed));
        add(new NoBlockUpdate(this));
        add(new CocoaReplant(this));
        add(new InstantBoneMeal(this));
        add(new Rage(this));
        add(new InstantConcrete(this));
        add(new FastSmelting(this, owners));
        add(new FurnaceDelivery(this, owners));
        add(new EnchantMastery(this, placed));
        add(new LapisSaver(this));
        add(new LevelSaver(this));
        add(new AllyResistance(this));
        if (Bukkit.getPluginManager().isPluginEnabled("packetevents")) {
            try {
                add(new PowderSnow(this));
            } catch (Throwable t) {
                plugin.getLogger().warning("Powder snow pet effect unavailable: " + t);
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
        File file = new File(plugin.getDataFolder(), "effects.yml");
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        maxLevel = Math.max(1, cfg.getInt("max-level", 10));
        ConfigurationSection all = cfg.getConfigurationSection("effects");
        int active = 0;
        for (PetEffect effect : effects) {
            ConfigurationSection section = all == null ? null : all.getConfigurationSection(effect.key());
            effect.load(section);
            if (section != null) {
                active++;
            }
        }
        if (!file.exists()) {
            plugin.getLogger().warning("effects.yml missing: coded pet effects disabled.");
        } else {
            plugin.getLogger().info(active + " coded pet effects loaded.");
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

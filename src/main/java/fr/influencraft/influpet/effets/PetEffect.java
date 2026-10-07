package fr.influencraft.influpet.effets;

import fr.influencraft.influpet.Texts;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Un effet de familier : section « effets.<key> » de effets.yml (familier, min, max, réglages propres).
 * Écouteur enregistré une seule fois ; sans section, familier null : l'effet ne s'applique à personne.
 */
abstract class PetEffect implements Listener {

    protected final PetEffects manager;
    private final String key;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    protected String petId;
    protected double min;
    protected double max;
    protected ConfigurationSection cfg;

    PetEffect(PetEffects manager, String key) {
        this.manager = manager;
        this.key = key;
    }

    final String key() {
        return key;
    }

    void load(ConfigurationSection section) {
        cfg = section;
        petId = section == null ? null : section.getString("familier");
        min = section == null ? 0 : section.getDouble("min");
        max = section == null ? 0 : section.getDouble("max");
        cooldowns.clear();
        onLoad();
    }

    /** Réglages propres à l'effet (cfg peut être null). */
    protected void onLoad() {
    }

    protected void shutdown() {
    }

    /** Niveau du familier de cet effet s'il est actif chez le joueur, sinon 0. */
    protected int level(Player player) {
        return manager.level(player, petId);
    }

    /** Valeur au niveau donné : min au niveau 1, max au niveau max, linéaire entre les deux. */
    protected double value(int level) {
        int maxLevel = manager.maxLevel();
        if (maxLevel <= 1) {
            return max;
        }
        return min + (Math.max(1, Math.min(level, maxLevel)) - 1) * (max - min) / (maxLevel - 1);
    }

    /** Tirage d'une chance en % (value du niveau). */
    protected boolean roll(int level) {
        return level > 0 && ThreadLocalRandom.current().nextDouble() * 100 < value(level);
    }

    protected int getInt(String path, int def) {
        return cfg == null ? def : cfg.getInt(path, def);
    }

    protected double getDouble(String path, double def) {
        return cfg == null ? def : cfg.getDouble(path, def);
    }

    protected String getString(String path, String def) {
        return cfg == null ? def : cfg.getString(path, def);
    }

    /** Secondes de recharge restantes (0 = prêt). */
    protected long cooldownLeft(Player player) {
        Long until = cooldowns.get(player.getUniqueId());
        long left = until == null ? 0 : until - Bukkit.getCurrentTick();
        return left <= 0 ? 0 : (left + 19) / 20;
    }

    protected void startCooldown(Player player, double seconds) {
        cooldowns.put(player.getUniqueId(), Bukkit.getCurrentTick() + Math.round(seconds * 20));
    }

    protected void actionBar(Player player, String path, String... replacements) {
        String msg = getString(path, "");
        if (msg == null || msg.isEmpty()) {
            return;
        }
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            msg = msg.replace(replacements[i], replacements[i + 1]);
        }
        player.sendActionBar(Texts.of(msg));
    }
}

package fr.influencraft.influpet.pets;

import com.willfp.eco.core.data.PlayerProfile;
import com.willfp.eco.core.data.keys.PersistentDataKey;
import com.willfp.eco.core.data.keys.PersistentDataKeyType;
import com.willfp.eco.util.NumberUtils;
import fr.influencraft.influpet.pets.event.PetActivateEvent;
import fr.influencraft.influpet.pets.event.PetAdoptEvent;
import fr.influencraft.influpet.pets.event.PetDeactivateEvent;
import fr.influencraft.influpet.pets.event.PetLevelUpEvent;
import fr.influencraft.influpet.pets.event.PetXpGainEvent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Familiers d'un joueur : niveau et XP de chaque familier, familier actif, familier caché. Tout est lu et écrit
 * dans le profil eco du joueur (PersistentDataKey), exactement comme EcoPets (com.willfp.ecopets.pets.PetKt) :
 * niveau 0 = pas adopté. C'est une collection : un familier adopté ne se retire pas (pas d'œuf, pas d'échange).
 */
public final class PetData {

    private static PersistentDataKey<String> activeKey;
    private static PersistentDataKey<Boolean> hiddenKey;
    private static final Map<UUID, double[]> multiplierCache = new HashMap<>();
    private static final Pattern LEVEL_OFFSET = Pattern.compile("%level_(-?\\d+)(_numeral)?%");

    private PetData() {
    }

    static void registerKeys(boolean local) {
        activeKey = new PersistentDataKey<>(Pets.key("active_pet"), PersistentDataKeyType.STRING, "", local);
        hiddenKey = new PersistentDataKey<>(Pets.key("hide_pet"), PersistentDataKeyType.BOOLEAN, false, local);
    }

    private static PlayerProfile profile(OfflinePlayer player) {
        return PlayerProfile.load(player);
    }

    // ------------------------------------------------------------------ niveau et XP

    public static int level(OfflinePlayer player, Pet pet) {
        Integer level = profile(player).read(pet.levelKey());
        return level == null ? 0 : level;
    }

    public static void setLevel(OfflinePlayer player, Pet pet, int level) {
        profile(player).write(pet.levelKey(), Math.max(0, Math.min(level, pet.maxLevel())));
        if (player instanceof Player online) {
            refresh(online);
        }
    }

    public static boolean has(OfflinePlayer player, Pet pet) {
        return level(player, pet) > 0;
    }

    public static double xp(OfflinePlayer player, Pet pet) {
        Double xp = profile(player).read(pet.xpKey());
        return xp == null ? 0 : xp;
    }

    public static void setXp(OfflinePlayer player, Pet pet, double xp) {
        profile(player).write(pet.xpKey(), Math.max(0, xp));
    }

    /** Progression vers le niveau suivant (0 à 1 ; 0 au niveau max). */
    public static double progress(OfflinePlayer player, Pet pet) {
        double required = pet.xpForLevel(level(player, pet) + 1);
        return Double.isInfinite(required) || required <= 0 ? 0 : xp(player, pet) / required;
    }

    /** Nombre de familiers adoptés. */
    public static int owned(OfflinePlayer player) {
        int count = 0;
        for (Pet pet : Pets.values()) {
            if (has(player, pet)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Donne de l'XP au familier. noMultiply = sans le multiplicateur de permission (les cartes : une carte vaut
     * toujours son XP). Événement annulable / modifiable, puis montée de niveau en cascade.
     */
    public static void giveXp(Player player, Pet pet, double amount, boolean noMultiply) {
        double exp = Math.abs(noMultiply ? amount : amount * multiplier(player));
        PetXpGainEvent event = new PetXpGainEvent(player, pet, exp, !noMultiply);
        Bukkit.getPluginManager().callEvent(event);
        if (!event.isCancelled()) {
            giveExactXp(player, pet, event.getAmount());
        }
    }

    /** XP exacte, comme EcoPets : passe les niveaux tant que l'XP suffit, reporte le surplus. */
    public static void giveExactXp(Player player, Pet pet, double amount) {
        double progress = xp(player, pet) + amount;
        int level = level(player, pet);
        // itératif (EcoPets est récursif) : même résultat sans risque de pile pour un gros don d'XP
        while (level + 1 <= pet.maxLevel() && progress >= pet.xpForLevel(level + 1)) {
            progress -= pet.xpForLevel(level + 1);
            level++;
            profile(player).write(pet.levelKey(), level);
            Bukkit.getPluginManager().callEvent(new PetLevelUpEvent(player, pet, level));
        }
        // au niveau max, l'XP continue de s'accumuler (comme EcoPets)
        setXp(player, pet, progress);
        refresh(player);
    }

    /** Multiplicateur d'XP par permission, mis en cache 10 s (comme EcoPets, permissions influpet.xpmultiplier.*). */
    public static double multiplier(Player player) {
        long now = System.currentTimeMillis();
        double[] cached = multiplierCache.get(player.getUniqueId());
        if (cached != null && cached[1] > now) {
            return cached[0];
        }
        double value = 1;
        if (player.hasPermission("influpet.xpmultiplier.quadruple")) {
            value = 4;
        } else if (player.hasPermission("influpet.xpmultiplier.triple")) {
            value = 3;
        } else if (player.hasPermission("influpet.xpmultiplier.double")) {
            value = 2;
        } else if (player.hasPermission("influpet.xpmultiplier.50percent")) {
            value = 1.5;
        } else {
            for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
                String perm = info.getPermission();
                if (info.getValue() && perm.startsWith("influpet.xpmultiplier.")) {
                    try {
                        value = Double.parseDouble(perm.substring(perm.lastIndexOf('.') + 1)) / 100 + 1;
                    } catch (NumberFormatException ignored) {
                        value = 2;
                    }
                    break;
                }
            }
        }
        multiplierCache.put(player.getUniqueId(), new double[]{value, now + 10_000});
        return value;
    }

    // ------------------------------------------------------------------ adoption

    /** Adopte le familier (niveau 1). false si déjà adopté ou adoption annulée. */
    public static boolean adopt(OfflinePlayer player, Pet pet) {
        if (has(player, pet)) {
            return false;
        }
        PetAdoptEvent event = new PetAdoptEvent(player, pet);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return false;
        }
        setXp(player, pet, 0);
        setLevel(player, pet, 1);
        return true;
    }

    /** Retire le familier (administration) : niveau 0, XP 0, rangé s'il était actif. */
    public static void take(OfflinePlayer player, Pet pet) {
        if (player instanceof Player online && pet.equals(active(online))) {
            setActive(online, null);
        }
        setXp(player, pet, 0);
        setLevel(player, pet, 0);
    }

    // ------------------------------------------------------------------ familier actif

    /** Familier actif (null sans familier, ou s'il n'est plus adopté / plus chargé). */
    public static Pet active(OfflinePlayer player) {
        Pet pet = Pets.get(profile(player).read(activeKey));
        return pet != null && has(player, pet) ? pet : null;
    }

    public static PetLevel activeLevel(OfflinePlayer player) {
        Pet pet = active(player);
        return pet == null ? null : pet.level(level(player, pet));
    }

    /**
     * Active un familier (null = range le familier actif). Événements annulables comme EcoPets. Renvoie true si
     * le changement a eu lieu.
     */
    public static boolean setActive(Player player, Pet pet) {
        Pet previous = active(player);
        if (pet == null) {
            if (previous != null) {
                PetDeactivateEvent event = new PetDeactivateEvent(player, previous);
                Bukkit.getPluginManager().callEvent(event);
                if (event.isCancelled()) {
                    return false;
                }
            }
        } else {
            PetActivateEvent event = new PetActivateEvent(player, pet, previous);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                return false;
            }
        }
        profile(player).write(activeKey, pet == null ? "" : pet.id());
        refresh(player);
        return true;
    }

    public static boolean hidden(OfflinePlayer player) {
        Boolean hidden = profile(player).read(hiddenKey);
        return hidden != null && hidden;
    }

    public static void setHidden(OfflinePlayer player, boolean hidden) {
        profile(player).write(hiddenKey, hidden);
    }

    /** Effets libreforge recalculés tout de suite (sinon au prochain passage de libreforge). */
    static void refresh(Player player) {
        Pets.refresh(player);
    }

    static void forget(UUID uuid) {
        multiplierCache.remove(uuid);
    }

    // ------------------------------------------------------------------ textes

    /**
     * Placeholders d'un familier dans des lignes (comme Pet.injectPlaceholdersInto d'EcoPets) : %pet%,
     * %description%, %level%, %level_numeral%, %level_<décalage>%, %current_xp%, %required_xp%,
     * %percentage_progress%, et les lignes %effects% / %rewards% / %level_up_messages% remplacées par la
     * description du niveau, avec le même retrait que la ligne.
     */
    public static List<String> inject(List<String> lines, Player player, Pet pet, Integer forceLevel) {
        int level = forceLevel != null ? forceLevel : level(player, pet);
        double required = pet.xpForLevel(level + 1);
        List<String> out = new ArrayList<>();
        for (String raw : lines) {
            String line = raw.replace("%percentage_progress%", Pet.nice(round(progress(player, pet) * 100)))
                    .replace("%current_xp%", Pet.nice(xp(player, pet)))
                    .replace("%required_xp%", Pet.nice(required))
                    .replace("%description%", pet.description())
                    .replace("%pet%", pet.name());
            Matcher m = LEVEL_OFFSET.matcher(line);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                int value = level + Integer.parseInt(m.group(1));
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(2) != null
                        ? NumberUtils.toNumeral(value) : String.valueOf(value)));
            }
            m.appendTail(sb);
            line = sb.toString().replace("%level_numeral%", NumberUtils.toNumeral(level))
                    .replace("%level%", String.valueOf(level));
            int indent = line.length() - line.replace(" ", "").length();
            List<String> block = line.contains("%effects%") ? pet.effectsDescription(level)
                    : line.contains("%rewards%") ? pet.rewardsDescription(level)
                    : line.contains("%level_up_messages%") ? pet.levelUpMessages(level) : null;
            if (block == null) {
                out.add(line);
            } else {
                String pad = " ".repeat(indent);
                block.forEach(l -> out.add(pad + l));
            }
        }
        return out;
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}

package fr.influencraft.fluxpets.pets;

import fr.influencraft.fluxpets.pets.event.PetActivateEvent;
import fr.influencraft.fluxpets.pets.event.PetAdoptEvent;
import fr.influencraft.fluxpets.pets.event.PetDeactivateEvent;
import fr.influencraft.fluxpets.pets.event.PetLevelUpEvent;
import fr.influencraft.fluxpets.pets.event.PetXpGainEvent;
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
 * Familiers d'un joueur : niveau et XP de chaque familier, familiers actifs (plusieurs selon la permission
 * fluxpets.pets.max.<n>), familiers cachés. Tout est lu et écrit
 * dans la base de Flux ({@link PetStore}, depuis le 2026-11-13 ; avant : le profil eco du joueur) :
 * niveau 0 = pas adopté. C'est une collection : un familier adopté ne se retire pas (pas d'œuf, pas d'échange).
 */
public final class PetData {

    private static final Map<UUID, double[]> maxCache = new HashMap<>();
    private static final Map<UUID, double[]> multiplierCache = new HashMap<>();
    private static final Pattern LEVEL_OFFSET = Pattern.compile("%level_(-?\\d+)(_numeral)?%");

    private PetData() {
    }

     private static PetStore.Profile profile(OfflinePlayer player) {
        return PetStore.get().of(player.getUniqueId());
    }

    private static void changed(OfflinePlayer player) {
        PetStore.get().changed(player.getUniqueId());
    }

    // ------------------------------------------------------------------ niveau et XP

    public static int level(OfflinePlayer player, Pet pet) {
        return profile(player).levels.getOrDefault(pet.id(), 0);
    }

    public static void setLevel(OfflinePlayer player, Pet pet, int level) {
        profile(player).levels.put(pet.id(), Math.max(0, Math.min(level, pet.maxLevel())));
        changed(player);
        if (player instanceof Player online) {
            refresh(online);
        }
    }

    public static boolean has(OfflinePlayer player, Pet pet) {
        return level(player, pet) > 0;
    }

    public static double xp(OfflinePlayer player, Pet pet) {
        return profile(player).xp.getOrDefault(pet.id(), 0.0);
    }

    public static void setXp(OfflinePlayer player, Pet pet, double xp) {
        profile(player).xp.put(pet.id(), Math.max(0, xp));
        changed(player);
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
            profile(player).levels.put(pet.id(), level);
            Bukkit.getPluginManager().callEvent(new PetLevelUpEvent(player, pet, level));
        }
        // au niveau max, l'XP continue de s'accumuler (comme EcoPets)
        setXp(player, pet, progress);
        refresh(player);
    }

    /** Multiplicateur d'XP par permission, mis en cache 10 s (comme EcoPets, permissions fluxpets.xpmultiplier.*). */
    public static double multiplier(Player player) {
        long now = System.currentTimeMillis();
        double[] cached = multiplierCache.get(player.getUniqueId());
        if (cached != null && cached[1] > now) {
            return cached[0];
        }
        double value = 1;
        if (player.hasPermission("fluxpets.xpmultiplier.quadruple")) {
            value = 4;
        } else if (player.hasPermission("fluxpets.xpmultiplier.triple")) {
            value = 3;
        } else if (player.hasPermission("fluxpets.xpmultiplier.double")) {
            value = 2;
        } else if (player.hasPermission("fluxpets.xpmultiplier.50percent")) {
            value = 1.5;
        } else {
            for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
                String perm = info.getPermission();
                if (info.getValue() && perm.startsWith("fluxpets.xpmultiplier.")) {
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
        if (player instanceof Player online && isActive(online, pet)) {
            deactivate(online, pet);
        }
        setXp(player, pet, 0);
        setLevel(player, pet, 0);
    }

    // ------------------------------------------------------------------ familiers actifs

    /** Résultat d'une activation. */
    public enum Activation { OK, ALREADY, FULL, CANCELLED }

    /**
     * Familiers actifs, dans l'ordre d'activation (le premier suit le joueur, les suivants marchent derrière lui).
     * Seuls les familiers adoptés et chargés comptent, au plus le maximum du joueur (s'il est connecté).
     * Ancienne donnée à un seul familier (active_pet) reprise si la liste est vide.
     */
    public static List<Pet> actives(OfflinePlayer player) {
        List<String> ids = List.copyOf(profile(player).active);
        int max = player instanceof Player online ? maxActive(online) : Integer.MAX_VALUE;
        List<Pet> pets = new ArrayList<>();
        for (String id : ids) {
            Pet pet = Pets.get(id);
            if (pet != null && !pets.contains(pet) && has(player, pet) && pets.size() < max) {
                pets.add(pet);
            }
        }
        return pets;
    }

    /** Premier familier actif (celui qui suit le joueur), null sans familier. */
    public static Pet active(OfflinePlayer player) {
        List<Pet> pets = actives(player);
        return pets.isEmpty() ? null : pets.get(0);
    }

    public static boolean isActive(OfflinePlayer player, Pet pet) {
        return actives(player).contains(pet);
    }

    /** Holders libreforge de tous les familiers actifs : leurs effets s'appliquent ensemble. */
    public static List<PetLevel> activeLevels(OfflinePlayer player) {
        List<PetLevel> levels = new ArrayList<>();
        for (Pet pet : actives(player)) {
            levels.add(pet.level(level(player, pet)));
        }
        return levels;
    }

    public static PetLevel activeLevel(OfflinePlayer player) {
        Pet pet = active(player);
        return pet == null ? null : pet.level(level(player, pet));
    }

    /**
     * Nombre de familiers actifs autorisés : permission fluxpets.pets.max.<n> (la plus haute), sinon
     * pet-settings.active-pets.default, jamais plus que pet-settings.active-pets.maximum. Mis en cache 10 s.
     */
    public static int maxActive(Player player) {
        long now = System.currentTimeMillis();
        double[] cached = maxCache.get(player.getUniqueId());
        if (cached != null && cached[1] > now) {
            return (int) cached[0];
        }
        int cap = Math.max(1, Pets.settings().getInt("active-pets.maximum"));
        int value = Math.max(1, Pets.settings().getInt("active-pets.default"));
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            String perm = info.getPermission().toLowerCase(java.util.Locale.ROOT);
            if (info.getValue() && perm.startsWith("fluxpets.pets.max.")) {
                try {
                    value = Math.max(value, Integer.parseInt(perm.substring("fluxpets.pets.max.".length())));
                } catch (NumberFormatException ignored) {
                    // permission mal écrite : ignorée
                }
            }
        }
        value = Math.min(value, cap);
        maxCache.put(player.getUniqueId(), new double[]{value, now + 10_000});
        return value;
    }

    /**
     * Active un familier en plus des autres. Plus de place : avec une seule place, il remplace le familier actif
     * (comportement d'EcoPets) ; sinon FULL (au joueur d'en ranger un). Événement annulable.
     */
    public static Activation activate(Player player, Pet pet) {
        List<Pet> pets = new ArrayList<>(actives(player));
        if (pets.contains(pet)) {
            return Activation.ALREADY;
        }
        int max = maxActive(player);
        Pet replaced = null;
        if (pets.size() >= max) {
            if (max > 1) {
                return Activation.FULL;
            }
            replaced = pets.isEmpty() ? null : pets.get(0);
        }
        PetActivateEvent event = new PetActivateEvent(player, pet, replaced);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return Activation.CANCELLED;
        }
        if (replaced != null) {
            pets.remove(replaced);
        }
        pets.add(pet);
        write(player, pets);
        return Activation.OK;
    }

    /** Range un familier actif (événement annulable). */
    public static boolean deactivate(Player player, Pet pet) {
        List<Pet> pets = new ArrayList<>(actives(player));
        if (!pets.contains(pet)) {
            return false;
        }
        PetDeactivateEvent event = new PetDeactivateEvent(player, pet);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return false;
        }
        pets.remove(pet);
        write(player, pets);
        return true;
    }

    /** Range tous les familiers actifs ; renvoie ceux qui ont été rangés. */
    public static List<Pet> deactivateAll(Player player) {
        List<Pet> done = new ArrayList<>();
        for (Pet pet : actives(player)) {
            if (deactivate(player, pet)) {
                done.add(pet);
            }
        }
        return done;
    }

    /** Compatibilité : null = tout ranger, sinon activer (en remplaçant s'il n'y a qu'une place). */
    public static boolean setActive(Player player, Pet pet) {
        if (pet == null) {
            return !deactivateAll(player).isEmpty() || actives(player).isEmpty();
        }
        Activation result = activate(player, pet);
        return result == Activation.OK || result == Activation.ALREADY;
    }

    private static void write(Player player, List<Pet> pets) {
        PetStore.Profile profile = profile(player);
        profile.active.clear();
        profile.active.addAll(pets.stream().map(Pet::id).toList());
        changed(player);
        refresh(player);
    }

    public static boolean hidden(OfflinePlayer player) {
        return profile(player).hidden;
    }

    public static void setHidden(OfflinePlayer player, boolean hidden) {
        profile(player).hidden = hidden;
        changed(player);
    }

    /** Effets libreforge recalculés tout de suite (sinon au prochain passage de libreforge). */
    static void refresh(Player player) {
        Pets.refresh(player);
    }

    static void forget(UUID uuid) {
        multiplierCache.remove(uuid);
        maxCache.remove(uuid);
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
                        ? fr.influencraft.flux.api.util.Format.roman(value) : String.valueOf(value)));
            }
            m.appendTail(sb);
            line = sb.toString().replace("%level_numeral%", fr.influencraft.flux.api.util.Format.roman(level))
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

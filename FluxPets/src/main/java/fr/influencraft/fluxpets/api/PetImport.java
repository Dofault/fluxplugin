package fr.influencraft.fluxpets.api;

import fr.influencraft.fluxpets.pets.PetStore;
import fr.influencraft.fluxpets.pets.Pets;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Pets of the players, for imports (FluxMigration): ids of the pets known, and a player's whole data. */
public final class PetImport {

    private PetImport() {
    }

    public static boolean available() {
        return PetStore.get() != null;
    }

    /** Ids of every pet loaded by FluxPets. */
    public static List<String> ids() {
        return Pets.values().stream().map(p -> p.id()).toList();
    }

    /** Whether FluxPets already has data for this player (an import leaves him as he is). */
    public static boolean known(UUID player) {
        PetStore s = PetStore.get();
        return s == null || s.known(player);
    }

    /** Writes a player's levels and XP per pet, active pets in order, hidden or not. */
    public static void put(UUID player, Map<String, Integer> levels, Map<String, Double> xp, List<String> active, boolean hidden) {
        PetStore s = PetStore.get();
        if (s != null) s.put(player, levels, xp, active, hidden);
    }
}

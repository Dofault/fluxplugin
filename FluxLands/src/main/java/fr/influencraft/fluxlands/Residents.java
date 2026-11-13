package fr.influencraft.fluxlands;

import me.angeschossen.lands.api.LandsIntegration;
import me.angeschossen.lands.api.land.Area;
import me.angeschossen.lands.api.land.Land;
import me.angeschossen.lands.api.levels.LevelsHandler;
import me.angeschossen.lands.api.levels.requirement.RequirementDefinition;
import me.angeschossen.lands.api.levels.requirement.impl.AbstractRequirement;
import me.angeschossen.lands.api.memberholder.HolderType;
import me.angeschossen.lands.api.memberholder.MemberHolder;
import me.angeschossen.lands.api.nation.Nation;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Level requirement {@code residents}: everyone who lives in the town, its plots included.
 * <p>
 * Lands' own {@code members} requirement only counts players trusted in the town itself (its default area). A tenant
 * of a plot, or a player trusted in one plot only, is trusted in that sub-area and nowhere else, so a town renting out
 * dozens of plots stayed at "3/25" (Nouvelleamare, production, 2026-11-08). This requirement counts the unique players
 * of the town and of every one of its areas, tenants included. For a country, the unique players of its counted towns
 * (Lands' {@code land-time} rule kept, through {@link LevelsHandler#sumOverLands}).
 * <p>
 * Used in {@code Lands/levels.yml} in place of {@code members:}. Lands reads the levels when it enables, so the type
 * must be registered from {@link JavaPlugin#onLoad()}: later, Lands throws {@link IllegalStateException}.
 */
final class Residents extends AbstractRequirement {

    static final String ID = "residents";

    /** Lands' levels handler, kept from {@link #register} (values are read on Lands' async executor). */
    private static volatile LevelsHandler levels;

    private Residents(RequirementDefinition definition) {
        super(definition);
    }

    /** Registers the requirement for towns and countries. Call from {@code onLoad()}. */
    static void register(JavaPlugin plugin) {
        try {
            levels = LandsIntegration.of(plugin).getLevelsHandler();
            levels.registerRequirementType(HolderType.LAND, ID, Residents::new);
            levels.registerRequirementType(HolderType.NATION, ID, Residents::new);
            plugin.getLogger().info("Level requirement '" + ID + "' registered (town and plot residents).");
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().severe("Could not register the level requirement '" + ID + "': levels using it are "
                    + "ignored by Lands (" + e + ").");
        }
    }

    @Override
    public CompletableFuture<Double> retrieveValue(MemberHolder holder) {
        if (holder instanceof Land land) return CompletableFuture.completedFuture((double) of(land).size());
        if (holder instanceof Nation nation && levels != null) {
            // sumOverLands walks the towns that count for the country's level (land-time); we only want their union
            Set<UUID> all = ConcurrentHashMap.newKeySet();
            @SuppressWarnings("unchecked")
            CompletableFuture<Double> walked = levels.sumOverLands(nation, l -> {
                all.addAll(of((Land) l));
                return CompletableFuture.completedFuture(0d);
            });
            return walked.thenApply(ignored -> (double) all.size());
        }
        return CompletableFuture.completedFuture((double) holder.getTrustedPlayers().size());
    }

    /** Unique players of the town: trusted in the town, trusted in one of its areas, or tenant of one. */
    static Set<UUID> of(Land land) {
        Set<UUID> out = new HashSet<>();
        for (Object o : land.getTrustedPlayers()) if (o instanceof UUID u) out.add(u);
        for (Object o : land.getAllAreas()) {
            if (!(o instanceof Area area)) continue;
            for (Object t : area.getTrustedPlayers()) if (t instanceof UUID u) out.add(u);
            UUID tenant = area.getTenant();
            if (tenant != null) out.add(tenant);
        }
        return out;
    }
}

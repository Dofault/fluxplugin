package fr.influencraft.flux.adapters.lands;

import fr.influencraft.flux.api.claims.Claims;
import me.angeschossen.lands.api.LandsIntegration;
import me.angeschossen.lands.api.land.Area;
import me.angeschossen.lands.api.land.Land;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

import java.util.UUID;

/** Lands behind {@link Claims} for the plugins of the suite that are not Lands extensions. */
public final class LandsClaims implements Claims.Provider {

    private final LandsIntegration lands;

    public LandsClaims(Plugin flux) {
        this.lands = LandsIntegration.of(flux);
    }

    @Override
    public String name() {
        return "Lands";
    }

    @Override
    public String areaId(Location location) {
        Area area = lands.getArea(location);
        return area == null ? null : area.getULID().toString();
    }

    @Override
    public String claimId(Location location) {
        Area area = lands.getArea(location);
        return area == null ? null : area.getLand().getULID().toString();
    }

    @Override
    public String claimName(Location location) {
        Area area = lands.getArea(location);
        return area == null ? null : area.getLand().getName();
    }

    @Override
    public boolean member(Location location, UUID player) {
        Area area = lands.getArea(location);
        if (area == null) return false;
        Land land = area.getLand();
        return player.equals(land.getOwnerUID()) || land.isTrusted(player);
    }

    @Override
    public boolean mayOpenContainers(Location location, UUID player) {
        Area area = lands.getArea(location);
        return area == null || area.hasRoleFlag(player, me.angeschossen.lands.api.flags.type.Flags.INTERACT_CONTAINER);
    }

    @Override
    public boolean owner(Location location, UUID player) {
        Area area = lands.getArea(location);
        return area != null && player.equals(area.getLand().getOwnerUID());
    }
}

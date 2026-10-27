package fr.influencraft.flux.adapters.lands;

import fr.influencraft.flux.api.claims.Claims;
import me.angeschossen.lands.api.LandsIntegration;
import me.angeschossen.lands.api.land.Area;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

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
}

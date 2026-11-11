package fr.influencraft.fluxportal;

import fr.influencraft.fluxportal.api.Portal;

/**
 * What draws the 3D window of a portal (the far side seen through the ring, in depth) and carries travellers through
 * it. Today RPE-portals ({@code rpe.RpeRenderer}); tomorrow possibly our own. Without any, FluxPortal draws a flat
 * swirl and carries travellers itself.
 */
public interface PortalRenderer {

    /** Opens (or opens again) the window of a complete portal. */
    void show(Portal portal);

    /** Closes the window of a portal (removed, changed, or the plugin stops). */
    void hide(Portal portal);

    void stop();
}

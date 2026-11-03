package fr.influencraft.fluxreferral.service;

import fr.influencraft.fluxfamily.api.Family;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.UUID;

/**
 * Referrals in FluxFamily's tree (2026-10-30): the sponsor of a player is his parent, link « parrainage ». Only
 * loaded when FluxFamily runs.
 */
public final class FamilyLink {

    private FamilyLink() {
    }

    public static void register(Plugin plugin, ReferralTree tree) {
        Family.lineage(plugin, "parrainage", new Family.Lineage() {
            public UUID parent(UUID child) {
                return tree.sponsor(child);
            }

            public List<UUID> children(UUID parent) {
                return tree.referrals(parent);
            }
        });
    }
}

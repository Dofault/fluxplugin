package fr.influencraft.fluxfamily;

import fr.influencraft.fluxfamily.api.Family;
import fr.influencraft.flux.api.cosmetics.Tags;

import java.util.UUID;

/**
 * Tags of FluxFamily, given to the cosmetics plugin through Flux ({@link Tags}): « situation », always unlocked,
 * showing the relationship status (célibataire, en couple, marié), and « divorce », unlocked for good by leaving a
 * marriage. Images: {@code fluxtags:<id>} (FluxCosmetics/assets/tags/gen_tags.py).
 */
final class FamilyTags {

    static final String STATUS = "situation";
    static final String DIVORCED = "divorce";

    private FamilyTags() {
    }

    static void register(FluxFamily plugin) {
        Tags.register(plugin, STATUS, plugin.messages().raw("tag-status-name"), plugin.messages().raw("tag-status-description"),
                p -> switch (Family.status(p.getUniqueId())) {
                    case MARRIED -> "marie";
                    case COUPLE -> "en_couple";
                    case SINGLE -> "celibataire";
                });
        Tags.register(plugin, DIVORCED, plugin.messages().raw("tag-divorced-name"), plugin.messages().raw("tag-divorced-description"),
                p -> Family.divorced(p.getUniqueId()) ? "divorce" : null);
    }

    /** After a divorce: a player who was wearing his status tag now wears « Divorcé ». */
    static void afterDivorce(UUID leaver) {
        if (STATUS.equals(Tags.selected(leaver))) Tags.select(leaver, DIVORCED);
    }
}

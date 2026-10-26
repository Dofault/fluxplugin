package fr.influencraft.influshop.model;

/**
 * Who runs a COMMAND-source entry's command on purchase.
 * <p>
 * CONSOLE is the default (matches an admin-level "/give"-style command such as
 * ExcellentCrates' "crates key give"). PLAYER is for a command that's gated on the buyer's
 * own permission rather than console/op (e.g. VaultSupermarket's "/supermarket autosell",
 * which only works run by a player who already holds "supermarket.autosell") — see
 * shop.yml's comment on the "vente_automatique" entry for the caveat that implies.
 */
public enum CommandRunAs {
    CONSOLE, PLAYER
}

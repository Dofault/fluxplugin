package fr.influencraft.fluxmigration.migrations;

import fr.influencraft.flux.api.economy.Currency;
import fr.influencraft.flux.api.economy.Economy;
import fr.influencraft.flux.api.economy.Ledger;
import fr.influencraft.fluxmigration.Migration;

/**
 * 2026-11-14, the server's money kept by Flux: the account history recorded BitBlock under the id {@code vault} (the
 * money was CMI's, seen through Vault); its lines move under the id of the Flux currency that replaced it, so players
 * keep their history in {@code /compte}. Remove once production runs it.
 */
public final class LedgerVaultToMoney implements Migration {

    @Override
    public String id() {
        return "2026-11-14_ledger_vault_to_money";
    }

    @Override
    public String description() {
        return "Account history lines of the Vault money -> the currency kept by Flux";
    }

    private static Currency money() {
        Currency main = Economy.get("vault");
        return main == null || main.id().equals("vault") ? null : main;
    }

    @Override
    public boolean ready() {
        return money() != null && Ledger.available();
    }

    @Override
    public String run() throws Exception {
        return Ledger.renameCurrency("vault", money().id()) + " line(s) moved to " + money().id();
    }
}

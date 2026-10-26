package fr.influencraft.fluxtools.modules;

import com.willfp.eco.core.integrations.economy.EconomyIntegration;
import com.willfp.eco.core.integrations.economy.EconomyManager;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.math.BigDecimal;

/**
 * Contourne un race condition de démarrage confirmé le 2026-08-26 (logs
 * `serveurs/03_semirp/logs/latest.log`) : eco (framework EcoJobs) enregistre
 * son intégration économie Vault une seule fois, de façon synchrone, pendant
 * son propre onEnable() (`EcoSpigotPlugin.loadIntegrationLoaders`, un simple
 * {@code ServicesManager.getRegistration(Economy.class)} sans retry ni écoute
 * de {@code ServiceRegisterEvent}) — décompilation confirmée. Depuis la
 * migration EssentialsX→CMI, CMIEInjector hooke CMIEconomy dans Vault
 * plusieurs secondes après ce moment précis (log : "[eco] Loaded
 * integrations" à 23:32:24, "[CMIEInjector][Economy] CMI Economy hooked."
 * à 23:32:29) : eco démarre donc sans aucune intégration économie
 * enregistrée, et tout {@code give_money} d'EcoJobs (mineur, rochiste, etc.)
 * devient un no-op totalement silencieux — {@code EconomyManager}'s registry
 * vide retombe sur ses valeurs par défaut (false / BigDecimal.ZERO) sans
 * lever d'exception, donc invisible dans les logs. Symptôme observé :
 * l'XP de métier progresse normalement (mécanisme interne, indépendant de
 * l'économie) mais le BitBlock ne bouge jamais.
 *
 * <p>Ce binder réenregistre une intégration économie Vault dès que le
 * serveur a fini de démarrer ({@code ServerLoadEvent}, garanti après le
 * onEnable de tous les plugins, donc après le hook CMIEInjector) — sans
 * condition sur ce qu'a déjà fait eco : redondant si eco a réussi son
 * propre hook (le premier enregistrement gagne dans
 * {@code EconomyManager.REGISTRY}, celui-ci ne sert jamais), mais corrige
 * le cas où il a raté sa fenêtre.</p>
 */
final class LateVaultEconomyBinder {

    private LateVaultEconomyBinder() {
    }

    static void bindIfNeeded() {
        if (EconomyManager.hasRegistrations()) {
            return;
        }
        RegisteredServiceProvider<Economy> registration =
                Bukkit.getServicesManager().getRegistration(Economy.class);
        if (registration == null) {
            return;
        }

        Economy economy = registration.getProvider();
        EconomyManager.register(new EconomyIntegration() {
            @Override
            public String getPluginName() {
                return "Vault";
            }

            @Override
            public boolean hasAmount(OfflinePlayer player, BigDecimal amount) {
                return economy.has(player, amount.doubleValue());
            }

            @Override
            public boolean giveMoney(OfflinePlayer player, BigDecimal amount) {
                return economy.depositPlayer(player, amount.doubleValue()).transactionSuccess();
            }

            @Override
            public boolean removeMoney(OfflinePlayer player, BigDecimal amount) {
                return economy.withdrawPlayer(player, amount.doubleValue()).transactionSuccess();
            }

            @Override
            public BigDecimal getExactBalance(OfflinePlayer player) {
                return new BigDecimal(String.valueOf(economy.getBalance(player)));
            }
        });
    }
}

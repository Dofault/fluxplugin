package fr.influencraft.flux.economy;

import fr.influencraft.flux.api.util.Format;
import fr.influencraft.flux.api.util.Players;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Flux as the server's Vault economy: the currency of Flux's config marked {@code vault: true} (BitBlock) is what
 * every third-party plugin pays and charges through Vault (Lands, EcoJobs and the eco plugins, CustomFishing...).
 * No other economy plugin is needed, and the account history sees every movement where it is made, with its origin.
 *
 * <ul>
 *   <li>Registered in Flux's {@code onLoad}, before any plugin is enabled: a plugin that looks Vault's economy up
 *   once, in its own start, finds this one (eco used to find nothing, see {@code EcoVaultBridge}). The currency
 *   behind it is bound when Flux starts; until then every operation answers "not ready".</li>
 *   <li>Vault's worlds are ignored (one balance per player) and banks are not supported, like CMI before.</li>
 *   <li>An account by name that is not a known player is a virtual account (a plugin's own till), kept under the
 *   offline UUID of that name.</li>
 * </ul>
 */
public final class VaultProvider implements Economy {

    private static final VaultProvider INSTANCE = new VaultProvider();

    private volatile StoredCurrency currency;
    /** {@code currencies.<id>.vault-format}: how other plugins show an amount ({@code %amount%}), plain text. */
    private volatile String format = "%amount%";
    private volatile String singular = "BitBlock";
    private volatile String plural = "BitBlock";

    private VaultProvider() {
    }

    /** Called from Flux's onLoad when a currency of its config is the Vault one, and again when Flux starts. */
    public static void register(JavaPlugin flux) {
        for (var registration : Bukkit.getServicesManager().getRegistrations(Economy.class)) {
            if (registration.getProvider() == INSTANCE) return;
        }
        Bukkit.getServicesManager().register(Economy.class, INSTANCE, flux, ServicePriority.Highest);
    }

    /** Called when Flux starts (and on reload): the currency served, how its amounts are written for other plugins. */
    public static void bind(StoredCurrency currency, String format, String singular, String plural) {
        INSTANCE.currency = currency;
        INSTANCE.format = format == null || !format.contains("%amount%") ? "%amount%" : format;
        INSTANCE.singular = singular;
        INSTANCE.plural = plural;
    }

    public static void unbind() {
        INSTANCE.currency = null;
    }

    /** True when Vault's economy is this one (another economy plugin may have registered above us). */
    public static boolean active() {
        var registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        return registration != null && registration.getProvider() == INSTANCE;
    }

    /** Name of the plugin whose economy Vault serves, or null. */
    public static String activeProviderName() {
        var registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        return registration == null ? null : registration.getPlugin().getName();
    }

    private static OfflinePlayer account(String name) {
        OfflinePlayer known = Players.find(name);
        if (known != null) return known;
        return Bukkit.getOfflinePlayer(UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)));
    }

    private static EconomyResponse refused(double amount, double balance, String why) {
        return new EconomyResponse(amount, balance, ResponseType.FAILURE, why);
    }

    private static EconomyResponse noBanks() {
        return new EconomyResponse(0, 0, ResponseType.NOT_IMPLEMENTED, "Flux has no bank accounts");
    }

    @Override
    public boolean isEnabled() {
        StoredCurrency c = currency;
        return c != null && c.available();
    }

    @Override
    public String getName() {
        return "Flux";
    }

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public int fractionalDigits() {
        StoredCurrency c = currency;
        return c == null ? 2 : c.decimals();
    }

    @Override
    public String format(double amount) {
        String shown = Math.abs(amount - Math.rint(amount)) < 0.005 ? Format.number(Math.rint(amount)) : Format.decimal(amount);
        return format.replace("%amount%", shown);
    }

    @Override
    public String currencyNamePlural() {
        return plural;
    }

    @Override
    public String currencyNameSingular() {
        return singular;
    }

    // ---------------------------------------------------------------- accounts

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return isEnabled();
    }

    @Override
    public boolean hasAccount(String name) {
        return isEnabled();
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String world) {
        return isEnabled();
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        return isEnabled();
    }

    @Override
    public boolean createPlayerAccount(String name) {
        return isEnabled();
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String world) {
        return isEnabled();
    }

    // ---------------------------------------------------------------- balances

    @Override
    public double getBalance(OfflinePlayer player) {
        StoredCurrency c = currency;
        return c == null || player == null ? 0 : c.balance(player);
    }

    @Override
    public double getBalance(String name) {
        return getBalance(account(name));
    }

    @Override
    public double getBalance(OfflinePlayer player, String world) {
        return getBalance(player);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        StoredCurrency c = currency;
        return c != null && player != null && c.has(player, amount);
    }

    @Override
    public boolean has(String name, double amount) {
        return has(account(name), amount);
    }

    @Override
    public boolean has(OfflinePlayer player, String world, double amount) {
        return has(player, amount);
    }

    // ---------------------------------------------------------------- movements

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        StoredCurrency c = currency;
        if (c == null || player == null) return refused(amount, 0, "Flux's economy is not ready");
        if (amount < 0 || Double.isNaN(amount)) return refused(amount, c.balance(player), "Cannot withdraw a negative amount");
        if (!c.take(player, amount)) return refused(amount, c.balance(player), "Insufficient funds");
        return new EconomyResponse(amount, c.balance(player), ResponseType.SUCCESS, null);
    }

    @Override
    public EconomyResponse withdrawPlayer(String name, double amount) {
        return withdrawPlayer(account(name), amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String world, double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        StoredCurrency c = currency;
        if (c == null || player == null) return refused(amount, 0, "Flux's economy is not ready");
        if (amount < 0 || Double.isNaN(amount)) return refused(amount, c.balance(player), "Cannot deposit a negative amount");
        if (!c.give(player, amount)) return refused(amount, c.balance(player), "Balance limit reached");
        return new EconomyResponse(amount, c.balance(player), ResponseType.SUCCESS, null);
    }

    @Override
    public EconomyResponse depositPlayer(String name, double amount) {
        return depositPlayer(account(name), amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String world, double amount) {
        return depositPlayer(player, amount);
    }

    // ---------------------------------------------------------------- by name and world (Vault's oldest forms)

    @Override
    public boolean hasAccount(String name, String world) {
        return isEnabled();
    }

    @Override
    public boolean createPlayerAccount(String name, String world) {
        return isEnabled();
    }

    @Override
    public double getBalance(String name, String world) {
        return getBalance(account(name));
    }

    @Override
    public boolean has(String name, String world, double amount) {
        return has(account(name), amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String name, String world, double amount) {
        return withdrawPlayer(account(name), amount);
    }

    @Override
    public EconomyResponse depositPlayer(String name, String world, double amount) {
        return depositPlayer(account(name), amount);
    }

    // ---------------------------------------------------------------- banks: not supported

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return noBanks();
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return noBanks();
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return noBanks();
    }

    @Override
    public EconomyResponse createBank(String name, String player) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankOwner(String name, String player) {
        return noBanks();
    }

    @Override
    public EconomyResponse isBankMember(String name, String player) {
        return noBanks();
    }

    @Override
    public List<String> getBanks() {
        return List.of();
    }
}

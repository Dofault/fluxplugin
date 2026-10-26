package fr.influencraft.casino;

import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Jetons de casino d'Influencraft. Solde et mise par joueur (SQLite, journal de chaque mouvement), échange
 * contre des BitBlock (Vault) aux prix de la config, API {@link Casino} pour les jeux, placeholders
 * %influcasino_...% pour les menus.
 *
 * <pre>
 *   /jetons                          solde et mise
 *   /jetons mise +N|-N|N|min|max     change la mise (boutons des jeux, sans message)
 *   /jetons acheter N                N jetons contre N x buy-price BitBlock (si exchange.buy-enabled)
 *   /jetons vendre N|tout            N jetons contre N x sell-price BitBlock
 *   /jetons admin give|take|set|historique joueur [N]
 * </pre>
 */
public final class InfluCasino extends JavaPlugin implements TabExecutor, Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&').hexColors().hexCharacter('#').build();

    private Store store;
    private Economy economy;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            store = new Store(getDataFolder(), getConfig().getLong("bet.default", 10));
        } catch (Exception e) {
            getLogger().severe("Base des jetons inaccessible, plugin désactivé : " + e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        economy = rsp == null ? null : rsp.getProvider();
        if (economy == null) getLogger().warning("Aucune économie Vault : échange jetons / BitBlock indisponible.");
        Casino.init(this);
        var command = getCommand("jetons");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
        getServer().getPluginManager().registerEvents(this, this);
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) new Placeholders().register();
    }

    @Override
    public void onDisable() {
        if (store != null) store.close();
    }

    Store store() {
        return store;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        store.forget(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ mise

    long betMin() {
        return Math.max(1, getConfig().getLong("bet.min", 10));
    }

    long betMax() {
        return Math.max(betMin(), getConfig().getLong("bet.max", 1000));
    }

    long clampBet(long bet) {
        return Math.max(betMin(), Math.min(betMax(), bet));
    }

    private void changeBet(Player player, String arg) {
        long current = Casino.bet(player);
        long next;
        String a = arg.toLowerCase(Locale.ROOT);
        try {
            next = switch (a) {
                case "min" -> betMin();
                case "max" -> betMax();
                default -> a.startsWith("+") ? current + Long.parseLong(a.substring(1))
                        : a.startsWith("-") ? current - Long.parseLong(a.substring(1)) : Long.parseLong(a);
            };
        } catch (NumberFormatException e) {
            send(player, "invalid-amount");
            return;
        }
        store.setBet(player.getUniqueId(), player.getName(), clampBet(next));
    }

    // ------------------------------------------------------------------ échange

    private boolean exchangeOpen(Player player) {
        if (economy == null || !getConfig().getBoolean("exchange.enabled", false)) {
            send(player, "exchange-disabled");
            return false;
        }
        return true;
    }

    private long amount(Player player, String arg, long all) {
        long max = getConfig().getLong("exchange.max-per-operation", 100000);
        try {
            long n = arg.equalsIgnoreCase("tout") ? Math.min(all, max) : Long.parseLong(arg);
            if (n <= 0 || n > max) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) {
            send(player, "invalid-amount");
            return -1;
        }
    }

    /** Achat : les BitBlock sont pris d'abord, puis les jetons crédités (remboursé si l'écriture échoue). */
    private void buy(Player player, String arg) {
        if (!exchangeOpen(player)) return;
        if (!getConfig().getBoolean("exchange.buy-enabled", false)) {
            send(player, "buy-disabled");
            return;
        }
        long n = amount(player, arg, 0);
        if (n < 0) return;
        double price = n * getConfig().getDouble("exchange.buy-price", 10);
        if (!economy.has(player, price)) {
            send(player, "not-enough-money", "%price%", format(price));
            return;
        }
        EconomyResponse r = economy.withdrawPlayer(player, price);
        if (!r.transactionSuccess()) {
            send(player, "not-enough-money", "%price%", format(price));
            return;
        }
        try {
            store.add(player.getUniqueId(), player.getName(), n, "achat (" + format(price) + " BitBlock)");
        } catch (RuntimeException e) {
            economy.depositPlayer(player, price);
            throw e;
        }
        send(player, "bought-money", "%price%", format(price));
        send(player, "bought-chips", "%amount%", format(n));
    }

    /** Revente : les jetons sont retirés d'abord, puis les BitBlock versés (jetons rendus si le versement échoue). */
    private void sell(Player player, String arg) {
        if (!exchangeOpen(player)) return;
        long n = amount(player, arg, Casino.chips(player));
        if (n < 0) return;
        double price = n * getConfig().getDouble("exchange.sell-price", 10);
        if (!store.add(player.getUniqueId(), player.getName(), -n, "revente (" + format(price) + " BitBlock)")) {
            send(player, "not-enough-chips", "%amount%", format(Casino.chips(player)));
            return;
        }
        EconomyResponse r = economy.depositPlayer(player, price);
        if (!r.transactionSuccess()) {
            store.add(player.getUniqueId(), player.getName(), n, "revente annulée");
            getLogger().warning("Revente de " + n + " jetons de " + player.getName() + " annulée : " + r.errorMessage);
            return;
        }
        send(player, "sold-chips", "%amount%", format(n));
        send(player, "sold-money", "%price%", format(price));
    }

    // ------------------------------------------------------------------ commandes

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("admin")) {
            if (!sender.hasPermission("influcasino.admin")) return true;
            admin(sender, args);
            return true;
        }
        // « execute as <joueur> run jetons ... » (console, tests) : l'expéditeur est un relais vers le joueur
        if (sender instanceof org.bukkit.command.ProxiedCommandSender proxied) sender = proxied.getCallee();
        if (!(sender instanceof Player player)) return true;
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "" -> send(player, "balance", "%amount%", format(Casino.chips(player)), "%bet%", format(Casino.bet(player)));
            case "mise" -> {
                if (args.length >= 2) changeBet(player, args[1]);
            }
            case "acheter" -> {
                if (args.length >= 2) buy(player, args[1]);
                else send(player, "usage");
            }
            case "vendre" -> {
                if (args.length >= 2) sell(player, args[1]);
                else send(player, "usage");
            }
            default -> send(player, "usage");
        }
        return true;
    }

    private void admin(CommandSender sender, String[] args) {
        if (args.length < 3) {
            send(sender, "admin-usage");
            return;
        }
        OfflinePlayer target = Bukkit.getPlayerExact(args[2]);
        if (target == null) target = Bukkit.getOfflinePlayerIfCached(args[2]);
        if (target == null) {
            send(sender, "player-not-found", "%player%", args[2]);
            return;
        }
        String name = target.getName() == null ? args[2] : target.getName();
        String sub = args[1].toLowerCase(Locale.ROOT);
        if (sub.equals("historique")) {
            SimpleDateFormat date = new SimpleDateFormat("dd/MM HH:mm");
            for (String[] line : store.history(target.getUniqueId(), 15)) {
                sender.sendMessage(LEGACY.deserialize("&7" + date.format(new Date(Long.parseLong(line[3]))) + " &f"
                        + (line[0].startsWith("-") ? "" : "+") + line[0] + " &7= &f" + line[1] + " &8" + line[2]));
            }
            return;
        }
        long n;
        try {
            n = Long.parseLong(args.length >= 4 ? args[3] : "x");
            if (n < 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            send(sender, "invalid-amount");
            return;
        }
        long current = store.chips(target.getUniqueId());
        String by = "admin " + sender.getName();
        switch (sub) {
            case "give" -> store.add(target.getUniqueId(), name, n, by);
            case "take" -> store.add(target.getUniqueId(), name, -Math.min(n, current), by);
            case "set" -> store.add(target.getUniqueId(), name, n - current, by);
            default -> {
                send(sender, "admin-usage");
                return;
            }
        }
        send(sender, "admin-done", "%player%", name, "%amount%", format(store.chips(target.getUniqueId())));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias,
                                      @NotNull String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(List.of("acheter", "vendre", "mise"));
            if (sender.hasPermission("influcasino.admin")) out.add("admin");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            out.addAll(List.of("give", "take", "set", "historique"));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("admin")) {
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        }
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return out.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(last)).toList();
    }

    // ------------------------------------------------------------------ textes

    /** 1234567 -> « 1 234 567 » (espaces simples : l'espace fine n'existe pas dans la police du jeu). */
    static String format(double value) {
        long v = Math.round(value);
        return String.format(Locale.ROOT, "%,d", v).replace(',', ' ');
    }

    private void send(CommandSender to, String key, String... replacements) {
        String text = getConfig().getString("messages." + key, key);
        for (int i = 0; i + 1 < replacements.length; i += 2) text = text.replace(replacements[i], replacements[i + 1]);
        if (to instanceof Player p && getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            text = PlaceholderAPI.setPlaceholders(p, text);
        }
        to.sendMessage(LEGACY.deserialize(text));
    }

    // ------------------------------------------------------------------ placeholders

    /**
     * %influcasino_jetons% (solde mis en forme), jetons_brut, mise, mise_min, mise_max, mise_pas, mise_grand_pas
     * (pas des boutons de mise), prix_achat, prix_vente
     * (BitBlock par jeton), echange (true/false).
     */
    private final class Placeholders extends PlaceholderExpansion {
        @Override
        public @NotNull String getIdentifier() {
            return "influcasino";
        }

        @Override
        public @NotNull String getAuthor() {
            return "Influencraft";
        }

        @Override
        public @NotNull String getVersion() {
            return getPluginMeta().getVersion();
        }

        @Override
        public boolean persist() {
            return true;
        }

        @Override
        public String onRequest(OfflinePlayer player, @NotNull String params) {
            switch (params) {
                case "mise_min":
                    return format(betMin());
                case "mise_max":
                    return format(betMax());
                case "mise_pas":
                    return String.valueOf(getConfig().getLong("bet.step", 10));
                case "mise_grand_pas":
                    return String.valueOf(getConfig().getLong("bet.big-step", 100));
                case "prix_achat":
                    return format(getConfig().getDouble("exchange.buy-price", 10));
                case "prix_vente":
                    return format(getConfig().getDouble("exchange.sell-price", 10));
                case "echange":
                    return String.valueOf(economy != null && getConfig().getBoolean("exchange.enabled", false));
                default:
                    break;
            }
            if (player == null) return "";
            return switch (params) {
                case "jetons" -> format(Casino.chips(player));
                case "jetons_brut" -> String.valueOf(Casino.chips(player));
                case "mise" -> format(Casino.bet(player));
                default -> null;
            };
        }
    }
}

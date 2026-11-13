package fr.influencraft.flux.economy;

import fr.influencraft.flux.api.economy.BalanceChangeEvent;
import fr.influencraft.flux.api.economy.Currency;
import fr.influencraft.flux.api.economy.Economy;
import fr.influencraft.flux.api.text.Messages;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.PlayerSettings;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Running total of the main currency gained, in the action bar ({@code money-actionbar} of Flux's config, look
 * {@code money.actionbar} of style.yml): each new gain adds up and pushes the end back; after
 * {@code hide-after-seconds} without a gain the bar clears. Per player switch {@code /moneynotif on|off} (settings
 * window), on by default.
 * <p>DurabilityNotifier warns of a breaking tool in the same action bar while mining: when it runs, the total keeps
 * adding up but is not shown while the held tool is under its threshold.</p>
 */
public final class MoneyActionBar implements Listener {

    /** Player setting key, kept from FluxTools so the players' choices stay. */
    static final String SETTING = "fluxtools:money-notif";

    private final Plugin flux;
    private final Supplier<Messages> messages;
    private final Map<UUID, Double> totals = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> hides = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;
    private volatile long hideTicks = 30;
    private volatile String format = "{color}+{amount}";
    private volatile String color = "&#B376E5";
    private volatile int durabilityThreshold = -1;

    public MoneyActionBar(Plugin flux, Supplier<Messages> messages) {
        this.flux = flux;
        this.messages = messages;
    }

    /** {@code config}: {@code money-actionbar} of Flux's config; {@code money}: the money section of style.yml. */
    public void configure(ConfigurationSection config, ConfigurationSection money) {
        enabled = config == null || config.getBoolean("enabled", true);
        hideTicks = Math.max(1, Math.round((config == null ? 1.5 : config.getDouble("hide-after-seconds", 1.5)) * 20));
        if (money != null) {
            format = money.getString("actionbar", format);
            color = money.getString("currencies." + Economy.main().id() + ".color", money.getString("color", color));
        }
        durabilityThreshold = -1;
        if (Bukkit.getPluginManager().isPluginEnabled("DurabilityNotifier")) {
            File file = new File(flux.getDataFolder().getParentFile(), "DurabilityNotifier/config.yml");
            if (file.isFile()) {
                YamlConfiguration other = YamlConfiguration.loadConfiguration(file);
                if (other.getBoolean("notifications.types.action-bar.enabled", true)) {
                    durabilityThreshold = other.getInt("notifications.required-durability", 30);
                }
            }
        }
    }

    public boolean shown(OfflinePlayer player) {
        return PlayerSettings.flag(player, SETTING, true);
    }

    /** {@code /moneynotif [on|off|toggle]}. */
    public boolean command(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.get().send(sender, "players-only");
            return true;
        }
        String a = args.length == 0 ? "toggle" : args[0].toLowerCase(java.util.Locale.ROOT);
        boolean on = switch (a) {
            case "on" -> true;
            case "off" -> false;
            default -> !shown(player);
        };
        PlayerSettings.set(player, SETTING, on);
        messages.get().send(player, on ? "money-actionbar.on" : "money-actionbar.off");
        return true;
    }

    @EventHandler
    public void onBalance(BalanceChangeEvent event) {
        if (!enabled || !event.deposit() || !event.currency().equals(Economy.main().id())) return;
        double delta = event.to() - event.from();
        if (delta <= 0) return;
        UUID id = event.player();
        // money events may come from any thread (Vault providers)
        Bukkit.getScheduler().runTask(flux, () -> {
            Player player = Bukkit.getPlayer(id);
            if (player != null && shown(player)) add(player, delta);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        totals.remove(id);
        BukkitTask task = hides.remove(id);
        if (task != null) task.cancel();
    }

    private void add(Player player, double delta) {
        UUID id = player.getUniqueId();
        double total = totals.merge(id, delta, Double::sum);
        if (!lowDurability(player)) {
            Currency money = Economy.main();
            String amount = money == null ? String.valueOf(Math.round(total * 100) / 100.0) : money.format(total);
            player.sendActionBar(Texts.parse(format.replace("{color}", color).replace("{amount}", amount), player));
        }
        BukkitTask before = hides.remove(id);
        if (before != null) before.cancel();
        hides.put(id, Bukkit.getScheduler().runTaskLater(flux, () -> {
            player.sendActionBar(Component.empty());
            totals.remove(id);
            hides.remove(id);
        }, hideTicks));
    }

    /** Same rule as DurabilityNotifier: remaining durability points under its threshold. */
    private boolean lowDurability(Player player) {
        if (durabilityThreshold < 0) return false;
        ItemStack item = player.getInventory().getItemInMainHand();
        int max = item.getType().getMaxDurability();
        if (max <= 0) return false;
        int damage = item.getItemMeta() instanceof Damageable d ? d.getDamage() : 0;
        return max - damage < durabilityThreshold;
    }
}

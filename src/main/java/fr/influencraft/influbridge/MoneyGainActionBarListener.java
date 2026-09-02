package fr.influencraft.influbridge;

import com.Zrips.CMI.events.CMIUserBalanceChangeEvent;
import com.willfp.eco.core.data.ProfileExtensions;
import com.willfp.eco.core.data.keys.PersistentDataKey;
import com.willfp.eco.core.data.keys.PersistentDataKeyType;
import com.willfp.eco.util.StringUtils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Action bar de gain d'argent cumulatif : tant que de nouveaux dépôts
 * (actionType "Deposit" — celle du "give_money" EcoJobs via Vault, entre
 * autres, cf. CMIUserBalanceChangeEvent) arrivent, ils s'additionnent à un
 * compteur par joueur et repoussent l'expiration. Sans nouveau gain pendant
 * le délai configuré, l'action bar s'efface et le compteur retombe à 0.
 *
 * <p>CMIUserBalanceChangeEvent est déclaré asynchrone par CMI (peut être
 * appelé depuis n'importe quel thread ayant déclenché le dépôt côté Vault) —
 * seul l'UUID est lu directement dans le handler, tout le reste (résolution
 * du joueur en ligne, préférence, action bar, scheduler) est repoussé sur le
 * thread principal via {@link Bukkit#getScheduler()}.</p>
 *
 * <p>DurabilityNotifierSpigot affiche lui aussi ses alertes de casse d'outil
 * en action bar (déclenchées sur les mêmes actions de minage) — l'action bar
 * Minecraft n'affichant qu'un seul message à la fois, le nôtre écraserait en
 * permanence son avertissement si on l'envoyait sans condition. Le montant
 * continue de s'accumuler normalement, mais l'affichage est simplement
 * différé tant que l'outil en main est sous le seuil de durabilité de
 * DurabilityNotifier — rien n'est perdu, juste montré plus tard.</p>
 */
public final class MoneyGainActionBarListener implements Listener {

    private static final DecimalFormat AMOUNT_FORMAT =
            new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.FRANCE));

    private final Plugin plugin;
    private final long hideAfterTicks;
    private final String color;
    private final boolean durabilityGuardEnabled;
    private final int durabilityThreshold;

    private final Map<UUID, BigDecimal> totals = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> hideTasks = new ConcurrentHashMap<>();

    // Préférence par joueur, activée par défaut — bascule via la commande "/bitblocknotif on|off"
    // (bouton "Notif. gain BitBlock" de /reglages, DeluxeMenus).
    private final PersistentDataKey<Boolean> enabledKey;

    public MoneyGainActionBarListener(Plugin plugin, double hideAfterSeconds, String color,
                                       boolean durabilityGuardEnabled, int durabilityThreshold) {
        this.plugin = plugin;
        this.hideAfterTicks = Math.round(hideAfterSeconds * 20.0);
        this.color = color;
        this.durabilityGuardEnabled = durabilityGuardEnabled;
        this.durabilityThreshold = durabilityThreshold;
        this.enabledKey = new PersistentDataKey<>(
                new NamespacedKey(plugin, "money_gain_actionbar_enabled"),
                PersistentDataKeyType.BOOLEAN,
                true);
    }

    public boolean isEnabled(OfflinePlayer player) {
        return ProfileExtensions.getProfile(player).read(enabledKey);
    }

    public void setEnabled(Player player, boolean enabled) {
        ProfileExtensions.getProfile((OfflinePlayer) player).write(enabledKey, enabled);
    }

    @EventHandler
    public void onBalanceUpdate(CMIUserBalanceChangeEvent event) {
        if (!"Deposit".equals(event.getActionType())) {
            return;
        }
        BigDecimal delta = BigDecimal.valueOf(event.getTo()).subtract(BigDecimal.valueOf(event.getFrom()));
        if (delta.signum() <= 0) {
            return;
        }

        UUID uuid = event.getUser().getUuid();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !isEnabled(player)) {
                return;
            }
            handleGain(player, delta);
        });
    }

    private void handleGain(Player player, BigDecimal delta) {
        UUID uuid = player.getUniqueId();
        BigDecimal total = totals.merge(uuid, delta, BigDecimal::add);

        if (!(durabilityGuardEnabled && isHeldItemDurabilityLow(player))) {
            String raw = color + "+" + AMOUNT_FORMAT.format(total) + "&r %img_bitblock%";
            String formatted = StringUtils.format(raw, player);
            Component component = LegacyComponentSerializer.legacySection().deserialize(formatted);
            player.sendActionBar(component);
        }

        BukkitTask previousTask = hideTasks.remove(uuid);
        if (previousTask != null) {
            previousTask.cancel();
        }

        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            player.sendActionBar(Component.empty());
            totals.remove(uuid);
            hideTasks.remove(uuid);
        }, hideAfterTicks);
        hideTasks.put(uuid, task);
    }

    // Même formule que DurabilityUtil#getDurability/isDurabilityLow de DurabilityNotifierSpigot
    // (durabilité restante = maxDurability - damage, en points bruts, pas en %).
    private boolean isHeldItemDurabilityLow(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        short maxDurability = item.getType().getMaxDurability();
        if (maxDurability <= 0) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        int damage = meta instanceof Damageable damageable ? damageable.getDamage() : 0;
        int durability = maxDurability - damage;
        return durability < durabilityThreshold;
    }
}

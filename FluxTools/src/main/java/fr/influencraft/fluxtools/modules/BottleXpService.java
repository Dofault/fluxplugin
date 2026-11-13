package fr.influencraft.fluxtools.modules;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownExpBottle;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fioles d'expérience : /bottlexp stocke l'XP du joueur par tranches de 1395 points
 * (= niveaux 0 → 30) dans des bouteilles d'XP vanilla, rendues par clic droit.
 *
 * Reconnaissance : uniquement par la valeur entière stockée sous la clé PDC
 * "influbridge:bottled_xp", jamais par le nom ou le lore (renommables à l'enclume).
 * Une bouteille d'XP vanilla n'a pas cette clé et garde son comportement normal.
 * La valeur stockée est celle rendue au clic droit : si XP_PER_BOTTLE change un jour,
 * les fioles déjà créées gardent leur montant d'origine.
 *
 * Anti-lancer : le clic droit refuse l'usage de l'objet (setUseItemInHand DENY) avant
 * le lancer vanilla. Filets de sécurité : distributeurs bloqués et lancer de projectile
 * annulé si l'objet lancé porte la clé, pour qu'une fiole ne se brise jamais en 3 à 11
 * points d'XP aléatoires.
 */
public final class BottleXpService implements CommandExecutor, TabCompleter, Listener {

    /** XP totale pour passer du niveau 0 au niveau 30 (formule vanilla). */
    static final int XP_PER_BOTTLE = 1395;
    static final String PERMISSION = "influbridge.bottlexp";
    static final String ADMIN_PERMISSION = "influbridge.bottlexp.admin";

    private final NamespacedKey bottledXpKey;
    /** Tick du dernier clic consommé par joueur : un clic peut déclencher main + offhand. */
    private final Map<UUID, Integer> lastRedeemTick = new HashMap<>();

    private final fr.influencraft.flux.api.FluxPlugin plugin;

    public BottleXpService(JavaPlugin plugin) {
        this.plugin = (fr.influencraft.flux.api.FluxPlugin) plugin;
        // namespace of the former plugin (InfluBridge): data already written stays readable
        this.bottledXpKey = new NamespacedKey("influbridge", "bottled_xp");
    }

    // ─── Commande ──────────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("give") && sender.hasPermission(ADMIN_PERMISSION)) {
            return handleGive(sender, args);
        }
        if (!(sender instanceof Player player)) {
            say(sender, "players-only");
            return true;
        }
        if (!player.hasPermission(PERMISSION)) {
            say(player, "bottle.no-permission");
            return true;
        }

        int totalXp = (int) Math.min(Integer.MAX_VALUE, fr.influencraft.flux.api.util.Experience.total(player));
        int requested = totalXp / XP_PER_BOTTLE;
        if (requested == 0) {
            say(player, "bottle.not-enough");
            return true;
        }

        // addItem répartit en piles de 64 et complète les piles de fioles existantes
        // (items identiques = empilables) ; ce qui ne rentre pas revient en leftover.
        // L'XP n'est retirée que pour les fioles réellement ajoutées : rien n'est jeté au sol.
        Map<Integer, ItemStack> leftover =
                player.getInventory().addItem(createBottle(player, requested, XP_PER_BOTTLE));
        int notGiven = leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
        int given = requested - notGiven;

        if (given == 0) {
            say(player, "bottle.full");
            return true;
        }

        fr.influencraft.flux.api.util.Experience.set(player, totalXp - (long) given * XP_PER_BOTTLE);
        player.playSound(player.getLocation(), Sound.ITEM_BOTTLE_FILL, 1f, 1f);

        say(player, notGiven > 0 ? "bottle.filled-partly" : "bottle.filled", "%count%", String.valueOf(given),
                "%left%", String.valueOf(notGiven));
        return true;
    }

    /**
     * /bottlexp give <joueur> <xp> [quantité] : fioles contenant un montant libre (hors
     * tranche de 1395). Inventaire plein : le surplus tombe aux pieds de la cible, l'admin
     * est prévenu (rien à rembourser, contrairement au remplissage joueur).
     */
    private boolean handleGive(CommandSender sender, String[] args) {
        if (args.length < 3 || args.length > 4) {
            say(sender, "bottle.give-usage");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            say(sender, "bottle.give-offline", "%player%", args[1]);
            return true;
        }
        int xp;
        int quantity;
        try {
            xp = Integer.parseInt(args[2]);
            quantity = args.length == 4 ? Integer.parseInt(args[3]) : 1;
        } catch (NumberFormatException e) {
            say(sender, "bottle.give-numbers");
            return true;
        }
        if (xp <= 0 || quantity <= 0 || quantity > 2304) {
            say(sender, "bottle.give-range");
            return true;
        }

        Map<Integer, ItemStack> leftover = target.getInventory().addItem(createBottle(target, quantity, xp));
        int dropped = 0;
        for (ItemStack stack : leftover.values()) {
            dropped += stack.getAmount();
            target.getWorld().dropItemNaturally(target.getLocation(), stack);
        }

        String[] vars = {"%count%", String.valueOf(quantity), "%xp%", String.valueOf(xp), "%player%", target.getName(),
                "%dropped%", String.valueOf(dropped)};
        say(sender, dropped > 0 ? "bottle.given-dropped" : "bottle.given", vars);
        if (sender != target) say(target, dropped > 0 ? "bottle.received-dropped" : "bottle.received", vars);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            return result;
        }
        if (args.length == 1 && "give".startsWith(args[0].toLowerCase())) {
            result.add("give");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase().startsWith(args[1].toLowerCase())) {
                    result.add(online.getName());
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            result.add(String.valueOf(XP_PER_BOTTLE));
        }
        return result;
    }

    // ─── Utilisation (clic droit) ─────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Integer storedXp = getStoredXp(event.getItem());
        if (storedXp == null) {
            return;
        }

        // Toujours refuser l'usage vanilla : c'est ce qui empêche le lancer.
        event.setUseItemInHand(Event.Result.DENY);

        // Clic droit sur un coffre, une porte... sans s'accroupir : on laisse le bloc
        // s'ouvrir normalement et on ne consomme rien.
        Block clicked = event.getClickedBlock();
        Player player = event.getPlayer();
        if (action == Action.RIGHT_CLICK_BLOCK && clicked != null
                && clicked.getType().isInteractable() && !player.isSneaking()) {
            return;
        }
        event.setCancelled(true);

        int tick = Bukkit.getCurrentTick();
        Integer last = lastRedeemTick.put(player.getUniqueId(), tick);
        if (last != null && last == tick) {
            return;
        }

        EquipmentSlot hand = event.getHand() == null ? EquipmentSlot.HAND : event.getHand();
        ItemStack inHand = player.getInventory().getItem(hand);
        if (getStoredXp(inHand) == null) {
            return;
        }
        inHand.setAmount(inHand.getAmount() - 1);
        player.getInventory().setItem(hand, inHand.getAmount() > 0 ? inHand : null);

        player.giveExp(storedXp, false);
        fr.influencraft.flux.api.util.Sounds.cue(player, "complete");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        if (getStoredXp(event.getItem()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        if (event.getEntity() instanceof ThrownExpBottle bottle && getStoredXp(bottle.getItem()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastRedeemTick.remove(event.getPlayer().getUniqueId());
    }

    // ─── Objet ────────────────────────────────────────────────────────────

    private ItemStack createBottle(Player player, int amount, int xp) {
        ItemStack item = new ItemStack(Material.EXPERIENCE_BOTTLE, amount);
        ItemMeta meta = item.getItemMeta();
        // points, not levels: "30 levels" is only true from zero
        ItemStack dressed = fr.influencraft.flux.api.inventory.Items.dress(item, plugin.messages().raw("bottle.name"),
                fr.influencraft.flux.api.inventory.Lore.create().kv(plugin.messages().raw("bottle.contains"), xp + " xp")
                        .right(plugin.messages().raw("bottle.use")).build(), player);
        meta = dressed.getItemMeta();
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(bottledXpKey, PersistentDataType.INTEGER, xp);
        item.setItemMeta(meta);
        return item;
    }

    private Integer getStoredXp(ItemStack item) {
        if (item == null || item.getType() != Material.EXPERIENCE_BOTTLE || !item.hasItemMeta()) {
            return null;
        }
        Integer value = item.getItemMeta().getPersistentDataContainer()
                .get(bottledXpKey, PersistentDataType.INTEGER);
        return value != null && value > 0 ? value : null;
    }

    private void say(CommandSender to, String key, String... vars) {
        plugin.messages().send(to, key, vars);
    }
}

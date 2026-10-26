package fr.influencraft.influbridge;

import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
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

    private static final String PREFIX = "&#FFD94D&lFiole d'XP &8► ";
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexCharacter('#')
            .hexColors()
            .build();

    private final NamespacedKey bottledXpKey;
    /** Tick du dernier clic consommé par joueur : un clic peut déclencher main + offhand. */
    private final Map<UUID, Integer> lastRedeemTick = new HashMap<>();

    public BottleXpService(JavaPlugin plugin) {
        this.bottledXpKey = new NamespacedKey(plugin, "bottled_xp");
    }

    // ─── Commande ──────────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("give") && sender.hasPermission(ADMIN_PERMISSION)) {
            return handleGive(sender, args);
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Cette commande est réservée aux joueurs.");
            return true;
        }
        if (!player.hasPermission(PERMISSION)) {
            send(player, PREFIX + "&#FF5C5CTu n'as pas la permission d'effectuer cette action.");
            return true;
        }

        int totalXp = getTotalXp(player);
        int requested = totalXp / XP_PER_BOTTLE;
        if (requested == 0) {
            send(player, PREFIX + "&#FF5C5CIl faut être au moins niveau 30 pour remplir une fiole.");
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
            send(player, PREFIX + "&#FF5C5CTon inventaire est plein, libère un emplacement.");
            return true;
        }

        setTotalXp(player, totalXp - given * XP_PER_BOTTLE);
        player.playSound(player.getLocation(), Sound.ITEM_BOTTLE_FILL, 1f, 1f);

        String fioles = given > 1 ? given + " fioles" : "1 fiole";
        if (notGiven > 0) {
            send(player, PREFIX + "&#2ECF7BTu as rempli " + fioles + " d'expérience. &#FF5C5CInventaire plein : "
                    + notGiven + (notGiven > 1 ? " fioles n'ont" : " fiole n'a")
                    + " pas pu être créée" + (notGiven > 1 ? "s" : "") + ", l'XP correspondante est conservée.");
        } else {
            send(player, PREFIX + "&#2ECF7BTu as rempli " + fioles + " d'expérience.");
        }
        return true;
    }

    /**
     * /bottlexp give <joueur> <xp> [quantité] : fioles contenant un montant libre (hors
     * tranche de 1395). Inventaire plein : le surplus tombe aux pieds de la cible, l'admin
     * est prévenu (rien à rembourser, contrairement au remplissage joueur).
     */
    private boolean handleGive(CommandSender sender, String[] args) {
        if (args.length < 3 || args.length > 4) {
            sendTo(sender, PREFIX + "&#FF5C5CUsage : /bottlexp give <joueur> <xp> [quantité]");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sendTo(sender, PREFIX + "&#FF5C5CJoueur introuvable ou hors ligne : " + args[1]);
            return true;
        }
        int xp;
        int quantity;
        try {
            xp = Integer.parseInt(args[2]);
            quantity = args.length == 4 ? Integer.parseInt(args[3]) : 1;
        } catch (NumberFormatException e) {
            sendTo(sender, PREFIX + "&#FF5C5CL'XP et la quantité doivent être des nombres entiers.");
            return true;
        }
        if (xp <= 0 || quantity <= 0 || quantity > 2304) {
            sendTo(sender, PREFIX + "&#FF5C5CL'XP doit être positive et la quantité entre 1 et 2304.");
            return true;
        }

        Map<Integer, ItemStack> leftover = target.getInventory().addItem(createBottle(target, quantity, xp));
        int dropped = 0;
        for (ItemStack stack : leftover.values()) {
            dropped += stack.getAmount();
            target.getWorld().dropItemNaturally(target.getLocation(), stack);
        }

        String fioles = quantity > 1 ? quantity + " fioles" : "1 fiole";
        sendTo(sender, PREFIX + "&#2ECF7B" + fioles + " de &f" + xp + " xp &#2ECF7Bdonnée"
                + (quantity > 1 ? "s" : "") + " à &f" + target.getName() + "&#2ECF7B."
                + (dropped > 0 ? " &#FF5C5CInventaire plein : " + dropped + " au sol." : ""));
        if (sender != target) {
            send(target, PREFIX + "&#2ECF7BTu as reçu " + fioles + " de &f" + xp + " xp&#2ECF7B."
                    + (dropped > 0 ? " &#FF5C5CInventaire plein : " + dropped + " au sol." : ""));
        }
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
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.2f);
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
        meta.displayName(text(player, "&#FFD94DFiole d'expérience"));
        // Points et non niveaux : "30 niveaux" n'est exact qu'en partant de zéro.
        meta.lore(List.of(
                text(player, "&#B376E5Contient &f" + xp + " &#B376E5xp"),
                Component.empty(),
                text(player, "&r&7%img_right_click%&#FFD94D Absorber l'expérience")));
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

    private Component text(Player player, String legacy) {
        String resolved = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")
                ? PlaceholderAPI.setPlaceholders(player, legacy)
                : legacy;
        return LEGACY.deserialize(resolved).decoration(TextDecoration.ITALIC, false);
    }

    private void send(Player player, String message) {
        player.sendMessage(text(player, message));
    }

    private void sendTo(CommandSender sender, String message) {
        if (sender instanceof Player player) {
            send(player, message);
        } else {
            sender.sendMessage(LEGACY.deserialize(message));
        }
    }

    // ─── Calcul d'XP (formules vanilla) ───────────────────────────────────
    // Player#getTotalExperience n'est pas fiable (pas décrémenté par la table
    // d'enchantement ni l'enclume) : on recalcule depuis niveau + progression.

    private static int xpToNextLevel(int level) {
        if (level <= 15) {
            return 2 * level + 7;
        }
        if (level <= 30) {
            return 5 * level - 38;
        }
        return 9 * level - 158;
    }

    private static int xpForLevel(int level) {
        int total = 0;
        for (int i = 0; i < level; i++) {
            total += xpToNextLevel(i);
        }
        return total;
    }

    static int getTotalXp(Player player) {
        int level = player.getLevel();
        return xpForLevel(level) + Math.round(player.getExp() * xpToNextLevel(level));
    }

    static void setTotalXp(Player player, int totalXp) {
        int level = 0;
        int remaining = Math.max(0, totalXp);
        while (remaining >= xpToNextLevel(level)) {
            remaining -= xpToNextLevel(level);
            level++;
        }
        player.setLevel(level);
        player.setExp((float) remaining / xpToNextLevel(level));
        player.setTotalExperience(Math.max(0, totalXp));
    }
}

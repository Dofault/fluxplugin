package fr.influencraft.influpet;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class BoosterListener implements Listener {

    private final InfluPet plugin;
    /** Dernier tick traité par joueur : un clic peut lever plusieurs événements (deux mains, air + bloc). */
    private final Map<UUID, Integer> lastTick = new HashMap<>();
    /** Tick de la dernière ouverture de booster, pour repérer les ouvertures en rafale. */
    private final Map<UUID, Integer> lastOpen = new HashMap<>();

    public BoosterListener(InfluPet plugin) {
        this.plugin = plugin;
    }

    private String boosterId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(plugin.boosterKey(), PersistentDataType.STRING);
    }

    private static boolean isCardOrBooster(InfluPet plugin, ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        var pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.has(plugin.boosterKey()) || pdc.has(plugin.cardKey());
    }

    /**
     * Clic droit : passe la révélation en cours s'il y en a une, puis, avec un booster en main,
     * en ouvre un nouveau. Spammer le clic droit ouvre donc tous les boosters à la suite.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        // Un plugin a interdit d'utiliser l'objet en main (ex. le supermarché : plus aucune interaction dans le
        // magasin, sinon on ouvrait des boosters et mangeait des cartes, même pas encore payés). On ne regarde
        // pas isCancelled() : Bukkit annule d'office les clics dans le vide (useInteractedBlock = DENY), mais
        // ne touche à useItemInHand que si un plugin l'a décidé.
        if (event.useItemInHand() == org.bukkit.event.Event.Result.DENY) {
            return;
        }
        // Comme pour un objet vanilla : un bloc qui a sa propre action au clic droit (coffre, four, porte, levier,
        // table de craft...) passe avant l'objet tenu, sauf accroupi. BlockType#isInteractable reprend la règle du
        // jeu (le bloc redéfinit useWithoutItem / useItemOn), pas une liste à tenir à jour. Bloc dont l'usage est
        // refusé (protection) : il ne s'ouvrira pas, l'objet tenu reprend la main.
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null
                && !event.getPlayer().isSneaking()
                && event.useInteractedBlock() != org.bukkit.event.Event.Result.DENY
                && event.getClickedBlock().getType().asBlockType() != null
                && event.getClickedBlock().getType().asBlockType().isInteractable()) {
            return;
        }
        Player player = event.getPlayer();
        EquipmentSlot hand = event.getHand();
        if (hand == null) {
            return;
        }
        String id = boosterId(player.getInventory().getItem(hand));
        // la main secondaire ne compte que si la principale ne tient pas de booster
        if (hand == EquipmentSlot.OFF_HAND && boosterId(player.getInventory().getItemInMainHand()) != null) {
            if (id != null) {
                event.setCancelled(true);
            }
            return;
        }
        if (id == null && !plugin.reveals().isRevealing(player)) {
            useCard(event, player, hand);
            return;
        }
        if (id != null) {
            event.setCancelled(true);
        }

        int tick = Bukkit.getCurrentTick();
        Integer last = lastTick.put(player.getUniqueId(), tick);
        if (last != null && last == tick) {
            return;
        }

        boolean skipped = plugin.reveals().skip(player, id == null);
        if (id == null) {
            return;
        }
        open(player, hand, id, skipped);
    }

    private void open(Player player, EquipmentSlot hand, String id, boolean afterSkip) {
        CardRegistry.Booster booster = plugin.registry().booster(id);
        if (booster == null) {
            plugin.send(player, "unknown-booster", "%id%", id);
            return;
        }
        if (player.isSneaking()) {
            openAll(player, hand, booster);
            return;
        }
        // place vérifiée AVANT le tirage, sur le pire cas du booster
        if (!hasRoom(player, hand, booster, 1)) {
            return;
        }
        List<CardRegistry.Reward> cards = plugin.registry().roll(booster, player);
        if (cards.isEmpty()) {
            plugin.send(player, "empty-booster");
            return;
        }
        ItemStack held = player.getInventory().getItem(hand);
        held.setAmount(held.getAmount() - 1);
        player.getInventory().setItem(hand, held.getAmount() > 0 ? held : null);
        // rafale : booster ouvert pendant une révélation ou peu après le précédent -> sans animation
        int now = Bukkit.getCurrentTick();
        Integer previous = lastOpen.put(player.getUniqueId(), now);
        long window = plugin.getConfig().getLong("reveal.spam-window-ticks", 40);
        if (afterSkip || (previous != null && now - previous < window)) {
            plugin.reveals().instant(player, cards);
            return;
        }
        plugin.send(player, "opened", "%booster%", booster.name());
        plugin.reveals().start(player, cards, booster.itemId());
    }

    /**
     * Le tirage (déjà fait) doit tenir dans l'inventaire, sinon le booster n'est ni ouvert ni consommé.
     */
    private boolean hasRoom(Player player, EquipmentSlot hand, CardRegistry.Booster booster, int boosters) {
        int slot = hand == EquipmentSlot.HAND ? player.getInventory().getHeldItemSlot() : -1;
        int missing = plugin.reveals().worstSlots(player, booster, boosters)
                - plugin.reveals().freeSlots(player, slot, boosters);
        if (missing > 0) {
            plugin.send(player, "inventory-needed", "%slots%", String.valueOf(missing));
            player.playSound(player, "entity.villager.no", 0.6f, 1f);
            return false;
        }
        return true;
    }

    /**
     * Maj + clic droit : ouvre d'un coup tous les boosters de la pile tenue, sans animation. Les
     * nouveaux familiers gardent leur mise en scène.
     */
    private void openAll(Player player, EquipmentSlot hand, CardRegistry.Booster booster) {
        ItemStack held = player.getInventory().getItem(hand);
        int amount = held == null ? 0 : held.getAmount();
        if (!hasRoom(player, hand, booster, amount)) {
            return;
        }
        List<CardRegistry.Reward> all = new java.util.ArrayList<>();
        for (int i = 0; i < amount; i++) {
            all.addAll(plugin.registry().roll(booster, player));
        }
        if (all.isEmpty()) {
            plugin.send(player, "empty-booster");
            return;
        }
        player.getInventory().setItem(hand, null);
        lastOpen.put(player.getUniqueId(), Bukkit.getCurrentTick());
        plugin.reveals().instant(player, all, amount);
    }

    /**
     * Carte en main + clic droit : la carte nourrit son familier (XP selon sa rareté).
     * Maj + clic droit : toute la pile. Le familier doit déjà être adopté.
     */
    private void useCard(PlayerInteractEvent event, Player player, EquipmentSlot hand) {
        ItemStack held = player.getInventory().getItem(hand);
        if (held == null || !held.hasItemMeta()) {
            return;
        }
        String cardId = held.getItemMeta().getPersistentDataContainer().get(plugin.cardKey(), PersistentDataType.STRING);
        if (cardId == null) {
            return;
        }
        event.setCancelled(true);
        int tick = Bukkit.getCurrentTick();
        Integer last = lastTick.put(player.getUniqueId(), tick);
        if (last != null && last == tick) {
            return;
        }
        CardRegistry.Card card = plugin.registry().card(cardId);
        if (card == null || card.pet() == null || !PetHook.available()) {
            plugin.send(player, "card-no-use");
            return;
        }
        String petName = plugin.registry().petName(card.pet());
        if (!PetHook.has(player, card.pet())) {
            plugin.send(player, "card-pet-locked", "%pet%", petName);
            return;
        }
        double perCard = plugin.registry().cardXp(card.rarity());
        if (perCard <= 0) {
            plugin.send(player, "card-no-use");
            return;
        }
        int used = player.isSneaking() ? held.getAmount() : 1;
        held.setAmount(held.getAmount() - used);
        player.getInventory().setItem(hand, held.getAmount() > 0 ? held : null);
        double xp = perCard * used;
        PetHook.giveXp(player, card.pet(), xp);
        player.sendActionBar(Texts.of(plugin.format("card-used", "%amount%", String.valueOf(used),
                "%xp%", String.format(InfluPet.locale(), "%.0f", xp), "%pet%", petName)));
        player.playSound(player, "entity.player.levelup", 0.6f, 1.6f);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastTick.remove(event.getPlayer().getUniqueId());
        lastOpen.remove(event.getPlayer().getUniqueId());
        plugin.reveals().skip(event.getPlayer(), false);
        plugin.reveals().clearShowcases(event.getPlayer());
    }

    /** Mort pendant une révélation : les cartes restantes suivent les règles de l'inventaire. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent event) {
        List<ItemStack> rest = plugin.reveals().takeRemaining(event.getPlayer());
        if (rest.isEmpty()) {
            return;
        }
        if (event.getKeepInventory()) {
            rest.forEach(item -> RevealManager.giveOrDrop(event.getPlayer(), item));
        } else {
            event.getDrops().addAll(rest);
        }
    }

    /** Cartes et boosters sont du papier : on les empêche de servir dans une recette. */
    @EventHandler
    public void onCraft(PrepareItemCraftEvent event) {
        for (ItemStack item : event.getInventory().getMatrix()) {
            if (isCardOrBooster(plugin, item)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }
}

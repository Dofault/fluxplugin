package fr.influencraft.fluxdungeons;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * What a player takes into a dungeon. Before he enters, everything he has on him (inventory, experience, life,
 * hunger, effects, game mode, place) is saved in the database; he then carries only what he chose on the launch
 * screen ({@link Selection}): his armour and six items, with his abilities on the first three hotbar slots.
 * Leaving gives back the rest, plus what he still carries. Dying makes him lose what he carried ({@link #strip}).
 * <p>
 * The saved state also keeps a copy of what he carried when he entered: after a crash the player's own file may be
 * older or newer than the run, so recovery ignores it and rebuilds everything from the saved state (no duplicate).
 */
public final class Loadouts {

    /** Hotbar slot of the first chosen item: slots 0 to 2 hold the abilities. */
    public static final int FIRST_ITEM_SLOT = 3;
    /** Items a player takes besides his armour. */
    public static final int ITEMS = 6;
    /** Inventory indexes of the armour pieces, boots to helmet. */
    public static final int[] ARMOR = {36, 37, 38, 39};
    /** Iron pieces lent on easy levels, boots to helmet. */
    private static final org.bukkit.Material[] LOAN_ARMOR = {org.bukkit.Material.IRON_BOOTS, org.bukkit.Material.IRON_LEGGINGS,
            org.bukkit.Material.IRON_CHESTPLATE, org.bukkit.Material.IRON_HELMET};
    /** Marks an item lent for a run: never given back, never locked with the player's gear. */
    private static final NamespacedKey LOANED = new NamespacedKey("fluxdungeons", "loaned");

    /**
     * What a player chose: {@code items} holds indexes of his inventory (-1 = empty), {@code snapshots} the items seen
     * there when chosen (a changed item is left behind), {@code armor} which worn pieces he takes, boots to helmet.
     */
    public record Selection(int[] items, ItemStack[] snapshots, boolean[] armor, List<String> pets) {
        public static Selection empty() {
            return new Selection(filled(ITEMS), new ItemStack[ITEMS], new boolean[]{true, true, true, true}, new ArrayList<>());
        }

        private static int[] filled(int n) {
            int[] a = new int[n];
            java.util.Arrays.fill(a, -1);
            return a;
        }
    }

    private final Store store;
    private final Logger log;
    private final java.util.function.Predicate<ItemStack> abilityItem;
    /** Items that did not fit back into a player's inventory: handed over another way (rewards). */
    private java.util.function.BiConsumer<Player, List<ItemStack>> overflow = (p, items) -> items.forEach(i -> p.getWorld().dropItem(p.getLocation(), i));

    public Loadouts(Store store, Logger log, java.util.function.Predicate<ItemStack> abilityItem) {
        this.store = store;
        this.log = log;
        this.abilityItem = abilityItem;
    }

    public void onOverflow(java.util.function.BiConsumer<Player, List<ItemStack>> overflow) {
        this.overflow = overflow;
    }

    public boolean hasVault(Player player) {
        return store.vault(player.getUniqueId()) != null;
    }

    /** Saves what the player has, then leaves him only what he chose. False (nothing changed) when saving failed. */
    public boolean enter(Player player, Selection selection) {
        ItemStack[] contents = player.getInventory().getContents();
        ItemStack[] carried = new ItemStack[contents.length];
        for (int k = 0; k < ITEMS && k < selection.items().length; k++) {
            int index = selection.items()[k];
            if (index < 0 || index >= 36 || contents[index] == null) continue;
            ItemStack seen = selection.snapshots()[k];
            if (seen != null && !(seen.isSimilar(contents[index]) && seen.getAmount() == contents[index].getAmount())) continue;
            carried[FIRST_ITEM_SLOT + k] = contents[index];
            contents[index] = null;
        }
        for (int a = 0; a < ARMOR.length; a++) {
            if (!selection.armor()[a] || contents[ARMOR[a]] == null) continue;
            carried[ARMOR[a]] = contents[ARMOR[a]];
            contents[ARMOR[a]] = null;
        }
        YamlConfiguration y = new YamlConfiguration();
        y.set("items", Store.encode(contents));
        y.set("carried", Store.encode(carried));
        y.set("level", player.getLevel());
        y.set("exp", (double) player.getExp());
        y.set("total-exp", player.getTotalExperience());
        y.set("health", player.getHealth());
        y.set("food", player.getFoodLevel());
        y.set("saturation", (double) player.getSaturation());
        y.set("fire", player.getFireTicks());
        y.set("game-mode", player.getGameMode().name());
        y.set("allow-flight", player.getAllowFlight());
        y.set("flying", player.isFlying());
        List<String> effects = new ArrayList<>();
        for (PotionEffect e : player.getActivePotionEffects()) {
            effects.add(e.getType().getKey() + ";" + e.getAmplifier() + ";" + e.getDuration() + ";" + e.isAmbient() + ";" + e.hasParticles());
        }
        y.set("effects", effects);
        Location l = player.getLocation();
        y.set("world", l.getWorld().getName());
        y.set("x", l.getX());
        y.set("y", l.getY());
        y.set("z", l.getZ());
        y.set("yaw", (double) l.getYaw());
        y.set("pitch", (double) l.getPitch());
        if (!store.saveVault(player.getUniqueId(), y.saveToString())) return false;

        player.getInventory().clear();
        for (PotionEffect e : player.getActivePotionEffects()) player.removePotionEffect(e.getType());
        player.setFireTicks(0);
        player.setFlying(false);
        player.setAllowFlight(false);
        player.setGameMode(GameMode.ADVENTURE);
        player.getInventory().setContents(carried);
        player.getInventory().setHeldItemSlot(FIRST_ITEM_SLOT);
        return true;
    }

    /**
     * Gives back what the player had before, plus what he still carries (ability items excluded). Returns where he
     * stood before entering (null when unknown: the caller sends him to the spawn).
     */
    public Location leave(Player player) {
        ItemStack[] carried = carried(player);
        Location back = restore(player, null);
        giveBack(player, carried);
        return back;
    }

    /**
     * The player died: takes everything he carries (returned) and forgets it in the saved state. Items enchanted
     * with Soulbound (EcoEnchants' {@code soulbound}) stay with him, as anywhere else on the server.
     */
    public ItemStack[] strip(Player player) {
        ItemStack[] carried = carried(player);
        ItemStack[] kept = new ItemStack[carried.length];
        for (int i = 0; i < carried.length; i++) {
            if (soulbound(carried[i])) {
                kept[i] = carried[i];
                carried[i] = null;
            }
        }
        player.getInventory().clear();
        player.getInventory().setContents(kept);
        String data = store.vault(player.getUniqueId());
        if (data != null) {
            YamlConfiguration y = new YamlConfiguration();
            try {
                y.loadFromString(data);
                y.set("carried", Store.encode(new ItemStack[0]));
                store.saveVault(player.getUniqueId(), y.saveToString());
            } catch (InvalidConfigurationException e) {
                log.warning("Saved state of " + player.getName() + " is unreadable: " + e.getMessage());
            }
        }
        return carried;
    }

    /**
     * At join: a player who still has a saved state left a dungeon without getting it back (disconnect, crash).
     * Everything is rebuilt from the saved state: his things, plus what he carried when he entered.
     */
    public Location recover(Player player) {
        if (!hasVault(player)) return null;
        ItemStack[][] carried = new ItemStack[1][];
        Location back = restore(player, carried);
        if (carried[0] != null) giveBack(player, carried[0]);
        return back;
    }

    private static boolean soulbound(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        for (org.bukkit.enchantments.Enchantment e : item.getEnchantments().keySet()) {
            if (e.getKey().getKey().equals("soulbound")) return true;
        }
        return false;
    }

    /** What the player carries now, ability items and lent items left out. */
    private ItemStack[] carried(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) if (abilityItem.test(contents[i]) || loaned(contents[i])) contents[i] = null;
        return contents;
    }

    /**
     * Easy levels: fills the armour slots the player left empty with iron pieces and gives him an iron sword when he
     * carries no weapon, all marked as lent. True when something was lent.
     */
    public boolean lend(Player player, String label) {
        org.bukkit.inventory.PlayerInventory inv = player.getInventory();
        boolean lent = false;
        for (int a = 0; a < ARMOR.length; a++) {
            ItemStack worn = inv.getItem(ARMOR[a]);
            if (worn != null && !worn.isEmpty()) continue;
            inv.setItem(ARMOR[a], loan(LOAN_ARMOR[a], label));
            lent = true;
        }
        for (ItemStack item : inv.getStorageContents()) if (isWeapon(item)) return lent;
        int slot = -1;
        for (int i = FIRST_ITEM_SLOT; i < 9 && slot < 0; i++) if (inv.getItem(i) == null || inv.getItem(i).isEmpty()) slot = i;
        if (slot < 0) slot = inv.firstEmpty();
        if (slot < 0) return lent;
        inv.setItem(slot, loan(org.bukkit.Material.IRON_SWORD, label));
        return true;
    }

    private static ItemStack loan(org.bukkit.Material material, String label) {
        ItemStack item = new ItemStack(material);
        item.editMeta(m -> {
            m.getPersistentDataContainer().set(LOANED, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
            m.lore(List.of(fr.influencraft.flux.api.text.Texts.parse(label)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
        });
        return item;
    }

    public static boolean loaned(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(LOANED, org.bukkit.persistence.PersistentDataType.BYTE);
    }

    /**
     * A weapon to defend oneself: any sword, axe, spear, bow, crossbow, trident or mace, custom ones included (they
     * keep their material); or an item known to Flux ({@code CustomItems}) made on another material that adds attack
     * damage (a custom weapon drawn on paper).
     */
    public static boolean isWeapon(ItemStack item) {
        if (item == null || item.isEmpty() || loaned(item)) return false;
        String n = item.getType().name();
        if (n.endsWith("_SWORD") || n.endsWith("_AXE") || n.endsWith("_SPEAR") || n.equals("BOW") || n.equals("CROSSBOW")
                || n.equals("TRIDENT") || n.equals("MACE")) return true;
        if (fr.influencraft.flux.api.items.CustomItems.id(item) == null || !item.hasItemMeta()) return false;
        java.util.Collection<org.bukkit.attribute.AttributeModifier> mods = item.getItemMeta().getAttributeModifiers(Attribute.ATTACK_DAMAGE);
        if (mods == null) return false;
        for (org.bukkit.attribute.AttributeModifier m : mods) if (m.getAmount() > 0) return true;
        return false;
    }

    /** Puts carried items back: armour on when its slot is free, the rest in the inventory, overflow elsewhere. */
    private void giveBack(Player player, ItemStack[] carried) {
        org.bukkit.inventory.PlayerInventory inv = player.getInventory();
        List<ItemStack> rest = new ArrayList<>();
        for (int i = 0; i < carried.length; i++) {
            ItemStack item = carried[i];
            if (item == null || item.isEmpty() || abilityItem.test(item) || loaned(item)) continue;
            if (i >= 36 && i < 40 && (inv.getItem(i) == null || inv.getItem(i).isEmpty())) inv.setItem(i, item);
            else rest.add(item);
        }
        List<ItemStack> left = new ArrayList<>();
        for (ItemStack item : rest) left.addAll(inv.addItem(item).values());
        if (!left.isEmpty()) overflow.accept(player, left);
    }

    private Location restore(Player player, ItemStack[][] carriedOut) {
        String data = store.vault(player.getUniqueId());
        if (data == null) return null;
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(data);
        } catch (InvalidConfigurationException e) {
            log.severe("Saved state of " + player.getName() + " is unreadable, kept in the database: " + e.getMessage());
            return null;
        }
        if (carriedOut != null && y.contains("carried")) carriedOut[0] = Store.decode(y.getString("carried", ""));
        player.getInventory().clear();
        ItemStack[] saved = Store.decode(y.getString("items", ""));
        ItemStack[] contents = new ItemStack[player.getInventory().getContents().length];
        System.arraycopy(saved, 0, contents, 0, Math.min(saved.length, contents.length));
        player.getInventory().setContents(contents);
        for (PotionEffect e : player.getActivePotionEffects()) player.removePotionEffect(e.getType());
        for (String line : y.getStringList("effects")) {
            String[] a = line.split(";");
            try {
                PotionEffectType type = Registry.EFFECT.get(NamespacedKey.fromString(a[0]));
                if (type != null) player.addPotionEffect(new PotionEffect(type, Integer.parseInt(a[2]), Integer.parseInt(a[1]),
                        Boolean.parseBoolean(a[3]), Boolean.parseBoolean(a[4])));
            } catch (RuntimeException ignored) {
                // an effect that no longer exists
            }
        }
        player.setTotalExperience(y.getInt("total-exp"));
        player.setLevel(y.getInt("level"));
        player.setExp((float) y.getDouble("exp"));
        AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
        double cap = max == null ? 20 : max.getValue();
        player.setHealth(Math.max(1, Math.min(cap, y.getDouble("health", 20))));
        player.setFoodLevel(y.getInt("food", 20));
        player.setSaturation((float) y.getDouble("saturation", 5));
        player.setFireTicks(y.getInt("fire"));
        try {
            player.setGameMode(GameMode.valueOf(y.getString("game-mode", "SURVIVAL")));
        } catch (IllegalArgumentException e) {
            player.setGameMode(GameMode.SURVIVAL);
        }
        player.setAllowFlight(y.getBoolean("allow-flight"));
        if (y.getBoolean("flying") && player.getAllowFlight()) player.setFlying(true);
        store.deleteVault(player.getUniqueId());
        World world = Bukkit.getWorld(y.getString("world", ""));
        if (world == null) return null;
        return new Location(world, y.getDouble("x"), y.getDouble("y"), y.getDouble("z"),
                (float) y.getDouble("yaw"), (float) y.getDouble("pitch"));
    }
}

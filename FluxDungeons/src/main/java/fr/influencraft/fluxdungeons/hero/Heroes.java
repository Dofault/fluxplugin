package fr.influencraft.fluxdungeons.hero;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.util.Format;
import fr.influencraft.flux.api.util.Sounds;
import fr.influencraft.fluxdungeons.Store;
import fr.influencraft.fluxdungeons.hero.HeroClass.AbilitySpec;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Classes of the server and the heroes playing them. A player picks a class outside dungeons ({@link #choose}); when
 * he enters a dungeon he becomes a {@link Hero} ({@link #activate}): his class stats are added to his own and his
 * experience bar shows his mana. Hotbar: abilities on keys 1, 2 and 3, the weapon in the middle (key 5), and the
 * slots on each side of it repeat abilities 1 and 2, so the wheel casts them too (up: ability 1, down: ability 2).
 * Selecting an ability slot casts it and keeps the weapon in hand ({@link #castSlot}).
 */
public final class Heroes {

    /** Hotbar slot of the weapon (key 5). */
    public static final int WEAPON_SLOT = 4;
    /** Hotbar slots of abilities 1, 2 and 3 (keys 1, 2, 3). */
    private static final int[] ABILITY_SLOTS = {0, 1, 2};
    /** Slots on each side of the weapon, reached with the wheel: a copy of abilities 1 (up) and 2 (down). */
    private static final int[] WHEEL_SLOTS = {3, 5};
    /** Shortest time between two casts (a wheel turn fires several slot changes), never shown. */
    private static final long CAST_GAP_MS = 250;
    private final java.util.Map<java.util.UUID, Long> lastCast = new java.util.HashMap<>();

    private static final Map<String, Attribute> STATS = Map.of(
            "max-health", Attribute.MAX_HEALTH,
            "attack", Attribute.ATTACK_DAMAGE,
            "armor", Attribute.ARMOR,
            "speed", Attribute.MOVEMENT_SPEED,
            "attack-speed", Attribute.ATTACK_SPEED,
            "knockback-resistance", Attribute.KNOCKBACK_RESISTANCE);

    private final FluxPlugin plugin;
    private final Store store;
    private final Abilities abilities;
    private final NamespacedKey abilityKey;
    private final Map<String, HeroClass> classes = new LinkedHashMap<>();
    private final Map<UUID, Hero> active = new ConcurrentHashMap<>();
    private int maxLevel = 30;
    private double xpBase = 60, xpExponent = 1.5;

    public Heroes(FluxPlugin plugin, Store store) {
        this.plugin = plugin;
        this.store = store;
        this.abilities = new Abilities(plugin);
        this.abilityKey = new NamespacedKey(plugin, "ability");
    }

    public void load(ConfigurationSection classesFile, ConfigurationSection settings) {
        classes.clear();
        if (classesFile != null) {
            for (String id : classesFile.getKeys(false)) {
                ConfigurationSection c = classesFile.getConfigurationSection(id);
                if (c != null) classes.put(id, HeroClass.read(id, c));
            }
        }
        if (classes.isEmpty()) plugin.getLogger().warning("No class in classes.yml: players cannot enter dungeons.");
        if (settings != null) {
            maxLevel = Math.max(1, settings.getInt("max-level", 30));
            xpBase = settings.getDouble("xp-base", 60);
            xpExponent = settings.getDouble("xp-exponent", 1.5);
        }
    }

    public Collection<HeroClass> classes() {
        return classes.values();
    }

    public HeroClass get(String id) {
        return id == null ? null : classes.get(id);
    }

    public Abilities abilities() {
        return abilities;
    }

    /** The class the player plays, null when he never chose one. */
    public HeroClass classOf(UUID uuid) {
        return get(store.heroClass(uuid));
    }

    public void choose(Player player, HeroClass heroClass) {
        store.heroClass(player.getUniqueId(), heroClass.id());
        if (!store.heroes(player.getUniqueId()).containsKey(heroClass.id())) {
            store.hero(player.getUniqueId(), heroClass.id(), new Store.HeroRow(1, 0, false));
        }
    }

    /** Level of the player in a class (1 when he never played it). */
    public int level(UUID uuid, String heroClass) {
        Store.HeroRow row = store.heroes(uuid).get(heroClass);
        return row == null ? 1 : row.level();
    }

    public Hero hero(Player player) {
        return active.get(player.getUniqueId());
    }

    public Hero hero(UUID uuid) {
        return active.get(uuid);
    }

    /** Class level of a player in a run (1 outside). */
    public int levelOf(Player player) {
        Hero h = active.get(player.getUniqueId());
        return h == null ? 1 : h.level();
    }

    // ---------------------------------------------------------------- entering / leaving a dungeon

    /**
     * The player becomes a hero of his class: stats added, ability items placed, full life and mana. Gives the class
     * kit the first time he plays it ({@code kitGiven}). Returns null when he has no class.
     */
    public Hero activate(Player player) {
        HeroClass heroClass = classOf(player.getUniqueId());
        if (heroClass == null) return null;
        Store.HeroRow row = store.heroes(player.getUniqueId()).get(heroClass.id());
        if (row == null) row = new Store.HeroRow(1, 0, false);
        Hero hero = new Hero(player.getUniqueId(), heroClass, row.level(), row.xp());
        active.put(player.getUniqueId(), hero);
        if (!row.kitGiven()) {
            for (String id : heroClass.kit()) {
                ItemStack item = fr.influencraft.flux.api.items.CustomItems.item(id, player);
                if (item == null) {
                    plugin.getLogger().warning("Unknown kit item " + id + " of class " + heroClass.id());
                    continue;
                }
                if (!equip(player, item)) {
                    player.getInventory().addItem(item).values().forEach(left -> player.getWorld().dropItem(player.getLocation(), left));
                }
            }
            store.hero(player.getUniqueId(), heroClass.id(), new Store.HeroRow(row.level(), row.xp(), true));
        }
        applyStats(player, hero);
        placeAbilityItems(player, hero);
        AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
        if (max != null) player.setHealth(max.getValue());
        player.setFoodLevel(20);
        player.setSaturation(10);
        showMana(player, hero);
        return hero;
    }

    /** Puts a piece of armour on when its slot is free; false when the item is not armour or the slot is taken. */
    private static boolean equip(Player player, ItemStack item) {
        org.bukkit.inventory.EquipmentSlot slot = item.getType().getEquipmentSlot();
        PlayerInventory inv = player.getInventory();
        ItemStack worn = switch (slot) {
            case HEAD -> inv.getHelmet();
            case CHEST -> inv.getChestplate();
            case LEGS -> inv.getLeggings();
            case FEET -> inv.getBoots();
            default -> null;
        };
        if (slot != org.bukkit.inventory.EquipmentSlot.HEAD && slot != org.bukkit.inventory.EquipmentSlot.CHEST
                && slot != org.bukkit.inventory.EquipmentSlot.LEGS && slot != org.bukkit.inventory.EquipmentSlot.FEET) return false;
        if (worn != null && !worn.isEmpty()) return false;
        inv.setItem(slot, item);
        return true;
    }

    /** Back to a plain player: stats removed, ability items removed from the inventory. */
    public void deactivate(Player player) {
        active.remove(player.getUniqueId());
        removeStats(player);
        removeAbilityItems(player.getInventory());
    }

    /** Stat modifiers and ability items left by a crash (called at join, outside dungeons). */
    public void cleanLeftovers(Player player) {
        removeStats(player);
        removeAbilityItems(player.getInventory());
    }

    /** An item that only lives during a run (ability): never saved, never dropped. */
    public boolean isAbilityItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(abilityKey, PersistentDataType.INTEGER);
    }

    public void removeAbilityItems(PlayerInventory inv) {
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) if (isAbilityItem(contents[i])) inv.setItem(i, null);
        if (isAbilityItem(inv.getItemInOffHand())) inv.setItemInOffHand(null);
    }

    // ---------------------------------------------------------------- stats

    private void applyStats(Player player, Hero hero) {
        removeStats(player);
        for (Map.Entry<String, Attribute> e : STATS.entrySet()) {
            double value = hero.heroClass().stat(e.getKey(), hero.level());
            if (value == 0) continue;
            AttributeInstance inst = player.getAttribute(e.getValue());
            if (inst == null) continue;
            AttributeModifier.Operation op = e.getKey().equals("speed") ? AttributeModifier.Operation.ADD_SCALAR
                    : AttributeModifier.Operation.ADD_NUMBER;
            inst.addModifier(new AttributeModifier(statKey(e.getKey()), value, op, EquipmentSlotGroup.ANY));
        }
    }

    private void removeStats(Player player) {
        for (Map.Entry<String, Attribute> e : STATS.entrySet()) {
            AttributeInstance inst = player.getAttribute(e.getValue());
            if (inst != null) inst.removeModifier(statKey(e.getKey()));
        }
        AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
        if (max != null && player.getHealth() > max.getValue()) player.setHealth(max.getValue());
    }

    private NamespacedKey statKey(String stat) {
        return new NamespacedKey(plugin, "class_" + stat.replace('-', '_'));
    }

    // ---------------------------------------------------------------- abilities

    private void placeAbilityItems(Player player, Hero hero) {
        PlayerInventory inv = player.getInventory();
        removeAbilityItems(inv);
        List<AbilitySpec> specs = hero.heroClass().abilities();
        List<Integer> reserved = new ArrayList<>();
        for (int i = 0; i < specs.size() && i < ABILITY_SLOTS.length; i++) reserved.add(ABILITY_SLOTS[i]);
        for (int i = 0; i < specs.size() && i < WHEEL_SLOTS.length; i++) reserved.add(WHEEL_SLOTS[i]);
        // free the reserved slots (their items go elsewhere), keep the weapon slot for a weapon
        for (int slot : reserved) moveAway(player, slot);
        if (!weapon(inv.getItem(WEAPON_SLOT))) {
            for (int i = 0; i < 36; i++) {
                if (i == WEAPON_SLOT || reserved.contains(i) || !weapon(inv.getItem(i))) continue;
                ItemStack there = inv.getItem(WEAPON_SLOT);
                inv.setItem(WEAPON_SLOT, inv.getItem(i));
                inv.setItem(i, there);
                break;
            }
        }
        for (int i = 0; i < specs.size() && i < ABILITY_SLOTS.length; i++) inv.setItem(ABILITY_SLOTS[i], abilityItem(player, specs.get(i), i));
        for (int i = 0; i < specs.size() && i < WHEEL_SLOTS.length; i++) inv.setItem(WHEEL_SLOTS[i], abilityItem(player, specs.get(i), i));
        inv.setHeldItemSlot(WEAPON_SLOT);
    }

    private static void moveAway(Player player, int slot) {
        PlayerInventory inv = player.getInventory();
        ItemStack there = inv.getItem(slot);
        if (there == null || there.isEmpty()) return;
        inv.setItem(slot, null);
        for (int i = 9; i < 36; i++) {
            if (inv.getItem(i) == null || inv.getItem(i).isEmpty()) {
                inv.setItem(i, there);
                return;
            }
        }
        inv.addItem(there).values().forEach(left -> player.getWorld().dropItem(player.getLocation(), left));
    }

    /** Something to fight with: a weapon, a staff, a tool held as a weapon (not armour, not arrows). */
    private static boolean weapon(ItemStack item) {
        if (item == null || item.isEmpty()) return false;
        String n = item.getType().name();
        return n.endsWith("_SWORD") || n.endsWith("_AXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE") || n.endsWith("_SPEAR")
                || n.equals("BOW") || n.equals("CROSSBOW") || n.equals("TRIDENT") || n.equals("MACE") || n.equals("BLAZE_ROD")
                || n.equals("STICK") || n.equals("BREEZE_ROD");
    }

    /** How to use an ability, for its description ({@code ability.key-*}). */
    private String keyLabel(int index) {
        String kind = index == 0 ? "up" : index == 1 ? "down" : "key";
        return plugin.messages().raw("ability.key-" + kind, "%key%", String.valueOf(ABILITY_SLOTS[index] + 1));
    }

    private ItemStack abilityItem(Player viewer, AbilitySpec spec, int index) {
        List<String> lore = Lore.create()
                .description(spec.description())
                .kv(plugin.messages().raw("ability.mana"), Format.number(spec.mana()))
                .info(plugin.messages().raw("ability.how", "%key%", keyLabel(index)))
                .build();
        ItemStack item = Items.dress(new ItemStack(spec.icon()), spec.name(), lore, viewer);
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(abilityKey, PersistentDataType.INTEGER, index);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * The player selected a hotbar slot: casts the ability there, if any. True when the slot holds an ability (the
     * selection must then be cancelled, so the weapon stays in hand).
     */
    public boolean castSlot(Player player, int slot, Abilities.Context ctx) {
        Hero hero = hero(player);
        if (hero == null) return false;
        ItemStack item = player.getInventory().getItem(slot);
        if (!isAbilityItem(item)) return false;
        Integer index = item.getItemMeta().getPersistentDataContainer().get(abilityKey, PersistentDataType.INTEGER);
        if (index == null || index >= hero.heroClass().abilities().size()) return true;
        cast(player, hero, index, ctx);
        return true;
    }

    private void cast(Player player, Hero hero, int index, Abilities.Context ctx) {
        AbilitySpec spec = hero.heroClass().abilities().get(index);
        if (player.isDead() || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) return;
        // abilities are limited by mana only; this short gap only stops one wheel turn from casting several times
        long now = System.currentTimeMillis();
        Long last = lastCast.get(player.getUniqueId());
        if (last != null && now - last < CAST_GAP_MS) return;
        if (hero.mana() < spec.mana()) {
            plugin.messages().actionBar(player, "ability.no-mana", "%ability%", spec.name());
            Sounds.cue(player, "error");
            return;
        }
        if (!abilities.cast(player, hero, spec, ctx)) {
            plugin.messages().actionBar(player, "ability.no-target", "%ability%", spec.name());
            return;
        }
        hero.mana(hero.mana() - spec.mana());
        lastCast.put(player.getUniqueId(), now);
        showMana(player, hero);
    }

    /** Every second: mana comes back, the bar and the action bar show it. */
    public void tick(Player player) {
        Hero hero = hero(player);
        if (hero == null) return;
        hero.mana(hero.mana() + hero.heroClass().manaRegen());
        showMana(player, hero);
        plugin.messages().actionBar(player, "hud.bar",
                "%mana%", String.valueOf((int) hero.mana()), "%max%", String.valueOf((int) hero.heroClass().mana()));
    }

    /** The experience bar is the mana bar, the level number is the class level. */
    private void showMana(Player player, Hero hero) {
        player.setLevel(hero.level());
        player.setExp((float) Math.max(0, Math.min(0.9999, hero.mana() / Math.max(1, hero.heroClass().mana()))));
    }

    // ---------------------------------------------------------------- experience

    /** Experience needed to go from this level to the next. */
    public double xpToNext(int level) {
        return Math.round(xpBase * Math.pow(level, xpExponent));
    }

    public int maxLevel() {
        return maxLevel;
    }

    /** Class experience earned in a dungeon; levels up (stats applied again) and saves. */
    public void giveXp(Player player, double amount) {
        Hero hero = hero(player);
        if (hero == null || amount <= 0) return;
        double xp = hero.xp() + amount;
        int level = hero.level();
        boolean up = false;
        while (level < maxLevel && xp >= xpToNext(level)) {
            xp -= xpToNext(level);
            level++;
            up = true;
        }
        if (level >= maxLevel) xp = 0;
        hero.level(level);
        hero.xp(xp);
        store.hero(player.getUniqueId(), hero.heroClass().id(), new Store.HeroRow(level, xp, true));
        if (up) {
            applyStats(player, hero);
            plugin.messages().send(player, "level-up", "%class%", hero.heroClass().colored(), "%level%", String.valueOf(level));
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
        }
    }
}

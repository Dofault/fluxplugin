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
 * he enters a dungeon he becomes a {@link Hero} ({@link #activate}): his class stats are added to his own and the
 * action bar shows his mana. Hotbar: abilities on keys 1, 2 and 3, then the six items he chose on the launch screen.
 * Selecting an ability slot casts it and keeps the item in hand ({@link #castSlot}).
 */
public final class Heroes {

    /** Hotbar slots of abilities 1, 2 and 3 (keys 1, 2, 3). */
    private static final int[] ABILITY_SLOTS = {0, 1, 2};
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
     * The player becomes a hero of his class: stats added, ability items placed, full life and mana. Returns null
     * when he has no class.
     */
    public Hero activate(Player player) {
        HeroClass heroClass = classOf(player.getUniqueId());
        if (heroClass == null) return null;
        Store.HeroRow row = store.heroes(player.getUniqueId()).get(heroClass.id());
        if (row == null) row = new Store.HeroRow(1, 0, false);
        Hero hero = new Hero(player.getUniqueId(), heroClass, row.level(), row.xp());
        active.put(player.getUniqueId(), hero);
        applyStats(player, hero);
        placeAbilityItems(player, hero);
        AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
        if (max != null) player.setHealth(max.getValue());
        player.setFoodLevel(20);
        player.setSaturation(10);
        showMana(player, hero);
        return hero;
    }

    /** Icons of the class abilities, in hotbar order (launch screen). */
    public List<ItemStack> abilityIcons(Player viewer, HeroClass heroClass) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < heroClass.abilities().size() && i < ABILITY_SLOTS.length; i++) {
            ItemStack icon = abilityItem(viewer, heroClass.abilities().get(i), i);
            icon.editMeta(m -> m.getPersistentDataContainer().remove(abilityKey));
            out.add(icon);
        }
        return out;
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

    /** Abilities on the first hotbar slots (the launch screen keeps them free: whatever is there is moved away). */
    private void placeAbilityItems(Player player, Hero hero) {
        PlayerInventory inv = player.getInventory();
        removeAbilityItems(inv);
        List<AbilitySpec> specs = hero.heroClass().abilities();
        for (int i = 0; i < specs.size() && i < ABILITY_SLOTS.length; i++) {
            moveAway(player, ABILITY_SLOTS[i]);
            inv.setItem(ABILITY_SLOTS[i], abilityItem(player, specs.get(i), i));
        }
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

    /** How to use an ability, for its description ({@code ability.key-*}). */
    private String keyLabel(int index) {
        return plugin.messages().raw("ability.key-key", "%key%", String.valueOf(ABILITY_SLOTS[index] + 1));
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

    /** Every second: mana comes back, the action bar shows it. */
    public void tick(Player player) {
        Hero hero = hero(player);
        if (hero == null) return;
        hero.mana(hero.mana() + hero.heroClass().manaRegen());
        plugin.messages().actionBar(player, "hud.bar",
                "%mana%", String.valueOf((int) hero.mana()), "%max%", String.valueOf((int) hero.heroClass().mana()));
    }

    /**
     * Shows the mana in the action bar at once (after a cast). The experience bar is left alone: it is the player's own
     * experience (it showed the mana before, players took its moves for experience gains).
     */
    private void showMana(Player player, Hero hero) {
        plugin.messages().actionBar(player, "hud.bar",
                "%mana%", String.valueOf((int) hero.mana()), "%max%", String.valueOf((int) hero.heroClass().mana()));
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

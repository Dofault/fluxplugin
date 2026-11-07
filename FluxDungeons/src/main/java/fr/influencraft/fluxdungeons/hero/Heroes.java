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
 * he enters a dungeon he becomes a {@link Hero} ({@link #activate}): his class stats are added to his own, his three
 * abilities sit on hotbar slots 7, 8 and 9, and his experience bar shows his mana. Selecting one of those slots casts
 * the ability and keeps the weapon in hand ({@link #castSlot}).
 */
public final class Heroes {

    /** Hotbar slots of the three abilities (keys 7, 8 and 9). */
    public static final int FIRST_SLOT = 6;

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
                player.getInventory().addItem(item).values().forEach(left -> player.getWorld().dropItem(player.getLocation(), left));
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
        for (int i = 0; i < specs.size() && FIRST_SLOT + i < 9; i++) {
            int slot = FIRST_SLOT + i;
            ItemStack there = inv.getItem(slot);
            if (there != null && !there.isEmpty()) {
                inv.setItem(slot, null);
                inv.addItem(there).values().forEach(left -> player.getWorld().dropItem(player.getLocation(), left));
            }
            inv.setItem(slot, abilityItem(player, specs.get(i), i));
        }
        if (inv.getHeldItemSlot() >= FIRST_SLOT) inv.setHeldItemSlot(0);
    }

    private ItemStack abilityItem(Player viewer, AbilitySpec spec, int index) {
        List<String> lore = Lore.create()
                .description(spec.description())
                .kv(plugin.messages().raw("ability.mana"), Format.number(spec.mana()))
                .kv(plugin.messages().raw("ability.cooldown"), Format.decimal(spec.cooldown()) + " s")
                .info(plugin.messages().raw("ability.how", "%key%", String.valueOf(FIRST_SLOT + index + 1)))
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
        double cd = hero.cooldown(index);
        if (cd > 0) {
            plugin.messages().actionBar(player, "ability.cooling", "%ability%", spec.name(), "%time%", Format.decimal(cd));
            Sounds.cue(player, "error");
            return;
        }
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
        hero.cooldown(index, spec.cooldown());
        ItemStack item = player.getInventory().getItem(FIRST_SLOT + index);
        if (item != null) player.setCooldown(item, (int) (spec.cooldown() * 20));
        showMana(player, hero);
    }

    /** Every second: mana comes back, the bar and the action bar show it. */
    public void tick(Player player) {
        Hero hero = hero(player);
        if (hero == null) return;
        hero.mana(hero.mana() + hero.heroClass().manaRegen());
        showMana(player, hero);
        List<String> parts = new ArrayList<>();
        List<AbilitySpec> specs = hero.heroClass().abilities();
        for (int i = 0; i < specs.size(); i++) {
            double cd = hero.cooldown(i);
            parts.add(plugin.messages().raw(cd > 0 ? "hud.ability-cooling" : "hud.ability-ready",
                    "%key%", String.valueOf(FIRST_SLOT + i + 1), "%ability%", specs.get(i).name(),
                    "%time%", String.valueOf((int) Math.ceil(cd))));
        }
        plugin.messages().actionBar(player, "hud.bar",
                "%mana%", String.valueOf((int) hero.mana()), "%max%", String.valueOf((int) hero.heroClass().mana()),
                "%abilities%", String.join(plugin.messages().raw("hud.separator"), parts));
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

package fr.influencraft.flux.universe;

import fr.influencraft.flux.api.universe.Universes;
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
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;

/**
 * A Bukkit player as {@link Transition} sees him. The marker lives in the player's own data (saved by the game in the
 * same file, at the same time, as his inventory). A profile is YAML text: inventory (armour and off hand included),
 * ender chest, experience, life, hunger, effects, fire, game mode, flight, and where he stood (return point).
 */
final class PlayerLive implements Transition.Live<String> {

    /** Never rename: players' files carry them. */
    static final NamespacedKey MARKER = new NamespacedKey("flux", "universe");
    static final NamespacedKey MARKER_VERSION = new NamespacedKey("flux", "universe_version");

    private final Player player;
    /** Game mode of a fresh profile, per universe. */
    private final Function<String, GameMode> freshMode;
    /** Loose items of an applied profile that found no room (handed over later, in that universe). */
    private final java.util.function.BiConsumer<String, List<ItemStack>> overflow;

    /**
     * Where the player stood in the outgoing universe (the return point of its profile). The swap happens once he is
     * already in the other universe's world, so his current place is never it: the caller gives the place before the
     * teleport, or null to keep the return point of the stored profile.
     */
    private final Location origin;
    private final String previous;

    PlayerLive(Player player, Function<String, GameMode> freshMode, java.util.function.BiConsumer<String, List<ItemStack>> overflow) {
        this(player, freshMode, overflow, null, null);
    }

    PlayerLive(Player player, Function<String, GameMode> freshMode, java.util.function.BiConsumer<String, List<ItemStack>> overflow,
               Location origin, String previous) {
        this.player = player;
        this.freshMode = freshMode;
        this.overflow = overflow;
        this.origin = origin;
        this.previous = previous;
    }

    /**
     * Takes the items that are neither in the inventory nor in the ender chest: on the cursor and in the 2x2 crafting
     * grid. Closing the inventory would put them back or, when it is full, drop them where the player stands (in the
     * world of the other universe during a swap).
     */
    private List<ItemStack> takeLoose() {
        List<ItemStack> loose = new ArrayList<>();
        ItemStack cursor = player.getItemOnCursor();
        if (!cursor.isEmpty()) loose.add(cursor.clone());
        player.setItemOnCursor(null);
        org.bukkit.inventory.Inventory top = player.getOpenInventory().getTopInventory();
        if (top instanceof org.bukkit.inventory.CraftingInventory grid && top.getType() == org.bukkit.event.inventory.InventoryType.CRAFTING) {
            for (ItemStack i : grid.getMatrix()) if (i != null && !i.isEmpty()) loose.add(i.clone());
            grid.setMatrix(new ItemStack[grid.getMatrix().length]);
        }
        return loose;
    }

    static String markerOf(Player player) {
        String u = player.getPersistentDataContainer().get(MARKER, PersistentDataType.STRING);
        return u == null ? Universes.VANILLA : u;
    }

    @Override
    public String universe() {
        return markerOf(player);
    }

    @Override
    public long version() {
        Long v = player.getPersistentDataContainer().get(MARKER_VERSION, PersistentDataType.LONG);
        return v == null ? 0 : v;
    }

    @Override
    public void mark(String universe, long version) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        pdc.set(MARKER, PersistentDataType.STRING, universe);
        pdc.set(MARKER_VERSION, PersistentDataType.LONG, version);
    }

    @Override
    public String capture() {
        List<ItemStack> loose = takeLoose();
        player.closeInventory();
        YamlConfiguration y = new YamlConfiguration();
        y.set("items", encode(player.getInventory().getContents()));
        y.set("loose", encode(loose.toArray(new ItemStack[0])));
        y.set("ender", encode(player.getEnderChest().getContents()));
        y.set("held", player.getInventory().getHeldItemSlot());
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
        Location l = origin != null ? origin : location(previous);
        if (l == null) return y.saveToString();   // no known return point: /rpg quitter falls back to the spawn
        y.set("world", l.getWorld().getName());
        y.set("x", l.getX());
        y.set("y", l.getY());
        y.set("z", l.getZ());
        y.set("yaw", (double) l.getYaw());
        y.set("pitch", (double) l.getPitch());
        return y.saveToString();
    }

    @Override
    public void apply(String profile, String universe, long version) {
        // what the outgoing profile had loose was captured with it; anything still loose belongs to the current marker
        List<ItemStack> stray = takeLoose();
        if (!stray.isEmpty()) overflow.accept(universe(), stray);
        player.closeInventory();
        YamlConfiguration y = new YamlConfiguration();
        if (profile != null) {
            try {
                y.loadFromString(profile);
            } catch (InvalidConfigurationException e) {
                // never reached with our own text; refusing here would leave the player half swapped
                throw new IllegalStateException("profile " + universe + " of " + player.getName() + " is unreadable", e);
            }
        }
        player.getInventory().clear();
        player.getInventory().setContents(fit(decode(y.getString("items", "")), player.getInventory().getSize()));
        player.getEnderChest().setContents(fit(decode(y.getString("ender", "")), player.getEnderChest().getSize()));
        player.getInventory().setHeldItemSlot(Math.max(0, Math.min(8, y.getInt("held", 0))));
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
        player.setTotalExperience(y.getInt("total-exp", 0));
        player.setLevel(y.getInt("level", 0));
        player.setExp((float) y.getDouble("exp", 0));
        AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
        double cap = max == null ? 20 : max.getValue();
        player.setHealth(Math.max(1, Math.min(cap, y.getDouble("health", cap))));
        player.setFoodLevel(y.getInt("food", 20));
        player.setSaturation((float) y.getDouble("saturation", 5));
        player.setFireTicks(y.getInt("fire", 0));
        GameMode mode;
        try {
            mode = profile == null ? freshMode.apply(universe) : GameMode.valueOf(y.getString("game-mode", "SURVIVAL"));
        } catch (IllegalArgumentException e) {
            mode = freshMode.apply(universe);
        }
        // only what really changes is sent: a needless game mode or flight packet can unsettle a moving client
        if (player.getGameMode() != mode) player.setGameMode(mode);
        boolean allow = y.getBoolean("allow-flight", mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR);
        if (player.getAllowFlight() != allow) player.setAllowFlight(allow);
        boolean fly = y.getBoolean("flying") && allow;
        if (player.isFlying() != fly) player.setFlying(fly);
        mark(universe, version);
        List<ItemStack> left = new ArrayList<>();
        for (ItemStack i : decode(y.getString("loose", ""))) if (i != null) left.addAll(player.getInventory().addItem(i).values());
        if (!left.isEmpty()) overflow.accept(universe, left);
        player.updateInventory();
    }

    @Override
    public void persist() {
        player.saveData();
    }

    /** Where the player stood in a stored profile (null when unknown or the world is gone). */
    static Location location(String profile) {
        if (profile == null) return null;
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(profile);
        } catch (InvalidConfigurationException e) {
            return null;
        }
        World w = Bukkit.getWorld(y.getString("world", ""));
        return w == null ? null : new Location(w, y.getDouble("x"), y.getDouble("y"), y.getDouble("z"),
                (float) y.getDouble("yaw"), (float) y.getDouble("pitch"));
    }

    static String encode(ItemStack[] items) {
        ItemStack[] copy = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) copy[i] = items[i] == null ? ItemStack.empty() : items[i];
        return Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(copy));
    }

    static ItemStack[] decode(String data) {
        if (data == null || data.isEmpty()) return new ItemStack[0];
        ItemStack[] items = ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(data));
        for (int i = 0; i < items.length; i++) if (items[i] != null && items[i].isEmpty()) items[i] = null;
        return items;
    }

    private static ItemStack[] fit(ItemStack[] items, int size) {
        ItemStack[] out = new ItemStack[size];
        System.arraycopy(items, 0, out, 0, Math.min(items.length, size));
        return out;
    }
}

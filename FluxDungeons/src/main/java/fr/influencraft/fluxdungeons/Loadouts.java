package fr.influencraft.fluxdungeons;

import fr.influencraft.flux.api.items.CustomItems;
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
 * The two inventories of a player. Outside, his own things; in a dungeon, his dungeon inventory (class kit, gear
 * found in dungeons), kept between runs and never seen outside. Entering saves everything he has on him (inventory,
 * experience, life, hunger, effects, game mode, place) in the database first, then swaps; leaving does the opposite.
 * Dungeon gear therefore never reaches the semi-RP world, and nothing of the semi-RP world enters a dungeon.
 */
public final class Loadouts {

    private final Store store;
    private final Logger log;
    private final java.util.function.Predicate<ItemStack> abilityItem;

    public Loadouts(Store store, Logger log, java.util.function.Predicate<ItemStack> abilityItem) {
        this.store = store;
        this.log = log;
        this.abilityItem = abilityItem;
    }

    public boolean hasVault(Player player) {
        return store.vault(player.getUniqueId()) != null;
    }

    /** Saves what the player has, then gives him his dungeon inventory. False (nothing changed) when saving failed. */
    public boolean enter(Player player) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("items", Store.encode(player.getInventory().getContents()));
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
        player.setLevel(0);
        player.setExp(0);
        player.setFireTicks(0);
        player.setFlying(false);
        player.setAllowFlight(false);
        player.setGameMode(GameMode.ADVENTURE);
        ItemStack[] dungeon = store.loadout(player.getUniqueId());
        if (dungeon != null) {
            ItemStack[] contents = new ItemStack[player.getInventory().getContents().length];
            System.arraycopy(dungeon, 0, contents, 0, Math.min(dungeon.length, contents.length));
            player.getInventory().setContents(contents);
        }
        return true;
    }

    /**
     * Keeps the dungeon inventory the player has on him (ability items excluded) and gives him back what he had
     * before. Returns where he stood before entering (null when unknown: the caller sends him to the spawn).
     */
    public Location leave(Player player) {
        saveDungeonInventory(player);
        return restore(player);
    }

    /** At join: a player who still has a saved state left a dungeon without getting it back (disconnect, crash). */
    public Location recover(Player player, boolean inDungeonWorld) {
        if (!hasVault(player)) return null;
        if (inDungeonWorld || carriesDungeonItems(player)) saveDungeonInventory(player);
        return restore(player);
    }

    private void saveDungeonInventory(Player player) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) if (abilityItem.test(contents[i])) contents[i] = null;
        store.loadout(player.getUniqueId(), contents);
    }

    private boolean carriesDungeonItems(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (abilityItem.test(item)) return true;
            String id = item == null ? null : CustomItems.id(item);
            if (id != null && id.startsWith("fluxdungeons:")) return true;
        }
        return false;
    }

    private Location restore(Player player) {
        String data = store.vault(player.getUniqueId());
        if (data == null) return null;
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(data);
        } catch (InvalidConfigurationException e) {
            log.severe("Saved state of " + player.getName() + " is unreadable, kept in the database: " + e.getMessage());
            return null;
        }
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

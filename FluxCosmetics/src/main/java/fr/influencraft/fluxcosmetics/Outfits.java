package fr.influencraft.fluxcosmetics;

import fr.influencraft.flux.api.client.Appearance;
import fr.influencraft.flux.api.data.Database;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.items.CustomItems;
import fr.influencraft.flux.api.staff.Staff;
import fr.influencraft.flux.api.util.Sounds;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The cosmetics, who owns them and what each player wears. A worn cosmetic is an item drawn in one equipment slot over
 * his real one ({@link Appearance}): the others see it, he sees it too (third person, inventory; his real item's name
 * and lore kept) and so does the wardrobe's mannequin; his real armour never changes.
 *
 * <ul>
 *   <li>A player owns a cosmetic that is {@code free}, that he was given ({@code /tenue donner}, table
 *   {@code flux_cosmetics_owned}), or every cosmetic when he is an admin ({@link Staff#isAdmin}).</li>
 *   <li>What he wears is kept in {@code flux_cosmetics_worn} and put on again when he joins (dropped if he no longer
 *   owns it).</li>
 *   <li>Each cosmetic has a rarity (the {@code rarities} of the config, in order; names in the lang file).</li>
 * </ul>
 * Database reads and writes off the server thread; the looks are put on from the server thread.
 */
final class Outfits implements Listener {

    /** A rarity: id, colour of its name, rank (file order). */
    record Rarity(String id, String colour, int rank) {
    }

    /** {@code item}: what is worn, a Flux item id ({@code namespace:id}) or a material. */
    record Cosmetic(String key, String name, String item, EquipmentSlot slot, boolean free, Rarity rarity) {
    }

    /** What a player has: kept while he is online. */
    private static final class Wardrobe {
        final Set<String> owned = ConcurrentHashMap.newKeySet();
        final Map<EquipmentSlot, String> worn = new EnumMap<>(EquipmentSlot.class);
    }

    private final FluxCosmetics plugin;
    private final Map<String, Cosmetic> cosmetics = new LinkedHashMap<>();
    private final Map<String, Rarity> rarities = new LinkedHashMap<>();
    private final Map<UUID, Wardrobe> players = new ConcurrentHashMap<>();
    private String ownedTable, wornTable;
    private final java.util.concurrent.ExecutorService db = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FluxCosmetics-db");
        t.setDaemon(true);
        return t;
    });

    Outfits(FluxCosmetics plugin) {
        this.plugin = plugin;
    }

    void createTables() {
        ownedTable = Database.table("flux_cosmetics_owned");
        wornTable = Database.table("flux_cosmetics_worn");
        String uuid = Database.dialect().key(36);
        try (Connection c = Database.connection(); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + ownedTable + " (uuid " + uuid + " NOT NULL, cosmetic VARCHAR(64) NOT NULL,"
                    + " PRIMARY KEY (uuid, cosmetic))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + wornTable + " (uuid " + uuid + " NOT NULL, slot VARCHAR(8) NOT NULL,"
                    + " cosmetic VARCHAR(64) NOT NULL, PRIMARY KEY (uuid, slot))");
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not create the cosmetics tables: " + e.getMessage());
        }
    }

    void load(ConfigurationSection config) {
        rarities.clear();
        ConfigurationSection r = config.getConfigurationSection("rarities");
        if (r != null) for (String id : r.getKeys(false)) {
            rarities.put(id, new Rarity(id, r.getString(id + ".colour", "#FFFFFF"), rarities.size()));
        }
        if (rarities.isEmpty()) rarities.put("common", new Rarity("common", "#FFFFFF", 0));
        Rarity lowest = rarities.values().iterator().next();
        cosmetics.clear();
        ConfigurationSection section = config.getConfigurationSection("cosmetics");
        if (section == null) {
            plugin.getLogger().warning("No cosmetics in the config");
            return;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection c = section.getConfigurationSection(key);
            if (c == null) continue;
            EquipmentSlot slot = switch (c.getString("slot", "head").toLowerCase(Locale.ROOT)) {
                case "chest" -> EquipmentSlot.CHEST;
                case "legs" -> EquipmentSlot.LEGS;
                case "feet" -> EquipmentSlot.FEET;
                default -> EquipmentSlot.HEAD;
            };
            String item = c.getString("item");
            if (item == null || item.isBlank()) {
                plugin.getLogger().warning("Cosmetic " + key + ": no item, left out");
                continue;
            }
            Rarity rarity = rarities.get(c.getString("rarity", ""));
            if (rarity == null) {
                plugin.getLogger().warning("Cosmetic " + key + ": unknown rarity " + c.getString("rarity") + ", " + lowest.id() + " used");
                rarity = lowest;
            }
            cosmetics.put(key, new Cosmetic(key, c.getString("name", key), item, slot, c.getBoolean("free", false), rarity));
        }
        // the looks of the players online follow the new definitions
        for (Player p : Bukkit.getOnlinePlayers()) if (players.containsKey(p.getUniqueId())) dress(p);
    }

    List<String> keys() {
        return List.copyOf(cosmetics.keySet());
    }

    Cosmetic get(String key) {
        return cosmetics.get(key);
    }

    /** Every cosmetic, rarest last, in config order within a rarity. */
    List<Cosmetic> all() {
        List<Cosmetic> out = new ArrayList<>(cosmetics.values());
        out.sort(Comparator.comparingInt(c -> c.rarity().rank()));
        return out;
    }

    /** Whether he may wear it: free, given to him, or he is an admin. */
    boolean owns(Player player, Cosmetic c) {
        if (c.free() || Staff.isAdmin(player)) return true;
        Wardrobe w = players.get(player.getUniqueId());
        return w != null && w.owned.contains(c.key());
    }

    /** Key of the cosmetic he wears in that slot, or null. */
    String worn(Player player, EquipmentSlot slot) {
        Wardrobe w = players.get(player.getUniqueId());
        return w == null ? null : w.worn.get(slot);
    }

    /**
     * Puts this cosmetic on him, or takes it off when he wears it. False when it does not exist. {@code quiet}: no
     * chat line (the menu shows it on the mannequin).
     */
    boolean toggle(Player player, String key, boolean quiet) {
        Cosmetic c = cosmetics.get(key);
        if (c == null) return false;
        if (!owns(player, c)) {
            Sounds.cue(player, "error");
            if (!quiet) plugin.messages().send(player, "outfits.locked", "%name%", c.name());
            return true;
        }
        Wardrobe w = players.computeIfAbsent(player.getUniqueId(), k -> new Wardrobe());
        if (key.equals(w.worn.get(c.slot()))) {
            w.worn.remove(c.slot());
            Appearance.set(player, c.slot(), null);
            saveWorn(player.getUniqueId(), c.slot(), null);
            Sounds.cue(player, "toggle-off");
            if (!quiet) plugin.messages().send(player, "outfits.taken-off", "%name%", c.name());
        } else {
            w.worn.put(c.slot(), key);
            Appearance.set(player, c.slot(), item(c, player));
            saveWorn(player.getUniqueId(), c.slot(), key);
            Sounds.cue(player, "toggle-on");
            if (!quiet) plugin.messages().send(player, "outfits.wearing", "%name%", c.name());
        }
        return true;
    }

    /** Takes off whatever cosmetic he wears in that slot (his real item shows again), quietly. */
    void takeOff(Player player, EquipmentSlot slot) {
        Wardrobe w = players.get(player.getUniqueId());
        if (w == null || w.worn.remove(slot) == null) return;
        Appearance.set(player, slot, null);
        saveWorn(player.getUniqueId(), slot, null);
        Sounds.cue(player, "toggle-off");
    }

    /** The item worn for this cosmetic (a new stack). */
    ItemStack item(Cosmetic c, Player player) {
        String spec = c.item();
        boolean fluxId = spec.indexOf(':') > 0 && !spec.startsWith("itemsadder:") && !spec.startsWith("source:");
        ItemStack item = fluxId ? CustomItems.item(spec) : null;
        return item != null ? item : Items.of(spec, player);
    }

    // ================================================================== giving (admin)

    /**
     * Gives cosmetics to a player, online or not (null keys: every cosmetic). Kept at once in the database; his
     * wardrobe follows if he is online.
     */
    void give(UUID player, Collection<String> keys) {
        Wardrobe w = players.get(player);
        if (w != null) w.owned.addAll(keys);
        List<String> list = List.copyOf(keys);
        async(() -> {
            try (Connection c = Database.connection()) {
                for (String key : list) {
                    try (PreparedStatement del = c.prepareStatement("DELETE FROM " + ownedTable + " WHERE uuid = ? AND cosmetic = ?");
                         PreparedStatement ins = c.prepareStatement("INSERT INTO " + ownedTable + " (uuid, cosmetic) VALUES (?, ?)")) {
                        del.setString(1, player.toString());
                        del.setString(2, key);
                        del.executeUpdate();
                        ins.setString(1, player.toString());
                        ins.setString(2, key);
                        ins.executeUpdate();
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Could not give cosmetics to " + player + ": " + e.getMessage());
            }
        });
    }

    /** Takes cosmetics back (worn ones come off when he no longer owns them). */
    void take(UUID player, Collection<String> keys) {
        Wardrobe w = players.get(player);
        if (w != null) w.owned.removeAll(keys);
        List<String> list = List.copyOf(keys);
        async(() -> {
            try (Connection c = Database.connection();
                 PreparedStatement del = c.prepareStatement("DELETE FROM " + ownedTable + " WHERE uuid = ? AND cosmetic = ?")) {
                for (String key : list) {
                    del.setString(1, player.toString());
                    del.setString(2, key);
                    del.executeUpdate();
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Could not take cosmetics from " + player + ": " + e.getMessage());
            }
        });
        Player online = Bukkit.getPlayer(player);
        if (online != null) dress(online);
    }

    // ================================================================== joining and leaving

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        open(e.getPlayer());
    }

    /** Reads what he owns and wears, then dresses him (also for the players online when the plugin starts). */
    void open(Player player) {
        UUID id = player.getUniqueId();
        async(() -> {
            Wardrobe w = new Wardrobe();
            try (Connection c = Database.connection()) {
                try (PreparedStatement ps = c.prepareStatement("SELECT cosmetic FROM " + ownedTable + " WHERE uuid = ?")) {
                    ps.setString(1, id.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) w.owned.add(rs.getString(1));
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("SELECT slot, cosmetic FROM " + wornTable + " WHERE uuid = ?")) {
                    ps.setString(1, id.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            try {
                                w.worn.put(EquipmentSlot.valueOf(rs.getString(1)), rs.getString(2));
                            } catch (IllegalArgumentException ignored) {
                                // a slot name of no use: left out
                            }
                        }
                    }
                }
            } catch (SQLException ex) {
                plugin.getLogger().warning("Could not read the cosmetics of " + id + ": " + ex.getMessage());
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                // something put on before the read came back wins (it is already written after it)
                Wardrobe before = players.put(id, w);
                if (before != null) {
                    w.owned.addAll(before.owned);
                    w.worn.putAll(before.worn);
                }
                dress(player);
            });
        });
    }

    /** Puts on him what he wears, dropping what is unknown or no longer his. */
    private void dress(Player player) {
        Wardrobe w = players.get(player.getUniqueId());
        if (w == null) return;
        for (EquipmentSlot slot : new ArrayList<>(w.worn.keySet())) {
            Cosmetic c = cosmetics.get(w.worn.get(slot));
            if (c == null || c.slot() != slot || !owns(player, c)) {
                w.worn.remove(slot);
                Appearance.set(player, slot, null);
                saveWorn(player.getUniqueId(), slot, null);
            } else {
                Appearance.set(player, slot, item(c, player));
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        players.remove(e.getPlayer().getUniqueId());
        Appearance.clear(e.getPlayer());
    }

    /** The plugin stops: every look off (put on again by the next start). */
    void closeAll() {
        for (Player p : Bukkit.getOnlinePlayers()) Appearance.clear(p);
        players.clear();
    }

    private void saveWorn(UUID player, EquipmentSlot slot, String key) {
        async(() -> {
            try (Connection c = Database.connection()) {
                try (PreparedStatement del = c.prepareStatement("DELETE FROM " + wornTable + " WHERE uuid = ? AND slot = ?")) {
                    del.setString(1, player.toString());
                    del.setString(2, slot.name());
                    del.executeUpdate();
                }
                if (key == null) return;
                try (PreparedStatement ins = c.prepareStatement("INSERT INTO " + wornTable + " (uuid, slot, cosmetic) VALUES (?, ?, ?)")) {
                    ins.setString(1, player.toString());
                    ins.setString(2, slot.name());
                    ins.setString(3, key);
                    ins.executeUpdate();
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Could not save the cosmetics worn by " + player + ": " + e.getMessage());
            }
        });
    }

    /** Database work off the server thread, one task at a time in the order asked (a fast put on / take off). */
    private void async(Runnable task) {
        if (db.isShutdown()) {
            task.run();
            return;
        }
        db.execute(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Cosmetics database task failed: " + e.getMessage());
            }
        });
    }

    /** Lets the writes waiting finish (plugin stopping). */
    void shutdown() {
        db.shutdown();
        try {
            if (!db.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) plugin.getLogger().warning("Cosmetics writes still waiting at shutdown");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Keys of every cosmetic, for "all" in the admin commands. */
    Set<String> everything() {
        return new HashSet<>(cosmetics.keySet());
    }
}

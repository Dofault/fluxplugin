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
    record Cosmetic(String key, String name, String item, EquipmentSlot slot, boolean free, Rarity rarity, boolean adminOnly) {
    }

    /**
     * An outfit saved by a player: its name (already cleaned), the cosmetic of each armour slot (missing: nothing
     * there) and the tag (FluxTags id, null: not saved).
     */
    record Preset(String name, Map<EquipmentSlot, String> pieces, String tag) {
    }

    /** What a player has: kept while he is online. */
    private static final class Wardrobe {
        final Set<String> owned = ConcurrentHashMap.newKeySet();
        final Map<EquipmentSlot, String> worn = new EnumMap<>(EquipmentSlot.class);
        /** Saved outfits by slot number (1, 2...). */
        final Map<Integer, Preset> presets = new ConcurrentHashMap<>();
        /** Rpg outfit he chose, null: his class's ({@link RpgOutfits}). */
        volatile String rpg;
    }

    /** Slot name of the chosen rpg outfit in the worn table. */
    private static final String RPG_SLOT = "RPG";

    /** Armour slots kept in a preset, in the order of the table's columns. */
    static final EquipmentSlot[] ARMOUR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final String[] COLUMNS = {"head", "chest", "legs", "feet"};

    private final FluxCosmetics plugin;
    private final RpgOutfits rpg;
    private final Map<String, Cosmetic> cosmetics = new LinkedHashMap<>();
    private final Map<String, Rarity> rarities = new LinkedHashMap<>();
    private final Map<UUID, Wardrobe> players = new ConcurrentHashMap<>();
    private String ownedTable, wornTable, presetsTable;
    private final java.util.concurrent.ExecutorService db = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FluxCosmetics-db");
        t.setDaemon(true);
        return t;
    });

    Outfits(FluxCosmetics plugin) {
        this.plugin = plugin;
        this.rpg = new RpgOutfits(plugin, this);
    }

    RpgOutfits rpg() {
        return rpg;
    }

    /** Rarity by id, the most common when unknown. */
    Rarity rarity(String id) {
        Rarity r = rarities.get(id == null ? "" : id);
        return r != null ? r : rarities.values().iterator().next();
    }

    void createTables() {
        ownedTable = Database.table("flux_cosmetics_owned");
        wornTable = Database.table("flux_cosmetics_worn");
        presetsTable = Database.table("flux_cosmetics_presets");
        String uuid = Database.dialect().key(36);
        try (Connection c = Database.connection(); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + ownedTable + " (uuid " + uuid + " NOT NULL, cosmetic VARCHAR(64) NOT NULL,"
                    + " PRIMARY KEY (uuid, cosmetic))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + wornTable + " (uuid " + uuid + " NOT NULL, slot VARCHAR(8) NOT NULL,"
                    + " cosmetic VARCHAR(64) NOT NULL, PRIMARY KEY (uuid, slot))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + presetsTable + " (uuid " + uuid + " NOT NULL, slot INT NOT NULL,"
                    + " name VARCHAR(64) NOT NULL, head VARCHAR(64), chest VARCHAR(64), legs VARCHAR(64), feet VARCHAR(64),"
                    + " tag VARCHAR(64), PRIMARY KEY (uuid, slot))");
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
            cosmetics.put(key, new Cosmetic(key, c.getString("name", key), item, slot, c.getBoolean("free", false), rarity, false));
        }
        rpg.load(config);
        // each piece of an rpg outfit, worn on its own outside the rpg universe by admins only
        for (RpgOutfits.Outfit o : rpg.all()) {
            for (EquipmentSlot slot : ARMOUR) {
                String item = o.pieces().get(slot);
                String key = pieceKey(o, slot);
                if (item != null && !cosmetics.containsKey(key)) cosmetics.put(key, new Cosmetic(key, o.name(), item, slot, false, o.rarity(), true));
            }
        }
        // the looks of the players online follow the new definitions
        for (Player p : Bukkit.getOnlinePlayers()) if (players.containsKey(p.getUniqueId())) dress(p);
    }

    /** Keys of the cosmetics players can be given (never the admin-only pieces of rpg outfits). */
    List<String> keys() {
        return cosmetics.values().stream().filter(c -> !c.adminOnly()).map(Cosmetic::key).toList();
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

    /** Whether he may wear it: free, given to him, or he is an admin (an admin-only one: admins alone). */
    boolean owns(Player player, Cosmetic c) {
        if (Staff.isAdmin(player)) return true;
        if (c.adminOnly()) return false;
        if (c.free()) return true;
        Wardrobe w = players.get(player.getUniqueId());
        return w != null && w.owned.contains(c.key());
    }

    /** Whether he was given this key (a cosmetic or an rpg outfit). */
    boolean given(Player player, String key) {
        Wardrobe w = players.get(player.getUniqueId());
        return w != null && w.owned.contains(key);
    }

    /** The rpg outfit he chose, null for his class's. */
    String rpgChoice(Player player) {
        Wardrobe w = players.get(player.getUniqueId());
        return w == null ? null : w.rpg;
    }

    /** Chooses his rpg outfit (null: his class's), shown at once in the rpg universe. */
    void chooseRpg(Player player, String key) {
        Wardrobe w = players.computeIfAbsent(player.getUniqueId(), k -> new Wardrobe());
        w.rpg = key;
        saveWorn(player.getUniqueId(), RPG_SLOT, key);
        show(player);
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
        // edited from any universe: show() draws only those of the universe he is in
        if (!owns(player, c)) {
            Sounds.cue(player, "error");
            if (!quiet) plugin.messages().send(player, "outfits.locked", "%name%", c.name());
            return true;
        }
        Wardrobe w = players.computeIfAbsent(player.getUniqueId(), k -> new Wardrobe());
        if (key.equals(w.worn.get(c.slot()))) {
            w.worn.remove(c.slot());
            show(player);
            saveWorn(player.getUniqueId(), c.slot(), null);
            Sounds.cue(player, "toggle-off");
            if (!quiet) plugin.messages().send(player, "outfits.taken-off", "%name%", c.name());
        } else {
            w.worn.put(c.slot(), key);
            show(player);
            saveWorn(player.getUniqueId(), c.slot(), key);
            Sounds.cue(player, "toggle-on");
            if (!quiet) plugin.messages().send(player, "outfits.wearing", "%name%", c.name());
        }
        return true;
    }

    /**
     * Puts this cosmetic on him, quietly, whatever he wore in its slot (a preset): false when it is unknown or not his.
     */
    boolean wear(Player player, String key) {
        Cosmetic c = cosmetics.get(key);
        if (c == null || !owns(player, c)) return false;
        Wardrobe w = players.computeIfAbsent(player.getUniqueId(), k -> new Wardrobe());
        if (key.equals(w.worn.get(c.slot()))) return true;
        w.worn.put(c.slot(), key);
        show(player);
        saveWorn(player.getUniqueId(), c.slot(), key);
        return true;
    }

    // ================================================================== presets

    /** His saved outfits by slot number (1...), empty while not read yet. */
    Map<Integer, Preset> presets(Player player) {
        Wardrobe w = players.get(player.getUniqueId());
        return w == null ? Map.of() : Map.copyOf(w.presets);
    }

    /** What he wears now, as a preset of that name ({@code tag}: his tag now, null when unknown). */
    Preset current(Player player, String name, String tag) {
        Map<EquipmentSlot, String> pieces = new EnumMap<>(EquipmentSlot.class);
        for (EquipmentSlot slot : ARMOUR) {
            String key = worn(player, slot);
            if (key != null) pieces.put(slot, key);
        }
        return new Preset(name, Map.copyOf(pieces), tag);
    }

    /** Saves (or overwrites) one of his outfits. */
    void savePreset(Player player, int number, Preset preset) {
        Wardrobe w = players.computeIfAbsent(player.getUniqueId(), k -> new Wardrobe());
        w.presets.put(number, preset);
        UUID id = player.getUniqueId();
        async(() -> {
            try (Connection c = Database.connection()) {
                try (PreparedStatement del = c.prepareStatement("DELETE FROM " + presetsTable + " WHERE uuid = ? AND slot = ?")) {
                    del.setString(1, id.toString());
                    del.setInt(2, number);
                    del.executeUpdate();
                }
                try (PreparedStatement ins = c.prepareStatement("INSERT INTO " + presetsTable
                        + " (uuid, slot, name, head, chest, legs, feet, tag) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                    ins.setString(1, id.toString());
                    ins.setInt(2, number);
                    ins.setString(3, preset.name());
                    for (int i = 0; i < ARMOUR.length; i++) ins.setString(4 + i, preset.pieces().get(ARMOUR[i]));
                    ins.setString(8, preset.tag());
                    ins.executeUpdate();
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Could not save an outfit of " + id + ": " + e.getMessage());
            }
        });
    }

    void deletePreset(Player player, int number) {
        Wardrobe w = players.get(player.getUniqueId());
        if (w != null) w.presets.remove(number);
        UUID id = player.getUniqueId();
        async(() -> {
            try (Connection c = Database.connection();
                 PreparedStatement del = c.prepareStatement("DELETE FROM " + presetsTable + " WHERE uuid = ? AND slot = ?")) {
                del.setString(1, id.toString());
                del.setInt(2, number);
                del.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Could not delete an outfit of " + id + ": " + e.getMessage());
            }
        });
    }

    /** Takes off whatever cosmetic he wears in that slot (his real item shows again), quietly. */
    void takeOff(Player player, EquipmentSlot slot) {
        Wardrobe w = players.get(player.getUniqueId());
        if (w == null || w.worn.remove(slot) == null) return;
        show(player);
        saveWorn(player.getUniqueId(), slot, null);
        Sounds.cue(player, "toggle-off");
    }

    /** The item worn for this cosmetic (a new stack), with the looks of the suite (3D armour). */
    ItemStack item(Cosmetic c, Player player) {
        String spec = c.item();
        boolean fluxId = spec.indexOf(':') > 0 && !spec.startsWith("itemsadder:") && !spec.startsWith("source:");
        ItemStack item = fluxId ? CustomItems.item(spec) : null;
        if (item == null) item = Items.of(spec, player);
        // the looks of the suite (FluxArmor's 3D worn asset, no glint over it): Flux gives them to items in
        // inventories only, and a cosmetic's item never is in one
        if (item != null) fr.influencraft.flux.api.items.ItemLooks.apply(item);
        return item;
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
                            if (RPG_SLOT.equals(rs.getString(1))) {
                                w.rpg = rs.getString(2);
                                continue;
                            }
                            try {
                                w.worn.put(EquipmentSlot.valueOf(rs.getString(1)), rs.getString(2));
                            } catch (IllegalArgumentException ignored) {
                                // a slot name of no use: left out
                            }
                        }
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("SELECT slot, name, head, chest, legs, feet, tag FROM " + presetsTable + " WHERE uuid = ?")) {
                    ps.setString(1, id.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            Map<EquipmentSlot, String> pieces = new EnumMap<>(EquipmentSlot.class);
                            for (int i = 0; i < ARMOUR.length; i++) {
                                String key = rs.getString(COLUMNS[i]);
                                if (key != null) pieces.put(ARMOUR[i], key);
                            }
                            w.presets.put(rs.getInt("slot"), new Preset(rs.getString("name"), Map.copyOf(pieces), rs.getString("tag")));
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
                    w.presets.putAll(before.presets);
                    if (before.rpg != null) w.rpg = before.rpg;
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
                saveWorn(player.getUniqueId(), slot, null);
            }
        }
        if (w.rpg != null && !rpg.owns(player, rpg.get(w.rpg))) {
            w.rpg = null;
            saveWorn(player.getUniqueId(), RPG_SLOT, null);
        }
        show(player);
    }

    /**
     * Draws what he wears for the universe he is in: in the rpg universe his rpg outfit only (the one he chose, else
     * his class's: never bare), elsewhere his vanilla cosmetics only. The one place looks are set.
     */
    void show(Player player) {
        if (!players.containsKey(player.getUniqueId())) return;
        Map<EquipmentSlot, ItemStack> looks = looks(player, rpg.in(player));
        for (EquipmentSlot slot : ARMOUR) Appearance.set(player, slot, looks.get(slot));
    }

    /**
     * His looks in a universe (rpg or not), whichever he is in: the item drawn over each armour slot, missing when his
     * real piece shows. Rpg: his rpg outfit. Elsewhere: his vanilla cosmetics (for an admin, pieces of rpg outfits too).
     */
    Map<EquipmentSlot, ItemStack> looks(Player player, boolean rpgUniverse) {
        Map<EquipmentSlot, ItemStack> out = new EnumMap<>(EquipmentSlot.class);
        Wardrobe w = players.get(player.getUniqueId());
        if (w == null) return out;
        RpgOutfits.Outfit o = rpgUniverse ? rpg.active(player, w.rpg) : null;
        if (o != null) {
            for (EquipmentSlot slot : ARMOUR) {
                ItemStack item = rpg.item(o, slot);
                if (item != null) out.put(slot, item);
            }
            return out;
        }
        if (rpgUniverse) return out;
        for (EquipmentSlot slot : ARMOUR) {
            Cosmetic c = cosmetics.get(w.worn.get(slot));
            if (c != null) out.put(slot, item(c, player));
        }
        return out;
    }

    /** Key of the cosmetic that is one piece of an rpg outfit (admins only, see {@link #load}). */
    private static String pieceKey(RpgOutfits.Outfit o, EquipmentSlot slot) {
        return o.key() + "." + slot.name().toLowerCase(Locale.ROOT);
    }

    /** Whether an admin wears every piece of this rpg outfit outside the rpg universe. */
    boolean wornHere(Player player, RpgOutfits.Outfit o) {
        Wardrobe w = players.get(player.getUniqueId());
        if (w == null || o == null || o.pieces().isEmpty() || !Staff.isAdmin(player)) return false;
        for (EquipmentSlot slot : o.pieces().keySet()) if (!pieceKey(o, slot).equals(w.worn.get(slot))) return false;
        return true;
    }

    /**
     * Puts every piece of an rpg outfit on an admin outside the rpg universe, or takes them off when he wears them
     * all. A shortcut over the single pieces: each slot can then be changed on its own.
     */
    void toggleRpgHere(Player player, RpgOutfits.Outfit o) {
        Wardrobe w = players.computeIfAbsent(player.getUniqueId(), k -> new Wardrobe());
        boolean off = wornHere(player, o);
        for (EquipmentSlot slot : o.pieces().keySet()) {
            String key = off ? null : pieceKey(o, slot);
            if (key == null) w.worn.remove(slot);
            else w.worn.put(slot, key);
            saveWorn(player.getUniqueId(), slot, key);
        }
        show(player);
    }

    /** Whether his data is read (looks can be drawn). */
    boolean ready(Player player) {
        return players.containsKey(player.getUniqueId());
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
        saveWorn(player, slot.name(), key);
    }

    private void saveWorn(UUID player, String slot, String key) {
        async(() -> {
            try (Connection c = Database.connection()) {
                try (PreparedStatement del = c.prepareStatement("DELETE FROM " + wornTable + " WHERE uuid = ? AND slot = ?")) {
                    del.setString(1, player.toString());
                    del.setString(2, slot);
                    del.executeUpdate();
                }
                if (key == null) return;
                try (PreparedStatement ins = c.prepareStatement("INSERT INTO " + wornTable + " (uuid, slot, cosmetic) VALUES (?, ?, ?)")) {
                    ins.setString(1, player.toString());
                    ins.setString(2, slot);
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

    /** Keys of every cosmetic and rpg outfit, for "all" in the admin commands. */
    Set<String> everything() {
        Set<String> out = new HashSet<>(keys());
        out.addAll(rpg.keys());
        return out;
    }
}

package fr.influencraft.fluxmigration.migrations;

import fr.influencraft.flux.api.data.Database;
import fr.influencraft.flux.api.reward.Reward;
import fr.influencraft.flux.api.reward.Rewards;
import fr.influencraft.flux.api.universe.Universes;
import fr.influencraft.fluxmigration.FluxMigration;
import fr.influencraft.fluxmigration.Migration;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 2026-11-14: FluxDungeons no longer has dungeons played with the players' own gear (only the rpg universe), so gear
 * still locked in {@code flux_dungeons_lost} (players who died in such a dungeon, never won since, or released but
 * not claimed yet) goes back to its owner as a reward ({@code /recompenses}, kept without expiry). One reward per
 * locked gear and universe, keyed by its id: running it again gives nothing twice. The table is left as it is.
 * Remove once Labs ran it (FluxDungeons never ran in production).
 */
public final class DungeonLostGearToRewards implements Migration {

    private static final String TABLE = "flux_dungeons_lost";
    private final FluxMigration plugin;

    public DungeonLostGearToRewards(FluxMigration plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "2026-11-14_dungeon_lost_gear_to_rewards";
    }

    @Override
    public String description() {
        return "Gear locked in dungeons (flux_dungeons_lost) -> rewards";
    }

    @Override
    public boolean ready() {
        if (!Database.available() || !Rewards.available()) return false;
        try (Connection c = Database.connection()) {
            return Database.tableExists(c, Database.table(TABLE));
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String run() throws Exception {
        int given = 0, already = 0, empty = 0;
        try (Connection c = Database.connection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, uuid, dungeon, items FROM " + Database.table(TABLE))) {
            while (rs.next()) {
                String id = rs.getString(1);
                UUID owner = UUID.fromString(rs.getString(2));
                List<ItemStack> items = decode(rs.getString(4));
                if (items.isEmpty()) {
                    empty++;
                    continue;
                }
                String label = plugin.messages().raw("dungeon-lost.label", "%dungeon%", rs.getString(3));
                // each item claimed in its own universe (vanilla gear in the vanilla world)
                Map<String, List<ItemStack>> byUniverse = new LinkedHashMap<>();
                for (ItemStack item : items) byUniverse.computeIfAbsent(Universes.universeOf(item), u -> new ArrayList<>()).add(item);
                for (Map.Entry<String, List<ItemStack>> en : byUniverse.entrySet()) {
                    String key = "dungeons_lost_" + id + "_" + en.getKey();
                    if (Rewards.given(owner, key)) {
                        already++;
                        continue;
                    }
                    if (!Rewards.give(Bukkit.getOfflinePlayer(owner), Reward.of("dungeons", label).key(key).items(en.getValue()).universe(en.getKey()).keep())) {
                        throw new IllegalStateException("could not keep the gear " + id + " of " + owner);
                    }
                    given++;
                }
            }
        }
        return given + " reward(s) given, " + already + " already given, " + empty + " empty";
    }

    private static List<ItemStack> decode(String data) {
        List<ItemStack> out = new ArrayList<>();
        if (data == null || data.isBlank()) return out;
        for (ItemStack i : ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(data))) {
            if (i != null && !i.isEmpty()) out.add(i);
        }
        return out;
    }
}

package fr.influencraft.fluxreferral.service;

import fr.influencraft.fluxreferral.bdd.Tables;

import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.list.ListEntry;
import fr.influencraft.flux.api.list.Lists;
import fr.influencraft.fluxreferral.bdd.GestionnaireBDD;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Referral graph (2026-10-30): who brought whom. The {@code parrainage} menu lists the players brought by the viewer
 * (list {@code parrainage_filleuls}); the whole tree is drawn by FluxFamily, where a sponsor is his referrals' parent
 * ({@link FamilyLink}).
 * <p>The whole graph (validated referrals, table {@code evenements}) is read off the main thread every minute and
 * kept in memory: it is small, and neither the menu nor the family tree ever wait for the database. Placeholders
 * {@code %parrainage_arbre_gen_<n>%} and {@code %parrainage_arbre_total%}.
 */
public final class ReferralTree {

    public static final int GENERATIONS = 4;
    private static final long REFRESH_TICKS = 20L * 60;

    /** A referred player: who he is, who brought him. */
    public record Node(UUID uuid, String name, UUID sponsor, String sponsorName) {
    }

    private final Plugin plugin;
    private final GestionnaireBDD database;
    private volatile Map<UUID, List<Node>> children = Map.of();
    private volatile Map<UUID, UUID> sponsors = Map.of();

    public ReferralTree(Plugin plugin, GestionnaireBDD database) {
        this.plugin = plugin;
        this.database = database;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::reload, 20L, REFRESH_TICKS);
        Lists.register(plugin, "parrainage_filleuls", q -> entries(q.viewer(), 1));
    }

    /** Reads every validated referral (off the main thread). */
    public void reload() {
        String sql = "SELECT e.parrain_uuid, e.filleul_uuid, f.pseudo, p.pseudo FROM `" + Tables.EVENTS + "` e"
                + " JOIN `" + Tables.PLAYERS + "` f ON f.uuid = e.filleul_uuid"
                + " JOIN `" + Tables.PLAYERS + "` p ON p.uuid = e.parrain_uuid";
        Map<UUID, List<Node>> out = new HashMap<>();
        try (Connection conn = database.obtenirConnexion();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                UUID sponsor = UUID.fromString(rs.getString(1));
                out.computeIfAbsent(sponsor, k -> new ArrayList<>())
                        .add(new Node(UUID.fromString(rs.getString(2)), rs.getString(3), sponsor, rs.getString(4)));
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Referral tree: could not read the referrals", e);
            return;
        }
        Map<UUID, UUID> up = new HashMap<>();
        for (List<Node> l : out.values()) {
            l.sort(Comparator.comparing(n -> n.name().toLowerCase()));
            for (Node n : l) up.put(n.uuid(), n.sponsor());
        }
        children = out;
        sponsors = up;
        Bukkit.getScheduler().runTask(plugin, () -> Lists.invalidate("parrainage_filleuls"));
    }

    /** Sponsor of a player, null when he was not referred. */
    public UUID sponsor(UUID player) {
        return sponsors.get(player);
    }

    /** Players brought by a sponsor. */
    public List<UUID> referrals(UUID sponsor) {
        return children.getOrDefault(sponsor, List.of()).stream().map(Node::uuid).toList();
    }

    /** Players of generation {@code generation} under {@code root} (1 = brought by him). */
    public List<Node> generation(UUID root, int generation) {
        Map<UUID, List<Node>> graph = children;
        List<Node> level = new ArrayList<>(graph.getOrDefault(root, List.of()));
        Set<UUID> seen = new HashSet<>();
        seen.add(root);
        for (Node n : level) seen.add(n.uuid());
        for (int g = 2; g <= generation; g++) {
            List<Node> next = new ArrayList<>();
            for (Node n : level) {
                for (Node c : graph.getOrDefault(n.uuid(), List.of())) {
                    if (seen.add(c.uuid())) next.add(c);   // a loop in the data would never end
                }
            }
            level = next;
        }
        return level;
    }

    public int total(UUID root) {
        int total = 0;
        for (int g = 1; g <= GENERATIONS; g++) total += generation(root, g).size();
        return total;
    }

    private List<ListEntry> entries(Player viewer, int generation) {
        List<ListEntry> out = new ArrayList<>();
        for (Node n : generation(viewer.getUniqueId(), generation)) {
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            if (head.getItemMeta() instanceof SkullMeta skull) {
                skull.setPlayerProfile(Bukkit.createProfile(n.uuid(), n.name()));
                head.setItemMeta(skull);
            }
            int theirs = children.getOrDefault(n.uuid(), List.of()).size();
            Lore lore = Lore.create().kv("Filleuls", String.valueOf(theirs));
            out.add(ListEntry.of(n.uuid().toString()).item(Items.dress(head, n.name(), lore.build(), viewer)));
        }
        return out;
    }
}

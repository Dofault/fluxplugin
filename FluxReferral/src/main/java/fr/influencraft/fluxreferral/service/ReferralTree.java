package fr.influencraft.fluxreferral.service;

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
 * Referral tree (2026-10-30): a player sees every player he brought, then the players they brought, and so on, one
 * row per generation in the {@code parrainage} menu.
 * <p>The whole graph (validated referrals, table {@code evenements}) is read off the main thread every minute and
 * kept in memory: it is small, and the menu never waits for the database. Lists {@code parrainage_gen1} to
 * {@code parrainage_gen<GENERATIONS>} (heads), placeholders {@code %parrainage_arbre_gen_<n>%} and
 * {@code %parrainage_arbre_total%}.
 */
public final class ReferralTree {

    public static final int GENERATIONS = 4;
    private static final long REFRESH_TICKS = 20L * 60;

    /** A referred player: who he is, who brought him. */
    public record Node(UUID uuid, String name, UUID sponsor, String sponsorName) {
    }

    private final Plugin plugin;
    private final GestionnaireBDD database;
    private final String prefix;
    private volatile Map<UUID, List<Node>> children = Map.of();

    public ReferralTree(Plugin plugin, GestionnaireBDD database, String prefix) {
        this.plugin = plugin;
        this.database = database;
        this.prefix = prefix;
    }

    public void start() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::reload, 20L, REFRESH_TICKS);
        for (int g = 1; g <= GENERATIONS; g++) {
            int generation = g;
            Lists.register(plugin, "parrainage_gen" + g, q -> entries(q.viewer(), generation));
        }
    }

    /** Reads every validated referral (off the main thread). */
    public void reload() {
        String sql = "SELECT e.parrain_uuid, e.filleul_uuid, f.pseudo, p.pseudo FROM `" + prefix + "evenements` e"
                + " JOIN `" + prefix + "joueurs` f ON f.uuid = e.filleul_uuid"
                + " JOIN `" + prefix + "joueurs` p ON p.uuid = e.parrain_uuid";
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
        for (List<Node> l : out.values()) l.sort(Comparator.comparing(n -> n.name().toLowerCase()));
        children = out;
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (int g = 1; g <= GENERATIONS; g++) Lists.invalidate("parrainage_gen" + g);
        });
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
            Lore lore = Lore.create().kv("Génération", String.valueOf(generation));
            if (generation > 1) lore.kv("Parrainé par", n.sponsorName());
            lore.kv("Filleuls", String.valueOf(theirs));
            out.add(ListEntry.of(n.uuid().toString()).item(Items.dress(head, n.name(), lore.build(), viewer)));
        }
        return out;
    }
}

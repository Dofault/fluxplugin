package fr.influencraft.fluxdungeons.party;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** A group of players who play together. The leader is always a member; a party of one is not kept. */
public final class Party {

    private UUID leader;
    private final Set<UUID> members = new LinkedHashSet<>();

    Party(UUID leader) {
        this.leader = leader;
        members.add(leader);
    }

    public UUID leader() {
        return leader;
    }

    void leader(UUID uuid) {
        leader = uuid;
    }

    public boolean isLeader(UUID uuid) {
        return leader.equals(uuid);
    }

    public Set<UUID> members() {
        return Set.copyOf(members);
    }

    public int size() {
        return members.size();
    }

    void add(UUID uuid) {
        members.add(uuid);
    }

    void remove(UUID uuid) {
        members.remove(uuid);
    }

    public boolean contains(UUID uuid) {
        return members.contains(uuid);
    }

    /** Members currently connected, leader first. */
    public List<Player> online() {
        List<Player> out = new ArrayList<>();
        Player l = Bukkit.getPlayer(leader);
        if (l != null) out.add(l);
        for (UUID m : members) {
            if (m.equals(leader)) continue;
            Player p = Bukkit.getPlayer(m);
            if (p != null) out.add(p);
        }
        return out;
    }
}

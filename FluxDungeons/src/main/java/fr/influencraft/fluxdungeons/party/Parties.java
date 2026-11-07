package fr.influencraft.fluxdungeons.party;

import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Parties of the server, kept in memory (a party lasts a play session). A player is in at most one party; inviting
 * creates the inviter's party. Invitations expire after {@link #INVITE_MILLIS}.
 *
 * <p>Generic on purpose: nothing here knows dungeons, so the system can move to Flux the day another plugin needs
 * groups (events, quests).</p>
 */
public final class Parties {

    public static final long INVITE_MILLIS = 120_000L;

    public enum Result { OK, SELF, ALREADY_IN_PARTY, NOT_LEADER, FULL, NO_INVITE, NOT_IN_PARTY, NOT_MEMBER }

    private final Map<UUID, Party> byMember = new HashMap<>();
    /** invited -> (party leader -> expiry ms). */
    private final Map<UUID, Map<UUID, Long>> invites = new HashMap<>();
    private int maxSize = 4;

    public void maxSize(int size) {
        maxSize = Math.max(2, size);
    }

    public int maxSize() {
        return maxSize;
    }

    public Party of(UUID player) {
        return byMember.get(player);
    }

    public Party of(Player player) {
        return byMember.get(player.getUniqueId());
    }

    public Result invite(Player from, Player to) {
        if (from.equals(to)) return Result.SELF;
        if (byMember.containsKey(to.getUniqueId())) return Result.ALREADY_IN_PARTY;
        Party party = byMember.get(from.getUniqueId());
        if (party != null && !party.isLeader(from.getUniqueId())) return Result.NOT_LEADER;
        if (party != null && party.size() >= maxSize) return Result.FULL;
        invites.computeIfAbsent(to.getUniqueId(), k -> new HashMap<>())
                .put(from.getUniqueId(), System.currentTimeMillis() + INVITE_MILLIS);
        return Result.OK;
    }

    /** Accepts the invitation of {@code leader}, or the latest one when null. */
    public Result accept(Player player, UUID leader) {
        if (byMember.containsKey(player.getUniqueId())) return Result.ALREADY_IN_PARTY;
        Map<UUID, Long> mine = invites.get(player.getUniqueId());
        if (mine == null) return Result.NO_INVITE;
        long now = System.currentTimeMillis();
        mine.values().removeIf(expiry -> expiry < now);
        UUID from = leader;
        if (from == null) {
            from = mine.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        }
        if (from == null || !mine.containsKey(from)) return Result.NO_INVITE;
        Party party = byMember.get(from);
        if (party != null && !party.isLeader(from)) return Result.NO_INVITE;
        if (party != null && party.size() >= maxSize) return Result.FULL;
        if (party == null) {
            party = new Party(from);
            byMember.put(from, party);
        }
        party.add(player.getUniqueId());
        byMember.put(player.getUniqueId(), party);
        mine.remove(from);
        if (mine.isEmpty()) invites.remove(player.getUniqueId());
        return Result.OK;
    }

    /** The player leaves his party; the party passes to the next member, and dissolves below two players. */
    public Result leave(UUID player) {
        Party party = byMember.remove(player);
        if (party == null) return Result.NOT_IN_PARTY;
        party.remove(player);
        if (party.size() < 2) {
            for (UUID m : party.members()) byMember.remove(m);
            return Result.OK;
        }
        if (party.isLeader(player)) party.leader(party.members().iterator().next());
        return Result.OK;
    }

    public Result kick(Player leader, UUID target) {
        Party party = byMember.get(leader.getUniqueId());
        if (party == null) return Result.NOT_IN_PARTY;
        if (!party.isLeader(leader.getUniqueId())) return Result.NOT_LEADER;
        if (leader.getUniqueId().equals(target)) return Result.SELF;
        if (!party.contains(target)) return Result.NOT_MEMBER;
        return leave(target);
    }

    public Result promote(Player leader, UUID target) {
        Party party = byMember.get(leader.getUniqueId());
        if (party == null) return Result.NOT_IN_PARTY;
        if (!party.isLeader(leader.getUniqueId())) return Result.NOT_LEADER;
        if (!party.contains(target)) return Result.NOT_MEMBER;
        party.leader(target);
        return Result.OK;
    }

    /** Leaders whose invitation to {@code player} is still valid. */
    public java.util.Set<UUID> invitesOf(UUID player) {
        Map<UUID, Long> mine = invites.get(player);
        if (mine == null) return java.util.Set.of();
        long now = System.currentTimeMillis();
        mine.values().removeIf(expiry -> expiry < now);
        return java.util.Set.copyOf(mine.keySet());
    }

    public boolean sameParty(UUID a, UUID b) {
        Party p = byMember.get(a);
        return p != null && p.contains(b);
    }
}

package fr.influencraft.fluxfamily;

import fr.influencraft.fluxfamily.api.Family;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The family tree, read from FluxFamily's adoptions and the lineages of other plugins (referral). A player has one
 * parent at most: his adoption first, else the first lineage that knows him. A married couple shares its children:
 * the children of either spouse are the couple's.
 */
final class Tree {

    /** Generations counted in the size of a family (a loop in the data would never end otherwise). */
    static final int DEPTH = 10;

    private final FamilyStore store;

    Tree(FamilyStore store) {
        this.store = store;
    }

    UUID parent(UUID child) {
        FamilyStore.Adoption a = store.adoption(child);
        if (a != null) return a.parent();
        for (Family.Lineage l : Family.lineages().values()) {
            UUID p = l.parent(child);
            if (p != null && !p.equals(child)) return p;
        }
        return null;
    }

    /** Whether the parent link of this child is an adoption (the only kind that can be undone). */
    boolean adopted(UUID child) {
        return store.adoption(child) != null;
    }

    /** Children of one player (not of his spouse). */
    List<UUID> children(UUID parent) {
        Set<UUID> out = new LinkedHashSet<>(store.adopted(parent));
        for (Family.Lineage l : Family.lineages().values()) out.addAll(l.children(parent));
        // a child counts under his one effective parent only
        out.removeIf(c -> !parent.equals(parent(c)));
        return new ArrayList<>(out);
    }

    UUID spouse(UUID player) {
        FamilyStore.Couple c = store.couple(player);
        return c != null && c.married() ? c.other(player) : null;
    }

    /** Children of the player and, when married, of his spouse. */
    List<UUID> familyChildren(UUID player) {
        Set<UUID> out = new LinkedHashSet<>(children(player));
        UUID spouse = spouse(player);
        if (spouse != null) out.addAll(children(spouse));
        return new ArrayList<>(out);
    }

    /** Parent of the player, then the parent's spouse (empty when none). */
    List<UUID> parents(UUID child) {
        List<UUID> out = new ArrayList<>();
        UUID p = parent(child);
        if (p == null) return out;
        out.add(p);
        UUID s = spouse(p);
        if (s != null) out.add(s);
        return out;
    }

    /** Whether {@code ancestor} is above {@code player} in the tree (spouses of ancestors included). */
    boolean descendsFrom(UUID player, UUID ancestor) {
        Set<UUID> seen = new HashSet<>();
        UUID current = player;
        for (int i = 0; i < DEPTH * 3 && current != null && seen.add(current); i++) {
            for (UUID p : parents(current)) if (p.equals(ancestor)) return true;
            current = parent(current);
        }
        return false;
    }

    /** Everyone below the player (and his spouse when married), over {@link #DEPTH} generations. */
    int size(UUID player) {
        Set<UUID> seen = new HashSet<>();
        seen.add(player);
        UUID spouse = spouse(player);
        if (spouse != null) seen.add(spouse);
        Deque<UUID> level = new ArrayDeque<>(seen);
        int count = 0;
        for (int g = 0; g < DEPTH && !level.isEmpty(); g++) {
            Deque<UUID> next = new ArrayDeque<>();
            for (UUID p : level) {
                for (UUID c : children(p)) {
                    if (seen.add(c)) {
                        count++;
                        next.add(c);
                        UUID s = spouse(c);
                        if (s != null && seen.add(s)) next.add(s);   // a child's spouse joins the family, not counted
                    }
                }
            }
            level = next;
        }
        return count;
    }
}

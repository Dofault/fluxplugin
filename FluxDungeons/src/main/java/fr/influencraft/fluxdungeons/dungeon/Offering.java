package fr.influencraft.fluxdungeons.dungeon;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What an offering door asks for (2026-11-11, herbalist dungeon): players bring items to its altar (a block they
 * right-click), one kind after the other when {@code ordered}. Each entry is shown by its {@code hint} (a riddle or a
 * recipe line: the players must recognise the plant) until it is complete. Once everything is in, the door opens and,
 * when {@code grant} is set, every player of the run receives that scoreboard tag for the rest of the run (an
 * advantage another part of the dungeon reads: the herbalist tisane against the fox's roots).
 * <p>
 * Pure: no Bukkit world access, the progress is an array of counts kept by the run (tested by OfferingTest).
 */
public record Offering(int ax, int ay, int az, List<Entry> entries, boolean ordered, String title, String grant,
                       String grantMessage) {

    /** One kind of item and how many of it, shown by its hint. */
    public record Entry(Material item, int amount, String hint) {
    }

    /** What happens to an item put on the altar. */
    public enum Result { ACCEPTED, COMPLETE, WRONG, NOT_YET, ENOUGH }

    public int[] fresh() {
        return new int[entries.size()];
    }

    public boolean complete(int[] progress) {
        for (int i = 0; i < entries.size(); i++) if (progress[i] < entries.get(i).amount()) return false;
        return true;
    }

    /** Index of the entry that takes this item, -1 when none asks for it. */
    public int indexOf(Material m) {
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).item() == m) return i;
        return -1;
    }

    /** How many of this item the altar still takes now (0 when it refuses it). */
    public int wants(int[] progress, Material m) {
        int i = indexOf(m);
        if (i < 0 || progress[i] >= entries.get(i).amount()) return 0;
        if (ordered) for (int k = 0; k < i; k++) if (progress[k] < entries.get(k).amount()) return 0;
        return entries.get(i).amount() - progress[i];
    }

    /**
     * Puts {@code count} of an item on the altar: the progress is updated with what it takes (at most what it still
     * wants). Returns the result and, in {@code taken[0]}, how many were taken.
     */
    public Result offer(int[] progress, Material m, int count, int[] taken) {
        taken[0] = 0;
        int i = indexOf(m);
        if (i < 0) return Result.WRONG;
        if (progress[i] >= entries.get(i).amount()) return Result.ENOUGH;
        int want = wants(progress, m);
        if (want <= 0) return Result.NOT_YET;
        int n = Math.min(want, Math.max(1, count));
        progress[i] += n;
        taken[0] = n;
        return complete(progress) ? Result.COMPLETE : Result.ACCEPTED;
    }

    /** Reads the {@code offering} section of a door (a map from the YAML), null when it has none or is invalid. */
    public static Offering read(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) return null;
        if (!(m.get("altar") instanceof List<?> a) || a.size() < 3) return null;
        List<Entry> entries = new ArrayList<>();
        if (m.get("items") instanceof List<?> items) {
            for (Object o : items) {
                if (!(o instanceof Map<?, ?> it)) continue;
                Material mat = Material.matchMaterial(String.valueOf(it.get("item")));
                if (mat == null) continue;
                int amount = it.get("amount") instanceof Number n ? Math.max(1, n.intValue()) : 1;
                entries.add(new Entry(mat, amount, it.get("hint") == null ? "" : String.valueOf(it.get("hint"))));
            }
        }
        if (entries.isEmpty()) return null;
        return new Offering(num(a.get(0)), num(a.get(1)), num(a.get(2)), List.copyOf(entries), Boolean.TRUE.equals(m.get("ordered")),
                str(m.get("title")), str(m.get("grant")), str(m.get("grant-message")));
    }

    /** The YAML form (what {@link #read} reads back). */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("altar", List.of(ax, ay, az));
        out.put("ordered", ordered);
        if (!title.isEmpty()) out.put("title", title);
        if (!grant.isEmpty()) out.put("grant", grant);
        if (!grantMessage.isEmpty()) out.put("grant-message", grantMessage);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Entry e : entries) {
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("item", e.item().name().toLowerCase(Locale.ROOT));
            it.put("amount", e.amount());
            it.put("hint", e.hint());
            items.add(it);
        }
        out.put("items", items);
        return out;
    }

    private static int num(Object o) {
        return o instanceof Number n ? (int) Math.floor(n.doubleValue()) : 0;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}

package fr.influencraft.fluxvalue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Value of every item: the cheapest way to get it, harvested or crafted. Starting from the items that have a source,
 * recipes are relaxed until nothing gets cheaper. Taking the cheapest way is what makes the economy safe: an item can
 * never be worth more than what it is made of, so no craft turns cheap ingredients into a more expensive item, and a
 * value set on a block reaches everything made from it. No Bukkit here, to be testable on its own.
 */
public final class ValueGraph {

    /** One ingredient slot: any of {@code choices}, {@code amount} times. */
    public record Slot(List<String> choices, int amount) {
    }

    /** A recipe: {@code cost} points of work (a smelt pays its job) on top of the ingredients. */
    public record Recipe(String id, String output, int count, List<Slot> slots, double cost) {
    }

    /** Where a value comes from: a job or group for a source, a recipe id when crafted. */
    public record Entry(double points, String source, Recipe recipe) {
    }

    private final Map<String, Entry> entries = new HashMap<>();
    private final List<Recipe> recipes = new ArrayList<>();

    /** Declares a way to get {@code item}; the cheapest one wins. */
    public void source(String item, double points, String source) {
        Entry known = entries.get(item);
        if (points > 0 && (known == null || points < known.points())) entries.put(item, new Entry(points, source, null));
    }

    public void recipes(List<Recipe> all) {
        recipes.addAll(all);
    }

    public void solve() {
        boolean changed = true;
        // each pass settles at least one more level of crafts; the bound only guards against a broken recipe set
        for (int pass = 0; changed && pass < 500; pass++) {
            changed = false;
            for (Recipe r : recipes) {
                double total = r.cost();
                boolean known = true;
                for (Slot slot : r.slots()) {
                    double cheapest = Double.MAX_VALUE;
                    for (String c : slot.choices()) {
                        Entry e = entries.get(c);
                        if (e != null && e.points() < cheapest) cheapest = e.points();
                    }
                    if (cheapest == Double.MAX_VALUE) {
                        known = false;
                        break;
                    }
                    total += cheapest * slot.amount();
                }
                if (!known) continue;
                double unit = total / r.count();
                Entry e = entries.get(r.output());
                if (e == null || unit < e.points() - 1e-9) {
                    entries.put(r.output(), new Entry(unit, null, r));
                    changed = true;
                }
            }
        }
    }

    public Entry get(String item) {
        return entries.get(item);
    }

    public Map<String, Entry> all() {
        return entries;
    }

    /** Job or group behind an item: the source of the costliest ingredient, down the chain of its recipe. */
    public String origin(String item, double smeltOver) {
        return origin(item, smeltOver, 0);
    }

    private String origin(String item, double smeltCost, int depth) {
        Entry e = entries.get(item);
        if (e == null || depth > 16) return "divers";
        if (e.recipe() == null) return e.source();
        String best = null;
        double bestPoints = -1;
        for (Slot slot : e.recipe().slots()) {
            String cheapest = null;
            double points = Double.MAX_VALUE;
            for (String c : slot.choices()) {
                Entry x = entries.get(c);
                if (x != null && x.points() < points) {
                    points = x.points();
                    cheapest = c;
                }
            }
            if (cheapest != null && points * slot.amount() > bestPoints) {
                bestPoints = points * slot.amount();
                best = cheapest;
            }
        }
        if (e.recipe().cost() > bestPoints && e.recipe().cost() == smeltCost) return "fondeur";
        return best == null ? "divers" : origin(best, smeltCost, depth + 1);
    }
}

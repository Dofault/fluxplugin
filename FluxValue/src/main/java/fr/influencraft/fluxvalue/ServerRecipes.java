package fr.influencraft.fluxvalue;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.inventory.StonecuttingRecipe;
import org.bukkit.inventory.TransmuteRecipe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The recipes the server really has, so a recipe added or removed needs nothing in the config. Recipes of custom items
 * (a result with data, an exact ingredient) are left out: those items get a value from the plugin that owns them.
 */
final class ServerRecipes {

    private ServerRecipes() {
    }

    static List<ValueGraph.Recipe> read() {
        List<ValueGraph.Recipe> out = new ArrayList<>();
        Iterator<Recipe> it = Bukkit.recipeIterator();
        int index = 0;
        while (it.hasNext()) {
            Recipe recipe = it.next();
            index++;
            ItemStack result = recipe.getResult();
            if (result.getType().isAir() || result.hasItemMeta()) continue;
            List<ValueGraph.Slot> slots = new ArrayList<>();
            boolean cooked = false;
            boolean ok;
            if (recipe instanceof ShapedRecipe r) {
                ok = shaped(r, slots);
            } else if (recipe instanceof ShapelessRecipe r) {
                ok = true;
                for (RecipeChoice c : r.getChoiceList()) ok &= add(c, 1, slots);
            } else if (recipe instanceof CookingRecipe<?> r) {
                ok = add(r.getInputChoice(), 1, slots);
                cooked = true;
            } else if (recipe instanceof StonecuttingRecipe r) {
                ok = add(r.getInputChoice(), 1, slots);
            } else if (recipe instanceof SmithingTransformRecipe r) {
                ok = add(r.getBase(), 1, slots) && add(r.getAddition(), 1, slots) && add(r.getTemplate(), 1, slots);
            } else if (recipe instanceof TransmuteRecipe r) {
                ok = add(r.getInput(), 1, slots) && add(r.getMaterial(), 1, slots);
            } else {
                continue;
            }
            if (ok && !slots.isEmpty()) {
                out.add(new ValueGraph.Recipe("recipe_" + index, result.getType().name(), result.getAmount(), slots, 0, cooked));
            }
        }
        return out;
    }

    private static boolean shaped(ShapedRecipe r, List<ValueGraph.Slot> slots) {
        Map<Character, Integer> counts = new HashMap<>();
        for (String row : r.getShape()) {
            for (char c : row.toCharArray()) counts.merge(c, 1, Integer::sum);
        }
        boolean ok = true;
        for (Map.Entry<Character, RecipeChoice> e : r.getChoiceMap().entrySet()) {
            if (e.getValue() != null) ok &= add(e.getValue(), counts.getOrDefault(e.getKey(), 0), slots);
        }
        return ok;
    }

    private static boolean add(RecipeChoice choice, int amount, List<ValueGraph.Slot> slots) {
        if (choice == null) return true;
        if (!(choice instanceof RecipeChoice.MaterialChoice mc)) return false;
        List<String> names = new ArrayList<>();
        for (Material m : mc.getChoices()) names.add(m.name());
        slots.add(new ValueGraph.Slot(names, amount));
        return true;
    }
}

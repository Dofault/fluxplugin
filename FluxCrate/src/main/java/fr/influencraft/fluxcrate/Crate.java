package fr.influencraft.fluxcrate;

import fr.influencraft.flux.api.items.CustomItems;
import fr.influencraft.flux.api.text.Texts;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A crate of {@code crates/<id>.yml}: its key (an item carrying {@code fluxcrate:key = <id>}), what is written above
 * it once placed, and its rewards, drawn by weight.
 */
public record Crate(String id, String name, String keyItem, String keyName, List<String> keyLore,
                    List<String> hologram, String particle, List<Reward> rewards) {

    /**
     * A reward of a crate.
     *
     * @param weight   chance = weight / sum of the crate's weights
     * @param display  item shown in the menus ({@code Items.of} spec), {@code lore} under its name, {@code modelData}
     *                 its custom model data (0 = none), {@code glow} the enchantment shine
     * @param commands console commands run when won ({@code %player%} = player name)
     * @param items    items given when won ({@code <spec>} or {@code <spec> <amount>})
     * @param item     id of the item this reward is ({@code fluxarmor:virale_helmet}), null for none: given when won,
     *                 and without {@code display} shown as it is, with its own name and description
     * @param amount   how many of {@code item}
     */
    public record Reward(String id, String name, double weight, String display, List<String> lore, int modelData,
                         boolean glow, List<String> commands, List<String> items, String item, int amount) {

        /** Name of the reward in a text: its own, else the name of its item. */
        public String shownName() {
            if (name != null) return name;
            ItemStack stack = item == null ? null : CustomItems.item(item);
            if (stack == null) return id;
            return Texts.legacy(stack.effectiveName());
        }
    }

    static Crate read(String id, ConfigurationSection c) {
        List<Reward> rewards = new ArrayList<>();
        ConfigurationSection rs = c.getConfigurationSection("rewards");
        if (rs != null) {
            for (String rid : rs.getKeys(false)) {
                ConfigurationSection r = rs.getConfigurationSection(rid);
                if (r == null) continue;
                rewards.add(new Reward(rid, r.getString("name"), Math.max(0, r.getDouble("weight", 1)),
                        r.getString("display.item"), r.getStringList("display.lore"),
                        r.getInt("display.model-data", 0), r.getBoolean("display.glow", false),
                        r.getStringList("commands"), r.getStringList("items"), r.getString("item"), Math.max(1, r.getInt("amount", 1))));
            }
        }
        return new Crate(id, c.getString("name", id), c.getString("key.item", "TRIPWIRE_HOOK"),
                c.getString("key.name", id), c.getStringList("key.lore"), c.getStringList("hologram"),
                c.getString("particle", "#FFFFFF"), List.copyOf(rewards));
    }

    double totalWeight() {
        double t = 0;
        for (Reward r : rewards) t += r.weight();
        return t;
    }

    /** Chance of a reward, in percent. */
    double chance(Reward r) {
        double t = totalWeight();
        return t <= 0 ? 0 : r.weight() * 100 / t;
    }

    /** A reward drawn by weight, null when the crate has none. */
    Reward draw() {
        double t = totalWeight();
        if (rewards.isEmpty() || t <= 0) return null;
        double x = ThreadLocalRandom.current().nextDouble(t);
        for (Reward r : rewards) {
            x -= r.weight();
            if (x < 0) return r;
        }
        return rewards.get(rewards.size() - 1);
    }
}

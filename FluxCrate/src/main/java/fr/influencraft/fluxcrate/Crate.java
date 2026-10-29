package fr.influencraft.fluxcrate;

import org.bukkit.configuration.ConfigurationSection;

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
     * @param display  item shown in the menus ({@code Items.of} spec), {@code lore} under its name
     * @param commands console commands run when won ({@code %player%} = player name)
     * @param items    items given when won ({@code <spec>} or {@code <spec> <amount>})
     */
    public record Reward(String id, String name, double weight, String display, List<String> lore,
                         List<String> commands, List<String> items) {
    }

    static Crate read(String id, ConfigurationSection c) {
        List<Reward> rewards = new ArrayList<>();
        ConfigurationSection rs = c.getConfigurationSection("rewards");
        if (rs != null) {
            for (String rid : rs.getKeys(false)) {
                ConfigurationSection r = rs.getConfigurationSection(rid);
                if (r == null) continue;
                rewards.add(new Reward(rid, r.getString("name", rid), Math.max(0, r.getDouble("weight", 1)),
                        r.getString("display.item", "PAPER"), r.getStringList("display.lore"),
                        r.getStringList("commands"), r.getStringList("items")));
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

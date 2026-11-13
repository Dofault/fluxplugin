package fr.influencraft.fluxcrate;

import fr.influencraft.flux.api.items.CustomItems;
import fr.influencraft.flux.api.text.Texts;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * A crate of {@code crates/<id>.yml}: its key (an item carrying {@code fluxcrate:key = <id>}), what is written above
 * it once placed, the colours of its ring of particles, its effects and its rewards, drawn by weight.
 */
public record Crate(String id, String name, String keyItem, String keyName, List<String> keyLore,
                    List<String> hologram, List<Color> ring, Effects effects, List<Reward> rewards) {

    /**
     * Optional effects of a placed crate ({@code effects} section), none by default.
     *
     * @param model      item shown on the crate's block ({@code Items.of} spec), floating and slowly turning; it hops
     *                   when a key is used. Null: nothing shown (the block itself, or a furniture, is the crate)
     * @param scale      size of the model
     * @param ambient    particle drifting around the crate every second, null for none
     * @param burst      particle bursting out of the crate when a key is used, null for none
     * @param burstSound sound played there at that moment ({@code "<key> [volume] [pitch]"}), null for none
     * @param tune       tune played to the winner at the end of the roll instead of the usual sound, null for none
     * @param chest      the model is a chest that opens ({@code effects.chest}), null for a floating model
     */
    public record Effects(String model, float scale, Particle ambient, Particle burst, String burstSound, Tune tune,
                          Chest chest) {

        static final Effects NONE = new Effects(null, 1f, null, null, null, null, null);

        static Effects read(String crate, ConfigurationSection c, Logger log) {
            if (c == null) return NONE;
            String model = c.getString("model");
            ConfigurationSection chest = c.getConfigurationSection("chest");
            if (chest != null && model == null) log.warning("Crate " + crate + ": effects.chest needs effects.model, ignored.");
            return new Effects(model, (float) Math.max(0.1, c.getDouble("scale", 1)),
                    particle(crate, c.getString("ambient"), log), particle(crate, c.getString("burst"), log),
                    c.getString("burst-sound"), Tune.parse(crate, c.getString("tune-sound"), c.getString("tune"), log),
                    chest == null || model == null ? null : Chest.read(chest));
        }

        /** A particle without data (SNOWFLAKE, END_ROD...), null when empty or not usable. */
        private static Particle particle(String crate, String name, Logger log) {
            if (name == null || name.isBlank()) return null;
            try {
                Particle p = Particle.valueOf(name.trim().toUpperCase(Locale.ROOT));
                if (p.getDataType() == Void.class) return p;
            } catch (IllegalArgumentException ignored) {
                // logged below
            }
            log.warning("Crate " + crate + ": particle " + name + " unknown or needs data, ignored.");
            return null;
        }
    }

    /**
     * A crate shown as a chest that opens ({@code effects.chest}): {@code effects.model} is its body, the other parts
     * are item models drawn in the same space (16 pixels = 1 block, front to the north), shown on top of it. Opening
     * with a key plays in the world instead of the roll menu: the lock lights up, the ornament spins, the lid opens on
     * its hinge, the reward rises out of it and is given, then the lid closes. See {@link ChestScene}.
     *
     * @param lid       the lid, turning on its hinge
     * @param hingeY    height of the hinge, in pixels of the model
     * @param hingeZ    depth of the hinge (toward the back, south), in pixels
     * @param openAngle how far the lid opens, in degrees
     * @param ornament  turns on the vertical axis through the centre of the model, carried by the lid (null: none)
     * @param keyhole   lights up when a key is used (null: none)
     * @param light     light of the inside and of the seam, on while the chest opens (null: none)
     * @param beam      beam rising from the open chest, stretched up from the height of 8 pixels (null: none)
     * @param sounds    sounds of each moment ({@code activate}, {@code awaken}, {@code rattle}, {@code open},
     *                  {@code reveal}, {@code close}), each a list of {@code "<key> [volume] [pitch]"}
     */
    public record Chest(String lid, float hingeY, float hingeZ, float openAngle, String ornament, String keyhole,
                        String light, String beam, Map<String, List<String>> sounds) {

        private static final Map<String, List<String>> DEFAULT_SOUNDS = Map.of(
                "activate", List.of("minecraft:block.vault.insert_item 1 1", "minecraft:block.note_block.chime 0.6 1.6"),
                "awaken", List.of("minecraft:block.vault.activate 0.9 1.1", "minecraft:block.amethyst_block.resonate 0.8 1.2"),
                "rattle", List.of("minecraft:block.chest.locked 0.5 1.4"),
                "open", List.of("minecraft:block.chest.open 0.8 0.9", "minecraft:block.vault.open_shutter 0.9 1.1"),
                "reveal", List.of("minecraft:block.amethyst_block.chime 1 1.4", "minecraft:block.note_block.chime 0.7 2"),
                "close", List.of("minecraft:block.chest.close 0.7 1", "minecraft:block.powder_snow.fall 0.6 1.2"));

        static Chest read(ConfigurationSection c) {
            List<Double> hinge = c.getDoubleList("hinge");
            Map<String, List<String>> sounds = new LinkedHashMap<>(DEFAULT_SOUNDS);
            ConfigurationSection s = c.getConfigurationSection("sounds");
            if (s != null) for (String k : s.getKeys(false)) sounds.put(k, s.isList(k) ? s.getStringList(k) : List.of(s.getString(k, "")));
            return new Chest(c.getString("lid"), hinge.size() > 0 ? hinge.get(0).floatValue() : 8f,
                    hinge.size() > 1 ? hinge.get(1).floatValue() : 16f, (float) c.getDouble("open-angle", 105),
                    c.getString("ornament"), c.getString("keyhole"), c.getString("light"), c.getString("beam"),
                    Map.copyOf(sounds));
        }

        List<String> sounds(String moment) {
            return sounds.getOrDefault(moment, List.of());
        }
    }

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

    static Crate read(String id, ConfigurationSection c, Logger log) {
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
        if (rewards.isEmpty()) log.warning("Crate " + id + " has no reward.");
        // particle: one colour or a list; an empty list (particle: []) means no ring
        List<String> colours = c.isList("particle") ? c.getStringList("particle") : List.of(c.getString("particle", "#FFFFFF"));
        List<Color> ring = new ArrayList<>();
        for (String colour : colours) {
            try {
                ring.add(Color.fromRGB(Integer.parseInt(colour.trim().replace("#", ""), 16)));
            } catch (IllegalArgumentException e) {
                log.warning("Crate " + id + ": particle colour " + colour + " is not #RRGGBB, ignored.");
            }
        }
        if (ring.isEmpty() && !colours.isEmpty()) ring.add(Color.WHITE);
        return new Crate(id, c.getString("name", id), c.getString("key.item", "TRIPWIRE_HOOK"),
                c.getString("key.name", id), c.getStringList("key.lore"), c.getStringList("hologram"),
                List.copyOf(ring), Effects.read(id, c.getConfigurationSection("effects"), log), List.copyOf(rewards));
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

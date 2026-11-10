package fr.influencraft.fluxarmor;

import fr.influencraft.flux.api.effects.Effects;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * An armour set (one file {@code sets/<id>.yml}): its pieces, known to the suite as {@code fluxarmor:<set>_<piece>}, the
 * bonus of the full set and of part of it, the sounds of the full set, the optional 3D model.
 */
final class ArmorSet {

    /** A sound of the set; {@code sound} is a Minecraft sound key. */
    record SetSound(String sound, float volume, float pitch) {

        static SetSound read(ConfigurationSection c) {
            return c == null ? null : new SetSound(c.getString("sound", ""), (float) c.getDouble("volume", 1), (float) c.getDouble("pitch", 1));
        }
    }

    final String id;
    final ConfigurationSection config;
    /** Piece name ({@code helmet}...) in the file's order. */
    final Map<String, ConfigurationSection> pieces = new LinkedHashMap<>();
    /**
     * Other items handed out with the set by {@code /set} ({@code extras.<id>}, same format as a piece: a weapon...),
     * known as {@code fluxarmor:<set>_<id>}; never counted as pieces of the set.
     */
    final Map<String, ConfigurationSection> extras = new LinkedHashMap<>();
    /** Ids of other items wearing the 3D armour (patterns of {@code CustomItems.matches}). */
    final List<String> wornBy = new ArrayList<>();
    final SetSound equipSound;
    final SetSound unequipSound;
    final boolean partialStacked;
    final boolean partialDisabledByFull;

    Effects.Bundle fullBonus;
    final Map<String, Effects.Bundle> pieceBonus = new LinkedHashMap<>();
    /** Pieces worn -> bonus. */
    final TreeMap<Integer, Effects.Bundle> partialBonus = new TreeMap<>();

    ArmorSet(String id, ConfigurationSection c) {
        this.id = id;
        config = c;
        ConfigurationSection p = c.getConfigurationSection("pieces");
        if (p != null) for (String piece : p.getKeys(false)) if (p.isConfigurationSection(piece)) pieces.put(piece, p.getConfigurationSection(piece));
        ConfigurationSection x = c.getConfigurationSection("extras");
        if (x != null) for (String extra : x.getKeys(false)) if (x.isConfigurationSection(extra)) extras.put(extra, x.getConfigurationSection(extra));
        wornBy.add("fluxarmor:" + id + "_*");
        wornBy.addAll(c.getStringList("worn-by"));
        equipSound = SetSound.read(c.getConfigurationSection("sounds.equip"));
        unequipSound = SetSound.read(c.getConfigurationSection("sounds.unequip"));
        partialStacked = c.getBoolean("partial.stacked", false);
        partialDisabledByFull = c.getBoolean("partial.disabled-by-full", false);
    }

    /** Pieces needed for the full set: an elytra stands in for a chestplate, it does not add to the count. */
    int fullCount() {
        return (int) pieces.keySet().stream().filter(p -> !p.equals("elytra")).count();
    }

    /** Compiles the bonuses (once the effect engine is ready). */
    void compile(FluxArmor plugin) {
        fullBonus = Effects.compile(plugin, id + "_full", config.getMapList("effects"), config.getMapList("conditions"));
        pieceBonus.clear();
        pieces.forEach((piece, c) -> pieceBonus.put(piece,
                Effects.compile(plugin, id + "_" + piece, c.getMapList("effects"), c.getMapList("conditions"))));
        partialBonus.clear();
        ConfigurationSection amounts = config.getConfigurationSection("partial.amounts");
        if (amounts != null) for (String n : amounts.getKeys(false)) {
            partialBonus.put(Integer.parseInt(n), Effects.compile(plugin, id + "_partial_" + n,
                    amounts.getMapList(n + ".effects"), amounts.getMapList(n + ".conditions")));
        }
    }

    /** The bonuses of {@code worn} pieces of this set. */
    List<Effects.Bundle> bonuses(List<String> worn) {
        List<Effects.Bundle> out = new ArrayList<>();
        for (String piece : worn) if (pieceBonus.containsKey(piece)) out.add(pieceBonus.get(piece));
        boolean full = worn.size() >= fullCount();
        if (full && fullBonus != null) out.add(fullBonus);
        if (full && partialDisabledByFull) return out;
        if (partialStacked) out.addAll(partialBonus.headMap(worn.size(), true).values());
        else if (partialBonus.floorEntry(worn.size()) != null) out.add(partialBonus.floorEntry(worn.size()).getValue());
        return out;
    }
}

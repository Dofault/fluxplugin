package fr.influencraft.tri;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Objet "Coffre de tri" et marquage du bloc posé.
 * L'objet porte une marque PDC ; une fois posé, la marque est recopiée
 * sur le bloc (PDC de l'entité de bloc), et inversement à la casse.
 */
public final class SorterItem {

    public static final Material MATERIAL = Material.WAXED_OXIDIZED_COPPER_CHEST;

    private final InfluTri plugin;
    private final NamespacedKey key;
    /** Niveau (entier), jamais affiché : stocké dans l'objet et dans le bloc posé. */
    private final NamespacedKey levelKey;

    public SorterItem(InfluTri plugin) {
        this.plugin = plugin;
        this.key = InfluTri.key("coffre_de_tri");
        this.levelKey = InfluTri.key("niveau");
    }

    public NamespacedKey key() { return key; }

    public ItemStack create(int amount) {
        return create(amount, 1);
    }

    public ItemStack create(int amount, int level) {
        level = plugin.levels().normalize(level);
        ItemStack item = new ItemStack(MATERIAL, amount);
        ItemMeta meta = item.getItemMeta();
        String rayon = String.valueOf(plugin.levels().radius(level));
        meta.displayName(InfluTri.mm(plugin.conf().getString("item.name", "Sorting chest")));
        List<Component> lore = new ArrayList<>();
        for (String line : plugin.conf().getStringList("item.lore")) {
            lore.add(InfluTri.mm(line, Placeholder.unparsed("radius", rayon), Placeholder.unparsed("rayon", rayon)));
        }
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(levelKey, PersistentDataType.INTEGER, level);
        item.setItemMeta(meta);
        return item;
    }

    /** Niveau d'un objet coffre de tri (1 si absent : objets d'avant les niveaux, ou nom seul). */
    public int levelOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 1;
        Integer level = item.getItemMeta().getPersistentDataContainer().get(levelKey, PersistentDataType.INTEGER);
        return level == null ? 1 : level;
    }

    /** Niveau d'un coffre de tri posé (1 si absent). */
    public int levelOf(BlockState state) {
        if (!(state instanceof TileState tile)) return 1;
        Integer level = tile.getPersistentDataContainer().get(levelKey, PersistentDataType.INTEGER);
        return level == null ? 1 : level;
    }

    public int levelOf(Block block) {
        return levelOf(block.getState(false));
    }

    /** Nom réservé à l'objet, comparé sans couleurs, casse ni espaces. */
    public boolean isReservedName(String name) {
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(InfluTri.mm(plugin.conf().getString("item.name", "Sorting chest")));
        return normalize(name).equals(normalize(plain));
    }

    private static String normalize(String s) {
        return s.replaceAll("[&§][0-9a-fk-orA-FK-OR#]", "").replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Coffre de tri fonctionnel : marque PDC, ou à défaut le nom coloré exact de l'objet.
     * À la casse, vanilla ne recopie du bloc vers l'objet que le nom (pas le PDC) : si la
     * conversion du drop échoue, le coffre récupéré doit rester un coffre de tri une fois reposé.
     * Une enclume ne produit que du texte sans couleur, elle ne peut pas imiter ce nom.
     */
    public boolean isSorterItem(ItemStack item) {
        if (item == null || item.getType() != MATERIAL || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta.getPersistentDataContainer().has(key, PersistentDataType.BYTE) || hasSorterName(meta);
    }

    /** Objet complet (marque PDC, description) : faux pour un coffre qui n'a gardé que le nom. */
    public boolean hasItemMark(ItemStack item) {
        return item != null && item.getType() == MATERIAL && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    private boolean hasSorterName(ItemMeta meta) {
        if (!meta.hasDisplayName()) return false;
        Component expected = InfluTri.mm(plugin.conf().getString("item.name", "Sorting chest")).compact();
        return expected.equals(meta.displayName().compact());
    }

    public boolean isSorterBlock(Block block) {
        if (block.getType() != MATERIAL) return false;
        BlockState state = block.getState(false);
        return state instanceof TileState tile
                && tile.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    /** Marque présente, quelle que soit la variante de coffre en cuivre. */
    public boolean hasMark(BlockState state) {
        return state instanceof TileState tile
                && tile.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    public boolean isSorterState(BlockState state) {
        return state.getType() == MATERIAL && state instanceof TileState tile
                && tile.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    /**
     * Rend sa variante (oxydé ciré, simple) à un coffre de tri que vanilla a transformé en une
     * autre variante de coffre en cuivre, s'il porte encore la marque. Renvoie vrai si réparé.
     */
    public boolean repair(Block block) {
        if (!SorterListener.isCopperChest(block.getType())) return false;
        if (!(block.getState(false) instanceof TileState tile)
                || !tile.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) return false;
        if (!(block.getBlockData() instanceof org.bukkit.block.data.type.Chest data)) return false;
        org.bukkit.block.data.type.Chest fixed = (org.bukkit.block.data.type.Chest) MATERIAL.createBlockData();
        fixed.setFacing(data.getFacing());
        fixed.setWaterlogged(data.isWaterlogged());
        fixed.setType(org.bukkit.block.data.type.Chest.Type.SINGLE);
        block.setBlockData(fixed, false);
        if (!isSorterBlock(block)) markBlock(block);
        return true;
    }

    /** Marque le bloc en gardant son niveau s'il en a déjà un (réparation). */
    public void markBlock(Block block) {
        markBlock(block, levelOf(block));
    }

    public void markBlock(Block block, int level) {
        BlockState state = block.getState();
        if (state instanceof TileState tile) {
            tile.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
            tile.getPersistentDataContainer().set(levelKey, PersistentDataType.INTEGER, level);
            tile.update(true, false);
        }
    }
}

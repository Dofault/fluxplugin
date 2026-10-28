package fr.influencraft.flux.adapters.itemsadder;

import dev.lone.itemsadder.api.CustomBlock;
import dev.lone.itemsadder.api.CustomStack;
import dev.lone.itemsadder.api.FontImages.FontImageWrapper;
import dev.lone.itemsadder.api.ItemsAdder;
import fr.influencraft.flux.api.items.CustomItems;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** ItemsAdder behind {@link CustomItems}: the only class of the suite that talks to ItemsAdder. */
public final class ItemsAdderItems implements CustomItems.Provider, Listener {

    @EventHandler
    public void onLoad(dev.lone.itemsadder.api.Events.ItemsAdderLoadDataEvent event) {
        synchronized (this) {
            widths = null; // glyphs may have changed
        }
        CustomItems.fireLoaded();
    }

    @Override
    public String name() {
        return "ItemsAdder";
    }

    @Override
    public boolean loaded() {
        try {
            return ItemsAdder.areItemsLoaded();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    @Override
    public String id(ItemStack item) {
        CustomStack s = CustomStack.byItemStack(item);
        return s == null ? null : s.getNamespacedID();
    }

    @Override
    public ItemStack item(String id) {
        CustomStack s = CustomStack.getInstance(id);
        return s == null ? null : s.getItemStack().clone();
    }

    @Override
    public boolean exists(String id) {
        return CustomStack.isInRegistry(id);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Collection<String> ids() {
        List<String> out = new ArrayList<>();
        for (Object o : (Iterable<Object>) CustomStack.getNamespacedIdsInRegistry()) out.add(String.valueOf(o));
        return out;
    }

    @Override
    public String glyphs(String text) {
        return FontImageWrapper.replaceFontImages(text);
    }

    @Override
    public boolean glyphExists(String id) {
        return new FontImageWrapper(id).exists();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Collection<String> glyphIds() {
        List<String> out = new ArrayList<>();
        for (Object o : (Iterable<Object>) FontImageWrapper.getNamespacedIdsInRegistry()) out.add(String.valueOf(o));
        return out;
    }

    @Override
    public int glyphWidth(String id) {
        FontImageWrapper w = new FontImageWrapper(id);
        return w.exists() ? w.getWidth() : 0;
    }

    @Override
    public String blockId(Block block) {
        CustomBlock b = CustomBlock.byAlreadyPlaced(block);
        return b == null ? null : b.getNamespacedID();
    }

    @Override
    public boolean placeBlock(String id, Location location) {
        return CustomBlock.place(id, location) != null;
    }

    private java.util.Map<Integer, Integer> widths;

    /**
     * Advances of the glyphs (font images), computed like the client does for a bitmap glyph: rightmost visible
     * column of the image, scaled to the glyph height ({@code scale_ratio}), rounded, plus 1. Read from the ItemsAdder
     * sources ({@code contents/<pack>/configs}, textures), the generated pack being protected; recomputed when
     * ItemsAdder reloads its data.
     */
    @Override
    public synchronized java.util.Map<Integer, Integer> charWidths() {
        if (widths != null) return widths;
        org.bukkit.plugin.Plugin ia = org.bukkit.Bukkit.getPluginManager().getPlugin("ItemsAdder");
        if (ia == null || !loaded()) return java.util.Map.of();
        java.util.Map<Integer, Integer> out = new java.util.HashMap<>();
        java.io.File[] packs = new java.io.File(ia.getDataFolder(), "contents").listFiles(java.io.File::isDirectory);
        if (packs != null) for (java.io.File pack : packs) readPack(pack, out);
        widths = out;
        return widths;
    }

    private static void readPack(java.io.File pack, java.util.Map<Integer, Integer> out) {
        java.util.List<java.io.File> files = new java.util.ArrayList<>();
        try (var walk = java.nio.file.Files.walk(pack.toPath())) {
            walk.filter(p -> p.toString().endsWith(".yml")).forEach(p -> files.add(p.toFile()));
        } catch (java.io.IOException e) {
            return;
        }
        for (java.io.File file : files) {
            var yml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
            var images = yml.getConfigurationSection("font_images");
            String ns = yml.getString("info.namespace");
            if (images == null || ns == null) continue;
            for (String id : images.getKeys(false)) {
                String path = images.getString(id + ".path");
                if (path == null) continue;
                FontImageWrapper glyph = new FontImageWrapper(ns + ":" + id);
                if (!glyph.exists()) continue;
                String unicode = glyph.internalGetUnicode();
                if (unicode == null || unicode.isEmpty()) continue;
                java.awt.image.BufferedImage image = texture(pack, ns, path.endsWith(".png") ? path : path + ".png");
                if (image == null) continue;
                int height = images.getInt(id + ".scale_ratio", glyph.getHeight());
                out.put(unicode.codePointAt(0), (int) (0.5f + visibleWidth(image) * (float) height / image.getHeight()) + 1);
            }
        }
    }

    private static java.awt.image.BufferedImage texture(java.io.File pack, String ns, String path) {
        for (java.io.File f : new java.io.File[]{new java.io.File(pack, "textures/" + path),
                new java.io.File(pack, "resourcepack/assets/" + ns + "/textures/" + path),
                new java.io.File(pack, "resourcepack/" + ns + "/textures/" + path)}) {
            if (!f.isFile()) continue;
            try {
                return javax.imageio.ImageIO.read(f);
            } catch (java.io.IOException e) {
                return null;
            }
        }
        return null;
    }

    /** Rightmost column with a visible pixel, plus 1 (0 for an empty image). */
    private static int visibleWidth(java.awt.image.BufferedImage image) {
        for (int x = image.getWidth() - 1; x >= 0; x--) {
            for (int y = 0; y < image.getHeight(); y++) if ((image.getRGB(x, y) >>> 24) != 0) return x + 1;
        }
        return 0;
    }
}

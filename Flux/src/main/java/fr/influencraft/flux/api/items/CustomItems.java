package fr.influencraft.flux.api.items;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom items, blocks and font glyphs of the server, whatever plugin provides them (ItemsAdder today, see
 * {@code providers.items} in {@code plugins/Flux/config.yml}). Plugins of the suite never call that plugin directly:
 * changing it means writing one adapter in Flux ({@link Provider}).
 *
 * <p>Ids are namespaced ({@code influitems:btn_next}); {@link #resolve} also accepts a bare id when it is unique.</p>
 *
 * <p>The plugins of the suite declare their own items here ({@link #register}, e.g. {@code fluxtools:pioche_virale},
 * {@code fluxpets:booster_mineur}), so every plugin recognises them the same way. A source only answers for its own
 * items (marked by its plugin): any other item keeps its usual behaviour.</p>
 */
public final class CustomItems {

    /** What an adapter provides. */
    public interface Provider {
        String name();

        /** True once its data is loaded (items, glyphs). */
        boolean loaded();

        String id(ItemStack item);

        ItemStack item(String id);

        boolean exists(String id);

        Collection<String> ids();

        /** Text with every glyph shortcode ({@code :ns:id:} or {@code :id:}) replaced. */
        String glyphs(String text);

        boolean glyphExists(String id);

        Collection<String> glyphIds();

        /** Width of a glyph in pixels (0 when unknown). */
        int glyphWidth(String id);

        /** The bare character(s) of a glyph ({@code namespace:id}), coloured by the text colour; null when unknown. */
        default String glyphChar(String id) {
            return null;
        }

        /** Id of the custom block placed there, or null. */
        String blockId(Block block);

        boolean placeBlock(String id, Location location);
    }

    private static final Provider NONE = new Provider() {
        public String name() { return "none"; }
        public boolean loaded() { return true; }
        public String id(ItemStack item) { return null; }
        public ItemStack item(String id) { return null; }
        public boolean exists(String id) { return false; }
        public Collection<String> ids() { return List.of(); }
        public String glyphs(String text) { return text; }
        public boolean glyphExists(String id) { return false; }
        public Collection<String> glyphIds() { return List.of(); }
        public int glyphWidth(String id) { return 0; }
        public String blockId(Block block) { return null; }
        public boolean placeBlock(String id, Location location) { return false; }
    };

    /** Items of a plugin of the suite, under its own namespace. */
    public interface Source {
        /** Id (without namespace) of this plugin's item, or null when the item is not one of its own. */
        String id(ItemStack item);

        /** A new stack of the item, or null when unknown. */
        ItemStack item(String id);

        /** Ids (without namespace). */
        Collection<String> ids();
    }

    private record Owned(Plugin owner, Source source) {
    }

    private static final Map<String, Owned> SOURCES = new ConcurrentHashMap<>();
    private static volatile Provider provider = NONE;
    private static final Map<Plugin, Runnable> ON_LOAD = new ConcurrentHashMap<>();

    private CustomItems() {
    }

    /** Called by Flux. */
    public static void use(Provider p) {
        provider = p == null ? NONE : p;
    }

    /** Called by the adapter each time its data is (re)loaded. */
    public static void fireLoaded() {
        ON_LOAD.forEach((owner, task) -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                owner.getLogger().warning("Custom items loaded: " + e);
            }
        });
    }

    /** Runs {@code task} every time the custom items are (re)loaded (main thread). */
    public static void onLoaded(Plugin owner, Runnable task) {
        ON_LOAD.put(owner, task);
    }

    public static void unregisterAll(Plugin owner) {
        ON_LOAD.remove(owner);
        SOURCES.values().removeIf(o -> o.owner() == owner);
    }

    /** Declares the items of a plugin of the suite under {@code namespace}. */
    public static void register(Plugin owner, String namespace, Source source) {
        SOURCES.put(namespace.toLowerCase(java.util.Locale.ROOT), new Owned(owner, source));
    }

    /** True when a custom item plugin is in use. */
    public static boolean available() {
        return provider != NONE;
    }

    public static String providerName() {
        return provider.name();
    }

    public static boolean loaded() {
        return provider.loaded();
    }

    /** Namespaced id of a custom item (suite or custom item plugin), or null for a vanilla item. */
    public static String id(ItemStack item) {
        if (item == null || item.isEmpty()) return null;
        if (item.hasItemMeta()) {
            for (Map.Entry<String, Owned> e : SOURCES.entrySet()) {
                String own;
                try {
                    own = e.getValue().source().id(item);
                } catch (RuntimeException ex) {
                    own = null;
                }
                if (own != null) return e.getKey() + ":" + own;
            }
        }
        return provider.id(item);
    }

    /** A new stack of this custom item, or null when unknown. */
    public static ItemStack item(String id) {
        String full = resolve(id);
        if (full == null) return null;
        Owned own = SOURCES.get(full.substring(0, full.indexOf(':')));
        return own != null ? own.source().item(full.substring(full.indexOf(':') + 1)) : provider.item(full);
    }

    public static boolean exists(String id) {
        String full = resolve(id);
        if (full == null) return false;
        Owned own = SOURCES.get(full.substring(0, full.indexOf(':')));
        return own != null ? own.source().ids().contains(full.substring(full.indexOf(':') + 1)) : provider.exists(full);
    }

    /** Every id: the suite's items, then the custom item plugin's. */
    public static Collection<String> ids() {
        List<String> out = new java.util.ArrayList<>();
        SOURCES.forEach((ns, own) -> own.source().ids().forEach(id -> out.add(ns + ":" + id)));
        out.addAll(provider.ids());
        return out;
    }

    /** {@code ns:id} as it is, else the only {@code *:id} of the registry (the first one when several). */
    public static String resolve(String id) {
        if (id == null || id.isEmpty()) return null;
        if (id.contains(":")) return id;
        String suffix = ":" + id;
        for (String full : ids()) if (full.endsWith(suffix)) return full;
        return null;
    }

    /** Glyphs for this viewer: the Bedrock equivalents ({@code bedrock.glyphs}) when he plays on Bedrock. */
    public static String glyphs(String text, org.bukkit.entity.Player viewer) {
        if (viewer != null && fr.influencraft.flux.api.client.Clients.bedrock(viewer)) {
            return fr.influencraft.flux.api.client.Clients.bedrockGlyphs(text);
        }
        return glyphs(text);
    }

    /** A custom item for this viewer: its vanilla equivalent ({@code bedrock.items}) when he plays on Bedrock. */
    public static ItemStack item(String id, org.bukkit.entity.Player viewer) {
        ItemStack custom = item(id);
        if (custom == null || viewer == null || !fr.influencraft.flux.api.client.Clients.bedrock(viewer)) return custom;
        ItemStack vanilla = new ItemStack(fr.influencraft.flux.api.client.Clients.bedrockItem(id));
        org.bukkit.inventory.meta.ItemMeta from = custom.getItemMeta(), to = vanilla.getItemMeta();
        if (from != null && to != null) {
            if (from.displayName() != null) to.displayName(from.displayName());
            if (from.lore() != null) to.lore(from.lore());
            vanilla.setItemMeta(to);
        }
        return vanilla;
    }

    public static String glyphs(String text) {
        if (text == null || text.indexOf(':') < 0) return text;
        try {
            return provider.glyphs(text);
        } catch (RuntimeException e) {
            return text; // provider still loading
        }
    }

    public static boolean glyphExists(String id) {
        return provider.glyphExists(id);
    }

    public static Collection<String> glyphIds() {
        return provider.glyphIds();
    }

    /** The bare character(s) of a glyph ({@code namespace:id}), to colour it; null when unknown or still loading. */
    public static String glyph(String id) {
        try {
            return provider.glyphChar(id);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static int glyphWidth(String id) {
        if (!id.contains(":")) {
            for (String full : provider.glyphIds()) {
                if (full.endsWith(":" + id)) {
                    id = full;
                    break;
                }
            }
        }
        return provider.glyphWidth(id);
    }

    public static String blockId(Block block) {
        return block == null ? null : provider.blockId(block);
    }

    public static boolean placeBlock(String id, Location location) {
        return provider.placeBlock(id, location);
    }

    /** Same custom item, or same vanilla item when neither is custom. */
    public static boolean same(ItemStack a, ItemStack b) {
        String ia = id(a), ib = id(b);
        if (ia != null || ib != null) return ia != null && ia.equals(ib);
        return a != null && b != null && a.isSimilar(b);
    }
}

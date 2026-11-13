package fr.influencraft.fluxtools;

import fr.influencraft.flux.api.items.CustomItems;
import fr.influencraft.flux.api.items.ItemLooks;
import fr.influencraft.fluxtools.modules.Modules;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * FluxTools: the crate tools, known to the whole suite as {@code fluxtools:<id>}. A levelled tool climbs to its last
 * level with the XP earned by using it, and its enchantments climb with it; all of it is written in the item, shown in
 * its description. Also the utility modules (jobs, repair, bottled XP...).
 */
public final class FluxTools extends fr.influencraft.flux.api.FluxPlugin {

    /** Items {@code fluxtools:book_<enchantment>}: the only books a tool accepts at the anvil. */
    private static final String BOOK_PREFIX = "book_";

    private Map<String, Tool> tools = Map.of();
    private final Set<String> excludedWorlds = new HashSet<>();
    private final ToolRenderer renderer = new ToolRenderer(this);
    private ToolProgress progress;
    private String barGlyph = "";
    private int barSteps = 20;

    @Override
    protected java.util.List<String> formerNames() {
        return java.util.List.of("FluxItems");
    }

    @Override
    protected void enable() {
        // utility modules (ex-InfluBridge) do not need the crate tools, start them first
        new Modules(this).enable();
        loadTools();
        fr.influencraft.fluxtools.api.Tools.bind(this::info, this::rewrite);
        CustomItems.register(this, "fluxtools", new CustomItems.Source() {
            @Override
            public String id(ItemStack item) {
                String id = ToolState.toolId(item);
                return id != null && tools.containsKey(id) ? id : null;
            }

            @Override
            public ItemStack item(String id) {
                if (id.startsWith(BOOK_PREFIX)) return book(id.substring(BOOK_PREFIX.length()));
                Tool tool = tools.get(id);
                return tool == null ? null : renderer.create(tool);
            }

            @Override
            public Collection<String> ids() {
                List<String> ids = new ArrayList<>(tools.keySet());
                for (String book : ToolRenderer.BOOKS) ids.add(BOOK_PREFIX + book);
                return ids;
            }
        });
        // tools already handed out follow the config (description, enchantments), at the moments Flux refreshes items
        ItemLooks.register(this, this::refresh);
        // the bar glyphs may only be known once the custom items are loaded: tools are then written again
        CustomItems.onLoaded(this, () -> {
            ToolRenderer.version = version(toolsFolder());
            ItemLooks.refreshOnline();
        });
        progress = new ToolProgress(this);
        Bukkit.getPluginManager().registerEvents(progress, this);
        Bukkit.getPluginManager().registerEvents(new ToolLocks(this), this);
        ToolCommand tool = new ToolCommand(this);
        command("tools")
                .description("Admin of the crate tools.")
                .permission("fluxtools.admin")
                .suggest(false)
                .executor(tool::run)
                .completer(tool::complete)
                .register();
    }

    private File toolsFolder() {
        return new File(getDataFolder(), "tools");
    }

    void loadTools() {
        reload();
        excludedWorlds.clear();
        for (String world : getConfig().getStringList("excluded-worlds")) excludedWorlds.add(world.toLowerCase(Locale.ROOT));
        barGlyph = getConfig().getString("level-bar.glyph", "fluxafk:bar_{step}");
        barSteps = Math.max(1, getConfig().getInt("level-bar.steps", 20));
        Map<String, Tool> read = new LinkedHashMap<>();
        File folder = toolsFolder();
        if (!folder.isDirectory() && !folder.mkdirs()) getLogger().warning("Cannot create the tools folder.");
        File[] files = folder.listFiles((d, n) -> n.endsWith(".yml") && !n.startsWith("_"));
        if (files != null) {
            Arrays.sort(files);
            for (File f : files) {
                Tool tool = Tool.load(f, getLogger());
                if (tool != null) read.put(tool.id, tool);
            }
        }
        tools = read;
        ToolRenderer.version = version(folder);
        getLogger().info(tools.size() + " tools loaded.");
    }

    /**
     * Fingerprint of the jar, config.yml, the tool files and the bar picture: a tool whose description was written with
     * another one is written again ({@link #refresh}).
     */
    private int version(File folder) {
        int h = Long.hashCode(getFile().lastModified());
        h = 31 * h + (CustomItems.bar(barGlyph, barSteps, 0) == null ? 0 : 1);
        try {
            h = 31 * h + Arrays.hashCode(java.nio.file.Files.readAllBytes(new File(getDataFolder(), "config.yml").toPath()));
            File[] files = folder.listFiles((d, n) -> n.endsWith(".yml"));
            if (files != null) {
                Arrays.sort(files);
                for (File f : files) h = 31 * h + Arrays.hashCode(java.nio.file.Files.readAllBytes(f.toPath()));
            }
        } catch (java.io.IOException e) {
            getLogger().warning("Cannot read the config for its fingerprint: " + e.getMessage());
        }
        return h;
    }

    /** Writes a tool again when its description dates from another config (ItemLooks). */
    private boolean refresh(String id, ItemStack item) {
        if (!id.startsWith("fluxtools:")) return false;
        Tool tool = tools.get(id.substring("fluxtools:".length()));
        if (tool == null || ToolState.renderedVersion(item) == ToolRenderer.version) return false;
        renderer.update(item, tool, ToolState.read(item), true);
        return true;
    }

    /** An enchanted book of one of the enchantments a tool accepts, null when unknown. */
    private static ItemStack book(String enchantment) {
        Enchantment ench = ToolRenderer.BOOKS.contains(enchantment) ? Tool.enchantment(enchantment) : null;
        if (ench == null) return null;
        ItemStack book = ItemStack.of(Material.ENCHANTED_BOOK);
        book.editMeta(EnchantmentStorageMeta.class, m -> m.addStoredEnchant(ench, 1, true));
        return book;
    }

    Map<String, Tool> tools() {
        return Collections.unmodifiableMap(tools);
    }

    /** The tool for other plugins ({@link fr.influencraft.fluxtools.api.Tools#info}). */
    fr.influencraft.fluxtools.api.ToolInfo info(ItemStack item) {
        Tool tool = tool(item);
        return tool == null ? null : info(tool, ToolState.read(item));
    }

    private void rewrite(ItemStack item) {
        Tool tool = tool(item);
        if (tool != null) renderer.update(item, tool, ToolState.read(item), true);
    }

    static fr.influencraft.fluxtools.api.ToolInfo info(Tool tool, ToolState state) {
        int max = tool.hasLevels() ? tool.maxLevel() : 0;
        return new fr.influencraft.fluxtools.api.ToolInfo(tool.id, tool.tier, tool.family, state == null ? 0 : tool.level(state.xp), max,
                max > 0 ? tool.threshold(max) : 0);
    }

    /** The tool carried by the item, or null. */
    Tool tool(ItemStack item) {
        String id = ToolState.toolId(item);
        return id == null ? null : tools.get(id);
    }

    boolean excluded(World world) {
        return excludedWorlds.contains(world.getName().toLowerCase(Locale.ROOT));
    }

    ToolRenderer renderer() {
        return renderer;
    }

    ToolProgress progress() {
        return progress;
    }

    String barGlyph() {
        return barGlyph;
    }

    int barSteps() {
        return barSteps;
    }
}

package fr.influencraft.flux.api;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Public API of Flux, the interface layer of the Flux suite. A plugin of the suite builds its whole interface
 * with it: it ships its menus (YAML files in its own data folder), adds the button actions and placeholders
 * those menus need, and opens them. Everything registered by a plugin is dropped automatically when that
 * plugin is disabled.
 *
 * <pre>
 *   FluxApi.registerMenuFolder(this, new File(getDataFolder(), "menus"));
 *   FluxApi.registerAction(this, "roulette", (player, view, arg) -> ...);   // [roulette] spin
 *   FluxApi.registerPlaceholders(this, "fluxroulette", (player, params) -> ...);  // %fluxroulette_last%
 *   FluxApi.open(player, "roulette");
 * </pre>
 *
 * <p>Item sources: a button {@code material: "source:<id>:<argument>"} asks the source registered under
 * {@code <id>} for its item. The item keeps its name and description; the button's {@code name}, when given,
 * replaces the name, and its {@code lore} is added <b>below</b> the item description. Without {@code amount},
 * the item's own amount is kept.</p>
 *
 * <p>Call from the main thread, after Flux is enabled (declare {@code depend} or {@code softdepend: [Flux]}).</p>
 */
public final class FluxApi {

    /** Implemented by the Flux plugin itself. */
    public interface Backend {
        boolean open(Player player, String menuId);

        MenuView view(Player player);

        boolean hasMenu(String menuId);

        void menuFoldersChanged();

        void placeholdersChanged();

        String language();
    }

    private record Owned<T>(Plugin owner, T value) {
    }

    private static final Map<String, Owned<ItemSource>> SOURCES = new ConcurrentHashMap<>();
    private static final Map<String, Owned<FluxAction>> ACTIONS = new ConcurrentHashMap<>();
    private static final Map<String, Owned<PlaceholderProvider>> PLACEHOLDERS = new ConcurrentHashMap<>();
    private static final List<Owned<File>> MENU_FOLDERS = new CopyOnWriteArrayList<>();
    private static volatile Backend backend;

    private FluxApi() {
    }

    /** Suite-wide language ({@code language} in Flux's config, e.g. {@code fr_FR}), {@code en_US} without Flux. */
    public static String language() {
        Backend b = backend;
        return b == null ? "en_US" : b.language();
    }

    /** Whether Flux is running (false when the plugin is missing or disabled). */
    public static boolean available() {
        return backend != null;
    }

    // ---------------------------------------------------------------- menus

    /**
     * Adds a folder of menu files ({@code <id>.yml}, sub-folders allowed, files starting with "_" ignored),
     * loaded with Flux's own menus and reloaded by {@code /fluxmenu reload}. Usually the plugin's
     * {@code menus/} folder, filled from its jar on first start.
     */
    public static void registerMenuFolder(Plugin owner, File folder) {
        MENU_FOLDERS.removeIf(o -> o.value().equals(folder));
        MENU_FOLDERS.add(new Owned<>(owner, folder));
        Backend b = backend;
        if (b != null) b.menuFoldersChanged();
    }

    /** Opens a menu for a player. False if Flux is not running, the menu is unknown or the opening was refused. */
    public static boolean open(Player player, String menuId) {
        Backend b = backend;
        return b != null && b.open(player, menuId);
    }

    /** Whether a menu with this identifier is loaded. */
    public static boolean hasMenu(String menuId) {
        Backend b = backend;
        return b != null && b.hasMenu(menuId);
    }

    /** The Flux menu the player has open, or null. */
    public static MenuView view(Player player) {
        Backend b = backend;
        return b == null ? null : b.view(player);
    }

    // ---------------------------------------------------------------- actions

    /** Button action {@code [type] argument}. A type already taken by another plugin is replaced (logged by Flux). */
    public static void registerAction(Plugin owner, String type, FluxAction action) {
        ACTIONS.put(type.toLowerCase(Locale.ROOT), new Owned<>(owner, action));
    }

    public static FluxAction action(String type) {
        Owned<FluxAction> o = ACTIONS.get(type.toLowerCase(Locale.ROOT));
        return o == null ? null : o.value();
    }

    // ---------------------------------------------------------------- placeholders

    /** Placeholders {@code %<prefix>_...%}, resolved in menus and exposed to PlaceholderAPI when it is installed. */
    public static void registerPlaceholders(Plugin owner, String prefix, PlaceholderProvider provider) {
        PLACEHOLDERS.put(prefix.toLowerCase(Locale.ROOT), new Owned<>(owner, provider));
        Backend b = backend;
        if (b != null) b.placeholdersChanged();
    }

    public static PlaceholderProvider placeholders(String prefix) {
        Owned<PlaceholderProvider> o = PLACEHOLDERS.get(prefix.toLowerCase(Locale.ROOT));
        return o == null ? null : o.value();
    }

    public static Collection<String> placeholderPrefixes() {
        return List.copyOf(PLACEHOLDERS.keySet());
    }

    // ---------------------------------------------------------------- item sources

    public static void registerItemSource(Plugin owner, String id, ItemSource source) {
        SOURCES.put(id.toLowerCase(Locale.ROOT), new Owned<>(owner, source));
    }

    /** @deprecated use {@link #registerItemSource(Plugin, String, ItemSource)}, dropped automatically on disable */
    @Deprecated
    public static void registerItemSource(String id, ItemSource source) {
        SOURCES.put(id.toLowerCase(Locale.ROOT), new Owned<>(null, source));
    }

    public static void unregisterItemSource(String id) {
        SOURCES.remove(id.toLowerCase(Locale.ROOT));
    }

    public static ItemSource itemSource(String id) {
        Owned<ItemSource> o = SOURCES.get(id.toLowerCase(Locale.ROOT));
        return o == null ? null : o.value();
    }

    // ---------------------------------------------------------------- Flux side

    /** Called by Flux when it starts or stops (null). */
    public static void bind(Backend flux) {
        backend = flux;
    }

    /** Menu folders registered by other plugins, in registration order. */
    public static List<File> menuFolders() {
        return MENU_FOLDERS.stream().map(Owned::value).toList();
    }

    /** Drops everything registered by a plugin being disabled. Returns true if it had menu folders. */
    public static boolean unregisterAll(Plugin owner) {
        SOURCES.values().removeIf(o -> o.owner() == owner);
        ACTIONS.values().removeIf(o -> o.owner() == owner);
        PLACEHOLDERS.values().removeIf(o -> o.owner() == owner);
        return MENU_FOLDERS.removeIf(o -> o.owner() == owner);
    }
}

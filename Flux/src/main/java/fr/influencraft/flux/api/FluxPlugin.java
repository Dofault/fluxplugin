package fr.influencraft.flux.api;

import fr.influencraft.flux.api.command.Commands;
import fr.influencraft.flux.api.economy.Currency;
import fr.influencraft.flux.api.economy.Economy;
import fr.influencraft.flux.api.list.ListProvider;
import fr.influencraft.flux.api.list.Lists;
import fr.influencraft.flux.api.text.Messages;
import fr.influencraft.flux.api.util.DataFolders;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Base class of every plugin of the Flux suite ({@code depend: [Flux]} in its plugin.yml). It takes care of what
 * every plugin used to repeat:
 * <ul>
 *   <li>copying the data folder of a former plugin name on first start ({@link #formerNames()});</li>
 *   <li>{@code config.yml} written from the jar when missing;</li>
 *   <li>texts in {@code lang/<language>.yml} ({@link #messages()}), in the plugin's {@code language} or else the
 *   suite's one;</li>
 *   <li>menus shipped in the jar under {@code menus/}: copied to {@code plugins/<Plugin>/menus/} when missing and
 *   loaded by Flux;</li>
 *   <li>shortcuts to declare commands, placeholders, actions and lists, all dropped when the plugin stops.</li>
 * </ul>
 * Subclasses implement {@link #enable()} (and optionally {@link #disable()}, {@link #reload()}).
 */
public abstract class FluxPlugin extends JavaPlugin {

    private Messages messages;

    /** Former names of the plugin, whose data folder is copied on first start (most recent first). */
    protected List<String> formerNames() {
        return List.of();
    }

    @Override
    public final void onLoad() {
        List<String> names = formerNames();
        if (!names.isEmpty()) DataFolders.migrate(this, names.toArray(String[]::new));
        load();
    }

    /** Called from onLoad, after the data folder migration. */
    protected void load() {
    }

    @Override
    public final void onEnable() {
        if (getResource("config.yml") != null) saveDefaultConfig();
        if (getResource("lang/en_US.yml") != null) messages = Messages.load(this, language());
        shipMenus();
        enable();
    }

    @Override
    public final void onDisable() {
        disable();
    }

    protected abstract void enable();

    protected void disable() {
    }

    /** Re-reads config.yml and the language file; subclasses re-apply their settings after {@code super.reload()}. */
    public void reload() {
        reloadConfig();
        if (messages != null) messages.reload();
    }

    /** Language of this plugin: its own {@code language} config key, else the suite's. */
    public String language() {
        String own = getConfig().getString("language", "");
        return own.isBlank() ? FluxApi.language() : own;
    }

    /** Texts of the plugin; null when the jar ships no {@code lang/en_US.yml}. */
    public Messages messages() {
        return messages;
    }

    /** The server's main currency (see {@link Economy}). */
    public Currency money() {
        return Economy.main();
    }

    // ---------------------------------------------------------------- shortcuts

    protected Commands.Builder command(String id) {
        return Commands.create(this, id);
    }

    protected void placeholders(String prefix, PlaceholderProvider provider) {
        FluxApi.registerPlaceholders(this, prefix, provider);
    }

    protected void action(String type, FluxAction action) {
        FluxApi.registerAction(this, type, action);
    }

    protected void list(String id, ListProvider provider) {
        Lists.register(this, id, provider);
    }

    protected void currency(Currency currency) {
        Economy.register(this, currency);
    }

    /** Copies the jar's {@code menus/} files that are missing on disk, then hands the folder to Flux. */
    private void shipMenus() {
        List<String> shipped = new ArrayList<>();
        try (JarFile jar = new JarFile(getFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith("menus/") && name.endsWith(".yml")) shipped.add(name);
            }
        } catch (IOException e) {
            getLogger().warning("Could not list the menus of the jar: " + e.getMessage());
        }
        if (shipped.isEmpty()) return;
        for (String name : shipped) {
            if (!new File(getDataFolder(), name).exists()) saveResource(name, false);
        }
        FluxApi.registerMenuFolder(this, new File(getDataFolder(), "menus"));
    }
}

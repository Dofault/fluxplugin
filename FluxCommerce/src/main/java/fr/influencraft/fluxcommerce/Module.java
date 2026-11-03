package fr.influencraft.fluxcommerce;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.PlaceholderProvider;
import fr.influencraft.flux.api.command.Commands;
import fr.influencraft.flux.api.economy.Currency;
import fr.influencraft.flux.api.economy.Origin;
import fr.influencraft.flux.api.list.ListProvider;
import fr.influencraft.flux.api.list.Lists;
import fr.influencraft.flux.api.text.Messages;
import fr.influencraft.flux.api.util.Resources;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * One part of FluxCommerce (trades, auction house, chest shops). Each part has its own folder in the jar and in
 * {@code plugins/FluxCommerce/<id>/}: {@code config.yml}, {@code lang/<language>.yml}, {@code menus/}. Everything Bukkit
 * needs a plugin for (tasks, listeners, commands, lists) goes through {@link #host()}; the rules every part shares
 * (forbidden items, bans) are on the host too.
 */
public abstract class Module {

    protected final FluxCommerce host;
    private final String id;
    private final String key;
    private YamlConfiguration config = new YamlConfiguration();
    private Messages messages;

    /**
     * @param id  folder of the part ({@code trade}, {@code auctionhouse}, {@code chestshop}), its switch in
     *            {@code modules} of the main config
     * @param key short name in permissions and money origins ({@code trade}, {@code ah}, {@code chestshop})
     */
    protected Module(FluxCommerce host, String id, String key) {
        this.host = host;
        this.id = id;
        this.key = key;
    }

    /** The plugin (tasks, listeners, commands, lists, database). */
    public FluxCommerce host() {
        return host;
    }

    public String id() {
        return id;
    }

    /** Loads config and texts, then starts the part. */
    final void start() {
        loadFiles();
        Resources.shipMenus(host, host.language(), id + "/menus");
        enable();
    }

    protected abstract void enable();

    protected void disable() {
    }

    /** Re-reads the part's config and texts; parts re-apply their settings after {@code super.reload()}. */
    public void reload() {
        loadFiles();
    }

    private void loadFiles() {
        File file = new File(host.getDataFolder(), id + "/config.yml");
        if (!file.exists() && host.getResource(id + "/config.yml") != null) host.saveResource(id + "/config.yml", false);
        config = YamlConfiguration.loadConfiguration(file);
        try (InputStream in = host.getResource(id + "/config.yml")) {
            if (in != null) config.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            getLogger().warning("Could not read the default " + id + "/config.yml: " + e.getMessage());
        }
        if (messages == null) messages = Messages.load(host, host.language(), id + "/lang");
        else messages.reload();
    }

    // ---------------------------------------------------------------- what the parts used to get from their plugin

    /** {@code plugins/FluxCommerce/<id>/config.yml}, defaults from the jar. */
    public YamlConfiguration getConfig() {
        return config;
    }

    public void saveConfig() {
        try {
            config.save(new File(host.getDataFolder(), id + "/config.yml"));
        } catch (IOException e) {
            getLogger().warning("Could not save " + id + "/config.yml: " + e.getMessage());
        }
    }

    /** Texts of the part: {@code plugins/FluxCommerce/<id>/lang/<language>.yml}. */
    public Messages messages() {
        return messages;
    }

    public Logger getLogger() {
        return host.getLogger();
    }

    public Currency money() {
        return host.money();
    }

    public boolean isEnabled() {
        return host.isEnabled();
    }

    /**
     * Origin of a money movement of this part: key {@code <key>_<what>} ({@code ah_buy}), the part's key alone for
     * {@code ""}. Labels in the main lang file ({@code origins.<key>}).
     */
    public Origin origin(String what) {
        return Origin.of(host, what.isEmpty() ? key : key + "_" + what);
    }

    /** Permission node of this part: {@code fluxcommerce.<key>.<node>}. */
    public String permission(String node) {
        return FluxCommerce.PERMISSION + key + "." + node;
    }

    protected Commands.Builder command(String commandId) {
        return Commands.create(host, commandId);
    }

    protected void placeholders(String prefix, PlaceholderProvider provider) {
        FluxApi.registerPlaceholders(host, prefix, provider);
    }

    protected void action(String type, fr.influencraft.flux.api.FluxAction action) {
        FluxApi.registerAction(host, type, action);
    }

    protected void list(String listId, ListProvider provider) {
        Lists.register(host, listId, provider);
    }
}

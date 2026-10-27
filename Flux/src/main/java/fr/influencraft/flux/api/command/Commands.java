package fr.influencraft.flux.api.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Commands of the server, in one file: {@code plugins/Flux/commands.yml}. A plugin of the suite declares its
 * commands here (not in plugin.yml), under an English default name; Flux writes each one into the file where it can
 * be renamed, given aliases, described, hidden from the "/" suggestions or switched off:
 *
 * <pre>
 *   fluxrewards:
 *     rewards:            # identifier, never changes
 *       name: recompenses # label typed by players
 *       aliases: [rewards, rw]
 *       description: "Récompenses à récupérer"   # suggestions and /aide
 *       suggest: true     # proposed when typing "/" (permissions still apply)
 *       enabled: true
 *   menus:                # commands opening a menu (first label of each menu), written by Flux
 *     metiers: {description: "Tes métiers", suggest: true}
 *   others:               # commands of other plugins (CMI, Lands...) proposed when typing "/"
 *     spawn: "Retourner au spawn"
 *     vanish: {description: "Devenir invisible", permission: flux.commands.staff}
 * </pre>
 * Messages mention a command with {@code %flux_cmd_<plugin>_<id>%} (the configured label) so they stay right.
 *
 * <pre>
 *   Commands.create(this, "rewards")
 *       .description("Claim your pending rewards")
 *       .permission("fluxrewards.use")
 *       .executor((sender, label, args) -> { ...; return true; })
 *       .completer((sender, args) -> List.of())
 *       .register();
 * </pre>
 */
public final class Commands {

    /** What the command does; return false to show the usage. */
    @FunctionalInterface
    public interface Executor {
        boolean run(CommandSender sender, String label, String[] args);
    }

    @FunctionalInterface
    public interface Completer {
        List<String> complete(CommandSender sender, String[] args);
    }

    private record Registered(Plugin owner, String id, SuiteCommand command, String description, boolean suggest) {
    }

    /** A command proposed when typing "/": its label, description, other labels, and the permission to see it. */
    public record Shown(String label, String description, List<String> aliases, String permission) {
    }

    /** Sections of commands.yml that are not plugins. */
    private static final java.util.Set<String> SECTIONS = java.util.Set.of("menus", "others");

    private static final Map<String, Registered> COMMANDS = new ConcurrentHashMap<>();
    private static File file;
    private static YamlConfiguration config = new YamlConfiguration();
    private static Plugin flux;
    private static boolean syncScheduled;

    private Commands() {
    }

    /** Called by Flux at start-up (and on reload, which re-reads the file without re-registering). */
    public static void init(Plugin fluxPlugin) {
        flux = fluxPlugin;
        file = new File(fluxPlugin.getDataFolder(), "commands.yml");
        config = YamlConfiguration.loadConfiguration(file);
        // the server owner's own header (comments at the top) is kept
        if (config.options().getHeader().isEmpty()) config.options().setHeader(List.of(
                "Commands of the server. Each plugin of the suite adds its own here on first start.",
                "name: label typed by players, aliases: other labels, enabled: false removes the command,",
                "description: shown by /aide, suggest: false hides it from the \"/\" suggestions (permissions still apply).",
                "menus: commands opening a menu (written by Flux). others: commands of other plugins to propose.",
                "Messages show the configured label with %flux_cmd_<plugin>_<id>%. Names apply after a restart,",
                "descriptions and suggestions after /fluxmenu reload."));
    }

    /** Re-reads the file (descriptions, suggestions, others) without registering again. */
    public static synchronized void reload() {
        if (file != null) config = YamlConfiguration.loadConfiguration(file);
    }

    /** Commands proposed when typing "/": suite commands, menu commands and the listed commands of other plugins. */
    public static synchronized List<Shown> shown() {
        List<Shown> out = new ArrayList<>();
        for (Registered r : COMMANDS.values()) {
            String path = r.owner().getName().toLowerCase(Locale.ROOT) + "." + r.id();
            if (!config.getBoolean(path + ".suggest", r.suggest())) continue;
            out.add(new Shown(r.command().getName(), config.getString(path + ".description", r.description()),
                    r.command().getAliases(), r.command().getPermission()));
        }
        for (String section : SECTIONS) {
            ConfigurationSection s = config.getConfigurationSection(section);
            if (s == null) continue;
            for (String label : s.getKeys(false)) {
                String description;
                String permission = null;
                if (s.isConfigurationSection(label)) {
                    ConfigurationSection e = s.getConfigurationSection(label);
                    if (!e.getBoolean("suggest", true)) continue;
                    description = e.getString("description", "");
                    permission = e.getString("permission", null);
                } else {
                    description = s.getString(label, "");
                }
                out.add(new Shown(label.toLowerCase(Locale.ROOT), description, List.of(), permission));
            }
        }
        return out;
    }

    /** A menu opened by a command: written to the {@code menus} section on first start (suggested, no description). */
    public static synchronized void menuCommand(String label) {
        String path = "menus." + label.toLowerCase(Locale.ROOT);
        if (config.isSet(path)) return;
        config.set(path + ".description", "");
        config.set(path + ".suggest", true);
        save();
    }

    private static void save() {
        try {
            config.save(file);
        } catch (IOException e) {
            if (flux != null) flux.getLogger().warning("Could not save commands.yml: " + e.getMessage());
        }
    }

    public static Builder create(Plugin owner, String id) {
        return new Builder(owner, id);
    }

    /** Label players type for a command ({@code /<label>}), or its default id when unknown. */
    public static String label(String plugin, String id) {
        Registered r = COMMANDS.get(key(plugin, id));
        if (r != null) return r.command().getName();
        return config.getString(plugin.toLowerCase(Locale.ROOT) + "." + id + ".name", id);
    }

    /** Removes the commands of a plugin being disabled. */
    public static void unregisterAll(Plugin owner) {
        CommandMap map = Bukkit.getCommandMap();
        COMMANDS.values().removeIf(r -> {
            if (r.owner() != owner) return false;
            unregister(map, r.command());
            return true;
        });
        scheduleSync();
    }

    private static String key(String plugin, String id) {
        return plugin.toLowerCase(Locale.ROOT) + ":" + id.toLowerCase(Locale.ROOT);
    }

    private static void unregister(CommandMap map, Command command) {
        command.unregister(map);
        Map<String, Command> known = map.getKnownCommands();
        known.values().removeIf(c -> c == command);
    }

    private static void scheduleSync() {
        if (syncScheduled || flux == null || !flux.isEnabled()) return;
        syncScheduled = true;
        Bukkit.getScheduler().runTask(flux, () -> {
            syncScheduled = false;
            for (Player p : Bukkit.getOnlinePlayers()) p.updateCommands();
        });
    }

    private static synchronized ConfigurationSection entry(Plugin owner, String id, List<String> defaultAliases,
                                                          String description, boolean suggest) {
        String path = owner.getName().toLowerCase(Locale.ROOT) + "." + id;
        ConfigurationSection section = config.getConfigurationSection(path);
        boolean changed = false;
        if (section == null) {
            section = config.createSection(path);
            section.set("name", id);
            section.set("aliases", defaultAliases);
            section.set("enabled", true);
            changed = true;
        }
        // keys added later to the file: written once with the plugin's defaults
        if (!section.isSet("description")) {
            section.set("description", description);
            changed = true;
        }
        if (!section.isSet("suggest")) {
            section.set("suggest", suggest);
            changed = true;
        }
        if (changed) save();
        return section;
    }

    /** A command being declared. */
    public static final class Builder {
        private final Plugin owner;
        private final String id;
        private String description = "";
        private String usage = null;
        private String permission = null;
        private List<String> aliases = List.of();
        private Executor executor = (s, l, a) -> true;
        private Completer completer = null;
        private boolean suggest = true;

        private Builder(Plugin owner, String id) {
            this.owner = owner;
            this.id = id.toLowerCase(Locale.ROOT);
        }

        public Builder description(String text) {
            this.description = text;
            return this;
        }

        /** Shown when the executor returns false; {@code <command>} is replaced by the label used. */
        public Builder usage(String text) {
            this.usage = text;
            return this;
        }

        /** Required to use (and see) the command; null for everyone. */
        public Builder permission(String node) {
            this.permission = node;
            return this;
        }

        /** Default aliases, written to commands.yml on first start. */
        public Builder aliases(String... labels) {
            this.aliases = List.of(labels);
            return this;
        }

        /** False: not proposed when typing "/" by default (admin or technical commands); commands.yml decides. */
        public Builder suggest(boolean shown) {
            this.suggest = shown;
            return this;
        }

        public Builder executor(Executor executor) {
            this.executor = executor;
            return this;
        }

        public Builder completer(Completer completer) {
            this.completer = completer;
            return this;
        }

        /**
         * Runs an existing Bukkit {@link org.bukkit.command.CommandExecutor} (and tab completer, may be null): it
         * receives a command named after the id, whatever label the server owner configured.
         */
        public Builder bukkit(org.bukkit.command.CommandExecutor bukkitExecutor, org.bukkit.command.TabCompleter bukkitCompleter) {
            Command self = new Command(id) {
                @Override
                public boolean execute(CommandSender sender, String label, String[] args) {
                    return true;
                }
            };
            this.executor = (sender, label, args) -> bukkitExecutor.onCommand(sender, self, label, args);
            this.completer = bukkitCompleter == null ? null : (sender, args) -> bukkitCompleter.onTabComplete(sender, self, id, args);
            return this;
        }

        /** Registers the command under its configured label. Returns false when switched off in commands.yml. */
        public boolean register() {
            if (flux == null) throw new IllegalStateException("Flux is not enabled");
            ConfigurationSection entry = entry(owner, id, aliases, description, suggest);
            if (!entry.getBoolean("enabled", true)) return false;
            String name = entry.getString("name", id).trim().toLowerCase(Locale.ROOT);
            List<String> labels = new ArrayList<>();
            for (String a : entry.getStringList("aliases")) {
                String l = a.trim().toLowerCase(Locale.ROOT);
                if (!l.isEmpty() && !l.equals(name)) labels.add(l);
            }
            SuiteCommand command = new SuiteCommand(name, description, usage, labels, permission, executor, completer);
            Registered previous = COMMANDS.put(key(owner.getName(), id), new Registered(owner, id, command, description, suggest));
            CommandMap map = Bukkit.getCommandMap();
            if (previous != null) unregister(map, previous.command());
            map.register(owner.getName().toLowerCase(Locale.ROOT), command);
            // the configured label wins over a command of the same name from another plugin
            map.getKnownCommands().put(name, command);
            for (String l : labels) map.getKnownCommands().putIfAbsent(l, command);
            scheduleSync();
            return true;
        }
    }

    private static final class SuiteCommand extends Command {
        private final Executor executor;
        private final Completer completer;

        SuiteCommand(String name, String description, String usage, List<String> aliases, String permission,
                     Executor executor, Completer completer) {
            super(name, description, usage == null ? "/" + name : usage, aliases);
            setPermission(permission);
            this.executor = executor;
            this.completer = completer;
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            if (!testPermission(sender)) return true;
            boolean ok = executor.run(sender, label, args);
            if (!ok && !getUsage().isEmpty()) {
                sender.sendMessage(fr.influencraft.flux.api.text.Texts.parse("&#FF5C5C" + getUsage().replace("<command>", label)));
            }
            return true;
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            if (completer == null) return super.tabComplete(sender, alias, args);
            if (getPermission() != null && !sender.hasPermission(getPermission())) return List.of();
            List<String> out = completer.complete(sender, args);
            return out == null ? List.of() : out;
        }
    }
}

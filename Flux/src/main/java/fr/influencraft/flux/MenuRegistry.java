package fr.influencraft.flux;

import fr.influencraft.flux.menu.Menu;
import fr.influencraft.flux.menu.MenuLoader;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Menus chargés depuis menus/ (sous-dossiers compris) et commandes d'ouverture associées. */
public final class MenuRegistry {

    private final Flux plugin;
    private final Map<String, Menu> menus = new LinkedHashMap<>();
    /** Commandes inscrites, par libellé. L'objet garde son menu en mémoire par identifiant. */
    private final Map<String, OpenCommand> registered = new LinkedHashMap<>();

    MenuRegistry(Flux plugin) {
        this.plugin = plugin;
    }

    public Menu get(String id) {
        return id == null ? null : menus.get(id.trim().toLowerCase(Locale.ROOT));
    }

    public Collection<Menu> all() {
        return menus.values();
    }

    /** Relit tous les fichiers. Retourne le nombre d'avertissements. */
    int load() {
        menus.clear();
        File dir = new File(plugin.getDataFolder(), "menus");
        int[] warnings = {0};
        scan(dir, "menus/", warnings);
        // menus shipped by the other plugins of the suite (FluxApi.registerMenuFolder)
        for (File folder : fr.influencraft.flux.api.FluxApi.menuFolders()) {
            scan(folder, folder.getParentFile().getName() + "/" + folder.getName() + "/", warnings);
        }
        registerCommands();
        return warnings[0];
    }

    private void scan(File dir, String prefix, int[] warnings) {
        File[] files = dir.listFiles();
        if (files == null) return;
        java.util.Arrays.sort(files);
        for (File f : files) {
            if (f.isDirectory()) {
                // Dossier _xxx : pages incluses par un menu (page-files), pas des menus
                if (!f.getName().startsWith("_")) scan(f, prefix + f.getName() + "/", warnings);
            } else if (f.getName().endsWith(".yml") && !f.getName().startsWith("_")) {
                String name = f.getName().substring(0, f.getName().length() - 4).toLowerCase(Locale.ROOT);
                // Identifiant = nom du fichier ; un doublon dans un sous-dossier est signalé.
                if (menus.containsKey(name)) {
                    plugin.getLogger().warning(prefix + f.getName() + " : identifiant « " + name + " » déjà pris, fichier ignoré");
                    warnings[0]++;
                    continue;
                }
                Menu menu = MenuLoader.load(name, f, msg -> {
                    plugin.getLogger().warning(msg);
                    warnings[0]++;
                });
                menus.put(name, menu);
            }
        }
    }

    /**
     * Ne touche l'arbre des commandes que pour les libellés ajoutés ou retirés : le modifier
     * pendant que Paper le recopie pour les joueurs (fil asynchrone) lève une
     * ConcurrentModificationException. La synchronisation part une seule fois, au tick suivant.
     */
    /** Commandes d'autres plugins remplacées (override-commands), à remettre au retrait. */
    private final Map<String, Command> replaced = new LinkedHashMap<>();

    private void registerCommands() {
        Map<String, String> wanted = new LinkedHashMap<>();
        java.util.Set<String> overriding = new java.util.HashSet<>();
        for (Menu menu : menus.values()) {
            // the first label of a menu is its command in commands.yml (description, suggestion); the others are aliases
            if (!menu.commands().isEmpty() && !menu.commands().get(0).isBlank()) {
                fr.influencraft.flux.api.command.Commands.menuCommand(menu.commands().get(0).trim());
            }
            for (String label : menu.commands()) {
                String l = label.trim().toLowerCase(Locale.ROOT);
                if (!l.isEmpty() && wanted.putIfAbsent(l, menu.id()) == null && menu.overrideCommands()) overriding.add(l);
            }
        }
        CommandMap map = Bukkit.getCommandMap();
        boolean changed = false;
        for (String label : new ArrayList<>(registered.keySet())) {
            if (!wanted.containsKey(label)) {
                remove(map, registered.remove(label));
                changed = true;
            }
        }
        for (Map.Entry<String, String> e : wanted.entrySet()) {
            OpenCommand cmd = registered.get(e.getKey());
            if (cmd != null) {
                cmd.menuId = e.getValue();
            } else {
                cmd = new OpenCommand(e.getKey(), e.getValue());
                map.register("flux", cmd);
                registered.put(e.getKey(), cmd);
                changed = true;
            }
            // override-commands : le libellé nu pointe sur Flux même s'il appartenait à un
            // autre plugin (ex : /metiers de DeluxeMenus) ; l'ancienne commande est gardée de côté.
            Map<String, Command> known = map.getKnownCommands();
            if (overriding.contains(e.getKey()) && known.get(e.getKey()) != cmd) {
                Command previous = known.put(e.getKey(), cmd);
                if (previous != null && !replaced.containsKey(e.getKey())) replaced.put(e.getKey(), previous);
                changed = true;
            }
        }
        if (changed && plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, MenuRegistry::syncCommands);
    }

    /** À l'arrêt du plugin. */
    void unregisterCommands() {
        if (registered.isEmpty()) return;
        CommandMap map = Bukkit.getCommandMap();
        for (OpenCommand cmd : registered.values()) remove(map, cmd);
        registered.clear();
    }

    private void remove(CommandMap map, Command cmd) {
        cmd.unregister(map);
        Map<String, Command> known = map.getKnownCommands();
        known.remove(cmd.getName(), cmd);
        known.remove("flux:" + cmd.getName(), cmd);
        Command previous = replaced.remove(cmd.getName());
        if (previous != null) known.putIfAbsent(cmd.getName(), previous);
    }

    private static void syncCommands() {
        for (Player p : Bukkit.getOnlinePlayers()) p.updateCommands();
    }

    /** /<commande> ouvre le menu pour soi. */
    private final class OpenCommand extends Command {
        private volatile String menuId;

        OpenCommand(String label, String menuId) {
            super(label, "Ouvre le menu " + menuId, "/" + label, List.of());
            this.menuId = menuId;
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Commande réservée aux joueurs (console : /fluxmenu open " + menuId + " <joueur>).");
                return true;
            }
            Menu menu = get(menuId);
            if (menu != null) plugin.menus().open(player, menu);
            return true;
        }
    }
}

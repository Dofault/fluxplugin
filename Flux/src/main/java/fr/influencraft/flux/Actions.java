package fr.influencraft.flux;

import fr.influencraft.flux.menu.Menu;
import fr.influencraft.flux.menu.Page;
import fr.influencraft.flux.session.MenuSession;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/**
 * Actions des boutons, une par ligne : {@code [type] argument}.
 * <pre>
 *   [player] home             commande lancée par le joueur
 *   [console] give %player% x commande lancée par la console
 *   [message] &aBonjour       message au joueur
 *   [close]                   ferme le menu
 *   [menu] profil             ouvre un menu Flux
 *   [deluxemenu] profil       ouvre un menu DeluxeMenus (transition)
 *   [openguimenu] profil      menu Flux s'il existe, sinon DeluxeMenus
 *   [sound] minecraft:ui.button.click 1 1   (or a named cue of the suite: [sound] @success)
 *   [refresh]                 recalcule le menu ouvert
 *   [refresh-buttons]         recalcule seulement les boutons (garde le titre affiché : animations)
 *   [page] parametres         anime puis affiche une page du menu ([page] main pour revenir)
 *   [page-toggle] parametres  idem, ou revient au menu si la page est déjà affichée
 *   [page-swap] parrainage_2  remplace la page affichée sans animation (précédent / suivant)
 *   [copy] %parrainage_adresse%      dialogue vanilla à un bouton : copie dans le presse-papier
 *   [link] https://wiki.influencraft.fr  dialogue vanilla à un bouton : ouvre le lien
 *   [collection] geologie     ouvre une catégorie EcoCollections (aucune commande ne le permet)
 *   [<type>] ...              action fournie par un autre plugin (FluxApi.registerAction), ex. [roulette] spin
 * </pre>
 * Les actions d'un clic sont jouées au tick suivant (ouvrir une fenêtre pendant un
 * InventoryClickEvent est déconseillé par Paper), dans l'ordre écrit.
 */
public final class Actions {

    private final Flux plugin;

    Actions(Flux plugin) {
        this.plugin = plugin;
    }

    public void run(Player player, MenuSession session, List<String> actions) {
        if (actions.isEmpty() || !plugin.isEnabled()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            // one sound per click, whatever the lines play (see Sounds#begin)
            fr.influencraft.flux.api.util.Sounds.begin(player);
            try {
                for (String line : actions) {
                    if (!player.isOnline()) return;
                    try {
                        runOne(player, session, line);
                    } catch (RuntimeException e) {
                        plugin.getLogger().warning("Action \"" + line + "\" failed for " + player.getName() + ": " + e);
                    }
                }
            } finally {
                fr.influencraft.flux.api.util.Sounds.end(player);
            }
        });
    }

    private void runOne(Player player, MenuSession session, String line) {
        String trimmed = line.trim();
        String type = "player";
        String arg = trimmed;
        if (trimmed.startsWith("[")) {
            int end = trimmed.indexOf(']');
            if (end > 0) {
                type = trimmed.substring(1, end).trim().toLowerCase(Locale.ROOT);
                arg = trimmed.substring(end + 1).trim();
            }
        }
        switch (type) {
            case "player" -> player.performCommand(stripSlash(Text.placeholders(player, arg)));
            case "console" -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), stripSlash(Text.placeholders(player, arg)));
            case "message" -> player.sendMessage(Text.line(player, arg));
            case "close" -> player.closeInventory();
            case "menu" -> openMenu(player, arg, false);
            case "openguimenu" -> openMenu(player, arg, true);
            case "deluxemenu" -> deluxeMenu(player, arg);
            case "sound" -> sound(player, arg);
            case "page" -> page(player, session, Text.placeholders(player, arg), false);
            case "page-toggle" -> page(player, session, Text.placeholders(player, arg), true);
            case "copy" -> {
                String text = Text.placeholders(player, arg);
                if (text.isBlank()) plugin.getLogger().warning("[copy] texte vide pour " + player.getName());
                else fr.influencraft.flux.api.dialog.Dialogs.copy(player, fr.influencraft.flux.api.util.Format.word("copy-title"), text);
            }
            case "link" -> {
                String url = Text.placeholders(player, arg).trim();
                if (url.isBlank()) plugin.getLogger().warning("[link] lien vide pour " + player.getName());
                else fr.influencraft.flux.api.dialog.Dialogs.link(player, fr.influencraft.flux.api.util.Format.word("link-title"), url);
            }
            case "page-swap" -> {
                if (session == null) return;
                Page target = session.menu().page(Text.placeholders(player, arg).trim().toLowerCase(Locale.ROOT));
                if (target == null) {
                    plugin.getLogger().warning("[page-swap] page inconnue « " + arg + " » dans le menu " + session.menu().id());
                    return;
                }
                plugin.menus().swapPage(player, session, target);
            }
            case "list" -> list(player, session, Text.placeholders(player, arg));
            case "refresh-buttons" -> {
                // boutons seulement : le titre affiché (animation du Plinko...) n'est pas touché
                if (session != null) plugin.menus().renderButtons(player, session);
            }
            case "refresh" -> {
                if (session != null) plugin.menus().refresh(player, session);
            }
            default -> {
                // action of another plugin of the suite
                fr.influencraft.flux.api.FluxAction external = fr.influencraft.flux.api.FluxApi.action(type);
                if (external == null) {
                    plugin.getLogger().warning("Action inconnue « " + line + " »");
                    return;
                }
                external.run(player, session == null ? null : new FluxView(plugin, player, session), Text.placeholders(player, arg));
            }
        }
    }

    /**
     * [list] <id> <next|prev|first|page <n>|filter|sort|search|clear|reset ...>: navigation in a list of the open menu.
     * "search" without text opens a search field, then the menu again.
     */
    private void list(Player player, MenuSession session, String arg) {
        String[] parts = arg.trim().split("\\s+", 2);
        if (parts.length < 2) {
            plugin.getLogger().warning("[list] attend « <liste> <commande> », lu « " + arg + " »");
            return;
        }
        String id = parts[0].toLowerCase(Locale.ROOT);
        fr.influencraft.flux.menu.ListSpec spec = session == null ? null : session.menu().list(id);
        int pageSize = spec == null ? 21 : spec.pageSize();
        boolean askSearch = fr.influencraft.flux.api.list.Lists.act(player, id, parts[1], pageSize);
        if (askSearch) {
            Menu menu = session == null ? null : session.menu();
            String current = fr.influencraft.flux.api.list.Lists.state(player, id).search();
            fr.influencraft.flux.api.dialog.Dialogs.form(player, fr.influencraft.flux.api.util.Format.word("search-title"))
                    .input("search", fr.influencraft.flux.api.util.Format.word("search-label"), current, 48)
                    .onSubmit((p, answer) -> {
                        fr.influencraft.flux.api.list.Lists.act(p, id, answer.text("search").isEmpty() ? "clear" : "search " + answer.text("search"), pageSize);
                        if (menu != null) plugin.menus().open(p, menu);
                    })
                    .onCancel(p -> {
                        if (menu != null) plugin.menus().open(p, menu);
                    })
                    .open();
            return;
        }
        // the same feedback in every list of the suite: page sound for pages, click for filters and sorts
        String verb = parts[1].trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
        fr.influencraft.flux.api.util.Sounds.cue(player, verb.equals("next") || verb.equals("prev") || verb.equals("first") ? "page" : "click");
        if (session != null) plugin.menus().refresh(player, session);
    }

    /** [page] <id|main> ; [page-toggle] <id> revient au menu si la page est déjà affichée. */
    private void page(Player player, MenuSession session, String id, boolean toggle) {
        if (session == null) return;
        String wanted = id.trim().toLowerCase(Locale.ROOT);
        Page target = wanted.isEmpty() || wanted.equals("main") ? null : session.menu().page(wanted);
        if (target == null && !(wanted.isEmpty() || wanted.equals("main"))) {
            plugin.getLogger().warning("[page] page inconnue « " + id + " » dans le menu " + session.menu().id());
            return;
        }
        if (toggle && target != null && session.page() == target) target = null;
        plugin.menus().switchPage(player, session, target);
    }

    private void openMenu(Player player, String id, boolean fallbackDeluxe) {
        Menu menu = plugin.registry().get(id);
        if (menu != null) {
            plugin.menus().open(player, menu);
        } else if (fallbackDeluxe) {
            deluxeMenu(player, id);
        } else {
            plugin.getLogger().warning("[menu] menu inconnu « " + id + " »");
        }
    }

    /**
     * Ouvre le menu d'une catégorie EcoCollections ({@code GroupGUI.open}), par réflexion :
     * {@code /collections} n'ouvre que la liste générale, sans argument de catégorie.
     */
    private void deluxeMenu(Player player, String id) {
        if (!Bukkit.getPluginManager().isPluginEnabled("DeluxeMenus")) {
            plugin.getLogger().warning("[deluxemenu] DeluxeMenus absent, « " + id + " » non ouvert");
            return;
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "dm open " + id.trim() + " " + player.getName());
    }

    /** {@code [sound] <key> [volume] [pitch]} or a named cue of the suite: {@code [sound] @success}. */
    private static void sound(Player player, String arg) {
        fr.influencraft.flux.api.util.Sounds.explicit(player, arg);
    }


    private static String stripSlash(String cmd) {
        return cmd.startsWith("/") ? cmd.substring(1) : cmd;
    }
}

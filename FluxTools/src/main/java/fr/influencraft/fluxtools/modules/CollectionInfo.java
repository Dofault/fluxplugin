package fr.influencraft.fluxtools.modules;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Placeholders EcoCollections par catégorie (EcoCollections n'en donne que par collection et
 * pour le total) : sert au menu Collection.
 * <pre>
 *   %fluxcoll_<catégorie>_completed%  collections terminées de la catégorie
 *   %fluxcoll_<catégorie>_total%      collections de la catégorie
 *   %fluxcoll_<catégorie>_percent%    part terminée, en pourcentage arrondi vers le bas (0 à 100)
 *   %fluxcoll_completed%              collections terminées, toutes catégories
 *   %fluxcoll_total%                  nombre total de collections
 *   %fluxcoll_percent%                part terminée, toutes catégories
 *   %fluxcoll_selected%               collection sélectionnée dans le menu d'une catégorie
 *                                      ([fluxcoll] select <id>), vide si aucune
 *   %fluxcoll_selected_tier%          son palier (%ecocollections_<id>%) : barre de progression
 *   %fluxcoll_selected_<champ>%       %ecocollections_<id>_<champ>% de la collection sélectionnée
 *                                      (count, required, percent, max_tier, numeral...)
 * </pre>
 * Accès par réflexion à l'API d'EcoCollections (aucune dépendance de compilation) : les
 * collections ajoutées ou retirées sont prises en compte sans rien changer ici.
 */
public final class CollectionInfo {

    private final Plugin plugin;
    private final Object collections;
    private final Method values, groupId, isComplete;
    /** Collection sélectionnée par joueur dans le menu d'une catégorie. */
    private final Map<UUID, String> selected = new ConcurrentHashMap<>();

    private CollectionInfo(Plugin plugin, Plugin eco) throws ReflectiveOperationException {
        this.plugin = plugin;
        ClassLoader loader = eco.getClass().getClassLoader();
        Class<?> registry = Class.forName("com.exanthiax.ecocollections.collections.Collections", true, loader);
        Class<?> collection = Class.forName("com.exanthiax.ecocollections.collections.Collection", true, loader);
        Class<?> api = Class.forName("com.exanthiax.ecocollections.api.CollectionAPI", true, loader);
        collections = registry.getField("INSTANCE").get(null);
        values = registry.getMethod("values");
        groupId = collection.getMethod("getGroupId");
        isComplete = api.getMethod("isCollectionComplete", OfflinePlayer.class, collection);
    }

    /** Registers the placeholders when EcoCollections is there; null otherwise. */
    public static CollectionInfo register(Plugin plugin) {
        Plugin eco = Bukkit.getPluginManager().getPlugin("EcoCollections");
        if (eco == null || !eco.isEnabled()) return null;
        try {
            CollectionInfo expansion = new CollectionInfo(plugin, eco);
            // [fluxcoll] select <id> | reset: collection shown at the bottom of a category menu
            fr.influencraft.flux.api.FluxApi.registerAction(plugin, "fluxcoll", (player, view, argument) -> {
                String[] parts = argument.trim().split("\\s+");
                if (parts[0].equalsIgnoreCase("select") && parts.length == 2) expansion.select(player, parts[1]);
                else if (parts[0].equalsIgnoreCase("reset")) expansion.reset(player);
                if (view != null) view.refresh();
            });
            // [collection] <category>: opens an EcoCollections category directly (/collections takes no category)
            fr.influencraft.flux.api.FluxApi.registerAction(plugin, "collection", (player, view, argument) ->
                    openGroup(plugin, eco, player, argument.trim().toLowerCase(java.util.Locale.ROOT)));
            // %fluxcoll_*%: resolved by Flux in menus, exposed to PlaceholderAPI by Flux
            fr.influencraft.flux.api.FluxApi.registerPlaceholders(plugin, "fluxcoll", expansion::onPlaceholderRequest);
            return expansion;
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().warning("Placeholders des collections indisponibles (API EcoCollections changée ?) : " + e);
            return null;
        }
    }





    /** [fluxcoll] select <id> : retient la collection affichée en bas du menu de catégorie. */
    public void select(Player player, String id) {
        selected.put(player.getUniqueId(), id.toLowerCase(Locale.ROOT));
    }

    /** [fluxcoll] reset : plus aucune collection sélectionnée. */
    public void reset(Player player) {
        selected.remove(player.getUniqueId());
    }

    public String onPlaceholderRequest(Player player, String params) {
        String p = params.toLowerCase(Locale.ROOT);
        if (p.equals("selected") || p.startsWith("selected_")) {
            String id = player == null ? null : selected.get(player.getUniqueId());
            if (p.equals("selected")) return id == null ? "" : id;
            if (id == null) return "0";
            String field = p.substring("selected_".length());
            String target = field.equals("tier") ? "%ecocollections_" + id + "%" : "%ecocollections_" + id + "_" + field + "%";
            return fr.influencraft.flux.api.text.Texts.placeholders(target, player);
        }
        String field = p.contains("_") ? p.substring(p.lastIndexOf('_') + 1) : p;
        if (!field.equals("completed") && !field.equals("total") && !field.equals("percent")) return null;
        String group = p.equals(field) ? null : p.substring(0, p.length() - field.length() - 1);
        if (player == null && !field.equals("total")) return "0";
        try {
            int total = 0;
            int done = 0;
            for (Object c : (Collection<?>) values.invoke(collections)) {
                if (group != null && !group.equalsIgnoreCase((String) groupId.invoke(c))) continue;
                total++;
                if (!field.equals("total") && (boolean) isComplete.invoke(null, player, c)) done++;
            }
            return switch (field) {
                case "total" -> String.valueOf(total);
                case "completed" -> String.valueOf(done);
                default -> String.valueOf(total == 0 ? 0 : done * 100 / total);
            };
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "0";
        }
    }

    private static void openGroup(Plugin plugin, Plugin eco, Player player, String id) {
        try {
            ClassLoader loader = eco.getClass().getClassLoader();
            Class<?> groupsClass = Class.forName("com.exanthiax.ecocollections.groups.CollectionGroups", true, loader);
            Object groups = groupsClass.getField("INSTANCE").get(null);
            Object group = groupsClass.getMethod("getByID", String.class).invoke(groups, id);
            if (group == null) {
                plugin.getLogger().warning("[collection] unknown category \u00ab " + id + " \u00bb");
                return;
            }
            Class<?> guiClass = Class.forName("com.exanthiax.ecocollections.gui.GroupGUI", true, loader);
            Class<?> groupClass = Class.forName("com.exanthiax.ecocollections.groups.CollectionGroup", true, loader);
            Object gui = guiClass.getField("INSTANCE").get(null);
            guiClass.getMethod("open", Player.class, groupClass, boolean.class, int.class).invoke(gui, player, group, false, 1);
        } catch (ReflectiveOperationException e) {
            plugin.getLogger().warning("[collection] could not open \u00ab " + id + " \u00bb: " + e);
        }
    }
}

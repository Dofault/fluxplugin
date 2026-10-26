package fr.influencraft.fluxmenu;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
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
public final class CollectionInfo extends PlaceholderExpansion {

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

    /** Inscrit l'extension si EcoCollections et PlaceholderAPI sont là ; null sinon. */
    static CollectionInfo register(Plugin plugin) {
        Plugin eco = Bukkit.getPluginManager().getPlugin("EcoCollections");
        if (eco == null || !eco.isEnabled() || !Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return null;
        try {
            CollectionInfo expansion = new CollectionInfo(plugin, eco);
            expansion.register();
            return expansion;
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().warning("Placeholders des collections indisponibles (API EcoCollections changée ?) : " + e);
            return null;
        }
    }

    @Override
    public String getIdentifier() {
        return "fluxcoll";
    }

    @Override
    public String getAuthor() {
        return "Influencraft";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    /** [fluxcoll] select <id> : retient la collection affichée en bas du menu de catégorie. */
    public void select(Player player, String id) {
        selected.put(player.getUniqueId(), id.toLowerCase(Locale.ROOT));
    }

    /** [fluxcoll] reset : plus aucune collection sélectionnée. */
    public void reset(Player player) {
        selected.remove(player.getUniqueId());
    }

    @Override
    public String onPlaceholderRequest(Player player, String params) {
        String p = params.toLowerCase(Locale.ROOT);
        if (p.equals("selected") || p.startsWith("selected_")) {
            String id = player == null ? null : selected.get(player.getUniqueId());
            if (p.equals("selected")) return id == null ? "" : id;
            if (id == null) return "0";
            String field = p.substring("selected_".length());
            String target = field.equals("tier") ? "%ecocollections_" + id + "%" : "%ecocollections_" + id + "_" + field + "%";
            return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, target);
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
}

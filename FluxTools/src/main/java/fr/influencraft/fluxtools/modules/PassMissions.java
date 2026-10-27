package fr.influencraft.fluxtools.modules;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Placeholders des missions EcoBattlepass, une par une (EcoBattlepass n'expose que des
 * compteurs par catégorie) : sert aux pages de missions de /menu2.
 * <pre>
 *   %fluxpass_<catégorie>_<n>_name%      nom de la n-ième mission (1 = première)
 *   %fluxpass_<catégorie>_<n>_line_<k>%  k-ième ligne de sa description (tâches et progression,
 *                                         format quests-icon d'EcoBattlepass) ; vide au-delà
 *   %fluxpass_<catégorie>_<n>_done%      oui / non
 *   %fluxpass_<catégorie>_<n>_icon%      matériau de la tâche en cours : clé « icon » de la tâche
 *                                         (EcoBattlepass/tasks/...) si présente, sinon déduit de ses
 *                                         filtres (bloc / objet, œuf de l'entité) ou de son déclencheur
 *   %fluxpass_<catégorie>_count%         nombre de missions de la catégorie
 *   %fluxpass_<catégorie>_pending_count% nombre de missions pas encore terminées
 *   %fluxpass_<catégorie>_pending_<k>_<champ>%  champ (name, icon, line_<n>) de la k-ième mission
 *                                         non terminée : les missions faites disparaissent et les
 *                                         restantes se regroupent
 *   %fluxpass_<catégorie>_active%        oui si la catégorie est ouverte (commencée, pas finie)
 *   %fluxpass_tierpage_<pass>_<n>%       page (n paliers par page) qui contient le palier du
 *                                         joueur : sert à ouvrir le chemin du pass au bon endroit
 * </pre>
 * Accès par réflexion à l'API d'EcoBattlepass (aucune dépendance de compilation) ; la
 * description d'une mission est calculée une fois par tick et par joueur.
 */
public final class PassMissions {

    private static final Pattern QUEST = Pattern.compile("^(.+)_(\\d+)_(name|done|icon|line_(\\d+))$");
    private static final Pattern CATEGORY = Pattern.compile("^(.+)_(count|active)$");
    private static final Pattern PENDING = Pattern.compile("^(.+)_pending_(\\d+)_(name|icon|line_(\\d+))$");
    private static final Pattern PENDING_COUNT = Pattern.compile("^(.+)_pending_count$");
    private static final Pattern TIER_PAGE = Pattern.compile("^tierpage_(.+)_(\\d+)$");

    private final Plugin plugin;
    private final Logger logger;
    private final Object categories;
    private final Method valuesSorted, getId, getQuests, isActive, formattedName, formattedLore, hasCompleted;
    private final Method questTasks, taskParent, parentConfig, cfgString, cfgStrings, cfgSubsections, cfgHas;
    private final Map<String, CachedLore> lore = new ConcurrentHashMap<>();
    private boolean warned;

    private record CachedLore(int tick, List<String> lines) {
    }

    private record CachedPending(int tick, List<Integer> indexes) {
    }

    private final Map<String, CachedPending> pending = new ConcurrentHashMap<>();

    private PassMissions(Plugin plugin, Plugin eco) throws ReflectiveOperationException {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        ClassLoader loader = eco.getClass().getClassLoader();
        Class<?> cats = Class.forName("com.exanthiax.ecobattlepass.categories.Categories", true, loader);
        Class<?> category = Class.forName("com.exanthiax.ecobattlepass.categories.Category", true, loader);
        Class<?> quest = Class.forName("com.exanthiax.ecobattlepass.quests.ActiveBattleQuest", true, loader);
        Class<?> api = Class.forName("com.exanthiax.ecobattlepass.api.EcoBattlePassAPIKt", true, loader);
        categories = cats.getField("INSTANCE").get(null);
        valuesSorted = cats.getMethod("valuesSorted");
        getId = category.getMethod("getID");
        getQuests = category.getMethod("getQuests");
        isActive = category.getMethod("isActive");
        formattedName = quest.getMethod("getFormattedName", Player.class);
        formattedLore = quest.getMethod("getFormattedLore", Player.class);
        hasCompleted = api.getMethod("hasCompletedQuest", OfflinePlayer.class, quest);
        Class<?> activeTask = Class.forName("com.exanthiax.ecobattlepass.tasks.ActiveBattleTask", true, loader);
        Class<?> task = Class.forName("com.exanthiax.ecobattlepass.tasks.BattleTask", true, loader);
        Class<?> config = Class.forName("com.willfp.eco.core.config.interfaces.Config", true, loader);
        questTasks = quest.getMethod("getTasks");
        taskParent = activeTask.getMethod("getParent");
        parentConfig = task.getMethod("getConfig");
        cfgString = config.getMethod("getString", String.class);
        cfgStrings = config.getMethod("getStrings", String.class);
        cfgSubsections = config.getMethod("getSubsections", String.class);
        cfgHas = config.getMethod("has", String.class);
    }

    /** Registers the placeholders when EcoBattlepass is there; null otherwise. */
    public static PassMissions register(Plugin plugin) {
        Plugin eco = Bukkit.getPluginManager().getPlugin("EcoBattlepass");
        if (eco == null || !eco.isEnabled()) return null;
        try {
            PassMissions expansion = new PassMissions(plugin, eco);
            // %fluxpass_*%: resolved by Flux in menus, exposed to PlaceholderAPI by Flux
            fr.influencraft.flux.api.FluxApi.registerPlaceholders(plugin, "fluxpass", expansion::onPlaceholderRequest);
            return expansion;
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().warning("Placeholders des missions du pass indisponibles (API EcoBattlepass changée ?) : " + e);
            return null;
        }
    }





    public String onPlaceholderRequest(Player player, String params) {
        try {
            Matcher tp = TIER_PAGE.matcher(params);
            if (tp.matches()) return tierPage(player, tp.group(1), Integer.parseInt(tp.group(2)));
            Matcher pc = PENDING_COUNT.matcher(params);
            if (pc.matches()) {
                if (player == null) return "0";
                Object category = category(pc.group(1));
                return category == null ? "0" : String.valueOf(pending(player, pc.group(1), category).size());
            }
            Matcher pq = PENDING.matcher(params);
            if (pq.matches()) {
                if (player == null) return "";
                Object category = category(pq.group(1));
                if (category == null) return "";
                List<Integer> left = pending(player, pq.group(1), category);
                int k = Integer.parseInt(pq.group(2)) - 1;
                if (k < 0 || k >= left.size()) return "";
                int index = left.get(k);
                Object quest = ((List<?>) getQuests.invoke(category)).get(index);
                return switch (pq.group(3)) {
                    case "name" -> (String) formattedName.invoke(quest, player);
                    case "icon" -> icon(quest);
                    default -> {
                        List<String> lines = lore(player, pq.group(1), index, quest);
                        int n = Integer.parseInt(pq.group(4)) - 1;
                        yield n >= 0 && n < lines.size() ? lines.get(n) : "";
                    }
                };
            }
            Matcher c = CATEGORY.matcher(params);
            Matcher q = QUEST.matcher(params);
            if (q.matches()) {
                if (player == null) return "";
                Object category = category(q.group(1));
                if (category == null) return "";
                List<?> quests = (List<?>) getQuests.invoke(category);
                int index = Integer.parseInt(q.group(2)) - 1;
                if (index < 0 || index >= quests.size()) return "";
                Object quest = quests.get(index);
                return switch (q.group(3)) {
                    case "name" -> (String) formattedName.invoke(quest, player);
                    case "done" -> (boolean) hasCompleted.invoke(null, player, quest) ? "oui" : "non";
                    case "icon" -> icon(quest);
                    default -> {
                        List<String> lines = lore(player, q.group(1), index, quest);
                        int k = Integer.parseInt(q.group(4)) - 1;
                        yield k >= 0 && k < lines.size() ? lines.get(k) : "";
                    }
                };
            }
            if (c.matches()) {
                Object category = category(c.group(1));
                if (category == null) return "";
                return c.group(2).equals("count")
                        ? String.valueOf(((List<?>) getQuests.invoke(category)).size())
                        : (boolean) isActive.invoke(category) ? "oui" : "non";
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!warned) {
                warned = true;
                logger.warning("Placeholder %fluxpass_" + params + "% en échec : " + e);
            }
        }
        return null;
    }

    /** Page du chemin qui contient le palier du joueur (palier 0 = page 1), bornée à la dernière. */
    private static String tierPage(Player player, String pass, int perPage) {
        if (player == null || perPage <= 0) return "1";
        int tier = number(fr.influencraft.flux.api.text.Texts.placeholders("%ecobattlepass_tier_" + pass + "%", player));
        int max = number(fr.influencraft.flux.api.text.Texts.placeholders("%ecobattlepass_" + pass + "_max_tiers%", player));
        int page = Math.max(1, (Math.max(tier, 1) + perPage - 1) / perPage);
        if (max > 0) page = Math.min(page, (max + perPage - 1) / perPage);
        return String.valueOf(page);
    }

    private static int number(String s) {
        try {
            return (int) Math.floor(Double.parseDouble(s.replaceAll("§.", "").trim().replace(',', '.')));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Objet évocateur par déclencheur libreforge, quand la tâche n'a ni « icon » ni filtre utilisable. */
    private static final Map<String, String> TRIGGER_ICONS = Map.ofEntries(
            Map.entry("mine_block", "iron_pickaxe"), Map.entry("kill", "iron_sword"), Map.entry("smelt", "furnace"),
            Map.entry("craft", "crafting_table"), Map.entry("gain_job_xp", "experience_bottle"),
            Map.entry("brew", "brewing_stand"), Map.entry("shear_entity", "shears"), Map.entry("place_block", "bricks"),
            Map.entry("consume", "cooked_beef"), Map.entry("click_entity", "emerald"), Map.entry("catch_fish", "fishing_rod"),
            Map.entry("bow_attack", "bow"), Map.entry("smith_item", "smithing_table"),
            Map.entry("enchant_item", "enchanting_table"), Map.entry("tame_animal", "bone"), Map.entry("move", "leather_boots"),
            Map.entry("gain_xp", "experience_bottle"), Map.entry("breed", "wheat"));

    /** Matériau représentant la (première) tâche en cours de la mission. */
    private String icon(Object quest) throws ReflectiveOperationException {
        List<?> tasks = (List<?>) questTasks.invoke(quest);
        if (tasks.isEmpty()) return "paper";
        Object config = parentConfig.invoke(taskParent.invoke(tasks.get(0)));
        if ((boolean) cfgHas.invoke(config, "icon")) {
            String forced = (String) cfgString.invoke(config, "icon");
            if (isItem(forced)) return forced.toLowerCase(java.util.Locale.ROOT);
        }
        for (Object method : (List<?>) cfgSubsections.invoke(config, "xp-gain-methods")) {
            for (String key : List.of("filters.blocks", "filters.items")) {
                for (Object m : (List<?>) cfgStrings.invoke(method, key)) {
                    String name = String.valueOf(m).replace("minecraft:", "");
                    if (isItem(name)) return name.toLowerCase(java.util.Locale.ROOT);
                }
            }
            for (Object e : (List<?>) cfgStrings.invoke(method, "filters.entities")) {
                String egg = String.valueOf(e).replace("minecraft:", "") + "_spawn_egg";
                if (isItem(egg)) return egg.toLowerCase(java.util.Locale.ROOT);
            }
            String trigger = (String) cfgString.invoke(method, "trigger");
            if (trigger != null && TRIGGER_ICONS.containsKey(trigger)) return TRIGGER_ICONS.get(trigger);
        }
        return "paper";
    }

    private static boolean isItem(String name) {
        if (name == null || name.isBlank()) return false;
        org.bukkit.Material m = org.bukkit.Material.matchMaterial(name.trim());
        return m != null && m.isItem() && !m.isAir();
    }

    /** Index (dans la catégorie) des missions pas encore terminées, calculés une fois par tick. */
    private List<Integer> pending(Player player, String categoryId, Object category) throws ReflectiveOperationException {
        String key = player.getUniqueId() + ":" + categoryId;
        int tick = Bukkit.getCurrentTick();
        CachedPending cached = pending.get(key);
        if (cached != null && cached.tick() == tick) return cached.indexes();
        List<?> quests = (List<?>) getQuests.invoke(category);
        List<Integer> left = new java.util.ArrayList<>();
        for (int i = 0; i < quests.size(); i++) {
            if (!(boolean) hasCompleted.invoke(null, player, quests.get(i))) left.add(i);
        }
        pending.put(key, new CachedPending(tick, left));
        return left;
    }

    private Object category(String id) throws ReflectiveOperationException {
        for (Object category : (Collection<?>) valuesSorted.invoke(categories)) {
            if (id.equalsIgnoreCase((String) getId.invoke(category))) return category;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<String> lore(Player player, String categoryId, int index, Object quest) throws ReflectiveOperationException {
        UUID id = player.getUniqueId();
        String key = id + ":" + categoryId + ":" + index;
        int tick = Bukkit.getCurrentTick();
        CachedLore cached = lore.get(key);
        if (cached != null && cached.tick() == tick) return cached.lines();
        List<String> lines = (List<String>) formattedLore.invoke(quest, player);
        lines.removeIf(l -> l == null || l.replaceAll("§.", "").isBlank());
        lore.put(key, new CachedLore(tick, lines));
        return lines;
    }
}

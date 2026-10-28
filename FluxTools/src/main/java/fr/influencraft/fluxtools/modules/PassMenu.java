package fr.influencraft.fluxtools.modules;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.list.ListEntry;
import fr.influencraft.flux.api.list.Lists;
import fr.influencraft.flux.api.text.Texts;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Missions of the pass menu (EcoBattlepass, read by reflection: no compile dependency), built here and shown by
 * Flux lists; the tiers of the path stay in the menu file with EcoBattlepass's own placeholders.
 * <ul>
 *   <li>{@code pass_categories}: the mission categories ({@code modules.pass-menu.categories}: icon, name,
 *   amount); the one shown glows, a week not started yet shows when it starts</li>
 *   <li>{@code pass_missions}: missions of the category shown, centred on 9 slots (an even count leaves the middle
 *   empty): done, not available yet, or in progress with the item of its task</li>
 * </ul>
 * Action {@code [fluxpass] category <id>} shows a category. Placeholder {@code %fluxpass_tierpage_<pass>_<n>%}: page
 * (n tiers per page) holding the player's tier, to open the path at the right place; {@code %fluxpass_tierstates_<pass>_<first>_<count>%}:
 * colours of the path steps.
 */
final class PassMenu implements Listener {

    static final String CATEGORIES = "pass_categories", MISSIONS = "pass_missions";
    private static final int SLOTS = 9;

    /** A category of the menu (config). */
    private record Shown(String id, String icon, String name, int amount) {
    }

    private final Object categories;
    private final Method valuesSorted, getId, getQuests, isActive, formattedName, formattedLore, hasCompleted;
    private final Method questTasks, taskParent, parentConfig, cfgString, cfgStrings, cfgSubsections, cfgHas;
    private final List<Shown> shown = new ArrayList<>();
    private final Map<UUID, String> selected = new ConcurrentHashMap<>();
    private final Plugin plugin;
    private boolean warned;

    private PassMenu(Plugin plugin, Plugin eco) throws ReflectiveOperationException {
        this.plugin = plugin;
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

    /** Registers the lists when EcoBattlepass is there. */
    static void register(Plugin plugin, ConfigurationSection config) {
        Plugin eco = Bukkit.getPluginManager().getPlugin("EcoBattlepass");
        if (eco == null || !eco.isEnabled()) return;
        PassMenu menu;
        try {
            menu = new PassMenu(plugin, eco);
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().warning("Pass menu missions unavailable (EcoBattlepass API changed?): " + e);
            return;
        }
        ConfigurationSection cats = config == null ? null : config.getConfigurationSection("categories");
        if (cats != null) {
            for (String id : cats.getKeys(false)) {
                menu.shown.add(new Shown(id, cats.getString(id + ".icon", "PAPER"), cats.getString(id + ".name", id),
                        cats.getInt(id + ".amount", 1)));
            }
        }
        FluxApi.registerPlaceholders(plugin, "fluxpass", menu::placeholder);
        FluxApi.registerAction(plugin, "fluxpass", (player, view, argument) -> {
            String[] parts = argument.trim().split("\\s+");
            if (parts.length != 2 || !parts[0].equalsIgnoreCase("category")) return;
            menu.selected.put(player.getUniqueId(), parts[1].toLowerCase(Locale.ROOT));
            Lists.invalidate(player, CATEGORIES);
            Lists.invalidate(player, MISSIONS);
            if (view != null) view.refresh();
        });
        Lists.register(plugin, CATEGORIES, q -> menu.guard(() -> menu.categories(q.viewer())));
        Lists.register(plugin, MISSIONS, q -> menu.guard(() -> menu.missions(q.viewer())));
        Bukkit.getPluginManager().registerEvents(menu, plugin);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        selected.remove(event.getPlayer().getUniqueId());
    }

    private interface Builder {
        List<ListEntry> build() throws ReflectiveOperationException;
    }

    private List<ListEntry> guard(Builder builder) {
        try {
            return builder.build();
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!warned) {
                warned = true;
                plugin.getLogger().warning("Pass menu missions failed: " + e);
            }
            return List.of();
        }
    }

    private String selected(Player player) {
        String id = selected.get(player.getUniqueId());
        return id != null ? id : shown.isEmpty() ? "" : shown.get(0).id();
    }

    // ---------------------------------------------------------------- lists

    private List<ListEntry> categories(Player player) throws ReflectiveOperationException {
        List<ListEntry> out = new ArrayList<>();
        String current = selected(player);
        for (Shown s : shown) {
            Object category = category(s.id());
            if (category == null) continue;
            List<?> quests = (List<?>) getQuests.invoke(category);
            int done = 0;
            for (Object q : quests) if ((boolean) hasCompleted.invoke(null, player, q)) done++;
            Lore lore = Lore.create().kv("Missions terminées", done + "&7/&f" + quests.size());
            if (s.id().equals("quotidiennes")) {
                lore.kv("Renouvellement dans", external(player, "%ecobattlepass_category_" + s.id() + "_reset_timer%"));
            }
            ItemStack item;
            String start = external(player, "%ecobattlepass_category_" + s.id() + "_start_timer%");
            if (s.id().equals(current)) {
                item = item(s.icon(), s.name(), lore.build(), player);
                glow(item);
            } else if (!start.contains("Disponible")) {
                item = item("itemsadder:influitems:lock", s.name(), Lore.create().kv("Débute dans", start).build(), player);
            } else {
                item = item(s.icon(), s.name(), lore.left("Voir les missions").build(), player);
            }
            item.setAmount(Math.max(1, s.amount()));
            out.add(ListEntry.of(s.id()).item(item));
        }
        return out;
    }

    private List<ListEntry> missions(Player player) throws ReflectiveOperationException {
        Object category = category(selected(player));
        if (category == null) return List.of();
        boolean open = (boolean) isActive.invoke(category);
        List<?> quests = (List<?>) getQuests.invoke(category);
        List<ListEntry> missions = new ArrayList<>();
        for (int i = 0; i < quests.size() && i < SLOTS; i++) {
            Object quest = quests.get(i);
            String name = (String) formattedName.invoke(quest, player);
            Lore lore = Lore.create();
            styled(lore, lore(quest, player));
            String icon;
            if ((boolean) hasCompleted.invoke(null, player, quest)) {
                icon = "itemsadder:influitems:accept";
                lore.line(Lore.GREEN + "Terminée.");
            } else if (!open) {
                icon = "itemsadder:influitems:lock";
                lore.ko("Pas encore disponible.");
            } else {
                // the resource of its task (block, item, egg...), to see at a glance what to do
                icon = icon(quest);
                lore.info("En cours.");
            }
            missions.add(ListEntry.of(String.valueOf(i)).item(item(icon, name, lore.build(), player)));
        }
        return centred(missions);
    }

    /** Centred on 9 slots: an odd count side by side, an even count around the empty middle slot. */
    private static List<ListEntry> centred(List<ListEntry> entries) {
        int c = entries.size();
        List<ListEntry> out = new ArrayList<>();
        int first = 4 - c / 2;
        for (int i = 0; i < first; i++) out.add(ListEntry.gap());
        for (int i = 0; i < c; i++) {
            if (c % 2 == 0 && out.size() == 4) out.add(ListEntry.gap());
            out.add(entries.get(i));
        }
        return out;
    }

    // ---------------------------------------------------------------- placeholder

    /**
     * {@code tierstates_<pass>_<first>_<count>}: colour of tiers first to first + count - 1 for the path of the pass
     * (vert free reward claimed, orange reached and waiting, eteint not reached).
     */
    private String tierStates(Player player, String rest) {
        String[] p = rest.split("_");
        if (player == null || p.length < 3) return "";
        int first = number(p[p.length - 2]), count = number(p[p.length - 1]);
        String pass = String.join("_", java.util.Arrays.copyOf(p, p.length - 2));
        int tier = number(external(player, "%ecobattlepass_tier_" + pass + "%"));
        List<String> out = new ArrayList<>();
        for (int t = first; t < first + count; t++) {
            if (t > tier) {
                out.add("eteint");
                continue;
            }
            String state = external(player, "%ecobattlepass_tier_state_" + pass + "_" + t + "%").trim();
            boolean claimed = !state.isEmpty() && !state.equals("NOT_RECEIVED") && !state.equals("RECEIVED_PREMIUM");
            out.add(claimed ? "vert" : "orange");
        }
        return String.join(",", out);
    }

    private String placeholder(Player player, String params) {
        if (params.startsWith("tierstates_")) return tierStates(player, params.substring("tierstates_".length()));
        if (!params.startsWith("tierpage_")) return null;
        String rest = params.substring("tierpage_".length());
        int sep = rest.lastIndexOf('_');
        if (sep < 0 || player == null) return "1";
        int perPage;
        try {
            perPage = Integer.parseInt(rest.substring(sep + 1));
        } catch (NumberFormatException e) {
            return "1";
        }
        String pass = rest.substring(0, sep);
        int tier = number(external(player, "%ecobattlepass_tier_" + pass + "%"));
        int max = number(external(player, "%ecobattlepass_" + pass + "_max_tiers%"));
        int page = Math.max(1, (Math.max(tier, 1) + perPage - 1) / perPage);
        if (max > 0) page = Math.min(page, (max + perPage - 1) / perPage);
        return String.valueOf(page);
    }

    // ---------------------------------------------------------------- helpers

    /** A placeholder of EcoBattlepass (external plugin, through PlaceholderAPI). */
    private static String external(Player player, String placeholder) {
        return Texts.placeholders(placeholder, player);
    }

    private static int number(String s) {
        try {
            return (int) Math.floor(Double.parseDouble(s.replaceAll("§.", "").trim().replace(',', '.')));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static ItemStack item(String material, String name, List<String> lore, Player player) {
        ItemStack item = Items.of(material, name, lore, player);
        return item == null ? new ItemStack(Material.PAPER) : item;
    }

    private static void glow(ItemStack item) {
        var meta = item.getItemMeta();
        if (meta == null) return;
        meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
    }

    /** Item evoking a libreforge trigger, when the task has neither "icon" nor a usable filter. */
    private static final Map<String, String> TRIGGER_ICONS = Map.ofEntries(
            Map.entry("mine_block", "iron_pickaxe"), Map.entry("kill", "iron_sword"), Map.entry("smelt", "furnace"),
            Map.entry("craft", "crafting_table"), Map.entry("gain_job_xp", "experience_bottle"),
            Map.entry("brew", "brewing_stand"), Map.entry("shear_entity", "shears"), Map.entry("place_block", "bricks"),
            Map.entry("consume", "cooked_beef"), Map.entry("click_entity", "emerald"), Map.entry("catch_fish", "fishing_rod"),
            Map.entry("bow_attack", "bow"), Map.entry("smith_item", "smithing_table"),
            Map.entry("enchant_item", "enchanting_table"), Map.entry("tame_animal", "bone"), Map.entry("move", "leather_boots"),
            Map.entry("gain_xp", "experience_bottle"), Map.entry("breed", "wheat"));

    /** Material of the (first) task of the mission: its "icon" key, else its filters, else its trigger. */
    private String icon(Object quest) throws ReflectiveOperationException {
        List<?> tasks = (List<?>) questTasks.invoke(quest);
        if (tasks.isEmpty()) return "paper";
        Object config = parentConfig.invoke(taskParent.invoke(tasks.get(0)));
        if ((boolean) cfgHas.invoke(config, "icon")) {
            String forced = (String) cfgString.invoke(config, "icon");
            if (isItem(forced)) return forced.toLowerCase(Locale.ROOT);
        }
        for (Object method : (List<?>) cfgSubsections.invoke(config, "xp-gain-methods")) {
            for (String key : List.of("filters.blocks", "filters.items")) {
                for (Object m : (List<?>) cfgStrings.invoke(method, key)) {
                    String name = String.valueOf(m).replace("minecraft:", "");
                    if (isItem(name)) return name.toLowerCase(Locale.ROOT);
                }
            }
            for (Object e : (List<?>) cfgStrings.invoke(method, "filters.entities")) {
                String egg = String.valueOf(e).replace("minecraft:", "") + "_spawn_egg";
                if (isItem(egg)) return egg.toLowerCase(Locale.ROOT);
            }
            String trigger = (String) cfgString.invoke(method, "trigger");
            if (trigger != null && TRIGGER_ICONS.containsKey(trigger)) return TRIGGER_ICONS.get(trigger);
        }
        return "paper";
    }

    private static boolean isItem(String name) {
        if (name == null || name.isBlank()) return false;
        Material m = Material.matchMaterial(name.trim());
        return m != null && m.isItem() && !m.isAir();
    }

    private Object category(String id) throws ReflectiveOperationException {
        for (Object category : (Collection<?>) valuesSorted.invoke(categories)) {
            if (id.equalsIgnoreCase((String) getId.invoke(category))) return category;
        }
        return null;
    }

    /** Lines of the mission (tasks and progress, EcoBattlepass quests-icon format), blank lines dropped. */
    @SuppressWarnings("unchecked")
    private static final java.util.regex.Pattern EXPANDED_HEX = java.util.regex.Pattern.compile(
            "&x&(\\p{XDigit})&(\\p{XDigit})&(\\p{XDigit})&(\\p{XDigit})&(\\p{XDigit})&(\\p{XDigit})");
    private static final java.util.regex.Pattern KEY_VALUE = java.util.regex.Pattern.compile("^(.+?)\\s*(?:&[0-9a-fA-F])?\\s*[►▸]\\s*(.*)$");
    private static final java.util.regex.Pattern LEADING_CODES = java.util.regex.Pattern.compile("^(?:&#\\p{XDigit}{6}|&[0-9a-fk-orA-FK-OR])+");
    private static final java.util.regex.Pattern TEXT_COLOUR = java.util.regex.Pattern.compile("(?i)&#B376E5");

    /**
     * EcoBattlepass quest lines in the suite's style: « Key ► value » as key/values, other lines as description (its
     * own text colour in the middle of a line becomes « back to the description colour »).
     */
    private static void styled(Lore lore, List<String> lines) {
        for (String raw : lines) {
            String line = EXPANDED_HEX.matcher(raw.replace('§', '&')).replaceAll("&#$1$2$3$4$5$6").strip();
            java.util.regex.Matcher kv = KEY_VALUE.matcher(line);
            if (kv.matches()) {
                String key = LEADING_CODES.matcher(kv.group(1)).replaceFirst("").strip();
                String value = kv.group(2).startsWith("&f") ? kv.group(2).substring(2) : kv.group(2);
                lore.kv(TEXT_COLOUR.matcher(key).replaceAll(""), value);
            } else {
                String text = TEXT_COLOUR.matcher(line).lookingAt() ? line.substring(8) : line;
                lore.description(TEXT_COLOUR.matcher(text).replaceAll("<base>"));
            }
        }
    }

    private List<String> lore(Object quest, Player player) throws ReflectiveOperationException {
        List<String> lines = new ArrayList<>((List<String>) formattedLore.invoke(quest, player));
        lines.removeIf(l -> l == null || l.replaceAll("§.", "").isBlank());
        return lines;
    }
}

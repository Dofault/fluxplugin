package fr.influencraft.fluxtools.modules;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.jobs.JobAction;
import fr.influencraft.flux.api.jobs.Jobs;
import fr.influencraft.flux.api.list.ListEntry;
import fr.influencraft.flux.api.list.Lists;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.Format;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Careers menu ({@code metiers}): its buttons are built here and shown by Flux lists, the menu file only places
 * them. The job shown is chosen by {@code [fluxjob] <job> reset} (also centres the levels on the player).
 * <ul>
 *   <li>{@code metiers_grille}: the jobs (order, icon and text: {@code modules.jobs.menu} of the config)</li>
 *   <li>{@code metiers_niveaux}: 7 levels around the player's, {@code metiers_niveaux_prec} / {@code _suiv} move them
 *   ({@code [fluxjob] <job> prev|next})</li>
 *   <li>{@code metiers_actions}: paid actions, paged by Flux</li>
 *   <li>{@code metiers_adhesion}: join or leave ({@code [fluxjob] <job> adhesion}), or "no free slot"</li>
 * </ul>
 * Placeholders left for the title and the progress paths of each job page: {@code %fluxjob_<job>_window%} (steps
 * reached in the levels shown), {@code %fluxjob_<job>_xpsteps%} (0 to 9 towards the next level),
 * {@code %fluxjob_<job>_levelglyphs_<c>%} (level written with the {@code chiffre_<n>} glyphs, centred, net advance 0).
 */
final class JobMenu implements Listener {

    static final String GRID = "metiers_grille", LEVELS = "metiers_niveaux", PREV = "metiers_niveaux_prec",
            NEXT = "metiers_niveaux_suiv", ACTIONS = "metiers_actions", MEMBERSHIP = "metiers_adhesion";
    private static final int WINDOW = 7;
    /** Digit "chiffre_<n>": 5 px + shadow = 6 wide, advance 7. */
    private static final int DIGIT_ADVANCE = 7;

    private static final Map<String, Material> CROP_BLOCK_TO_ITEM = Map.of(
            "potatoes", Material.POTATO, "carrots", Material.CARROT, "beetroots", Material.BEETROOT, "cocoa", Material.COCOA_BEANS);
    private static final Map<String, Material> TRIGGER_ICONS = Map.ofEntries(
            Map.entry("mine_block", Material.STONE_PICKAXE), Map.entry("mine_block_cascade", Material.STONE_PICKAXE),
            Map.entry("catch_fish", Material.FISHING_ROD), Map.entry("kill", Material.IRON_SWORD),
            Map.entry("place_block", Material.BRICKS), Map.entry("smelt", Material.FURNACE),
            Map.entry("brew", Material.BREWING_STAND), Map.entry("enchant_item", Material.ENCHANTED_BOOK));

    /** A job of the grid: icon and short description (config). */
    private record Shown(String id, String icon, List<String> description) {
    }

    private final List<Shown> grid = new ArrayList<>();
    private final Map<UUID, String> selected = new ConcurrentHashMap<>();
    /** Window offset chosen by each player, per job (in levels). */
    private final Map<UUID, Map<String, Integer>> offsets = new ConcurrentHashMap<>();

    static void register(Plugin plugin, ConfigurationSection config) {
        JobMenu menu = new JobMenu();
        ConfigurationSection jobs = config == null ? null : config.getConfigurationSection("menu");
        if (jobs != null) {
            for (String id : jobs.getKeys(false)) {
                menu.grid.add(new Shown(id, jobs.getString(id + ".icon", "PAPER"), jobs.getStringList(id + ".description")));
            }
        }
        FluxApi.registerPlaceholders(plugin, "fluxjob", menu::placeholder);
        FluxApi.registerAction(plugin, "fluxjob", (player, view, argument) -> {
            String[] parts = argument.trim().split("\\s+");
            if (parts.length != 2) return;
            menu.act(player, parts[0], parts[1].toLowerCase(Locale.ROOT));
            if (view != null) view.refresh();
        });
        Lists.register(plugin, GRID, q -> menu.grid(q.viewer()));
        Lists.register(plugin, LEVELS, q -> menu.levels(q.viewer()));
        Lists.register(plugin, PREV, q -> menu.arrow(q.viewer(), false));
        Lists.register(plugin, NEXT, q -> menu.arrow(q.viewer(), true));
        Lists.register(plugin, ACTIONS, q -> menu.actions(q.viewer()));
        Lists.register(plugin, MEMBERSHIP, q -> menu.membership(q.viewer()));
        Bukkit.getPluginManager().registerEvents(menu, plugin);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        offsets.remove(event.getPlayer().getUniqueId());
        selected.remove(event.getPlayer().getUniqueId());
    }

    // ---------------------------------------------------------------- actions

    private void act(Player player, String job, String how) {
        switch (how) {
            case "reset" -> {
                selected.put(player.getUniqueId(), job);
                offsets.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>()).remove(job);
                Lists.reset(player, ACTIONS);
            }
            case "prev", "next" -> shift(player, job, how.equals("prev") ? -WINDOW : WINDOW);
            case "adhesion" -> {
                Jobs.Provider jobs = Jobs.get();
                if (jobs.active(player, job)) {
                    Bukkit.dispatchCommand(player, "metier quitter " + job);
                } else if (jobs.canJoin(player, job)) {
                    Bukkit.dispatchCommand(player, "metier rejoindre " + job);
                    if (jobs.active(player, job)) fr.influencraft.flux.api.util.Sounds.cue(player, "complete");
                }
            }
            default -> {
                return;
            }
        }
        for (String list : List.of(GRID, LEVELS, PREV, NEXT, ACTIONS, MEMBERSHIP)) Lists.invalidate(player, list);
    }

    /** Moves the window by 7 levels (within bounds). */
    private void shift(Player player, String job, int delta) {
        Map<String, Integer> mine = offsets.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>());
        int before = windowStart(player, job);
        mine.merge(job, delta, Integer::sum);
        int after = windowStart(player, job);
        if (after == before) mine.merge(job, -delta, Integer::sum);
        else mine.put(job, mine.get(job) - (before + delta - after));
    }

    /** First level of the window: the player's level in 3rd position plus his offset, within [1, max - 6]. */
    private int windowStart(Player player, String job) {
        int max = Math.max(WINDOW, Jobs.get().maxLevel(job));
        int offset = offsets.getOrDefault(player.getUniqueId(), Map.of()).getOrDefault(job, 0);
        int start = Math.max(1, Jobs.get().level(player, job) - 2) + offset;
        return Math.max(1, Math.min(start, max - WINDOW + 1));
    }

    private String job(Player player) {
        String job = selected.get(player.getUniqueId());
        if (job != null) return job;
        return grid.isEmpty() ? null : grid.get(0).id();
    }

    // ---------------------------------------------------------------- lists

    private List<ListEntry> grid(Player player) {
        List<ListEntry> out = new ArrayList<>();
        if (!Jobs.available()) return out;
        Jobs.Provider jobs = Jobs.get();
        String shown = job(player);
        for (Shown s : grid) {
            if (!jobs.exists(s.id())) continue;
            boolean active = jobs.active(player, s.id());
            boolean chosen = s.id().equals(shown);
            Lore lore = levelLore(player, s.id()).description(s.description());
            if (active) lore.ok("Métier actif.");
            else lore.ko("Métier inactif.");
            if (!chosen) lore.left("Voir le métier");
            ItemStack item = item(s.icon(), plainName(s.id()), lore.build(), player);
            decorate(item, jobs.level(player, s.id()), active || chosen);
            out.add(ListEntry.of(s.id()).item(item));
        }
        return out;
    }

    private Lore levelLore(Player player, String job) {
        return Lore.create()
                .kv("Niveau", Jobs.get().level(player, job))
                .kv("Progression", Format.decimal(Jobs.progress(player, job) * 100) + "%");
    }

    private List<ListEntry> levels(Player player) {
        List<ListEntry> out = new ArrayList<>();
        String job = job(player);
        if (job == null || !Jobs.available()) return out;
        Jobs.Provider jobs = Jobs.get();
        int current = jobs.level(player, job);
        int start = windowStart(player, job);
        for (int level = start; level < start + WINDOW; level++) {
            Lore lore = Lore.create();
            lore.items(levelLines(player, job, level));
            String icon;
            if (level < current) {
                icon = "itemsadder:influitems:chest_checkmark";
                lore.ok("Atteint.");
            } else if (level == current) {
                icon = "itemsadder:influitems:stairs";
                lore.kv("XP", Format.decimal(jobs.xp(player, job)) + "&7/&f" + Format.decimal(jobs.xpForLevel(job, level + 1)));
                lore.kv("Progression", Format.decimal(Jobs.progress(player, job) * 100) + "%");
                lore.ok("Ton niveau actuel.");
            } else {
                icon = "itemsadder:influitems:lock";
                lore.ko("Pas encore atteint.");
            }
            ItemStack item = item(icon, "Niveau " + level, lore.build(), player);
            decorate(item, level, level == current);
            out.add(ListEntry.of(String.valueOf(level)).item(item));
        }
        return out;
    }

    /** Arrow moving the levels, only when there are levels on that side. */
    private List<ListEntry> arrow(Player player, boolean next) {
        String job = job(player);
        if (job == null || !Jobs.available()) return List.of();
        int start = windowStart(player, job);
        boolean shown = next ? start + WINDOW - 1 < Jobs.get().maxLevel(job) : start > 1;
        if (!shown) return List.of();
        ItemStack item = item(next ? "itemsadder:influitems:btn_next" : "itemsadder:influitems:btn_prev",
                next ? "Niveaux suivants" : "Niveaux précédents", List.of(), player);
        return List.of(ListEntry.of(job).item(item));
    }

    private List<ListEntry> actions(Player player) {
        List<ListEntry> out = new ArrayList<>();
        String job = job(player);
        if (job == null || !Jobs.available()) return out;
        int i = 0;
        for (JobAction a : Jobs.get().actions(player, job)) {
            Lore lore = Lore.create().kv("XP", Format.decimal(a.xp())).kv("Gain", money(player, a));
            out.add(ListEntry.of(String.valueOf(i++)).item(item(icon(a).name(), actionName(a), lore.build(), player)));
        }
        return out;
    }

    private List<ListEntry> membership(Player player) {
        String job = job(player);
        if (job == null || !Jobs.available()) return List.of();
        Jobs.Provider jobs = Jobs.get();
        ItemStack item;
        String confirm = "";
        if (jobs.active(player, job)) {
            item = item("itemsadder:influitems:deny", "Quitter le métier", Lore.create().description("Ta progression est gardée.")
                    .kv("Coût", jobs.leavePrice(player, job)).left("Double clic pour quitter").build(), player);
            confirm = "&#f0716fClique encore pour quitter le métier.";
        } else if (jobs.canJoin(player, job)) {
            item = item("itemsadder:influitems:accept", "Rejoindre ce métier", Lore.create().description("Commence à gagner des BitBlock avec ce métier.")
                    .kv("Coût", jobs.joinPrice(player, job)).left("Rejoindre").build(), player);
        } else {
            item = item("itemsadder:influitems:lock", "Plus de place", Lore.create().description("Tu exerces déjà le maximum de métiers pour ton rang.")
                    .ko("Monte en rang pour en exercer plus.").build(), player);
        }
        return List.of(ListEntry.of(job).item(item).set("confirm", confirm));
    }

    // ---------------------------------------------------------------- placeholders (title and paths)

    private String placeholder(Player player, String params) {
        if (player == null || !Jobs.available()) return null;
        Jobs.Provider jobs = Jobs.get();
        if (params.endsWith("_window")) {
            String job = params.substring(0, params.length() - "_window".length());
            return String.valueOf(jobs.level(player, job) - windowStart(player, job) + 1);
        }
        if (params.endsWith("_xpsteps")) {
            String job = params.substring(0, params.length() - "_xpsteps".length());
            return String.valueOf(Math.max(0, Math.min(9, (int) Math.floor(Jobs.progress(player, job) * 9))));
        }
        int glyphs = params.lastIndexOf("_levelglyphs_");
        if (glyphs > 0) {
            String digits = String.valueOf(jobs.level(player, params.substring(0, glyphs)));
            int cursor = Integer.parseInt(params.substring(glyphs + "_levelglyphs_".length()));
            int width = digits.length() * DIGIT_ADVANCE - 1;
            int start = (88 - width / 2) - 8; // centred on the window (176 px), from the start of the title
            StringBuilder out = new StringBuilder(":offset_").append(start - cursor).append(':');
            for (char c : digits.toCharArray()) out.append(":chiffre_").append(c).append(':');
            out.append(":offset_").append(cursor - start - digits.length() * DIGIT_ADVANCE).append(':');
            return out.toString();
        }
        return null;
    }

    // ---------------------------------------------------------------- helpers

    /** What reaching a level gives: money, booster (a "stash" command), higher gains per action. */
    private static List<String> levelLines(Player player, String job, int level) {
        Jobs.Provider jobs = Jobs.get();
        List<String> out = new ArrayList<>();
        double money = jobs.levelUpMoney(job, level);
        if (!Double.isNaN(money)) out.add(Lore.GREEN + "+" + amount(money) + Lore.PURPLE + " en atteignant ce niveau");
        for (String command : jobs.levelUpCommands(job, level)) {
            if (command.contains(" stash ")) out.add(Lore.GREEN + "+1 " + Lore.PURPLE + "Booster " + plainName(job) + " &8(/boosters)");
        }
        if (jobs.moneyGrowsWithLevel(job)) out.add("Gains par action plus élevés");
        if (out.isEmpty()) out.add("Aucune récompense");
        return out;
    }

    private static String plainName(String job) {
        return Texts.plain(Texts.colors(Jobs.get().displayName(job)));
    }

    private static ItemStack item(String material, String name, List<String> lore, Player player) {
        ItemStack item = Items.of(material, name, lore, player);
        return item == null ? new ItemStack(Material.PAPER) : item;
    }

    /** Level shown as the amount (1 to 99), glow, no attributes on tools. */
    private static void decorate(ItemStack item, int level, boolean glow) {
        item.setAmount(Math.max(1, Math.min(99, level % 100)));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        if (glow) meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
    }

    private static Material icon(JobAction a) {
        if (a.targets().isEmpty()) return TRIGGER_ICONS.getOrDefault(a.trigger(), Material.PAPER);
        String id = a.targets().get(0).toLowerCase(Locale.ROOT);
        Material mapped = CROP_BLOCK_TO_ITEM.get(id);
        if (mapped != null) return mapped;
        Material material = Material.matchMaterial(id);
        return material == null || !material.isItem() ? Material.BARRIER : material;
    }

    private static String actionName(JobAction a) {
        if (a.targets().isEmpty()) {
            return a.label() != null ? a.label() : JobBlockBreakdownMenu.TRIGGER_LABELS.getOrDefault(a.trigger(), a.trigger());
        }
        Material icon = icon(a);
        return icon == Material.BARRIER ? a.targets().get(0) : "<lang:" + icon.translationKey() + ">";
    }

    /** Money line at the player's level, with the currency of the suite (glyph, never the ₿ character). */
    private static String money(Player player, JobAction a) {
        if (a.money() == null) return "&7Aucun gain d'argent";
        String text = "&7Gagne " + Lore.GREEN + amount(a.money());
        return a.every() <= 1 ? text + "&7 par action" : text + "&7 toutes les " + Lore.GREEN + Format.decimal(a.every()) + " &7actions";
    }

    private static String amount(double value) {
        var currency = fr.influencraft.flux.api.economy.Economy.main();
        return currency == null ? Format.decimal(value) : currency.format(value);
    }
}

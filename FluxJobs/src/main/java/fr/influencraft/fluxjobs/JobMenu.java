package fr.influencraft.fluxjobs;

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
            if (active) lore.ok(Lang.t("menu.active"));
            else lore.ko(Lang.t("menu.inactive"));
            if (!chosen) lore.left(Lang.t("menu.view"));
            ItemStack item = item(s.icon(), plainName(s.id()), lore.build(), player);
            decorate(item, jobs.level(player, s.id()), active || chosen);
            out.add(ListEntry.of(s.id()).item(item));
        }
        return out;
    }

    private Lore levelLore(Player player, String job) {
        return Lore.create()
                .kv(Lang.t("menu.level"), Jobs.get().level(player, job))
                .kv(Lang.t("menu.progress"), Format.decimal(Jobs.progress(player, job) * 100) + "%");
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
                lore.ok(Lang.t("menu.reached"));
            } else if (level == current) {
                icon = "itemsadder:influitems:stairs";
                lore.kv(Lang.t("menu.xp"), Format.decimal(jobs.xp(player, job)) + "&7/&f" + Format.decimal(jobs.xpForLevel(job, level + 1)));
                lore.kv(Lang.t("menu.progress"), Format.decimal(Jobs.progress(player, job) * 100) + "%");
                lore.ok(Lang.t("menu.current"));
            } else {
                icon = "itemsadder:influitems:lock";
                lore.ko(Lang.t("menu.not-reached"));
            }
            ItemStack item = item(icon, Lang.t("menu.level-name", "%level%", String.valueOf(level)), lore.build(), player);
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
                Lang.t(next ? "menu.next-levels" : "menu.previous-levels"), List.of(), player);
        return List.of(ListEntry.of(job).item(item));
    }

    private List<ListEntry> actions(Player player) {
        List<ListEntry> out = new ArrayList<>();
        String job = job(player);
        if (job == null || !Jobs.available()) return out;
        int i = 0;
        for (JobAction a : Jobs.get().actions(player, job)) {
            Lore lore = Lore.create().kv(Lang.t("menu.xp"), Format.decimal(a.xp()) + (a.perHealth() ? Lang.t("actions.per-health") : "")).kv(Lang.t("menu.gain"), money(player, a));
            JobActionLook.Look look = JobActionLook.of(player, a);
            out.add(ListEntry.of(String.valueOf(i++)).item(Items.dress(look.icon(), look.name(), lore.build(), player)));
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
            item = item("itemsadder:fluxui:btn_close_red", Lang.t("menu.leave"), Lore.create().description(Lang.t("menu.leave-description"))
                    .kv(Lang.t("menu.cost"), jobs.leavePrice(player, job)).left(Lang.t("menu.leave-action")).build(), player);
            confirm = Lang.t("menu.leave-confirm");
        } else if (jobs.canJoin(player, job)) {
            item = item("itemsadder:fluxui:btn_add_green", Lang.t("menu.join"), Lore.create().description(Lang.t("menu.join-description"))
                    .kv(Lang.t("menu.cost"), jobs.joinPrice(player, job)).left(Lang.t("menu.join-action")).build(), player);
        } else {
            item = item("itemsadder:fluxui:btn_lock_grey", Lang.t("menu.full"), Lore.create().description(Lang.t("menu.full-description"))
                    .ko(Lang.t("menu.full-hint")).build(), player);
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
        if (!Double.isNaN(money)) out.add(Lang.t("menu.reward-money", "%amount%", amount(money)));
        for (String command : jobs.levelUpCommands(job, level)) {
            if (command.contains(" stash ")) out.add(Lang.t("menu.reward-booster", "%job%", plainName(job)));
        }
        if (jobs.moneyGrowsWithLevel(job)) out.add(Lang.t("menu.reward-rates"));
        if (out.isEmpty()) out.add(Lang.t("menu.reward-none"));
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

    /** Money line at the player's level, with the currency of the suite (glyph, never the ₿ character). */
    private static String money(Player player, JobAction a) {
        return Lang.money(a, amount(a.money() == null ? 0 : a.money()));
    }

    private static String amount(double value) {
        var currency = fr.influencraft.flux.api.economy.Economy.main();
        return currency == null ? Format.decimal(value) : currency.format(value);
    }
}

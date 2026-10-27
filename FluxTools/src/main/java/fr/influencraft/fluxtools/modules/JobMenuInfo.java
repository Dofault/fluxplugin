package fr.influencraft.fluxtools.modules;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.jobs.JobAction;
import fr.influencraft.flux.api.jobs.Jobs;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.Format;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Data of the Careers menu ({@code metiers}), through the jobs service of Flux: placeholders {@code %fluxjob_...%}
 * and action {@code [fluxjob] <job> prev|next|reset} (moves the window of levels).
 * <pre>
 *   %fluxjob_<job>_action_count%          paid actions
 *   %fluxjob_<job>_action_<i>_icon|name|xp|money%
 *   %fluxjob_<job>_lvl_<k>%               level shown at position k (1 to 7) of the window, which follows the player
 *   %fluxjob_<job>_window%                steps reached in that window
 *   %fluxjob_<job>_level_<k>_line_<n>%    n-th line of what this level gives (money, booster, higher gains)
 *   %fluxjob_<job>_lvlmod_<k>%, %fluxjob_<job>_levelmod%   level modulo 100 (item amounts)
 *   %fluxjob_<job>_max%, %fluxjob_<job>_nextlevel%, %fluxjob_<job>_xpsteps% (0 to 9)
 *   %fluxjob_<job>_levelglyphs_<c>%      level written with the "chiffre_<n>" glyphs, centred, net advance 0
 * </pre>
 */
final class JobMenuInfo implements Listener {

    private static final int WINDOW = 7;
    private static final Pattern ACTION = Pattern.compile("^(.+)_action_(\\d+)_(icon|name|xp|money)$");
    private static final Pattern ACTION_COUNT = Pattern.compile("^(.+)_action_count$");
    private static final Pattern LVL = Pattern.compile("^(.+)_lvl_(\\d+)$");
    private static final Pattern WINDOW_PROGRESS = Pattern.compile("^(.+)_window$");
    private static final Pattern LEVEL_LINE = Pattern.compile("^(.+)_level_(\\d+)_line_(\\d+)$");
    private static final Pattern LVL_MOD = Pattern.compile("^(.+)_lvlmod_(\\d+)$");
    private static final Pattern LEVEL_MOD = Pattern.compile("^(.+)_levelmod$");
    private static final Pattern MAX = Pattern.compile("^(.+)_max$");
    private static final Pattern XP_STEPS = Pattern.compile("^(.+)_xpsteps$");
    private static final Pattern NEXT_LEVEL = Pattern.compile("^(.+)_nextlevel$");
    private static final Pattern LEVEL_GLYPHS = Pattern.compile("^(.+)_levelglyphs_(-?\\d+)$");
    /** Digit "chiffre_<n>": 5 px + shadow = 6 wide, advance 7. */
    private static final int DIGIT_ADVANCE = 7;
    private static final String BAR = "&#FFD94D│ ";

    private static final Map<String, Material> CROP_BLOCK_TO_ITEM = Map.of(
            "potatoes", Material.POTATO, "carrots", Material.CARROT, "beetroots", Material.BEETROOT, "cocoa", Material.COCOA_BEANS);
    private static final Map<String, Material> TRIGGER_ICONS = Map.ofEntries(
            Map.entry("mine_block", Material.STONE_PICKAXE), Map.entry("mine_block_cascade", Material.STONE_PICKAXE),
            Map.entry("catch_fish", Material.FISHING_ROD), Map.entry("kill", Material.IRON_SWORD),
            Map.entry("place_block", Material.BRICKS), Map.entry("smelt", Material.FURNACE),
            Map.entry("brew", Material.BREWING_STAND), Map.entry("enchant_item", Material.ENCHANTED_BOOK));

    /** Window offset chosen by each player, per job (in levels). */
    private final Map<UUID, Map<String, Integer>> offsets = new ConcurrentHashMap<>();

    static void register(Plugin plugin) {
        JobMenuInfo info = new JobMenuInfo();
        FluxApi.registerPlaceholders(plugin, "fluxjob", info::request);
        FluxApi.registerAction(plugin, "fluxjob", (player, view, argument) -> {
            String[] parts = argument.trim().split("\\s+");
            if (parts.length != 2) return;
            info.shift(player, parts[0], parts[1]);
            if (view != null) view.refresh();
        });
        Bukkit.getPluginManager().registerEvents(info, plugin);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        offsets.remove(event.getPlayer().getUniqueId());
    }

    private String request(Player player, String params) {
        if (!Jobs.available()) return "";
        Jobs.Provider jobs = Jobs.get();
        Matcher m;
        if ((m = ACTION_COUNT.matcher(params)).matches()) return String.valueOf(jobs.actions(player, m.group(1)).size());
        if ((m = ACTION.matcher(params)).matches()) {
            List<JobAction> list = jobs.actions(player, m.group(1));
            int i = Integer.parseInt(m.group(2)) - 1;
            if (i < 0 || i >= list.size()) return "";
            JobAction a = list.get(i);
            return switch (m.group(3)) {
                case "icon" -> icon(a).name().toLowerCase(java.util.Locale.ROOT);
                case "name" -> name(a);
                case "xp" -> Format.decimal(a.xp());
                default -> money(player, a);
            };
        }
        if (player == null) return "";
        if ((m = MAX.matcher(params)).matches()) return String.valueOf(jobs.maxLevel(m.group(1)));
        if ((m = XP_STEPS.matcher(params)).matches()) {
            return String.valueOf(Math.max(0, Math.min(9, (int) Math.floor(Jobs.progress(player, m.group(1)) * 9))));
        }
        if ((m = LEVEL_GLYPHS.matcher(params)).matches()) {
            String digits = String.valueOf(jobs.level(player, m.group(1)));
            int cursor = Integer.parseInt(m.group(2));
            int width = digits.length() * DIGIT_ADVANCE - 1;
            int start = (88 - width / 2) - 8; // centred on the window (176 px), from the start of the title
            StringBuilder out = new StringBuilder(":offset_").append(start - cursor).append(':');
            for (char c : digits.toCharArray()) out.append(":chiffre_").append(c).append(':');
            out.append(":offset_").append(cursor - start - digits.length() * DIGIT_ADVANCE).append(':');
            return out.toString();
        }
        if ((m = NEXT_LEVEL.matcher(params)).matches()) return String.valueOf(jobs.level(player, m.group(1)) + 1);
        if ((m = LEVEL_MOD.matcher(params)).matches()) return String.valueOf(jobs.level(player, m.group(1)) % 100);
        if ((m = LVL_MOD.matcher(params)).matches()) {
            return String.valueOf((windowStart(player, m.group(1)) + Integer.parseInt(m.group(2)) - 1) % 100);
        }
        if ((m = LVL.matcher(params)).matches()) return String.valueOf(windowStart(player, m.group(1)) + Integer.parseInt(m.group(2)) - 1);
        if ((m = WINDOW_PROGRESS.matcher(params)).matches()) {
            return String.valueOf(jobs.level(player, m.group(1)) - windowStart(player, m.group(1)) + 1);
        }
        if ((m = LEVEL_LINE.matcher(params)).matches()) {
            String job = m.group(1);
            List<String> lines = levelLines(player, job, windowStart(player, job) + Integer.parseInt(m.group(2)) - 1);
            int n = Integer.parseInt(m.group(3)) - 1;
            return n >= 0 && n < lines.size() ? lines.get(n) : "";
        }
        return null;
    }

    /** What reaching a level gives: money, booster (a "stash" command), higher gains per action. */
    private List<String> levelLines(Player player, String job, int level) {
        Jobs.Provider jobs = Jobs.get();
        String name = Texts.plain(Texts.colors(jobs.displayName(job)));
        List<String> phrases = new ArrayList<>();
        double money = jobs.levelUpMoney(job, level);
        if (!Double.isNaN(money)) phrases.add("&#2ECF7B+" + amount(player, money) + "&#B376E5 en atteignant ce niveau");
        for (String command : jobs.levelUpCommands(job, level)) {
            if (command.contains(" stash ")) phrases.add("&#2ECF7B+1 &#B376E5Booster " + name + " &8(/boosters)");
        }
        if (jobs.moneyGrowsWithLevel(job)) phrases.add("&#B376E5Gains par action plus élevés");
        if (phrases.isEmpty()) phrases.add("&#B376E5Aucune récompense");
        List<String> out = new ArrayList<>();
        for (String phrase : phrases) out.addAll(Texts.wrap(BAR + phrase));
        return out;
    }

    /** First level of the window: the player's level in 3rd position plus his offset, within [1, max - 6]. */
    private int windowStart(Player player, String job) {
        int max = Math.max(WINDOW, Jobs.get().maxLevel(job));
        int offset = offsets.getOrDefault(player.getUniqueId(), Map.of()).getOrDefault(job, 0);
        int start = Math.max(1, Jobs.get().level(player, job) - 2) + offset;
        return Math.max(1, Math.min(start, max - WINDOW + 1));
    }

    /** Moves the window by 7 levels ("prev" / "next", within bounds); "reset" centres it on the player. */
    void shift(Player player, String job, String how) {
        Map<String, Integer> mine = offsets.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>());
        if (how.equalsIgnoreCase("reset")) {
            mine.remove(job);
            return;
        }
        int before = windowStart(player, job);
        int delta = how.equalsIgnoreCase("prev") ? -WINDOW : WINDOW;
        mine.merge(job, delta, Integer::sum);
        int after = windowStart(player, job);
        if (after == before) mine.merge(job, -delta, Integer::sum);
        else mine.put(job, mine.get(job) - (before + delta - after));
    }

    private static Material icon(JobAction a) {
        if (a.targets().isEmpty()) return TRIGGER_ICONS.getOrDefault(a.trigger(), Material.PAPER);
        String id = a.targets().get(0).toLowerCase(java.util.Locale.ROOT);
        Material mapped = CROP_BLOCK_TO_ITEM.get(id);
        if (mapped != null) return mapped;
        Material material = Material.matchMaterial(id);
        return material == null || !material.isItem() ? Material.BARRIER : material;
    }

    private static String name(JobAction a) {
        if (a.targets().isEmpty()) {
            return a.label() != null ? a.label() : JobBlockBreakdownMenu.TRIGGER_LABELS.getOrDefault(a.trigger(), a.trigger());
        }
        Material icon = icon(a);
        return icon == Material.BARRIER ? a.targets().get(0) : "lang:" + icon.translationKey();
    }

    /** Money line at the player's level, with the currency of the suite (glyph, never the ₿ character). */
    private static String money(Player player, JobAction a) {
        if (a.money() == null) return "&7Aucun gain d'argent";
        String text = "&7Gagne &#2ECF7B" + amount(player, a.money());
        return a.every() <= 1 ? text + "&7 par action" : text + "&7 toutes les &#2ECF7B" + Format.decimal(a.every()) + " &7actions";
    }

    private static String amount(Player player, double value) {
        var currency = fr.influencraft.flux.api.economy.Economy.main();
        return currency == null ? Format.decimal(value) : currency.format(value);
    }
}

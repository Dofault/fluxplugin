package fr.influencraft.fluxcalendar;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.TitleOverlay;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.list.ListEntry;
import fr.influencraft.flux.api.list.Lists;
import fr.influencraft.flux.api.reward.ClaimResult;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.Sounds;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The calendar menu ({@code menus/calendrier.yml}, {@code /calendrier}): its parts are built here, the menu file only
 * places them.
 * <ul>
 *   <li>{@code calendrier_jours}: the 42 cases of the month's weeks (Monday first), a day's number shown by the item
 *   count; only today's case claims ({@code [fluxcalendar] claim});</li>
 *   <li>{@code calendrier_info} (menu icon), {@code calendrier_aujourdhui} (today's reward, shown only: the player
 *   claims it on the calendar), {@code calendrier_stats} (this month, next big reward);</li>
 *   <li>{@code %fluxcalendar_titre%}: the month and year drawn on the calendar's header (pack {@code fluxcalendar},
 *   widths in {@code glyphs.yml}, {@code assets/calendrier/gen_fond.py}).</li>
 * </ul>
 */
final class CalendarMenu {

    static final String DAYS = "calendrier_jours";
    static final String INFO = "calendrier_info";
    static final String TODAY = "calendrier_aujourdhui";
    static final String STATS = "calendrier_stats";
    private static final int CASES = 42;
    private static final String MARK = "itemsadder:fluxcalendar:";
    private static final String EMPTY = "itemsadder:influitems:invisible";
    private static final Locale FRENCH = Locale.FRENCH;

    private final FluxCalendar plugin;
    private final LoginCalendar calendar;
    private YamlConfiguration glyphs = new YamlConfiguration();

    CalendarMenu(FluxCalendar plugin, LoginCalendar calendar) {
        this.plugin = plugin;
        this.calendar = calendar;
    }

    void enable() {
        reload();
        Lists.register(plugin, DAYS, q -> days(q.viewer()));
        Lists.register(plugin, INFO, q -> List.of(info(q.viewer())));
        Lists.register(plugin, TODAY, q -> List.of(today(q.viewer())));
        Lists.register(plugin, STATS, q -> stats(q.viewer()));
        FluxApi.registerAction(plugin, "fluxcalendar", (player, view, argument) -> {
            if (argument.trim().equalsIgnoreCase("claim")) claim(player);
        });
    }

    void reload() {
        glyphs = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "glyphs.yml"));
    }

    private void claim(Player player) {
        ClaimResult result = calendar.claimToday(player);
        Sounds.cue(player, result.delivered() > 0 ? "complete" : "error");
        for (String list : List.of(DAYS, INFO, TODAY, STATS)) Lists.invalidate(player, list);
    }

    // ------------------------------------------------------------------ title

    /** Month and year centred on the header (title text: cursor at the window's left edge before and after). */
    String title() {
        LocalDate today = calendar.today();
        String month = "mois_" + today.getMonthValue();
        String year = String.valueOf(today.getYear());
        int total = glyphs.getInt(month) + glyphs.getInt("space", 4);
        for (char c : year.toCharArray()) total += glyphs.getInt("chiffre_" + c);
        int x = glyphs.getInt("centre", 88) - total / 2;
        TitleOverlay out = new TitleOverlay("", 0);
        out.add("fluxcalendar:" + month, x, glyphs.getInt(month) - 1, "&f");
        x += glyphs.getInt(month) + glyphs.getInt("space", 4);
        for (char c : year.toCharArray()) {
            int w = glyphs.getInt("chiffre_" + c);
            out.add("fluxcalendar:chiffre_" + c, x, w - 1, "&f");
            x += w;
        }
        return out.moveTo(0).resolved();
    }

    // ------------------------------------------------------------------ the month

    private List<ListEntry> days(Player player) {
        LocalDate today = calendar.today();
        LocalDate first = today.withDayOfMonth(1);
        int offset = first.getDayOfWeek().getValue() - 1; // Monday = 0
        int length = today.lengthOfMonth();
        List<ListEntry> out = new ArrayList<>(CASES);
        for (int i = 0; i < offset; i++) out.add(ListEntry.gap());
        for (int day = 1; day <= length; day++) out.add(day(player, first.withDayOfMonth(day)));
        while (out.size() < CASES) out.add(ListEntry.gap());
        return out;
    }

    private ListEntry day(Player player, LocalDate date) {
        int day = date.getDayOfMonth();
        DayReward reward = calendar.reward(day);
        LoginCalendar.State state = calendar.state(player, day);
        Lore lore = Lore.create();
        if (reward != null) lore.items(placeholders(player, reward.lore()));
        String icon = EMPTY;
        String click = "[sound] @error";
        boolean glow = false;
        switch (state) {
            case CLAIMED -> {
                icon = MARK + "jour_recupere";
                lore.ok("Récupérée.");
            }
            case TODAY -> {
                if (reward == null || reward.commands().isEmpty()) {
                    lore.info("Pas de récompense aujourd'hui.");
                } else {
                    icon = MARK + "jour_cadeau";
                    glow = true;
                    lore.ok("Aujourd'hui ! À récupérer avant minuit.").left("Récupérer");
                    click = "[fluxcalendar] claim";
                }
            }
            case MISSED -> {
                icon = MARK + "jour_manque";
                lore.ko("Manquée : pas récupérée ce jour-là.");
            }
            case BEFORE_ARRIVAL -> lore.info("Tu n'étais pas encore arrivé sur le serveur.");
            case TO_COME -> {
                if (reward != null && reward.big()) icon = MARK + "jour_bonus";
                int in = day - calendar.today().getDayOfMonth();
                lore.info(in == 1 ? "Demain." : "Dans " + in + " jours.");
            }
        }
        // the game shows no count of 1: the 1st has its « 1 » drawn on its icon
        if (day == 1) icon = icon.equals(EMPTY) ? MARK + "jour_un" : icon + "_un";
        return entry(player, "jour:" + day, icon, dayName(date), lore, day, glow).set("clic", click);
    }

    // ------------------------------------------------------------------ around the calendar

    private ListEntry info(Player player) {
        Lore lore = Lore.create().description(
                "Une récompense par jour du mois : clique sur la date du jour pour la récupérer, avant minuit.",
                "Les cadeaux marquent les grosses récompenses. Le calendrier repart à zéro le 1er de chaque mois.");
        return entry(player, "info", "itemsadder:influitems:reward", "Calendrier", lore, 1, false);
    }

    private ListEntry today(Player player) {
        LocalDate date = calendar.today();
        DayReward reward = calendar.reward(date.getDayOfMonth());
        Lore lore = Lore.create();
        if (reward != null) lore.items(placeholders(player, reward.lore()));
        String icon;
        if (calendar.claimable(player)) {
            icon = MARK + "jour_cadeau";
            lore.info("Clique sur le " + date.getDayOfMonth() + " dans le calendrier pour la récupérer.");
        } else if (calendar.state(player, date.getDayOfMonth()) == LoginCalendar.State.CLAIMED) {
            icon = MARK + "jour_recupere";
            lore.ok("Déjà récupérée, reviens demain !");
        } else {
            icon = EMPTY;
            lore.info("Pas de récompense aujourd'hui, reviens demain !");
        }
        return entry(player, "aujourdhui", icon, "Récompense du jour", lore, 1, calendar.claimable(player));
    }

    private List<ListEntry> stats(Player player) {
        LocalDate today = calendar.today();
        PlayerData d = calendar.data(player.getUniqueId());
        Lore month = Lore.create()
                .kv("Jours récupérés", calendar.claimedCount(player) + "&7/&f" + today.lengthOfMonth())
                .kv("Jours de connexion", d == null ? 0 : d.days);
        List<ListEntry> out = new ArrayList<>();
        out.add(entry(player, "mois", MARK + "jour_recupere", "Ce mois-ci", month, 1, false));
        Lore next = Lore.create();
        LocalDate big = null;
        for (int day = today.getDayOfMonth() + 1; day <= today.lengthOfMonth() && big == null; day++) {
            DayReward r = calendar.reward(day);
            if (r != null && r.big()) {
                big = today.withDayOfMonth(day);
                next.items(placeholders(player, r.lore()));
            }
        }
        if (big == null) next.info("Plus de grosse récompense ce mois-ci : rendez-vous le 1er !");
        else next.kv("Le", dayName(big)).info(big.getDayOfMonth() - today.getDayOfMonth() == 1 ? "Demain."
                : "Dans " + (big.getDayOfMonth() - today.getDayOfMonth()) + " jours.");
        out.add(entry(player, "prochaine", MARK + "jour_bonus", "Prochaine grosse récompense", next, 1, false));
        return out;
    }

    // ------------------------------------------------------------------ helpers

    /** « Jeudi 29 octobre ». */
    static String dayName(LocalDate date) {
        String weekday = date.getDayOfWeek().getDisplayName(TextStyle.FULL, FRENCH);
        return Character.toUpperCase(weekday.charAt(0)) + weekday.substring(1) + " " + date.getDayOfMonth() + " "
                + date.getMonth().getDisplayName(TextStyle.FULL, FRENCH);
    }

    private static ListEntry entry(Player player, String id, String icon, String name, Lore lore, int amount, boolean glow) {
        ItemStack item = Items.of(icon, name, lore.build(), player);
        if (item == null) item = new ItemStack(Material.PAPER);
        item.setAmount(Math.max(1, Math.min(99, amount)));
        if (glow) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setEnchantmentGlintOverride(true);
                item.setItemMeta(meta);
            }
        }
        return ListEntry.of(id).item(item);
    }

    private static List<String> placeholders(Player player, List<String> lines) {
        List<String> out = new ArrayList<>(lines.size());
        for (String l : lines) out.add(Texts.placeholders(l, player));
        return out;
    }
}

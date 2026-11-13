package fr.influencraft.fluxcalendar;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.welcome.JoinScreens;
import fr.influencraft.flux.api.util.Format;
import fr.influencraft.flux.api.util.Players;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.Locale;

/**
 * FluxCalendar: the login calendar ({@code /calendrier}, {@link LoginCalendar}, {@link CalendarMenu}). The first
 * login of a day opens the calendar by itself when today's reward waits, through the join screens of Flux (after
 * the resource pack and the screens before it, such as the questionnaire of a new player); the player claims it by
 * clicking today's date.
 *
 * <pre>
 *   /calendrier                                              (players)
 *   /fluxcalendar reset &lt;player&gt; [all] | reload             (admin)
 * </pre>
 */
public final class FluxCalendar extends FluxPlugin implements Listener {

    private static final String MENU = "calendrier";

    private LoginCalendar calendar;
    private CalendarMenu menu;

    @Override
    protected List<String> formerNames() {
        return List.of("FluxRewards", "InfluRecompenses");
    }

    @Override
    protected void enable() {
        calendar = new LoginCalendar(this);
        calendar.load();
        menu = new CalendarMenu(this, calendar);
        menu.enable();
        placeholders("fluxcalendar", this::placeholder);
        command("calendar")
                .aliases("calendrier")
                .description("Daily login rewards: one per day of the month.")
                .executor((sender, label, args) -> {
                    Player p = Players.of(sender);
                    if (p != null) FluxApi.open(p, MENU);
                    return true;
                })
                .register();
        command("fluxcalendar")
                .description("Login calendar administration.")
                .permission("fluxcalendar.admin")
                .usage(messages().raw("admin-usage"))
                .executor(this::onAdmin)
                .completer((sender, args) -> args.length == 1 ? List.of("reset", "reload")
                        : args.length == 2 ? Players.complete(args[1]) : args.length == 3 ? List.of("all") : List.of())
                .register();
        Bukkit.getPluginManager().registerEvents(this, this);
        for (Player p : Bukkit.getOnlinePlayers()) arrive(p);
        // midnight for players staying online
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (calendar.countDay(p) && calendar.claimable(p)) messages().send(p, "new-day");
            }
        }, 1200L, 1200L);
    }

    @Override
    protected void disable() {
        if (calendar != null) calendar.saveAll();
    }

    @Override
    public void reload() {
        super.reload();
        calendar.load();
        menu.reload();
    }

    /** Loads the player; true when this login starts a new day. */
    private boolean arrive(Player p) {
        calendar.load(p.getUniqueId());
        return calendar.countDay(p);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!arrive(p) || !calendar.claimable(p)) return;
        // after the screens shown before it (the questionnaire of a new player), once the resource pack is there
        JoinScreens.request(p, 100, (player, done) -> {
            if (calendar.claimable(player)) FluxApi.open(player, MENU);
            done.run();
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        calendar.unload(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ admin command

    private boolean onAdmin(CommandSender sender, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (sub.equals("reload")) {
            reload();
            messages().send(sender, "reloaded");
            return true;
        }
        Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;
        if (!sub.equals("reset") || target == null) {
            messages().send(sender, "admin-usage");
            return true;
        }
        calendar.reset(target, args.length > 2 && args[2].equalsIgnoreCase("all"));
        messages().send(sender, "admin-reset", "%player%", target.getName());
        return true;
    }

    // ------------------------------------------------------------------ placeholders

    /**
     * {@code %fluxcalendar_...%}: {@code titre} (month and year of the menu's header, title glyphs), {@code jour}
     * (today's date), {@code recuperes} (dates claimed this month), {@code jours_mois} (days of the month),
     * {@code dispo} (today's reward waits).
     */
    private String placeholder(Player player, String params) {
        switch (params) {
            case "titre" -> {
                return menu.title();
            }
            case "jour" -> {
                return String.valueOf(calendar.today().getDayOfMonth());
            }
            case "jours_mois" -> {
                return String.valueOf(calendar.today().lengthOfMonth());
            }
            default -> {
            }
        }
        if (player == null) return "";
        return switch (params) {
            case "recuperes" -> String.valueOf(calendar.claimedCount(player));
            case "dispo" -> Format.yesNo(calendar.claimable(player));
            default -> null;
        };
    }
}

package fr.influencraft.fluxtournament;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.effects.Effects;
import fr.influencraft.flux.api.leaderboard.Leaderboards;
import fr.influencraft.flux.api.util.Format;
import fr.influencraft.flux.api.util.Players;
import fr.influencraft.flux.api.value.Values;
import fr.influencraft.fluxtournament.Plan.Group;
import fr.influencraft.fluxtournament.Plan.Kind;
import fr.influencraft.fluxtournament.Run.Score;
import fr.influencraft.fluxtournament.TournamentStore.Stats;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * FluxTournament: short tournaments every half hour on the jobs of the server (whatever the job: fishing, mining,
 * farming...). What counts and what it is worth come from FluxValue (the job's own paid actions and weights), so a
 * new job or a new weight makes new tournaments without a line to write. Four kinds: points of the whole job,
 * one resource drawn at random, a race to an amount, the most different resources. The podium and the players who
 * took a real part are paid (money in seconds of the reference income), tournament points feed the leaderboard
 * {@code tournois} of Flux, every tournament is kept in the history of the menu.
 *
 * <pre>
 *   /tournoi                                       menu (current tournament, live ranking, history, totals)
 *   /fluxtournament start [kind] [job] [resource] | stop | cancel | next | preview [job] | reload   (admin)
 * </pre>
 * Placeholders {@code %fluxtournament_active|name|time|place|score|leader|next%}.
 */
public final class FluxTournament extends FluxPlugin implements Listener {

    private static final String MENU = "tournoi";

    private final TournamentStore store = new TournamentStore(this);
    private final TournamentService service = new TournamentService(this, store);
    private final TournamentMenu menu = new TournamentMenu(this, service, store);
    private int task = -1;

    @Override
    protected void enable() {
        store.open();
        settings();
        menu.register();
        Values.onChange(() -> Bukkit.getScheduler().runTask(this, () -> service.planner.configure(getConfig())));
        Leaderboards.register(this, TournamentMenu.BOARD, p -> {
            Stats s = store.stats(p.getUniqueId());
            return s == null || s.points <= 0 ? null : (double) s.points;
        }, v -> messages().raw("leaderboard-value", "{n}", Format.number(v)));
        Leaderboards.name(TournamentMenu.BOARD, messages().raw("leaderboard-name"));
        placeholders("fluxtournament", this::placeholder);
        command("tournament")
                .aliases("tournoi", "tournois")
                .description("Tournaments: the current one, the live ranking, the last ones.")
                .permission("fluxtournament.use")
                .executor((sender, label, args) -> {
                    Player p = Players.of(sender);
                    if (p != null && !FluxApi.open(p, MENU)) messages().send(p, "no-menu");
                    return true;
                })
                .register();
        command("fluxtournament")
                .description("Tournament administration.")
                .permission("fluxtournament.admin")
                .suggest(false)
                .usage(messages().raw("admin-usage"))
                .executor(this::onAdmin)
                .completer(this::complete)
                .register();
        Bukkit.getPluginManager().registerEvents(this, this);
        // counting needs the effect engine, which may start after the suite
        Effects.whenReady(this, () -> {
            if (task == -1) task = Bukkit.getScheduler().runTaskTimer(this, service::tick, 20L, 20L).getTaskId();
        });
    }

    @Override
    protected void disable() {
        service.stop();
    }

    @Override
    public void reload() {
        super.reload();
        settings();
        Leaderboards.name(TournamentMenu.BOARD, messages().raw("leaderboard-name"));
    }

    private void settings() {
        service.configure(getConfig());
        store.historySize(getConfig().getInt("history-size", 50));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        service.hide(e.getPlayer());
    }

    // ---------------------------------------------------------------- admin

    private boolean onAdmin(CommandSender sender, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (sub) {
            case "start" -> {
                if (service.current != null || service.drawing != null) {
                    messages().send(sender, "admin-running");
                    return true;
                }
                Plan plan;
                if (args.length >= 3) {
                    Kind kind = Kind.of(args[1]);
                    plan = kind == null ? null : service.planner.plan(kind, args[2].toLowerCase(Locale.ROOT), args.length > 3 ? args[3] : null);
                } else if (args.length == 2) {
                    Kind kind = Kind.of(args[1]);
                    plan = null;
                    if (kind != null) {
                        for (String job : service.planner.jobs(kind)) {
                            plan = service.planner.plan(kind, job, null);
                            if (plan != null) break;
                        }
                    }
                } else {
                    plan = service.announced != null ? service.announced : service.planner.draw();
                }
                if (plan == null) {
                    messages().send(sender, "admin-no-plan");
                    return true;
                }
                if (!service.start(plan)) messages().send(sender, "admin-not-started");
                else messages().send(sender, "admin-started", "{name}", service.name(plan));
            }
            case "stop" -> {
                if (service.current == null) messages().send(sender, "admin-none");
                else service.end(false);
            }
            case "cancel" -> {
                if (service.current == null) messages().send(sender, "admin-none");
                else service.end(true);
            }
            case "next" -> {
                long in = Math.max(0, (service.nextStart - System.currentTimeMillis()) / 1000);
                messages().send(sender, "admin-next", "{time}", service.scheduled ? Format.duration(in) : "-",
                        "{name}", service.announced == null ? "?" : service.name(service.announced));
            }
            case "preview" -> preview(sender, args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : null);
            case "reload" -> {
                reload();
                messages().send(sender, "reloaded");
            }
            case "status" -> status(sender);
            case "draw" -> {
                // the draw animation shown to the sender alone: no tournament starts
                Player p = Players.of(sender);
                Plan plan = service.planner.draw();
                if (p == null || plan == null) {
                    messages().send(sender, "admin-usage");
                    return true;
                }
                messages().send(p, "admin-draw");
                service.show(List.of(p), plan, () -> messages().send(p, "admin-draw-done", "{name}", service.name(plan)));
            }
            default -> messages().send(sender, "admin-usage");
        }
        return true;
    }

    /** What every job can hold: kinds allowed, resources a resource or a race may draw (and the race amount). */
    private void preview(CommandSender sender, String only) {
        Planner p = service.planner;
        List<String> jobs = only != null ? List.of(only) : Values.jobs();
        for (String job : jobs) {
            List<Group> groups = p.groups(job);
            List<String> kinds = new ArrayList<>();
            for (Kind k : Kind.values()) if (p.jobs(k).contains(job) && p.plan(k, job, null) != null) kinds.add(k.id());
            sender.sendMessage("§e" + job + " §7(" + groups.size() + " resources) §fkinds: " + (kinds.isEmpty() ? "§cnone" : String.join(", ", kinds)));
            if (only == null) continue;
            Planner.KindSettings race = p.settings(Kind.RACE);
            for (Group g : groups) {
                sender.sendMessage("§7  " + g.key() + " §8(" + g.sources().size() + " source(s), " + Math.round(g.points() * 100) / 100.0
                        + " pts/unit)" + (race != null ? " §7race §f" + p.raceAmount(race, g) : ""));
            }
        }
    }

    private void status(CommandSender sender) {
        Run run = service.current;
        if (run == null) {
            messages().send(sender, "admin-none");
            return;
        }
        sender.sendMessage("§e" + run.plan.kind().id() + " " + run.plan.job() + (run.plan.target() == null ? "" : " " + run.plan.target().key())
                + " §7counters: §f" + run.counters.size() + " §7ends in §f" + TournamentService.clock((run.ends - System.currentTimeMillis()) / 1000));
        int i = 1;
        for (Score s : run.ranking()) sender.sendMessage("§7 " + i++ + ". §f" + s.name + " §7" + s.value);
    }

    private List<String> complete(CommandSender sender, String[] args) {
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> options = List.of();
        if (args.length == 1) options = List.of("start", "stop", "cancel", "next", "preview", "status", "draw", "reload");
        else if (args.length == 2 && args[0].equalsIgnoreCase("start")) options = Arrays.stream(Kind.values()).map(Kind::id).toList();
        else if (args.length == 2 && args[0].equalsIgnoreCase("preview")) options = Values.jobs();
        else if (args.length == 3 && args[0].equalsIgnoreCase("start")) {
            Kind k = Kind.of(args[1]);
            options = k == null ? List.of() : service.planner.jobs(k);
        } else if (args.length == 4 && args[0].equalsIgnoreCase("start")) {
            options = service.planner.groups(args[2].toLowerCase(Locale.ROOT)).stream()
                    .map(g -> g.key().substring(g.key().indexOf('/') + 1)).toList();
        }
        return Stream.of(options.toArray(new String[0])).filter(s -> s.toLowerCase(Locale.ROOT).startsWith(last)).toList();
    }

    // ---------------------------------------------------------------- placeholders

    private String placeholder(Player player, String params) {
        Run run = service.current;
        long now = System.currentTimeMillis();
        return switch (params) {
            case "active" -> run != null ? "true" : "false";
            case "name" -> run != null ? service.name(run.plan) : "";
            case "time" -> run != null ? TournamentService.clock((run.ends - now) / 1000) : "";
            case "leader" -> run == null || run.ranking().isEmpty() ? "" : run.ranking().get(0).name;
            case "next" -> service.scheduled && service.nextStart > 0 ? TournamentService.clock((service.nextStart - now) / 1000) : "";
            case "place" -> run == null || player == null ? "" : String.valueOf(run.rank(player.getUniqueId()));
            case "score" -> {
                Score s = run == null || player == null ? null : run.scores.get(player.getUniqueId());
                yield s == null ? "" : service.score(run.plan, s.value);
            }
            default -> null;
        };
    }
}

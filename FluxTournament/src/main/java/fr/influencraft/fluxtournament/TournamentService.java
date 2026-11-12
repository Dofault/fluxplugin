package fr.influencraft.fluxtournament;

import fr.influencraft.flux.api.draw.Draws;
import fr.influencraft.flux.api.economy.Economy;
import fr.influencraft.flux.api.economy.Origin;
import fr.influencraft.flux.api.effects.Effects;
import fr.influencraft.flux.api.jobs.Jobs;
import fr.influencraft.flux.api.list.Lists;
import fr.influencraft.flux.api.reward.Reward;
import fr.influencraft.flux.api.reward.Rewards;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.universe.Universes;
import fr.influencraft.flux.api.util.Format;
import fr.influencraft.flux.api.util.Sounds;
import fr.influencraft.flux.api.value.Values;
import fr.influencraft.flux.api.value.Values.JobSource;
import fr.influencraft.fluxtournament.Plan.Group;
import fr.influencraft.fluxtournament.Plan.Kind;
import fr.influencraft.fluxtournament.Run.Score;
import fr.influencraft.fluxtournament.TournamentStore.PastRun;
import fr.influencraft.fluxtournament.TournamentStore.Result;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs the tournaments: the schedule (one every {@code every-minutes}, aligned on the clock, announced a little
 * before), the counting of actions (Flux's effect engine, the job's own counters from FluxValue), the boss bar, the
 * end (ranking, rewards, tournament points, history).
 */
final class TournamentService {

    /** What a place pays: seconds of the reference income (FluxValue), tournament points, rewards to claim. */
    record Prize(double seconds, int points, List<Reward> rewards) {
    }

    static final String LIVE = "tournoi_direct", CURRENT = "tournoi_actuel", HISTORY = "tournoi_historique", STATS = "tournoi_stats";

    private final FluxTournament plugin;
    private final TournamentStore store;
    final Planner planner = new Planner();

    // settings
    boolean scheduled = true;
    private int everyMinutes = 30, offsetMinutes = 0;
    long durationMs = 600_000;
    private long announceMs = 60_000;
    private int minOnline = 2, minParticipants = 2;
    private final Map<Integer, Prize> prizes = new HashMap<>();
    private Prize participation;
    private double participationShare = 0.2;
    private double fallbackMoneyPerSecond = 5;
    private boolean bossbar = true;
    private BossBar.Color barColor = BossBar.Color.BLUE;
    private long rankingEveryMs = 300_000;
    private boolean drawEnabled = true;
    private int drawChoices = 8;

    // state
    Run current;
    Plan announced;
    /** Being drawn (animation playing): starts when it ends. */
    Plan drawing;
    long nextStart;
    private final Map<UUID, BossBar> bars = new ConcurrentHashMap<>();

    TournamentService(FluxTournament plugin, TournamentStore store) {
        this.plugin = plugin;
        this.store = store;
    }

    void configure(ConfigurationSection c) {
        scheduled = c.getBoolean("schedule.enabled", true);
        everyMinutes = Math.max(5, c.getInt("schedule.every-minutes", 30));
        offsetMinutes = Math.floorMod(c.getInt("schedule.offset-minutes", 0), everyMinutes);
        durationMs = Math.max(1, c.getInt("schedule.duration-minutes", 10)) * 60_000L;
        announceMs = Math.max(0, c.getInt("schedule.announce-seconds", 60)) * 1000L;
        minOnline = Math.max(1, c.getInt("schedule.min-online", 2));
        minParticipants = Math.max(1, c.getInt("min-participants", 2));
        planner.configure(c);
        prizes.clear();
        ConfigurationSection ranks = c.getConfigurationSection("rewards.ranks");
        if (ranks != null) {
            for (String k : ranks.getKeys(false)) {
                try {
                    prizes.put(Integer.parseInt(k), prize(ranks.getConfigurationSection(k), "rank_" + k));
                } catch (NumberFormatException e) {
                    plugin.getLogger().warning("rewards.ranks." + k + ": the key must be a place (1, 2, 3...)");
                }
            }
        }
        if (prizes.isEmpty()) plugin.getLogger().warning("rewards.ranks is empty: the podium wins nothing");
        participation = prize(c.getConfigurationSection("rewards.participation"), "participation");
        participationShare = Math.max(0, c.getDouble("rewards.participation.min-share", 0.2));
        fallbackMoneyPerSecond = c.getDouble("rewards.fallback-money-per-second", 5);
        bossbar = c.getBoolean("bossbar.enabled", true);
        try {
            barColor = BossBar.Color.valueOf(c.getString("bossbar.color", "BLUE").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            barColor = BossBar.Color.BLUE;
        }
        // YELLOW is drawn transparent by the server's resource pack
        if (barColor == BossBar.Color.YELLOW) barColor = BossBar.Color.BLUE;
        rankingEveryMs = Math.max(0, c.getInt("ranking-every-minutes", 5)) * 60_000L;
        drawEnabled = c.getBoolean("draw.enabled", true);
        drawChoices = Math.max(2, c.getInt("draw.choices", 8));
        nextStart = 0;
    }

    private Prize prize(ConfigurationSection s, String key) {
        if (s == null) return new Prize(0, 0, List.of());
        List<Reward> rewards = new ArrayList<>();
        ConfigurationSection rs = s.getConfigurationSection("rewards");
        if (rs != null) {
            for (String id : rs.getKeys(false)) {
                ConfigurationSection r = rs.getConfigurationSection(id);
                if (r != null) rewards.add(Reward.fromConfig(r, "tournament", id).key(key + ":" + id));
            }
        }
        return new Prize(Math.max(0, s.getDouble("seconds", 0)), Math.max(0, s.getInt("points", 0)), List.copyOf(rewards));
    }

    int podium() {
        Planner.KindSettings s = planner.settings(Kind.RACE);
        return s == null ? 3 : s.podium();
    }

    // ---------------------------------------------------------------- schedule

    /** Called every second. */
    void tick() {
        long now = System.currentTimeMillis();
        if (drawing != null) return;
        if (current != null) {
            if (now >= current.ends || current.plan.kind() == Kind.RACE && current.finishers() >= current.podium) {
                end(false);
            } else {
                updateBars(now);
                rankingInChat(current, now);
            }
            return;
        }
        if (!scheduled) return;
        if (nextStart == 0 || nextStart < now - 5000) nextStart = next(now);
        if (announced == null && announceMs > 0 && now >= nextStart - announceMs && now < nextStart && enoughOnline()) {
            announced = planner.draw();
            if (announced != null) {
                String seconds = String.valueOf(Math.round((nextStart - now) / 1000.0));
                for (Player p : Bukkit.getOnlinePlayers()) plugin.messages().send(p, "announce", "{seconds}", seconds);
            }
        }
        if (now >= nextStart) {
            Plan p = announced != null ? announced : planner.draw();
            announced = null;
            nextStart = next(now + 1000);
            if (!enoughOnline()) return;
            if (p == null) {
                plugin.getLogger().warning("No tournament can be drawn (FluxValue missing, or no job allowed in the config)");
                return;
            }
            start(p);
        }
    }

    private boolean enoughOnline() {
        int n = 0;
        for (Player p : Bukkit.getOnlinePlayers()) if (Universes.isVanilla(p)) n++;
        return n >= minOnline;
    }

    /** Next start after {@code now}: every {@code every-minutes} from midnight, shifted by {@code offset-minutes}. */
    long next(long now) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime at = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), zone);
        LocalDateTime t = at.toLocalDate().atStartOfDay().plusMinutes(offsetMinutes);
        while (!t.isAfter(at)) t = t.plusMinutes(everyMinutes);
        // a day that does not divide by the interval starts again at midnight
        LocalDateTime midnight = LocalDate.from(at).plusDays(1).atStartOfDay().plusMinutes(offsetMinutes);
        if (t.isAfter(midnight)) t = midnight;
        return t.atZone(zone).toInstant().toEpochMilli();
    }

    // ---------------------------------------------------------------- start

    /**
     * Starts a tournament: the draw animation first (what is drawn revealed to everybody while they keep playing),
     * then the tournament. False when one runs or is being drawn, or the effect engine is missing.
     */
    boolean start(Plan plan) {
        if (current != null || drawing != null) return false;
        if (!Effects.available()) {
            plugin.getLogger().warning("No effect engine (libreforge): tournaments cannot count actions");
            return false;
        }
        announced = null;
        if (!drawEnabled) return begin(plan);
        drawing = plan;
        show(viewers(), plan, () -> {
            drawing = null;
            if (!begin(plan)) broadcast("cancelled", plan);
        });
        return true;
    }

    /** Plays the draw of a plan to some players; {@code then} runs once the result was shown. */
    void show(List<Player> to, Plan plan, Runnable then) {
        Draws.Choice winner = choice(plan, plan.target(), plan.job());
        List<Draws.Choice> pool = new ArrayList<>();
        pool.add(winner);
        if (plan.target() != null) {
            List<Group> others = new ArrayList<>(plan.groups());
            others.remove(plan.target());
            java.util.Collections.shuffle(others);
            for (Group g : others) if (pool.size() < drawChoices) pool.add(choice(plan, g, plan.job()));
        } else {
            List<String> jobs = new ArrayList<>(planner.jobs(plan.kind()));
            jobs.remove(plan.job());
            java.util.Collections.shuffle(jobs);
            for (String j : jobs) if (pool.size() < drawChoices) pool.add(choice(plan, null, j));
        }
        Draws.play(plugin, to, pool, winner, kindIcon(plan.kind()) + " " + plugin.messages().raw("draw-heading." + plan.kind().id()), then);
    }

    /** A resource (or, with no group, a job) as a choice of the draw. */
    private Draws.Choice choice(Plan plan, Group g, String job) {
        if (g == null) {
            return new Draws.Choice(job, Material.matchMaterial(planner.jobIcon(job)), jobName(job));
        }
        return new Draws.Choice(g.key(), Material.matchMaterial(g.icon()), g.name());
    }

    /** Who watches a draw and plays a tournament: players outside the rpg universe. */
    List<Player> viewers() {
        List<Player> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) if (Universes.isVanilla(p)) out.add(p);
        return out;
    }

    private boolean begin(Plan plan) {
        if (current != null) return false;
        long now = System.currentTimeMillis();
        Run run = new Run(now, plan, now, now + durationMs, podium());
        int i = 0;
        for (Group g : plan.counted()) {
            for (JobSource s : g.sources()) {
                Effects.Counter c = Effects.count(plugin, "t" + now + "_" + i++, s.counter(1, plan.byPoints()),
                        (player, amount) -> action(player, g, amount));
                if (c != null) run.counters.add(c);
                else plugin.getLogger().warning("Tournament " + g.key() + ": counter not valid " + s.counter(1, plan.byPoints()));
            }
        }
        if (run.counters.isEmpty()) {
            plugin.getLogger().warning("Tournament " + plan.kind().id() + " " + plan.job() + ": nothing to count");
            return false;
        }
        current = run;
        planner.started(plan);
        broadcast("start", plan, "{minutes}", String.valueOf(durationMs / 60_000));
        updateBars(now);
        refreshMenus();
        return true;
    }

    private void action(Player player, Group group, double amount) {
        Run run = current;
        if (run == null || amount <= 0) return;
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (!Universes.isVanilla(player)) return;
        long now = System.currentTimeMillis();
        if (now >= run.ends) return;
        Score before = run.scores.get(player.getUniqueId());
        boolean wasFinished = before != null && before.finished > 0;
        if (!run.add(player.getUniqueId(), player.getName(), group.key(), amount, now)) return;
        Score s = run.scores.get(player.getUniqueId());
        if (before == null) plugin.messages().send(player, "joined", "{name}", name(run.plan));
        if (run.plan.kind() == Kind.RACE && !wasFinished && s.finished > 0) {
            int place = run.finishers();
            for (Player p : Bukkit.getOnlinePlayers()) {
                plugin.messages().send(p, "race-finished", "{player}", player.getName(), "{place}", String.valueOf(place));
            }
            Sounds.cue(player, "complete");
        }
        if (run.plan.kind() == Kind.VARIETY) {
            plugin.messages().actionBar(player, "variety-new", "{resource}", group.name(), "{count}", String.valueOf((int) s.value),
                    "{amount}", String.valueOf(run.plan.amount()));
            // everything gathered: the tournament stops now, the ranking as it stands
            if (run.plan.amount() > 0 && s.value >= run.plan.amount()) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    plugin.messages().send(p, "variety-complete", "{player}", player.getName(), "{name}", shortName(run.plan));
                }
                Sounds.cue(player, "complete");
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (current == run) end(false);
                });
            }
        }
        Lists.invalidate(LIVE);
    }

    // ---------------------------------------------------------------- end

    /** Ends the running tournament: {@code cancelled} = no ranking, no reward, nothing recorded. */
    void end(boolean cancelled) {
        Run run = current;
        if (run == null) return;
        current = null;
        run.close();
        hideBars();
        if (cancelled) {
            broadcast("cancelled", run.plan);
            refreshMenus();
            return;
        }
        List<Score> ranking = run.ranking();
        boolean rewarded = ranking.size() >= minParticipants;
        double best = ranking.isEmpty() ? 0 : ranking.get(0).value;
        List<Result> results = new ArrayList<>();
        Map<UUID, Integer> points = new HashMap<>();
        Map<UUID, Double> money = new HashMap<>();
        for (int i = 0; i < ranking.size(); i++) {
            Score s = ranking.get(i);
            int place = i + 1;
            results.add(new Result(s.uuid, s.name, place, s.value));
            if (!rewarded) continue;
            Prize prize = prizeOf(run, s, place, best);
            if (prize == null) continue;
            OfflinePlayer op = Bukkit.getOfflinePlayer(s.uuid);
            double amount = Economy.round(prize.seconds() * moneyPerSecond());
            if (amount >= 0.01 && plugin.money() != null && plugin.money().available()) {
                plugin.money().give(op, amount, Origin.of(plugin, "prize").detail(Texts.plain(name(run.plan), null)).notifyPlayer());
                money.put(s.uuid, amount);
            }
            for (Reward r : prize.rewards()) Rewards.give(op, r.key("tournament:" + run.id + ":" + r.key()));
            if (prize.points() > 0) points.put(s.uuid, prize.points());
        }
        String[] top = podiumVars(run, ranking);
        if (ranking.isEmpty()) {
            broadcast("end-empty", run.plan);
        } else {
            broadcast(rewarded ? "end" : "end-too-few", run.plan, concat(top, "{participants}", String.valueOf(ranking.size()),
                    "{min}", String.valueOf(minParticipants)));
        }
        for (Score s : ranking) {
            Player p = Bukkit.getPlayer(s.uuid);
            if (p == null) continue;
            int place = run.rank(s.uuid);
            plugin.messages().send(p, "your-result", "{place}", String.valueOf(place), "{score}", score(run.plan, s.value),
                    "{points}", String.valueOf(points.getOrDefault(s.uuid, 0)));
            Sounds.cue(p, place == 1 && rewarded ? "complete" : "success");
        }
        store.record(new PastRun(run.id, run.plan.kind().id(), run.plan.job(), run.plan.target() == null ? null : run.plan.target().key(),
                name(run.plan), icon(run.plan), run.plan.amount(), System.currentTimeMillis(), rewarded, List.copyOf(results)), points, podium());
        refreshMenus();
    }

    /** The prize of a place: the podium (a race's finishers only), else participation when the score is enough. */
    private Prize prizeOf(Run run, Score s, int place, double best) {
        boolean racer = run.plan.kind() != Kind.RACE || s.finished > 0;
        Prize p = racer ? prizes.get(place) : null;
        if (p != null) return p;
        double reference = run.plan.kind() == Kind.RACE ? run.plan.amount() : best;
        return s.value >= reference * participationShare ? participation : null;
    }

    double moneyPerSecond() {
        double v = Values.moneyPerSecond();
        return v > 0 ? v : fallbackMoneyPerSecond;
    }

    // ---------------------------------------------------------------- texts

    /** Name of a tournament ({@code names.<kind>}). */
    String name(Plan p) {
        return plugin.messages().raw("names." + p.kind().id(), vars(p));
    }

    /** Pixel-art icon of a kind of tournament (FluxTournament/assets/gen_icons.py, blocks atlas). */
    static String kindIcon(Kind kind) {
        return kind == null ? "" : "<sprite:block/fluxtournament_" + kind.id() + ">";
    }

    /** Item sprite of the tournament's resource or job, for the chat ("" without one: Bedrock shows nothing). */
    String iconTag(Plan p) {
        return fr.influencraft.flux.api.text.ItemSprites.tag(Material.matchMaterial(icon(p)));
    }

    /** Short name for the boss bar ({@code short-names.<kind>}). */
    String shortName(Plan p) {
        return plugin.messages().raw("short-names." + p.kind().id(), vars(p));
    }

    String description(Plan p) {
        return plugin.messages().raw("descriptions." + p.kind().id(), vars(p));
    }

    String icon(Plan p) {
        return p.target() != null ? p.target().icon() : planner.jobIcon(p.job());
    }

    String[] vars(Plan p) {
        return new String[]{"{job}", jobName(p.job()), "{target}", p.target() == null ? "" : p.target().name(),
                "{amount}", String.valueOf(p.amount())};
    }

    /** Display name of a job without its colours. */
    static String jobName(String job) {
        String name = Jobs.available() && Jobs.get().exists(job) ? Jobs.get().displayName(job) : job;
        name = name.replaceAll("(?i)&#[0-9a-f]{6}|&[0-9a-fk-or]|§.", "").trim();
        return name.isEmpty() ? job : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /** A score as shown ({@code score.<kind>}). */
    String score(Plan p, double value) {
        return plugin.messages().raw("score." + p.kind().id(), "{n}", Format.number(Math.floor(value)), "{amount}", String.valueOf(p.amount()));
    }

    String scoreOf(String kind, double value, int amount) {
        return plugin.messages().raw("score." + kind, "{n}", Format.number(Math.floor(value)), "{amount}", String.valueOf(amount));
    }

    private String[] podiumVars(Run run, List<Score> ranking) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            Score s = ranking.size() >= i ? ranking.get(i - 1) : null;
            out.add("{" + i + "_player}");
            out.add(s == null ? "-" : s.name);
            out.add("{" + i + "_score}");
            out.add(s == null ? "-" : score(run.plan, s.value));
        }
        return out.toArray(new String[0]);
    }

    private static String[] concat(String[] a, String... b) {
        String[] out = new String[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private void broadcast(String key, Plan plan, String... extra) {
        String[] vars = concat(concat(vars(plan), "{name}", name(plan), "{short}", shortName(plan), "{icon}", iconTag(plan),
                "{kind}", kindIcon(plan.kind()),
                "{description}", description(plan)), extra);
        for (Player p : Bukkit.getOnlinePlayers()) plugin.messages().send(p, key, vars);
        Bukkit.getConsoleSender().sendMessage("[FluxTournament] " + key + ": " + Texts.plain(name(plan), null));
    }

    // ---------------------------------------------------------------- boss bar

    /**
     * Every {@code ranking-every-minutes}, the ranking in the chat of the players taking part (podium and their own
     * place), never in the last minute (the end tells it).
     */
    private void rankingInChat(Run run, long now) {
        if (rankingEveryMs <= 0 || run.ends - now < 60_000) return;
        long due = run.started + rankingEveryMs * (run.rankingsSent + 1);
        if (now < due) return;
        run.rankingsSent++;
        List<Score> ranking = run.ranking();
        if (ranking.isEmpty()) return;
        String[] vars = concat(podiumVars(run, ranking), "{name}", shortName(run.plan), "{icon}", iconTag(run.plan),
                "{kind}", kindIcon(run.plan.kind()), "{time}", clock((run.ends - now) / 1000),
                "{players}", String.valueOf(ranking.size()));
        for (Score s : ranking) {
            Player p = Bukkit.getPlayer(s.uuid);
            if (p == null) continue;
            plugin.messages().send(p, "ranking", concat(vars, "{place}", String.valueOf(run.rank(s.uuid)), "{score}", score(run.plan, s.value)));
        }
    }

    private void updateBars(long now) {
        Run run = current;
        if (!bossbar || run == null) return;
        float progress = Math.max(0f, Math.min(1f, (run.ends - now) / (float) (run.ends - run.started)));
        String time = clock((run.ends - now) / 1000);
        List<Score> ranking = run.ranking();
        String players = String.valueOf(ranking.size());
        String title = shortName(run.plan);
        String kind = kindIcon(run.plan.kind());
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!Universes.isVanilla(p)) {
                hide(p);
                continue;
            }
            Score s = run.scores.get(p.getUniqueId());
            String text = s == null || s.value <= 0
                    ? plugin.messages().raw("bossbar-none", "{kind}", kind, "{name}", title, "{time}", time, "{players}", players)
                    : plugin.messages().raw("bossbar", "{kind}", kind, "{name}", title, "{time}", time, "{players}", players,
                    "{place}", String.valueOf(run.rank(p.getUniqueId())), "{score}", score(run.plan, s.value));
            BossBar bar = bars.get(p.getUniqueId());
            if (bar == null) {
                bar = BossBar.bossBar(Texts.parse(text, p), progress, barColor, BossBar.Overlay.PROGRESS);
                bars.put(p.getUniqueId(), bar);
                p.showBossBar(bar);
            } else {
                bar.name(Texts.parse(text, p));
                bar.progress(progress);
            }
        }
    }

    void hide(Player p) {
        BossBar bar = bars.remove(p.getUniqueId());
        if (bar != null) p.hideBossBar(bar);
    }

    void hideBars() {
        for (Map.Entry<UUID, BossBar> e : bars.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) p.hideBossBar(e.getValue());
        }
        bars.clear();
    }

    static String clock(long seconds) {
        seconds = Math.max(0, seconds);
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    void refreshMenus() {
        Lists.invalidate(LIVE);
        Lists.invalidate(CURRENT);
        Lists.invalidate(HISTORY);
        Lists.invalidate(STATS);
    }

    void stop() {
        if (current != null) {
            current.close();
            current = null;
        }
        hideBars();
    }
}

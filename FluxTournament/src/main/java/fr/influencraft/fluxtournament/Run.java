package fr.influencraft.fluxtournament;

import fr.influencraft.flux.api.effects.Effects;
import fr.influencraft.fluxtournament.Plan.Kind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** A tournament being played: its plan, its time, the score of every player who took part. */
final class Run {

    /** Score of one player. */
    static final class Score {
        final UUID uuid;
        String name;
        /** Points (job), units (resource, race) or different resources (variety). */
        double value;
        /** When the score last went up: the earlier wins a tie. */
        long reached;
        /** Race: when the amount was reached, 0 before. */
        long finished;
        /** Variety: resources already gathered. */
        final Set<String> seen = new HashSet<>();

        Score(UUID uuid, String name) {
            this.uuid = uuid;
            this.name = name;
        }
    }

    final long id;
    final Plan plan;
    final long started;
    final long ends;
    final int podium;
    /** Rankings already sent in the chat. */
    int rankingsSent;
    final Map<UUID, Score> scores = new ConcurrentHashMap<>();
    final List<Effects.Counter> counters = new ArrayList<>();
    private List<Score> ranking = List.of();
    private boolean dirty = true;

    Run(long id, Plan plan, long started, long ends, int podium) {
        this.id = id;
        this.plan = plan;
        this.started = started;
        this.ends = ends;
        this.podium = podium;
    }

    /**
     * Adds what a player did; true when his score went up. {@code amount} = units or points of the action (the
     * counter decides), {@code group} = the resource it belongs to.
     */
    boolean add(UUID uuid, String name, String group, double amount, long now) {
        if (amount <= 0) return false;
        Score s = scores.computeIfAbsent(uuid, u -> new Score(u, name));
        s.name = name;
        switch (plan.kind()) {
            case VARIETY -> {
                if (!s.seen.add(group)) return false;
                s.value = s.seen.size();
            }
            case RACE -> {
                if (s.finished > 0) return false;
                s.value = Math.min(plan.amount(), s.value + amount);
                if (s.value >= plan.amount()) s.finished = now;
            }
            default -> s.value += amount;
        }
        s.reached = now;
        dirty = true;
        return true;
    }

    /** Players who scored, best first. */
    List<Score> ranking() {
        if (dirty) {
            List<Score> out = new ArrayList<>();
            for (Score s : scores.values()) if (s.value > 0) out.add(s);
            Comparator<Score> byScore = Comparator.<Score>comparingDouble(s -> -s.value).thenComparingLong(s -> s.reached);
            if (plan.kind() == Kind.RACE) {
                out.sort(Comparator.<Score>comparingInt(s -> s.finished > 0 ? 0 : 1)
                        .thenComparingLong(s -> s.finished > 0 ? s.finished : 0).thenComparing(byScore));
            } else {
                out.sort(byScore);
            }
            ranking = List.copyOf(out);
            dirty = false;
        }
        return ranking;
    }

    /** Position of a player (1 = best), 0 when he has not scored. */
    int rank(UUID uuid) {
        List<Score> r = ranking();
        for (int i = 0; i < r.size(); i++) if (r.get(i).uuid.equals(uuid)) return i + 1;
        return 0;
    }

    /** Race: players who reached the amount. */
    int finishers() {
        int n = 0;
        for (Score s : scores.values()) if (s.finished > 0) n++;
        return n;
    }

    void close() {
        for (Effects.Counter c : counters) c.close();
        counters.clear();
    }
}

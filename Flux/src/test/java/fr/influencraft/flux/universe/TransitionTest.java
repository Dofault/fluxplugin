package fr.influencraft.flux.universe;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every crash point of a swap, with and without an autosave of the player file at each step: after the next join
 * (reconcile), every item of every universe must exist exactly once (no loss, no duplicate).
 */
class TransitionTest {

    /** A profile: the items of one universe, as a sorted comma list. */
    private static String items(String... ids) {
        String[] sorted = ids.clone();
        Arrays.sort(sorted);
        return String.join(",", sorted);
    }

    /** The player: live state in memory, and the file on disk (written by persist or an autosave). */
    static final class Player implements Transition.Live<String> {
        String universe = "vanilla", content;
        long version;
        String fileUniverse = "vanilla", fileContent;
        long fileVersion;

        Player(String content) {
            this.content = content;
            this.fileContent = content;
        }

        @Override public String universe() { return universe; }
        @Override public long version() { return version; }
        @Override public String capture() { return content; }
        @Override public void mark(String u, long v) { universe = u; version = v; }

        @Override
        public void apply(String profile, String u, long v) {
            content = profile == null ? "" : profile;
            universe = u;
            version = v;
        }

        @Override
        public void persist() {
            fileUniverse = universe;
            fileVersion = version;
            fileContent = content;
        }

        /** The server dies: what is in memory is gone, the next join reads the file. */
        void crash() {
            universe = fileUniverse;
            version = fileVersion;
            content = fileContent;
        }
    }

    static final class Repo implements Transition.Repository<String> {
        final Map<String, Transition.Stored<String>> rows = new HashMap<>();
        boolean failSave, failLoad;

        @Override
        public Transition.Stored<String> load(String universe) {
            if (failLoad) throw new IllegalStateException("database down");
            return rows.get(universe);
        }

        @Override
        public boolean save(String universe, long version, String profile) {
            if (failSave) return false;
            rows.put(universe, new Transition.Stored<>(version, profile));
            return true;
        }
    }

    /** Every item the player owns, all universes: live for his universe, stored copies for the others. */
    private static Map<String, Integer> owned(Player p, Repo repo) {
        Map<String, Integer> count = new TreeMap<>();
        add(count, p.content);
        for (Map.Entry<String, Transition.Stored<String>> e : repo.rows.entrySet()) {
            if (!e.getKey().equals(p.universe)) add(count, e.getValue().profile());
        }
        return count;
    }

    private static void add(Map<String, Integer> count, String content) {
        if (content == null || content.isEmpty()) return;
        for (String id : content.split(",")) count.merge(id, 1, Integer::sum);
    }

    /** Steps of a swap that can be interrupted. */
    enum Crash { NONE, AFTER_MARK, AFTER_SAVE, AFTER_APPLY_BEFORE_PERSIST }

    private record Run(Player player, Repo repo) {
    }

    /**
     * A player owning vanilla items V1 V2 (V3 just given away) and rpg items R1 R2 (stored), in vanilla, swaps to rpg; the server may crash
     * at a step, an autosave may have written his file at each step. Then he joins again and is reconciled.
     */
    private Run scenario(Crash crash, boolean[] autosave) {
        Player p = new Player(items("V1", "V2", "V3"));
        p.persist();
        // since the last write of his file he gave V3 to another player (trade): his file still holds it
        p.content = items("V1", "V2");
        Repo repo = new Repo();
        repo.rows.put("rpg", new Transition.Stored<>(3, items("R1", "R2")));
        Queue<Runnable> async = new ArrayDeque<>(), main = new ArrayDeque<>();
        List<Transition.Outcome> outcome = new ArrayList<>();

        Transition.swap(p, repo, "rpg", async::add, main::add, () -> true, outcome::add);
        if (autosave[0]) p.persist();
        if (crash == Crash.AFTER_MARK) return crashAndJoin(p, repo);
        async.poll().run();
        if (autosave[1]) p.persist();
        if (crash == Crash.AFTER_SAVE) return crashAndJoin(p, repo);
        // apply without the persist inside: run main step through a player that skips persist once
        if (crash == Crash.AFTER_APPLY_BEFORE_PERSIST) {
            String fu = p.fileUniverse, fc = p.fileContent;
            long fv = p.fileVersion;
            main.poll().run();
            p.fileUniverse = fu;          // the persist of the apply never reached the disk
            p.fileContent = fc;
            p.fileVersion = fv;
            if (autosave[2]) p.persist();
            return crashAndJoin(p, repo);
        }
        main.poll().run();
        assertEquals(List.of(Transition.Outcome.DONE), outcome);
        return new Run(p, repo);
    }

    private Run crashAndJoin(Player p, Repo repo) {
        p.crash();
        Transition.reconcile(p, repo);
        return new Run(p, repo);
    }

    /**
     * After the crash and the next join, the player holds the newest state that reached a disk (his file or the
     * database), and no item twice. The only case where V3 comes back is a crash before anything was written since
     * the gift: that is Minecraft's own rollback (same as a crash without any universe), not a duplicate made by the
     * swap.
     */
    @Test
    void everyCrashPointKeepsTheNewestWrittenState() {
        for (Crash crash : Crash.values()) {
            for (int mask = 0; mask < 8; mask++) {
                boolean[] autosave = {(mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0};
                Run run = scenario(crash, autosave);
                boolean nothingWritten = crash == Crash.AFTER_MARK && !autosave[0];
                Map<String, Integer> expected = new TreeMap<>(Map.of("V1", 1, "V2", 1, "R1", 1, "R2", 1));
                if (nothingWritten) expected.put("V3", 1);
                Map<String, Integer> owned = owned(run.player(), run.repo());
                String label = "crash " + crash + ", autosaves " + Arrays.toString(autosave);
                assertEquals(expected, owned, label);
                owned.forEach((id, n) -> assertEquals(1, n, label + ": " + id + " held twice"));
            }
        }
    }

    @Test
    void swapBackAndForthKeepsEverything() {
        Player p = new Player(items("V1"));
        Repo repo = new Repo();
        for (int i = 0; i < 6; i++) {
            String target = p.universe.equals("vanilla") ? "rpg" : "vanilla";
            Transition.swap(p, repo, target, Runnable::run, Runnable::run, () -> true, o -> assertEquals(Transition.Outcome.DONE, o));
            if (p.universe.equals("rpg") && !p.content.contains("R1")) p.content = items("R1");   // first visit: gets his class kit
        }
        assertEquals(Map.of("V1", 1, "R1", 1), owned(p, repo));
    }

    @Test
    void failedSaveChangesNothing() {
        Player p = new Player(items("V1", "V2"));
        Repo repo = new Repo();
        repo.failSave = true;
        List<Transition.Outcome> out = new ArrayList<>();
        Transition.swap(p, repo, "rpg", Runnable::run, Runnable::run, () -> true, out::add);
        assertEquals(List.of(Transition.Outcome.SAVE_FAILED), out);
        assertEquals("vanilla", p.universe);
        assertEquals(items("V1", "V2"), p.content);
        // the marker moved ahead of the (missing) stored copy: the next join keeps the live state
        p.persist();
        p.crash();
        Transition.reconcile(p, repo);
        assertEquals(items("V1", "V2"), p.content);
    }

    @Test
    void unreadableIncomingProfileIsNeverReplacedByAnEmptyOne() {
        Player p = new Player(items("V1"));
        Repo repo = new Repo();
        repo.rows.put("rpg", new Transition.Stored<>(5, items("R1")));
        List<Transition.Outcome> out = new ArrayList<>();
        Queue<Runnable> async = new ArrayDeque<>();
        Transition.swap(p, repo, "rpg", async::add, Runnable::run, () -> true, out::add);
        repo.failLoad = true;              // the database fails between the save and the read of the rpg profile
        async.poll().run();
        assertEquals(List.of(Transition.Outcome.LOAD_FAILED), out);
        assertEquals("vanilla", p.universe);
        assertEquals(items("V1"), p.content);
        repo.failLoad = false;
        assertEquals(items("R1"), repo.rows.get("rpg").profile());
    }

    @Test
    void playerLeavingDuringTheSwapIsReconciledAtJoin() {
        Player p = new Player(items("V1"));
        Repo repo = new Repo();
        repo.rows.put("rpg", new Transition.Stored<>(2, items("R1")));
        List<Transition.Outcome> out = new ArrayList<>();
        Transition.swap(p, repo, "rpg", Runnable::run, Runnable::run, () -> false, out::add);
        assertEquals(List.of(Transition.Outcome.ABANDONED), out);
        p.persist();                       // the game writes his file when he quits
        p.crash();
        Transition.reconcile(p, repo);
        assertEquals(Map.of("V1", 1, "R1", 1), owned(p, repo));
    }

    @Test
    void staleFileAfterACheckoutIsRepaired() {
        // in rpg for a long time, file saved with his latest rpg items: the old stored rpg copy must not come back
        Player p = new Player(items("V1"));
        Repo repo = new Repo();
        Transition.swap(p, repo, "rpg", Runnable::run, Runnable::run, () -> true, o -> { });
        p.content = items("R1", "R9");     // loot earned in rpg
        p.persist();
        p.crash();
        Transition.reconcile(p, repo);
        assertEquals(items("R1", "R9"), p.content);
        assertEquals(Map.of("V1", 1, "R1", 1, "R9", 1), owned(p, repo));
    }
}

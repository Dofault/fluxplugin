package fr.influencraft.flux.api.draw;

import fr.influencraft.flux.api.client.Clients;
import fr.influencraft.flux.api.text.ItemSprites;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.Sounds;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A draw shown to players while they keep playing: no menu, no frozen screen (boss bar, title, a floating item...).
 * The caller gives what can come out and what came out; Flux animates the way there, then runs {@code onDone}.
 * Icons are the client's own item sprites ({@link ItemSprites}): nothing in the resource pack.
 *
 * <p>Bedrock players (no sprites, no display tricks) see no animation: they get the result written out at the
 * moment the others see it revealed.</p>
 *
 * <pre>
 *   Draws.play(plugin, players, pool, winner, Draws.Style.ROULETTE, "Tournament draw", () -> start());
 * </pre>
 */
public final class Draws {

    /**
     * One thing that can be drawn.
     *
     * @param id      stable id
     * @param icon    vanilla item whose sprite stands for it (null: none)
     * @param name    text shown, any Flux syntax ({@code <lang:block.minecraft.iron_ore>} is translated by each client)
     * @param letters the name in plain letters (server language), for animations that play with the letters
     */
    public record Choice(String id, Material icon, String name, String letters) {

        /** Sprite tag of the icon, "" when there is none. */
        public String sprite() {
            return icon == null ? "" : ItemSprites.tag(icon);
        }

        /** Sprite then name. */
        public String full() {
            String s = sprite();
            return s.isEmpty() ? name : s + " " + name;
        }
    }

    public enum Style {
        ROULETTE, ELIMINATION, FLAP, TITLE, HOLOGRAM;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Style of(String id) {
            for (Style s : values()) if (s.id().equalsIgnoreCase(id)) return s;
            return null;
        }
    }

    /** What every animation is given. */
    record Show(Plugin owner, List<Player> viewers, List<Choice> pool, Choice winner, String heading) {

        /** Viewers still online. */
        List<Player> online() {
            List<Player> out = new ArrayList<>();
            for (Player p : viewers) if (p.isOnline()) out.add(p);
            return out;
        }

        /** {@code n} choices in a random order, never the same twice in a row, ending on the winner. */
        List<Choice> reel(int n) {
            List<Choice> out = new ArrayList<>();
            Choice last = null;
            for (int i = 0; i < n - 1; i++) {
                Choice c = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
                for (int tries = 0; tries < 12 && pool.size() > 1 && (c.equals(last) || i == n - 2 && c.equals(winner)); tries++) {
                    c = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
                }
                out.add(c);
                last = c;
            }
            out.add(winner);
            return out;
        }
    }

    /** An animation: steps at its own pace, then the reveal, then the end. */
    interface Animation {
        /** Ticks before each step (its length is the number of steps). */
        int[] delays();

        void step(int index);

        /** The winner is shown. */
        void reveal();

        /** Ticks the reveal stays before {@link #close}. */
        default int hold() {
            return 50;
        }

        /** Removes what was shown. */
        void close();

        /** Sound of a step, a named cue (null = none). */
        default String tick(int index) {
            return "draw-tick";
        }
    }

    private static final List<BukkitTask> RUNNING = new ArrayList<>();

    private Draws() {
    }

    /**
     * Plays a draw to {@code viewers}; {@code onDone} runs on the main thread once the result was shown (also when
     * nobody watches). {@code pool} should hold the winner; one choice alone still animates.
     */
    public static void play(Plugin owner, Collection<? extends Player> viewers, List<Choice> pool, Choice winner, Style style,
                            String heading, Runnable onDone) {
        List<Player> java = new ArrayList<>(), bedrock = new ArrayList<>();
        for (Player p : viewers) (Clients.bedrock(p) ? bedrock : java).add(p);
        List<Choice> choices = pool == null || pool.isEmpty() ? List.of(winner) : List.copyOf(pool);
        Show show = new Show(owner, java, choices, winner, heading == null ? "" : heading);
        Animation a = switch (style == null ? Style.ROULETTE : style) {
            case ROULETTE -> new RouletteDraw(show);
            case ELIMINATION -> new EliminationDraw(show);
            case FLAP -> new FlapDraw(show);
            case TITLE -> new TitleDraw(show);
            case HOLOGRAM -> new HologramDraw(show);
        };
        int[] delays = a.delays();
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(owner, new Runnable() {
            int step = 0, wait = delays.length == 0 ? 0 : delays[0];
            boolean revealed;
            int holding;

            @Override
            public void run() {
                try {
                    if (!revealed) {
                        if (--wait > 0) return;
                        if (step < delays.length) {
                            a.step(step);
                            String cue = a.tick(step);
                            if (cue != null) for (Player p : show.online()) Sounds.cue(p, cue);
                            step++;
                            wait = step < delays.length ? delays[step] : 1;
                            return;
                        }
                        revealed = true;
                        a.reveal();
                        for (Player p : show.online()) Sounds.cue(p, "draw-reveal");
                        for (Player p : bedrock) {
                            if (p.isOnline()) p.sendActionBar(Texts.parse("&#FFD94D" + show.heading() + " &8» &f" + winner.name(), p));
                        }
                        holding = a.hold();
                        return;
                    }
                    if (--holding > 0) return;
                } catch (RuntimeException e) {
                    owner.getLogger().warning("Draw animation failed: " + e);
                }
                finish(task[0], a, onDone, owner);
            }
        }, 1L, 1L);
        RUNNING.add(task[0]);
    }

    private static void finish(BukkitTask task, Animation a, Runnable onDone, Plugin owner) {
        task.cancel();
        RUNNING.remove(task);
        try {
            a.close();
        } catch (RuntimeException e) {
            owner.getLogger().warning("Draw animation not closed: " + e);
        }
        if (onDone != null) onDone.run();
    }

    /** Delays slowing down like a wheel: {@code steps} steps from 1 tick up to {@code slowest}. */
    static int[] wheel(int steps, int slowest) {
        int[] d = new int[steps];
        for (int i = 0; i < steps; i++) {
            double t = i / (double) Math.max(1, steps - 1);
            d[i] = 1 + (int) Math.round((slowest - 1) * Math.pow(t, 2.4));
        }
        return d;
    }
}

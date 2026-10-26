package fr.influencraft.fluxcasino.games;

import fr.influencraft.flux.api.MenuView;
import fr.influencraft.flux.api.TitleOverlay;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Slot machine ({@code [slots]}): three reels of eight symbols, one pay line (the middle cell). The result is
 * drawn beforehand (one weighted draw per reel), then the reels are animated in the window title, one frame per
 * tick: each reel is an ItemsAdder glyph showing three cells of the symbol strip at a given position (4 px steps,
 * sharp or blurred depending on speed; pack influcasino, assets/slots/gen_slots.py). The reels speed up, spin,
 * slow down then stop one after the other with a small bounce; the cabinet bulbs chase; the winning cells blink.
 *
 * <pre>
 *   %fluxslots_last%   last spin of the player ("x2 (20 jetons)", "perdu", "aucun" before the first)
 *   %fluxslots_count%  spins since the server started
 *   %fluxslots_erreur% reason of the last refused spin (not enough chips), empty otherwise
 * </pre>
 * Gains (bet x multiplier, rounded down): three identical symbols per {@link #TRIPLE}, two cherries anywhere on the
 * line x{@link #TWO}, one cherry x{@link #ONE}. Expected return 0.937 for 1 chip (over the 68,921 combinations,
 * see docs/technique/casino.md).
 */
final class Slots extends CasinoGame {

    // Geometry (same as gen_slots.py), in px of the window.
    private static final int CELL_H = 28, REEL_W = 32, PERIOD = 224, STEP = 4;
    private static final int[] REEL_X = {36, 72, 108};
    private static final int CAISSE_X = 27, CAISSE_W = 122;

    /** Symbols in strip order (top to bottom), as ORDRE in assets/slots/symboles.py. */
    static final String[] SYMBOLS = {"cerise", "cloche", "citron", "couronne", "prune", "sept", "etoile", "diamant"};
    /** Draw weight of each symbol on a reel (total 41). */
    private static final int[] WEIGHTS = {10, 5, 9, 2, 7, 1, 4, 3};
    /** Gain of three identical symbols on the line, as a multiple of the bet. */
    private static final double[] TRIPLE = {7, 30, 12, 80, 20, 100, 45, 60};
    private static final double TWO = 2, ONE = 0.5;
    private static final int CHERRY = 0;
    private static final int WEIGHT_TOTAL = java.util.stream.IntStream.of(WEIGHTS).sum();

    // Animation (ticks). One reel: acceleration, fast cruise, braking, bounce.
    private static final int[] ACCEL = {8, 16, 24, 32};
    private static final int[] BRAKE = {112, 84, 60, 40, 24, 12, 4, -4, 0}; // distance left, from the end of the cruise to the stop
    private static final int[] CRUISE_WANTED = {10, 20, 30};
    private static final int BLUR_FROM = 16;      // px per tick from which the blurred version is shown
    private static final int FLASH_TICKS = 24;    // blinking of a gain
    private static final int FLASH_BIG_TICKS = 44;

    /** Result of a spin: middle symbols, multiplier, winning cells. */
    private record Outcome(int[] stops, double mult, boolean[] win) {
    }

    private final Map<UUID, Spin> spins = new ConcurrentHashMap<>();
    /** Stop position of the reels, per player, while they stay in the same window. */
    private final Map<UUID, Rest> rests = new ConcurrentHashMap<>();

    private record Rest(MenuView view, int[] pos) {
    }

    Slots(Plugin plugin) {
        super(plugin, "slots");
    }

    /** A spin in progress: draw, path of each reel, bet taken, gain to pay. */
    private final class Spin {
        final Player player;
        final MenuView view;
        final String base;
        final int cursor;
        final long bet;
        final Outcome outcome;
        final int[][] pos = new int[3][];        // position of each reel at each tick
        final boolean[][] blur = new boolean[3][];
        final int moveTicks;                     // tick at which the last reel stops
        final int flashTicks;
        BukkitTask task;
        int tick;
        boolean settled;

        Spin(Player player, MenuView view, long bet, Outcome outcome, int[] from) {
            this.player = player;
            this.view = view;
            this.base = view.title();
            this.cursor = view.titleCursor();
            this.bet = bet;
            this.outcome = outcome;
            int longest = 0;
            for (int r = 0; r < 3; r++) {
                int target = Math.floorMod(outcome.stops()[r] - 1, SYMBOLS.length) * CELL_H;
                Track track = track(from[r], target, CRUISE_WANTED[r]);
                pos[r] = track.pos();
                blur[r] = track.blur();
                longest = Math.max(longest, pos[r].length);
            }
            this.moveTicks = longest - 1;
            this.flashTicks = outcome.mult() >= 45 ? FLASH_BIG_TICKS : outcome.mult() >= 1 ? FLASH_TICKS : 0;
        }
    }

    /** Path of one reel: strip position at each tick and version (sharp or blurred) to show. */
    record Track(int[] pos, boolean[] blur) {
    }

    /**
     * Path of a reel from the previous stop {@code from} to {@code target} (strip positions in px, multiples of
     * STEP): acceleration, a cruise of about {@code cruiseWanted} cells, braking then a slight bounce, stopping
     * exactly on the target.
     */
    static Track track(int from, int target, int cruiseWanted) {
        int brakeStart = BRAKE[0];
        int accel = 0;
        for (int v : ACCEL) accel += v;
        // distance to travel: from the previous stop to the target, plus whole turns so that it lasts long enough
        int d0 = Math.floorMod(from - target, PERIOD);
        int need = accel + brakeStart + CELL_H * cruiseWanted;
        int turns = Math.max(0, (need - d0 + PERIOD - 1) / PERIOD);
        int remaining = d0 + turns * PERIOD - accel - brakeStart;
        // cruise: remaining px split into steps close to 32, all multiples of STEP
        int cruiseTicks = Math.max(1, Math.round(remaining / 32f));
        int units = remaining / STEP;
        List<Integer> steps = new ArrayList<>();
        for (int v : ACCEL) steps.add(v);
        for (int i = 0; i < cruiseTicks; i++) steps.add((units / cruiseTicks + (i < units % cruiseTicks ? 1 : 0)) * STEP);
        for (int i = 1; i < BRAKE.length; i++) steps.add(BRAKE[i - 1] - BRAKE[i]);
        int[] pos = new int[steps.size() + 1];
        boolean[] blur = new boolean[pos.length];
        int dist = d0 + turns * PERIOD;
        pos[0] = Math.floorMod(target + dist, PERIOD);
        for (int i = 0; i < steps.size(); i++) {
            dist -= steps.get(i);
            pos[i + 1] = Math.floorMod(target + dist, PERIOD);
            blur[i + 1] = steps.get(i) >= BLUR_FROM;
        }
        return new Track(pos, blur);
    }

    /**
     * Starts a spin. One spin at a time per window: a click while the reels turn is ignored. The bet is taken
     * right away, the gain paid when the reels stop (or when the menu closes).
     */
    @Override
    void act(Player player, MenuView view, String argument) {
        if (view == null || !view.isOpen()) return;
        UUID id = player.getUniqueId();
        Spin running = spins.get(id);
        if (running != null) {
            if (running.view.equals(view) && !running.settled) return;
            stop(running); // spin of a previous window: settled before starting another
        }
        long bet = takeBet(player, view);
        if (bet < 0) return;
        Rest rest = rests.get(id);
        int[] from = rest != null && rest.view().equals(view) ? rest.pos() : new int[3];
        Spin spin = new Spin(player, view, bet, draw(ThreadLocalRandom.current()), from);
        spins.put(id, spin);
        if (bet > 0) view.refreshButtons(); // balance
        player.playSound(player.getLocation(), Sound.BLOCK_LEVER_CLICK, SoundCategory.MASTER, 0.8f, 1.0f);
        spin.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> step(spin), 0L, 1L);
    }

    /** One frame: reels, bulbs, sounds, then (once stopped) gain and blinking. */
    private void step(Spin spin) {
        Player player = spin.player;
        if (!spin.view.isOpen() || !player.isOnline()) {
            stop(spin);
            return;
        }
        int t = spin.tick++;
        boolean moving = t <= spin.moveTicks;
        int[] frame = new int[3];
        boolean[] blurry = new boolean[3];
        for (int r = 0; r < 3; r++) {
            int i = Math.min(t, spin.pos[r].length - 1);
            frame[r] = spin.pos[r][i];
            blurry[r] = spin.blur[r][i];
            if (t == spin.pos[r].length - 1) reelStopped(spin, r);
        }
        if (moving && t % 2 == 0 && t > 0) {
            // ticking while a reel turns fast
            boolean fast = false;
            for (boolean b : blurry) fast |= b;
            if (fast) player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, SoundCategory.MASTER, 0.35f, 1.5f);
        }
        int after = t - spin.moveTicks;   // ticks since the last reel stopped
        if (after == 1) win(spin);
        boolean flashing = after >= 1 && after < spin.flashTicks; // the last frame stays still

        TitleOverlay o = new TitleOverlay(spin.base, spin.cursor);
        // bulbs: chasing during the spin, fast blinking on a gain
        if (moving || flashing) {
            int rhythm = flashing ? 2 : 3;
            o.add("slots_ampoules_" + ((t / rhythm) % 2 == 0 ? "a" : "b"), CAISSE_X, CAISSE_W);
        }
        for (int r = 0; r < 3; r++) {
            o.add((blurry[r] ? "slots_rouleau_flou_p" : "slots_rouleau_p") + frame[r], REEL_X[r], REEL_W);
        }
        if (after >= 1 && spin.outcome.mult() > 0) {
            String tint = flashing && (after / 2) % 2 == 1 ? "b" : "a";
            for (int r = 0; r < 3; r++) {
                if (spin.outcome.win()[r]) o.add("slots_cadre_" + tint, REEL_X[r], REEL_W);
            }
        }
        spin.view.showTitle(o.toString());
        if (after == 1) spin.view.refreshButtons(); // last spin, number of spins
        if (after >= Math.max(1, spin.flashTicks)) finish(spin); // last frame shown: reels stopped, win framed
    }

    /** A reel just stopped: a dull thud, higher and higher. */
    private void reelStopped(Spin spin, int reel) {
        float pitch = 0.8f + reel * 0.15f;
        spin.player.playSound(spin.player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASEDRUM, SoundCategory.MASTER, 0.8f, pitch);
        spin.player.playSound(spin.player.getLocation(), Sound.BLOCK_STONE_BUTTON_CLICK_ON, SoundCategory.MASTER, 0.5f, 1.2f + reel * 0.1f);
    }

    /** All reels stopped: gain paid, sound depending on the gain. */
    private void win(Spin spin) {
        settle(spin);
        Player player = spin.player;
        double m = spin.outcome.mult();
        if (m >= 80) {
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.MASTER, 0.9f, 1.0f);
            player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, SoundCategory.MASTER, 0.9f, 1.0f);
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.MASTER, 0.7f, 1.2f);
        } else if (m >= 20) {
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.MASTER, 0.7f, 1.2f);
            player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, SoundCategory.MASTER, 0.6f, 1.2f);
        } else if (m >= 1) {
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.MASTER, 0.6f, 1.4f);
        } else if (m > 0) {
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.MASTER, 0.6f, 0.8f);
        } else {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, SoundCategory.MASTER, 0.6f, 0.7f);
        }
    }

    /** End of the spin: the last frame stays shown, the reel positions are kept for the next spin. */
    private void finish(Spin spin) {
        int[] rest = new int[3];
        for (int r = 0; r < 3; r++) rest[r] = spin.pos[r][spin.pos[r].length - 1];
        rests.put(spin.player.getUniqueId(), new Rest(spin.view, rest));
        if (spin.task != null) spin.task.cancel();
        spin.task = null;
        spins.remove(spin.player.getUniqueId(), spin);
    }

    private void stop(Spin spin) {
        // Menu closed or player gone during the spin: the result is drawn and the bet taken, the gain is paid right
        // away without animation; the next window starts from rest.
        settle(spin);
        if (spin.task != null) spin.task.cancel();
        spin.task = null;
        spins.remove(spin.player.getUniqueId(), spin);
        rests.remove(spin.player.getUniqueId());
    }

    @Override
    void shutdown() {
        new ArrayList<>(spins.values()).forEach(this::stop);
    }

    /** Gain of the spin, paid once. */
    private void settle(Spin spin) {
        if (spin.settled) return;
        spin.settled = true;
        double m = spin.outcome.mult();
        Outcome o = spin.outcome;
        String detail = SYMBOLS[o.stops()[0]] + "-" + SYMBOLS[o.stops()[1]] + "-" + SYMBOLS[o.stops()[2]] + " x" + format(m);
        pay(spin.player, spin.bet, m, detail, m > 0 ? "x" + format(m) + (spin.bet > 0 ? " (%gain% jetons)" : "") : "perdu");
    }

    // ------------------------------------------------------------------ draw and gains

    /** Draws the middle symbol of each reel (weighted) and evaluates the line. */
    private static Outcome draw(ThreadLocalRandom rng) {
        int[] stops = new int[3];
        for (int r = 0; r < 3; r++) {
            int roll = rng.nextInt(WEIGHT_TOTAL);
            int s = 0;
            while (roll >= WEIGHTS[s]) roll -= WEIGHTS[s++];
            stops[r] = s;
        }
        return evaluate(stops);
    }

    /** Gain of the line: three identical, otherwise two cherries, otherwise one cherry. */
    static Outcome evaluate(int[] stops) {
        boolean[] win = new boolean[3];
        if (stops[0] == stops[1] && stops[1] == stops[2]) {
            java.util.Arrays.fill(win, true);
            return new Outcome(stops, TRIPLE[stops[0]], win);
        }
        int cherries = 0;
        for (int r = 0; r < 3; r++) {
            if (stops[r] == CHERRY) {
                win[r] = true;
                cherries++;
            }
        }
        return new Outcome(stops, cherries == 2 ? TWO : cherries == 1 ? ONE : 0, win);
    }

    /** Expected gain for a bet of 1, over every weighted combination (to check the tuning). */
    static double expectedReturn() {
        double total = 0;
        for (int a = 0; a < SYMBOLS.length; a++) {
            for (int b = 0; b < SYMBOLS.length; b++) {
                for (int c = 0; c < SYMBOLS.length; c++) {
                    double p = (double) WEIGHTS[a] * WEIGHTS[b] * WEIGHTS[c] / ((double) WEIGHT_TOTAL * WEIGHT_TOTAL * WEIGHT_TOTAL);
                    total += p * evaluate(new int[]{a, b, c}).mult();
                }
            }
        }
        return total;
    }
}

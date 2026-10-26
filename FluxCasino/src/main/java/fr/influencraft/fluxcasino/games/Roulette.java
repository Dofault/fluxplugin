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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Roulette ({@code [roulette] ...}): a still wheel of 25 pockets (the green 0 at the top, then the 24 numbers
 * clockwise, odd red, even black) on which a ball runs. The player bets on red (odd numbers), black (even
 * numbers) or a single number (0 to 24).
 *
 * <p>The result is drawn beforehand (one pocket out of 25), then the ball is animated in the window title, one
 * ItemsAdder glyph per frame (pack influcasino, assets/casino/roulette/gen_roulette_jeu.py): it laps the track
 * while slowing down (with a speed trail), then drops into its pocket in a single move, without bouncing, and
 * stays still: the ball in its pocket shows the number, no pocket animation (request of 2026-10-17). The number
 * shows on a button next to the spin button (the centre of the wheel is never touched). A click at each pocket
 * separator crossed.</p>
 *
 * <pre>
 * Actions: [roulette] spin | pari rouge|noir|numero | numero +N|-N|N
 *   %fluxroulette_pari%        current bet: rouge, noir or numero
 *   %fluxroulette_pari_nom%    "Rouge", "Noir" or "Numéro 17"
 *   %fluxroulette_numero%      chosen number (0 to 24)
 *   %fluxroulette_gain%        multiplier of the current bet ("2")
 *   %fluxroulette_dernier%     number of the last spin ("aucun" before the first and during a spin)
 *   %fluxroulette_dernier_nom% "17 rouge", "8 noir", "0 vert"
 *   %fluxroulette_last%        last spin ("17 rouge : x2 (20 jetons)", "8 noir : perdu", "aucun")
 *   %fluxroulette_count%       spins since the server started
 *   %fluxroulette_erreur%      reason of the last refused spin (not enough chips), empty otherwise
 * </pre>
 * Expected returns (25 pockets): red 12/25 x 2 = 0.96; black 12/25 x 2 = 0.96; number 1/25 x 24 = 0.96.
 */
final class Roulette extends CasinoGame {

    // Geometry (same as gen_roulette_jeu.py): continuous window coordinates.
    private static final double CX = 87.5, CY = 79.5;
    /** Radius of the track (running ball) and at rest (bottom of the pocket, receptacle from r 31 to 40). */
    private static final double R_TRACK = 55.5, R_REST = 35.5;
    private static final int POCKETS = 25;
    private static final double STEP = 360.0 / POCKETS;
    private static final int BALL_Y_MIN = 19, BALL_Y_MAX = 136;
    /** Number of each pocket, 0 = green at the top, then clockwise (read on the user's wheel). */
    static final int[] NUMBERS = {0, 17, 8, 21, 2, 13, 10, 19, 4, 15, 6, 23, 12, 1, 20, 9, 14, 3, 22, 7, 16, 11, 18, 5, 24};

    // Animation (ticks): laps of the track while slowing down, then a single drop into the pocket.
    private static final int ORBIT_TICKS = 84;
    private static final int DROP_TICKS = 14;
    /** Angle to the winning pocket (degrees) when the drop starts: the ball slows into it, without bouncing. */
    private static final double DROP_ANGLE = -28;

    enum Bet {
        ROUGE("rouge", "Rouge", 2.0), NOIR("noir", "Noir", 2.0), NUMERO("numero", "Numéro", 24.0);

        final String id, label;
        final double multiplier;

        Bet(String id, String label, double multiplier) {
            this.id = id;
            this.label = label;
            this.multiplier = multiplier;
        }

        static Bet of(String id) {
            for (Bet b : values()) if (b.id.equals(id)) return b;
            return null;
        }
    }

    private final Map<UUID, Bet> bets = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> numbers = new ConcurrentHashMap<>();
    /** Number of the last spin (absent during a spin: the button shows a question mark). */
    private final Map<UUID, Integer> lastNumber = new ConcurrentHashMap<>();
    private final Map<UUID, Spin> spins = new ConcurrentHashMap<>();

    Roulette(Plugin plugin) {
        super(plugin, "roulette");
    }

    // ------------------------------------------------------------------ rules

    /** Colour of a number: odd red, even black, 0 green (as drawn on the wheel). */
    static String color(int number) {
        return number == 0 ? "vert" : number % 2 == 1 ? "rouge" : "noir";
    }

    /** Bet multiplier for this bet when the ball stops on this number (0 when lost). */
    static double payout(Bet bet, int chosen, int result) {
        return switch (bet) {
            case ROUGE -> color(result).equals("rouge") ? bet.multiplier : 0;
            case NOIR -> color(result).equals("noir") ? bet.multiplier : 0;
            case NUMERO -> result == chosen ? bet.multiplier : 0;
        };
    }

    /** Expected gain for a bet of 1 (to check the tuning). */
    static double expectedReturn(Bet bet, int chosen) {
        double total = 0;
        for (int number : NUMBERS) total += payout(bet, chosen, number);
        return total / POCKETS;
    }

    // ------------------------------------------------------------------ button actions

    @Override
    void act(Player player, MenuView view, String argument) {
        String[] parts = argument.trim().toLowerCase(Locale.ROOT).split("\\s+");
        UUID id = player.getUniqueId();
        switch (parts[0]) {
            case "spin" -> spin(player, view);
            case "pari" -> {
                Bet bet = parts.length > 1 ? Bet.of(parts[1]) : null;
                if (bet != null) bets.put(id, bet);
            }
            case "numero" -> {
                if (parts.length < 2) return;
                int current = numbers.getOrDefault(id, 17);
                try {
                    int n = parts[1].startsWith("+") || parts[1].startsWith("-")
                            ? current + Integer.parseInt(parts[1].replace("+", ""))
                            : Integer.parseInt(parts[1]);
                    numbers.put(id, Math.floorMod(n, POCKETS));
                    bets.put(id, Bet.NUMERO);   // choosing a number means betting on it
                } catch (NumberFormatException ignored) {
                    // invalid argument: nothing changes
                }
            }
            default -> plugin.getLogger().warning("[roulette] action inconnue « " + argument + " »");
        }
        if (view != null) view.refreshButtons();
    }

    // ------------------------------------------------------------------ one spin

    /** A spin in progress: draw, bet taken, path of the ball. */
    private final class Spin {
        final Player player;
        final MenuView view;
        final String base;
        final int cursor;
        final long bet;
        final Bet kind;
        final int chosen;
        final int pocket;       // landing pocket
        final int result;       // its number
        final double mult;
        final double startAngle, orbitAngle;
        BukkitTask task;
        int tick;
        boolean settled;

        Spin(Player player, MenuView view, long bet, Bet kind, int chosen, int pocket) {
            this.player = player;
            this.view = view;
            this.base = view.title();
            this.cursor = view.titleCursor();
            this.bet = bet;
            this.kind = kind;
            this.chosen = chosen;
            this.pocket = pocket;
            this.result = NUMBERS[pocket];
            this.mult = payout(kind, chosen, result);
            // the track: 2 to 3 whole laps then the arc that brings the ball just before the winning pocket
            this.startAngle = ThreadLocalRandom.current().nextDouble(360);
            double end = pocket * STEP + DROP_ANGLE;
            this.orbitAngle = 720 + Math.floorMod((long) Math.round((end - startAngle) * 1000), 360_000L) / 1000.0;
        }

        /** Ball position at time t (ticks, fractional): clockwise angle from the top, radius. */
        double[] pose(double t) {
            if (t <= ORBIT_TICKS) {
                double u = Math.max(0, t) / ORBIT_TICKS;
                return new double[]{startAngle + orbitAngle * (1 - (1 - u) * (1 - u)), R_TRACK};
            }
            // single drop without bouncing: the angle reaches the pocket while slowing down, the radius goes down
            // a little later, and the ball stops right at the bottom of its pocket
            double u = Math.min(t - ORBIT_TICKS, DROP_TICKS) / DROP_TICKS;
            double angle = pocket * STEP + DROP_ANGLE * (1 - smooth(u));
            double radius = R_TRACK + (R_REST - R_TRACK) * smooth(Math.max(0, (u - 0.15) / 0.85));
            return new double[]{angle, radius};
        }
    }

    /** Smoothing 0 to 1 (zero speed at both ends). */
    private static double smooth(double u) {
        return u * u * (3 - 2 * u);
    }

    private void spin(Player player, MenuView view) {
        if (view == null || !view.isOpen()) return;
        UUID id = player.getUniqueId();
        Spin running = spins.get(id);
        if (running != null) {
            if (running.view.equals(view) && !running.settled) return; // the ball is already running
            stop(running); // spin of a previous window: settled before starting another
        }
        long bet = takeBet(player, view);
        if (bet < 0) return;
        lastNumber.remove(id);   // the previous number disappears while the ball runs
        Spin spin = new Spin(player, view, bet, bets.getOrDefault(id, Bet.ROUGE), numbers.getOrDefault(id, 17),
                ThreadLocalRandom.current().nextInt(POCKETS));
        spins.put(id, spin);
        view.refreshButtons(); // balance, previous number removed
        player.playSound(player.getLocation(), Sound.BLOCK_LEVER_CLICK, SoundCategory.MASTER, 0.8f, 0.8f);
        spin.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> step(spin), 0L, 1L);
    }

    /** One frame: ball (with its trail), sounds; the spin ends when the ball rests. */
    private void step(Spin spin) {
        Player player = spin.player;
        if (!spin.view.isOpen() || !player.isOnline()) {
            stop(spin);
            return;
        }
        int t = spin.tick++;
        int landed = ORBIT_TICKS + DROP_TICKS;
        double[] now = spin.pose(Math.min(t, landed));
        double[] before = spin.pose(Math.min(Math.max(t - 1, 0), landed));
        sounds(spin, t, now, before);

        TitleOverlay o = new TitleOverlay(spin.base, spin.cursor);
        // speed trail: two intermediate positions of the last tick, while the ball goes fast
        for (int g = 2; g >= 1; g--) {
            double[] ghost = spin.pose(Math.min(Math.max(t - g / 3.0, 0), landed));
            if (distance(ghost, now) < 4) continue;
            addBall(o, "roulette_traine_y", ghost);
        }
        addBall(o, "roulette_bille_y", now);
        spin.view.showTitle(o.toString());
        if (t >= landed) {
            // the ball is in its pocket: gain, last spin and count, then nothing moves any more
            win(spin);
            spin.view.refreshButtons();
            finish(spin);
        }
    }

    /** Pocket under an angle (degrees from the top, clockwise): each pocket covers 14.4°. */
    private static int pocketAt(double angle) {
        return Math.floorMod((int) Math.round(angle / STEP), POCKETS);
    }

    private static double[] xy(double[] pose) {
        double a = Math.toRadians(pose[0]);
        return new double[]{CX + pose[1] * Math.sin(a), CY - pose[1] * Math.cos(a)};
    }

    private static double distance(double[] a, double[] b) {
        double[] p = xy(a), q = xy(b);
        return Math.hypot(p[0] - q[0], p[1] - q[1]);
    }

    private static void addBall(TitleOverlay o, String glyph, double[] pose) {
        double[] p = xy(pose);
        int x = (int) Math.round(p[0] - 2.5);
        int y = Math.max(BALL_Y_MIN, Math.min(BALL_Y_MAX, (int) Math.round(p[1] - 2.5)));
        o.add(glyph + y, x, 5);
    }

    /** Sounds: the hum of the track, then a click at each pocket separator crossed. */
    private void sounds(Spin spin, int t, double[] now, double[] before) {
        Player player = spin.player;
        if (t == 0) return;
        if (t <= ORBIT_TICKS) {
            if (t % 3 == 0) {
                float speed = (float) (1 - (double) t / ORBIT_TICKS);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, SoundCategory.MASTER, 0.3f, 0.7f + 0.9f * speed);
            }
            return;
        }
        if (now[1] < 51 && pocketAt(now[0]) != pocketAt(before[0])) {
            float pitch = 1.1f + ThreadLocalRandom.current().nextFloat() * 0.5f;
            player.playSound(player.getLocation(), Sound.BLOCK_STONE_BUTTON_CLICK_ON, SoundCategory.MASTER, 0.7f, pitch);
        }
    }

    /** The ball rests: gain paid, sound depending on the gain. */
    private void win(Spin spin) {
        settle(spin);
        Player player = spin.player;
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASEDRUM, SoundCategory.MASTER, 0.8f, 0.9f);
        if (spin.mult >= 20) {
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.MASTER, 0.9f, 1.0f);
            player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, SoundCategory.MASTER, 0.9f, 1.0f);
        } else if (spin.mult > 0) {
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.MASTER, 0.6f, 1.4f);
        } else {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, SoundCategory.MASTER, 0.6f, 0.7f);
        }
    }

    private void finish(Spin spin) {
        if (spin.task != null) spin.task.cancel();
        spin.task = null;
        spins.remove(spin.player.getUniqueId(), spin);
    }

    private void stop(Spin spin) {
        // Menu closed or player gone during the spin: the draw is made and the bet taken, the gain is paid right away.
        settle(spin);
        finish(spin);
    }

    @Override
    void shutdown() {
        new ArrayList<>(spins.values()).forEach(this::stop);
    }

    /** Gain of the spin, paid once. */
    private void settle(Spin spin) {
        if (spin.settled) return;
        spin.settled = true;
        String what = spin.result + " " + color(spin.result);
        String detail = what + " pari=" + spin.kind.id + (spin.kind == Bet.NUMERO ? ":" + spin.chosen : "") + " x" + format(spin.mult);
        pay(spin.player, spin.bet, spin.mult, detail,
                spin.mult > 0 ? what + " : x" + format(spin.mult) + (spin.bet > 0 ? " (%gain% jetons)" : "") : what + " : perdu");
        lastNumber.put(spin.player.getUniqueId(), spin.result);
    }

    // ------------------------------------------------------------------ placeholders

    @Override
    String extraPlaceholder(Player player, String params) {
        UUID id = player.getUniqueId();
        Bet bet = bets.getOrDefault(id, Bet.ROUGE);
        int number = numbers.getOrDefault(id, 17);
        return switch (params) {
            case "pari" -> bet.id;
            case "pari_nom" -> bet == Bet.NUMERO ? "Numéro " + number : bet.label;
            case "numero" -> String.valueOf(number);
            case "gain" -> format(bet.multiplier);
            case "dernier" -> lastNumber.containsKey(id) ? String.valueOf(lastNumber.get(id)) : "aucun";
            case "dernier_nom" -> lastNumber.containsKey(id) ? lastNumber.get(id) + " " + color(lastNumber.get(id)) : "aucun";
            default -> null;
        };
    }
}

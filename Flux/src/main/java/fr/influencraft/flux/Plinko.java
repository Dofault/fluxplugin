package fr.influencraft.flux;

import fr.influencraft.flux.session.MenuSession;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Plinko ({@code [plinko]}) : une bille lâchée en haut d'une pyramide de clous rebondit jusqu'à une case
 * de gains. Le résultat sort d'une vraie simulation physique (gravité, rebonds amortis, parois), calculée
 * d'un coup au clic, puis rejouée image par image dans le titre de la fenêtre (une image par tick) : la
 * bille et les clous touchés sont des glyphes ItemsAdder posés au pixel près (pack influplinko, généré par
 * assets/plinko/gen_plinko.py, même géométrie qu'ici). Un son par clou touché, un son d'arrivée selon le gain.
 *
 * <pre>
 *   %fluxplinko_last%   multiplicateur du dernier lancer du joueur (« x1.5 », « aucun » avant le premier)
 *   %fluxplinko_count%  lancers du joueur depuis le démarrage
 *   %fluxplinko_erreur% raison du dernier lancer refusé (pas assez de jetons), vide sinon
 * Avec FluxCasino : la mise du joueur (en jetons) est prise au lancer et le gain versé à l'arrivée ; sans
 * lui, le Plinko est gratuit.
 * </pre>
 * Réglages retenus sur 2 000 lancers simulés (2026-10-17) : cloche proche d'une loi binomiale (centre
 * 20 %, bords 0,05 %), chute de 3 s environ, espérance des multiplicateurs 0,94.
 */
public final class Plinko extends PlaceholderExpansion {

    // Géométrie (identique à gen_plinko.py) : coordonnées en px de la fenêtre.
    private static final int ROWS = 10, SX = 14, SY = 13, Y0 = 48, CX = 88;
    private static final double START_Y = 38, BUCKET_TOP = 172, REST = 176;
    private static final int BUCKET_L = 13;
    static final String[] MULTIPLIERS = {"10", "3", "1.5", "1.1", "0.6", "0.4", "0.6", "1.1", "1.5", "3", "10"};

    // Physique (par tick ; une image = 1,5 tick, soit 12 sous-pas de 1/8).
    private static final double RB = 2.0, RP = 1.5, G = 0.6, E = 0.3, VMAX = 6, KICK = 0.2, DAMP = 0.5;
    private static final int SUBSTEPS = 8, SUBSTEPS_PER_FRAME = 12, MAX_FRAMES = 400;
    private static final int LIT_FRAMES = 3, BALL_Y_MIN = 30, BALL_Y_MAX = 181, MAX_BALLS = 12, HOLD = 12;

    private record Peg(double x, double y, int row) {
    }

    /** Une image : position de la bille, clous touchés pendant cette image. */
    private record Frame(int x, int y, List<Integer> hits) {
    }

    private static final List<Peg> PEGS = new ArrayList<>();

    static {
        for (int k = 0; k < ROWS; k++) {
            for (int i = 0; i < k + 3; i++) {
                PEGS.add(new Peg(CX + (i - (k + 2) / 2.0) * SX, Y0 + SY * k, k));
            }
        }
    }

    private final Flux plugin;
    private final Map<UUID, String> last = new ConcurrentHashMap<>();
    private final Map<UUID, String> error = new ConcurrentHashMap<>();
    private final CasinoLink casino;
    private final Map<UUID, Integer> count = new ConcurrentHashMap<>();

    Plinko(Flux plugin) {
        this.plugin = plugin;
        this.casino = new CasinoLink(plugin);
    }

    /** Billes en jeu d'un joueur : un plateau par menu ouvert, une image par tick tant qu'il bouge. */
    private final class Board {
        final Player player;
        final MenuSession session;
        final String base;
        final int cursor;
        final List<Ball> balls = new ArrayList<>();
        final int[] litUntil = new int[PEGS.size()];
        BukkitTask task;
        int tick;

        Board(Player player, MenuSession session) {
            this.player = player;
            this.session = session;
            this.base = session.fullTitle(player);
            this.cursor = 8 + PathRenderer.advance(base);
        }
    }

    /** Une bille : sa chute simulée d'avance, l'image en cours, le tick de son arrivée (-1 en vol). */
    private static final class Ball {
        final List<Frame> frames;
        final int bucket;
        final long bet;
        int index;
        int landedAt = -1;

        Ball(List<Frame> frames, int bucket, long bet) {
            this.frames = frames;
            this.bucket = bucket;
            this.bet = bet;
        }
    }

    private final Map<UUID, Board> boards = new ConcurrentHashMap<>();

    /**
     * Lâche une bille. Plusieurs billes peuvent tomber en même temps (au plus MAX_BALLS) : chaque clic en
     * ajoute une, avec sa propre chute. Une bille arrivée reste dans sa case HOLD images tant que d'autres
     * tombent, puis disparaît ; quand plus rien ne tombe, les dernières arrivées restent affichées.
     */
    public void launch(Player player, MenuSession session) {
        if (session == null || !session.isOpen()) return;
        Board board = boards.get(player.getUniqueId());
        if (board == null || board.session != session) {
            if (board != null) stop(board); // ancien plateau : billes en vol réglées
            board = new Board(player, session);
            boards.put(player.getUniqueId(), board);
        }
        if (board.balls.stream().noneMatch(b -> b.landedAt < 0)) board.balls.clear(); // nouvelle salve
        if (board.balls.size() >= MAX_BALLS) return;
        // Jetons (FluxCasino) : la mise est prise au lancer ; sans jetons suffisants, la bille ne part pas.
        long bet = 0;
        if (casino.available()) {
            bet = casino.placeBet(player, "plinko");
            if (bet < 0) {
                error.put(player.getUniqueId(), "Pas assez de jetons pour cette mise.");
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, SoundCategory.MASTER, 0.6f, 1.0f);
                plugin.menus().renderButtons(player, session);
                return;
            }
        }
        error.remove(player.getUniqueId());
        List<Frame> frames = new ArrayList<>();
        int bucket = simulate(ThreadLocalRandom.current(), frames);
        board.balls.add(new Ball(frames, bucket, bet));
        if (bet > 0) plugin.menus().renderButtons(player, session); // solde
        if (board.task == null) {
            Board b = board;
            board.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> step(b), 0L, 1L);
        }
    }

    /** Une image du plateau : avance chaque bille, sons, puis titre complet. */
    private void step(Board board) {
        Player player = board.player;
        if (!board.session.isOpen() || !player.isOnline()) {
            stop(board);
            return;
        }
        int t = ++board.tick;
        boolean landed = false;
        for (Ball ball : board.balls) {
            if (ball.landedAt >= 0) continue;
            Frame frame = ball.frames.get(ball.index);
            List<Integer> previous = ball.index > 0 ? ball.frames.get(ball.index - 1).hits() : List.of();
            for (int p : frame.hits()) {
                board.litUntil[p] = t + LIT_FRAMES;
                // un son par choc : pas tant que la bille reste appuyée sur le même clou (contact
                // à chaque image quand elle stagne dessus)
                if (previous.contains(p)) continue;
                float pitch = 0.9f + PEGS.get(p).row() * 0.08f;
                player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_HIT, SoundCategory.MASTER, 0.6f, pitch);
            }
            if (ball.index == ball.frames.size() - 1) {
                ball.landedAt = t;
                land(player, MULTIPLIERS[ball.bucket], ball.bet);
                landed = true;
            } else {
                ball.index++;
            }
        }
        boolean falling = board.balls.stream().anyMatch(b -> b.landedAt < 0);
        if (falling) board.balls.removeIf(b -> b.landedAt >= 0 && t - b.landedAt > HOLD);

        TitleOverlay o = new TitleOverlay(board.base, board.cursor);
        boolean lit = false;
        for (int p = 0; p < PEGS.size(); p++) {
            if (board.litUntil[p] > t) {
                Peg peg = PEGS.get(p);
                o.add("plinko_clou_y" + ((int) peg.y() - 1), (int) peg.x() - 1, 3);
                lit = true;
            }
        }
        for (Ball ball : board.balls) {
            if (ball.landedAt >= 0) o.add("plinko_case", CX + (ball.bucket - 5) * SX - BUCKET_L / 2, BUCKET_L);
        }
        for (Ball ball : board.balls) {
            Frame frame = ball.frames.get(ball.index);
            o.add("plinko_bille_y" + Math.max(BALL_Y_MIN, Math.min(BALL_Y_MAX, frame.y() - 2)), frame.x() - 2, 5);
        }
        plugin.menus().showTitle(player, board.session, o.toString());
        if (landed) plugin.menus().renderButtons(player, board.session); // dernier lancer, nombre de lancers
        if (!falling && !lit) {
            board.task.cancel();
            board.task = null;
        }
    }

    private void stop(Board board) {
        // Menu fermé ou joueur parti pendant la chute : chaque bille en vol a déjà son résultat (simulé
        // d'avance) et sa mise prise, son gain est versé tout de suite, sans animation.
        for (Ball ball : board.balls) {
            if (ball.landedAt < 0) {
                ball.landedAt = board.tick;
                settle(board.player, MULTIPLIERS[ball.bucket], ball.bet);
            }
        }
        if (board.task != null) board.task.cancel();
        board.task = null;
        boards.remove(board.player.getUniqueId(), board);
    }

    /** Arrêt du plugin : toutes les billes en vol sont réglées (gains versés). */
    void shutdown() {
        new ArrayList<>(boards.values()).forEach(this::stop);
    }

    /** Gain d'une bille (mise x multiplicateur, arrondi vers le bas), versé et retenu pour l'affichage. */
    private void settle(Player player, String multiplier, long bet) {
        long gain = (long) Math.floor(bet * Double.parseDouble(multiplier) + 1e-9);
        if (bet > 0 && casino.available()) casino.payout(player, gain, "plinko", "x" + multiplier);
        last.put(player.getUniqueId(), "x" + multiplier + (bet > 0 ? " (" + gain + " jetons)" : ""));
        count.merge(player.getUniqueId(), 1, Integer::sum);
    }

    /** Arrivée d'une bille : gain, son selon le gain. */
    private void land(Player player, String multiplier, long bet) {
        settle(player, multiplier, bet);
        double m = Double.parseDouble(multiplier);
        if (m >= 3) {
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.MASTER, 0.7f, 1.2f);
        } else if (m >= 1) {
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.MASTER, 0.6f, 1.4f);
        } else {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, SoundCategory.MASTER, 0.7f, 0.8f);
        }
    }

    // ------------------------------------------------------------------ physique

    /** Simule une chute, remplit frames (une par image) et renvoie l'indice de la case (0 à 10). */
    private static int simulate(ThreadLocalRandom rng, List<Frame> frames) {
        double x = CX + rng.nextDouble(-1.5, 1.5), y = START_Y, vx = 0, vy = 0;
        double dt = 1.0 / SUBSTEPS;
        for (int f = 0; f < MAX_FRAMES; f++) {
            List<Integer> hits = new ArrayList<>();
            for (int s = 0; s < SUBSTEPS_PER_FRAME; s++) {
                vy += G * dt;
                x += vx * dt;
                y += vy * dt;
                for (int p = 0; p < PEGS.size(); p++) {
                    Peg peg = PEGS.get(p);
                    double dx = x - peg.x(), dy = y - peg.y();
                    double d = Math.hypot(dx, dy);
                    if (d >= RB + RP || d < 1e-6) continue;
                    double nx = dx / d, ny = dy / d;
                    x = peg.x() + nx * (RB + RP);
                    y = peg.y() + ny * (RB + RP);
                    double vn = vx * nx + vy * ny;
                    if (vn < 0) {
                        vx -= (1 + E) * vn * nx;
                        vy -= (1 + E) * vn * ny;
                        vx *= DAMP;
                        // un peu de hasard tangentiel, plus fort si la bille tombe pile sur le sommet
                        double kick = rng.nextDouble(-KICK, KICK) + (Math.abs(nx) < 0.25 ? (rng.nextBoolean() ? 0.25 : -0.25) : 0);
                        vx += -ny * kick;
                        vy += nx * kick;
                        if (!hits.contains(p)) hits.add(p);
                    }
                }
                if (y < BUCKET_TOP) {
                    double n = 3 + Math.max(0, Math.min(ROWS - 1, (y - Y0) / SY));
                    double half = (n - 1) / 2 * SX + 7 - RB;
                    if (x < CX - half) {
                        x = CX - half;
                        vx = Math.abs(vx) * E;
                    }
                    if (x > CX + half) {
                        x = CX + half;
                        vx = -Math.abs(vx) * E;
                    }
                }
                // en équilibre sur un clou : une poussée la fait tomber d'un côté
                if (!hits.isEmpty() && Math.abs(vx) < 0.15 && Math.abs(vy) < 0.3) {
                    vx += rng.nextBoolean() ? 0.6 : -0.6;
                }
                double speed = Math.hypot(vx, vy);
                if (speed > VMAX) {
                    vx = vx / speed * VMAX;
                    vy = vy / speed * VMAX;
                }
            }
            if (y >= BUCKET_TOP) {
                int bucket = (int) Math.max(0, Math.min(10, Math.round((x - (CX - 5 * SX)) / SX)));
                int cx = CX + (bucket - 5) * SX;
                frames.add(new Frame((int) Math.round(x), (int) Math.round(y), hits));
                while (y < REST) {
                    y = Math.min(REST, y + Math.max(1.5, vy));
                    x += (cx - x) * 0.4;
                    frames.add(new Frame((int) Math.round(x), (int) Math.round(y), List.of()));
                }
                frames.add(new Frame(cx, (int) REST, List.of()));
                return bucket;
            }
            frames.add(new Frame((int) Math.round(x), (int) Math.round(y), hits));
        }
        // ne devrait pas arriver (bille bloquée) : case la plus proche
        int bucket = (int) Math.max(0, Math.min(10, Math.round((x - (CX - 5 * SX)) / SX)));
        frames.add(new Frame(CX + (bucket - 5) * SX, (int) REST, List.of()));
        return bucket;
    }

    // ------------------------------------------------------------------ placeholders

    static Plinko register(Flux plugin) {
        Plinko plinko = new Plinko(plugin);
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) plinko.register();
        return plinko;
    }

    @Override
    public String getIdentifier() {
        return "fluxplinko";
    }

    @Override
    public String getAuthor() {
        return "Influencraft";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, String params) {
        if (player == null) return "";
        return switch (params) {
            case "last" -> last.getOrDefault(player.getUniqueId(), "aucun");
            case "count" -> String.valueOf(count.getOrDefault(player.getUniqueId(), 0));
            case "erreur" -> error.getOrDefault(player.getUniqueId(), "");
            default -> null;
        };
    }
}

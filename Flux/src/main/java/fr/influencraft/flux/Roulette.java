package fr.influencraft.flux;

import fr.influencraft.flux.session.MenuSession;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Roulette ({@code [roulette] ...}) : une roue de 25 cases (le 0 vert en haut, puis les 24 numéros dans le sens
 * horaire, impairs rouges, pairs noirs), immobile, sur laquelle une bille tourne. Le joueur parie sur le rouge
 * (numéros impairs), le noir (numéros pairs) ou un numéro précis (0 à 24).
 *
 * <p>Le résultat est tiré d'avance (une case sur 25), puis la bille est animée dans le titre de la fenêtre,
 * un glyphe ItemsAdder par image (pack influcasino, assets/casino/roulette/gen_roulette_jeu.py) : elle fait
 * des tours de piste en ralentissant (avec une traînée de vitesse), puis tombe dans sa case d'un seul mouvement,
 * sans rebond, et ne bouge plus : la bille dans son trou suffit à montrer le numéro, aucune animation de case
 * (demande du 2026-10-17). Le numéro tombé s'affiche sur un bouton à côté du lancer (le centre de la roue n'est
 * jamais touché). Un cliquetis à chaque séparation de case franchie.
 *
 * <pre>
 * Actions : [roulette] spin | pari rouge|noir|numero | numero +N|-N|N
 *   %fluxroulette_pari%     pari courant : rouge, noir ou numero
 *   %fluxroulette_pari_nom% « Rouge », « Noir » ou « Numéro 17 »
 *   %fluxroulette_numero%   numéro choisi (0 à 24)
 *   %fluxroulette_gain%     multiplicateur du pari courant (« 2 »)
 *   %fluxroulette_dernier%  numéro tombé au dernier tour (« aucun » avant le premier et pendant un tour)
 *   %fluxroulette_dernier_nom% « 17 rouge », « 8 noir », « 0 vert »
 *   %fluxroulette_last%     dernier tour (« 17 rouge : x2 (20 jetons) », « 8 noir : perdu », « aucun »)
 *   %fluxroulette_count%    tours du joueur depuis le démarrage
 *   %fluxroulette_erreur%   raison du dernier tour refusé (pas assez de jetons), vide sinon
 * </pre>
 * Avec FluxCasino : la mise (jetons) est prise au lancer et le gain versé à l'arrivée de la bille (ou tout
 * de suite si le menu se ferme : le tirage est déjà fait) ; sans lui, la roulette est gratuite.
 * Espérances (25 cases) : rouge 12/25 x 2 = 0,96 ; noir 12/25 x 2 = 0,96 ; numéro 1/25 x 24 = 0,96.
 */
public final class Roulette extends PlaceholderExpansion {

    // Géométrie (identique à gen_roulette_jeu.py) : coordonnées continues de la fenêtre.
    private static final double CX = 87.5, CY = 79.5;
    /** Rayon de la piste (bille en course) et de repos (au fond de la case, réceptacle de r 31 à 40). */
    private static final double R_TRACK = 55.5, R_REST = 35.5;
    private static final int POCKETS = 25;
    private static final double STEP = 360.0 / POCKETS;
    private static final int BALL_Y_MIN = 19, BALL_Y_MAX = 136;
    /** Numéro de chaque case, 0 = vert en haut, puis dans le sens horaire (relevé sur la roue de l'utilisateur). */
    static final int[] NUMBERS = {0, 17, 8, 21, 2, 13, 10, 19, 4, 15, 6, 23, 12, 1, 20, 9, 14, 3, 22, 7, 16, 11, 18, 5, 24};

    // Animation (ticks) : tours de piste en ralentissant, puis une chute d'un seul mouvement dans la case.
    private static final int ORBIT_TICKS = 84;
    private static final int DROP_TICKS = 14;
    /** Écart à la case gagnante (degrés) au début de la chute : la bille y arrive en ralentissant, sans rebond. */
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

    private final Flux plugin;
    private final CasinoLink casino;
    private final Map<UUID, Bet> bets = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> numbers = new ConcurrentHashMap<>();
    private final Map<UUID, String> last = new ConcurrentHashMap<>();
    /** Numéro tombé au dernier tour (absent pendant un tour : le bouton montre un point d'interrogation). */
    private final Map<UUID, Integer> lastNumber = new ConcurrentHashMap<>();
    private final Map<UUID, String> error = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> count = new ConcurrentHashMap<>();
    private final Map<UUID, Spin> spins = new ConcurrentHashMap<>();

    Roulette(Flux plugin) {
        this.plugin = plugin;
        this.casino = new CasinoLink(plugin);
    }

    // ------------------------------------------------------------------ règles

    /** Couleur d'un numéro : impair rouge, pair noir, le 0 vert (comme la roue dessinée). */
    static String color(int number) {
        return number == 0 ? "vert" : number % 2 == 1 ? "rouge" : "noir";
    }

    /** Multiplicateur de la mise pour ce pari quand la bille s'arrête sur ce numéro (0 si perdu). */
    static double payout(Bet bet, int chosen, int result) {
        return switch (bet) {
            case ROUGE -> color(result).equals("rouge") ? bet.multiplier : 0;
            case NOIR -> color(result).equals("noir") ? bet.multiplier : 0;
            case NUMERO -> result == chosen ? bet.multiplier : 0;
        };
    }

    /** Espérance du gain pour 1 de mise (vérification des réglages). */
    static double expectedReturn(Bet bet, int chosen) {
        double total = 0;
        for (int number : NUMBERS) total += payout(bet, chosen, number);
        return total / POCKETS;
    }

    // ------------------------------------------------------------------ actions des boutons

    /** Point d'entrée de l'action {@code [roulette] <arg>}. */
    public void act(Player player, MenuSession session, String arg) {
        String[] parts = arg.trim().toLowerCase(Locale.ROOT).split("\\s+");
        UUID id = player.getUniqueId();
        switch (parts[0]) {
            case "spin" -> spin(player, session);
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
                    bets.put(id, Bet.NUMERO);   // choisir un numéro, c'est parier dessus
                } catch (NumberFormatException ignored) {
                    // argument invalide : rien ne change
                }
            }
            default -> plugin.getLogger().warning("[roulette] action inconnue « " + arg + " »");
        }
        if (session != null) plugin.menus().renderButtons(player, session);
    }

    // ------------------------------------------------------------------ un tour

    /** Un tour en cours : tirage, mise prise, trajectoire de la bille. */
    private final class Spin {
        final Player player;
        final MenuSession session;
        final String base;
        final int cursor;
        final long bet;
        final Bet kind;
        final int chosen;
        final int pocket;       // case d'arrivée
        final int result;       // son numéro
        final double mult;
        final double startAngle, orbitAngle;
        BukkitTask task;
        int tick;
        boolean settled;

        Spin(Player player, MenuSession session, long bet, Bet kind, int chosen, int pocket) {
            this.player = player;
            this.session = session;
            this.base = session.fullTitle(player);
            this.cursor = 8 + PathRenderer.advance(base);
            this.bet = bet;
            this.kind = kind;
            this.chosen = chosen;
            this.pocket = pocket;
            this.result = NUMBERS[pocket];
            this.mult = payout(kind, chosen, result);
            // la piste : de 2 à 3 tours entiers puis l'arc qui amène la bille juste avant la case gagnante
            this.startAngle = ThreadLocalRandom.current().nextDouble(360);
            double end = pocket * STEP + DROP_ANGLE;
            this.orbitAngle = 720 + Math.floorMod((long) Math.round((end - startAngle) * 1000), 360_000L) / 1000.0;
        }

        /** Position de la bille à l'instant t (en ticks, fractionnaire) : angle horaire depuis le haut, rayon. */
        double[] pose(double t) {
            if (t <= ORBIT_TICKS) {
                double u = Math.max(0, t) / ORBIT_TICKS;
                return new double[]{startAngle + orbitAngle * (1 - (1 - u) * (1 - u)), R_TRACK};
            }
            // chute d'un seul mouvement, sans rebond : l'angle rejoint la case en ralentissant (la bille part
            // de l'arrêt de la piste), le rayon descend un peu après, et la bille s'arrête pile au fond de sa case
            double u = Math.min(t - ORBIT_TICKS, DROP_TICKS) / DROP_TICKS;
            double angle = pocket * STEP + DROP_ANGLE * (1 - smooth(u));
            double radius = R_TRACK + (R_REST - R_TRACK) * smooth(Math.max(0, (u - 0.15) / 0.85));
            return new double[]{angle, radius};
        }
    }

    /** Lissage 0 à 1 (départ et arrivée à vitesse nulle). */
    private static double smooth(double u) {
        return u * u * (3 - 2 * u);
    }

    private void spin(Player player, MenuSession session) {
        if (session == null || !session.isOpen()) return;
        UUID id = player.getUniqueId();
        Spin running = spins.get(id);
        if (running != null) {
            if (running.session == session && !running.settled) return; // la bille tourne déjà
            stop(running); // tour d'une ancienne fenêtre : réglé avant d'en commencer un autre
        }
        long bet = 0;
        if (casino.available()) {
            bet = casino.placeBet(player, "roulette");
            if (bet < 0) {
                error.put(id, "Pas assez de jetons pour cette mise.");
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, SoundCategory.MASTER, 0.6f, 1.0f);
                plugin.menus().renderButtons(player, session);
                return;
            }
        }
        error.remove(id);
        lastNumber.remove(id);   // le numéro précédent disparaît pendant que la bille tourne
        Bet kind = bets.getOrDefault(id, Bet.ROUGE);
        int chosen = numbers.getOrDefault(id, 17);
        Spin spin = new Spin(player, session, bet, kind, chosen, ThreadLocalRandom.current().nextInt(POCKETS));
        spins.put(id, spin);
        plugin.menus().renderButtons(player, session); // solde, numéro précédent retiré
        player.playSound(player.getLocation(), Sound.BLOCK_LEVER_CLICK, SoundCategory.MASTER, 0.8f, 0.8f);
        spin.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> step(spin), 0L, 1L);
    }

    /** Une image : bille (avec sa traînée), sons ; le tour s'achève quand la bille est posée. */
    private void step(Spin spin) {
        Player player = spin.player;
        if (!spin.session.isOpen() || !player.isOnline()) {
            stop(spin);
            return;
        }
        int t = spin.tick++;
        int landed = ORBIT_TICKS + DROP_TICKS;
        double[] now = spin.pose(Math.min(t, landed));
        double[] before = spin.pose(Math.min(Math.max(t - 1, 0), landed));
        sounds(spin, t, now, before);

        TitleOverlay o = new TitleOverlay(spin.base, spin.cursor);
        // traînée de vitesse : deux positions intermédiaires du dernier tick, tant que la bille va vite
        for (int g = 2; g >= 1; g--) {
            double[] ghost = spin.pose(Math.min(Math.max(t - g / 3.0, 0), landed));
            if (distance(ghost, now) < 4) continue;
            addBall(o, "roulette_traine_y", ghost);
        }
        addBall(o, "roulette_bille_y", now);
        plugin.menus().showTitle(player, spin.session, o.toString());
        if (t >= landed) {
            // la bille est dans son trou : gain, dernier tour et nombre de tours, puis plus rien ne bouge
            win(spin);
            plugin.menus().renderButtons(player, spin.session);
            finish(spin);
        }
    }

    /** Case sous un angle (degrés depuis le haut, sens horaire) : chaque case couvre 14,4° centrés sur son numéro d'ordre. */
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

    /** Sons : ronronnement de la piste, puis un cliquetis à chaque séparation de case franchie. */
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
        // en chute : une séparation franchie (la case change) = un coup de bille
        if (now[1] < 51 && pocketAt(now[0]) != pocketAt(before[0])) {
            float pitch = 1.1f + ThreadLocalRandom.current().nextFloat() * 0.5f;
            player.playSound(player.getLocation(), Sound.BLOCK_STONE_BUTTON_CLICK_ON, SoundCategory.MASTER, 0.7f, pitch);
        }
    }

    /** La bille est posée : gain versé, son selon le gain. */
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
        // Menu fermé ou joueur parti pendant le tour : le tirage est fait et la mise prise, le gain est
        // versé tout de suite, sans animation.
        settle(spin);
        finish(spin);
    }

    /** Arrêt du plugin : tous les tours en cours sont réglés (gains versés). */
    void shutdown() {
        new java.util.ArrayList<>(spins.values()).forEach(this::stop);
    }

    /** Gain du tour (mise x multiplicateur, arrondi vers le bas) : versé une seule fois, retenu pour l'affichage. */
    private void settle(Spin spin) {
        if (spin.settled) return;
        spin.settled = true;
        Player player = spin.player;
        long gain = (long) Math.floor(spin.bet * spin.mult + 1e-9);
        String what = spin.result + " " + color(spin.result);
        if (spin.bet > 0 && casino.available()) {
            casino.payout(player, gain, "roulette", what + " pari=" + spin.kind.id
                    + (spin.kind == Bet.NUMERO ? ":" + spin.chosen : "") + " x" + format(spin.mult));
        }
        String shown = spin.mult > 0 ? what + " : x" + format(spin.mult) + (spin.bet > 0 ? " (" + gain + " jetons)" : "")
                : what + " : perdu";
        last.put(player.getUniqueId(), shown);
        lastNumber.put(player.getUniqueId(), spin.result);
        count.merge(player.getUniqueId(), 1, Integer::sum);
    }

    private static String format(double m) {
        return m == Math.floor(m) ? String.valueOf((long) m) : String.valueOf(m);
    }

    // ------------------------------------------------------------------ placeholders

    static Roulette register(Flux plugin) {
        Roulette roulette = new Roulette(plugin);
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) roulette.register();
        return roulette;
    }

    @Override
    public String getIdentifier() {
        return "fluxroulette";
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
            case "last" -> last.getOrDefault(id, "aucun");
            case "count" -> String.valueOf(count.getOrDefault(id, 0));
            case "erreur" -> error.getOrDefault(id, "");
            default -> null;
        };
    }
}

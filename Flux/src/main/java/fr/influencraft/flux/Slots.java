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
 * Machine à sous ({@code [slots]}) : trois rouleaux de huit symboles, une ligne de gain (la case du milieu).
 * Le résultat est tiré d'avance (un tirage pondéré par rouleau), puis les rouleaux sont animés dans le titre
 * de la fenêtre, un par tick : chacun est un glyphe ItemsAdder (pack influcasino, assets/slots/gen_slots.py)
 * qui montre trois cases de la bande des symboles à une position donnée (par pas de 4 px, nette ou floue
 * selon la vitesse). Les rouleaux accélèrent, tournent, ralentissent puis s'arrêtent l'un après l'autre
 * avec un petit rebond ; ampoules de la caisse en chenillard ; case gagnante encadrée qui clignote.
 *
 * <pre>
 *   %fluxslots_last%   résultat du dernier tour du joueur (« x2 (20 jetons) », « perdu », « aucun » avant le premier)
 *   %fluxslots_count%  tours du joueur depuis le démarrage
 *   %fluxslots_erreur% raison du dernier tour refusé (pas assez de jetons), vide sinon
 * Avec FluxCasino : la mise du joueur (en jetons) est prise au lancer et le gain versé à l'arrêt des rouleaux
 * (ou tout de suite si le menu se ferme : le résultat est déjà tiré) ; sans lui, la machine est gratuite.
 * </pre>
 * Gains (mise x multiplicateur, arrondi vers le bas) : trois symboles identiques selon {@link #TRIPLE}, deux
 * cerises (n'importe où sur la ligne) x{@link #TWO}, une cerise x{@link #ONE}. Espérance retenue 0,937 pour
 * 1 jeton misé (calculée sur les 68 921 combinaisons, cf. docs/technique/casino.md).
 */
public final class Slots extends PlaceholderExpansion {

    // Géométrie (identique à gen_slots.py) : coordonnées en px de la fenêtre.
    private static final int CELL_H = 28, REEL_W = 32, PERIOD = 224, STEP = 4;
    private static final int WIN_Y = 60;
    private static final int[] REEL_X = {36, 72, 108};
    private static final int CAISSE_X = 27, CAISSE_W = 122;

    /** Symboles dans l'ordre de la bande (de haut en bas), comme ORDRE de assets/slots/symboles.py. */
    static final String[] SYMBOLS = {"cerise", "cloche", "citron", "couronne", "prune", "sept", "etoile", "diamant"};
    /** Poids de tirage de chaque symbole sur un rouleau (total 41). */
    private static final int[] WEIGHTS = {10, 5, 9, 2, 7, 1, 4, 3};
    /** Gain de trois symboles identiques sur la ligne, en multiple de la mise. */
    private static final double[] TRIPLE = {7, 30, 12, 80, 20, 100, 45, 60};
    private static final double TWO = 2, ONE = 0.5;
    private static final int CHERRY = 0;
    private static final int WEIGHT_TOTAL = java.util.stream.IntStream.of(WEIGHTS).sum();

    // Animation (en ticks). Un rouleau : accélération, croisière rapide, ralentissement, rebond.
    private static final int[] ACCEL = {8, 16, 24, 32};
    private static final int[] BRAKE = {112, 84, 60, 40, 24, 12, 4, -4, 0}; // distance restante, de la fin de la croisière à l'arrêt
    private static final int[] CRUISE_WANTED = {10, 20, 30};
    private static final int BLUR_FROM = 16;      // pas par tick à partir duquel on affiche la version floue
    private static final int FLASH_TICKS = 24;    // clignotement d'un gain
    private static final int FLASH_BIG_TICKS = 44;

    /** Résultat d'un tour : symboles du milieu, multiplicateur, cases gagnantes. */
    private record Outcome(int[] stops, double mult, boolean[] win) {
    }

    private final Flux plugin;
    private final CasinoLink casino;
    private final Map<UUID, String> last = new ConcurrentHashMap<>();
    private final Map<UUID, String> error = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> count = new ConcurrentHashMap<>();
    private final Map<UUID, Spin> spins = new ConcurrentHashMap<>();
    /** Position d'arrêt des rouleaux, par joueur, tant qu'il reste dans la même fenêtre. */
    private final Map<UUID, Rest> rests = new ConcurrentHashMap<>();

    private record Rest(MenuSession session, int[] pos) {
    }

    Slots(Flux plugin) {
        this.plugin = plugin;
        this.casino = new CasinoLink(plugin);
    }

    /** Un tour en cours : tirage, trajectoire de chaque rouleau, mise prise, gain à verser. */
    private final class Spin {
        final Player player;
        final MenuSession session;
        final String base;
        final int cursor;
        final long bet;
        final Outcome outcome;
        final int[][] pos = new int[3][];        // position de chaque rouleau à chaque tick
        final boolean[][] blur = new boolean[3][];
        final int moveTicks;                     // tick d'arrêt du dernier rouleau
        final int flashTicks;
        BukkitTask task;
        int tick;
        boolean settled;

        Spin(Player player, MenuSession session, long bet, Outcome outcome, int[] from) {
            this.player = player;
            this.session = session;
            this.base = session.fullTitle(player);
            this.cursor = 8 + PathRenderer.advance(base);
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

    /** Trajectoire d'un rouleau : position de la bande à chaque tick et version (nette ou floue) à afficher. */
    record Track(int[] pos, boolean[] blur) {
    }

    /**
     * Trajectoire d'un rouleau de l'arrêt précédent {@code from} à la cible {@code target} (positions de la
     * bande en px, multiples de STEP) : accélération, croisière d'environ {@code cruiseWanted} cases, freinage
     * puis léger rebond, arrêt exactement sur la cible.
     */
    static Track track(int from, int target, int cruiseWanted) {
        int brakeStart = BRAKE[0];
        int accel = 0;
        for (int v : ACCEL) accel += v;
        // distance à parcourir : de l'arrêt précédent à la cible, plus des tours entiers pour durer assez
        int d0 = Math.floorMod(from - target, PERIOD);
        int need = accel + brakeStart + CELL_H * cruiseWanted;
        int turns = Math.max(0, (need - d0 + PERIOD - 1) / PERIOD);
        int remaining = d0 + turns * PERIOD - accel - brakeStart;
        // croisière : remaining px répartis en pas voisins de 32, tous multiples de STEP
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
     * Lance un tour. Un seul tour à la fois par fenêtre : un clic pendant que les rouleaux tournent est ignoré.
     * La mise est prise tout de suite, le gain versé à l'arrêt (ou à la fermeture du menu).
     */
    public void spin(Player player, MenuSession session) {
        if (session == null || !session.isOpen()) return;
        UUID id = player.getUniqueId();
        Spin running = spins.get(id);
        if (running != null) {
            if (running.session == session && !running.settled) return;
            stop(running); // tour d'une ancienne fenêtre : réglé avant d'en commencer un autre
        }
        long bet = 0;
        if (casino.available()) {
            bet = casino.placeBet(player, "slots");
            if (bet < 0) {
                error.put(id, "Pas assez de jetons pour cette mise.");
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, SoundCategory.MASTER, 0.6f, 1.0f);
                plugin.menus().renderButtons(player, session);
                return;
            }
        }
        error.remove(id);
        Rest rest = rests.get(id);
        int[] from = rest != null && rest.session() == session ? rest.pos() : new int[3];
        Spin spin = new Spin(player, session, bet, draw(ThreadLocalRandom.current()), from);
        spins.put(id, spin);
        if (bet > 0) plugin.menus().renderButtons(player, session); // solde
        player.playSound(player.getLocation(), Sound.BLOCK_LEVER_CLICK, SoundCategory.MASTER, 0.8f, 1.0f);
        spin.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> step(spin), 0L, 1L);
    }

    /** Une image : rouleaux, ampoules, sons, puis (une fois arrêtés) gain et clignotement. */
    private void step(Spin spin) {
        Player player = spin.player;
        if (!spin.session.isOpen() || !player.isOnline()) {
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
            // cliquetis tant qu'un rouleau tourne vite
            boolean fast = false;
            for (boolean b : blurry) fast |= b;
            if (fast) player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, SoundCategory.MASTER, 0.35f, 1.5f);
        }
        int after = t - spin.moveTicks;   // ticks écoulés depuis l'arrêt du dernier rouleau
        if (after == 1) win(spin);
        boolean flashing = after >= 1 && after < spin.flashTicks; // la dernière image est fixe

        TitleOverlay o = new TitleOverlay(spin.base, spin.cursor);
        // ampoules : chenillard pendant le tour, clignotement rapide sur un gain
        boolean lamps = moving || flashing;
        if (lamps) {
            int rhythm = flashing ? 2 : 3;
            o.add("slots_ampoules_" + ((t / rhythm) % 2 == 0 ? "a" : "b"), CAISSE_X, CAISSE_W);
        }
        for (int r = 0; r < 3; r++) {
            o.add((blurry[r] ? "slots_rouleau_flou_p" : "slots_rouleau_p") + frame[r], REEL_X[r], REEL_W);
        }
        boolean showWin = after >= 1 && spin.outcome.mult() > 0;
        if (showWin) {
            String tint = flashing && (after / 2) % 2 == 1 ? "b" : "a";
            for (int r = 0; r < 3; r++) {
                if (spin.outcome.win()[r]) o.add("slots_cadre_" + tint, REEL_X[r], REEL_W);
            }
        }
        plugin.menus().showTitle(player, spin.session, o.toString());
        if (after == 1) plugin.menus().renderButtons(player, spin.session); // dernier tour, nombre de tours
        if (after >= Math.max(1, spin.flashTicks)) {
            // dernière image affichée : rouleaux à l'arrêt, case gagnante encadrée (fixe)
            finish(spin);
        }
    }

    /** Un rouleau vient de s'arrêter : coup sourd, de plus en plus aigu. */
    private void reelStopped(Spin spin, int reel) {
        float pitch = 0.8f + reel * 0.15f;
        spin.player.playSound(spin.player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASEDRUM, SoundCategory.MASTER, 0.8f, pitch);
        spin.player.playSound(spin.player.getLocation(), Sound.BLOCK_STONE_BUTTON_CLICK_ON, SoundCategory.MASTER, 0.5f, 1.2f + reel * 0.1f);
    }

    /** Tous les rouleaux sont arrêtés : gain versé, son selon le gain. */
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

    /** Fin du tour : la dernière image reste affichée (rouleaux arrêtés), les positions sont retenues pour le tour suivant. */
    private void finish(Spin spin) {
        rests.put(spin.player.getUniqueId(), new Rest(spin.session, restPositions(spin)));
        if (spin.task != null) spin.task.cancel();
        spin.task = null;
        spins.remove(spin.player.getUniqueId(), spin);
    }

    private static int[] restPositions(Spin spin) {
        int[] rest = new int[3];
        for (int r = 0; r < 3; r++) rest[r] = spin.pos[r][spin.pos[r].length - 1];
        return rest;
    }

    private void stop(Spin spin) {
        // Menu fermé ou joueur parti pendant le tour : le résultat est déjà tiré et la mise prise, le gain
        // est versé tout de suite, sans animation.
        settle(spin);
        if (spin.task != null) spin.task.cancel();
        spin.task = null;
        spins.remove(spin.player.getUniqueId(), spin);
        // positions retenues pour la fenêtre suivante : la fenêtre d'avant est fermée, on repart du repos
        rests.remove(spin.player.getUniqueId());
    }

    /** Arrêt du plugin : tous les tours en cours sont réglés (gains versés). */
    void shutdown() {
        new ArrayList<>(spins.values()).forEach(this::stop);
    }

    /** Gain du tour (mise x multiplicateur, arrondi vers le bas) : versé une seule fois, retenu pour l'affichage. */
    private void settle(Spin spin) {
        if (spin.settled) return;
        spin.settled = true;
        Player player = spin.player;
        double m = spin.outcome.mult();
        long gain = (long) Math.floor(spin.bet * m + 1e-9);
        if (spin.bet > 0 && casino.available()) casino.payout(player, gain, "slots", detail(spin.outcome));
        String shown = m > 0 ? "x" + format(m) + (spin.bet > 0 ? " (" + gain + " jetons)" : "") : "perdu";
        last.put(player.getUniqueId(), shown);
        count.merge(player.getUniqueId(), 1, Integer::sum);
    }

    private static String format(double m) {
        return m == Math.floor(m) ? String.valueOf((long) m) : String.valueOf(m);
    }

    /** Détail du tour pour le journal d'FluxCasino : symboles de la ligne et multiplicateur. */
    private static String detail(Outcome o) {
        return SYMBOLS[o.stops()[0]] + "-" + SYMBOLS[o.stops()[1]] + "-" + SYMBOLS[o.stops()[2]] + " x" + format(o.mult());
    }

    // ------------------------------------------------------------------ tirage et gains

    /** Tire le symbole du milieu de chaque rouleau (pondéré) et évalue la ligne. */
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

    /** Gain de la ligne : trois identiques, sinon deux cerises, sinon une cerise. */
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

    /** Espérance du gain pour 1 de mise, sur toutes les combinaisons pondérées (vérification des réglages). */
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

    // ------------------------------------------------------------------ placeholders

    static Slots register(Flux plugin) {
        Slots slots = new Slots(plugin);
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) slots.register();
        return slots;
    }

    @Override
    public String getIdentifier() {
        return "fluxslots";
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

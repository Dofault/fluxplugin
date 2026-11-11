package fr.influencraft.flux;

import fr.influencraft.flux.menu.PathSpec;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chemins de progression : transforme des {@link PathSpec} en pièces à coller dans le titre, juste après l'image
 * de fond. Depuis le 2026-11-11 les pièces sont des caractères des polices {@code fluxui:y<Y>} (planche
 * {@code sprites_misc} de Flux/assets/vanilla/gen_ui.py, sources dans Flux/assets/vanilla/chemin/) : plus aucun
 * glyphe ItemsAdder par hauteur (l'ancien pack influroad en comptait 595, lus un par un au chargement du client).
 *
 * <p>Géométrie (grille Minecraft de 18 px, cf. plugins/fluxplugin/Flux/assets/vanilla/chemin/LISEZMOI.md) :
 * case en colonne c, haut en y : {@code slot_x = 8 + 18c}. Pièces : h (19x3) en
 * {@code (slot_x+8, y+7)}, v (3x19) en {@code (slot_x+7, y+8)}, demi-pièces de départ / fin,
 * losange (5x5) en {@code (slot_x+6, y+6)}. La police {@code fluxui:y<Y>} pose le haut de la pièce Y px sous le
 * haut de la fenêtre ; la position horizontale se règle avec des {@code :offset_N:}. Chaque pièce est suivie d'un
 * espace négatif de sa largeur + 1 : elle laisse le curseur où elle l'a trouvé, et le titre complet garde l'avance
 * de son fond (mesurable par {@link #advance}).
 *
 * <p>Allumage : une liaison est colorée si ses deux cases sont atteintes ; le départ dès la
 * 1re étape ; une branche dès que sa case de départ est atteinte ; au-delà de la longueur
 * du chemin tout est allumé. Ordre de dessin : verticales, horizontales, losanges (les
 * items sont toujours dessinés par-dessus le titre).
 */
public final class PathRenderer {

    private static final int H_W = 19, V_W = 3, H_DEMI_W = 10, V_DEMI_W = 3, NODE_W = 5;
    /** Same order as PATH_PIECES / PATH_STATES of gen_ui.py: char = PATH_BASE + 8 * piece + state. */
    private static final List<String> PIECES = List.of("chemin_h", "chemin_v", "chemin_h_demi", "chemin_v_demi", "chemin_noeud");
    private static final List<String> STATE_ORDER = List.of("eteint", "vert", "or", "bleu", "rose", "rouge", "orange");
    private static final int PATH_BASE = 0xE140, BACK_BASE = 0xE1F1, MAX_Y = 220;
    private static final Pattern FONT_SEGMENT = Pattern.compile("<font:[^>]*>.*?</font>");
    private static final Pattern TOKEN = Pattern.compile(":([a-z0-9_-]+):");
    private static final Pattern OFFSET = Pattern.compile("offset_(-?\\d+)");

    private static final Map<String, Integer> WIDTHS = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> WARNED = new ConcurrentHashMap<>();
    private static Logger logger;

    private PathRenderer() {
    }

    static void init(Logger log) {
        logger = log;
    }

    static void reset() {
        WIDTHS.clear();
        WARNED.clear();
    }

    /** Une pièce à dessiner : index de pièce et d'état, haut y et position x depuis le bord gauche de la fenêtre. */
    private record Piece(int piece, int state, int y, int x, int width) {
    }

    /**
     * Glyphes à ajouter après {@code title} pour dessiner les chemins. Vide si aucun chemin.
     *
     * @param rows nombre de rangées du coffre (position des cases de l'inventaire)
     */
    public static String render(Player player, String title, int rows, List<PathSpec> paths) {
        if (paths.isEmpty()) return "";
        List<Piece> vertical = new ArrayList<>();
        List<Piece> horizontal = new ArrayList<>();
        List<Piece> nodes = new ArrayList<>();
        for (PathSpec path : paths) collect(player, path, rows, vertical, horizontal, nodes);

        StringBuilder out = new StringBuilder();
        // Position du curseur (depuis le début du titre) une fois le fond dessiné.
        int start = advance(title);
        int cursor = start;
        for (List<Piece> layer : List.of(vertical, horizontal, nodes)) {
            for (Piece p : layer) {
                if (p.y() < 0 || p.y() > MAX_Y) {
                    warnOnce("y" + p.y(), "pièce de chemin à y " + p.y() + " hors des polices fluxui (ignorée)");
                    continue;
                }
                int target = p.x() - 8; // le titre commence 8 px à droite du bord de la fenêtre
                if (target != cursor) out.append(":offset_").append(target - cursor).append(':');
                out.append("<font:fluxui:y").append(p.y()).append("><white>")
                        .append((char) (PATH_BASE + 8 * p.piece() + p.state()))
                        .append((char) (BACK_BASE + p.piece())).append("</font>");
                cursor = target;
            }
        }
        if (cursor != start) out.append(":offset_").append(start - cursor).append(':');
        return out.toString();
    }

    private static void collect(Player player, PathSpec path, int rows,
                                List<Piece> vertical, List<Piece> horizontal, List<Piece> nodes) {
        List<Integer> slots = path.slots();
        int progress = progress(player, path);
        String on = path.color();
        // Numéro d'étape (1 = première) de chaque case ; une case de passage prend le numéro de
        // l'étape qui la suit : elle est atteinte en même temps qu'elle.
        int[] step = new int[slots.size()];
        int steps = 0;
        for (int i = 0; i < slots.size(); i++) if (!path.waypoints().contains(slots.get(i))) steps++;
        int next = steps + 1;
        int count = steps;
        for (int i = slots.size() - 1; i >= 0; i--) {
            if (!path.waypoints().contains(slots.get(i))) next = count--;
            step[i] = next;
        }

        // Couleur de chaque étape (states), sinon progression + couleur unique.
        String[] colors = states(player, path);
        java.util.function.IntFunction<String> color = k -> colors == null
                ? (progress >= k ? on : "eteint")
                : (k >= 1 && k <= colors.length ? colors[k - 1] : "eteint");

        // Liaisons du chemin : allumées si les deux cases sont atteintes (couleur de l'étape d'arrivée).
        for (int i = 0; i + 1 < slots.size(); i++) {
            String state = colors != null
                    ? (color.apply(step[i]).equals("eteint") ? "eteint" : color.apply(step[i + 1]))
                    : progress >= step[i] && progress >= step[i + 1] ? on : "eteint";
            link(path, slots.get(i), slots.get(i + 1), state, rows, vertical, horizontal);
        }
        // Départ et fin (demi-pièces), dans le prolongement du chemin.
        if (path.start()) {
            int first = slots.get(0);
            int dir = slots.size() > 1 ? direction(slots.get(1), first, rows) : LEFT;
            demi(path, first, dir, color.apply(1), rows, vertical, horizontal);
        }
        if (path.end()) {
            int last = slots.get(slots.size() - 1);
            int dir = slots.size() > 1 ? direction(slots.get(slots.size() - 2), last, rows) : RIGHT;
            demi(path, last, dir, color.apply(steps), rows, vertical, horizontal);
        }
        // Branches vers les paliers : allumées quand la case de départ est atteinte.
        for (int[] b : path.branches()) {
            int index = slots.indexOf(b[0]);
            if (index < 0) {
                warnOnce(path.id() + b[0], "chemin " + path.id() + " : la branche part de la case " + b[0]
                        + " qui n'est pas sur le chemin (éteinte)");
            }
            String state = index >= 0 ? color.apply(step[index]) : "eteint";
            link(path, b[0], b[1], state, rows, vertical, horizontal);
        }
        // Losanges : couleur de la case (chemin) ou de l'étape d'où part leur branche.
        for (int slot : path.nodes()) {
            String state = "eteint";
            int index = slots.indexOf(slot);
            if (index >= 0) {
                state = color.apply(step[index]);
            } else {
                for (int[] b : path.branches()) {
                    int from = slots.indexOf(b[0]);
                    if (b[1] == slot && from >= 0 && !color.apply(step[from]).equals("eteint")) state = color.apply(step[from]);
                }
            }
            int[] pos = position(slot, rows);
            nodes.add(piece("chemin_noeud", state, pos[1] + 6, pos[0] + 6, NODE_W));
        }
    }

    private static final java.util.Set<String> STATES = java.util.Set.of("eteint", "vert", "orange", "rouge", "or", "bleu", "rose");

    /** Couleurs des étapes affichées (option states), null quand le chemin n'en a pas. */
    private static String[] states(Player player, PathSpec path) {
        if (path.states() == null || path.states().isBlank()) return null;
        String[] parts = Text.placeholders(player, path.states()).split(",");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim().toLowerCase(java.util.Locale.ROOT);
            parts[i] = STATES.contains(p) ? p : "eteint";
        }
        return parts;
    }

    private static int progress(Player player, PathSpec path) {
        int value;
        try {
            value = (int) Math.floor(Double.parseDouble(Text.placeholders(player, path.progress()).trim().replace(',', '.')));
        } catch (NumberFormatException e) {
            warnOnce("progress" + path.id(), "chemin " + path.id() + " : progression illisible « "
                    + path.progress() + " » (0 retenu)");
            value = 0;
        }
        value -= path.offset();
        if (value <= 0) return 0;
        if (path.cycle() > 0) value = (value - 1) % path.cycle() + 1; // nouveau cycle : tout repart éteint
        return value;
    }

    private static final int LEFT = 0, RIGHT = 1, UP = 2, DOWN = 3;

    /** Direction pour aller de {@code from} vers {@code to} (cases alignées). */
    private static int direction(int from, int to, int rows) {
        int[] a = position(from, rows), b = position(to, rows);
        if (a[1] == b[1]) return b[0] > a[0] ? RIGHT : LEFT;
        return b[1] > a[1] ? DOWN : UP;
    }

    /** Demi-pièce sur la case {@code slot}, côté d'où arrive / où part le chemin. */
    private static void demi(PathSpec path, int slot, int towardOutside, String state, int rows,
                             List<Piece> vertical, List<Piece> horizontal) {
        int[] p = position(slot, rows);
        int x = p[0], y = p[1];
        // towardOutside = direction depuis le voisin vers cette case : la demi-pièce continue dans ce sens.
        switch (towardOutside) {
            case LEFT -> horizontal.add(piece("chemin_h_demi", state, y + 7, x - 1, H_DEMI_W));
            case RIGHT -> horizontal.add(piece("chemin_h_demi", state, y + 7, x + 8, H_DEMI_W));
            case UP -> vertical.add(piece("chemin_v_demi", state, y - 1, x + 7, V_DEMI_W));
            default -> vertical.add(piece("chemin_v_demi", state, y + 8, x + 7, V_DEMI_W));
        }
    }

    /** Liaison droite entre deux cases d'une même ligne ou colonne (une pièce par intervalle). */
    private static void link(PathSpec path, int from, int to, String state, int rows,
                             List<Piece> vertical, List<Piece> horizontal) {
        int[] a = position(from, rows), b = position(to, rows);
        if (a[1] == b[1]) {
            int x1 = Math.min(a[0], b[0]), x2 = Math.max(a[0], b[0]);
            for (int x = x1; x < x2; x += 18) horizontal.add(piece("chemin_h", state, a[1] + 7, x + 8, H_W));
        } else if (a[0] == b[0]) {
            int y1 = Math.min(a[1], b[1]), y2 = Math.max(a[1], b[1]);
            // whole rows: one piece per row; across the gap between the chest and the inventory (not a multiple of
            // 18), the last piece is pulled up so it ends on the lower slot (any height works, the fonts cover y 0 to 220)
            int y = y1;
            for (; y + 18 <= y2; y += 18) vertical.add(piece("chemin_v", state, y + 8, a[0] + 7, V_W));
            if (y < y2) vertical.add(piece("chemin_v", state, y2 - 18 + 8, a[0] + 7, V_W));
        } else {
            warnOnce(path.id() + from + "-" + to, "chemin " + path.id() + " : cases " + from + " et " + to
                    + " ni sur la même ligne ni sur la même colonne (liaison ignorée)");
        }
    }

    /** Top left of the item of a raw slot in the window {x, y} (inventory rows as drawn in game). */
    public static int[] slotPosition(int slot, int rows) {
        return position(slot, rows);
    }

    /** Coin haut gauche de la case brute {@code slot} dans la fenêtre {x, y}. */
    static int[] position(int slot, int rows) {
        int top = rows * 9;
        if (slot < top) return new int[]{8 + 18 * (slot % 9), 18 + 18 * (slot / 9)};
        int i = slot - top;
        if (i < 27) return new int[]{8 + 18 * (i % 9), 103 + (rows - 4) * 18 + 18 * (i / 9)};
        return new int[]{8 + 18 * (i % 9), 161 + (rows - 4) * 18};
    }

    private static Piece piece(String piece, String state, int y, int x, int width) {
        int s = STATE_ORDER.indexOf(state);
        if (s < 0) {
            warnOnce("state" + state, "état de chemin inconnu « " + state + " » (éteint utilisé)");
            s = 0;
        }
        return new Piece(PIECES.indexOf(piece), s, y, x, width);
    }

    /**
     * ItemsAdder pictures of a title (every {@code :name:} that is not an offset). Measured in game (2026-10-30): a
     * picture advances by its width, not width + 1 as {@link #advance} counts, so the widgets subtract this count.
     */
    public static int glyphCount(String title) {
        int n = 0;
        Matcher m = TOKEN.matcher(title);
        while (m.find()) if (!OFFSET.matcher(m.group(1)).matches()) n++;
        return n;
    }

    /**
     * Avance horizontale du titre (en px) : {@code :offset_N:} avance de N, une image
     * ItemsAdder de sa largeur + 1. Le texte libre n'est pas mesurable : signalé une fois.
     */
    public static int advance(String title) {
        int total = 0;
        Matcher m = TOKEN.matcher(title);
        int last = 0;
        while (m.find()) {
            if (m.start() > last && !libre(title.substring(last, m.start()))) warnText(title);
            last = m.end();
            String name = m.group(1);
            Matcher off = OFFSET.matcher(name);
            if (off.matches()) {
                total += Integer.parseInt(off.group(1));
            } else {
                total += width(name) + 1;
            }
        }
        if (last < title.length() && !libre(title.substring(last))) warnText(title);
        return total;
    }

    /**
     * Texte sans largeur connue entre deux glyphes : vide, ou seulement des %placeholders%, comptés
     * pour 0 (à eux d'avoir une avance nette nulle, ex : %fluxjob_<id>_levelglyphs_<c>%).
     */
    private static boolean libre(String text) {
        // <font:...>...</font>: Flux sprites (path pieces, widgets) that bring the cursor back themselves
        return FONT_SEGMENT.matcher(text).replaceAll("").replaceAll("%[^%\\s]+%", "").isBlank();
    }

    private static int width(String name) {
        return WIDTHS.computeIfAbsent(name, n -> {
            if (!fr.influencraft.flux.api.items.CustomItems.available()) return 0;
            int w = fr.influencraft.flux.api.items.CustomItems.glyphWidth(n);
            if (w <= 0) warnOnce("width" + n, "glyph :" + n + ": unknown, width 0 used to place the paths");
            return w;
        });
    }

    private static void warnText(String title) {
        warnOnce("text" + title, "titre « " + title + " » : du texte libre précède un chemin, sa largeur est"
                + " inconnue et le chemin sera décalé (ne mettre que des glyphes et des :offset_N:)");
    }

    private static void warnOnce(String key, String message) {
        if (logger != null && WARNED.putIfAbsent(key, Boolean.TRUE) == null) logger.warning(message);
    }
}

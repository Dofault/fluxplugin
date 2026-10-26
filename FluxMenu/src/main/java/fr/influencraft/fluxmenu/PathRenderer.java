package fr.influencraft.fluxmenu;

import fr.influencraft.fluxmenu.menu.PathSpec;
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
 * Chemins de progression : transforme des {@link PathSpec} en une suite de glyphes
 * ItemsAdder (pack influroad) à coller dans le titre, juste après l'image de fond.
 *
 * <p>Géométrie (grille Minecraft de 18 px, cf. plugins/FluxMenu/assets/influroad/LISEZMOI.md) :
 * case en colonne c, haut en y : {@code slot_x = 8 + 18c}. Pièces : h (19x3) en
 * {@code (slot_x+8, y+7)}, v (3x19) en {@code (slot_x+7, y+8)}, demi-pièces de départ / fin,
 * losange (5x5) en {@code (slot_x+6, y+6)}. Chaque glyphe ItemsAdder a une hauteur fixe :
 * son nom porte la position verticale ({@code chemin_h_vert_y146}), la position
 * horizontale se règle avec des {@code :offset_N:} (un glyphe de largeur w avance de w+1).
 *
 * <p>Allumage : une liaison est colorée si ses deux cases sont atteintes ; le départ dès la
 * 1re étape ; une branche dès que sa case de départ est atteinte ; au-delà de la longueur
 * du chemin tout est allumé. Ordre de dessin : verticales, horizontales, losanges (les
 * items sont toujours dessinés par-dessus le titre).
 */
public final class PathRenderer {

    private static final int H_W = 19, V_W = 3, H_DEMI_W = 10, V_DEMI_W = 3, NODE_W = 5;
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

    /** Une pièce à dessiner : glyphe et position (x depuis le bord gauche de la fenêtre). */
    private record Piece(String glyph, int x, int width) {
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
        int cursor = advance(title);
        for (List<Piece> layer : List.of(vertical, horizontal, nodes)) {
            for (Piece p : layer) {
                int target = p.x() - 8; // le titre commence 8 px à droite du bord de la fenêtre
                if (target != cursor) out.append(":offset_").append(target - cursor).append(':');
                out.append(':').append(p.glyph()).append(':');
                cursor = target + p.width() + 1;
            }
        }
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

        // Liaisons du chemin : allumées si les deux cases sont atteintes.
        for (int i = 0; i + 1 < slots.size(); i++) {
            String state = progress >= step[i] && progress >= step[i + 1] ? on : "eteint";
            link(path, slots.get(i), slots.get(i + 1), state, rows, vertical, horizontal);
        }
        // Départ et fin (demi-pièces), dans le prolongement du chemin.
        if (path.start()) {
            int first = slots.get(0);
            int dir = slots.size() > 1 ? direction(slots.get(1), first, rows) : LEFT;
            demi(path, first, dir, progress >= 1 ? on : "eteint", rows, vertical, horizontal);
        }
        if (path.end()) {
            int last = slots.get(slots.size() - 1);
            int dir = slots.size() > 1 ? direction(slots.get(slots.size() - 2), last, rows) : RIGHT;
            demi(path, last, dir, progress >= steps ? on : "eteint", rows, vertical, horizontal);
        }
        // Branches vers les paliers : allumées quand la case de départ est atteinte.
        for (int[] b : path.branches()) {
            int index = slots.indexOf(b[0]);
            if (index < 0) {
                warnOnce(path.id() + b[0], "chemin " + path.id() + " : la branche part de la case " + b[0]
                        + " qui n'est pas sur le chemin (éteinte)");
            }
            String state = index >= 0 && progress >= step[index] ? on : "eteint";
            link(path, b[0], b[1], state, rows, vertical, horizontal);
        }
        // Losanges : allumés si la case est atteinte (chemin) ou si sa branche l'est.
        for (int slot : path.nodes()) {
            boolean lit;
            int index = slots.indexOf(slot);
            if (index >= 0) {
                lit = progress >= step[index];
            } else {
                lit = false;
                for (int[] b : path.branches()) {
                    int from = slots.indexOf(b[0]);
                    if (b[1] == slot && from >= 0 && progress >= step[from]) lit = true;
                }
            }
            int[] pos = position(slot, rows);
            nodes.add(new Piece(glyph("chemin_noeud", lit ? on : "eteint", pos[1] + 6), pos[0] + 6, NODE_W));
        }
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
            case LEFT -> horizontal.add(new Piece(glyph("chemin_h_demi", state, y + 7), x - 1, H_DEMI_W));
            case RIGHT -> horizontal.add(new Piece(glyph("chemin_h_demi", state, y + 7), x + 8, H_DEMI_W));
            case UP -> vertical.add(new Piece(glyph("chemin_v_demi", state, y - 1), x + 7, V_DEMI_W));
            default -> vertical.add(new Piece(glyph("chemin_v_demi", state, y + 8), x + 7, V_DEMI_W));
        }
    }

    /** Liaison droite entre deux cases d'une même ligne ou colonne (une pièce par intervalle). */
    private static void link(PathSpec path, int from, int to, String state, int rows,
                             List<Piece> vertical, List<Piece> horizontal) {
        int[] a = position(from, rows), b = position(to, rows);
        if (a[1] == b[1]) {
            int x1 = Math.min(a[0], b[0]), x2 = Math.max(a[0], b[0]);
            for (int x = x1; x < x2; x += 18) horizontal.add(new Piece(glyph("chemin_h", state, a[1] + 7), x + 8, H_W));
        } else if (a[0] == b[0]) {
            int y1 = Math.min(a[1], b[1]), y2 = Math.max(a[1], b[1]);
            if ((y2 - y1) % 18 != 0) {
                warnOnce(path.id() + from + "-" + to, "chemin " + path.id() + " : cases " + from + " et " + to
                        + " non reliables (écart vertical de " + (y2 - y1) + " px, pas un multiple de 18)");
                return;
            }
            for (int y = y1; y < y2; y += 18) vertical.add(new Piece(glyph("chemin_v", state, y + 8), a[0] + 7, V_W));
        } else {
            warnOnce(path.id() + from + "-" + to, "chemin " + path.id() + " : cases " + from + " et " + to
                    + " ni sur la même ligne ni sur la même colonne (liaison ignorée)");
        }
    }

    /** Coin haut gauche de la case brute {@code slot} dans la fenêtre {x, y}. */
    static int[] position(int slot, int rows) {
        int top = rows * 9;
        if (slot < top) return new int[]{8 + 18 * (slot % 9), 18 + 18 * (slot / 9)};
        int i = slot - top;
        if (i < 27) return new int[]{8 + 18 * (i % 9), 103 + (rows - 4) * 18 + 18 * (i / 9)};
        return new int[]{8 + 18 * (i % 9), 161 + (rows - 4) * 18};
    }

    private static String glyph(String piece, String state, int y) {
        return piece + "_" + state + "_y" + y;
    }

    /**
     * Avance horizontale du titre (en px) : {@code :offset_N:} avance de N, une image
     * ItemsAdder de sa largeur + 1. Le texte libre n'est pas mesurable : signalé une fois.
     */
    static int advance(String title) {
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
        return text.replaceAll("%[^%\\s]+%", "").isBlank();
    }

    @SuppressWarnings("unchecked")
    private static int width(String name) {
        return WIDTHS.computeIfAbsent(name, n -> {
            if (!Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) return 0;
            String full = n;
            if (!n.contains(":")) {
                for (Object o : (Iterable<Object>) dev.lone.itemsadder.api.FontImages.FontImageWrapper.getNamespacedIdsInRegistry()) {
                    String id = String.valueOf(o);
                    if (id.endsWith(":" + n)) {
                        full = id;
                        break;
                    }
                }
            }
            dev.lone.itemsadder.api.FontImages.FontImageWrapper w = new dev.lone.itemsadder.api.FontImages.FontImageWrapper(full);
            if (!w.exists()) {
                warnOnce("width" + n, "glyphe :" + n + ": inconnu d'ItemsAdder, largeur 0 retenue pour placer les chemins");
                return 0;
            }
            return w.getWidth();
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

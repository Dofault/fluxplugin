package fr.influencraft.fluxmenu;

/**
 * Titre d'une image d'animation : fond du menu puis sprites posés au pixel près (:offset_N: puis le glyphe
 * ItemsAdder). Partagé par les jeux animés dans le titre (Plinko, machine à sous). Le curseur suit l'avance du
 * texte : un glyphe de largeur w avance de w + 1 px, l'écart au prochain sprite se règle avec :offset_N:.
 */
final class TitleOverlay {

    private final StringBuilder sb;
    private int cursor;

    /** @param cursor position (px depuis le bord gauche de la fenêtre) où s'arrête le fond déjà écrit */
    TitleOverlay(String base, int cursor) {
        this.sb = new StringBuilder(base);
        this.cursor = cursor;
    }

    /** Pose un glyphe de {@code width} px de large avec son bord gauche en {@code x}. */
    void add(String glyph, int x, int width) {
        int offset = x - cursor;
        if (offset != 0) sb.append(":offset_").append(offset).append(':');
        sb.append(':').append(glyph).append(':');
        cursor = x + width + 1;
    }

    @Override
    public String toString() {
        return sb.toString();
    }
}

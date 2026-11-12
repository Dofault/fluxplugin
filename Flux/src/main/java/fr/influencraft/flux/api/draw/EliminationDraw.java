package fr.influencraft.flux.api.draw;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Every candidate in the boss bar, struck out one by one, slower and slower, until only the winner is left. */
final class EliminationDraw extends BossBarDraw {

    private static final int MAX = 8;
    private final List<Draws.Choice> candidates = new ArrayList<>();
    private final List<Integer> order = new ArrayList<>();
    private final boolean[] out;

    EliminationDraw(Draws.Show show) {
        super(show);
        List<Draws.Choice> others = new ArrayList<>(show.pool());
        others.remove(show.winner());
        Collections.shuffle(others);
        candidates.addAll(others.subList(0, Math.min(MAX - 1, others.size())));
        candidates.add(show.winner());
        Collections.shuffle(candidates);
        for (int i = 0; i < candidates.size(); i++) if (!candidates.get(i).equals(show.winner())) order.add(i);
        Collections.shuffle(order);
        out = new boolean[candidates.size()];
        draw(-1);
    }

    @Override
    public int[] delays() {
        // a short look at every candidate, then eliminations that take their time towards the end
        int n = order.size();
        int[] d = new int[n];
        for (int i = 0; i < n; i++) d[i] = i == 0 ? 30 : 7 + (int) Math.round(14 * Math.pow(i / (double) Math.max(1, n - 1), 1.6));
        return d;
    }

    @Override
    public void step(int index) {
        out[order.get(index)] = true;
        draw(order.get(index));
    }

    private void draw(int justOut) {
        StringBuilder sb = new StringBuilder(head());
        int left = 0;
        for (int i = 0; i < candidates.size(); i++) {
            Draws.Choice c = candidates.get(i);
            if (out[i]) sb.append(i == justOut ? "&#FF5C5C✕ " : "&8✕ ");
            else {
                left++;
                sb.append("&f").append(c.sprite().isEmpty() ? "&7■" : c.sprite()).append(' ');
            }
        }
        sb.append(" &7").append(left).append(" &8/ &7").append(candidates.size());
        text(sb.toString(), left / (float) candidates.size());
    }

    @Override
    public String tick(int index) {
        return "remove";
    }
}

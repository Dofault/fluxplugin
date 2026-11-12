package fr.influencraft.flux.api.draw;

import fr.influencraft.flux.api.text.Texts;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Every candidate side by side, struck out one by one, slower and slower, until the winner is the only one left. */
final class EliminationDraw implements Draws.Animation {

    private static final int MAX = 8;
    private final List<Draws.Choice> candidates = new ArrayList<>();
    private final List<Integer> order = new ArrayList<>();
    private final boolean[] out;

    EliminationDraw(List<Draws.Choice> pool, Draws.Choice winner) {
        List<Draws.Choice> others = new ArrayList<>(pool);
        others.remove(winner);
        Collections.shuffle(others);
        candidates.addAll(others.subList(0, Math.min(MAX - 1, others.size())));
        candidates.add(winner);
        Collections.shuffle(candidates);
        for (int i = 0; i < candidates.size(); i++) if (!candidates.get(i).equals(winner)) order.add(i);
        Collections.shuffle(order);
        out = new boolean[candidates.size()];
    }

    @Override
    public Component start() {
        return line(-1);
    }

    @Override
    public int[] delays() {
        int n = order.size();
        int[] d = new int[n];
        for (int i = 0; i < n; i++) d[i] = i == 0 ? 30 : 7 + (int) Math.round(14 * Math.pow(i / (double) Math.max(1, n - 1), 1.6));
        return d;
    }

    @Override
    public Component step(int index) {
        out[order.get(index)] = true;
        return line(order.get(index));
    }

    @Override
    public float progress(int index) {
        return (candidates.size() - index - 1) / (float) candidates.size();
    }

    @Override
    public String sound(int index) {
        return "remove";
    }

    private Component line(int justOut) {
        StringBuilder sb = new StringBuilder();
        int left = 0;
        for (int i = 0; i < candidates.size(); i++) {
            if (out[i]) sb.append(i == justOut ? "&#FF5C5C✕ " : "&8✕ ");
            else {
                left++;
                sb.append("&f").append(candidates.get(i).sprite()).append(' ');
            }
        }
        sb.append(" &#A6E4F5").append(left).append(" / ").append(candidates.size());
        return Texts.parse(sb.toString());
    }
}

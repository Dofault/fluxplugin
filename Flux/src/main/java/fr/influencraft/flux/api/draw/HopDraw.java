package fr.influencraft.flux.api.draw;

import fr.influencraft.flux.api.text.Texts;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The candidates in a row and a light jumping from one to the next, slower and slower, until it settles on the
 * winner. The light is two twinkling stars; the others have empty squares of the same width, so nothing moves.
 */
final class HopDraw implements Draws.Animation {

    private static final int MAX = 7, STEPS = 26;
    private final List<Draws.Choice> candidates = new ArrayList<>();
    private final int[] at = new int[STEPS];

    HopDraw(List<Draws.Choice> pool, Draws.Choice winner) {
        List<Draws.Choice> others = new ArrayList<>(pool);
        others.remove(winner);
        Collections.shuffle(others);
        candidates.addAll(others.subList(0, Math.min(MAX - 1, others.size())));
        candidates.add(winner);
        Collections.shuffle(candidates);
        int n = candidates.size();
        int end = candidates.indexOf(winner);
        // walk back from the winner: the light goes left to right, round and round
        for (int i = STEPS - 1; i >= 0; i--) at[i] = Math.floorMod(end - (STEPS - 1 - i), n);
        if (ThreadLocalRandom.current().nextBoolean() && n > 2) at[STEPS - 2] = Math.floorMod(end + 1, n);
    }

    @Override
    public Component start() {
        return line(-1);
    }

    @Override
    public int[] delays() {
        int[] d = Draws.wheel(STEPS, 12);
        d[0] = 14;
        return d;
    }

    @Override
    public Component step(int index) {
        return line(at[index]);
    }

    private Component line(int lit) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < candidates.size(); i++) {
            String side = i == lit ? Draws.SPARK : Draws.BLANK;
            sb.append(side).append(candidates.get(i).sprite()).append(side).append(' ');
        }
        return Texts.parse(sb.toString().strip());
    }
}

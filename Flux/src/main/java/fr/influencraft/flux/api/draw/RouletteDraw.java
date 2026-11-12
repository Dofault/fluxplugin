package fr.influencraft.flux.api.draw;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** A strip of icons scrolling in the boss bar, slowing down until the winner stops in the frame at its centre. */
final class RouletteDraw extends BossBarDraw {

    private static final int SIDE = 3, STEPS = 30;
    private final List<Draws.Choice> reel;

    RouletteDraw(Draws.Show show) {
        super(show);
        // the strip shows SIDE icons on each side: SIDE more after the winner, so it stops in the middle
        reel = new ArrayList<>(show.reel(STEPS + SIDE));
        for (int i = 0; i < SIDE; i++) reel.add(show.pool().get(ThreadLocalRandom.current().nextInt(show.pool().size())));
    }

    @Override
    public int[] delays() {
        return Draws.wheel(STEPS, 9);
    }

    @Override
    public void step(int index) {
        int centre = index + SIDE;
        StringBuilder sb = new StringBuilder(head()).append("&8« ");
        for (int i = centre - SIDE; i <= centre + SIDE; i++) {
            Draws.Choice c = reel.get(Math.max(0, Math.min(reel.size() - 1, i)));
            String icon = c.sprite().isEmpty() ? "&7■" : c.sprite();
            if (i == centre) sb.append("&#FFD94D[ &f").append(icon).append(" &#FFD94D] ");
            else sb.append("&f").append(icon).append(' ');
        }
        sb.append("&8»  &f").append(reel.get(centre).name());
        text(sb.toString(), (index + 1) / (float) STEPS);
    }
}

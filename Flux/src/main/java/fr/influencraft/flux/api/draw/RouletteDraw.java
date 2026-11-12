package fr.influencraft.flux.api.draw;

import fr.influencraft.flux.api.text.Texts;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** A strip of icons running through the bar, slowing down until the winner stops between two shining frames. */
final class RouletteDraw implements Draws.Animation {

    private static final int SIDE = 3, STEPS = 30;
    private final List<Draws.Choice> reel;

    RouletteDraw(List<Draws.Choice> pool, Draws.Choice winner) {
        reel = new ArrayList<>(Draws.reel(pool, winner, STEPS + SIDE));
        for (int i = 0; i < SIDE; i++) reel.add(pool.get(ThreadLocalRandom.current().nextInt(pool.size())));
    }

    @Override
    public Component start() {
        return line(SIDE - 1);
    }

    @Override
    public int[] delays() {
        return Draws.wheel(STEPS, 9);
    }

    @Override
    public Component step(int index) {
        return line(index + SIDE);
    }

    private Component line(int centre) {
        StringBuilder sb = new StringBuilder("&8« ");
        for (int i = centre - SIDE; i <= centre + SIDE; i++) {
            Draws.Choice c = reel.get(Math.max(0, Math.min(reel.size() - 1, i)));
            if (i == centre) sb.append(Draws.SHINE).append(' ').append(c.sprite()).append(' ').append(Draws.SHINE).append(' ');
            else sb.append(c.sprite()).append(' ');
        }
        return Texts.parse(sb.append("&8»").toString());
    }
}

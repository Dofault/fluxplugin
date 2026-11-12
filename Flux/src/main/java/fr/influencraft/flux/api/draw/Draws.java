package fr.influencraft.flux.api.draw;

import fr.influencraft.flux.api.client.Clients;
import fr.influencraft.flux.api.text.ItemSprites;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.Sounds;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * A draw shown to players while they keep playing, without any menu: the candidates stand side by side in a boss
 * bar and are struck out one by one, slower and slower, until the winner is the only one left. Icons are the client's
 * own item sprites ({@link ItemSprites}): nothing in the resource pack.
 *
 * <p>Bedrock players (no sprites) see nothing of it: the caller tells the result in the chat once {@code onDone}
 * runs, for everybody.</p>
 *
 * <pre>
 *   Draws.play(plugin, players, pool, winner, "Tournament draw", () -> start());
 * </pre>
 */
public final class Draws {

    /**
     * One thing that can be drawn.
     *
     * @param id   stable id
     * @param icon vanilla item whose sprite stands for it (null: none)
     * @param name text shown, any Flux syntax ({@code <lang:block.minecraft.iron_ore>} is translated by each client)
     */
    public record Choice(String id, Material icon, String name) {

        /** Sprite tag of the icon, a grey square when there is none. */
        String sprite() {
            String s = icon == null ? "" : ItemSprites.tag(icon);
            return s.isEmpty() ? "&7■" : s;
        }
    }

    /** Candidates shown at most. */
    private static final int MAX = 8;
    /** Ticks the full line stays before the first one is struck out, then the slowest step, the result's stay. */
    private static final int LOOK = 30, SLOWEST = 21, HOLD = 50;

    private Draws() {
    }

    /**
     * Plays a draw to {@code viewers} (Bedrock players are left out); {@code onDone} runs on the main thread once the
     * result was shown, also when nobody watches. {@code pool} may hold the winner or not.
     */
    public static void play(Plugin owner, Collection<? extends Player> viewers, List<Choice> pool, Choice winner,
                            String heading, Runnable onDone) {
        List<Player> watching = new ArrayList<>();
        for (Player p : viewers) if (!Clients.bedrock(p)) watching.add(p);
        // the winner among up to MAX - 1 others, in a random order
        List<Choice> others = new ArrayList<>(pool == null ? List.of() : pool);
        others.remove(winner);
        Collections.shuffle(others);
        List<Choice> candidates = new ArrayList<>(others.subList(0, Math.min(MAX - 1, others.size())));
        candidates.add(winner);
        Collections.shuffle(candidates);
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) if (!candidates.get(i).equals(winner)) order.add(i);
        Collections.shuffle(order);

        String head = "&#2BC4E8&l" + (heading == null ? "" : heading) + "   ";
        boolean[] out = new boolean[candidates.size()];
        BossBar bar = BossBar.bossBar(line(head, candidates, out, -1), 1f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
        for (Player p : watching) p.showBossBar(bar);

        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(owner, new Runnable() {
            int step = 0, wait = LOOK, holding = -1;

            @Override
            public void run() {
                if (--wait > 0) return;
                if (holding < 0 && step < order.size()) {
                    int struck = order.get(step++);
                    out[struck] = true;
                    int left = candidates.size() - step;
                    bar.name(line(head, candidates, out, struck));
                    bar.progress(left / (float) candidates.size());
                    for (Player p : watching) if (p.isOnline()) Sounds.cue(p, "remove");
                    // slower and slower: the last ones take their time
                    wait = 7 + (int) Math.round((SLOWEST - 7) * Math.pow(step / (double) Math.max(1, order.size()), 1.6));
                    return;
                }
                if (holding < 0) {
                    bar.progress(1f);
                    bar.name(Texts.parse(head + "&f" + winner.sprite() + " &f" + winner.name()));
                    for (Player p : watching) if (p.isOnline()) Sounds.cue(p, "draw-reveal");
                    holding = 1;
                    wait = HOLD;
                    return;
                }
                task[0].cancel();
                for (Player p : watching) if (p.isOnline()) p.hideBossBar(bar);
                if (onDone != null) onDone.run();
            }
        }, 1L, 1L);
    }

    /** The candidates in a row, the ones struck out as crosses (red for the one just struck), and how many are left. */
    private static net.kyori.adventure.text.Component line(String head, List<Choice> candidates, boolean[] out, int justOut) {
        StringBuilder sb = new StringBuilder(head);
        int left = 0;
        for (int i = 0; i < candidates.size(); i++) {
            if (out[i]) {
                sb.append(i == justOut ? "&#FF5C5C✕ " : "&8✕ ");
            } else {
                left++;
                sb.append("&f").append(candidates.get(i).sprite()).append(' ');
            }
        }
        sb.append("  &#A6E4F5").append(left).append(" / ").append(candidates.size());
        return Texts.parse(sb.toString());
    }
}

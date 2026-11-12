package fr.influencraft.flux.api.draw;

import fr.influencraft.flux.api.client.Clients;
import fr.influencraft.flux.api.client.Toasts;
import fr.influencraft.flux.api.music.Music;
import fr.influencraft.flux.api.text.ItemSprites;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.Sounds;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A draw shown to players while they keep playing, without any menu. At the top of the screen, over a drawn banner,
 * the candidates stand side by side and are struck out one by one, slower and slower, until the winner is the only
 * one left; then a toast slides in at the top right and a few sparks fly round the player. A short drum roll plays
 * meanwhile ({@code tirage} of FluxMusic).
 *
 * <p>How the banner stays behind the text without measuring it: three boss bars, drawn in the order they were shown.
 * The first two are YELLOW, whose bar picture is empty in the server's pack (docs/technique/itemsadder.md): the
 * first carries the banner glyph ({@code fluxdraw:banner}, 300 x 60, pulled up behind the two bars below it), the
 * second the heading; the third, BLUE, the candidates. Each text is centred on its own. Icons are the client's
 * own sprites ({@link ItemSprites}); the sparks and the shining frame are animated textures of the blocks atlas
 * ({@code block/fluxdraw_spark}, {@code block/fluxdraw_shine}), played by the client itself. Pictures from
 * {@code Flux/assets/draw/gen_draw.py}.</p>
 *
 * <p>Bedrock players (no pack) see nothing of it: the caller tells the result in the chat once {@code onDone} runs.</p>
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

    /** What the animation does in the blue bar: its steps, at its pace; the shell does the rest. */
    interface Animation {
        /** Line shown before the first step. */
        Component start();

        /** Ticks before each step (its length is the number of steps). */
        int[] delays();

        /** Line after the step {@code index}. */
        Component step(int index);

        /** Progress of the bar after a step (0 to 1). */
        default float progress(int index) {
            return (index + 1) / (float) delays().length;
        }

        /** Named sound of a step (null: none). */
        default String sound(int index) {
            return "draw-tick";
        }
    }

    /** Ticks the result stays. */
    private static final int HOLD = 60;
    static final String SPARK = "<sprite:block/fluxdraw_spark>", SHINE = "<sprite:block/fluxdraw_shine>";
    private static final Component BANNER = Component.text("").font(Key.key("fluxdraw", "banner"));

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
        List<Choice> choices = new ArrayList<>(pool == null ? List.of() : pool);
        if (!choices.contains(winner)) choices.add(winner);
        Animation a = new EliminationDraw(choices, winner);
        int[] delays = a.delays();
        String title = heading == null ? "" : heading;
        BossBar banner = BossBar.bossBar(BANNER, 0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        BossBar head = BossBar.bossBar(Texts.parse(SPARK + "  <shadow:#0B2A44>&#2BC4E8&l" + title + "</shadow>  " + SPARK),
                0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        BossBar bar = BossBar.bossBar(a.start(), 0f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
        for (Player p : watching) {
            // in this order: the banner is drawn first, under the two others
            p.showBossBar(banner);
            p.showBossBar(head);
            p.showBossBar(bar);
            Music.jingle(p, "tirage");
        }

        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(owner, new Runnable() {
            int step = 0, wait = delays.length == 0 ? 1 : delays[0];
            boolean revealed;

            @Override
            public void run() {
                if (--wait > 0) return;
                if (!revealed && step < delays.length) {
                    bar.name(a.step(step));
                    bar.progress(Math.max(0f, Math.min(1f, a.progress(step))));
                    String cue = a.sound(step);
                    if (cue != null) for (Player p : watching) if (p.isOnline()) Sounds.cue(p, cue);
                    step++;
                    wait = step < delays.length ? delays[step] : 12;
                    return;
                }
                if (!revealed) {
                    revealed = true;
                    bar.progress(1f);
                    bar.name(Texts.parse(SHINE + " " + winner.sprite() + "  <shadow:#2BC4E8>&f&l" + winner.name() + "</shadow>  "
                            + winner.sprite() + " " + SHINE));
                    List<Player> online = new ArrayList<>();
                    for (Player p : watching) if (p.isOnline()) online.add(p);
                    for (Player p : online) {
                        Sounds.cue(p, "draw-reveal");
                        sparks(p);
                    }
                    Toasts.show(owner, online, winner.icon(), Texts.parse("&#2BC4E8" + title + " &7: &f" + winner.name()), Toasts.Frame.GOAL);
                    wait = HOLD;
                    return;
                }
                task[0].cancel();
                for (Player p : watching) {
                    if (!p.isOnline()) continue;
                    p.hideBossBar(bar);
                    p.hideBossBar(head);
                    p.hideBossBar(banner);
                }
                if (onDone != null) onDone.run();
            }
        }, 1L, 1L);
    }

    /** A burst of light round the player's head, seen by him alone. */
    private static void sparks(Player p) {
        Location at = p.getEyeLocation().add(p.getEyeLocation().getDirection().multiply(1.5)).add(0, 0.6, 0);
        p.spawnParticle(Particle.END_ROD, at, 24, 0.9, 0.5, 0.9, 0.03);
        p.spawnParticle(Particle.FIREWORK, at, 18, 0.6, 0.4, 0.6, 0.06);
    }
}

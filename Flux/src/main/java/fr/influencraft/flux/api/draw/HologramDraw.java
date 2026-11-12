package fr.influencraft.flux.api.draw;

import fr.influencraft.flux.api.text.Texts;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A real item turning in the air in front of each viewer, above his line of sight, shown to him alone (display
 * entities hidden from everybody else); it changes slower and slower, then grows and glows with the name below.
 * The displays follow the player every tick and never block a click (displays have no hit box).
 */
final class HologramDraw implements Draws.Animation {

    private static final int STEPS = 26;
    private static final double AHEAD = 3.2, ABOVE = 1.35;
    private final Draws.Show show;
    private final List<Draws.Choice> reel;
    private final Map<UUID, ItemDisplay> items = new HashMap<>();
    private final Map<UUID, TextDisplay> labels = new HashMap<>();
    private final int follower;
    private float angle;
    private float scale = 0.7f;

    HologramDraw(Draws.Show show) {
        this.show = show;
        this.reel = show.reel(STEPS);
        for (Player p : show.online()) {
            ItemDisplay d = p.getWorld().spawn(spot(p), ItemDisplay.class, e -> {
                e.setPersistent(false);
                e.setVisibleByDefault(false);
                e.setBillboard(Display.Billboard.FIXED);
                e.setTeleportDuration(2);
                e.setInterpolationDuration(3);
                e.setBrightness(new Display.Brightness(15, 15));
                e.setItemStack(item(reel.get(0)));
            });
            p.showEntity(show.owner(), d);
            items.put(p.getUniqueId(), d);
        }
        // the displays follow the player's eyes, smoothed by their teleport duration
        follower = org.bukkit.Bukkit.getScheduler().runTaskTimer(show.owner(), this::follow, 1L, 1L).getTaskId();
    }

    /** Ahead of the player, a little above where he looks horizontally: in sight, out of the way. */
    private static Location spot(Player p) {
        Location eye = p.getEyeLocation();
        Vector ahead = eye.getDirection().setY(0);
        if (ahead.lengthSquared() < 1e-4) ahead = new Vector(0, 0, 1);
        ahead.normalize().multiply(AHEAD);
        Location at = eye.clone().add(ahead).add(0, ABOVE, 0);
        at.setYaw(eye.getYaw() + 180);
        at.setPitch(0);
        return at;
    }

    private void follow() {
        angle += 0.35f;
        for (Player p : show.online()) {
            ItemDisplay d = items.get(p.getUniqueId());
            if (d == null || !d.isValid()) continue;
            if (!d.getWorld().equals(p.getWorld())) {
                d.remove();
                continue;
            }
            d.teleport(spot(p));
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(angle, 0, 1, 0),
                    new Vector3f(scale, scale, scale), new AxisAngle4f()));
            TextDisplay t = labels.get(p.getUniqueId());
            if (t != null && t.isValid()) t.teleport(spot(p).add(0, -0.75, 0));
        }
    }

    private static ItemStack item(Draws.Choice c) {
        return new ItemStack(c.icon() == null ? org.bukkit.Material.PAPER : c.icon());
    }

    @Override
    public int[] delays() {
        return Draws.wheel(STEPS, 10);
    }

    @Override
    public void step(int index) {
        ItemStack stack = item(reel.get(index));
        for (ItemDisplay d : items.values()) if (d.isValid()) d.setItemStack(stack);
    }

    @Override
    public void reveal() {
        scale = 1.15f;
        ItemStack stack = item(show.winner());
        for (Player p : show.online()) {
            ItemDisplay d = items.get(p.getUniqueId());
            if (d == null || !d.isValid()) continue;
            d.setItemStack(stack);
            d.setGlowing(true);
            d.setGlowColorOverride(Color.fromRGB(0xFFD94D));
            TextDisplay t = p.getWorld().spawn(spot(p).add(0, -0.75, 0), TextDisplay.class, e -> {
                e.setPersistent(false);
                e.setVisibleByDefault(false);
                e.setBillboard(Display.Billboard.CENTER);
                e.setTeleportDuration(2);
                e.setBackgroundColor(Color.fromARGB(150, 30, 10, 60));
                e.text(Texts.parse("&#FFD94D✦ &f" + show.winner().name() + " &#FFD94D✦"));
            });
            p.showEntity(show.owner(), t);
            labels.put(p.getUniqueId(), t);
        }
    }

    @Override
    public int hold() {
        return 60;
    }

    @Override
    public void close() {
        org.bukkit.Bukkit.getScheduler().cancelTask(follower);
        List<org.bukkit.entity.Entity> all = new ArrayList<>(items.values());
        all.addAll(labels.values());
        for (org.bukkit.entity.Entity e : all) if (e.isValid()) e.remove();
        items.clear();
        labels.clear();
    }
}

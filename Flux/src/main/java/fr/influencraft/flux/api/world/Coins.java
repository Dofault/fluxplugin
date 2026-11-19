package fr.influencraft.flux.api.world;

import fr.influencraft.flux.api.client.Clients;
import fr.influencraft.flux.api.inventory.Items;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Coins springing out of a place and flying to a player: the picture of a gain (dungeon tokens out of a monster, money
 * out of a block a job pays). Only a show: the plugin gives the money itself, before or after. Coins are item displays,
 * never saved, never picked up, gone after a few seconds whatever happens.
 *
 * <pre>
 *   Coins.drop(icon).burst(monster.getLocation(), 6, party);        // thrown, bounce, rest, fly: seen by everybody
 *   Coins.pop(icon).burst(block.getLocation().toCenterLocation(), 1, List.of(player)); // quick, seen by him only
 * </pre>
 */
public final class Coins {

    /** How coins look and move. Made by {@link #drop} or {@link #pop}, adjusted with the setters. */
    public static final class Show {
        private final ItemStack icon;
        private float scale = 0.45f;
        private int flyAfter = 34;
        private double out = 0.09, outRandom = 0.08, up = 0.42, upRandom = 0.16;
        private boolean bounce = true;
        private boolean privateShow;
        private boolean throwSound = true;
        private float pickupVolume = 0.5f;
        private int maxPerPlayer = Integer.MAX_VALUE;

        private Show(ItemStack icon) {
            this.icon = icon;
        }

        /** Size of the coin (1 = an item held in hand). */
        public Show scale(float value) {
            scale = value;
            return this;
        }

        /** Each player sees only the coins flying to him (a burst then throws {@code count} coins per collector). */
        public Show privateShow(boolean value) {
            privateShow = value;
            return this;
        }

        /** Coins in flight to one player at most: more are not thrown (private shows only). */
        public Show maxPerPlayer(int value) {
            maxPerPlayer = Math.max(1, value);
            return this;
        }

        /** Volume of the pickup sound (0 = silent). */
        public Show pickupVolume(float value) {
            pickupVolume = value;
            return this;
        }

        /**
         * Throws {@code count} coins from {@code at}. Each one flies to the nearest collector still there (the same
         * world, alive); nobody left: it fades.
         */
        public void burst(Location at, int count, Collection<? extends Player> collectors) {
            Coins.burst(this, at, count, collectors);
        }
    }

    private static final class Coin {
        final ItemDisplay display;
        final Show show;
        final List<Player> collectors;
        double x, y, z, vx, vy, vz;
        int age, bounces;
        boolean resting;
        Player target;

        Coin(ItemDisplay display, Show show, List<Player> collectors) {
            this.display = display;
            this.show = show;
            this.collectors = collectors;
        }
    }

    private static final List<Coin> COINS = new ArrayList<>();
    private static final Map<UUID, Integer> FLYING = new HashMap<>();
    private static final Map<UUID, Integer> LAST_PICKUP = new HashMap<>();
    /** Pickup volume of {@link #pop} coins. */
    public static final float POP_VOLUME = 0.22f;
    private static BukkitTask task;
    private static Plugin flux;

    private Coins() {
    }

    /** Coins thrown high, falling a few blocks away, bouncing once, resting, then flying: a monster's loot. */
    public static Show drop(ItemStack icon) {
        return new Show(icon);
    }

    /** One short hop then straight to the player, seen by him only, quiet: a gain repeated many times a minute. */
    public static Show pop(ItemStack icon) {
        Show s = new Show(icon);
        s.scale = 0.34f;
        s.flyAfter = 7;
        s.out = 0.03;
        s.outRandom = 0.05;
        s.up = 0.2;
        s.upRandom = 0.08;
        s.bounce = false;
        s.privateShow = true;
        s.throwSound = false;
        s.pickupVolume = POP_VOLUME;
        s.maxPerPlayer = 10;
        return s;
    }

    private static void burst(Show show, Location at, int count, Collection<? extends Player> collectors) {
        World world = at.getWorld();
        if (world == null || show.icon == null || count <= 0 || collectors.isEmpty()) return;
        if (flux == null) {
            flux = JavaPlugin.getProvidingPlugin(Coins.class);
            Bukkit.getPluginManager().registerEvents(new Listener() {
                @EventHandler
                public void onDisable(PluginDisableEvent event) {
                    if (event.getPlugin() == flux) clear();
                }
            }, flux);
        }
        List<Player> all = List.copyOf(collectors);
        if (show.privateShow) {
            for (Player p : all) {
                int room = show.maxPerPlayer - FLYING.getOrDefault(p.getUniqueId(), 0);
                for (int i = 0; i < Math.min(count, room); i++) spawn(show, at, i, List.of(p));
            }
        } else {
            for (int i = 0; i < count; i++) spawn(show, at, i, all);
        }
        if (show.throwSound) {
            world.playSound(at, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 0.6f);
            world.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.4f);
        }
        if (task == null && !COINS.isEmpty()) task = Bukkit.getScheduler().runTaskTimer(flux, Coins::tick, 1L, 1L);
    }

    private static void spawn(Show show, Location at, int index, List<Player> collectors) {
        Player only = show.privateShow ? collectors.get(0) : null;
        ItemStack icon = show.icon;
        if (only != null && Clients.bedrock(only)) {
            // no resource pack there: the vanilla stand-in of a custom item
            String custom = Items.itemsAdderId(icon);
            if (custom != null) icon = new ItemStack(Clients.bedrockItem(custom));
        }
        ItemStack shown = icon;
        ItemDisplay d = at.getWorld().spawn(at, ItemDisplay.class, e -> {
            e.setItemStack(shown);
            e.setPersistent(false);
            if (only != null) e.setVisibleByDefault(false);
            e.setBillboard(Display.Billboard.VERTICAL);
            e.setTeleportDuration(1);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                    new Vector3f(show.scale, show.scale, show.scale), new AxisAngle4f()));
        });
        if (only != null) {
            only.showEntity(flux, d);
            FLYING.merge(only.getUniqueId(), 1, Integer::sum);
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Coin c = new Coin(d, show, collectors);
        c.x = at.getX();
        c.y = at.getY();
        c.z = at.getZ();
        double angle = r.nextDouble(Math.PI * 2);
        double out = show.out + r.nextDouble(show.outRandom);
        c.vx = Math.cos(angle) * out;
        c.vz = Math.sin(angle) * out;
        c.vy = show.up + r.nextDouble(show.upRandom);
        c.age = -index;  // a short delay between coins
        COINS.add(c);
    }

    private static void tick() {
        for (Iterator<Coin> it = COINS.iterator(); it.hasNext(); ) {
            Coin c = it.next();
            boolean gone = !c.display.isValid();
            if (!gone) {
                c.age++;
                if (c.age <= 0) continue;
                gone = step(c);
                if (gone) c.display.remove();
            }
            if (gone) {
                it.remove();
                if (c.show.privateShow) {
                    FLYING.computeIfPresent(c.collectors.get(0).getUniqueId(), (k, n) -> n <= 1 ? null : n - 1);
                }
            }
        }
        if (COINS.isEmpty() && task != null) {
            task.cancel();
            task = null;
        }
    }

    /** One tick of a coin; true when it is gone. */
    private static boolean step(Coin c) {
        World world = c.display.getWorld();
        int flyAfter = c.show.flyAfter;
        if (c.age > flyAfter + 86) return true;   // nobody near: it fades
        if (c.target == null && c.age > flyAfter) c.target = nearest(c);
        if (c.target != null) {
            // flies to the player and is picked up
            if (!c.target.isValid() || c.target.getWorld() != world) return true;
            Location chest = c.target.getLocation().add(0, 1.0, 0);
            Vector to = chest.toVector().subtract(new Vector(c.x, c.y, c.z));
            double dist = to.length();
            if (dist < 0.6 || c.age > flyAfter + 46) {
                pickup(c);
                return true;
            }
            double speed = Math.min(dist, 0.25 + (c.age - flyAfter) * 0.04);
            to.normalize().multiply(speed);
            c.x += to.getX();
            c.y += to.getY();
            c.z += to.getZ();
        } else if (!c.resting) {
            c.vy -= 0.045;
            double nx = c.x + c.vx, ny = c.y + c.vy, nz = c.z + c.vz;
            Block below = world.getBlockAt((int) Math.floor(nx), (int) Math.floor(ny - 0.15), (int) Math.floor(nz));
            if (c.show.bounce && c.vy < 0 && !below.isPassable()) {
                ny = below.getBoundingBox().getMaxY() + 0.15;
                if (c.bounces++ == 0) {
                    c.vy = -c.vy * 0.35;
                    c.vx *= 0.55;
                    c.vz *= 0.55;
                    world.playSound(new Location(world, nx, ny, nz), Sound.BLOCK_CHAIN_STEP, 0.4f, 1.8f);
                } else {
                    c.resting = true;
                }
            }
            if (c.show.bounce
                    && !world.getBlockAt((int) Math.floor(nx), (int) Math.floor(ny), (int) Math.floor(nz)).isPassable()) {
                c.vx = 0;
                c.vz = 0;
                nx = c.x;
                nz = c.z;
            }
            c.x = nx;
            c.y = ny;
            c.z = nz;
        }
        c.display.teleport(new Location(world, c.x, c.y, c.z));
        return false;
    }

    private static void pickup(Coin c) {
        chime(c.target, c.show.pickupVolume);
    }

    /**
     * The sound of a coin picked up, heard by this player only, at most once every 3 ticks: a vein of blocks is one
     * chime, not twenty. Played by the coins themselves; call it for a player who hears his gains without seeing them.
     */
    public static void chime(Player player, float volume) {
        if (volume <= 0) return;
        int now = Bukkit.getCurrentTick();
        Integer last = LAST_PICKUP.get(player.getUniqueId());
        if (last != null && now - last >= 0 && now - last < 3) return;
        LAST_PICKUP.put(player.getUniqueId(), now);
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, volume,
                1.6f + (float) ThreadLocalRandom.current().nextDouble(0.3));
    }

    private static Player nearest(Coin c) {
        Player best = null;
        double bestD = 48 * 48;
        Location here = c.display.getLocation();
        for (Player p : c.collectors) {
            if (!p.isValid() || p.isDead() || p.getGameMode() == GameMode.SPECTATOR || p.getWorld() != here.getWorld()) continue;
            double d = p.getLocation().distanceSquared(here);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    private static void clear() {
        for (Coin c : COINS) if (c.display.isValid()) c.display.remove();
        COINS.clear();
        FLYING.clear();
        LAST_PICKUP.clear();
        if (task != null) task.cancel();
        task = null;
    }
}

package fr.influencraft.fluxdungeons.reward;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.economy.Currency;
import fr.influencraft.flux.api.economy.Economy;
import fr.influencraft.flux.api.economy.Origin;
import fr.influencraft.flux.api.inventory.Items;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Dungeon tokens (2026-11-14): a Flux currency ({@code tokens.currency}, {@code dungeon_tokens}) every player of the
 * party gets for each monster killed in a run, by its rank ({@code tokens.per-rank}) times the difficulty's reward,
 * spent at the potion merchant of the rpg lobby.
 * <p>
 * The tokens spring out of the monster: coins (item displays) thrown up in an arc, they fall a few blocks away, bounce
 * once, rest a moment, then fly to the nearest player of the party with the sound of an experience orb. Only a show:
 * the tokens are already in the account. No coin is ever saved (not persistent) and the dungeon world goes with them.
 */
public final class DungeonTokens {

    private static final class Coin {
        final ItemDisplay display;
        final List<Player> party;
        double x, y, z, vx, vy, vz;
        int age, bounces;
        boolean resting;
        Player target;

        Coin(ItemDisplay display, List<Player> party) {
            this.display = display;
            this.party = party;
        }
    }

    private final FluxPlugin plugin;
    private final List<Coin> coins = new ArrayList<>();
    private BukkitTask task;
    private String currency = "dungeon_tokens";
    private final Map<String, Integer> perRank = new HashMap<>();
    private int maxCoins = 6;

    public DungeonTokens(FluxPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload(ConfigurationSection c) {
        currency = c == null ? "dungeon_tokens" : c.getString("currency", "dungeon_tokens");
        perRank.clear();
        perRank.putAll(Map.of("TRASH", 1, "ELITE", 2, "MINIBOSS", 5, "BOSS", 20));
        ConfigurationSection ranks = c == null ? null : c.getConfigurationSection("per-rank");
        if (ranks != null) for (String k : ranks.getKeys(false)) perRank.put(k.toUpperCase(Locale.ROOT), ranks.getInt(k));
        maxCoins = c == null ? 6 : Math.max(1, c.getInt("max-coins-shown", 6));
    }

    public Currency currency() {
        return Economy.get(currency);
    }

    public String currencyId() {
        return currency;
    }

    /** Tokens of a monster of this rank at this difficulty reward (random rounding: 1.5 is 1 or 2). */
    public int amount(String rank, double reward) {
        double base = perRank.getOrDefault(rank == null ? "TRASH" : rank.toUpperCase(Locale.ROOT), 1) * Math.max(0, reward);
        int n = (int) base;
        if (ThreadLocalRandom.current().nextDouble() < base - n) n++;
        return n;
    }

    /** The base amount of a rank (menus: what a monster gives). */
    public int base(String rank) {
        return perRank.getOrDefault(rank.toUpperCase(Locale.ROOT), 1);
    }

    /** Gives {@code amount} to each player of the party and throws the coins out of the monster. */
    public void award(Location at, List<Player> party, int amount, Map<java.util.UUID, Integer> earned) {
        Currency money = currency();
        if (amount <= 0 || party.isEmpty()) return;
        if (money != null) {
            for (Player p : party) {
                money.give(p, amount, Origin.of(plugin, "dungeon_kill"));
                earned.merge(p.getUniqueId(), amount, Integer::sum);
            }
        }
        burst(at, Math.min(maxCoins, amount), party);
    }

    // ---------------------------------------------------------------- the show

    private void burst(Location at, int n, List<Player> party) {
        World world = at.getWorld();
        if (world == null) return;
        ItemStack coin = Items.of("itemsadder:fluxdonjon:jeton_donjon", (Player) null);
        if (coin == null) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < n; i++) {
            Location start = at.clone().add(0, 0.8, 0);
            ItemDisplay d = world.spawn(start, ItemDisplay.class, e -> {
                e.setItemStack(coin);
                e.setPersistent(false);
                e.setBillboard(Display.Billboard.VERTICAL);
                e.setTeleportDuration(1);
                e.setBrightness(new Display.Brightness(15, 15));
                e.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(0.45f, 0.45f, 0.45f),
                        new AxisAngle4f()));
            });
            Coin c = new Coin(d, party);
            c.x = start.getX();
            c.y = start.getY();
            c.z = start.getZ();
            double angle = r.nextDouble(Math.PI * 2);
            double out = 0.09 + r.nextDouble(0.08);
            c.vx = Math.cos(angle) * out;
            c.vz = Math.sin(angle) * out;
            c.vy = 0.42 + r.nextDouble(0.16);
            c.age = -i;  // a short delay between coins
            coins.add(c);
        }
        world.playSound(at, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 0.6f);
        world.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.4f);
        if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private void tick() {
        for (Iterator<Coin> it = coins.iterator(); it.hasNext(); ) {
            Coin c = it.next();
            if (!c.display.isValid()) {
                it.remove();
                continue;
            }
            c.age++;
            if (c.age <= 0) continue;
            if (step(c)) {
                c.display.remove();
                it.remove();
            }
        }
        if (coins.isEmpty() && task != null) {
            task.cancel();
            task = null;
        }
    }

    /** One tick of a coin; true when it is gone. */
    private boolean step(Coin c) {
        World world = c.display.getWorld();
        if (c.age > 120) return true;   // nobody of the party near: it fades
        if (c.target == null && c.age > 34) c.target = nearest(c);
        if (c.target != null) {
            // flies to the player and is picked up
            if (!c.target.isValid() || c.target.getWorld() != world) return true;
            Location eye = c.target.getLocation().add(0, 1.0, 0);
            Vector to = eye.toVector().subtract(new Vector(c.x, c.y, c.z));
            double dist = to.length();
            if (dist < 0.6 || c.age > 80) {
                c.target.playSound(c.target.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.6f + (float) ThreadLocalRandom.current().nextDouble(0.3));
                return true;
            }
            double speed = Math.min(dist, 0.25 + (c.age - 34) * 0.04);
            to.normalize().multiply(speed);
            c.x += to.getX();
            c.y += to.getY();
            c.z += to.getZ();
        } else if (!c.resting) {
            c.vy -= 0.045;
            double nx = c.x + c.vx, ny = c.y + c.vy, nz = c.z + c.vz;
            Block below = world.getBlockAt((int) Math.floor(nx), (int) Math.floor(ny - 0.15), (int) Math.floor(nz));
            if (c.vy < 0 && !below.isPassable()) {
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
            if (!world.getBlockAt((int) Math.floor(nx), (int) Math.floor(ny), (int) Math.floor(nz)).isPassable()) {
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

    private static Player nearest(Coin c) {
        Player best = null;
        double bestD = 48 * 48;
        for (Player p : c.party) {
            if (!p.isValid() || p.getWorld() != c.display.getWorld()) continue;
            double d = p.getLocation().distanceSquared(c.display.getLocation());
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    public void shutdown() {
        for (Coin c : coins) if (c.display.isValid()) c.display.remove();
        coins.clear();
        if (task != null) task.cancel();
        task = null;
    }
}

package fr.influencraft.fluxdungeons.dungeon;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.client.Camera;
import fr.influencraft.flux.api.client.Clients;
import fr.influencraft.flux.api.client.Sidebar;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.fluxdungeons.mob.MobTemplate;
import fr.influencraft.fluxdungeons.mob.Mobs;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The boss room of a map dungeon: the boss waits asleep until a fighter stands in its arena, then the whole party is
 * gathered in front of it and the fight starts for everybody at once. Around that, what a dungeon may add in its
 * {@code boss-room} section: a door closed behind the party, a scene before the fight and one when the boss dies
 * (camera on a path, the boss's animations, sounds, titles), and short lines the boss says during the fight.
 *
 * <pre>
 * boss-room:
 *   enabled: true        # false: the boss notices players as any monster does
 *   radius: 14           # a fighter on the ground within this many blocks of the boss starts it
 *   height: 6            # and within this many blocks of its height
 *   distance: 8          # the party is gathered this far in front of the boss
 *   close-door: id       # a door of the map put back when the fight starts
 *   intro: {scene}       # played before the fight
 *   outro: {scene}       # played when the boss dies, on a stand-in of its model; the victory waits for it
 *   speech:
 *     every: [22, 38]    # seconds between two lines of the fight
 *     fight: [lines]
 *     kill: [lines]      # a player fell
 *     thresholds: [{below: 0.5, lines: [...]}]   # once each, under that share of its life
 *
 * scene:
 *   duration: 180        # ticks
 *   animation: intro     # of the boss (outro: of its stand-in), started at tick 0
 *   camera:              # places in the boss's own frame [right, up, forward], seen looking at `look`
 *   - {at: 0, pos: [6, 5, 17], look: [0, 1.8, 0]}
 *   - {at: 50, pos: [3, 3.5, 12], look: [0, 1.8, 0]}
 *   - {at: 51, pos: [-2, 1, 5.5], look: [0, 1.3, 2], cut: true}   # cut: no glide from the former place
 *   events:
 *   - {at: 20, sound: 'minecraft:entity.warden.emerge', volume: 1, pitch: 0.6}
 *   - {at: 60, say: 'A line of the boss'}
 *   - {at: 148, title: '...', subtitle: '...'}
 *   - {at: 148, shake: 16, power: 0.12}
 *   - {at: 70, particle: CAMPFIRE_COSY_SMOKE, count: 20, spread: 1.5, pos: [0, 1, 0]}
 *   - {at: 30, animation: other}
 * </pre>
 * Scenes are written in the boss's frame, so they play in any copy of the map. Players are seated where they stand
 * during a scene (sneaking asks to skip it: it ends once every viewer asked); Java players watch through the camera,
 * Bedrock players (no camera packet) keep their own view.
 */
public final class BossRooms implements Listener {

    private static final String SEAT = "fluxdungeons_scene_seat";

    enum Stage {WAITING, SCENE, FIGHT, OUTRO, DONE}

    /** What a run's boss room is doing. */
    static final class State {
        Stage stage = Stage.WAITING;
        UUID boss;
        Location home;
        boolean hadAi = true;
        long nextLine;
        String lastLine;
        final Set<Double> crossed = new HashSet<>();
        BossBar speech;
        int speechTask = -1;
        Playing scene;
    }

    private final FluxPlugin plugin;
    private final Runs runs;
    private final Mobs mobs;
    private final Map<String, State> states = new HashMap<>();
    private final Map<UUID, Playing> seated = new HashMap<>();

    BossRooms(FluxPlugin plugin, Runs runs, Mobs mobs) {
        this.plugin = plugin;
        this.runs = runs;
        this.mobs = mobs;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private static ConfigurationSection section(Run run) {
        return run.def.bossRoom();
    }

    private static boolean enabled(Run run) {
        ConfigurationSection c = section(run);
        return run.def.burrows() == null && (c == null || c.getBoolean("enabled", true));
    }

    private static double number(Run run, String key, double fallback) {
        ConfigurationSection c = section(run);
        return c == null ? fallback : c.getDouble(key, fallback);
    }

    // ---------------------------------------------------------------- the gate

    /** A monster of the run just appeared: its boss falls asleep until a fighter stands in its arena. */
    void spawned(Run run, LivingEntity mob, MobTemplate t) {
        if (t == null || !t.boss() || !mobs.isObjective(mob) || !enabled(run)) return;
        State s = states.computeIfAbsent(run.id, k -> new State());
        if (s.stage != Stage.WAITING || s.boss != null) return;
        s.boss = mob.getUniqueId();
        s.home = mob.getLocation();
        s.hadAi = !(mob instanceof org.bukkit.entity.Mob m) || m.hasAI();
        mobs.sleep(mob, true);
    }

    /** Twice a second. */
    void tick(Run run) {
        State s = states.get(run.id);
        if (s == null || run.state != Run.State.RUNNING) return;
        if (s.stage == Stage.WAITING) {
            LivingEntity boss = boss(s);
            if (boss == null) return;
            double r = number(run, "radius", 14), h = number(run, "height", 6);
            for (Player p : runs.fighters(run)) {
                Location l = p.getLocation();
                if (l.getWorld() != s.home.getWorld() || !((Entity) p).isOnGround()) continue;
                if (Math.abs(l.getY() - s.home.getY()) > h) continue;
                if (Math.hypot(l.getX() - s.home.getX(), l.getZ() - s.home.getZ()) > r) continue;
                start(run, s, boss, p);
                return;
            }
        } else if (s.stage == Stage.FIGHT) {
            speech(run, s);
        }
    }

    private LivingEntity boss(State s) {
        return s.boss != null && Bukkit.getEntity(s.boss) instanceof LivingEntity le && le.isValid() ? le : null;
    }

    /** The boss of the fight, whatever body it has now (a phase change gives it a new one). */
    private LivingEntity fightingBoss(Run run, State s) {
        LivingEntity b = boss(s);
        if (b != null) return b;
        for (UUID id : run.mobs) {
            if (!(Bukkit.getEntity(id) instanceof LivingEntity le) || !le.isValid() || !mobs.isObjective(le)) continue;
            MobTemplate t = mobs.templateOfEntity(le);
            if (t == null || !t.boss()) continue;
            s.boss = id;
            return le;
        }
        return null;
    }

    private void start(Run run, State s, LivingEntity boss, Player trigger) {
        ConfigurationSection intro = section(run) == null ? null : section(run).getConfigurationSection("intro");
        Runnable fight = () -> {
            if (run.state != Run.State.RUNNING) return;
            gather(run, s, trigger);
            closeDoor(run);
            LivingEntity b = boss(s);
            if (b != null) {
                mobs.sleep(b, false);
                if (!s.hadAi && b instanceof org.bukkit.entity.Mob m) m.setAI(false);
                List<Player> fighters = runs.fighters(run);
                Player first = trigger.isOnline() && fighters.contains(trigger) ? trigger : fighters.isEmpty() ? null : fighters.get(0);
                if (first != null) mobs.targeted(b, first);
            }
            s.stage = Stage.FIGHT;
            s.nextLine = Bukkit.getCurrentTick() + seconds(run, 0.6);
        };
        if (intro == null) {
            // no scene: the boss's name and a sound tell the party why it was just moved
            MobTemplate t = mobs.templateOfEntity(boss);
            Title title = Title.title(Texts.parse(t == null ? "" : t.name()), Texts.parse(plugin.messages().raw("room.boss")),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(2), Duration.ofMillis(500)));
            for (Player p : runs.online(run)) {
                p.showTitle(title);
                p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.5f, 0.8f);
            }
            fight.run();
            return;
        }
        s.stage = Stage.SCENE;
        play(run, s, intro, s.home, anim -> mobs.play(boss, anim), fight);
    }

    /** Everybody on the arena floor in front of the boss, looking at it: nobody starts the fight hanging on a wall. */
    private void gather(Run run, State s, Player trigger) {
        Location home = s.home;
        Vector f = forward(home), r = new Vector(-f.getZ(), 0, f.getX());
        double wanted = number(run, "distance", 8);
        double[] side = {0, 1.5, -1.5, 3, -3, 4.5, -4.5};
        List<Player> party = runs.fighters(run);
        Location first = null;
        for (int i = 0; i < party.size(); i++) {
            Location spot = null;
            for (double d = wanted; d >= 3 && spot == null; d -= 1.5) {
                spot = floor(home.clone().add(f.clone().multiply(d)).add(r.clone().multiply(side[i % side.length])));
            }
            if (spot == null) spot = first != null ? first.clone() : trigger.isOnline() ? trigger.getLocation() : null;
            if (spot == null) continue;
            spot.setDirection(home.toVector().add(new Vector(0, 1, 0)).subtract(spot.toVector()));
            spot.setPitch(0);
            if (first == null) first = spot.clone();
            Player p = party.get(i);
            runs.teleport(p, spot);
            p.setFallDistance(0);
            p.setVelocity(new Vector());
        }
        if (first != null) run.checkpoint = first.clone();
    }

    /** The standable place of this column around the boss's height (solid below, room for a player), null when none. */
    private static Location floor(Location at) {
        for (int dy = 2; dy >= -3; dy--) {
            Block feet = at.getWorld().getBlockAt(at.getBlockX(), at.getBlockY() + dy, at.getBlockZ());
            if (!feet.isPassable() || feet.isLiquid() || !feet.getRelative(0, 1, 0).isPassable()) continue;
            Block below = feet.getRelative(0, -1, 0);
            if (!below.getType().isSolid()) continue;
            return new Location(at.getWorld(), at.getBlockX() + 0.5, feet.getY(), at.getBlockZ() + 0.5);
        }
        return null;
    }

    private void closeDoor(Run run) {
        ConfigurationSection c = section(run);
        String id = c == null ? null : c.getString("close-door");
        if (id == null || id.isBlank()) return;
        Map<Block, org.bukkit.block.data.BlockData> blocks = run.doorBlocks.remove(id);
        // a door that never opened is closed already; it must not open later either
        run.openDoors.add(id);
        if (blocks == null || blocks.isEmpty()) return;
        Location center = null;
        for (Map.Entry<Block, org.bukkit.block.data.BlockData> e : blocks.entrySet()) {
            e.getKey().setBlockData(e.getValue(), false);
            if (center == null) center = e.getKey().getLocation().add(0.5, 1, 0.5);
        }
        run.world.playSound(center, Sound.BLOCK_IRON_DOOR_CLOSE, 1.5f, 0.5f);
        run.world.playSound(center, Sound.BLOCK_ANVIL_LAND, 0.6f, 0.5f);
        run.world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, center, 12, 1.2, 0.4, 1.2, 0.01);
    }

    // ---------------------------------------------------------------- the end

    /**
     * The run's last objective just died. True when its death is played as a scene first: {@code victory} then runs
     * at the end of it.
     */
    boolean outro(Run run, LivingEntity mob, MobTemplate t, Runnable victory) {
        State s = states.get(run.id);
        ConfigurationSection c = section(run);
        ConfigurationSection outro = c == null ? null : c.getConfigurationSection("outro");
        if (s == null || outro == null || t == null || !t.boss() || s.stage == Stage.OUTRO) return false;
        clearSpeech(run, s);
        s.stage = Stage.OUTRO;
        Location at = mob.getLocation();
        at.setPitch(0);
        Mobs.Corpse corpse = t.model() == null ? null : mobs.corpse(at, t.model(), runs.online(run));
        play(run, s, outro, at, anim -> {
            if (corpse != null) corpse.play(anim);
        }, () -> {
            if (corpse != null) corpse.close();
            s.stage = Stage.DONE;
            victory.run();
        });
        return true;
    }

    void playerDown(Run run) {
        State s = states.get(run.id);
        if (s != null && s.stage == Stage.FIGHT) say(run, s, pick(s, lines(run, "speech.kill")));
    }

    void ended(Run run) {
        State s = states.remove(run.id);
        if (s == null) return;
        if (s.scene != null) s.scene.end(false);
        clearSpeech(run, s);
    }

    // ---------------------------------------------------------------- speech

    private long seconds(Run run, double share) {
        ConfigurationSection c = section(run);
        List<Double> every = c == null ? List.of() : c.getDoubleList("speech.every");
        double lo = every.size() > 0 ? every.get(0) : 22, hi = every.size() > 1 ? Math.max(lo, every.get(1)) : lo + 16;
        return Math.round((lo + ThreadLocalRandom.current().nextDouble() * (hi - lo)) * 20 * share);
    }

    private static List<String> lines(Run run, String path) {
        ConfigurationSection c = section(run);
        return c == null ? List.of() : c.getStringList(path);
    }

    private static String pick(State s, List<String> lines) {
        if (lines.isEmpty()) return null;
        String line = lines.get(ThreadLocalRandom.current().nextInt(lines.size()));
        if (lines.size() > 1 && line.equals(s.lastLine)) line = lines.get((lines.indexOf(line) + 1) % lines.size());
        return line;
    }

    private void speech(Run run, State s) {
        ConfigurationSection c = section(run);
        if (c == null || !c.isConfigurationSection("speech")) return;
        LivingEntity boss = fightingBoss(run, s);
        if (boss == null) return;
        AttributeInstance max = boss.getAttribute(Attribute.MAX_HEALTH);
        double ratio = max == null || max.getValue() <= 0 ? 1 : boss.getHealth() / max.getValue();
        for (Map<?, ?> t : c.getMapList("speech.thresholds")) {
            if (!(t.get("below") instanceof Number n) || ratio > n.doubleValue() || !s.crossed.add(n.doubleValue())) continue;
            List<String> lines = new ArrayList<>();
            if (t.get("lines") instanceof List<?> l) for (Object o : l) lines.add(String.valueOf(o));
            say(run, s, pick(s, lines));
            s.nextLine = Bukkit.getCurrentTick() + seconds(run, 1);
            return;
        }
        if (Bukkit.getCurrentTick() < s.nextLine) return;
        s.nextLine = Bukkit.getCurrentTick() + seconds(run, 1);
        say(run, s, pick(s, lines(run, "speech.fight")));
    }

    /** A line of the boss, on a text line at the top of the screen for a few seconds. */
    private void say(Run run, State s, String line) {
        if (line == null || line.isBlank()) return;
        s.lastLine = line;
        clearSpeech(run, s);
        // a yellow bar: its picture is empty in the server's pack (as Flux's draws), the line is text only
        BossBar bar = BossBar.bossBar(Texts.parse(plugin.messages().raw("boss.speech", "%line%", line)), 0f,
                BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        s.speech = bar;
        for (Player p : runs.online(run)) p.showBossBar(bar);
        int ticks = Math.max(50, Math.min(120, 40 + line.length() * 2));
        s.speechTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (s.speech == bar) clearSpeech(run, s);
        }, ticks).getTaskId();
    }

    private void clearSpeech(Run run, State s) {
        if (s.speechTask != -1) Bukkit.getScheduler().cancelTask(s.speechTask);
        s.speechTask = -1;
        if (s.speech == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(s.speech);
        s.speech = null;
    }

    // ---------------------------------------------------------------- scenes

    private static Vector forward(Location base) {
        Location flat = base.clone();
        flat.setPitch(0);
        return flat.getDirection().setY(0).normalize();
    }

    private static Vector vector(Object o, Vector fallback) {
        if (!(o instanceof List<?> l) || l.size() < 3) return fallback;
        double[] v = new double[3];
        for (int i = 0; i < 3; i++) v[i] = l.get(i) instanceof Number n ? n.doubleValue() : 0;
        return new Vector(v[0], v[1], v[2]);
    }

    private static int integer(Object o, int fallback) {
        return o instanceof Number n ? n.intValue() : fallback;
    }

    private static double decimal(Object o, double fallback) {
        return o instanceof Number n ? n.doubleValue() : fallback;
    }

    private record Key(int at, Vector pos, Vector look, boolean cut) {
    }

    private void play(Run run, State s, ConfigurationSection scene, Location base, java.util.function.Consumer<String> animate, Runnable after) {
        Playing playing = new Playing(run, s, scene, base, animate, after);
        s.scene = playing;
        playing.runTaskTimer(plugin, 1L, 1L);
    }

    /** A scene being played to the fighters of a run. */
    private final class Playing extends BukkitRunnable {
        private final Run run;
        private final State state;
        private final Location base;
        private final Vector f, r;
        private final int duration;
        private final List<Key> keys = new ArrayList<>();
        private final List<Map<?, ?>> events;
        private final java.util.function.Consumer<String> animate;
        private final Runnable after;
        private final Map<UUID, ArmorStand> seats = new HashMap<>();
        private final Set<UUID> skips = new HashSet<>();
        private ItemDisplay camera;
        private int tick, shakeUntil;
        private double shakePower;
        private boolean over;

        Playing(Run run, State state, ConfigurationSection scene, Location base, java.util.function.Consumer<String> animate, Runnable after) {
            this.run = run;
            this.state = state;
            this.base = base.clone();
            this.f = forward(base);
            this.r = new Vector(-f.getZ(), 0, f.getX());
            this.duration = Math.max(20, scene.getInt("duration", 100));
            this.events = scene.getMapList("events");
            this.animate = animate;
            this.after = after;
            for (Map<?, ?> k : scene.getMapList("camera")) {
                keys.add(new Key(integer(k.get("at"), 0), vector(k.get("pos"), new Vector(0, 3, 10)),
                        vector(k.get("look"), new Vector(0, 1.5, 0)), Boolean.TRUE.equals(k.get("cut"))));
            }
            keys.sort(java.util.Comparator.comparingInt(Key::at));
            String animation = scene.getString("animation", "");
            if (!animation.isBlank()) animate.accept(animation);
            if (!keys.isEmpty()) {
                camera = base.getWorld().spawn(place(keys.get(0).pos(), keys.get(0).look()), ItemDisplay.class, d -> {
                    d.setPersistent(false);
                    d.setTeleportDuration(1);
                });
            }
            for (Player p : runs.fighters(run)) seat(p);
        }

        private void seat(Player p) {
            if (p.getWorld() != base.getWorld()) return;
            if (p.getVehicle() != null) p.leaveVehicle();
            Location at = p.getLocation();
            ArmorStand seat = at.getWorld().spawn(at, ArmorStand.class, a -> {
                a.addScoreboardTag(SEAT);
                a.setPersistent(false);
                a.setMarker(true);
                a.setInvisible(true);
                a.setGravity(false);
                a.setInvulnerable(true);
            });
            seat.addPassenger(p);
            seats.put(p.getUniqueId(), seat);
            seated.put(p.getUniqueId(), this);
            fr.influencraft.fluxdungeons.api.Combat.takeControls(p, true);
            if (camera != null && !Clients.bedrock(p)) {
                Sidebar.hide(p, true);
                Camera.view(p, camera);
            }
            plugin.messages().send(p, "boss.scene-skip");
        }

        private Location place(Vector pos, Vector look) {
            Location at = local(pos);
            Vector to = local(look).toVector().subtract(at.toVector());
            if (to.lengthSquared() > 1e-6) at.setDirection(to);
            return at;
        }

        private Location local(Vector v) {
            return base.clone().add(r.clone().multiply(v.getX())).add(0, v.getY(), 0).add(f.clone().multiply(v.getZ()));
        }

        @Override
        public void run() {
            if (over) return;
            if (run.state != Run.State.RUNNING || tick >= duration) {
                end(true);
                return;
            }
            for (Map<?, ?> e : events) if (integer(e.get("at"), 0) == tick) event(e);
            if (camera != null && camera.isValid()) move();
            tick++;
        }

        private void move() {
            Key from = keys.get(0), to = null;
            for (Key k : keys) {
                if (k.at() <= tick) from = k;
                else {
                    to = k;
                    break;
                }
            }
            Vector pos = from.pos(), look = from.look();
            // a cut waits at the former place, then jumps: the next key is not glided to
            if (to != null && !to.cut() && to.at() > from.at()) {
                double t = (tick - from.at()) / (double) (to.at() - from.at());
                t = t * t * (3 - 2 * t);
                pos = from.pos().clone().multiply(1 - t).add(to.pos().clone().multiply(t));
                look = from.look().clone().multiply(1 - t).add(to.look().clone().multiply(t));
            }
            Location at = place(pos, look);
            if (tick < shakeUntil) {
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                at.add((rnd.nextDouble() - 0.5) * shakePower, (rnd.nextDouble() - 0.5) * shakePower, (rnd.nextDouble() - 0.5) * shakePower);
            }
            boolean jump = from.cut() && from.at() == tick;
            camera.setTeleportDuration(jump ? 0 : 1);
            camera.teleport(at);
            // the client forgets its camera when the entity is sent again: said again now and then
            if (tick % 10 == 3) {
                for (UUID id : seats.keySet()) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null && !Clients.bedrock(p)) Camera.view(p, camera);
                }
            }
        }

        private void event(Map<?, ?> e) {
            List<Player> viewers = runs.online(run);
            if (e.get("sound") != null) {
                Location at = local(vector(e.get("pos"), new Vector(0, 1, 0)));
                float volume = (float) decimal(e.get("volume"), 1), pitch = (float) decimal(e.get("pitch"), 1);
                // heard from the camera, not from where each player was left
                for (Player p : viewers) p.playSound(p.getLocation(), String.valueOf(e.get("sound")), org.bukkit.SoundCategory.HOSTILE, volume, pitch);
                if (viewers.isEmpty()) at.getWorld().playSound(at, String.valueOf(e.get("sound")), volume, pitch);
            }
            if (e.get("say") != null) say(run, state, String.valueOf(e.get("say")));
            if (e.get("title") != null || e.get("subtitle") != null) {
                Title title = Title.title(Texts.parse(e.get("title") == null ? "" : String.valueOf(e.get("title"))),
                        Texts.parse(e.get("subtitle") == null ? "" : String.valueOf(e.get("subtitle"))),
                        Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(integer(e.get("stay"), 40) * 50L), Duration.ofMillis(500)));
                for (Player p : viewers) p.showTitle(title);
            }
            if (e.get("shake") instanceof Number n) {
                shakeUntil = tick + n.intValue();
                shakePower = decimal(e.get("power"), 0.12);
            }
            if (e.get("animation") != null) animate.accept(String.valueOf(e.get("animation")));
            if (e.get("particle") != null) {
                try {
                    Particle particle = Particle.valueOf(String.valueOf(e.get("particle")).toUpperCase(java.util.Locale.ROOT));
                    double spread = decimal(e.get("spread"), 0.5);
                    if (particle.getDataType() == Void.class) {
                        base.getWorld().spawnParticle(particle, local(vector(e.get("pos"), new Vector(0, 1, 0))), integer(e.get("count"), 10),
                                spread, spread / 2, spread, decimal(e.get("speed"), 0.01));
                    }
                } catch (IllegalArgumentException ignored) {
                    plugin.getLogger().warning("Unknown particle in a boss scene of " + run.def.id() + ": " + e.get("particle"));
                }
            }
        }

        /** A viewer asks to skip: the scene ends once every seated viewer did. */
        void skip(Player p) {
            if (over || tick < 20 || !skips.add(p.getUniqueId())) return;
            for (UUID id : seats.keySet()) {
                Player q = Bukkit.getPlayer(id);
                if (q != null && q.isOnline() && !skips.contains(id)) {
                    plugin.messages().send(p, "boss.scene-skip-vote");
                    return;
                }
            }
            end(true);
        }

        void end(boolean then) {
            if (over) return;
            over = true;
            cancel();
            if (state.scene == this) state.scene = null;
            for (Map.Entry<UUID, ArmorStand> e : seats.entrySet()) {
                seated.remove(e.getKey());
                Player p = Bukkit.getPlayer(e.getKey());
                if (p != null) {
                    if (p.getVehicle() == e.getValue()) p.leaveVehicle();
                    fr.influencraft.fluxdungeons.api.Combat.takeControls(p, false);
                    if (camera != null && !Clients.bedrock(p)) {
                        Camera.view(p, null);
                        Sidebar.hide(p, false);
                    }
                }
                e.getValue().remove();
            }
            seats.clear();
            if (camera != null) camera.remove();
            if (then) after.run();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDismount(EntityDismountEvent e) {
        if (!(e.getEntity() instanceof Player p) || !e.getDismounted().getScoreboardTags().contains(SEAT)) return;
        Playing playing = seated.get(p.getUniqueId());
        if (playing == null || playing.over) return;
        e.setCancelled(true);
        playing.skip(p);
    }

    @EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
        release(e.getPlayer());
    }

    /** A player leaves his run: out of its scene (his seat, the camera), its boss's line off his screen. */
    void left(Run run, Player p) {
        release(p);
        State s = states.get(run.id);
        if (s != null && s.speech != null) p.hideBossBar(s.speech);
    }

    private void release(Player p) {
        Playing playing = seated.remove(p.getUniqueId());
        if (playing == null) return;
        ArmorStand seat = playing.seats.remove(p.getUniqueId());
        if (seat != null) seat.remove();
        fr.influencraft.fluxdungeons.api.Combat.takeControls(p, false);
        if (playing.camera != null && p.isOnline() && !Clients.bedrock(p)) {
            Camera.view(p, null);
            Sidebar.hide(p, false);
        }
    }
}

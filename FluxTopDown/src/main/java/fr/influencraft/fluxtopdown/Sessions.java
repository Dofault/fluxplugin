package fr.influencraft.fluxtopdown;

import fr.influencraft.fluxdungeons.api.Combat;
import fr.influencraft.fluxdungeons.api.Dungeons;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every view from above: started by command (at once, or when the player's dungeon run begins), driven each tick
 * (mouse pointer, camera, walking, basic attack, aimed spell zones, bar of spells), stopped on death, world change,
 * quit or command.
 */
final class Sessions implements Listener {

    /** How long "/hackslash play" waits for the run to begin (launch screen included). */
    private static final long WAIT = 20L * 60 * 5;

    private final FluxTopDown plugin;
    private final Kit kit;
    private final Map<UUID, Session> sessions = new HashMap<>();
    /** Players who asked for the view in their next dungeon run, with the tick the wait ends. */
    private final Map<UUID, Long> waiting = new HashMap<>();
    private final Map<UUID, Long> told = new HashMap<>();
    /** Players who see what each click did (testing). */
    private final java.util.Set<UUID> debug = new java.util.HashSet<>();

    boolean toggleDebug(Player p) {
        if (debug.remove(p.getUniqueId())) return false;
        debug.add(p.getUniqueId());
        return true;
    }

    Sessions(FluxTopDown plugin) {
        this.plugin = plugin;
        this.kit = new Kit(plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    boolean active(Player p) {
        return sessions.containsKey(p.getUniqueId());
    }

    /** "/hackslash off": no view now, nor later in the run he waits for. */
    void cancel(Player p) {
        waiting.remove(p.getUniqueId());
        stop(p, true);
    }

    void start(Player p) {
        waiting.remove(p.getUniqueId());
        if (active(p)) return;
        Session s = new Session(plugin, p);
        if (!s.camera.isValid() || !s.avatar.isValid() || !s.cursor.isValid() || !s.target.isValid()) {
            // another plugin refused one of the entities (a spawn rule of the world)
            plugin.getLogger().warning("View of " + p.getName() + " not opened: an entity was refused in " + p.getWorld().getName());
            s.close();
            plugin.messages().send(p, "refused");
            return;
        }
        sessions.put(p.getUniqueId(), s);
        s.open();
        Combat.takeControls(p, true);
        plugin.messages().send(p, "on");
    }

    /** Stops his view; the wait for a dungeon run is kept (entering the run's world must not cancel it). */
    void stop(Player p, boolean tell) {
        Session s = sessions.remove(p.getUniqueId());
        if (s == null) return;
        s.close();
        Combat.takeControls(p, false);
        if (tell && p.isOnline()) plugin.messages().send(p, "off");
    }

    void stopAll() {
        for (Session s : new ArrayList<>(sessions.values())) stop(s.player, false);
    }

    /** Starts his dungeon (FluxDungeons tells him any refusal) and the view once he is in it. */
    void play(Player p, String dungeon) {
        if (Dungeons.start(p, dungeon)) {
            waiting.put(p.getUniqueId(), (long) Bukkit.getCurrentTick() + WAIT);
            plugin.messages().send(p, "waiting");
        }
    }

    // ---------------------------------------------------------------- every tick

    private void tick() {
        long tick = Bukkit.getCurrentTick();
        if (!waiting.isEmpty()) {
            for (UUID id : new ArrayList<>(waiting.keySet())) {
                Player p = Bukkit.getPlayer(id);
                long until = waiting.get(id);
                if (p == null) waiting.remove(id);
                else if (Dungeons.playing(p) && Dungeons.dungeonWorld(p.getWorld()) && p.getGameMode() != GameMode.SPECTATOR) {
                    waiting.remove(id);
                    // a tick after the run placed him, so its own set up (inventory, slot) is done
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (p.isOnline() && Dungeons.playing(p)) start(p);
                    }, 10L);
                } else if (tick > until) {
                    waiting.remove(id);
                    plugin.messages().send(p, "waiting-over");
                }
            }
        }
        for (Session s : new ArrayList<>(sessions.values())) {
            Player p = s.player;
            if (!p.isOnline() || p.isDead() || p.getGameMode() == GameMode.SPECTATOR || !s.avatar.isValid() || !s.camera.isValid() || !s.seat.isValid()) {
                stop(p, p.isOnline());
                continue;
            }
            try {
                tick(s, tick);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("View of " + p.getName() + " stopped: " + e);
                stop(p, true);
            }
        }
    }

    private void tick(Session s, long tick) {
        Player p = s.player;
        Settings set = plugin.settings();
        walk(s, tick);
        fight(s, tick);
        s.moveAvatar(tick);
        s.place();
        if (s.aiming != null && tick % 2 == 0) kit.indicate(s, s.aiming, s.pointed());
        if (s.chase != null && tick % 4 == 0) kit.marked(p, s.chase);
        if (tick % 5 == 0) bar(s, set, tick);
        if (tick % 20 == 0) {
            s.hideOwnGear();
            s.dressAvatar();
            // the camera packet is lost on respawn or world reload; sending it again costs nothing
            fr.influencraft.flux.api.client.Camera.view(p, s.camera);
            if (p.getInventory().getHeldItemSlot() != set.restSlot()) p.getInventory().setHeldItemSlot(set.restSlot());
        }
    }

    /**
     * Walks him one tick: along his path, or through his leap, with the server's own gravity and steps (he sits on
     * his seat, the game's physics do not move him).
     */
    private void walk(Session s, long tick) {
        Player p = s.player;
        s.ride();
        Vector pos = p.getLocation().toVector();
        Paths floors = new Paths(p.getWorld());
        double floor = floors.floorNear(pos.getBlockX(), (int) Math.floor(pos.getY() + 0.01), pos.getBlockZ());
        Vector next = pos.clone();
        if (s.leapVel != null) {
            Vector v = s.leapVel;
            Vector to = pos.clone().add(v);
            double f = floors.floorNear(to.getBlockX(), (int) Math.floor(to.getY() + 0.01), to.getBlockZ());
            // a wall in the way stops the forward part of the jump
            if (Double.isNaN(f) || f > to.getY() + 0.01 && v.getY() > 0) to = pos.clone().add(new Vector(0, v.getY(), 0));
            v.setX(v.getX() * 0.91).setZ(v.getZ() * 0.91).setY((v.getY() - 0.08) * 0.98);
            double under = floors.floorNear(to.getBlockX(), (int) Math.floor(to.getY() + 0.5), to.getBlockZ());
            boolean landed = tick >= s.leapStart + 3 && v.getY() < 0 && !Double.isNaN(under) && to.getY() <= under;
            if (landed || tick >= s.leapUntil) {
                if (!Double.isNaN(under)) to.setY(Math.max(to.getY(), under));
                s.leapVel = null;
                s.leapUntil = 0;
                s.moveFeet(to);
                Runnable land = s.onLand;
                s.onLand = null;
                if (land != null) land.run();
                return;
            }
            s.moveFeet(to);
            return;
        }
        // keys first (forward = up the screen): they take over from a click's path
        org.bukkit.Input in = p.getCurrentInput();
        int side = (in.isRight() ? 1 : 0) - (in.isLeft() ? 1 : 0), ahead = (in.isForward() ? 1 : 0) - (in.isBackward() ? 1 : 0);
        if (tick >= s.busyUntil && (side != 0 || ahead != 0)) {
            s.stop();
            Vector[] ax = Session.axes(plugin.settings());
            Vector dir = ax[0].clone().setY(0).normalize().multiply(ahead).add(ax[1].clone().setY(0).normalize().multiply(side)).normalize();
            Vector moved = step(floors, pos, dir.multiply(plugin.settings().moveSpeed()));
            if (moved != null) {
                next = moved;
                floor = moved.getY();
            }
        } else if (tick >= s.busyUntil && !s.path.isEmpty()) {
            while (s.pathIndex < s.path.size()) {
                Vector w = s.path.get(s.pathIndex);
                double dx = w.getX() - pos.getX(), dz = w.getZ() - pos.getZ();
                if (dx * dx + dz * dz < 0.01) s.pathIndex++;
                else break;
            }
            if (s.pathIndex >= s.path.size()) s.path = List.of();
            else {
                Vector w = s.path.get(s.pathIndex);
                Vector flat = w.clone().subtract(pos).setY(0);
                double len = flat.length();
                Vector moved = step(floors, pos, flat.multiply(Math.min(plugin.settings().moveSpeed(), len) / len));
                // blocked (a door closed, a monster's wall): he stops there
                if (moved == null) s.stop();
                else {
                    next = moved;
                    floor = moved.getY();
                }
            }
        }
        // steps up at once, falls with gravity
        if (!Double.isNaN(floor) && floor >= next.getY() - 0.01) {
            next.setY(floor);
            s.vy = 0;
        } else {
            s.vy = (s.vy - 0.08) * 0.98;
            double y = next.getY() + s.vy;
            if (!Double.isNaN(floor) && y <= floor) {
                y = floor;
                s.vy = 0;
            }
            next.setY(y);
        }
        if (next.distanceSquared(pos) > 1e-6) s.moveFeet(next);
    }

    /**
     * One step of {@code delta} from {@code pos}: the new feet position with the floor height there as y, sliding
     * along a wall on the free axis; null when he cannot move at all. Keeps 0.3 blocks (his half width) from walls.
     */
    private static Vector step(Paths floors, Vector pos, Vector delta) {
        for (Vector d : new Vector[]{delta, new Vector(delta.getX(), 0, 0), new Vector(0, 0, delta.getZ())}) {
            if (d.lengthSquared() < 1e-6) continue;
            Vector to = pos.clone().add(d);
            double f = floorAt(floors, to, pos.getY());
            if (Double.isNaN(f)) continue;
            // his edges, in the direction he goes
            Vector edge = d.clone().normalize().multiply(0.3);
            double fe = floorAt(floors, to.clone().add(edge), pos.getY());
            if (Double.isNaN(fe) || fe - pos.getY() > 0.6 && f - pos.getY() <= 0.6) continue;
            to.setY(f);
            return to;
        }
        return null;
    }

    /** Floor under a point, NaN when it cannot be stood on or is more than a step (1.25) above {@code from}. */
    private static double floorAt(Paths floors, Vector at, double from) {
        double f = floors.floorNear(at.getBlockX(), (int) Math.floor(from + 0.01), at.getBlockZ());
        return Double.isNaN(f) || f - from > 1.25 ? Double.NaN : f;
    }

    /** The basic attack: walks to the chased monster, strikes it at his class's attack speed. */
    private void fight(Session s, long tick) {
        LivingEntity e = s.chase;
        if (e == null) return;
        if (!e.isValid() || e.isDead() || e.getWorld() != s.player.getWorld()) {
            s.chase = null;
            return;
        }
        if (tick < s.busyUntil || s.leapVel != null) return;
        Location at = s.player.getLocation(), there = e.getLocation();
        double dx = there.getX() - at.getX(), dz = there.getZ() - at.getZ();
        double reach = plugin.settings().basicRange() + e.getWidth() / 2;
        if (dx * dx + dz * dz <= reach * reach && Math.abs(there.getY() - at.getY()) < 2.5) {
            if (!s.path.isEmpty()) s.path = List.of();
            if (tick >= s.nextBasic) {
                kit.basic(s, e, tick);
                s.nextBasic = tick + Math.max(4, Math.round(20 * Combat.attackInterval(s.player)));
            }
        } else if (tick >= s.nextRepath) {
            s.nextRepath = tick + 8;
            goTo(s, there);
        }
    }

    private void goTo(Session s, Location to) {
        s.path = new Paths(to.getWorld()).find(s.player.getLocation(), to, plugin.settings().maxPath());
        s.pathIndex = 0;
    }

    private void bar(Session s, Settings set, long tick) {
        var m = plugin.messages();
        String[] slots = new String[8];
        for (int key = 1; key <= 4; key++) {
            Settings.Spell sp = set.spell(key);
            String name = sp == null ? "-" : m.raw("spells." + sp.id());
            long ready = sp == null ? 0 : s.readyAt.getOrDefault(key, 0L);
            String text;
            if (sp != null && s.aiming == sp) text = m.raw("slot-aiming", "%key%", String.valueOf(key), "%name%", name);
            else if (tick < ready) text = m.raw("slot-cooldown", "%key%", String.valueOf(key), "%name%", name,
                    "%time%", String.valueOf((ready - tick + 19) / 20));
            else text = m.raw("slot-ready", "%key%", String.valueOf(key), "%name%", name);
            slots[(key - 1) * 2] = "%s" + key + "%";
            slots[(key - 1) * 2 + 1] = text;
        }
        s.bar.name(m.get("bar", s.player, slots));
    }

    // ---------------------------------------------------------------- input

    /** Tick of each player's last right click: one click may come by several ways (packet, use event, arm swing). */
    private final Map<UUID, Long> lastRight = new HashMap<>();

    /**
     * A click of the player. Right clicks come from the box around his camera (Flux reads them) or, when his client
     * missed the box, from the use event; left clicks from his arm swing, which the client always sends.
     */
    void clicked(Session s, boolean left) {
        if (!sessions.containsValue(s)) return;
        long tick = Bukkit.getCurrentTick();
        Long right = lastRight.get(s.player.getUniqueId());
        if (left && right != null && tick - right <= 1) return;     // the swing of a right click
        if (!left) {
            if (right != null && right == tick) return;
            lastRight.put(s.player.getUniqueId(), tick);
        }
        if (debug.contains(s.player.getUniqueId())) {
            plugin.messages().send(s.player, "debug-received", "%button%", plugin.messages().raw(left ? "debug-left" : "debug-right"));
        }
        Location aim = s.pointed();
        if (s.aiming != null) {
            if (left) {
                cast(s, s.aiming, aim, tick);
                return;
            }
            s.aiming = null;
        }
        if (tick < s.busyUntil || s.leapVel != null) return;
        LivingEntity enemy = Kit.enemyAt(aim, 1.6);
        if (enemy != null) {
            if (s.chase != enemy) s.nextRepath = 0;
            s.chase = enemy;
            return;
        }
        s.chase = null;
        goTo(s, aim);
        kit.marker(s.player, aim);
        if (debug.contains(s.player.getUniqueId())) {
            plugin.messages().send(s.player, "debug-click", "%button%", plugin.messages().raw(left ? "debug-left" : "debug-right"),
                    "%x%", String.format(java.util.Locale.ROOT, "%.1f", aim.getX()), "%y%", String.format(java.util.Locale.ROOT, "%.1f", aim.getY()),
                    "%z%", String.format(java.util.Locale.ROOT, "%.1f", aim.getZ()), "%points%", String.valueOf(s.path.size()),
                    "%ground%", plugin.messages().raw(s.player.isOnGround() ? "debug-yes" : "debug-no"));
        }
    }

    private void cast(Session s, Settings.Spell spell, Location aim, long tick) {
        if (tick < s.busyUntil || s.leapVel != null) return;
        kit.cast(s, spell, aim, tick);
    }

    /** Keys 1 to 4: aim a spell (a second press casts it), or cast it at once when it needs no aim. */
    private void key(Session s, int key) {
        Settings.Spell spell = plugin.settings().spell(key);
        if (spell == null) return;
        long tick = Bukkit.getCurrentTick();
        long ready = s.readyAt.getOrDefault(key, 0L);
        if (tick < ready) {
            Long last = told.get(s.player.getUniqueId());
            if (last == null || tick - last > 15) {
                told.put(s.player.getUniqueId(), tick);
                plugin.messages().send(s.player, "cooldown", "%spell%", plugin.messages().raw("spells." + spell.id()),
                        "%time%", String.valueOf((ready - tick + 19) / 20));
            }
            return;
        }
        if (spell.instant() || s.aiming == spell) cast(s, spell, s.pointed(), tick);
        else s.aiming = spell;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHeld(PlayerItemHeldEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null) return;
        e.setCancelled(true);
        // keys 1 to 4 are slots 0 to 3; the wheel only reaches the slots around the rest slot
        if (e.getNewSlot() <= 3) key(s, e.getNewSlot() + 1);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null) return;
        e.setCancelled(true);
        s.aiming = null;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent e) {
        if (active(e.getPlayer())) e.setCancelled(true);
    }

    /** His own uses do nothing in the world; a right click the box missed still counts. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null) return;
        e.setCancelled(true);
        if (e.getHand() == org.bukkit.inventory.EquipmentSlot.HAND
                && (e.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_AIR || e.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK)) {
            clicked(s, false);
        }
    }

    /** A left click: the client sends the arm swing whatever it hit. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwing(io.papermc.paper.event.player.PlayerArmSwingEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null || e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        e.setCancelled(true);
        // a tick later: the right click this swing may belong to reaches us from the network a tick late
        Bukkit.getScheduler().runTask(plugin, () -> clicked(s, true));
    }

    /** Sneaking does not get him off his seat (a teleport by another plugin does: he is seated again next tick). */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDismount(org.bukkit.event.entity.EntityDismountEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Session s = sessions.get(p.getUniqueId());
        if (s == null || s.closing || e.getDismounted() != s.seat) return;
        if (p.getCurrentInput().isSneak()) e.setCancelled(true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        stop(e.getPlayer(), true);
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        stop(e.getPlayer(), true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        waiting.remove(e.getPlayer().getUniqueId());
        lastRight.remove(e.getPlayer().getUniqueId());
        stop(e.getPlayer(), false);
    }

    /** Someone joining does not see the hidden bodies of players in the view (their avatars stand for them). */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        for (Session s : sessions.values()) e.getPlayer().hidePlayer(plugin, s.player);
    }
}

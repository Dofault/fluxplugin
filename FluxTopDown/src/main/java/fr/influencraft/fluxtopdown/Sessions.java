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
            if (!p.isOnline() || p.isDead() || p.getGameMode() == GameMode.SPECTATOR || !s.avatar.isValid() || !s.camera.isValid()) {
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
        s.readMouse();
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

    /** Pushes him along his path by velocity, the game's physics doing the rest (walls, steps, falls). */
    private void walk(Session s, long tick) {
        Player p = s.player;
        if (s.leapUntil > 0) {
            if (tick >= s.leapStart + 3 && p.isOnGround() || tick >= s.leapUntil) {
                s.leapUntil = 0;
                Runnable land = s.onLand;
                s.onLand = null;
                if (land != null) land.run();
            }
            return;
        }
        if (tick < s.busyUntil || s.path.isEmpty()) return;
        Location at = p.getLocation();
        Vector pos = at.toVector();
        boolean ground = p.isOnGround();
        while (s.pathIndex < s.path.size()) {
            Vector w = s.path.get(s.pathIndex);
            double dx = w.getX() - pos.getX(), dz = w.getZ() - pos.getZ();
            boolean last = s.pathIndex == s.path.size() - 1;
            if (dx * dx + dz * dz < (last ? 0.04 : 0.16) && Math.abs(w.getY() - pos.getY()) < 0.7) s.pathIndex++;
            else break;
        }
        if (s.pathIndex >= s.path.size()) {
            s.path = List.of();
            if (ground) p.setVelocity(new Vector(0, 0, 0));
            return;
        }
        if (!ground) return;
        Vector w = s.path.get(s.pathIndex);
        Vector flat = w.clone().subtract(pos).setY(0);
        double len = flat.length();
        double speed = Math.min(plugin.settings().moveSpeed(), s.pathIndex == s.path.size() - 1 ? len : 99);
        Vector v = len < 1e-4 ? new Vector() : flat.multiply(speed / len);
        double rise = w.getY() - pos.getY();
        int stuck = s.stuck(pos, true);
        if (rise > 0.6 && len < 1.8 || stuck == 8) v.setY(0.42);
        if (stuck > 30) {
            s.stop();
            return;
        }
        p.setVelocity(v);
    }

    /** The basic attack: walks to the chased monster, strikes it at his class's attack speed. */
    private void fight(Session s, long tick) {
        LivingEntity e = s.chase;
        if (e == null) return;
        if (!e.isValid() || e.isDead() || e.getWorld() != s.player.getWorld()) {
            s.chase = null;
            return;
        }
        if (tick < s.busyUntil || s.leapUntil > 0) return;
        Location at = s.player.getLocation(), there = e.getLocation();
        double dx = there.getX() - at.getX(), dz = there.getZ() - at.getZ();
        double reach = plugin.settings().basicRange() + e.getWidth() / 2;
        if (dx * dx + dz * dz <= reach * reach && Math.abs(there.getY() - at.getY()) < 2.5) {
            if (!s.path.isEmpty()) {
                s.path = List.of();
                if (s.player.isOnGround()) s.player.setVelocity(new Vector());
            }
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
        s.stuck(s.player.getLocation().toVector(), false);
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

    /** A click of the player (on the box around his camera). */
    void clicked(Session s, boolean left) {
        if (!sessions.containsValue(s)) return;
        long tick = Bukkit.getCurrentTick();
        Location aim = s.pointed();
        if (s.aiming != null) {
            if (left) {
                cast(s, s.aiming, aim, tick);
                return;
            }
            s.aiming = null;
        }
        if (tick < s.busyUntil || s.leapUntil > 0) return;
        LivingEntity enemy = Kit.enemyAt(aim, 1.6);
        if (enemy != null) {
            if (s.chase != enemy) s.nextRepath = 0;
            s.chase = enemy;
            return;
        }
        s.chase = null;
        goTo(s, aim);
        kit.marker(s.player, aim);
    }

    private void cast(Session s, Settings.Spell spell, Location aim, long tick) {
        if (tick < s.busyUntil || s.leapUntil > 0) return;
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

    /** His own swings and uses (the server's view of his clicks) do nothing: the box around the camera has them. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent e) {
        if (active(e.getPlayer())) e.setCancelled(true);
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
        stop(e.getPlayer(), false);
    }

    /** Someone joining does not see the hidden bodies of players in the view (their avatars stand for them). */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        for (Session s : sessions.values()) e.getPlayer().hidePlayer(plugin, s.player);
    }
}

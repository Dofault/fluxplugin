package fr.influencraft.fluxdungeons.rpg;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.text.Texts;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Characters of the rpg lobby drawn by a BetterModel model (2026-11-16: the Oracle, who runs the dungeon shop):
 * {@code rpg.figures} of the config. Each one is a model shown to every player in the lobby world (no entity under
 * it: nothing to push, kill or save), a click box ({@link Interaction}) running its command as the player, and its
 * name above it. The click box and the name are not saved with the world (made again at start and when their chunk
 * loads).
 * <p>
 * Why not Typewriter, where the lobby's other characters live: Typewriter draws its characters itself and cannot show
 * a BetterModel model; that would need our own Typewriter extension, worth writing once several model characters
 * exist (the welcome robot of FluxMascot is the other one).
 */
public final class LobbyFigures implements Listener {

    record Figure(String id, String model, Location at, float scale, float yaw, String name, String command,
                  double width, double height, double nameHeight) {
    }

    private static final String TAG = "fluxdungeons_figure";
    private final FluxPlugin plugin;
    private final World world;
    private final List<Figure> figures = new ArrayList<>();
    private final Map<UUID, List<AutoCloseable>> shown = new HashMap<>();
    private final Map<UUID, Figure> boxes = new HashMap<>();
    private final boolean models;

    public LobbyFigures(FluxPlugin plugin, World world) {
        this.plugin = plugin;
        this.world = world;
        this.models = Bukkit.getPluginManager().isPluginEnabled("BetterModel");
    }

    public void reload(ConfigurationSection c) {
        clear();
        figures.clear();
        if (c == null || world == null) return;
        for (String id : c.getKeys(false)) {
            ConfigurationSection f = c.getConfigurationSection(id);
            if (f == null) continue;
            float yaw = (float) f.getDouble("yaw", 0);
            Location at = new Location(world, f.getDouble("x"), f.getDouble("y"), f.getDouble("z"), yaw, 0);
            figures.add(new Figure(id, f.getString("model", id), at, (float) f.getDouble("scale", 1), yaw,
                    f.getString("name", ""), f.getString("command", ""), f.getDouble("width", 1.2),
                    f.getDouble("height", 2.4), f.getDouble("name-height", 2.6)));
        }
        // a tick later: the world's chunks and BetterModel's models are ready
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Figure f : figures) place(f);
            for (Player p : world.getPlayers()) show(p);
        });
    }

    /** The click box and the name of a figure (old ones of ours around it removed first). */
    private void place(Figure f) {
        if (!f.at().isChunkLoaded()) return;
        for (Entity e : world.getNearbyEntities(f.at(), 2, 4, 2)) {
            if (e.getScoreboardTags().contains(TAG + "_" + f.id())) {
                boxes.remove(e.getUniqueId());
                e.remove();
            }
        }
        Interaction box = world.spawn(f.at(), Interaction.class, i -> {
            i.setInteractionWidth((float) f.width());
            i.setInteractionHeight((float) f.height());
            i.setResponsive(true);
            i.setPersistent(false);
            i.addScoreboardTag(TAG);
            i.addScoreboardTag(TAG + "_" + f.id());
        });
        boxes.put(box.getUniqueId(), f);
        if (!f.name().isEmpty()) {
            world.spawn(f.at().clone().add(0, f.nameHeight(), 0), TextDisplay.class, t -> {
                t.text(Texts.parse(f.name()));
                t.setBillboard(Display.Billboard.CENTER);
                t.setPersistent(false);
                t.addScoreboardTag(TAG);
                t.addScoreboardTag(TAG + "_" + f.id());
            });
        }
    }

    private void show(Player p) {
        hide(p);
        if (!models || p.getWorld() != world) return;
        List<AutoCloseable> list = new ArrayList<>();
        for (Figure f : figures) {
            AutoCloseable h = fr.influencraft.fluxdungeons.mob.ModelBridge.figure(p, f.model(), f.at(), f.scale(), f.yaw());
            if (h != null) list.add(h);
            else plugin.getLogger().warning("Lobby figure " + f.id() + ": no BetterModel model " + f.model());
        }
        shown.put(p.getUniqueId(), list);
    }

    private void hide(Player p) {
        List<AutoCloseable> list = shown.remove(p.getUniqueId());
        if (list != null) for (AutoCloseable h : list) {
            try {
                h.close();
            } catch (Exception ignored) {
            }
        }
    }

    public void clear() {
        for (Player p : Bukkit.getOnlinePlayers()) hide(p);
        if (world != null) for (Entity e : world.getEntities()) if (e.getScoreboardTags().contains(TAG)) e.remove();
        boxes.clear();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) show(p);
        }, 20L);
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        if (e.getFrom() == world) hide(p);
        if (p.getWorld() == world) Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && p.getWorld() == world) show(p);
        }, 10L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        hide(e.getPlayer());
    }

    @EventHandler
    public void onChunk(ChunkLoadEvent e) {
        if (e.getWorld() != world) return;
        for (Figure f : figures) {
            if (f.at().getBlockX() >> 4 == e.getChunk().getX() && f.at().getBlockZ() >> 4 == e.getChunk().getZ()) {
                Bukkit.getScheduler().runTask(plugin, () -> place(f));
            }
        }
    }

    @EventHandler
    public void onClick(PlayerInteractEntityEvent e) {
        Figure f = boxes.get(e.getRightClicked().getUniqueId());
        if (f == null) return;
        e.setCancelled(true);
        if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND || f.command().isEmpty()) return;
        e.getPlayer().performCommand(f.command());
    }
}

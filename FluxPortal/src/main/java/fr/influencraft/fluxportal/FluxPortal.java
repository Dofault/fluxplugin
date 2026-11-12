package fr.influencraft.fluxportal;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.command.Commands;
import fr.influencraft.fluxportal.api.Portal;
import fr.influencraft.fluxportal.api.PortalEnd;
import fr.influencraft.fluxportal.api.Portals;
import fr.influencraft.fluxportal.window.ShaderPack;
import fr.influencraft.fluxportal.window.Windows;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Permanent portals (docs/technique/fluxportal.md): a rectangular hole on each side, anywhere, in any worlds. Each
 * side shows the other one in depth, block by block (the window: {@link Windows}, a text shader of the server's pack),
 * and walking into either one leads out of the other ({@link Crossings}); a flowing border, motes and sounds make it
 * feel like a portal ({@link PortalLook}). Bedrock players get nether portal blocks
 * instead of the window ({@link BedrockView}). Saved in Flux's database, open to other plugins through
 * {@link Portals}, placed with {@code /portail} ({@link PortalCommand}).
 */
public final class FluxPortal extends FluxPlugin {

    private PortalStore store;
    private final Map<String, Portal> portals = new ConcurrentHashMap<>();
    private Windows windows;
    private BedrockView bedrock;
    private PortalLook look;
    private int width, height;

    @Override
    protected void enable() {
        saveDefaultConfig();
        preloadClasses();
        width = getConfig().getInt("width", 5);
        height = Math.max(2, Math.min(9, getConfig().getInt("height", 4)));
        if (width % 2 == 0 || width < 1 || width > 19) {
            getLogger().warning("width " + width + ": a hole is an odd number of blocks wide (1 to 19), 5 is used");
            width = 5;
        }
        try {
            store = new PortalStore(getLogger());
            for (Portal p : store.load()) portals.put(p.name(), p);
        } catch (SQLException e) {
            getLogger().severe("Portals unreadable, FluxPortal is off: " + e.getMessage());
            return;
        }
        windows = new Windows(this, portals::values);
        writeShader();
        getServer().getPluginManager().registerEvents(windows, this);
        // once every world is up
        Bukkit.getScheduler().runTask(this, () -> windows.start(section("window")));
        look = new PortalLook(this, portals::values);
        look.start();
        getServer().getPluginManager().registerEvents(new Crossings(this, portals::values, look::travelled), this);
        bedrock = new BedrockView(this, portals::values);
        bedrock.start();
        getServer().getPluginManager().registerEvents(bedrock, this);
        Portals.register(new Portals.Service() {
            public Portal set(String name, int side, PortalEnd end) {
                return put(name, side, end);
            }

            public boolean open(String name, boolean open) {
                return setOpen(name, open);
            }

            public boolean remove(String name) {
                return FluxPortal.this.remove(name);
            }

            public Portal get(String name) {
                return portal(name);
            }

            public Collection<Portal> all() {
                return portals();
            }
        });
        PortalCommand command = new PortalCommand(this);
        Commands.create(this, "portail").description("Portals: create, link, move, open, close, remove, list.")
                .permission("fluxportal.admin").usage("/portail aide")
                .executor(command::run).completer((sender, args) -> command.complete(args)).register();
        getLogger().info(portals.size() + " portal(s), holes of " + width + "x" + height);
    }

    @Override
    protected void disable() {
        Portals.register(null);
        if (windows != null) windows.stop();
        if (look != null) look.stop();
        if (bedrock != null) bedrock.reset();
    }

    private ConfigurationSection section(String key) {
        ConfigurationSection s = getConfig().getConfigurationSection(key);
        return s == null ? new YamlConfiguration() : s;
    }

    /** The window's shader, handed to Flux which writes the pack's text shader (Flux's config, client-pack). */
    private void writeShader() {
        try {
            ShaderPack.register(this, windows.colours());
        } catch (IOException | RuntimeException e) {
            getLogger().severe("Window shader not given to Flux: " + e.getMessage());
        }
    }

    /**
     * A jar replaced while the server runs cannot load a class it never loaded: every class is loaded at start (see
     * plugins/fluxplugin/CLAUDE.md, known pitfalls).
     */
    private void preloadClasses() {
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(getFile())) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.startsWith("fr/influencraft/fluxportal/") || !name.endsWith(".class")) continue;
                try {
                    Class.forName(name.substring(0, name.length() - 6).replace('/', '.'), false, getClassLoader());
                } catch (ClassNotFoundException | LinkageError ignored) {
                    // a class that cannot load now would not load later either
                }
            }
        } catch (IOException e) {
            getLogger().warning("Classes not preloaded: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ portals

    static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    static boolean validName(String name) {
        return name.matches("[A-Za-z0-9_-]{1,32}");
    }

    Portal portal(String name) {
        return portals.get(key(name));
    }

    List<Portal> portals() {
        List<Portal> out = new ArrayList<>(portals.values());
        out.sort(Comparator.comparing(Portal::name));
        return out;
    }

    /** Side {@code side} standing on the floor block (x, y, z), its front facing {@code facing}. */
    Portal place(String name, int side, String world, int x, int y, int z, int facing) {
        Portal old = portal(name);
        int h = old != null ? old.height() : height;
        return put(name, side, PortalEnd.standing(world, x, y, z, facing, h));
    }

    private Portal put(String name, int side, PortalEnd end) {
        if (!validName(name) || side < 1 || side > 2) return null;
        String k = key(name);
        Portal old = portals.get(k);
        // a side of the API may not sit on the block grid: snapped as /portail would place it
        Portal p = (old != null ? old : new Portal(k, null, null, width, height, true)).withSide(side, snap(end));
        if (!store.save(p)) return null;
        changed(old, p);
        return p;
    }

    private static PortalEnd snap(PortalEnd e) {
        int f = e.facing();
        int[] n = PortalEnd.DIRECTIONS[f];
        double x = n[0] != 0 ? Math.round(e.x()) : Math.floor(e.x()) + 0.5;
        double z = n[1] != 0 ? Math.round(e.z()) : Math.floor(e.z()) + 0.5;
        return new PortalEnd(e.world(), x, Math.round(e.y() * 2) / 2.0, z, f * 90f);
    }

    boolean setOpen(String name, boolean open) {
        Portal old = portal(name);
        if (old == null) return false;
        Portal p = old.withOpen(open);
        if (!store.save(p)) return false;
        changed(old, p);
        return true;
    }

    boolean remove(String name) {
        Portal p = portals.remove(key(name));
        if (p == null) return false;
        store.delete(p.name());
        windows.refresh(p);
        bedrock.reset();
        return true;
    }

    private void changed(Portal old, Portal p) {
        portals.put(p.name(), p);
        // the frames hang at the old place: taken down, hung again at the next pass
        if (old != null && !sameSides(old, p)) windows.refresh(old);
        bedrock.reset();
    }

    private static boolean sameSides(Portal a, Portal b) {
        return java.util.Objects.equals(a.side1(), b.side1()) && java.util.Objects.equals(a.side2(), b.side2());
    }

    /** Blocks of a side's hole a player cannot walk through (the portal cannot be crossed there). */
    int blockedCells(Portal p, int side) {
        PortalEnd e = p.side(side);
        World w = e == null ? null : Bukkit.getWorld(e.world());
        if (w == null) return 0;
        Frame f = new Frame(e, p.width(), p.height());
        int hw = (p.width() - 1) / 2, blocked = 0;
        for (int i = -hw; i <= hw; i++) {
            for (int j = 0; j < p.height(); j++) {
                if (!w.getBlockAt((int) Math.floor(f.worldX(i, -0.5)), f.bottomY() + j, (int) Math.floor(f.worldZ(i, -0.5))).isPassable()) blocked++;
            }
        }
        return blocked;
    }
}

package fr.influencraft.fluxdungeons;

import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldUnloadEvent;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

/**
 * Admin diagnostics for memory: worlds unloaded but still held in memory (a plugin keeping a reference), and a heap
 * dump readable by the server group (spark writes it for the server account only). Console only, in English.
 */
final class Diagnostics implements Listener {

    private record Unloaded(String name, long at, WeakReference<World> ref) {
    }

    private final FluxDungeons plugin;
    private final List<Unloaded> unloaded = new ArrayList<>();

    Diagnostics(FluxDungeons plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onUnload(WorldUnloadEvent e) {
        unloaded.add(new Unloaded(e.getWorld().getName(), System.currentTimeMillis(), new WeakReference<>(e.getWorld())));
    }

    /** Lists unloaded worlds still in memory after a garbage collection. */
    void leaks(CommandSender sender) {
        System.gc();
        int held = 0;
        for (Unloaded u : unloaded) {
            if (u.ref().get() == null) continue;
            held++;
            sender.sendMessage("held: " + u.name() + " (unloaded " + (System.currentTimeMillis() - u.at()) / 1000 + " s ago)");
        }
        unloaded.removeIf(u -> u.ref().get() == null);
        Runtime r = Runtime.getRuntime();
        sender.sendMessage("unloaded worlds still in memory: " + held + ", heap used " + (r.totalMemory() - r.freeMemory()) / 1048576
                + " MB of " + r.maxMemory() / 1048576 + " MB");
    }

    /** Writes a heap dump in the plugin folder, readable by the group. */
    void heapDump(CommandSender sender) {
        File f = new File(plugin.getDataFolder(), "heap-" + System.currentTimeMillis() + ".hprof");
        try {
            var bean = ManagementFactory.newPlatformMXBeanProxy(ManagementFactory.getPlatformMBeanServer(),
                    "com.sun.management:type=HotSpotDiagnostic", com.sun.management.HotSpotDiagnosticMXBean.class);
            bean.dumpHeap(f.getAbsolutePath(), true);
            Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-rw-r--"));
            sender.sendMessage("heap dump written: " + f.getAbsolutePath());
        } catch (Exception e) {
            sender.sendMessage("heap dump failed: " + e);
        }
    }
}

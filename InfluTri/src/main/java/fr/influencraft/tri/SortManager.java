package fr.influencraft.tri;

import me.angeschossen.lands.api.land.Area;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.Lidded;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tri pile par pile.
 *
 * Sécurité : la portée est la zone Lands (Area) où se trouve le coffre de tri,
 * c'est-à-dire sa parcelle, ou la zone principale de la ville hors parcelles.
 * Pouvoir ouvrir le coffre de tri demande l'accès conteneur de cette zone, qui est
 * le même droit que pour ouvrir n'importe quel coffre de la zone : le tri ne donne
 * donc jamais accès à un coffre qu'on ne pourrait pas remplir à la main.
 * Tout est revérifié avant chaque envoi (zone du coffre de tri et de chaque cible),
 * pour suivre les revendications, abandons de chunks et parcelles modifiées en cours de tri.
 *
 * Aucune perte ni duplication : la pile passe du coffre de tri au coffre cible au
 * moment de l'envoi, en une seule opération synchrone. L'objet qui vole n'est qu'un
 * affichage non persistant.
 */
public final class SortManager {

    private final InfluTri plugin;
    private final Map<Block, Job> jobs = new HashMap<>();
    /** Nombre de vols en cours vers chaque coffre cible, pour refermer son couvercle au bon moment. */
    private final Map<Block, Integer> openTargets = new HashMap<>();
    private final Set<ItemDisplay> flying = new HashSet<>();

    public SortManager(InfluTri plugin) {
        this.plugin = plugin;
    }

    // ---------------------------------------------------------------- cycle de vie

    public void start(Block sorter, Player player) {
        if (jobs.containsKey(sorter)) {
            // Tri déjà en cours : il reprendra aussi ce qui vient d'être déposé.
            return;
        }
        if (!plugin.sorterItem().isSorterBlock(sorter)) return;
        Inventory inv = sorterInventory(sorter);
        if (inv == null || inv.isEmpty()) return;
        if (plugin.lands().getArea(sorter.getLocation()) == null) {
            player.sendMessage(plugin.message("outside-land"));
            return;
        }
        Job job = new Job(sorter);
        jobs.put(sorter, job);
        setLid(sorter, true);
        if (plugin.sorterGolem().enabled()) job.golem = plugin.sorterGolem().spawn(sorter);
        long period = plugin.levels().period(plugin.sorterItem().levelOf(sorter));
        // Avec golem, on le laisse sortir du coffre avant le premier envoi.
        long delay = job.golem != null ? 14L : 1L;
        job.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> step(job), delay, period);
    }

    public void stop(Block sorter) {
        Job job = jobs.remove(sorter);
        if (job == null) return;
        job.task.cancel();
        if (job.golem != null && job.golem.isValid()) {
            if (plugin.isEnabled()) {
                // Le couvercle se referme une fois le golem rentré.
                plugin.sorterGolem().despawn(job.golem);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (!jobs.containsKey(sorter) && sorter.getType() == SorterItem.MATERIAL) setLid(sorter, false);
                }, 14L);
                return;
            }
            job.golem.remove();
        }
        if (sorter.getType() == SorterItem.MATERIAL) setLid(sorter, false);
    }

    public void stopAll() {
        for (Block sorter : new ArrayList<>(jobs.keySet())) stop(sorter);
        for (ItemDisplay display : flying) display.remove();
        flying.clear();
        for (Block target : openTargets.keySet()) setLid(target, false);
        openTargets.clear();
    }

    // ---------------------------------------------------------------- un envoi

    private void step(Job job) {
        Block sorter = job.sorter;
        if (!sorter.getWorld().isChunkLoaded(sorter.getX() >> 4, sorter.getZ() >> 4)
                || !plugin.sorterItem().isSorterBlock(sorter)) {
            stop(sorter);
            return;
        }
        Area zone = plugin.lands().getArea(sorter.getLocation());
        Inventory source = sorterInventory(sorter);
        if (zone == null || source == null) {
            stop(sorter);
            return;
        }

        List<Target> targets = findTargets(sorter, zone);
        if (targets.isEmpty()) {
            stop(sorter);
            return;
        }

        ItemStack[] contents = source.getStorageContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.isEmpty()) continue;
            String key = matchKey(stack);
            for (Target target : targets) {
                if (!target.keys.contains(key) || !hasRoom(target.inventory, stack)) continue;
                send(job, source, slot, stack, target);
                return;
            }
        }
        // Plus rien ne peut partir.
        stop(sorter);
    }

    private void send(Job job, Inventory source, int slot, ItemStack stack, Target target) {
        Hooks.logContainer("#influtri", job.sorter.getLocation());
        Hooks.logContainer("#influtri", target.block.getLocation());

        ItemStack moving = stack.clone();
        Map<Integer, ItemStack> leftover = target.inventory.addItem(moving);
        int notMoved = leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
        int moved = stack.getAmount() - notMoved;
        if (moved <= 0) return;

        if (notMoved <= 0) {
            source.setItem(slot, null);
        } else {
            ItemStack rest = stack.clone();
            rest.setAmount(notMoved);
            source.setItem(slot, rest);
        }

        ItemStack shown = stack.clone();
        shown.setAmount(1);
        animate(job, target, shown);
    }

    // ---------------------------------------------------------------- cibles

    private List<Target> findTargets(Block sorter, Area zone) {
        World world = sorter.getWorld();
        int radius = plugin.levels().radius(plugin.sorterItem().levelOf(sorter));
        double radiusSq = (double) radius * radius;
        Location origin = sorter.getLocation().add(0.5, 0.5, 0.5);
        int cx = sorter.getX() >> 4, cz = sorter.getZ() >> 4, cr = (radius >> 4) + 1;
        Set<Block> seen = new HashSet<>();
        List<Target> targets = new ArrayList<>();

        for (int x = cx - cr; x <= cx + cr; x++) {
            for (int z = cz - cr; z <= cz + cr; z++) {
                // Jamais de chargement de chunk : les coffres des chunks déchargés sont ignorés.
                if (!world.isChunkLoaded(x, z)) continue;
                Chunk chunk = world.getChunkAt(x, z);
                for (BlockState state : chunk.getTileEntities(b -> SorterListener.isCopperChest(b.getType()), false)) {
                    Block block = state.getBlock();
                    if (block.equals(sorter) || !(state instanceof Chest chest)) continue;
                    if (plugin.sorterItem().hasMark(state)) continue;
                    Location center = block.getLocation().add(0.5, 0.5, 0.5);
                    double distSq = center.distanceSquared(origin);
                    if (distSq > radiusSq) continue;

                    // Double coffre : traité une seule fois, via sa première moitié rencontrée.
                    if (!seen.add(block)) continue;
                    List<Block> halves = halves(chest);
                    seen.addAll(halves);
                    Inventory inventory = chest.getInventory();

                    // Toutes les moitiés du coffre doivent être dans la même zone que le coffre de tri.
                    boolean ok = true;
                    for (Block half : halves) {
                        if (!sameZone(zone, plugin.lands().getArea(half.getLocation()))) { ok = false; break; }
                        if (plugin.sorterItem().hasMark(half.getState(false))) { ok = false; break; }
                        if (Hooks.isLocked(half)) { ok = false; break; }
                    }
                    if (!ok) continue;

                    Set<String> keys = new HashSet<>();
                    for (ItemStack content : inventory.getStorageContents()) {
                        if (content != null && !content.isEmpty()) keys.add(matchKey(content));
                    }
                    if (keys.isEmpty()) continue;

                    double best = distSq;
                    Location visual = center;
                    if (halves.size() == 2) {
                        visual = halves.get(0).getLocation().add(halves.get(1).getLocation()).multiply(0.5).add(0.5, 0.5, 0.5);
                        best = Math.min(distSq, visual.distanceSquared(origin));
                    }
                    targets.add(new Target(halves.get(0), halves, inventory, keys, best, visual));
                }
            }
        }
        targets.sort(Comparator.comparingDouble(t -> t.distanceSq));
        return targets;
    }

    private static List<Block> halves(Chest chest) {
        InventoryHolder holder = chest.getInventory().getHolder(false);
        if (holder instanceof DoubleChest dc
                && dc.getLeftSide(false) instanceof Chest left
                && dc.getRightSide(false) instanceof Chest right) {
            return List.of(left.getBlock(), right.getBlock());
        }
        return List.of(chest.getBlock());
    }

    private static boolean sameZone(Area zone, Area other) {
        return other != null && zone.getULID().equals(other.getULID());
    }

    private static boolean hasRoom(Inventory inventory, ItemStack stack) {
        for (ItemStack content : inventory.getStorageContents()) {
            if (content == null || content.isEmpty()) return true;
            if (content.isSimilar(stack) && content.getAmount() < content.getMaxStackSize()) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- correspondance

    private Method iaByItemStack;
    private Method iaGetId;
    private boolean iaResolved;

    /** Même type d'objet : matériau, ou id ItemsAdder pour un objet custom, ou marque coffre de tri. */
    private String matchKey(ItemStack stack) {
        if (plugin.sorterItem().isSorterItem(stack)) return "influtri:coffre_de_tri";
        String ia = itemsAdderId(stack);
        if (ia != null) return "ia:" + ia;
        return stack.getType().getKey().toString();
    }

    private String itemsAdderId(ItemStack stack) {
        if (!iaResolved) {
            iaResolved = true;
            if (Hooks.isEnabled("ItemsAdder")) {
                try {
                    Class<?> cls = Class.forName("dev.lone.itemsadder.api.CustomStack");
                    iaByItemStack = cls.getMethod("byItemStack", ItemStack.class);
                    iaGetId = cls.getMethod("getNamespacedID");
                } catch (ReflectiveOperationException e) {
                    plugin.getLogger().warning("ItemsAdder API not found, comparing by material only.");
                }
            }
        }
        if (iaByItemStack == null) return null;
        try {
            Object custom = iaByItemStack.invoke(null, stack);
            return custom == null ? null : (String) iaGetId.invoke(custom);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- visuel

    private void animate(Job job, Target target, ItemStack shown) {
        if (job.golem != null && job.golem.isValid()) {
            // Le golem prend la pile dans le coffre, se tourne vers la cible puis la lance.
            plugin.sorterGolem().grab(job.golem, target.visual, shown);
            Bukkit.getScheduler().runTaskLater(plugin,
                    () -> fly(job.sorter, plugin.sorterGolem().hand(job.golem, job.sorter), target, shown),
                    SorterGolem.THROW_DELAY);
        } else {
            fly(job.sorter, job.sorter.getLocation().add(0.5, 0.9, 0.5), target, shown);
        }
    }

    private void fly(Block sorter, Location start, Target target, ItemStack shown) {
        World world = sorter.getWorld();
        Location end = target.visual.clone().add(0, 0.4, 0);
        double distance = start.distance(end);
        int ticks = (int) Math.round(Math.max(plugin.conf().getInt("flight.ticks-min", 10),
                Math.min(plugin.conf().getInt("flight.ticks-max", 40),
                        plugin.conf().getDouble("flight.ticks-per-block", 0.6) * distance)));
        double apex = 1.0 + distance * 0.15;

        for (Block half : target.halves) openTarget(half);
        world.playSound(start, Sound.BLOCK_COPPER_CHEST_OPEN, 0.4f, 1.4f);

        ItemDisplay display = world.spawn(start, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setItemStack(shown);
            d.setBillboard(Display.Billboard.CENTER);
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                    new Vector3f(0.45f, 0.45f, 0.45f), new AxisAngle4f()));
            d.setTeleportDuration(2);
        });
        flying.add(display);

        final int[] tick = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            tick[0] += 2;
            if (!display.isValid()) {
                task.cancel();
                finishFlight(display, target);
                return;
            }
            if (tick[0] >= ticks) {
                task.cancel();
                world.playSound(end, Sound.ENTITY_ITEM_PICKUP, 0.35f, 1.2f);
                finishFlight(display, target);
                return;
            }
            double t = (double) tick[0] / ticks;
            Location pos = start.clone().multiply(1 - t).add(end.clone().multiply(t));
            pos.add(0, apex * 4 * t * (1 - t), 0);
            display.teleport(pos);
        }, 2L, 2L);
    }

    private void finishFlight(ItemDisplay display, Target target) {
        display.remove();
        flying.remove(display);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Block half : target.halves) closeTarget(half);
        }, 8L);
    }

    private void openTarget(Block block) {
        int count = openTargets.merge(block, 1, Integer::sum);
        if (count == 1) setLid(block, true);
    }

    private void closeTarget(Block block) {
        Integer count = openTargets.get(block);
        if (count == null) return;
        if (count <= 1) {
            openTargets.remove(block);
            setLid(block, false);
        } else {
            openTargets.put(block, count - 1);
        }
    }

    private static void setLid(Block block, boolean open) {
        if (!SorterListener.isCopperChest(block.getType())) return;
        if (block.getState(false) instanceof Lidded lidded) {
            if (open) lidded.open();
            else lidded.close();
        }
    }

    // ---------------------------------------------------------------- utilitaires

    private static Inventory sorterInventory(Block block) {
        return block.getState(false) instanceof Chest chest ? chest.getBlockInventory() : null;
    }

    private static final class Job {
        final Block sorter;
        BukkitTask task;
        org.bukkit.entity.CopperGolem golem;

        Job(Block sorter) {
            this.sorter = sorter;
        }
    }

    private record Target(Block block, List<Block> halves, Inventory inventory, Set<String> keys,
                          double distanceSq, Location visual) {
    }
}

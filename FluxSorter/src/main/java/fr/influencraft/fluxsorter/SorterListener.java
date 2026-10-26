package fr.influencraft.fluxsorter;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.Iterator;
import java.util.Set;

public final class SorterListener implements Listener {

    private static final Set<Material> COPPER_CHESTS = Set.of(
            Material.COPPER_CHEST, Material.EXPOSED_COPPER_CHEST,
            Material.WEATHERED_COPPER_CHEST, Material.OXIDIZED_COPPER_CHEST,
            Material.WAXED_COPPER_CHEST, Material.WAXED_EXPOSED_COPPER_CHEST,
            Material.WAXED_WEATHERED_COPPER_CHEST, Material.WAXED_OXIDIZED_COPPER_CHEST);

    private static final BlockFace[] HORIZONTAL = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    private final FluxSorter plugin;
    private final SorterItem items;

    public SorterListener(FluxSorter plugin) {
        this.plugin = plugin;
        this.items = plugin.sorterItem();
    }

    public static boolean isCopperChest(Material material) {
        return COPPER_CHESTS.contains(material);
    }

    // ---------------------------------------------------------------- pose

    /**
     * Empêche toute connexion en double coffre avec un coffre de tri, au moment de la pose.
     *
     * Vanilla (CopperChestBlock) : deux coffres en cuivre qui se connectent sont harmonisés,
     * le bloc est REMPLACÉ par la variante la moins oxydée des deux. Un coffre de tri rattaché
     * devient donc un coffre en cuivre ordinaire, et un coffre de tri posé à côté d'un coffre
     * peu oxydé n'est même plus un coffre oxydé ciré à la pose. Corriger après coup ne suffit pas.
     *
     * Paper appelle BlockPlaceEvent avant la mise à jour des voisins, puis relit l'état réel du
     * bloc pour les prévenir : forcer ici le bloc posé en coffre simple (et le coffre de tri dans
     * sa variante) fait que ni la connexion ni l'harmonisation n'ont lieu.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlaceShape(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (!(block.getBlockData() instanceof org.bukkit.block.data.type.Chest data)) return;

        if (items.isSorterItem(event.getItemInHand())) {
            if (block.getType() == SorterItem.MATERIAL
                    && data.getType() == org.bukkit.block.data.type.Chest.Type.SINGLE) return;
            org.bukkit.block.data.type.Chest fixed =
                    (org.bukkit.block.data.type.Chest) SorterItem.MATERIAL.createBlockData();
            fixed.setFacing(data.getFacing());
            fixed.setWaterlogged(data.isWaterlogged());
            fixed.setType(org.bukkit.block.data.type.Chest.Type.SINGLE);
            block.setBlockData(fixed, false);
            return;
        }

        if (data.getType() == org.bukkit.block.data.type.Chest.Type.SINGLE) return;
        if (items.isSorterBlock(block.getRelative(partnerDirection(data)))) {
            data.setType(org.bukkit.block.data.type.Chest.Type.SINGLE);
            block.setBlockData(data, false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        // Tout coffre (classique, piégé, en cuivre) : on ne présume pas des règles de connexion.
        if (!(block.getBlockData() instanceof org.bukkit.block.data.type.Chest)) return;

        boolean placedSorter = items.isSorterItem(event.getItemInHand());
        int level = placedSorter ? items.levelOf(event.getItemInHand()) : 1;

        // Tout se fait au tick suivant : pendant BlockPlaceEvent, Paper travaille sur une copie
        // capturée du bloc et une écriture dans le PDC de l'entité de bloc serait perdue.
        // La connexion vanilla en double coffre est aussi calculée à ce moment-là.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (placedSorter) {
                if (block.getType() != SorterItem.MATERIAL) return;
                items.markBlock(block, level);
                plugin.registry().add(block);
                unlink(block);
            } else {
                for (BlockFace face : HORIZONTAL) {
                    Block neighbour = block.getRelative(face);
                    if (items.isSorterBlock(neighbour)) unlink(neighbour);
                }
            }
        });
    }

    /**
     * Garantit qu'un coffre de tri reste simple : le repasse en coffre simple, ainsi que tout
     * coffre voisin dont l'autre moitié serait ce coffre de tri. Appelé à la pose et en
     * filet de sécurité régulier par le registre (quelle que soit la cause d'un rattachement).
     */
    public static void unlink(Block sorter) {
        setSingle(sorter);
        for (BlockFace face : HORIZONTAL) {
            Block neighbour = sorter.getRelative(face);
            if (!(neighbour.getBlockData() instanceof org.bukkit.block.data.type.Chest data)) continue;
            if (data.getType() == org.bukkit.block.data.type.Chest.Type.SINGLE) continue;
            if (neighbour.getRelative(partnerDirection(data)).equals(sorter)) setSingle(neighbour);
        }
    }

    public static boolean isLinked(Block sorter) {
        return sorter.getBlockData() instanceof org.bukkit.block.data.type.Chest data
                && data.getType() != org.bukkit.block.data.type.Chest.Type.SINGLE;
    }

    private static void setSingle(Block block) {
        if (!(block.getBlockData() instanceof org.bukkit.block.data.type.Chest data)) return;
        if (data.getType() == org.bukkit.block.data.type.Chest.Type.SINGLE) return;
        data.setType(org.bukkit.block.data.type.Chest.Type.SINGLE);
        block.setBlockData(data, false);
    }

    /** Direction de l'autre moitié (vanilla : LEFT = sens horaire depuis la façade, RIGHT = antihoraire). */
    private static BlockFace partnerDirection(org.bukkit.block.data.type.Chest data) {
        BlockFace f = data.getFacing();
        boolean left = data.getType() == org.bukkit.block.data.type.Chest.Type.LEFT;
        return switch (f) {
            case NORTH -> left ? BlockFace.EAST : BlockFace.WEST;
            case EAST -> left ? BlockFace.SOUTH : BlockFace.NORTH;
            case SOUTH -> left ? BlockFace.WEST : BlockFace.EAST;
            default -> left ? BlockFace.NORTH : BlockFace.SOUTH;
        };
    }

    // ---------------------------------------------------------------- casse

    /**
     * Seul le drop réel est traité : BlockDropItemEvent n'est déclenché qu'après la
     * suppression effective du bloc par un joueur. Un BlockBreakEvent "de test" lancé par
     * un autre plugin, ou annulé plus tard, ne produit donc rien (pas de duplication).
     *
     * Le contenu du coffre est capturé dans la même liste que le drop du bloc. On compte les
     * coffres en cuivre oxydés cirés vanilla du contenu (état d'avant la casse) : s'il y en a
     * un de plus dans les drops, c'est celui du bloc, et on le convertit en coffre de tri.
     * Sinon (créatif, outil inadapté...), rien n'est converti.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(BlockDropItemEvent event) {
        BlockState before = event.getBlockState();
        if (!items.isSorterState(before) || !(before instanceof Chest chest)) return;
        plugin.sortManager().stop(event.getBlock());
        plugin.registry().remove(event.getBlock());

        int level = items.levelOf(before);
        int inContents = 0;
        for (ItemStack content : chest.getSnapshotInventory().getContents()) {
            if (isVanillaSorterMaterial(content)) inContents += content.getAmount();
        }
        int inDrops = 0;
        for (Item drop : event.getItems()) {
            if (isVanillaSorterMaterial(drop.getItemStack())) inDrops += drop.getItemStack().getAmount();
        }
        if (inDrops <= inContents) return;

        for (Item drop : event.getItems()) {
            ItemStack stack = drop.getItemStack();
            if (!isVanillaSorterMaterial(stack)) continue;
            if (stack.getAmount() <= 1) {
                drop.setItemStack(items.create(1, level));
            } else {
                stack.setAmount(stack.getAmount() - 1);
                drop.setItemStack(stack);
                event.getBlock().getWorld().dropItemNaturally(
                        event.getBlock().getLocation().add(0.5, 0.5, 0.5), items.create(1, level));
            }
            return;
        }
    }

    private boolean isVanillaSorterMaterial(ItemStack stack) {
        // Un coffre qui n'a gardé que le nom compte aussi : converti, il retrouve sa description.
        return stack != null && stack.getType() == SorterItem.MATERIAL && !items.hasItemMark(stack);
    }

    // ---------------------------------------------------------------- explosions : incassable

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        stripSorters(event.blockList().iterator());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        stripSorters(event.blockList().iterator());
    }

    private void stripSorters(Iterator<Block> it) {
        while (it.hasNext()) {
            if (items.isSorterBlock(it.next())) it.remove();
        }
    }

    // ---------------------------------------------------------------- hache : pas de décirage

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        ItemStack hand = event.getItem();
        if (hand == null || !Tag.ITEMS_AXES.isTagged(hand.getType())) return;
        if (items.isSorterBlock(event.getClickedBlock())) {
            event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
            // Sans objet utilisé, le clic droit ouvre le coffre normalement (sauf en sneak).
        }
    }

    // ---------------------------------------------------------------- golem de cuivre

    /**
     * Le golem de cuivre vanilla prend des objets dans les coffres en cuivre : il ignore
     * complètement les coffres de tri (ni source ni destination).
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onGolemTarget(io.papermc.paper.event.entity.ItemTransportingEntityValidateTargetEvent event) {
        if (items.isSorterBlock(event.getBlock())) event.setAllowed(false);
    }

    // ---------------------------------------------------------------- enclume : pas de contrefaçon

    /**
     * Un objet renommé "Coffre de tri" à l'enclume n'est pas fonctionnel (pas de marque PDC,
     * que l'enclume ne peut pas ajouter), mais pourrait être revendu comme un vrai :
     * ce nom est donc interdit sur tout objet qui n'est pas un coffre de tri.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAnvil(org.bukkit.event.inventory.PrepareAnvilEvent event) {
        ItemStack result = event.getResult();
        if (result == null || result.isEmpty() || items.isSorterItem(result)) return;
        String rename = event.getView().getRenameText();
        if (rename != null && items.isReservedName(rename)) event.setResult(null);
    }

    // ---------------------------------------------------------------- fermeture : lancement du tri

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (player.getGameMode() == GameMode.SPECTATOR) return;
        InventoryHolder holder = event.getInventory().getHolder(false);
        if (!(holder instanceof Chest chest)) return;
        Block block = chest.getBlock();
        if (!items.isSorterBlock(block)) return;
        // Un tick plus tard : la fermeture peut encore rendre l'objet du curseur à l'inventaire.
        Bukkit.getScheduler().runTask(plugin, () -> plugin.sortManager().start(block, player));
    }
}

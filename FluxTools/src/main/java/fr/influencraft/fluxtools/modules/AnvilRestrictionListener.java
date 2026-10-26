package fr.influencraft.fluxtools.modules;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * Bloque toute interaction d'enclume (réparation au matériau, fusion de deux
 * objets identiques, renommage) dès qu'un des deux objets posés porte la
 * ligne de lore "Irréparable" — le tag visuel déjà utilisé sur les outils
 * ordinaire/tendance (ItemsAdder, contents/influrewards/config.yml) et sur
 * les objets légendaires type Hache Phénomène sert aussi de marqueur
 * technique ici.
 * <p>
 * Nécessaire car le composant vanilla {@code repairable={items:[]}} ne bloque
 * que la réparation au matériau (lingot, etc.) : la fusion de deux objets
 * identiques pour cumuler leur durabilité est un mécanisme d'enclume séparé,
 * câblé en dur côté vanilla, qu'aucun composant d'item ne peut désactiver.
 */
public final class AnvilRestrictionListener implements Listener {

    private static final String MARKER = "Irréparable";

    /**
     * Priorité HIGHEST : doit s'exécuter après les autres plugins qui touchent
     * à {@link PrepareAnvilEvent} (WorldGuard, Lands, etc.) pour que notre
     * annulation du résultat ne soit pas recalculée/écrasée après coup.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        AnvilInventory inventory = event.getInventory();
        if (isMarkedUnrepairable(inventory.getItem(0)) || isMarkedUnrepairable(inventory.getItem(1))) {
            event.setResult(null);
        }
    }

    /**
     * Exposé (statique) pour que {@link RepairMenu} refuse exactement les mêmes objets :
     * `/repair` serait sinon une porte dérobée qui annule ce tag.
     */
    static boolean isMarkedUnrepairable(ItemStack item) {
        if (item == null) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore()) {
            return false;
        }
        List<Component> lore = meta.lore();
        if (lore != null) {
            for (Component line : lore) {
                if (PlainTextComponentSerializer.plainText().serialize(line).contains(MARKER)) {
                    return true;
                }
            }
        }
        return false;
    }
}

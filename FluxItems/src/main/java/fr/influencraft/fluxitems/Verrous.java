package fr.influencraft.fluxitems;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Les enchantements d'un outil FluxItems évoluent avec lui : ni table, ni meule, ni table de forge,
 * ni fusion à l'établi. À l'enclume, seules passent la réparation au matériau (outil réparable) et
 * les livres Incassable (outil réparable) et Lié à l'âme, jamais les deux sur le même outil.
 */
final class Verrous implements Listener {

    /** Enchantements vanilla : EcoEnchants inscrit les siens dans le même espace {@code minecraft}. */
    private static final Set<Enchantment> VANILLA = new HashSet<>();

    static {
        for (Field f : Enchantment.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == Enchantment.class) {
                try {
                    VANILLA.add((Enchantment) f.get(null));
                } catch (IllegalAccessException ignored) {
                }
            }
        }
    }

    private final FluxItems plugin;

    Verrous(FluxItems plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void enclume(PrepareAnvilEvent ev) {
        appliquer(ev);
    }

    /** Réaffirmé en dernier : EcoEnchants peut aussi écrire le résultat de l'enclume. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void enclumeFin(PrepareAnvilEvent ev) {
        appliquer(ev);
    }

    private void appliquer(PrepareAnvilEvent ev) {
        ItemStack gauche = ev.getInventory().getFirstItem();
        ItemStack droite = ev.getInventory().getSecondItem();
        Outil o = plugin.outil(gauche);
        if (o == null) {
            // Objet ordinaire : enchantements vanilla libres, mais aucun enchantement custom à l'enclume
            // (hors Incassable et Lié à l'âme), et jamais un outil FluxItems en sacrifice.
            if (plugin.outil(droite) != null || customInterdit(gauche) || customInterdit(droite)) ev.setResult(null);
            return;
        }
        if (droite == null || droite.isEmpty() || plugin.outil(droite) != null) {
            ev.setResult(null);
            return;
        }
        if (droite.getType() == Material.ENCHANTED_BOOK) {
            ev.setResult(livre(o, gauche, droite));
            if (ev.getResult() != null) ev.getView().setRepairCost(10);
            return;
        }
        if (!o.reparable || !gauche.canRepair(droite)) {
            ev.setResult(null);
            return;
        }
        // Réparation vanilla : on garde le résultat calculé, mais sans renommage ni autre enchantement.
        ItemStack r = ev.getResult();
        if (r == null || r.isEmpty()) return;
        Etat e = Etat.lire(r);
        if (e == null) return;
        r.removeEnchantments();
        r.addUnsafeEnchantments(gauche.getEnchantments());
        Rendu.maj(r, o, e, false);
        ev.setResult(r);
    }

    /** Livre portant un enchantement custom (EcoEnchants) autre qu'Incassable ou Lié à l'âme. */
    private static boolean customInterdit(ItemStack i) {
        if (i == null || !(i.getItemMeta() instanceof EnchantmentStorageMeta m)) return false;
        for (Enchantment e : m.getStoredEnchants().keySet()) {
            if (!VANILLA.contains(e) && !Rendu.LIVRES.contains(e.getKey().getKey())) return true;
        }
        return false;
    }

    private ItemStack livre(Outil o, ItemStack outil, ItemStack livre) {
        if (!(livre.getItemMeta() instanceof EnchantmentStorageMeta m) || m.getStoredEnchants().size() != 1) return null;
        Map.Entry<Enchantment, Integer> en = m.getStoredEnchants().entrySet().iterator().next();
        String id = en.getKey().getKey().getKey();
        if (!Rendu.LIVRES.contains(id)) return null;
        if ("incassable".equals(id) && !o.reparable) return null;
        for (Enchantment deja : outil.getEnchantments().keySet()) {
            if (Rendu.LIVRES.contains(deja.getKey().getKey())) return null;
        }
        ItemStack r = outil.clone();
        r.addUnsafeEnchantment(en.getKey(), en.getValue());
        return r;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void table(PrepareItemEnchantEvent ev) {
        if (plugin.outil(ev.getItem()) != null) ev.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void tableEnchante(EnchantItemEvent ev) {
        if (plugin.outil(ev.getItem()) == null) return;
        ev.setCancelled(true);
        Player p = ev.getEnchanter();
        plugin.message(p, "verrou");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void meule(PrepareGrindstoneEvent ev) {
        if (plugin.outil(ev.getInventory().getUpperItem()) != null || plugin.outil(ev.getInventory().getLowerItem()) != null) {
            ev.setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void forge(PrepareSmithingEvent ev) {
        for (ItemStack i : ev.getInventory().getContents()) {
            if (plugin.outil(i) != null) {
                ev.setResult(null);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void etabli(PrepareItemCraftEvent ev) {
        for (ItemStack i : ev.getInventory().getMatrix()) {
            if (plugin.outil(i) != null) {
                ev.getInventory().setResult(null);
                return;
            }
        }
    }
}

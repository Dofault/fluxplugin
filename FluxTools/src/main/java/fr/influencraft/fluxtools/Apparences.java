package fr.influencraft.fluxtools;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Equippable;
import net.kyori.adventure.key.Key;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Apparences 3D posées sur des objets d'autres plugins (pièces EcoArmor...), reconnus par leur matériau et
 * une valeur de leur PersistentDataContainer ({@code apparences.yml}) : composant item_model (icône, modèle
 * porté sur la tête) et apparence portée (composant equippable). Mêmes moments que {@link Rafraichissement},
 * plus l'équipement d'une armure, le ramassage et la fermeture d'un inventaire (gain de caisse).
 */
final class Apparences implements Listener {

    /**
     * {@code equipement} : absent = apparence portée d'origine, null = aucune (modèle de l'objet sur la tête).
     * {@code parure} : modèle 3D porté sur le torse par {@link Parures}, ou null.
     */
    private record Regle(NamespacedKey marqueur, String valeur, NamespacedKey modele, boolean equipe, NamespacedKey equipement,
                         NamespacedKey parure) {
    }

    private final FluxTools plugin;
    private final Map<Material, List<Regle>> regles = new EnumMap<>(Material.class);
    private ConfigurationSection reglagesParures;

    Apparences(FluxTools plugin) {
        this.plugin = plugin;
    }

    void charger() {
        regles.clear();
        reglagesParures = null;
        File f = new File(plugin.getDataFolder(), "apparences.yml");
        if (!f.isFile()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
        reglagesParures = yml.getConfigurationSection("parures");
        ConfigurationSection racine = yml.getConfigurationSection("apparences");
        if (racine == null) return;
        for (String id : racine.getKeys(false)) {
            ConfigurationSection c = racine.getConfigurationSection(id);
            Material mat = c == null ? null : Material.matchMaterial(c.getString("materiau", ""));
            NamespacedKey marqueur = c == null ? null : NamespacedKey.fromString(c.getString("marqueur", ""));
            if (mat == null || marqueur == null) {
                plugin.getLogger().warning("Apparence " + id + " : matériau ou marqueur invalide, ignorée.");
                continue;
            }
            String eq = c.getString("equipement");
            regles.computeIfAbsent(mat, k -> new ArrayList<>()).add(new Regle(marqueur, c.getString("valeur", ""),
                    cle(c.getString("modele")), eq != null, eq == null || eq.equalsIgnoreCase("aucun") ? null : cle(eq),
                    cle(c.getString("parure"))));
        }
        int n = regles.values().stream().mapToInt(List::size).sum();
        if (n > 0) plugin.getLogger().info(n + " apparences chargées.");
    }

    /** Section {@code parures} d'apparences.yml (placement des modèles du torse), ou null. */
    ConfigurationSection reglagesParures() {
        return reglagesParures;
    }

    /** Modèle de parure de la règle qui reconnaît l'objet, ou null. */
    NamespacedKey parure(ItemStack item) {
        Regle r = regle(item);
        return r == null ? null : r.parure();
    }

    private Regle regle(ItemStack item) {
        if (item == null || item.isEmpty()) return null;
        List<Regle> liste = regles.get(item.getType());
        if (liste == null) return null;
        for (Regle r : liste)
            if (r.valeur().equals(item.getPersistentDataContainer().get(r.marqueur(), PersistentDataType.STRING))) return r;
        return null;
    }

    private static NamespacedKey cle(String s) {
        return s == null || s.isBlank() ? null : NamespacedKey.fromString(s);
    }

    /**
     * Pose l'apparence de la règle qui reconnaît l'objet ; vrai si l'objet a changé. Composants lus par l'API de
     * Paper, qui renvoie la valeur réelle (celle du matériau si l'objet n'en écrit pas) : ItemMeta#getEquippable
     * renvoie au contraire un composant vide (tête, sans apparence) quand l'objet n'en écrit pas.
     */
    boolean appliquer(ItemStack item) {
        Regle r = regle(item);
        if (r == null) return false;
        boolean change = false;
        if (r.modele() != null && !meme(item.getData(DataComponentTypes.ITEM_MODEL), r.modele())) {
            item.setData(DataComponentTypes.ITEM_MODEL, r.modele());
            change = true;
        }
        Equippable eq = item.getData(DataComponentTypes.EQUIPPABLE);
        if (r.equipe() && eq != null && !meme(eq.assetId(), r.equipement())) {
            item.setData(DataComponentTypes.EQUIPPABLE, eq.toBuilder().assetId(r.equipement()));
            change = true;
        }
        return change;
    }

    private static boolean meme(Key a, Key b) {
        return Objects.equals(a == null ? null : a.asString(), b == null ? null : b.asString());
    }

    void inventaire(Inventory inv) {
        if (regles.isEmpty()) return;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (appliquer(item)) inv.setItem(i, item);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void connexion(PlayerJoinEvent ev) {
        inventaire(ev.getPlayer().getInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void ouverture(InventoryOpenEvent ev) {
        inventaire(ev.getInventory());
        inventaire(ev.getPlayer().getInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void fermeture(InventoryCloseEvent ev) {
        inventaire(ev.getPlayer().getInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void enMain(PlayerItemHeldEvent ev) {
        Player p = ev.getPlayer();
        ItemStack item = p.getInventory().getItem(ev.getNewSlot());
        if (appliquer(item)) p.getInventory().setItem(ev.getNewSlot(), item);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void equipement(PlayerArmorChangeEvent ev) {
        ItemStack item = ev.getPlayer().getInventory().getItem(ev.getSlot());
        if (appliquer(item)) ev.getPlayer().getInventory().setItem(ev.getSlot(), item);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void ramassage(EntityPickupItemEvent ev) {
        ItemStack item = ev.getItem().getItemStack();
        if (appliquer(item)) ev.getItem().setItemStack(item);
    }
}

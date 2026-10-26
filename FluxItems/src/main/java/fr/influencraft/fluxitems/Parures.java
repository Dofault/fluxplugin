package fr.influencraft.fluxitems;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import io.papermc.paper.datacomponent.DataComponentTypes;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Parures : pièces 3D d'armure qui suivent le torse (grand V, épaulières, jupe), impossibles avec une armure
 * portée, dont Minecraft ne dessine que des textures sur les boîtes du joueur. Une entité d'affichage par pièce
 * équipée (clé {@code parure} de la règle d'apparence), replacée à chaque tick sur le joueur et tournée comme son
 * corps ; le client lisse le déplacement ({@code lissage} ticks, comme il lisse les autres joueurs).
 * <p>
 * Jamais montée sur le joueur en passager : Paper refuse de téléporter un joueur qui porte un passager.
 * Cachée au porteur, sauf {@code visible-porteur} (il la verrait traîner derrière sa position prédite), à qui ne voit pas le porteur (vanish),
 * et quand le corps n'est plus debout (nage, élytres, lit, invisibilité). Penchée comme le torse en accroupi.
 */
final class Parures implements Listener {

    private static final EquipmentSlot[] EMPLACEMENTS = {EquipmentSlot.CHEST, EquipmentSlot.LEGS};

    /** Pièce portée : son entité, l'objet affiché et l'état déjà envoyé (pour n'envoyer que les changements). */
    private static final class Porte {
        final NamespacedKey modele;
        final ItemDisplay entite;
        final ItemStack objet;
        Boolean accroupi;
        boolean visible = true;

        Porte(NamespacedKey modele, ItemDisplay entite, ItemStack objet) {
            this.modele = modele;
            this.entite = entite;
            this.objet = objet;
        }
    }

    private final FluxItems plugin;
    private final Map<UUID, EnumMap<EquipmentSlot, Porte>> portes = new HashMap<>();
    private BukkitTask tache;
    private int ticks;

    // Réglages (section parures d'apparences.yml), en blocs et en degrés.
    private float hauteur = 1.125f;    // centre du torse au-dessus des pieds (modèles centrés sur le torse)
    private float decalage;            // ajouté au lacet du corps si le modèle regarde du mauvais côté
    private int lissage = 3;
    private float accroupiBaisse = 0.325f;
    private float accroupiAngle = 28.6f;
    private float pivot = 0.375f;      // cou : haut du torse, au-dessus de son centre
    private boolean visiblePorteur;    // essais : le porteur voit ses pièces (en retard sur sa position prédite)

    Parures(FluxItems plugin) {
        this.plugin = plugin;
    }

    void charger(ConfigurationSection c) {
        if (c != null) {
            hauteur = (float) c.getDouble("hauteur", hauteur);
            decalage = (float) c.getDouble("decalage-lacet", decalage);
            lissage = Math.max(0, Math.min(59, c.getInt("lissage", lissage)));
            accroupiBaisse = (float) c.getDouble("accroupi-baisse", accroupiBaisse);
            accroupiAngle = (float) c.getDouble("accroupi-angle", accroupiAngle);
            visiblePorteur = c.getBoolean("visible-porteur", visiblePorteur);
        }
        retirerTout();
        for (Player p : Bukkit.getOnlinePlayers()) verifier(p);
        if (tache == null) tache = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    void arreter() {
        if (tache != null) tache.cancel();
        tache = null;
        retirerTout();
    }

    private void retirerTout() {
        for (EnumMap<EquipmentSlot, Porte> m : portes.values()) for (Porte pt : m.values()) pt.entite.remove();
        portes.clear();
    }

    private void retirer(Player p) {
        EnumMap<EquipmentSlot, Porte> m = portes.remove(p.getUniqueId());
        if (m != null) for (Porte pt : m.values()) pt.entite.remove();
    }

    /** Crée ou retire les pièces d'après l'armure équipée. */
    void verifier(Player p) {
        if (!p.isOnline() || p.isDead()) {
            retirer(p);
            return;
        }
        EnumMap<EquipmentSlot, Porte> m = portes.computeIfAbsent(p.getUniqueId(), k -> new EnumMap<>(EquipmentSlot.class));
        for (EquipmentSlot e : EMPLACEMENTS) {
            NamespacedKey modele = plugin.apparences().parure(p.getInventory().getItem(e));
            Porte pt = m.get(e);
            if (pt != null && (!Objects.equals(pt.modele, modele) || !pt.entite.isValid() || pt.entite.getWorld() != p.getWorld())) {
                pt.entite.remove();
                m.remove(e);
                pt = null;
            }
            if (pt == null && modele != null) m.put(e, creer(p, modele));
        }
        if (m.isEmpty()) portes.remove(p.getUniqueId());
    }

    private Porte creer(Player p, NamespacedKey modele) {
        ItemStack objet = new ItemStack(Material.PAPER);
        objet.setData(DataComponentTypes.ITEM_MODEL, modele);
        ItemDisplay d = p.getWorld().spawn(position(p), ItemDisplay.class, ent -> {
            ent.setPersistent(false);
            ent.setItemStack(objet);
            ent.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            ent.setBillboard(Display.Billboard.FIXED);
            ent.setTeleportDuration(lissage);
            ent.setInterpolationDuration(2);
            ent.setTransformation(transformation(false));
        });
        if (!visiblePorteur) p.hideEntity(plugin, d);
        Porte pt = new Porte(modele, d, objet);
        pt.accroupi = false;
        return pt;
    }

    private Location position(Player p) {
        Location l = p.getLocation();
        l.setYaw(p.getBodyYaw() + decalage);
        l.setPitch(0);
        return l;
    }

    /** Torse debout, ou penché vers l'avant autour du cou et abaissé comme en accroupi. */
    private Transformation transformation(boolean accroupi) {
        Vector3f t = new Vector3f(0, hauteur, 0);
        Quaternionf r = new Quaternionf();
        if (accroupi) {
            r.rotateX((float) Math.toRadians(accroupiAngle));
            // Rotation autour du cou : t + p - R.p, avec p le cou par rapport au centre du torse.
            Vector3f cou = new Vector3f(0, pivot, 0);
            Vector3f tourne = new Vector3f(cou).rotate(r);
            t.add(cou).sub(tourne).sub(0, accroupiBaisse, 0);
        }
        return new Transformation(t, r, new Vector3f(1, 1, 1), new Quaternionf(new AxisAngle4f()));
    }

    private static boolean debout(Player p) {
        return !(p.isGliding() || p.isSwimming() || p.isSleeping() || p.isRiptiding() || p.isInvisible()
                || p.getGameMode() == GameMode.SPECTATOR || p.getPose() == Pose.SWIMMING || p.getPose() == Pose.FALL_FLYING);
    }

    private void tick() {
        ticks++;
        Iterator<Map.Entry<UUID, EnumMap<EquipmentSlot, Porte>>> it = portes.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, EnumMap<EquipmentSlot, Porte>> en = it.next();
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null || !p.isOnline()) {
                for (Porte pt : en.getValue().values()) pt.entite.remove();
                it.remove();
                continue;
            }
            boolean visible = debout(p);
            boolean accroupi = p.getPose() == Pose.SNEAKING;
            Location l = position(p);
            for (Porte pt : en.getValue().values()) {
                ItemDisplay d = pt.entite;
                if (!d.isValid()) continue;
                if (d.getWorld() != l.getWorld() || d.getLocation().distanceSquared(l) > 64) {
                    // Grand saut (téléportation) : pas de glissade, la pièce y va d'un coup.
                    d.setTeleportDuration(0);
                    d.teleport(l);
                    d.setTeleportDuration(lissage);
                } else {
                    d.teleport(l);
                }
                if (visible != pt.visible) {
                    d.setItemStack(visible ? pt.objet : null);
                    pt.visible = visible;
                }
                if (!Objects.equals(pt.accroupi, accroupi)) {
                    d.setInterpolationDelay(0);
                    d.setTransformation(transformation(accroupi));
                    pt.accroupi = accroupi;
                }
                if (ticks % 10 == 0) visibilite(p, d);
            }
        }
    }

    /** Qui ne voit pas le porteur (vanish) ne voit pas ses pièces ; le porteur ne voit jamais les siennes. */
    private void visibilite(Player porteur, ItemDisplay d) {
        for (Player v : porteur.getWorld().getPlayers()) {
            if (v == porteur) continue;
            boolean doit = v.canSee(porteur);
            if (doit != v.canSee(d)) {
                if (doit) v.showEntity(plugin, d);
                else v.hideEntity(plugin, d);
            }
        }
    }

    private void plusTard(Player p) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> verifier(p), 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void connexion(PlayerJoinEvent ev) {
        plusTard(ev.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void deconnexion(PlayerQuitEvent ev) {
        retirer(ev.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void equipement(PlayerArmorChangeEvent ev) {
        plusTard(ev.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void mort(PlayerDeathEvent ev) {
        retirer(ev.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void reapparition(PlayerRespawnEvent ev) {
        plusTard(ev.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void monde(PlayerChangedWorldEvent ev) {
        plusTard(ev.getPlayer());
    }
}

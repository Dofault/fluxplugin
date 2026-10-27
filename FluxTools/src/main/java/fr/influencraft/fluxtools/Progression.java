package fr.influencraft.fluxtools;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Avancement des quêtes : blocs minés, cultures récoltées, monstres vaincus avec l'outil en main.
 *
 * <p>Les blocs cassés en plus par une capacité (Filon, Tranchée, Récolte d'EcoEnchants) ne comptent
 * pas : ils sont cassés <i>pendant</i> le traitement de l'événement du bloc frappé (événements
 * imbriqués), ou au même tick. Seul le bloc frappé avance la quête, comme pour les métiers.</p>
 */
final class Progression implements Listener {

    private final FluxTools plugin;
    private final Map<UUID, Integer> profondeur = new HashMap<>();
    private final Map<UUID, Integer> dernierTick = new HashMap<>();

    Progression(FluxTools plugin) {
        this.plugin = plugin;
        if (!fr.influencraft.flux.api.world.PlacedBlocks.available()) {
            plugin.getLogger().warning("No placed blocks tracker in Flux: blocks placed by players count for the quests.");
        }
    }

    // ─── Blocs ──────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOWEST)
    public void debut(BlockBreakEvent ev) {
        profondeur.merge(ev.getPlayer().getUniqueId(), 1, Integer::sum);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void casse(BlockBreakEvent ev) {
        Player p = ev.getPlayer();
        UUID id = p.getUniqueId();
        int d = profondeur.getOrDefault(id, 1);
        if (d <= 1) profondeur.remove(id);
        else profondeur.put(id, d - 1);
        if (ev.isCancelled() || d > 1) return;
        int tick = Bukkit.getCurrentTick();
        Integer avant = dernierTick.put(id, tick);
        if (avant != null && avant == tick) return;

        ItemStack item = p.getInventory().getItemInMainHand();
        Outil o = plugin.outil(item);
        if (o == null || plugin.exclu(p.getWorld())) return;
        Block b = ev.getBlock();
        String cible = b.getType().getKey().getKey();
        boolean pose = pose(b);
        Etat e = Etat.lire(item);
        if (!pose) e.blocs++;

        boolean utile = "houe".equals(o.famille) ? mur(b) && !(pose && !(b.getBlockData() instanceof Ageable)) : !pose;
        boolean monte = utile && gagner(p, o, e, o.points.getOrDefault(cible, 0.0));

        Outil.Quete q = e.queteActive(o);
        boolean compte = utile && q != null && q.vise(cible)
                && (q.type() == Outil.Type.MINER || q.type() == Outil.Type.RECOLTER);
        if (compte) avancer(p, item, o, e, q, 1, monte);
        else Rendu.maj(item, o, e, monte);
    }

    private boolean pose(Block b) {
        return fr.influencraft.flux.api.world.PlacedBlocks.playerPlaced(b);
    }

    /** Culture mûre (âge maximal) ; un bloc sans âge (melon, citrouille) compte toujours. */
    private static boolean mur(Block b) {
        return !(b.getBlockData() instanceof Ageable a) || a.getAge() >= a.getMaximumAge();
    }

    // ─── Monstres ───────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void mort(EntityDeathEvent ev) {
        LivingEntity mort = ev.getEntity();
        Player p = mort.getKiller();
        if (p == null || mort.fromMobSpawner() || plugin.exclu(p.getWorld())) return;
        ItemStack item = p.getInventory().getItemInMainHand();
        Outil o = plugin.outil(item);
        if (o == null) return;

        boolean melee = false, tir = false;
        EntityDamageEvent cause = mort.getLastDamageCause();
        if (cause instanceof EntityDamageByEntityEvent de) {
            Entity source = de.getDamager();
            melee = source.equals(p);
            tir = source instanceof Projectile pr && p.equals(pr.getShooter());
        }
        if (!melee && !tir) return;

        Etat e = Etat.lire(item);
        e.tues++;
        String cible = mort.getType().getKey().getKey();
        boolean utile = "arc".equals(o.famille) ? tir : melee;
        boolean monte = utile && gagner(p, o, e, o.points.getOrDefault(cible, 0.0));
        Outil.Quete q = e.queteActive(o);
        boolean compte = q != null && q.vise(cible)
                && (q.type() == Outil.Type.TUER && melee || q.type() == Outil.Type.TIRER && tir);
        if (compte) avancer(p, item, o, e, q, 1, monte);
        else Rendu.maj(item, o, e, monte);
    }

    @EventHandler
    public void quitte(PlayerQuitEvent ev) {
        profondeur.remove(ev.getPlayer().getUniqueId());
        dernierTick.remove(ev.getPlayer().getUniqueId());
    }

    // ─── Avancement, récompenses ────────────────────────────────────────────

    /**
     * Ajoute de l'XP d'outil ; au passage d'un niveau, prévient le joueur.
     * @return vrai si l'enchantement de niveau change (à réappliquer sur l'objet)
     */
    boolean gagner(Player p, Outil o, Etat e, double xp) {
        if (!o.aNiveaux() || xp <= 0) return false;
        int avant = o.niveau(e.xp);
        e.xp += xp;
        int apres = o.niveau(e.xp);
        if (apres == avant) return false;
        plugin.message(p, "niveau", "%niveau%", String.valueOf(apres));
        Component debloque = null;
        for (String id : o.progressifs.keySet()) {
            int v = o.valeur(id, apres);
            Enchantment ench = Outil.enchantement(id);
            if (v == o.valeur(id, avant) || ench == null) continue;
            Component nom = Component.text().append(ench.description()).append(Component.text(" " + Fmt.romain(v))).build();
            p.sendMessage(Fmt.texte(plugin.texte("prefix") + plugin.texte("deblocage")).append(nom.color(NamedTextColor.WHITE)));
            if (debloque == null) debloque = nom;
        }
        p.showTitle(Title.title(Fmt.texte(Fmt.TITRE + "◆ Niveau " + apres),
                debloque == null ? Component.empty() : debloque.color(TextColor.color(0xFFD6DE))));
        fr.influencraft.flux.api.util.Sounds.cue(p, "complete");
        if (apres >= o.niveauMax()) {
            plugin.message(p, "maitrise");
            if (plugin.getConfig().getBoolean("annonce-maitrise", true)) {
                String texte = plugin.texte("annonce").replace("%joueur%", p.getName()).replace("%outil%", o.nom);
                Bukkit.broadcast(Fmt.texte(texte));
            }
        }
        return true;
    }

    void avancer(Player p, ItemStack item, Outil o, Etat e, Outil.Quete q, int n, boolean enchants) {
        Outil.Etape etape = o.etapes.get(e.etape);
        e.avance = Math.min(q.quantite(), e.avance + n);
        while (e.jalons < etape.jalons().size() && e.avance >= etape.jalons().get(e.jalons).quantite()) {
            Outil.Jalon j = etape.jalons().get(e.jalons++);
            payer(p, e, j.recompense());
            plugin.message(p, "jalon", "%quantite%", Fmt.nombre(j.quantite()), "%total%", Fmt.nombre(q.quantite()));
            fr.influencraft.flux.api.util.Sounds.cue(p, "success");
        }
        if (e.avance < q.quantite()) {
            Rendu.maj(item, o, e, enchants);
            return;
        }

        payer(p, e, etape.recompense());
        e.quetes++;
        e.etape++;
        e.avance = 0;
        e.jalons = 0;
        plugin.message(p, "etape", "%quete%", q.libelle());
        boolean fini = e.termine(o);
        if (!fini) {
            e.quete = Rendu.tirer(o.etapes.get(e.etape));
            e.dispo = o.quotidien ? prochainRenouvellement() : 0;
        }
        if (etape.xp() > 0 && o.aNiveaux()) {
            plugin.message(p, "bonus-xp", "%xp%", Fmt.nombre(etape.xp()));
            gagner(p, o, e, etape.xp());
        }
        fr.influencraft.flux.api.util.Sounds.cue(p, "complete");
        Rendu.maj(item, o, e, true);

        if (!fini) {
            if (e.dispo > 0) plugin.message(p, "prochaine", "%duree%", Fmt.duree((e.dispo - System.currentTimeMillis()) / 1000));
            return;
        }
        if (o.seBrise) briser(p, e.uid);
    }

    private void payer(Player p, Etat e, double montant) {
        if (montant <= 0) return;
        plugin.economie().give(p, montant);
        e.gagne += montant;
        p.sendMessage(Fmt.texte(plugin.texte("gain").replace("%montant%", Fmt.nombre(montant))));
    }

    /** Contrat rempli : l'outil se brise au tick suivant (après les dégâts vanilla du coup en cours). */
    private void briser(Player p, String uid) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            ItemStack main = p.getInventory().getItemInMainHand();
            Etat e = Etat.lire(main);
            if (e == null || !e.uid.equals(uid)) return;
            p.getWorld().spawnParticle(Particle.ITEM, p.getEyeLocation().add(p.getLocation().getDirection().multiply(0.6)),
                    20, 0.2, 0.2, 0.2, 0.05, main.clone());
            p.getInventory().setItemInMainHand(null);
            fr.influencraft.flux.api.util.Sounds.cue(p, "remove");
            plugin.message(p, "brise");
        });
    }

    long prochainRenouvellement() {
        ZoneId zone = ZoneId.of(plugin.getConfig().getString("fuseau", "Europe/Paris"));
        ZonedDateTime maintenant = ZonedDateTime.now(zone);
        ZonedDateTime suivant = maintenant.withHour(plugin.getConfig().getInt("heure-renouvellement", 6))
                .withMinute(0).withSecond(0).withNano(0);
        if (!suivant.isAfter(maintenant)) suivant = suivant.plusDays(1);
        return suivant.toInstant().toEpochMilli();
    }
}

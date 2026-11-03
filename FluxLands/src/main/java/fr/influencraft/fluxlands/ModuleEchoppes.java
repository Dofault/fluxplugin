package fr.influencraft.fluxlands;

import fr.influencraft.flux.api.shops.ChestShop;
import fr.influencraft.flux.api.shops.ChestShopPurchaseEvent;
import fr.influencraft.flux.api.shops.ChestShopTradeEvent;
import fr.influencraft.flux.api.shops.ChestShops;
import me.angeschossen.lands.api.events.LandRenameEvent;
import me.angeschossen.lands.api.flags.type.Flags;
import me.angeschossen.lands.api.land.Area;
import me.angeschossen.lands.api.land.Land;
import fr.influencraft.flux.api.economy.Currency;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Lien avec les échoppes des joueurs (FluxCommerce, par l'API {@code shops} de Flux ; chargé seulement s'il est présent) :
 * <ul>
 *   <li>index des échoppes et de la ville où se trouve chacune ;</li>
 *   <li>droit de place : à chaque transaction réussie d'une échoppe située dans une ville,
 *       le propriétaire de l'échoppe reverse {@code taux %} du montant à la banque de la
 *       ville (Lands). Sur un achat (l'échoppe achète), la transaction est refusée avant
 *       coup si le propriétaire ne peut pas payer le montant et le droit ;</li>
 *   <li>échoppe de la ville : option d'une échoppe de vente (proposée à la création et dans ses
 *       réglages, cf. {@link #enregistrerOptionVille}) qui reverse 100 % de ses ventes à la banque de la
 *       ville, comme un droit de place à 100 %.</li>
 * </ul>
 */
final class ModuleEchoppes implements Listener {

    /** Option des échoppes (identifiant stocké avec l'échoppe) : toutes les ventes vont à la ville. */
    static final String ECHOPPE_DE_VILLE = "town";
    /** Suffixe des résumés de versement d'une échoppe de la ville (clé de ville des résumés). */
    private static final String VERSEMENT = "#ville";

    private final FluxLands plugin;
    private final Currency eco;
    private volatile List<Fiches.Echoppe> index = List.of();
    private boolean reindexPrevu;

    /** Retenues en attente de résumé : joueur -> ville -> {montant, nombre}. */
    private final Map<UUID, Map<String, double[]>> resumesProprio = new HashMap<>();
    private final Map<String, double[]> resumesVille = new HashMap<>();

    ModuleEchoppes(FluxLands plugin, Currency eco) {
        this.plugin = plugin;
        this.eco = eco;
    }

    List<Fiches.Echoppe> toutes() {
        return index;
    }

    /** Recalcule l'index (fil principal : API des échoppes et Lands). */
    void indexer() {
        List<Fiches.Echoppe> l = new ArrayList<>();
        for (ChestShop shop : ChestShops.all()) {
            try {
                Location loc = shop.location();
                if (loc == null || loc.getWorld() == null) continue;
                Land land = plugin.villes().a(loc);
                UUID proprio = shop.owner();
                String proprioNom = proprio != null ? Villes.nomJoueur(proprio) : shop.ownerName();
                // last count of the shop plugin (made whenever the chunk is loaded), -1 never counted
                int stock = shop.stock();
                l.add(new Fiches.Echoppe(shop.id(), loc.clone(), proprio, proprioNom,
                        Fiches.materiau(shop.item()), Fiches.nomObjet(shop.item()), shop.item().getAmount(),
                        shop.price(), shop.buying(), stock,
                        land != null ? Villes.id(land) : "", land != null ? land.getName() : ""));
            } catch (RuntimeException e) {
                // échoppe corrompue ou monde absent : ignorée jusqu'au prochain passage
            }
        }
        index = l;
    }

    /** Après chaque transaction : index recalculé une seconde plus tard (une échoppe vidée disparaît des listes). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void stockApresTransaction(ChestShopTradeEvent e) {
        if (reindexPrevu) return;
        reindexPrevu = true;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            reindexPrevu = false;
            plugin.indexer();
        }, 20L);
    }

    // ── Ménage par la ville ─────────────────────────────────────────────────

    /** The shop plugin only lets the owner remove a shop: the town may too (see {@link #peutNettoyer}). */
    void enregistrerMenage() {
        ChestShops.allowRemoval(plugin, this::peutNettoyer);
    }

    /**
     * Mode {@code echoppes.menage} :
     * <ul>
     *   <li>« adjoints » (défaut) : le maire et ses adjoints, c'est-à-dire qui a dans la zone publique de la ville
     *       (zone par défaut de Lands) à la fois le droit Casser et le droit de modifier la ville
     *       ({@code setting_edit_land}, que n'ont ni les résidents ni les locataires) ;</li>
     *   <li>« maire » : le maire seulement ;</li>
     *   <li>« droit-de-casser » : le maire et quiconque a le droit Casser là où est l'échoppe ;</li>
     *   <li>« non » : personne d'autre que le propriétaire (et le staff).</li>
     * </ul>
     * Personne ne passe outre un contrat : sur une parcelle louée ou vendue au propriétaire de l'échoppe, seul le mode
     * « droit-de-casser » laisse faire qui a le droit Casser de cette parcelle (la ville retrouve la main une fois le
     * contrat terminé).
     */
    private boolean peutNettoyer(ChestShop shop, UUID joueur) {
        String mode = plugin.getConfig().getString("echoppes.menage", "adjoints");
        if (joueur == null || "non".equalsIgnoreCase(mode)) return false;
        Location loc = shop.location();
        Land land = plugin.villes().a(loc);
        if (land == null) return false;
        Area zone = land.getArea(loc);
        UUID proprio = shop.owner();
        boolean contrat = zone != null && proprio != null && proprio.equals(zone.getTenant());
        if (contrat) return "droit-de-casser".equalsIgnoreCase(mode) && zone.hasRoleFlag(joueur, Flags.BLOCK_BREAK);
        if (Villes.estMaire(land, joueur)) return true;
        if ("adjoints".equalsIgnoreCase(mode)) {
            Area publique = land.getDefaultArea();
            return publique.hasRoleFlag(joueur, Flags.BLOCK_BREAK) && publique.hasRoleFlag(joueur, Flags.SETTING_EDIT_LAND);
        }
        return "droit-de-casser".equalsIgnoreCase(mode) && zone != null && zone.hasRoleFlag(joueur, Flags.BLOCK_BREAK);
    }

    /** Après la casse d'une échoppe par la ville : prévient les deux joueurs et garde une trace. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void apresMenage(BlockBreakEvent e) {
        ChestShop shop = ChestShops.at(e.getBlock().getLocation());
        if (shop == null) return;
        Player p = e.getPlayer();
        UUID proprio = shop.owner();
        if (proprio == null || proprio.equals(p.getUniqueId()) || !peutNettoyer(shop, p.getUniqueId())) return;
        Land land = plugin.villes().a(shop.location());
        String objet = Fiches.nomObjetTexte(shop.item());
        String ville = land != null ? land.getName() : "?";
        plugin.getLogger().info(p.getName() + " a retiré l'échoppe " + shop.id() + " (" + shop.item().getType().getKey() + ") de "
                + Villes.nomJoueur(proprio) + " à " + ville);
        Fmt.envoyer(p, "Tu as retiré l'échoppe de " + Fmt.v(Villes.nomJoueur(proprio)) + " (" + Fmt.v(objet)
                + "). Son contenu est tombé au sol.");
        Fmt.envoyer(Bukkit.getPlayer(proprio), "<t:attention>Ton échoppe de</t> " + Fmt.v(objet) + " <t:attention>à</t> "
                + Fmt.ville(ville) + " <t:attention>a été retirée par</t> " + Fmt.v(p.getName()) + "<t:attention>.</t>");
    }

    // ── Échoppe de la ville ─────────────────────────────────────────────────

    /**
     * Propose l'option « Échoppe de la ville » (case des fenêtres de création et de réglages de
     * l'échoppe) à qui crée une échoppe de vente dans une ville qui perçoit le droit de place.
     */
    void enregistrerOptionVille() {
        ChestShops.option(plugin, ECHOPPE_DE_VILLE, (joueur, loc, shop) -> {
            if (!plugin.getConfig().getBoolean("droit-de-place.actif", true)
                    || !plugin.getConfig().getBoolean("droit-de-place.echoppe-de-ville", true)) return null;
            if (shop != null && (shop.buying() || shop.unlimited())) return null;
            Land land = plugin.villes().a(loc);
            if (!plugin.villes().percoit(land)) return null;
            return new ChestShops.Offer(Fmt.ligne(joueur, "&#FFD94DÉchoppe de la ville"),
                    Fmt.ligne(joueur, "&#B376E5100 % des ventes versées à la banque de &f" + land.getName()
                            + (shop == null ? " &7(échoppe de vente uniquement)" : "")));
        });
    }

    /**
     * Seller of a town shop: the town's name (Lands colours and style) instead of the owner's, above the shop and in
     * its trade window. Shown again when the town is renamed.
     */
    void enregistrerNomVendeur() {
        ChestShops.sellerName(plugin, shop -> {
            if (!echoppeDeVille(shop)) return null;
            Land land = plugin.villes().a(shop.location());
            return land == null ? null : fr.influencraft.flux.api.text.Texts.colors(Couleurs.legacy(land.getColorName()));
        });
    }

    /** Town renamed: its town shops are drawn again (the new name is set on the next tick). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void villeRenommee(LandRenameEvent e) {
        String ville = Villes.id(e.getLand());
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (ChestShop shop : ChestShops.all()) {
                if (!echoppeDeVille(shop)) continue;
                Land land = plugin.villes().a(shop.location());
                if (land != null && Villes.id(land).equals(ville)) ChestShops.refresh(shop);
            }
        });
    }

    /** Échoppe de vente dont le propriétaire reverse tout à la ville (un achat n'a pas de recette). */
    private static boolean echoppeDeVille(ChestShop shop) {
        return shop.has(ECHOPPE_DE_VILLE) && !shop.buying();
    }

    // ── Droit de place ──────────────────────────────────────────────────────

    /** Ville qui perçoit le droit pour cette échoppe, ou null (hors ville, illimitée, désactivé...). */
    private Land villeTaxatrice(ChestShop shop) {
        UUID proprio = shop.owner();
        if (!plugin.getConfig().getBoolean("droit-de-place.actif", true) || proprio == null) return null;
        if (shop.unlimited() && plugin.getConfig().getBoolean("droit-de-place.ignorer-echoppes-illimitees", true)) return null;
        Land land = plugin.villes().a(shop.location());
        if (!plugin.villes().percoit(land)) return null;
        if (!echoppeDeVille(shop) && plugin.getConfig().getBoolean("droit-de-place.exempter-maire", false)
                && Villes.estMaire(land, proprio)) return null;
        return land;
    }

    private double part(Land land, double montant) {
        double taux = plugin.registre().taux(Villes.id(land));
        return Math.floor(montant * taux) / 100.0;
    }

    /** Achat : refuse d'avance si le propriétaire ne peut pas payer le joueur ET le droit de place. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void avantTransaction(ChestShopPurchaseEvent e) {
        ChestShop shop = e.shop();
        if (!shop.buying()) return;
        Land land = villeTaxatrice(shop);
        if (land == null) return;
        double droit = part(land, e.total());
        if (droit <= 0) return;
        OfflinePlayer owner = Bukkit.getOfflinePlayer(shop.owner());
        if (eco.balance(owner) < e.total() + droit) {
            e.refuse(Fmt.message(null, "<t:erreur>Le propriétaire de cette échoppe ne peut pas payer cet achat</t> et le droit de place de "
                    + Fmt.ville(land.getName()) + "."));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void apresTransaction(ChestShopTradeEvent e) {
        ChestShop shop = e.shop();
        Land land = villeTaxatrice(shop);
        if (land == null) return;
        boolean versement = echoppeDeVille(shop);
        // échoppe de la ville : tout ce que le propriétaire a reçu (vente moins la taxe retenue sur sa part)
        double droit = versement ? Math.floor(Math.max(0, e.total() - e.tax()) * 100) / 100.0 : part(land, e.total());
        if (droit <= 0) return;
        UUID proprio = shop.owner();
        OfflinePlayer owner = Bukkit.getOfflinePlayer(proprio);
        var origine = fr.influencraft.flux.api.economy.Origin.of("FluxLands", versement ? "town_shop" : "stall_fee");
        if (!eco.take(owner, droit, origine)) {
            plugin.getLogger().warning("Droit de place non prélevé : " + Villes.nomJoueur(proprio) + " n'a pas " + droit
                    + " (échoppe " + shop.id() + ", ville " + land.getName() + ")");
            return;
        }
        if (!land.modifyBalance(droit)) {
            eco.give(owner, droit, origine); // bank refused: money given back
            plugin.getLogger().warning("Banque de " + land.getName() + " refusée, droit de place rendu à " + Villes.nomJoueur(proprio));
            return;
        }
        String id = Villes.id(land);
        plugin.registre().encaisser(id, shop.id(), droit);
        noter(proprio, versement ? id + VERSEMENT : id, droit);
        if (plugin.getConfig().getInt("droit-de-place.resume-secondes", 60) <= 0) envoyerResumes();
    }

    private void noter(UUID proprio, String ville, double droit) {
        double[] a = resumesProprio.computeIfAbsent(proprio, k -> new HashMap<>()).computeIfAbsent(ville, k -> new double[2]);
        a[0] += droit;
        a[1]++;
        double[] v = resumesVille.computeIfAbsent(ville, k -> new double[2]);
        v[0] += droit;
        v[1]++;
    }

    /** Messages regroupés : au propriétaire (perte) et au maire de la ville (gain). Convention docs/notifications/bitblock.md. */
    void envoyerResumes() {
        for (Map.Entry<UUID, Map<String, double[]>> e : resumesProprio.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) continue;
            for (Map.Entry<String, double[]> v : e.getValue().entrySet()) {
                boolean versement = v.getKey().endsWith(VERSEMENT);
                Land land = plugin.villes().parId(v.getKey().replace(VERSEMENT, ""));
                String nom = land != null ? land.getName() : "?";
                long n = (long) v.getValue()[1];
                if (versement) {
                    Fmt.notification(p, "&7%img_bitblock% &#B376E5Tu as reversé &f" + Fmt.argent(v.getValue()[0]) + " &#B376E5Bitblock &#FF5C5C▼&7 à la banque de &f"
                            + nom + " &7(" + n + " vente" + (n > 1 ? "s" : "") + " de tes échoppes de la ville) [ᴇᴄʜᴏᴘᴘᴇ ᴅᴇ ᴠɪʟʟᴇ]");
                } else {
                    Fmt.notification(p, "&7%img_bitblock% &#B376E5Tu as perdu &f" + Fmt.argent(v.getValue()[0]) + " &#B376E5Bitblock &#FF5C5C▼&7 en droit de place à &f"
                            + nom + " &7(" + n + " transaction" + (n > 1 ? "s" : "") + ") [ᴅʀᴏɪᴛ ᴅᴇ ᴘʟᴀᴄᴇ]");
                }
            }
        }
        resumesProprio.clear();
        for (Map.Entry<String, double[]> v : resumesVille.entrySet()) {
            boolean versement = v.getKey().endsWith(VERSEMENT);
            Land land = plugin.villes().parId(v.getKey().replace(VERSEMENT, ""));
            if (land == null) continue;
            Player maire = Bukkit.getPlayer(land.getOwnerUID());
            if (maire == null) continue;
            long n = (long) v.getValue()[1];
            if (versement) {
                Fmt.notification(maire, "&7%img_bitblock% &#B376E5Ta ville &f" + land.getName() + " &#B376E5a gagné &f" + Fmt.argent(v.getValue()[0])
                        + " &#B376E5Bitblock &#2ECF7B▲&7 grâce à ses échoppes de la ville (" + n + " vente" + (n > 1 ? "s" : "")
                        + ") [ᴇᴄʜᴏᴘᴘᴇ ᴅᴇ ᴠɪʟʟᴇ]");
            } else {
                Fmt.notification(maire, "&7%img_bitblock% &#B376E5Ta ville &f" + land.getName() + " &#B376E5a gagné &f" + Fmt.argent(v.getValue()[0])
                        + " &#B376E5Bitblock &#2ECF7B▲&7 en droit de place (" + n + " transaction" + (n > 1 ? "s" : "")
                        + ") [ᴅʀᴏɪᴛ ᴅᴇ ᴘʟᴀᴄᴇ]");
            }
        }
        resumesVille.clear();
    }
}

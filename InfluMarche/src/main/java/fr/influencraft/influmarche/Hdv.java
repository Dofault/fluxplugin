package fr.influencraft.influmarche;

import me.elaineqheart.auctionHouse.GUI.impl.CollectSoldItemGUI;
import me.elaineqheart.auctionHouse.data.persistentStorage.ItemNoteStorage;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.SettingManager;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.configs.Blacklist;
import me.elaineqheart.auctionHouse.data.persistentStorage.local.data.ConfigManager;
import me.elaineqheart.auctionHouse.data.ram.AuctionHouseStorage;
import me.elaineqheart.auctionHouse.data.ram.Bid;
import me.elaineqheart.auctionHouse.data.ram.ItemNote;
import net.kyori.adventure.text.event.ClickEvent;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Lien avec AuctionHouse (jar d'origine, non modifié) : lecture des notes et opérations du
 * joueur. Chaque opération reprend <b>les mêmes contrôles, dans le même ordre</b> que le menu
 * natif correspondant d'AuctionHouse 1.5.4 (ConfirmBuyGUI, ConfirmBidGUI, CancelAuctionGUI,
 * CollectSoldItemGUI, CollectExpiredItemGUI, EndedAuctionGUI, commande sell), puis appelle ses
 * méthodes de stockage : les données restent celles d'AuctionHouse (notes, taxe, journaux).
 * Seuls les messages changent (charte, convention des notifications BitBlock).
 */
final class Hdv {

    private final InfluMarche plugin;
    private final Economy eco;

    Hdv(InfluMarche plugin, Economy eco) {
        this.plugin = plugin;
        this.eco = eco;
    }

    // ── Lecture ─────────────────────────────────────────────────────────────

    static double taxe() {
        return SettingManager.taxRate;
    }

    /** Ce que reçoit le vendeur pour une vente de {@code prix} (même arrondi qu'AuctionHouse). */
    static double net(double prix) {
        return Math.floor(prix * 100 * (1 - SettingManager.taxRate)) / 100;
    }

    static ItemNote note(String id) {
        try {
            return AuctionHouseStorage.getNote(UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Offres en cours (ni expirées, ni en attente de mise en vente), fil principal. */
    List<Offre> offres() {
        List<Offre> l = new ArrayList<>();
        for (ItemNote n : AuctionHouseStorage.getAll()) {
            if (n == null || !n.isTheoreticallyOnAuction() || n.isExpired() || n.isOnWaitingList()) continue;
            try {
                l.add(offre(n));
            } catch (RuntimeException e) {
                // note illisible (objet corrompu) : ignorée jusqu'au passage suivant
            }
        }
        return l;
    }

    Offre offre(ItemNote n) {
        ItemStack item = n.getItem();
        int qte = n.getCurrentAmount();
        item.setAmount(Math.max(1, Math.min(qte, 99)));
        boolean enchere = n.isBIDAuction();
        double prix = enchere ? n.getPrice() : n.getCurrentPrice();
        double prochaine = enchere ? (n.hasBidHistory() ? Bid.nextMinBid(n.getPrice()) : n.getPrice()) : 0;
        return new Offre(enchere ? Offre.Type.ENCHERE : Offre.Type.VENTE, n.getNoteID().toString(), item, Objets.cle(item),
                Objets.nom(item), Objets.index(item) + " " + Objets.normaliser(nomJoueur(n.getPlayerUUID(), n.getPlayerName())),
                Objets.categorie(item), prix, qte, n.getPlayerUUID(), nomJoueur(n.getPlayerUUID(), n.getPlayerName()),
                n.getTimeLeft(), -1, null, null, 0, null, n.getBidHistoryList().size(), prochaine, 0);
    }

    /** Pseudo actuel (AuctionHouse garde le nom affiché du moment, parfois avec un préfixe de rang). */
    static String nomJoueur(UUID id, String repli) {
        if (id != null) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(id);
            if (op.getName() != null) return op.getName();
        }
        return repli == null ? "?" : org.bukkit.ChatColor.stripColor(repli);
    }

    int ventesEnCours(Player p) {
        return AuctionHouseStorage.getNumberOfAuctions(p.getUniqueId());
    }

    int emplacements(Player p) {
        return ConfigManager.permissions.getAuctionSlots(p);
    }

    long duree(Player p, boolean enchere) {
        return ConfigManager.permissions.getAuctionDuration(p, enchere);
    }

    // ── États des notes du joueur ───────────────────────────────────────────

    enum Etat {
        ATTENTE("Mise en vente imminente", "&#2BC4E8"),
        EN_VENTE("En vente", "&#2ECF7B"),
        ENCHERE_EN_COURS("Enchère en cours", "&#E060A0"),
        A_ENCAISSER("Vendu : BitBlocks à récupérer", "&#FFD94D"),
        EXPIREE("Expirée : objet à récupérer", "&#FF5C5C"),
        RETIREE_ADMIN("Retirée par la modération", "&#FF5C5C");

        final String nom, couleur;

        Etat(String nom, String couleur) {
            this.nom = nom;
            this.couleur = couleur;
        }
    }

    /** Même arbre de décision que MyAuctionsGUI. */
    static Etat etat(ItemNote n) {
        if (n.isSold() || n.isExpired() && n.hasBidHistory() && n.getAdminMessage() == null) return Etat.A_ENCAISSER;
        if (n.isExpired()) return n.getAdminMessage() != null && !n.getAdminMessage().isEmpty() ? Etat.RETIREE_ADMIN : Etat.EXPIREE;
        if (n.isOnWaitingList()) return Etat.ATTENTE;
        return n.isBIDAuction() && n.hasBidHistory() ? Etat.ENCHERE_EN_COURS : Etat.EN_VENTE;
    }

    List<ItemNote> mesVentes(UUID joueur) {
        return AuctionHouseStorage.getMySortedDateCreated(joueur);
    }

    List<ItemNote> mesEncheres(UUID joueur) {
        return AuctionHouseStorage.getMyBids(joueur);
    }

    /** Notes du joueur qui attendent une action de sa part (BitBlocks ou objet à récupérer). */
    int aRecuperer(Player p) {
        int n = 0;
        for (ItemNote note : mesVentes(p.getUniqueId())) {
            Etat e = etat(note);
            if (e == Etat.A_ENCAISSER || e == Etat.EXPIREE || e == Etat.RETIREE_ADMIN) n++;
        }
        for (ItemNote note : mesEncheres(p.getUniqueId())) if (note.isExpired()) n++;
        return n;
    }

    // ── Achat (ConfirmBuyGUI) ───────────────────────────────────────────────

    /** Prix d'achat de {@code quantite} objets d'une vente directe (vente partielle comprise). */
    static double prixAchat(ItemNote n, int quantite) {
        return n.getPrice() / n.getItem().getAmount() * quantite;
    }

    boolean acheter(Player p, String noteId, int quantite) {
        ItemNote note = note(noteId);
        if (note == null) return refus(p, "Cette offre n'existe plus.");
        if (note.isBIDAuction()) return refus(p, "C'est une enchère : propose une offre.");
        if (note.getPlayerUUID().equals(p.getUniqueId())) return refus(p, "Tu ne peux pas acheter ta propre offre.");
        if (ConfigManager.bannedPlayers.checkIsBannedSendMessage(p)) return false;
        if (quantite < 1 || !SettingManager.partialSelling && quantite != note.getCurrentAmount()) quantite = note.getCurrentAmount();
        ItemStack item = note.getItem();
        item.setAmount(quantite);
        if (!place(p.getInventory(), item)) return refus(p, "Ton inventaire est plein.");
        if (!note.isTheoreticallyOnAuction() || note.getCurrentAmount() < quantite) return refus(p, "Cet objet vient d'être vendu.");
        if (note.isExpired()) return refus(p, "Cette offre a expiré.");
        if (note.isOnWaitingList()) return refus(p, "Cette offre n'est pas encore en vente.");
        double prix = prixAchat(note, quantite);
        if (eco.getBalance(p) < prix) return refus(p, "Il te manque " + Fmt.argent(prix - eco.getBalance(p)) + " BitBlocks.");
        if (!ItemNoteStorage.setSoldIfOnAuction(note, p, quantite, prix)) return refus(p, "Cet objet vient d'être vendu.");

        eco.withdrawPlayer(p, prix);
        p.getInventory().addItem(item.clone());
        String nom = Objets.nom(item);
        String vendeur = nomJoueur(note.getPlayerUUID(), note.getPlayerName());
        Fmt.envoyer(p, Fmt.bitblock("Tu as acheté &fx" + quantite + " " + nom + " " + Fmt.VIOLET + "pour", prix, false, " à &f" + vendeur));
        p.playSound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f);
        plugin.historique().enregistrer(new Historique.Vente(System.currentTimeMillis(), "vente", noteId, note.getPlayerUUID(), vendeur,
                p.getUniqueId(), p.getName(), Objets.cle(item), nom, quantite, prix, prix - net(prix), bytes(item), null));

        Player seller = Bukkit.getPlayer(note.getPlayerUUID());
        if (seller != null && seller.isOnline() && SettingManager.soldMessageEnabled) {
            if (SettingManager.autoCollect) {
                // Tout ce qui est vendu et pas encore encaissé (ventes partielles précédentes comprises).
                double recu = note.getSoldPrice();
                int vendus = note.getItem().getAmount() - note.getPartiallySoldAmountLeft();
                if (CollectSoldItemGUI.collect(seller, note.getNoteID(), vendus, recu)) {
                    Fmt.envoyer(seller, Fmt.bitblock("Tu as vendu &fx" + vendus + " " + nom + " " + Fmt.VIOLET + "pour", net(recu), true,
                            " à &f" + p.getName() + " &7(taxe " + Fmt.taux(taxe() * 100) + " %)"));
                }
            } else {
                Fmt.envoyer(seller, Fmt.VIOLET + "Ton offre &fx" + quantite + " " + nom + Fmt.VIOLET + " a été achetée par &f" + p.getName()
                        + Fmt.VIOLET + " : récupère tes BitBlocks dans Mes ventes (/hdv). " + Fmt.SOURCE);
            }
        }
        return true;
    }

    // ── Enchère (ConfirmBidGUI) ─────────────────────────────────────────────

    /** Offre minimale qu'un joueur peut proposer sur une enchère. */
    static double offreMin(ItemNote n) {
        return n.hasBidHistory() ? Bid.nextMinBid(n.getPrice()) : n.getPrice();
    }

    boolean encherir(Player p, String noteId, double montant) {
        ItemNote note = note(noteId);
        if (note == null) return refus(p, "Cette enchère n'existe plus.");
        if (!note.isBIDAuction()) return refus(p, "Ce n'est pas une enchère.");
        if (note.getPlayerUUID().equals(p.getUniqueId())) return refus(p, "Tu ne peux pas enchérir sur ta propre enchère.");
        if (Objects.equals(note.getLastBidder(), p.getUniqueId())) return refus(p, "Tu as déjà la meilleure offre.");
        if (ConfigManager.bannedPlayers.checkIsBannedSendMessage(p)) return false;
        if (!note.isTheoreticallyOnAuction()) return refus(p, "Cette enchère est terminée.");
        if (note.isExpired()) return refus(p, "Cette enchère est terminée.");
        if (offreMin(note) > montant) return refus(p, "Quelqu'un a enchéri avant toi : l'offre minimale est maintenant " + Fmt.argent(offreMin(note)) + ".");
        double hausse = montant - note.getBid(p);
        if (eco.getBalance(p) < hausse) return refus(p, "Il te manque " + Fmt.argent(hausse - eco.getBalance(p)) + " BitBlocks.");
        if (!ItemNoteStorage.addBidIfOnAuction(note, p, montant)) return refus(p, "Cette enchère n'existe plus.");

        eco.withdrawPlayer(p, hausse);
        String nom = Objets.nom(note.getItem());
        Fmt.envoyer(p, Fmt.bitblock("Tu as engagé", hausse, false, " sur &f" + nom + " &7(ton offre : " + Fmt.argent(montant) + ")"));
        p.playSound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f);
        Set<UUID> autres = note.getBidders();
        autres.remove(p.getUniqueId());
        for (UUID id : autres) {
            Player b = Bukkit.getPlayer(id);
            if (b == null) continue;
            b.sendMessage(Fmt.ligne(b, Fmt.VIOLET + "Ton offre sur &f" + nom + Fmt.VIOLET + " a été dépassée par &f" + p.getName()
                            + Fmt.VIOLET + " (" + Fmt.argent(montant) + " " + Fmt.BB + Fmt.VIOLET + "). &n" + Fmt.OR + "Clique pour surenchérir&r " + Fmt.SOURCE)
                    .clickEvent(ClickEvent.runCommand("/hdv view " + noteId)));
        }
        return true;
    }

    // ── Retrait (CancelAuctionGUI / AuctionViewGUI) ─────────────────────────

    boolean retirer(Player p, String noteId) {
        ItemNote note = note(noteId);
        if (note == null || !note.getPlayerUUID().equals(p.getUniqueId())) return refus(p, "Cette offre n'existe plus.");
        if (!place(p.getInventory(), note.getItem())) return refus(p, "Ton inventaire est plein.");
        if (note.isSold()) return refus(p, "Cet objet vient d'être vendu.");
        if (note.isBIDAuction() && note.hasBidHistory()) return refus(p, "Cette enchère a déjà reçu des offres : elle ne peut plus être retirée.");
        if (note.isExpired()) return recuperer(p, noteId);
        p.getInventory().addItem(note.getItem());
        ItemNoteStorage.deleteCancelNote(note);
        Fmt.envoyer(p, Fmt.VIOLET + "Offre retirée : &f" + Objets.nom(note.getItem()) + Fmt.VIOLET + " est revenu dans ton inventaire. " + Fmt.SOURCE);
        p.playSound(p, Sound.BLOCK_WOOD_BREAK, 0.7f, 1f);
        return true;
    }

    // ── Récupération du vendeur (CollectSoldItemGUI, CollectExpiredItemGUI) ──

    boolean recuperer(Player p, String noteId) {
        ItemNote note = note(noteId);
        if (note == null || !note.getPlayerUUID().equals(p.getUniqueId())) return refus(p, "Rien à récupérer ici.");
        Etat e = etat(note);
        String nom = Objets.nom(note.getItem());
        if (e == Etat.A_ENCAISSER) {
            int quantite = note.getItem().getAmount() - note.getPartiallySoldAmountLeft();
            double prix = note.getSoldPrice();
            String acheteur = nomJoueur(note.getBuyerUUID(), note.getBuyerName());
            if (!CollectSoldItemGUI.collect(p, note.getNoteID(), quantite, prix)) return refus(p, "Rien à récupérer ici.");
            Fmt.envoyer(p, Fmt.bitblock("Tu as vendu &fx" + quantite + " " + nom + " " + Fmt.VIOLET + "pour", net(prix), true,
                    " à &f" + acheteur + " &7(taxe " + Fmt.taux(taxe() * 100) + " %)"));
            p.playSound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f);
            return true;
        }
        if (e != Etat.EXPIREE && e != Etat.RETIREE_ADMIN) return refus(p, "Cette offre est toujours en vente.");
        ItemStack objet = note.getItem();
        boolean supprimee = e == Etat.RETIREE_ADMIN && objet.getType() == Material.DIRT && objet.isSimilar(me.elaineqheart.auctionHouse.data.ram.ItemManager.createDirt());
        if (!supprimee && !place(p.getInventory(), objet)) return refus(p, "Ton inventaire est plein.");
        boolean ok;
        if (e == Etat.RETIREE_ADMIN) {
            ok = supprimee ? ItemNoteStorage.collectAdminDeletedAuctionItem(note) : ItemNoteStorage.collectAdminExpiredAuctionItem(note);
        } else {
            ok = ItemNoteStorage.collectExpiredAuctionItem(note);
        }
        if (!ok) return refus(p, "Rien à récupérer ici.");
        if (supprimee) {
            Fmt.envoyer(p, Fmt.ROUGE + "Ton offre a été supprimée par la modération : &f" + note.getAdminMessage() + " " + Fmt.SOURCE);
            return true;
        }
        p.getInventory().addItem(objet);
        if (e == Etat.RETIREE_ADMIN) {
            Fmt.envoyer(p, Fmt.ROUGE + "Ton offre a été retirée par la modération (&f" + note.getAdminMessage() + Fmt.ROUGE + ") : l'objet t'est rendu. " + Fmt.SOURCE);
        } else {
            Fmt.envoyer(p, Fmt.VIOLET + "Offre expirée : &f" + nom + Fmt.VIOLET + " est revenu dans ton inventaire. " + Fmt.SOURCE);
        }
        p.playSound(p, Sound.ENTITY_ITEM_PICKUP, 0.7f, 1f);
        return true;
    }

    // ── Fin d'enchère côté enchérisseur (EndedAuctionGUI) ───────────────────

    boolean recupererEnchere(Player p, String noteId) {
        ItemNote note = note(noteId);
        if (note == null || !note.canClaimBid(p.getUniqueId())) return refus(p, "Rien à récupérer ici.");
        if (!note.isExpired()) return refus(p, "Cette enchère n'est pas terminée.");
        String nom = Objets.nom(note.getItem());
        boolean gagnant = Objects.equals(note.getLastBidder(), p.getUniqueId()) && note.getAdminMessage() == null;
        if (gagnant) {
            ItemStack item = note.getItem();
            if (!place(p.getInventory(), item)) return refus(p, "Ton inventaire est plein.");
            if (!ItemNoteStorage.claimEndedAuctionItem(p, note)) return refus(p, "Rien à récupérer ici.");
            p.getInventory().addItem(item);
            Fmt.envoyer(p, Fmt.VIOLET + "Enchère remportée : &f" + nom + Fmt.VIOLET + " est dans ton inventaire. " + Fmt.SOURCE);
            ConfigManager.transactionLogger.logTransaction(p.getDisplayName(), note.getPlayerName(), note.getItemName(),
                    note.getPrice(), item.getAmount(), true);
        } else {
            double rendu = note.getBid(p);
            if (!ItemNoteStorage.claimEndedAuctionItem(p, note)) return refus(p, "Rien à récupérer ici.");
            eco.depositPlayer(p, rendu);
            Fmt.envoyer(p, Fmt.bitblock("Tu as récupéré", rendu, true, " de ton offre sur &f" + nom));
        }
        p.playSound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f);
        return true;
    }

    /** Tout ce qui peut l'être, sans s'arrêter au premier refus (inventaire plein...). */
    int toutRecuperer(Player p) {
        int n = 0;
        for (ItemNote note : new ArrayList<>(mesVentes(p.getUniqueId()))) {
            Etat e = etat(note);
            if ((e == Etat.A_ENCAISSER || e == Etat.EXPIREE || e == Etat.RETIREE_ADMIN) && recuperer(p, note.getNoteID().toString())) n++;
        }
        for (ItemNote note : new ArrayList<>(mesEncheres(p.getUniqueId()))) {
            if (note.isExpired() && recupererEnchere(p, note.getNoteID().toString())) n++;
        }
        return n;
    }

    // ── Mise en vente (commande sell / bid) ─────────────────────────────────

    /**
     * Met en vente {@code quantite} objets de la case {@code slot} de l'inventaire. {@code attendu}
     * est l'objet vu au moment du choix : si la case a changé entre-temps, rien n'est vendu.
     */
    boolean vendre(Player p, int slot, ItemStack attendu, int quantite, long prix, boolean enchere) {
        if (enchere && !SettingManager.BIDAuctions) return refus(p, "Les enchères sont désactivées.");
        if (!enchere && !SettingManager.BINAuctions) return refus(p, "La vente directe est désactivée.");
        if (ConfigManager.bannedPlayers.checkIsBannedSendMessage(p)) return false;
        int max = emplacements(p);
        if (ventesEnCours(p) >= max) {
            return refus(p, max <= 0 ? "Ton rang ne permet pas encore de vendre à l'hôtel des ventes (dès Citoyen)."
                    : "Tu as atteint ta limite de " + max + " offres en cours. Retire ou récupère une offre d'abord.");
        }
        ItemStack item = p.getInventory().getItem(slot);
        if (item == null || item.getType().isAir() || attendu == null || !item.isSimilar(attendu)) return refus(p, "Choisis d'abord un objet de ton inventaire.");
        if (quantite < 1 || quantite > item.getAmount()) return refus(p, "Quantité invalide : tu as " + item.getAmount() + " objet(s) dans cette case.");
        double min = enchere ? SettingManager.minBIDPrice : SettingManager.minBINPrice;
        double maxPrix = enchere ? SettingManager.maxBIDPrice : SettingManager.maxBINPrice;
        if (prix < min) return refus(p, "Prix minimum : " + Fmt.argent(min) + ".");
        if (maxPrix > -1 && prix > maxPrix) return refus(p, "Prix maximum : " + Fmt.argent(maxPrix) + ".");
        if (Blacklist.isBlacklisted(item)) {
            p.playSound(p, Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 0.1f);
            return refus(p, "Cet objet ne peut pas être vendu à l'hôtel des ventes.");
        }
        ItemStack vendu = item.clone();
        vendu.setAmount(quantite);
        int reste = item.getAmount() - quantite;
        if (reste <= 0) p.getInventory().setItem(slot, null);
        else item.setAmount(reste);
        ItemNoteStorage.createNote(p, vendu, prix, enchere);
        Fmt.envoyer(p, Fmt.VIOLET + (enchere ? "Enchère ouverte" : "Mise en vente") + " : &fx" + quantite + " " + Objets.nom(vendu) + Fmt.VIOLET
                + (enchere ? " à partir de &f" : " pour &f") + Fmt.argent(prix) + " " + Fmt.BB + Fmt.VIOLET + ". En ligne dans "
                + SettingManager.auctionSetupTime + " s, pendant " + Fmt.duree(duree(p, enchere)) + ". " + Fmt.SOURCE);
        p.playSound(p, Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 1.4f);
        return true;
    }

    // ── Enchères terminées (historique) ─────────────────────────────────────

    /** Enregistre une fois chaque enchère terminée avec un gagnant (balayage régulier). */
    void enregistrerEncheresTerminees() {
        for (ItemNote n : AuctionHouseStorage.getAll()) {
            if (n == null || !n.isBIDAuction() || !n.hasBidHistory() || !n.isExpired() || n.getAdminMessage() != null) continue;
            if (!plugin.historique().premiereFois(n.getNoteID().toString())) continue;
            ItemStack item = n.getItem();
            double prix = n.getPrice();
            plugin.historique().enregistrer(new Historique.Vente(System.currentTimeMillis(), "enchere", n.getNoteID().toString(),
                    n.getPlayerUUID(), nomJoueur(n.getPlayerUUID(), n.getPlayerName()), n.getLastBidder(),
                    nomJoueur(n.getLastBidder(), n.getLastBidderName()), Objets.cle(item), Objets.nom(item), item.getAmount(),
                    prix, prix - net(prix), bytes(item), null));
        }
    }

    // ── Outils ──────────────────────────────────────────────────────────────

    static byte[] bytes(ItemStack item) {
        try {
            return item.serializeAsBytes();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Vrai si l'objet entier tient dans l'inventaire (cases libres et piles compatibles). */
    static boolean place(PlayerInventory inv, ItemStack item) {
        int reste = item.getAmount();
        for (ItemStack s : inv.getStorageContents()) {
            if (s == null || s.getType().isAir()) reste -= item.getMaxStackSize();
            else if (s.isSimilar(item)) reste -= Math.max(0, s.getMaxStackSize() - s.getAmount());
            if (reste <= 0) return true;
        }
        return false;
    }

    private static boolean refus(Player p, String texte) {
        Fmt.erreur(p, texte);
        p.playSound(p, Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
        return false;
    }
}

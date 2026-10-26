package fr.influencraft.fluxitems;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Progression d'un outil, écrite dans l'objet lui-même (PersistentDataContainer) : l'outil garde son
 * histoire s'il est échangé, vendu ou rangé. Aucune base de données.
 */
final class Etat {

    private static NamespacedKey OUTIL, UID, ETAPE, QUETE, AVANCE, DISPO, JALONS, BLOCS, TUES, QUETES, GAGNE, XP, RENDU;

    static void init(Plugin p) {
        OUTIL = new NamespacedKey(p, "outil");
        UID = new NamespacedKey(p, "uid");
        ETAPE = new NamespacedKey(p, "etape");
        QUETE = new NamespacedKey(p, "quete");
        AVANCE = new NamespacedKey(p, "avance");
        DISPO = new NamespacedKey(p, "dispo");
        JALONS = new NamespacedKey(p, "jalons");
        BLOCS = new NamespacedKey(p, "blocs");
        TUES = new NamespacedKey(p, "tues");
        QUETES = new NamespacedKey(p, "quetes");
        GAGNE = new NamespacedKey(p, "gagne");
        XP = new NamespacedKey(p, "xp");
        RENDU = new NamespacedKey(p, "rendu");
    }

    String outil;
    String uid;
    /** Nombre d'étapes (quêtes) réussies. */
    int etape;
    /** Quête tirée pour l'étape en cours. */
    String quete;
    int avance;
    /** Instant (ms) où l'étape en cours devient disponible, 0 = tout de suite. */
    long dispo;
    /** Jalons déjà payés dans l'étape en cours. */
    int jalons;
    long blocs;
    long tues;
    int quetes;
    double gagne;
    /** XP d'outil cumulée : le niveau s'en déduit (seuils de l'outil). */
    double xp;

    /** Identifiant d'outil FluxItems porté par l'objet, ou null. */
    static String outil(ItemStack item) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(OUTIL, PersistentDataType.STRING);
    }

    static Etat lire(ItemStack item) {
        String id = outil(item);
        if (id == null) return null;
        PersistentDataContainer c = item.getItemMeta().getPersistentDataContainer();
        Etat e = new Etat();
        e.outil = id;
        e.uid = c.getOrDefault(UID, PersistentDataType.STRING, "");
        e.etape = c.getOrDefault(ETAPE, PersistentDataType.INTEGER, 0);
        e.quete = c.getOrDefault(QUETE, PersistentDataType.STRING, "");
        e.avance = c.getOrDefault(AVANCE, PersistentDataType.INTEGER, 0);
        e.dispo = c.getOrDefault(DISPO, PersistentDataType.LONG, 0L);
        e.jalons = c.getOrDefault(JALONS, PersistentDataType.INTEGER, 0);
        e.blocs = c.getOrDefault(BLOCS, PersistentDataType.LONG, 0L);
        e.tues = c.getOrDefault(TUES, PersistentDataType.LONG, 0L);
        e.quetes = c.getOrDefault(QUETES, PersistentDataType.INTEGER, 0);
        e.gagne = c.getOrDefault(GAGNE, PersistentDataType.DOUBLE, 0.0);
        e.xp = c.getOrDefault(XP, PersistentDataType.DOUBLE, 0.0);
        return e;
    }

    void ecrire(ItemMeta meta) {
        PersistentDataContainer c = meta.getPersistentDataContainer();
        c.set(OUTIL, PersistentDataType.STRING, outil);
        c.set(UID, PersistentDataType.STRING, uid);
        c.set(ETAPE, PersistentDataType.INTEGER, etape);
        c.set(QUETE, PersistentDataType.STRING, quete == null ? "" : quete);
        c.set(AVANCE, PersistentDataType.INTEGER, avance);
        c.set(DISPO, PersistentDataType.LONG, dispo);
        c.set(JALONS, PersistentDataType.INTEGER, jalons);
        c.set(BLOCS, PersistentDataType.LONG, blocs);
        c.set(TUES, PersistentDataType.LONG, tues);
        c.set(QUETES, PersistentDataType.INTEGER, quetes);
        c.set(GAGNE, PersistentDataType.DOUBLE, gagne);
        c.set(XP, PersistentDataType.DOUBLE, xp);
    }

    /** Empreinte de config avec laquelle la description de l'objet a été écrite. */
    static int rendu(ItemStack item) {
        return item.getItemMeta().getPersistentDataContainer().getOrDefault(RENDU, PersistentDataType.INTEGER, 0);
    }

    static void marquerRendu(ItemMeta meta, int version) {
        meta.getPersistentDataContainer().set(RENDU, PersistentDataType.INTEGER, version);
    }

    boolean termine(Outil o) {
        return etape >= o.etapes.size();
    }

    boolean enAttente() {
        return dispo > System.currentTimeMillis();
    }

    /** Quête active (disponible et pas terminée), ou null. */
    Outil.Quete queteActive(Outil o) {
        if (termine(o) || enAttente()) return null;
        return o.etapes.get(etape).quete(quete);
    }
}

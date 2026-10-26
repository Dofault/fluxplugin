package fr.influencraft.fluxtools;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static fr.influencraft.fluxtools.Fmt.*;

/** Fabrication d'un outil et réécriture de son nom, de sa description et de ses enchantements. */
final class Rendu {

    /** Enchantements posés à l'enclume par le joueur, que la réécriture ne retire jamais. */
    static final List<String> LIVRES = List.of("incassable", "soulbound");

    private static final Random HASARD = new Random();

    /** Empreinte de la config en cours ({@link FluxTools}), écrite sur chaque description rendue. */
    static int version;

    private Rendu() {
    }

    static ItemStack creer(Outil o) {
        ItemStack item = ItemStack.of(o.materiau);
        Etat e = new Etat();
        e.outil = o.id;
        e.uid = UUID.randomUUID().toString();
        e.quete = tirer(o.etapes.get(0));
        item.editMeta(meta -> {
            if (o.durabilite > 0 && meta instanceof Damageable d) d.setMaxDamage(o.durabilite);
            e.ecrire(meta);
            enchanter(meta, o, e);
            decorer(meta, o, e);
        });
        return item;
    }

    static String tirer(Outil.Etape etape) {
        if (etape.quetes().isEmpty()) return "";
        return etape.quetes().get(HASARD.nextInt(etape.quetes().size())).id();
    }

    /** Écrit l'état dans l'objet et réécrit sa description ; {@code enchants} : réapplique aussi le niveau. */
    static void maj(ItemStack item, Outil o, Etat e, boolean enchants) {
        item.editMeta(meta -> {
            e.ecrire(meta);
            if (enchants) enchanter(meta, o, e);
            decorer(meta, o, e);
        });
    }

    static void enchanter(ItemMeta meta, Outil o, Etat e) {
        for (Enchantment ench : List.copyOf(meta.getEnchants().keySet())) {
            if (!LIVRES.contains(ench.getKey().getKey())) meta.removeEnchant(ench);
        }
        for (Map.Entry<String, Integer> en : o.enchantements.entrySet()) {
            Enchantment ench = Outil.enchantement(en.getKey());
            if (ench != null && en.getValue() > 0) meta.addEnchant(ench, en.getValue(), true);
        }
        int niveau = o.niveau(e.xp);
        for (String id : o.progressifs.keySet()) {
            Enchantment ench = Outil.enchantement(id);
            int v = o.valeur(id, niveau);
            if (ench != null && v > 0) meta.addEnchant(ench, v, true);
        }
    }

    private static void decorer(ItemMeta meta, Outil o, Etat e) {
        Etat.marquerRendu(meta, version);
        // Posé à chaque rendu : les outils déjà distribués prennent le modèle à leur prochaine réécriture.
        meta.setItemModel(o.modele);
        int niveau = o.niveau(e.xp);
        meta.customName(objet(o.aNiveaux() ? o.nom + " &7niv &f" + niveau : o.nom));
        List<Component> lore = new ArrayList<>();
        if (o.aNiveaux()) {
            // Affichage d'origine (vanilla et EcoEnchants) masqué : une ligne par enchantement, à sa place.
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            lore.addAll(lignesEnchants(meta, o, e));
        }
        for (String l : o.description) lore.add(objet(l));
        if (!o.reparable) lore.add(objet(KO + "Irréparable"));
        lore.add(Component.empty());

        Outil.Quete q = e.termine(o) ? null : o.etapes.get(e.etape).quete(e.quete);
        if (o.mode == Outil.Mode.PASS) {
            lore.add(objet(TITRE + "Quête bonus &f" + Math.min(e.etape + 1, o.etapes.size()) + "&7/&f" + o.etapes.size()));
        } else if (o.etapes.size() > 1) {
            lore.add(objet(TITRE + "Contrat &f" + Math.min(e.etape + 1, o.etapes.size()) + "&7/&f" + o.etapes.size()));
        } else {
            lore.add(objet(TITRE + "Contrat"));
        }
        if (e.termine(o)) {
            lore.add(objet(OK + (o.mode == Outil.Mode.PASS ? "Toutes les quêtes bonus sont réussies." : "Contrat rempli.")));
        } else if (e.enAttente()) {
            lore.add(objet(TEXTE + "Prochaine quête le &f" + new SimpleDateFormat("dd/MM 'à' H'h'", Locale.FRANCE).format(new Date(e.dispo))));
        } else if (q != null) {
            for (String l : couper(TEXTE + q.libelle(), LARGEUR)) lore.add(objet(l));
            lore.add(objet(barre(e.avance, q.quantite()) + " &f" + nombre(e.avance) + "&7/&f" + nombre(q.quantite())));
        }

        lore.add(Component.empty());
        boolean arme = "epee".equals(o.famille) || "arc".equals(o.famille) || "masse".equals(o.famille);
        lore.add(objet("&8" + (arme ? "Monstres vaincus : " + nombre(e.tues) : "Blocs : " + nombre(e.blocs))
                + " &8· Quêtes : " + e.quetes));
        lore.add(raccourci());
        meta.lore(lore);
    }

    /** « Efficacité III/VII » : niveau de l'enchantement débloqué / maximum, rien d'autre. */
    static List<Component> lignesEnchants(ItemMeta meta, Outil o, Etat e) {
        List<Component> l = new ArrayList<>();
        int niveau = o.niveau(e.xp);
        for (String id : o.progressifs.keySet()) {
            Enchantment ench = Outil.enchantement(id);
            if (ench != null) l.add(ligne(ench, " " + romain(o.valeur(id, niveau)) + "/" + romain(o.valeurMax(id))));
        }
        for (Enchantment ench : meta.getEnchants().keySet()) {
            if (LIVRES.contains(ench.getKey().getKey())) l.add(ligne(ench, ""));
        }
        return l;
    }

    private static Component ligne(Enchantment ench, String fin) {
        return Component.text().color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                .append(Component.text().color(NamedTextColor.GRAY).append(ench.description()))
                .append(texte("&7" + fin)).build();
    }

    /**
     * « Accroupi + [clic droit] Ouvrir l'outil » : texte fixe et icône clic droit du pack (U+E022), pas
     * les noms de touches du client, dont la longueur varie avec sa langue et ses réglages.
     */
    private static Component raccourci() {
        return objet("&r&7Accroupi + \uE022 " + TITRE + "Ouvrir l'outil");
    }
}

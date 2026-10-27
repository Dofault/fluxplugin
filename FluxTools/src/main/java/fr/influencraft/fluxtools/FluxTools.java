package fr.influencraft.fluxtools;

import fr.influencraft.fluxtools.modules.Modules;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * FluxTools : outils de progression des caisses de vote. Chaque outil porte sa progression (contrat ou
 * pass de 7 jours), ses statistiques et ses enchantements, fixés par son niveau. Interface : menus
 * Flux, alimentés par nos placeholders et la source d'objet {@code fluxitems}.
 */
public final class FluxTools extends fr.influencraft.flux.api.FluxPlugin {

    private Map<String, Outil> outils = Map.of();
    private Set<String> materiaux = Set.of();
    private final Set<String> mondesExclus = new HashSet<>();
    private Economy eco;
    private Progression progression;
    private final Apparences apparences = new Apparences(this);
    private final Parures parures = new Parures(this);

    @Override
    protected java.util.List<String> formerNames() {
        return java.util.List.of("FluxItems");
    }

    @Override
    protected void enable() {
        // utility modules (ex-InfluBridge) do not need the crate tools, start them first
        new Modules(this).enable();
        Etat.init(this);
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            getLogger().severe("No Vault economy: crate tools disabled, the other modules keep running.");
            return;
        }
        eco = rsp.getProvider();
        charger();

        progression = new Progression(this);
        Bukkit.getPluginManager().registerEvents(progression, this);
        Bukkit.getPluginManager().registerEvents(new Verrous(this), this);
        Bukkit.getPluginManager().registerEvents(new Ouverture(this), this);
        Bukkit.getPluginManager().registerEvents(new Rafraichissement(this), this);
        Bukkit.getPluginManager().registerEvents(apparences, this);
        Bukkit.getPluginManager().registerEvents(parures, this);
        Commande cmd = new Commande(this);
        command("tool")
                .description("Opens the menu of the tool in hand.")
                .aliases("outil")
                .executor((sender, label, args) -> {
                    if (sender instanceof Player p) ouvrir(p);
                    return true;
                })
                .register();
        command("tools")
                .description("Admin of the crate tools.")
                .aliases("fluxitems")
                .permission("fluxitems.admin")
                .executor(cmd::run)
                .completer(cmd::complete)
                .register();

        placeholders("fluxitems", new Expansion(this)::request);
        Sources.inscrire(this);
    }

    @Override
    protected void disable() {
        parures.arreter();
    }

    void charger() {
        reloadConfig();
        mondesExclus.clear();
        for (String m : getConfig().getStringList("mondes-exclus")) mondesExclus.add(m.toLowerCase(Locale.ROOT));
        Map<String, Outil> lus = new LinkedHashMap<>();
        File dossier = new File(getDataFolder(), "outils");
        if (!dossier.isDirectory() && !dossier.mkdirs()) getLogger().warning("Dossier outils/ impossible à créer.");
        File[] fichiers = dossier.listFiles((d, n) -> n.endsWith(".yml") && !n.startsWith("_"));
        if (fichiers != null) {
            java.util.Arrays.sort(fichiers);
            for (File f : fichiers) {
                Outil o = Outil.charger(f, getLogger());
                if (o != null) lus.put(o.id, o);
            }
        }
        Set<String> mats = new HashSet<>();
        for (Outil o : lus.values()) mats.add(o.materiau.getKey().getKey());
        outils = lus;
        materiaux = mats;
        Rendu.version = version(dossier);
        getLogger().info(outils.size() + " outils chargés.");
        apparences.charger();
        parures.charger(apparences.reglagesParures());
    }

    /**
     * Empreinte du jar, de config.yml et des fichiers d'outils : un outil dont la description a été écrite avec
     * une autre empreinte est réécrit à sa prochaine rencontre (cf. {@link Rafraichissement}).
     */
    private int version(File dossier) {
        // Le jar compte aussi : une nouvelle version du rendu (code) réécrit les outils une fois.
        int h = Long.hashCode(getFile().lastModified());
        try {
            h = 31 * h + java.util.Arrays.hashCode(java.nio.file.Files.readAllBytes(new File(getDataFolder(), "config.yml").toPath()));
            File[] fichiers = dossier.listFiles((d, n) -> n.endsWith(".yml"));
            if (fichiers != null) {
                java.util.Arrays.sort(fichiers);
                for (File f : fichiers) h = 31 * h + java.util.Arrays.hashCode(java.nio.file.Files.readAllBytes(f.toPath()));
            }
        } catch (java.io.IOException ex) {
            getLogger().warning("Empreinte de config illisible : " + ex.getMessage());
        }
        return h;
    }

    /** Matériau d'un de nos outils (filtre rapide avant de lire un objet). */
    boolean materiau(String cle) {
        return materiaux.contains(cle);
    }

    Map<String, Outil> outils() {
        return Collections.unmodifiableMap(outils);
    }

    /** Définition de l'outil FluxTools porté par l'objet, ou null. */
    Outil outil(ItemStack item) {
        String id = Etat.outil(item);
        return id == null ? null : outils.get(id);
    }

    boolean exclu(World w) {
        return mondesExclus.contains(w.getName().toLowerCase(Locale.ROOT));
    }

    Economy economie() {
        return eco;
    }

    Progression progression() {
        return progression;
    }

    Apparences apparences() {
        return apparences;
    }

    /** Raw text of the language file. */
    String texte(String cle) {
        return messages().raw(cle);
    }

    void message(Player p, String cle, String... remplacements) {
        messages().send(p, cle, remplacements);
    }

    /** Opens the Flux menu of the tool in hand. */
    void ouvrir(Player p) {
        if (outil(p.getInventory().getItemInMainHand()) == null) {
            message(p, "pas-d-outil");
            return;
        }
        fr.influencraft.flux.api.FluxApi.open(p, getConfig().getString("menu", "outil"));
    }
}

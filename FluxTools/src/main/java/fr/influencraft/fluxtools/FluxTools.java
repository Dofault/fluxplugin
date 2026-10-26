package fr.influencraft.fluxtools;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

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
public final class FluxTools extends JavaPlugin {

    private Map<String, Outil> outils = Map.of();
    private Set<String> materiaux = Set.of();
    private final Set<String> mondesExclus = new HashSet<>();
    private Economy eco;
    private Progression progression;
    private final Apparences apparences = new Apparences(this);
    private final Parures parures = new Parures(this);

    @Override
    public void onLoad() {
        LegacyDataFolder.migrate(this, "FluxItems");
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Etat.init(this);
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            getLogger().severe("Aucune économie Vault : FluxTools désactivé.");
            Bukkit.getPluginManager().disablePlugin(this);
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
        getCommand("fluxitems").setExecutor(cmd);
        getCommand("fluxitems").setTabCompleter(cmd);
        getCommand("outil").setExecutor(cmd);

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) new Expansion(this).register();
        if (Bukkit.getPluginManager().isPluginEnabled("Flux")) Sources.inscrire(this);
    }

    @Override
    public void onDisable() {
        parures.arreter();
        if (Bukkit.getPluginManager().isPluginEnabled("Flux")) Sources.retirer();
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

    String texte(String cle) {
        return getConfig().getString("messages." + cle, cle);
    }

    void message(Player p, String cle, String... remplacements) {
        String t = texte(cle);
        for (int i = 0; i + 1 < remplacements.length; i += 2) t = t.replace(remplacements[i], remplacements[i + 1]);
        p.sendMessage(Fmt.texte(texte("prefixe") + t));
    }

    /** Ouvre le menu Flux de l'outil tenu en main. */
    void ouvrir(Player p) {
        if (outil(p.getInventory().getItemInMainHand()) == null) {
            message(p, "pas-d-outil");
            return;
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "fluxmenu open " + getConfig().getString("menu", "outil") + " " + p.getName());
    }
}

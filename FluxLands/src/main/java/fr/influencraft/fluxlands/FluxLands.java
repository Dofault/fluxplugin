package fr.influencraft.fluxlands;

import me.angeschossen.lands.api.events.LandDeleteEvent;
import me.angeschossen.lands.api.land.Land;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * FluxLands : extension de Lands qui unifie les villes, les adresses (InfluPW) et les
 * échoppes (FluxChestShop). Fournit les placeholders {@code %influlands_...%} et la commande
 * {@code /influlands} lus et appelés par les menus Flux (menus/ville*.yml,
 * adresses.yml, echoppes.yml). InfluPW et FluxChestShop sont facultatifs : sans eux, les
 * listes correspondantes sont simplement vides.
 */
public final class FluxLands extends JavaPlugin implements Listener {

    private Villes villes;
    private Registre registre;
    private Vues vues;
    private Tetes tetes;
    private Saisie saisie;
    private Gestion gestion;
    private Pays pays;
    private Commande commande;
    private Interception interception;
    private ModuleQuickShop quickShop;
    private ModulePlayerWarps playerWarps;
    private volatile Map<String, int[]> comptes = Map.of();

    @Override
    public void onLoad() {
        LegacyDataFolder.migrate(this, "InfluLands");
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        villes = new Villes(this);
        villes.recharger();
        Couleurs.charger(Bukkit.getPluginManager().getPlugin("Lands"));
        registre = new Registre(this);
        registre.charger();
        vues = new Vues(this);
        tetes = new Tetes();
        saisie = new Saisie(this);
        gestion = new Gestion(this);
        pays = new Pays(this);
        interception = new Interception(this);

        if (Bukkit.getPluginManager().isPluginEnabled("FluxChestShop")) {
            RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
            if (rsp == null) {
                getLogger().warning("Aucune économie Vault : droit de place désactivé, échoppes listées quand même.");
            }
            quickShop = new ModuleQuickShop(this, rsp != null ? rsp.getProvider() : null);
            quickShop.chargerStocks();
            if (rsp != null) Bukkit.getPluginManager().registerEvents(quickShop, this);
            getLogger().info("FluxChestShop trouvé : échoppes et droit de place actifs.");
        } else {
            getLogger().info("FluxChestShop absent : pas d'échoppes ni de droit de place.");
        }
        if (Bukkit.getPluginManager().isPluginEnabled("InfluPW")) {
            playerWarps = new ModulePlayerWarps(this);
            getLogger().info("InfluPW trouvé : warps actifs.");
        } else {
            getLogger().info("InfluPW absent : pas de warps.");
        }

        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(interception, this);
        Commande cmd = new Commande(this);
        commande = cmd;
        var pc = getCommand("influlands");
        if (pc != null) {
            pc.setExecutor(cmd);
            pc.setTabCompleter(cmd);
        }
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new Expansion(this).register();
        } else {
            getLogger().warning("PlaceholderAPI absent : les menus ne pourront rien afficher.");
        }
        planifier();
    }

    private void planifier() {
        Bukkit.getScheduler().cancelTasks(this);
        // Lands charge ses villes après le démarrage : premier index une fois Lands prêt.
        villes.api().onLoad(this::indexer);
        long index = Math.max(5, getConfig().getInt("index-secondes", 30)) * 20L;
        Bukkit.getScheduler().runTaskTimer(this, this::indexer, 100L, index);
        long resume = getConfig().getInt("droit-de-place.resume-secondes", 60) * 20L;
        if (resume > 0 && quickShop != null) Bukkit.getScheduler().runTaskTimer(this, quickShop::envoyerResumes, resume, resume);
        long save = Math.max(1, getConfig().getInt("sauvegarde-minutes", 5)) * 1200L;
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            registre.sauvegarder(false);
            if (quickShop != null) quickShop.sauvegarderStocks();
        }, save, save);
        // Parcelle en cours de sélection : la saisie du nom s'ouvre dès que la sélection est complète.
        Bukkit.getScheduler().runTaskTimer(this, () -> { if (commande != null) commande.verifierSelections(); }, 20L, 10L);
    }

    @Override
    public void onDisable() {
        if (quickShop != null) quickShop.envoyerResumes();
        if (registre != null) registre.sauvegarder(true);
        if (quickShop != null) quickShop.sauvegarderStocks();
    }

    void recharger() {
        reloadConfig();
        villes.recharger();
        Couleurs.charger(Bukkit.getPluginManager().getPlugin("Lands"));
        interception.recharger();
        planifier();
    }

    /** Index des échoppes et adresses, puis compte par ville. */
    void indexer() {
        try {
            if (quickShop != null) quickShop.indexer();
            if (playerWarps != null) playerWarps.indexer();
        } catch (RuntimeException | LinkageError e) {
            getLogger().warning("Index des échoppes / adresses en échec : " + e);
        }
        Map<String, int[]> c = new HashMap<>();
        // Seules les échoppes actives comptent (fiches, annuaire) : une échoppe vide n'a pas d'intérêt public.
        for (Fiches.Echoppe s : echoppes()) if (!s.villeId().isEmpty() && s.active()) c.computeIfAbsent(s.villeId(), k -> new int[2])[0]++;
        for (Fiches.Adresse a : adresses()) if (!a.villeId().isEmpty() && a.ouverte()) c.computeIfAbsent(a.villeId(), k -> new int[2])[1]++;
        comptes = c;
        if (playerWarps != null) {
            for (Land land : villes.toutes()) registre.nettoyerVitrine(Villes.id(land), id -> playerWarps.parId(id) != null);
        }
    }

    // ── Accès ───────────────────────────────────────────────────────────────

    Villes villes() {
        return villes;
    }

    Registre registre() {
        return registre;
    }

    Vues vues() {
        return vues;
    }

    Tetes tetes() {
        return tetes;
    }

    Saisie saisie() {
        return saisie;
    }

    Gestion gestion() {
        return gestion;
    }

    Pays pays() {
        return pays;
    }

    Interception interception() {
        return interception;
    }

    boolean aEchoppes() {
        return quickShop != null;
    }

    boolean aAdresses() {
        return playerWarps != null;
    }

    List<Fiches.Echoppe> echoppes() {
        return quickShop != null ? quickShop.toutes() : List.of();
    }

    List<Fiches.Adresse> adresses() {
        return playerWarps != null ? playerWarps.toutes() : List.of();
    }

    Fiches.Adresse adresse(String id) {
        return playerWarps != null ? playerWarps.parId(id) : null;
    }

    /** ville -> {échoppes, adresses ouvertes}. */
    Map<String, int[]> comptes() {
        return comptes;
    }

    /** Adresse ouverte la plus proche d'une échoppe, dans la même ville. */
    Fiches.Adresse adresseProche(Fiches.Echoppe s) {
        if (s.villeId().isEmpty() || s.lieu() == null) return null;
        Fiches.Adresse best = null;
        double d = Double.MAX_VALUE;
        for (Fiches.Adresse a : adresses()) {
            if (!a.ouverte() || !s.villeId().equals(a.villeId()) || a.lieu().getWorld() != s.lieu().getWorld()) continue;
            double x = a.lieu().distanceSquared(s.lieu());
            if (x < d) {
                d = x;
                best = a;
            }
        }
        return best;
    }

    /** Champs d'une ville pour les placeholders ({@code ma_*}, {@code fiche_*}, lignes de l'annuaire). */
    Map<String, String> champsVille(Land land, Player viewer) {
        Map<String, String> m = new HashMap<>();
        if (land == null) {
            m.put("existe", "non");
            return m;
        }
        String id = Villes.id(land);
        int[] c = comptes.getOrDefault(id, new int[2]);
        boolean membre = viewer != null && land.isTrusted(viewer.getUniqueId());
        boolean maire = viewer != null && Villes.estMaire(land, viewer.getUniqueId());
        Registre.Recette r = registre.recette(id);
        int niveau = Villes.niveau(land);
        m.put("existe", "oui");
        m.put("id", id);
        m.put("nom", land.getName());
        m.put("nom_couleur", Couleurs.legacy(land.getColorName()));
        m.put("niveau", Villes.nomNiveau(land));
        m.put("niveau_num", String.valueOf(niveau));
        m.put("icone", "itemsadder:influville:niveau_" + niveau);
        m.put("habitants", String.valueOf(land.getTrustedPlayers().size()));
        m.put("en_ligne", String.valueOf(land.getOnlinePlayers().size()));
        m.put("chunks", String.valueOf(land.getChunksAmount()));
        m.put("chunks_max", String.valueOf(land.getMaxChunks()));
        m.put("solde", membre ? Fmt.argent(land.getBalance()) : "");
        m.put("entretien", Fmt.argent(land.getUpkeepCosts()));
        String nation = Villes.nation(land);
        m.put("nation", nation.isEmpty() ? "Aucune" : nation);
        m.put("a_nation", Fmt.oui(!nation.isEmpty()));
        m.put("maire", Villes.nomJoueur(land.getOwnerUID()));
        m.put("taux", Fmt.taux(registre.taux(id)));
        m.put("echoppes", String.valueOf(c[0]));
        m.put("adresses", String.valueOf(c[1]));
        m.put("recette_jour", Fmt.argent(r.jour(java.time.LocalDate.now())));
        m.put("recette_7j", Fmt.argent(r.depuis(7)));
        m.put("recette_total", Fmt.argent(r.total));
        m.put("transactions", String.valueOf(r.transactions));
        m.put("membre", Fmt.oui(membre));
        m.put("est_maire", Fmt.oui(maire));
        m.put("role", membre ? Villes.role(land, viewer.getUniqueId()) : "");
        m.put("spawn", Fmt.oui(land.getSpawnPosition() != null));
        m.put("creation", new SimpleDateFormat("dd/MM/yyyy").format(new Date(land.getCreationTime())));
        m.put("vitrine", String.valueOf(registre.vitrine(id).size()));
        m.put("vitrine_max", String.valueOf(getConfig().getInt("vitrine.max-adresses", 3)));
        return m;
    }

    Commande commande() {
        return commande;
    }

    ModuleQuickShop quickShop() {
        return quickShop;
    }

    /** Ouvre un menu Flux (clé de config {@code menus.*} ou identifiant direct). */
    void ouvrirMenu(Player p, String cleOuId) {
        if (cleOuId == null || cleOuId.isEmpty() || !p.isOnline()) return;
        String id = getConfig().getString("menus." + cleOuId, cleOuId);
        if (Bukkit.getPluginManager().isPluginEnabled("Flux")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "fluxmenu open " + id + " " + p.getName());
        } else {
            getLogger().warning("Flux absent : menu « " + id + " » non ouvert");
        }
    }

    // ── Événements ──────────────────────────────────────────────────────────

    @EventHandler
    public void quitter(PlayerQuitEvent e) {
        vues.oublier(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void villeSupprimee(LandDeleteEvent e) {
        registre.oublierVille(Villes.id(e.getLand()));
    }
}

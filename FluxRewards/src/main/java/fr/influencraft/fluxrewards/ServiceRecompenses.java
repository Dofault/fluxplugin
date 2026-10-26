package fr.influencraft.fluxrewards;

import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Récompenses de connexion quotidienne et de temps de jeu.
 *
 * <p><b>Connexion</b> : chaque jour de connexion fait avancer d'un jour dans un cycle (28 jours par
 * défaut) ; la récompense du jour se récupère dans le menu, le jour même. Un jour non récupéré est
 * manqué. Avec {@code serie-stricte}, un jour d'absence ramène au jour 1.</p>
 * <p><b>Temps de jeu</b> : paliers de temps de jeu total (statistique du serveur), chacun récupérable
 * une fois, sans limite de date.</p>
 *
 * <p>Tout se passe sur le thread principal ; seules les écritures de fichiers sont asynchrones.</p>
 */
public final class ServiceRecompenses {

    public enum Etat { RECUPERE, DISPONIBLE, MANQUE, VERROUILLE }

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder().character('&').hexCharacter('#').hexColors().build();

    private final FluxRewards plugin;
    private final Map<UUID, DonneesJoueur> joueurs = new ConcurrentHashMap<>();
    private final File dossier;

    private ZoneId fuseau;
    private int cycle;
    private boolean serieStricte;
    private List<Palier> jours = List.of();
    private List<Palier> temps = List.of();

    public ServiceRecompenses(FluxRewards plugin) {
        this.plugin = plugin;
        this.dossier = new File(plugin.getDataFolder(), "joueurs");
    }

    // ====================================================================
    // Configuration
    // ====================================================================

    public void charger() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration c = plugin.getConfig();
        try {
            fuseau = ZoneId.of(c.getString("fuseau-horaire", "Europe/Paris"));
        } catch (RuntimeException e) {
            plugin.getLogger().warning("fuseau-horaire invalide, Europe/Paris retenu");
            fuseau = ZoneId.of("Europe/Paris");
        }
        serieStricte = c.getBoolean("connexion.serie-stricte", false);

        List<Palier> lusJours = lire(c.getConfigurationSection("connexion.jours"), false);
        cycle = Math.max(1, c.getInt("connexion.cycle", lusJours.isEmpty() ? 1 : (int) lusJours.get(lusJours.size() - 1).seuil()));
        jours = lusJours;
        temps = lire(c.getConfigurationSection("temps-de-jeu.paliers"), true);
        plugin.getLogger().info(jours.size() + " jour(s) de connexion (cycle de " + cycle + "), "
                + temps.size() + " palier(s) de temps de jeu.");
    }

    /** Clés = numéro du jour, ou durée « 1h », « 90m », « 2h30 » pour le temps de jeu. */
    private List<Palier> lire(ConfigurationSection section, boolean duree) {
        List<Palier> out = new ArrayList<>();
        if (section == null) return out;
        for (String cle : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(cle);
            if (s == null) continue;
            long seuil = duree ? Duree.lire(cle) : entier(cle);
            if (seuil <= 0) {
                plugin.getLogger().warning("Clé « " + cle + " » illisible dans " + section.getCurrentPath() + ", ignorée");
                continue;
            }
            String nomDefaut = duree ? Duree.texte(seuil) + " de jeu" : "Jour " + seuil;
            out.add(new Palier(seuil, s.getString("nom", nomDefaut), s.getStringList("description"), s.getStringList("commandes")));
        }
        out.sort(Comparator.comparingLong(Palier::seuil));
        return List.copyOf(out);
    }

    public int cycle() { return cycle; }
    public List<Palier> jours() { return jours; }
    public List<Palier> temps() { return temps; }

    // ====================================================================
    // Données joueur
    // ====================================================================

    /** Données d'un joueur connecté (null s'il n'est pas chargé). */
    public DonneesJoueur donnees(UUID uuid) {
        return joueurs.get(uuid);
    }

    public DonneesJoueur charger(UUID uuid) {
        File f = fichier(uuid);
        DonneesJoueur d = new DonneesJoueur();
        if (f.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            d.jours = y.getInt("jours", 0);
            String dernier = y.getString("dernier-jour");
            if (dernier != null && !dernier.isEmpty()) {
                try {
                    d.dernierJour = LocalDate.parse(dernier);
                } catch (RuntimeException ignored) {
                    // date illisible : le prochain jour de connexion repartira proprement
                }
            }
            d.joursRecuperes.addAll(y.getIntegerList("jours-recuperes"));
            for (Object o : y.getList("temps-recuperes", List.of())) d.tempsRecuperes.add(((Number) o).longValue());
            d.tempsJoue = y.getLong("temps-joue", 0);
        }
        joueurs.put(uuid, d);
        return d;
    }

    public void decharger(UUID uuid) {
        DonneesJoueur d = joueurs.remove(uuid);
        if (d != null) sauvegarder(uuid, d, false);
    }

    public void sauvegarderTout() {
        joueurs.forEach((uuid, d) -> sauvegarder(uuid, d, true));
    }

    private void sauvegarder(UUID uuid, DonneesJoueur d, boolean synchrone) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("jours", d.jours);
        y.set("dernier-jour", d.dernierJour != null ? d.dernierJour.toString() : null);
        y.set("jours-recuperes", new ArrayList<>(d.joursRecuperes));
        y.set("temps-recuperes", new ArrayList<>(d.tempsRecuperes));
        y.set("temps-joue", d.tempsJoue);
        String contenu = y.saveToString();
        Runnable ecrire = () -> {
            try {
                dossier.mkdirs();
                java.nio.file.Files.writeString(fichier(uuid).toPath(), contenu);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Sauvegarde impossible pour " + uuid, e);
            }
        };
        if (synchrone || !plugin.isEnabled()) ecrire.run();
        else Bukkit.getScheduler().runTaskAsynchronously(plugin, ecrire);
    }

    private File fichier(UUID uuid) {
        return new File(dossier, uuid + ".yml");
    }

    // ====================================================================
    // Connexion quotidienne
    // ====================================================================

    public LocalDate aujourdhui() {
        return LocalDate.now(fuseau);
    }

    /**
     * Compte le jour de connexion s'il ne l'est pas encore (connexion, passage de minuit).
     * @return true si un nouveau jour vient d'être compté
     */
    public boolean compterJour(Player joueur) {
        DonneesJoueur d = joueurs.get(joueur.getUniqueId());
        if (d == null) return false;
        LocalDate ajd = aujourdhui();
        if (ajd.equals(d.dernierJour)) return false;

        if (serieStricte && d.dernierJour != null && !d.dernierJour.plusDays(1).equals(ajd)) {
            d.jours = 0;
            d.joursRecuperes.clear();
        }
        d.jours++;
        d.dernierJour = ajd;
        if (d.jourDuCycle(cycle) == 1) d.joursRecuperes.clear(); // nouveau cycle
        sauvegarder(joueur.getUniqueId(), d, false);
        return true;
    }

    public Etat etatJour(DonneesJoueur d, int jour) {
        int courant = d.jourDuCycle(cycle);
        if (d.joursRecuperes.contains(jour)) return Etat.RECUPERE;
        if (jour == courant && aujourdhui().equals(d.dernierJour)) return Etat.DISPONIBLE;
        if (jour < courant) return Etat.MANQUE;
        return Etat.VERROUILLE;
    }

    /** Palier configuré pour un jour du cycle (null : jour sans récompense). */
    public Palier palierJour(int jour) {
        for (Palier p : jours) if (p.seuil() == jour) return p;
        return null;
    }

    public boolean recupererJour(Player joueur) {
        DonneesJoueur d = joueurs.get(joueur.getUniqueId());
        if (d == null) return false;
        int jour = d.jourDuCycle(cycle);
        if (jour == 0 || etatJour(d, jour) != Etat.DISPONIBLE) {
            message(joueur, "rien-a-recuperer", Map.of());
            return false;
        }
        d.joursRecuperes.add(jour);
        sauvegarder(joueur.getUniqueId(), d, false);
        Palier p = palierJour(jour);
        if (p != null) executer(joueur, p);
        message(joueur, "jour-recupere", Map.of("jour", String.valueOf(jour), "nom", p != null ? p.nom() : "Jour " + jour));
        return true;
    }

    // ====================================================================
    // Temps de jeu
    // ====================================================================

    /** Temps de jeu en secondes (statistique du serveur si connecté, dernière valeur sinon). */
    public long tempsJoue(UUID uuid) {
        Player p = Bukkit.getPlayer(uuid);
        DonneesJoueur d = joueurs.get(uuid);
        if (p != null) {
            long secondes = p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L;
            if (d != null) d.tempsJoue = secondes;
            return secondes;
        }
        return d != null ? d.tempsJoue : 0;
    }

    public Etat etatTemps(UUID uuid, DonneesJoueur d, Palier p) {
        if (d.tempsRecuperes.contains(p.seuil())) return Etat.RECUPERE;
        return tempsJoue(uuid) >= p.seuil() ? Etat.DISPONIBLE : Etat.VERROUILLE;
    }

    /** Nombre de paliers de temps atteints (récupérés ou non) : progression du chemin. */
    public int tempsAtteints(UUID uuid) {
        long t = tempsJoue(uuid);
        int n = 0;
        for (Palier p : temps) if (t >= p.seuil()) n++;
        return n;
    }

    /** @param index palier à récupérer (1 = le plus court), 0 = tous ceux disponibles */
    public int recupererTemps(Player joueur, int index) {
        DonneesJoueur d = joueurs.get(joueur.getUniqueId());
        if (d == null) return 0;
        int n = 0;
        for (int i = 0; i < temps.size(); i++) {
            if (index != 0 && index != i + 1) continue;
            Palier p = temps.get(i);
            if (etatTemps(joueur.getUniqueId(), d, p) != Etat.DISPONIBLE) continue;
            d.tempsRecuperes.add(p.seuil());
            executer(joueur, p);
            message(joueur, "temps-recupere", Map.of("nom", p.nom()));
            n++;
        }
        if (n > 0) sauvegarder(joueur.getUniqueId(), d, false);
        else message(joueur, "rien-a-recuperer", Map.of());
        return n;
    }

    /** Récompenses disponibles maintenant (jour + temps de jeu). */
    public int aRecuperer(UUID uuid) {
        DonneesJoueur d = joueurs.get(uuid);
        if (d == null) return 0;
        int n = 0;
        int jour = d.jourDuCycle(cycle);
        if (jour > 0 && etatJour(d, jour) == Etat.DISPONIBLE) n++;
        for (Palier p : temps) if (etatTemps(uuid, d, p) == Etat.DISPONIBLE) n++;
        return n;
    }

    // ====================================================================
    // Administration (tests)
    // ====================================================================

    /** Place le joueur au jour {@code jour} du cycle, aujourd'hui, récompense non récupérée. */
    public void definirJour(Player joueur, int jour) {
        DonneesJoueur d = joueurs.get(joueur.getUniqueId());
        if (d == null) return;
        int base = d.jours <= 0 ? 0 : ((d.jours - 1) / cycle) * cycle;
        d.jours = base + Math.max(1, Math.min(jour, cycle));
        d.dernierJour = aujourdhui();
        d.joursRecuperes.removeIf(j -> j >= jour);
        sauvegarder(joueur.getUniqueId(), d, false);
    }

    public void reinitialiser(Player joueur) {
        DonneesJoueur d = joueurs.get(joueur.getUniqueId());
        if (d == null) return;
        d.jours = 0;
        d.dernierJour = null;
        d.joursRecuperes.clear();
        d.tempsRecuperes.clear();
        compterJour(joueur);
    }

    // ====================================================================

    private void executer(Player joueur, Palier p) {
        for (String cmd : p.commandes()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{joueur}", joueur.getName()));
        }
    }

    public void message(Player joueur, String cle, Map<String, String> variables) {
        String brut = plugin.getConfig().getString("messages." + cle);
        if (brut == null || brut.isEmpty()) return;
        brut = brut.replace("{prefixe}", plugin.getConfig().getString("messages.prefixe", ""));
        for (var e : variables.entrySet()) brut = brut.replace("{" + e.getKey() + "}", e.getValue());
        joueur.sendMessage(texte(joueur, brut));
    }

    public static Component texte(Player joueur, String brut) {
        if (brut.indexOf('%') >= 0 && Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            brut = PlaceholderAPI.setPlaceholders(joueur, brut);
        }
        return LEGACY.deserialize(brut);
    }

    private static long entier(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}

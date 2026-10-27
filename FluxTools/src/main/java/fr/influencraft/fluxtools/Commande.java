package fr.influencraft.fluxtools;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.command.TabExecutor;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /outil} : menu de l'outil en main. {@code /fluxitems} (admin) :
 * <pre>
 *   give &lt;joueur&gt; &lt;outil&gt; [quantité]   donner un outil neuf (caisses : commande console)
 *   livre &lt;joueur&gt; &lt;incassable|soulbound&gt;  livre d'enchantement (caisse Phénomène)
 *   reload                             relire config.yml et outils/
 *   info                               état de l'outil en main
 *   debloquer                          rend la quête du jour disponible tout de suite (tests)
 *   avancer &lt;n&gt;                        avance la quête en cours de n (tests, verse les récompenses)
 *   etape &lt;n&gt;                          place l'outil à l'étape n, sans récompense (tests)
 *   xp &lt;n&gt;                             ajoute n XP d'outil (tests, niveaux)
 * </pre>
 */
final class Commande implements TabExecutor, TabCompleter {

    private static final List<String> SOUS = List.of("give", "livre", "reload", "info", "debloquer", "avancer", "etape", "xp");

    private final FluxTools plugin;

    Commande(FluxTools plugin) {
        this.plugin = plugin;
    }

    /** Executor of {@code /tools} (Flux command). */
    boolean run(CommandSender s, String label, String[] a) {
        return onCommand(s, null, label, a);
    }

    /** Completer of {@code /tools}. */
    List<String> complete(CommandSender s, String[] a) {
        return onTabComplete(s, null, "tools", a);
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (c != null && c.getName().equals("outil")) {
            if (s instanceof Player p) plugin.ouvrir(p);
            return true;
        }
        if (!s.hasPermission("fluxitems.admin")) {
            s.sendMessage(Fmt.texte(Fmt.KO + "Permission manquante."));
            return true;
        }
        if (a.length == 0) {
            s.sendMessage(Fmt.texte(Fmt.TITRE + "/fluxitems " + String.join("|", SOUS)));
            return true;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "give" -> give(s, a);
            case "livre" -> livre(s, a);
            case "reload" -> {
                plugin.charger();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    Rafraichissement.inventaire(plugin, p.getInventory());
                    plugin.apparences().inventaire(p.getInventory());
                }
                s.sendMessage(Fmt.texte(Fmt.OK + plugin.outils().size() + " outils rechargés."));
            }
            case "info", "debloquer", "avancer", "etape", "xp" -> main(s, a);
            default -> s.sendMessage(Fmt.texte(Fmt.KO + "Sous-commande inconnue."));
        }
        return true;
    }

    private void give(CommandSender s, String[] a) {
        if (a.length < 3) {
            s.sendMessage(Fmt.texte(Fmt.KO + "/fluxitems give <joueur> <outil> [quantité]"));
            return;
        }
        Player p = Bukkit.getPlayerExact(a[1]);
        Outil o = plugin.outils().get(a[2].toLowerCase(Locale.ROOT));
        if (p == null || o == null) {
            s.sendMessage(Fmt.texte(Fmt.KO + (p == null ? "Joueur absent." : "Outil inconnu : " + a[2])));
            return;
        }
        int n = a.length > 3 ? Math.max(1, Integer.parseInt(a[3])) : 1;
        for (int i = 0; i < n; i++) {
            // Chaque outil est unique (identifiant, quête tirée) : jamais en pile.
            p.getInventory().addItem(Rendu.creer(o)).values().forEach(r -> p.getWorld().dropItem(p.getLocation(), r));
        }
    }

    private void livre(CommandSender s, String[] a) {
        Player p = a.length > 2 ? Bukkit.getPlayerExact(a[1]) : null;
        String id = a.length > 2 ? a[2].toLowerCase(Locale.ROOT) : "";
        Enchantment ench = Rendu.LIVRES.contains(id) ? Outil.enchantement(id) : null;
        if (p == null || ench == null) {
            s.sendMessage(Fmt.texte(Fmt.KO + "/fluxitems livre <joueur> <" + String.join("|", Rendu.LIVRES) + ">"));
            return;
        }
        ItemStack livre = ItemStack.of(Material.ENCHANTED_BOOK);
        livre.editMeta(EnchantmentStorageMeta.class, m -> m.addStoredEnchant(ench, 1, true));
        p.getInventory().addItem(livre).values().forEach(r -> p.getWorld().dropItem(p.getLocation(), r));
    }

    private void main(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) {
            s.sendMessage("En jeu seulement.");
            return;
        }
        ItemStack item = p.getInventory().getItemInMainHand();
        Outil o = plugin.outil(item);
        if (o == null) {
            plugin.message(p, "pas-d-outil");
            return;
        }
        Etat e = Etat.lire(item);
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "info" -> p.sendMessage(Fmt.texte(Fmt.TEXTE + o.id + " &7uid " + e.uid + " &7étape &f" + e.etape + "/" + o.etapes.size()
                    + " &7quête &f" + e.quete + " &7avance &f" + e.avance + " &7jalons &f" + e.jalons
                    + " &7dispo &f" + (e.enAttente() ? Fmt.duree((e.dispo - System.currentTimeMillis()) / 1000) : "oui")
                    + " &7niveau &f" + o.niveau(e.xp) + " &7xp &f" + Fmt.nombre(e.xp) + " &7blocs &f" + e.blocs + " &7tués &f" + e.tues + " &7gagné &f" + Fmt.nombre(e.gagne)));
            case "debloquer" -> {
                e.dispo = 0;
                Rendu.maj(item, o, e, false);
            }
            case "avancer" -> {
                Outil.Quete q = e.queteActive(o);
                if (q == null) {
                    p.sendMessage(Fmt.texte(Fmt.KO + "Aucune quête active (en attente ou terminé)."));
                    return;
                }
                int n = a.length > 1 ? Integer.parseInt(a[1]) : q.quantite() - e.avance;
                plugin.progression().avancer(p, item, o, e, q, Math.max(1, n), false);
            }
            case "xp" -> {
                double n = a.length > 1 ? Double.parseDouble(a[1]) : 0;
                boolean monte = plugin.progression().gagner(p, o, e, n);
                Rendu.maj(item, o, e, monte);
            }
            case "etape" -> {
                int n = a.length > 1 ? Integer.parseInt(a[1]) : 0;
                e.etape = Math.max(0, Math.min(n, o.etapes.size()));
                e.avance = 0;
                e.jalons = 0;
                e.dispo = 0;
                if (!e.termine(o)) e.quete = Rendu.tirer(o.etapes.get(e.etape));
                Rendu.maj(item, o, e, true);
            }
            default -> {
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        List<String> r = new ArrayList<>();
        if (!c.getName().equals("fluxitems")) return r;
        if (a.length == 1) {
            for (String x : SOUS) if (x.startsWith(a[0].toLowerCase(Locale.ROOT))) r.add(x);
        } else if (a.length == 2 && a[0].equalsIgnoreCase("give")) {
            for (Player p : Bukkit.getOnlinePlayers()) r.add(p.getName());
        } else if (a.length == 2 && a[0].equalsIgnoreCase("livre")) {
            for (Player p : Bukkit.getOnlinePlayers()) r.add(p.getName());
        } else if (a.length == 3 && a[0].equalsIgnoreCase("livre")) {
            r.addAll(Rendu.LIVRES);
        } else if (a.length == 3 && a[0].equalsIgnoreCase("give")) {
            for (String id : plugin.outils().keySet()) if (id.startsWith(a[2].toLowerCase(Locale.ROOT))) r.add(id);
        }
        return r;
    }
}

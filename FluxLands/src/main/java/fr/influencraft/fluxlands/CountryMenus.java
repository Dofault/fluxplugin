package fr.influencraft.fluxlands;

import fr.influencraft.flux.api.dialog.Dialogs;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.list.ListEntry;
import fr.influencraft.flux.api.list.Lists;
import me.angeschossen.lands.api.land.Land;
import me.angeschossen.lands.api.land.TaxHolder;
import me.angeschossen.lands.api.nation.Nation;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Taxes and effects of my country, in menus of ours ({@code pays_taxes}, {@code pays_effets}) instead of Lands' own.
 * Taxes: the country is a {@link TaxHolder} of Lands' API (amount per claimed chunk, taken from each member town at
 * every economic collection, bounded by {@code taxes.min/max} of Lands' nations.yml); the head changes it with a
 * slider. Effects: kept and applied by {@link CountryEffects}.
 */
final class CountryMenus {

    static final String EFFECTS = "fluxlands_country_effects";
    static final String EFFECTS_INFO = "fluxlands_country_effects_info";
    static final String TAX_INFO = "fluxlands_country_tax_info";
    static final String TAX_TOWNS = "fluxlands_country_tax_towns";

    private final FluxLands plugin;

    CountryMenus(FluxLands plugin) {
        this.plugin = plugin;
        Lists.register(plugin, EFFECTS, q -> effects(q.viewer()));
        Lists.registerItem(plugin, EFFECTS_INFO, this::effectsInfo);
        Lists.registerItem(plugin, TAX_INFO, this::taxInfo);
        Lists.register(plugin, TAX_TOWNS, q -> taxTowns(q.viewer()));
    }

    void refresh(Player p) {
        for (String id : List.of(EFFECTS, EFFECTS_INFO, TAX_INFO, TAX_TOWNS)) Lists.invalidate(p, id);
    }

    // ------------------------------------------------------------------ effects

    private List<ListEntry> effects(Player p) {
        Nation n = plugin.pays().monPays(p);
        List<ListEntry> out = new ArrayList<>();
        if (n == null) return out;
        CountryEffects ce = plugin.countryEffects();
        CountryEffects.Unlocks now = ce.unlocks(n);
        List<PotionEffectType> active = ce.active(n);
        boolean head = Pays.estChef(n, p) || p.hasPermission("fluxlands.admin");
        // every effect of every level, the locked ones too, so players see what the next levels bring
        Set<PotionEffectType> all = new LinkedHashSet<>(ce.unlocks(4).pool());
        for (PotionEffectType t : all) {
            Lore lore = Lore.create();
            boolean on = active.contains(t);
            if (on) lore.ok("Actif dans les villes du pays");
            else if (now.pool().contains(t)) lore.info("Disponible");
            else lore.ko("Débloqué au niveau " + firstLevel(ce, t) + " du pays");
            if (head && now.pool().contains(t)) lore.left(on ? "Désactiver" : "Activer");
            ItemStack item = potion(t, on);
            out.add(ListEntry.of(t.getKey().getKey()).item(Items.dress(item, CountryEffects.name(t), lore.build(), p)).set("effect", t.getKey().getKey()));
        }
        return out;
    }

    private ItemStack effectsInfo(Player p) {
        Nation n = plugin.pays().monPays(p);
        if (n == null) return null;
        CountryEffects ce = plugin.countryEffects();
        CountryEffects.Unlocks u = ce.unlocks(n);
        Lore lore = Lore.create().description("Les habitants du pays ont ces effets tant qu'ils se trouvent dans une de ses villes. "
                + "Le niveau du pays débloque de nouveaux effets.");
        lore.kv("Effets actifs", ce.active(n).size() + " / " + u.active());
        lore.kv("Effets débloqués", String.valueOf(u.pool().size()));
        if (!Pays.estChef(n, p)) lore.info("Seul le chef du pays choisit les effets.");
        return Items.of("itemsadder:influville:effets", "Effets de " + Couleurs.legacy(n.getColorName()), lore.build(), p);
    }

    /** First level whose unlocks contain the effect (2 to 4), for the locked ones. */
    private static int firstLevel(CountryEffects ce, PotionEffectType t) {
        for (int l = 1; l <= 4; l++) if (ce.unlocks(l).pool().contains(t)) return l;
        return 4;
    }

    private static ItemStack potion(PotionEffectType t, boolean on) {
        ItemStack item = new ItemStack(Material.POTION);
        if (item.getItemMeta() instanceof PotionMeta meta) {
            meta.setColor(t.getColor());
            meta.setEnchantmentGlintOverride(on);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            item.setItemMeta(meta);
        }
        return item;
    }

    void toggleEffect(Player p, String key) {
        Nation n = plugin.pays().monPays(p);
        if (n == null) return;
        if (!Pays.estChef(n, p) && !p.hasPermission("fluxlands.admin")) {
            Fmt.erreur(p, "Seul le chef du pays choisit ses effets.");
            return;
        }
        PotionEffectType t = CountryEffects.type(key);
        if (t == null) return;
        String error = plugin.countryEffects().toggle(n, t);
        if (error != null) Fmt.erreur(p, error);
        refresh(p);
    }

    // ------------------------------------------------------------------ taxes

    private double tax(Nation n) {
        return n instanceof TaxHolder th ? th.getTax() : 0;
    }

    /** Bounds of the tax per chunk ({@code taxes.min/max} of Lands' nations.yml) and the days between collections. */
    private double[] bounds() {
        org.bukkit.plugin.Plugin lands = Bukkit.getPluginManager().getPlugin("Lands");
        if (lands == null) return new double[]{0, 0, 7};
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new File(lands.getDataFolder(), "nations.yml"));
        return new double[]{y.getDouble("taxes.min", 0), y.getDouble("taxes.max", 0),
                lands.getConfig().getInt("economic.interval.days_2", 1)};
    }

    private static String every(double days) {
        return days == 7 ? "chaque semaine" : days == 1 ? "chaque jour" : "tous les " + (int) days + " jours";
    }

    private ItemStack taxInfo(Player p) {
        Nation n = plugin.pays().monPays(p);
        if (n == null) return null;
        double[] b = bounds();
        double t = tax(n);
        double total = 0;
        for (Land l : Pays.villes(n)) if (!isCapital(n, l)) total += l.getChunksAmount() * t;
        Lore lore = Lore.create().description("Chaque ville membre verse au pays, " + every(b[2]) + ", ce montant par chunk qu'elle "
                + "revendique. L'argent va à la banque de la capitale.");
        lore.kv("Taxe", Fmt.argent(t) + " &r&f &7par chunk");
        lore.kv("Recette estimée", Fmt.argent(total) + " &r&f");
        lore.kv("Bornes", Fmt.argent(b[0]) + " à " + Fmt.argent(b[1]));
        if (Pays.estChef(n, p) || p.hasPermission("fluxlands.admin")) lore.left("Changer la taxe");
        else lore.info("Seul le chef du pays fixe la taxe.");
        return Items.of("itemsadder:influville:taxes", "Taxes de " + Couleurs.legacy(n.getColorName()), lore.build(), p);
    }

    private List<ListEntry> taxTowns(Player p) {
        Nation n = plugin.pays().monPays(p);
        List<ListEntry> out = new ArrayList<>();
        if (n == null) return out;
        double t = tax(n);
        List<Land> towns = new ArrayList<>(Pays.villes(n));
        towns.sort((a, b) -> Integer.compare(b.getChunksAmount(), a.getChunksAmount()));
        for (Land l : towns) {
            boolean capital = isCapital(n, l);
            Lore lore = Lore.create().kv("Chunks", String.valueOf(l.getChunksAmount()));
            if (capital) lore.info("Capitale : elle reçoit les taxes");
            else lore.kv("Taxe", Fmt.argent(l.getChunksAmount() * t) + " &r&f");
            out.add(ListEntry.of(Villes.id(l)).item(Items.of("itemsadder:influville:niveau_" + Villes.niveau(l),
                    Couleurs.legacy(l.getColorName()), lore.build(), p)));
        }
        return out;
    }

    private static boolean isCapital(Nation n, Land l) {
        Land cap = n.getCapital();
        return cap != null && Villes.id(cap).equals(Villes.id(l));
    }

    /** Slider of the tax per chunk, between Lands' bounds, for the head of the country. */
    void askTax(Player p) {
        Nation n = plugin.pays().monPays(p);
        if (n == null) return;
        if (!Pays.estChef(n, p) && !p.hasPermission("fluxlands.admin")) {
            Fmt.erreur(p, "Seul le chef du pays fixe la taxe.");
            return;
        }
        if (!(n instanceof TaxHolder th)) {
            Fmt.erreur(p, "Les taxes de ce pays ne peuvent pas être changées.");
            return;
        }
        double[] b = bounds();
        Dialogs.form(p, "Taxe du pays")
                .body("Montant versé par chaque ville membre, " + every(b[2]) + ", pour chaque chunk qu'elle revendique.")
                .body("Une taxe trop haute pousse les villes à quitter le pays.")
                .slider("taxe", "Taxe par chunk", (float) b[0], (float) Math.max(b[0], b[1]), 0.5f, (float) th.getTax(), "%s : %s")
                .onSubmit((pl, a) -> {
                    Float v = a.number("taxe");
                    if (v != null) {
                        th.setTax(Math.max(b[0], Math.min(b[1], v)));
                        Fmt.envoyer(pl, "<t:texte>Taxe de</t> " + Fmt.ville(n.getName()) + " <t:texte>:</t> "
                                + Fmt.bitblock(th.getTax()) + " <t:texte>par chunk.</t>");
                    }
                    refresh(pl);
                    plugin.ouvrirMenu(pl, "pays_taxes");
                })
                .onCancel(pl -> plugin.ouvrirMenu(pl, "pays_taxes"))
                .open();
    }
}

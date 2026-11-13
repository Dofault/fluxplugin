package fr.influencraft.fluxlands;

import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.list.ListEntry;
import fr.influencraft.flux.api.list.Lists;
import me.angeschossen.lands.api.land.Land;
import me.angeschossen.lands.api.levels.Level;
import me.angeschossen.lands.api.levels.LevelUpResult;
import me.angeschossen.lands.api.levels.requirement.Requirement;
import me.angeschossen.lands.api.levels.reward.Reward;
import me.angeschossen.lands.api.memberholder.MemberHolder;
import me.angeschossen.lands.api.nation.Nation;
import me.angeschossen.lands.api.player.LandPlayer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Next-level objectives of my town or my country, in a menu of ours (menu {@code ville_niveau}) instead of Lands' own
 * level menu: one button per requirement of the next level (title, description, progress), the menu icon with the
 * current and next level, and the level-up button (cost, rewards) for the mayor or the head of the country. Values are
 * read through Lands' API ({@link Requirement#retrieveValue}, asynchronous): the menu shows « ... » until they arrive.
 */
final class Levels {

    static final String LIST = "fluxlands_level_goals";
    static final String INFO = "fluxlands_level_info";
    static final String UP = "fluxlands_level_up";

    /** What a player looks at: the town or the country, and the values read for the next level's requirements. */
    private record View(boolean country, String holderId, Map<String, Double> values) {
    }

    private final FluxLands plugin;
    private final Map<UUID, View> views = new ConcurrentHashMap<>();

    Levels(FluxLands plugin) {
        this.plugin = plugin;
        Lists.register(plugin, LIST, q -> goals(q.viewer()));
        Lists.registerItem(plugin, INFO, this::info);
        Lists.registerItem(plugin, UP, this::upButton);
    }

    /** Opens the objectives of my town ({@code country} false) or of my country. */
    void open(Player p, boolean country) {
        MemberHolder holder = country ? plugin.pays().monPays(p) : plugin.villes().maVille(p);
        if (holder == null) {
            Fmt.erreur(p, country ? Lang.t("levels.ta-ville-ne-fait-partie") : Lang.t("levels.tu-n-es-habitant-d"));
            return;
        }
        View view = new View(country, holder.getULID().toString(), new ConcurrentHashMap<>());
        views.put(p.getUniqueId(), view);
        Level next = next(holder);
        if (next != null) {
            List<CompletableFuture<?>> pending = new ArrayList<>();
            for (Object o : next.getRequirements()) {
                if (!(o instanceof Requirement r) || !r.shouldApply(holder)) continue;
                pending.add(r.retrieveValue(holder).thenAccept(v -> {
                    if (v instanceof Number n) view.values().put(r.getId(), n.doubleValue());
                }));
            }
            CompletableFuture.allOf(pending.toArray(new CompletableFuture[0])).whenComplete((ok, err) ->
                    Bukkit.getScheduler().runTask(plugin, () -> refresh(p)));
        }
        refresh(p);
        plugin.ouvrirMenu(p, "niveau");
    }

    /** Back button: the dashboard the objectives belong to. */
    void back(Player p) {
        View v = views.get(p.getUniqueId());
        if (v != null && v.country()) plugin.ouvrirMenu(p, plugin.pays().monPays(p) != null ? "pays" : "pays_accueil");
        else plugin.ouvrirMenu(p, plugin.villes().maVille(p) != null ? "ville" : "accueil");
    }

    void forget(UUID player) {
        views.remove(player);
    }

    private void refresh(Player p) {
        Lists.invalidate(p, LIST);
        Lists.invalidate(p, INFO);
        Lists.invalidate(p, UP);
    }

    private MemberHolder holder(Player p) {
        View v = views.get(p.getUniqueId());
        if (v == null) return null;
        MemberHolder h = v.country() ? plugin.pays().monPays(p) : plugin.villes().maVille(p);
        return h != null && h.getULID().toString().equals(v.holderId()) ? h : null;
    }

    private static Level next(MemberHolder holder) {
        Level current = holder.getLevel();
        return current == null ? null : current.getNext();
    }

    private static List<Requirement> requirements(MemberHolder holder, Level level) {
        List<Requirement> out = new ArrayList<>();
        if (level == null) return out;
        for (Object o : level.getRequirements()) if (o instanceof Requirement r && r.shouldApply(holder)) out.add(r);
        return out;
    }

    // ------------------------------------------------------------------ list content

    private List<ListEntry> goals(Player p) {
        MemberHolder holder = holder(p);
        List<ListEntry> out = new ArrayList<>();
        if (holder == null) return out;
        Map<String, Double> values = views.get(p.getUniqueId()).values();
        for (Requirement r : requirements(holder, next(holder))) {
            Double value = values.get(r.getId());
            boolean done = value != null && r.isFulfilled(value);
            Lore lore = Lore.create();
            for (Object line : r.getDescription()) {
                String text = plain(String.valueOf(line), r);
                // the commands of Lands' descriptions (/v trust, /v claim) are not how players do it here
                if (!text.isEmpty() && !text.toLowerCase(java.util.Locale.ROOT).startsWith("commande")) lore.description(text);
            }
            lore.kv("Progression", value == null ? "..." : r.formatValue(value) + " / " + r.formatValue(r.getRequired()));
            if (value != null) {
                if (done) lore.ok("Objectif rempli");
                else lore.ko("Pas encore rempli");
            }
            ItemStack item = Items.of(icon(r.getId()), title(r), lore.build(), p);
            if (done) glow(item);
            out.add(ListEntry.of(r.getId()).item(item));
        }
        return out;
    }

    private ItemStack info(Player p) {
        MemberHolder holder = holder(p);
        if (holder == null) return null;
        View v = views.get(p.getUniqueId());
        Level current = holder.getLevel();
        Level next = next(holder);
        Lore lore = Lore.create().description(v.country()
                ? Lang.t("levels.remplis-les-objectifs-du-pays")
                : Lang.t("levels.remplis-les-objectifs-de-la"));
        lore.kv("Niveau actuel", current == null ? "?" : Couleurs.legacy(current.getName()));
        lore.kv("Niveau suivant", next == null ? Lang.t("levels.aucun-niveau-maximum") : Couleurs.legacy(next.getName()));
        String icon = holder instanceof Nation n ? "itemsadder:influville:pays_niveau_" + Pays.niveau(n)
                : "itemsadder:influville:niveau_" + Villes.niveau((Land) holder);
        return Items.of(icon, Lang.t("levels.niveau-de") + Couleurs.legacy(holder.getColorName()), lore.build(), p);
    }

    private ItemStack upButton(Player p) {
        MemberHolder holder = holder(p);
        if (holder == null) return null;
        Level next = next(holder);
        if (next == null) {
            return Items.of("itemsadder:influitems:checkmark", "Niveau maximum",
                    Lore.create().description(Lang.t("levels.il-n-y-a-pas")).build(), p);
        }
        Map<String, Double> values = views.get(p.getUniqueId()).values();
        List<Requirement> reqs = requirements(holder, next);
        long missing = reqs.stream().filter(r -> {
            Double v = values.get(r.getId());
            return v == null || !r.isFulfilled(v);
        }).count();
        Lore lore = Lore.create();
        var money = fr.influencraft.flux.api.economy.Economy.main();
        if (next.getCost() > 0) lore.kv(Lang.t("levels.cout"), money != null ? money.format(next.getCost()) : Fmt.argent(next.getCost()));
        List<String> rewards = new ArrayList<>();
        for (Object o : next.getRewards()) if (o instanceof Reward r && r.getDescription() != null) rewards.add(Couleurs.nu(r.getDescription()));
        rewards.removeIf(String::isBlank);
        // country effects are FluxLands' (CountryEffects), not Lands' rewards any more
        if (holder instanceof Nation n) {
            int to = Pays.niveau(n) + 1;
            List<org.bukkit.potion.PotionEffectType> before = plugin.countryEffects().unlocks(to - 1).pool();
            List<String> added = new ArrayList<>();
            for (var t : plugin.countryEffects().unlocks(to).pool()) if (!before.contains(t)) added.add(CountryEffects.name(t));
            if (!added.isEmpty()) rewards.add("Effets : " + String.join(", ", added));
            int more = plugin.countryEffects().unlocks(to).active() - plugin.countryEffects().unlocks(to - 1).active();
            if (more > 0) rewards.add(more + Lang.t("levels.effet-s-actif-s-de"));
        }
        if (!rewards.isEmpty()) lore.description(Lang.t("levels.debloque")).items(rewards);
        if (missing == 0) lore.ok(Lang.t("levels.tous-les-objectifs-sont-remplis"));
        else lore.ko(missing == 1 ? "Il reste 1 objectif" : "Il reste " + missing + " objectifs");
        if (canLevelUp(p, holder)) lore.left(Lang.t("levels.passer-au-niveau-suivant"));
        else lore.info(views.get(p.getUniqueId()).country() ? Lang.t("levels.seul-le-chef-du-pays") : Lang.t("levels.seul-le-maire-peut-le"));
        ItemStack item = Items.of("itemsadder:influitems:stairs", Lang.t("levels.passer-au-niveau") + Couleurs.legacy(next.getName()), lore.build(), p);
        if (missing == 0) glow(item);
        return item;
    }

    // ------------------------------------------------------------------ level up

    void levelUp(Player p) {
        MemberHolder holder = holder(p);
        if (holder == null) return;
        if (!canLevelUp(p, holder)) {
            Fmt.erreur(p, holder instanceof Nation ? Lang.t("levels.seul-le-chef-du-pays-2")
                    : Lang.t("levels.seul-le-maire-peut-faire"));
            return;
        }
        Level next = next(holder);
        if (next == null) {
            Fmt.erreur(p, Lang.t("levels.il-n-y-a-pas"));
            return;
        }
        LandPlayer lp = plugin.villes().api().getLandPlayer(p.getUniqueId());
        if (lp == null) return;
        holder.levelUp(lp).whenComplete((res, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (res instanceof LevelUpResult r) answer(p, holder, next, r);
            else Fmt.erreur(p, Lang.t("levels.le-passage-de-niveau-n"), Lang.t("levels.reessaie-dans-un-instant"));
            open(p, holder instanceof Nation);
        }));
    }

    private void answer(Player p, MemberHolder holder, Level next, LevelUpResult r) {
        String name = Couleurs.legacy(next.getName());
        switch (r) {
            case SUCCESS -> Fmt.envoyer(p, "<t:succes>Niveau suivant atteint :</t> " + Fmt.ville(Couleurs.nu(holder.getName()))
                    + " " + Lang.t("levels.est-maintenant") + Fmt.v(Couleurs.nu(next.getName())) + "<t:texte>.</t>");
            case REQUIREMENTS_NOT_MET -> Fmt.erreur(p, Lang.t("levels.tous-les-objectifs-ne-sont"), Lang.t("levels.ils-sont-listes-dans-ce"));
            case NOT_ENOUGH_MONEY -> Fmt.erreur(p, Lang.t("levels.pas-assez-d-argent-pour") + Fmt.v(Couleurs.nu(next.getName())) + ".");
            case HIGHEST_LEVEL -> Fmt.erreur(p, Lang.t("levels.il-n-y-a-pas"));
            case NO_PERMISSION -> Fmt.erreur(p, Lang.t("levels.tu-n-as-pas-le"));
            default -> Fmt.erreur(p, Lang.t("levels.le-passage-au-niveau") + Fmt.v(Couleurs.nu(next.getName())) + Lang.t("levels.n-a-pas-pu-se"), Lang.t("levels.reessaie-dans-un-instant"));
        }
    }

    private boolean canLevelUp(Player p, MemberHolder holder) {
        if (p.hasPermission("fluxlands.admin")) return true;
        if (holder instanceof Nation n) return Pays.estChef(n, p);
        return holder instanceof Land l && Villes.estMaire(l, p.getUniqueId());
    }

    // ------------------------------------------------------------------ helpers

    /** Requirement title without Lands' « Objectif ▸ » prefix, plain. */
    private static String title(Requirement r) {
        String t = plain(r.getTitle(), r);
        int arrow = t.indexOf('▸');
        return arrow >= 0 ? t.substring(arrow + 1).trim() : t;
    }

    private static String plain(String raw, Requirement r) {
        if (raw == null) return "";
        return Couleurs.nu(raw.replace("<v:req>", r.formatValue(r.getRequired())));
    }

    private static String icon(String requirementId) {
        return switch (requirementId) {
            case "members", Residents.ID -> "itemsadder:influville:habitants";
            case "chunks" -> "itemsadder:influville:chunks";
            case "lands" -> "itemsadder:influville:ville";
            default -> "itemsadder:influville:niveau";
        };
    }

    private static void glow(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
    }
}

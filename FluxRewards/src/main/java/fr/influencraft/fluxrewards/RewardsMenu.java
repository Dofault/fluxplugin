package fr.influencraft.fluxrewards;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.list.ListEntry;
import fr.influencraft.flux.api.list.ListProvider;
import fr.influencraft.flux.api.list.ListQuery;
import fr.influencraft.flux.api.list.Lists;
import fr.influencraft.flux.api.reward.Rewards;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.Format;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Lower part of Flux's rewards menu ({@code menus/rewards.yml}): the frieze of the login cycle or of the playtime
 * tiers ({@code recompenses_frise}, 7 per page, filter 0 = login, 1 = playtime) and today's reward
 * ({@code recompenses_jour}). Each element is built here, the menu file only places it; a click on a waiting
 * reward claims it ({@code %entry_clic%}). {@code [fluxrewards] semaine} opens the frieze on the current week.
 */
final class RewardsMenu {

    static final String FRISE = "recompenses_frise";
    static final String JOUR = "recompenses_jour";
    private static final int PAR_SEMAINE = 7;

    private final FluxRewards plugin;
    private final DailyRewards daily;

    private RewardsMenu(FluxRewards plugin, DailyRewards daily) {
        this.plugin = plugin;
        this.daily = daily;
    }

    static void enable(FluxRewards plugin, DailyRewards daily) {
        RewardsMenu menu = new RewardsMenu(plugin, daily);
        Lists.register(plugin, FRISE, new ListProvider() {
            @Override
            public List<ListEntry> entries(ListQuery query) {
                return query.filter() == 1 ? menu.playtime(query.viewer()) : menu.days(query.viewer());
            }

            @Override
            public List<String> filters() {
                return List.of("Connexion", "Temps de jeu");
            }
        });
        Lists.register(plugin, JOUR, query -> List.of(menu.today(query.viewer())));
        FluxApi.registerAction(plugin, "fluxrewards", (player, view, argument) -> {
            if (argument.trim().equalsIgnoreCase("semaine")) menu.currentWeek(player);
        });
    }

    /** Opens the frieze on the login view, at the week of today. */
    private void currentWeek(Player player) {
        Lists.reset(player, FRISE);
        PlayerData d = daily.data(player.getUniqueId());
        int day = d == null ? 1 : Math.max(1, d.dayOfCycle(daily.cycle()));
        Lists.act(player, FRISE, "page " + ((day - 1) / PAR_SEMAINE + 1), PAR_SEMAINE);
    }

    // ------------------------------------------------------------------ login days

    private List<ListEntry> days(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerData d = daily.data(uuid);
        List<ListEntry> out = new ArrayList<>();
        if (d == null) return out;
        int today = d.dayOfCycle(daily.cycle());
        for (int n = 1; n <= daily.cycle(); n++) {
            RewardTier tier = daily.dayTier(n);
            String name = tier != null ? tier.name() : plugin.messages().raw("day-name", "%day%", String.valueOf(n));
            DailyRewards.State state = daily.dayState(d, n);
            boolean waiting = n == today && Rewards.waiting(uuid, daily.dayKey(d));
            Lore lore = Lore.create();
            if (tier != null) lore.lines(placeholders(player, tier.lore()));
            String clic = "[sound] @error";
            String icon;
            if (waiting) {
                icon = "itemsadder:influitems:chest_item";
                lore.line(Lore.GREEN + "Aujourd'hui, en attente.").left("Récupérer");
                clic = "[rewards] claim key:" + daily.dayKey(d);
            } else if (state == DailyRewards.State.CLAIMED) {
                icon = "itemsadder:influitems:chest_checkmark";
                lore.line(Lore.GREEN + "Récupéré.");
            } else if (state == DailyRewards.State.MISSED) {
                icon = "itemsadder:influitems:deny";
                lore.line(Lore.RED + "Manqué ce jour-là.");
            } else {
                icon = "itemsadder:influitems:lock";
                lore.line(n == today + 1 ? "Reviens demain." : "Dans " + (n - today) + " jours de connexion.");
            }
            out.add(entry(player, "jour:" + n, icon, name, lore, n, n == today).set("clic", clic));
        }
        return out;
    }

    // ------------------------------------------------------------------ playtime tiers

    private List<ListEntry> playtime(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerData d = daily.data(uuid);
        List<ListEntry> out = new ArrayList<>();
        if (d == null) return out;
        int i = 0;
        for (RewardTier tier : daily.playtimeTiers()) {
            i++;
            String key = DailyRewards.playtimeKey(tier);
            DailyRewards.State state = daily.playtimeState(uuid, d, tier);
            Lore lore = Lore.create().lines(placeholders(player, tier.lore()))
                    .line("Palier : " + Lore.GOLD + Format.duration(tier.threshold()) + Lore.PURPLE + " de jeu.");
            String clic = "[sound] @error";
            String icon;
            if (Rewards.waiting(uuid, key)) {
                icon = "itemsadder:influitems:chest_item";
                lore.line(Lore.GREEN + "Atteint, en attente.").left("Récupérer");
                clic = "[rewards] claim key:" + key;
            } else if (state == DailyRewards.State.CLAIMED) {
                icon = "itemsadder:influitems:chest_checkmark";
                lore.line(Lore.GREEN + "Récupéré.");
            } else if (state == DailyRewards.State.AVAILABLE) {
                icon = "itemsadder:influitems:chest_item";
                lore.line(Lore.GREEN + "Atteint, il arrive dans tes récompenses.");
            } else {
                icon = "itemsadder:influitems:lock";
                lore.line("Encore " + Lore.GOLD + Format.duration(Math.max(0, tier.threshold() - daily.playtime(uuid)))
                        + Lore.PURPLE + " de jeu.");
            }
            out.add(entry(player, "temps:" + i, icon, tier.name(), lore, i, false).set("clic", clic));
        }
        return out;
    }

    // ------------------------------------------------------------------ today

    private ListEntry today(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerData d = daily.data(uuid);
        int day = d == null ? 0 : d.dayOfCycle(daily.cycle());
        Lore lore = Lore.create().line("Une récompense t'attend à chaque")
                .line("jour de connexion, et d'autres")
                .line("en jouant (temps de jeu).")
                .line("")
                .line("Jour " + Lore.GOLD + day + "&7/" + Lore.GOLD + daily.cycle() + Lore.PURPLE + " de connexion.")
                .line("Temps de jeu : " + Lore.GOLD + Format.duration(daily.playtime(uuid)) + Lore.PURPLE + ".");
        String clic = "[sound] @error";
        String icon;
        if (d != null && Rewards.waiting(uuid, daily.dayKey(d))) {
            icon = "itemsadder:influitems:chest_item";
            lore.line(Lore.GREEN + "Celle d'aujourd'hui t'attend !").left("Récupérer");
            clic = "[rewards] claim key:" + daily.dayKey(d);
        } else {
            icon = "itemsadder:influitems:chest_checkmark";
            lore.line(Lore.GREEN + "Déjà récupérée, reviens demain.");
        }
        return entry(player, "aujourdhui", icon, "Récompense du jour", lore, Math.max(1, day), true).set("clic", clic);
    }

    // ------------------------------------------------------------------ helpers

    private static ListEntry entry(Player player, String id, String icon, String name, Lore lore, int amount, boolean glow) {
        ItemStack item = Items.of(icon, Lore.GOLD + name, lore.build(), player);
        if (item == null) item = new ItemStack(Material.PAPER);
        item.setAmount(Math.max(1, Math.min(99, amount)));
        if (glow) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setEnchantmentGlintOverride(true);
                item.setItemMeta(meta);
            }
        }
        return ListEntry.of(id).item(item);
    }

    private static List<String> placeholders(Player player, List<String> lines) {
        List<String> out = new ArrayList<>(lines.size());
        for (String l : lines) out.add(Texts.placeholders(l, player));
        return out;
    }
}

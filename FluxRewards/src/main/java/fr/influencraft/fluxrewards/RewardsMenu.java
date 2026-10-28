package fr.influencraft.fluxrewards;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.list.ListEntry;
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
 * Parts of Flux's rewards menu ({@code menus/rewards.yml}) built here, the menu file only places them: the frieze
 * of the login cycle ({@code recompenses_connexion}) and of the playtime tiers ({@code recompenses_temps}), 7 per
 * page, and the explanation of the menu with today's reward ({@code recompenses_info}). A click on a waiting
 * reward claims it ({@code %entry_clic%}). {@code [fluxrewards] semaine} opens the login frieze on the current week.
 */
final class RewardsMenu {

    static final String CONNEXION = "recompenses_connexion";
    static final String TEMPS = "recompenses_temps";
    static final String INFO = "recompenses_info";
    private static final int PAR_SEMAINE = 7;

    private final FluxRewards plugin;
    private final DailyRewards daily;

    private RewardsMenu(FluxRewards plugin, DailyRewards daily) {
        this.plugin = plugin;
        this.daily = daily;
    }

    static void enable(FluxRewards plugin, DailyRewards daily) {
        RewardsMenu menu = new RewardsMenu(plugin, daily);
        Lists.register(plugin, CONNEXION, query -> menu.days(query.viewer()));
        Lists.register(plugin, TEMPS, query -> menu.playtime(query.viewer()));
        Lists.register(plugin, INFO, query -> List.of(menu.info(query.viewer())));
        FluxApi.registerAction(plugin, "fluxrewards", (player, view, argument) -> {
            if (argument.trim().equalsIgnoreCase("semaine")) menu.currentWeek(player);
        });
    }

    /** Opens the login frieze at the week of today, the playtime frieze on its first page. */
    private void currentWeek(Player player) {
        Lists.reset(player, TEMPS);
        Lists.reset(player, CONNEXION);
        PlayerData d = daily.data(player.getUniqueId());
        int day = d == null ? 1 : Math.max(1, d.dayOfCycle(daily.cycle()));
        Lists.act(player, CONNEXION, "page " + ((day - 1) / PAR_SEMAINE + 1), PAR_SEMAINE);
    }

    /** Steps reached among the 7 of the page of a frieze the player looks at (0 to 7). */
    static int pathProgress(Player player, String list, int reached) {
        int start = Lists.state(player, list).page() * PAR_SEMAINE;
        return Math.max(0, Math.min(PAR_SEMAINE, reached - start));
    }

    /** Colours of the 7 steps of the login frieze page shown (path states): vert, orange, rouge, eteint. */
    static String dayColors(Player player, DailyRewards daily) {
        UUID uuid = player.getUniqueId();
        PlayerData d = daily.data(uuid);
        int start = Lists.state(player, CONNEXION).page() * PAR_SEMAINE;
        List<String> out = new ArrayList<>();
        int today = d == null ? 0 : d.dayOfCycle(daily.cycle());
        for (int n = start + 1; n <= Math.min(daily.cycle(), start + PAR_SEMAINE); n++) {
            if (d == null) {
                out.add("eteint");
                continue;
            }
            boolean waiting = n == today && Rewards.waiting(uuid, daily.dayKey(d));
            out.add(waiting ? "orange" : switch (daily.dayState(d, n)) {
                case CLAIMED -> "vert";
                case MISSED -> "rouge";
                case AVAILABLE -> "orange";
                case LOCKED -> "eteint";
            });
        }
        return String.join(",", out);
    }

    /** Colours of the 7 steps of the playtime frieze page shown. */
    static String playtimeColors(Player player, DailyRewards daily) {
        UUID uuid = player.getUniqueId();
        PlayerData d = daily.data(uuid);
        List<RewardTier> tiers = daily.playtimeTiers();
        int start = Lists.state(player, TEMPS).page() * PAR_SEMAINE;
        List<String> out = new ArrayList<>();
        for (int i = start; i < Math.min(tiers.size(), start + PAR_SEMAINE); i++) {
            RewardTier tier = tiers.get(i);
            if (d == null) {
                out.add("eteint");
            } else if (Rewards.waiting(uuid, DailyRewards.playtimeKey(tier))) {
                out.add("orange");
            } else {
                out.add(switch (daily.playtimeState(uuid, d, tier)) {
                    case CLAIMED -> "vert";
                    case AVAILABLE -> "orange";
                    case MISSED -> "rouge";
                    case LOCKED -> "eteint";
                });
            }
        }
        return String.join(",", out);
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

    // ------------------------------------------------------------------ explanation and today

    private ListEntry info(Player player) {
        UUID uuid = player.getUniqueId();
        PlayerData d = daily.data(uuid);
        int day = d == null ? 0 : d.dayOfCycle(daily.cycle());
        Lore lore = Lore.create().line("Tout ce que tu gagnes arrive ici :")
                .line("connexion, temps de jeu, votes,")
                .line("parrainage, boosters...")
                .line("")
                .line("En bas, une récompense par jour")
                .line("de connexion (1re ligne) et par")
                .line("palier de temps de jeu (2e ligne).")
                .line("")
                .line("Jour " + Lore.GOLD + day + "&7/" + Lore.GOLD + daily.cycle() + Lore.PURPLE + " de connexion.")
                .line("Temps de jeu : " + Lore.GOLD + Format.duration(daily.playtime(uuid)) + Lore.PURPLE + ".");
        String clic = "[sound] @error";
        String icon;
        boolean waiting = d != null && Rewards.waiting(uuid, daily.dayKey(d));
        if (waiting) {
            icon = "itemsadder:influitems:reward";
            lore.line(Lore.GREEN + "Celle d'aujourd'hui t'attend !").left("Récupérer");
            clic = "[rewards] claim key:" + daily.dayKey(d);
        } else {
            icon = "itemsadder:influitems:reward";
            lore.line(Lore.GREEN + "Celle d'aujourd'hui est prise, reviens demain.");
        }
        return entry(player, "info", icon, "Récompenses", lore, 1, waiting).set("clic", clic);
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

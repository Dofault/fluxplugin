package fr.influencraft.fluxtournament;

import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.leaderboard.Leaderboards;
import fr.influencraft.flux.api.list.ListEntry;
import fr.influencraft.flux.api.list.Lists;
import fr.influencraft.flux.api.text.Messages;
import fr.influencraft.flux.api.util.Format;
import fr.influencraft.fluxtournament.Run.Score;
import fr.influencraft.fluxtournament.TournamentStore.PastRun;
import fr.influencraft.fluxtournament.TournamentStore.Result;
import fr.influencraft.fluxtournament.TournamentStore.Stats;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Parts of the tournament menu ({@code menus/tournoi.yml}), built here for each viewer, placed by the menu file:
 * <ul>
 *   <li>{@code tournoi_actuel}: the tournament running, or the next one;</li>
 *   <li>{@code tournoi_direct}: ranking of the running tournament (the last one's when none runs);</li>
 *   <li>{@code tournoi_historique}: last tournaments, newest first;</li>
 *   <li>{@code tournoi_stats}: the viewer's totals and his place in the tournament leaderboard.</li>
 * </ul>
 */
final class TournamentMenu {

    static final String BOARD = "tournois";

    private final FluxTournament plugin;
    private final TournamentService service;
    private final TournamentStore store;

    TournamentMenu(FluxTournament plugin, TournamentService service, TournamentStore store) {
        this.plugin = plugin;
        this.service = service;
        this.store = store;
    }

    void register() {
        Lists.registerItem(plugin, TournamentService.CURRENT, this::current);
        Lists.registerItem(plugin, TournamentService.STATS, this::stats);
        Lists.register(plugin, TournamentService.LIVE, q -> live(q.viewer()));
        Lists.register(plugin, TournamentService.HISTORY, q -> history(q.viewer()));
    }

    private Messages m() {
        return plugin.messages();
    }

    // ---------------------------------------------------------------- current / next

    private ItemStack current(Player viewer) {
        Run run = service.current;
        long now = System.currentTimeMillis();
        if (run != null) {
            Lore lore = Lore.create().description(service.description(run.plan))
                    .kv(m().raw("menu.time-left"), TournamentService.clock((run.ends - now) / 1000))
                    .kv(m().raw("menu.participants"), run.ranking().size());
            Score s = run.scores.get(viewer.getUniqueId());
            if (s != null && s.value > 0) {
                lore.kv(m().raw("menu.your-place"), run.rank(viewer.getUniqueId()) + "")
                        .kv(m().raw("menu.your-score"), service.score(run.plan, s.value));
            } else {
                lore.info(m().raw("menu.not-yet"));
            }
            return glow(Items.of(service.icon(run.plan), TournamentService.kindIcon(run.plan.kind()) + " " + m().raw("menu.running", "{name}", service.name(run.plan)), lore.build(), viewer));
        }
        Lore lore = Lore.create();
        if (!service.scheduled) {
            lore.description(m().raw("menu.none-planned"));
            return Items.of("CLOCK", m().raw("menu.next-title"), lore.build(), viewer);
        }
        long in = Math.max(0, (service.nextStart - now) / 1000);
        // what comes next stays secret until the draw
        if (service.drawing != null) {
            lore.description(m().raw("menu.drawing"));
            return glow(Items.of("CLOCK", m().raw("menu.next-title"), lore.build(), viewer));
        }
        lore.description(m().raw("menu.next-secret")).kv(m().raw("menu.starts-in"), Format.duration(Math.max(60, in)));
        return Items.of("CLOCK", m().raw("menu.next-title"), lore.build(), viewer);
    }

    // ---------------------------------------------------------------- live ranking

    private List<ListEntry> live(Player viewer) {
        List<ListEntry> out = new ArrayList<>();
        Run run = service.current;
        if (run != null) {
            List<Score> ranking = run.ranking();
            for (int i = 0; i < ranking.size(); i++) {
                Score s = ranking.get(i);
                out.add(place(viewer, s.uuid, s.name, i + 1, service.score(run.plan, s.value),
                        run.plan.kind() == Plan.Kind.RACE && s.finished > 0));
            }
            return out;
        }
        List<PastRun> history = store.history();
        if (history.isEmpty()) return out;
        PastRun last = history.get(0);
        for (Result r : last.results()) {
            out.add(place(viewer, r.uuid(), r.name(), r.rank(), service.scoreOf(last.kind(), r.score(), last.amount()), false));
        }
        return out;
    }

    private ListEntry place(Player viewer, UUID uuid, String name, int place, String score, boolean finished) {
        Lore lore = Lore.create().kv(m().raw("menu.place"), medal(place)).kv(m().raw("menu.score"), score);
        if (finished) lore.ok(m().raw("menu.finished"));
        ItemStack head = head(uuid, (uuid.equals(viewer.getUniqueId()) ? "&#2ECF7B" : "&f") + name, lore.build(), viewer);
        head.setAmount(Math.max(1, Math.min(64, place)));
        return ListEntry.of(uuid.toString()).item(head);
    }

    // ---------------------------------------------------------------- history

    private List<ListEntry> history(Player viewer) {
        List<ListEntry> out = new ArrayList<>();
        for (PastRun r : store.history()) {
            Lore lore = Lore.create().kv(m().raw("menu.played"), Format.ago(r.ended()))
                    .kv(m().raw("menu.participants"), r.results().size());
            List<String> podium = new ArrayList<>();
            for (int i = 0; i < Math.min(3, r.results().size()); i++) {
                Result res = r.results().get(i);
                podium.add(m().raw("menu.podium", "{medal}", m().raw("menu.medals." + (i + 1)), "{player}", res.name(),
                        "{score}", String.valueOf(service.scoreOf(r.kind(), res.score(), r.amount()))));
            }
            if (!podium.isEmpty()) lore.items(podium);
            Result mine = r.of(viewer.getUniqueId());
            if (mine != null) lore.ok(m().raw("menu.your-result", "{place}", String.valueOf(mine.rank())));
            if (!r.rewarded()) lore.ko(m().raw("menu.not-rewarded"));
            String icon = r.icon() == null || r.icon().isBlank() ? "PAPER" : r.icon();
            String name = TournamentService.kindIcon(Plan.Kind.of(r.kind())) + " " + m().raw("menu.run-name", "{name}", r.name());
            out.add(ListEntry.of(String.valueOf(r.id())).item(Items.of(icon, name, lore.build(), viewer)));
        }
        return out;
    }

    // ---------------------------------------------------------------- viewer's totals

    private ItemStack stats(Player viewer) {
        Stats s = store.stats(viewer.getUniqueId());
        int rank = Leaderboards.rank(BOARD, viewer.getUniqueId());
        Lore lore = Lore.create().description(m().raw("menu.stats-description"))
                .kv(m().raw("menu.points"), s == null ? 0 : s.points)
                .kv(m().raw("menu.wins"), s == null ? 0 : s.wins)
                .kv(m().raw("menu.podiums"), s == null ? 0 : s.podiums)
                .kv(m().raw("menu.played-count"), s == null ? 0 : s.played)
                .kv(m().raw("menu.leaderboard-place"), rank > 0 ? "#" + rank : "-");
        return head(viewer.getUniqueId(), m().raw("menu.stats-title"), lore.build(), viewer);
    }

    // ---------------------------------------------------------------- helpers

    private String medal(int place) {
        return place <= 3 ? m().raw("menu.medals." + place) : "&f" + m().raw("menu.place-n", "{n}", String.valueOf(place));
    }

    private static ItemStack head(UUID uuid, String name, List<String> lore, Player viewer) {
        ItemStack item = Items.of("PLAYER_HEAD", name, lore, viewer);
        if (item == null) item = new ItemStack(Material.PLAYER_HEAD);
        if (item.getItemMeta() instanceof SkullMeta meta) {
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(uuid));
            item.setItemMeta(meta);
        }
        return item;
    }

    private static ItemStack glow(ItemStack item) {
        if (item == null) return new ItemStack(Material.PAPER);
        var meta = item.getItemMeta();
        if (meta != null) {
            meta.setEnchantmentGlintOverride(true);
            item.setItemMeta(meta);
        }
        return item;
    }
}

package fr.influencraft.fluxjobs;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.jobs.JobAction;
import fr.influencraft.flux.api.jobs.Jobs;
import fr.influencraft.flux.api.list.ListEntry;
import fr.influencraft.flux.api.list.Lists;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.Format;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What pays XP and money in a job ({@code /metier blocs <job>}), read from the job plugin through the jobs service of
 * Flux on every opening: the menu never needs an update when a block is added to a job. Flux menu
 * {@code job_actions} (plugins/FluxJobs/menus/), list {@code job_actions} (one entry per block when the job lists
 * blocks, else one per kind of action) and one-item list {@code job_actions_header}.
 */
public final class JobBlockBreakdownMenu {

    public static final String MENU = "job_actions";
    public static final String LIST = "job_actions";

    private final Map<UUID, String> opened = new ConcurrentHashMap<>();

    public JobBlockBreakdownMenu(Plugin plugin) {
        Lists.register(plugin, LIST, query -> entries(query.viewer()));
        Lists.registerItem(plugin, "job_actions_header", this::header);
        fr.influencraft.flux.api.FluxApi.registerPlaceholders(plugin, "jobactions", (player, params) ->
                player == null || !params.equals("name") ? null : Jobs.get().displayName(opened.getOrDefault(player.getUniqueId(), "")));
    }

    public void open(Player player, String jobId, int page) {
        if (!Jobs.get().exists(jobId)) {
            Lang.send(player, "unknown-job", "%job%", jobId);
            return;
        }
        opened.put(player.getUniqueId(), jobId);
        Lists.reset(player, LIST);
        Lists.invalidate(player, "job_actions_header");
        for (int i = 1; i < page; i++) Lists.act(player, LIST, "next", 28);
        FluxApi.open(player, MENU);
    }

    private List<ListEntry> entries(Player player) {
        String job = opened.get(player.getUniqueId());
        List<ListEntry> out = new ArrayList<>();
        if (job == null) return out;
        int i = 0;
        for (JobAction a : Jobs.get().actions(player, job)) {
            JobActionLook.Look look = JobActionLook.of(player, a);
            List<String> lore = new ArrayList<>();
            lore.add(Lang.t("actions.xp", "%xp%", Format.decimal(a.xp())) + (a.perHealth() ? Lang.t("actions.per-health") : ""));
            lore.add(money(player, a));
            out.add(ListEntry.of(String.valueOf(i++)).set("name", look.name())
                    .item(fr.influencraft.flux.api.inventory.Items.dress(look.icon(), look.name(), lore, player)));
        }
        return out;
    }

    private ItemStack header(Player player) {
        String job = opened.get(player.getUniqueId());
        if (job == null) return null;
        List<JobAction> actions = Jobs.get().actions(player, job);
        String key = actions.isEmpty() ? "none" : actions.get(0).perHealth() ? "per-health" : actions.get(0).trigger().equals("catch_fish") ? "fish"
                : actions.get(0).targets().isEmpty() ? "kinds" : "blocks";
        fr.influencraft.flux.api.inventory.Lore lore = fr.influencraft.flux.api.inventory.Lore.create().description(Lang.t("actions.header." + key));
        return fr.influencraft.flux.api.inventory.Items.of(Material.PAPER.name(), Jobs.get().displayName(job), lore.build(), player);
    }

    /** Money of an action in the server's currency format (glyph resolved, these items skip placeholders). */
    private static String money(Player player, JobAction a) {
        var currency = fr.influencraft.flux.api.economy.Economy.main();
        double v = a.money() == null ? 0 : a.money();
        return Lang.money(a, Texts.placeholders(currency == null ? Format.decimal(v) : currency.format(v), player));
    }

}

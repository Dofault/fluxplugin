package fr.influencraft.fluxtools.modules;

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
 * {@code job_actions} (plugins/FluxTools/menus/), list {@code job_actions} (one entry per block when the job lists
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
            Texts.send(player, "&cMétier inconnu : &7" + jobId);
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
            lore.add("&7XP &8► &#2ECF7B" + Format.decimal(a.xp()) + (a.perHealth() ? "&7 par point de vie" : ""));
            lore.add(money(player, a));
            if (a.perHealth()) lore.add("&7Vie de base &8► &#2ECF7B" + Format.decimal(a.health()) + " &7(un kill : &#2ECF7B"
                    + Format.decimal(a.xp() * a.health()) + " XP&7)");
            out.add(ListEntry.of(String.valueOf(i++)).set("name", look.name())
                    .item(fr.influencraft.flux.api.inventory.Items.dress(look.icon(), look.name(), lore, player)));
        }
        return out;
    }

    private ItemStack header(Player player) {
        String job = opened.get(player.getUniqueId());
        if (job == null) return null;
        List<JobAction> actions = Jobs.get().actions(player, job);
        List<String> lore = new ArrayList<>();
        if (actions.isEmpty()) lore.add("&7Aucune action rémunérée détectée.");
        else if (actions.get(0).trigger().equals("kill") && !actions.get(0).targets().isEmpty()) lore.add("&#B376E5Payé par point de vie de la créature tuée : un mob de haut niveau rapporte plus.");
        else if (actions.get(0).trigger().equals("catch_fish")) lore.add("&#B376E5Détail par rareté de poisson.");
        else if (actions.get(0).targets().isEmpty()) lore.add("&#B376E5Détail par type d'action.");
        else lore.add("&#B376E5Détail bloc par bloc.");
        return item(Material.PAPER, "&#FFD94D" + Jobs.get().displayName(job), lore);
    }

    /** Money of an action in the server's currency format (glyph resolved, these items skip placeholders). */
    private static String money(Player player, JobAction a) {
        if (a.money() == null) return "&7Argent &8► &8Aucun";
        var currency = fr.influencraft.flux.api.economy.Economy.main();
        String amount = Texts.placeholders(currency == null ? Format.decimal(a.money()) : currency.format(a.money()), player);
        if (a.perHealth()) return "&7Gagne &#2ECF7B" + amount + "&7 par point de vie";
        return a.every() <= 1 ? "&7Gagne &#2ECF7B" + amount + "&7 par action"
                : "&7Gagne &#2ECF7B" + amount + "&7 tous les &#2ECF7B" + Format.decimal(a.every()) + " &7fois";
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Texts.colors(name));
            meta.lore(Texts.wrapLore(lore.stream().map(Texts::colors).toList()));
            stack.setItemMeta(meta);
        }
        return stack;
    }
}

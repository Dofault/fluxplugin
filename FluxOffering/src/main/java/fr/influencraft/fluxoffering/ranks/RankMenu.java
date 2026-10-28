package fr.influencraft.fluxoffering.ranks;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.list.ListEntry;
import fr.influencraft.flux.api.list.Lists;
import fr.influencraft.flux.api.reward.Reward;
import fr.influencraft.flux.api.reward.Rewards;
import fr.influencraft.flux.api.text.Texts;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Rank menu ({@code /grade}, Flux menu {@code grades}): the ranks grouped in tiers (one banner each, weakest rank on
 * top), each tier ending in a reward chest. Ranks and tiers come from {@code ranks.yml}; the player's rank is read on
 * the permission plugin through a placeholder (LuckPerms track). Built here, placed by the menu file:
 * <ul>
 *   <li>list {@code grades}: per tier its ranks then its chest (4 tiers x 4 = 16 entries, column by column)</li>
 *   <li>list {@code grades_info}: the menu icon (current rank, next step)</li>
 *   <li>placeholders {@code %grades_states_<tier>%} (path colour of each step), {@code %grades_rank%}</li>
 *   <li>action {@code [grades] claim <tier>}: hands the tier's reward through Flux rewards (once, keyed)</li>
 * </ul>
 */
public final class RankMenu implements org.bukkit.event.Listener {

    /** Fired by {@code /influoffrande reload}: ranks.yml is read again. */
    public static final class Reload extends org.bukkit.event.Event {
        private static final org.bukkit.event.HandlerList HANDLERS = new org.bukkit.event.HandlerList();

        @Override
        public org.bukkit.event.HandlerList getHandlers() {
            return HANDLERS;
        }

        public static org.bukkit.event.HandlerList getHandlerList() {
            return HANDLERS;
        }
    }

    @org.bukkit.event.EventHandler
    public void onReload(Reload event) {
        load();
    }

    public static final String LIST = "grades", INFO = "grades_info";

    record Rank(String id, String name, String colour, List<String> description, List<String> perks) {
    }

    record Tier(String id, String name, List<Rank> ranks, String rewardLabel, String rewardIcon, int rewardSlots,
                List<String> rewardLines, List<String> rewardCommands) {
    }

    private final JavaPlugin plugin;
    /** Whether an offering (collection) exists for this id. */
    private final java.util.function.Predicate<String> offeringExists;
    private final List<Tier> tiers = new ArrayList<>();
    private final List<Rank> ranks = new ArrayList<>();
    private String placeholder;

    public RankMenu(JavaPlugin plugin, java.util.function.Predicate<String> offeringExists) {
        this.plugin = plugin;
        this.offeringExists = offeringExists;
    }

    public void enable() {
        load();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        Lists.register(plugin, LIST, query -> entries(query.viewer()));
        Lists.register(plugin, INFO, query -> List.of(info(query.viewer())));
        FluxApi.registerAction(plugin, "grades", (player, view, argument) -> {
            String[] parts = argument.trim().split("\\s+");
            if (parts.length == 2 && parts[0].equalsIgnoreCase("claim")) claim(player, parts[1]);
        });
        FluxApi.registerPlaceholders(plugin, "grades", this::placeholder);
    }

    /** Reads ranks.yml (written from the jar on first start). */
    public void load() {
        File file = new File(plugin.getDataFolder(), "ranks.yml");
        if (!file.exists()) plugin.saveResource("ranks.yml", false);
        YamlConfiguration c = YamlConfiguration.loadConfiguration(file);
        placeholder = c.getString("current-rank-placeholder", "%luckperms_current_group_on_track_standard%");
        tiers.clear();
        ranks.clear();
        ConfigurationSection rs = c.getConfigurationSection("ranks");
        ConfigurationSection ts = c.getConfigurationSection("tiers");
        if (rs == null || ts == null) return;
        for (String tid : ts.getKeys(false)) {
            ConfigurationSection t = ts.getConfigurationSection(tid);
            List<Rank> own = new ArrayList<>();
            for (String rid : t.getStringList("ranks")) {
                ConfigurationSection r = rs.getConfigurationSection(rid);
                if (r == null) continue;
                Rank rank = new Rank(rid.toLowerCase(Locale.ROOT), r.getString("name", rid), r.getString("colour", "#FFFFFF"),
                        r.getStringList("description"), r.getStringList("perks"));
                own.add(rank);
                ranks.add(rank);
            }
            ConfigurationSection rw = t.getConfigurationSection("reward");
            tiers.add(new Tier(tid, t.getString("name", tid), own,
                    rw == null ? tid : rw.getString("label", tid), rw == null ? "CHEST" : rw.getString("icon", "CHEST"),
                    rw == null ? 1 : rw.getInt("slots", 1), rw == null ? List.of() : rw.getStringList("lines"),
                    rw == null ? List.of() : rw.getStringList("commands")));
        }
        plugin.getLogger().info(ranks.size() + " rank(s) in " + tiers.size() + " tier(s).");
    }

    // ------------------------------------------------------------------ state

    /** Index of the player's rank in the ladder (0 = first), first rank when unknown. */
    private int current(Player player) {
        String id = Texts.placeholders(placeholder, player).trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < ranks.size(); i++) if (ranks.get(i).id().equals(id)) return i;
        return 0;
    }

    private int lastIndex(Tier t) {
        return ranks.indexOf(t.ranks().get(t.ranks().size() - 1));
    }

    private static String key(Tier t) {
        return "grades:tier:" + t.id();
    }

    private enum Chest { LOCKED, READY, WAITING, CLAIMED }

    /**
     * A tier is done once its last rank is validated: holding a rank is not validating it, the player validates it
     * with the offering that takes him to the next rank. So the chest opens when he reached the next tier's first
     * rank; the very last rank of the ladder, with nothing after it, counts as validated once held.
     */
    private boolean done(Tier t, int current) {
        int last = lastIndex(t);
        return current > last || (last == ranks.size() - 1 && current == last);
    }

    private Chest chest(Player player, Tier t, int current) {
        UUID uuid = player.getUniqueId();
        if (Rewards.given(uuid, key(t))) return Rewards.waiting(uuid, key(t)) ? Chest.WAITING : Chest.CLAIMED;
        return done(t, current) ? Chest.READY : Chest.LOCKED;
    }

    // ------------------------------------------------------------------ list entries

    private List<ListEntry> entries(Player player) {
        List<ListEntry> out = new ArrayList<>();
        int current = current(player);
        for (Tier t : tiers) {
            for (Rank r : t.ranks()) out.add(rankEntry(player, r, ranks.indexOf(r), current));
            out.add(chestEntry(player, t, current));
        }
        return out;
    }

    /**
     * A rank: name and number, description, perks (server lore frame, grey arrows). A click opens the rank's offering
     * (the one that gives it) for every rank up to the next one, already done ones included; further ranks stay
     * closed (the offering command checks the access again).
     */
    private ListEntry rankEntry(Player player, Rank r, int index, int current) {
        Lore lore = Lore.create();
        for (String line : r.description()) lore.line("&f" + line);
        if (!r.perks().isEmpty()) lore.line("");
        for (String perk : r.perks()) lore.line(perk);
        lore.line("");
        String icon, click = "[sound] @error";
        boolean glow = false;
        boolean offering = index <= current + 1 && offeringExists.test(r.id());
        if (index == current) {
            icon = "player-head";
            glow = true;
            lore.line(Lore.GREEN + "Ton rang actuel.").line("Valide-le avec l'offrande du rang suivant.");
        } else if (index < current) {
            icon = "itemsadder:influitems:unlock";
            lore.line(Lore.GREEN + "Validé.");
        } else if (index == current + 1) {
            icon = "itemsadder:influitems:tracking";
            lore.line("Prochain rang : fais son offrande.");
        } else {
            icon = "itemsadder:influitems:lock";
            lore.line(Lore.RED + "Pas encore accessible.");
        }
        if (offering) {
            lore.left(index == current + 1 ? "Faire l'offrande" : "Voir l'offrande");
            click = "[player] offrande " + r.id();
        }
        String name = "&" + r.colour() + "&l" + r.name() + " &7" + (index + 1) + "/" + ranks.size();
        return entry(player, "rank:" + r.id(), icon, name, lore, index + 1, glow).set("clic", click);
    }

    private ListEntry chestEntry(Player player, Tier t, int current) {
        Rank last = t.ranks().get(t.ranks().size() - 1);
        Lore lore = Lore.create().line("Récompense du palier &f" + t.name()).lines(t.rewardLines());
        String icon, click = "[sound] @error";
        switch (chest(player, t, current)) {
            case CLAIMED -> {
                icon = "itemsadder:influitems:chest_checkmark";
                lore.line(Lore.GREEN + "Récupérée.");
            }
            case WAITING -> {
                icon = "itemsadder:influitems:chest_item";
                lore.line("Elle t'attend dans tes récompenses.").left("Ouvrir les récompenses");
                click = "[menu] rewards";
            }
            case READY -> {
                icon = "itemsadder:influitems:chest_unlock";
                lore.line(Lore.GREEN + "Palier terminé !").left("Récupérer");
                click = "[grades] claim " + t.id();
            }
            default -> {
                icon = "itemsadder:influitems:chest";
                lore.line("Valide le rang &" + last.colour() + last.name() + Lore.PURPLE + " (son offrande,")
                    .line("qui fait passer au rang suivant) pour l'ouvrir.");
            }
        }
        return entry(player, "tier:" + t.id(), icon, t.rewardLabel(), lore, 1, false).set("clic", click);
    }

    private ListEntry info(Player player) {
        int current = current(player);
        Rank now = ranks.isEmpty() ? null : ranks.get(current);
        Lore lore = Lore.create().line("Monte en grade en faisant")
                .line("les offrandes : chaque grade")
                .line("débloque de nouveaux avantages.")
                .line("")
                .line("Chaque palier de 3 grades se")
                .line("termine par un coffre de récompense.");
        if (now != null) lore.line("").line("Ton grade : &" + now.colour() + now.name());
        String click = "[sound] @error";
        if (current + 1 < ranks.size()) {
            Rank next = ranks.get(current + 1);
            lore.line("Prochain : &" + next.colour() + next.name()).left("Faire l'offrande");
            click = "[player] offrande";
        }
        return entry(player, "info", "itemsadder:influitems:stairs", "Grades", lore, 1, false).set("clic", click);
    }

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

    // ------------------------------------------------------------------ placeholders, action

    /** {@code states_<tier number from 1>}: colour of each step of the tier's path; {@code rank}: current rank name. */
    private String placeholder(Player player, String params) {
        if (player == null) return "";
        int current = current(player);
        if (params.equals("rank")) return ranks.isEmpty() ? "" : ranks.get(current).name();
        if (!params.startsWith("states_")) return null;
        int n;
        try {
            n = Integer.parseInt(params.substring(7)) - 1;
        } catch (NumberFormatException e) {
            return "";
        }
        if (n < 0 || n >= tiers.size()) return "";
        Tier t = tiers.get(n);
        List<String> out = new ArrayList<>();
        for (Rank r : t.ranks()) out.add(ranks.indexOf(r) <= current ? "vert" : "eteint");
        out.add(switch (chest(player, t, current)) {
            case CLAIMED -> "vert";
            case READY, WAITING -> "orange";
            case LOCKED -> "eteint";
        });
        return String.join(",", out);
    }

    private void claim(Player player, String tierId) {
        Tier t = tiers.stream().filter(x -> x.id().equalsIgnoreCase(tierId)).findFirst().orElse(null);
        if (t == null || chest(player, t, current(player)) != Chest.READY) return;
        Reward reward = Reward.of("grades", t.rewardLabel()).key(key(t)).icon(t.rewardIcon())
                .slots(Math.max(1, t.rewardSlots())).commands(t.rewardCommands());
        if (Rewards.give(player, reward)) Rewards.claimKey(player, key(t), true);
        fr.influencraft.flux.api.util.Sounds.cue(player, "complete");
        Lists.invalidate(player, LIST);
    }
}

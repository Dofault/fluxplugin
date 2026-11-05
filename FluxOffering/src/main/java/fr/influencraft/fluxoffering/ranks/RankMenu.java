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
 * Rank menu ({@code /grade}, Flux menu {@code grades}), 2026-10-30: a route of {@code cycles x ranks} steps (3 x 12 =
 * 36): the 12 ranks, then again with "Prestige I", then "Prestige II", drawn as a snake over the chest. Ranks come
 * from {@code ranks.yml}; the player's rank is read on the permission plugin (LuckPerms track), his prestige through
 * {@code prestige-placeholder} (0 while there is no prestige). No reward between tiers for now.
 * <ul>
 *   <li>list {@code grades}: the steps of the route in order</li>
 *   <li>list {@code grades_info}: the menu icon (current rank, next step)</li>
 *   <li>placeholders {@code %grades_rank%}, {@code %grades_step%} (1-based position on the route),
 *   {@code %grades_steps%} (route length), {@code %grades_route%} (path colour of every step)</li>
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
    private String prestigePlaceholder = "0";
    /** Name added to a rank on each cycle of the route: none, Prestige I, Prestige II. */
    private List<String> cycles = List.of("", "Prestige I", "Prestige II");

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
        prestigePlaceholder = c.getString("prestige-placeholder", "0");
        if (!c.getStringList("cycles").isEmpty()) cycles = List.copyOf(c.getStringList("cycles"));
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

    /** Prestige of the player: 0 for the first cycle. */
    private int prestige(Player player) {
        try {
            int p = Integer.parseInt(Texts.placeholders(prestigePlaceholder, player).trim());
            return Math.max(0, Math.min(cycles.size() - 1, p));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Index of the player on the route (0-based): prestige x ranks + rank. */
    private int step(Player player) {
        return prestige(player) * ranks.size() + current(player);
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
        int position = step(player);
        for (int i = 0; i < ranks.size() * cycles.size(); i++) out.add(stepEntry(player, i, position));
        return out;
    }

    /**
     * A step of the route: the rank's badge, coloured once passed, greyed for the next one (which glows), the player's
     * head on his step, and past the next one a single "?" badge that tells nothing about the rank. A click opens the
     * rank's offering up to the next step (the offering command checks the access again).
     */
    private ListEntry stepEntry(Player player, int i, int position) {
        if (i > position + 1) {
            Lore hidden = Lore.create().description("Ce grade n'a pas encore été découvert.").ko("Pas encore accessible.");
            String title = "&7&l? ? ? &8" + (i + 1) + "/" + ranks.size() * cycles.size();
            return entry(player, "step:" + i, "itemsadder:fluxui:rang_hidden", title, hidden, 1, false).set("clic", "[sound] @error");
        }
        Rank r = ranks.get(i % ranks.size());
        int cycle = i / ranks.size();
        Lore lore = Lore.create().description(r.description());
        // prestige cycles give nothing more: the player keeps every perk of the last rank, so none is listed
        if (cycle == 0) for (String perk : r.perks()) lore.item(perk.replaceFirst("^&8(&m)?➤ ", ""));
        else lore.info("Tu gardes tous les avantages de " + ranks.get(ranks.size() - 1).name() + ".");
        // badge of the rank (fluxui:rang_<n>_<cycle>_<on|off>, Flux/assets/vanilla/gen_ui.py)
        String badge = "itemsadder:fluxui:rang_" + (i % ranks.size() + 1) + "_" + cycle + "_";
        String icon, click = "[sound] @error";
        boolean glow = false;
        if (i == position) {
            icon = "player-head";
            glow = true;
            lore.ok("C'est ton rang actuel.");
        } else if (i < position) {
            icon = badge + "on";
            lore.ok("Validé.");
        } else {
            icon = badge + "off";
            glow = true;
            lore.info("Prochaine étape.");
        }
        String offering = cycle == 0 ? r.id() : r.id() + "_p" + cycle;
        if (offeringExists.test(offering)) {
            lore.left(i == position + 1 ? "Faire l'offrande" : "Voir l'offrande");
            click = "[player] offrande " + offering;
        }
        String prefix = cycles.get(cycle).isBlank() ? "" : " &7" + cycles.get(cycle);
        String name = "&" + r.colour() + "&l" + r.name() + prefix + " &8" + (i + 1) + "/" + ranks.size() * cycles.size();
        return entry(player, "step:" + i, icon, name, lore, i % ranks.size() + 1, glow).set("clic", click);
    }

    private ListEntry info(Player player) {
        int current = current(player);
        Rank now = ranks.isEmpty() ? null : ranks.get(current);
        Lore lore = Lore.create().description("Monte en rang en faisant les offrandes : chaque rang débloque de nouveaux avantages.",
                "Après le 12e rang, la route repart du premier en Prestige.");
        lore.kv("Étape", (step(player) + 1) + "/" + ranks.size() * cycles.size());
        if (now != null) lore.kv("Ton rang", "&" + now.colour() + now.name());
        String click = "[sound] @error";
        if (current + 1 < ranks.size()) {
            Rank next = ranks.get(current + 1);
            lore.kv("Prochain", "&" + next.colour() + next.name()).left("Faire l'offrande");
            click = "[player] offrande";
        }
        return entry(player, "info", "itemsadder:influitems:stairs", "Rangs", lore, 1, false).set("clic", click);
    }

    private static ListEntry entry(Player player, String id, String icon, String name, Lore lore, int amount, boolean glow) {
        ItemStack item = Items.of(icon, name, lore.build(), player);
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

    /** {@code rank}: current rank name; {@code step}, {@code steps}, {@code route}: position and colours of the route. */
    private String placeholder(Player player, String params) {
        if (player == null) return "";
        int current = current(player);
        if (params.equals("rank")) return ranks.isEmpty() ? "" : ranks.get(current).name();
        if (params.equals("step")) return String.valueOf(step(player) + 1);
        if (params.equals("steps")) return String.valueOf(ranks.size() * cycles.size());
        if (params.equals("route")) {
            int position = step(player);
            List<String> out = new ArrayList<>();
            for (int i = 0; i < ranks.size() * cycles.size(); i++) out.add(i <= position ? "vert" : i == position + 1 ? "orange" : "eteint");
            return String.join(",", out);
        }
        return null;
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

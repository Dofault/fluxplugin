package fr.influencraft.flux.profile;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.economy.Currency;
import fr.influencraft.flux.api.economy.Economy;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.jobs.Jobs;
import fr.influencraft.flux.api.leaderboard.Leaderboards;
import fr.influencraft.flux.api.list.ListEntry;
import fr.influencraft.flux.api.list.Lists;
import fr.influencraft.flux.api.profile.Profiles;
import fr.influencraft.flux.api.util.Format;
import fr.influencraft.flux.api.util.Players;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The profile menu ({@code /profil [player]}, menu {@code profil}): the subject's head (list {@code profile_head}, a
 * big item model) and the sections of {@link Profiles} (list {@code profile}). Flux gives four sections itself:
 * economy, leaderboards (pages), jobs and statistics.
 */
public final class ProfileService {

    private static final int BOARDS_PER_PAGE = 4;
    private final Plugin flux;
    private final Map<String, Integer> pages = new ConcurrentHashMap<>(); // viewer:section -> page
    private final Map<UUID, Object[]> mined = new ConcurrentHashMap<>();  // subject -> {millis, blocks}
    private String headModel = "influmenu:tete_profil_b";

    public ProfileService(Plugin flux) {
        this.flux = flux;
    }

    public void start(String headModel) {
        if (headModel != null && !headModel.isBlank()) this.headModel = headModel;
        Lists.register(flux, "profile", q -> entries(q.viewer()));
        Lists.registerItem(flux, "profile_head", this::head);
        FluxApi.registerAction(flux, "profile", (player, view, argument) -> click(player, argument.trim()));
        fr.influencraft.flux.api.command.Commands.create(flux, "profile").aliases("profil")
                .description("Show a player's profile.")
                .executor((sender, label, args) -> {
                    Player p = Players.of(sender);
                    if (p == null) return true;
                    OfflinePlayer subject = args.length > 0 ? Players.find(args[0]) : p;
                    if (subject == null || (!subject.hasPlayedBefore() && !subject.isOnline())) {
                        p.sendMessage(fr.influencraft.flux.api.text.Texts.parse("&#FF5C5CJoueur inconnu.", p));
                        return true;
                    }
                    Profiles.subject(p, subject);
                    pages.keySet().removeIf(k -> k.startsWith(p.getUniqueId() + ":"));
                    FluxApi.open(p, "profil");
                    return true;
                })
                .completer((sender, args) -> args.length == 1 ? Players.complete(args[0]) : List.of())
                .register();
        Profiles.register(flux, "economy", 10, new Economy_());
        Profiles.register(flux, "leaderboards", 30, new Boards());
        Profiles.register(flux, "jobs", 70, new JobsSection());
        Profiles.register(flux, "stats", 80, new Stats());
    }

    /** {@code %flux_profile_name%}: name of the shown profile's player. */
    public String placeholder(Player viewer, String field) {
        if (viewer == null) return "";
        OfflinePlayer s = Profiles.subject(viewer);
        return switch (field) {
            case "name" -> Players.name(s, "?");
            case "own" -> String.valueOf(Profiles.own(viewer));
            default -> null;
        };
    }

    private int page(Player viewer, String section) {
        return pages.getOrDefault(viewer.getUniqueId() + ":" + section, 0);
    }

    private List<ListEntry> entries(Player viewer) {
        OfflinePlayer subject = Profiles.subject(viewer);
        List<ListEntry> out = new ArrayList<>();
        for (Profiles.Registered r : Profiles.sections()) {
            ItemStack item;
            try {
                item = r.section().item(viewer, subject, page(viewer, r.id()));
            } catch (RuntimeException e) {
                flux.getLogger().warning("Profile section " + r.id() + " failed: " + e);
                continue;
            }
            if (item != null) out.add(ListEntry.of(r.id()).item(item).set("id", r.id()));
        }
        return out;
    }

    /** {@code [profile] left|right <section>}: page turn, else the section's own click. */
    private void click(Player viewer, String argument) {
        String[] parts = argument.split(" ", 2);
        if (parts.length < 2) return;
        Profiles.Registered r = Profiles.section(parts[1]);
        if (r == null) return;
        boolean right = parts[0].equals("right");
        OfflinePlayer subject = Profiles.subject(viewer);
        int count = r.section().pages(viewer, subject);
        if (count > 1) {
            String key = viewer.getUniqueId() + ":" + r.id();
            int page = pages.getOrDefault(key, 0);
            pages.put(key, Math.floorMod(page + (right ? -1 : 1), count));
            fr.influencraft.flux.api.util.Sounds.cue(viewer, "page");
        } else {
            r.section().click(viewer, subject, right);
        }
        Lists.invalidate(viewer, "profile");
    }

    private ItemStack head(Player viewer) {
        OfflinePlayer subject = Profiles.subject(viewer);
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        if (item.getItemMeta() instanceof SkullMeta skull) {
            skull.setPlayerProfile(subject instanceof Player online ? online.getPlayerProfile() : Bukkit.createProfile(subject.getUniqueId(), subject.getName()));
            NamespacedKey model = NamespacedKey.fromString(headModel);
            if (model != null) skull.setItemModel(model);
            skull.setHideTooltip(false);
            item.setItemMeta(skull);
        }
        String rank = fr.influencraft.flux.api.text.Texts.placeholders("%luckperms_prefix%", subject);
        Lore lore = Lore.create();
        if (rank != null && !rank.contains("%")) lore.kv("Rang", rank);
        lore.kv("Arrivé le", subject.getFirstPlayed() > 0 ? Format.date(subject.getFirstPlayed()) : "-");
        lore.kv("En ligne", subject.isOnline() ? "oui" : "non");
        return Items.dress(item, Players.name(subject, "?"), lore.build(), viewer);
    }

    // ---------------------------------------------------------------- sections of Flux

    private static ItemStack button(Player viewer, String material, String name, Lore lore) {
        return Items.of(material, name, lore.build(), viewer);
    }

    private final class Economy_ implements Profiles.Section {
        public ItemStack item(Player viewer, OfflinePlayer s, int page) {
            Lore lore = Lore.create();
            for (Currency c : Economy.all()) {
                String label = switch (c.id()) {
                    case "vault" -> "BitBlock";
                    case "playerpoints" -> "Influence";
                    default -> null; // casino chips and the like: not part of the profile
                };
                if (label != null) lore.kv(label, c.format(c.balance(s)));
            }
            Integer rank = Leaderboards.board("fortune") == null ? null : Leaderboards.rank("fortune", s.getUniqueId());
            if (rank != null && rank > 0) lore.kv("Classement fortune", place(rank));
            return button(viewer, "itemsadder:influitems:bitblock", "Économie", lore);
        }
    }

    private final class Boards implements Profiles.Section {
        public int pages(Player viewer, OfflinePlayer s) {
            return Math.max(1, (Leaderboards.boards().size() + BOARDS_PER_PAGE - 1) / BOARDS_PER_PAGE);
        }

        public ItemStack item(Player viewer, OfflinePlayer s, int page) {
            List<String> ids = new ArrayList<>(Leaderboards.boards().keySet());
            ids.sort(String::compareTo);
            int count = pages(viewer, s);
            page = Math.min(page, count - 1);
            Lore lore = Lore.create();
            for (int i = page * BOARDS_PER_PAGE; i < Math.min(ids.size(), (page + 1) * BOARDS_PER_PAGE); i++) {
                String id = ids.get(i);
                int rank = Leaderboards.rank(id, s.getUniqueId());
                Double value = Leaderboards.value(id, s.getUniqueId());
                String shown = rank == 0 || value == null ? "-" : place(rank) + " &8(&7" + Leaderboards.board(id).format().apply(value) + "&8)";
                lore.kv(Leaderboards.name(id), shown);
            }
            if (count > 1) {
                lore.info("Page " + (page + 1) + "/" + count);
                lore.left("Page suivante").right("Page précédente");
            }
            return button(viewer, "itemsadder:influitems:leaderboard", "Classement", lore);
        }
    }

    private final class JobsSection implements Profiles.Section {
        public ItemStack item(Player viewer, OfflinePlayer s, int page) {
            Lore lore = Lore.create();
            if (!Jobs.available()) return null;
            Player online = s.getPlayer();
            if (online == null) {
                lore.ko("Visible quand le joueur est en ligne.");
            } else {
                int total = 0;
                for (String job : Jobs.get().ids()) {
                    int level = Jobs.get().level(online, job);
                    total += level;
                    if (level > 0) lore.item(strip(Jobs.get().displayName(job)) + " &8· &f" + level);
                }
                lore.kv("Niveau cumulé", total);
            }
            return button(viewer, "IRON_PICKAXE", "Métiers", lore);
        }
    }

    private final class Stats implements Profiles.Section {
        public ItemStack item(Player viewer, OfflinePlayer s, int page) {
            Lore lore = Lore.create();
            lore.kv("Temps de jeu", Format.duration(s.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L));
            lore.kv("Blocs cassés", s.isOnline() ? Format.number(mined(s)) : "-");
            lore.kv("Monstres vaincus", Format.number(s.getStatistic(Statistic.MOB_KILLS)));
            lore.kv("Morts", Format.number(s.getStatistic(Statistic.DEATHS)));
            lore.kv("Distance à pied", Format.number(s.getStatistic(Statistic.WALK_ONE_CM) / 100000.0) + " km");
            return button(viewer, "CLOCK", "Statistiques", lore);
        }
    }

    /** Blocks mined (every block type), counted at most every 5 minutes per player. */
    private long mined(OfflinePlayer s) {
        Object[] hit = mined.get(s.getUniqueId());
        if (hit != null && System.currentTimeMillis() - (long) hit[0] < 300_000) return (long) hit[1];
        long total = 0;
        for (Material m : Material.values()) {
            if (m.isBlock() && !m.isLegacy() && m.isItem()) {
                try {
                    total += s.getStatistic(Statistic.MINE_BLOCK, m);
                } catch (IllegalArgumentException ignored) {
                    // not a countable block
                }
            }
        }
        mined.put(s.getUniqueId(), new Object[]{System.currentTimeMillis(), total});
        return total;
    }

    private static String place(int rank) {
        return rank == 1 ? Format.word("first-place") : rank + Format.word("nth-place");
    }

    private static String strip(String s) {
        return s.replaceAll("&#[0-9A-Fa-f]{6}|&[0-9a-fk-or]", "");
    }
}

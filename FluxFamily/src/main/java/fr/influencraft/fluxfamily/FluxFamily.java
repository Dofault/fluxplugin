package fr.influencraft.fluxfamily;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.command.Commands;
import fr.influencraft.flux.api.dialog.Dialogs;
import fr.influencraft.flux.api.economy.Origin;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.leaderboard.Leaderboards;
import fr.influencraft.flux.api.list.ListEntry;
import fr.influencraft.flux.api.list.Lists;
import fr.influencraft.flux.api.profile.Profiles;
import fr.influencraft.flux.api.tab.TabList;
import fr.influencraft.flux.api.util.Format;
import fr.influencraft.flux.api.util.Players;
import fr.influencraft.flux.api.util.Sounds;
import fr.influencraft.fluxfamily.FamilyStore.Adoption;
import fr.influencraft.fluxfamily.FamilyStore.Couple;
import fr.influencraft.fluxfamily.FamilyStore.Home;
import fr.influencraft.fluxfamily.api.Family;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * FluxFamily: couples, marriage and family trees.
 * <ul>
 *   <li>Couple: free, both players agree. Marriage: a couple's next step, both agree, paid once by the one who
 *   proposes ({@code marriage.price}). Leaving a marriage leaves the « Divorcé » tag to the one who leaves, for good.</li>
 *   <li>One home per married couple ({@code /famille sethome}, {@code /famille home}), with the teleport warm-up of
 *   the resource worlds.</li>
 *   <li>Family tree: a player's parent is the player who adopted him, else his sponsor (FluxReferral, through
 *   {@link Family#lineage}). A married couple shares its children. Menu {@code famille}, focused on any player.</li>
 * </ul>
 * Shown in the tab list ({@code {married}}), the profile, FluxTags (status and divorced tags) and the leaderboard
 * {@code famille} (size of the family). API for the other plugins: {@link Family}.
 */
public final class FluxFamily extends FluxPlugin implements Listener {

    private enum Kind { COUPLE, MARRIAGE, ADOPTION }

    private record Request(Kind kind, UUID from, long at) {
    }

    /** Words players type after /famille, English keys of {@code words.<verb>} in the lang file. */
    private static final List<String> VERBS = List.of("couple", "marry", "leave", "adopt", "accept", "deny", "home",
            "sethome", "emancipate", "disown", "tree");

    private final FamilyStore store = new FamilyStore(getLogger());
    private final Tree tree = new Tree(store);
    /** Pending request of each target (the newest replaces the older one). */
    private final Map<UUID, Request> requests = new HashMap<>();
    /** Whose family each viewer is looking at in the menu (himself when absent). */
    private final Map<UUID, UUID> focus = new HashMap<>();
    private final Map<UUID, BukkitTask> warmups = new HashMap<>();

    private double price;
    private long requestMs;
    private int warmup;
    private List<String> instantWorlds = List.of();
    private List<String> blockedWorlds = List.of();
    private boolean announce;
    private boolean tags;

    @Override
    protected void enable() {
        settings();
        store.open();
        Family.bind(new Family.Service() {
            public Family.Status status(UUID player) {
                Couple c = store.couple(player);
                return c == null ? Family.Status.SINGLE : c.married() ? Family.Status.MARRIED : Family.Status.COUPLE;
            }

            public UUID partner(UUID player) {
                Couple c = store.couple(player);
                return c == null ? null : c.other(player);
            }

            public boolean divorced(UUID player) {
                return store.divorced(player);
            }

            public boolean importCouple(UUID a, UUID b, boolean married, long since, Location home) {
                if (a.equals(b) || store.couple(a) != null || store.couple(b) != null) return false;
                store.save(Couple.of(a, b, married, since, married ? since : 0, Home.of(home)));
                return true;
            }

            public boolean importAdoption(UUID parent, UUID child, long since) {
                if (parent.equals(child) || store.adoption(child) != null) return false;
                store.save(new Adoption(parent, child, since));
                return true;
            }
        });
        tags = Bukkit.getPluginManager().isPluginEnabled("FluxTags");
        if (tags) FamilyTags.register(this);
        TabList.piece(this, "married", p -> Family.married(p.getUniqueId()) ? getConfig().getString("tab.married", "") : "");
        placeholders("fluxfamily", this::placeholder);
        list("famille_info", q -> List.of(ListEntry.of("info").item(infoItem(q.viewer())).set("clic", "[sound] @click")));
        list("famille_parents", q -> parentEntries(q.viewer()));
        list("famille_couple", q -> coupleEntries(q.viewer()));
        list("famille_enfants", q -> childEntries(q.viewer()));
        list("famille_actions", q -> actionEntries(q.viewer()));
        action("famille", (player, view, argument) -> menuAction(player, argument.trim()));
        command("family")
                .description("Your family: couple, marriage, adoption, couple home.")
                .permission("fluxfamily.use")
                .executor(this::onFamily)
                .completer(this::completeFamily)
                .register();
        command("fluxfamily")
                .description("Families administration.")
                .permission("fluxfamily.admin")
                .usage(messages().raw("admin-usage"))
                .executor(this::onAdmin)
                .completer((sender, args) -> args.length == 1 ? List.of("info", "separate", "reload")
                        : args.length == 2 ? Players.complete(args[1]) : List.of())
                .register();
        Profiles.register(this, "famille", 70, new Profiles.Section() {
            public ItemStack item(Player viewer, OfflinePlayer subject, int page) {
                return profileItem(viewer, subject);
            }

            public boolean click(Player viewer, OfflinePlayer subject, boolean right) {
                if (!right) open(viewer, subject.getUniqueId());
                return false;
            }
        });
        Leaderboards.register(this, "famille", player -> {
            int size = tree.size(player.getUniqueId());
            return size == 0 ? null : (double) size;
        }, v -> Format.number(v));
        Leaderboards.name("famille", messages().raw("leaderboard-name"));
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    @Override
    protected void disable() {
        warmups.values().forEach(BukkitTask::cancel);
        Family.bind(null);
        Family.unregisterAll(this);
        if (tags) fr.influencraft.fluxtags.api.Tags.unregisterAll(this);
    }

    @Override
    public void reload() {
        super.reload();
        settings();
    }

    private void settings() {
        price = getConfig().getDouble("marriage.price", 500000);
        announce = getConfig().getBoolean("marriage.announce", true);
        requestMs = getConfig().getLong("request-seconds", 120) * 1000L;
        warmup = getConfig().getInt("home.warmup-seconds", 5);
        instantWorlds = lower(getConfig().getStringList("home.instant-worlds"));
        blockedWorlds = lower(getConfig().getStringList("home.blocked-worlds"));
    }

    private static List<String> lower(List<String> list) {
        return list.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
    }

    // ---------------------------------------------------------------- helpers

    private String cmd() {
        return Commands.label(getName(), "family");
    }

    private String word(String verb) {
        String w = messages().raw("words." + verb);
        return w.isEmpty() ? verb : w;
    }

    /** English verb of a typed word (French word of the lang file, or the English one). */
    private String verb(String typed) {
        for (String v : VERBS) if (typed.equalsIgnoreCase(v) || typed.equalsIgnoreCase(word(v))) return v;
        return null;
    }

    private static String name(UUID id) {
        return Players.name(Bukkit.getOfflinePlayer(id), "?");
    }

    private void send(UUID id, String key, String... vars) {
        Player p = Bukkit.getPlayer(id);
        if (p != null) messages().send(p, key, vars);
    }

    private String[] vars(String... more) {
        List<String> out = new ArrayList<>(List.of("%cmd%", cmd(), "%price%", money().format(price),
                "%accept%", word("accept"), "%deny%", word("deny")));
        out.addAll(List.of(more));
        return out.toArray(String[]::new);
    }

    private String status(UUID id) {
        return messages().raw("status." + Family.status(id).name().toLowerCase(Locale.ROOT));
    }

    private void refresh(UUID... players) {
        for (UUID id : players) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) TabList.refresh(p);
        }
        for (String list : List.of("famille_info", "famille_parents", "famille_couple", "famille_enfants", "famille_actions"))
            Lists.invalidate(list);
    }

    // ---------------------------------------------------------------- commands

    private boolean onFamily(CommandSender sender, String label, String[] args) {
        Player p = Players.of(sender);
        if (p == null) return true;
        if (args.length == 0) {
            open(p, p.getUniqueId());
            return true;
        }
        String verb = verb(args[0]);
        String arg = args.length > 1 ? args[1] : null;
        if (verb == null) {
            OfflinePlayer target = Players.find(args[0]);
            if (target == null) messages().send(p, "usage", vars());
            else open(p, target.getUniqueId());
            return true;
        }
        run(p, verb, arg);
        return true;
    }

    private void run(Player p, String verb, String arg) {
        switch (verb) {
            case "couple" -> couple(p, arg);
            case "marry" -> marry(p);
            case "leave" -> leave(p);
            case "adopt" -> adopt(p, arg);
            case "accept" -> accept(p);
            case "deny" -> deny(p);
            case "home" -> home(p);
            case "sethome" -> setHome(p);
            case "emancipate" -> emancipate(p);
            case "disown" -> disown(p, arg);
            default -> {
                OfflinePlayer target = arg == null ? p : Players.find(arg);
                if (target == null) messages().send(p, "unknown-player");
                else open(p, target.getUniqueId());
            }
        }
    }

    private List<String> completeFamily(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            for (String v : VERBS) if (word(v).startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(word(v));
            out.addAll(Players.complete(args[0]));
            return out;
        }
        String verb = verb(args[0]);
        if (args.length == 2 && verb != null && List.of("couple", "adopt", "disown", "tree").contains(verb)) return Players.complete(args[1]);
        return List.of();
    }

    /** An online player other than {@code p}, or null after telling why. */
    private Player other(Player p, String name) {
        Player t = name == null ? null : Bukkit.getPlayerExact(name);
        if (t == null) {
            messages().send(p, "not-online", vars("%player%", name == null ? "?" : name));
            return null;
        }
        if (t.equals(p)) {
            messages().send(p, "self");
            return null;
        }
        return t;
    }

    private void couple(Player p, String name) {
        if (!p.hasPermission("fluxfamily.couple")) {
            messages().send(p, "no-permission-couple");
            return;
        }
        Player t = other(p, name);
        if (t == null) return;
        if (store.couple(p.getUniqueId()) != null) {
            messages().send(p, "already-couple");
            return;
        }
        if (store.couple(t.getUniqueId()) != null) {
            messages().send(p, "other-in-couple", vars("%player%", t.getName()));
            return;
        }
        request(p, t, Kind.COUPLE);
    }

    private void marry(Player p) {
        if (!p.hasPermission("fluxfamily.marry")) {
            messages().send(p, "no-permission-marry");
            return;
        }
        Couple c = store.couple(p.getUniqueId());
        if (c == null) {
            messages().send(p, "not-couple", vars());
            return;
        }
        if (c.married()) {
            messages().send(p, "already-married");
            return;
        }
        Player partner = Bukkit.getPlayer(c.other(p.getUniqueId()));
        if (partner == null) {
            messages().send(p, "partner-offline");
            return;
        }
        if (!money().has(p, price)) {
            messages().send(p, "no-money", vars());
            return;
        }
        Dialogs.confirm(p, messages().raw("marry-confirm-title"),
                messages().raw("marry-confirm", vars("%player%", partner.getName())),
                yes -> {
                    Couple now = store.couple(p.getUniqueId());
                    Player still = Bukkit.getPlayer(c.other(p.getUniqueId()));
                    if (now == null || now.married() || still == null) return;
                    request(p, still, Kind.MARRIAGE);
                }, no -> {
                });
    }

    private void request(Player from, Player to, Kind kind) {
        requests.put(to.getUniqueId(), new Request(kind, from.getUniqueId(), System.currentTimeMillis()));
        String k = kind.name().toLowerCase(Locale.ROOT);
        messages().send(from, "sent-" + k, vars("%player%", to.getName()));
        messages().send(to, "request-" + k, vars("%player%", from.getName()));
        Sounds.cue(to, "alert");
    }

    private void accept(Player p) {
        Request r = requests.remove(p.getUniqueId());
        if (r == null || System.currentTimeMillis() - r.at() > requestMs) {
            messages().send(p, "no-request");
            return;
        }
        UUID me = p.getUniqueId(), from = r.from();
        long now = System.currentTimeMillis();
        switch (r.kind()) {
            case COUPLE -> {
                if (store.couple(me) != null || store.couple(from) != null) {
                    messages().send(p, "request-outdated");
                    return;
                }
                store.save(Couple.of(me, from, false, now, 0, null));
                messages().send(p, "couple-done", vars("%player%", name(from)));
                send(from, "couple-done", vars("%player%", p.getName()));
            }
            case MARRIAGE -> {
                Couple c = store.couple(me);
                if (c == null || c.married() || !c.other(me).equals(from)) {
                    messages().send(p, "request-outdated");
                    return;
                }
                OfflinePlayer payer = Bukkit.getOfflinePlayer(from);
                if (!money().take(payer, price, Origin.of(this, "marriage").detail(p.getName()).notifyPlayer())) {
                    messages().send(p, "payment-failed", vars("%player%", name(from)));
                    send(from, "payment-failed-payer", vars());
                    return;
                }
                store.save(c.marry(now));
                messages().send(p, "married", vars("%player%", name(from)));
                send(from, "married", vars("%player%", p.getName()));
                if (announce) for (Player online : Bukkit.getOnlinePlayers())
                    messages().send(online, "married-broadcast", vars("%a%", name(from), "%b%", p.getName()));
            }
            case ADOPTION -> {
                String refused = adoptionRefusal(from, me);
                if (refused != null) {
                    messages().send(p, "request-outdated");
                    return;
                }
                store.save(new Adoption(from, me, now));
                messages().send(p, "adopted-child", vars("%player%", name(from)));
                send(from, "adopted-parent", vars("%player%", p.getName()));
            }
        }
        Sounds.cue(p, "complete");
        Player sender = Bukkit.getPlayer(from);
        if (sender != null) Sounds.cue(sender, "complete");
        refresh(me, from);
    }

    private void deny(Player p) {
        Request r = requests.remove(p.getUniqueId());
        if (r == null) {
            messages().send(p, "no-request");
            return;
        }
        messages().send(p, "denied");
        send(r.from(), "denied-sender", vars("%player%", p.getName()));
    }

    private void leave(Player p) {
        Couple c = store.couple(p.getUniqueId());
        if (c == null) {
            messages().send(p, "not-couple", vars());
            return;
        }
        String partner = name(c.other(p.getUniqueId()));
        Dialogs.confirm(p, messages().raw(c.married() ? "divorce-confirm-title" : "leave-confirm-title"),
                messages().raw(c.married() ? "divorce-confirm" : "leave-confirm", vars("%player%", partner)),
                yes -> separate(p.getUniqueId(), true), no -> {
                });
    }

    /** Ends the couple of a player; {@code byHim}: he left (a married leaver becomes divorced). */
    private void separate(UUID leaver, boolean byHim) {
        Couple c = store.couple(leaver);
        if (c == null) return;
        UUID other = c.other(leaver);
        store.delete(c);
        if (c.married() && byHim) {
            store.divorce(leaver, other, System.currentTimeMillis());
            if (tags) FamilyTags.afterDivorce(leaver);
        }
        String k = c.married() ? "divorced" : "separated";
        send(leaver, k, vars("%player%", name(other)));
        send(other, k + "-partner", vars("%player%", name(leaver)));
        refresh(leaver, other);
    }

    /** Why {@code parent} cannot adopt {@code child} (message key), null when he can. */
    private String adoptionRefusal(UUID parent, UUID child) {
        if (parent.equals(child)) return "self";
        Couple c = store.couple(parent);
        if (c != null && c.other(parent).equals(child)) return "adopt-partner";
        if (tree.parent(child) != null) return "adopt-has-parent";
        if (tree.descendsFrom(parent, child)) return "adopt-ancestor";
        return null;
    }

    private void adopt(Player p, String name) {
        if (!p.hasPermission("fluxfamily.adopt")) {
            messages().send(p, "no-permission-adopt");
            return;
        }
        Player t = other(p, name);
        if (t == null) return;
        String refused = adoptionRefusal(p.getUniqueId(), t.getUniqueId());
        if (refused != null) {
            messages().send(p, refused, vars("%player%", t.getName()));
            return;
        }
        request(p, t, Kind.ADOPTION);
    }

    private void emancipate(Player p) {
        Adoption a = store.adoption(p.getUniqueId());
        if (a == null) {
            messages().send(p, "not-adopted");
            return;
        }
        store.deleteAdoption(p.getUniqueId());
        messages().send(p, "emancipated", vars("%player%", name(a.parent())));
        send(a.parent(), "emancipated-parent", vars("%player%", p.getName()));
        refresh(p.getUniqueId(), a.parent());
    }

    private void disown(Player p, String name) {
        OfflinePlayer t = name == null ? null : Players.find(name);
        Adoption a = t == null ? null : store.adoption(t.getUniqueId());
        if (a == null || !a.parent().equals(p.getUniqueId())) {
            messages().send(p, "not-your-child", vars("%player%", name == null ? "?" : name));
            return;
        }
        store.deleteAdoption(t.getUniqueId());
        messages().send(p, "disowned", vars("%player%", Players.name(t, name)));
        send(t.getUniqueId(), "disowned-child", vars("%player%", p.getName()));
        refresh(p.getUniqueId(), t.getUniqueId());
    }

    // ---------------------------------------------------------------- couple home

    private Couple marriedCouple(Player p) {
        Couple c = store.couple(p.getUniqueId());
        if (c == null || !c.married()) {
            messages().send(p, "not-married");
            return null;
        }
        return c;
    }

    private void setHome(Player p) {
        Couple c = marriedCouple(p);
        if (c == null) return;
        if (blockedWorlds.contains(p.getWorld().getName().toLowerCase(Locale.ROOT)) && !p.hasPermission("fluxfamily.bypass.worlds")) {
            messages().send(p, "home-world");
            return;
        }
        store.save(c.home(Home.of(p.getLocation())));
        messages().send(p, "home-set");
        send(c.other(p.getUniqueId()), "home-set-partner", vars("%player%", p.getName()));
    }

    private void home(Player p) {
        Couple c = marriedCouple(p);
        if (c == null) return;
        Location to = c.home() == null ? null : c.home().location();
        if (to == null) {
            messages().send(p, c.home() == null ? "no-home" : "home-unavailable", vars());
            return;
        }
        BukkitTask previous = warmups.remove(p.getUniqueId());
        if (previous != null) previous.cancel();
        if (warmup <= 0 || p.hasPermission("fluxfamily.bypass.warmup")
                || instantWorlds.contains(p.getWorld().getName().toLowerCase(Locale.ROOT))) {
            p.teleportAsync(to);
            messages().send(p, "home-teleported");
            return;
        }
        // the warm-up of the resource worlds: stand still, else it is cancelled
        Location start = p.getLocation().clone();
        int[] left = {warmup};
        messages().send(p, "home-warmup", "%seconds%", String.valueOf(warmup));
        warmups.put(p.getUniqueId(), Bukkit.getScheduler().runTaskTimer(this, () -> {
            boolean moved = !p.isOnline() || p.getWorld() != start.getWorld() || p.getLocation().distanceSquared(start) > 0.25;
            if (moved || --left[0] <= 0) {
                BukkitTask task = warmups.remove(p.getUniqueId());
                if (task != null) task.cancel();
                if (!p.isOnline()) return;
                if (moved) {
                    messages().send(p, "home-cancelled");
                    return;
                }
                p.teleportAsync(to);
                messages().send(p, "home-teleported");
            }
        }, 20L, 20L));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        BukkitTask task = warmups.remove(id);
        if (task != null) task.cancel();
        focus.remove(id);
    }

    // ---------------------------------------------------------------- menu

    private void open(Player viewer, UUID subject) {
        focus.put(viewer.getUniqueId(), subject);
        Lists.reset(viewer, "famille_enfants");
        FluxApi.open(viewer, "famille");
    }

    private UUID focus(Player viewer) {
        return focus.getOrDefault(viewer.getUniqueId(), viewer.getUniqueId());
    }

    /** {@code [famille] focus <uuid>}, {@code [famille] back}, or a verb of /famille (the menu closes first). */
    private void menuAction(Player p, String argument) {
        String[] parts = argument.split("\\s+", 2);
        String verb = parts[0].toLowerCase(Locale.ROOT);
        String arg = parts.length > 1 ? parts[1] : null;
        if (verb.equals("focus") && arg != null) {
            try {
                focus.put(p.getUniqueId(), UUID.fromString(arg));
            } catch (IllegalArgumentException e) {
                return;
            }
            Lists.reset(p, "famille_enfants");
            refreshMenu(p);
            return;
        }
        if (verb.equals("back")) {
            if (!focus(p).equals(p.getUniqueId())) {
                focus.put(p.getUniqueId(), p.getUniqueId());
                Lists.reset(p, "famille_enfants");
                refreshMenu(p);
            } else {
                FluxApi.open(p, "main");
            }
            return;
        }
        if (!VERBS.contains(verb)) return;
        p.closeInventory();
        run(p, verb, arg);
    }

    private void refreshMenu(Player p) {
        for (String list : List.of("famille_info", "famille_parents", "famille_couple", "famille_enfants", "famille_actions"))
            Lists.invalidate(p, list);
    }

    private ItemStack head(UUID id, String title, Lore lore, Player viewer) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (head.getItemMeta() instanceof SkullMeta skull) {
            skull.setPlayerProfile(Bukkit.createProfile(id, Bukkit.getOfflinePlayer(id).getName()));
            head.setItemMeta(skull);
        }
        return Items.dress(head, title, lore.build(), viewer);
    }

    private ListEntry headEntry(Player viewer, UUID id, Lore lore) {
        lore.left(messages().raw("menu.see-family"));
        return ListEntry.of(id.toString()).item(head(id, name(id), lore, viewer)).set("clic", "[famille] focus " + id);
    }

    /** What links a child to his parent: adoption, or the id of the lineage that knows him. */
    private String link(UUID child) {
        if (tree.adopted(child)) return messages().raw("menu.link-adoption");
        for (Map.Entry<String, Family.Lineage> l : Family.lineages().entrySet())
            if (l.getValue().parent(child) != null) {
                String key = "menu.link-" + l.getKey();
                return messages().has(key) ? messages().raw(key) : l.getKey();
            }
        return "-";
    }

    private ItemStack infoItem(Player viewer) {
        UUID f = focus(viewer);
        Lore lore = Lore.create().description(messages().raw("menu.info-description"));
        lore.kv(messages().raw("menu.family-of"), name(f));
        lore.kv(messages().raw("menu.members"), tree.size(f));
        int rank = Leaderboards.rank("famille", f);
        if (rank > 0) lore.kv(messages().raw("menu.rank"), "#" + rank);
        return Items.of("itemsadder:influitems:referral", messages().raw("menu.info-name"), lore.build(), viewer);
    }

    private List<ListEntry> parentEntries(Player viewer) {
        UUID f = focus(viewer);
        List<ListEntry> out = new ArrayList<>();
        List<UUID> parents = tree.parents(f);
        for (int i = 0; i < parents.size(); i++) {
            Lore lore = Lore.create();
            lore.kv(messages().raw("menu.role"), messages().raw(i == 0 ? "menu.role-parent" : "menu.role-parent-spouse"));
            if (i == 0) lore.kv(messages().raw("menu.link"), link(f));
            out.add(headEntry(viewer, parents.get(i), lore));
        }
        return out;
    }

    private List<ListEntry> coupleEntries(Player viewer) {
        UUID f = focus(viewer);
        List<ListEntry> out = new ArrayList<>();
        Lore me = Lore.create();
        me.kv(messages().raw("menu.status"), status(f));
        me.kv(messages().raw("menu.children"), tree.familyChildren(f).size());
        if (store.divorced(f)) me.info(messages().raw("menu.divorced"));
        out.add(headEntry(viewer, f, me));
        Couple c = store.couple(f);
        Family.Status s = Family.status(f);
        Lore bond = Lore.create().description(messages().raw("menu.bond-" + s.name().toLowerCase(Locale.ROOT)));
        if (c != null) lore(bond, c);
        String material = switch (s) {
            case MARRIED -> "ROSE_BUSH";
            case COUPLE -> "PINK_TULIP";
            case SINGLE -> "LILY_OF_THE_VALLEY";
        };
        out.add(ListEntry.of("bond").item(Items.of(material, status(f), bond.build(), viewer)).set("clic", "[sound] @click"));
        if (c != null) {
            Lore partner = Lore.create().kv(messages().raw("menu.role"), messages().raw(c.married() ? "menu.role-spouse" : "menu.role-partner"));
            out.add(headEntry(viewer, c.other(f), partner));
        }
        return out;
    }

    private void lore(Lore lore, Couple c) {
        lore.kv(messages().raw("menu.together-since"), Format.date(c.since()));
        if (c.married()) lore.kv(messages().raw("menu.married-since"), Format.date(c.marriedAt()));
    }

    private List<ListEntry> childEntries(Player viewer) {
        UUID f = focus(viewer);
        List<ListEntry> out = new ArrayList<>();
        List<UUID> children = tree.familyChildren(f);
        children.sort((a, b) -> name(a).compareToIgnoreCase(name(b)));
        for (UUID c : children) {
            Lore lore = Lore.create();
            UUID parent = tree.parent(c);
            if (parent != null) lore.kv(messages().raw("menu.role-parent"), name(parent));
            lore.kv(messages().raw("menu.link"), link(c));
            lore.kv(messages().raw("menu.children"), tree.familyChildren(c).size());
            out.add(headEntry(viewer, c, lore));
        }
        return out;
    }

    /** The buttons under the tree: what the viewer can do here (on himself or on the player he looks at). */
    private List<ListEntry> actionEntries(Player viewer) {
        UUID me = viewer.getUniqueId(), f = focus(viewer);
        Couple mine = store.couple(me);
        List<ListEntry> out = new ArrayList<>();
        if (f.equals(me)) {
            if (mine != null && mine.married()) {
                Lore home = Lore.create().description(messages().raw("menu.home-description"));
                if (mine.home() != null) home.kv(messages().raw("menu.home-world"), mine.home().world());
                home.left(messages().raw("menu.home-go")).right(messages().raw("menu.home-set"));
                out.add(button("home", "itemsadder:influitems:home", "menu.home", home, viewer, "[famille] home", "[famille] sethome"));
            }
            if (mine != null && !mine.married()) {
                Lore marry = Lore.create().description(messages().raw("menu.marry-description", vars()));
                marry.kv(messages().raw("menu.price"), money().format(price));
                if (!viewer.hasPermission("fluxfamily.marry")) marry.ko(messages().raw("menu.marry-locked"));
                else marry.left(messages().raw("menu.marry"));
                out.add(button("marry", "itemsadder:influitems:accept", "menu.marry", marry, viewer, "[famille] marry", null));
            }
            if (mine != null) {
                Lore leave = Lore.create().description(messages().raw(mine.married() ? "menu.divorce-description" : "menu.leave-description"));
                leave.left(messages().raw(mine.married() ? "menu.divorce" : "menu.leave"));
                out.add(button("leave", "itemsadder:influitems:deny", mine.married() ? "menu.divorce" : "menu.leave", leave, viewer, "[famille] leave", null));
            }
            if (mine == null) {
                Lore single = Lore.create().description(messages().raw("menu.single-description", vars()));
                out.add(button("single", "itemsadder:influitems:unknown", "menu.single", single, viewer, null, null));
            }
            if (tree.adopted(me)) {
                Lore free = Lore.create().description(messages().raw("menu.emancipate-description"));
                free.left(messages().raw("menu.emancipate"));
                out.add(button("emancipate", "itemsadder:influitems:remove", "menu.emancipate", free, viewer, "[famille] emancipate", null));
            }
            return out;
        }
        String target = name(f);
        boolean online = Bukkit.getPlayer(f) != null;
        if (mine == null && store.couple(f) == null) {
            Lore couple = Lore.create().description(messages().raw("menu.couple-description", vars("%player%", target)));
            if (!online) couple.ko(messages().raw("menu.offline"));
            else couple.left(messages().raw("menu.couple"));
            out.add(button("couple", "itemsadder:influitems:add", "menu.couple", couple, viewer, online ? "[famille] couple " + target : null, null));
        }
        if (mine != null && !mine.married() && mine.other(me).equals(f)) {
            Lore marry = Lore.create().description(messages().raw("menu.marry-description", vars()));
            marry.kv(messages().raw("menu.price"), money().format(price));
            marry.left(messages().raw("menu.marry"));
            out.add(button("marry", "itemsadder:influitems:accept", "menu.marry", marry, viewer, "[famille] marry", null));
        }
        if (viewer.hasPermission("fluxfamily.adopt") && adoptionRefusal(me, f) == null) {
            Lore adopt = Lore.create().description(messages().raw("menu.adopt-description", vars("%player%", target)));
            if (!online) adopt.ko(messages().raw("menu.offline"));
            else adopt.left(messages().raw("menu.adopt"));
            out.add(button("adopt", "itemsadder:influitems:referral", "menu.adopt", adopt, viewer, online ? "[famille] adopt " + target : null, null));
        }
        Adoption a = store.adoption(f);
        if (a != null && a.parent().equals(me)) {
            Lore disown = Lore.create().description(messages().raw("menu.disown-description", vars("%player%", target)));
            disown.left(messages().raw("menu.disown"));
            out.add(button("disown", "itemsadder:influitems:remove", "menu.disown", disown, viewer, "[famille] disown " + target, null));
        }
        return out;
    }

    private ListEntry button(String id, String material, String nameKey, Lore lore, Player viewer, String left, String right) {
        return ListEntry.of(id).item(Items.of(material, messages().raw(nameKey), lore.build(), viewer))
                .set("clic", left == null ? "[sound] @error" : left)
                .set("droit", right == null ? (left == null ? "[sound] @error" : left) : right);
    }

    // ---------------------------------------------------------------- profile, placeholders, admin

    private ItemStack profileItem(Player viewer, OfflinePlayer subject) {
        UUID id = subject.getUniqueId();
        Lore lore = Lore.create();
        lore.kv(messages().raw("menu.status"), status(id));
        Couple c = store.couple(id);
        if (c != null) {
            lore.kv(messages().raw(c.married() ? "menu.role-spouse" : "menu.role-partner"), name(c.other(id)));
            lore(lore, c);
        }
        UUID parent = tree.parent(id);
        if (parent != null) lore.kv(messages().raw("menu.role-parent"), name(parent));
        lore.kv(messages().raw("menu.children"), tree.familyChildren(id).size());
        lore.kv(messages().raw("menu.members"), tree.size(id));
        lore.left(messages().raw("menu.see-family"));
        return Items.of("itemsadder:influitems:referral", messages().raw("menu.info-name"), lore.build(), viewer);
    }

    /**
     * {@code %fluxfamily_status%} (shown name), {@code _married} / {@code _divorced} (true / false), {@code _partner},
     * {@code _children}, {@code _size}. For plugins outside the suite only: the suite uses {@link Family}.
     */
    private String placeholder(Player p, String key) {
        if (p == null) return "";
        UUID id = p.getUniqueId();
        return switch (key) {
            case "status" -> status(id);
            case "married" -> String.valueOf(Family.married(id));
            case "divorced" -> String.valueOf(store.divorced(id));
            case "partner" -> {
                UUID partner = Family.partner(id);
                yield partner == null ? "" : name(partner);
            }
            case "children" -> String.valueOf(tree.familyChildren(id).size());
            case "size" -> String.valueOf(tree.size(id));
            default -> null;
        };
    }

    private boolean onAdmin(CommandSender sender, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (sub.equals("reload")) {
            reload();
            messages().send(sender, "reloaded");
            return true;
        }
        OfflinePlayer target = args.length > 1 ? Players.find(args[1]) : null;
        if (target == null || !(sub.equals("info") || sub.equals("separate"))) {
            messages().send(sender, target == null && args.length > 1 ? "unknown-player" : "admin-usage");
            return true;
        }
        UUID id = target.getUniqueId();
        if (sub.equals("separate")) {
            if (store.couple(id) == null) {
                messages().send(sender, "admin-no-couple");
                return true;
            }
            separate(id, false);   // staff action: nobody becomes divorced
            messages().send(sender, "admin-separated", "%player%", Players.name(target, args[1]));
            return true;
        }
        Couple c = store.couple(id);
        UUID parent = tree.parent(id);
        messages().send(sender, "admin-info", "%player%", Players.name(target, args[1]), "%status%", status(id),
                "%partner%", c == null ? "-" : name(c.other(id)), "%parent%", parent == null ? "-" : name(parent) + " (" + link(id) + ")",
                "%children%", String.valueOf(tree.familyChildren(id).size()), "%divorced%", String.valueOf(store.divorced(id)),
                "%couples%", String.valueOf(store.coupleCount()));
        return true;
    }
}

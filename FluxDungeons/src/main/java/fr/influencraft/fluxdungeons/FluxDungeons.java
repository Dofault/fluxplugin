package fr.influencraft.fluxdungeons;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.dialog.Dialogs;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.items.CustomItems;
import fr.influencraft.flux.api.items.ItemCatalog;
import fr.influencraft.flux.api.util.Sounds;
import fr.influencraft.fluxdungeons.api.Dungeons;
import fr.influencraft.fluxdungeons.dungeon.Arena;
import fr.influencraft.fluxdungeons.dungeon.DungeonDefinition;
import fr.influencraft.fluxdungeons.dungeon.Run;
import fr.influencraft.fluxdungeons.dungeon.Runs;
import fr.influencraft.fluxdungeons.hero.HeroClass;
import fr.influencraft.fluxdungeons.hero.Heroes;
import fr.influencraft.fluxdungeons.mob.Mobs;
import fr.influencraft.fluxdungeons.party.Parties;
import fr.influencraft.fluxdungeons.party.Party;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * FluxDungeons: instanced dungeons played in parties, apart from the semi-RP world. A player picks a class
 * ({@code classes.yml}), forms a party, and starts a dungeon ({@code dungeons/<id>.yml}): a copy of it is made for
 * his party alone, he enters with his dungeon inventory (his own things wait for him in the database), fights room
 * after room with his three abilities, and leaves with money, class experience and dungeon gear (kept in his dungeon
 * inventory, useless outside).
 *
 * <pre>
 *   /dungeon [play &lt;id&gt; | leave | class [id] | list]
 *   /party [invite|accept|leave|kick|leader] [player]
 *   /fluxdungeons build &lt;id|all&gt; | template &lt;id&gt; | start &lt;id&gt; &lt;player&gt; | stop &lt;player|all&gt;
 *                 | give &lt;player&gt; &lt;item&gt; [n] | runs | reload            (admin)
 * </pre>
 */
public final class FluxDungeons extends FluxPlugin {

    private Store store;
    private Parties parties;
    private Heroes heroes;
    private Mobs mobs;
    private Arena arena;
    private Diagnostics diagnostics;
    private Runs runs;
    private Loadouts loadouts;
    private ItemCatalog items;
    private fr.influencraft.fluxdungeons.combat.AttackCadence attackCadence;
    private DungeonListener listener;
    private fr.influencraft.fluxdungeons.combat.HealthBars healthBars;

    @Override
    protected void enable() {
        store = new Store(getLogger());
        store.open();
        parties = new Parties();
        heroes = new Heroes(this, store);
        mobs = new Mobs(this);
        items = ItemCatalog.create(this, "fluxdungeons");
        diagnostics = new Diagnostics(this);
        getServer().getPluginManager().registerEvents(diagnostics, this);
        loadouts = new Loadouts(store, getLogger(), heroes::isAbilityItem);
        arena = new Arena(this, getConfig().getString("world", "flux_dungeons"), getConfig().getInt("slot-spacing", 512),
                getConfig().getInt("blocks-per-tick", 20000));
        runs = new Runs(this, arena, mobs, heroes, loadouts, store);
        // weapons' attack cadence: a rule of the whole server, carried here because it serves the dungeons first
        // life bars above creatures: dungeon monsters, and every other one with health-bar.all-mobs
        healthBars = new fr.influencraft.fluxdungeons.combat.HealthBars(this);
        healthBars.skip(e -> mobs.templateOf(e) != null || runs.isPet(e) || runs.isDungeonWorld(e.getWorld()));
        mobs.bars(healthBars);
        getServer().getPluginManager().registerEvents(healthBars, this);
        attackCadence = new fr.influencraft.fluxdungeons.combat.AttackCadence(this);
        attackCadence.assist(p -> runs.runOf(p) == null ? null : runs.context(p)::enemy);
        getServer().getPluginManager().registerEvents(attackCadence, this);
        getServer().getPluginManager().registerEvents(runs.lobby(), this);
        runs.editor().onSaved(this::reload);
        getServer().getPluginManager().registerEvents(runs.editor(), this);
        settings();
        Dungeons.register(new Dungeons.Service() {
            public boolean playing(Player player) {
                return runs.runOf(player) != null;
            }

            public boolean dungeonWorld(org.bukkit.World world) {
                return runs.isDungeonWorld(world);
            }
        });
        placeholders("fluxdungeons", (player, params) -> runs.placeholder(player, params));
        listener = new DungeonListener(this, arena, runs, heroes, mobs, loadouts);
        listener.hitReaction(getConfig().getConfigurationSection("hit-reaction"));
        Bukkit.getPluginManager().registerEvents(listener, this);
        command("dungeon")
                .description("Dungeons: play, leave, choose your class.")
                .permission("fluxdungeons.play")
                .executor(this::onDungeon)
                .completer(this::completeDungeon)
                .register();
        command("party")
                .description("Your party: invite, accept, leave.")
                .permission("fluxdungeons.play")
                .executor(this::onParty)
                .completer(this::completeParty)
                .register();
        command("fluxdungeons")
                .description("Dungeon administration.")
                .permission("fluxdungeons.admin")
                .suggest(false)
                .executor(this::onAdmin)
                .completer(this::completeAdmin)
                .register();
        // players already online (reload): give back what a crash may have left
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (loadouts.hasVault(p)) {
                var back = loadouts.recover(p);
                if (back != null) p.teleport(back, PlayerTeleportEvent.TeleportCause.PLUGIN);
            }
        }
        Bukkit.getScheduler().runTaskLater(this, runs::buildChanged, 40L);
    }

    @Override
    protected void disable() {
        if (runs != null) runs.stopAll();
        if (mobs != null) mobs.removeAll();
        if (healthBars != null) healthBars.clearAll();
    }

    @Override
    public void reload() {
        super.reload();
        settings();
    }

    public Parties parties() {
        return parties;
    }

    private void settings() {
        parties.maxSize(getConfig().getInt("party.max-size", 4));
        heroes.load(file("classes.yml"), getConfig().getConfigurationSection("classes"));
        // the players' reach bonus in a run, and the same for the monsters' melee
        heroes.reachBonus(getConfig().getDouble("dungeon-reach-bonus", 1.5));
        mobs.reachBonus(getConfig().getDouble("dungeon-reach-bonus", 1.5));
        mobs.alertRadius(getConfig().getDouble("aggro.alert-radius", 10));
        if (attackCadence != null) {
            attackCadence.load(getConfig().getConfigurationSection("attack-cadence"));
            attackCadence.loadAssist(getConfig().getConfigurationSection("aim-assist"));
        }
        if (healthBars != null) healthBars.load(getConfig().getConfigurationSection("health-bar"));
        if (listener != null) listener.hitReaction(getConfig().getConfigurationSection("hit-reaction"));
        List<org.bukkit.configuration.ConfigurationSection> mobFiles = new ArrayList<>();
        mobFiles.add(file("mobs.yml"));
        mobFiles.addAll(mobFiles(new File(getDataFolder(), "mobs")));
        mobs.load(mobFiles);
        // dungeon gear: items.yml, then every file of items/ (imported dungeons), one catalog
        YamlConfiguration allItems = new YamlConfiguration();
        List<YamlConfiguration> itemFiles = new ArrayList<>();
        itemFiles.add(file("items.yml"));
        itemFiles.addAll(folder("items"));
        for (YamlConfiguration f : itemFiles) for (String k : f.getKeys(false)) allItems.set(k, f.get(k));
        items.load(allItems);
        runs.settings(getConfig());
        Map<String, DungeonDefinition> defs = new LinkedHashMap<>();
        File folder = new File(getDataFolder(), "dungeons");
        File[] files = folder.listFiles((d, n) -> n.endsWith(".yml"));
        if (files != null) {
            java.util.Arrays.sort(files);
            for (File f : files) {
                String id = f.getName().substring(0, f.getName().length() - 4);
                try {
                    YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
                    // points moved in game (/fluxdungeons edit) replace the imported ones
                    File edits = new File(getDataFolder(), "edits/" + id + ".yml");
                    if (edits.isFile() && y.isConfigurationSection("map")) {
                        YamlConfiguration e = YamlConfiguration.loadConfiguration(edits);
                        for (String k : List.of("start", "spawns", "objectives", "doors")) if (e.contains("map." + k)) y.set("map." + k, e.get("map." + k));
                    }
                    defs.put(id, new DungeonDefinition(id, y));
                } catch (RuntimeException e) {
                    getLogger().severe("Dungeon " + f.getName() + " could not be read: " + e);
                }
            }
        }
        if (defs.isEmpty()) getLogger().warning("No dungeon in plugins/FluxDungeons/dungeons/.");
        // menus list the dungeons from the easiest to the hardest
        Map<String, DungeonDefinition> sorted = new LinkedHashMap<>();
        defs.values().stream().sorted(java.util.Comparator.comparingInt(DungeonDefinition::recommendedLevel))
                .forEach(d -> sorted.put(d.id(), d));
        runs.load(sorted);
    }

    /** Every .yml of a folder of the plugin, sorted by name. */
    private List<YamlConfiguration> folder(String name) {
        List<YamlConfiguration> out = new ArrayList<>();
        File[] files = new File(getDataFolder(), name).listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) return out;
        java.util.Arrays.sort(files);
        for (File f : files) out.add(YamlConfiguration.loadConfiguration(f));
        return out;
    }

    /**
     * Monster files of {@code mobs/}, sub-folders included (one per dungeon: {@code mobs/roche/}). A file holds one
     * monster, named after the file ({@code rochiste_tailleur.yml}: its keys at the root), or several under their ids.
     */
    private List<org.bukkit.configuration.ConfigurationSection> mobFiles(File dir) {
        List<org.bukkit.configuration.ConfigurationSection> out = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) return out;
        java.util.Arrays.sort(files);
        for (File f : files) {
            if (f.isDirectory()) {
                out.addAll(mobFiles(f));
                continue;
            }
            if (!f.getName().endsWith(".yml")) continue;
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            if (y.isString("type")) {
                YamlConfiguration one = new YamlConfiguration();
                one.createSection(f.getName().substring(0, f.getName().length() - 4), y.getValues(false));
                out.add(one);
            } else {
                out.add(y);
            }
        }
        return out;
    }

    /** A config file of the plugin, written from the jar (in the plugin's language when the jar has it) if missing. */
    private YamlConfiguration file(String name) {
        ship(name);
        return YamlConfiguration.loadConfiguration(new File(getDataFolder(), name));
    }

    private void ship(String name) {
        File target = new File(getDataFolder(), name);
        if (target.exists()) return;
        String localized = "i18n/" + language() + "/" + name;
        String resource = getResource(localized) != null ? localized : name;
        try (InputStream in = getResource(resource)) {
            if (in == null) return;
            target.getParentFile().mkdirs();
            Files.copy(in, target.toPath());
        } catch (IOException e) {
            getLogger().warning("Could not write " + name + ": " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- /dungeon

    private boolean onDungeon(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            messages().send(sender, "players-only");
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "" -> {
                if (!runs.lobby().reopen(p)) openMain(p);
            }
            case "play", "jouer" -> {
                if (args.length < 2) {
                    openMain(p);
                    return true;
                }
                DungeonDefinition def = dungeon(args[1]);
                if (def == null) messages().send(p, "unknown-dungeon");
                else play(p, def);
            }
            case "leave", "quitter" -> {
                if (runs.lobby().waiting(p)) runs.lobby().cancel(p);
                else if (runs.runOf(p) == null) messages().send(p, "not-in-run");
                else {
                    runs.leave(p);
                    messages().send(p, "run.left");
                }
            }
            case "class", "classe" -> {
                if (args.length >= 2) {
                    HeroClass c = classByName(args[1]);
                    if (c == null) messages().send(p, "unknown-class");
                    else choose(p, c);
                } else {
                    openClasses(p);
                }
            }
            case "list", "liste" -> {
                for (DungeonDefinition def : runs.dungeons()) {
                    messages().send(p, "list-line", "%dungeon%", def.name(), "%id%", def.id(),
                            "%min%", String.valueOf(def.minPlayers()), "%max%", String.valueOf(def.maxPlayers()),
                            "%level%", String.valueOf(def.recommendedLevel()));
                }
            }
            default -> messages().send(p, "dungeon-usage");
        }
        return true;
    }

    private List<String> completeDungeon(CommandSender sender, String[] args) {
        if (args.length == 1) return filter(List.of("play", "leave", "class", "list"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("play")) return filter(runs.dungeons().stream().map(DungeonDefinition::id).toList(), args[1]);
        if (args.length == 2 && args[0].equalsIgnoreCase("class")) return filter(heroes.classes().stream().map(HeroClass::id).toList(), args[1]);
        return List.of();
    }

    /** Main window: class, party, dungeons to play. */
    private void openMain(Player p) {
        HeroClass mine = heroes.classOf(p.getUniqueId());
        Dialogs.Form form = Dialogs.form(p, messages().raw("main.title")).width(300).columns(1);
        if (mine == null) form.body(messages().raw("main.no-class"));
        else form.body(messages().raw("main.class", "%class%", mine.colored(), "%level%", String.valueOf(heroes.level(p.getUniqueId(), mine.id()))));
        Party party = parties.of(p);
        form.body(party == null ? messages().raw("main.solo")
                : messages().raw("main.party", "%count%", String.valueOf(party.size()), "%leader%", name(party.leader())));
        Run run = runs.runOf(p);
        if (run != null) {
            form.body(messages().raw("main.in-run", "%dungeon%", run.definition().name()));
            form.action("leave", messages().raw("main.leave"));
        } else {
            // one button per dungeon; its details (players, level, description) show on mouse over
            for (DungeonDefinition def : runs.dungeons()) {
                List<String> tip = new ArrayList<>();
                tip.add(messages().raw("main.tooltip", "%dungeon%", def.name(), "%min%", String.valueOf(def.minPlayers()),
                        "%max%", String.valueOf(def.maxPlayers()), "%level%", String.valueOf(def.recommendedLevel())));
                for (String d : def.description()) tip.add("&#B376E5" + d);
                form.action("play:" + def.id(), def.name(), String.join("\n", tip));
            }
            form.action("class", messages().raw("main.choose-class"));
        }
        form.onSubmit((player, answer) -> {
            String b = answer.button();
            if (b == null) return;
            if (b.equals("leave")) player.performCommand("dungeon leave");
            else if (b.equals("class")) openClasses(player);
            else if (b.startsWith("play:")) {
                DungeonDefinition def = runs.dungeon(b.substring(5));
                if (def != null) play(player, def);
            }
        }).open();
    }

    private void openClasses(Player p) {
        if (runs.runOf(p) != null) {
            messages().send(p, "class.in-run");
            return;
        }
        HeroClass mine = heroes.classOf(p.getUniqueId());
        Dialogs.Form form = Dialogs.form(p, messages().raw("class.title")).width(320).columns(2);
        form.body(messages().raw("class.intro"));
        for (HeroClass c : heroes.classes()) {
            int level = heroes.level(p.getUniqueId(), c.id());
            List<String> lines = new ArrayList<>();
            lines.add(c.colored() + " &7" + messages().raw("class.level", "%level%", String.valueOf(level))
                    + (mine != null && mine.id().equals(c.id()) ? " " + messages().raw("class.current") : ""));
            for (String d : c.description()) lines.add("&#B376E5" + d);
            List<String> abilities = new ArrayList<>();
            for (HeroClass.AbilitySpec a : c.abilities()) abilities.add(a.name());
            lines.add("&7" + messages().raw("class.abilities") + " &f" + String.join("&7, &f", abilities));
            ItemStack icon = Items.dress(new ItemStack(c.icon()), c.colored(), Lore.create().description(c.description()).build(), p);
            form.item(icon, String.join("\n", lines));
        }
        for (HeroClass c : heroes.classes()) form.action(c.id(), c.colored());
        form.onSubmit((player, answer) -> {
            HeroClass c = heroes.get(answer.button());
            if (c != null) choose(player, c);
        }).open();
    }

    private void choose(Player p, HeroClass c) {
        if (runs.runOf(p) != null) {
            messages().send(p, "class.in-run");
            return;
        }
        heroes.choose(p, c);
        messages().send(p, "class.chosen", "%class%", c.colored(), "%level%", String.valueOf(heroes.level(p.getUniqueId(), c.id())));
        Sounds.cue(p, "success");
        runs.lobby().reopen(p);
    }

    /** Starts a dungeon for the player's party (he must lead it), or for him alone. */
    private void play(Player p, DungeonDefinition def) {
        Party party = parties.of(p);
        if (party != null && !party.isLeader(p.getUniqueId())) {
            messages().send(p, "start.not-leader");
            return;
        }
        List<Player> players = party == null ? List.of(p) : party.online();
        start(p, def, players);
    }

    private void start(CommandSender by, DungeonDefinition def, List<Player> players) {
        Runs.Refusal refusal = runs.check(def, players);
        switch (refusal) {
            case NONE -> {
                runs.prepare(def, players);
                if (!(by instanceof Player bp && players.contains(bp))) messages().send(by, "admin.started", "%dungeon%", def.name());
            }
            case TOO_FEW -> messages().send(by, "start.too-few", "%min%", String.valueOf(def.minPlayers()));
            case TOO_MANY -> messages().send(by, "start.too-many", "%max%", String.valueOf(def.maxPlayers()));
            case NO_CLASS -> {
                Player who = runs.blocking(players);
                messages().send(by, "start.no-class", "%player%", who == null ? "?" : who.getName());
            }
            case BUSY_PLAYER -> {
                Player who = runs.blocking(players);
                messages().send(by, "start.busy", "%player%", who == null ? "?" : who.getName());
            }
            case NO_SLOT -> messages().send(by, "start.no-slot");
            case EDITING -> messages().send(by, "start.editing");
            default -> messages().send(by, "start.failed");
        }
    }

    // ---------------------------------------------------------------- /party

    private boolean onParty(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            messages().send(sender, "players-only");
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : null;
        switch (sub) {
            case "" -> showParty(p);
            case "invite", "inviter" -> {
                if (target == null) {
                    messages().send(p, "unknown-player");
                    return true;
                }
                Parties.Result r = parties.invite(p, target);
                if (r == Parties.Result.OK) {
                    messages().send(p, "party.invited", "%player%", target.getName());
                    messages().send(target, "party.invitation", "%player%", p.getName());
                    Sounds.cue(target, "alert");
                } else partyError(p, r, target.getName());
            }
            case "accept", "accepter" -> {
                UUID from = target == null ? null : target.getUniqueId();
                Parties.Result r = parties.accept(p, from);
                if (r == Parties.Result.OK) {
                    Party party = parties.of(p);
                    for (Player m : party.online()) messages().send(m, "party.joined", "%player%", p.getName());
                } else partyError(p, r, target == null ? "" : target.getName());
            }
            case "leave", "quitter" -> {
                Party party = parties.of(p);
                Parties.Result r = parties.leave(p.getUniqueId());
                if (r == Parties.Result.OK) {
                    messages().send(p, "party.left");
                    for (Player m : party.online()) if (!m.equals(p)) messages().send(m, "party.member-left", "%player%", p.getName());
                } else partyError(p, r, "");
            }
            case "kick", "expulser" -> {
                OfflinePlayer t = args.length >= 2 ? Bukkit.getOfflinePlayerIfCached(args[1]) : null;
                if (t == null) {
                    messages().send(p, "unknown-player");
                    return true;
                }
                Party party = parties.of(p);
                Parties.Result r = parties.kick(p, t.getUniqueId());
                if (r == Parties.Result.OK) {
                    for (Player m : party.online()) messages().send(m, "party.kicked", "%player%", String.valueOf(t.getName()));
                    if (t.getPlayer() != null) messages().send(t.getPlayer(), "party.you-were-kicked");
                } else partyError(p, r, String.valueOf(t.getName()));
            }
            case "leader", "chef" -> {
                if (target == null) {
                    messages().send(p, "unknown-player");
                    return true;
                }
                Parties.Result r = parties.promote(p, target.getUniqueId());
                if (r == Parties.Result.OK) {
                    for (Player m : parties.of(p).online()) messages().send(m, "party.new-leader", "%player%", target.getName());
                } else partyError(p, r, target.getName());
            }
            default -> messages().send(p, "party-usage");
        }
        return true;
    }

    private void showParty(Player p) {
        Party party = parties.of(p);
        if (party == null) {
            messages().send(p, "party.none");
            for (UUID leader : parties.invitesOf(p.getUniqueId())) messages().send(p, "party.pending", "%player%", name(leader));
            return;
        }
        List<String> names = new ArrayList<>();
        for (UUID m : party.members()) {
            String n = name(m);
            HeroClass c = heroes.classOf(m);
            names.add((party.isLeader(m) ? "&#FFD94D★ " : "&f") + n + (c == null ? "" : " &7(" + c.colored() + "&7)"));
        }
        messages().send(p, "party.info", "%count%", String.valueOf(party.size()), "%max%", String.valueOf(parties.maxSize()),
                "%members%", String.join("&7, ", names));
    }

    private void partyError(Player p, Parties.Result r, String other) {
        messages().send(p, "party.error." + r.name().toLowerCase(Locale.ROOT).replace('_', '-'), "%player%", other);
        Sounds.cue(p, "error");
    }

    private List<String> completeParty(CommandSender sender, String[] args) {
        if (args.length == 1) return filter(List.of("invite", "accept", "leave", "kick", "leader"), args[0]);
        if (args.length == 2) return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        return List.of();
    }

    // ---------------------------------------------------------------- /fluxdungeons

    private boolean onAdmin(CommandSender sender, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "build" -> {
                if (args.length < 2) return usage(sender);
                List<DungeonDefinition> defs = (args[1].equalsIgnoreCase("all") ? List.copyOf(runs.dungeons())
                        : runs.dungeon(args[1]) == null ? List.<DungeonDefinition>of() : List.of(runs.dungeon(args[1])))
                        .stream().filter(d -> d.map() == null).toList();
                if (defs.isEmpty()) messages().send(sender, "unknown-dungeon");
                for (DungeonDefinition def : defs) runs.build(def, () -> messages().send(sender, "admin.built", "%dungeon%", def.name()));
            }
            case "template" -> {
                if (!(sender instanceof Player p) || args.length < 2) return usage(sender);
                DungeonDefinition def = runs.dungeon(args[1]);
                if (def == null) {
                    messages().send(sender, "unknown-dungeon");
                    return true;
                }
                if (def.map() != null) {
                    messages().send(sender, "admin.imported-no-template", "%dungeon%", def.name());
                    return true;
                }
                var at = runs.templateOrigin(def).add(def.layout().centerX() + 0.5, 1, 3.5);
                p.teleport(at, PlayerTeleportEvent.TeleportCause.PLUGIN);
                messages().send(p, "admin.template", "%dungeon%", def.name());
            }
            case "create", "creer" -> {
                if (!(sender instanceof Player p) || args.length < 2) return usage(sender);
                createDungeon(p, args[1].toLowerCase(Locale.ROOT), args.length > 2 ? args[2] : null);
            }
            case "edit", "editer" -> {
                if (!(sender instanceof Player p)) return usage(sender);
                var editor = runs.editor();
                String sub2 = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
                switch (sub2) {
                    case "save", "sauver" -> editor.save(p);
                    case "cancel", "annuler" -> editor.cancel(p);
                    case "add", "ajouter" -> {
                        if (args.length < 3) messages().send(p, "edit.usage");
                        else if (mobs.template(args[2]) == null) messages().send(p, "edit.unknown-mob", "%mob%", args[2]);
                        else editor.add(p, args[2]);
                    }
                    case "remove", "retirer" -> editor.remove(p);
                    case "move", "deplacer" -> editor.move(p);
                    case "start", "depart" -> editor.start(p);
                    case "objective", "objectif" -> editor.objective(p);
                    case "" -> messages().send(p, "edit.usage");
                    default -> {
                        DungeonDefinition def = runs.dungeon(args[1]);
                        if (def == null) messages().send(p, "unknown-dungeon");
                        else editor.open(p, def);
                    }
                }
            }
            case "visit", "voir" -> {
                if (!(sender instanceof Player p)) return usage(sender);
                if (args.length < 2) {
                    if (!runs.endVisit(p)) usage(sender);
                    return true;
                }
                DungeonDefinition def = runs.dungeon(args[1]);
                Runs.MapEntry map = def == null ? runs.maps().get(args[1]) : null;
                if (def != null) runs.visit(p, def);
                else if (map != null) runs.visitMap(p, map);
                else messages().send(sender, "unknown-dungeon");
            }
            case "start" -> {
                if (args.length < 3) return usage(sender);
                DungeonDefinition def = runs.dungeon(args[1]);
                Player target = Bukkit.getPlayerExact(args[2]);
                if (def == null || target == null) {
                    messages().send(sender, def == null ? "unknown-dungeon" : "unknown-player");
                    return true;
                }
                Party party = parties.of(target);
                start(sender, def, party == null ? List.of(target) : party.online());
            }
            case "stop" -> {
                if (args.length < 2) return usage(sender);
                if (args[1].equalsIgnoreCase("all")) {
                    runs.stopAll();
                    messages().send(sender, "admin.stopped-all");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                Run run = target == null ? null : runs.runOf(target);
                if (run == null) messages().send(sender, "not-in-run");
                else {
                    runs.stop(run);
                    messages().send(sender, "admin.stopped", "%player%", target.getName());
                }
            }
            case "give" -> {
                if (args.length < 3) return usage(sender);
                Player target = Bukkit.getPlayerExact(args[1]);
                String id = args[2].contains(":") ? args[2] : "fluxdungeons:" + args[2];
                int n = args.length >= 4 ? Math.max(1, parse(args[3])) : 1;
                if (target == null) messages().send(sender, "unknown-player");
                else if (!CustomItems.give(target, id, n)) messages().send(sender, "admin.unknown-item", "%item%", id);
                else messages().send(sender, "admin.given", "%item%", id, "%player%", target.getName());
            }
            case "claimlost" -> {
                // run by Flux's rewards when a player claims gear handed back to him
                if (sender instanceof Player) return usage(sender);
                if (args.length < 3) return usage(sender);
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) messages().send(sender, "unknown-player");
                else if (!runs.claimLost(target, args[2])) getLogger().warning("Lost gear " + args[2] + " of " + args[1] + " not found (already claimed?)");
            }
            case "runs" -> {
                if (runs.all().isEmpty()) messages().send(sender, "admin.no-runs");
                for (Run r : runs.all()) {
                    List<String> names = new ArrayList<>();
                    for (UUID u : r.players()) names.add(name(u));
                    messages().send(sender, "admin.run-line", "%id%", r.id(), "%dungeon%", r.definition().name(),
                            "%state%", r.state().name(), "%players%", String.join(", ", names));
                }
            }
            case "debug" -> {
                String what = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
                switch (what) {
                    case "leaks" -> diagnostics.leaks(sender);
                    case "heapdump" -> diagnostics.heapDump(sender);
                    case "save" -> {
                        DungeonDefinition def = args.length > 2 ? runs.dungeon(args[2]) : null;
                        if (def == null || def.map() == null) sender.sendMessage("usage: fluxdungeons debug save <imported dungeon>");
                        else runs.debugSave(def, msg -> {
                            sender.sendMessage(msg);
                            getLogger().info("debug save: " + msg);
                        });
                    }
                    case "cycle" -> {
                        DungeonDefinition def = args.length > 2 ? runs.dungeon(args[2]) : null;
                        int n = args.length > 3 ? Math.max(1, parse(args[3])) : 3;
                        if (def == null || def.map() == null) sender.sendMessage("usage: fluxdungeons debug cycle <imported dungeon> [n]");
                        else runs.debugCycle(def, n, () -> sender.sendMessage("cycle done: " + n + " copies opened and closed"));
                    }
                    default -> sender.sendMessage("fluxdungeons debug leaks | heapdump | cycle <dungeon> [n]");
                }
            }
            case "reload" -> {
                reload();
                messages().send(sender, "admin.reloaded");
            }
            default -> {
                return usage(sender);
            }
        }
        return true;
    }

    /**
     * A new dungeon: its file ({@code dungeons/<id>.yml}, default values to adjust) and its map
     * ({@code maps/custom/<id>}): empty, or a copy of a loaded world (a build). Then opened in edit mode.
     */
    private void createDungeon(Player p, String id, String fromWorld) {
        if (!id.matches("[a-z0-9_]{2,32}")) {
            messages().send(p, "create.bad-id");
            return;
        }
        File file = new File(getDataFolder(), "dungeons/" + id + ".yml");
        File map = new File(getDataFolder(), "maps/custom/" + id);
        if (file.exists() || runs.dungeon(id) != null || map.exists()) {
            messages().send(p, "create.exists", "%dungeon%", id);
            return;
        }
        // a map kept as support (maps/maps.yml), else a loaded world
        Runs.MapEntry fromMap = fromWorld == null ? null : runs.maps().get(fromWorld);
        org.bukkit.World source = fromWorld == null || fromMap != null ? null : Bukkit.getWorld(fromWorld);
        if (fromWorld != null && source == null && fromMap == null) {
            messages().send(p, "create.unknown-world", "%world%", fromWorld);
            return;
        }
        Location here = p.getLocation();
        boolean fromHere = source != null && source.equals(p.getWorld());
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of("Dungeon made in game (/fluxdungeons create). Points: /fluxdungeons edit " + id + "."));
        y.set("name", "&#FFD94D" + id);
        y.set("icon", "IRON_SWORD");
        y.set("description", List.of());
        y.set("players.min", 1);
        y.set("players.max", 4);
        y.set("recommended-level", 1);
        y.set("time-limit", "30m");
        y.set("reward.minutes", 10);
        y.set("reward.xp", 200);
        y.set("loot", List.of());
        y.set("map.folder", "maps/custom/" + id);
        if (fromMap != null) {
            double[] st = fromMap.start();
            y.set("map.start", List.of(st[0], st[1], st[2], st[3], st[4]));
        } else {
            y.set("map.start", fromHere ? List.of(Math.floor(here.getX()) + 0.5, Math.floor(here.getY()), Math.floor(here.getZ()) + 0.5, (double) Math.round(here.getYaw()), 0.0)
                    : List.of(0.5, 64.0, 0.5, 0.0, 0.0));
        }
        y.set("map.objectives", List.of());
        y.set("map.spawns", List.of());
        Runnable finish = () -> {
            try {
                file.getParentFile().mkdirs();
                y.save(file);
            } catch (java.io.IOException e) {
                messages().send(p, "start.failed");
                return;
            }
            reload();
            DungeonDefinition def = runs.dungeon(id);
            messages().send(p, "create.created", "%dungeon%", id);
            if (def != null && p.isOnline()) runs.editor().open(p, def);
        };
        if (fromMap != null) {
            messages().send(p, "create.copying", "%world%", fromMap.name());
            runs.copyMap(fromMap.folder(), map, ok -> {
                if (ok) finish.run();
                else messages().send(p, "start.failed");
            });
        } else if (source == null) {
            new File(map, "region").mkdirs();
            finish.run();
        } else {
            messages().send(p, "create.copying", "%world%", source.getName());
            runs.copyWorld(source, map, ok -> {
                if (ok) finish.run();
                else messages().send(p, "start.failed");
            });
        }
    }

    private boolean usage(CommandSender sender) {
        messages().send(sender, "admin-usage");
        return true;
    }

    private List<String> completeAdmin(CommandSender sender, String[] args) {
        if (args.length == 1) return filter(List.of("create", "edit", "visit", "build", "template", "start", "stop", "give", "runs", "reload"), args[0]);
        List<String> ids = runs.dungeons().stream().map(DungeonDefinition::id).toList();
        List<String> online = Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (sub) {
                case "build" -> filter(concat(ids, List.of("all")), args[1]);
                case "visit", "voir" -> filter(concat(ids, new ArrayList<>(runs.maps().keySet())), args[1]);
                case "template", "start" -> filter(ids, args[1]);
                case "edit", "editer" -> filter(concat(ids, List.of("add", "remove", "move", "start", "objective", "save", "cancel")), args[1]);
                case "stop" -> filter(concat(online, List.of("all")), args[1]);
                case "give" -> filter(online, args[1]);
                default -> List.of();
            };
        }
        if (args.length == 3 && sub.equals("start")) return filter(online, args[2]);
        if (args.length == 3 && (sub.equals("create") || sub.equals("creer"))) {
            return filter(concat(new ArrayList<>(runs.maps().keySet()), Bukkit.getWorlds().stream().map(org.bukkit.World::getName).toList()), args[2]);
        }
        if (args.length == 3 && (sub.equals("edit") || sub.equals("editer")) && (args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("ajouter"))) {
            List<String> placed = sender instanceof Player p ? runs.editor().placedMobs(p) : List.of();
            List<String> typed = filter(concat(placed, mobs.templateIds()), args[2]);
            return typed.stream().distinct().limit(80).toList();
        }
        if (args.length == 3 && sub.equals("give")) {
            return filter(CustomItems.ids().stream().filter(i -> i.startsWith("fluxdungeons:")).toList(), args[2]);
        }
        return List.of();
    }

    // ---------------------------------------------------------------- helpers

    private DungeonDefinition dungeon(String idOrName) {
        DungeonDefinition def = runs.dungeon(idOrName.toLowerCase(Locale.ROOT));
        if (def != null) return def;
        for (DungeonDefinition d : runs.dungeons()) if (d.name().equalsIgnoreCase(idOrName)) return d;
        return null;
    }

    private HeroClass classByName(String idOrName) {
        HeroClass c = heroes.get(idOrName.toLowerCase(Locale.ROOT));
        if (c != null) return c;
        for (HeroClass h : heroes.classes()) if (h.name().equalsIgnoreCase(idOrName)) return h;
        return null;
    }

    private static String name(UUID uuid) {
        OfflinePlayer o = Bukkit.getOfflinePlayer(uuid);
        return o.getName() == null ? "?" : o.getName();
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static List<String> filter(List<String> options, String typed) {
        String t = typed.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(t)).toList();
    }
}

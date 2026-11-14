package fr.influencraft.fluxdungeons;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.dialog.Dialogs;
import fr.influencraft.flux.api.items.CustomItems;
import fr.influencraft.flux.api.items.ItemCatalog;
import fr.influencraft.flux.api.util.Sounds;
import fr.influencraft.fluxdungeons.api.Dungeons;
import fr.influencraft.fluxdungeons.dungeon.Arena;
import fr.influencraft.fluxdungeons.dungeon.DungeonDefinition;
import fr.influencraft.fluxdungeons.dungeon.Run;
import fr.influencraft.fluxdungeons.dungeon.Runs;
import fr.influencraft.fluxdungeons.mob.Mobs;
import fr.influencraft.fluxdungeons.pages.DungeonPages;
import fr.influencraft.fluxdungeons.pages.PageKit;
import fr.influencraft.fluxdungeons.party.Parties;
import fr.influencraft.fluxdungeons.party.Party;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

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
 * FluxDungeons: the rpg universe (classes, stats, combat engine, skills, {@code rpg} package) and its instanced
 * dungeons played in parties. A player picks his rpg class ({@code /classe}), forms a party, and starts a dungeon
 * ({@code dungeons/<id>.yml}): a copy of it is made for his party alone, he plays it with his rpg profile as it is
 * (never with things of the semi-rp world), fights room after room, and leaves with money, rpg experience and loot.
 *
 * <pre>
 *   /dungeon [play &lt;id&gt; | leave | class | list]
 *   /party [invite|accept|leave|kick|leader] [player]
 *   /fluxdungeons build &lt;id|all&gt; | template &lt;id&gt; | start &lt;id&gt; &lt;player&gt; | stop &lt;player|all&gt;
 *                 | give &lt;player&gt; &lt;item&gt; [n] | runs | reload            (admin)
 * </pre>
 */
public final class FluxDungeons extends FluxPlugin {

    private Parties parties;
    private Mobs mobs;
    private Arena arena;
    private Diagnostics diagnostics;
    private Runs runs;
    private ItemCatalog items;
    private fr.influencraft.fluxdungeons.combat.AttackCadence attackCadence;
    private DungeonListener listener;
    /** Run id of monsters summoned for a test (/fluxdungeons summon): belong to no run, removed by "summon clear". */
    private static final String TEST_RUN = "test";
    private fr.influencraft.fluxdungeons.combat.CombatRules combat;
    private fr.influencraft.fluxdungeons.combat.HealthBars healthBars;

    private fr.influencraft.fluxdungeons.rpg.RpgModule rpg;
    private fr.influencraft.fluxdungeons.combat.DamageNumbers damageNumbers;

    /**
     * Dungeons belong to the rpg universe (docs/technique/rpg.md): their worlds swap players to their rpg profile,
     * runs use the rpg class, experience and lobby, and the rpg engine fights their monsters (level of the party,
     * rank from the monster, life and damage times the difficulty), the dungeon rules still pacing and reacting.
     */
    /** Rank of a dungeon monster in balance.yml: its file's {@code rank} (ELITE, MINIBOSS, BOSS), a boss is a boss. */
    private static String rpgRank(fr.influencraft.fluxdungeons.mob.MobTemplate t) {
        String r = t.extra().rank();
        if (t.boss() || "BOSS".equals(r)) return "boss";
        if ("MINIBOSS".equals(r) || "MINI_BOSS".equals(r)) return "miniboss";
        if ("ELITE".equals(r)) return "elite";
        return "normal";
    }

    private void wireRpg() {
        if (rpg == null || rpg.combat() == null) {
            // dungeons are played in the rpg universe only: without it nobody may start one (NO_CLASS)
            getLogger().severe("Rpg module off (see the errors above): no dungeon can be played");
            return;
        }
        fr.influencraft.flux.api.universe.Universes.claim(this, fr.influencraft.fluxdungeons.rpg.RpgWorld.UNIVERSE, runs::isDungeonWorld);
        // the HUD shows the class instead of the position in the rpg universe (hud.yml of Flux)
        fr.influencraft.flux.api.hud.Hud.piece(this, "rpg_class", p -> {
            String[] c = rpgClass(p);
            return c == null ? "" : c[0];
        });
        fr.influencraft.flux.api.hud.Hud.piece(this, "rpg_class_id", p -> {
            var r = rpg.players().get(p);
            return r == null ? "" : r.heroClass().id();
        });
        // a sword or an axe swung in the rpg universe is the class's basic attack
        attackCadence.melee(rpg::melee);
        var players = rpg.players();
        runs.rpg(new fr.influencraft.fluxdungeons.dungeon.RpgBridge() {
            public boolean mayPlay(Player p) {
                return players.mayPlay(p);
            }

            public void giveXp(Player p, double xp) {
                players.giveXp(p, xp);
            }

            public org.bukkit.Location lobby() {
                return rpg.world().spawn();
            }

            public double killXp(org.bukkit.entity.LivingEntity mob, fr.influencraft.fluxdungeons.mob.MobTemplate t) {
                var b = rpg.balance();
                return b == null ? 0 : b.monsterXp(players.averageLevel(mob.getWorld()), rpgRank(t));
            }

            public double victoryXp(org.bukkit.World w) {
                var b = rpg.balance();
                return b == null ? 0 : b.monsterXp(players.averageLevel(w), "miniboss");
            }

            public String classLine(Player p) {
                var r = players.get(p);
                return r == null ? null : messages().raw("classes." + r.heroClass().id() + ".name") + " " + r.level();
            }
        });
        var engine = rpg.combat();
        engine.profiles(e -> {
            var t = mobs.templateOfEntity(e);
            if (t == null) return fr.influencraft.fluxdungeons.rpg.RpgModule.profileFromTags(e);
            var level = runs.levelOf(mobs.runOf(e));
            java.util.List<fr.influencraft.fluxdungeons.rpg.core.Modifier> extra = new java.util.ArrayList<>();
            if (level != null) {
                extra.add(fr.influencraft.fluxdungeons.rpg.core.Modifier.multiply("max_health", level.health() - 1, "difficulty"));
                extra.add(fr.influencraft.fluxdungeons.rpg.core.Modifier.multiply("physical_attack", level.damage() - 1, "difficulty"));
            }
            // a troop's own shape (stats: of its file): a fragile pest, a slow brute that hits hard
            var troop = t.extra().troop();
            if (troop.health() != 1) extra.add(fr.influencraft.fluxdungeons.rpg.core.Modifier.multiply("max_health", troop.health() - 1, "troop"));
            if (troop.damage() != 1) {
                extra.add(fr.influencraft.fluxdungeons.rpg.core.Modifier.multiply("physical_attack", troop.damage() - 1, "troop"));
                extra.add(fr.influencraft.fluxdungeons.rpg.core.Modifier.multiply("magic_power", troop.damage() - 1, "troop"));
            }
            String rank = rpgRank(t);
            if (rank.equals("boss")) {
                // a boss's life in balance.yml is for a party of four: each player present brings a quarter of it
                int size = runs.partySize(mobs.runOf(e));
                int n = Math.max(1, size > 0 ? size : players.countIn(e.getWorld()));
                extra.add(fr.influencraft.fluxdungeons.rpg.core.Modifier.multiply("max_health", Math.min(4, n) / 4.0 - 1 + Math.max(0, n - 4) * 0.25, "party"));
            }
            double unit = mobs.meleeDamage(e);
            return new fr.influencraft.fluxdungeons.rpg.CombatEngine.MobProfile(players.averageLevel(e.getWorld()),
                    rank, null, extra, unit > 0 ? unit : t.damage(), t.extra().rangedResistance());
        });
        engine.afterMonsterHit(combat::onPlayerHit);
        engine.numbers(damageNumbers);
        damageNumbers.handledElsewhere(engine::rpg);
        // players hit a model's hit box: the creature under it takes the hit
        engine.body(fr.influencraft.fluxdungeons.mob.Mobs::body);
        healthBars.life(e -> engine.rpg(e) && !(e instanceof Player) ? new double[]{engine.health(e), engine.maxHealth(e)} : null);
    }

    @Override
    protected void enable() {
        preloadClasses();
        parties = new Parties();
        mobs = new Mobs(this);
        items = ItemCatalog.create(this, "fluxdungeons");
        diagnostics = new Diagnostics(this);
        getServer().getPluginManager().registerEvents(diagnostics, this);
        arena = new Arena(this, getConfig().getString("world", "flux_dungeons"), getConfig().getInt("slot-spacing", 512),
                getConfig().getInt("blocks-per-tick", 20000));
        runs = new Runs(this, arena, mobs);
        // rpg universe (docs/technique/rpg.md): lobby world, classes, stats, combat engine, skills, /rpg, /classe
        rpg = new fr.influencraft.fluxdungeons.rpg.RpgModule(this);
        rpg.start();
        // damage numbers of the whole server, in place of CMI's
        damageNumbers = new fr.influencraft.fluxdungeons.combat.DamageNumbers(this);
        damageNumbers.load(getConfig().getConfigurationSection("damage-numbers"));
        getServer().getPluginManager().registerEvents(damageNumbers, this);
        // weapons' attack cadence: a rule of the whole server, carried here because it serves the dungeons first
        // life bars above creatures: dungeon monsters, and every other one with health-bar.all-mobs
        healthBars = new fr.influencraft.fluxdungeons.combat.HealthBars(this);
        healthBars.skip(e -> mobs.templateOf(e) != null || runs.isDungeonWorld(e.getWorld()));
        mobs.bars(healthBars);
        getServer().getPluginManager().registerEvents(healthBars, this);
        attackCadence = new fr.influencraft.fluxdungeons.combat.AttackCadence(this);
        getServer().getPluginManager().registerEvents(attackCadence, this);
        getServer().getPluginManager().registerEvents(runs.lobby(), this);
        runs.editor().onSaved(this::reload);
        // staff tool: invulnerable on or off and full heal, to test dungeons (fluxdungeons:god_tool)
        fr.influencraft.flux.api.items.ItemUses.register(this, "fluxdungeons:god_tool", (player, item, id) -> {
            if (!player.isOp() && !player.hasPermission("fluxdungeons.admin")) {
                messages().send(player, "admin.god-denied");
                return false;
            }
            boolean on = !player.isInvulnerable();
            player.setInvulnerable(on);
            var max = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            player.setHealth(max == null ? 20 : max.getValue());
            // the rpg life too (the engine's, the game's health only mirrors it there)
            if (rpg != null && rpg.combat() != null) rpg.combat().heal(player, 1e9, false);
            player.setFoodLevel(20);
            player.setSaturation(20);
            player.setFireTicks(0);
            player.setFreezeTicks(0);
            for (org.bukkit.potion.PotionEffect pe : player.getActivePotionEffects()) player.removePotionEffect(pe.getType());
            messages().send(player, on ? "admin.god-on" : "admin.god-off");
            Sounds.cue(player, on ? "toggle-on" : "toggle-off");
            return false;
        });
        getServer().getPluginManager().registerEvents(runs.editor(), this);
        settings();
        Dungeons.register(new Dungeons.Service() {
            public boolean playing(Player player) {
                return runs.runOf(player) != null;
            }

            public boolean dungeonWorld(org.bukkit.World world) {
                return runs.isDungeonWorld(world);
            }

            public boolean start(Player player, String dungeon) {
                DungeonDefinition def = runs.dungeon(dungeon);
                if (def == null) {
                    messages().send(player, "unknown-dungeon");
                    return false;
                }
                Party party = parties.of(player);
                FluxDungeons.this.start(player, def, party == null ? List.of(player) : party.online(), -1);
                return true;
            }
        });
        // a run has the player's screen: other plugins keep their boss bars and draws off it (tournaments)
        fr.influencraft.flux.api.client.Focus.register(this, player -> runs.runOf(player) != null);
        placeholders("fluxdungeons", (player, params) -> runs.placeholder(player, params));
        combat = new fr.influencraft.fluxdungeons.combat.CombatRules(this, runs, mobs);
        combat.hitReaction(getConfig().getConfigurationSection("hit-reaction"));
        wireRpg();
        Bukkit.getPluginManager().registerEvents(combat, this);
        listener = new DungeonListener(this, arena, runs, mobs, combat);
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
        Bukkit.getScheduler().runTaskLater(this, runs::buildChanged, 40L);
    }

    @Override
    protected void disable() {
        if (rpg != null) rpg.stop();
        if (runs != null) runs.stopAll();
        if (mobs != null) mobs.removeAll();
        if (healthBars != null) healthBars.clearAll();
    }

    /**
     * Loads every class of the plugin now. A class first needed in the middle of a run (the ice breaking under the
     * Tide Otter at half life) is otherwise read from the jar at that moment: once the jar has been replaced on a
     * running server, that fails with ClassNotFoundException and the run breaks. Loaded at start, a replaced jar only
     * waits for the next restart.
     */
    private void preloadClasses() {
        int count = 0;
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(getFile())) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.startsWith("fr/influencraft/fluxdungeons/") || !name.endsWith(".class")) continue;
                try {
                    Class.forName(name.substring(0, name.length() - 6).replace('/', '.'), false, getClassLoader());
                    count++;
                } catch (ClassNotFoundException | LinkageError ignored) {
                    // a class that cannot load now would not load later either
                }
            }
        } catch (java.io.IOException e) {
            getLogger().warning("Could not preload the classes of the plugin: " + e.getMessage());
        }
        getLogger().info(count + " classes preloaded.");
    }

    @Override
    public void reload() {
        super.reload();
        settings();
        if (listener != null) listener.reloadBreakable();
    }

    public Parties parties() {
        return parties;
    }

    private void settings() {
        if (damageNumbers != null) damageNumbers.load(getConfig().getConfigurationSection("damage-numbers"));
        parties.maxSize(getConfig().getInt("party.max-size", 4));
        // the monsters' melee reaches this much further than vanilla (players' attacks have their class range)
        mobs.reachBonus(getConfig().getDouble("dungeon-reach-bonus", 1.5));
        mobs.alertRadius(getConfig().getDouble("aggro.alert-radius", 10));
        if (attackCadence != null) attackCadence.load(getConfig().getConfigurationSection("attack-cadence"));
        // one aim assist for every class of the rpg
        if (rpg != null) rpg.aimAssist(getConfig().getConfigurationSection("aim-assist"));
        if (healthBars != null) healthBars.load(getConfig().getConfigurationSection("health-bar"));
        if (combat != null) combat.hitReaction(getConfig().getConfigurationSection("hit-reaction"));
        // spells first: monsters take them by id
        fr.influencraft.fluxdungeons.mob.Spells.load(new File(getDataFolder(), "spells"), getLogger());
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
                        for (String k : List.of("start", "spawns", "objectives", "doors", "ambience")) if (e.contains("map." + k)) y.set("map." + k, e.get("map." + k));
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
        writePages(sorted.values());
    }

    /** The dungeon selector page (Flux's Screens), written again each time the dungeons load; nothing without pages. */
    private void writePages(java.util.Collection<DungeonDefinition> dungeons) {
        java.nio.file.Path root = fr.influencraft.flux.api.screen.Screens.pagesFolder();
        if (root == null) return;
        List<DungeonPages.Entry> entries = new ArrayList<>();
        for (DungeonDefinition d : dungeons) {
            entries.add(new DungeonPages.Entry(d.id(), d.name(), d.description(), d.minPlayers(), d.maxPlayers(), d.recommendedLevel(),
                    Math.max(0, d.timeLimit()) / 60, d.lives(), d.levels().stream().map(DungeonDefinition.Level::name).toList()));
        }
        List<String> classes = rpg == null ? List.of() : rpg.classIds();
        DungeonPages pages = new DungeonPages(new PageKit.Source() {
            public String raw(String key, String... vars) {
                return messages().raw(key, vars);
            }

            public java.io.InputStream resource(String path) {
                return getResource(path);
            }

            public void warn(String message) {
                getLogger().warning(message);
            }
        });
        // files only: off the server thread
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> pages.write(root, entries, classes));
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
                if (runs.lobby().reopen(p)) return true;
                // the full screen selector; the window for a player in a run (leave button) or who cannot see pages
                if (runs.runOf(p) != null || !fr.influencraft.flux.api.screen.Screens.open(p, DungeonPages.LIST)) openMain(p);
            }
            case "play", "jouer" -> {
                if (args.length < 2) {
                    openMain(p);
                    return true;
                }
                DungeonDefinition def = dungeon(args[1]);
                if (def == null) messages().send(p, "unknown-dungeon");
                else play(p, def, args.length >= 3 ? level(args[2]) : -1);
            }
            case "leave", "quitter" -> {
                if (runs.lobby().waiting(p)) runs.lobby().cancel(p);
                else if (runs.runOf(p) == null) messages().send(p, "not-in-run");
                else {
                    runs.leave(p);
                    messages().send(p, "run.left");
                }
            }
            // the class is the rpg one
            case "class", "classe" -> p.performCommand("classe");
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
        return List.of();
    }

    /** The rpg class line of a player ("Guerrier", level), null without the rpg module or a class. */
    private String[] rpgClass(Player p) {
        if (rpg == null || rpg.players() == null) return null;
        var r = rpg.players().get(p);
        return r == null ? null : new String[]{messages().raw("classes." + r.heroClass().id() + ".name"), String.valueOf(r.level())};
    }

    /** Main window: class, party, dungeons to play. */
    private void openMain(Player p) {
        Dialogs.Form form = Dialogs.form(p, messages().raw("main.title")).width(300).columns(1);
        String[] c = rpgClass(p);
        if (c == null) form.body(messages().raw("main.no-class"));
        else form.body(messages().raw("main.class", "%class%", Dialogs.heading(c[0]), "%level%", c[1]));
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
                tip.addAll(def.description());
                form.action("play:" + def.id(), def.name(), String.join("\n", tip));
            }
            form.action("class", messages().raw("main.choose-class"));
        }
        form.onSubmit((player, answer) -> {
            String b = answer.button();
            if (b == null) return;
            if (b.equals("leave")) player.performCommand("dungeon leave");
            else if (b.equals("class")) player.performCommand("classe");
            else if (b.startsWith("play:")) {
                DungeonDefinition def = runs.dungeon(b.substring(5));
                if (def != null) play(player, def, -1);
            }
        }).open();
    }

    /** A difficulty level typed as its rank (1 = easiest), as an index; -1 when it is not a number. */
    private static int level(String text) {
        try {
            return Integer.parseInt(text) - 1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Starts a dungeon for the player's party (he must lead it), or for him alone. {@code level}: the difficulty
     * already chosen (the buttons of the selector page), else a dungeon with several levels asks.
     */
    private void play(Player p, DungeonDefinition def, int level) {
        // a launch screen closed with Escape keeps him waiting: the same dungeon opens it again, another one replaces it
        DungeonDefinition waiting = runs.lobby().dungeonOf(p);
        if (waiting != null) {
            if (waiting.id().equals(def.id())) {
                runs.lobby().reopen(p);
                return;
            }
            runs.lobby().cancel(p);
        }
        Party party = parties.of(p);
        if (party != null && !party.isLeader(p.getUniqueId())) {
            messages().send(p, "start.not-leader");
            return;
        }
        if (def.levels().size() > 1 && (level < 0 || level >= def.levels().size())) {
            pickLevel(p, def);
            return;
        }
        start(p, def, party == null ? List.of(p) : party.online(), def.levels().size() > 1 ? level : -1);
    }

    /**
     * A dungeon with several difficulty levels asks first, in a window: one button per level, its gear, multipliers
     * and rules on mouse over. The launch screen then opens at that level (the leader may still change it there).
     */
    private void pickLevel(Player p, DungeonDefinition def) {
        Dialogs.Form form = Dialogs.form(p, messages().raw("pick-level.title", "%dungeon%", def.name())).width(260).columns(1);
        form.body(messages().raw("pick-level.intro"));
        List<DungeonDefinition.Level> levels = def.levels();
        // easiest in green, hardest in red, the ones between in gold
        for (int i = 0; i < levels.size(); i++) {
            DungeonDefinition.Level l = levels.get(i);
            String color = messages().raw(i == 0 ? "pick-level.color-easy" : i == levels.size() - 1 ? "pick-level.color-hard" : "pick-level.color-middle");
            String tip = messages().raw("pick-level.tooltip", "%level%", color + "&l" + l.name(),
                    "%health%", number(l.health()), "%damage%", number(l.damage()), "%reward%", number(l.reward()),
                    "%spells%", !l.spells() ? messages().raw("pick-level.spells-off") : l.abilities() > 0
                            ? messages().raw("pick-level.spells-count", "%count%", String.valueOf(l.abilities()))
                            : messages().raw("pick-level.spells-on"));
            form.action("level:" + i, color + "&l" + l.name(), tip);
        }
        form.onSubmit((player, answer) -> {
            String b = answer.button();
            if (b == null || !b.startsWith("level:")) return;
            Party party = parties.of(player);
            if (party != null && !party.isLeader(player.getUniqueId())) return;
            start(player, def, party == null ? List.of(player) : party.online(), Integer.parseInt(b.substring(6)));
        }).open();
    }

    private static String number(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v).replace('.', ',');
    }

    private void start(CommandSender by, DungeonDefinition def, List<Player> players, int level) {
        Runs.Refusal refusal = runs.check(def, players);
        switch (refusal) {
            case NONE -> {
                runs.prepare(def, players, level);
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
            Player online = Bukkit.getPlayer(m);
            String[] c = online == null ? null : rpgClass(online);
            names.add(messages().raw(party.isLeader(m) ? "party.leader" : "party.member", "%name%", n)
                    + (c == null ? "" : messages().raw("party.class", "%class%", c[0])));
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
                start(sender, def, party == null ? List.of(target) : party.online(), -1);
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
                if (run == null && target != null && runs.lobby().waiting(target)) {
                    runs.lobby().cancel(target);
                    messages().send(sender, "admin.stopped", "%player%", target.getName());
                } else if (run == null) messages().send(sender, "not-in-run");
                else {
                    runs.stop(run);
                    messages().send(sender, "admin.stopped", "%player%", target.getName());
                }
            }
            case "god" -> {
                if (!(sender instanceof Player p)) return usage(sender);
                if (!CustomItems.give(p, "fluxdungeons:god_tool", 1)) messages().send(p, "admin.unknown-item", "%item%", "fluxdungeons:god_tool");
            }
            case "summon", "invoquer" -> {
                // test a monster anywhere, out of any dungeon: /fluxdungeons summon <monster> [count] | summon clear
                if (!(sender instanceof Player p) || args.length < 2) return usage(sender);
                if (args[1].equalsIgnoreCase("clear")) {
                    mobs.removeRun(TEST_RUN);
                    messages().send(p, "admin.summon-cleared");
                    return true;
                }
                var t = mobs.template(args[1]);
                if (t == null) {
                    messages().send(p, "edit.unknown-mob", "%mob%", args[1]);
                    return true;
                }
                int n = args.length > 2 ? Math.max(1, Math.min(20, parse(args[2]))) : 1;
                org.bukkit.block.Block aimed = p.getTargetBlockExact(20);
                Location at = aimed == null ? p.getLocation() : aimed.getLocation().add(0.5, 1, 0.5);
                at.setYaw(p.getLocation().getYaw() + 180);
                int done = 0;
                for (int i = 0; i < n; i++) if (mobs.spawn(t, at, TEST_RUN) != null) done++;
                messages().send(p, "admin.summoned", "%mob%", t.name(), "%count%", String.valueOf(done));
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
                    case "bars" -> {
                        for (String line : healthBars.describe()) getLogger().info(line);
                        for (String line : healthBars.describe()) sender.sendMessage("§7" + line);
                    }
                    case "mob" -> {
                        // near the sender, or near a named player (from the console: written in the log too)
                        Player near = args.length > 2 ? Bukkit.getPlayerExact(args[2]) : sender instanceof Player dp ? dp : null;
                        if (near == null) return usage(sender);
                        for (String line : mobs.inspect(near.getLocation())) {
                            sender.sendMessage("§7" + line);
                            if (!(sender instanceof Player)) getLogger().info(line);
                        }
                    }
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
        y.set("name", id);
        y.set("icon", "IRON_SWORD");
        y.set("description", List.of());
        y.set("players.min", 1);
        y.set("players.max", 4);
        y.set("recommended-level", 1);
        y.set("time-limit", "30m");
        y.set("reward.minutes", 10);
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
        if (args.length == 1) return filter(List.of("create", "edit", "visit", "build", "template", "start", "stop", "summon", "god", "give", "runs", "reload"), args[0]);
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
                case "summon", "invoquer" -> filter(concat(new ArrayList<>(mobs.templateIds()), List.of("clear")), args[1]);
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

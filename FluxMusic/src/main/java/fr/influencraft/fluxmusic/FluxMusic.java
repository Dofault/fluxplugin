package fr.influencraft.fluxmusic;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.util.Format;
import fr.influencraft.flux.api.util.Players;
import fr.influencraft.flux.api.jobs.JobLevelUpEvent;
import fr.influencraft.flux.api.music.Music;
import fr.influencraft.flux.api.util.PlayerSettings;
import fr.influencraft.fluxmusic.play.Ambience;
import fr.influencraft.fluxmusic.play.Jukebox;
import fr.influencraft.fluxmusic.song.NbsReader;
import fr.influencraft.fluxmusic.song.ScoreReader;
import fr.influencraft.fluxmusic.song.Song;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * FluxMusic: songs played note by note with the client's note block sounds, so nothing goes in the resource pack.
 * Songs are scores in {@code songs/<id>.yml} (see {@link ScoreReader}) or Note Block Studio files
 * {@code songs/<id>.nbs}.
 *
 * <pre>
 *   /musique [list]
 *   /musique play &lt;song&gt; [player|*]
 *   /musique stop [player|*]
 *   /musique reload
 * </pre>
 *
 * Other plugins play music through Flux's {@link Music} (this plugin is its provider); moments of the game that
 * FluxMusic follows itself (job level up) are given a song in {@code moments} of config.yml, and places (worlds)
 * their ambient songs in {@code ambience} (see {@link Ambience}).
 */
public final class FluxMusic extends FluxPlugin implements Listener {

    private volatile Map<String, Song> songs = Map.of();
    private Jukebox jukebox;
    private Ambience ambience;

    @Override
    protected void enable() {
        jukebox = new Jukebox();
        ambience = new Ambience(jukebox, this::song, FluxMusic::wants);
        loadSongs();
        jukebox.fadeOut(getConfig().getDouble("fade-out-seconds", 2.5));
        ambience.load(getConfig().getConfigurationSection("ambience"), getLogger());
        Bukkit.getScheduler().runTaskTimer(this, ambience::tick, 40L, 20L);
        Music.provide(new Music.Provider() {
            public boolean play(Player player, String id, Boolean loop) {
                Song song = song(id);
                if (song == null || !wants(player)) return false;
                jukebox.play(player, song, loop == null ? song.loop() : loop);
                return true;
            }

            public boolean jingle(Player player, String id) {
                Song song = song(id);
                if (song == null || !wants(player)) return false;
                jukebox.jingle(player, song);
                return true;
            }

            public boolean stop(Player player) {
                return jukebox.stop(player.getUniqueId());
            }

            public String playing(Player player) {
                Song s = jukebox.playing(player.getUniqueId());
                return s == null ? null : s.id();
            }

            public Set<String> songs() {
                return songs.keySet();
            }
        });
        command("musique")
                .description("Play a note block song.")
                .permission("fluxmusic.play")
                .usage(messages().raw("usage"))
                .executor(this::onCommand)
                .completer(this::complete)
                .register();
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    @Override
    protected void disable() {
        Music.provide(null);
        if (jukebox != null) jukebox.shutdown();
    }

    @Override
    public void reload() {
        super.reload();
        loadSongs();
        if (jukebox != null) jukebox.fadeOut(getConfig().getDouble("fade-out-seconds", 2.5));
        if (ambience != null) ambience.load(getConfig().getConfigurationSection("ambience"), getLogger());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        jukebox.stopAll(e.getPlayer().getUniqueId());
        ambience.forget(e.getPlayer().getUniqueId());
    }

    /** Moments of the game given a song in config.yml ({@code moments}); empty = nothing played. */
    private String moment(String key) {
        return getConfig().getString("moments." + key, "");
    }

    /** Whether the player wants music (settings window, option {@code music}). */
    private static boolean wants(Player player) {
        return PlayerSettings.flag(player, Music.SETTING, true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJobLevelUp(JobLevelUpEvent e) {
        Player p = e.player();
        // without music, the plain level up sound (the jobs plugin's own sound is turned off for the jingle)
        if (!Music.jingle(p, moment("job-level-up"))) p.playSound(p, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.3f);
    }

    // ---------------------------------------------------------------- songs

    private Song song(String id) {
        return id == null ? null : songs.get(id.toLowerCase(Locale.ROOT));
    }

    private float masterVolume() {
        return (float) Math.max(0, getConfig().getDouble("volume", 1.0));
    }

    /** Writes every song of the jar ({@code songs/}) missing from the songs folder. */
    private void shipSongs(File folder) {
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(getFile())) {
            jar.stream().map(java.util.jar.JarEntry::getName)
                    .filter(n -> n.startsWith("songs/") && !n.endsWith("/"))
                    .filter(n -> !new File(getDataFolder(), n).exists())
                    .forEach(n -> saveResource(n, false));
        } catch (java.io.IOException e) {
            getLogger().warning("Songs of the jar not copied: " + e.getMessage());
        }
    }

    private void loadSongs() {
        File folder = new File(getDataFolder(), "songs");
        shipSongs(folder);
        Map<String, Song> loaded = new TreeMap<>();
        File[] files = folder.listFiles();
        if (files != null) for (File f : files) {
            String name = f.getName();
            int dot = name.lastIndexOf('.');
            if (dot <= 0) continue;
            String id = name.substring(0, dot).toLowerCase(Locale.ROOT);
            String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            try {
                Song song = switch (ext) {
                    case "yml", "yaml" -> ScoreReader.read(id, f, masterVolume());
                    case "nbs" -> NbsReader.read(id, f.toPath(), masterVolume());
                    default -> null;
                };
                if (song == null) continue;
                for (String w : song.warnings()) getLogger().warning("Song " + id + ": " + w);
                loaded.put(id, song);
            } catch (Exception e) {
                getLogger().warning("Song " + name + " not loaded: " + e.getMessage());
            }
        }
        songs = Map.copyOf(loaded);
        getLogger().info(loaded.size() + " song(s) loaded.");
    }

    // ---------------------------------------------------------------- command

    private boolean onCommand(CommandSender sender, String label, String[] args) {
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list", "liste" -> {
                if (songs.isEmpty()) {
                    messages().send(sender, "no-songs");
                    return true;
                }
                messages().send(sender, "list-header");
                for (Song s : songs.values()) {
                    messages().send(sender, "list-line", "%id%", s.id(), "%title%", s.title(),
                            "%duration%", Format.duration(Math.max(1, s.durationMillis() / 1000)),
                            "%notes%", String.valueOf(s.noteCount()));
                }
                return true;
            }
            case "play", "jouer" -> {
                if (args.length < 2) return false;
                Song song = songs.get(args[1].toLowerCase(Locale.ROOT));
                if (song == null) {
                    messages().send(sender, "unknown-song", "%id%", args[1]);
                    return true;
                }
                Collection<? extends Player> targets = targets(sender, args, 2);
                if (targets == null) return true;
                for (Player p : targets) {
                    // a player who turned music off only hears what he asked for himself
                    if (p != sender && !wants(p)) continue;
                    jukebox.play(p, song, song.loop());
                    messages().send(p, "now-playing", "%title%", song.title());
                }
                if (args.length > 2) messages().send(sender, "played-to", "%title%", song.title(),
                        "%count%", String.valueOf(targets.size()));
                return true;
            }
            case "stop" -> {
                Collection<? extends Player> targets = targets(sender, args, 1);
                if (targets == null) return true;
                int stopped = 0;
                for (Player p : targets) if (jukebox.stop(p.getUniqueId())) stopped++;
                messages().send(sender, stopped > 0 ? "stopped" : "nothing-playing");
                return true;
            }
            case "reload" -> {
                if (!sender.hasPermission("fluxmusic.admin")) {
                    messages().send(sender, "no-permission");
                    return true;
                }
                reload();
                messages().send(sender, "reloaded", "%count%", String.valueOf(songs.size()));
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** Players aimed at by {@code args[index]} (a name or {@code *}), the sender when absent; null after an error. */
    private Collection<? extends Player> targets(CommandSender sender, String[] args, int index) {
        if (args.length <= index) {
            Player self = Players.of(sender);
            return self == null ? null : List.of(self);
        }
        if (!sender.hasPermission("fluxmusic.others")) {
            messages().send(sender, "no-permission");
            return null;
        }
        if (args[index].equals("*")) return Bukkit.getOnlinePlayers();
        Player p = Bukkit.getPlayerExact(args[index]);
        if (p == null) {
            messages().send(sender, "unknown-player");
            return null;
        }
        return List.of(p);
    }

    private List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) return filter(Stream.of("liste", "jouer", "stop", "reload"), args[0]);
        String sub = args[0].toLowerCase(Locale.ROOT);
        boolean play = sub.equals("play") || sub.equals("jouer");
        if (args.length == 2 && play) return filter(songs.keySet().stream(), args[1]);
        if (sender.hasPermission("fluxmusic.others")
                && ((args.length == 3 && play) || (args.length == 2 && sub.equals("stop")))) {
            List<String> names = new ArrayList<>(Players.complete(args[args.length - 1]));
            names.add("*");
            return names;
        }
        return List.of();
    }

    private static List<String> filter(Stream<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return options.filter(o -> o.startsWith(p)).toList();
    }
}

package fr.influencraft.fluxmusic;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.util.Format;
import fr.influencraft.flux.api.util.Players;
import fr.influencraft.fluxmusic.api.Music;
import fr.influencraft.fluxmusic.play.Jukebox;
import fr.influencraft.fluxmusic.song.NbsReader;
import fr.influencraft.fluxmusic.song.ScoreReader;
import fr.influencraft.fluxmusic.song.Song;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
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
 */
public final class FluxMusic extends FluxPlugin implements Listener {

    /** Songs shipped in the jar, written to the songs folder when missing. */
    private static final List<String> SHIPPED = List.of("eveil_de_valdor.yml");

    private volatile Map<String, Song> songs = Map.of();
    private Jukebox jukebox;

    @Override
    protected void enable() {
        jukebox = new Jukebox((player, song) -> { });
        loadSongs();
        Music.register(new Music.Service() {
            public boolean play(Player player, String id) {
                Song song = songs.get(id.toLowerCase(Locale.ROOT));
                if (song == null) return false;
                jukebox.play(player, song);
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
        Music.register(null);
        if (jukebox != null) jukebox.shutdown();
    }

    @Override
    public void reload() {
        super.reload();
        loadSongs();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        jukebox.stop(e.getPlayer().getUniqueId());
    }

    // ---------------------------------------------------------------- songs

    private float masterVolume() {
        return (float) Math.max(0, getConfig().getDouble("volume", 1.0));
    }

    private void loadSongs() {
        File folder = new File(getDataFolder(), "songs");
        for (String name : SHIPPED) {
            if (!new File(folder, name).exists()) saveResource("songs/" + name, false);
        }
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
                    jukebox.play(p, song);
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

package fr.influencraft.influbridge;

import com.bencodez.votingplugin.events.PlayerPostVoteEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Vote Party : compte les votes confirmés côté mineweb (tous joueurs et tous
 * vote-sites confondus, cf. insertVote() dans poll.get.ts qui déclenche la
 * commande console "influvoteparty add" à chaque vote) et déclenche une
 * récompense — commandes + messages configurables dans config.yml — une fois
 * le seuil atteint. VotingPlugin (PlayerPostVoteEvent) reste écouté en plus,
 * au cas où son propre Votifier recevrait un jour un vote réel, mais mineweb
 * est la source réelle des votes sur ce serveur (VotingPlugin n'en a jamais
 * reçu). Le compteur est persisté dans voteparty.yml pour survivre à un
 * redémarrage.
 */
public final class VotePartyService implements Listener {

    private final JavaPlugin plugin;
    private final File storageFile;
    private final AtomicInteger current = new AtomicInteger();

    private volatile int votesRequired;
    private volatile List<String> rewardCommands;
    private volatile String broadcastMessage;
    private volatile String rewardMessage;

    public VotePartyService(JavaPlugin plugin) {
        this.plugin = plugin;
        this.storageFile = new File(plugin.getDataFolder(), "voteparty.yml");
        reloadConfig();
        load();
    }

    public void reloadConfig() {
        var config = plugin.getConfig();
        this.votesRequired = Math.max(1, config.getInt("voteparty.votes-required", 30));
        this.rewardCommands = config.getStringList("voteparty.reward-commands");
        this.broadcastMessage = config.getString("voteparty.broadcast-message", "");
        this.rewardMessage = config.getString("voteparty.reward-message", "");
    }

    public int getCurrent() {
        return current.get();
    }

    public int getVotesRequired() {
        return votesRequired;
    }

    @EventHandler
    public void onVote(PlayerPostVoteEvent event) {
        if (!event.isRealVote()) return;

        if (Bukkit.isPrimaryThread()) {
            addVote();
        } else {
            Bukkit.getScheduler().runTask(plugin, this::addVote);
        }
    }

    /**
     * Incrémente le compteur d'un vote confirmé. Appelé par la commande console
     * "influvoteparty add" (source réelle : mineweb) et par onVote() ci-dessus.
     * Doit être appelé sur le thread principal.
     */
    public void addVote() {
        int value = current.incrementAndGet();
        boolean reached = value >= votesRequired;
        if (reached) {
            current.set(0);
        }
        save();

        if (reached) {
            triggerVoteParty();
        }
    }

    private void triggerVoteParty() {
        if (!broadcastMessage.isBlank()) {
            Bukkit.broadcastMessage(colorize(
                    broadcastMessage.replace("%votes-required%", String.valueOf(votesRequired))));
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            for (String commandTemplate : rewardCommands) {
                String command = commandTemplate.replace("%player%", player.getName());
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            }
            if (!rewardMessage.isBlank()) {
                player.sendMessage(colorize(rewardMessage));
            }
        }
    }

    private String colorize(String message) {
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    private void load() {
        if (!storageFile.exists()) {
            current.set(0);
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(storageFile);
        current.set(yaml.getInt("current", 0));
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("current", current.get());
        try {
            yaml.save(storageFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Erreur pendant la sauvegarde de voteparty.yml : " + e.getMessage());
        }
    }
}

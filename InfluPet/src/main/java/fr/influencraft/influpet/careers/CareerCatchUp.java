package fr.influencraft.influpet.careers;

import com.willfp.eco.core.data.PlayerProfile;
import com.willfp.eco.core.data.keys.PersistentDataKey;
import com.willfp.eco.core.data.keys.PersistentDataKeyType;
import fr.influencraft.influpet.CardRegistry;
import fr.influencraft.influpet.InfluPet;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Boosters en attente, récupérés avec {@code /boosters} (alias {@code /rattrapage}, {@code /catchup}),
 * et rattrapage unique des boosters de carrière (section {@code career-catch-up} de config.yml).
 * <p>
 * Tout booster qui ne va pas directement dans l'inventaire passe par ce stock
 * ({@code influpet:career_catchup_pending}, nom gardé pour les données déjà écrites) : boosters de
 * passage de niveau EcoJobs ({@code level-up-effects} → {@code run_command influpet stash ...}), surplus
 * de {@code influpet give} quand l'inventaire est plein, rattrapage. Rien ne tombe au sol, rien n'est donné
 * sans que le joueur le demande : {@code /boosters} donne ce qui rentre, le reste attend.
 * <p>
 * Rattrapage : EcoJobs ne rejoue jamais un niveau
 * déjà atteint : à sa première connexion après l'activation, un joueur reçoit donc une fois pour toutes les
 * boosters de ses niveaux déjà passés, soit {@code (niveau - 1) x boosters-per-level} par métier (le
 * niveau 1 est donné d'office par EcoJobs, sans passage de niveau). Un marqueur dans ses données eco
 * ({@code influpet:career_catchup_done}) empêche tout second rattrapage.
 * <p>
 * Rappel à la connexion, puis toutes les {@code reminder-seconds} secondes (0 : jamais) tant qu'il reste
 * des boosters en attente.
 */
public final class CareerCatchUp implements Listener, CommandExecutor {

    private static final String SECTION = "career-catch-up";

    private final InfluPet plugin;
    private final PersistentDataKey<Boolean> doneKey = new PersistentDataKey<>(key("career_catchup_done"),
            PersistentDataKeyType.BOOLEAN, false);
    /** Boosters en attente, au format {@code <booster>:<nombre>}. */
    private final PersistentDataKey<List<String>> pendingKey = new PersistentDataKey<>(
            key("career_catchup_pending"), PersistentDataKeyType.STRING_LIST, List.of());

    private CareerCatchUp(InfluPet plugin) {
        this.plugin = plugin;
    }

    private static NamespacedKey key(String value) {
        return new NamespacedKey("influpet", value);
    }

    /** Démarre /boosters et le rappel ; le rattrapage de carrière ne joue que si EcoJobs est là. */
    public static CareerCatchUp enable(InfluPet plugin) {
        CareerCatchUp catchUp = new CareerCatchUp(plugin);
        var command = plugin.getCommand("boosters");
        if (command != null) {
            command.setExecutor(catchUp);
        }
        Bukkit.getPluginManager().registerEvents(catchUp, plugin);
        long seconds = plugin.getConfig().getLong(SECTION + ".reminder-seconds", 0);
        if (seconds > 0) {
            long period = Math.max(10, seconds) * 20L;
            Bukkit.getScheduler().runTaskTimer(plugin, catchUp::remindAll, period, period);
        }
        // joueurs déjà connectés (reload du plugin)
        Bukkit.getOnlinePlayers().forEach(player -> catchUp.later(player, 20L));
        return catchUp;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean(SECTION + ".enabled", false)
                && Bukkit.getPluginManager().isPluginEnabled("EcoJobs");
    }

    /** Met des boosters en attente de /boosters. */
    public void stash(Player player, String boosterId, int amount) {
        if (amount <= 0) {
            return;
        }
        PlayerProfile profile = PlayerProfile.load(player);
        Map<String, Integer> pending = readPending(profile);
        pending.merge(boosterId, amount, Integer::sum);
        profile.write(pendingKey, writePending(pending));
    }

    /**
     * Donne des boosters dans l'inventaire, ce qui ne rentre pas part en attente de /boosters.
     * Renvoie le nombre mis en attente.
     */
    public int giveOrStash(Player player, CardRegistry.Booster booster, int amount) {
        int left = amount - add(player, booster, amount);
        if (left > 0) {
            stash(player, booster.id(), left);
            plugin.send(player, "boosters-stashed", "%count%", String.valueOf(left));
        }
        return left;
    }

    /** Ajoute jusqu'à {@code amount} boosters à l'inventaire, renvoie le nombre réellement ajouté. */
    private int add(Player player, CardRegistry.Booster booster, int amount) {
        // construit pour le joueur (✔ / ✘ des familiers) : s'empile avec les boosters qu'il a déjà
        ItemStack model = plugin.registry().boosterItem(booster, player);
        int given = 0;
        while (given < amount) {
            ItemStack stack = model.clone();
            stack.setAmount(Math.min(amount - given, model.getMaxStackSize()));
            int tried = stack.getAmount();
            int notAdded = player.getInventory().addItem(stack).values().stream()
                    .mapToInt(ItemStack::getAmount).sum();
            given += tried - notAdded;
            if (notAdded > 0) {
                break; // inventaire plein
            }
        }
        return given;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // laisse les données eco/EcoJobs et l'inventaire se charger
        later(event.getPlayer(), 60L);
    }

    private void later(Player player, long delay) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                catchUp(player);
                remind(player);
            }
        }, delay);
    }

    /** Calcule le rattrapage, une seule fois par joueur, et le met en attente. */
    private void catchUp(Player player) {
        if (!enabled()) {
            return;
        }
        PlayerProfile profile = PlayerProfile.load(player);
        if (Boolean.TRUE.equals(profile.read(doneKey))) {
            return;
        }
        ConfigurationSection jobs = plugin.getConfig().getConfigurationSection(SECTION + ".jobs");
        int perLevel = Math.max(0, plugin.getConfig().getInt(SECTION + ".boosters-per-level", 1));
        if (jobs == null || perLevel == 0) {
            return;
        }
        Map<String, Integer> pending = readPending(profile);
        List<String> summary = new ArrayList<>();
        int total = 0;
        for (String jobId : jobs.getKeys(false)) {
            String boosterId = jobs.getString(jobId);
            CardRegistry.Booster booster = boosterId == null ? null : plugin.registry().booster(boosterId);
            int level = EcoJobsLevels.level(player, jobId);
            if (booster == null || level < 0) {
                plugin.getLogger().warning("career-catch-up: job '" + jobId + "' or booster '" + boosterId
                        + "' not found, skipped.");
                continue;
            }
            int amount = Math.max(0, level - 1) * perLevel;
            if (amount == 0) {
                continue;
            }
            pending.merge(booster.id(), amount, Integer::sum);
            summary.add(plugin.format("career-catch-up-line", "%booster%", booster.name(),
                    "%amount%", String.valueOf(amount), "%job%", EcoJobsLevels.name(jobId),
                    "%level%", String.valueOf(level)));
            total += amount;
        }
        // marqueur et attente écrits avant toute remise : un arrêt brutal ne peut ni rejouer ni perdre
        profile.write(pendingKey, writePending(pending));
        profile.write(doneKey, true);
        if (total > 0) {
            plugin.getLogger().info("Career catch-up for " + player.getName() + ": " + total + " boosters "
                    + pending + ".");
            plugin.send(player, "career-catch-up-received", "%count%", String.valueOf(total));
            summary.forEach(line -> player.sendMessage(fr.influencraft.influpet.Texts.of(line)));
        }
    }

    /**
     * Donne ce qui rentre dans l'inventaire, garde le reste en attente.
     *
     * Appelé par /boosters.
     */
    private void deliver(Player player) {
        PlayerProfile profile = PlayerProfile.load(player);
        Map<String, Integer> pending = readPending(profile);
        if (pending.isEmpty()) {
            plugin.send(player, "boosters-nothing");
            return;
        }
        int given = 0;
        Map<String, Integer> left = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : pending.entrySet()) {
            CardRegistry.Booster booster = plugin.registry().booster(entry.getKey());
            if (booster == null) {
                left.put(entry.getKey(), entry.getValue()); // booster retiré de la config : gardé
                continue;
            }
            int added = add(player, booster, entry.getValue());
            given += added;
            int remaining = entry.getValue() - added;
            if (remaining > 0) {
                left.put(entry.getKey(), remaining);
            }
        }
        profile.write(pendingKey, writePending(left));
        if (given > 0) {
            plugin.send(player, "boosters-given", "%count%", String.valueOf(given));
        }
        if (!left.isEmpty()) {
            int count = left.values().stream().mapToInt(Integer::intValue).sum();
            plugin.send(player, "boosters-inventory-full", "%count%", String.valueOf(count));
        }
    }

    private void remindAll() {
        Bukkit.getOnlinePlayers().forEach(this::remind);
    }

    private void remind(Player player) {
        int count = readPending(PlayerProfile.load(player)).values().stream().mapToInt(Integer::intValue).sum();
        if (count > 0) {
            plugin.send(player, "boosters-pending", "%count%", String.valueOf(count));
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
            String @NotNull [] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        deliver(player);
        return true;
    }

    private Map<String, Integer> readPending(PlayerProfile profile) {
        Map<String, Integer> pending = new LinkedHashMap<>();
        List<String> stored = profile.read(pendingKey);
        for (String line : stored == null ? List.<String>of() : stored) {
            int sep = line.lastIndexOf(':');
            if (sep <= 0) {
                continue;
            }
            try {
                int amount = Integer.parseInt(line.substring(sep + 1));
                if (amount > 0) {
                    pending.merge(line.substring(0, sep), amount, Integer::sum);
                }
            } catch (NumberFormatException ignored) {
                // ligne illisible : ignorée
            }
        }
        return pending;
    }

    private static List<String> writePending(Map<String, Integer> pending) {
        List<String> lines = new ArrayList<>();
        pending.forEach((id, amount) -> {
            if (amount > 0) {
                lines.add(id + ":" + amount);
            }
        });
        return lines;
    }
}

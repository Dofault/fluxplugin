package fr.influencraft.fluxpets.careers;

import com.willfp.eco.core.data.PlayerProfile;
import com.willfp.eco.core.data.keys.PersistentDataKey;
import com.willfp.eco.core.data.keys.PersistentDataKeyType;
import fr.influencraft.fluxpets.CardRegistry;
import fr.influencraft.fluxpets.FluxPets;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Waiting boosters and the one-time career catch-up ({@code career-catch-up} section of config.yml).
 * <p>
 * Every booster that does not go straight into the inventory is handed to Flux's reward manager (source
 * {@code boosters}, one reward per stack of 64), claimed with every other reward ({@code /rewards}, Flux reminds the
 * player of them): EcoJobs level-up boosters ({@code level-up-effects} → {@code influpet stash ...}), what
 * {@code influpet give} could not fit, the catch-up. Nothing is dropped.
 * <p>
 * Catch-up: EcoJobs never replays a level already reached, so on his first login after activation a player gets
 * once the boosters of his levels already passed, {@code (level - 1) x boosters-per-level} per job (level 1 is given
 * by EcoJobs without level-up). A marker in his eco data ({@code influpet:career_catchup_done}) prevents a second
 * catch-up. Boosters kept by a former version in his eco data ({@code influpet:career_catchup_pending}) are moved to
 * Flux on his next login.
 */
public final class CareerCatchUp implements Listener {

    private static final String SECTION = "career-catch-up";

    private final FluxPets plugin;
    private final PersistentDataKey<Boolean> doneKey = new PersistentDataKey<>(key("career_catchup_done"),
            PersistentDataKeyType.BOOLEAN, false);
    /** Boosters kept by former versions, {@code <booster>:<amount>}: moved to Flux on login. */
    private final PersistentDataKey<List<String>> pendingKey = new PersistentDataKey<>(
            key("career_catchup_pending"), PersistentDataKeyType.STRING_LIST, List.of());

    private CareerCatchUp(FluxPets plugin) {
        this.plugin = plugin;
    }

    private static NamespacedKey key(String value) {
        return new NamespacedKey("influpet", value);
    }

    /** Starts the waiting boosters; the career catch-up only runs with EcoJobs. */
    public static CareerCatchUp enable(FluxPets plugin) {
        CareerCatchUp catchUp = new CareerCatchUp(plugin);
        // the booster items, for the icons of the pending rewards: source:fluxpets:booster:<id>
        fr.influencraft.flux.api.FluxApi.registerItemSource(plugin, "fluxpets", (viewer, arg) -> {
            if (!arg.startsWith("booster:")) return null;
            CardRegistry.Booster b = plugin.registry().booster(arg.substring(8));
            return b == null ? null : plugin.registry().boosterItem(b, viewer);
        });
        Bukkit.getPluginManager().registerEvents(catchUp, plugin);
        // joueurs déjà connectés (reload du plugin)
        Bukkit.getOnlinePlayers().forEach(player -> catchUp.later(player, 20L));
        return catchUp;
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean(SECTION + ".enabled", false)
                && fr.influencraft.flux.api.jobs.Jobs.available(); // job plugin served by Flux (providers.jobs)
    }

    /** Hands boosters to Flux's rewards (one reward per stack of 64), claimed with /rewards. */
    public void stash(org.bukkit.OfflinePlayer player, String boosterId, int amount) {
        stash(player, boosterId, amount, null);
    }

    /** Same, {@code key} (or null) keeps a catch-up from being given twice. */
    private void stash(org.bukkit.OfflinePlayer player, String boosterId, int amount, String key) {
        if (amount <= 0) {
            return;
        }
        CardRegistry.Booster booster = plugin.registry().booster(boosterId);
        String label = booster == null ? boosterId : booster.name();
        String command = fr.influencraft.flux.api.command.Commands.label("FluxPets", "fluxpets");
        int chunk = 0;
        for (int left = amount; left > 0; left -= 64, chunk++) {
            int n = Math.min(64, left);
            fr.influencraft.flux.api.reward.Reward reward = fr.influencraft.flux.api.reward.Reward.of("boosters", label)
                    .icon("source:fluxpets:booster:" + boosterId).amount(n).slots(1)
                    .commands(command + " give %player% booster " + boosterId + " " + n);
            fr.influencraft.flux.api.reward.Rewards.give(player, key == null ? reward : reward.key(key + ":" + chunk));
        }
    }

    /**
     * Donne des boosters dans l'inventaire, ce qui ne rentre pas part dans les récompenses de Flux (qui prévient
     * le joueur).
     * Renvoie le nombre mis en attente.
     */
    public int giveOrStash(Player player, CardRegistry.Booster booster, int amount) {
        int left = amount - add(player, booster, amount);
        if (left > 0) {
            stash(player, booster.id(), left);
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
                moveFormerPending(player);
                catchUp(player);
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
        Map<String, Integer> pending = new LinkedHashMap<>();
        List<String> summary = new ArrayList<>();
        int total = 0;
        for (String jobId : jobs.getKeys(false)) {
            String boosterId = jobs.getString(jobId);
            CardRegistry.Booster booster = boosterId == null ? null : plugin.registry().booster(boosterId);
            int level = (fr.influencraft.flux.api.jobs.Jobs.get().exists(jobId) ? fr.influencraft.flux.api.jobs.Jobs.get().level(player, jobId) : -1);
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
                    "%amount%", String.valueOf(amount), "%job%", fr.influencraft.flux.api.jobs.Jobs.get().displayName(jobId),
                    "%level%", String.valueOf(level)));
            total += amount;
        }
        // keyed rewards first, then the marker: a crash in between replays the catch-up, Flux ignores the keys
        pending.forEach((id, amount) -> stash(player, id, amount, "boosters:catchup:" + id));
        profile.write(doneKey, true);
        if (total > 0) {
            plugin.getLogger().info("Career catch-up for " + player.getName() + ": " + total + " boosters "
                    + pending + ".");
            plugin.send(player, "career-catch-up-received", "%count%", String.valueOf(total));
            summary.forEach(line -> player.sendMessage(fr.influencraft.fluxpets.Texts.of(line)));
        }
    }

    /** Boosters kept in the eco data by a former version: handed to Flux once. */
    private void moveFormerPending(Player player) {
        PlayerProfile profile = PlayerProfile.load(player);
        Map<String, Integer> former = readPending(profile);
        if (former.isEmpty()) {
            return;
        }
        profile.write(pendingKey, List.of());
        former.forEach((id, amount) -> stash(player, id, amount));
        plugin.getLogger().info("Waiting boosters of " + player.getName() + " moved into Flux: " + former);
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

}

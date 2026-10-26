package fr.influencraft.fluxtools.modules;

import com.willfp.eco.core.data.ProfileExtensions;
import com.willfp.eco.core.data.keys.PersistentDataKey;
import com.willfp.eco.core.data.keys.PersistentDataKeyType;
import com.willfp.ecojobs.api.EcoJobsAPI;
import com.willfp.ecojobs.api.event.PlayerJobExpGainEvent;
import com.willfp.ecojobs.api.event.PlayerJobLevelUpEvent;
import com.willfp.ecojobs.jobs.Job;
import com.willfp.ecojobs.jobs.JobXPAccumulatorKt;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Affiche une boss bar de progression d'XP de métier (EcoJobs) à chaque gain,
 * même minime, puis la masque après un délai d'inactivité. Une bar par (joueur,
 * métier) : si une seule action fait progresser plusieurs métiers à la fois
 * (ex. un effet qui casse plusieurs blocs d'un coup, mélangeant minerai et
 * pierre — Mineur + Rochiste gagnent tous les deux de l'XP), chaque métier a sa
 * propre bar affichée, empilées comme le fait nativement Minecraft. Un même
 * métier ne réutilise en revanche jamais plus d'une bar à la fois (mise à jour
 * en place sur gain répété, pas de doublon).
 */
public final class JobXpBossBarListener implements Listener {

    private final Plugin plugin;
    private final long hideAfterTicks;
    private final BossBar.Color color;
    private final BossBar.Overlay overlay;

    // Clé = uuid joueur + ":" + id du métier (Job.getId()).
    private final Map<String, BossBar> activeBars = new ConcurrentHashMap<>();
    private final Map<String, BukkitTask> hideTasks = new ConcurrentHashMap<>();

    // Dernier gain d'XP affiché pour cette bar : le rafraîchissement déclenché par un passage de
    // niveau n'a pas accès à l'event de gain, mais doit continuer d'afficher le "(+x)" plutôt que
    // de le faire disparaître au moment précis où le joueur a le plus envie de le voir.
    private final Map<String, Double> lastGains = new ConcurrentHashMap<>();

    // Préférence par joueur, activée par défaut — bascule via la commande "/jobxpbar toggle"
    // (bouton "Notif. XP métier" de /reglages, DeluxeMenus).
    private final PersistentDataKey<Boolean> enabledKey;

    public JobXpBossBarListener(Plugin plugin, long hideAfterSeconds, String colorName, String overlayName) {
        this.plugin = plugin;
        this.hideAfterTicks = hideAfterSeconds * 20L;
        this.color = parseColor(colorName);
        this.overlay = parseOverlay(overlayName);
        this.enabledKey = new PersistentDataKey<>(
                // namespace of the former plugin (InfluBridge): data already written stays readable
                new NamespacedKey("influbridge", "job_xp_bossbar_enabled"),
                PersistentDataKeyType.BOOLEAN,
                true);
    }

    public boolean isEnabled(OfflinePlayer player) {
        return ProfileExtensions.getProfile(player).read(enabledKey);
    }

    public void setEnabled(Player player, boolean enabled) {
        ProfileExtensions.getProfile((OfflinePlayer) player).write(enabledKey, enabled);
        if (!enabled) {
            hideAllBars(player);
        }
    }

    private void hideAllBars(Player player) {
        String prefix = player.getUniqueId() + ":";
        Iterator<Map.Entry<String, BossBar>> it = activeBars.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, BossBar> entry = it.next();
            if (!entry.getKey().startsWith(prefix)) {
                continue;
            }
            player.hideBossBar(entry.getValue());
            it.remove();
            lastGains.remove(entry.getKey());
            BukkitTask task = hideTasks.remove(entry.getKey());
            if (task != null) {
                task.cancel();
            }
        }
    }

    // Nettoie le nom du job de toute couleur avant de l'injecter dans le template MiniMessage :
    // Job.getName() renvoie le texte déjà "prêt à afficher" d'EcoJobs, donc potentiellement déjà
    // traduit en format legacy Bukkit "§x§R§R§G§G§B§B" (un caractère par §, cf. convention
    // AdvancedJobs) en plus du "&#RRGGBB" brut écrit dans jobs/*.yml — les deux formes sont
    // couvertes ici.
    private static final java.util.regex.Pattern COLOR_CODES = java.util.regex.Pattern.compile(
            "(?i)[&§]x(?:[&§].){6}|&#[0-9a-f]{6}|[&§][0-9a-fk-or]");

    // XP exacte, pas arrondie à l'entier : "10" reste "10", "10.4" reste "10.4" (2 décimales
    // max, zéros inutiles retirés). Locale forcée en US pour un séparateur "." constant, quelle
    // que soit la locale du serveur.
    private static final DecimalFormat XP_FORMAT =
            new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.US));

    // Gain lu juste avant les effets libreforge (familiers InfluPet : job_xp_multiplier), pour en
    // déduire leur bonus. Événement synchrone et non réentrant : un seul gain en cours à la fois.
    private PlayerJobExpGainEvent pendingEvent;
    private double pendingAmount;

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onJobXpGainBeforeEffects(PlayerJobExpGainEvent event) {
        pendingEvent = event;
        pendingAmount = event.getAmount();
    }

    // MONITOR : le gain affiché doit être le gain final. À la priorité par défaut, ce listener passait
    // avant celui de l'effet job_xp_multiplier de libreforge (enregistré seulement une fois libreforge
    // activé, après InfluBridge) : la barre montrait le gain sans le bonus des familiers (2026-10-09).
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJobXpGain(PlayerJobExpGainEvent event) {
        Player player = event.getPlayer();
        if (!isEnabled(player)) {
            return;
        }
        String key = player.getUniqueId() + ":" + event.getJob().getId();

        // EcoJobsAPI.getJobProgress() lirait l'état AVANT ce gain : cet event est
        // déclenché par EcoJobsAPI.giveJobExperience() avant l'écriture réelle de l'XP
        // (callEvent -> check isCancelled -> giveExactJobExperience), donc on doit
        // reconstituer la progression post-gain à la main plutôt que lire l'API.
        int level = EcoJobsAPI.getJobLevel(player, event.getJob());
        double xpForNextLevel = event.getJob().getExpForLevel(level + 1);
        double newXp = EcoJobsAPI.getJobXP(player, event.getJob()) + event.getAmount();
        float progress = xpForNextLevel > 0
                ? (float) Math.max(0.0, Math.min(1.0, newXp / xpForNextLevel))
                : 1f;
        // current/required : arrondis à l'entier (pas besoin de précision sur la progression
        // globale). Seul le gain entre parenthèses reste exact à la décimale près.
        String currentXpFormatted = String.valueOf(Math.round(newXp));
        String requiredXpFormatted = String.valueOf(Math.round(xpForNextLevel));
        String plainJobName = COLOR_CODES.matcher(event.getJob().getName()).replaceAll("");

        // Booster d'XP éventuel (permission-boosters, potions de boost...), même mécanisme que
        // celui déjà appliqué en amont dans EcoJobsAPI.giveJobExperience. Affiché en pourcentage
        // (convention du serveur, cf. AdvancedJobs/config.yml "permission-boosters: percent: 25.0"),
        // pas en multiplicateur — et seulement s'il diffère de +0% (pas de bruit permanent sur la bar).
        // Multiplicateur de permission, déjà inclus dans le montant de l'événement, multiplié par celui
        // des effets libreforge (familiers) appliqués pendant l'événement.
        double multiplier = JobXPAccumulatorKt.getJobExperienceMultiplier(player);
        if (pendingEvent == event && pendingAmount > 0) {
            multiplier *= event.getAmount() / pendingAmount;
        }
        pendingEvent = null;
        double bonusPercent = (multiplier - 1.0) * 100.0;
        lastGains.put(key, event.getAmount());

        Component title = buildTitle(plainJobName, level, currentXpFormatted, requiredXpFormatted,
                event.getAmount(), bonusPercent);
        if (title == null) {
            return;
        }

        BossBar bar = activeBars.get(key);
        if (bar == null) {
            bar = BossBar.bossBar(title, progress, color, overlay);
            activeBars.put(key, bar);
            player.showBossBar(bar);
        } else {
            bar.name(title);
            bar.progress(progress);
        }

        BukkitTask previousTask = hideTasks.remove(key);
        if (previousTask != null) {
            previousTask.cancel();
        }

        BossBar barToHide = bar;
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            player.hideBossBar(barToHide);
            activeBars.remove(key);
            lastGains.remove(key);
            hideTasks.remove(key);
        }, hideAfterTicks);
        hideTasks.put(key, task);
    }

    // EcoJobsAPI.giveExactJobExperience() remet l'XP à 0 et fire cet event AVANT de reporter le
    // reliquat (giveExactJobExperience(remainder), appelé juste après callEvent() dans la même
    // pile d'appel synchrone) — lire l'état ici donnerait donc toujours "0/required", pas le
    // vrai reliquat. On attend le tick suivant : la récursion (potentiellement plusieurs paliers
    // d'affilée sur un très gros gain) est de toute façon terminée bien avant, dans le même tick
    // serveur que l'event d'origine. Sans ce correctif, la bar affichée au moment du palier
    // restait figée à 100% jusqu'au prochain gain naturel (parfois long à revenir sur un métier
    // peu actif) au lieu de refléter tout de suite le vrai niveau/pourcentage.
    @EventHandler
    public void onJobLevelUp(PlayerJobLevelUpEvent event) {
        Player player = event.getPlayer();
        Job job = event.getJob();
        String key = player.getUniqueId() + ":" + job.getId();
        if (!activeBars.containsKey(key)) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> refreshAfterLevelUp(player, job, key));
    }

    private void refreshAfterLevelUp(Player player, Job job, String key) {
        if (!player.isOnline()) {
            return;
        }
        BossBar bar = activeBars.get(key);
        if (bar == null) {
            return;
        }

        int level = EcoJobsAPI.getJobLevel(player, job);
        double currentXp = EcoJobsAPI.getJobXP(player, job);
        double xpForNextLevel = job.getExpForLevel(level + 1);
        float progress = xpForNextLevel > 0
                ? (float) Math.max(0.0, Math.min(1.0, currentXp / xpForNextLevel))
                : 1f;
        String currentXpFormatted = String.valueOf(Math.round(currentXp));
        String requiredXpFormatted = String.valueOf(Math.round(xpForNextLevel));
        String plainJobName = COLOR_CODES.matcher(job.getName()).replaceAll("");

        // Le gain qui a provoqué le palier n'est pas porté par PlayerJobLevelUpEvent : on réaffiche
        // celui mémorisé au dernier PlayerJobExpGainEvent, sinon le "(+x)" disparaîtrait
        // exactement au moment du palier. Le bonus est relu, il n'a pas pu changer entre-temps.
        Double gained = lastGains.get(key);
        double multiplier = JobXPAccumulatorKt.getJobExperienceMultiplier(player);
        Component title = buildTitle(plainJobName, level, currentXpFormatted, requiredXpFormatted,
                gained, (multiplier - 1.0) * 100.0);
        if (title == null) {
            return;
        }

        bar.name(title);
        bar.progress(progress);
    }

    // Format MiniMessage fourni par l'utilisateur. Le nom du job est injecté via un placeholder
    // "unparsed" (pas de concaténation directe dans le template) : sinon, n'importe quel caractère
    // résiduel dans le nom du job pourrait casser le parsing MiniMessage (déjà arrivé une fois,
    // ParsingException plantant l'event listener). Renvoie null si le rendu échoue (déjà loggé).
    private Component buildTitle(String plainJobName, int level, String currentXpFormatted,
                                 String requiredXpFormatted, Double gained, double bonusPercent) {
        String gainSegment = gained != null ? "<#4DA954>(<#aafb93>+<gained></#aafb93>)" : "";
        String bonusSegment = Math.abs(bonusPercent) > 0.1 ? " <#4DA954>(<#C8256A>+<bonus>%</#C8256A>)" : "";
        try {
            return MiniMessage.miniMessage().deserialize(
                    "<#4DA954>『<job> <#aafb93>Niv.<level></#aafb93>』  "
                            + "<gray><white><current><gray>/<required> <gray>"
                            + gainSegment + bonusSegment,
                    Placeholder.unparsed("job", plainJobName),
                    Placeholder.unparsed("level", String.valueOf(level)),
                    Placeholder.unparsed("current", currentXpFormatted),
                    Placeholder.unparsed("required", requiredXpFormatted),
                    Placeholder.unparsed("gained", gained != null ? XP_FORMAT.format(gained) : ""),
                    Placeholder.unparsed("bonus", XP_FORMAT.format(bonusPercent)));
        } catch (Exception e) {
            plugin.getLogger().warning("job-xp-bossbar: échec de rendu du titre — " + e.getMessage());
            return null;
        }
    }

    private static BossBar.Color parseColor(String name) {
        try {
            return BossBar.Color.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return BossBar.Color.GREEN;
        }
    }

    private static BossBar.Overlay parseOverlay(String name) {
        try {
            return BossBar.Overlay.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return BossBar.Overlay.NOTCHED_20;
        }
    }
}

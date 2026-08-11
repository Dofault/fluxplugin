package fr.influencraft.influbridge;

import com.willfp.ecojobs.api.EcoJobsAPI;
import com.willfp.ecojobs.api.event.PlayerJobExpGainEvent;
import com.willfp.ecojobs.jobs.JobXPAccumulatorKt;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
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

    public JobXpBossBarListener(Plugin plugin, long hideAfterSeconds, String colorName, String overlayName) {
        this.plugin = plugin;
        this.hideAfterTicks = hideAfterSeconds * 20L;
        this.color = parseColor(colorName);
        this.overlay = parseOverlay(overlayName);
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

    @EventHandler(ignoreCancelled = true)
    public void onJobXpGain(PlayerJobExpGainEvent event) {
        Player player = event.getPlayer();
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
        String gainedFormatted = XP_FORMAT.format(event.getAmount());
        String plainJobName = COLOR_CODES.matcher(event.getJob().getName()).replaceAll("");

        // Booster d'XP éventuel (permission-boosters, potions de boost...), même mécanisme que
        // celui déjà appliqué en amont dans EcoJobsAPI.giveJobExperience. Affiché en pourcentage
        // (convention du serveur, cf. AdvancedJobs/config.yml "permission-boosters: percent: 25.0"),
        // pas en multiplicateur — et seulement s'il diffère de +0% (pas de bruit permanent sur la bar).
        double multiplier = JobXPAccumulatorKt.getJobExperienceMultiplier(player);
        double bonusPercent = (multiplier - 1.0) * 100.0;
        boolean hasBonus = Math.abs(bonusPercent) > 0.1;
        String bonusSegment = hasBonus ? " <#4DA954>(+<bonus>%)" : "";

        // Format MiniMessage fourni par l'utilisateur. Le nom du job est injecté via un
        // placeholder "unparsed" (pas de concaténation directe dans le template) : sinon,
        // n'importe quel caractère résiduel dans le nom du job pourrait casser le parsing
        // MiniMessage (déjà arrivé une fois — ParsingException plantant l'event listener).
        Component title;
        try {
            title = MiniMessage.miniMessage().deserialize(
                    "<#4DA954>『<job> <#aafb93>Niv.<level></#aafb93>』  "
                            + "<gray><white><current><gray>/<required> <gray>"
                            + "<#4DA954>(<#aafb93>+<gained></#aafb93>)" + bonusSegment,
                    Placeholder.unparsed("job", plainJobName),
                    Placeholder.unparsed("level", String.valueOf(level)),
                    Placeholder.unparsed("current", currentXpFormatted),
                    Placeholder.unparsed("required", requiredXpFormatted),
                    Placeholder.unparsed("gained", gainedFormatted),
                    Placeholder.unparsed("bonus", XP_FORMAT.format(bonusPercent)));
        } catch (Exception e) {
            plugin.getLogger().warning("job-xp-bossbar: échec de rendu du titre — " + e.getMessage());
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
            hideTasks.remove(key);
        }, hideAfterTicks);
        hideTasks.put(key, task);
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

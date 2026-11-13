package fr.influencraft.fluxtools.modules;

import fr.influencraft.flux.api.jobs.JobLevelUpEvent;
import fr.influencraft.flux.api.jobs.JobXpGainEvent;
import fr.influencraft.flux.api.jobs.Jobs;
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

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Affiche une boss bar de progression d'XP de métier (service métiers de Flux) à chaque gain,
 * même minime, puis la masque après un délai d'inactivité. Une bar par (joueur,
 * métier) : si une seule action fait progresser plusieurs métiers à la fois
 * (ex. un effet qui casse plusieurs blocs d'un coup, mélangeant minerai et
 * pierre — Mineur + Rochiste gagnent tous les deux de l'XP), chaque métier a sa
 * propre bar affichée, empilées comme le fait nativement Minecraft. Un même
 * métier ne réutilise en revanche jamais plus d'une bar à la fois (mise à jour
 * en place sur gain répété, pas de doublon).
 */
public final class JobXpBossBarListener implements Listener {

    /** Per-player toggle (settings menu), on by default. */
    static final String SETTING = "fluxtools:job-xp-bar";

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

    public JobXpBossBarListener(Plugin plugin, long hideAfterSeconds, String colorName, String overlayName) {
        this.plugin = plugin;
        this.hideAfterTicks = hideAfterSeconds * 20L;
        this.color = parseColor(colorName);
        this.overlay = parseOverlay(overlayName);
    }

    public boolean isEnabled(OfflinePlayer player) {
        return fr.influencraft.flux.api.util.PlayerSettings.flag(player, SETTING, true);
    }

    public void setEnabled(Player player, boolean enabled) {
        fr.influencraft.flux.api.util.PlayerSettings.set(player, SETTING, enabled);
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

    // exact XP, not rounded (Flux Format.decimal: 2 decimals at most, trailing zeros removed)

    /** Final gain, every bonus included, and the state after it (Flux jobs service). */
    @EventHandler
    public void onJobXpGain(JobXpGainEvent event) {
        Player player = event.player();
        if (!isEnabled(player)) {
            return;
        }
        String key = player.getUniqueId() + ":" + event.job();
        lastGains.put(key, event.gained());
        show(player, key, event.job(), event.level(), event.xp(), event.required(), event.gained(), event.bonusPercent(), true);
    }

    /** A level was just passed: the bar shows the new level right away, with the gain that caused it. */
    @EventHandler
    public void onJobLevelUp(JobLevelUpEvent event) {
        Player player = event.player();
        String key = player.getUniqueId() + ":" + event.job();
        if (!activeBars.containsKey(key)) {
            return;
        }
        double bonus = (Jobs.get().xpMultiplier(player) - 1.0) * 100.0;
        show(player, key, event.job(), event.level(), event.xp(), event.required(), lastGains.get(key), bonus, false);
    }

    private void show(Player player, String key, String job, int level, double xp, double required, Double gained,
                      double bonusPercent, boolean restartTimer) {
        float progress = required > 0 ? (float) Math.max(0.0, Math.min(1.0, xp / required)) : 1f;
        // current/required rounded (no need for precision on the overall progress), the gain stays exact
        String plainJobName = COLOR_CODES.matcher(Jobs.get().displayName(job)).replaceAll("");
        Component title = buildTitle(plainJobName, level, String.valueOf(Math.round(xp)), String.valueOf(Math.round(required)),
                gained, bonusPercent);
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
        if (!restartTimer) {
            return;
        }
        BukkitTask previousTask = hideTasks.remove(key);
        if (previousTask != null) {
            previousTask.cancel();
        }
        BossBar barToHide = bar;
        hideTasks.put(key, Bukkit.getScheduler().runTaskLater(plugin, () -> {
            player.hideBossBar(barToHide);
            activeBars.remove(key);
            lastGains.remove(key);
            hideTasks.remove(key);
        }, hideAfterTicks));
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
                    Placeholder.unparsed("gained", gained != null ? fr.influencraft.flux.api.util.Format.decimal(gained) : ""),
                    Placeholder.unparsed("bonus", fr.influencraft.flux.api.util.Format.decimal(bonusPercent)));
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

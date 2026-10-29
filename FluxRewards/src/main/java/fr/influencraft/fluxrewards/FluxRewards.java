package fr.influencraft.fluxrewards;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.reward.Rewards;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.Format;
import fr.influencraft.flux.api.util.Players;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * FluxRewards: the daily login and playtime rewards, claimed in the loyalty menu ({@code /fidelite}, {@link
 * LoyaltyMenu}). The first login of a day opens that menu by itself when something can be claimed (once the
 * resource pack is loaded); a claim is delivered at once, {@code /rewards} only keeps what does not fit.
 *
 * <pre>
 *   /fidelite                                                          (players)
 *   /fluxrewards day &lt;player&gt; &lt;n&gt; | reset &lt;player&gt; | reload    (admin)
 * </pre>
 */
public final class FluxRewards extends FluxPlugin implements Listener {

    /** Seconds to wait for the resource pack before opening the loyalty menu anyway. */
    private static final long PACK_TIMEOUT = 30;

    private DailyRewards daily;
    /** Players who just started a new login day, waiting for their resource pack before the menu opens. */
    private final java.util.Set<UUID> toOpen = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Playtime tiers reached, per online player: a new one is announced. */
    private final java.util.Map<UUID, Integer> reached = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    protected List<String> formerNames() {
        return List.of("InfluRecompenses");
    }

    @Override
    protected void enable() {
        daily = new DailyRewards(this);
        daily.load();
        placeholders("fluxrewards", this::placeholder);
        placeholders("recompenses", this::placeholder); // former prefix, used by the existing menus
        command("fluxrewards")
                .description("Login and playtime rewards administration.")
                .aliases("influrecompenses")
                .permission("influrecompenses.admin")
                .usage(messages().raw("admin-usage"))
                .executor(this::onCommand)
                .completer(this::complete)
                .register();
        command("loyalty")
                .aliases("fidelite")
                .description("Daily login and playtime rewards.")
                .executor((sender, label, args) -> {
                    Player p = Players.of(sender);
                    if (p != null) FluxApi.open(p, "fidelite");
                    return true;
                })
                .register();
        LoyaltyMenu.enable(this, daily);

        Bukkit.getPluginManager().registerEvents(this, this);
        for (Player p : Bukkit.getOnlinePlayers()) arrive(p);
        // midnight and playtime tiers for players staying online
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (daily.countDay(p) && daily.availableDay(p) != null) messages().send(p, "new-day");
                int now = daily.playtimeReached(p.getUniqueId());
                Integer before = reached.put(p.getUniqueId(), now);
                if (before != null && now > before) messages().send(p, "tier-reached");
            }
        }, 1200L, 1200L);
    }

    @Override
    protected void disable() {
        if (daily != null) daily.saveAll();
    }

    @Override
    public void reload() {
        super.reload();
        daily.load();
    }

    DailyRewards daily() {
        return daily;
    }

    /** Loads the player; true when this login starts a new day. */
    private boolean arrive(Player p) {
        daily.load(p.getUniqueId());
        boolean newDay = daily.countDay(p);
        daily.playtime(p.getUniqueId());
        reached.put(p.getUniqueId(), daily.playtimeReached(p.getUniqueId()));
        return newDay;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!arrive(p) || !daily.claimable(p)) return;
        // first login of the day: the loyalty menu opens once the resource pack is there (its images), or anyway later
        toOpen.add(p.getUniqueId());
        Bukkit.getScheduler().runTaskLater(this, () -> openLoyalty(p), PACK_TIMEOUT * 20L);
    }

    @EventHandler
    public void onPack(org.bukkit.event.player.PlayerResourcePackStatusEvent e) {
        var status = e.getStatus();
        if (status == org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.ACCEPTED
                || status == org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.DOWNLOADED) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> openLoyalty(p), 20L);
    }

    private void openLoyalty(Player p) {
        if (!p.isOnline() || !toOpen.remove(p.getUniqueId()) || !daily.claimable(p)) return;
        FluxApi.open(p, "fidelite");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        toOpen.remove(e.getPlayer().getUniqueId());
        reached.remove(e.getPlayer().getUniqueId());
        daily.playtime(e.getPlayer().getUniqueId());
        daily.unload(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ admin command

    private boolean onCommand(CommandSender sender, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (sub.equals("admin")) args = Arrays.copyOfRange(args, 1, args.length); // former "/rewards admin ..."
        sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (sub.equals("reload")) {
            reload();
            messages().send(sender, "reloaded");
            return true;
        }
        Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;
        if (target == null) {
            messages().send(sender, "admin-usage");
            return true;
        }
        switch (sub) {
            case "day", "jour" -> {
                int n = args.length > 2 ? parseInt(args[2]) : -1;
                if (n < 1) {
                    messages().send(sender, "admin-usage");
                    return true;
                }
                daily.setDay(target, n);
                messages().send(sender, "admin-day", "%player%", target.getName(), "%day%", String.valueOf(n));
            }
            case "reset" -> {
                daily.reset(target);
                messages().send(sender, "admin-reset", "%player%", target.getName());
            }
            default -> messages().send(sender, "admin-usage");
        }
        return true;
    }

    private List<String> complete(CommandSender sender, String[] args) {
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) return List.of("day", "reset", "reload").stream().filter(s -> s.startsWith(last)).toList();
        if (args.length == 2) return Players.complete(last);
        return List.of();
    }

    // ------------------------------------------------------------------ placeholders

    /** Whether a reward of this key waits in Flux for the player. */
    private static boolean waiting(UUID uuid, String key) {
        return Rewards.pending(uuid).stream().anyMatch(r -> key.equals(r.reward().key()));
    }

    /**
     * {@code %fluxrewards_...%} (also {@code %recompenses_...%}), indexes from 1, empty when absent:
     * <ul>
     *   <li>{@code pending} (or {@code en_attente}): every reward waiting in Flux, whatever the plugin</li>
     *   <li>{@code jour} (day of the cycle), {@code jours_total}, {@code cycle}, {@code jour_dispo} (oui/non: today's
     *   reward waits in Flux)</li>
     *   <li>{@code jour_<d>_etat}: recupere, disponible (waiting in Flux), manque, verrouille;
     *   {@code jour_<d>_nom}, {@code jour_<d>_ligne_<j>}</li>
     *   <li>{@code temps_joue}, {@code temps_atteints} (path progress), {@code temps_total}</li>
     *   <li>{@code temps_<i>_etat|nom|seuil|restant|ligne_<j>}</li>
     *   <li>{@code a_recuperer}: login and playtime rewards waiting in Flux</li>
     *   <li>{@code frise_connexion}, {@code frise_temps}: progress of the paths of the rewards menu, on the page shown;
     *   {@code frise_connexion_etats}, {@code frise_temps_etats}: colour of each step (vert, orange, rouge, eteint)</li>
     * </ul>
     */
    private String placeholder(Player player, String params) {
        if (params.equals("cycle")) return String.valueOf(daily.cycle());
        if (params.equals("temps_total")) return String.valueOf(daily.playtimeTiers().size());
        if (player == null) return "";
        UUID uuid = player.getUniqueId();
        if (params.equals("pending") || params.equals("en_attente")) return String.valueOf(Rewards.count(uuid));
        PlayerData d = daily.data(uuid);
        if (d == null) return "";
        switch (params) {
            case "jour" -> {
                return String.valueOf(d.dayOfCycle(daily.cycle()));
            }
            case "jours_total" -> {
                return String.valueOf(d.days);
            }
            case "jour_dispo" -> {
                return Format.yesNo(waiting(uuid, daily.dayKey(d)));
            }
            case "temps_joue" -> {
                return Format.duration(daily.playtime(uuid));
            }
            case "temps_atteints" -> {
                return String.valueOf(daily.playtimeReached(uuid));
            }
            case "frise_connexion" -> {
                // path of the menu's login frieze: steps reached on the page shown (today included)
                return String.valueOf(LoyaltyMenu.pathProgress(player, LoyaltyMenu.CONNEXION, d.dayOfCycle(daily.cycle())));
            }
            case "frise_connexion_etats" -> {
                return LoyaltyMenu.dayColors(player, daily);
            }
            case "frise_temps_etats" -> {
                return LoyaltyMenu.playtimeColors(player, daily);
            }
            case "frise_temps" -> {
                return String.valueOf(LoyaltyMenu.pathProgress(player, LoyaltyMenu.TEMPS, daily.playtimeReached(uuid)));
            }
            case "a_recuperer" -> {
                return String.valueOf(Rewards.pending(uuid).stream()
                        .filter(r -> r.reward().source().equals("daily") || r.reward().source().equals("playtime")).count());
            }
            default -> {
            }
        }
        String[] p = params.split("_", 3); // jour_<d>_<field> / temps_<i>_<field>
        if (p.length < 3) return null;
        int n = parseInt(p[1]);
        String field = p[2];
        if (p[0].equals("jour")) {
            if (n < 1 || n > daily.cycle()) return "";
            RewardTier tier = daily.dayTier(n);
            if (field.equals("etat")) {
                DailyRewards.State state = daily.dayState(d, n);
                boolean today = n == d.dayOfCycle(daily.cycle());
                if (state == DailyRewards.State.CLAIMED && today && waiting(uuid, daily.dayKey(d))) state = DailyRewards.State.AVAILABLE;
                return stateId(state);
            }
            if (field.equals("nom")) return tier != null ? tier.name() : messages().raw("day-name", "%day%", String.valueOf(n));
            if (field.startsWith("ligne_")) return tier != null ? line(player, tier.lore(), field) : "";
            return "";
        }
        if (p[0].equals("temps")) {
            List<RewardTier> tiers = daily.playtimeTiers();
            if (n < 1 || n > tiers.size()) return "";
            RewardTier tier = tiers.get(n - 1);
            return switch (field) {
                case "etat" -> {
                    DailyRewards.State state = daily.playtimeState(uuid, d, tier);
                    if (state == DailyRewards.State.CLAIMED && waiting(uuid, DailyRewards.playtimeKey(tier))) state = DailyRewards.State.AVAILABLE;
                    yield stateId(state);
                }
                case "nom" -> tier.name();
                case "seuil" -> Format.duration(tier.threshold());
                case "restant" -> Format.duration(Math.max(0, tier.threshold() - daily.playtime(uuid)));
                case "cle" -> DailyRewards.playtimeKey(tier);
                default -> field.startsWith("ligne_") ? line(player, tier.lore(), field) : "";
            };
        }
        return null;
    }

    /** State ids read by the existing menus (French, kept for compatibility). */
    private static String stateId(DailyRewards.State state) {
        return switch (state) {
            case CLAIMED -> "recupere";
            case AVAILABLE -> "disponible";
            case MISSED -> "manque";
            case LOCKED -> "verrouille";
        };
    }

    private static String line(Player player, List<String> lines, String field) {
        int j = parseInt(field.substring("ligne_".length()));
        if (j < 1 || j > lines.size()) return "";
        return Texts.placeholders(lines.get(j - 1), player);
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}

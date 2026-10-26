package fr.influencraft.fluxwebsite;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.reward.Rewards;
import fr.influencraft.flux.api.util.Players;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Bridge between the website (mineweb) and the game server:
 * <ul>
 *   <li>shop purchases and other commands to run right away ({@link ShopDelivery});</li>
 *   <li>vote rewards to claim ({@link VoteRewards}, source {@code votes} of the suite's rewards, {@code /voteclaims});</li>
 *   <li>Vote Party counter and rewards ({@link VoteParty}), vote reminder and {@code /voteinfo} ({@link VoteReminder});</li>
 *   <li>placeholders {@code %influbridge_voteparty_current|required%} and {@code %influbridge_flame_*%}.</li>
 * </ul>
 * Formerly InfluBridge (its game-only features are FluxTools modules now).
 */
public final class FluxWebsite extends FluxPlugin {

    private WebApi api;
    private VoteParty party;
    private VoteReminder reminder;
    private FlameCache flames;
    private VoteRewards votes;

    @Override
    protected List<String> formerNames() {
        return List.of("InfluBridge");
    }

    @Override
    protected void enable() {
        api = new WebApi(getConfig().getString("api-base-url", ""), getConfig().getString("api-secret", ""));
        if (api.ready()) {
            ShopDelivery shop = new ShopDelivery(this, api);
            long period = Math.max(5, getConfig().getLong("poll-interval-seconds", 30)) * 20L;
            Bukkit.getScheduler().runTaskTimerAsynchronously(this, shop::poll, period, period);
        } else {
            getLogger().warning("api-base-url / api-secret not set in config.yml: shop delivery and vote rewards are disabled.");
        }
        if (api.reachable()) {
            flames = new FlameCache(this, api);
            long period = Math.max(5, getConfig().getLong("streak-cache-interval-seconds", 30)) * 20L;
            Bukkit.getScheduler().runTaskTimerAsynchronously(this, flames::refresh, period, period);
        }
        party = new VoteParty(this, api);
        reminder = new VoteReminder(this, api, party);
        reminder.start();
        votes = new VoteRewards(this, api, party);
        Rewards.register(this, votes);
        placeholders("influbridge", this::placeholder);
        registerCommands();
    }

    @Override
    public void reload() {
        super.reload();
        party.reload();
        reminder.start();
    }

    private void registerCommands() {
        command("voteclaims")
                .description("Claim your vote rewards.")
                .aliases("votekeys")
                .executor((sender, label, args) -> {
                    Player p = Players.of(sender);
                    if (p == null) {
                        messages().send(sender, "players-only");
                        return true;
                    }
                    if (args.length > 1 || (args.length == 1 && !args[0].equalsIgnoreCase("claim"))) return false;
                    votes.command(p);
                    return true;
                })
                .register();
        command("voteinfo")
                .description("Vote sites available and rewards waiting.")
                .executor((sender, label, args) -> {
                    Player p = Players.of(sender);
                    if (p != null) reminder.showTo(p);
                    return true;
                })
                .register();
        command("votenotif")
                .description("Turns the periodic vote reminder on or off.")
                .usage("/<command> <on|off>")
                .executor((sender, label, args) -> {
                    Player p = Players.of(sender);
                    if (p == null) return true;
                    if (args.length != 1 || !(args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("off"))) return false;
                    boolean on = args[0].equalsIgnoreCase("on");
                    reminder.setEnabled(p, on);
                    messages().send(p, on ? "reminder-on" : "reminder-off");
                    return true;
                })
                .register();
        command("voteparty")
                .description("Vote Party counter (website: add; admins: add|remove <amount>).")
                .aliases("influvoteparty")
                .usage("/<command> <add [amount]|remove <amount>>")
                .permission("influbridge.voteparty.admin")
                .executor(this::voteParty)
                .register();
        command("website")
                .description("FluxWebsite admin.")
                .aliases("influbridge")
                .usage("/<command> reload")
                .permission("influbridge.admin")
                .executor((sender, label, args) -> {
                    if (args.length != 1 || !args[0].equalsIgnoreCase("reload")) return false;
                    reload();
                    messages().send(sender, "reloaded", "%required%", String.valueOf(party.votesRequired()),
                            "%current%", String.valueOf(party.current()),
                            "%reminder%", reminder.globallyEnabled() ? messages().raw("reminder-every", "%seconds%",
                                    String.valueOf(reminder.intervalSeconds())) : messages().raw("reminder-disabled"));
                    return true;
                })
                .register();
    }

    private boolean voteParty(CommandSender sender, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("add")) {
            party.add(1);
            return true;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("remove"))) {
            int amount;
            try {
                amount = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                messages().send(sender, "invalid-amount", "%value%", args[1]);
                return true;
            }
            if (amount <= 0) {
                messages().send(sender, "invalid-amount", "%value%", args[1]);
                return true;
            }
            if (args[0].equalsIgnoreCase("add")) party.add(amount);
            else party.remove(amount);
            messages().send(sender, "vote-party-counter", "%current%", String.valueOf(party.current()),
                    "%required%", String.valueOf(party.votesRequired()));
            return true;
        }
        return false;
    }

    private String placeholder(Player player, String params) {
        if (params.equals("voteparty_current")) return String.valueOf(party.current());
        if (params.equals("voteparty_required")) return String.valueOf(party.votesRequired());
        if (player == null || flames == null) return "";
        FlameCache.Flame f = flames.get(player.getName());
        if (f == null) return "";
        return switch (params) {
            case "flame_streak" -> String.valueOf(f.current());
            case "flame_best" -> String.valueOf(f.best());
            case "flame_tier" -> f.tier();
            case "flame_next_milestone" -> f.nextMilestone() > 0 ? String.valueOf(f.nextMilestone()) : "-";
            case "flame_legendary_chance" -> String.format("%.2f", f.legendaryChance());
            default -> null;
        };
    }
}

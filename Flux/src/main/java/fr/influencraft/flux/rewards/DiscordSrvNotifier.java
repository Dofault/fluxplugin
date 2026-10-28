package fr.influencraft.flux.rewards;

import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.dependencies.jda.api.JDA;
import github.scarsz.discordsrv.dependencies.jda.api.entities.User;
import github.scarsz.discordsrv.dependencies.jda.api.events.interaction.ButtonClickEvent;
import github.scarsz.discordsrv.dependencies.jda.api.hooks.ListenerAdapter;
import github.scarsz.discordsrv.dependencies.jda.api.interactions.components.Button;
import github.scarsz.discordsrv.util.DiscordUtil;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@link DiscordNotifier} over DiscordSRV: its account link gives the player's Discord account, its bot sends the
 * private message with a « stop » button. A click (only by the linked account) records the opt-out in Flux's
 * database ({@link RewardStore#setDiscordOptOut}). Loaded only when DiscordSRV is installed.
 */
final class DiscordSrvNotifier extends ListenerAdapter implements DiscordNotifier {

    private static final String BUTTON = "flux-rewards-optout:";

    private final Plugin flux;
    private final RewardStore store;
    private final Supplier<String> doneText;
    private volatile JDA listening;
    private BukkitTask waiting;

    DiscordSrvNotifier(Plugin flux, RewardStore store, Supplier<String> doneText) {
        this.flux = flux;
        this.store = store;
        this.doneText = doneText;
        // DiscordSRV connects after the start: listen to the buttons once its bot is there
        waiting = Bukkit.getScheduler().runTaskTimerAsynchronously(flux, this::listen, 200L, 600L);
    }

    private void listen() {
        JDA jda = DiscordUtil.getJda();
        if (jda == null || jda == listening) return;
        jda.addEventListener(this);
        listening = jda;
        if (waiting != null) waiting.cancel();
    }

    @Override
    public void send(UUID player, String message, String buttonLabel) {
        if (store.discordOptOut(player)) return;
        String id = DiscordSRV.getPlugin().getAccountLinkManager().getDiscordId(player);
        JDA jda = DiscordUtil.getJda();
        if (id == null || jda == null) return;
        listen();
        try {
            User user = jda.retrieveUserById(id).complete();
            user.openPrivateChannel().complete().sendMessage(message)
                    .setActionRow(Button.secondary(BUTTON + player, buttonLabel)).queue();
        } catch (RuntimeException e) {
            // closed private messages, unknown account: nothing to do
            flux.getLogger().fine("No Discord reminder for " + player + ": " + e);
        }
    }

    @Override
    public void onButtonClick(ButtonClickEvent event) {
        String component = event.getComponentId();
        if (component == null || !component.startsWith(BUTTON)) return;
        UUID player;
        try {
            player = UUID.fromString(component.substring(BUTTON.length()));
        } catch (IllegalArgumentException e) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(flux, () -> {
            String linked = DiscordSRV.getPlugin().getAccountLinkManager().getDiscordId(player);
            if (!event.getUser().getId().equals(linked)) return;
            store.setDiscordOptOut(player, true);
        });
        event.editComponents().queue();
        event.getChannel().sendMessage(doneText.get()).queue();
    }
}

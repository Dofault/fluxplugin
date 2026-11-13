package fr.influencraft.flux.adapters.packetevents;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAdvancementTab;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;

/**
 * The advancements key (L) opens one of our menus instead of the vanilla screen ({@code advancement-redirect} in
 * Flux's config). The client tells when the screen opens a tab ({@code OPENED_TAB}) and when it closes
 * ({@code CLOSED_SCREEN}): only the opening runs the command. The command goes through
 * {@link PlayerCommandPreprocessEvent} first, as if typed, so plugins listening to typed commands (Typewriter) see it.
 */
public final class AdvancementRedirect extends PacketListenerAbstract {

    private final Plugin flux;
    private final Supplier<String> command;

    /** {@code command}: without the slash, blank = off (read at each press, so a reload applies at once). */
    public AdvancementRedirect(Plugin flux, Supplier<String> command) {
        super(PacketListenerPriority.NORMAL);
        this.flux = flux;
        this.command = command;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.ADVANCEMENT_TAB) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        if (new WrapperPlayClientAdvancementTab(event).getAction() != WrapperPlayClientAdvancementTab.Action.OPENED_TAB) return;
        String cmd = command.get();
        if (cmd == null || cmd.isBlank() || !flux.isEnabled()) return;
        Bukkit.getScheduler().runTask(flux, () -> {
            if (!player.isOnline()) return;
            PlayerCommandPreprocessEvent typed = new PlayerCommandPreprocessEvent(player, "/" + cmd);
            Bukkit.getPluginManager().callEvent(typed);
            if (!typed.isCancelled()) Bukkit.dispatchCommand(player, typed.getMessage().substring(1));
        });
    }
}

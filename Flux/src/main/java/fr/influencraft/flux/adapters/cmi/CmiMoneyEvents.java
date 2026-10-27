package fr.influencraft.flux.adapters.cmi;

import com.Zrips.CMI.events.CMIUserBalanceChangeEvent;
import fr.influencraft.flux.api.economy.BalanceChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/** CMI's balance changes as {@link BalanceChangeEvent}s of the main currency: the only class of the suite using CMI. */
public final class CmiMoneyEvents implements Listener {

    @EventHandler
    public void onBalance(CMIUserBalanceChangeEvent event) {
        Bukkit.getPluginManager().callEvent(new BalanceChangeEvent(event.getUser().getUuid(), "vault", event.getFrom(), event.getTo(),
                "Deposit".equals(event.getActionType())));
    }
}

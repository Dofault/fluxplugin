package io.dofault.supermarket.integration;

import dev.lone.itemsadder.api.Events.ItemsAdderLoadDataEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * Prévient quand ItemsAdder a fini de charger ses données (au démarrage, puis à chaque
 * {@code /iareload}). Enregistré seulement si ItemsAdder est présent : la classe de l'événement
 * n'existe pas sans lui.
 */
public final class ItemsAdderLoadListener implements Listener {

    private final Runnable onLoad;

    public ItemsAdderLoadListener(Runnable onLoad) {
        this.onLoad = onLoad;
    }

    @EventHandler
    public void onLoad(ItemsAdderLoadDataEvent event) {
        onLoad.run();
    }
}

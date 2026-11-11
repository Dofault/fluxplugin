package fr.influencraft.flux.adapters.mcgui;

import ai.resourcepack.ui.MCGUI;
import ai.resourcepack.ui.event.PageCloseEvent;
import ai.resourcepack.ui.framework.api.CloseReason;
import fr.influencraft.flux.api.screen.Screens;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;

/** MC GUI (ResourcePack AI, GPL-3.0) behind {@link Screens}: HTML pages drawn on maps, full screen. */
public final class McguiScreens implements Screens.Provider, Listener {

    private final Plugin mcgui;

    public McguiScreens() {
        mcgui = Bukkit.getPluginManager().getPlugin("MCGUI");
    }

    @Override
    public String name() {
        return "MC GUI";
    }

    @Override
    public Path pagesFolder() {
        return mcgui.getDataFolder().toPath().resolve(mcgui.getConfig().getString("pages-folder", "pages")).normalize();
    }

    @Override
    public boolean open(Player player, String page) {
        return MCGUI.exists(page) && MCGUI.open(player, page);
    }

    @Override
    public boolean close(Player player) {
        return MCGUI.close(player);
    }

    @Override
    public String page(Player player) {
        return MCGUI.page(player);
    }

    @EventHandler
    public void onClose(PageCloseEvent e) {
        Screens.fireClosed(new Screens.Closed(e.getPlayer(), e.getPage(), e.getReason() == CloseReason.PLAYER));
    }
}

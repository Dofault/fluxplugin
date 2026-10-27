package fr.influencraft.flux;

import fr.influencraft.flux.api.MenuView;
import fr.influencraft.flux.session.MenuSession;
import org.bukkit.entity.Player;

/** {@link MenuView} of an open {@link MenuSession}, handed to other plugins. */
final class FluxView implements MenuView {

    private final Flux plugin;
    private final Player player;
    private final MenuSession session;

    FluxView(Flux plugin, Player player, MenuSession session) {
        this.plugin = plugin;
        this.player = player;
        this.session = session;
    }

    MenuSession session() {
        return session;
    }

    @Override
    public Player player() {
        return player;
    }

    @Override
    public String menuId() {
        return session.menu().id();
    }

    @Override
    public String pageId() {
        return session.page() == null ? null : session.page().id();
    }

    @Override
    public boolean isOpen() {
        return session.isOpen() && plugin.menus().session(player.getUniqueId()) == session;
    }

    @Override
    public String title() {
        return session.fullTitle(player);
    }

    @Override
    public int titleCursor() {
        // a chest title starts 8 px from the left edge of the window
        return 8 + PathRenderer.advance(title());
    }

    @Override
    public void showTitle(String title) {
        plugin.menus().showTitle(player, session, title);
    }

    @Override
    public void refreshButtons() {
        plugin.menus().renderButtons(player, session);
    }

    @Override
    public void refresh() {
        plugin.menus().refresh(player, session);
    }

    @Override
    public void close() {
        if (isOpen()) player.closeInventory();
    }

    @Override
    public java.util.Map<Integer, org.bukkit.inventory.ItemStack> inputs() {
        return session.inputs();
    }

    @Override
    public java.util.List<org.bukkit.inventory.ItemStack> takeInputs() {
        return session.takeInputs();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FluxView v && v.session == session;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(session);
    }
}

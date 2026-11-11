package fr.influencraft.flux.api.screen;

import fr.influencraft.flux.api.client.Clients;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Full screen pages drawn from HTML and CSS (MC GUI today, see docs/technique/mcgui.md). A page is a file
 * {@code <pages folder>/<name>.html}; a plugin writes its pages there ({@link #pagesFolder()}) and opens them by name
 * ({@code fluxdungeons/classes}). Buttons of a page run commands ({@code data-command}), so a page needs no code to
 * act; {@link #onClose} tells the plugins when a page is left.
 * <p>
 * Pages are an extra, never the only way: no adapter, a Bedrock client or a page that cannot be shown and
 * {@link #canShow} is false, the plugin opens its own menu instead.
 */
public final class Screens {

    /** What an adapter provides. */
    public interface Provider {
        String name();

        /** The folder the page names are read from. */
        Path pagesFolder();

        boolean open(Player player, String page);

        boolean close(Player player);

        /** The page the player has open, or null. */
        String page(Player player);
    }

    /** A page closed: who, which page, and whether the player left it himself (sneak, close button). */
    public record Closed(Player player, String page, boolean byPlayer) {
    }

    private static volatile Provider provider;
    private static final List<Owned> closeListeners = new CopyOnWriteArrayList<>();

    private record Owned(Plugin owner, Consumer<Closed> listener) {
    }

    private Screens() {
    }

    /** Called by Flux. */
    public static void use(Provider p) {
        provider = p;
    }

    public static String providerName() {
        Provider p = provider;
        return p == null ? "none" : p.name();
    }

    public static boolean available() {
        return provider != null;
    }

    /** True when pages can be shown to this player (an adapter is there and he plays on Java). */
    public static boolean canShow(Player player) {
        return provider != null && player != null && !Clients.bedrock(player);
    }

    /** The folder pages are written to, or null without an adapter. */
    public static Path pagesFolder() {
        Provider p = provider;
        return p == null ? null : p.pagesFolder();
    }

    /** Opens a page; false when it could not be (no adapter, unknown page, Bedrock): open your own menu then. */
    public static boolean open(Player player, String page) {
        Provider p = provider;
        if (p == null || !canShow(player)) return false;
        try {
            return p.open(player, page);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static boolean close(Player player) {
        Provider p = provider;
        return p != null && p.close(player);
    }

    public static String page(Player player) {
        Provider p = provider;
        return p == null ? null : p.page(player);
    }

    /** Called when a page is closed, until the owner is disabled. */
    public static void onClose(Plugin owner, Consumer<Closed> listener) {
        closeListeners.add(new Owned(owner, listener));
    }

    /** Called by the adapter. */
    public static void fireClosed(Closed closed) {
        for (Owned o : closeListeners) {
            if (!o.owner().isEnabled()) {
                closeListeners.remove(o);
                continue;
            }
            try {
                o.listener().accept(closed);
            } catch (RuntimeException e) {
                o.owner().getLogger().warning("Page close listener failed: " + e);
            }
        }
    }
}

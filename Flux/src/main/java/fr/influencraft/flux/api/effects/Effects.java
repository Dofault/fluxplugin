package fr.influencraft.flux.api.effects;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Effects described in config files (bonus of an armour set, of an item...) that a player gets while a plugin says so.
 * Plugins of the suite never call the engine directly (libreforge today, {@code adapters/libreforge}). Without an
 * engine, nothing fails: {@link #compile} gives an empty bundle and no effect runs.
 *
 * <pre>
 *   Bundle bonus = Effects.compile(this, "my_bonus", section.getMapList("effects"), section.getMapList("conditions"));
 *   Effects.provide(this, player -> wearing(player) ? List.of(bonus) : List.of());
 *   Effects.refresh(player);   // when what the provider answers changed
 * </pre>
 */
public final class Effects {

    /** Compiled effects, to give back to {@link #provide}. */
    public interface Bundle {
    }

    /** What an adapter provides. */
    public interface Engine {
        Bundle compile(String owner, String id, List<Map<?, ?>> effects, List<Map<?, ?>> conditions);

        /** Installs the one provider of the engine; it asks {@link Effects#bundles} for a player. */
        void start();

        void refresh(Player player);
    }

    private static final Bundle EMPTY = new Bundle() {
    };
    private static final Map<Plugin, Function<Player, Collection<Bundle>>> PROVIDERS = new ConcurrentHashMap<>();
    private static volatile Engine engine;

    private Effects() {
    }

    /** True when an effect engine runs. */
    public static boolean available() {
        return engine() != null;
    }

    /** Runs {@code task} once the engine is ready: it may be loaded after the plugins of the suite (eco loads libreforge). */
    public static void whenReady(Plugin owner, Runnable task) {
        if (available()) {
            task.run();
            return;
        }
        int[] tries = {0};
        Bukkit.getScheduler().runTaskTimer(owner, t -> {
            if (available() || ++tries[0] > 60) {
                t.cancel();
                if (engine == null) owner.getLogger().warning("No effect engine (libreforge): effects are off.");
                task.run();
            }
        }, 20, 20);
    }

    public static Bundle compile(Plugin owner, String id, List<Map<?, ?>> effects, List<Map<?, ?>> conditions) {
        Engine e = engine();
        return e == null ? EMPTY : e.compile(owner.getName().toLowerCase(java.util.Locale.ROOT), id, effects, conditions);
    }

    /** {@code provider} answers the bundles a player has right now; dropped when {@code owner} stops. */
    public static void provide(Plugin owner, Function<Player, Collection<Bundle>> provider) {
        PROVIDERS.put(owner, provider);
    }

    public static void refresh(Player player) {
        Engine e = engine;
        if (e != null) e.refresh(player);
    }

    /** Every bundle of a player, asked by the engine. */
    public static List<Bundle> bundles(Player player) {
        return PROVIDERS.values().stream().flatMap(p -> p.apply(player).stream()).filter(b -> b != EMPTY).toList();
    }

    /** Called by Flux when a plugin stops. */
    public static void unregisterAll(Plugin owner) {
        PROVIDERS.remove(owner);
    }

    private static Engine engine() {
        if (engine == null && Bukkit.getPluginManager().isPluginEnabled("libreforge")) {
            try {
                Engine started = new fr.influencraft.flux.adapters.libreforge.LibreforgeEffects();
                started.start();
                engine = started;
            } catch (LinkageError | RuntimeException e) {
                Bukkit.getLogger().warning("[Flux] libreforge unusable: " + e);
            }
        }
        return engine;
    }
}

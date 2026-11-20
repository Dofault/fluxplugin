package fr.influencraft.flux.api.mobs;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Mob stacks: one entity standing for several identical mobs (FluxStack is the provider). Other plugins read and
 * change a stack through here, never through FluxStack itself: FluxSpawner adds the mobs it makes to the stack already
 * standing at its spawner instead of spawning one entity per mob.
 *
 * <p>Two mobs stack only when every {@linkplain #keyPart key part} gives the same text for both, besides the
 * provider's own rule (type, age, variant). A plugin that owns mobs keeps them apart that way: the mobs of two
 * spawners, or a spawner's cows and the cows of the wild, never become one stack.</p>
 *
 * <p>Without a provider every mob counts as one and {@link #setCount} does nothing.</p>
 */
public final class MobStacks {

    /** What the stacking plugin provides (main thread). */
    public interface Provider {
        /** Number of mobs this entity stands for (1 for a mob that is not a stack). */
        int count(Entity entity);

        /**
         * Makes the entity stand for {@code count} mobs (1: a normal mob again), within the provider's limits.
         *
         * @return the count actually set
         */
        int setCount(LivingEntity entity, int count);

        /** Most mobs one stack may hold. */
        int maxCount();
    }

    private static volatile Provider provider;
    private static final Map<Plugin, Function<Entity, String>> KEY_PARTS = new ConcurrentHashMap<>();

    private MobStacks() {
    }

    public static void provide(Provider p) {
        provider = p;
    }

    public static boolean available() {
        return provider != null;
    }

    public static int count(Entity entity) {
        Provider p = provider;
        return p == null || entity == null ? 1 : Math.max(1, p.count(entity));
    }

    /** Count set (1 without a provider: nothing changes). */
    public static int setCount(LivingEntity entity, int count) {
        Provider p = provider;
        return p == null || entity == null ? 1 : p.setCount(entity, count);
    }

    /** Most mobs in one stack (1 without a provider). */
    public static int maxCount() {
        Provider p = provider;
        return p == null ? 1 : Math.max(1, p.maxCount());
    }

    /**
     * Adds a part to the identity of every mob: {@code part} returns a text (null or empty when the plugin has
     * nothing to say about that mob). Called by the provider for each mob it considers, keep it cheap (a data key).
     */
    public static void keyPart(Plugin owner, Function<Entity, String> part) {
        KEY_PARTS.put(owner, part);
    }

    /** Every key part of this mob, joined (empty when no plugin marks it). Used by the provider. */
    public static String key(Entity entity) {
        if (KEY_PARTS.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Function<Entity, String> part : KEY_PARTS.values()) {
            String s = part.apply(entity);
            if (s != null && !s.isEmpty()) sb.append('|').append(s);
        }
        return sb.toString();
    }

    public static void unregisterAll(Plugin owner) {
        KEY_PARTS.remove(owner);
    }
}

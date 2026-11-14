package fr.influencraft.fluxdungeons.mob;

import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.animation.AnimationIterator;
import kr.toxicity.model.api.animation.AnimationModifier;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import kr.toxicity.model.api.tracker.DummyTracker;
import kr.toxicity.model.api.tracker.EntityTracker;
import kr.toxicity.model.api.tracker.TrackerUpdateAction;
import kr.toxicity.model.api.util.function.BonePredicate;
import org.bukkit.entity.LivingEntity;

import java.util.Collection;

/**
 * The only class touching BetterModel: an animated model worn by a monster. Loaded only when BetterModel runs
 * (checked by {@link Mobs}), so FluxDungeons works without it (bosses then show their vanilla mob).
 * <p>
 * Bones whose name ends with {@code _empty} are a second look of a part (the empty cheeks of the Harvest Hamster):
 * hidden when the model is put on, shown by the monster's abilities ({@link Handle#parts}).
 */
final class ModelBridge {

    /** A model on a monster. */
    interface Handle {
        /** Plays an animation once (attack, hurt, death...); false when the model has no such animation. */
        boolean play(String animation);

        /** Plays an animation in a loop until {@link #stop(String)} (running while an ability moves the monster). */
        default boolean loop(String animation) {
            return false;
        }

        default void stop(String animation) {
        }

        /** Shows or hides bones (by name). */
        default void parts(boolean visible, Collection<String> bones) {
        }

        /** How far the head bones ({@code h_}) turn to follow the creature's look, each side (degrees). */
        default void headRange(float degrees) {
        }

        /** Outline glow of the whole model in this colour (0xRRGGBB), off with a negative colour. */
        default void glow(int rgb) {
        }

        void close();
    }

    private ModelBridge() {
    }

    /** Puts the model on the monster, null when the model does not exist. */
    static Handle attach(LivingEntity entity, String model) {
        var renderer = BetterModel.modelOrNull(model);
        if (renderer == null) return null;
        EntityTracker tracker = renderer.getOrCreate(BukkitAdapter.adapt(entity));
        tracker.update(TrackerUpdateAction.togglePart(false), BonePredicate.from(b -> b.name().rawName().endsWith("_empty")));
        return new Handle() {
            public boolean play(String animation) {
                return !tracker.isClosed() && tracker.animate(animation, AnimationModifier.DEFAULT_WITH_PLAY_ONCE);
            }

            public boolean loop(String animation) {
                return !tracker.isClosed() && tracker.animate(animation,
                        AnimationModifier.builder().type(AnimationIterator.Type.LOOP).build());
            }

            public void stop(String animation) {
                if (!tracker.isClosed()) tracker.stopAnimation(animation);
            }

            public void parts(boolean visible, Collection<String> bones) {
                if (tracker.isClosed()) return;
                tracker.update(TrackerUpdateAction.togglePart(visible), BonePredicate.from(b -> bones.contains(b.name().rawName())));
            }

            public void headRange(float degrees) {
                if (tracker.isClosed()) return;
                tracker.bodyRotator().setValue(d -> {
                    d.setHeadUneven(false);
                    d.setMaxHead(degrees);
                });
            }

            public void glow(int rgb) {
                if (tracker.isClosed()) return;
                tracker.update(rgb < 0 ? TrackerUpdateAction.glow(false)
                        : TrackerUpdateAction.composite(TrackerUpdateAction.glow(true), TrackerUpdateAction.glowColor(rgb)), BonePredicate.TRUE);
            }

            public void close() {
                if (!tracker.isClosed()) tracker.close();
            }
        };
    }

    /**
     * A model standing on its own at a place, turned as the place is (no creature under it: the collapse of a boss
     * that is already dead), shown to these players; null when the model does not exist. To be closed by the caller.
     */
    static Handle stand(org.bukkit.Location at, String model, Collection<org.bukkit.entity.Player> viewers) {
        var renderer = BetterModel.modelOrNull(model);
        if (renderer == null) return null;
        DummyTracker tracker = renderer.create(BukkitAdapter.adapt(at));
        tracker.update(TrackerUpdateAction.togglePart(false), BonePredicate.from(b -> b.name().rawName().endsWith("_empty")));
        for (org.bukkit.entity.Player p : viewers) tracker.spawn(BukkitAdapter.adapt(p));
        return new Handle() {
            public boolean play(String animation) {
                return !tracker.isClosed() && tracker.animate(animation, AnimationModifier.DEFAULT_WITH_PLAY_ONCE);
            }

            public void parts(boolean visible, Collection<String> bones) {
                if (tracker.isClosed()) return;
                tracker.update(TrackerUpdateAction.togglePart(visible), BonePredicate.from(b -> bones.contains(b.name().rawName())));
            }

            public void close() {
                if (!tracker.isClosed()) tracker.close();
            }
        };
    }

    /**
     * The creature a BetterModel hit box stands for (players hit the box of a model, not the creature under it), else
     * the entity itself.
     */
    public static org.bukkit.entity.Entity body(org.bukkit.entity.Entity e) {
        if (e instanceof kr.toxicity.model.api.nms.HitBox box) {
            try {
                org.bukkit.entity.Entity source = org.bukkit.Bukkit.getEntity(box.source().uuid());
                if (source != null) return source;
            } catch (RuntimeException ignored) {
                // a box whose creature is gone
            }
        }
        return e;
    }
}

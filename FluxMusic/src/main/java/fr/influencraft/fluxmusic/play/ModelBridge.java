package fr.influencraft.fluxmusic.play;

import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.animation.AnimationIterator;
import kr.toxicity.model.api.animation.AnimationModifier;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import kr.toxicity.model.api.tracker.DummyTracker;
import kr.toxicity.model.api.tracker.ModelRotation;
import kr.toxicity.model.api.tracker.ModelScaler;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * The only class touching BetterModel: a musician's model standing at a place, without any entity under it (nothing
 * to push, kill or save), shown to the players given to {@link Figure#show}. Loaded only when BetterModel runs.
 *
 * <p>FluxMascot and FluxDungeons each have a bridge of their own for the same job; they belong together in a Flux
 * API (to do, with FluxPets').</p>
 */
final class ModelBridge {

    interface Figure {
        void show(Player viewer);

        void hide(Player viewer);

        /** Loops this animation in place of the one looping before (a short blend between the two). */
        void loop(String animation);

        /** Whether the model has this animation. */
        boolean has(String animation);

        /** Plays an animation once over the loop (from its start again when it was still playing). */
        void once(String animation);

        /** Loops a pose over the base loop, in place of the pose held before; null lets go of it. */
        void hold(String animation);

        /** Speed of the looping animations from now on (1 = as drawn); read by BetterModel as it plays. */
        void speed(float speed);

        /** Moves the model to another place. */
        void move(Location to);

        void close();
    }

    private ModelBridge() {
    }

    /** Null when no model has this name. A model without entity turns the other way round, hence the minus. */
    static Figure figure(String model, Location at, float scale, float yaw) {
        var renderer = BetterModel.modelOrNull(model);
        if (renderer == null) return null;
        DummyTracker tracker = renderer.create(BukkitAdapter.adapt(at.clone()));
        tracker.scaler(ModelScaler.value(scale));
        float facing = -yaw;
        tracker.rotation(() -> new ModelRotation(0, facing));
        return new Figure() {
            private String current;
            private volatile float speed = 1f;
            private String held;

            public boolean has(String animation) {
                return renderer.animation(animation).isPresent();
            }

            public void once(String animation) {
                if (tracker.isClosed()) return;
                tracker.stopAnimation(animation);
                tracker.animate(animation, AnimationModifier.builder().type(AnimationIterator.Type.PLAY_ONCE)
                        .start(0).end(2).build());
            }

            public void hold(String animation) {
                if (tracker.isClosed() || java.util.Objects.equals(animation, held)) return;
                if (held != null) tracker.stopAnimation(held);
                // start: the hand slides to its new place in a few ticks
                if (animation != null) tracker.animate(animation, AnimationModifier.builder()
                        .type(AnimationIterator.Type.LOOP).start(3).build());
                held = animation;
            }

            public void speed(float value) {
                speed = value;
            }

            public void move(Location to) {
                // a copy: BetterModel keeps the location it is given and skips a move to an "equal" one
                if (!tracker.isClosed()) tracker.location(BukkitAdapter.adapt(to.clone()));
            }

            public void show(Player viewer) {
                if (!tracker.isClosed()) tracker.spawn(BukkitAdapter.adapt(viewer));
            }

            public void hide(Player viewer) {
                if (!tracker.isClosed()) tracker.remove(BukkitAdapter.adapt(viewer));
            }

            public void loop(String animation) {
                if (tracker.isClosed() || animation.equals(current)) return;
                if (current != null) tracker.stopAnimation(current);
                // start: ticks taken to reach the animation's first pose from the pose shown before it
                tracker.animate(animation, AnimationModifier.builder().type(AnimationIterator.Type.LOOP).start(5)
                        .speed(() -> speed).build());
                current = animation;
            }

            public void close() {
                if (!tracker.isClosed()) tracker.close();
            }
        };
    }
}

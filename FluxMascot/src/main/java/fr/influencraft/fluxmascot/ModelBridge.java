package fr.influencraft.fluxmascot;

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
 * The only class touching BetterModel: a model standing in the world without any entity under it, shown to one
 * player only. Loaded only when BetterModel runs (checked by {@link FluxMascot}).
 */
final class ModelBridge {

    /** A model shown to one player. */
    interface Handle {
        /** Plays an animation once. */
        void play(String animation);

        /** Plays an animation in a loop until {@link #stop(String)}. */
        void loop(String animation);

        void stop(String animation);

        /** Moves the model and turns it (Minecraft yaw of the way it faces). */
        void move(Location to, float yaw);

        void close();
    }

    private ModelBridge() {
    }

    /** Shows the model to the viewer at this scale, null when no model has this name. */
    static Handle show(Player viewer, String model, float scale, Location at, float yaw) {
        var renderer = BetterModel.modelOrNull(model);
        if (renderer == null) return null;
        float[] facing = {yaw};
        DummyTracker tracker = renderer.create(BukkitAdapter.adapt(at.clone()));
        tracker.scaler(ModelScaler.value(scale));
        tracker.rotation(() -> new ModelRotation(0, facing[0]));
        tracker.spawn(BukkitAdapter.adapt(viewer));
        return new Handle() {
            public void play(String animation) {
                if (!tracker.isClosed()) tracker.animate(animation, AnimationModifier.DEFAULT_WITH_PLAY_ONCE);
            }

            public void loop(String animation) {
                if (!tracker.isClosed()) tracker.animate(animation,
                        AnimationModifier.builder().type(AnimationIterator.Type.LOOP).build());
            }

            public void stop(String animation) {
                if (!tracker.isClosed()) tracker.stopAnimation(animation);
            }

            public void move(Location to, float yaw) {
                if (tracker.isClosed()) return;
                facing[0] = yaw;
                // a copy: BetterModel keeps the location it is given and skips a move to an "equal" one, so handing
                // it the caller's own (mutated) Location would never move the model
                tracker.location(BukkitAdapter.adapt(to.clone()));
            }

            public void close() {
                if (!tracker.isClosed()) tracker.close();
            }
        };
    }
}

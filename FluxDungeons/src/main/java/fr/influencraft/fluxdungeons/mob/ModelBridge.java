package fr.influencraft.fluxdungeons.mob;

import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.animation.AnimationModifier;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import kr.toxicity.model.api.tracker.EntityTracker;
import org.bukkit.entity.LivingEntity;

/**
 * The only class touching BetterModel: an animated model worn by a monster. Loaded only when BetterModel runs
 * (checked by {@link Mobs}), so FluxDungeons works without it (bosses then show their vanilla mob).
 */
final class ModelBridge {

    /** A model on a monster. */
    interface Handle {
        /** Plays an animation once (attack, hurt, death...); false when the model has no such animation. */
        boolean play(String animation);

        void close();
    }

    private ModelBridge() {
    }

    /** Puts the model on the monster, null when the model does not exist. */
    static Handle attach(LivingEntity entity, String model) {
        var renderer = BetterModel.modelOrNull(model);
        if (renderer == null) return null;
        EntityTracker tracker = renderer.getOrCreate(BukkitAdapter.adapt(entity));
        return new Handle() {
            public boolean play(String animation) {
                return !tracker.isClosed() && tracker.animate(animation, AnimationModifier.DEFAULT_WITH_PLAY_ONCE);
            }

            public void close() {
                if (!tracker.isClosed()) tracker.close();
            }
        };
    }
}

package fr.influencraft.influpet.pets;

import com.willfp.libreforge.Holder;
import com.willfp.libreforge.conditions.ConditionList;
import com.willfp.libreforge.effects.EffectList;
import org.bukkit.NamespacedKey;

/** Holder libreforge d'un familier à un niveau donné (comme com.willfp.ecopets.pets.PetLevel). */
public final class PetLevel implements Holder {

    private final Pet pet;
    private final int level;
    private final EffectList effects;
    private final ConditionList conditions;
    private final NamespacedKey id;

    PetLevel(Pet pet, int level, EffectList effects, ConditionList conditions) {
        this.pet = pet;
        this.level = level;
        this.effects = effects;
        this.conditions = conditions;
        this.id = Pet.holderKey(pet, level);
    }

    public Pet pet() {
        return pet;
    }

    public int level() {
        return level;
    }

    @Override
    public EffectList getEffects() {
        return effects;
    }

    @Override
    public ConditionList getConditions() {
        return conditions;
    }

    @Override
    public NamespacedKey getId() {
        return id;
    }
}

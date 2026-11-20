package fr.influencraft.fluxspawner;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.util.List;

/**
 * A kind of spawner (types.yml): the mob it makes and its rules.
 *
 * @param id             key in types.yml, item id {@code fluxspawner:<id>}
 * @param entity         the mob
 * @param name           shown name, null = the mob's name in the game's language
 * @param icon           icon in menus (its spawn egg by default)
 * @param profile        level profile of levels.yml
 * @param rate           production multiplier (1 = the value of the level, for balancing one type)
 * @param costMultiplier multiplier of every goal measured in hours (money and items) to level it up
 * @param vanillaXp      its mobs drop vanilla experience
 * @param loot           its mobs drop their loot
 * @param upgradeItem    item meant by {@code item: type} in the goals
 * @param changePrice    money to turn a spawner into this type (hours of the reference income, or fixed)
 * @param permission     needed to place or choose this type, null for everybody
 * @param fuels          fuels accepted on top of the common ones
 */
record SpawnerType(String id, EntityType entity, String name, Material icon, String profile, double rate,
                   double costMultiplier, boolean vanillaXp, boolean loot, String upgradeItem, Amount changePrice,
                   String permission, List<String> fuels) {

    /** The shown name: the configured one, or the mob's name translated by each client. */
    String displayName() {
        return name != null && !name.isBlank() ? name : "<lang:" + entity.translationKey() + ">";
    }
}

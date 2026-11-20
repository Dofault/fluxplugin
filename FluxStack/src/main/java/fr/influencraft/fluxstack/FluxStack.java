package fr.influencraft.fluxstack;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.command.Commands;

/**
 * Mob stacking: nearby identical mobs become one entity carrying a count, and a stack dies one mob at a time. Every
 * death stays a real death of an entity, so nothing that listens to kills (job payments, loot, experience, mob levels)
 * needs to know about stacks.
 */
public final class FluxStack extends FluxPlugin {

    private StackManager stacks;

    @Override
    protected void enable() {
        stacks = new StackManager(this);
        getServer().getPluginManager().registerEvents(stacks, this);
        stacks.start();
        // other plugins read and grow stacks through Flux (FluxSpawner)
        fr.influencraft.flux.api.mobs.MobStacks.provide(new fr.influencraft.flux.api.mobs.MobStacks.Provider() {
            public int count(org.bukkit.entity.Entity entity) {
                return stacks.count(entity);
            }

            public int setCount(org.bukkit.entity.LivingEntity entity, int count) {
                return stacks.setCountFromApi(entity, count);
            }

            public int maxCount() {
                return stacks.maxStack();
            }
        });
        Commands.create(this, "fluxstack").description("Mob stacking administration.").permission(fr.influencraft.flux.api.staff.Staff.ADMIN)
                .executor((sender, label, args) -> {
                    if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                        reload();
                        messages().send(sender, "reloaded");
                    } else {
                        messages().send(sender, "usage");
                    }
                    return true;
                }).register();
    }

    @Override
    public void reload() {
        super.reload();
        stacks.start();
    }

    @Override
    protected void disable() {
        fr.influencraft.flux.api.mobs.MobStacks.provide(null);
        if (stacks != null) stacks.shutdown();
    }
}

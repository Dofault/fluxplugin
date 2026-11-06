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
        Commands.create(this, "fluxstack").description("Mob stacking administration.").permission("fluxstack.admin")
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
        if (stacks != null) stacks.shutdown();
    }
}

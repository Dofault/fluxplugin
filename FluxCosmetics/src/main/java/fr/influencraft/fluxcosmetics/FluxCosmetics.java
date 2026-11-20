package fr.influencraft.fluxcosmetics;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.staff.Staff;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * FluxCosmetics: the cosmetics of the players. Outfits will be drawn over the armour a player wears (or over no
 * armour at all), for everybody's eyes; they are chosen in the wardrobe ({@link Wardrobe}), where the player sees
 * himself from outside and turns the camera around himself with his mouse.
 *
 * <pre>
 *   /tenue           open or close the wardrobe
 *   /tenue reload    (flux.admin)
 * </pre>
 */
public final class FluxCosmetics extends FluxPlugin {

    private Settings settings;
    private Wardrobe wardrobe;

    @Override
    protected void enable() {
        settings = Settings.read(getConfig());
        wardrobe = new Wardrobe(this);
        Bukkit.getPluginManager().registerEvents(wardrobe, this);
        command("tenue")
                .description("Wardrobe: see yourself from outside and choose your outfit.")
                .aliases("garderobe")
                .permission("fluxcosmetics.use")
                .executor(this::onCommand)
                .completer((sender, args) -> args.length == 1 && Staff.isAdmin(sender) ? List.of("reload") : List.of())
                .register();
    }

    @Override
    protected void disable() {
        if (wardrobe != null) wardrobe.closeAll();
    }

    @Override
    public void reload() {
        super.reload();
        settings = Settings.read(getConfig());
    }

    Settings settings() {
        return settings;
    }

    Wardrobe wardrobe() {
        return wardrobe;
    }

    private boolean onCommand(CommandSender sender, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload") && Staff.isAdmin(sender)) {
            reload();
            messages().send(sender, "reloaded");
            return true;
        }
        if (!(sender instanceof Player p) || args.length > 0) {
            messages().send(sender, "usage");
            return true;
        }
        if (wardrobe.active(p)) wardrobe.close(p, true);
        else wardrobe.open(p);
        return true;
    }
}

package fr.influencraft.fluxcosmetics;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.staff.Staff;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * FluxCosmetics: the cosmetics of the players. Outfits ({@link Outfits}) are drawn over the armour a player wears
 * (or over no armour at all), for everybody's eyes; the wardrobe ({@link Wardrobe}) shows the player himself from
 * outside, a camera turning around him.
 *
 * <pre>
 *   /tenue                    open or close the wardrobe (aliases /garderobe, /cosmetique, /cosmetiques)
 *   /tenue porter &lt;cosmetic&gt;  put a cosmetic on, or take it off (flux.admin, until the wardrobe has its menu)
 *   /tenue reload             (flux.admin)
 * </pre>
 */
public final class FluxCosmetics extends FluxPlugin {

    private Settings settings;
    private Wardrobe wardrobe;
    private Outfits outfits;

    @Override
    protected void enable() {
        settings = Settings.read(getConfig());
        wardrobe = new Wardrobe(this);
        Bukkit.getPluginManager().registerEvents(wardrobe, this);
        outfits = new Outfits(this);
        outfits.load(getConfig().getConfigurationSection("cosmetics"));
        Bukkit.getPluginManager().registerEvents(outfits, this);
        command("tenue")
                .description("Wardrobe: see yourself from outside and choose your outfit.")
                .aliases("garderobe", "cosmetique", "cosmetiques")
                .permission("fluxcosmetics.use")
                .executor(this::onCommand)
                .completer((sender, args) -> !Staff.isAdmin(sender) ? List.of()
                        : args.length == 1 ? List.of("porter", "reload")
                        : args.length == 2 && args[0].equalsIgnoreCase("porter") ? outfits.keys() : List.of())
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
        if (outfits != null) outfits.load(getConfig().getConfigurationSection("cosmetics"));
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
        if (args.length == 2 && args[0].equalsIgnoreCase("porter") && Staff.isAdmin(sender) && sender instanceof Player p) {
            if (!outfits.toggle(p, args[1])) messages().send(p, "outfits.unknown", "%name%", args[1]);
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

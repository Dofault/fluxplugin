package fr.influencraft.fluxcosmetics;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.client.RecipeBook;
import fr.influencraft.flux.api.staff.Staff;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * FluxCosmetics: the cosmetics of the players. Outfits will be drawn over the armour a player wears (or over no
 * armour at all), for everybody's eyes; they are chosen in the wardrobe ({@link Wardrobe}), where the player sees
 * himself from outside and turns the camera around himself, and in the recipe book of the inventory, which becomes the
 * cosmetics catalog ({@link Catalog}).
 *
 * <pre>
 *   /tenue           open or close the wardrobe (aliases /garderobe, /cosmetique, /cosmetiques)
 *   /tenue reload    (flux.admin)
 * </pre>
 */
public final class FluxCosmetics extends FluxPlugin {

    private Settings settings;
    private Wardrobe wardrobe;
    private Catalog catalog;

    @Override
    protected void enable() {
        settings = Settings.read(getConfig());
        wardrobe = new Wardrobe(this);
        Bukkit.getPluginManager().registerEvents(wardrobe, this);
        catalog = new Catalog(this);
        catalog.load(getConfig().getConfigurationSection("cosmetics"));
        Bukkit.getPluginManager().registerEvents(catalog, this);
        // the green book of the inventory becomes the cosmetics catalog (no crafting recipe in it any more)
        if (getConfig().getBoolean("recipe-book", true)) {
            if (RecipeBook.available()) RecipeBook.claim(this, catalog);
            else getLogger().warning("No packet adapter in Flux: the recipe book keeps its recipes");
        }
        command("tenue")
                .description("Wardrobe: see yourself from outside and choose your outfit.")
                .aliases("garderobe", "cosmetique", "cosmetiques")
                .permission("fluxcosmetics.use")
                .executor(this::onCommand)
                .completer((sender, args) -> args.length == 1 && Staff.isAdmin(sender) ? List.of("reload") : List.of())
                .register();
    }

    @Override
    protected void disable() {
        if (wardrobe != null) wardrobe.closeAll();
        RecipeBook.release(this);
    }

    @Override
    public void reload() {
        super.reload();
        settings = Settings.read(getConfig());
        if (catalog != null) {
            catalog.load(getConfig().getConfigurationSection("cosmetics"));
            for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) RecipeBook.refresh(p);
        }
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

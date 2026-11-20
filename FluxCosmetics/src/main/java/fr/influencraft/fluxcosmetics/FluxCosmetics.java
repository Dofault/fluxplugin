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
 *   /tenue                    open the wardrobe on the choice of cosmetics, or close it (aliases /garderobe,
 *                             /cosmetique, /cosmetiques)
 *   /tenue porter &lt;cosmetic&gt;  put a cosmetic on, or take it off (flux.admin)
 *   /tenue donner &lt;player&gt; &lt;cosmetic|tout&gt;     give cosmetics (flux.admin, offline players too)
 *   /tenue reprendre &lt;player&gt; &lt;cosmetic|tout&gt;  take them back (flux.admin)
 *   /tenue reload             (flux.admin)
 * </pre>
 */
public final class FluxCosmetics extends FluxPlugin {

    private Settings settings;
    private Wardrobe wardrobe;
    private Outfits outfits;
    private CosmeticsMenu menu;

    @Override
    protected void enable() {
        settings = Settings.read(getConfig());
        wardrobe = new Wardrobe(this);
        Bukkit.getPluginManager().registerEvents(wardrobe, this);
        outfits = new Outfits(this);
        outfits.createTables();
        outfits.load(getConfig());
        Bukkit.getPluginManager().registerEvents(outfits, this);
        // started while players are online (restart of the plugin): their cosmetics back on
        for (Player p : Bukkit.getOnlinePlayers()) outfits.open(p);
        menu = new CosmeticsMenu(this);
        menu.register();
        command("tenue")
                .description("Wardrobe: see yourself from outside and choose your outfit.")
                .aliases("garderobe", "cosmetique", "cosmetiques")
                .permission("fluxcosmetics.use")
                .executor(this::onCommand)
                .completer((sender, args) -> !Staff.isAdmin(sender) ? List.of()
                        : args.length == 1 ? List.of("porter", "donner", "reprendre", "reload")
                        : args.length == 2 && args[0].equalsIgnoreCase("porter") ? outfits.keys()
                        : args.length == 2 ? fr.influencraft.flux.api.util.Players.complete(args[1])
                        : args.length == 3 && !args[0].equalsIgnoreCase("porter") ? withAll(outfits.keys()) : List.of())
                .register();
    }

    @Override
    protected void disable() {
        if (wardrobe != null) wardrobe.closeAll();
        if (outfits != null) {
            outfits.closeAll();
            outfits.shutdown();
        }
    }

    @Override
    public void reload() {
        super.reload();
        settings = Settings.read(getConfig());
        if (outfits != null) outfits.load(getConfig());
    }

    private static List<String> withAll(List<String> keys) {
        List<String> out = new java.util.ArrayList<>(keys);
        out.addFirst("tout");
        return out;
    }

    /** /tenue donner|reprendre &lt;player&gt; &lt;cosmetic|tout&gt; (admin), online or offline players. */
    @SuppressWarnings("deprecation")
    private void giveOrTake(CommandSender sender, boolean give, String name, String what) {
        org.bukkit.OfflinePlayer target = Bukkit.getPlayerExact(name);
        if (target == null) target = Bukkit.getOfflinePlayerIfCached(name);
        if (target == null || target.getName() == null) {
            messages().send(sender, "admin.unknown-player", "%player%", name);
            return;
        }
        java.util.Collection<String> keys;
        if (what.equalsIgnoreCase("tout")) keys = outfits.everything();
        else if (outfits.get(what) != null) keys = List.of(what);
        else {
            messages().send(sender, "outfits.unknown", "%name%", what);
            return;
        }
        if (give) outfits.give(target.getUniqueId(), keys);
        else outfits.take(target.getUniqueId(), keys);
        String shown = keys.size() == 1 ? outfits.get(keys.iterator().next()).name() : messages().raw("admin.all");
        messages().send(sender, give ? "admin.given" : "admin.taken", "%player%", target.getName(), "%name%", shown);
    }

    Settings settings() {
        return settings;
    }

    Wardrobe wardrobe() {
        return wardrobe;
    }

    Outfits outfits() {
        return outfits;
    }

    CosmeticsMenu menu() {
        return menu;
    }

    private boolean onCommand(CommandSender sender, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload") && Staff.isAdmin(sender)) {
            reload();
            messages().send(sender, "reloaded");
            return true;
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("donner") || args[0].equalsIgnoreCase("reprendre")) && Staff.isAdmin(sender)) {
            giveOrTake(sender, args[0].equalsIgnoreCase("donner"), args[1], args[2]);
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("porter") && Staff.isAdmin(sender) && sender instanceof Player p) {
            if (!outfits.toggle(p, args[1], false)) messages().send(p, "outfits.unknown", "%name%", args[1]);
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

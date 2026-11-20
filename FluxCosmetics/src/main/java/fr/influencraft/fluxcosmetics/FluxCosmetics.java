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
 *   /cosmetique               open the wardrobe on the category last looked at, or close it (aliases
 *                             /cosmetiques, /garderobe, /garde-robe)
 *   /tenue                    open the wardrobe on the saved outfits (alias /tenues)
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
        Bukkit.getPluginManager().registerEvents(outfits.rpg(), this);
        outfits.rpg().start();
        // started while players are online (restart of the plugin): their cosmetics back on
        for (Player p : Bukkit.getOnlinePlayers()) outfits.open(p);
        menu = new CosmeticsMenu(this);
        menu.register();
        fr.influencraft.flux.api.command.Commands.Completer admin = (sender, args) -> !Staff.isAdmin(sender) ? List.of()
                : args.length == 1 ? List.of("porter", "donner", "reprendre", "reload")
                : args.length == 2 && args[0].equalsIgnoreCase("porter") ? outfits.keys()
                : args.length == 2 ? fr.influencraft.flux.api.util.Players.complete(args[1])
                : args.length == 3 && !args[0].equalsIgnoreCase("porter") ? withAll(both()) : List.of();
        // the wardrobe on the category last looked at
        command("cosmetique")
                .description("Wardrobe: see yourself from outside and choose your cosmetics.")
                .aliases("cosmetiques", "garderobe", "garde-robe")
                .permission("fluxcosmetics.use")
                .executor((sender, label, args) -> onCommand(sender, args, false))
                .completer(admin)
                .register();
        // the wardrobe on the saved outfits
        command("tenue")
                .description("Wardrobe on your saved outfits.")
                .aliases("tenues")
                .permission("fluxcosmetics.use")
                .executor((sender, label, args) -> onCommand(sender, args, true))
                .completer(admin)
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

    private List<String> both() {
        List<String> out = new java.util.ArrayList<>(outfits.keys());
        out.addAll(outfits.rpg().keys());
        return out;
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
        else if (outfits.get(what) != null || outfits.rpg().get(what) != null) keys = List.of(what);
        else {
            messages().send(sender, "outfits.unknown", "%name%", what);
            return;
        }
        if (give) outfits.give(target.getUniqueId(), keys);
        else outfits.take(target.getUniqueId(), keys);
        String one = keys.size() == 1 ? keys.iterator().next() : null;
        String shown = one == null ? messages().raw("admin.all")
                : outfits.get(one) != null ? outfits.get(one).name() : outfits.rpg().get(one).name();
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

    /** {@code saved}: /tenue, the wardrobe opens on the saved outfits. */
    private boolean onCommand(CommandSender sender, String[] args, boolean saved) {
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
        if (saved) menu.showOutfits(p);
        // already in it: the menu again on that category; else the wardrobe opens on it
        if (wardrobe.active(p)) {
            if (saved) menu.open(p);
            else wardrobe.close(p, true);
        } else wardrobe.open(p);
        return true;
    }
}

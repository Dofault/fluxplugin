package fr.influencraft.fluxportal;

import fr.influencraft.flux.api.text.ChatHelp;
import fr.influencraft.flux.api.text.Messages;
import fr.influencraft.fluxportal.api.Portal;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /portail}: a portal is made by aiming at the floor of its opening, on one side then the other.
 * <pre>
 *   create &lt;name&gt;          first side, where the player aims
 *   link &lt;name&gt;            second side: the portal opens
 *   move &lt;name&gt; [1|2]      a side placed again where the player aims (the nearest one by default)
 *   open|close &lt;name&gt;      the window and the way through, on or off
 *   remove &lt;name&gt;
 *   list                   every portal, its sides clickable to go there
 *   goto &lt;name&gt; [1|2]      in front of a side
 *   help [page]
 * </pre>
 * Words typed come from the lang file ({@code subcommands.<id>}); the English ids work too.
 */
final class PortalCommand {

    private static final List<String> SUBS = List.of("create", "link", "move", "open", "close", "remove", "list", "goto", "help");
    /** How far the floor of the opening may be aimed at. */
    private static final int REACH = 12;

    private final FluxPortal plugin;

    PortalCommand(FluxPortal plugin) {
        this.plugin = plugin;
    }

    private Messages messages() {
        return plugin.messages();
    }

    private String label(String sub) {
        String l = messages().raw("subcommands." + sub);
        return l.isEmpty() ? sub : l;
    }

    private String sub(String typed) {
        String t = typed.toLowerCase(Locale.ROOT);
        for (String s : SUBS) if (s.equals(t) || label(s).equalsIgnoreCase(t)) return s;
        return null;
    }

    boolean run(CommandSender sender, String cmd, String[] args) {
        String sub = args.length == 0 ? "help" : sub(args[0]);
        if (sub == null) {
            messages().send(sender, "unknown-command", "%cmd%", cmd, "%help%", label("help"));
            return true;
        }
        if (!sub.equals("list") && !sub.equals("help") && args.length < 2) {
            messages().send(sender, "usage", "%usage%", "/" + cmd + " " + label(sub) + " " + messages().raw("help.args." + sub));
            return true;
        }
        String name = args.length > 1 ? args[1] : "";
        switch (sub) {
            case "create", "link" -> place(sender, cmd, sub, name, sub.equals("create") ? 1 : 2);
            case "move" -> move(sender, cmd, name, args.length > 2 ? args[2] : null);
            case "open", "close" -> {
                Portal p = plugin.portal(name);
                if (p == null) unknown(sender, cmd, name);
                else {
                    plugin.setOpen(p.name(), sub.equals("open"));
                    messages().send(sender, sub.equals("open") ? "opened" : "closed", "%name%", p.name());
                }
            }
            case "remove" -> {
                if (plugin.remove(name)) messages().send(sender, "removed", "%name%", name.toLowerCase(Locale.ROOT));
                else unknown(sender, cmd, name);
            }
            case "list" -> list(sender, cmd);
            case "goto" -> go(sender, cmd, name, args.length > 2 ? args[2] : "1");
            default -> help(sender, cmd, args.length > 1 ? page(args[1]) : 1);
        }
        return true;
    }

    // ------------------------------------------------------------------ placing

    private void place(CommandSender sender, String cmd, String sub, String name, int side) {
        if (!(sender instanceof Player p)) {
            messages().send(sender, "players-only");
            return;
        }
        Portal existing = plugin.portal(name);
        if (side == 1 && existing != null) {
            messages().send(p, "already-exists", "%name%", existing.name(), "%cmd%", cmd, "%move%", label("move"));
            return;
        }
        if (side == 2 && existing == null) {
            messages().send(p, "create-first", "%name%", name, "%cmd%", cmd, "%create%", label("create"));
            return;
        }
        if (side == 2 && existing.side2() != null) {
            messages().send(p, "already-linked", "%name%", existing.name(), "%cmd%", cmd, "%move%", label("move"));
            return;
        }
        if (!FluxPortal.validName(name)) {
            messages().send(p, "bad-name");
            return;
        }
        put(p, cmd, name, side);
    }

    private void move(CommandSender sender, String cmd, String name, String which) {
        if (!(sender instanceof Player p)) {
            messages().send(sender, "players-only");
            return;
        }
        Portal portal = plugin.portal(name);
        if (portal == null) {
            unknown(sender, cmd, name);
            return;
        }
        int side;
        if (which != null) {
            side = which.equals("1") ? 1 : which.equals("2") ? 2 : 0;
            if (side == 0) {
                messages().send(p, "bad-side");
                return;
            }
        } else {
            side = nearestSide(portal, p.getLocation());
            if (side == 0) {
                messages().send(p, "which-side", "%name%", portal.name(), "%cmd%", cmd, "%move%", label("move"));
                return;
            }
        }
        put(p, cmd, portal.name(), side);
    }

    /** The side the player aims at the floor of, saved; tells what is left to do. */
    private void put(Player p, String cmd, String name, int side) {
        Block floor = p.getTargetBlockExact(REACH, FluidCollisionMode.NEVER);
        BlockFace face = p.getTargetBlockFace(REACH, FluidCollisionMode.NEVER);
        if (floor == null || face != BlockFace.UP) {
            messages().send(p, "aim-floor");
            return;
        }
        int facing = Math.floorMod(Math.round((p.getLocation().getYaw() + 180) / 90f), 4);
        Portal portal = plugin.place(name, side, p.getWorld().getName(), floor.getX(), floor.getY(), floor.getZ(), facing);
        if (portal == null) {
            messages().send(p, "error");
            return;
        }
        messages().send(p, side == 1 ? "side1-placed" : "side2-placed", "%name%", portal.name(),
                "%width%", String.valueOf(portal.width()), "%height%", String.valueOf(portal.height()));
        int blocked = plugin.blockedCells(portal, side);
        if (blocked > 0) messages().send(p, "blocked", "%count%", String.valueOf(blocked));
        if (!portal.complete()) messages().send(p, "now-link", "%name%", portal.name(), "%cmd%", cmd, "%link%", label("link"));
        else messages().send(p, "ready", "%name%", portal.name());
    }

    private static int nearestSide(Portal portal, Location at) {
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int side = 1; side <= 2; side++) {
            var end = portal.side(side);
            if (end == null || !end.world().equals(at.getWorld().getName())) continue;
            double d = end.middle().distanceSquared(at.toVector());
            if (d < bestD) {
                bestD = d;
                best = side;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ looking

    private void list(CommandSender sender, String cmd) {
        var all = plugin.portals();
        if (all.isEmpty()) {
            messages().send(sender, "list-empty", "%cmd%", cmd, "%create%", label("create"));
            return;
        }
        messages().send(sender, "list-header", "%count%", String.valueOf(all.size()));
        for (Portal p : all) {
            String state = messages().raw(!p.complete() ? "state-unlinked" : p.open() ? "state-open" : "state-closed");
            messages().send(sender, "list-line", "%name%", p.name(), "%state%", state,
                    "%side1%", where(cmd, p, 1), "%side2%", where(cmd, p, 2));
        }
    }

    private String where(String cmd, Portal p, int side) {
        var e = p.side(side);
        if (e == null) return messages().raw("list-missing");
        return messages().raw("list-side", "%world%", e.world(), "%x%", String.valueOf((long) Math.floor(e.x())),
                "%y%", String.valueOf(new Frame(e, p.width(), p.height()).bottomY()), "%z%", String.valueOf((long) Math.floor(e.z())),
                "%go%", "/" + cmd + " " + label("goto") + " " + p.name() + " " + side);
    }

    private void go(CommandSender sender, String cmd, String name, String which) {
        if (!(sender instanceof Player p)) {
            messages().send(sender, "players-only");
            return;
        }
        Portal portal = plugin.portal(name);
        if (portal == null) {
            unknown(sender, cmd, name);
            return;
        }
        int side = which.equals("2") ? 2 : 1;
        var end = portal.side(side);
        var world = end == null ? null : org.bukkit.Bukkit.getWorld(end.world());
        if (world == null) {
            messages().send(p, "side-missing", "%name%", portal.name(), "%side%", String.valueOf(side));
            return;
        }
        Frame f = new Frame(end, portal.width(), portal.height());
        Vector n = end.normal();
        // three blocks in front, on the floor of the hole, facing it
        Location to = new Location(world, f.worldX(0, 3), f.bottomY(), f.worldZ(0, 3));
        to.setDirection(n.multiply(-1));
        p.teleport(Crossings.standable(to), PlayerTeleportEvent.TeleportCause.COMMAND);
    }

    private void unknown(CommandSender sender, String cmd, String name) {
        messages().send(sender, "unknown", "%name%", name, "%cmd%", cmd, "%list%", label("list"));
    }

    // ------------------------------------------------------------------ help and completion

    private void help(CommandSender sender, String cmd, int page) {
        List<ChatHelp.Entry> entries = new ArrayList<>();
        for (String s : SUBS) entries.add(new ChatHelp.Entry((label(s) + " " + messages().raw("help.args." + s)).trim(), s, null));
        ChatHelp.show(sender, messages(), cmd, entries, label("help"), false, page, perm -> true);
    }

    private static int page(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    List<String> complete(String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : SUBS) out.add(label(s));
        } else if (args.length == 2) {
            String sub = sub(args[0]);
            if (sub != null && !sub.equals("create") && !sub.equals("list") && !sub.equals("help")) {
                for (Portal p : plugin.portals()) if (!sub.equals("link") || p.side2() == null) out.add(p.name());
            }
        } else if (args.length == 3) {
            String sub = sub(args[0]);
            if ("move".equals(sub) || "goto".equals(sub)) out.addAll(List.of("1", "2"));
        }
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(typed));
        return out;
    }
}

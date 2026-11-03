package fr.influencraft.fluxcrate;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.items.CustomItems;
import fr.influencraft.flux.api.reward.Reward;
import fr.influencraft.flux.api.reward.Rewards;
import fr.influencraft.flux.api.util.Players;
import fr.influencraft.flux.api.util.Sounds;
import fr.influencraft.fluxcrate.api.Crates;
import fr.influencraft.fluxcrate.compat.EcoCratesCommand;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * FluxCrate: the crates of the server and their keys (replaces EcoCrates, 2026-10-29). One file per crate in
 * {@code crates/}; crates placed in the worlds open with a key (right click), show their rewards (left click), open
 * every key at once (sneak + right click). Opening and preview are Flux menus ({@link CrateMenus}).
 *
 * <pre>
 *   /fluxcrate give &lt;player|*&gt; &lt;crate&gt; [amount] | place &lt;crate&gt; | remove | preview &lt;crate&gt; | list | reload
 * </pre>
 * Keys of EcoCrates still open their crate, and {@code ecocrates give ...} still works once EcoCrates is removed
 * ({@code compat}).
 */
public final class FluxCrate extends FluxPlugin implements Listener {

    private final Map<String, Crate> crates = new LinkedHashMap<>();
    private PlacedCrates placed;
    private CrateMenus menus;

    @Override
    protected void enable() {
        placed = new PlacedCrates(this);
        menus = new CrateMenus(this);
        loadCrates();
        placed.load();
        menus.enable();
        Crates.bind(new Crates.Service() {
            public boolean hasCrates() {
                return !crates.isEmpty();
            }

            public void save(String id, YamlConfiguration config) throws IOException {
                File dir = new File(getDataFolder(), "crates");
                dir.mkdirs();
                config.save(new File(dir, id + ".yml"));
            }

            public void place(Location location, String crate) {
                placed.place(location, crate);
            }

            public void reload() {
                FluxCrate.this.reload();
            }
        });
        command("fluxcrate")
                .description("Crates and keys administration.")
                .permission("fluxcrate.admin")
                .usage(messages().raw("admin-usage"))
                .executor(this::onAdmin)
                .completer(this::complete)
                .register();
        if (EcoCratesCommand.available()) {
            command("ecocrates")
                    .description("Former EcoCrates give command, answered by FluxCrate.")
                    .permission("fluxcrate.admin")
                    .suggest(false)
                    .executor((sender, label, args) -> {
                        if (!EcoCratesCommand.run(args, (who, crate, n) -> give(sender, who, crate, n))) {
                            messages().send(sender, "admin-usage");
                        }
                        return true;
                    })
                    .register();
        }
        // keys known to the suite as fluxcrate:key_<crate>
        CustomItems.register(this, "fluxcrate", new CustomItems.Source() {
            @Override
            public String id(ItemStack item) {
                String c = Keys.crateOf(item);
                return c == null ? null : "key_" + c;
            }

            @Override
            public ItemStack item(String id) {
                Crate c = id.startsWith("key_") ? crate(id.substring(4)) : null;
                return c == null ? null : Keys.create(c, 1);
            }

            @Override
            public java.util.Collection<String> ids() {
                return crates.keySet().stream().map(c -> "key_" + c).toList();
            }
        });
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, placed::tick, 40L, 20L);
    }

    @Override
    protected void disable() {
        if (placed != null) placed.removeHolograms();
        Crates.bind(null);
    }

    @Override
    public void reload() {
        super.reload();
        loadCrates();
        placed.load();
    }

    private void loadCrates() {
        crates.clear();
        File dir = new File(getDataFolder(), "crates");
        if (!new File(dir, "_example.yml").exists()) saveResource("crates/_example.yml", false);
        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml") && !n.startsWith("_"));
        if (files != null) {
            java.util.Arrays.sort(files);
            for (File f : files) {
                String id = f.getName().substring(0, f.getName().length() - 4).toLowerCase(Locale.ROOT);
                crates.put(id, Crate.read(id, YamlConfiguration.loadConfiguration(f)));
            }
        }
        getLogger().info(crates.size() + " crate(s): " + String.join(", ", crates.keySet()));
    }

    /** Opens crates without a key (staff tests): permission {@code fluxcrate.free}, operators by default. */
    static boolean free(Player p) {
        return p.hasPermission("fluxcrate.free");
    }

    Crate crate(String id) {
        return id == null ? null : crates.get(id.toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------ rewards

    /** Gives a reward won: its items (what does not fit falls at the player's feet) and its commands. */
    void give(Player player, Crate crate, Crate.Reward reward) {
        if (reward.item() != null && !CustomItems.give(player, reward.item(), reward.amount())) {
            getLogger().warning("Crate " + crate.id() + ", reward " + reward.id() + ": unknown item " + reward.item());
        }
        for (String spec : reward.items()) {
            String[] parts = spec.trim().split(" ");
            int amount = 1;
            String material = spec.trim();
            if (parts.length > 1) {
                try {
                    amount = Integer.parseInt(parts[parts.length - 1]);
                    material = spec.trim().substring(0, spec.trim().lastIndexOf(' '));
                } catch (NumberFormatException ignored) {
                    // the last word is an argument of the item, not an amount
                }
            }
            ItemStack item = Items.of(material, player);
            if (item == null) continue;
            item.setAmount(Math.max(1, amount));
            for (ItemStack rest : player.getInventory().addItem(item).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
        }
        for (String cmd : reward.commands()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", player.getName()));
        }
        messages().send(player, "won", "%reward%", reward.shownName(), "%crate%", crate.name());
    }

    // ------------------------------------------------------------------ placed crates

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null || e.getHand() != EquipmentSlot.HAND) return;
        Crate crate = crate(placed.at(b));
        if (crate == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (e.getAction() == Action.LEFT_CLICK_BLOCK) {
            menus.preview(p, crate);
            return;
        }
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        int keys = Keys.count(p, crate.id());
        if (keys == 0 && !free(p)) {
            messages().send(p, "no-key", "%key%", crate.keyName());
            Sounds.cue(p, "error");
            Vector away = p.getLocation().toVector().subtract(b.getLocation().add(0.5, 0, 0.5).toVector());
            if (away.lengthSquared() > 0) p.setVelocity(away.normalize().multiply(0.8).setY(0.3));
            return;
        }
        if (p.isSneaking() && keys > 1) openAll(p, crate, keys);
        else menus.open(p, crate);
    }

    /** Sneak + right click: every key opened at once, no roll. */
    private void openAll(Player p, Crate crate, int keys) {
        if (!Keys.take(p, crate.id(), keys)) return;
        for (int i = 0; i < keys; i++) {
            Crate.Reward r = crate.draw();
            if (r != null) give(p, crate, r);
        }
        Sounds.cue(p, "complete");
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (placed.at(e.getBlock()) == null) return;
        e.setCancelled(true);
        messages().send(e.getPlayer(), "protected");
    }

    // ------------------------------------------------------------------ admin command

    private boolean onAdmin(CommandSender sender, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        Player p = Players.of(sender);
        switch (sub) {
            case "reload" -> {
                reload();
                messages().send(sender, "reloaded", "%count%", String.valueOf(crates.size()));
            }
            case "list" -> messages().send(sender, "list", "%crates%", String.join(", ", crates.keySet()),
                    "%placed%", String.valueOf(placed.size()));
            case "give" -> {
                if (args.length < 3) messages().send(sender, "admin-usage");
                else give(sender, args[1], args[2], args.length > 3 ? parse(args[3]) : 1);
            }
            case "place" -> {
                Block target = p == null ? null : p.getTargetBlockExact(6);
                Crate crate = args.length > 1 ? crate(args[1]) : null;
                if (target == null || crate == null) {
                    messages().send(sender, "admin-usage");
                    return true;
                }
                placed.place(target.getLocation(), crate.id());
                messages().send(sender, "placed", "%crate%", crate.id());
            }
            case "remove" -> {
                Block target = p == null ? null : p.getTargetBlockExact(6);
                messages().send(sender, target != null && placed.remove(target.getLocation()) ? "removed" : "not-a-crate");
            }
            case "preview" -> {
                Crate crate = args.length > 1 ? crate(args[1]) : null;
                if (p != null && crate != null) menus.preview(p, crate);
                else messages().send(sender, "admin-usage");
            }
            default -> messages().send(sender, "admin-usage");
        }
        return true;
    }

    /** Keys to an online player (or every online player with *); to an offline one, through the rewards of Flux. */
    private void give(CommandSender sender, String who, String crateId, int amount) {
        Crate crate = crate(crateId);
        if (crate == null || amount < 1) {
            messages().send(sender, "unknown-crate", "%crate%", crateId);
            return;
        }
        List<Player> targets = new ArrayList<>();
        if (who.equals("*")) targets.addAll(Bukkit.getOnlinePlayers());
        else if (Bukkit.getPlayerExact(who) != null) targets.add(Bukkit.getPlayerExact(who));
        if (targets.isEmpty()) {
            OfflinePlayer off = Players.find(who);
            if (off == null) {
                messages().send(sender, "unknown-player");
                return;
            }
            Rewards.give(off, Reward.of("crate", amount + " " + crate.keyName()).icon(crate.keyItem()).slots(1)
                    .commands(List.of("fluxcrate give %player% " + crate.id() + " " + amount)));
            messages().send(sender, "given-later", "%player%", Players.name(off, who));
            return;
        }
        for (Player t : targets) {
            Keys.give(t, crate, amount);
            messages().send(t, "received", "%amount%", String.valueOf(amount), "%key%", crate.keyName());
        }
    }

    private List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) return List.of("give", "place", "remove", "preview", "list", "reload");
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && sub.equals("give")) return Players.complete(args[1]);
        if ((args.length == 2 && (sub.equals("place") || sub.equals("preview"))) || (args.length == 3 && sub.equals("give"))) {
            return List.copyOf(crates.keySet());
        }
        return List.of();
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}

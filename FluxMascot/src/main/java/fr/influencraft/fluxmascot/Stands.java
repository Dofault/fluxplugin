package fr.influencraft.fluxmascot;

import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.spawn.Spawns;
import fr.influencraft.flux.api.text.Texts;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Stands (2026-11-19): a model standing on a base, its name above it, and a right click that runs a command for the
 * player (the job familiars of the tutorial spawn: a click opens the job). Shown only to the players of a spawn group
 * of Flux ({@code group}, {@link Spawns}), so they vanish for a player the moment he leaves that group. Everything is
 * client side for the others: the model is a BetterModel model shown player by player, the base, the name and the click
 * box are entities nobody sees unless shown to him. Nothing is saved in the world.
 *
 * <p>{@code plugins/FluxMascot/stands.yml}, read again by {@code /fluxmascot reload}.</p>
 */
final class Stands implements Listener {

    private static final double VIEW_DISTANCE = 48;

    private record Spec(String id, Location at, String model, float scale, String idle, String click, String base,
                        double baseHeight, float baseScale, String label, double labelHeight, String command,
                        String group, double clickWidth, double clickHeight) {
    }

    /** One stand in the world: its shared entities, and the model of each player who sees it. */
    private final class Stand {
        final Spec spec;
        ItemDisplay base;
        TextDisplay label;
        Interaction box;
        final Map<UUID, ModelBridge.Handle> models = new HashMap<>();

        Stand(Spec spec) {
            this.spec = spec;
        }

        Location modelAt() {
            return spec.at.clone().add(0, spec.baseHeight, 0);
        }

        void spawn() {
            World w = spec.at.getWorld();
            ItemStack item = spec.base.isEmpty() ? null : Items.of(spec.base, (Player) null);
            if (item != null) {
                // an item display draws its model centred on itself: lifted by half its height, it stands on the floor
                Location at = spec.at.clone();
                at.setYaw(spec.at.getYaw());
                at.setPitch(0);
                base = w.spawn(at, ItemDisplay.class, d -> {
                    hidden(d);
                    d.setItemStack(item);
                    d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                    d.setTransformation(new Transformation(new Vector3f(0, spec.baseScale / 2f, 0), new AxisAngle4f(),
                            new Vector3f(spec.baseScale), new AxisAngle4f()));
                });
            }
            if (!spec.label.isEmpty()) {
                label = w.spawn(spec.at.clone().add(0, spec.baseHeight + spec.labelHeight, 0), TextDisplay.class, d -> {
                    hidden(d);
                    d.text(Texts.parse(spec.label));
                    d.setBillboard(Display.Billboard.CENTER);
                    d.setShadowed(true);
                    d.setDefaultBackground(false);
                    d.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
                });
            }
            if (!spec.command.isEmpty()) {
                box = w.spawn(spec.at.clone(), Interaction.class, e -> {
                    hidden(e);
                    e.setInteractionWidth((float) spec.clickWidth);
                    e.setInteractionHeight((float) (spec.baseHeight + spec.clickHeight));
                    e.setResponsive(true);
                });
            }
        }

        private void hidden(Entity e) {
            e.setPersistent(false);
            e.setVisibleByDefault(false);
        }

        boolean sees(Player p) {
            return models.containsKey(p.getUniqueId());
        }

        void show(Player p) {
            ModelBridge.Handle h = ModelBridge.show(p, spec.model, spec.scale, yawOffset, yawMirror, modelAt(), spec.at.getYaw());
            if (h == null) {
                warnOnce(spec.model);
                return;
            }
            h.loop(spec.idle);
            models.put(p.getUniqueId(), h);
            for (Entity e : entities()) p.showEntity(plugin, e);
        }

        void hide(Player p) {
            ModelBridge.Handle h = models.remove(p.getUniqueId());
            if (h != null) h.close();
            if (p.isOnline()) for (Entity e : entities()) p.hideEntity(plugin, e);
        }

        void forget(UUID id) {
            ModelBridge.Handle h = models.remove(id);
            if (h != null) h.close();
        }

        List<Entity> entities() {
            List<Entity> out = new ArrayList<>(3);
            if (base != null && base.isValid()) out.add(base);
            if (label != null && label.isValid()) out.add(label);
            if (box != null && box.isValid()) out.add(box);
            return out;
        }

        void remove() {
            models.values().forEach(ModelBridge.Handle::close);
            models.clear();
            for (Entity e : entities()) e.remove();
        }
    }

    private final FluxMascot plugin;
    private final List<Stand> stands = new ArrayList<>();
    private final java.util.Set<String> warned = new java.util.HashSet<>();
    private List<Spec> specs = List.of();
    /** How the models are turned (stands.yml): the robot needs its yaw mirrored, the musicians of FluxMusic did not. */
    private boolean yawMirror;
    private float yawOffset;

    Stands(FluxMascot plugin) {
        this.plugin = plugin;
    }

    private void warnOnce(String model) {
        if (warned.add(model)) plugin.getLogger().warning("Stand: no BetterModel model " + model);
    }

    /** Reads stands.yml (shipped when missing); the stands are made again on the next tick. */
    void load() {
        clear();
        File file = new File(plugin.getDataFolder(), "stands.yml");
        if (!file.exists()) plugin.saveResource("stands.yml", false);
        YamlConfiguration c = YamlConfiguration.loadConfiguration(file);
        yawMirror = c.getBoolean("yaw-mirror", false);
        yawOffset = (float) c.getDouble("yaw-offset", 0);
        ConfigurationSection all = c.getConfigurationSection("stands");
        List<Spec> out = new ArrayList<>();
        if (all != null) for (String id : all.getKeys(false)) {
            ConfigurationSection s = all.getConfigurationSection(id);
            if (s == null) continue;
            out.add(new Spec(id, null, s.getString("model", ""), (float) s.getDouble("scale", 1),
                    s.getString("animation", "idle"), s.getString("click-animation", ""), s.getString("base", ""),
                    s.getDouble("base-height", 0), (float) s.getDouble("base-scale", 1), s.getString("label", ""),
                    s.getDouble("label-height", 1.2), s.getString("command", ""), s.getString("group", ""),
                    s.getDouble("click.width", 1), s.getDouble("click.height", 1.2)));
            // the place is read when the world is there (see tick)
            places.put(id, s.getString("world", "") + ";" + s.getDouble("x") + ";" + s.getDouble("y") + ";"
                    + s.getDouble("z") + ";" + s.getDouble("yaw"));
        }
        specs = out;
    }

    /** Place of each stand as written in stands.yml (world;x;y;z;yaw), turned into a location once the world exists. */
    private final Map<String, String> places = new HashMap<>();

    private Location place(String id) {
        String[] p = places.getOrDefault(id, "").split(";");
        if (p.length < 5) return null;
        World w = Bukkit.getWorld(p[0]);
        if (w == null) return null;
        return new Location(w, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                (float) Double.parseDouble(p[4]), 0);
    }

    /** Every half second: stands made once their world is loaded, then shown to / hidden from each player. */
    void tick() {
        if (stands.size() < specs.size()) {
            for (Spec spec : specs) {
                if (stands.stream().anyMatch(s -> s.spec.id.equals(spec.id))) continue;
                Location at = place(spec.id);
                if (at == null) continue;
                Spec placed = new Spec(spec.id, at, spec.model, spec.scale, spec.idle, spec.click, spec.base,
                        spec.baseHeight, spec.baseScale, spec.label, spec.labelHeight, spec.command, spec.group,
                        spec.clickWidth, spec.clickHeight);
                Stand s = new Stand(placed);
                s.spawn();
                stands.add(s);
            }
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("BetterModel")) return;
        for (Stand s : stands) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                boolean wants = p.getWorld() == s.spec.at.getWorld()
                        && p.getLocation().distanceSquared(s.spec.at) < VIEW_DISTANCE * VIEW_DISTANCE
                        && Spawns.in(p, s.spec.group) && !fr.influencraft.flux.api.client.Clients.bedrock(p);
                if (wants && !s.sees(p)) s.show(p);
                else if (!wants && s.sees(p)) s.hide(p);
            }
        }
    }

    void clear() {
        stands.forEach(Stand::remove);
        stands.clear();
        places.clear();
    }

    @EventHandler
    public void onClick(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        for (Stand s : stands) {
            if (s.box == null || !s.box.equals(e.getRightClicked())) continue;
            e.setCancelled(true);
            Player p = e.getPlayer();
            if (!s.sees(p)) return;
            ModelBridge.Handle h = s.models.get(p.getUniqueId());
            if (h != null && !s.spec.click.isEmpty()) h.play(s.spec.click);
            p.performCommand(s.spec.command.replace("%player%", p.getName()));
            return;
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        for (Stand s : stands) s.forget(e.getPlayer().getUniqueId());
    }
}

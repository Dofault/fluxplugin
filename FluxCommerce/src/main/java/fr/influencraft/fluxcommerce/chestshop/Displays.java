package fr.influencraft.fluxcommerce.chestshop;

import fr.influencraft.flux.api.shops.ChestShops;
import fr.influencraft.flux.api.text.Texts;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What players see of a shop: the item floating above the container, turning and bobbing slowly, always; then the
 * text either on its sign ({@link Signs}, default look) or as a hologram above the item (selling or buying, price of
 * one item, seller). Display entities that are never saved: spawned when the chunk loads, removed when it unloads,
 * when the shop goes and when the plugin stops. The hologram is the same for every player, hence in the server's
 * language (no item name in it: the item itself is shown). The animation is drawn by the clients: every
 * {@code display.spin-ticks} the item gets its next quarter turn with that much interpolation time.
 */
final class Displays {

    private final ChestShopModule plugin;
    private final NamespacedKey mark;
    private final Map<Long, List<UUID>> shown = new HashMap<>();
    /** The item display of each shown shop, animated by {@link #spin}. */
    private final Map<Long, UUID> items = new HashMap<>();
    private int step;
    private org.bukkit.scheduler.BukkitTask spinTask;

    Displays(ChestShopModule plugin) {
        this.plugin = plugin;
        // namespace of the former plugin: displays left by a crash carry it
        this.mark = new NamespacedKey("fluxchestshop", "display");
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("display.enabled", true);
    }

    /** Starts (again) the animation of the items, unless {@code display.spin} is false. */
    void start() {
        if (spinTask != null) spinTask.cancel();
        spinTask = null;
        if (!plugin.getConfig().getBoolean("display.spin", true)) return;
        long period = Math.max(5, plugin.getConfig().getLong("display.spin-ticks", 40));
        spinTask = Bukkit.getScheduler().runTaskTimer(plugin.host(), () -> spin((int) period), period, period);
    }

    /** Next quarter turn of every item, half of them up, half down (a slow bob), interpolated by the clients. */
    private void spin(int ticks) {
        step++;
        float bob = (float) plugin.getConfig().getDouble("display.bob-height", 0.06);
        for (UUID id : items.values()) {
            if (!(Bukkit.getEntity(id) instanceof ItemDisplay d)) continue;
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(ticks);
            d.setTransformation(transformation(step, bob));
        }
    }

    private Transformation transformation(int at, float bob) {
        float scale = (float) plugin.getConfig().getDouble("display.item-scale", 0.45);
        return new Transformation(new Vector3f(0, at % 2 == 0 ? 0 : bob, 0),
                new AxisAngle4f((float) (Math.PI / 2 * (at % 4)), 0, 1, 0), new Vector3f(scale, scale, scale), new AxisAngle4f());
    }

    /** Draws the shop again (settings, stock or seller name changed): its item, then its sign or its hologram. */
    void update(Shop s) {
        update(s, null);
    }

    /**
     * @param front side for a new sign (towards the player who made the shop), null for the container's front
     * @return false when the sign had no room (the shop is then shown floating)
     */
    boolean update(Shop s, org.bukkit.block.BlockFace front) {
        remove(s);
        if (!s.loaded()) return true;
        boolean signed = true;
        if (s.hasSign()) {
            signed = plugin.signs().draw(s, front);
        } else {
            plugin.signs().remove(s);
        }
        // the hologram text replaces the sign (chosen, or no room for one)
        if (enabled()) spawn(s, !s.hasSign() || !signed);
        return signed;
    }

    void remove(Shop s) {
        items.remove(s.id);
        List<UUID> ids = shown.remove(s.id);
        if (ids == null) return;
        for (UUID id : ids) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
    }

    /** A chunk was loaded: its shops are drawn (and their stock counted) on the next tick, once it is ready. */
    void chunkLoaded(Chunk chunk) {
        if (plugin.registry().inChunk(chunk.getWorld().getName(), chunk.getX(), chunk.getZ()).isEmpty()) return;
        Bukkit.getScheduler().runTask(plugin.host(), () -> {
            if (chunk.isLoaded()) drawChunk(chunk);
        });
    }

    private void drawChunk(Chunk chunk) {
        clean(chunk);
        for (Shop s : plugin.registry().inChunk(chunk.getWorld().getName(), chunk.getX(), chunk.getZ())) {
            plugin.registry().count(s);
            update(s);
        }
    }

    void chunkUnloaded(Chunk chunk) {
        for (Shop s : plugin.registry().inChunk(chunk.getWorld().getName(), chunk.getX(), chunk.getZ())) remove(s);
    }

    /** Every loaded shop (start, reload). */
    void showAll() {
        for (Shop s : new ArrayList<>(plugin.registry().shops())) {
            if (s.loaded()) update(s);
        }
    }

    void hideAll() {
        for (List<UUID> ids : shown.values()) {
            for (UUID id : ids) {
                Entity e = Bukkit.getEntity(id);
                if (e != null) e.remove();
            }
        }
        shown.clear();
        items.clear();
    }

    /** Displays of this plugin left over in a chunk (they are never saved, this is only a safety net). */
    private void clean(Chunk chunk) {
        for (Entity e : chunk.getEntities()) {
            if (e instanceof Display && e.getPersistentDataContainer().has(mark)) e.remove();
        }
    }

    /** @param withText the hologram above the item (no sign) */
    private void spawn(Shop s, boolean withText) {
        World w = s.bukkitWorld();
        if (w == null) return;
        double height = plugin.getConfig().getDouble("display.height", 1.15);
        Location base = new Location(w, s.x + 0.5, s.y + height, s.z + 0.5);
        List<UUID> ids = new ArrayList<>(2);
        if (plugin.getConfig().getBoolean("display.item", true)) {
            ItemStack item = s.item.clone();
            item.setAmount(1);
            float bob = (float) plugin.getConfig().getDouble("display.bob-height", 0.06);
            UUID id = w.spawn(base, ItemDisplay.class, d -> {
                prepare(d);
                d.setItemStack(item);
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                // fixed (not facing the player): the item turns on itself
                d.setBillboard(Display.Billboard.FIXED);
                d.setTransformation(transformation(step, bob));
            }).getUniqueId();
            ids.add(id);
            items.put(s.id, id);
        }
        if (withText && plugin.getConfig().getBoolean("display.text", true)) {
            Component text = text(s);
            Location at = base.clone().add(0, plugin.getConfig().getDouble("display.text-offset", 0.45), 0);
            ids.add(w.spawn(at, TextDisplay.class, d -> {
                prepare(d);
                d.text(text);
                d.setBillboard(Display.Billboard.CENTER);
                d.setShadowed(true);
                d.setDefaultBackground(false);
                d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                d.setAlignment(TextDisplay.TextAlignment.CENTER);
            }).getUniqueId());
        }
        shown.put(s.id, ids);
    }

    private void prepare(Display d) {
        d.setPersistent(false);
        d.getPersistentDataContainer().set(mark, PersistentDataType.BYTE, (byte) 1);
        d.setViewRange((float) plugin.getConfig().getDouble("display.view-range", 0.5));
    }

    /** Selling / buying, price of one item (and bundle), then the seller (or out of stock, full). */
    private Component text(Shop s) {
        String bundle = s.bundle > 1 ? plugin.messages().raw("display.bundle", "%bundle%", String.valueOf(s.bundle)) : "";
        String first = plugin.messages().raw(s.buying ? "display.buying" : "display.selling",
                "%unit%", plugin.unitPrice(s), "%bundle%", bundle);
        Component seller = s.unlimited ? null : ChestShops.sellerName(s.view());
        String state = !s.unlimited && s.stock == 0 ? plugin.messages().raw(s.buying ? "display.full" : "display.empty") : null;
        Component out = parse(first);
        if (state != null) out = out.appendNewline().append(parse(state));
        if (s.unlimited) {
            out = out.appendNewline().append(parse(plugin.messages().raw("display.server")));
        } else if (seller != null) {
            out = out.appendNewline().append(parse(plugin.messages().raw("display.seller-prefix"))).append(seller);
        } else {
            out = out.appendNewline().append(parse(plugin.messages().raw("display.seller",
                    "%seller%", s.ownerName == null ? "?" : s.ownerName)));
        }
        return out;
    }

    /** Text without a viewer: glyphs written {@code :id:} (PlaceholderAPI needs a player). */
    private static Component parse(String raw) {
        return Texts.parse(raw.replaceAll("%img_([a-z0-9_]+)%", ":$1:"));
    }
}

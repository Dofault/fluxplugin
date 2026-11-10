package fr.influencraft.fluxarmor;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.effects.Effects;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.items.CustomItems;
import fr.influencraft.flux.api.items.ItemCatalog;
import fr.influencraft.flux.api.items.ItemLooks;
import fr.influencraft.fluxarmor.compat.EcoArmorPieces;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Equippable;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * FluxArmor: armour sets. A set ({@code sets/<id>.yml}) defines its pieces, known to the suite as
 * {@code fluxarmor:<set>_<piece>} ({@link ItemCatalog}), the bonus of the full set and of part of it ({@link Effects}),
 * its sounds and recipes, and optionally a 3D model drawn by each player's GPU through the entity shader of the resource
 * pack ({@link PackBuilder}), also worn by the items listed in {@code worn-by}.
 *
 * <p>The worn appearance an item had is kept in its data ({@code fluxarmor:original_asset}) and given back as soon as no
 * 3D armour applies to it any more.</p>
 *
 * <pre>
 *   /fluxarmor build     writes the pack (then /iazip)
 *   /fluxarmor reload    re-reads config.yml and sets/
 * </pre>
 */
public final class FluxArmor extends FluxPlugin implements Listener {

    private final NamespacedKey originalAsset = new NamespacedKey("fluxarmor", "original_asset");
    private final Map<String, ArmorSet> sets = new LinkedHashMap<>();
    private final Map<UUID, String> fullSets = new HashMap<>();
    private final List<NamespacedKey> recipes = new ArrayList<>();
    /** {@code <set>_<piece>} built in the pack. */
    private final Set<String> built = new HashSet<>();
    private ItemCatalog catalog;

    @Override
    protected List<String> formerNames() {
        return List.of("InfluArmor");
    }

    @Override
    protected void enable() {
        if (!new File(getDataFolder(), "sets/_example.yml").exists()) saveResource("sets/_example.yml", false);
        catalog = ItemCatalog.create(this, "fluxarmor");
        EcoArmorPieces.register(this, catalog);
        settings();
        ItemLooks.register(this, this::look);
        Effects.provide(this, this::bonuses);
        Effects.whenReady(this, this::compileBonuses);
        Bukkit.getPluginManager().registerEvents(this, this);
        command("fluxarmor")
                .description("Armour sets administration.")
                .permission("fluxarmor.admin")
                .suggest(false)
                .usage(messages().raw("usage"))
                .executor(this::onCommand)
                .completer((sender, args) -> args.length == 1 ? List.of("build", "reload") : List.of())
                .register();
        command("set")
                .description("Gives a whole armour set (and its extras).")
                .permission("fluxarmor.admin")
                .suggest(false)
                .usage(messages().raw("set.usage"))
                .executor(this::onSet)
                .completer((sender, args) -> args.length == 1 ? List.copyOf(sets.keySet())
                        : args.length == 2 ? Bukkit.getOnlinePlayers().stream().map(org.bukkit.entity.Player::getName).toList() : List.of())
                .register();
        ItemLooks.refreshOnline();
    }

    /** {@code /set <set> [player]}: every piece of the set, then its extras, into the inventory (the rest drops at his feet). */
    private boolean onSet(CommandSender sender, String label, String[] args) {
        if (args.length == 0) {
            messages().send(sender, "set.usage");
            return true;
        }
        ArmorSet set = sets.get(args[0].toLowerCase(Locale.ROOT));
        if (set == null) {
            messages().send(sender, "set.unknown", "%set%", args[0], "%sets%", String.join(", ", sets.keySet()));
            return true;
        }
        org.bukkit.entity.Player to = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : sender instanceof org.bukkit.entity.Player p ? p : null;
        if (to == null) {
            messages().send(sender, "set.no-player");
            return true;
        }
        List<String> ids = new java.util.ArrayList<>();
        set.pieces.keySet().forEach(piece -> ids.add("fluxarmor:" + set.id + "_" + piece));
        set.extras.keySet().forEach(extra -> ids.add("fluxarmor:" + set.id + "_" + extra));
        int given = 0;
        for (String id : ids) {
            ItemStack item = CustomItems.item(id);
            if (item == null) continue;
            to.getInventory().addItem(item).values().forEach(left -> to.getWorld().dropItem(to.getLocation(), left));
            given++;
        }
        messages().send(sender, "set.given", "%set%", set.id, "%count%", String.valueOf(given), "%player%", to.getName());
        return true;
    }

    @Override
    public void reload() {
        super.reload();
        settings();
        compileBonuses();
        ItemLooks.refreshOnline();
    }

    private File content() {
        return new File(getDataFolder().getParentFile(), getConfig().getString("itemsadder-content", "ItemsAdder/contents/fluxarmor"));
    }

    private void settings() {
        sets.clear();
        built.clear();
        catalog.clear();
        recipes.forEach(Bukkit::removeRecipe);
        recipes.clear();
        File folder = new File(getDataFolder(), "sets");
        if (!folder.isDirectory() && !folder.mkdirs()) getLogger().warning("Cannot create the sets folder.");
        File[] files = folder.listFiles((d, n) -> n.endsWith(".yml") && !n.startsWith("_"));
        if (files != null) {
            Arrays.sort(files);
            for (File f : files) {
                ArmorSet set = new ArmorSet(f.getName().replaceFirst("\\.yml$", ""), YamlConfiguration.loadConfiguration(f));
                sets.put(set.id, set);
                set.pieces.forEach((piece, section) -> catalog.add(set.id + "_" + piece, section));
                set.extras.forEach((extra, section) -> catalog.add(set.id + "_" + extra, section));
            }
        }
        for (ArmorSet set : sets.values()) {
            set.pieces.forEach((piece, section) -> recipe(set.id + "_" + piece, section.getConfigurationSection("recipe")));
            for (String piece : PackBuilder.PIECE_NAMES) if (PackBuilder.built(content(), set.id, piece)) built.add(set.id + "_" + piece);
        }
        getLogger().info(sets.size() + " armour sets, " + built.size() + " 3D pieces built.");
    }

    /** Shaped recipe of a piece: {@code shape} (lines of keys), {@code ingredients} (key: any spec of Items.of). */
    private void recipe(String id, ConfigurationSection section) {
        if (section == null) return;
        ItemStack result = CustomItems.item("fluxarmor:" + id);
        ConfigurationSection ingredients = section.getConfigurationSection("ingredients");
        if (result == null || ingredients == null) return;
        NamespacedKey key = new NamespacedKey(this, id);
        ShapedRecipe recipe = new ShapedRecipe(key, result);
        recipe.shape(section.getStringList("shape").toArray(String[]::new));
        for (String k : ingredients.getKeys(false)) {
            ItemStack ingredient = Items.of(ingredients.getString(k));
            if (ingredient != null) recipe.setIngredient(k.charAt(0), new RecipeChoice.ExactChoice(ingredient));
        }
        if (Bukkit.addRecipe(recipe)) recipes.add(key);
    }

    private void compileBonuses() {
        for (ArmorSet set : sets.values()) set.compile(this);
        for (Player p : Bukkit.getOnlinePlayers()) Effects.refresh(p);
    }

    /** Set pieces worn by the player: set id -> piece names. */
    private Map<String, List<String>> worn(Player player) {
        Map<String, List<String>> out = new HashMap<>();
        for (ItemStack item : player.getInventory().getArmorContents()) {
            String id = CustomItems.id(item);
            if (id == null || !id.startsWith("fluxarmor:")) continue;
            String name = id.substring("fluxarmor:".length());
            int sep = name.lastIndexOf('_');
            if (sep > 0) out.computeIfAbsent(name.substring(0, sep), k -> new ArrayList<>()).add(name.substring(sep + 1));
        }
        return out;
    }

    private Collection<Effects.Bundle> bonuses(Player player) {
        List<Effects.Bundle> out = new ArrayList<>();
        worn(player).forEach((id, pieces) -> {
            ArmorSet set = sets.get(id);
            if (set != null) out.addAll(set.bonuses(pieces));
        });
        return out;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void armorChange(PlayerArmorChangeEvent event) {
        Player player = event.getPlayer();
        // the inventory is up to date on the next tick
        Bukkit.getScheduler().runTask(this, () -> {
            if (!player.isOnline()) return;
            Effects.refresh(player);
            String full = null;
            for (Map.Entry<String, List<String>> e : worn(player).entrySet()) {
                ArmorSet set = sets.get(e.getKey());
                if (set != null && e.getValue().size() >= set.fullCount()) full = set.id;
            }
            String before = full == null ? fullSets.remove(player.getUniqueId()) : fullSets.put(player.getUniqueId(), full);
            if (Objects.equals(before, full)) return;
            if (before != null && sets.get(before) != null) play(player, sets.get(before).unequipSound);
            if (full != null) play(player, sets.get(full).equipSound);
        });
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        fullSets.remove(event.getPlayer().getUniqueId());
    }

    private static void play(Player player, ArmorSet.SetSound sound) {
        if (sound != null && !sound.sound().isEmpty()) player.playSound(player.getLocation(), sound.sound(), SoundCategory.PLAYERS, sound.volume(), sound.pitch());
    }

    /** Worn appearance of the item (ItemLooks): its armour's piece, or back to its own once no armour applies. */
    private boolean look(String id, ItemStack item) {
        Equippable eq = item.getData(DataComponentTypes.EQUIPPABLE);
        if (eq == null) return false;
        String piece = switch (eq.slot()) {
            case CHEST -> PackBuilder.PIECE_NAMES.get(0);
            case LEGS -> PackBuilder.PIECE_NAMES.get(1);
            case FEET -> PackBuilder.PIECE_NAMES.get(2);
            default -> null;
        };
        Key target = null;
        if (piece != null) for (ArmorSet set : sets.values()) {
            if (!built.contains(set.id + "_" + piece) || set.wornBy.stream().noneMatch(p -> CustomItems.matches(p, id))) continue;
            target = Key.key(PackBuilder.NAMESPACE, set.id + "_" + piece);
            break;
        }
        String original = item.getPersistentDataContainer().get(originalAsset, PersistentDataType.STRING);
        if (target != null) {
            if (eq.assetId() != null && eq.assetId().asString().equals(target.asString())) return false;
            if (original == null) {
                String own = eq.assetId() == null ? "" : eq.assetId().asString();
                item.editMeta(m -> m.getPersistentDataContainer().set(originalAsset, PersistentDataType.STRING, own));
            }
            item.setData(DataComponentTypes.EQUIPPABLE, eq.toBuilder().assetId(target));
            // The enchantment glint is drawn over the vanilla boxes: it would show a flat armour over the 3D one.
            item.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, false);
            return true;
        }
        if (original == null) return false;
        item.editMeta(m -> m.getPersistentDataContainer().remove(originalAsset));
        item.setData(DataComponentTypes.EQUIPPABLE, eq.toBuilder().assetId(original.isEmpty() ? null : Key.key(original)));
        item.resetData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE);
        return true;
    }

    private boolean onCommand(CommandSender sender, String label, String[] args) {
        switch (args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                reload();
                messages().send(sender, "reloaded");
            }
            case "build" -> build(sender);
            default -> messages().send(sender, "usage");
        }
        return true;
    }

    private void build(CommandSender sender) {
        File content = content();
        PackBuilder builder = new PackBuilder(getDataFolder());
        try {
            builder.clean(content);
            builder.writeShaders(content);
            for (ArmorSet set : sets.values()) {
                ConfigurationSection c = set.config;
                if (c.getString("model") == null) continue;
                messages().send(sender, "built", "%summary%", builder.build(set.id, c.getString("model"),
                        c.getString("fallback-humanoid"), c.getString("fallback-leggings"), content));
            }
            settings();
            ItemLooks.refreshOnline();
            messages().send(sender, "written", "%folder%", content.getPath());
        } catch (IOException | RuntimeException e) {
            messages().send(sender, "failed", "%error%", String.valueOf(e.getMessage()));
            getLogger().warning("Build failed: " + e);
        }
    }
}

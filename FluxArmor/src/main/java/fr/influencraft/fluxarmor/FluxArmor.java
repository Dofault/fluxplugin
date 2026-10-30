package fr.influencraft.fluxarmor;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.items.CustomItems;
import fr.influencraft.flux.api.items.ItemLooks;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Equippable;
import net.kyori.adventure.key.Key;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * FluxArmor: armour in real 3D, drawn by each player's GPU through the entity shader of the resource pack (see
 * {@link PackBuilder}). FluxArmor does not own any item: an armour of {@code config.yml} lists the Flux ids of the
 * items wearing it ({@code ecoarmor:virale_*}, {@code influarmures:enchanteur_*}...), recognised by Flux
 * ({@link CustomItems#id}); FluxArmor only gives them their worn appearance ({@link ItemLooks}).
 *
 * <p>The item's former worn appearance is kept in its data ({@code fluxarmor:original_asset}) and given back as soon
 * as no armour applies to it any more: removing an armour from the config (then FluxArmor itself) leaves the items as
 * they were.</p>
 *
 * <pre>
 *   /fluxarmor build     writes the pack (then /iazip)
 *   /fluxarmor reload    re-reads config.yml
 * </pre>
 */
public final class FluxArmor extends FluxPlugin {

    /** An armour: its id and the item id patterns wearing it. */
    private record Armor(String id, List<String> items) {
    }

    private final NamespacedKey originalAsset = new NamespacedKey("fluxarmor", "original_asset");
    private final List<Armor> armors = new ArrayList<>();
    /** {@code <armour>_<piece>} built in the pack. */
    private final Set<String> built = new HashSet<>();

    @Override
    protected List<String> formerNames() {
        return List.of("InfluArmor");
    }

    @Override
    protected void enable() {
        settings();
        ItemLooks.register(this, this::look);
        command("fluxarmor")
                .description("3D armour administration.")
                .permission("fluxarmor.admin")
                .usage(messages().raw("usage"))
                .executor(this::onCommand)
                .completer((sender, args) -> args.length == 1 ? List.of("build", "reload") : List.of())
                .register();
        ItemLooks.refreshOnline();
    }

    @Override
    public void reload() {
        super.reload();
        settings();
        ItemLooks.refreshOnline();
    }

    private File content() {
        return new File(getDataFolder().getParentFile(), getConfig().getString("itemsadder-content", "ItemsAdder/contents/fluxarmor"));
    }

    private void settings() {
        armors.clear();
        built.clear();
        ConfigurationSection section = getConfig().getConfigurationSection("armors");
        if (section != null) for (String id : section.getKeys(false)) {
            List<String> items = section.getStringList(id + ".items");
            if (items.isEmpty()) getLogger().warning("Armour " + id + ": no items, never worn.");
            armors.add(new Armor(id, items));
            for (String piece : PackBuilder.PIECE_NAMES) if (PackBuilder.built(content(), id, piece)) built.add(id + "_" + piece);
        }
        getLogger().info(armors.size() + " armours, " + built.size() + " pieces built.");
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
        if (piece != null) for (Armor a : armors) {
            if (!built.contains(a.id() + "_" + piece) || a.items().stream().noneMatch(p -> CustomItems.matches(p, id))) continue;
            target = Key.key(PackBuilder.NAMESPACE, a.id() + "_" + piece);
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
        ConfigurationSection section = getConfig().getConfigurationSection("armors");
        try {
            builder.clean(content);
            builder.writeShaders(content);
            if (section != null) for (String id : Set.copyOf(section.getKeys(false))) {
                ConfigurationSection a = section.getConfigurationSection(id);
                messages().send(sender, "built", "%summary%", builder.build(id, a.getString("model"),
                        a.getString("fallback-humanoid"), a.getString("fallback-leggings"), content));
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

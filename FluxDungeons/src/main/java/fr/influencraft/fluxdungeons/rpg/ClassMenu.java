package fr.influencraft.fluxdungeons.rpg;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.list.ListEntry;
import fr.influencraft.flux.api.list.Lists;
import fr.influencraft.fluxdungeons.rpg.core.ClassDef;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The classes in a plain Flux menu (2026-11-16, owner: outside the rpg lobby, a utility menu rather than the MC GUI
 * pages; the lobby keeps its showcase): {@code rpg_classes}, written by {@code assets/menus/gen_menus.py}. The three
 * classes with their role, description and the player's level in each (read from the database, so it works outside
 * the rpg universe too), his own class marked; a click chooses one through {@code /classe choisir} (the rules of a
 * change: from the lobby, once per cooldown).
 */
public final class ClassMenu {

    public static final String MENU = "rpg_classes";

    /** What a player had when the menu was opened: his class and his level in each class. */
    private record Seen(String heroClass, Map<String, Integer> levels) {
    }

    private final FluxPlugin plugin;
    private final RpgConfig config;
    private final RpgStore store;
    private final RpgItems items;
    private final Map<UUID, Seen> seen = new ConcurrentHashMap<>();

    ClassMenu(FluxPlugin plugin, RpgConfig config, RpgStore store, RpgItems items) {
        this.plugin = plugin;
        this.config = config;
        this.store = store;
        this.items = items;
        Lists.register(plugin, "rpg_classes_boutons", q -> buttons(q.viewer()));
        Lists.register(plugin, "rpg_classes_liste", q -> classes(q.viewer()));
    }

    /** Reads his class and levels (off the main thread), then opens the menu; false when it cannot be shown. */
    boolean open(Player p) {
        if (!FluxApi.hasMenu(MENU) || store == null) return false;
        UUID id = p.getUniqueId();
        List<String> classes = RpgPlayers.visible(config.get());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String heroClass = null;
            Map<String, Integer> levels = new HashMap<>();
            try {
                RpgStore.Hero hero = store.hero(id);
                heroClass = hero == null ? null : hero.heroClass();
                for (String c : classes) {
                    RpgStore.Progress pr = store.progress(id, c);
                    if (pr != null) levels.put(c, pr.level());
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Classes of " + id + " unreadable: " + e.getMessage());
            }
            Seen s = new Seen(heroClass, levels);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!p.isOnline()) return;
                seen.put(id, s);
                Lists.invalidate(p, "rpg_classes_boutons");
                Lists.invalidate(p, "rpg_classes_liste");
                FluxApi.open(p, MENU);
            });
        });
        return true;
    }

    private List<ListEntry> buttons(Player viewer) {
        Seen s = seen.get(viewer.getUniqueId());
        Lore info = Lore.create().description(raw("class-menu.info-desc"));
        if (s != null && s.heroClass() != null) {
            info.kv(raw("class-menu.current"), raw("classes." + s.heroClass() + ".name"))
                    .kv(raw("class-menu.level"), s.levels().getOrDefault(s.heroClass(), 1));
        } else info.kv(raw("class-menu.current"), raw("class-menu.none"));
        List<ListEntry> out = new ArrayList<>();
        out.add(ListEntry.of("back").item(orBarrier(Items.of("itemsadder:fluxui:btn_back_orange", raw("class-menu.back"),
                Lore.create().left(raw("class-menu.back-action")).build(), viewer))).set("left", "[menu] donjons"));
        out.add(ListEntry.of("info").item(orBarrier(Items.of("itemsadder:fluxui:btn_info_blue", raw("class-menu.title"),
                info.build(), viewer))).set("left", ""));
        return out;
    }

    private List<ListEntry> classes(Player viewer) {
        Seen s = seen.get(viewer.getUniqueId());
        RpgConfig.Set set = config.get();
        List<ListEntry> out = new ArrayList<>();
        if (set == null) return out;
        for (String id : RpgPlayers.visible(set)) {
            ClassDef c = set.classes().get(id);
            boolean mine = s != null && id.equals(s.heroClass());
            Lore lore = Lore.create().description(raw("class-pages.classes." + id + ".role"))
                    .description(raw("classes." + id + ".description"))
                    .kv(raw("class-menu.level"), s == null ? 1 : s.levels().getOrDefault(id, 1));
            if (mine) lore.ok(raw("class-menu.mine"));
            else lore.left(raw("class-menu.choose"));
            ItemStack weapon = items.make(RpgItems.starterId(id));
            ItemStack item = Items.dress(weapon == null ? new ItemStack(Material.BOOK) : weapon,
                    raw("classes." + id + ".name"), lore.build(), viewer);
            if (mine) item.editMeta(m -> m.setEnchantmentGlintOverride(true));
            out.add(ListEntry.of(id).item(item).set("left", mine || c == null ? "" : "[player] classe choisir " + id));
        }
        return out;
    }

    private static ItemStack orBarrier(ItemStack i) {
        return i == null ? new ItemStack(Material.BARRIER) : i;
    }

    private String raw(String key, String... vars) {
        return plugin.messages().raw(key, vars);
    }
}

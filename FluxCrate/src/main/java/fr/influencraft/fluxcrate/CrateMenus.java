package fr.influencraft.fluxcrate;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.flux.api.MenuView;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.list.ListEntry;
import fr.influencraft.flux.api.list.Lists;
import fr.influencraft.flux.api.util.Format;
import fr.influencraft.flux.api.util.Sounds;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The two menus of a crate (Flux menus, built here, placed by the menu files):
 * <ul>
 *   <li>{@code caisse_apercu}: every reward with its chance ({@code fluxcrate_apercu}), the crate's button
 *   ({@code fluxcrate_caisse}: keys carried, left click opens with a key);</li>
 *   <li>{@code caisse_ouverture}: the roll, a strip of rewards sliding and slowing down under a gold frame
 *   ({@code fluxcrate_roll}), then the reward won. The reward is drawn before the strip moves and given at the end,
 *   or at once when the player closes the menu.</li>
 * </ul>
 * Actions: {@code [fluxcrate] open} (crate shown in the preview).
 */
final class CrateMenus {

    static final String PREVIEW = "fluxcrate_apercu";
    static final String CRATE = "fluxcrate_caisse";
    static final String ROLL = "fluxcrate_roll";
    static final String PREVIEW_MENU = "caisse_apercu";
    static final String ROLL_MENU = "caisse_ouverture";
    private static final int VISIBLE = 7;     // cases of the strip
    private static final int STRIP = 52;      // rewards on the strip (at least DELAYS + 7), the won one at STRIP - 4
    private static final int[] DELAYS = {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            2, 2, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 5, 6, 8};

    /** A roll in progress or just finished. */
    private static final class Roll {
        final Crate crate;
        final Crate.Reward won;
        final List<Crate.Reward> strip = new ArrayList<>();
        int position;
        boolean given;

        Roll(Crate crate, Crate.Reward won) {
            this.crate = crate;
            this.won = won;
            for (int i = 0; i < STRIP; i++) strip.add(i == STRIP - 4 ? won : crate.draw());
        }

        boolean done() {
            return position >= DELAYS.length;
        }
    }

    private final FluxCrate plugin;
    private final Map<UUID, String> shown = new ConcurrentHashMap<>();
    private final Map<UUID, Roll> rolls = new ConcurrentHashMap<>();

    CrateMenus(FluxCrate plugin) {
        this.plugin = plugin;
    }

    void enable() {
        Lists.register(plugin, PREVIEW, q -> preview(q.viewer()));
        Lists.register(plugin, CRATE, q -> List.of(crateButton(q.viewer())));
        Lists.register(plugin, ROLL, q -> strip(q.viewer()));
        FluxApi.registerAction(plugin, "fluxcrate", (player, view, argument) -> {
            if (argument.trim().equalsIgnoreCase("open")) {
                Crate crate = plugin.crate(shown.get(player.getUniqueId()));
                if (crate != null) open(player, crate);
            }
        });
        // a roll whose menu is gone (closed, another menu): finished at once
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Map.Entry<UUID, Roll> e : rolls.entrySet()) {
                Player p = Bukkit.getPlayer(e.getKey());
                MenuView v = p == null ? null : FluxApi.view(p);
                if (p == null || v == null || !v.isOpen() || !ROLL_MENU.equals(v.menuId())) finish(e.getKey(), p);
            }
        }, 10L, 10L);
    }

    // ------------------------------------------------------------------ preview

    void preview(Player player, Crate crate) {
        shown.put(player.getUniqueId(), crate.id());
        Lists.reset(player, PREVIEW);
        FluxApi.open(player, PREVIEW_MENU);
    }

    private List<ListEntry> preview(Player player) {
        Crate crate = plugin.crate(shown.get(player.getUniqueId()));
        List<ListEntry> out = new ArrayList<>();
        if (crate == null) return out;
        for (Crate.Reward r : crate.rewards()) {
            Lore lore = Lore.create().items(r.lore()).kv("Chance", Format.decimal(crate.chance(r)) + "%");
            out.add(ListEntry.of(r.id()).item(display(r, lore, player, false)));
        }
        return out;
    }

    private ListEntry crateButton(Player player) {
        Crate crate = plugin.crate(shown.get(player.getUniqueId()));
        if (crate == null) return ListEntry.gap();
        int keys = Keys.count(player, crate.id());
        Lore lore = Lore.create().description("Une récompense tirée au hasard parmi celles du dessus, contre une clé.")
                .kv("Clés", keys);
        if (keys > 0) lore.left("Ouvrir");
        else lore.ko("Il te faut une " + crate.keyName() + "&r.");
        return ListEntry.of(crate.id()).item(Items.of(crate.keyItem(), crate.name(), lore.build(), player));
    }

    // ------------------------------------------------------------------ roll

    /** Takes a key and starts the roll; false when the player has no key. */
    boolean open(Player player, Crate crate) {
        if (rolls.containsKey(player.getUniqueId())) return true;
        Crate.Reward won = crate.draw();
        if (won == null || !Keys.take(player, crate.id(), 1)) {
            plugin.messages().send(player, "no-key", "%key%", crate.keyName());
            Sounds.cue(player, "error");
            return false;
        }
        Roll roll = new Roll(crate, won);
        rolls.put(player.getUniqueId(), roll);
        Lists.reset(player, ROLL);
        FluxApi.open(player, ROLL_MENU);
        step(player.getUniqueId());
        return true;
    }

    private void step(UUID id) {
        Roll roll = rolls.get(id);
        Player p = Bukkit.getPlayer(id);
        if (roll == null || roll.given || p == null) return;
        if (roll.done()) {
            finish(id, p);
            return;
        }
        int delay = DELAYS[roll.position];
        roll.position++;
        Sounds.cue(p, "click");
        refresh(p);
        Bukkit.getScheduler().runTaskLater(plugin, () -> step(id), delay);
    }

    private static void refresh(Player p) {
        Lists.invalidate(p, ROLL);
        MenuView v = FluxApi.view(p);
        if (v != null && v.isOpen()) v.refreshButtons();
    }

    /** Gives the reward won (once); the strip stays on it until the menu is closed. */
    private void finish(UUID id, Player p) {
        Roll roll = rolls.get(id);
        if (roll == null) return;
        if (!roll.given) {
            roll.given = true;
            roll.position = DELAYS.length;
            if (p != null) {
                plugin.give(p, roll.crate, roll.won);
                Sounds.cue(p, "complete");
                refresh(p);
            }
        }
        MenuView v = p == null ? null : FluxApi.view(p);
        if (v == null || !v.isOpen() || !ROLL_MENU.equals(v.menuId())) rolls.remove(id);
    }

    /** The item shown for a reward: its display item, name, lore, model data and shine. */
    @SuppressWarnings("deprecation") // custom model data: the number the resource pack of the server uses
    private static ItemStack display(Crate.Reward r, Lore lore, Player player, boolean shine) {
        ItemStack item = Items.of(r.display(), r.name(), lore.build(), player);
        if (item == null) item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (r.modelData() != 0) meta.setCustomModelData(r.modelData());
            if (r.glow() || shine) meta.setEnchantmentGlintOverride(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** The 7 cases of the strip; the middle one is the reward under the gold frame. */
    private List<ListEntry> strip(Player player) {
        Roll roll = rolls.get(player.getUniqueId());
        List<ListEntry> out = new ArrayList<>();
        if (roll == null) return out;
        int start = roll.position + (STRIP - 4 - DELAYS.length) - VISIBLE / 2;
        for (int i = 0; i < VISIBLE; i++) {
            int index = Math.max(0, Math.min(STRIP - 1, start + i));
            Crate.Reward r = roll.strip.get(index);
            boolean centre = i == VISIBLE / 2;
            Lore lore = Lore.create().items(r.lore());
            if (centre && roll.given) lore.ok("Gagné !");
            out.add(ListEntry.of("roll:" + i).item(display(r, lore, player, centre && roll.given)));
        }
        return out;
    }
}

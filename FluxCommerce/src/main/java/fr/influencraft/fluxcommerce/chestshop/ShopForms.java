package fr.influencraft.fluxcommerce.chestshop;

import fr.influencraft.flux.api.dialog.Dialogs;
import fr.influencraft.flux.api.shops.ChestShops;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.Sounds;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The windows of a shop (Paper dialogs through Flux): creation (sneak + right click on a container with the item in
 * hand), settings of an existing shop (sneak + right click by its owner or the staff: mode, bundle, price, options,
 * friends, removal), friends, removal confirmation. Nothing is typed in the chat.
 */
final class ShopForms {

    private static final String SELL = "sell", BUY = "buy";

    private final ChestShopModule plugin;

    ShopForms(ChestShopModule plugin) {
        this.plugin = plugin;
    }

    private String t(String key, String... vars) {
        return plugin.messages().raw(key, vars);
    }

    // ---------------------------------------------------------------- creation

    /** Creation window for this container and the item held; the checks are made again on submit. */
    void create(Player player, Block block, ItemStack held) {
        String refused = plugin.mayCreate(player, block, held);
        if (refused != null) {
            plugin.messages().send(player, refused);
            Sounds.cue(player, "error");
            return;
        }
        ItemStack item = held.clone();
        item.setAmount(1);
        Location loc = block.getLocation();
        Map<String, ChestShops.Offer> options = ChestShops.offers(player, loc, null);
        int maxBundle = Math.max(1, item.getMaxStackSize());
        List<String> modes = List.of(t("form.mode-sell"), t("form.mode-buy"));
        Dialogs.Form form = Dialogs.form(player, t("form.create-title"))
                .item(item, t("form.create-item"))
                .body(t("form.create-help"))
                .choice("mode", t("form.mode"), modes)
                .amount("bundle", t("form.bundle"), Math.min(maxBundle, held.getAmount()), 1, maxBundle)
                .amount("price", t("form.price"), 0, plugin.minPrice(), plugin.maxPrice());
        List<String> looks = looks(plugin.defaultLook());
        List<String> woods = woods(plugin.defaultWood());
        form.choice("look", t("form.look"), looks).choice("wood", t("form.wood"), woods);
        for (Map.Entry<String, ChestShops.Offer> o : options.entrySet()) {
            form.toggle("option_" + o.getKey(), Texts.ampersand(o.getValue().label()), false,
                    o.getValue().description() == null ? null : Texts.ampersand(o.getValue().description()));
        }
        form.buttons(t("form.create-confirm"), t("form.cancel"))
                .onSubmit((p, a) -> {
                    String again = plugin.mayCreate(p, block, item);
                    if (again != null) {
                        plugin.messages().send(p, again);
                        Sounds.cue(p, "error");
                        return;
                    }
                    Shop s = new Shop(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
                    s.owner = p.getUniqueId();
                    s.ownerName = p.getName();
                    s.item = item;
                    s.buying = a.text("mode").equals(modes.get(1));
                    s.bundle = (int) a.amount("bundle");
                    s.price = a.amount("price");
                    s.created = System.currentTimeMillis();
                    for (String id : options.keySet()) if (a.toggle("option_" + id)) s.options.add(id);
                    s.look = look(a.text("look"));
                    s.wood = wood(a.text("wood"));
                    if (!plugin.registry().add(s)) {
                        plugin.messages().send(p, "trade.failed");
                        Sounds.cue(p, "error");
                        return;
                    }
                    plugin.registry().count(s);
                    if (!plugin.displays().update(s, p.getFacing().getOppositeFace())) plugin.messages().send(p, "no-room-for-sign");
                    plugin.send(p, s.buying ? "created-buying" : "created-selling", s.item,
                            "%bundle%", String.valueOf(s.bundle), "%price%", plugin.price(s.price), "%unit%", plugin.unitPrice(s));
                    Sounds.cue(p, "success");
                    plugin.getLogger().info(p.getName() + " created shop " + s.id + " (" + (s.buying ? "buying " : "selling ")
                            + s.bundle + " " + s.item.getType().getKey() + " for " + s.price + ") at " + s.key());
                })
                .open();
    }

    // ---------------------------------------------------------------- settings

    void settings(Player player, Shop shop) {
        if (!plugin.mayManage(player, shop)) {
            plugin.messages().send(player, "not-yours", "%owner%", shop.ownerName == null ? "?" : shop.ownerName);
            Sounds.cue(player, "error");
            return;
        }
        plugin.registry().count(shop);
        Location loc = shop.location();
        Map<String, ChestShops.Offer> options = ChestShops.offers(player, loc, shop.view());
        // the current mode first: a drop-down list starts on its first option
        List<String> modes = shop.buying ? List.of(t("form.mode-buy"), t("form.mode-sell")) : List.of(t("form.mode-sell"), t("form.mode-buy"));
        String buyLabel = t("form.mode-buy");
        int maxBundle = Math.max(1, shop.item.getMaxStackSize());
        String stock = shop.unlimited ? t("form.unlimited") : shop.stock < 0 ? "?" : String.valueOf(shop.stock);
        boolean staff = player.hasPermission(ChestShopModule.ADMIN);
        Dialogs.Form form = Dialogs.form(player, t("form.settings-title"))
                .item(shop.item, t(shop.buying ? "form.settings-item-buying" : "form.settings-item-selling",
                        "%stock%", stock, "%owner%", shop.ownerName == null ? "?" : shop.ownerName,
                        "%friends%", String.valueOf(shop.friends.size())))
                .choice("mode", t("form.mode"), modes)
                .amount("bundle", t("form.bundle"), shop.bundle, 1, maxBundle)
                .amount("price", t("form.price"), (long) Math.floor(shop.price), plugin.minPrice(), plugin.maxPrice());
        form.choice("look", t("form.look"), looks(shop.look)).choice("wood", t("form.wood"), woods(shop.wood));
        for (Map.Entry<String, ChestShops.Offer> o : options.entrySet()) {
            form.toggle("option_" + o.getKey(), Texts.ampersand(o.getValue().label()), shop.options.contains(o.getKey()),
                    o.getValue().description() == null ? null : Texts.ampersand(o.getValue().description()));
        }
        if (staff) form.toggle("unlimited", t("form.unlimited-toggle"), shop.unlimited, t("form.unlimited-help"));
        form.action("save", t("form.save"))
                .action("friends", t("form.friends", "%count%", String.valueOf(shop.friends.size())))
                .action("remove", t("form.remove"))
                .buttons(t("form.save"), t("form.close"))
                .columns(3)
                .onSubmit((p, a) -> {
                    if (plugin.registry().get(shop.id) != shop) return;
                    switch (a.button()) {
                        case "friends" -> friends(p, shop);
                        case "remove" -> confirmRemove(p, shop);
                        default -> save(p, shop, a, options, staff, buyLabel);
                    }
                })
                .open();
    }

    private void save(Player p, Shop shop, Dialogs.Answer a, Map<String, ChestShops.Offer> options, boolean staff, String buyLabel) {
        if (!plugin.mayManage(p, shop)) return;
        boolean buying = a.text("mode").equals(buyLabel);
        long price = a.amount("price");
        boolean changedMode = buying != shop.buying;
        shop.buying = buying;
        shop.bundle = (int) a.amount("bundle");
        // a price typed back as shown (whole part) keeps the cents of a former price
        if (price != (long) Math.floor(shop.price)) shop.price = price;
        for (String id : options.keySet()) {
            if (a.toggle("option_" + id)) shop.options.add(id);
            else shop.options.remove(id);
        }
        if (staff) shop.unlimited = a.toggle("unlimited");
        if (changedMode) shop.stock = -1;
        // the sign is hung, re-hung in another wood or taken down by the redraw below
        shop.look = look(a.text("look"));
        shop.wood = wood(a.text("wood"));
        plugin.registry().count(shop);
        if (!plugin.displays().update(shop, p.getFacing().getOppositeFace())) plugin.messages().send(p, "no-room-for-sign");
        plugin.registry().save(shop);
        plugin.send(p, "saved", shop.item);
        Sounds.cue(p, "success");
    }

    // ---------------------------------------------------------------- look

    /** Labels of the two looks, the current one first (a drop-down list starts on its first option). */
    private List<String> looks(String current) {
        String sign = t("form.look-sign"), floating = t("form.look-floating");
        return Shop.FLOATING.equals(current) ? List.of(floating, sign) : List.of(sign, floating);
    }

    private String look(String label) {
        return label.equals(t("form.look-floating")) ? Shop.FLOATING : Shop.SIGN;
    }

    /** Labels of the woods, the current one first. */
    private List<String> woods(String current) {
        Map<String, String> all = plugin.signs().woods();
        List<String> out = new ArrayList<>();
        if (all.containsKey(current)) out.add(all.get(current));
        for (Map.Entry<String, String> e : all.entrySet()) if (!e.getKey().equals(current)) out.add(e.getValue());
        return out;
    }

    private String wood(String label) {
        for (Map.Entry<String, String> e : plugin.signs().woods().entrySet()) if (e.getValue().equals(label)) return e.getKey();
        return plugin.defaultWood();
    }

    // ---------------------------------------------------------------- friends

    void friends(Player player, Shop shop) {
        if (!plugin.mayManage(player, shop)) return;
        int max = plugin.getConfig().getInt("friends.max", 10);
        Dialogs.Form form = Dialogs.form(player, t("form.friends-title"))
                .body(t("form.friends-help"));
        if (shop.friends.isEmpty()) {
            form.body(t("form.friends-none"));
        } else {
            List<String> line = new ArrayList<>();
            for (UUID f : shop.friends) {
                line.add("remove:" + f);
                line.add(t("form.friend-remove", "%player%", name(f)));
            }
            form.choiceLine(line.toArray(new String[0]));
        }
        if (shop.friends.size() < max) form.input("name", t("form.friend-add"), "", 16).action("add", t("form.add"));
        form.action("back", t("form.back"))
                .buttons(t("form.add"), t("form.close"))
                .onSubmit((p, a) -> {
                    if (plugin.registry().get(shop.id) != shop || !plugin.mayManage(p, shop)) return;
                    String button = a.button();
                    if (button.startsWith("remove:")) {
                        UUID f = UUID.fromString(button.substring(7));
                        if (shop.friends.remove(f)) plugin.store().removeFriend(shop.id, f);
                        plugin.messages().send(p, "friend-removed", "%player%", name(f));
                        Sounds.cue(p, "remove");
                        friends(p, shop);
                    } else if (button.equals("add")) {
                        addFriend(p, shop, a.text("name"));
                        friends(p, shop);
                    } else if (button.equals("back")) {
                        settings(p, shop);
                    }
                })
                .open();
    }

    private void addFriend(Player p, Shop shop, String name) {
        if (name.isBlank()) return;
        OfflinePlayer friend = Bukkit.getOfflinePlayerIfCached(name);
        if (friend == null || (!friend.hasPlayedBefore() && !friend.isOnline())) {
            plugin.messages().send(p, "friend-unknown", "%player%", name);
            Sounds.cue(p, "error");
            return;
        }
        if (shop.isOwner(friend.getUniqueId()) || !shop.friends.add(friend.getUniqueId())) {
            plugin.messages().send(p, "friend-already", "%player%", friend.getName());
            return;
        }
        plugin.store().addFriend(shop.id, friend.getUniqueId());
        plugin.messages().send(p, "friend-added", "%player%", friend.getName());
        Sounds.cue(p, "success");
        Player online = friend.getPlayer();
        if (online != null) plugin.messages().send(online, "friend-added-you", "%player%", p.getName(),
                "%place%", shop.x + " " + shop.y + " " + shop.z);
    }

    private static String name(UUID player) {
        OfflinePlayer o = Bukkit.getOfflinePlayer(player);
        return o.getName() == null ? "?" : o.getName();
    }

    // ---------------------------------------------------------------- removal

    void confirmRemove(Player player, Shop shop) {
        Dialogs.confirm(player, t("form.remove-title"), t("form.remove-question"),
                p -> {
                    if (plugin.registry().get(shop.id) != shop || !plugin.mayManage(p, shop)) return;
                    plugin.removeShop(shop, p);
                },
                p -> settings(p, shop));
    }
}

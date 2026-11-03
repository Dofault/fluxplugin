package fr.influencraft.fluxcommerce.chestshop;

import fr.influencraft.flux.api.shops.ChestShops;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.text.Translations;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The sign of a shop (default look): a waxed wall sign on one side of the container, of the wood the owner chose,
 * written in the server's language for everybody (selling or buying, item, price of one item, seller). A shop that has
 * no sign yet (just created, imported from QuickShop) takes the wall sign already hanging on its container if there
 * is one (the former QuickShop sign, its wood kept), else hangs a new one on the front, else on a free side. Main
 * thread.
 */
final class Signs {

    private static final List<BlockFace> SIDES = List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST);
    private static final int MAX_NAME = 15;

    private final ChestShopModule plugin;
    /** Sign block -> shop. */
    private final Map<String, Shop> bySign = new HashMap<>();

    Signs(ChestShopModule plugin) {
        this.plugin = plugin;
    }

    /** Woods of which a wall sign exists, in the order of the game ({@code OAK} -> label of the lang file). */
    Map<String, String> woods() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Material m : Material.values()) {
            if (m.isLegacy() || !m.name().endsWith("_WALL_SIGN") || m.name().endsWith("_HANGING_SIGN")) continue;
            String wood = m.name().substring(0, m.name().length() - "_WALL_SIGN".length());
            String label = plugin.messages().raw("woods." + wood);
            out.put(wood, label.isEmpty() ? wood.toLowerCase(Locale.ROOT).replace('_', ' ') : label);
        }
        return out;
    }

    private static Material material(String wood) {
        Material m = wood == null ? null : Material.matchMaterial(wood + "_WALL_SIGN");
        return m == null ? Material.OAK_WALL_SIGN : m;
    }

    void index(Shop s) {
        Block b = s.signBlock();
        if (b != null) bySign.put(key(b), s);
    }

    private void unindex(Shop s) {
        bySign.values().removeIf(other -> other == s);
    }

    /** The shop whose sign this block is, or null. */
    Shop at(Block block) {
        return block == null ? null : bySign.get(key(block));
    }

    private static String key(Block b) {
        return Shop.key(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
    }

    /**
     * Hangs the sign if needed, then writes it.
     *
     * @param front preferred side for a new sign (the player's side), null for the container's front
     * @return false when there is no room for a sign around the container
     */
    boolean draw(Shop s, BlockFace front) {
        Block container = s.block();
        if (container == null) return false;
        Block sign = s.signBlock();
        if (sign == null || !isSignOf(sign, s.signFace) && !free(sign)) {
            BlockFace face = place(s, container, front);
            if (face == null) return false;
            plugin.registry().save(s);
        } else if (!(sign.getState(false) instanceof Sign) || sign.getType() != material(s.wood)) {
            hang(sign, s.signFace, material(s.wood));
        }
        index(s);
        write(s);
        return true;
    }

    /** Chooses the side (former sign kept), hangs the sign there; null when no side is free. */
    private BlockFace place(Shop s, Block container, BlockFace front) {
        unindex(s);
        for (BlockFace side : SIDES) {
            Block b = container.getRelative(side);
            if (isSignOf(b, side) && at(b) == null) {
                // a sign already hangs there: an imported shop keeps it with its wood (the former QuickShop sign),
                // a new shop takes its place with the wood chosen
                s.signFace = side;
                String name = b.getType().name();
                if (s.source != null) s.wood = name.substring(0, name.length() - "_WALL_SIGN".length());
                else hang(b, side, material(s.wood));
                return side;
            }
        }
        List<BlockFace> order = new ArrayList<>();
        if (front != null && SIDES.contains(front)) order.add(front);
        BlockData data = container.getBlockData();
        if (data instanceof Directional d && SIDES.contains(d.getFacing()) && !order.contains(d.getFacing())) order.add(d.getFacing());
        for (BlockFace side : SIDES) if (!order.contains(side)) order.add(side);
        for (BlockFace side : order) {
            Block b = container.getRelative(side);
            if (!free(b)) continue;
            hang(b, side, material(s.wood));
            s.signFace = side;
            return side;
        }
        s.signFace = null;
        return null;
    }

    private static boolean free(Block b) {
        return b.getType().isAir() || b.isReplaceable() && !b.isLiquid();
    }

    /** A wall sign hanging on the container from this side. */
    private static boolean isSignOf(Block b, BlockFace side) {
        return side != null && b.getBlockData() instanceof WallSign w && w.getFacing() == side;
    }

    private static void hang(Block b, BlockFace side, Material material) {
        WallSign data = (WallSign) material.createBlockData();
        data.setFacing(side);
        b.setBlockData(data, false);
    }

    /** Four lines for everybody: selling / buying (or out of stock), item, price of one item, seller. */
    void write(Shop s) {
        Block b = s.signBlock();
        if (b == null || !(b.getState(false) instanceof Sign sign)) return;
        String state = !s.unlimited && s.stock == 0 ? (s.buying ? "sign.full" : "sign.empty") : (s.buying ? "sign.buying" : "sign.selling");
        String name = Translations.itemName(s.item);
        if (name.length() > MAX_NAME) name = name.substring(0, MAX_NAME - 1) + ".";
        String bundle = s.bundle > 1 ? plugin.messages().raw("sign.bundle", "%bundle%", String.valueOf(s.bundle)) : "";
        Component seller;
        if (s.unlimited) {
            seller = parse(plugin.messages().raw("sign.server"));
        } else {
            Component other = ChestShops.sellerName(s.view());
            seller = other != null ? other : parse(plugin.messages().raw("sign.seller", "%seller%", s.ownerName == null ? "?" : s.ownerName));
        }
        SignSide front = sign.getSide(Side.FRONT);
        front.line(0, parse(plugin.messages().raw(state)));
        front.line(1, parse(plugin.messages().raw("sign.item", "%item%", name)));
        front.line(2, parse(plugin.messages().raw("sign.price", "%unit%", plugin.unitPrice(s), "%bundle%", bundle)));
        front.line(3, seller);
        SignSide back = sign.getSide(Side.BACK);
        for (int i = 0; i < 4; i++) back.line(i, Component.empty());
        sign.setWaxed(true);
        sign.update(true, false);
    }

    /** Takes the sign down (nothing dropped). */
    void remove(Shop s) {
        Block b = s.signBlock();
        unindex(s);
        if (b != null && s.loaded() && isSignOf(b, s.signFace)) b.setType(Material.AIR, false);
        s.signFace = null;
    }

    /** Forgets the index (reload). */
    void clear() {
        bySign.clear();
    }

    /** Glyphs written {@code :id:} (no viewer for PlaceholderAPI). */
    private static Component parse(String raw) {
        return Texts.parse(raw.replaceAll("%img_([a-z0-9_]+)%", ":$1:"));
    }
}

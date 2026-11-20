package fr.influencraft.flux.adapters.packetevents;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.item.type.ItemType;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.mapper.MappedEntitySet;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.recipe.RecipeDisplayEntry;
import com.github.retrooper.packetevents.protocol.recipe.RecipeDisplayId;
import com.github.retrooper.packetevents.protocol.recipe.category.RecipeBookCategories;
import com.github.retrooper.packetevents.protocol.recipe.category.RecipeBookCategory;
import com.github.retrooper.packetevents.protocol.recipe.display.ShapelessCraftingRecipeDisplay;
import com.github.retrooper.packetevents.protocol.recipe.display.slot.ItemSlotDisplay;
import com.github.retrooper.packetevents.protocol.recipe.display.slot.ItemStackSlotDisplay;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCraftRecipeRequest;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerRecipeBookAdd;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerRecipeBookRemove;
import fr.influencraft.flux.api.client.RecipeBook;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link RecipeBook} with PacketEvents. The catalog's entries get display ids from {@link #BASE} up (the server
 * counts its own from 0) and are always sent first: the client lists its book in the order it learnt it. What the
 * server says a player knows is remembered ({@link #known}), so the whole book can be sent again, with or without the
 * crafting recipes, whenever the catalog asks; a click on one of ours never reaches the server.
 */
public final class RecipeBookCatalog extends PacketListenerAbstract implements RecipeBook.Service {

    /** First display id of the catalog's entries. */
    private static final int BASE = 0x40000000;

    private final Plugin flux;
    /** The server's own entries each player knows, by display id, in the order they came. */
    private final Map<UUID, Map<Integer, RecipeDisplayEntry>> known = new ConcurrentHashMap<>();

    public RecipeBookCatalog(Plugin flux) {
        super(PacketListenerPriority.HIGH);
        this.flux = flux;
    }

    @Override
    public void show(Player player, List<RecipeBook.Entry> entries, boolean recipes) {
        Map<Integer, RecipeDisplayEntry> server = known.get(player.getUniqueId());
        // never heard the server's book (Flux started with him online): nothing to rebuild his book from
        if (server == null) return;
        PacketEvents.getAPI().getPlayerManager().sendPacketSilently(player,
                new WrapperPlayServerRecipeBookAdd(book(entries, server, recipes), true));
    }

    /** The whole book: the catalog's entries first, then the server's (its crafting recipes only when kept). */
    private static List<WrapperPlayServerRecipeBookAdd.AddEntry> book(List<RecipeBook.Entry> entries,
                                                                    Map<Integer, RecipeDisplayEntry> server, boolean recipes) {
        List<WrapperPlayServerRecipeBookAdd.AddEntry> all = new ArrayList<>();
        for (RecipeBook.Entry e : entries) all.add(convert(e, e.fresh()));
        synchronized (server) {
            for (RecipeDisplayEntry e : server.values()) {
                if (recipes || !crafting(e.getCategory())) all.add(new WrapperPlayServerRecipeBookAdd.AddEntry(e, false, false));
            }
        }
        return all;
    }

    /** A catalog entry as a shapeless crafting display: the icon as its result, nothing or an unknown item asked. */
    private static WrapperPlayServerRecipeBookAdd.AddEntry convert(RecipeBook.Entry e, boolean fresh) {
        var display = new ShapelessCraftingRecipeDisplay(List.of(),
                new ItemStackSlotDisplay(SpigotConversionUtil.fromBukkitItemStack(e.icon())),
                new ItemSlotDisplay(ItemTypes.CRAFTING_TABLE));
        // what the client checks against his inventory to draw it craftable: nothing, or an item nobody carries
        List<MappedEntitySet<ItemType>> needs = e.available() ? List.of()
                : List.of(new MappedEntitySet<>(List.of(ItemTypes.STRUCTURE_VOID)));
        var entry = new RecipeDisplayEntry(new RecipeDisplayId(BASE + e.id()), display, null, category(e.tab()), needs);
        return new WrapperPlayServerRecipeBookAdd.AddEntry(entry, fresh, fresh);
    }

    private static RecipeBookCategory category(RecipeBook.Tab tab) {
        return switch (tab) {
            case BUILDING -> RecipeBookCategories.CRAFTING_BUILDING_BLOCKS;
            case REDSTONE -> RecipeBookCategories.CRAFTING_REDSTONE;
            case EQUIPMENT -> RecipeBookCategories.CRAFTING_EQUIPMENT;
            case MISC -> RecipeBookCategories.CRAFTING_MISC;
        };
    }

    private static boolean crafting(RecipeBookCategory c) {
        return c == RecipeBookCategories.CRAFTING_BUILDING_BLOCKS || c == RecipeBookCategories.CRAFTING_REDSTONE
                || c == RecipeBookCategories.CRAFTING_EQUIPMENT || c == RecipeBookCategories.CRAFTING_MISC;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        var type = event.getPacketType();
        if (type == PacketType.Play.Server.RECIPE_BOOK_REMOVE) {
            UUID id = event.getUser().getUUID();
            Map<Integer, RecipeDisplayEntry> server = id == null ? null : known.get(id);
            if (server == null) return;
            synchronized (server) {
                for (RecipeDisplayId gone : new WrapperPlayServerRecipeBookRemove(event).getRecipeIds()) server.remove(gone.getId());
            }
            return;
        }
        if (type != PacketType.Play.Server.RECIPE_BOOK_ADD) return;
        UUID id = event.getUser().getUUID();
        if (id == null) return;
        WrapperPlayServerRecipeBookAdd w = new WrapperPlayServerRecipeBookAdd(event);
        Map<Integer, RecipeDisplayEntry> server = known.computeIfAbsent(id, k -> new java.util.LinkedHashMap<>());
        synchronized (server) {
            if (w.isReplace()) server.clear();
            for (var e : w.getEntries()) server.put(e.getContents().getId().getId(), e.getContents());
        }
        // what his book shows now: null until the catalog first said (he keeps the server's book as it is)
        Boolean recipes = RecipeBook.claimed() ? RecipeBook.recipes(id) : null;
        if (recipes == null) return;
        if (w.isReplace()) {
            // the whole book again (join, reload): rebuilt our way
            w.setEntries(book(RecipeBook.sent(id), server, recipes));
            event.markForReEncode(true);
        } else if (!recipes) {
            // a recipe learnt while the catalog hides them: kept for later
            List<WrapperPlayServerRecipeBookAdd.AddEntry> kept = new ArrayList<>();
            for (var e : w.getEntries()) if (!crafting(e.getContents().getCategory())) kept.add(e);
            if (kept.size() == w.getEntries().size()) return;
            if (kept.isEmpty()) {
                event.setCancelled(true);
                return;
            }
            w.setEntries(kept);
            event.markForReEncode(true);
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.CRAFT_RECIPE_REQUEST || !RecipeBook.claimed()) return;
        WrapperPlayClientCraftRecipeRequest w = new WrapperPlayClientCraftRecipeRequest(event);
        // since 1.21.2 the display id, given as an Integer by getRecipe() (a RecipeDisplayId in other versions)
        Object recipe = w.getRecipe();
        int id;
        if (recipe instanceof Integer i) id = i;
        else if (recipe instanceof RecipeDisplayId display) id = display.getId();
        else return;
        // the server's own (a furnace's book): its business
        if (id < BASE) return;
        event.setCancelled(true);
        UUID player = event.getUser().getUUID();
        boolean shift = w.isMakeAll();
        Bukkit.getScheduler().runTask(flux, () -> {
            Player p = player == null ? null : Bukkit.getPlayer(player);
            if (p != null) RecipeBook.click(p, id - BASE, shift);
        });
    }

    /** He left: forget his ids. */
    @Override
    public void onUserDisconnect(com.github.retrooper.packetevents.event.UserDisconnectEvent event) {
        UUID player = event.getUser().getUUID();
        if (player == null) return;
        known.remove(player);
        RecipeBook.forget(player);
    }
}

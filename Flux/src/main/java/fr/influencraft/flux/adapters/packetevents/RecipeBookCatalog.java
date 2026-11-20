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
 * counts its own from 0); the crafting recipes the server sends are left out of every book add, the catalog's put in
 * when the server sends the whole book again (login, reload), and a click on one of ours never reaches the server.
 */
public final class RecipeBookCatalog extends PacketListenerAbstract implements RecipeBook.Service {

    /** First display id of the catalog's entries. */
    private static final int BASE = 0x40000000;

    private final Plugin flux;
    /** Ids last sent to each player, removed before the next ones are added. */
    private final Map<UUID, List<RecipeDisplayId>> shown = new ConcurrentHashMap<>();

    public RecipeBookCatalog(Plugin flux) {
        super(PacketListenerPriority.HIGH);
        this.flux = flux;
    }

    @Override
    public void show(Player player, List<RecipeBook.Entry> entries) {
        List<RecipeDisplayId> before = shown.get(player.getUniqueId());
        if (before != null && !before.isEmpty()) {
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, new WrapperPlayServerRecipeBookRemove(before));
        }
        List<WrapperPlayServerRecipeBookAdd.AddEntry> add = new ArrayList<>(entries.size());
        List<RecipeDisplayId> ids = new ArrayList<>(entries.size());
        for (RecipeBook.Entry e : entries) {
            add.add(convert(e, e.fresh()));
            ids.add(new RecipeDisplayId(BASE + e.id()));
        }
        shown.put(player.getUniqueId(), ids);
        if (!add.isEmpty()) PacketEvents.getAPI().getPlayerManager().sendPacket(player, new WrapperPlayServerRecipeBookAdd(add, false));
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
        if (event.getPacketType() != PacketType.Play.Server.RECIPE_BOOK_ADD || !RecipeBook.claimed()) return;
        UUID id = event.getUser().getUUID();
        if (id == null) return;
        WrapperPlayServerRecipeBookAdd w = new WrapperPlayServerRecipeBookAdd(event);
        List<WrapperPlayServerRecipeBookAdd.AddEntry> kept = new ArrayList<>();
        boolean ours = false;
        for (var e : w.getEntries()) {
            int display = e.getContents().getId().getId();
            if (display >= BASE) ours = true;
            if (display >= BASE || !crafting(e.getContents().getCategory())) kept.add(e);
        }
        // the whole book again (join, reload): the catalog goes in with it
        if (w.isReplace() && !ours) for (RecipeBook.Entry e : RecipeBook.sent(id)) kept.add(convert(e, false));
        if (kept.size() == w.getEntries().size() && !(w.isReplace() && !ours)) return;
        if (kept.isEmpty() && !w.isReplace()) {
            event.setCancelled(true);
            return;
        }
        w.setEntries(kept);
        event.markForReEncode(true);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.CRAFT_RECIPE_REQUEST || !RecipeBook.claimed()) return;
        WrapperPlayClientCraftRecipeRequest w = new WrapperPlayClientCraftRecipeRequest(event);
        Object recipe = w.getRecipe();
        if (!(recipe instanceof RecipeDisplayId display)) return;
        int id = display.getId();
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
        shown.remove(player);
        RecipeBook.forget(player);
    }
}

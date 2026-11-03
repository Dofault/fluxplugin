package fr.influencraft.fluxmigration.migrations;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.influencraft.flux.api.items.CustomItems;
import fr.influencraft.fluxcommerce.api.Listings;
import fr.influencraft.fluxmigration.FluxMigration;
import fr.influencraft.fluxmigration.Migration;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 2026-11-01, go-live of FluxCommerce 1.0.0 (its own auction house): the notes of the AuctionHouse plugin
 * ({@code plugins/AuctionHouse/data/notes.json}) become listings of FluxCommerce, with the money AuctionHouse still
 * owed; its bans ({@code data/bannedPlayers.yml}) are copied. Read from the files, AuctionHouse need not run.
 *
 * <ul>
 *   <li>direct sale: what is left goes on sale (or expired, to take back); money of a sold part not collected yet is
 *   paid to the seller, tax deducted;</li>
 *   <li>auction running: kept with its bids; AuctionHouse held the money of every bidder until the end, FluxCommerce
 *   only the highest one: the others get their bid back now;</li>
 *   <li>auction ended: the winner collects the item (WON), the seller gets the price if he had not collected it, the
 *   losers who had not taken their bid back get it;</li>
 *   <li>removed by staff: REMOVED (item to take back) or DELETED (item destroyed, AuctionHouse put a named dirt
 *   instead).</li>
 * </ul>
 * Every import is recorded by FluxCommerce under {@code auctionhouse:<note>[:what]}: nothing twice. Blacklist rules
 * are converted (material, name, lore, model, custom model data, exact custom item as its id) and listed in the
 * report, to copy by hand. Remove once production runs FluxCommerce 1.0.0 (id kept from FluxCommerce, merged into
 * FluxCommerce on 2026-11-01).
 */
public final class AuctionHouseToFluxAuctionHouse implements Migration {

    private final FluxMigration plugin;

    public AuctionHouseToFluxAuctionHouse(FluxMigration plugin) {
        this.plugin = plugin;
    }

    private File folder() {
        return new File(plugin.getDataFolder().getParentFile(), "AuctionHouse");
    }

    @Override
    public String id() {
        return "2026-11-01_auctionhouse_to_fluxauctionhouse";
    }

    @Override
    public String description() {
        return "AuctionHouse listings, bids and bans -> FluxCommerce";
    }

    @Override
    public boolean ready() {
        return new File(folder(), "data/notes.json").isFile() && Listings.available()
                && Listings.available();
    }

    @Override
    public String run() throws Exception {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File(folder(), "config.yml"));
        long setup = config.getLong("auction-setup-time", 0) * 1000;
        long saleTime = config.getLong("bin-auction-duration", 172800), auctionTime = config.getLong("bid-auction-duration", 86400);
        double tax = config.getDouble("tax", 0);
        long now = System.currentTimeMillis();
        int listings = 0, payments = 0, skipped = 0, unreadable = 0;
        JsonArray notes;
        try (FileReader r = new FileReader(new File(folder(), "data/notes.json"), StandardCharsets.UTF_8)) {
            notes = JsonParser.parseReader(r).getAsJsonArray();
        }
        for (JsonElement e : notes) {
            JsonObject n = e.getAsJsonObject();
            String id = text(n, "noteID");
            ItemStack item = decode(text(n, "itemData"));
            UUID seller = uuid(text(n, "playerUUID"));
            if (id == null || item == null || seller == null) {
                unreadable++;
                continue;
            }
            String sellerName = name(seller, text(n, "playerName"));
            boolean auction = n.has("isBIDAuction") && n.get("isBIDAuction").getAsBoolean();
            boolean sold = n.has("isSold") && n.get("isSold").getAsBoolean();
            int partial = n.has("partiallySoldAmountLeft") ? n.get("partiallySoldAmountLeft").getAsInt() : 0;
            double price = n.has("price") ? n.get("price").getAsDouble() : 0;
            long time = n.has("auctionTime") ? n.get("auctionTime").getAsLong() : 0;
            String admin = text(n, "adminMessage");
            long created = date(text(n, "dateCreated"), now);
            if (time == 0) time = auction ? auctionTime : saleTime;
            long start = created + setup, end = time < 0 ? created : start + time * 1000;
            int amount = item.getAmount();
            String source = "auctionhouse:" + id;
            String itemName = item.getType().name().toLowerCase(Locale.ROOT);

            List<Listings.ImportedBid> bids = new ArrayList<>();
            Map<UUID, Double> best = new HashMap<>();
            if (n.has("bidHistory") && n.get("bidHistory").isJsonArray()) {
                for (JsonElement b : n.getAsJsonArray("bidHistory")) {
                    JsonObject o = b.getAsJsonObject();
                    UUID bidder = uuid(text(o, "player"));
                    if (bidder == null) continue;
                    double amountBid = o.get("bid").getAsDouble();
                    bids.add(new Listings.ImportedBid(bidder, name(bidder, text(o, "playerName")), amountBid, date(text(o, "date"), created)));
                    best.merge(bidder, amountBid, Math::max);
                }
            }
            Set<UUID> claimed = new HashSet<>();
            if (n.has("claimedPlayers") && n.get("claimedPlayers").isJsonArray()) {
                for (JsonElement c : n.getAsJsonArray("claimedPlayers")) {
                    UUID u = uuid(c.getAsString());
                    if (u != null) claimed.add(u);
                }
            }
            UUID winner = bids.isEmpty() ? null : bids.getLast().bidder();

            if (admin != null) {
                // ended by staff: the item (or the notice) for the seller, every bid not taken back is given back
                boolean destroyed = item.getType() == Material.DIRT && item.hasItemMeta() && item.getItemMeta().hasDisplayName();
                for (Map.Entry<UUID, Double> b : best.entrySet()) {
                    if (!claimed.contains(b.getKey()) && Listings.importPayment(source + ":refund:" + b.getKey(), b.getKey(), b.getValue(), itemName)) payments++;
                }
                if (add(source, id, seller, sellerName, item, partial > 0 ? partial : amount, auction, price, created, start, created,
                        destroyed ? "DELETED" : "REMOVED", admin, List.of())) listings++;
                else skipped++;
                continue;
            }
            if (!auction) {
                // money of a sold part the seller did not collect yet (AuctionHouse paid it when he collected)
                if (sold) {
                    double soldPrice = partial == 0 ? price : price - price / amount * partial;
                    double net = Math.floor(soldPrice * 100 * (1 - tax)) / 100;
                    if (Listings.importPayment(source + ":sale", seller, net, itemName)) payments++;
                }
                int left = sold ? partial : amount;
                if (left <= 0) continue;
                ItemStack lot = item.clone();
                lot.setAmount(left);
                if (add(source, id, seller, sellerName, lot, left, false, price / amount * left, created, start, end,
                        now >= end ? "EXPIRED" : "ACTIVE", null, List.of())) listings++;
                else skipped++;
                continue;
            }
            // auction: AuctionHouse's price is the highest bid; the starting price is not kept, the lowest bid stands for it
            double startPrice = bids.isEmpty() ? price : bids.stream().mapToDouble(Listings.ImportedBid::amount).min().orElse(price);
            for (Map.Entry<UUID, Double> b : best.entrySet()) {
                if (b.getKey().equals(winner) || claimed.contains(b.getKey())) continue;
                if (Listings.importPayment(source + ":refund:" + b.getKey(), b.getKey(), b.getValue(), itemName)) payments++;
            }
            if (now < end) {
                if (add(source, id, seller, sellerName, item, amount, true, startPrice, created, start, end, "ACTIVE", null, bids)) listings++;
                else skipped++;
            } else if (winner == null) {
                if (add(source, id, seller, sellerName, item, amount, true, startPrice, created, start, end, "EXPIRED", null, List.of())) listings++;
                else skipped++;
            } else {
                if (!sold && Listings.importPayment(source + ":sale", seller, Math.floor(price * 100 * (1 - tax)) / 100, itemName)) payments++;
                if (!claimed.contains(winner)
                        && add(source, id, seller, sellerName, item, amount, true, startPrice, created, start, end, "WON", null, bids)) listings++;
            }
        }
        int bans = bans();
        return listings + " listing(s), " + payments + " payment(s), " + bans + " ban(s), " + skipped + " already there, "
                + unreadable + " unreadable" + blacklist();
    }

    private static boolean add(String source, String id, UUID seller, String sellerName, ItemStack item, int left, boolean auction,
                               double price, long created, long start, long end, String state, String reason, List<Listings.ImportedBid> bids) {
        return Listings.importListing(new Listings.Import(source, UUID.fromString(id), seller, sellerName, item, left, auction, price,
                created, start, end, state, reason, bids));
    }

    private int bans() {
        YamlConfiguration data = YamlConfiguration.loadConfiguration(new File(folder(), "data/bannedPlayers.yml"));
        ConfigurationSection section = data.getConfigurationSection("BannedPlayers");
        if (section == null) return 0;
        int n = 0;
        for (String key : section.getKeys(false)) {
            UUID id = uuid(key);
            Object until = section.get(key + ".Date");
            if (id == null || !(until instanceof Date d)) continue;
            if (Listings.importBan("auctionhouse:ban:" + key, id, section.getString(key + ".PlayerName"), d.getTime(), section.getString(key + ".Reason"))) n++;
        }
        return n;
    }

    /** Blacklist rules of AuctionHouse, written in FluxCommerce's format, to copy into its config by hand. */
    private String blacklist() {
        YamlConfiguration data = YamlConfiguration.loadConfiguration(new File(folder(), "data/blacklist.yml"));
        List<String> rules = new ArrayList<>();
        for (Map<?, ?> entry : data.getMapList("blacklist")) {
            Object type = entry.get("type"), key = entry.get("key");
            String rule = switch (String.valueOf(type)) {
                case "material" -> String.valueOf(key);
                case "name" -> "name:" + key;
                case "lore" -> "lore:" + key;
                case "item_model" -> "model:" + key;
                case "custom_model_data" -> "cmd:" + key;
                // an exact item: a custom item (ItemsAdder...) is banned by its id, a vanilla one cannot be told apart
                case "exact" -> key instanceof ItemStack item && CustomItems.id(item) != null ? CustomItems.id(item)
                        : "(exact " + (key instanceof ItemStack item ? item.getType().name() : "?") + ", not convertible)";
                default -> "(" + type + ", not convertible)";
            };
            rules.add(rule);
        }
        return rules.isEmpty() ? "" : "; blacklist rules to copy into FluxCommerce's config.yml (shared by all commerce): " + String.join(", ", rules);
    }

    private static String text(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    private static UUID uuid(String s) {
        try {
            return s == null ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Current name of the player (AuctionHouse kept the display name of the moment, rank prefix included). */
    private static String name(UUID id, String fallback) {
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n != null ? n : fallback == null ? "?" : ChatColor.stripColor(fallback);
    }

    /** Dates as Gson wrote them ("Oct 30, 2026, 8:12:21 PM", narrow spaces with recent Java). */
    private static long date(String s, long fallback) {
        if (s == null) return fallback;
        String t = s.replace(' ', ' ').replace(' ', ' ');
        for (DateFormat f : List.of(new SimpleDateFormat("MMM d, yyyy, h:mm:ss a", Locale.US), new SimpleDateFormat("MMM d, yyyy h:mm:ss a", Locale.US),
                DateFormat.getDateTimeInstance(DateFormat.DEFAULT, DateFormat.DEFAULT, Locale.US))) {
            try {
                return f.parse(t).getTime();
            } catch (ParseException ignored) {
                // next format
            }
        }
        return fallback;
    }

    /** Items as AuctionHouse wrote them: Bukkit object stream, Base64. */
    @SuppressWarnings("deprecation")
    private static ItemStack decode(String data) {
        if (data == null) return null;
        try (BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(Base64.getMimeDecoder().decode(data)))) {
            return (ItemStack) in.readObject();
        } catch (Exception e) {
            return null;
        }
    }
}

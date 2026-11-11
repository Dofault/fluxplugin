package fr.influencraft.fluxoffering.integration;

import fr.influencraft.flux.api.economy.Currency;
import fr.influencraft.flux.api.economy.Economy;
import fr.influencraft.flux.api.text.ItemSprites;
import fr.influencraft.fluxoffering.config.ConfigManager;
import fr.influencraft.fluxoffering.data.DataManager;
import fr.influencraft.fluxoffering.model.CollectionDefinition;
import fr.influencraft.fluxoffering.model.ItemKey;
import fr.influencraft.fluxoffering.model.PlayerData;
import fr.influencraft.fluxoffering.service.DepositService;
import fr.influencraft.fluxoffering.util.ProgressCalculator;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The offering of the player's rank in the sidebar (Typewriter): one icon per requirement not complete yet, orange
 * when the player carries enough to complete it; a complete requirement disappears. Placeholders
 * {@code %offrandes_sidebar_count%} (requirements left), {@code %offrandes_sidebar_progress%} (done/total) and
 * {@code %offrandes_sidebar_<line>%} (icons of a line, {@link #PER_LINE} per line). Cached one second: the sidebar
 * asks every tick.
 */
final class OfferingSidebar {

    static final int PER_LINE = 16, LINES = 2;
    private static final String READY = "#FFA500";
    /** BitBlock glyph of the resource pack (raw character: Typewriter does not resolve %img_...% here). */
    private static final String MONEY = "";

    private record Cached(long at, CollectionDefinition offering, List<String> icons) {
    }

    private final ConfigManager configs;
    private final DataManager data;
    private final DepositService deposits;
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

    OfferingSidebar(ConfigManager configs, DataManager data, DepositService deposits) {
        this.configs = configs;
        this.data = data;
        this.deposits = deposits;
    }

    String request(Player player, String params) {
        Cached c = cached(player);
        if (params.equals("count")) return String.valueOf(c.icons().size());
        if (params.equals("progress")) {
            if (c.offering() == null) return "";
            PlayerData d = data.get(player);
            return ProgressCalculator.completedTypeCount(d, c.offering()) + "/" + ProgressCalculator.totalTypesWithMoney(c.offering());
        }
        int line;
        try {
            line = Integer.parseInt(params);
        } catch (NumberFormatException e) {
            return null;
        }
        List<String> icons = c.icons();
        int from = (line - 1) * PER_LINE;
        if (line < 1 || from >= icons.size()) return "";
        int to = Math.min(icons.size(), from + PER_LINE);
        // the last line says how many more do not fit
        if (line == LINES && icons.size() > to) {
            return String.join("", icons.subList(from, to - 1)) + " <white>+" + (icons.size() - to + 1) + "</white>";
        }
        return String.join("", icons.subList(from, to));
    }

    private Cached cached(Player player) {
        long now = System.currentTimeMillis();
        Cached c = cache.get(player.getUniqueId());
        if (c != null && now - c.at() < 1000) return c;
        c = build(player, now);
        cache.put(player.getUniqueId(), c);
        return c;
    }

    private Cached build(Player player, long now) {
        // the offering of the player's rank, as /offrande opens it (track "standard": a staff group never hides it)
        String group = fr.influencraft.flux.api.text.Texts.placeholders("%luckperms_current_group_on_track_standard%", player);
        CollectionDefinition offering = group == null || group.isBlank() || group.contains("%") ? null : configs.getCollection(group);
        if (offering == null) return new Cached(now, null, List.of());
        PlayerData d = data.get(player);
        List<String> icons = new ArrayList<>();
        for (Map.Entry<ItemKey, Integer> e : offering.items().entrySet()) {
            ItemKey key = e.getKey();
            int left = e.getValue() - d.getDeposited(offering.id(), key);
            if (left <= 0) continue;
            String icon = ItemSprites.tag(key.isEnchantment() ? org.bukkit.Material.ENCHANTED_BOOK
                    : key.material() != null ? key.material() : key.toDisplayStack(1).getType());
            if (icon.isEmpty()) icon = ItemSprites.tag(org.bukkit.Material.PAPER);
            icons.add(deposits.available(player, key) >= left ? "<" + READY + ">" + icon + "</" + READY + ">" : icon);
        }
        if (offering.hasMoneyRequirement()) {
            long left = offering.money() - d.getMoneyDeposited(offering.id());
            if (left > 0) {
                Currency money = Economy.main();
                boolean ready = money != null && money.balance(player) >= left;
                icons.add("<" + (ready ? READY : "white") + ">" + MONEY + "</" + (ready ? READY : "white") + ">");
            }
        }
        return new Cached(now, offering, icons);
    }

    void forget(UUID uuid) {
        cache.remove(uuid);
    }
}

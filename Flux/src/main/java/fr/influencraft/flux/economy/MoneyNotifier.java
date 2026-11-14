package fr.influencraft.flux.economy;

import fr.influencraft.flux.api.economy.Currency;
import fr.influencraft.flux.api.economy.Economy;
import fr.influencraft.flux.api.economy.Ledger;
import fr.influencraft.flux.api.economy.Origin;
import fr.influencraft.flux.api.text.Messages;
import fr.influencraft.flux.api.text.Style;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.Format;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.function.Supplier;

/**
 * The standard gain / loss message of the suite (formerly written by hand in each plugin, see
 * docs/notifications/bitblock.md): words from Flux's lang ({@code money.message}, {@code money.verbs.*},
 * {@code money.names.*}), look from style.yml ({@code money}: per-currency colour and icon, amount, arrows, origin).
 * <pre>
 *   &amp;r&amp;f%img_bitblock% &amp;#B376E5Tu as gagné &amp;f140 &amp;#B376E5BitBlock &amp;#2ECF7B▲&amp;7 [ꜱᴀʟᴀɪʀᴇ]
 * </pre>
 */
public final class MoneyNotifier implements Economy.Notifier {

    private final Supplier<Messages> messages;
    private volatile ConfigurationSection style;

    public MoneyNotifier(Supplier<Messages> messages) {
        this.messages = messages;
    }

    /** style.yml {@code money} section (null = built-in look). */
    public void configure(ConfigurationSection money) {
        style = money;
    }

    private String look(String key, String fallback) {
        ConfigurationSection s = style;
        return s == null ? fallback : s.getString(key, fallback);
    }

    /** Colour and icon of a currency ({@code money.currencies.<id>}). */
    public String color(String currency) {
        return ofCurrency(currency, "color", look("color", "&#B376E5"));
    }

    public String icon(String currency) {
        return ofCurrency(currency, "icon", "");
    }

    /** A look of a currency, read under its id, else under a former id of it ({@code vault} for the Vault one). */
    private String ofCurrency(String currency, String key, String fallback) {
        String found = look("currencies." + currency + "." + key, null);
        if (found != null) return found;
        for (String former : Economy.aliasesOf(currency)) {
            found = look("currencies." + former + "." + key, null);
            if (found != null) return found;
        }
        return fallback;
    }

    /** Amount alone (no icon): whole numbers without decimals. */
    static String number(double amount) {
        double a = Math.abs(amount);
        return Math.abs(a - Math.rint(a)) < 0.005 ? Format.number(Math.rint(a)) : Format.decimal(a);
    }

    /** Name of a currency ({@code money.names.<id>}). */
    public String name(String currency) {
        Messages m = messages.get();
        if (m == null) return currency;
        String n = m.raw("money.names." + currency);
        for (String former : Economy.aliasesOf(currency)) {
            if (!n.isEmpty()) break;
            n = m.raw("money.names." + former);
        }
        return n.isEmpty() ? currency : n;
    }

    /** Word of a verb ({@code money.verbs.<verb>}), by the sign when the origin names none. */
    String verb(Origin origin, boolean gain) {
        Messages m = messages.get();
        String key = origin != null && origin.verb() != null ? origin.verb() : gain ? "gained" : "lost";
        String v = m == null ? "" : m.raw("money.verbs." + key);
        if (v.isEmpty() && m != null) v = m.raw("money.verbs." + (gain ? "gained" : "lost"));
        return v.isEmpty() ? key : v;
    }

    /** The full line, without sending it. */
    public String line(Currency currency, double signedAmount, Origin origin) {
        Messages m = messages.get();
        String template = m == null ? "" : m.raw("money.message");
        if (template.isEmpty()) template = "{icon} {color}{verb} {amount} {color}{name} {arrow}{origin}";
        return line(template, currency, signedAmount, origin);
    }

    private String line(String template, Currency currency, double signedAmount, Origin origin) {
        boolean gain = signedAmount >= 0;
        String color = color(currency.id());
        String originPart = origin == null || origin.unknown() ? ""
                : look("origin", "&7 [<smallcaps>{origin}</smallcaps>]").replace("{origin}", Ledger.label(origin));
        String text = template
                .replace("{icon}", icon(currency.id()))
                .replace("{verb}", verb(origin, gain))
                .replace("{amount}", look("amount", "&f{amount}").replace("{amount}", number(signedAmount)))
                .replace("{name}", name(currency.id()))
                .replace("{arrow}", gain ? look("arrow-gain", "&#2ECF7B▲") : look("arrow-loss", "&#FF5C5C▼"))
                .replace("{origin}", originPart)
                .replace("{color}", color);
        return Style.applyTags(text);
    }

    /**
     * A transfer between players: the standard line with the other player named ({@code money.message-with},
     * {@code {link}} = {@code money.links.to} or {@code .from}), sent when the player is online.
     */
    void notifyTransfer(OfflinePlayer player, Currency currency, double signedAmount, Origin origin, String other) {
        Player online = player.getPlayer();
        Messages m = messages.get();
        if (online == null || m == null) return;
        String template = m.raw("money.message-with");
        if (template.isEmpty()) {
            notify(player, currency, signedAmount, origin);
            return;
        }
        String line = line(template, currency, signedAmount, origin)
                .replace("{link}", m.raw(signedAmount >= 0 ? "money.links.from" : "money.links.to")).replace("{player}", other);
        online.sendMessage(Texts.parse(line, online));
    }

    @Override
    public void notify(OfflinePlayer player, Currency currency, double signedAmount, Origin origin) {
        Player online = player.getPlayer();
        if (online == null) return;
        online.sendMessage(Texts.parse(line(currency, signedAmount, origin), online));
    }
}

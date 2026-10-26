package fr.influencraft.influoffrande.wiki;

import fr.influencraft.influoffrande.model.ItemKey;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds the wiki page URL of a required item, for the "Voir le wiki de l'objet" fallback
 * shown to a player who owns none of it.
 * <p>
 * The configured wiki (fr.minecraft.wiki) keeps redirects from the English page titles to
 * their French articles ("Stone" -&gt; "Roche", "Rotten Flesh" -&gt; "Chair putrefiee"), so the
 * URL is derived from the vanilla Material / Enchantment id rather than from a hand written
 * French name: nothing to maintain when an item is added to a collection. Only the handful of
 * ids whose page title is not their prettified name need an entry in {@code wiki.titles}.
 * <p>
 * ItemsAdder items (CustomFishing species...) have no vanilla wiki page: they return null,
 * which simply hides the button, unless an explicit title is configured for their key.
 */
public final class WikiLinks {

    /** Lower case in a page title, as the wiki writes them ("Totem of Undying"). */
    private static final Set<String> MINOR_WORDS = Set.of("of", "the", "and");

    private final boolean enabled;
    private final String urlFormat;
    private final Map<String, String> titleOverrides;

    /**
     * @param titleOverrides keys are upper case item keys as written in collections.yml
     *                       ("SLIME_BALL", "ENCHANT:SHARPNESS", "CUSTOMFISHING:COMMON_CARP_FISH").
     */
    public WikiLinks(boolean enabled, String urlFormat, Map<String, String> titleOverrides) {
        this.enabled = enabled;
        this.urlFormat = urlFormat;
        this.titleOverrides = titleOverrides;
    }

    /**
     * @return the wiki URL for this requirement, or null if none can be built (feature
     * disabled, or an item with no vanilla page and no configured title).
     */
    public String urlFor(ItemKey key) {
        if (!enabled || key == null) {
            return null;
        }
        String title = titleOverrides.get(key.raw().toUpperCase(Locale.ROOT));
        if (title == null) {
            title = defaultTitle(key);
        }
        if (title == null || title.isBlank()) {
            return null;
        }
        return urlFormat.replace("{title}", encode(title));
    }

    private static String defaultTitle(ItemKey key) {
        if (key.isEnchantment()) {
            Enchantment enchantment = key.enchantment();
            NamespacedKey nsKey = enchantment.getKey();
            // A non-vanilla enchantment (AdvancedEnchantments, EcoEnchants) has no page there.
            return NamespacedKey.MINECRAFT.equals(nsKey.getNamespace()) ? prettify(nsKey.getKey()) : null;
        }
        if (key.isItemsAdder()) {
            return null;
        }
        return prettify(key.material().name());
    }

    /** "ROTTEN_FLESH" -&gt; "Rotten Flesh", "totem_of_undying" -&gt; "Totem of Undying". */
    private static String prettify(String id) {
        String[] parts = id.toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            if (i > 0 && MINOR_WORDS.contains(part)) {
                sb.append(part);
            } else {
                sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        return sb.toString();
    }

    /**
     * MediaWiki page titles travel in the URL path with spaces written as underscores;
     * anything outside the safe set is percent encoded so an accented or exotic title
     * configured by hand still produces a valid link.
     */
    private static String encode(String title) {
        StringBuilder sb = new StringBuilder();
        for (byte b : title.replace(' ', '_').getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '.' || c == '\'' || c == '(' || c == ')') {
                sb.append(c);
            } else {
                sb.append('%').append(String.format("%02X", b & 0xFF));
            }
        }
        return sb.toString();
    }
}

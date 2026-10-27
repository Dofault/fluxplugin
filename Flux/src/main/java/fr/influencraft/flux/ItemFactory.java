package fr.influencraft.flux;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import fr.influencraft.flux.menu.MenuButton;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Fabrique les objets affichés par les boutons. Matériaux acceptés :
 * <ul>
 *   <li>{@code itemsadder:<namespace>:<id>} ou {@code itemsadder:<id>} (tout objet ItemsAdder :
 *       items, blocs, meubles), et l'ancienne forme DeluxeMenus {@code itemsadder-<id>} ;</li>
 *   <li>un matériau vanilla : {@code DIAMOND}, {@code minecraft:diamond} ;</li>
 *   <li>{@code head:<texture base64>}, {@code player-head} (tête du joueur qui regarde) et
 *       {@code hdb:<id>} (HeadDatabase, tête simple s'il est absent) ;</li>
 *   <li>{@code air} : case vide (utile pour masquer un bouton de plus faible priorité).</li>
 * </ul>
 * Chaque objet porte la marque {@link #BUTTON_KEY} : un objet marqué trouvé dans un vrai
 * inventaire est supprimé (filet de sécurité anti-duplication).
 *
 * <p>{@code muteTooltip} (paramètre de {@link #build}) force l'infobulle coupée quelle que
 * soit la config du bouton : utilisé pendant une animation de page, pour que plus aucune
 * infobulle ne s'affiche dès le clic, avant même le premier cadre (rien n'y est cliquable).</p>
 */
public final class ItemFactory {

    public static NamespacedKey BUTTON_KEY;

    private final Consumer<String> warn;
    private final Map<String, String> iaIds = new ConcurrentHashMap<>();
    private final Map<String, Boolean> warned = new ConcurrentHashMap<>();

    private static ItemFactory instance;

    /** The factory of the running Flux, for the public {@link fr.influencraft.flux.api.inventory.Items}. */
    public static ItemFactory instance() {
        return instance;
    }

    public ItemFactory(Flux plugin) {
        instance = this;
        // namespace of the former plugin name (FluxMenu), so stray buttons from older versions are still cleaned up
        BUTTON_KEY = new NamespacedKey("fluxmenu", "button");
        this.warn = plugin.getLogger()::warning;
    }

    public void clearCache() {
        iaIds.clear();
        warned.clear();
    }

    /** null si le bouton est vide (air) ou le matériau introuvable. */
    public ItemStack build(MenuButton b, Player viewer, boolean muteTooltip) {
        // Le matériau peut contenir un placeholder (ex : ressource de la mission en cours).
        String material = b.material().indexOf('%') >= 0 ? Text.placeholders(viewer, b.material()) : b.material();
        boolean fromEntry = material.startsWith(fr.influencraft.flux.menu.ListRenderer.ENTRY_MATERIAL);
        boolean fromSource = fromEntry || material.regionMatches(true, 0, "source:", 0, 7);
        ItemStack item;
        if (fromEntry) {
            // element of a list: its own item (copy)
            ItemStack own = fr.influencraft.flux.menu.ListRenderer.entryItem(material.substring(fr.influencraft.flux.menu.ListRenderer.ENTRY_MATERIAL.length()));
            item = own == null ? null : own.clone();
        } else {
            item = fromSource ? fromSource(material, viewer) : base(material, viewer);
        }
        if (item == null || item.getType().isAir()) return null;

        // Objet d'une source sans « amount » : sa propre quantité est gardée.
        int amount = fromSource && b.amount() == null ? item.getAmount() : amount(b.amount(), viewer);
        if (amount > item.getMaxStackSize()) {
            // Pile limitée à 64 (ou 16, 1) par défaut : on la monte pour afficher jusqu'à 99.
            ItemMeta stack = item.getItemMeta();
            if (stack != null) {
                stack.setMaxStackSize(Math.min(99, amount));
                item.setItemMeta(stack);
            }
        }
        item.setAmount(amount);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        // items built by code (list entries) get the same line width as the menu files
        if (fromEntry && meta.lore() != null) {
            List<Component> own = meta.lore();
            List<Component> wrapped = Text.wrapLore(own);
            if (wrapped != own) meta.lore(wrapped);
        }
        if (b.name() != null) meta.displayName(name(viewer, b.name()));
        if (!b.lore().isEmpty()) {
            List<Component> lore = new ArrayList<>(b.lore().size());
            // Objet d'une source : le lore du bouton vient sous la description de l'objet.
            if (fromSource && meta.lore() != null) lore.addAll(meta.lore());
            for (String rawLine : b.lore()) {
              // long lines are wrapped to the configured width (lore.max-line-length), after placeholders
              String resolvedLine = rawLine.indexOf('%') >= 0 ? Text.placeholders(viewer, rawLine) : rawLine;
              List<String> pieces = Text.wrap(resolvedLine);
              for (String line : pieces.size() == 1 ? List.of(rawLine) : pieces) {
                Component rendered = Text.line(viewer, line);
                // Une ligne qui contient un placeholder et qui ne reste, une fois résolue et les
                // couleurs retirées, que du vide (rien à afficher) est sautée : utile pour les
                // placeholders dynamiques en nombre variable (ex : récompenses EcoBattlepass,
                // qui renvoient "" au-delà du nombre réel). Une ligne "" littérale (sans
                // placeholder, espaceur volontaire) n'est jamais concernée.
                if (line.indexOf('%') >= 0 && PlainTextComponentSerializer.plainText().serialize(rendered).isBlank()) {
                    continue;
                }
                // Idem quand tous ses placeholders sont vides, même avec du texte autour (ex :
                // « │ %influpet_slot_1_effects_5% » au-delà des lignes réelles de l'effet).
                if (allPlaceholdersEmpty(viewer, line)) {
                    continue;
                }
                lore.add(rendered);
              }
            }
            meta.lore(lore);
        }
        if (b.glow()) meta.setEnchantmentGlintOverride(true);
        if (b.customModelData() != null) setCustomModelData(meta, b.customModelData());
        if (b.itemModel() != null) {
            NamespacedKey key = NamespacedKey.fromString(b.itemModel().toLowerCase(Locale.ROOT));
            if (key != null) meta.setItemModel(key);
        }
        if (!b.flags().isEmpty()) meta.addItemFlags(b.flags().toArray(new ItemFlag[0]));
        if (b.hideTooltip() || muteTooltip) meta.setHideTooltip(true);
        meta.getPersistentDataContainer().set(BUTTON_KEY, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Nom du bouton. Un nom (après placeholders) contenant « lang:<clé> » affiche le nom traduit
     * de Minecraft dans la langue du joueur (ex : « Minerai de fer »), en or comme les titres.
     */
    private static Component name(Player viewer, String raw) {
        String resolved = Text.placeholders(viewer, raw);
        int at = resolved.indexOf("lang:");
        if (at < 0) return Text.line(viewer, raw);
        String key = resolved.substring(at + 5).trim().split("\\s")[0];
        return Component.translatable(key).color(net.kyori.adventure.text.format.TextColor.color(0xFFD94D))
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false);
    }

    @SuppressWarnings("deprecation")
    private static void setCustomModelData(ItemMeta meta, int value) {
        meta.setCustomModelData(value);
    }

    public static boolean isButton(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(BUTTON_KEY, PersistentDataType.BYTE);
    }

    /** {@code source:<id>:<argument>} : objet fourni par un autre plugin ({@link fr.influencraft.flux.api.FluxApi}). */
    private ItemStack fromSource(String raw, Player viewer) {
        String rest = raw.trim().substring(7);
        int sep = rest.indexOf(':');
        String id = sep < 0 ? rest : rest.substring(0, sep);
        String arg = sep < 0 ? "" : rest.substring(sep + 1);
        fr.influencraft.flux.api.ItemSource source = fr.influencraft.flux.api.FluxApi.itemSource(id);
        if (source == null) {
            warnOnce("source:" + id, "source d'objets inconnue « " + id + " » (plugin absent ?), case laissée vide");
            return null;
        }
        try {
            ItemStack item = source.item(viewer, arg);
            return item == null ? null : item.clone();
        } catch (RuntimeException e) {
            warnOnce(raw, "source « " + id + " » en erreur pour « " + arg + " » : " + e);
            return null;
        }
    }

    public ItemStack base(String raw, Player viewer) {
        String m = raw.trim();
        String lower = m.toLowerCase(Locale.ROOT);
        if (lower.equals("air")) return null;

        if (lower.startsWith("itemsadder:") || lower.startsWith("itemsadder-") || lower.startsWith("ia:")) {
            String id = m.substring(lower.startsWith("ia:") ? 3 : 11);
            return itemsAdder(id, raw);
        }
        if (lower.startsWith("hdb:") || lower.startsWith("hdb-")) {
            return headDatabase(m.substring(4).trim(), raw);
        }
        if (lower.equals("player-head")) {
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            if (viewer != null && head.getItemMeta() instanceof SkullMeta skull) {
                skull.setPlayerProfile(viewer.getPlayerProfile());
                head.setItemMeta(skull);
            }
            return head;
        }
        if (lower.startsWith("head:")) {
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            if (head.getItemMeta() instanceof SkullMeta skull) {
                String texture = m.substring(5).trim();
                PlayerProfile profile = Bukkit.createProfile(UUID.nameUUIDFromBytes(texture.getBytes()), null);
                profile.setProperty(new ProfileProperty("textures", texture));
                skull.setPlayerProfile(profile);
                head.setItemMeta(skull);
            }
            return head;
        }
        String name = lower.startsWith("minecraft:") ? m.substring(10) : m;
        Material material = Material.matchMaterial(name);
        if (material == null || !material.isItem()) {
            warnOnce(raw, "matériau inconnu « " + raw + " », bouton remplacé par une barrière");
            return new ItemStack(Material.BARRIER);
        }
        return new ItemStack(material);
    }

    private ItemStack itemsAdder(String id, String raw) {
        if (!Text.hasItemsAdder()) {
            warnOnce(raw, "ItemsAdder absent, « " + raw + " » remplacé par une barrière");
            return new ItemStack(Material.BARRIER);
        }
        String namespaced = iaIds.computeIfAbsent(id, ItemFactory::resolveIaId);
        dev.lone.itemsadder.api.CustomStack stack = namespaced.isEmpty() ? null
                : dev.lone.itemsadder.api.CustomStack.getInstance(namespaced);
        if (stack == null) {
            // ItemsAdder peut ne pas avoir fini de charger : on ne garde pas l'échec en cache.
            iaIds.remove(id);
            warnOnce(raw, "objet ItemsAdder introuvable « " + raw + " », remplacé par une barrière");
            return new ItemStack(Material.BARRIER);
        }
        return stack.getItemStack().clone();
    }

    /** Tête HeadDatabase par réflexion (pas de dépendance de compilation) ; tête simple sinon. */
    private ItemStack headDatabase(String id, String raw) {
        if (Bukkit.getPluginManager().isPluginEnabled("HeadDatabase")) {
            try {
                Class<?> api = Class.forName("me.arcaniax.hdb.api.HeadDatabaseAPI");
                Object instance = api.getConstructor().newInstance();
                Object head = api.getMethod("getItemHead", String.class).invoke(instance, id);
                if (head instanceof ItemStack item) return item.clone();
            } catch (ReflectiveOperationException | RuntimeException e) {
                warnOnce(raw, "HeadDatabase : tête « " + id + " » illisible (" + e + ")");
            }
        } else {
            warnOnce(raw, "HeadDatabase absent, « " + raw + " » remplacé par une tête simple");
        }
        return new ItemStack(Material.PLAYER_HEAD);
    }

    /** « ns:id » tel quel, sinon on cherche l'unique « *:id » du registre ItemsAdder. */
    @SuppressWarnings("unchecked")
    private static String resolveIaId(String id) {
        if (id.contains(":")) return id;
        String suffix = ":" + id;
        String found = "";
        for (Object o : (Iterable<Object>) dev.lone.itemsadder.api.CustomStack.getNamespacedIdsInRegistry()) {
            String full = String.valueOf(o);
            if (full.endsWith(suffix)) {
                if (!found.isEmpty()) return found; // homonymes : premier trouvé, préciser le namespace
                found = full;
            }
        }
        return found;
    }

    private static int amount(String raw, Player viewer) {
        if (raw == null || raw.isBlank()) return 1;
        String resolved = Text.placeholders(viewer, raw).trim();
        try {
            return Math.max(1, Math.min(99, (int) Math.floor(Double.parseDouble(resolved.replace(',', '.')))));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private void warnOnce(String key, String message) {
        if (warned.putIfAbsent(key, Boolean.TRUE) == null) warn.accept(message);
    }

    private static final java.util.regex.Pattern PLACEHOLDER = java.util.regex.Pattern.compile("%[^%\\s]+%");

    /** La ligne contient au moins un placeholder et chacun, résolu et sans couleurs, est vide. */
    private static boolean allPlaceholdersEmpty(Player viewer, String line) {
        java.util.regex.Matcher m = PLACEHOLDER.matcher(line);
        boolean any = false;
        while (m.find()) {
            any = true;
            if (!PlainTextComponentSerializer.plainText().serialize(Text.line(viewer, m.group())).isBlank()) return false;
        }
        return any;
    }
}

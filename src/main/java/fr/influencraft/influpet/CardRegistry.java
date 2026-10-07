package fr.influencraft.influpet;

import dev.lone.itemsadder.api.CustomStack;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Raretés, cartes, familiers et boosters lus dans config.yml (et réécrits par le menu d'édition).
 * Le contenu d'un booster est une liste de récompenses : cartes ("carte:<id>") et familiers
 * EcoPets ("familier:<id>"). Liste vide = toutes les cartes et tous les familiers activés.
 */
public final class CardRegistry {

    public record Rarity(String id, int order, String tag) {
    }

    /** pet : familier nourri par cette carte (clic droit), ou null. */
    public record Card(String id, int number, String itemId, String name, String series, Rarity rarity,
                       List<String> lore, String pet) {
    }

    /** Familier obtenable dans les boosters. */
    public record PetEntry(String id, Rarity rarity, boolean enabled) {
    }

    /**
     * petChance : probabilité (0-1) qu'un tirage soit un familier plutôt qu'une carte.
     * petRarityWeights : poids des raretés pour les familiers (défaut : ceux des cartes).
     */
    public record Booster(String id, String itemId, String name, List<String> lore, int cards,
                          Map<String, Integer> rarityWeights, List<String> contents,
                          double petChance, Map<String, Integer> petRarityWeights) {

        public Map<String, Integer> weights(boolean pets) {
            return pets ? petRarityWeights : rarityWeights;
        }
    }

    /** Une récompense tirée : carte ou familier. */
    public sealed interface Reward permits CardReward, PetReward {
        String key();

        Rarity rarity();

        int order();
    }

    public record CardReward(Card card) implements Reward {
        public String key() {
            return "carte:" + card.id();
        }

        public Rarity rarity() {
            return card.rarity();
        }

        public int order() {
            return card.number();
        }
    }

    /**
     * duplicatePlush : un doublon de ce familier peut donner sa peluche (série complète). Faux pour les
     * boosters « duplicate-plush: false » (boosters de palier) : seule l'adoption qui complète une série en donne.
     */
    public record PetReward(PetEntry pet, boolean duplicatePlush) implements Reward {
        public PetReward(PetEntry pet) {
            this(pet, true);
        }

        public String key() {
            return "familier:" + pet.id();
        }

        public Rarity rarity() {
            return pet.rarity();
        }

        public int order() {
            return 100_000;
        }
    }

    public enum DuplicatePetMode { REROLL, XP }

    private final InfluPet plugin;
    private final Map<String, Rarity> rarities = new LinkedHashMap<>();
    private final Map<String, Card> cards = new LinkedHashMap<>();
    private final Map<String, PetEntry> pets = new LinkedHashMap<>();
    private final Map<String, Booster> boosters = new LinkedHashMap<>();
    private DuplicatePetMode duplicateMode = DuplicatePetMode.REROLL;
    /** Objets déjà construits (clé "carte:<id>" / "booster:<id>"), vidé à chaque rechargement. */
    private final Map<String, ItemStack> itemCache = new java.util.HashMap<>();
    /** Empreinte de la configuration d'affichage, posée sur chaque objet (mise à jour des anciens). */
    private final Map<String, Integer> versions = new java.util.HashMap<>();
    private double duplicateXp = 0;

    public CardRegistry(InfluPet plugin) {
        this.plugin = plugin;
    }

    public static String key(String id) {
        return id.toLowerCase(Locale.ROOT);
    }

    public void load() {
        rarities.clear();
        itemCache.clear();
        versions.clear();
        cards.clear();
        pets.clear();
        boosters.clear();
        Texts.clearCache();
        FileConfiguration config = plugin.getConfig();

        ConfigurationSection rs = config.getConfigurationSection("rarities");
        if (rs != null) {
            int order = 0;
            for (String id : rs.getKeys(false)) {
                rarities.put(key(id), new Rarity(key(id), order++, rs.getString(id + ".tag", id)));
            }
        }

        ConfigurationSection cs = config.getConfigurationSection("cards");
        if (cs != null) {
            int number = 1;
            for (String id : cs.getKeys(false)) {
                Rarity rarity = rarities.get(key(cs.getString(id + ".rarity", "")));
                if (rarity == null) {
                    plugin.getLogger().warning("Carte " + id + " : rareté inconnue, carte ignorée.");
                    continue;
                }
                String pet = cs.getString(id + ".pet", "");
                cards.put(key(id), new Card(key(id), number++, cs.getString(id + ".item", ""),
                        cs.getString(id + ".name", id), cs.getString(id + ".series", ""), rarity,
                        cs.getStringList(id + ".lore"), pet == null || pet.isBlank() ? null : key(pet)));
            }
        }

        ConfigurationSection ps = config.getConfigurationSection("pets.list");
        if (ps != null) {
            for (String id : ps.getKeys(false)) {
                Rarity rarity = rarities.get(key(ps.getString(id + ".rarity", "")));
                if (rarity == null) {
                    plugin.getLogger().warning("Familier " + id + " : rareté inconnue, ignoré.");
                    continue;
                }
                pets.put(key(id), new PetEntry(key(id), rarity, ps.getBoolean(id + ".enabled", true)));
            }
        }
        String mode = config.getString("pets.duplicate", "reroll");
        if (mode != null && mode.toLowerCase(Locale.ROOT).startsWith("xp")) {
            duplicateMode = DuplicatePetMode.XP;
        } else {
            duplicateMode = DuplicatePetMode.REROLL;
        }
        duplicateXp = Math.max(0, config.getDouble("pets.duplicate-xp", 0));

        ConfigurationSection bs = config.getConfigurationSection("boosters");
        if (bs != null) {
            for (String id : bs.getKeys(false)) {
                Map<String, Integer> weights = new LinkedHashMap<>();
                ConfigurationSection ws = bs.getConfigurationSection(id + ".rarity-weights");
                for (String r : rarities.keySet()) {
                    weights.put(r, ws == null ? 0 : Math.max(0, ws.getInt(r, 0)));
                }
                Map<String, Integer> petWeights = new LinkedHashMap<>();
                ConfigurationSection pw = bs.getConfigurationSection(id + ".pet-rarity-weights");
                for (String r : rarities.keySet()) {
                    petWeights.put(r, pw == null ? weights.get(r) : Math.max(0, pw.getInt(r, 0)));
                }
                double petChance = Math.max(0, Math.min(1, bs.getDouble(id + ".pet-chance", 0) / 100.0));
                boosters.put(key(id), new Booster(key(id), bs.getString(id + ".item", ""),
                        bs.getString(id + ".name", id), bs.getStringList(id + ".lore"),
                        Math.max(1, bs.getInt(id + ".cards", 5)), weights,
                        bs.getStringList(id + ".contents").stream().map(CardRegistry::key).toList(),
                        petChance, petWeights));
            }
        }
        plugin.getLogger().info(cards.size() + " cartes, " + pets.size() + " familiers, "
                + boosters.size() + " boosters chargés.");
        plugin.refreshOnlineItems();
    }

    // ---------------------------------------------------------------- accès

    public List<Rarity> rarities() {
        return List.copyOf(rarities.values());
    }

    public Rarity rarity(String id) {
        return id == null ? null : rarities.get(key(id));
    }

    public Collection<Card> cards() {
        return Collections.unmodifiableCollection(cards.values());
    }

    public Collection<PetEntry> pets() {
        return Collections.unmodifiableCollection(pets.values());
    }

    public Collection<Booster> boosters() {
        return Collections.unmodifiableCollection(boosters.values());
    }

    public Card card(String id) {
        return id == null ? null : cards.get(key(id));
    }

    public PetEntry pet(String id) {
        return id == null ? null : pets.get(key(id));
    }

    /** Série d'un familier = série de la carte qui lui est liée (null si aucune carte). */
    public String series(PetEntry pet) {
        for (Card c : cards.values()) {
            if (pet.id().equals(c.pet())) {
                return c.series();
            }
        }
        return null;
    }

    /** Carte liée au familier (celle qui le nourrit), null si aucune. */
    public Card cardOf(PetEntry pet) {
        for (Card c : cards.values()) {
            if (pet.id().equals(c.pet())) {
                return c;
            }
        }
        return null;
    }

    /** Familiers de la même série (cartes de cette série liées à un familier connu), lui compris. */
    public List<PetEntry> seriesPets(PetEntry pet) {
        String series = series(pet);
        List<PetEntry> result = new ArrayList<>();
        if (series == null) {
            result.add(pet);
            return result;
        }
        for (Card c : cards.values()) {
            PetEntry p = c.pet() == null ? null : pets.get(c.pet());
            if (series.equals(c.series()) && p != null && !result.contains(p)) {
                result.add(p);
            }
        }
        return result;
    }

    public Booster booster(String id) {
        return id == null ? null : boosters.get(key(id));
    }

    public DuplicatePetMode duplicateMode() {
        return duplicateMode;
    }

    public double duplicateXp() {
        return duplicateXp;
    }

    // ---------------------------------------------------------------- affichage

    public String displayName(Card card) {
        return plugin.getConfig().getString("card-format.name", "%tag% %name%")
                .replace("%tag%", card.rarity().tag())
                .replace("%name%", card.name());
    }

    public String displayName(PetEntry pet) {
        PetHook.PetInfo info = PetHook.available() ? PetHook.pet(pet.id()) : null;
        String name = info == null ? pet.id() : info.name();
        return plugin.getConfig().getString("pets.name-format", "%tag% %name%")
                .replace("%tag%", pet.rarity().tag())
                .replace("%name%", name);
    }

    public String displayName(Reward reward) {
        return switch (reward) {
            case CardReward c -> displayName(c.card());
            case PetReward p -> displayName(p.pet());
        };
    }

    private String fillCard(String line, Card card) {
        return line.replace("%number%", String.format("%03d", card.number()))
                .replace("%series%", card.series())
                .replace("%tag%", card.rarity().tag())
                .replace("%name%", card.name());
    }

    /** Texture ItemsAdder (namespace:id), papier nu si l'item n'existe pas. */
    public ItemStack base(String itemsAdderId, Material fallback) {
        if (itemsAdderId != null && !itemsAdderId.isBlank()
                && Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) {
            CustomStack stack = CustomStack.getInstance(itemsAdderId);
            if (stack != null) {
                return stack.getItemStack().clone();
            }
        }
        return new ItemStack(fallback);
    }

    /**
     * Peluche du familier (meuble ItemsAdder) : pets.list.<id>.plush, influcards:peluche_<id> par
     * défaut, vide = pas de peluche. Null si l'objet n'existe pas.
     */
    public ItemStack plushItem(PetEntry pet) {
        String id = plugin.getConfig().getString("pets.list." + pet.id() + ".plush", "influcards:peluche_" + pet.id());
        if (id == null || id.isBlank() || !Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) {
            return null;
        }
        CustomStack stack = CustomStack.getInstance(id);
        return stack == null ? null : stack.getItemStack().clone();
    }

    /** XP donnée au familier par une carte de cette rareté. */
    public double cardXp(Rarity rarity) {
        return Math.max(0, plugin.getConfig().getDouble("card-xp." + rarity.id(), 0));
    }

    /** Empreinte de tout ce qui change l'apparence d'une carte (config de la carte, format, raretés). */
    public int version(Card card) {
        return versions.computeIfAbsent("carte:" + card.id(), k -> java.util.Objects.hash(card,
                plugin.getConfig().getString("card-format.name"),
                plugin.getConfig().getStringList("card-format.lore-header"),
                plugin.getConfig().getStringList("card-format.lore-footer"),
                plugin.getConfig().getDouble("card-xp." + card.rarity().id()),
                plugin.getConfig().getStringList("card-format.lore-pet"),
                pets.get(card.pet() == null ? "" : card.pet())));
    }

    public int version(Booster booster) {
        return versions.computeIfAbsent("booster:" + booster.id(), k -> java.util.Objects.hash(
                booster.itemId(), booster.name(), booster.lore(), booster.cards()));
    }

    public ItemStack cardItem(Card card) {
        return itemCache.computeIfAbsent("carte:" + card.id(), k -> buildCard(card)).clone();
    }

    public ItemStack boosterItem(Booster booster) {
        return boosterItem(booster, null);
    }

    /**
     * Booster vu par un joueur : %adopte:<id>% dans la description devient ✔ (familier adopté) ou ✘.
     * Sans joueur : « » ».
     */
    public ItemStack boosterItem(Booster booster, org.bukkit.OfflinePlayer viewer) {
        String status = adoptionStatus(booster, viewer);
        return itemCache.computeIfAbsent("booster:" + booster.id() + ":" + status,
                k -> buildBooster(booster, viewer)).clone();
    }

    private ItemStack buildCard(Card card) {
        ItemStack item = base(card.itemId(), Material.PAPER);
        item.editMeta(meta -> {
            meta.displayName(Texts.of(displayName(card)));
            List<Component> lore = new ArrayList<>();
            for (String line : plugin.getConfig().getStringList("card-format.lore-header")) {
                lore.add(Texts.of(fillCard(line, card)));
            }
            for (String line : card.lore()) {
                lore.add(Texts.of(fillCard(line, card)));
            }
            if (card.pet() != null && cardXp(card.rarity()) > 0) {
                for (String line : plugin.getConfig().getStringList("card-format.lore-pet")) {
                    lore.add(Texts.of(fillCard(line, card)
                            .replace("%xp%", String.format(java.util.Locale.FRANCE, "%.0f", cardXp(card.rarity())))
                            .replace("%pet%", petName(card.pet()))));
                }
            }
            for (String line : plugin.getConfig().getStringList("card-format.lore-footer")) {
                lore.add(Texts.of(fillCard(line, card)));
            }
            meta.lore(lore);
            meta.getPersistentDataContainer().set(plugin.cardKey(), PersistentDataType.STRING, card.id());
            meta.getPersistentDataContainer().set(plugin.versionKey(), PersistentDataType.INTEGER, version(card));
        });
        return item;
    }

    /** Nom affiché d'un familier (son identifiant si le système de familiers est inactif). */
    public String petName(String petId) {
        PetHook.PetInfo info = PetHook.pet(petId);
        return info == null ? petId : info.name();
    }

    private static final java.util.regex.Pattern ADOPTED = java.util.regex.Pattern.compile("%adopte:([a-z0-9_]+)%");

    /** Familiers cités dans la description du booster ; « + » adopté, « - » non, vide sans joueur. */
    private String adoptionStatus(Booster booster, org.bukkit.OfflinePlayer viewer) {
        if (viewer == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : booster.lore()) {
            var m = ADOPTED.matcher(line);
            while (m.find()) {
                sb.append(PetHook.has(viewer, m.group(1)) ? '+' : '-');
            }
        }
        return sb.toString();
    }

    private int version(Booster booster, String status) {
        return java.util.Objects.hash(version(booster), status);
    }

    private ItemStack buildBooster(Booster booster, org.bukkit.OfflinePlayer viewer) {
        String status = adoptionStatus(booster, viewer);
        ItemStack item = base(booster.itemId(), Material.PAPER);
        item.editMeta(meta -> {
            meta.displayName(Texts.of(booster.name()));
            meta.lore(booster.lore().stream()
                    .map(l -> {
                        var m = ADOPTED.matcher(l.replace("%cards%", String.valueOf(booster.cards())));
                        StringBuilder out = new StringBuilder();
                        while (m.find()) {
                            String mark = viewer == null ? "&#6B7A8F»"
                                    : PetHook.has(viewer, m.group(1)) ? "&#2ECF7B✔" : "&#FF5C5C✘";
                            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(mark));
                        }
                        m.appendTail(out);
                        return Texts.of(out.toString());
                    })
                    .toList());
            meta.getPersistentDataContainer().set(plugin.boosterKey(), PersistentDataType.STRING, booster.id());
            meta.getPersistentDataContainer().set(plugin.versionKey(), PersistentDataType.INTEGER, version(booster, status));
        });
        return item;
    }

    /**
     * Met à jour une carte ou un booster existant si son affichage a changé depuis sa création
     * (nom, description, texture...). Renvoie le nouvel objet, ou null s'il n'y a rien à faire.
     */
    public ItemStack refreshed(ItemStack item) {
        return refreshed(item, null);
    }

    /** viewer non nul : les boosters affichent l'état d'adoption de ce joueur. */
    public ItemStack refreshed(ItemStack item, org.bukkit.OfflinePlayer viewer) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        var pdc = item.getItemMeta().getPersistentDataContainer();
        Integer current = pdc.get(plugin.versionKey(), PersistentDataType.INTEGER);
        String cardId = pdc.get(plugin.cardKey(), PersistentDataType.STRING);
        if (cardId != null) {
            Card card = card(cardId);
            if (card == null || (current != null && current == version(card))) {
                return null;
            }
            ItemStack fresh = cardItem(card);
            fresh.setAmount(item.getAmount());
            return fresh;
        }
        String boosterId = pdc.get(plugin.boosterKey(), PersistentDataType.STRING);
        if (boosterId != null) {
            Booster booster = booster(boosterId);
            if (booster == null || (current != null && current == version(booster, adoptionStatus(booster, viewer)))) {
                return null;
            }
            ItemStack fresh = boosterItem(booster, viewer);
            fresh.setAmount(item.getAmount());
            return fresh;
        }
        return null;
    }

    /** Objet montré pendant la révélation (carte, ou icône du familier). */
    public ItemStack displayItem(Player player, Reward reward) {
        return switch (reward) {
            case CardReward c -> cardItem(c.card());
            case PetReward p -> {
                ItemStack icon = PetHook.icon(player, p.pet().id());
                icon.editMeta(meta -> meta.displayName(Texts.of(displayName(p.pet()))));
                yield icon;
            }
        };
    }

    // ---------------------------------------------------------------- tirage

    /** Récompenses possibles d'un booster (contenu explicite, ou tout ce qui est activé). */
    public List<Reward> contents(Booster booster) {
        List<Reward> result = new ArrayList<>();
        if (booster.contents().isEmpty()) {
            cards.values().forEach(c -> result.add(new CardReward(c)));
            if (PetHook.available()) {
                pets.values().stream().filter(PetEntry::enabled).forEach(p -> result.add(new PetReward(p)));
            }
            return result;
        }
        for (String entry : booster.contents()) {
            Reward r = reward(entry);
            if (r != null) {
                result.add(r);
            }
        }
        return result;
    }

    public Reward reward(String key) {
        String[] parts = key(key).split(":", 2);
        if (parts.length != 2) {
            return null;
        }
        if (parts[0].equals("carte")) {
            Card c = cards.get(parts[1]);
            return c == null ? null : new CardReward(c);
        }
        if (parts[0].equals("familier")) {
            PetEntry p = pets.get(parts[1]);
            return p == null || !p.enabled() || !PetHook.available() ? null : new PetReward(p);
        }
        return null;
    }

    /** Groupes tirables par rareté (poids > 0 et au moins une récompense), sans les exclus. */
    private Map<Rarity, List<Reward>> pool(Booster booster, boolean pets, Set<String> excluded) {
        Map<Rarity, List<Reward>> pool = new LinkedHashMap<>();
        List<Reward> all = contents(booster);
        Map<String, Integer> weights = booster.weights(pets);
        for (Rarity rarity : rarities.values()) {
            if (weights.getOrDefault(rarity.id(), 0) <= 0) {
                continue;
            }
            List<Reward> inRarity = all.stream()
                    .filter(r -> (r instanceof PetReward) == pets)
                    .filter(r -> r.rarity().equals(rarity) && !excluded.contains(r.key()))
                    .toList();
            if (!inRarity.isEmpty()) {
                pool.put(rarity, inRarity);
            }
        }
        return pool;
    }

    private static int total(Map<Rarity, List<Reward>> pool, Map<String, Integer> weights) {
        return pool.keySet().stream().mapToInt(r -> weights.get(r.id())).sum();
    }

    /** Poids total des familiers tirables (le poids d'une rareté est partagé entre ses familiers). */
    private double petWeight(Booster booster, Set<String> excluded) {
        double total = 0;
        Map<String, Integer> weights = booster.weights(true);
        List<Reward> all = contents(booster).stream().filter(r -> r instanceof PetReward).toList();
        for (Rarity rarity : rarities.values()) {
            int w = weights.getOrDefault(rarity.id(), 0);
            List<Reward> inRarity = all.stream().filter(r -> r.rarity().equals(rarity)).toList();
            if (w <= 0 || inRarity.isEmpty()) {
                continue;
            }
            long left = inRarity.stream().filter(r -> !excluded.contains(r.key())).count();
            total += (double) w * left / inRarity.size();
        }
        return total;
    }

    /**
     * Part (0-1) des tirages qui vont aux familiers. Elle baisse quand des familiers sont exclus
     * (déjà adoptés, ou déjà sortis dans ce booster) : chaque familier restant garde sa propre chance,
     * au lieu de récupérer celle des familiers exclus.
     */
    private double petShare(Booster booster, Set<String> excluded) {
        double full = petWeight(booster, Set.of());
        if (full <= 0) {
            return 0;
        }
        boolean hasCards = !pool(booster, false, excluded).isEmpty();
        double left = petWeight(booster, excluded) / full;
        return hasCards ? booster.petChance() * left : (left > 0 ? 1 : 0);
    }

    /** Chance (0-1) d'obtenir cette récompense à chaque tirage (hors familiers déjà possédés). */
    public double chance(Booster booster, Reward reward) {
        boolean pets = reward instanceof PetReward;
        Map<Rarity, List<Reward>> pool = pool(booster, pets, Set.of());
        Map<String, Integer> weights = booster.weights(pets);
        int total = total(pool, weights);
        List<Reward> same = pool.get(reward.rarity());
        if (total <= 0 || same == null || same.stream().noneMatch(r -> r.key().equals(reward.key()))) {
            return 0;
        }
        double share = petShare(booster, Set.of());
        double kind = pets ? share : 1 - share;
        return kind * weights.get(reward.rarity().id()) / total / same.size();
    }

    /** Chance (0-1) qu'un tirage de cartes (ou de familiers) tombe sur cette rareté. */
    public double rarityChance(Booster booster, Rarity rarity, boolean pets) {
        Map<Rarity, List<Reward>> pool = pool(booster, pets, Set.of());
        Map<String, Integer> weights = booster.weights(pets);
        int total = total(pool, weights);
        if (total <= 0 || !pool.containsKey(rarity)) {
            return 0;
        }
        return (double) weights.get(rarity.id()) / total;
    }

    public double rarityChance(Booster booster, Rarity rarity) {
        return rarityChance(booster, rarity, false);
    }

    /** Chance (0-1) qu'un tirage soit un familier. */
    public double petChance(Booster booster) {
        return petShare(booster, Set.of());
    }

    /**
     * Tire les récompenses d'un booster pour un joueur. Chaque tirage est un familier avec la
     * probabilité pet-chance (sinon une carte), puis une rareté selon les poids, puis une récompense
     * au hasard dans cette rareté. Un familier ne sort qu'une fois par booster ; en mode reroll,
     * un familier déjà adopté n'est jamais tiré.
     */
    public List<Reward> roll(Booster booster, Player player) {
        List<Reward> result = new ArrayList<>();
        Set<String> excluded = new HashSet<>();
        if (duplicateMode == DuplicatePetMode.REROLL && player != null) {
            for (PetEntry p : pets.values()) {
                if (PetHook.has(player, p.id())) {
                    excluded.add("familier:" + p.id());
                }
            }
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        // dans un booster, toutes les cartes d'une même rareté sont la même carte (regroupées en xN :
        // une seule animation par rareté)
        Map<Rarity, Reward> cardOfRarity = new HashMap<>();
        boolean petTaken = false;  // un seul familier au maximum par ouverture
        for (int i = 0; i < booster.cards(); i++) {
            boolean pets = !petTaken && random.nextDouble() < petShare(booster, excluded);
            Map<Rarity, List<Reward>> pool = pool(booster, pets, excluded);
            Map<String, Integer> weights = booster.weights(pets);
            int total = total(pool, weights);
            if (total <= 0) {
                continue;
            }
            int pick = random.nextInt(total);
            for (var e : pool.entrySet()) {
                pick -= weights.get(e.getKey().id());
                if (pick < 0) {
                    List<Reward> inRarity = e.getValue();
                    if (pets && player != null
                            && plugin.getConfig().getBoolean("boosters." + booster.id() + ".pets-prefer-new", false)) {
                        // booster de palier : le familier est choisi parmi ceux que le joueur n'a pas encore
                        List<Reward> fresh = inRarity.stream()
                                .filter(x -> x instanceof PetReward pr && !PetHook.has(player, pr.pet().id()))
                                .toList();
                        if (!fresh.isEmpty()) {
                            inRarity = fresh;
                        }
                    }
                    List<Reward> pool2 = inRarity;
                    Reward r = pets ? pool2.get(random.nextInt(pool2.size()))
                            : cardOfRarity.computeIfAbsent(e.getKey(), k -> pool2.get(random.nextInt(pool2.size())));
                    if (r instanceof PetReward pr) {
                        // peluche des doublons : désactivable par booster (boosters de palier)
                        r = new PetReward(pr.pet(), plugin.getConfig()
                                .getBoolean("boosters." + booster.id() + ".duplicate-plush", true));
                    }
                    result.add(r);
                    if (r instanceof PetReward) {
                        excluded.add(r.key());
                        petTaken = true;
                    }
                    break;
                }
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- écriture (menu)

    private void save() {
        plugin.saveConfig();
        load();
    }

    public void setBooster(String id, String path, Object value) {
        plugin.getConfig().set("boosters." + key(id) + "." + path, value);
        save();
    }

    public void createBooster(String id) {
        String base = "boosters." + key(id);
        var cfg = plugin.getConfig();
        cfg.set(base + ".item", "influcards:booster_basique");
        cfg.set(base + ".name", "&#FF7A59&l" + id);
        cfg.set(base + ".lore", List.of("&#EADBC8Contient &#FFF4E0%cards% &#EADBC8cartes.", "",
                "&r&7%img_right_click% &#FF7A59Ouvrir"));
        cfg.set(base + ".cards", 5);
        for (Rarity r : rarities.values()) {
            cfg.set(base + ".rarity-weights." + r.id(), r.order() == 0 ? 1 : 0);
        }
        cfg.set(base + ".contents", List.of());
        save();
    }

    public void deleteBooster(String id) {
        plugin.getConfig().set("boosters." + key(id), null);
        save();
    }

    /** Ajoute ou retire une récompense du contenu explicite d'un booster. */
    public void toggleContent(Booster booster, String rewardKey) {
        List<String> list = new ArrayList<>(booster.contents());
        if (list.isEmpty()) {
            // passage de "tout" à une liste explicite : on part de tout le contenu actuel
            contents(booster).forEach(r -> list.add(r.key()));
        }
        if (!list.remove(rewardKey)) {
            list.add(rewardKey);
        }
        setBooster(booster.id(), "contents", list);
    }

    public void setCard(String id, String path, Object value) {
        plugin.getConfig().set("cards." + key(id) + "." + path, value);
        save();
    }

    public void createCard(String id) {
        String base = "cards." + key(id);
        var cfg = plugin.getConfig();
        cfg.set(base + ".item", "influcards:carte_test");
        cfg.set(base + ".name", id);
        cfg.set(base + ".series", "");
        cfg.set(base + ".rarity", rarities.isEmpty() ? "simple" : rarities.keySet().iterator().next());
        cfg.set(base + ".lore", List.of());
        save();
    }

    public void deleteCard(String id) {
        plugin.getConfig().set("cards." + key(id), null);
        // retire la carte des contenus explicites
        for (Booster b : boosters.values()) {
            if (b.contents().contains("carte:" + key(id))) {
                List<String> list = new ArrayList<>(b.contents());
                list.remove("carte:" + key(id));
                plugin.getConfig().set("boosters." + b.id() + ".contents", list);
            }
        }
        save();
    }

    public void setPet(String id, String path, Object value) {
        String base = "pets.list." + key(id);
        var cfg = plugin.getConfig();
        if (!cfg.isConfigurationSection(base)) {
            cfg.set(base + ".rarity", rarities.isEmpty() ? "simple" : rarities.keySet().iterator().next());
            cfg.set(base + ".enabled", false);
        }
        cfg.set(base + "." + path, value);
        save();
    }

    public void setDuplicate(DuplicatePetMode mode, double xp) {
        plugin.getConfig().set("pets.duplicate", mode == DuplicatePetMode.XP ? "xp" : "reroll");
        plugin.getConfig().set("pets.duplicate-xp", xp);
        save();
    }

    public Rarity nextRarity(Rarity current, int direction) {
        List<Rarity> list = rarities();
        if (list.isEmpty()) {
            return current;
        }
        int i = current == null ? 0 : list.indexOf(current);
        return list.get(Math.floorMod(i + direction, list.size()));
    }
}

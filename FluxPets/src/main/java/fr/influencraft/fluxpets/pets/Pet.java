package fr.influencraft.fluxpets.pets;

import com.willfp.eco.core.config.interfaces.Config;
import com.willfp.eco.core.items.Items;
import com.willfp.eco.core.placeholder.PlayerStaticPlaceholder;
import com.willfp.eco.util.NumberUtils;
import com.willfp.libreforge.ViolationContext;
import com.willfp.libreforge.conditions.ConditionList;
import com.willfp.libreforge.conditions.Conditions;
import com.willfp.libreforge.effects.Chain;
import com.willfp.libreforge.effects.EffectList;
import com.willfp.libreforge.effects.Effects;
import com.willfp.libreforge.effects.executors.impl.NormalExecutorFactory;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Un familier, lu depuis plugins/FluxPets/pets/<id>.yml (même format qu'un fichier EcoPets : name, description,
 * entity-texture, icon, trail, level-xp-requirements ou xp-formula, level-placeholders, effects-description,
 * rewards-description, level-up-messages, level-up-effects, effects, conditions, activate-conditions).
 * Keys of FluxPets: flying (the pet floats, else it walks on the ground, default true), slots (active pet slots it
 * takes, default 1), walk-animation / reveal-animation (animations of its model, default walk / happy) and mount
 * (level from which its owner can ride it, seat height in blocks, speed in blocks per tick, jump height; a pet
 * without that section cannot be ridden). The former key mountable is still read: a mount from level 1.
 *
 * Calqué sur com.willfp.ecopets.pets.Pet : niveau et XP du joueur dans la base de Flux ({@link PetStore}),
 * effets libreforge compilés une fois, le niveau étant fourni à leurs
 * expressions par un placeholder « level » injecté dans la config (lu pour chaque joueur à l'évaluation).
 */
public final class Pet {

    /** Placeholder de niveau d'une description : %bonus% = expression évaluée au niveau voulu. */
    private record LevelPlaceholder(String id, String expression) {
        double value(int level) {
            return NumberUtils.evaluateExpression(expression.replace("%level%", String.valueOf(level)));
        }
    }

    public record Trail(Particle particle, int gap, int amount, double yOffset) {
    }

    /** Riding settings: from which level, seat height and speed (blocks per tick), jump height in blocks. */
    public record Mount(int level, double seat, double speed, double jump) {
    }

    private static final Pattern LEVEL_OFFSET = Pattern.compile("%level_(-?\\d+)(_numeral)?%");

    private final String id;
    private final Config config;
    private final String name;
    private final String description;
    private final List<LevelPlaceholder> levelPlaceholders = new ArrayList<>();
    private final String entityTexture;
    private final Trail trail;
    private final String xpFormula;
    /** 0 en tête, comme EcoPets : xpRequirements.get(n - 1) = XP pour passer au niveau n. */
    private final List<Integer> xpRequirements = new ArrayList<>();
    private final int maxLevel;
    private final boolean flying;
    private final Mount mount;
    private final int slots;
    private final String walkAnimation;
    private final String revealAnimation;
    private final String iconLookup;
    private ItemStack baseItem;
    private final EffectList effects;
    private final ConditionList conditions;
    private final ConditionList activateConditions;
    private final Chain levelUpEffects;
    private final Map<Integer, PetLevel> levels = new HashMap<>();
    private final Map<Integer, List<String>> effectsDescription = new HashMap<>();
    private final Map<Integer, List<String>> rewardsDescription = new HashMap<>();
    private final Map<Integer, List<String>> levelUpMessages = new HashMap<>();

    Pet(String id, Config config, com.willfp.eco.core.EcoPlugin libreforge) {
        this.id = id;
        this.config = config;
        this.name = config.getString("name");
        this.description = config.getString("description");
        for (Config sub : config.getSubsections("level-placeholders")) {
            levelPlaceholders.add(new LevelPlaceholder(sub.getString("id"), sub.getString("value")));
        }
        this.entityTexture = config.getString("entity-texture");
        this.trail = trail(config.getSubsection("trail"));
        this.xpFormula = config.has("xp-formula") ? config.getString("xp-formula") : null;
        xpRequirements.add(0);
        xpRequirements.addAll(config.getInts("level-xp-requirements"));
        Integer max = config.getIntOrNull("max-level");
        this.maxLevel = max != null ? max : xpRequirements.size();
        if (xpFormula == null && xpRequirements.size() <= 1 && max == null) {
            throw new IllegalArgumentException("Pet " + id + ": neither level-xp-requirements nor xp-formula");
        }
        this.flying = !config.has("flying") || config.getBool("flying");
        this.mount = mount(config);
        this.slots = Math.max(1, config.has("slots") ? config.getInt("slots") : 1);
        this.walkAnimation = config.has("walk-animation") ? config.getString("walk-animation") : "walk";
        this.revealAnimation = config.has("reveal-animation") ? config.getString("reveal-animation") : null;
        this.iconLookup = config.getString("icon");

        // %level% des effets : le niveau de CE familier chez le joueur évalué (comme EcoPets)
        config.injectPlaceholders(new PlayerStaticPlaceholder("level", p -> String.valueOf(PetData.level(p, this))));
        this.effects = Effects.INSTANCE.compile(config.getSubsections("effects"),
                new ViolationContext(libreforge, "Pet " + id));
        this.conditions = Conditions.INSTANCE.compile(config.getSubsections("conditions"),
                new ViolationContext(libreforge, "Pet " + id));
        this.activateConditions = Conditions.INSTANCE.compile(config.getSubsections("activate-conditions"),
                new ViolationContext(libreforge, "Pet " + id + " activate-conditions"));
        this.levelUpEffects = Effects.INSTANCE.compileChain(config.getSubsections("level-up-effects"),
                NormalExecutorFactory.INSTANCE.create(),
                new ViolationContext(libreforge, "Pet " + id + " level-up-effects"));
    }

    private static Mount mount(Config config) {
        Config c = config.getSubsectionOrNull("mount");
        if (c == null) {
            return config.has("mountable") && config.getBool("mountable") ? new Mount(1, 1.0, 0.3, 1.0) : null;
        }
        return new Mount(Math.max(1, c.getIntOrNull("level") == null ? 1 : c.getInt("level")),
                c.getDoubleOrNull("seat") == null ? 1.0 : c.getDouble("seat"),
                c.getDoubleOrNull("speed") == null ? 0.3 : c.getDouble("speed"),
                c.getDoubleOrNull("jump") == null ? 1.0 : c.getDouble("jump"));
    }

    private static Trail trail(Config c) {
        if (c == null || !c.getBool("enabled")) {
            return null;
        }
        try {
            Particle particle = Particle.valueOf(c.getString("particle").toUpperCase(Locale.ROOT));
            Integer gap = c.getIntOrNull("gap");
            Integer amount = c.getIntOrNull("amount");
            Double y = c.getDoubleOrNull("y-offset");
            return new Trail(particle, Math.max(1, gap == null ? 2 : gap), Math.max(1, amount == null ? 1 : amount),
                    y == null ? 0 : y);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public String id() {
        return id;
    }

    public Config config() {
        return config;
    }

    /** Nom avec codes couleur & (à passer par Texts.of). */
    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    /** Modèle BetterModel (entity-texture « bettermodel:<modèle> »), ou la valeur brute. */
    public String model() {
        return entityTexture.startsWith("bettermodel:") ? entityTexture.substring("bettermodel:".length()) : entityTexture;
    }

    public Trail trail() {
        return trail;
    }

    public int maxLevel() {
        return maxLevel;
    }

    /** Le familier flotte (sinon il se pose au sol). */
    public boolean flying() {
        return flying;
    }

    /** Can be ridden by its owner (from {@link Mount#level()}), when mounts are enabled (pet-settings). */
    public boolean mountable() {
        return mount != null;
    }

    /** Riding settings, null when the pet cannot be ridden. */
    public Mount mount() {
        return mount;
    }

    /** Active pet slots this pet takes (legendary boss pets take several). */
    public int slots() {
        return slots;
    }

    /** Animation of its model while it moves. */
    public String walkAnimation() {
        return walkAnimation;
    }

    /** Animation played when it is adopted (null: the reveal's default). */
    public String revealAnimation() {
        return revealAnimation;
    }

    /**
     * Icône (clé icon, syntaxe d'objet eco). Résolue à la demande : les objets ItemsAdder ne sont chargés
     * qu'après le démarrage, une résolution au chargement donnerait du papier.
     */
    public ItemStack baseItem() {
        if (baseItem == null) {
            ItemStack icon = Items.lookup(iconLookup).getItem();
            if (icon == null || icon.getType().isAir()) {
                return new ItemStack(Material.PAPER);
            }
            baseItem = icon;
        }
        return baseItem.clone();
    }

    public EffectList effects() {
        return effects;
    }

    public ConditionList conditions() {
        return conditions;
    }

    public Chain levelUpEffects() {
        return levelUpEffects;
    }

    /** Holder libreforge de ce familier à ce niveau (mis en cache). */
    public PetLevel level(int level) {
        return levels.computeIfAbsent(level, l -> new PetLevel(this, l, effects, conditions));
    }

    /** Les conditions d'activation sont remplies (aucune = toujours). */
    public boolean canActivate(Player player) {
        if (activateConditions.isEmpty()) {
            return true;
        }
        return activateConditions.areMet(com.willfp.libreforge.DispatcherKt.toDispatcher(player),
                new com.willfp.libreforge.SimpleProvidedHolder(level(PetData.level(player, this))));
    }

    /** XP pour atteindre ce niveau (infini hors de 1..max). */
    public double xpForLevel(int level) {
        if (level < 1 || level > maxLevel) {
            return Double.POSITIVE_INFINITY;
        }
        if (xpFormula != null) {
            return NumberUtils.evaluateExpression(xpFormula.replace("%level%", String.valueOf(level - 1)));
        }
        return level - 1 < xpRequirements.size() ? xpRequirements.get(level - 1) : Double.POSITIVE_INFINITY;
    }

    // ------------------------------------------------------------------ descriptions par niveau

    public List<String> effectsDescription(int level) {
        return effectsDescription.computeIfAbsent(level, l -> configuredLines("effects-description", l));
    }

    public List<String> rewardsDescription(int level) {
        return rewardsDescription.computeIfAbsent(level, l -> configuredLines("rewards-description", l));
    }

    public List<String> levelUpMessages(int level) {
        return levelUpMessages.computeIfAbsent(level, l -> configuredLines("level-up-messages", l));
    }

    /** Lignes du plus haut niveau configuré inférieur ou égal (sinon 1), placeholders de niveau remplacés. */
    private List<String> configuredLines(String section, int level) {
        int best = 1;
        Config sub = config.getSubsection(section);
        if (sub != null) {
            for (String key : sub.getKeys(false)) {
                try {
                    int k = Integer.parseInt(key);
                    if (k <= level && k > best) {
                        best = k;
                    }
                } catch (NumberFormatException ignored) {
                    // clé non numérique : ignorée
                }
            }
        }
        List<String> lines = new ArrayList<>();
        for (String line : config.getStrings(section + "." + best)) {
            lines.add(formatLevel(line, level));
        }
        return lines;
    }

    /** Remplace les placeholders de niveau (%bonus%...), %level%, %level_numeral% et %level_<décalage>%. */
    public String formatLevel(String text, int level) {
        String out = text;
        for (LevelPlaceholder p : levelPlaceholders) {
            out = out.replace("%" + p.id() + "%", nice(p.value(level)));
        }
        Matcher m = LEVEL_OFFSET.matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int value = level + Integer.parseInt(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(2) != null
                    ? fr.influencraft.flux.api.util.Format.roman(value) : String.valueOf(value)));
        }
        m.appendTail(sb);
        return sb.toString().replace("%level_numeral%", fr.influencraft.flux.api.util.Format.roman(level))
                .replace("%level%", String.valueOf(level));
    }

    /** Nombre lisible : entier sans décimale, sinon jusqu'à deux décimales (comme toNiceString d'eco). */
    public static String nice(double value) {
        if (Double.isInfinite(value)) {
            return "∞";
        }
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        String s = String.format(Locale.ROOT, "%.2f", value);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "").replace('.', ',');
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Pet other && other.id.equals(id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    /** Identifiant de holder libreforge d'un niveau. */
    static NamespacedKey holderKey(Pet pet, int level) {
        return Pets.key(pet.id + "_" + level);
    }
}

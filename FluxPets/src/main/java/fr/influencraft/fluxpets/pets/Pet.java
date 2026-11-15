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
 * (level from which its owner can ride it, seat height in blocks, speed in blocks per tick at the top level and
 * speed-start at level 1, jump height, and the optional sections attack, sounds and swim, see {@link Mount}; a pet
 * without that section cannot be ridden). The former key mountable is still read: a mount from level 1.
 * size: horizontal reach of its model in blocks (default 1); a bigger pet keeps further from its owner and from the
 * pet before it, and its seat is as wide (boss pets are their boss's model at full size). run-animation: animation
 * played in place of the walk above run-speed blocks a second (default 5: faster than a walking player), when the
 * model has it; a run plays at its own pace, so it stays smooth (speeding the walk up did not).
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

    /**
     * A sound of the game: its key, volume and pitch, written « key|volume|pitch » in a pet file; several played
     * together are joined by « + » ({@code also}: the next one).
     */
    public record Snd(String key, float volume, float pitch, Snd also) {
        static Snd parse(String text) {
            if (text == null || text.isBlank()) return null;
            int plus = text.indexOf('+');
            Snd also = plus < 0 ? null : parse(text.substring(plus + 1));
            String[] p = (plus < 0 ? text : text.substring(0, plus)).trim().split("\\|");
            try {
                return new Snd(p[0].trim(), p.length > 1 ? Float.parseFloat(p[1].trim()) : 1f,
                        p.length > 2 ? Float.parseFloat(p[2].trim()) : 1f, also);
            } catch (NumberFormatException e) {
                return new Snd(p[0].trim(), 1f, 1f, also);
            }
        }
    }

    /** Footfalls of a gait: length of its animation's cycle in ticks, ticks of the cycle a foot lands at. */
    public record Steps(int cycle, List<Integer> at) {
    }

    /**
     * Sounds of a mount: {@code step} played in turn at each footfall of the walk or the run (in step with the
     * animation), {@code swimStep} at each stroke in water, {@code voice} now and then while it moves (every voiceMin to
     * voiceMax ticks), {@code jump} when it leaves the ground and {@code land} when it comes back.
     */
    public record Sounds(List<Snd> step, Steps walk, Steps run, List<Snd> swimStep, Steps swim, List<Snd> voice,
                         int voiceMin, int voiceMax, Snd jump, Snd land) {
    }

    /**
     * Attack of a ridden pet (from {@code level}): its animation (ticks long), the ticks it hits at, who it hits (the
     * hostile mobs within range blocks and angle degrees of where it faces; 180 = all around), damage at the unlock
     * level and more per level above, cooldown in ticks, share of its speed kept while it attacks. {@code effect}:
     * {@code melee} (a blow in front), {@code wave} (a ring spreading from a point {@code forward} blocks ahead:
     * all within range, debris of the block {@code item}) or {@code volley} ({@code count} projectiles shown as
     * {@code item}, {@code spread} degrees apart, flying {@code range} blocks: each hurts the first mob it meets).
     */
    public record Attack(int level, String name, String animation, int ticks, List<Integer> impacts, double range,
                         double angle, double damage, double damagePerLevel, int cooldown, double slow, Snd sound,
                         Snd hitSound, String particle, String effect, String item, int count, double spread,
                         double forward) {
        public double damageAt(int petLevel) {
            return damage + damagePerLevel * Math.max(0, petLevel - level);
        }
    }

    /** A mount that swims: animations played in water, moving and still. */
    public record Swim(String animation, String idle) {
    }

    /**
     * Riding settings: from which level, seat height, speed in blocks per tick at the pet's top level (speedStart at
     * level 1, in between along the levels), jump height in blocks, its attack, sounds and swimming (null: none).
     */
    public record Mount(int level, double seat, double speed, double speedStart, double jump, Attack attack,
                        Sounds sounds, Swim swim) {
        /** Speed at that level of a pet whose top level is {@code maxLevel}. */
        public double speedAt(int petLevel, int maxLevel) {
            if (maxLevel <= 1) return speed;
            double t = Math.max(0, Math.min(1, (petLevel - 1) / (double) (maxLevel - 1)));
            return speedStart + (speed - speedStart) * t;
        }
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
    private final double size;
    private final int slots;
    private final String walkAnimation;
    private final String runAnimation;
    private final double runSpeed;
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
        this.size = config.getDoubleOrNull("size") == null ? 1.0 : Math.max(0.5, config.getDouble("size"));
        this.slots = Math.max(1, config.has("slots") ? config.getInt("slots") : 1);
        this.walkAnimation = config.has("walk-animation") ? config.getString("walk-animation") : "walk";
        this.runAnimation = config.has("run-animation") ? config.getString("run-animation") : null;
        this.runSpeed = config.getDoubleOrNull("run-speed") == null ? 5.0 : Math.max(0.5, config.getDouble("run-speed"));
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
            return config.has("mountable") && config.getBool("mountable")
                    ? new Mount(1, 1.0, 0.3, 0.3, 1.0, null, null, null) : null;
        }
        double speed = num(c, "speed", 0.3);
        return new Mount(Math.max(1, (int) num(c, "level", 1)), num(c, "seat", 1.0), speed,
                num(c, "speed-start", speed), num(c, "jump", 1.0), attack(c.getSubsectionOrNull("attack")),
                sounds(c.getSubsectionOrNull("sounds")), swim(c.getSubsectionOrNull("swim")));
    }

    private static double num(Config c, String key, double def) {
        return c.getDoubleOrNull(key) == null ? def : c.getDouble(key);
    }

    private static String text(Config c, String key) {
        return c.has(key) ? c.getString(key) : null;
    }

    private static Attack attack(Config c) {
        if (c == null || text(c, "animation") == null) return null;
        List<Integer> impacts = c.getInts("impacts");
        return new Attack(Math.max(1, (int) num(c, "level", 1)), text(c, "name") == null ? "" : text(c, "name"),
                text(c, "animation"), Math.max(1, (int) num(c, "ticks", 20)), impacts.isEmpty() ? List.of(0) : impacts,
                num(c, "range", 4), num(c, "angle", 60), num(c, "damage", 6), num(c, "damage-per-level", 1),
                (int) Math.round(num(c, "cooldown", 6) * 20), Math.max(0, Math.min(1, num(c, "slow", 0.4))),
                Snd.parse(text(c, "sound")), Snd.parse(text(c, "hit-sound")), text(c, "particle"),
                text(c, "effect") == null ? "melee" : text(c, "effect").toLowerCase(Locale.ROOT), text(c, "item"),
                Math.max(1, (int) num(c, "count", 1)), num(c, "spread", 10), num(c, "forward", 0));
    }

    private static List<Snd> snds(Config c, String key) {
        List<Snd> out = new ArrayList<>();
        for (String line : c.getStrings(key)) {
            Snd s = Snd.parse(line);
            if (s != null) out.add(s);
        }
        return out;
    }

    private static Steps steps(Config c) {
        if (c == null) return null;
        List<Integer> at = c.getInts("at");
        return new Steps(Math.max(1, (int) num(c, "cycle", 10)), at.isEmpty() ? List.of(0) : at);
    }

    private static Sounds sounds(Config c) {
        if (c == null) return null;
        return new Sounds(snds(c, "step"), steps(c.getSubsectionOrNull("walk")), steps(c.getSubsectionOrNull("run")),
                snds(c, "swim-step"), steps(c.getSubsectionOrNull("swim")), snds(c, "voice"),
                (int) Math.round(num(c, "voice-min", 6) * 20), (int) Math.round(num(c, "voice-max", 14) * 20),
                Snd.parse(text(c, "jump")), Snd.parse(text(c, "land")));
    }

    private static Swim swim(Config c) {
        return c == null || text(c, "animation") == null ? null : new Swim(text(c, "animation"), text(c, "idle"));
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

    /** Horizontal reach of its model in blocks (1 for a pet-sized model). */
    public double size() {
        return size;
    }

    /** Active pet slots this pet takes (legendary boss pets take several). */
    public int slots() {
        return slots;
    }

    /** Animation of its model while it moves. */
    public String walkAnimation() {
        return walkAnimation;
    }

    /** Animation of the model played in place of the walk when the pet goes fast, null when it has none. */
    public String runAnimation() {
        return runAnimation;
    }

    /** Blocks a second from which the run animation plays. */
    public double runSpeed() {
        return runSpeed;
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

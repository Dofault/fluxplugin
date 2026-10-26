package fr.influencraft.flux;

import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Placeholders des métiers EcoJobs pour le menu Carrières de Flux (« fluxjob ») :
 * <pre>
 *   %fluxjob_<métier>_action_count%          nombre d'actions rémunérées
 *   %fluxjob_<métier>_action_<i>_icon%       matériau (bloc récolté, ou objet du déclencheur)
 *   %fluxjob_<métier>_action_<i>_name%       « lang:<clé> » (nom traduit du bloc) ou libellé
 *   %fluxjob_<métier>_action_<i>_xp%         XP gagnée par action
 *   %fluxjob_<métier>_action_<i>_money%      gain d'argent au niveau du joueur (ligne prête à afficher)
 *   %fluxjob_<métier>_lvl_<k>%               niveau affiché en k-ième position (1 à 7) de la fenêtre
 *                                            de niveaux, qui suit le niveau du joueur
 *   %fluxjob_<métier>_window%                étapes atteintes dans cette fenêtre (chemin de progression)
 *   %fluxjob_<métier>_level_<k>_line_<n>%    n-ième ligne (déjà préfixée de la barre │, coupée à ~30
 *                                            caractères) de ce que rapporte ce niveau : BitBlock et
 *                                            booster de ses level-up-effects, hausse du gain par action
 *   %fluxjob_<métier>_lvlmod_<k>%            ce niveau modulo 100 (quantité d'objet : 1 à 99, 100 -> 0 -> 1)
 *   %fluxjob_<métier>_levelmod%              niveau du joueur modulo 100
 *   %fluxjob_<métier>_max%                   niveau maximum du métier
 *   %fluxjob_<métier>_xpsteps%               progression vers le niveau suivant en 9 segments (0 à 9),
 *                                            pour la barre d'XP (chemin sur les 9 cases de la barre d'action)
 *   %fluxjob_<métier>_nextlevel%             niveau suivant
 *   %fluxjob_<métier>_levelglyphs_<c>%      niveau écrit en glyphes ItemsAdder « chiffre_<n> », centré
 *                                            sur la fenêtre, à insérer dans le titre là où le curseur
 *                                            vaut <c> px ; avance nette nulle (le curseur y revient)
 * </pre>
 * La fenêtre de niveaux peut être décalée par le joueur ({@link #shift}, action [fluxjob] de Flux).
 * Actions rémunérées : même logique que l'ancien menu d'InfluBridge (JobBlockBreakdownMenu),
 * lue dans EcoJobs/jobs/<métier>.yml, comme la description des niveaux. Aucune dépendance de
 * compilation à EcoJobs.
 */
public final class JobInfo extends PlaceholderExpansion {

    private static final int WINDOW = 7;
    private static final Pattern ACTION = Pattern.compile("^(.+)_action_(\\d+)_(icon|name|xp|money)$");
    private static final Pattern ACTION_COUNT = Pattern.compile("^(.+)_action_count$");
    private static final Pattern LVL = Pattern.compile("^(.+)_lvl_(\\d+)$");
    private static final Pattern WINDOW_PROGRESS = Pattern.compile("^(.+)_window$");
    private static final Pattern LEVEL_LINE = Pattern.compile("^(.+)_level_(\\d+)_line_(\\d+)$");
    private static final Pattern LVL_MOD = Pattern.compile("^(.+)_lvlmod_(\\d+)$");
    private static final Pattern LEVEL_MOD = Pattern.compile("^(.+)_levelmod$");
    private static final Pattern MAX = Pattern.compile("^(.+)_max$");
    private static final Pattern XP_STEPS = Pattern.compile("^(.+)_xpsteps$");
    private static final Pattern NEXT_LEVEL = Pattern.compile("^(.+)_nextlevel$");
    private static final Pattern LEVEL_GLYPHS = Pattern.compile("^(.+)_levelglyphs_(-?\\d+)$");
    /** Chiffre « chiffre_<n> » : 5 px + ombre = 6 de large, avance 7 (1 px d'écart entre chiffres). */
    private static final int DIGIT_ADVANCE = 7;

    private static final Map<String, Material> CROP_BLOCK_TO_ITEM = Map.of(
            "potatoes", Material.POTATO, "carrots", Material.CARROT,
            "beetroots", Material.BEETROOT, "cocoa", Material.COCOA_BEANS);
    private static final Map<String, String> TRIGGER_LABELS = Map.ofEntries(
            Map.entry("mine_block", "Miner un bloc"), Map.entry("mine_block_cascade", "Miner en cascade"),
            Map.entry("catch_fish", "Pêcher un poisson"), Map.entry("kill", "Éliminer un mob"),
            Map.entry("place_block", "Poser un bloc"), Map.entry("smelt", "Fondre un objet"),
            Map.entry("brew", "Brasser une potion"), Map.entry("enchant_item", "Enchanter un objet"));
    private static final Map<String, Material> TRIGGER_ICONS = Map.ofEntries(
            Map.entry("mine_block", Material.STONE_PICKAXE), Map.entry("mine_block_cascade", Material.STONE_PICKAXE),
            Map.entry("catch_fish", Material.FISHING_ROD), Map.entry("kill", Material.IRON_SWORD),
            Map.entry("place_block", Material.BRICKS), Map.entry("smelt", Material.FURNACE),
            Map.entry("brew", Material.BREWING_STAND), Map.entry("enchant_item", Material.ENCHANTED_BOOK));

    /** Une action rémunérée ; l'argent est une formule, évaluée au niveau du joueur. */
    private record Action(Material icon, String name, double xp, Map<?, ?> moneyEffect) {
    }

    private final Plugin plugin;
    private final Logger logger;
    private final File jobsDir;
    private final Map<String, List<Action>> actions = new ConcurrentHashMap<>();
    /** Décalage de la fenêtre de niveaux choisi par chaque joueur, par métier (en niveaux). */
    private final Map<java.util.UUID, Map<String, Integer>> offsets = new ConcurrentHashMap<>();
    private final Method getByID, getMaxLevel, evaluate;
    private boolean warned;

    private JobInfo(Plugin plugin, Plugin ecoJobs) throws ReflectiveOperationException {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.jobsDir = new File(ecoJobs.getDataFolder(), "jobs");
        ClassLoader loader = ecoJobs.getClass().getClassLoader();
        Class<?> jobs = Class.forName("com.willfp.ecojobs.jobs.Jobs", true, loader);
        Class<?> job = Class.forName("com.willfp.ecojobs.jobs.Job", true, loader);
        getByID = jobs.getMethod("getByID", String.class);
        getMaxLevel = job.getMethod("getMaxLevel");
        Class<?> numbers = Class.forName("com.willfp.eco.util.NumberUtils", true, loader);
        evaluate = numbers.getMethod("evaluateExpression", String.class);
    }

    /** Inscrit l'extension si EcoJobs et PlaceholderAPI sont là ; null sinon. */
    static JobInfo register(Plugin plugin) {
        Plugin eco = Bukkit.getPluginManager().getPlugin("EcoJobs");
        if (eco == null || !eco.isEnabled() || !Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) return null;
        try {
            JobInfo expansion = new JobInfo(plugin, eco);
            expansion.register();
            return expansion;
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().warning("Placeholders des métiers indisponibles (API EcoJobs changée ?) : " + e);
            return null;
        }
    }

    /** /fluxmenu reload : relit les fichiers des métiers au prochain accès. */
    void clear() {
        actions.clear();
        configs.clear();
    }

    @Override
    public String getIdentifier() {
        return "fluxjob";
    }

    @Override
    public String getAuthor() {
        return "Influencraft";
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player player, String params) {
        try {
            Matcher m;
            if ((m = ACTION_COUNT.matcher(params)).matches()) {
                return String.valueOf(actions(m.group(1)).size());
            }
            if ((m = ACTION.matcher(params)).matches()) {
                List<Action> list = actions(m.group(1));
                int i = Integer.parseInt(m.group(2)) - 1;
                if (i < 0 || i >= list.size()) return "";
                Action a = list.get(i);
                return switch (m.group(3)) {
                    case "icon" -> a.icon().name().toLowerCase(java.util.Locale.ROOT);
                    case "name" -> a.name();
                    case "xp" -> nice(a.xp());
                    default -> money(player, m.group(1), a.moneyEffect());
                };
            }
            if (player == null) return "";
            if ((m = MAX.matcher(params)).matches()) {
                Object job = getByID.invoke(null, m.group(1));
                return job == null ? "0" : String.valueOf((int) getMaxLevel.invoke(job));
            }
            if ((m = XP_STEPS.matcher(params)).matches()) {
                double pct = number(PlaceholderAPI.setPlaceholders(player, "%ecojobs_" + m.group(1) + "_percentage_progress%"));
                return String.valueOf(Math.max(0, Math.min(9, (int) Math.floor(pct * 9 / 100))));
            }
            if ((m = LEVEL_GLYPHS.matcher(params)).matches()) {
                String digits = String.valueOf(level(player, m.group(1)));
                int cursor = Integer.parseInt(m.group(2));
                int width = digits.length() * DIGIT_ADVANCE - 1;
                int start = (88 - width / 2) - 8;   // centré sur la fenêtre (176 px), depuis le début du titre
                StringBuilder out = new StringBuilder(":offset_").append(start - cursor).append(':');
                for (char c : digits.toCharArray()) out.append(":chiffre_").append(c).append(':');
                // Retour du curseur à sa position d'origine : la suite du titre ne bouge pas.
                out.append(":offset_").append(cursor - start - digits.length() * DIGIT_ADVANCE).append(':');
                return out.toString();
            }
            if ((m = NEXT_LEVEL.matcher(params)).matches()) {
                return String.valueOf(level(player, m.group(1)) + 1);
            }
            if ((m = LEVEL_MOD.matcher(params)).matches()) {
                return String.valueOf(level(player, m.group(1)) % 100);
            }
            if ((m = LVL_MOD.matcher(params)).matches()) {
                return String.valueOf((windowStart(player, m.group(1)) + Integer.parseInt(m.group(2)) - 1) % 100);
            }
            if ((m = LVL.matcher(params)).matches()) {
                int k = Integer.parseInt(m.group(2));
                return String.valueOf(windowStart(player, m.group(1)) + k - 1);
            }
            if ((m = WINDOW_PROGRESS.matcher(params)).matches()) {
                return String.valueOf(level(player, m.group(1)) - windowStart(player, m.group(1)) + 1);
            }
            if ((m = LEVEL_LINE.matcher(params)).matches()) {
                String jobId = m.group(1);
                int lvl = windowStart(player, jobId) + Integer.parseInt(m.group(2)) - 1;
                List<String> lines = levelLines(player, jobId, lvl);
                int n = Integer.parseInt(m.group(3)) - 1;
                return n >= 0 && n < lines.size() ? lines.get(n) : "";
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!warned) {
                warned = true;
                logger.warning("Placeholder %fluxjob_" + params + "% en échec : " + e);
            }
        }
        return null;
    }

    private static final String BARRE = "&#FFD94D│ ";
    private static final int LARGEUR = 30;
    private final Map<String, YamlConfiguration> configs = new ConcurrentHashMap<>();

    private YamlConfiguration config(String jobId) {
        return configs.computeIfAbsent(jobId.toLowerCase(java.util.Locale.ROOT),
                id -> YamlConfiguration.loadConfiguration(new File(jobsDir, id + ".yml")));
    }

    /**
     * Ce que rapporte le passage au niveau {@code lvl}, d'après le fichier du métier : give_money des
     * level-up-effects (montant calculé pour ce niveau), booster FluxPets (run_command « influpet
     * stash »), et hausse du gain par action si les gains dépendent du niveau. Lignes coupées et
     * préfixées de la barre │ (style des menus), sans la puce « » » d'EcoJobs.
     */
    private List<String> levelLines(Player player, String jobId, int lvl) throws ReflectiveOperationException {
        YamlConfiguration cfg = config(jobId);
        String nom = cfg.getString("name", jobId).replaceAll("&#[0-9A-Fa-f]{6}|&[0-9a-fk-or]", "");
        String glyph = player == null ? "" : PlaceholderAPI.setPlaceholders(player, "%img_bitblock%");
        List<String> phrases = new ArrayList<>();
        for (Map<?, ?> effect : cfg.getMapList("level-up-effects")) {
            String id = String.valueOf(effect.get("id"));
            Map<?, ?> args = effect.get("args") instanceof Map<?, ?> a ? a : Map.of();
            if (id.equals("give_money") && args.get("amount") != null) {
                String formula = args.get("amount").toString().replace("%level%", String.valueOf(lvl));
                if (!formula.contains("%")) {
                    double amount = (double) evaluate.invoke(null, formula);
                    phrases.add("&#2ECF7B+" + nice(amount) + " &r&f" + glyph + "&#B376E5 en atteignant ce niveau");
                }
            } else if (id.equals("run_command") && String.valueOf(args.get("command")).contains("influpet stash")) {
                phrases.add("&#2ECF7B+1 &#B376E5Booster " + nom + " &8(/boosters)");
            }
        }
        boolean gainsParNiveau = false;
        for (Map<?, ?> effect : cfg.getMapList("effects")) {
            if ("give_money".equals(String.valueOf(effect.get("id"))) && effect.get("args") instanceof Map<?, ?> a
                    && a.get("amount") != null && a.get("amount").toString().contains("%level%")) gainsParNiveau = true;
        }
        if (gainsParNiveau) phrases.add("&#B376E5Gains par action plus élevés");
        if (phrases.isEmpty()) phrases.add("&#B376E5Aucune récompense");
        List<String> out = new ArrayList<>();
        for (String phrase : phrases) out.addAll(wrap(phrase));
        return out;
    }

    /** Coupe une phrase colorée en lignes d'au plus LARGEUR caractères visibles, couleur reportée. */
    private static List<String> wrap(String phrase) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int visible = 0;
        String color = "";
        for (String word : phrase.split(" ")) {
            int len = word.replaceAll("&#[0-9A-Fa-f]{6}|&[0-9a-fk-or]", "").length();
            if (visible > 0 && visible + 1 + len > LARGEUR) {
                lines.add(BARRE + line);
                line = new StringBuilder(color);
                visible = 0;
            }
            if (visible > 0) {
                line.append(' ');
                visible++;
            }
            line.append(word);
            visible += len;
            Matcher c = Pattern.compile("&#[0-9A-Fa-f]{6}|&[0-9a-f]").matcher(word);
            while (c.find()) color = c.group();
        }
        if (line.length() > 0) lines.add(BARRE + line);
        return lines;
    }

    private int level(Player player, String jobId) {
        return Math.max(0, (int) number(PlaceholderAPI.setPlaceholders(player, "%ecojobs_" + jobId + "_level%")));
    }

    /**
     * Premier niveau de la fenêtre : le niveau du joueur en 3e position, plus le décalage choisi
     * par le joueur (flèches), borné à [1, max - 6].
     */
    private int windowStart(Player player, String jobId) throws ReflectiveOperationException {
        Object job = getByID.invoke(null, jobId);
        int max = job == null ? 100 : (int) getMaxLevel.invoke(job);
        int offset = offsets.getOrDefault(player.getUniqueId(), Map.of()).getOrDefault(jobId, 0);
        int start = Math.max(1, level(player, jobId) - 2) + offset;
        return Math.max(1, Math.min(start, max - WINDOW + 1));
    }

    /**
     * Décale la fenêtre de niveaux du joueur : « prev » / « next » de 7 niveaux (sans sortir des
     * bornes), « reset » la recentre sur son niveau.
     */
    public void shift(Player player, String jobId, String how) {
        try {
            Map<String, Integer> mine = offsets.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>());
            if (how.equalsIgnoreCase("reset")) {
                mine.remove(jobId);
                return;
            }
            int before = windowStart(player, jobId);
            int delta = how.equalsIgnoreCase("prev") ? -WINDOW : WINDOW;
            mine.merge(jobId, delta, Integer::sum);
            // Bornes atteintes : on ramène le décalage sur la fenêtre réellement affichée.
            int after = windowStart(player, jobId);
            if (after == before) mine.merge(jobId, -delta, Integer::sum);
            else mine.put(jobId, mine.get(jobId) - (before + delta - after));
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.warning("Décalage des niveaux de " + jobId + " impossible : " + e);
        }
    }

    void forget(java.util.UUID player) {
        offsets.remove(player);
    }

    private List<Action> actions(String jobId) {
        return actions.computeIfAbsent(jobId.toLowerCase(java.util.Locale.ROOT), this::loadActions);
    }

    /** Port de JobBlockBreakdownMenu (InfluBridge) : un bloc par entrée, sinon une entrée par déclencheur. */
    private List<Action> loadActions(String jobId) {
        File file = new File(jobsDir, jobId + ".yml");
        if (!file.isFile()) return List.of();
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        List<Map<?, ?>> moneyEffects = new ArrayList<>();
        for (Map<?, ?> effect : config.getMapList("effects")) {
            if ("give_money".equals(String.valueOf(effect.get("id")))) moneyEffects.add(effect);
        }
        // Régime « bloc par bloc » : une liste positive filters.blocks.
        LinkedHashMap<String, Double> xpByBlock = new LinkedHashMap<>();
        for (Map<?, ?> method : config.getMapList("xp-gain-methods")) {
            Object xp = method.get("multiplier") != null ? method.get("multiplier") : method.get("value");
            if (xp != null && method.get("filters") instanceof Map<?, ?> filters && filters.get("blocks") instanceof List<?> blocks) {
                for (Object block : blocks) xpByBlock.putIfAbsent(block.toString().toLowerCase(java.util.Locale.ROOT), number(xp.toString()));
            }
        }
        List<Action> out = new ArrayList<>();
        if (!xpByBlock.isEmpty()) {
            Map<String, Map<?, ?>> moneyByBlock = new LinkedHashMap<>();
            Map<?, ?> catchAll = null;
            for (Map<?, ?> effect : moneyEffects) {
                if (effect.get("filters") instanceof Map<?, ?> filters && filters.get("blocks") instanceof List<?> blocks) {
                    for (Object block : blocks) moneyByBlock.putIfAbsent(block.toString().toLowerCase(java.util.Locale.ROOT), effect);
                } else if (catchAll == null) {
                    catchAll = effect;
                }
            }
            for (Map.Entry<String, Double> e : xpByBlock.entrySet()) {
                Material icon = icon(e.getKey());
                String name = icon == Material.BARRIER ? e.getKey() : "lang:" + icon.translationKey();
                out.add(new Action(icon, name, e.getValue(), moneyByBlock.getOrDefault(e.getKey(), catchAll)));
            }
            return List.copyOf(out);
        }
        // Régime « par déclencheur » (bâtisseur, pêcheur, défenseur, fondeur...).
        for (Map<?, ?> method : config.getMapList("xp-gain-methods")) {
            Object trigger = method.get("trigger");
            Object xp = method.get("multiplier") != null ? method.get("multiplier") : method.get("value");
            if (trigger == null || xp == null) continue;
            String t = trigger.toString();
            Object label = method.get("display-name");
            out.add(new Action(TRIGGER_ICONS.getOrDefault(t, Material.PAPER),
                    label != null ? label.toString() : TRIGGER_LABELS.getOrDefault(t, t), number(xp.toString()),
                    moneyFor(moneyEffects, t, method.get("filters"))));
        }
        return List.copyOf(out);
    }

    /** Effet give_money qui correspond à ce déclencheur (mêmes filtres d'abord, sinon le plus général). */
    private static Map<?, ?> moneyFor(List<Map<?, ?>> effects, String trigger, Object filters) {
        if (filters != null) {
            for (Map<?, ?> e : effects) {
                if (e.get("triggers") instanceof List<?> t && t.contains(trigger) && Objects.equals(e.get("filters"), filters)) return e;
            }
        }
        for (Map<?, ?> e : effects) {
            if (e.get("triggers") instanceof List<?> t && t.contains(trigger) && e.get("filters") == null) return e;
        }
        for (Map<?, ?> e : effects) {
            if (e.get("triggers") instanceof List<?> t && t.contains(trigger)) return e;
        }
        return effects.size() == 1 ? effects.get(0) : null;
    }

    /** Ligne d'argent au niveau du joueur, avec le glyphe BitBlock (jamais le caractère ₿). */
    private String money(Player player, String jobId, Map<?, ?> effect) throws ReflectiveOperationException {
        if (effect == null || !(effect.get("args") instanceof Map<?, ?> args) || args.get("amount") == null) {
            return "&7Aucun gain d'argent";
        }
        int lvl = player == null ? 1 : Math.max(1, level(player, jobId));
        String formula = args.get("amount").toString().replace("%level%", String.valueOf(lvl));
        if (formula.contains("%")) return "&7Gain variable";
        double amount = (double) evaluate.invoke(null, formula);
        String glyph = player == null ? "" : PlaceholderAPI.setPlaceholders(player, "%img_bitblock%");
        String text = "&7Gagne &#2ECF7B" + nice(amount) + " &r&f" + glyph;
        if (args.get("every") == null) return text + "&7 par action";
        double every = (double) evaluate.invoke(null, args.get("every").toString().replace("%level%", String.valueOf(lvl)));
        return text + "&7 toutes les &#2ECF7B" + nice(every) + " &7actions";
    }

    private static Material icon(String blockId) {
        Material mapped = CROP_BLOCK_TO_ITEM.get(blockId);
        if (mapped != null) return mapped;
        Material material = Material.matchMaterial(blockId);
        return material == null || !material.isItem() ? Material.BARRIER : material;
    }

    private static String nice(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) return String.valueOf((long) v);
        String s = String.format(java.util.Locale.FRANCE, "%.2f", v);
        return s.endsWith("0") ? s.substring(0, s.length() - 1) : s;
    }

    private static double number(String s) {
        try {
            return Double.parseDouble(s.replaceAll("§.", "").trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

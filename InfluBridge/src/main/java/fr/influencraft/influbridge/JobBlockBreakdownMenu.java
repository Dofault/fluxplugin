package fr.influencraft.influbridge;

import com.willfp.eco.core.gui.menu.Menu;
import com.willfp.eco.core.gui.menu.MenuBuilder;
import com.willfp.eco.core.gui.slot.Slot;
import com.willfp.eco.core.gui.slot.functional.SlotHandler;
import com.willfp.eco.util.NumberUtils;
import com.willfp.eco.util.StringUtils;
import com.willfp.ecojobs.api.EcoJobsAPI;
import com.willfp.ecojobs.jobs.Job;
import com.willfp.ecojobs.jobs.Jobs;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Menu natif "eco" qui énumère automatiquement, à partir de EcoJobs/jobs/<job>.yml, ce qui
 * rapporte de l'XP/de l'argent pour un métier — lu à chaque ouverture, jamais dupliqué. But
 * explicite (demande utilisateur du 2026-08-14) : ne plus jamais avoir à mettre à jour un menu
 * DeluxeMenus quand un bloc est ajouté/retiré côté EcoJobs. Ouvert depuis le bouton "Actions
 * rémunérées" du menu détail et le clic droit du menu d'ensemble des métiers (tous deux
 * `[player] metier blocs <job>`).
 *
 * Deux régimes de rendu selon ce que la config du job permet d'énumérer :
 * - **Grille de blocs** (mineur, cultivateur, déblayeur, rochiste, herboriste, sylviculteur) :
 *   xp-gain-methods a une liste positive `filters.blocks` — un slot par bloc réel.
 * - **Liste générique par déclencheur** (batisseur, pêcheur, défenseur, fondeur, chimiste,
 *   enchanteur) : pas de liste positive de blocs (soit `filters.not_blocks`, cf. piège "not_
 *   inverse un filtre" dans CLAUDE.md — cas batisseur ; soit un trigger qui ne porte pas sur des
 *   blocs, ex. catch_fish/kill/smelt/brew/enchant_item) — un slot par entrée xp-gain-methods,
 *   avec le montant d'argent retrouvé en croisant `effects[].triggers` sur le même déclencheur.
 *   L'XP est lue dans `multiplier` ou, à défaut, dans `value` (clés alternatives de libreforge).
 *   Une entrée peut porter une clé cosmétique `display-label` pour son titre, indispensable quand
 *   plusieurs entrées partagent le même trigger (pêcheur : une entrée par rareté de poisson).
 */
public final class JobBlockBreakdownMenu {

    private static final int COLUMNS_PER_ROW = 7;
    private static final int ROWS_PER_PAGE = 4;
    private static final int PAGE_SIZE = COLUMNS_PER_ROW * ROWS_PER_PAGE;

    // Blocs de culture dont l'id de bloc Bukkit diffère de l'id de l'item récolté (le bloc seul
    // n'est pas "item-able" — confirmé en jeu le 2026-08-14, "POTATOES isn't an item"). Repli
    // manuel pour ces cas connus avant le repli générique sur BARRIER.
    private static final Map<String, Material> CROP_BLOCK_TO_ITEM = Map.of(
            "potatoes", Material.POTATO,
            "carrots", Material.CARROT,
            "beetroots", Material.BEETROOT,
            "cocoa", Material.COCOA_BEANS
    );

    private static final Map<String, String> TRIGGER_LABELS = Map.ofEntries(
            Map.entry("mine_block", "Miner un bloc"),
            Map.entry("mine_block_cascade", "Miner en cascade"),
            Map.entry("catch_fish", "Pêcher un poisson"),
            Map.entry("kill", "Éliminer un mob"),
            Map.entry("place_block", "Poser un bloc"),
            Map.entry("smelt", "Fondre un item"),
            Map.entry("brew", "Brasser une potion"),
            Map.entry("enchant_item", "Enchanter un objet")
    );

    private static final Map<String, Material> TRIGGER_ICONS = Map.ofEntries(
            Map.entry("mine_block", Material.STONE_PICKAXE),
            Map.entry("mine_block_cascade", Material.STONE_PICKAXE),
            Map.entry("catch_fish", Material.FISHING_ROD),
            Map.entry("kill", Material.IRON_SWORD),
            Map.entry("place_block", Material.BRICKS),
            Map.entry("smelt", Material.FURNACE),
            Map.entry("brew", Material.BREWING_STAND),
            Map.entry("enchant_item", Material.ENCHANTED_BOOK)
    );

    private record DisplayEntry(Material icon, String title, double xp, String moneyText, String extraLoreLine) {
    }

    public void open(Player player, String jobId, int page) {
        Job job = Jobs.getByID(jobId);
        if (job == null) {
            player.sendMessage(StringUtils.format("&cMétier inconnu : &7" + jobId, player));
            return;
        }

        YamlConfiguration config = EcoJobsConfig.readJob(jobId);
        List<DisplayEntry> entries = collectBlockEntries(config, job, player);
        boolean isGeneric = false;
        if (entries.isEmpty()) {
            entries = collectGenericEntries(config, job, player);
            isGeneric = true;
        }
        boolean finalIsGeneric = isGeneric;

        int totalPages = entries.isEmpty() ? 1 : Math.max(1, (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int clampedPage = Math.max(1, Math.min(page, totalPages));

        MenuBuilder builder = Menu.builder(6)
                .setTitle(StringUtils.format("&#FFD94D" + job.getName() + " &8— &#B376E5Détail", player));

        builder.setSlot(1, 5, buildHeaderSlot(job, entries.isEmpty(), finalIsGeneric, clampedPage, totalPages));

        int start = (clampedPage - 1) * PAGE_SIZE;
        int end = Math.min(entries.size(), start + PAGE_SIZE);
        for (int i = start; i < end; i++) {
            int localIndex = i - start;
            int row = 2 + localIndex / COLUMNS_PER_ROW;
            int column = 2 + localIndex % COLUMNS_PER_ROW;
            builder.setSlot(row, column, buildEntrySlot(entries.get(i)));
        }

        if (clampedPage > 1) {
            builder.setSlot(6, 2, buildNavSlot("&7%img_left_click%&#FFD94D Page précédente",
                    (event, slot, menu) -> open((Player) event.getWhoClicked(), jobId, clampedPage - 1)));
        }
        builder.setSlot(6, 5, buildNavSlot("&7%img_left_click%&#FFD94D Retour",
                (event, slot, menu) -> {
                    Player clicker = (Player) event.getWhoClicked();
                    clicker.closeInventory();
                    org.bukkit.Bukkit.dispatchCommand(clicker, "metiers");
                }));
        if (clampedPage < totalPages) {
            builder.setSlot(6, 8, buildNavSlot("&7%img_left_click%&#FFD94D Page suivante",
                    (event, slot, menu) -> open((Player) event.getWhoClicked(), jobId, clampedPage + 1)));
        }

        builder.build().open(player);
    }

    // ── Régime "grille de blocs" ────────────────────────────────────────────

    private static List<DisplayEntry> collectBlockEntries(YamlConfiguration config, Job job, Player player) {
        LinkedHashMap<String, Double> xpByBlock = new LinkedHashMap<>();
        for (Map<?, ?> method : config.getMapList("xp-gain-methods")) {
            Object multiplierObj = method.get("multiplier");
            Object filtersObj = method.get("filters");
            if (multiplierObj == null || !(filtersObj instanceof Map<?, ?> filters)) continue;
            if (!(filters.get("blocks") instanceof List<?> blocks)) continue;

            double multiplier = Double.parseDouble(multiplierObj.toString());
            for (Object block : blocks) {
                xpByBlock.putIfAbsent(block.toString(), multiplier);
            }
        }
        if (xpByBlock.isEmpty()) {
            return List.of();
        }

        Map<String, String> moneyByBlock = new LinkedHashMap<>();
        String catchAllMoney = null;
        for (Map<?, ?> effect : config.getMapList("effects")) {
            if (!"give_money".equals(String.valueOf(effect.get("id")))) continue;
            String moneyText = formatMoney(effect, job, player);
            if (moneyText == null) continue;

            Object filtersObj = effect.get("filters");
            if (filtersObj instanceof Map<?, ?> filters && filters.get("blocks") instanceof List<?> blocks) {
                for (Object block : blocks) {
                    moneyByBlock.putIfAbsent(block.toString(), moneyText);
                }
            } else {
                catchAllMoney = moneyText;
            }
        }

        List<DisplayEntry> entries = new ArrayList<>();
        for (Map.Entry<String, Double> e : xpByBlock.entrySet()) {
            String blockId = e.getKey();
            String money = moneyByBlock.getOrDefault(blockId, catchAllMoney);
            entries.add(new DisplayEntry(resolveIcon(blockId), prettify(blockId), e.getValue(), money, null));
        }
        return entries;
    }

    // Certains id de bloc (ex: "potatoes"/"carrots"/"beetroots"/"cocoa", les blocs de culture en
    // croissance) résolvent vers un Material valide mais non "item" (Material.isItem() == false,
    // ex: POTATOES vs l'item POTATO) — new ItemStack(...) lève IllegalArgumentException dans ce
    // cas (confirmé en jeu le 2026-08-14, "POTATOES isn't an item"). CROP_BLOCK_TO_ITEM couvre les
    // cas connus actuels ; BARRIER reste le repli générique pour tout futur bloc non prévu.
    private static Material resolveIcon(String blockId) {
        Material mapped = CROP_BLOCK_TO_ITEM.get(blockId);
        if (mapped != null) {
            return mapped;
        }
        Material material = Material.matchMaterial(blockId);
        if (material == null || !material.isItem()) {
            return Material.BARRIER;
        }
        return material;
    }

    // ── Régime "générique par déclencheur" ──────────────────────────────────
    // Couvre batisseur (filters.not_blocks — pas de liste positive énumérable, cf. piège "not_
    // inverse un filtre") et les métiers dont le trigger ne porte pas sur des blocs.

    private static List<DisplayEntry> collectGenericEntries(YamlConfiguration config, Job job, Player player) {
        List<DisplayEntry> entries = new ArrayList<>();
        List<Map<?, ?>> moneyEffects = config.getMapList("effects").stream()
                .filter(e -> "give_money".equals(String.valueOf(e.get("id"))))
                .toList();

        for (Map<?, ?> method : config.getMapList("xp-gain-methods")) {
            Object triggerObj = method.get("trigger");
            // `multiplier` (l'XP est la valeur du trigger multipliée) et `value` (l'XP est fixe,
            // la valeur du trigger est ignorée) sont les deux clés alternatives de libreforge
            // (com.willfp.libreforge.counters.Counters) — exclusives l'une de l'autre. Le pêcheur
            // utilise `value` depuis le passage à une XP par palier de rareté (2026-09-15).
            Object xpObj = method.get("multiplier") != null ? method.get("multiplier") : method.get("value");
            if (triggerObj == null || xpObj == null) continue;
            String trigger = triggerObj.toString();

            String moneyText = findMoneyForTrigger(moneyEffects, trigger, method.get("filters"), job, player);

            Material icon = TRIGGER_ICONS.getOrDefault(trigger, Material.PAPER);
            // `display-label` : clé purement cosmétique propre à ce menu, ignorée par libreforge
            // (qui ne lit que trigger/conditions/filters/args/multiplier/value). Sert quand un
            // métier a plusieurs entrées sur le MÊME trigger (pêcheur : une par rareté de
            // poisson) et que le libellé générique du trigger les rendrait indistinguables.
            Object labelObj = method.get("display-label");
            String title = labelObj != null
                    ? labelObj.toString()
                    : TRIGGER_LABELS.getOrDefault(trigger, trigger);
            entries.add(new DisplayEntry(icon, title, Double.parseDouble(xpObj.toString()), moneyText, null));
        }
        return entries;
    }

    // Un métier peut avoir PLUSIEURS effets give_money sur le même trigger, chacun filtré comme
    // l'entrée xp-gain-methods correspondante (pêcheur : un prix par rareté de poisson depuis le
    // 2026-09-15). Se contenter du premier effet portant le bon trigger afficherait alors le même
    // prix sur toutes les lignes — d'où l'appariement par `filters` identiques en priorité.
    private static String findMoneyForTrigger(List<Map<?, ?>> moneyEffects, String trigger,
                                              Object methodFilters, Job job, Player player) {
        if (methodFilters != null) {
            for (Map<?, ?> effect : moneyEffects) {
                if (!(effect.get("triggers") instanceof List<?> triggers) || !triggers.contains(trigger)) continue;
                if (!Objects.equals(effect.get("filters"), methodFilters)) continue;
                String text = formatMoney(effect, job, player);
                if (text != null) return text;
            }
        }
        // Repli 1 : un effet non filtré sur ce trigger vaut pour toutes les entrées (cas des
        // métiers à un seul tarif, ex. defenseur/fondeur).
        for (Map<?, ?> effect : moneyEffects) {
            if (!(effect.get("triggers") instanceof List<?> triggers) || !triggers.contains(trigger)) continue;
            if (effect.get("filters") != null) continue;
            String text = formatMoney(effect, job, player);
            if (text != null) return text;
        }
        // Repli 2 : comportement historique (premier effet du bon trigger, puis effet unique).
        for (Map<?, ?> effect : moneyEffects) {
            if (effect.get("triggers") instanceof List<?> triggers && triggers.contains(trigger)) {
                String text = formatMoney(effect, job, player);
                if (text != null) return text;
            }
        }
        if (moneyEffects.size() == 1) {
            return formatMoney(moneyEffects.get(0), job, player);
        }
        return null;
    }

    private static String formatMoney(Map<?, ?> effect, Job job, Player player) {
        Object argsObj = effect.get("args");
        if (!(argsObj instanceof Map<?, ?> args)) return null;
        Object everyObj = args.get("every");
        Object amountObj = args.get("amount");
        if (amountObj == null) return null;

        int level = Math.max(1, EcoJobsAPI.getJobLevel(player, job));
        String amountFormula = amountObj.toString().replace("%level%", String.valueOf(level));
        if (amountFormula.contains("%")) return null; // ex: %v% (item_points) non résolu ici
        double amount = NumberUtils.evaluateExpression(amountFormula);

        if (everyObj == null) {
            return "&7Gagne &#2ECF7B" + NumberUtils.format(amount) + "₿&7 par action";
        }
        String everyFormula = everyObj.toString().replace("%level%", String.valueOf(level));
        double every = NumberUtils.evaluateExpression(everyFormula);
        return "&7Gagne &#2ECF7B" + NumberUtils.format(amount) + "₿&7 tous les &#2ECF7B"
                + NumberUtils.format(every) + " &7fois";
    }

    // ── Rendu ────────────────────────────────────────────────────────────────

    private Slot buildHeaderSlot(Job job, boolean empty, boolean generic, int page, int totalPages) {
        return Slot.builder((player, menu) -> {
            ItemStack icon = new ItemStack(Material.PAPER);
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(StringUtils.format("&#FFD94D" + job.getName(), player));
                List<String> lore = new ArrayList<>();
                if (empty) {
                    lore.add(StringUtils.format("&7Aucune action rémunérée détectée.", player));
                } else if (generic) {
                    lore.add(StringUtils.format("&#B376E5Ce métier n'a pas de liste de blocs", player));
                    lore.add(StringUtils.format("&#B376E5— détail par type d'action.", player));
                } else {
                    lore.add(StringUtils.format("&#B376E5Détail bloc par bloc.", player));
                }
                if (totalPages > 1) {
                    lore.add("");
                    lore.add(StringUtils.format("&7Page &f" + page + "&7/&f" + totalPages, player));
                }
                meta.setLore(lore);
                icon.setItemMeta(meta);
            }
            return icon;
        }).build();
    }

    private Slot buildEntrySlot(DisplayEntry entry) {
        return Slot.builder((player, menu) -> {
            ItemStack icon = new ItemStack(entry.icon());
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(StringUtils.format("&#FFD94D" + entry.title(), player));
                List<String> lore = new ArrayList<>();
                lore.add(StringUtils.format("&7XP &8► &#2ECF7B" + formatXp(entry.xp()), player));
                lore.add(StringUtils.format(
                        entry.moneyText() != null ? entry.moneyText() : "&7Argent &8► &8Aucun", player));
                if (entry.extraLoreLine() != null) {
                    lore.add("");
                    lore.add(StringUtils.format(entry.extraLoreLine(), player));
                }
                meta.setLore(lore);
                icon.setItemMeta(meta);
            }
            return icon;
        }).build();
    }

    private Slot buildNavSlot(String label, SlotHandler onClick) {
        return Slot.builder((player, menu) -> {
            ItemStack icon = new ItemStack(Material.ARROW);
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                meta.setDisplayName(StringUtils.format(label, player));
                icon.setItemMeta(meta);
            }
            return icon;
        }).onLeftClick(onClick).build();
    }

    // Les multiplicateurs d'XP sont des doubles (0.5, 1.25...) mais la plupart des valeurs sont
    // entières : afficher "10" plutôt que "10.0".
    private static String formatXp(double xp) {
        if (xp == Math.rint(xp) && !Double.isInfinite(xp)) {
            return String.valueOf((long) xp);
        }
        return String.valueOf(xp);
    }

    private static String prettify(String id) {
        String[] parts = id.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }
}

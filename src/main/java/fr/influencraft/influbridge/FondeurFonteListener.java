package fr.influencraft.influbridge;

import com.willfp.ecojobs.api.EcoJobsAPI;
import com.willfp.ecojobs.jobs.Job;
import com.willfp.ecojobs.jobs.Jobs;
import net.advancedplugins.ae.api.AEAPI;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.Set;

/**
 * Crédite le job EcoJobs "fondeur" quand un joueur mine un minerai avec l'enchant
 * AdvancedEnchantments "fonte" équipé sur sa pioche — cas que le trigger natif "smelt"
 * d'EcoJobs (FurnaceSmeltEvent) ne voit jamais, puisque "fonte" convertit le minerai
 * directement à la casse sans jamais passer par un four (AE désactive les drops natifs
 * du BlockBreakEvent et donne l'item fondu lui-même).
 *
 * Détecte uniquement la présence de l'enchant sur l'outil, pas si "fonte" a réellement
 * procé sur ce coup précis (chance-based côté AE, non exposé en dehors de son moteur
 * interne) — cohérent avec la philosophie déjà appliquée aux autres enchants AE du
 * serveur (valeurs fixes plutôt que loterie, cf. docs/ae-technique.md).
 *
 * Scope volontairement restreint aux blocs qui ont réellement besoin d'une fonte pour
 * donner leur forme finale (fer/cuivre/or/débris antiques/argile→brique/sable→verre, cf.
 * SmeltMaterial.java d'AE) — contrairement à AE, qui traite aussi diamant/émeraude/
 * redstone/lapis/quartz comme "fondables" alors que leur forme minée est déjà la forme
 * finale en vanilla.
 *
 * Pierre/deepslate ajoutés malgré l'absence de rareté réelle (contrairement aux
 * minerais/argile ci-dessus, la pierre taillée repousse déjà à l'infini nativement — se
 * replace et se recasse sans le moindre enchant) : un farm XP fondeur dessus ne protège
 * aucune ressource limitée, décision assumée par l'utilisateur — d'où STONE_FAMILY,
 * seul groupe exempté du garde-fou Taille Soyeuse ci-dessous.
 */
public final class FondeurFonteListener implements Listener {

    private static final String ENCHANT_ID = "fonte";

    // Nécessite une vraie transformation (donc bloqué par la Taille Soyeuse, cf. plus bas).
    private static final Set<Material> TRANSFORMABLE = EnumSet.of(
            Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE,
            Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE,
            Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE,
            Material.ANCIENT_DEBRIS,
            Material.CLAY,
            Material.SAND, Material.RED_SAND
    );

    // Ressource infinie par nature (générateurs cobble/deepslate) : exemptée du garde-fou
    // Taille Soyeuse, cf. javadoc de la classe.
    private static final Set<Material> STONE_FAMILY = EnumSet.of(
            Material.STONE, Material.COBBLESTONE,
            Material.DEEPSLATE, Material.COBBLED_DEEPSLATE
    );

    private final double xpPerOre;

    public FondeurFonteListener(double xpPerOre) {
        this.xpPerOre = xpPerOre;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        boolean isStoneFamily = STONE_FAMILY.contains(event.getBlock().getType());
        if (!isStoneFamily && !TRANSFORMABLE.contains(event.getBlock().getType())) {
            return;
        }

        // AE re-déclenche lui-même un second BlockBreakEvent synthétique pour le même bloc
        // physique (DropsHandler.breakBlock, pour informer les autres plugins du break une
        // fois les drops calculés) — marqué par cette metadata le temps de l'appel, convention
        // qu'AE expose explicitement pour que les listeners tiers l'ignorent. Sans ce filtre,
        // l'event original ET ce doublon étaient tous les deux traités : XP créditée deux fois
        // pour un seul minerai (bug vécu le 2026-08-13, la boss bar affichait le bon montant —
        // elle affiche juste le dernier des deux events — mais le total réel doublait).
        if (event.getBlock().hasMetadata("blockbreakevent-ignore")) {
            return;
        }

        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }

        ItemStack tool = player.getInventory().getItemInMainHand();
        if (!tool.getType().name().endsWith("_PICKAXE")) {
            return;
        }

        if (!AEAPI.hasCustomEnchant(ENCHANT_ID, tool)) {
            return;
        }

        // La Taille Soyeuse prime sur "fonte" côté AE (DropsHandler : le drop garde le bloc
        // brut dès que isSilkTouch() est vrai, la branche isSmelt() n'est même pas évaluée) —
        // sans ce garde-fou, un joueur avec les deux enchants garderait le minerai brut ET
        // recevrait quand même l'XP fondeur ici, puis une seconde fois en le fondant lui-même
        // au four (trigger natif "smelt" d'EcoJobs) : doublon d'XP pour un seul minerai. Exempté
        // pour STONE_FAMILY (voir javadoc de la classe).
        if (!isStoneFamily && tool.containsEnchantment(Enchantment.SILK_TOUCH)) {
            return;
        }

        Job job = Jobs.getByID("fondeur");
        if (job == null || !EcoJobsAPI.hasJobActive(player, job)) {
            return;
        }

        EcoJobsAPI.giveJobExperience(player, job, xpPerOre);
    }
}

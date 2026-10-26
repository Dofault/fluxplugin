package fr.influencraft.influbridge;

import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Neutralise l'enchantement vanilla Raccommodage (Mending) : le livre peut
 * continuer à exister et à circuler (loot, échanges, ventes), mais il ne peut
 * plus jamais être <b>associé à un objet</b> — un Raccommodage appliqué est
 * retiré du résultat d'enclume, du loot généré et des prises de pêche. Un livre
 * Raccommodage qui ne s'applique jamais devient de fait inutile.
 * <p>
 * Géré en Java, à {@code HIGHEST}, pour passer après les plugins qui recalculent
 * le résultat d'enclume et voir le résultat final — même mécanisme que
 * {@link AnvilRestrictionListener}.
 * <p>
 * <b>Règle transverse :</b> un <b>livre</b> n'est jamais modifié, où qu'il soit.
 * On agit uniquement quand Raccommodage est (ou serait) appliqué à un objet qui
 * n'est pas un livre.
 * <p>
 * Hors périmètre volontaire : les trades de villageois (traités ailleurs), la
 * table d'enchantement (Raccommodage n'y apparaît pas nativement), et l'effet
 * {@code type: mending} de CustomFishing (réparation de canne à pêche, pas
 * l'enchant).
 */
public final class MendingRestrictionListener implements Listener {

    private final Logger logger;
    private final boolean log;

    /**
     * @param logger logger du plugin, pour tracer chaque neutralisation.
     * @param log    valeur de {@code raccommodage.log} (config.yml) : {@code true}
     *               écrit une ligne console à chaque retrait de Raccommodage
     *               (audit / debug), {@code false} = silencieux.
     */
    public MendingRestrictionListener(Logger logger, boolean log) {
        this.logger = logger;
        this.log = log;
    }

    /**
     * Enclume : livre + objet donne un objet avec Raccommodage. On retire juste
     * l'enchant du résultat, sans annuler l'opération : une réparation, une
     * fusion ou un renommage légitime qui embarquait aussi Raccommodage aboutit
     * quand même, simplement sans lui. Un résultat livre (livre + livre) est
     * laissé intact (cf. règle transverse).
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        ItemStack result = event.getResult();
        if (retirerRaccommodage(result)) {
            event.setResult(result);
            journaliser("enclume", result);
        }
    }

    /**
     * Loot généré : coffres de structures, bastions, cités, fouille
     * archéologique... {@link LootGenerateEvent#getLoot()} peut contenir des
     * entrées nulles (gérées par le helper). La pêche vanilla ne passe
     * <b>pas</b> par cet event sur Paper, d'où le hook {@link #onPlayerFish}
     * séparé.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLootGenerate(LootGenerateEvent event) {
        List<ItemStack> loot = event.getLoot();
        boolean modifie = false;
        for (ItemStack item : loot) {
            if (retirerRaccommodage(item)) {
                journaliser("loot", item);
                modifie = true;
            }
        }
        // getLoot() renvoie la liste interne de l'event, PAS une copie : setLoot()
        // fait this.loot.clear() puis this.loot.addAll(loot) — si "loot" est cette
        // même référence, le clear() la vide aussi avant le addAll(), qui réinjecte
        // alors une collection déjà vide. Résultat sans la copie défensive : tout
        // le loot du conteneur disparaît dès qu'un seul objet portait Raccommodage.
        if (modifie) {
            event.setLoot(new ArrayList<>(loot));
        }
    }

    /**
     * Pêche : CustomFishing rend parfois la main au loot natif de Minecraft
     * (entrée {@code vanilla:} de contents/item/default.yml), dont la catégorie
     * « trésor » peut sortir un arc ou une canne <b>déjà enchantés</b> — un
     * Raccommodage appliqué peut donc atterrir dans les mains d'un joueur sans
     * jamais passer par {@link LootGenerateEvent}.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) {
            return;
        }
        if (!(event.getCaught() instanceof Item entite)) {
            return;
        }
        ItemStack item = entite.getItemStack();
        if (retirerRaccommodage(item)) {
            entite.setItemStack(item);
            journaliser("pêche", item);
        }
    }

    /**
     * Retire l'enchantement Raccommodage d'un objet <b>s'il n'est pas un
     * livre</b>. Renvoie {@code true} si l'objet a été modifié.
     */
    private boolean retirerRaccommodage(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null || meta instanceof EnchantmentStorageMeta) {
            // Livre enchanté : jamais touché — il circule librement, il est
            // juste inutile puisqu'il ne pourra pas être appliqué.
            return false;
        }
        if (!meta.hasEnchant(Enchantment.MENDING)) {
            return false;
        }
        meta.removeEnchant(Enchantment.MENDING);
        item.setItemMeta(meta);
        return true;
    }

    /** Trace une neutralisation en console si {@code raccommodage.log} est actif. */
    private void journaliser(String contexte, ItemStack item) {
        if (log) {
            logger.info("Raccommodage retiré (" + contexte + ") : " + item.getType().name());
        }
    }
}

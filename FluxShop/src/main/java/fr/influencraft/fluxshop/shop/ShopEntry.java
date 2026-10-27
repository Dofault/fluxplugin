package fr.influencraft.fluxshop.shop;

import fr.influencraft.fluxshop.managers.PriceManager;
import org.bukkit.inventory.ItemStack;

/**
 * Une ligne d'un coffre magasin : la copie exacte de l'objet vendu (nom, lore, données de plugin
 * comprises : booster InfluPet, objet ItemsAdder...) et son prix d'achat unitaire.
 * <p>
 * Sans prix propre ({@code price == null}), un objet vanilla « nu » suit en direct son prix
 * d'achat dans {@code prices.yml}. Un objet façonné n'a pas de prix dans {@code prices.yml} : il
 * a toujours un prix propre.
 */
public final class ShopEntry {

    private final ItemStack item;
    private Double price;

    public ShopEntry(ItemStack item, Double price) {
        this.item = item.clone();
        this.item.setAmount(1);
        this.price = price;
    }

    /** @return une copie de l'objet vendu, en un exemplaire. */
    public ItemStack getItem() {
        return item.clone();
    }

    /** @return le prix propre au coffre, ou {@code null} pour suivre {@code prices.yml}. */
    public Double getPrice() {
        return price;
    }

    public void setPrice(Double price) {
        this.price = price;
    }

    /** L'objet est-il identique à l'objet vanilla de son matériau (aucune donnée en plus) ? */
    public boolean isPlain() {
        return isPlain(item);
    }

    public static boolean isPlain(ItemStack item) {
        return item.isSimilar(new ItemStack(item.getType()));
    }

    /** @return le prix d'achat unitaire effectif, 0 si l'objet n'est pas en vente. */
    public double unitPrice(PriceManager priceManager) {
        if (price != null) {
            return price;
        }
        return isPlain() ? priceManager.getBuyPrice(item.getType().name()) : 0.0;
    }

    public boolean matches(ItemStack other) {
        return other != null && item.isSimilar(other);
    }
}

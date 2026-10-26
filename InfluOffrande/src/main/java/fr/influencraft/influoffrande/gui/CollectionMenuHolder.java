package fr.influencraft.influoffrande.gui;

import org.bukkit.inventory.Inventory;

public final class CollectionMenuHolder implements InfluOffrandeHolder {

    /**
     * Cycle order for the display filter button: Tout -&gt; Deposes -&gt; Non deposes -&gt; Tout.
     * Same convention as the destination selector in "/monde" (Utopia).
     * <p>
     * Les deux filtres portent sur l'avancement de l'offrande (ce que le joueur a deja
     * depose ou non), jamais sur son inventaire : l'ancien filtre "Possedes" regardait ce
     * qu'il avait sur lui, ce qui ne repondait pas a la question "qu'est-ce qu'il me reste
     * a faire ?". DEPOSITED et NOT_DEPOSITED sont donc exactement complementaires.
     */
    public enum FilterMode {
        ALL, DEPOSITED, NOT_DEPOSITED;

        public FilterMode next() {
            return switch (this) {
                case ALL -> DEPOSITED;
                case DEPOSITED -> NOT_DEPOSITED;
                case NOT_DEPOSITED -> ALL;
            };
        }

        /** Tolerant parse for the value stored in the player's data file. */
        public static FilterMode fromStored(String raw) {
            if (raw == null) {
                return ALL;
            }
            try {
                return valueOf(raw.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return ALL; // valeur inconnue ou filtre d'une ancienne version (OWNED/MISSING)
            }
        }
    }

    private Inventory inventory;
    private final String collectionId;
    private int page = 0;
    private FilterMode filterMode = FilterMode.ALL;
    private boolean editorMode = false;

    public CollectionMenuHolder(String collectionId) {
        this.collectionId = collectionId;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    @Override
    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    public String collectionId() {
        return collectionId;
    }

    public int page() {
        return page;
    }

    public void page(int page) {
        this.page = page;
    }

    public FilterMode filterMode() {
        return filterMode;
    }

    public void filterMode(FilterMode filterMode) {
        this.filterMode = filterMode;
    }

    public boolean editorMode() {
        return editorMode;
    }

    public void editorMode(boolean editorMode) {
        this.editorMode = editorMode;
    }
}

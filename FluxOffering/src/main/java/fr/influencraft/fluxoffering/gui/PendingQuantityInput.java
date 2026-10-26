package fr.influencraft.fluxoffering.gui;

import fr.influencraft.fluxoffering.model.ItemKey;

/**
 * Tracks an in-progress "type the quantity in chat" prompt started from the collection editor.
 */
record PendingQuantityInput(String collectionId, ItemKey itemKey, boolean isNewItem) {
}

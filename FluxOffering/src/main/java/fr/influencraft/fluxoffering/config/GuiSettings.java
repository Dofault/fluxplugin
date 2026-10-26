package fr.influencraft.fluxoffering.config;

import java.util.List;

public record GuiSettings(
        int size,
        List<Integer> itemSlots,
        int navPreviousSlot,
        int navNextSlot,
        int filterSlot,
        int editorToggleSlot,
        int depositAllSlot,
        String openSound,
        String depositSound,
        String errorSound,
        String collectionCompleteSound,
        String wikiSound,
        String collectionTitleFormat,
        String editorTitleFormat,
        String navPrevItem,
        String navNextItem,
        String filterItem,
        String editorOpenItem,
        String editorCloseItem,
        String depositAllItem,
        String moneyItem,
        String backItem
) {
}

package fr.influencraft.influshop.config;

import java.util.List;

public record GuiSettings(
        int size,
        List<Integer> itemSlots,
        int navPreviousSlot,
        int navNextSlot,
        int sortSlot,
        int editorToggleSlot,
        int balanceSlot,
        String openSound,
        String purchaseSound,
        String errorSound,
        String shopTitleFormat,
        String editorTitleFormat,
        String navPrevItem,
        String navNextItem,
        String sortItem,
        String editorOpenItem,
        String editorCloseItem,
        String balanceItem,
        String backItem
) {
}

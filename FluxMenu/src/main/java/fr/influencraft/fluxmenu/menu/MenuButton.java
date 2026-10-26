package fr.influencraft.fluxmenu.menu;

import org.bukkit.inventory.ItemFlag;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Un bouton d'un menu (section items.<id>), éventuellement posé sur plusieurs cases. */
public record MenuButton(
        String id,
        String material,
        String name,
        List<String> lore,
        String amount,
        int[] slots,
        int priority,
        boolean glow,
        Integer customModelData,
        String itemModel,
        Set<ItemFlag> flags,
        boolean hideTooltip,
        String confirm,
        List<Requirement> viewRequirements,
        List<Requirement> clickRequirements,
        List<String> denyActions,
        EnumMap<ClickKind, List<String>> actions) {

    /** Actions à jouer pour ce clic, en suivant les replis (maj+gauche -> gauche -> any). */
    public List<String> actionsFor(ClickKind kind) {
        for (ClickKind k = kind; k != null; k = k.fallback()) {
            List<String> list = actions.get(k);
            if (list != null) return list;
        }
        return List.of();
    }

    public boolean hasActions() {
        for (Map.Entry<ClickKind, List<String>> e : actions.entrySet()) {
            if (!e.getValue().isEmpty()) return true;
        }
        return false;
    }
}

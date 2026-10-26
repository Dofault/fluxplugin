package fr.influencraft.flux.menu;

import org.bukkit.event.inventory.ClickType;

/** Type de clic reconnu dans la section actions d'un bouton. */
public enum ClickKind {
    LEFT("left"),
    RIGHT("right"),
    SHIFT_LEFT("shift-left"),
    SHIFT_RIGHT("shift-right"),
    MIDDLE("middle"),
    DROP("drop"),
    ANY("any");

    public final String key;

    ClickKind(String key) {
        this.key = key;
    }

    /** null pour les clics jamais transmis aux boutons (double clic, touches 1-9, F...). */
    public static ClickKind of(ClickType type) {
        return switch (type) {
            case LEFT -> LEFT;
            case RIGHT -> RIGHT;
            case SHIFT_LEFT -> SHIFT_LEFT;
            case SHIFT_RIGHT -> SHIFT_RIGHT;
            case MIDDLE -> MIDDLE;
            case DROP, CONTROL_DROP -> DROP;
            default -> null;
        };
    }

    /** Repli quand le bouton ne définit pas ce clic : maj + clic retombe sur le clic simple. */
    public ClickKind fallback() {
        return switch (this) {
            case SHIFT_LEFT -> LEFT;
            case SHIFT_RIGHT -> RIGHT;
            case ANY -> null;
            default -> ANY;
        };
    }
}

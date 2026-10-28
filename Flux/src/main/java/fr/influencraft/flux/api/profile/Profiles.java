package fr.influencraft.flux.api.profile;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sections of the profile menu ({@code /profil [player]}): each plugin of the suite gives the section of the data it
 * owns (economy, town, collection, pets, tags...), for the player whose profile is shown (the subject, possibly
 * offline). A profile is a summary: a section's clicks only turn its pages or open the viewer's own menu.
 */
public final class Profiles {

    public interface Section {
        /** The section's button for this viewer looking at the subject's profile; page 0 first. Null hides it. */
        ItemStack item(Player viewer, OfflinePlayer subject, int page);

        /** Number of pages of the section (left click next, right click previous). */
        default int pages(Player viewer, OfflinePlayer subject) {
            return 1;
        }

        /** Click that is not a page turn (single-page section); true when the menu must be redrawn. */
        default boolean click(Player viewer, OfflinePlayer subject, boolean right) {
            return false;
        }
    }

    public record Registered(String id, int order, Section section, Plugin owner) {
    }

    private static final Map<String, Registered> SECTIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, UUID> SUBJECTS = new ConcurrentHashMap<>();

    private Profiles() {
    }

    /** {@code order}: position in the menu (lowest first). */
    public static void register(Plugin owner, String id, int order, Section section) {
        SECTIONS.put(id, new Registered(id, order, section, owner));
    }

    public static void unregisterAll(Plugin owner) {
        SECTIONS.values().removeIf(r -> r.owner() == owner);
    }

    public static List<Registered> sections() {
        return SECTIONS.values().stream().sorted(Comparator.comparingInt(Registered::order)).toList();
    }

    public static Registered section(String id) {
        return SECTIONS.get(id);
    }

    /** Whose profile this viewer is looking at (himself by default). */
    public static OfflinePlayer subject(Player viewer) {
        UUID id = SUBJECTS.get(viewer.getUniqueId());
        return id == null ? viewer : org.bukkit.Bukkit.getOfflinePlayer(id);
    }

    public static void subject(Player viewer, OfflinePlayer subject) {
        if (subject == null || subject.getUniqueId().equals(viewer.getUniqueId())) SUBJECTS.remove(viewer.getUniqueId());
        else SUBJECTS.put(viewer.getUniqueId(), subject.getUniqueId());
    }

    /** True when the viewer looks at his own profile. */
    public static boolean own(Player viewer) {
        return !SUBJECTS.containsKey(viewer.getUniqueId());
    }
}

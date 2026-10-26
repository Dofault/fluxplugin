package fr.influencraft.flux.api;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * API publique de Flux pour les autres plugins maison.
 *
 * <p>Sources d'objets : un bouton {@code material: "source:<id>:<argument>"} demande son objet à la
 * source inscrite sous {@code <id>}. L'objet garde son nom et sa description ; le {@code name} du
 * bouton, s'il est donné, remplace le nom, et le {@code lore} du bouton est ajouté <b>sous</b> la
 * description de l'objet. Sans {@code amount}, la quantité de l'objet est gardée.</p>
 */
public final class FluxApi {

    private static final Map<String, ItemSource> SOURCES = new ConcurrentHashMap<>();

    private FluxApi() {
    }

    public static void registerItemSource(String id, ItemSource source) {
        SOURCES.put(id.toLowerCase(Locale.ROOT), source);
    }

    public static void unregisterItemSource(String id) {
        SOURCES.remove(id.toLowerCase(Locale.ROOT));
    }

    public static ItemSource itemSource(String id) {
        return SOURCES.get(id.toLowerCase(Locale.ROOT));
    }
}

package fr.influencraft.flux.menu;

import java.util.List;

/**
 * Calque de titre conditionnel (section title-layers.<id> d'un menu) : texte ajouté à la fin du
 * titre (glyphes ItemsAdder et :offset_N:) seulement si ses conditions sont remplies. Sert à
 * dessiner dans le fond une image propre à chaque joueur (cadenas d'un monde verrouillé...).
 *
 * <p>Le texte part du curseur laissé par le titre de base et doit l'y ramener (offset final)
 * pour que les calques restent indépendants les uns des autres.
 */
public record TitleLayer(String id, String text, List<Requirement> requirements) {
}

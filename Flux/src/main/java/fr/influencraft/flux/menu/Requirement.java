package fr.influencraft.flux.menu;

import fr.influencraft.flux.Text;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/**
 * Condition d'affichage ou de clic d'un bouton, écrite sur une ligne :
 * <pre>
 *   permission: playervaultz.vaults.3
 *   !permission: playervaultz.vaults.3
 *   placeholder: %vault_eco_balance% >= 1000
 *   placeholder: %player_world% == Utopia
 * </pre>
 * Opérateurs : == != >= <= > < contains !contains (numériques si les deux côtés sont des nombres).
 */
public final class Requirement {

    private static final String[] OPERATORS = {"!contains", "contains", "==", "!=", ">=", "<=", ">", "<"};

    private final String type;
    private final boolean negate;
    private final String left;
    private final String operator;
    private final String right;

    private Requirement(String type, boolean negate, String left, String operator, String right) {
        this.type = type;
        this.negate = negate;
        this.left = left;
        this.operator = operator;
        this.right = right;
    }

    public static Requirement parse(String line) {
        String trimmed = line.trim();
        int colon = trimmed.indexOf(':');
        if (colon < 0) throw new IllegalArgumentException("condition sans « type: » : " + line);
        String type = trimmed.substring(0, colon).trim().toLowerCase(Locale.ROOT);
        String body = trimmed.substring(colon + 1).trim();
        boolean negate = type.startsWith("!");
        if (negate) type = type.substring(1);
        switch (type) {
            case "permission" -> {
                if (body.isEmpty()) throw new IllegalArgumentException("permission vide : " + line);
                return new Requirement(type, negate, body, null, null);
            }
            case "placeholder" -> {
                for (String op : OPERATORS) {
                    int at = body.indexOf(" " + op + " ");
                    if (at >= 0) {
                        return new Requirement(type, negate, body.substring(0, at).trim(), op,
                                body.substring(at + op.length() + 2).trim());
                    }
                }
                throw new IllegalArgumentException("opérateur manquant (== != >= <= > < contains) : " + line);
            }
            default -> throw new IllegalArgumentException("type de condition inconnu « " + type + " » : " + line);
        }
    }

    public boolean test(Player player) {
        boolean result = switch (type) {
            case "permission" -> player.hasPermission(left);
            case "placeholder" -> compare(Text.placeholders(player, left), Text.placeholders(player, right));
            default -> false;
        };
        return negate != result;
    }

    private boolean compare(String a, String b) {
        Double na = number(a);
        Double nb = number(b);
        if (na != null && nb != null) {
            return switch (operator) {
                case "==" -> na.doubleValue() == nb.doubleValue();
                case "!=" -> na.doubleValue() != nb.doubleValue();
                case ">=" -> na >= nb;
                case "<=" -> na <= nb;
                case ">" -> na > nb;
                case "<" -> na < nb;
                case "contains" -> a.contains(b);
                case "!contains" -> !a.contains(b);
                default -> false;
            };
        }
        return switch (operator) {
            case "==" -> a.equalsIgnoreCase(b);
            case "!=" -> !a.equalsIgnoreCase(b);
            case "contains" -> a.contains(b);
            case "!contains" -> !a.contains(b);
            default -> false; // comparaison d'ordre sur du texte : toujours fausse
        };
    }

    private static Double number(String s) {
        try {
            return Double.parseDouble(s.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static boolean all(List<Requirement> requirements, Player player) {
        for (Requirement r : requirements) {
            if (!r.test(player)) return false;
        }
        return true;
    }
}

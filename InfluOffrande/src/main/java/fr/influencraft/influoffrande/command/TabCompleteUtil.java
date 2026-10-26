package fr.influencraft.influoffrande.command;

import java.util.ArrayList;
import java.util.List;

final class TabCompleteUtil {

    private TabCompleteUtil() {
    }

    static List<String> filter(List<String> options, String current) {
        String lower = current.toLowerCase();
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase().startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }
}

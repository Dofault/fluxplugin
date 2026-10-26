package fr.influencraft.fluxcasino;

import fr.influencraft.flux.api.FluxApi;
import fr.influencraft.fluxcasino.games.Games;

import java.io.File;

/**
 * Everything FluxCasino does with Flux: its placeholders, the games and their menus (casino, plinko, slots,
 * roulette, shipped in the jar and copied to plugins/FluxCasino/menus/ on first start). Only loaded when Flux
 * is installed.
 */
final class FluxHook {

    static final String[] MENUS = {"casino.yml", "plinko.yml", "slots.yml", "roulette.yml"};

    private static Games games;

    private FluxHook() {
    }

    static void enable(FluxCasino plugin) {
        FluxApi.registerPlaceholders(plugin, "influcasino", plugin::placeholder);
        games = Games.enable(plugin);
        File folder = new File(plugin.getDataFolder(), "menus");
        for (String menu : MENUS) {
            if (!new File(folder, menu).exists()) plugin.saveResource("menus/" + menu, false);
        }
        FluxApi.registerMenuFolder(plugin, folder);
    }

    static void disable() {
        if (games != null) games.shutdown();
        games = null;
    }
}

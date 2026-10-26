package fr.influencraft.fluxcasino.games;

import org.bukkit.plugin.Plugin;

import java.util.List;

/**
 * Casino games played in Flux menus. Only loaded when Flux is installed: without Flux, FluxCasino still runs the
 * chip balance and exchange, without games.
 */
public final class Games {

    private final List<CasinoGame> games;

    private Games(List<CasinoGame> games) {
        this.games = games;
    }

    public static Games enable(Plugin plugin) {
        Plinko plinko = new Plinko(plugin);
        plinko.register("plinko", "fluxplinko");
        Slots slots = new Slots(plugin);
        slots.register("slots", "fluxslots");
        Roulette roulette = new Roulette(plugin);
        roulette.register("roulette", "fluxroulette");
        return new Games(List.of(plinko, slots, roulette));
    }

    /** Settles every round in progress (gains paid). */
    public void shutdown() {
        games.forEach(CasinoGame::shutdown);
    }
}

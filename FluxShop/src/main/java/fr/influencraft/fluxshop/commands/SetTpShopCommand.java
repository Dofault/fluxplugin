package fr.influencraft.fluxshop.commands;

import fr.influencraft.fluxshop.managers.LangManager;

import org.bukkit.entity.Player;

/** {@code /supermarket settpshop} : la position de l'admin devient le point d'arrivée de /tpshop. */
public class SetTpShopCommand implements SupermarketCommand {

    private final LangManager lang;
    private final TpShopCommand tpShopCommand;

    public SetTpShopCommand(LangManager lang, TpShopCommand tpShopCommand) {
        this.lang = lang;
        this.tpShopCommand = tpShopCommand;
    }

    @Override
    public String getName() {
        return "settpshop";
    }

    @Override
    public boolean execute(Player player, String[] args) {
        if (!player.hasPermission("supermarket.admin")) {
            player.sendMessage(lang.get("shop-no-permission"));
            return true;
        }
        tpShopCommand.setLocation(player.getLocation());
        player.sendMessage(lang.get("shop-tpshop-location-set"));
        return true;
    }
}

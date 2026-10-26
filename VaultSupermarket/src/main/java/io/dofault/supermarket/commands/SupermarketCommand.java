package io.dofault.supermarket.commands;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public interface SupermarketCommand {
    String getName();
    boolean execute(Player player, String[] args);
}
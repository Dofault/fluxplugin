package io.dofault.supermarket.commands;


import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CommandManager {
    private final Map<String, SupermarketCommand> commands = new HashMap<>();

    public void register(SupermarketCommand command) {
        commands.put(command.getName().toLowerCase(), command);
    }

    public boolean execute(Player player, String[] args) {
        if (args.length == 0) return false;
        SupermarketCommand cmd = commands.get(args[0].toLowerCase());
        if (cmd == null) return false;
        return cmd.execute(player, args);
    }

    public List<String> getCommandNames() {
        return new ArrayList<>(commands.keySet());
    }
}
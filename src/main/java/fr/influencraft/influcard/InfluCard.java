package fr.influencraft.influcard;

import fr.influencraft.influcard.CardRegistry.CardReward;
import fr.influencraft.influcard.CardRegistry.PetReward;
import fr.influencraft.influcard.CardRegistry.Reward;
import fr.influencraft.influcard.effets.PetEffects;
import fr.influencraft.influcard.gui.ChatPrompt;
import fr.influencraft.influcard.gui.MainMenu;
import fr.influencraft.influcard.gui.MenuListener;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Cartes à collectionner et boosters : un booster s'ouvre au clic droit, chaque récompense
 * (carte ou familier EcoPets) est révélée par une animation de totem. Édition en jeu : /influcard menu.
 */
public final class InfluCard extends JavaPlugin implements TabCompleter {

    private NamespacedKey cardKey;
    private NamespacedKey boosterKey;
    private NamespacedKey versionKey;
    private ItemUpdater updater;
    private CardRegistry registry;
    private RevealManager reveals;
    private ChatPrompt prompts;
    private PetEffects petEffects;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        cardKey = new NamespacedKey(this, "card");
        boosterKey = new NamespacedKey(this, "booster");
        versionKey = new NamespacedKey(this, "version");
        registry = new CardRegistry(this);
        registry.load();
        reveals = new RevealManager(this);
        prompts = new ChatPrompt(this);
        getServer().getPluginManager().registerEvents(new BoosterListener(this), this);
        getServer().getPluginManager().registerEvents(new MenuListener(), this);
        updater = new ItemUpdater(this);
        getServer().getPluginManager().registerEvents(updater, this);
        getServer().getPluginManager().registerEvents(prompts, this);
        var command = getCommand("influcard");
        if (command != null) {
            command.setTabCompleter(this);
        }
        if (getServer().getPluginManager().isPluginEnabled("EcoPets")
                && getServer().getPluginManager().isPluginEnabled("BetterModel")) {
            try {
                BetterModelPets.register(this);
            } catch (Throwable t) {
                getLogger().warning("Familiers BetterModel indisponibles : " + t);
            }
        }
        // EcoPets se charge parfois après : on recharge une fois le serveur prêt pour lister les familiers
        getServer().getScheduler().runTask(this, () -> registry.load());
        // effets de familiers impossibles en config libreforge (familiers.yml)
        if (getServer().getPluginManager().isPluginEnabled("EcoPets")) {
            if (!new java.io.File(getDataFolder(), "familiers.yml").exists()) {
                saveResource("familiers.yml", false);
            }
            try {
                petEffects = new PetEffects(this);
            } catch (Throwable t) {
                getLogger().warning("Effets de familiers indisponibles : " + t);
            }
        }
    }

    @Override
    public void onDisable() {
        if (reveals != null) {
            reveals.skipAll();
        }
        if (petEffects != null) {
            petEffects.shutdown();
        }
        if (getServer().getPluginManager().isPluginEnabled("BetterModel")
                && getServer().getPluginManager().isPluginEnabled("EcoPets")) {
            BetterModelPets.shutdown();
        }
    }

    public NamespacedKey cardKey() {
        return cardKey;
    }

    public NamespacedKey boosterKey() {
        return boosterKey;
    }

    public NamespacedKey versionKey() {
        return versionKey;
    }

    /** Met à jour les cartes et boosters de tous les joueurs connectés (après un rechargement). */
    public void refreshOnlineItems() {
        if (updater != null) {
            getServer().getOnlinePlayers().forEach(updater::refresh);
        }
    }

    /** Met à jour les cartes et boosters d'un joueur (ex : ✔ / ✘ des boosters après une adoption). */
    public void refreshItems(org.bukkit.entity.Player player) {
        if (updater != null && player.isOnline()) {
            updater.refresh(player);
        }
    }

    public CardRegistry registry() {
        return registry;
    }

    public RevealManager reveals() {
        return reveals;
    }

    public ChatPrompt prompts() {
        return prompts;
    }

    public String format(String key, String... replacements) {
        String msg = getConfig().getString("messages." + key, key);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            msg = msg.replace(replacements[i], replacements[i + 1]);
        }
        return msg;
    }

    public void send(CommandSender to, String key, String... replacements) {
        to.sendMessage(Texts.of(getConfig().getString("messages.prefix", "") + format(key, replacements)));
    }

    private void raw(CommandSender to, String text) {
        to.sendMessage(Texts.of(text));
    }

    private void usage(CommandSender to) {
        for (String line : getConfig().getStringList("messages.usage")) {
            raw(to, line);
        }
    }

    /** Nombre optionnel (1 à 64). Renvoie -1 si invalide. */
    private int amount(CommandSender sender, String[] args, int index) {
        if (args.length <= index) {
            return 1;
        }
        try {
            int n = Integer.parseInt(args[index]);
            if (n >= 1 && n <= 64) {
                return n;
            }
        } catch (NumberFormatException ignored) {
            // traité ci-dessous
        }
        send(sender, "invalid-amount", "%amount%", args[index]);
        return -1;
    }

    private Player target(CommandSender sender, String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p == null) {
            send(sender, "player-not-found", "%player%", name);
        }
        return p;
    }

    private static boolean isCardType(String type) {
        return type.equals("carte") || type.equals("cartes") || type.equals("card") || type.equals("cards");
    }

    private static boolean isPetType(String type) {
        return type.equals("familier") || type.equals("familiers") || type.equals("pet") || type.equals("pets");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            usage(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "menu" -> {
                if (sender instanceof Player p) {
                    new MainMenu(this).open(p);
                } else {
                    send(sender, "player-only");
                }
            }
            case "reload" -> {
                reloadConfig();
                registry.load();
                if (petEffects != null) {
                    petEffects.reload();
                }
                if (getServer().getPluginManager().isPluginEnabled("BetterModel")
                        && getServer().getPluginManager().isPluginEnabled("EcoPets")) {
                    BetterModelPets.reload();
                }
                send(sender, "reloaded", "%cards%", String.valueOf(registry.cards().size()),
                        "%boosters%", String.valueOf(registry.boosters().size()));
            }
            case "give" -> give(sender, args);
            case "open" -> open(sender, args);
            case "list" -> list(sender, args);
            case "info" -> info(sender, args);
            default -> usage(sender);
        }
        return true;
    }

    private void give(CommandSender sender, String[] args) {
        if (args.length < 4) {
            usage(sender);
            return;
        }
        Player target = target(sender, args[1]);
        int n = amount(sender, args, 4);
        if (target == null || n < 0) {
            return;
        }
        String type = args[2].toLowerCase(Locale.ROOT);
        ItemStack item;
        String name;
        if (type.equals("booster") || type.equals("boosters")) {
            var booster = registry.booster(args[3]);
            if (booster == null) {
                send(sender, "unknown-booster", "%id%", args[3]);
                return;
            }
            item = registry.boosterItem(booster);
            name = booster.name();
        } else if (isCardType(type)) {
            var card = registry.card(args[3]);
            if (card == null) {
                send(sender, "unknown-card", "%id%", args[3]);
                return;
            }
            item = registry.cardItem(card);
            name = registry.displayName(card);
        } else {
            usage(sender);
            return;
        }
        int left = n;
        while (left > 0) {
            ItemStack stack = item.clone();
            stack.setAmount(Math.min(left, item.getMaxStackSize()));
            left -= stack.getAmount();
            RevealManager.giveOrDrop(target, stack);
        }
        send(sender, "given", "%amount%", String.valueOf(n), "%item%", name, "%player%", target.getName());
    }

    private void open(CommandSender sender, String[] args) {
        if (args.length < 3) {
            usage(sender);
            return;
        }
        Player target = target(sender, args[1]);
        int n = amount(sender, args, 3);
        if (target == null || n < 0) {
            return;
        }
        var booster = registry.booster(args[2]);
        if (booster == null) {
            send(sender, "unknown-booster", "%id%", args[2]);
            return;
        }
        // plusieurs boosters ouverts d'un coup : une seule révélation pour toutes les récompenses
        List<Reward> rewards = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            rewards.addAll(registry.roll(booster, target));
        }
        if (rewards.isEmpty()) {
            send(sender, "empty-booster");
            return;
        }
        send(target, "opened", "%booster%", booster.name());
        reveals.start(target, rewards);
        if (sender != target) {
            send(sender, "opened-for", "%amount%", String.valueOf(n), "%booster%", booster.name(),
                    "%player%", target.getName());
        }
    }

    private void list(CommandSender sender, String[] args) {
        String type = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "cartes";
        if (isCardType(type)) {
            raw(sender, "&#FF7A59Cartes &#6B7A8F(" + registry.cards().size() + ")");
            for (var card : registry.cards()) {
                raw(sender, "&#6B7A8FN°" + String.format("%03d", card.number()) + " &r"
                        + registry.displayName(card) + " &#6B7A8F(" + card.id() + ")");
            }
        } else if (isPetType(type)) {
            raw(sender, "&#FF7A59Familiers &#6B7A8F(" + registry.pets().size() + ")");
            for (var pet : registry.pets()) {
                raw(sender, "&r" + registry.displayName(pet) + " &#6B7A8F(" + pet.id() + ", "
                        + (pet.enabled() ? "obtenable" : "désactivé") + ")");
            }
        } else {
            raw(sender, "&#FF7A59Boosters &#6B7A8F(" + registry.boosters().size() + ")");
            for (var booster : registry.boosters()) {
                raw(sender, "&r" + booster.name() + " &#6B7A8F(" + booster.id() + ", "
                        + booster.cards() + " tirages)");
            }
        }
    }

    private void info(CommandSender sender, String[] args) {
        if (args.length < 3) {
            usage(sender);
            return;
        }
        String type = args[1].toLowerCase(Locale.ROOT);
        Reward reward = null;
        if (isCardType(type)) {
            var card = registry.card(args[2]);
            if (card == null) {
                send(sender, "unknown-card", "%id%", args[2]);
                return;
            }
            reward = new CardReward(card);
            raw(sender, registry.displayName(card) + " &#6B7A8F(" + card.id() + ", N°"
                    + String.format("%03d", card.number()) + ", série " + card.series() + ")");
        } else if (isPetType(type)) {
            var pet = registry.pet(args[2]);
            if (pet == null) {
                send(sender, "unknown-pet", "%id%", args[2]);
                return;
            }
            reward = new PetReward(pet);
            raw(sender, registry.displayName(pet) + " &#6B7A8F(" + pet.id() + ")");
        }
        if (reward != null) {
            for (var booster : registry.boosters()) {
                double c = registry.chance(booster, reward);
                if (c > 0) {
                    raw(sender, "&#6B7A8F- &r" + booster.name() + " &#EADBC8: " + percent(c) + " par tirage");
                }
            }
            return;
        }
        var booster = registry.booster(args[2]);
        if (booster == null) {
            send(sender, "unknown-booster", "%id%", args[2]);
            return;
        }
        raw(sender, booster.name() + " &#6B7A8F(" + booster.id() + ", " + booster.cards()
                + " tirages) - chance par tirage :");
        var contents = registry.contents(booster);
        double petShare = registry.petChance(booster);
        for (boolean pets : new boolean[]{false, true}) {
            double share = pets ? petShare : 1 - petShare;
            if (share <= 0) {
                continue;
            }
            raw(sender, "&#FF7A59" + (pets ? "Familiers" : "Cartes") + " &#EADBC8" + percent(share) + " des tirages");
            for (var rarity : registry.rarities()) {
                double rc = registry.rarityChance(booster, rarity, pets);
                if (rc <= 0) {
                    continue;
                }
                raw(sender, "&r" + rarity.tag() + " &#EADBC8" + percent(rc * share) + " &#6B7A8F: "
                        + contents.stream()
                        .filter(r -> (r instanceof PetReward) == pets && r.rarity().equals(rarity))
                        .map(r -> "&r" + nameOnly(r) + " &#EADBC8" + percent(registry.chance(booster, r)))
                        .collect(Collectors.joining("&#6B7A8F, ")));
            }
        }
    }

    private String nameOnly(Reward r) {
        return switch (r) {
            case CardReward c -> "&#FFF4E0" + c.card().name();
            case PetReward p -> {
                var info = PetHook.pet(p.pet().id());
                yield "&#5FB3B3" + (info == null ? p.pet().id() : info.name());
            }
        };
    }

    public static String percent(double chance) {
        return String.format(Locale.FRANCE, "%.2f %%", chance * 100);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        List<String> players = Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        List<String> cardIds = registry.cards().stream().map(CardRegistry.Card::id).toList();
        List<String> petIds = registry.pets().stream().map(CardRegistry.PetEntry::id).toList();
        List<String> boosterIds = registry.boosters().stream().map(CardRegistry.Booster::id).toList();
        String sub = args[0].toLowerCase(Locale.ROOT);
        List<String> options = switch (args.length) {
            case 1 -> List.of("menu", "give", "open", "list", "info", "reload");
            case 2 -> switch (sub) {
                case "give", "open" -> players;
                case "list", "info" -> List.of("cartes", "familiers", "boosters");
                default -> List.of();
            };
            case 3 -> switch (sub) {
                case "give" -> List.of("booster", "carte");
                case "open" -> boosterIds;
                case "info" -> {
                    String t = args[1].toLowerCase(Locale.ROOT);
                    yield isCardType(t) ? cardIds : isPetType(t) ? petIds : boosterIds;
                }
                default -> List.of();
            };
            case 4 -> sub.equals("give")
                    ? (isCardType(args[2].toLowerCase(Locale.ROOT)) ? cardIds : boosterIds)
                    : (sub.equals("open") ? List.of("1", "5", "10") : List.of());
            case 5 -> sub.equals("give") ? List.of("1", "16", "64") : List.of();
            default -> List.of();
        };
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(last)).toList();
    }
}

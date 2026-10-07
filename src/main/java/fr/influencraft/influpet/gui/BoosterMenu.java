package fr.influencraft.influpet.gui;

import fr.influencraft.influpet.CardRegistry;
import fr.influencraft.influpet.InfluPet;
import fr.influencraft.influpet.RevealManager;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Édition d'un booster : nom, texture, nombre de tirages, poids des raretés, contenu. */
public final class BoosterMenu extends Menu {

    private static final int[] RARITY_SLOTS = {28, 29, 30, 31, 32, 33, 34};
    private static final int[] PET_RARITY_SLOTS = {37, 38, 39, 40, 41, 42, 43};

    private final String id;

    public BoosterMenu(InfluPet plugin, String id) {
        super(plugin, 6, "&#FF7A59Booster &#6B7A8F▸ &#EADBC8" + id);
        this.id = id;
    }

    private static int step(ClickType click) {
        return switch (click) {
            case LEFT -> 1;
            case RIGHT -> -1;
            case SHIFT_LEFT -> 10;
            case SHIFT_RIGHT -> -10;
            default -> 0;
        };
    }

    @Override
    protected void build(Player player) {
        CardRegistry.Booster b = plugin.registry().booster(id);
        if (b == null) {
            back(new MainMenu(plugin));
            return;
        }
        var reg = plugin.registry();

        ItemStack preview = reg.boosterItem(b);
        List<String> previewLore = new ArrayList<>(b.lore().stream()
                .map(l -> l.replace("%cards%", String.valueOf(b.cards()))).toList());
        previewLore.add("");
        previewLore.add(left("Recevoir ce booster"));
        previewLore.add(right("Tester l'ouverture"));
        set(4, decorate(preview, b.name(), previewLore), (p, c) -> {
            if (c.isRightClick()) {
                p.closeInventory();
                var rewards = reg.roll(b, p);
                if (rewards.isEmpty()) {
                    plugin.send(p, "empty-booster");
                    return;
                }
                plugin.reveals().start(p, rewards);
            } else {
                RevealManager.giveOrDrop(p, reg.boosterItem(b));
            }
        });

        set(19, icon("name_tag", "&#FF7A59Nom", "&#6B7A8FActuel : &r" + b.name(), "",
                "&#EADBC8Couleurs : codes & et hexadécimaux.", "", left("Modifier")), (p, c) ->
                ask(p, "&#EADBC8Nouveau nom du booster :", answer -> reg.setBooster(id, "name", answer)));

        set(20, icon("painting", "&#FF7A59Texture", "&#6B7A8FActuelle : &#EADBC8" + b.itemId(), "",
                "&#EADBC8Identifiant d'un item ItemsAdder", "&#EADBC8(ex : influcards:booster_basique).",
                "", left("Modifier")), (p, c) ->
                ask(p, "&#EADBC8Item ItemsAdder du booster &#6B7A8F(namespace:id) :",
                        answer -> reg.setBooster(id, "item", answer.trim())));

        set(21, icon("book", "&#FF7A59Description",
                withHeader(b.lore(), "&#6B7A8FActuelle :", left("Modifier"),
                        "&#6B7A8FUne ligne par « | », %cards% = nombre de tirages.")), (p, c) ->
                ask(p, "&#EADBC8Description, lignes séparées par &#FFF4E0| &#EADBC8:",
                        answer -> reg.setBooster(id, "lore", Arrays.stream(answer.split("\\|"))
                                .map(String::trim).toList())));

        set(23, icon("paper", "&#FF7A59Tirages par booster &#FFF4E0" + b.cards(),
                "&#EADBC8Nombre de récompenses données", "&#EADBC8à chaque ouverture.", "",
                left("+1"), right("-1"), shift("±10")), (p, c) -> {
            int n = Math.max(1, Math.min(54, b.cards() + step(c)));
            reg.setBooster(id, "cards", n);
            refresh(p);
        });

        long count = reg.contents(b).size();
        set(24, icon("chest", "&#FF7A59Contenu",
                "&#EADBC8" + (b.contents().isEmpty() ? "Tout (toutes les cartes et" : count + " récompenses"),
                b.contents().isEmpty() ? "&#EADBC8tous les familiers obtenables)" : "&#EADBC8choisies à la main.",
                "", left("Choisir les cartes et familiers")), (p, c) -> new ContentMenu(plugin, id, 0).open(p));

        set(25, icon("influitems:search", "&#FF7A59Chances de tirage",
                "&#EADBC8Affiche dans le tchat la chance", "&#EADBC8de chaque carte et familier.", "",
                left("Afficher")), (p, c) -> {
            p.closeInventory();
            p.performCommand("influpet info booster " + id);
        });

        // Poids des raretés : ligne des cartes, ligne des familiers
        set(27, icon("influcards:carte_test", "&#FF7A59Cartes : poids des raretés",
                "&#EADBC8À chaque tirage de carte, une rareté", "&#EADBC8est choisie selon ces poids, puis",
                "&#EADBC8une carte au hasard dans cette rareté.", "",
                "&#6B7A8FUne rareté sans carte dans le", "&#6B7A8Fcontenu n'est jamais tirée."));
        set(36, icon("bone", "&#FF7A59Familiers : poids des raretés",
                "&#EADBC8Même principe pour les tirages", "&#EADBC8de familiers.", "",
                "&#6B7A8FUn familier ne sort qu'une fois", "&#6B7A8Fpar booster."));
        weightRow(player, b, false, RARITY_SLOTS);
        weightRow(player, b, true, PET_RARITY_SLOTS);

        double pc = b.petChance() * 100;
        set(22, icon("bone", "&#FF7A59Chance de familier &#FFF4E0" + String.format(java.util.Locale.FRANCE, "%.2f %%", pc),
                "&#EADBC8Probabilité qu'un tirage soit", "&#EADBC8un familier plutôt qu'une carte.",
                "&#EADBC8Par booster : &#FFF4E0" + InfluPet.percent(1 - Math.pow(1 - reg.petChance(b), b.cards()))
                        + " &#EADBC8d'en avoir au moins un.", "",
                left("+0,1 %"), right("-0,1 %"), shift("±1 %")), (p, c) -> {
            double delta = switch (c) {
                case LEFT -> 0.1;
                case RIGHT -> -0.1;
                case SHIFT_LEFT -> 1;
                case SHIFT_RIGHT -> -1;
                default -> 0;
            };
            double v = Math.round(Math.max(0, Math.min(100, pc + delta)) * 100) / 100.0;
            reg.setBooster(id, "pet-chance", v);
            refresh(p);
        });

        back(new MainMenu(plugin));
        set(53, icon("barrier", "&#D9534FSupprimer ce booster",
                "&#EADBC8Les boosters déjà distribués", "&#EADBC8ne s'ouvriront plus.", "",
                shift("Supprimer (Maj + clic droit)")), (p, c) -> {
            if (c == ClickType.SHIFT_RIGHT) {
                reg.deleteBooster(id);
                plugin.send(p, "deleted", "%id%", id);
                new MainMenu(plugin).open(p);
            }
        });
    }

    private void weightRow(Player player, CardRegistry.Booster b, boolean pets, int[] slots) {
        var reg = plugin.registry();
        var rarities = reg.rarities();
        String path = pets ? "pet-rarity-weights." : "rarity-weights.";
        for (int i = 0; i < rarities.size() && i < slots.length - 1; i++) {
            CardRegistry.Rarity r = rarities.get(i);
            int weight = b.weights(pets).getOrDefault(r.id(), 0);
            long inContent = reg.contents(b).stream()
                    .filter(x -> (x instanceof CardRegistry.PetReward) == pets && x.rarity().equals(r)).count();
            ItemStack item = icon(weight > 0 ? "lime_dye" : "gray_dye", r.tag() + " &#FFF4E0" + weight,
                    "&#EADBC8Chance de cette rareté : &#FFF4E0" + InfluPet.percent(reg.rarityChance(b, r, pets)),
                    "&#EADBC8" + (pets ? "Familiers" : "Cartes") + " de cette rareté : &#FFF4E0" + inContent,
                    "",
                    left("+1"), right("-1"), shift("±10"));
            item.setAmount(Math.max(1, Math.min(64, weight)));
            set(slots[i + 1], item, (p, c) -> {
                // première modification des poids familiers : on part des poids actuels
                if (pets) {
                    for (var other : rarities) {
                        reg.setBooster(id, path + other.id(), b.weights(true).getOrDefault(other.id(), 0));
                    }
                }
                reg.setBooster(id, path + r.id(), Math.max(0, weight + step(c)));
                refresh(p);
            });
        }
    }

    private static List<String> withHeader(List<String> lines, String header, String action, String hint) {
        List<String> lore = new ArrayList<>();
        lore.add(header);
        if (lines.isEmpty()) {
            lore.add("&#6B7A8F(vide)");
        } else {
            lore.addAll(lines);
        }
        lore.add("");
        lore.add(hint);
        lore.add("");
        lore.add(action);
        return lore;
    }
}

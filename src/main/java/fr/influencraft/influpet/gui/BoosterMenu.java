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
        super(plugin, 6, plugin.text("editor.booster.title", "%id%", id));
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
        previewLore.add(left(t("booster.preview.receive")));
        previewLore.add(right(t("booster.preview.test")));
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

        set(19, icon("name_tag", t("booster.name.name"),
                join(lines("booster.name.lore", "%value%", b.name()), List.of(left(t("action.edit"))))), (p, c) ->
                ask(p, t("booster.name.prompt"), answer -> reg.setBooster(id, "name", answer)));

        set(20, icon("painting", t("texture.name"),
                join(lines("texture.lore", "%value%", b.itemId(), "%example%", "influcards:booster_basique"),
                        List.of(left(t("action.edit"))))), (p, c) ->
                ask(p, t("texture.prompt"), answer -> reg.setBooster(id, "item", answer.trim())));

        set(21, icon("book", t("description.name"),
                withHeader(b.lore(), lines("booster.description-hint"), left(t("action.edit")))), (p, c) ->
                ask(p, t("description.prompt"), answer -> reg.setBooster(id, "lore", Arrays.stream(answer.split("\\|"))
                        .map(String::trim).toList())));

        set(23, icon("paper", t("booster.draws.name", "%count%", String.valueOf(b.cards())),
                join(lines("booster.draws.lore"), List.of(left("+1"), right("-1"), shift("±10")))), (p, c) -> {
            int n = Math.max(1, Math.min(54, b.cards() + step(c)));
            reg.setBooster(id, "cards", n);
            refresh(p);
        });

        long count = reg.contents(b).size();
        set(24, icon("chest", t("booster.contents.name"),
                join(b.contents().isEmpty() ? lines("booster.contents.lore-all")
                                : lines("booster.contents.lore-chosen", "%count%", String.valueOf(count)),
                        List.of(left(t("booster.contents.action"))))), (p, c) -> new ContentMenu(plugin, id, 0).open(p));

        set(25, icon("influitems:search", t("booster.chances.name"),
                join(lines("booster.chances.lore"), List.of(left(t("booster.chances.action"))))), (p, c) -> {
            p.closeInventory();
            p.performCommand("influpet info booster " + id);
        });

        // Poids des raretés : ligne des cartes, ligne des familiers
        set(27, icon("influcards:carte_test", t("booster.card-weights.name"), lines("booster.card-weights.lore")));
        set(36, icon("bone", t("booster.pet-weights.name"), lines("booster.pet-weights.lore")));
        weightRow(player, b, false, RARITY_SLOTS);
        weightRow(player, b, true, PET_RARITY_SLOTS);

        double pc = b.petChance() * 100;
        set(22, icon("bone", t("booster.pet-chance.name", "%chance%", InfluPet.percent(b.petChance())),
                join(lines("booster.pet-chance.lore", "%chance%",
                                InfluPet.percent(1 - Math.pow(1 - reg.petChance(b), b.cards()))),
                        List.of(left(t("booster.pet-chance.more")), right(t("booster.pet-chance.less")),
                                shift(t("booster.pet-chance.shift"))))), (p, c) -> {
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
        set(53, icon("barrier", t("booster.delete.name"),
                join(lines("booster.delete.lore"), List.of(shift(t("action.delete"))))), (p, c) -> {
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
            ItemStack item = icon(weight > 0 ? "lime_dye" : "gray_dye", t("booster.weight.name", "%rarity%", r.tag(),
                            "%weight%", String.valueOf(weight)),
                    join(lines(pets ? "booster.weight.lore-pets" : "booster.weight.lore-cards",
                                    "%chance%", InfluPet.percent(reg.rarityChance(b, r, pets)),
                                    "%count%", String.valueOf(inContent)),
                            List.of(left("+1"), right("-1"), shift("±10"))));
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

    /** Valeur actuelle (lignes), puis l'aide, puis l'action (description d'un booster ou d'une carte). */
    static List<String> withHeader(Menu menu, List<String> current, List<String> hint, String action) {
        List<String> lore = new ArrayList<>(menu.lines("description.current"));
        lore.addAll(current.isEmpty() ? menu.lines("description.empty") : current);
        lore.add("");
        lore.addAll(hint);
        lore.add("");
        lore.add(action);
        return lore;
    }

    private List<String> withHeader(List<String> current, List<String> hint, String action) {
        return withHeader(this, current, hint, action);
    }
}

package fr.influencraft.fluxcommerce.trade;

import fr.influencraft.flux.api.MenuView;
import fr.influencraft.flux.api.dialog.Dialogs;
import fr.influencraft.flux.api.economy.Economy;
import fr.influencraft.flux.api.inventory.Items;
import fr.influencraft.flux.api.inventory.Lore;
import fr.influencraft.flux.api.list.ListEntry;
import fr.influencraft.flux.api.util.Experience;
import fr.influencraft.flux.api.util.Format;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiFunction;

/**
 * The trade window (menu {@code echange}, vanilla theme, background {@code bg_echange} of Flux's {@code gen_ui.py}):
 * the items of its lists, built here for each player, the texts written in its fields (placeholders
 * {@code %fluxtrade_...%}), its clicks ({@code [echange] ...}) and the amount forms.
 *
 * <pre>
 *   row 0      quit | my name (field)        | . | his name (field)        | how it works
 *   rows 1-3   my offer (4 x 3)              | ⇄ | his offer (4 x 3)
 *   row 4      xp   | my experience (field)  | countdown | his experience | xp
 *   row 5      money| my money (field)       | validate  | his money      | money
 * </pre>
 */
final class TradeMenu {

    static final String MENU = "echange";
    static final String MINE = "echange_mine", THEIRS = "echange_theirs", ME = "echange_me", PARTNER = "echange_partner",
            XP = "echange_xp", MONEY = "echange_money", XP_PARTNER = "echange_xp_partner",
            MONEY_PARTNER = "echange_money_partner", VALIDATE = "echange_validate", STATUS = "echange_status",
            INFO = "echange_info";
    static final List<String> LISTS = List.of(MINE, THEIRS, ME, PARTNER, XP, MONEY, XP_PARTNER, MONEY_PARTNER, VALIDATE,
            STATUS, INFO);
    /** Characters of a name that fit in a name field (menu font, about 6 px each). */
    private static final int NAME_CHARS = 9;

    private final TradeModule plugin;
    private final Trades trades;

    TradeMenu(TradeModule plugin, Trades trades) {
        this.plugin = plugin;
        this.trades = trades;
    }

    void register() {
        plugin.listOf(MINE, viewer -> offered(viewer, false));
        plugin.listOf(THEIRS, viewer -> offered(viewer, true));
        plugin.listOf(ME, viewer -> field(viewer, false, 3));
        plugin.listOf(PARTNER, viewer -> field(viewer, true, 3));
        one(XP, (viewer, t) -> experience(viewer, t, false));
        one(MONEY, (viewer, t) -> money(viewer, t, false));
        one(XP_PARTNER, (viewer, t) -> experience(viewer, t, true));
        one(MONEY_PARTNER, (viewer, t) -> money(viewer, t, true));
        one(VALIDATE, this::validate);
        one(STATUS, this::status);
        one(INFO, (viewer, t) -> info(viewer));
    }

    private void one(String id, BiFunction<Player, Trade, ItemStack> item) {
        plugin.itemOf(id, viewer -> {
            Trade t = trades.of(viewer);
            return t == null ? null : item.apply(viewer, t);
        });
    }

    // ------------------------------------------------------------------ items

    private List<ListEntry> offered(Player viewer, boolean partner) {
        Trade t = trades.of(viewer);
        List<ListEntry> out = new ArrayList<>();
        if (t == null) return out;
        Trade.Side s = t.side(viewer.getUniqueId());
        if (partner) s = t.other(s);
        // the item exactly as it is (its own tooltip): what is seen is what is traded
        for (Trade.Offered o : s.items) out.add(ListEntry.of(String.valueOf(o.id())).item(o.item().clone()));
        return out;
    }

    /** Invisible items over a name field: its tooltip tells where the player stands. */
    private List<ListEntry> field(Player viewer, boolean partner, int slots) {
        Trade t = trades.of(viewer);
        List<ListEntry> out = new ArrayList<>();
        if (t == null) return out;
        Trade.Side mine = t.side(viewer.getUniqueId());
        Trade.Side s = partner ? t.other(mine) : mine;
        Lore lore = Lore.create().description(text(partner ? "menu.partner-help" : "menu.me-help", "%player%", s.name));
        if (s.editing) lore.info(text("menu.editing"));
        else if (s.ready) lore.ok(text(partner ? "menu.partner-ready" : "menu.me-ready", "%player%", s.name));
        else lore.ko(text(partner ? "menu.partner-waiting" : "menu.me-waiting", "%player%", s.name));
        ItemStack item = Items.of("itemsadder:influitems:invisible", text(partner ? "menu.partner-name" : "menu.me-name",
                "%player%", s.name), lore.build(), viewer);
        for (int i = 0; i < slots; i++) out.add(ListEntry.of(String.valueOf(i)).item(item));
        return out;
    }

    private ItemStack experience(Player viewer, Trade t, boolean partner) {
        if (!plugin.getConfig().getBoolean("experience.enabled", true)) return null;
        Trade.Side mine = t.side(viewer.getUniqueId());
        Trade.Side s = partner ? t.other(mine) : mine;
        ItemStack item = new ItemStack(Material.EXPERIENCE_BOTTLE);
        if (s.xp > 0) item.setAmount((int) Math.max(1, Math.min(64, s.xpLevels > 0 ? s.xpLevels : Experience.levelOf(s.xp))));
        Lore lore = Lore.create();
        if (partner) {
            lore.description(text("menu.xp-partner-help", "%player%", s.name));
            lore.kv(text("menu.offered"), xpText(s));
            if (s.xp > 0) {
                long mineNow = Experience.total(viewer);
                lore.kv(text("menu.xp-after"), Format.decimal(Math.floor(Experience.levelOf(mineNow + s.xp) * 10) / 10)
                        + " " + text("menu.levels-word"));
            }
        } else {
            lore.description(text("menu.xp-help"));
            lore.kv(text("menu.offered"), xpText(s));
            lore.kv(text("menu.you-have"), text("menu.xp-amount", "%levels%", String.valueOf(viewer.getLevel()),
                    "%points%", Format.number(Experience.total(viewer))));
            lore.left(text("menu.edit"));
            if (s.xp > 0) lore.right(text("menu.clear"));
        }
        return Items.dress(item, text("menu.xp-name"), lore.build(), viewer);
    }

    private ItemStack money(Player viewer, Trade t, boolean partner) {
        if (!plugin.getConfig().getBoolean("money.enabled", true) || plugin.money() == null) return null;
        Trade.Side mine = t.side(viewer.getUniqueId());
        Trade.Side s = partner ? t.other(mine) : mine;
        ItemStack item = Items.of("itemsadder:influitems:bitblock", viewer);
        Lore lore = Lore.create();
        if (partner) {
            lore.description(text("menu.money-partner-help", "%player%", s.name));
            lore.kv(text("menu.offered"), plugin.money().format(s.money));
        } else {
            lore.description(text("menu.money-help"));
            lore.kv(text("menu.offered"), plugin.money().format(s.money));
            lore.kv(text("menu.you-have"), plugin.money().format(plugin.money().balance(viewer)));
            lore.left(text("menu.edit"));
            if (s.money > 0) lore.right(text("menu.clear"));
        }
        return Items.dress(item, text("menu.money-name", "%currency%", plugin.money().displayName()), lore.build(), viewer);
    }

    private ItemStack validate(Player viewer, Trade t) {
        Trade.Side s = t.side(viewer.getUniqueId());
        Trade.Side other = t.other(s);
        String material, name;
        Lore lore = Lore.create();
        if (s.ready) {
            material = "itemsadder:fluxui:btn_close_orange";
            name = text("menu.unvalidate-name");
            lore.description(text(t.counting() ? "menu.counting-help" : "menu.validated-help", "%player%", other.name));
            lore.left(text("menu.unvalidate"));
        } else if (t.a.empty() && t.b.empty()) {
            material = "itemsadder:fluxui:btn_check_off";
            name = text("menu.validate-name");
            lore.description(text("menu.nothing-help"));
        } else if (t.locked()) {
            material = "itemsadder:fluxui:btn_lock_grey";
            name = text("menu.locked-name");
            lore.description(text("menu.locked-help"));
        } else {
            material = "itemsadder:fluxui:btn_check_green";
            name = text("menu.validate-name");
            lore.description(text("menu.validate-help", "%seconds%", String.valueOf(plugin.getConfig().getInt("trade.confirm-seconds", 3))));
            if (other.ready) lore.ok(text("menu.partner-ready", "%player%", other.name));
            lore.left(text("menu.validate"));
        }
        return Items.dress(Items.of(material, viewer), name, lore.build(), viewer);
    }

    /** Middle of row 4: the countdown, once both validated. */
    private ItemStack status(Player viewer, Trade t) {
        if (!t.counting() || t.secondsLeft <= 0) return null;
        ItemStack item = Items.of("itemsadder:fluxui:btn_check_gold", viewer);
        item.setAmount(Math.max(1, Math.min(99, t.secondsLeft)));
        return Items.dress(item, text("menu.countdown-name", "%seconds%", String.valueOf(t.secondsLeft)),
                Lore.create().description(text("menu.countdown-help")).build(), viewer);
    }

    private ItemStack info(Player viewer) {
        Lore lore = Lore.create().description(text("menu.info-help",
                "%seconds%", String.valueOf(plugin.getConfig().getInt("trade.confirm-seconds", 3))));
        for (String line : plugin.messages().rawList("menu.info-lines")) lore.item(line);
        return Items.dress(Items.of("itemsadder:fluxui:btn_info_blue", viewer), text("menu.info-name"), lore.build(), viewer);
    }

    // ------------------------------------------------------------------ texts of the fields (widgets)

    /** {@code %fluxtrade_<params>%}: texts of the window's fields, and {@code accepting} for the settings menu. */
    String placeholder(Player viewer, String params) {
        if (viewer == null) return "";
        if (params.equals("accepting")) return plugin.accepting(viewer) ? "true" : "false";
        Trade t = trades.of(viewer);
        if (t == null) return "";
        Trade.Side mine = t.side(viewer.getUniqueId());
        Trade.Side other = t.other(mine);
        return switch (params.toLowerCase(Locale.ROOT)) {
            case "name_me" -> shortName(mine.name);
            case "name_partner" -> shortName(other.name);
            case "xp_me" -> fieldXp(mine);
            case "xp_partner" -> fieldXp(other);
            case "money_me" -> fieldMoney(mine.money);
            case "money_partner" -> fieldMoney(other.money);
            case "ready_me" -> mine.ready ? "yes" : "no";
            case "ready_partner" -> other.ready ? "yes" : "no";
            default -> "";
        };
    }

    private static String shortName(String name) {
        return name.length() <= NAME_CHARS ? name : name.substring(0, NAME_CHARS - 1) + ".";
    }

    private String fieldXp(Trade.Side s) {
        if (!plugin.getConfig().getBoolean("experience.enabled", true)) return "";
        if (s.xp <= 0) return "0";
        // about 9 characters fit in a field: big amounts in their short form (12K)
        return s.xpLevels > 0 ? text("field.levels", "%amount%", plain(Format.number(s.xpLevels)))
                : text("field.points", "%amount%", plain(s.xp < 10_000 ? Format.number(s.xp) : Economy.compact(s.xp)));
    }

    private String fieldMoney(long amount) {
        if (!plugin.getConfig().getBoolean("money.enabled", true)) return "";
        String full = plain(Format.number(amount));
        return full.length() <= 8 ? full : plain(Economy.compact(amount));
    }

    /** Grouping spaces of the number format (narrow no-break) as plain spaces: the menu font only has those. */
    private static String plain(String number) {
        return number.replace(' ', ' ').replace(' ', ' ');
    }

    private String xpText(Trade.Side s) {
        if (s.xp <= 0) return "0";
        String points = text("menu.xp-points", "%points%", Format.number(s.xp));
        return s.xpLevels > 0 ? text("menu.xp-levels", "%levels%", String.valueOf(s.xpLevels), "%points%", Format.number(s.xp)) : points;
    }

    // ------------------------------------------------------------------ clicks

    /** {@code [echange] <what> [arguments]}. */
    void click(Player p, MenuView view, String argument) {
        String[] args = argument == null ? new String[0] : argument.trim().split("\\s+");
        if (args.length == 0) return;
        Trade t = trades.of(p);
        if (t == null) return;
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                // left: the stack, right: one item, shift: the stack; drop key and middle click do nothing
                String kind = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "left";
                Integer slot = args.length < 2 ? null : integer(args[1]);
                if (slot != null && (kind.equals("left") || kind.equals("right") || kind.startsWith("shift"))) {
                    trades.add(p, slot, kind.equals("right"));
                }
            }
            case "take", "take-one" -> {
                Integer id = args.length < 2 ? null : integer(args[1]);
                if (id != null) trades.takeBack(p, id, args[0].equalsIgnoreCase("take-one"));
            }
            case "xp" -> editExperience(p, t);
            case "xp-clear" -> trades.setExperience(p, 0, -1);
            case "money" -> editMoney(p, t);
            case "money-clear" -> trades.setMoney(p, 0);
            case "validate" -> trades.toggleReady(p);
            case "cancel" -> trades.abort(t, "aborted-cancelled", p.getUniqueId(), null, null);
            case "closed" -> trades.windowClosed(p);
            default -> {
            }
        }
    }

    private static Integer integer(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ amount forms

    /** Experience given in levels (his top levels) or in points; kept in points, the truth of the trade. */
    private void editExperience(Player p, Trade t) {
        if (!plugin.getConfig().getBoolean("experience.enabled", true)) return;
        Trade.Side s = t.side(p.getUniqueId());
        String levelsLabel = text("form.unit-levels"), pointsLabel = text("form.unit-points");
        boolean inPoints = s.xp > 0 && s.xpLevels <= 0;
        long initial = s.xp <= 0 ? 0 : inPoints ? s.xp : s.xpLevels;
        int level = p.getLevel();
        long total = Experience.total(p);
        Dialogs.Form form = Dialogs.form(p, text("form.xp-title"))
                .body(text("form.xp-have", "%levels%", String.valueOf(level), "%points%", Format.number(total)))
                .body(text("form.xp-levels-help"))
                .choice("unit", text("form.xp-unit"), inPoints ? List.of(pointsLabel, levelsLabel) : List.of(levelsLabel, pointsLabel))
                .amount("amount", text("form.xp-amount"), initial, 0, Long.MAX_VALUE)
                .check(a -> {
                    long n = a.amount("amount");
                    boolean levels = !a.text("unit").equals(pointsLabel);
                    if (levels && n > p.getLevel()) return text("form.xp-too-many-levels", "%levels%", String.valueOf(p.getLevel()));
                    if (!levels && n > Experience.total(p)) return text("form.xp-too-many-points", "%points%", Format.number(Experience.total(p)));
                    return null;
                });
        edit(p, s, form, a -> {
            long n = a.amount("amount");
            if (a.text("unit").equals(pointsLabel)) trades.setExperience(p, n, -1);
            else trades.setExperience(p, Experience.pointsOfLevels(p, (int) Math.min(Integer.MAX_VALUE, n)), (int) n);
        });
    }

    private void editMoney(Player p, Trade t) {
        if (!plugin.getConfig().getBoolean("money.enabled", true) || plugin.money() == null) return;
        Trade.Side s = t.side(p.getUniqueId());
        long balance = (long) Math.floor(plugin.money().balance(p));
        long max = plugin.getConfig().getLong("money.max", 0);
        Dialogs.Form form = Dialogs.form(p, text("form.money-title", "%currency%", plugin.money().displayName()))
                .body(text("form.money-have", "%balance%", plugin.money().format(balance)))
                .amount("amount", text("form.money-amount"), s.money, 0, max > 0 ? max : Long.MAX_VALUE)
                .check(a -> a.amount("amount") > (long) Math.floor(plugin.money().balance(p))
                        ? text("form.money-too-much", "%balance%", plugin.money().format(plugin.money().balance(p))) : null);
        edit(p, s, form, a -> trades.setMoney(p, a.amount("amount")));
    }

    /**
     * Shows a form over the trade: his window closes (the trade stays, his validation is cancelled), the form opens,
     * then the window comes back, whatever the answer.
     */
    private void edit(Player p, Trade.Side s, Dialogs.Form form, java.util.function.Consumer<Dialogs.Answer> apply) {
        Trade t = trades.of(p);
        if (t == null) return;
        s.editing = true;
        s.editingSince = System.currentTimeMillis();
        if (s.ready) {
            s.ready = false;
            t.stopTasks();
        }
        trades.refresh(t);
        p.closeInventory();
        form.buttons(text("form.save"), text("form.cancel"))
                .onSubmit((pl, a) -> back(pl, s, () -> apply.accept(a)))
                .onCancel(pl -> back(pl, s, null))
                .open();
    }

    private void back(Player p, Trade.Side s, Runnable apply) {
        s.editing = false;
        Trade t = trades.of(p);
        if (t == null || t.over || t.side(p.getUniqueId()) != s) return;
        if (apply != null) apply.run();
        if (!trades.showWindow(p)) trades.abort(t, "aborted-window");
        else trades.refresh(t);
    }

    private String text(String key, String... vars) {
        return plugin.messages().raw(key, vars);
    }
}

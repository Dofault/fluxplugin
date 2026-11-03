package fr.influencraft.flux.api.dialog;

import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.util.Format;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Vanilla dialog windows (Paper 26.2) for every input of the suite, instead of "type your answer in the chat":
 * forms with text fields, check boxes and drop-down lists, confirmations, one-click copy and link.
 *
 * <pre>
 *   Dialogs.form(player, "Deposit")
 *       .body("How much do you want to deposit?")
 *       .amount("amount", "Amount", 0, 1, balance)
 *       .onSubmit((p, answer) -> deposit(p, answer.amount("amount")))
 *       .onCancel(p -> FluxApi.open(p, "bank"))
 *       .open();
 * </pre>
 * Every amount, price or quantity asked to a player goes through {@link Form#amount} (or {@link Form#slider} for a
 * value on a short scale, such as a rate): the form checks the value itself and, when it is wrong, opens again with
 * the error and what the player typed, so a plugin only receives valid values.
 * Callbacks run on the main thread, at most once, within 5 minutes; Escape counts as the cancel button
 * ({@link Form#onCancel}). Colours of titles,
 * texts and buttons come from Flux's config ({@code dialogs.*}).
 */
public final class Dialogs {

    private static volatile Map<String, String> style = Map.ofEntries(
            Map.entry("title", "&#FFD94D"), Map.entry("text", "&f"), Map.entry("label", "&f"),
            Map.entry("option", "&f"), Map.entry("toggle", "&f"), Map.entry("confirm", "&f"), Map.entry("cancel", "&f"), Map.entry("hover", "&f"),
            Map.entry("line-length", "40"),
            Map.entry("link", "&#2BC4E8&n"), Map.entry("confirm-label", "Confirm"), Map.entry("cancel-label", "Cancel"),
            Map.entry("copy-label", "Copy"), Map.entry("link-label", "Open the link"), Map.entry("error", "&#FF5C5C"),
            Map.entry("amount-invalid", "Write a number, e.g. 2500 or 2.5k."),
            Map.entry("amount-min", "At least %min%."), Map.entry("amount-max", "At most %max%."));

    private Dialogs() {
    }

    /** Called by Flux from its config. */
    public static void configure(Map<String, String> colorsAndLabels) {
        Map<String, String> merged = new HashMap<>(style);
        merged.putAll(colorsAndLabels);
        style = Map.copyOf(merged);
    }

    static String style(String key) {
        return style.getOrDefault(key, "");
    }

    /** A colour or label of the dialogs ({@code dialogs.*} of Flux's config), e.g. for a dialog written as a data pack. */
    public static String color(String key) {
        return style(key);
    }

    /**
     * A description shown on hover (check box, button), cut into lines of {@code dialogs.line-length} characters
     * ({@link fr.influencraft.flux.api.util.Format#wrap}), in the hover colour.
     */
    public static String hover(String description) {
        int max;
        try {
            max = Integer.parseInt(style("line-length"));
        } catch (NumberFormatException e) {
            max = 40;
        }
        StringBuilder out = new StringBuilder();
        for (String line : fr.influencraft.flux.api.util.Format.wrap(description, max)) {
            if (!out.isEmpty()) out.append('\n');
            out.append(style("hover")).append(line);
        }
        return out.toString();
    }

    public static Form form(Player player, String title) {
        return new Form(player, title);
    }

    /** Yes / no question. */
    public static void confirm(Player player, String title, String question, Consumer<Player> onYes, Consumer<Player> onNo) {
        form(player, title).body(question).onSubmit((p, a) -> onYes.accept(p)).onCancel(onNo).open();
    }

    /** One-button window copying {@code text} to the player's clipboard. */
    public static void copy(Player player, String title, String text) {
        notice(player, title, style("link") + text, style("copy-label"), ClickEvent.copyToClipboard(text));
    }

    /** One-button window opening {@code url} (the client still shows its own vanilla confirmation). */
    public static void link(Player player, String title, String url) {
        notice(player, title, style("link") + url, style("link-label"), ClickEvent.openUrl(url));
    }

    private static void notice(Player player, String title, String body, String button, ClickEvent click) {
        ActionButton ok = ActionButton.create(Texts.parse("&f" + button, player), null, 200, DialogAction.staticAction(click));
        DialogBase base = DialogBase.create(Texts.parse(style("title") + title, player), null, true, false,
                DialogBase.DialogAfterAction.CLOSE, List.of(DialogBody.plainMessage(Texts.parse(body, player))), List.of());
        player.showDialog(Dialog.create(f -> f.empty().base(base).type(DialogType.notice(ok))));
    }

    /** A button of {@link #links}: opens a URL or runs a command as the player; its description shows on hover. */
    public record Link(String label, String description, String url, String command) {
    }

    /**
     * A window of buttons (help, links of the server): each one opens its URL (the client asks for confirmation) or
     * runs its command as the player, then the window closes. Buttons on {@code columns} columns, in order.
     */
    public static void links(Player player, String title, List<String> lines, List<Link> links, int columns) {
        List<ActionButton> buttons = new ArrayList<>();
        for (Link l : links) {
            ClickEvent click = l.url() != null && !l.url().isBlank() ? ClickEvent.openUrl(l.url())
                    : ClickEvent.callback(audience -> sync(() -> {
                        if (player.isOnline()) player.performCommand(l.command().startsWith("/") ? l.command().substring(1) : l.command());
                    }), ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build());
            buttons.add(ActionButton.create(Texts.parse(style("option") + l.label(), player),
                    l.description() == null || l.description().isBlank() ? null : Texts.parse(hover(l.description()), player),
                    150, DialogAction.staticAction(click)));
        }
        List<DialogBody> body = new ArrayList<>();
        for (String line : lines) body.add(DialogBody.plainMessage(Texts.parse(style("text") + line, player), 300));
        DialogBase base = DialogBase.create(Texts.parse(style("title") + title, player), null, true, false,
                DialogBase.DialogAfterAction.CLOSE, body, List.of());
        player.showDialog(Dialog.create(f -> f.empty().base(base).type(DialogType.multiAction(buttons, null, Math.max(1, columns)))));
    }

    static void sync(Runnable task) {
        Plugin flux = Bukkit.getPluginManager().getPlugin("Flux");
        if (flux != null && flux.isEnabled()) Bukkit.getScheduler().runTask(flux, task);
    }

    /** Values typed in a form. */
    public static final class Answer {
        private final Map<String, String> texts;
        private final Map<String, Boolean> toggles;
        private final Map<String, Float> numbers;
        private final String button;

        Answer(Map<String, String> texts, Map<String, Boolean> toggles, Map<String, Float> numbers, String button) {
            this.texts = texts;
            this.toggles = toggles;
            this.numbers = numbers;
            this.button = button;
        }

        /** Id of the button clicked ({@code "confirm"} in a confirm/cancel form, else an id of {@link Form#action}). */
        public String button() {
            return button;
        }

        /** Trimmed text of a field or the chosen option of a list ({@code ""} when empty). */
        public String text(String id) {
            String t = texts.get(id);
            return t == null ? "" : t.trim();
        }

        public boolean toggle(String id) {
            Boolean b = toggles.get(id);
            return b != null && b;
        }

        /** Whole number of a field, or null when empty or unreadable. */
        public Integer integer(String id) {
            try {
                return Integer.parseInt(text(id));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        /** Value of an {@link Form#amount} field ("10 000", "10k", "2,5k"): always within its bounds in onSubmit. */
        public long amount(String id) {
            return Format.parseAmount(text(id));
        }

        /** Value of a {@link Form#slider}, or null when the form has none of that id. */
        public Float number(String id) {
            return numbers.get(id);
        }
    }

    /** A form being built; nothing is shown before {@link #open()}. */
    public static final class Form {
        private final Player player;
        private final String title;
        /** Lines of text ({@code String}) and lines of clickable choices ({@code String[][]}), in order. */
        private final List<Object> body = new ArrayList<>();
        /**
         * Fields as recipes: each one builds its input from the previous answer (null on the first display), so a
         * window reopened after a wrong value keeps what the player typed.
         */
        private final List<Function<Answer, DialogInput>> inputs = new ArrayList<>();
        private final List<String> textIds = new ArrayList<>();
        private final List<String> toggleIds = new ArrayList<>();
        private final List<String> numberIds = new ArrayList<>();
        private final Map<String, List<String>> choices = new HashMap<>();
        private final List<String> required = new ArrayList<>();
        /** Amount fields: id, label, min, max. */
        private final List<Object[]> amounts = new ArrayList<>();
        private final List<Function<Answer, String>> checks = new ArrayList<>();
        private String confirm = style("confirm-label"), cancel = style("cancel-label");
        private final List<String[]> actions = new ArrayList<>();
        private int columns = 2;
        private boolean closable = true;
        private int width = 200;
        private BiConsumer<Player, Answer> onSubmit = (p, a) -> {
        };
        private Consumer<Player> onCancel = p -> {
        };
        /** Set when the window is shown again after a wrong value. */
        private Answer typed;
        private String error;

        private Form(Player player, String title) {
            this.player = player;
            this.title = title;
        }

        public Form body(String line) {
            body.add(line);
            return this;
        }

        /** An item shown in the text (its tooltip on mouse over), e.g. the item a form is about; {@code text} beside it. */
        public Form item(org.bukkit.inventory.ItemStack item, String text) {
            if (item != null && !item.getType().isAir()) body.add(new Object[]{item.clone(), text});
            return this;
        }

        /** Empty lines, to bring the text down towards the middle of the screen. */
        public Form spacer(int lines) {
            for (int i = 0; i < lines; i++) body.add(" ");
            return this;
        }

        /**
         * A line of clickable choices inside the text, where it is written (above the fields), e.g. answers that need no
         * field. A click closes the window and submits with {@link Answer#button()} = the choice id (fields left empty).
         *
         * @param idsAndLabels id, label, id, label...
         */
        public Form choiceLine(String... idsAndLabels) {
            List<String[]> line = new ArrayList<>();
            for (int i = 0; i + 1 < idsAndLabels.length; i += 2) line.add(new String[]{idsAndLabels[i], idsAndLabels[i + 1]});
            body.add(line.toArray(new String[0][]));
            return this;
        }

        /** One-line text field. */
        public Form input(String id, String label, String initial, int maxLength) {
            return text(id, label, initial, maxLength, 220);
        }

        /** Like {@link #input}, but an empty value counts as a cancel. */
        public Form requiredInput(String id, String label, String initial, int maxLength) {
            required.add(id);
            return input(id, label, initial, maxLength);
        }

        /** Short field (half width), e.g. a coordinate. */
        public Form smallInput(String id, String label, String initial, int maxLength) {
            return text(id, label, initial, maxLength, 110);
        }

        private Form text(String id, String label, String initial, int maxLength, int fieldWidth) {
            inputs.add(t -> DialogInput.text(id, Texts.parse(style("label") + label, player))
                    .initial(t != null ? t.text(id) : initial == null ? "" : initial)
                    .maxLength(Math.max(1, maxLength)).width(fieldWidth).build());
            textIds.add(id);
            return this;
        }

        /**
         * An amount, price or quantity, typed as players write it ("2500", "10 000", "10k", "2,5k"). The form refuses
         * an unreadable value or one outside {@code min..max} itself: it opens again with the error and the typed
         * text, so {@link Answer#amount} is always valid in onSubmit. {@code initial} &lt;= 0 leaves the field empty.
         */
        public Form amount(String id, String label, long initial, long min, long max) {
            amounts.add(new Object[]{id, label, min, max});
            return text(id, label, initial > 0 ? String.valueOf(initial) : "", 16, 220);
        }

        /**
         * A value on a short scale picked with the mouse (a rate, a level), from {@code min} to {@code max} by
         * {@code step}. {@code format}: how the client writes the label and the value, e.g. {@code "%s : %s %%"}
         * (null = vanilla "Label: value"). Read it with {@link Answer#number}.
         */
        public Form slider(String id, String label, float min, float max, float step, float initial, String format) {
            inputs.add(t -> {
                Float v = t != null && t.number(id) != null ? t.number(id) : initial;
                var b = DialogInput.numberRange(id, Texts.parse(style("label") + label, player), min, max)
                        .initial(Math.max(min, Math.min(max, v))).width(220);
                if (step > 0) b.step(step);
                if (format != null) b.labelFormat(format);
                return b.build();
            });
            numberIds.add(id);
            return this;
        }

        public Form toggle(String id, String label, boolean initial) {
            return toggle(id, label, initial, null);
        }

        /** Check box whose label shows {@code description} when the mouse is over it. */
        public Form toggle(String id, String label, boolean initial, String description) {
            net.kyori.adventure.text.Component text = Texts.parse(style("toggle") + label, player);
            if (description != null && !description.isBlank()) {
                text = text.hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Texts.parse(hover(description), player)));
            }
            net.kyori.adventure.text.Component shown = text;
            inputs.add(t -> DialogInput.bool(id, shown, t != null ? t.toggle(id) : initial, "true", "false"));
            toggleIds.add(id);
            return this;
        }

        /** Drop-down list (at most 100 options, the first one selected); the answer is the chosen option. */
        public Form choice(String id, String label, List<String> options) {
            if (options.isEmpty()) return this;
            List<String> kept = List.copyOf(options.subList(0, Math.min(100, options.size())));
            inputs.add(t -> {
                int picked = t != null ? Math.max(0, kept.indexOf(t.text(id))) : 0;
                List<SingleOptionDialogInput.OptionEntry> entries = new ArrayList<>();
                for (int i = 0; i < kept.size(); i++) {
                    entries.add(SingleOptionDialogInput.OptionEntry.create("o" + i, Texts.parse(style("option") + kept.get(i), player), i == picked));
                }
                return DialogInput.singleOption(id, Texts.parse(style("label") + label, player), entries).width(220).build();
            });
            choices.put(id, kept);
            return this;
        }

        /**
         * A rule of the plugin checked like the amounts (e.g. a minimum price that depends on a check box): return the
         * error to show, or null when the answer is fine.
         */
        public Form check(Function<Answer, String> rule) {
            checks.add(rule);
            return this;
        }

        public Form buttons(String confirmLabel, String cancelLabel) {
            this.confirm = confirmLabel;
            this.cancel = cancelLabel;
            return this;
        }

        /**
         * Adds an action button instead of the confirm/cancel pair: each button submits the form, {@link Answer#button()}
         * tells which one. Buttons are laid out on {@link #columns} columns, below the fields.
         */
        public Form action(String id, String label) {
            actions.add(new String[]{id, label});
            return this;
        }

        public Form columns(int count) {
            this.columns = Math.max(1, count);
            return this;
        }

        /** Width of the text lines in pixels (1 to 1024, Minecraft default 200): wider keeps a sentence on one line. */
        public Form width(int pixels) {
            this.width = Math.max(1, Math.min(1024, pixels));
            return this;
        }

        /** False: Escape does not close the window, the player has to click a button. */
        public Form closable(boolean canClose) {
            this.closable = canClose;
            return this;
        }

        public Form onSubmit(BiConsumer<Player, Answer> action) {
            this.onSubmit = action;
            return this;
        }

        /** Cancel button, Escape, or an empty required field. */
        public Form onCancel(Consumer<Player> action) {
            this.onCancel = action;
            return this;
        }

        public void open() {
            ClickCallback.Options once = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build();
            ActionButton ok = ActionButton.create(Texts.parse(style("confirm") + confirm, player), null, 100,
                    DialogAction.customClick((view, audience) -> submit(view, "confirm", true), once));
            ActionButton no = ActionButton.create(Texts.parse(style("cancel") + cancel, player), null, 100,
                    DialogAction.customClick((view, audience) -> sync(() -> {
                        if (player.isOnline()) onCancel.accept(player);
                    }), once));
            List<DialogBody> lines = new ArrayList<>();
            for (Object entry : body) {
                if (entry instanceof String l) {
                    lines.add(DialogBody.plainMessage(Texts.parse(style("text") + l, player), width));
                    continue;
                }
                if (entry instanceof Object[] shown && shown.length == 2 && shown[0] instanceof org.bukkit.inventory.ItemStack) {
                    var item = DialogBody.item((org.bukkit.inventory.ItemStack) shown[0]).showTooltip(true).showDecorations(true);
                    if (shown[1] instanceof String t && !t.isBlank()) {
                        item.description(DialogBody.plainMessage(Texts.parse(style("text") + t, player), Math.max(1, width - 24)));
                    }
                    lines.add(item.build());
                    continue;
                }
                net.kyori.adventure.text.TextComponent.Builder line = net.kyori.adventure.text.Component.text();
                boolean first = true;
                for (String[] choice : (String[][]) entry) {
                    if (!first) line.append(net.kyori.adventure.text.Component.text("     "));
                    first = false;
                    String id = choice[0];
                    line.append(Texts.parse(style("option") + "&l[ " + choice[1] + " ]", player)
                            .clickEvent(ClickEvent.callback(audience -> sync(() -> {
                                if (!player.isOnline()) return;
                                player.closeDialog();
                                onSubmit.accept(player, new Answer(Map.of(), Map.of(), Map.of(), id));
                            }), once)));
                }
                lines.add(DialogBody.plainMessage(line.build(), 250));
            }
            if (error != null) lines.add(DialogBody.plainMessage(Texts.parse(style("error") + error, player), width));
            List<DialogInput> fields = new ArrayList<>();
            for (Function<Answer, DialogInput> f : inputs) fields.add(f.apply(typed));
            DialogBase base = DialogBase.create(Texts.parse(style("title") + title, player), null, closable, false,
                    DialogBase.DialogAfterAction.CLOSE, lines, fields);
            if (actions.isEmpty()) {
                player.showDialog(Dialog.create(f -> f.empty().base(base).type(DialogType.confirmation(ok, no))));
                return;
            }
            List<ActionButton> buttons = new ArrayList<>();
            for (String[] a : actions) {
                String id = a[0];
                buttons.add(ActionButton.create(Texts.parse(style("option") + a[1], player), null, 150,
                        DialogAction.customClick((view, audience) -> submit(view, id, false), once)));
            }
            // Escape (or closing) runs onCancel, like the cancel button of a confirm/cancel form
            ActionButton exit = ActionButton.create(Texts.parse(style("cancel") + cancel, player), null, 100,
                    DialogAction.customClick((view, audience) -> sync(() -> {
                        if (player.isOnline()) onCancel.accept(player);
                    }), once));
            player.showDialog(Dialog.create(f -> f.empty().base(base).type(DialogType.multiAction(buttons, exit, columns))));
        }

        /** Reads the window, then cancels (empty required field), reopens with the error, or submits. */
        private void submit(io.papermc.paper.dialog.DialogResponseView view, String button, boolean checkRequired) {
            Map<String, String> texts = new HashMap<>();
            Map<String, Boolean> toggles = new HashMap<>();
            Map<String, Float> numbers = new HashMap<>();
            for (String id : textIds) texts.put(id, view.getText(id));
            for (String id : toggleIds) toggles.put(id, view.getBoolean(id));
            for (String id : numberIds) numbers.put(id, view.getFloat(id));
            readChoices(view, texts);
            boolean missing = checkRequired && required.stream().anyMatch(id -> texts.get(id) == null || texts.get(id).isBlank());
            Answer answer = new Answer(texts, toggles, numbers, button);
            sync(() -> {
                if (!player.isOnline()) return;
                if (missing) {
                    onCancel.accept(player);
                    return;
                }
                String wrong = validate(answer);
                if (wrong != null) {
                    typed = answer;
                    error = wrong;
                    open();
                    return;
                }
                onSubmit.accept(player, answer);
            });
        }

        /** First error of the answer (amount fields, then the plugin's checks), or null. */
        private String validate(Answer answer) {
            for (Object[] a : amounts) {
                String id = (String) a[0];
                long min = (long) a[2], max = (long) a[3];
                long v = answer.amount(id);
                String wrong = v < 0 ? style("amount-invalid")
                        : v < min ? style("amount-min").replace("%min%", Format.number(min))
                        : v > max ? style("amount-max").replace("%max%", Format.number(max))
                        : null;
                if (wrong != null) return amounts.size() > 1 ? a[1] + " : " + wrong : wrong;
            }
            for (Function<Answer, String> rule : checks) {
                String wrong = rule.apply(answer);
                if (wrong != null) return wrong;
            }
            return null;
        }

        /** Chosen option of each drop-down list, by its label (read by both button layouts). */
        private void readChoices(io.papermc.paper.dialog.DialogResponseView view, Map<String, String> texts) {
            for (Map.Entry<String, List<String>> c : choices.entrySet()) {
                String picked = view.getText(c.getKey());
                try {
                    texts.put(c.getKey(), c.getValue().get(Integer.parseInt(picked.substring(1))));
                } catch (RuntimeException ignored) {
                    // nothing picked
                }
            }
        }
    }
}

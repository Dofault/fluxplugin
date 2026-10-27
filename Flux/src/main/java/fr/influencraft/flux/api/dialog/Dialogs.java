package fr.influencraft.flux.api.dialog;

import fr.influencraft.flux.api.text.Texts;
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

/**
 * Vanilla dialog windows (Paper 26.2) for every input of the suite, instead of "type your answer in the chat":
 * forms with text fields, check boxes and drop-down lists, confirmations, one-click copy and link.
 *
 * <pre>
 *   Dialogs.form(player, "Deposit")
 *       .body("How much do you want to deposit?")
 *       .input("amount", "Amount", "", 12)
 *       .onSubmit((p, answer) -> deposit(p, Format.parseAmount(answer.text("amount"))))
 *       .onCancel(p -> FluxApi.open(p, "bank"))
 *       .open();
 * </pre>
 * Callbacks run on the main thread, at most once, within 5 minutes; Escape just closes the window. Colours of titles,
 * texts and buttons come from Flux's config ({@code dialogs.*}).
 */
public final class Dialogs {

    private static volatile Map<String, String> style = Map.ofEntries(
            Map.entry("title", "&#FFD94D"), Map.entry("text", "&#B376E5"), Map.entry("label", "&#B376E5"),
            Map.entry("option", "&f"), Map.entry("confirm", "&#2ECF7B"), Map.entry("cancel", "&#FF5C5C"),
            Map.entry("link", "&#2BC4E8&n"), Map.entry("confirm-label", "Confirm"), Map.entry("cancel-label", "Cancel"),
            Map.entry("copy-label", "Copy"), Map.entry("link-label", "Open the link"));

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

    static void sync(Runnable task) {
        Plugin flux = Bukkit.getPluginManager().getPlugin("Flux");
        if (flux != null && flux.isEnabled()) Bukkit.getScheduler().runTask(flux, task);
    }

    /** Values typed in a form. */
    public static final class Answer {
        private final Map<String, String> texts;
        private final Map<String, Boolean> toggles;
        private final String button;

        Answer(Map<String, String> texts, Map<String, Boolean> toggles) {
            this(texts, toggles, "confirm");
        }

        Answer(Map<String, String> texts, Map<String, Boolean> toggles, String button) {
            this.texts = texts;
            this.toggles = toggles;
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
    }

    /** A form being built; nothing is shown before {@link #open()}. */
    public static final class Form {
        private final Player player;
        private final String title;
        /** Lines of text ({@code String}) and lines of clickable choices ({@code String[][]}), in order. */
        private final List<Object> body = new ArrayList<>();
        private final List<DialogInput> inputs = new ArrayList<>();
        private final List<String> textIds = new ArrayList<>();
        private final List<String> toggleIds = new ArrayList<>();
        private final Map<String, List<String>> choices = new HashMap<>();
        private final List<String> required = new ArrayList<>();
        private String confirm = style("confirm-label"), cancel = style("cancel-label");
        private final List<String[]> actions = new ArrayList<>();
        private int columns = 2;
        private boolean closable = true;
        private int width = 200;
        private BiConsumer<Player, Answer> onSubmit = (p, a) -> {
        };
        private Consumer<Player> onCancel = p -> {
        };

        private Form(Player player, String title) {
            this.player = player;
            this.title = title;
        }

        public Form body(String line) {
            body.add(line);
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
            inputs.add(DialogInput.text(id, Texts.parse(style("label") + label, player))
                    .initial(initial == null ? "" : initial).maxLength(Math.max(1, maxLength)).width(220).build());
            textIds.add(id);
            return this;
        }

        /** Like {@link #input}, but an empty value counts as a cancel. */
        public Form requiredInput(String id, String label, String initial, int maxLength) {
            required.add(id);
            return input(id, label, initial, maxLength);
        }

        /** Short field (half width), e.g. a coordinate. */
        public Form smallInput(String id, String label, String initial, int maxLength) {
            inputs.add(DialogInput.text(id, Texts.parse(style("label") + label, player))
                    .initial(initial == null ? "" : initial).maxLength(Math.max(1, maxLength)).width(110).build());
            textIds.add(id);
            return this;
        }

        public Form toggle(String id, String label, boolean initial) {
            inputs.add(DialogInput.bool(id, Texts.parse(style("option") + label, player), initial, "true", "false"));
            toggleIds.add(id);
            return this;
        }

        /** Drop-down list (at most 100 options, the first one selected); the answer is the chosen option. */
        public Form choice(String id, String label, List<String> options) {
            if (options.isEmpty()) return this;
            List<SingleOptionDialogInput.OptionEntry> entries = new ArrayList<>();
            for (int i = 0; i < options.size() && i < 100; i++) {
                entries.add(SingleOptionDialogInput.OptionEntry.create("o" + i, Texts.parse(style("option") + options.get(i), player), i == 0));
            }
            inputs.add(DialogInput.singleOption(id, Texts.parse(style("label") + label, player), entries).width(220).build());
            choices.put(id, List.copyOf(options));
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
                    DialogAction.customClick((view, audience) -> {
                        Map<String, String> texts = new HashMap<>();
                        Map<String, Boolean> toggles = new HashMap<>();
                        for (String id : textIds) texts.put(id, view.getText(id));
                        for (String id : toggleIds) toggles.put(id, view.getBoolean(id));
                        for (Map.Entry<String, List<String>> c : choices.entrySet()) {
                            String picked = view.getText(c.getKey());
                            try {
                                texts.put(c.getKey(), c.getValue().get(Integer.parseInt(picked.substring(1))));
                            } catch (RuntimeException ignored) {
                                // nothing picked
                            }
                        }
                        boolean missing = required.stream().anyMatch(id -> texts.get(id) == null || texts.get(id).isBlank());
                        Answer answer = new Answer(texts, toggles);
                        sync(() -> {
                            if (!player.isOnline()) return;
                            if (missing) onCancel.accept(player);
                            else onSubmit.accept(player, answer);
                        });
                    }, once));
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
                                onSubmit.accept(player, new Answer(Map.of(), Map.of(), id));
                            }), once)));
                }
                lines.add(DialogBody.plainMessage(line.build(), 250));
            }
            DialogBase base = DialogBase.create(Texts.parse(style("title") + title, player), null, closable, false,
                    DialogBase.DialogAfterAction.CLOSE, lines, List.copyOf(inputs));
            if (actions.isEmpty()) {
                player.showDialog(Dialog.create(f -> f.empty().base(base).type(DialogType.confirmation(ok, no))));
                return;
            }
            List<ActionButton> buttons = new ArrayList<>();
            for (String[] a : actions) {
                String id = a[0];
                buttons.add(ActionButton.create(Texts.parse(style("option") + a[1], player), null, 150,
                        DialogAction.customClick((view, audience) -> {
                            Map<String, String> texts = new HashMap<>();
                            Map<String, Boolean> toggles = new HashMap<>();
                            for (String t : textIds) texts.put(t, view.getText(t));
                            for (String t : toggleIds) toggles.put(t, view.getBoolean(t));
                            Answer answer = new Answer(texts, toggles, id);
                            sync(() -> {
                                if (player.isOnline()) onSubmit.accept(player, answer);
                            });
                        }, once)));
            }
            player.showDialog(Dialog.create(f -> f.empty().base(base).type(DialogType.multiAction(buttons, null, columns))));
        }
    }
}

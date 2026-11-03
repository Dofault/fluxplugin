package fr.influencraft.flux.api.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Paged chat help of a command of the suite ({@code /pw help}, {@code /echoppe help}...), the same as Lands' help: a
 * struck-through header with clickable « » arrows and the page, one line per command (a click runs it, or writes it in
 * the chat bar when it needs an argument), a footer with Previous | Next when there is more than one page. The arrows
 * go round (previous from the first page = last page). Only the commands the sender may use are listed.
 *
 * <p>Words and look come from the plugin's lang file: {@code help.header} (or {@code help.header-admin}),
 * {@code help.line} ({@code %command%}, {@code %description%}), {@code help.footer}, {@code help.previous-arrow},
 * {@code help.next-arrow}, {@code help.previous}, {@code help.next}, {@code help.previous-hover},
 * {@code help.next-hover}, {@code help.hover-run}, {@code help.hover-suggest}, and {@code help.commands.<key>} for each
 * description.</p>
 */
public final class ChatHelp {

    /**
     * A command of the help.
     *
     * @param usage      what follows the label ({@code create <name>}), empty for the label alone
     * @param key        its description: {@code help.commands.<key>}
     * @param permission needed to see it, null for everybody
     */
    public record Entry(String usage, String key, String permission) {
    }

    private static final int PER_PAGE = 8;

    private ChatHelp() {
    }

    /**
     * Shows one page.
     *
     * @param label  the command as typed ({@code pw}, {@code echoppe})
     * @param base   what pages it after the label ({@code help} or {@code admin help})
     * @param allows whether the sender may use a permission (the plugin's own rule: admin nodes...)
     */
    public static void show(CommandSender to, Messages messages, String label, List<Entry> all, String base, boolean admin,
                            int page, Predicate<String> allows) {
        List<Entry> entries = new ArrayList<>();
        for (Entry e : all) if (e.permission() == null || allows.test(e.permission())) entries.add(e);
        int pages = Math.max(1, (entries.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.max(1, Math.min(page, pages));
        String previousPage = "/" + label + " " + base + " " + (page > 1 ? page - 1 : pages);
        String nextPage = "/" + label + " " + base + " " + (page < pages ? page + 1 : 1);
        Component previous = link(messages.raw("help.previous-arrow"), messages.raw("help.previous-hover"), previousPage);
        Component next = link(messages.raw("help.next-arrow"), messages.raw("help.next-hover"), nextPage);
        to.sendMessage(fill(messages.raw(admin ? "help.header-admin" : "help.header", "%page%", String.valueOf(page),
                "%pages%", String.valueOf(pages)), previous, next));
        for (Entry e : entries.subList((page - 1) * PER_PAGE, Math.min(entries.size(), page * PER_PAGE))) {
            String typed = "/" + label + (e.usage().isEmpty() ? "" : " " + e.usage());
            // an argument to type (<required> or [optional]): written in the chat bar, else run on click
            int cut = firstOf(typed, '<', '[');
            boolean needsArgument = cut >= 0;
            String written = needsArgument ? typed.substring(0, cut) : typed;
            // "<warp>" written as text, not read as a colour tag
            String shown = typed.replace("<", "\\<");
            Component line = Texts.parse(messages.raw("help.line", "%command%", shown, "%description%", messages.raw("help.commands." + e.key())))
                    .hoverEvent(HoverEvent.showText(Texts.parse(messages.raw(needsArgument ? "help.hover-suggest" : "help.hover-run"))))
                    .clickEvent(needsArgument ? ClickEvent.suggestCommand(written) : ClickEvent.runCommand(typed));
            to.sendMessage(line);
        }
        if (pages > 1) {
            to.sendMessage(fill(messages.raw("help.footer"), link(messages.raw("help.previous"), messages.raw("help.previous-hover"), previousPage),
                    link(messages.raw("help.next"), messages.raw("help.next-hover"), nextPage)));
        }
    }

    private static int firstOf(String text, char a, char b) {
        int i = text.indexOf(a), j = text.indexOf(b);
        return i < 0 ? j : j < 0 ? i : Math.min(i, j);
    }

    private static Component link(String text, String hover, String command) {
        return Texts.parse(text).hoverEvent(HoverEvent.showText(Texts.parse(hover))).clickEvent(ClickEvent.runCommand(command));
    }

    /** A template with {@code %previous%} and {@code %next%} replaced by components (the rest parsed piece by piece). */
    private static Component fill(String template, Component previous, Component next) {
        Component out = Component.empty();
        String rest = template;
        while (!rest.isEmpty()) {
            int p = rest.indexOf("%previous%"), n = rest.indexOf("%next%");
            int at = p < 0 ? n : n < 0 ? p : Math.min(p, n);
            if (at < 0) {
                out = out.append(Texts.parse(rest));
                break;
            }
            if (at > 0) out = out.append(Texts.parse(rest.substring(0, at)));
            boolean isPrevious = at == p;
            out = out.append(isPrevious ? previous : next);
            rest = rest.substring(at + (isPrevious ? "%previous%".length() : "%next%".length()));
        }
        return out;
    }
}

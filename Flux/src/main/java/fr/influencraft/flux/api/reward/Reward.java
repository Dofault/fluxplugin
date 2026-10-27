package fr.influencraft.flux.api.reward;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;

/**
 * A reward handed to a player: what it is (label, icon), the inventory slots it needs once claimed and the console
 * commands that deliver it. Given with {@link Rewards#give}, kept by Flux until the player claims it.
 *
 * <pre>
 *   Rewards.give(player, Reward.of("votes", "&amp;fVote key").icon("TRIPWIRE_HOOK").slots(1)
 *           .commands("ecocrates give %player% key vote physical 1").key("vote:" + id));
 * </pre>
 *
 * @param source   plugin feature it comes from ({@code votes}, {@code referral}...): label in the menu, claim filter
 * @param key      unique per player, or null: a reward with a key already given is never given twice, so a plugin
 *                 can hand the same reward again after a crash without any risk of duplicate
 * @param label    coloured text shown in the menu
 * @param icon     item of the menu button (Flux material syntax: {@code TRIPWIRE_HOOK}, {@code itemsadder:ns:id},
 *                 {@code source:<id>:<arg>})
 * @param amount   amount shown on the icon
 * @param slots    free storage slots needed to claim it (0 for money, permissions...)
 * @param commands console commands run on claim; {@code %player%} (and {@code {joueur}}) = player name,
 *                 {@code %uuid%} = his UUID
 */
public record Reward(String source, String key, String label, String icon, int amount, int slots, List<String> commands) {

    public Reward {
        source = source == null || source.isBlank() ? "rewards" : source;
        label = label == null ? "" : label;
        icon = icon == null || icon.isBlank() ? "CHEST" : icon;
        amount = Math.max(1, amount);
        slots = Math.max(0, slots);
        commands = commands == null ? List.of() : List.copyOf(commands);
    }

    public static Reward of(String source, String label) {
        return new Reward(source, null, label, null, 1, 1, List.of());
    }

    /**
     * The common config shape of a reward, the same in every plugin of the suite:
     * <pre>
     *   label: "&amp;fCrate key"        # or name
     *   icon: TRIPWIRE_HOOK
     *   slots: 1                     # default 1 when there are commands
     *   commands:                    # or commandes
     *     - "ecocrates give %player% key tendance physical 1"
     * </pre>
     */
    public static Reward fromConfig(ConfigurationSection section, String source, String defaultLabel) {
        List<String> commands = new ArrayList<>(section.getStringList("commands"));
        if (commands.isEmpty()) commands.addAll(section.getStringList("commandes"));
        String label = section.getString("label", section.getString("name", defaultLabel));
        return new Reward(source, null, label, section.getString("icon", null), section.getInt("amount", 1),
                section.getInt("slots", commands.isEmpty() ? 0 : 1), commands);
    }

    public Reward key(String key) {
        return new Reward(source, key, label, icon, amount, slots, commands);
    }

    public Reward label(String label) {
        return new Reward(source, key, label, icon, amount, slots, commands);
    }

    public Reward icon(String icon) {
        return new Reward(source, key, label, icon, amount, slots, commands);
    }

    public Reward amount(int amount) {
        return new Reward(source, key, label, icon, amount, slots, commands);
    }

    public Reward slots(int slots) {
        return new Reward(source, key, label, icon, amount, slots, commands);
    }

    public Reward commands(List<String> commands) {
        return new Reward(source, key, label, icon, amount, slots, commands);
    }

    public Reward commands(String... commands) {
        return commands(List.of(commands));
    }
}

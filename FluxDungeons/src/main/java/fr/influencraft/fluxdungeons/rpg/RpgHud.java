package fr.influencraft.fluxdungeons.rpg;

import fr.influencraft.flux.api.FluxPlugin;
import fr.influencraft.flux.api.client.Clients;
import fr.influencraft.flux.api.client.ResourceOverlays;
import fr.influencraft.flux.api.text.Texts;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The rpg universe's hud. Java players: hearts, food and armour hidden by an overlay pack ({@link ResourceOverlays}
 * "rpg_hud"), a real life bar and mana bar drawn in their place with the numbers inside (font
 * {@code fluxdungeons:hud}, assets/gen_hud.py). Bedrock players (no pack): life and mana written in the action bar.
 */
final class RpgHud {

    static final String OVERLAY = "rpg_hud";
    private static final Key FONT = Key.key("fluxdungeons", "hud");
    /** In line with assets/gen_hud.py. */
    private static final int STEPS = 40, BAR = 81, GAP = 20, DIGIT = 6;

    /** Every sprite of the hearts, food and armour rows: made fully transparent by the overlay. */
    private static final List<String> HIDDEN = sprites();

    private final FluxPlugin plugin;

    RpgHud(FluxPlugin plugin) {
        this.plugin = plugin;
    }

    /** Declares the overlay that hides the vanilla rows (Flux serves it; off when its port is 0). */
    void register() {
        try {
            BufferedImage empty = new BufferedImage(9, 9, BufferedImage.TYPE_INT_ARGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(empty, "png", out);
            byte[] png = out.toByteArray();
            Map<String, byte[]> files = new LinkedHashMap<>();
            for (String s : HIDDEN) files.put("assets/minecraft/textures/gui/sprites/hud/" + s + ".png", png);
            if (!ResourceOverlays.register(plugin, OVERLAY, files)) {
                plugin.getLogger().warning("Rpg hud overlay not served (Flux resource-overlays.port): hearts and food stay visible");
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Rpg hud overlay not made: " + e);
        }
    }

    void show(Player p) {
        ResourceOverlays.show(p, OVERLAY);
    }

    void hide(Player p) {
        ResourceOverlays.hide(p, OVERLAY);
    }

    /** What the action bar shows this half second. */
    Component render(Player p, double hp, double maxHp, double mana, double maxMana) {
        if (Clients.bedrock(p) || !ResourceOverlays.available(OVERLAY)) return text(hp, maxHp, mana, maxMana);
        StringBuilder b = new StringBuilder();
        int[] cursor = {0};
        // life bar over the hearts row (its left end on the hotbar's left end)
        b.append((char) (0xE100 + step(hp, maxHp)));
        cursor[0] += BAR + 1;
        numbers(b, cursor, 0, hp, maxHp);
        moveTo(b, cursor, BAR + GAP);
        b.append((char) (0xE200 + step(mana, maxMana)));
        cursor[0] += BAR + 1;
        numbers(b, cursor, BAR + GAP, mana, maxMana);
        // the line is centred: as wide as the hotbar, both bars land on their rows
        moveTo(b, cursor, 2 * BAR + GAP);
        return Component.text(b.toString()).font(FONT).color(NamedTextColor.WHITE);
    }

    private static int step(double v, double max) {
        if (max <= 0) return 0;
        int s = (int) Math.ceil(Math.max(0, Math.min(1, v / max)) * STEPS);
        return v > 0 ? Math.max(1, s) : 0;
    }

    /** "current/max" centred inside the bar starting at {@code barX}. */
    private static void numbers(StringBuilder b, int[] cursor, int barX, double v, double max) {
        String t = Math.round(Math.max(0, v)) + "/" + Math.round(max);
        int width = t.length() * DIGIT - 1;
        moveTo(b, cursor, barX + Math.max(0, (BAR - width) / 2));
        for (char c : t.toCharArray()) b.append((char) (0xE300 + (c == '/' ? 10 : c - '0')));
        cursor[0] += t.length() * DIGIT;
    }

    /** Moves the pen to {@code x} with space glyphs (powers of two, back or forward). */
    private static void moveTo(StringBuilder b, int[] cursor, int x) {
        int d = x - cursor[0];
        int base = d < 0 ? 0xF001 : 0xF011;
        int n = Math.abs(d);
        for (int i = 7; i >= 0; i--) {
            while (n >= (1 << i)) {
                b.append((char) (base + i));
                n -= 1 << i;
            }
        }
        cursor[0] = x;
    }

    /** Bedrock (or no overlay): plain text. */
    private Component text(double hp, double maxHp, double mana, double maxMana) {
        return Texts.colors("&#FF5C5C❤ &f" + Math.round(hp) + "&7/" + Math.round(maxHp)
                + "   &#2BC4E8✦ &f" + Math.round(mana) + "&7/" + Math.round(maxMana));
    }

    private static List<String> sprites() {
        List<String> out = new java.util.ArrayList<>();
        for (String s : List.of("armor_empty", "armor_full", "armor_half", "food_empty", "food_empty_hunger", "food_full",
                "food_full_hunger", "food_half", "food_half_hunger")) out.add(s);
        for (String kind : List.of("", "absorbing_", "frozen_", "poisoned_", "withered_")) {
            for (String hc : List.of("", "hardcore_")) {
                for (String part : List.of("full", "half")) {
                    for (String blink : List.of("", "_blinking")) out.add("heart/" + kind + hc + part + blink);
                }
            }
        }
        for (String c : List.of("container", "container_blinking", "container_hardcore", "container_hardcore_blinking",
                "vehicle_container", "vehicle_full", "vehicle_half")) out.add("heart/" + c);
        return out;
    }
}

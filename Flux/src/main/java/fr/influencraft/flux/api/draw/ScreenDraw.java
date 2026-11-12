package fr.influencraft.flux.api.draw;

import fr.influencraft.flux.api.screen.Screens;
import fr.influencraft.flux.api.text.ItemSprites;
import fr.influencraft.flux.api.text.Texts;
import fr.influencraft.flux.api.text.Translations;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * TRIAL (2026-11-12): the draw as an HTML page of MC GUI ({@link Screens}), drawn over the world (overlay) with a
 * banner at the top of the screen: a reel of item cards that slows down onto the winner, then a glowing reveal.
 * A page seats the player (camera held) while it is open: it closes by itself after the animation. Players who
 * cannot be shown a page (Bedrock, shaders, no MC GUI) get the boss bar draw instead.
 */
final class ScreenDraw {

    private static final int CARDS = 34, WINNER_AT = 29, CARD = 112, GAP = 16;
    private static final double REEL_SECONDS = 4.6, PAGE_SECONDS = 7.6;
    private static int counter;

    private ScreenDraw() {
    }

    static void play(Plugin owner, List<Player> viewers, List<Draws.Choice> pool, Draws.Choice winner, String heading,
                     Runnable onDone) {
        Path folder = Screens.pagesFolder();
        List<Player> paged = new ArrayList<>(), others = new ArrayList<>();
        for (Player p : viewers) (folder != null && Screens.canShow(p) ? paged : others).add(p);
        if (paged.isEmpty()) {
            Draws.play(owner, others, pool, winner, heading, onDone);
            return;
        }
        String name = "flux/draw_" + (counter++ % 4);
        try {
            write(folder, name, pool, winner, heading);
        } catch (IOException e) {
            owner.getLogger().warning("Draw page not written: " + e.getMessage());
            Draws.play(owner, viewers, pool, winner, heading, onDone);
            return;
        }
        // the boss bar draw for the others, its end (with its own timing) is not waited for
        if (!others.isEmpty()) Draws.play(owner, others, pool, winner, heading, null);
        // MC GUI reloads a page when its file changes: give it a moment before opening
        Bukkit.getScheduler().runTaskLater(owner, () -> {
            for (Player p : paged) if (p.isOnline() && !Screens.open(p, name)) Draws.play(owner, List.of(p), pool, winner, heading, null);
            Bukkit.getScheduler().runTaskLater(owner, () -> {
                for (Player p : paged) if (p.isOnline() && name.equals(Screens.page(p))) Screens.close(p);
                if (onDone != null) onDone.run();
            }, Math.round(PAGE_SECONDS * 20));
        }, 15L);
    }

    // ---------------------------------------------------------------- the page

    private static void write(Path folder, String name, List<Draws.Choice> pool, Draws.Choice winner, String heading) throws IOException {
        Path page = folder.resolve(name + ".html");
        Path items = folder.resolve("flux/draw_items");
        Files.createDirectories(items);
        List<Draws.Choice> reel = new ArrayList<>();
        Draws.Choice last = null;
        for (int i = 0; i < CARDS; i++) {
            Draws.Choice c;
            if (i == WINNER_AT) c = winner;
            else {
                c = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
                for (int t = 0; t < 8 && pool.size() > 2 && (c.equals(last) || i == WINNER_AT - 1 && c.equals(winner)); t++) {
                    c = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
                }
            }
            reel.add(c);
            last = c;
        }
        StringBuilder cards = new StringBuilder();
        for (int i = 0; i < reel.size(); i++) {
            Draws.Choice c = reel.get(i);
            String img = icon(items, c);
            cards.append("<div class=\"card").append(i == WINNER_AT ? " win" : "").append("\">")
                    .append(img == null ? "<div class=\"q\">?</div>" : "<img src=\"draw_items/" + img + "\">")
                    .append("</div>");
        }
        // the winner's card ends in the middle of the 1280 px wide view
        int travel = WINNER_AT * (CARD + GAP) + CARD / 2 - 640;
        String winIcon = icon(items, winner);
        String html = TEMPLATE
                .replace("$TRAVEL", String.valueOf(travel))
                .replace("$REEL", String.valueOf(REEL_SECONDS))
                .replace("$AFTER", String.valueOf(REEL_SECONDS + 0.1))
                .replace("$HEADING", esc(Texts.plain(heading, null)).toUpperCase(Locale.ROOT))
                .replace("$CARDS", cards.toString())
                .replace("$WINIMG", winIcon == null ? "" : "<img src=\"draw_items/" + winIcon + "\">")
                .replace("$WINNER", esc(plain(winner.name())));
        Files.writeString(page, html, StandardCharsets.UTF_8);
    }

    /** Plain name in the server's language (a page is drawn once for everybody). */
    private static String plain(String name) {
        if (name != null && name.startsWith("<lang:") && name.endsWith(">")) {
            String own = Translations.server(name.substring(6, name.length() - 1));
            if (own != null) return own;
        }
        return Texts.plain(name == null ? "" : name, null);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ---------------------------------------------------------------- item pictures

    /** File name of the item's picture (its texture from the client jar, scaled 4x without blur), null without one. */
    private static String icon(Path items, Draws.Choice c) {
        if (c.icon() == null) return null;
        String texture = ItemSprites.texture(c.icon().getKey().getKey());
        if (texture == null) return null;
        String file = texture.replace('/', '_') + ".png";
        Path out = items.resolve(file);
        if (Files.exists(out)) return file;
        File jar = clientJar();
        if (jar == null) return null;
        try (ZipFile zip = new ZipFile(jar)) {
            ZipEntry e = zip.getEntry("assets/minecraft/textures/" + texture + ".png");
            if (e == null) return null;
            BufferedImage src;
            try (InputStream in = zip.getInputStream(e)) {
                src = ImageIO.read(in);
            }
            if (src == null) return null;
            int size = src.getWidth(); // animated textures are a column of frames: keep the first
            BufferedImage big = new BufferedImage(size * 4, size * 4, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < size * 4; y++) {
                for (int x = 0; x < size * 4; x++) big.setRGB(x, y, src.getRGB(x / 4, y / 4));
            }
            ImageIO.write(big, "png", out.toFile());
            return file;
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    /** A Minecraft client jar on the server (RPEPortals keeps one in its cache). */
    private static File clientJar() {
        File cache = new File(Bukkit.getWorldContainer(), "plugins/RPEPortals/cache");
        File[] jars = cache.listFiles((d, n) -> n.startsWith("client-") && n.endsWith(".jar"));
        if (jars == null || jars.length == 0) return null;
        java.util.Arrays.sort(jars);
        return jars[jars.length - 1];
    }

    // ---------------------------------------------------------------- template

    private static final String TEMPLATE = """
            <!doctype html>
            <html>
            <head>
            <meta charset="utf-8">
            <meta name="ui:overlay" content="rgba(0, 0, 0, 0)">
            <meta name="ui:height" content="720">
            <style>
            * { box-sizing: border-box; }
            html, body { margin: 0; height: 100%; overflow: hidden; }
            body { font-family: Inter, sans-serif; color: #fff; }
            .banner { position: absolute; left: 0; right: 0; top: 0; height: 300px; overflow: hidden;
              background: linear-gradient(180deg, rgba(8, 18, 34, .94) 0%, rgba(10, 28, 52, .88) 80%, rgba(10, 28, 52, 0) 100%);
              animation: drop .45s cubic-bezier(.2,.9,.3,1.2) both; }
            .glow { position: absolute; left: 0; right: 0; top: 0; height: 300px;
              background: radial-gradient(ellipse 40% 70% at 50% 55%, rgba(43, 196, 232, .35), transparent 70%); }
            .title { position: absolute; top: 18px; left: 0; right: 0; text-align: center; font-family: Minecraft; font-size: 22px;
              letter-spacing: 6px; color: #A6E4F5; text-shadow: 2px 2px 0 #06223a, 0 0 14px rgba(43,196,232,.8);
              animation: fade .6s ease-out .2s both; }
            .line { position: absolute; top: 52px; left: 50%; width: 520px; margin-left: -260px; height: 2px;
              background: linear-gradient(90deg, transparent, #2BC4E8, transparent); }
            .window { position: absolute; top: 70px; left: 0; right: 0; height: 140px; }
            .reel { position: absolute; top: 14px; left: 0; display: flex; gap: 16px; width: 6000px;
              animation: spin $REELs cubic-bezier(.06, .72, .13, 1) .3s both; }
            .card { width: 112px; height: 112px; flex: none; border-radius: 14px;
              background: linear-gradient(160deg, rgba(43,196,232,.22), rgba(12,30,56,.9));
              border: 2px solid rgba(166,228,245,.35); display: flex; align-items: center; justify-content: center;
              box-shadow: inset 0 0 18px rgba(0,0,0,.45); }
            .card img { width: 64px; height: 64px; }
            .q { font-family: Minecraft; font-size: 40px; color: #A6E4F5; }
            .frame { position: absolute; top: 4px; left: 50%; width: 136px; height: 136px; margin-left: -68px; border-radius: 18px;
              border: 3px solid #2BC4E8; box-shadow: 0 0 24px rgba(43,196,232,.9), inset 0 0 18px rgba(43,196,232,.5); }
            .shade { position: absolute; top: 0; bottom: 0; width: 360px; }
            .shade.l { left: 0; background: linear-gradient(90deg, rgba(8,18,34,1), rgba(8,18,34,0)); }
            .shade.r { right: 0; background: linear-gradient(270deg, rgba(8,18,34,1), rgba(8,18,34,0)); }
            .reveal { position: absolute; top: 60px; left: 0; right: 0; height: 240px; opacity: 0;
              animation: show .5s ease-out $AFTERs both; }
            .rays { position: absolute; top: 72px; left: 50%; width: 420px; height: 420px; margin: -210px 0 0 -210px; opacity: .55;
              background: conic-gradient(from 0deg, rgba(43,196,232,.0) 0deg, rgba(43,196,232,.55) 12deg, rgba(43,196,232,0) 24deg,
                rgba(43,196,232,0) 45deg, rgba(166,228,245,.45) 57deg, rgba(43,196,232,0) 69deg, rgba(43,196,232,0) 90deg,
                rgba(43,196,232,.55) 102deg, rgba(43,196,232,0) 114deg, rgba(43,196,232,0) 135deg, rgba(166,228,245,.45) 147deg,
                rgba(43,196,232,0) 159deg, rgba(43,196,232,0) 180deg, rgba(43,196,232,.55) 192deg, rgba(43,196,232,0) 204deg,
                rgba(43,196,232,0) 225deg, rgba(166,228,245,.45) 237deg, rgba(43,196,232,0) 249deg, rgba(43,196,232,0) 270deg,
                rgba(43,196,232,.55) 282deg, rgba(43,196,232,0) 294deg, rgba(43,196,232,0) 315deg, rgba(166,228,245,.45) 327deg,
                rgba(43,196,232,0) 339deg, rgba(43,196,232,0) 360deg);
              border-radius: 50%; animation: turn 6s linear infinite; }
            .big { position: absolute; top: 14px; left: 50%; width: 136px; height: 136px; margin-left: -68px; border-radius: 18px;
              background: linear-gradient(160deg, #2BC4E8, #0f3c66); border: 3px solid #fff;
              box-shadow: 0 0 40px rgba(43,196,232,1), 0 0 90px rgba(166,228,245,.6);
              display: flex; align-items: center; justify-content: center; animation: pop .5s cubic-bezier(.2,1.6,.4,1) $AFTERs both; }
            .big img { width: 96px; height: 96px; }
            .name { position: absolute; top: 166px; left: 0; right: 0; text-align: center; font-family: Minecraft; font-size: 30px;
              color: #fff; text-shadow: 3px 3px 0 #06223a, 0 0 18px rgba(43,196,232,.9); }
            .spark { position: absolute; width: 8px; height: 8px; background: #fff; border-radius: 2px; transform: rotate(45deg);
              box-shadow: 0 0 10px #A6E4F5; opacity: 0; animation: twinkle 1.4s ease-in-out infinite; }
            @keyframes drop { from { transform: translateY(-260px); } to { transform: translateY(0); } }
            @keyframes fade { from { opacity: 0; } to { opacity: 1; } }
            @keyframes spin { from { transform: translateX(0); } to { transform: translateX(-$TRAVELpx); } }
            @keyframes show { from { opacity: 0; } to { opacity: 1; } }
            @keyframes pop { from { transform: scale(.4); } to { transform: scale(1); } }
            @keyframes turn { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
            @keyframes twinkle { 0% { opacity: 0; transform: rotate(45deg) scale(.3); } 50% { opacity: 1; transform: rotate(45deg) scale(1.2); }
              100% { opacity: 0; transform: rotate(45deg) scale(.3); } }
            </style>
            </head>
            <body>
            <div class="banner">
              <div class="glow"></div>
              <div class="title">$HEADING</div>
              <div class="line"></div>
              <div class="window">
                <div class="reel">$CARDS</div>
                <div class="shade l"></div><div class="shade r"></div>
                <div class="frame"></div>
              </div>
              <div class="reveal">
                <div class="rays"></div>
                <div class="big">$WINIMG</div>
                <div class="name">$WINNER</div>
                <div class="spark" style="top:40px; left:520px; animation-delay:0s"></div>
                <div class="spark" style="top:110px; left:760px; animation-delay:.35s"></div>
                <div class="spark" style="top:30px; left:700px; animation-delay:.7s"></div>
                <div class="spark" style="top:130px; left:480px; animation-delay:1.05s"></div>
                <div class="spark" style="top:70px; left:820px; animation-delay:.5s"></div>
                <div class="spark" style="top:90px; left:440px; animation-delay:.9s"></div>
              </div>
            </div>
            </body>
            </html>
            """;
}
